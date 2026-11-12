/*  Title:      PIDE_MCP/Command/pide_ai_command_endpoint.scala
    Author:     Kevin Kappelmann

Server side of a command connection.

Backport of PIDE AI's AI/src/Command/pide_ai_command_endpoint.scala and
Server/pide_ai_tool_endpoint.scala onto Isabelle2025-2.
*/

package isabelle.pide.mcp

import isabelle._

import java.io.IOException

object PIDE_AI_Command_Endpoint {
  // always available on command connections, even if not served as MCP tools
  def core_tools(server: PIDE_AI_Server): List[PIDE_MCP_Tool] =
    List(List_Extensions_Tool,
      server.tool("start_session").getOrElse(new Tool_Start_Session),
      Stop_Server_Tool)

  def tools(server: PIDE_AI_Server): List[PIDE_MCP_Tool] = {
    val core = core_tools(server)
    (core ::: server.tools.filterNot(tool => core.exists(_.name == tool.name))).sortBy(_.name)
  }

  def tool(server: PIDE_AI_Server, name: String): Option[PIDE_MCP_Tool] =
    core_tools(server).find(_.name == name).orElse(server.tool(name))

  def tool_not_found(id: String): String = s"Tool not found: ${quote(id)}"
}

class PIDE_AI_Command_Endpoint(
  server: PIDE_AI_Server,
  connection: Line_Connection,
  context: PIDE_AI_Client_Context,
  log: Logger
) extends PIDE_AI_Endpoint(server, connection, context) {
  private def respond(body: JSON.T): Unit = connection.write_line(JSON.Format(body))

  private def respond_error(message: String): Unit =
    respond(PIDE_AI_Command.result_response(Result.Error(message)))

  private def await_client_end(): Unit =
    try { for (line <- connection.read_line()) error(s"Unexpected input after command: $line") }
    catch { case _: IOException => }

  private val tool_call = Synchronized[Option[Progress_Task]](None)

  private def handle_tool_call(tool: PIDE_MCP_Tool, call: PIDE_MCP_Protocol.Tool_Call): Unit = {
    val report_lock = new Object
    var reporting = true
    def report(json: JSON.T): Unit = report_lock.synchronized {
      if (reporting && !connection.closed.value) Exn.capture { respond(json) }
    }
    val tool_progress = new PIDE_MCP_Task_Progress(server.base_progress,
      call.progress_token.map(_ =>
        (_: Long, message: String) => report(PIDE_AI_Command.progress_response(message))))
    val task = new Progress_Task("pide_ai_command_tool_call", tool_progress, daemon = true) {
      override protected def run(): Unit = {
        val result = PIDE_AI_Command.run_tool(server, tool, call.args, progress, context, log)
        PIDE_MCP_Util.release_combined(List(
          Exn.capture {
            PIDE_MCP_Util.log_failure(log, s"Failed to deliver result of tool " +
              s"${quote(tool.name)} to client ${connection.remote}",
              Exn.capture {
                report_lock.synchronized {
                  reporting = false
                  if (!connection.closed.value) respond(PIDE_AI_Command.result_response(result))
                }
              })
          },
          Exn.capture { connection.close() }))
      }
    }
    tool_call.change(_ => Some(task))
    task.start()
    PIDE_MCP_Util.release_combined(List(
      Exn.capture { await_client_end() },
      Exn.capture { connection.close() },
      Exn.capture { task.stop() },
      Exn.capture { task.join() }))
  }

  private def handle_request(line: String): Unit =
    Exn.result {
      val call = PIDE_AI_Command.parse_tool_call(line)
      val tool = PIDE_AI_Command_Endpoint.tool(server, call.name)
        .getOrElse(error(PIDE_AI_Command_Endpoint.tool_not_found(call.name)))
      (tool, call)
    } match {
      case Exn.Exn(exn) => respond_error(Exn.message(exn))
      case Exn.Res((tool, call)) => handle_tool_call(tool, call)
    }

  private val request_scope = new Task_Scope[Task[?]]

  def run(): Unit =
    if (Exn.is_res(server.ready.join_result)) {
      val read = Task.close_on_stop(connection, s"client ${connection.remote}", log)(
        connection.read_line())
      request_scope.run(read).foreach(handle_request)
    }

  def stop(): Unit =
    PIDE_MCP_Util.release_combined(List(
      Exn.capture { request_scope.stop() },
      Exn.capture { tool_call.value.foreach(_.stop()) }))
}
