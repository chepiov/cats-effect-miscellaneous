package org.chepiov

import java.io.{File, _}
import java.nio.file.Files

import cats.effect._
import cats.effect.concurrent.MVar
import cats.implicits._

import scala.io.StdIn

object CopyFile extends IOApp {

  final class MLock[F[_]](lock: MVar[F, Unit])(implicit F: Bracket[F, Throwable]) {
    def acquire: F[Unit]              = lock.put(())
    def release: F[Unit]              = lock.take
    def withPermit[A](fa: F[A]): F[A] = F.bracket(acquire)(_ => fa)(_ => release)
  }

  case class Invalid(msg: String) extends Throwable

  def inputStream[F[_]: Sync](file: File, guard: MLock[F]): Resource[F, FileInputStream] =
    Resource.make {
      for {
        canRead <- Sync[F].delay(file.canRead)
        r <- if (canRead) Sync[F].delay(new FileInputStream(file))
            else Sync[F].raiseError[FileInputStream](new IOException("Can't read file"))
      } yield r
    } { inStream =>
      guard.withPermit {
        Sync[F]
          .delay(inStream.close())
          .handleErrorWith(e => Sync[F].delay(println(s"Error during closing input stream: $e")))
      }
    }

  def outputStream[F[_]: Sync](file: File, guard: MLock[F]): Resource[F, FileOutputStream] =
    Resource.make {
      for {
        canWrite <- Sync[F].delay(file.canWrite || !file.exists())
        r <- if (canWrite) Sync[F].delay(new FileOutputStream(file))
            else Sync[F].raiseError[FileOutputStream](new IOException("Can't write to file"))
      } yield r
    } { outStream =>
      guard.withPermit {
        Sync[F]
          .delay(outStream.close())
          .handleErrorWith(e => Sync[F].delay(println(s"Error during closing output stream: $e")))
      }
    }

  def inputOutputStream[F[_]: Sync](
      in: File,
      out: File,
      guard: MLock[F]
  ): Resource[F, (FileInputStream, FileOutputStream)] =
    for {
      inStream  <- inputStream(in, guard)
      outStream <- outputStream(out, guard)
    } yield (inStream, outStream)

  def transmit[F[_]: Sync](origin: InputStream, destination: OutputStream, bufferSize: Int): F[Long] = {
    def go(buffer: Array[Byte], acc: Long): F[Long] =
      for {
        amount <- Sync[F].delay(origin.read(buffer, 0, buffer.length))
        count <- if (amount > -1)
                  Sync[F].delay(destination.write(buffer, 0, amount)) >> go(
                    buffer,
                    acc + amount
                  )
                else Sync[F].pure(acc)
      } yield count
    go(new Array[Byte](1024 * bufferSize), 0L)
  }

  def transfer[F[_]: Sync](origin: InputStream, destination: OutputStream): F[Long] =
    for {
      total <- transmit(origin, destination, 10)
    } yield total

  def copy[F[_]: Concurrent](origin: File, destination: File): F[Long] =
    for {
      _     <- check(origin, destination)
      guard <- MVar[F].empty[Unit].map(lock => new MLock(lock))
      count <- inputOutputStream(origin, destination, guard).use {
                case (in, out) =>
                  guard.withPermit(transfer(in, out))
              }
    } yield count

  def check[F[_]: Sync](orig: File, dest: File): F[Unit] = {

    def checkOrigin: F[Unit] =
      for {
        checked <- Sync[F].delay(orig.isFile && orig.canRead)
        r       <- if (checked) Sync[F].unit else Sync[F].raiseError[Unit](Invalid("Can't read origin file"))
      } yield r

    def checkDestination: F[Unit] = {
      def checkFile: F[Unit] =
        for {
          isFile <- Sync[F].delay(dest.isFile)
          r      <- if (isFile) Sync[F].unit else Sync[F].raiseError[Unit](Invalid("Destination should be a file"))
        } yield r

      def checkDiff: F[Unit] =
        for {
          isSame <- Sync[F].delay(Files.isSameFile(orig.toPath, dest.toPath))
          r <- if (isSame) Sync[F].raiseError[Unit](Invalid("Origin and destination files must be different"))
              else Sync[F].unit
        } yield r

      def needOverwrite: F[Boolean] =
        for {
          exists <- Sync[F].delay(dest.exists())
          _      <- if (exists) checkFile else Sync[F].unit
          _      <- if (exists) checkDiff else Sync[F].unit
        } yield exists

      def confirm: F[Unit] =
        for {
          answer <- Sync[F].delay(StdIn.readLine("Overwrite destination (yes/no)? "))
          r <- answer.toLowerCase match {
                case "yes" => Sync[F].unit
                case "no"  => Sync[F].raiseError[Unit](Invalid("Destination already exists"))
                case _     => Sync[F].delay(println("Say 'yes' or 'no'")) >> confirm
              }
        } yield r

      for {
        need <- needOverwrite
        r    <- if (need) confirm else Sync[F].unit
      } yield r
    }

    for {
      _ <- checkOrigin
      r <- checkDestination
    } yield r
  }

  def copyFile[F[_]: Concurrent]: F[Long] =
    for {
      origPath <- Sync[F].delay(StdIn.readLine("Origin file: "))
      destPath <- Sync[F].delay(StdIn.readLine("Destination file: "))
      orig     = new File(origPath)
      dest     = new File(destPath)
      count    <- copy[F](orig, dest)
      _        <- Sync[F].delay(println(s"$count bytes copied from ${orig.getPath} to ${dest.getPath}"))
    } yield count

  def copyDir[F[_]: Concurrent]: F[Long] = {

    def checkOrigin(file: File): F[Unit] =
      for {
        isDir <- Sync[F].delay(file.exists() && file.isDirectory)
        r     <- if (isDir) Sync[F].unit else Sync[F].raiseError[Unit](Invalid("Origin should be a directory"))
      } yield r

    def checkDestination(file: File): F[Unit] =
      for {
        exists <- Sync[F].delay(file.exists())
        r      <- if (exists) Sync[F].raiseError[Unit](Invalid("Destination already exists")) else Sync[F].unit
      } yield r

    def copyFiles(originDir: File, destDir: File, acc: Long): F[Long] =
      for {
        files <- Sync[F].delay(
                  originDir
                    .listFiles(new FileFilter {
                      def accept(file: File): Boolean = file.isFile && file.canRead
                    })
                    .toList
                )
        _ <- Sync[F].delay(Files.createDirectory(destDir.toPath))
        total <- files.traverse { origin =>
                  val dest = new File(destDir, origin.getName)
                  copy(origin, dest)
                }
        dirs <- Sync[F].delay(
                 originDir
                   .listFiles(new FileFilter {
                     def accept(file: File): Boolean = file.isDirectory && file.canRead
                   })
                   .toList
               )
        r <- if (dirs.isEmpty) Sync[F].pure(acc + total.sum)
            else
              dirs.traverse { nextOriginDir =>
                val nextDestDir = new File(destDir, nextOriginDir.getName)
                copyFiles(nextOriginDir, nextDestDir, acc + total.sum)
              }.map(_.sum)
      } yield r

    for {
      origPath <- Sync[F].delay(StdIn.readLine("Origin path: "))
      destPath <- Sync[F].delay(StdIn.readLine("Destination path: "))
      orig     = new File(origPath)
      dest     = new File(destPath)
      _        <- checkOrigin(orig)
      _        <- checkDestination(dest)
      count    <- copyFiles(orig, dest, 0L)
      _        <- Sync[F].delay(println(s"$count bytes copied from ${orig.getPath} to ${dest.getPath}"))
    } yield count
  }

  def run(args: List[String]): IO[ExitCode] = {
    def choose: IO[Boolean] =
      for {
        answer <- IO(StdIn.readLine("What operation (1/2)?: "))
        r <- answer match {
              case "1" => IO.pure(true)
              case "2" => IO.pure(false)
              case _   => IO(println("Say '1' or '2'")) >> choose
            }
      } yield r

    val program = for {
      _     <- IO(println("1. Copy files"))
      _     <- IO(println("2. Copy dirs"))
      files <- choose
      count <- if (files) copyFile[IO] else copyDir[IO]
    } yield count

    program.handleErrorWith {
      case Invalid(msg) => IO(println(msg))
      case e            => IO(e.printStackTrace())
    }.map(_ => ExitCode.Success)
  }
}
