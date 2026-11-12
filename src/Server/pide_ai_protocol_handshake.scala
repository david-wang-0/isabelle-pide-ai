/*  Title:      PIDE_MCP/Server/pide_ai_protocol_handshake.scala
    Author:     Kevin Kappelmann

Backported from PIDE AI (upstream AI/src/Server/pide_ai_protocol_handshake.scala) onto Isabelle2025-2.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_AI_Protocol_Handshake {
  def timeout(options: Options): Time = options.seconds("pide_ai_protocol_handshake_timeout")

  val OK = "OK"
  def error_response(message: String): JSON.T = JSON_Object("error" -> message)

  def error_message(reply: String): String =
    reply match {
      case JSON.Format(JSON.Object(obj)) => JSON.string(obj, "error").getOrElse(reply)
      case _ => reply
    }

  def parse_header(
    protocols: Map[String, PIDE_AI_Protocol],
    line: String
  ): (PIDE_AI_Protocol, PIDE_AI_Client_Context) = {
    val (name, rest) = line.span(_ != ' ')
    val protocol = protocols.getOrElse(name, error(s"Bad protocol: ${quote(name)}"))
    val cwd = Library.try_unprefix(" ", rest).map(s => Path.explode(File.standard_path(s)))
    (protocol, PIDE_AI_Client_Context(cwd))
  }

  def server_handshake(
    protocols: Map[String, PIDE_AI_Protocol],
    connection: Line_Connection
  ): Option[(PIDE_AI_Protocol, PIDE_AI_Client_Context)] =
    connection.read_line().flatMap { line =>
      val header = try { Right(parse_header(protocols, line)) }
        catch { case ERROR(message) => Left(message) }
      connection.write_line(header.fold(message => JSON.Format(error_response(message)), _ => OK))
      header.toOption
    }

  def header_line(protocol: PIDE_AI_Protocol, context: PIDE_AI_Client_Context): String =
    context.cwd.fold(protocol.id)(dir => protocol.id + " " + File.platform_path(dir))

  def client_handshake(
    protocol: PIDE_AI_Protocol,
    context: PIDE_AI_Client_Context,
    connection: Line_Connection,
    server: String
  ): Unit = {
    connection.write_line(header_line(protocol, context))
    connection.read_line() match {
      case Some(OK) =>
      case Some(line) => error(s"Server $server: ${error_message(line)}")
      case None => error(s"Server $server closed the connection")
    }
  }
}
