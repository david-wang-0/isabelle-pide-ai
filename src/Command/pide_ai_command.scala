/*  Title:      PIDE_MCP/Command/pide_ai_command.scala
    Author:     Kevin Kappelmann

Command connections: one request line with the tool call params of the Model Context Protocol
{"name": TOOL, "arguments": {...}, "_meta": {"progressToken": TOKEN}}, answered by lines
{"progress": ...} if requested, then {"result": ...} or {"error": ...}.

Backport of PIDE AI's AI/src/Command/pide_ai_command.scala, pide_ai_command_client.scala
and Server/pide_ai_tool_call.scala onto Isabelle2025-2.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_AI_Command {
  /* requests */

  def tool_call_json(call: PIDE_MCP_Protocol.Tool_Call): JSON.Object.T =
    JSON_Object("name" -> call.name, "arguments" -> call.args) ++
      JSON.optional("_meta" ->
        call.progress_token.map(token => JSON_Object("progressToken" -> token)))

  def read_tool_call(params: JSON.Object.T): PIDE_MCP_Protocol.Tool_Call = {
    val meta = PIDE_MCP_JSON.obj_default(params, "_meta", JSON.Object.empty)
    PIDE_MCP_Protocol.Tool_Call(
      PIDE_MCP_JSON.string(params, "name"),
      PIDE_MCP_JSON.obj_default(params, "arguments", JSON.Object.empty),
      PIDE_MCP_JSON.opt_value(meta, "progressToken",
        PIDE_MCP_Protocol.JSON_RPC.Id.unapply, Some("_meta")))
  }

  def parse_tool_call(line: String): PIDE_MCP_Protocol.Tool_Call =
    JSON.parse(line, strict = false) match {
      case JSON.Object(params) => read_tool_call(params)
      case _ => error("Bad request: expected JSON object")
    }


  /* responses */

  def progress_response(message: JSON.T): JSON.Object.T = JSON_Object("progress" -> message)

  def result_response(result: PIDE_MCP_Tool_Result): JSON.Object.T =
    result match {
      case Result.Res(res) => JSON_Object("result" -> res)
      case Result.Error(err) => JSON_Object("error" -> err)
    }

  def text(json: JSON.T): String =
    json match {
      case text: String => text
      case _ => JSON.Format(json)
    }


  /* tool calls */

  def run_tool(
    server: PIDE_AI_Server,
    tool: PIDE_MCP_Tool,
    args: JSON.Object.T,
    progress: Progress,
    context: PIDE_AI_Client_Context,
    log: Logger
  ): PIDE_MCP_Tool_Result =
    Exn.capture {
      progress.expose_interrupt()
      PIDE_MCP_Util.with_client_dir(context.cwd) {
        tool match {
          case core: PIDE_AI_Core_Tool => core.handle_server(server, args, progress)
          case _ => tool.handle(server.sessions, args, progress)
        }
      }
    } match {
      case Exn.Res(result) => result
      case Exn.Exn(Exn.Interrupt()) if progress.stopped => Result.Error("Tool call was stopped")
      case Exn.Exn(Exn.Interrupt()) => Result.Error("Tool call was interrupted")
      case Exn.Exn(exn) =>
        PIDE_MCP_Util.log_failure(log, "Internal error for tool " + quote(tool.name),
          Exn.Exn(exn))
        Result.Error("Internal error: " + Exn.message(exn))
    }


  /* client */

  def read_response(
    connection: Line_Connection,
    tool_id: String,
    progress: Progress
  ): PIDE_MCP_Tool_Result = {
    var result: Option[PIDE_MCP_Tool_Result] = None
    for (line <- connection.read_lines()) {
      def bad(): Nothing = error(s"Bad response to ${quote(tool_id)}: $line")
      if (result.isDefined) bad()
      JSON.Format.unapply(line) match {
        case Some(JSON.Object(obj)) if obj.keySet == Set("progress") =>
          progress.echo(text(obj("progress")))
        case Some(JSON.Object(obj)) if obj.keySet == Set("result") =>
          result = Some(Result.Res(obj("result")))
        case Some(JSON.Object(obj)) if obj.keySet == Set("error") =>
          result = Some(Result.Error(obj("error")))
        case _ => bad()
      }
    }
    result.getOrElse(error(s"No response to ${quote(tool_id)}"))
  }

  def call_tool(
    connection: Line_Connection,
    tool_id: String,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result = {
    connection.write_line(JSON.Format(
      tool_call_json(PIDE_MCP_Protocol.Tool_Call(tool_id, args, Some(1L)))))
    read_response(connection, tool_id, progress)
  }
}
