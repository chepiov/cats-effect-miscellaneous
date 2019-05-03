package org.chepiov

import cats.effect.Sync
import java.net.Socket

object EchoServer {
  def echoProtocol[F[_]: Sync](clientSocket: Socket): F[Unit] = ???
}
