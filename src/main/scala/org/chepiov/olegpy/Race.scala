package org.chepiov.olegpy

import cats.data._
import cats.effect._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Functor, NonEmptyTraverse}

import scala.concurrent.duration._
import scala.util.Random

object Race extends IOApp {

  case class Data(source: String, body: String)

  def provider(name: String)(implicit timer: Timer[IO]): IO[Data] = {
    val proc = for {
      dur <- IO { Random.nextInt(500) }
      _   <- IO.sleep { (100 + dur).millis }
      _   <- IO { if (Random.nextBoolean()) throw new Exception(s"Error in $name") }
      txt <- IO { Random.alphanumeric.take(16).mkString }
    } yield Data(name, txt)

    proc.guaranteeCase {
      case ExitCase.Completed => IO { println(s"$name request finished") }
      case ExitCase.Canceled  => IO { println(s"$name request canceled") }
      case ExitCase.Error(_)  => IO { println(s"$name errored") }
    }
  }

  // Use this class for reporting all failures.
  case class CompositeException(ex: NonEmptyList[Throwable]) extends Exception("All race candidates have failed") {
    def compose(e: CompositeException): CompositeException = CompositeException(ex concatNel e.ex)
  }

  case object CompositeException {
    def apply(ex: Throwable): CompositeException = CompositeException(NonEmptyList.one(ex))
  }

  // Implement this function:
  def raceToSuccess[F[_]: Concurrent, A, G[_]: NonEmptyTraverse](ios: G[F[A]]): F[A] =
    NonEmptyTraverse[G]
      .reduceLeft[F[A]](ios) {
        case (a, b) =>
          Concurrent[F].racePair(a.attempt, b.attempt) >>= {
            case success((v, fib)) => cancelAndFinish(v, fib)
            case failure((fib, e)) => composeAndContinue(fib, e)
          }
      }

  type Attempt[F[_], A] = Either[
    (Either[Throwable, A], Fiber[F, Either[Throwable, A]]),
    (Fiber[F, Either[Throwable, A]], Either[Throwable, A])
  ]
  type Success[F[_], A] = (A, Fiber[F, Either[Throwable, A]])
  type Failure[F[_], A] = (Fiber[F, Either[Throwable, A]], CompositeException)

  object success {
    def unapply[F[_], A](attempt: Attempt[F, A]): Option[Success[F, A]] =
      attempt.leftMap(_.swap).merge match {
        case (fib, Right(v)) => (v, fib).some
        case _               => none
      }
  }

  object failure {
    def unapply[F[_], A](attempt: Attempt[F, A]): Option[Failure[F, A]] =
      attempt.leftMap(_.swap).merge match {
        case (fib, Left(e)) => (fib, CompositeException(e)).some
        case _              => none
      }
  }

  def cancelAndFinish[F[_]: Functor, A](v: A, fib: Fiber[F, Either[Throwable, A]]): F[A] =
    fib.cancel.as(v)

  def composeAndContinue[F[_]: Sync, A](fib: Fiber[F, Either[Throwable, A]], ea: CompositeException): F[A] =
    fib.join >>= {
      case Left(eb: CompositeException) => Sync[F].raiseError[A](ea.compose(eb))
      case Left(eb)                     => Sync[F].raiseError[A](ea.compose(CompositeException(eb)))
      case Right(v)                     => v.pure[F]
    }

  // In your IOApp, you can use the following sample method list
  val methods: NonEmptyList[IO[Data]] = NonEmptyList
    .of(
      "memcached",
      "redis",
      "postgres",
      "mongodb",
      "hdd",
      "aws"
    )
    .map(provider)

  override def run(args: List[String]): IO[ExitCode] =
    raceToSuccess(methods)
      .flatMap(r => IO.delay(println(s"\tRace result: $r")))
      .recoverWith {
        case e: CompositeException =>
          IO.delay(println(s"\tRace failed: ${e.getMessage}"))
      }
      .replicateA(5)
      .as(ExitCode.Success)
}
