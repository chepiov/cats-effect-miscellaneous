package org.chepiov.tutorial

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, PrintWriter}
import java.net.{InetSocketAddress, Socket}
import java.nio.channels.spi.SelectorProvider
import java.nio.channels.{SelectionKey, Selector, ServerSocketChannel}
import java.util.{Set => JSet}

import cats.effect.ExitCase.{Canceled, Completed, Error}
import cats.effect._
import cats.effect.concurrent.{MVar, Ref}
import cats.effect.syntax.bracket._
import cats.effect.syntax.concurrent._
import cats.instances.list._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._

import scala.collection.JavaConverters._
import scala.util.Try

object NioEchoServer extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {

    def close[F[_]: Sync](serverSocketChannel: ServerSocketChannel): F[Unit] =
      Sync[F]
        .delay(serverSocketChannel.close())
        .handleErrorWith(e => putStrLine(s"Error: $e") *> Sync[F].unit)

    IO {
      val channel = ServerSocketChannel.open()
      channel.configureBlocking(false)
      channel.bind(new InetSocketAddress("localhost", 5432))
    }.bracket { serverSocketChannel =>
      server[IO](serverSocketChannel) >>= (code => putStrLine[IO]("Finishing").as(code))
    } { serverSocketChannel =>
      close[IO](serverSocketChannel) *> putStrLine[IO]("Closed")
    }
  }

  def server[F[_]: Concurrent: ContextShift](serverSocketChannel: ServerSocketChannel): F[ExitCode] = {
    for {
      stopFlag    <- MVar[F].empty[Unit]
      _           <- putStrLine("Before start")
      serverFiber <- serve(serverSocketChannel, stopFlag).start
      _           <- stopFlag.read
      _           <- serverFiber.cancel.start
    } yield ExitCode.Success
  }

  def serve[F[_]: Concurrent: ContextShift](
    serverSocketChannel: ServerSocketChannel,
    stopFlag: MVar[F, Unit]
  ): F[Unit] = {

    def close(socket: Socket, selector: Selector): F[Unit] =
      Sync[F].delay {
        socket.close()
        selector.close()
      }.handleErrorWith(_ => Sync[F].unit)

    def handle(readyKeys: ReadyKeys[F], selector: Selector): F[Unit] =
      for {
        keys <- readyKeys.keys
        _ <- keys.traverse_ { key =>
          for {
            socket <- putStrLine("Accept") >> Sync[F]
              .delay(serverSocketChannel.accept().socket())
              .bracketCase { socket =>
                echoProtocol(socket, stopFlag)
                  .guarantee(close(socket, selector))
                  .start >> Sync[F].delay(readyKeys.removeKey(key)) >> Sync[F].pure(socket)
              } { (socket, exit) =>
                exit match {
                  case Completed           => Sync[F].unit
                  case Error(_) | Canceled => close(socket, selector)
                }
              }
            _ <- (stopFlag.read >> close(socket, selector)).start
          } yield ()
        }
      } yield ()

    def loop(readyKeys: ReadyKeys[F], acceptSelector: Selector): F[Unit] =
      for {
        cnt <- Sync[F].delay(acceptSelector.select())
        _   <- putStrLine(s"Selected channels count: $cnt")
        _   <- readyKeys.set(acceptSelector.selectedKeys())
        _   <- handle(readyKeys, acceptSelector)
        _   <- loop(readyKeys, acceptSelector)
      } yield ()

    for {
      _ <- putStrLine(s"Before accept")
      acceptSelector <- Sync[F].delay {
        val selector = SelectorProvider.provider().openSelector()
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT)
        selector
      }
      keys <- Ref.of[F, JSet[SelectionKey]](Set[SelectionKey]().asJava)
      _    <- loop(ReadyKeys(keys), acceptSelector)
    } yield ()
  }

  def echoProtocol[F[_]: Async](clientSocket: Socket, stopFlag: MVar[F, Unit]): F[Unit] = {
    val clientsExecutionContext = scala.concurrent.ExecutionContext.global
    def loop(reader: BufferedReader, writer: BufferedWriter, stopFlag: MVar[F, Unit]): F[Unit] =
      for {
        _ <- putStrLine("echo loop")
        lineE <- Async[F].async { (cb: Either[Throwable, Either[Throwable, String]] => Unit) =>
                  //noinspection ConvertExpressionToSAM
                  clientsExecutionContext.execute(new Runnable {
                    override def run(): Unit = {
                      val result: Either[Throwable, String] = Try(reader.readLine()).toEither
                      cb(Right(result))
                    }
                  })
                }
        _ <- lineE match {
              case Right(line) =>
                line match {
                  case "STOP" =>
                    stopFlag.put(()) // Stopping server! Also put(()) returns F[Unit] which is handy as we are done
                  case "" => Sync[F].unit // Empty line, we are done
                  case _ =>
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

  case class ReadyKeys[F[_]: Sync](private val ref: Ref[F, JSet[SelectionKey]]) {
    def set(keys: JSet[SelectionKey]): F[Unit] = ref.set(keys)
    def removeKey(key: SelectionKey): F[Unit] = ref.update { keys =>
      keys.remove(key); keys
    }
    def keys: F[List[SelectionKey]] = ref.get.map(_.asScala.toList)
  }

  def putStrLine[F[_]: Sync](line: String): F[Unit] =
    Sync[F].delay(println(s"[${Thread.currentThread().getThreadGroup.getName}] $line"))
}
