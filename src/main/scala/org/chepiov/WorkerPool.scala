package org.chepiov

import cats.effect._
import cats.effect.concurrent.{MVar, Ref}
import cats.effect.syntax.bracket._
import cats.effect.syntax.concurrent._
import cats.implicits._

import scala.concurrent.duration._
import scala.util.Random

object WorkerPool extends IOApp {

  // To start, our requests can be modelled as simple functions.
  // You might want to replace this type with a class if you go for bonuses. Or not.
  type Worker[F[_], A, B] = A => F[B]

  // Sample stateful worker that keeps count of requests it has accepted
  def mkWorker[F[_]: Sync](id: Int)(implicit timer: Timer[F]): F[Worker[F, Int, Int]] =
    Ref[F].of(0).map { counter =>
      def simulateWork: F[Unit] =
        putStrLn(s"Starting by $id") >>
          Sync[F].delay(50 + Random.nextInt(450)).map(_.millis).flatMap(timer.sleep) >>
          putStrLn(s"Finishing by $id")

      def report: F[Unit] =
        counter.get.flatMap(i => putStrLn(s"Total processed by $id: $i"))

      x =>
        simulateWork >>
          counter.update(_ + 1) >>
          report >>
          Sync[F].pure(x + 1)
    }

  trait WorkerPool[F[_], A, B] {
    def exec(a: A): F[B]
    def removeAll: F[Unit]
    def add(worker: Worker[F, A, B]): F[Unit]
  }

  object WorkerPool {
    // Implement this constructor, and, correspondingly, the interface above.
    // You are free to use named or anonymous classes
    def of[F[_]: Concurrent, A, B](fs: List[Worker[F, A, B]]): F[WorkerPool[F, A, B]] =
      for {
        version <- Ref[F].of(1L)
        current <- MVar[F].empty[(Long, Worker[F, A, B])]
        pool = new WorkerPool[F, A, B] {

          def exec(a: A): F[B] =
            for {
              (workerVersion, worker) <- current.take
              currentVersion          <- version.get
              // in case of `removeAll` happened here worker will be removed eventually (e.g. next time)
              b <- if (currentVersion == workerVersion)
                    worker(a).guarantee(back(workerVersion, worker))
                  else exec(a)
            } yield b

          def removeAll: F[Unit] = version.update(_ + 1L)

          def add(worker: Worker[F, A, B]): F[Unit] =
            version.get >>= (v => current.put(v, worker).start.void)

          def back(workerVersion: Long, worker: Worker[F, A, B]): F[Unit] =
            current.put(workerVersion, worker).start.void
        }
        _ <- fs.traverse_(pool.add)
      } yield pool
  }

  // Sample test pool to play with in IOApp
  val testPool: IO[WorkerPool[IO, Int, Int]] =
    List
      .range(0, 10)
      .traverse(mkWorker[IO])
      .flatMap(WorkerPool.of[IO, Int, Int])

  def putStrLn[F[_]: Sync](s: String): F[Unit] = Sync[F].delay(println(s))

  override def run(args: List[String]): IO[ExitCode] =
    for {
      pool     <- testPool
      _        <- List.range(0, 100).parTraverse(pool.exec)
      //unitPool <- WorkerPool.of[IO, Unit, Unit](List(_ => IO.sleep(2.seconds)))
      //_        <- unitPool.exec(()).start
      //_        <- unitPool.removeAll
      //_        <- unitPool.exec(())
    } yield ExitCode.Success
}
