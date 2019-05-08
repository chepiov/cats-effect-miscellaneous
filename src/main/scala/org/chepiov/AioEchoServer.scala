package org.chepiov

import java.net.InetSocketAddress
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}
import java.util.concurrent.{ExecutorService, Executors}

import cats.effect._
import cats.effect.concurrent.MVar
import cats.effect.syntax.concurrent._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

class AioEchoServer extends IOApp {
  import AioEchoServer._

  def serve[F[_]: Concurrent](serverSocketChannel: AsynchronousServerSocketChannel, stopFlag: MVar[F, Unit]): F[Unit] = {

    def close(socket: AsynchronousSocketChannel): F[Unit] =
      Sync[F].delay(socket.close()).handleErrorWith(e => putStrLine(s"Error: $e") *> Sync[F].unit)

    for {
      _ <- putStrLine("Before accept")
      _ = serverSocketChannel.accept("", new CompletionHandler[AsynchronousSocketChannel, String] {
        override def completed(result: AsynchronousSocketChannel, attachment: String): Unit = ???

        override def failed(exc: Throwable, attachment: String): Unit = ???
      })
    } yield ()

    ???
  }

  import cats.effect.ContextShift
  import cats.implicits._
  def server[F[_]: ContextShift: Concurrent](serverSocketChannel: AsynchronousServerSocketChannel): F[Unit] =
    for {
      stopFlag    <- MVar[F].empty[Unit]
      _ <- putStrLine("Before start")
      serverFiber <- serve(serverSocketChannel, stopFlag).start
      _           <- stopFlag.read
      _ <- putStrLine("After start")
      _           <- serverFiber.cancel.start
    } yield ()

  override def run(args: List[String]): IO[ExitCode] = {

    def close[F[_]: Sync](serverSocketChannel: AsynchronousServerSocketChannel): F[Unit] =
      Sync[F]
        .delay(serverSocketChannel.close())
        .handleErrorWith(e => putStrLine(s"Error: $e") *> Sync[F].unit)

    IO(
      AsynchronousServerSocketChannel
        .open(AsynchronousChannelGroup.withThreadPool(nioPollingPool))
        .bind(new InetSocketAddress("localhost", 5432))
    ).bracket { serverSocketChannel =>
      server[IO](serverSocketChannel) *> putStrLine[IO]("Finishing").as(ExitCode.Success)
    } { serverSocketChannel =>
      close[IO](serverSocketChannel) *> putStrLine[IO]("Closed")
    }
  }
}

object AioEchoServer {

  def putStrLine[F[_]: Sync](line: String): F[Unit] =
    Sync[F].delay(println(s"[${Thread.currentThread().getThreadGroup.getName}] $line"))

  private val nioPollingTG: ThreadGroup = {
    val tg = new ThreadGroup("nio-polling")
    tg.setDaemon(true)
    tg.setMaxPriority(Thread.MAX_PRIORITY)
    tg
  }

  private val cpuBoundTG: ThreadGroup = {
    val tg = new ThreadGroup("cpu-bound")
    tg.setDaemon(true)
    tg
  }

  private val blockingIoTG: ThreadGroup = {
    val tg = new ThreadGroup("blocking-io")
    tg.setDaemon(true)
    tg
  }

  val nioPollingPool: ExecutorService =
    Executors
      .newSingleThreadExecutor((r: Runnable) => new Thread(nioPollingTG, r))

  val nioPollingEC: ExecutionContext =
    ExecutionContext.fromExecutor(nioPollingPool)

  val cpuBoundEC: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(
      Executors
        .newFixedThreadPool(Runtime.getRuntime.availableProcessors(), (r: Runnable) => new Thread(cpuBoundTG, r))
    )

  val blockingIoEC: ExecutorService =
    Executors
      .newCachedThreadPool((r: Runnable) => new Thread(blockingIoTG, r))
}
