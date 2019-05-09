package org.chepiov.tutorial

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, PrintWriter}
import java.net.{InetSocketAddress, Socket}
import java.nio.channels.{SelectionKey, Selector, ServerSocketChannel}
import java.util.concurrent.Executors
import java.util.{Set => JSet}

import cats.effect.ExitCase.{Canceled, Completed, Error}
import cats.effect._
import cats.effect.concurrent.{MVar, Ref}
import cats.effect.syntax.bracket._
import cats.effect.syntax.concurrent._
import cats.instances.list._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

object NioEchoServer extends IOApp {

  def echoProtocol[F[_]: Concurrent](
      clientSocket: Socket,
      stopFlag: MVar[F, Unit]
  )(cs: ContextShift[F]): F[Unit] = {

    def loop(reader: BufferedReader, writer: BufferedWriter, stopFlag: MVar[F, Unit]): F[Unit] =
      for {
        _     <- cs.shift
        lineE <- Sync[F].delay(reader.readLine()).attempt
        _ <- lineE match {
              case Right(line) =>
                line match {
                  case "STOP" =>
                    stopFlag.put(()) // Stopping server! Also put(()) returns F[Unit] which is handy as we are done
                  case "" => Sync[F].unit // Empty line, we are done
                  case _ =>
                    putStrLn(s"Received line: $line") >>
                      Sync[F].delay { writer.write(line); writer.newLine(); writer.flush() } >> loop(
                      reader,
                      writer,
                      stopFlag
                    )
                }
              case Left(e) =>
                for { // readLine() failed, stopFlag will tell us whether this is a graceful shutdown
                  isEmpty <- stopFlag.isEmpty
                  _ <- if (!isEmpty) Sync[F].unit // stopFlag is set, cool, we are done
                      else Sync[F].raiseError[Unit](e) // stopFlag not set, must raise error
                } yield ()
            }
      } yield ()

    def reader(clientSocket: Socket): Resource[F, BufferedReader] =
      Resource.make {
        Sync[F].delay(new BufferedReader(new InputStreamReader(clientSocket.getInputStream)))
      } { reader =>
        Sync[F].delay(reader.close()).handleErrorWith(_ => Sync[F].unit)
      }

    def writer(clientSocket: Socket): Resource[F, BufferedWriter] =
      Resource.make {
        Sync[F].delay(new BufferedWriter(new PrintWriter(clientSocket.getOutputStream)))
      } { writer =>
        Sync[F].delay(writer.close()).handleErrorWith(_ => Sync[F].unit)
      }

    def readerWriter(clientSocket: Socket): Resource[F, (BufferedReader, BufferedWriter)] =
      for {
        reader <- reader(clientSocket)
        writer <- writer(clientSocket)
      } yield (reader, writer)

    readerWriter(clientSocket).use {
      case (reader, writer) =>
        loop(reader, writer, stopFlag) // Let's get to work
    }
  }

  def serve[F[_]: Concurrent](
      ssc: ServerSocketChannel,
      selector: Selector,
      stopFlag: MVar[F, Unit]
  )(pollingCs: ContextShift[F], cpuBoundCs: ContextShift[F]): F[Unit] = {

    def close(socket: Socket): F[Unit] =
      Sync[F].delay(socket.close()).handleErrorWith(putStrErr[F]) >> putStrLn("Client socket closed")

    case class ReadyKeys(ref: Ref[F, JSet[SelectionKey]]) {

      def set(keys: JSet[SelectionKey]): F[Unit] = ref.set(keys)

      def get: F[List[SelectionKey]] = ref.get.map(_.asScala.toList)

      def removeKey(key: SelectionKey): F[Unit] =
        ref.update { keys =>
          keys.remove(key)
          keys
        }
    }

    def delegate(readyKeys: ReadyKeys, key: SelectionKey): F[Socket] =
      Sync[F]
        .delay(ssc.accept().socket())
        .bracketCase { s =>
          echoProtocol(s, stopFlag)(cpuBoundCs)
            .guarantee(close(s))
            .start
            .as(s)
        } { (s, exit) =>
          (exit match {
            case Completed => Sync[F].unit
            case Error(e)  => putStrLn("Error during delegating") >> putStrErr(e) >> close(s)
            case Canceled  => close(s)
          }) >> readyKeys.removeKey(key)
        }

    def handle(readyKeys: ReadyKeys): F[Unit] =
      for {
        keys <- readyKeys.get
        _ <- keys.traverse_ { key =>
              for {
                _      <- putStrLn("Accepting connection")
                socket <- delegate(readyKeys, key)
                _      = stopFlag.read >> close(socket).start
              } yield ()
            }

      } yield ()

    def loop(readyKeys: ReadyKeys): F[Unit] =
      for {
        _ <- pollingCs.shift
        _ <- putStrLn("Starting serving")
        _ <- Sync[F].delay(selector.select())
        _ <- readyKeys.set(selector.selectedKeys())
        _ <- handle(readyKeys).handleErrorWith(putStrErr[F])
        _ <- putStrLn("Ending serving")
        _ <- loop(readyKeys)

      } yield ()

    for {
      keys      <- Ref[F].of(Set[SelectionKey]().asJava)
      readyKeys = ReadyKeys(keys)
      _         <- loop(readyKeys)
    } yield ()
  }

  def server[F[_]: Concurrent](
      ssc: ServerSocketChannel
  )(pollingCs: ContextShift[F], cpuBoundCs: ContextShift[F]): F[ExitCode] = {

    def close(selector: Selector): F[Unit] =
      Sync[F].delay(selector.close()) >> putStrLn("Selector closed")

    for {
      _        <- pollingCs.shift
      _        <- putStrLn("Starting polling")
      selector <- Sync[F].delay(Selector.open())
      _        <- Sync[F].delay(ssc.register(selector, SelectionKey.OP_ACCEPT))
      _        <- putStrLn("Selector created and registered")
      stopFlag <- MVar[F].empty[Unit]
      _        <- serve(ssc, selector, stopFlag)(pollingCs, cpuBoundCs).start
      _        <- stopFlag.read
      _        <- close(selector)
      _        <- putStrLn("Polling stopped")
    } yield ExitCode.Success
  }

  val pollingCs: ContextShift[IO] = IO.contextShift {
    val executor = Executors.newSingleThreadExecutor((r: Runnable) => {
      val tg     = new ThreadGroup("polling")
      val thread = new Thread(tg, r)
      thread.setPriority(Thread.MAX_PRIORITY)
      thread
    })
    ExecutionContext.fromExecutor(executor)
  }

  val cpuBoundCs: ContextShift[IO] = IO.contextShift {
    val executor = Executors.newFixedThreadPool(
      Runtime.getRuntime.availableProcessors(),
      (r: Runnable) => {
        new Thread(new ThreadGroup("cpu-bound"), r)
      }
    )
    ExecutionContext.fromExecutor(executor)
  }

  def putStrLn[F[_]: Sync](s: String): F[Unit] =
    Sync[F].delay(println(s"[${Thread.currentThread().getThreadGroup.getName}] $s"))

  def putStrErr[F[_]: Sync](e: Throwable): F[Unit] =
    Sync[F].delay(e.printStackTrace())

  override def run(args: List[String]): IO[ExitCode] = {

    def serverSocket[F[_]: Sync]: Resource[F, ServerSocketChannel] =
      Resource {
        Sync[F].delay {
          val ssc = ServerSocketChannel.open()
          val ss  = ssc.socket()
          ss.bind(new InetSocketAddress("localhost", 5432))
          ssc.configureBlocking(false)
          (ssc, Sync[F].delay {
            ss.close()
          } >> putStrLn("Server closed"))
        }
      }

    serverSocket[IO].use { ssc =>
      for {
        _    <- putStrLn[IO]("Starting server")
        code <- server[IO](ssc)(pollingCs, cpuBoundCs)
        _    <- putStrLn[IO](s"Finished. Result: $code")
      } yield code
    }
  }
}
