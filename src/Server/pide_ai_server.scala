/*  Title:      PIDE_MCP/Server/pide_ai_server.scala
    Author:     Kevin Kappelmann

PIDE sessions served to clients via connectors (stdin/stdout, TCP).

Backport of PIDE AI's AI/src/Server/pide_ai_server.scala and pide_ai_startup_sessions.scala
onto Isabelle2025-2: the server serves the PIDE MCP sessions and tools of this version.
*/

package isabelle.pide.mcp

import isabelle._

class PIDE_AI_Startup_Sessions(
  specs: List[PIDE_MCP_Session.Spec],
  await: Boolean,
  exit_on_failure: Boolean,
  log: Logger,
  base_progress: Progress.Status,
  start_session: (PIDE_MCP_Session.Spec, Progress) => Unit
) extends Task[Unit] {
  val progress = new PIDE_MCP_Task_Progress(base_progress, None)

  private val finished = Future.promise[Unit]
  val ready: Future[Unit] = if (await) finished else Future.value(())

  def run(): Unit = {
    val result = Exn.capture {
      if (specs.nonEmpty) log("Starting PIDE sessions...")
      for (spec <- specs) {
        progress.expose_interrupt()
        Exn.capture { start_session(spec, progress) } match {
          case Exn.Res(_) =>
          case Exn.Exn(exn) =>
            progress.expose_interrupt()
            if (exit_on_failure || Exn.is_interrupt(exn)) throw exn
            else log(s"Failed to start PIDE session: ${Exn.message(exn)}")
        }
      }
    }
    finished.fulfill_result(result)
    Exn.release(result)
  }

  def stop(): Unit = progress.stop()
}

class PIDE_AI_Server(
  val options: Options,
  val sessions: PIDE_MCP_Sessions,
  val log: Logger,
  val base_progress: Progress.Status,
  val client_info_table: Map[String, PIDE_AI_Client_Info],
  specs: List[PIDE_MCP_Session.Spec]
) {
  def tool_table: Map[String, PIDE_MCP_Tool] = sessions.tool_table
  def tools: List[PIDE_MCP_Tool] = tool_table.values.toList.sortBy(_.name)
  def tool(id: String): Option[PIDE_MCP_Tool] = tool_table.get(id)

  private val connectors_state = Synchronized[List[PIDE_AI_Connector[?]]](Nil)
  def connectors: List[PIDE_AI_Connector[?]] = connectors_state.value
  def client_infos: List[PIDE_AI_Client_Info] = client_info_table.values.toList.sortBy(_.id)

  def access_note: Option[String] =
    proper_string((for {
      connector <- connectors if connector.accepting_connections
      info <- client_infos if connector.protocols.contains(info.protocol.id)
      note <- info.access_note(connector)
    } yield note).mkString(" "))

  val scope = new Task_Scope[Task[?]]
  def stop(): Unit = scope.stop()

  val startup = new PIDE_AI_Startup_Sessions(specs,
    await = PIDE_MCP.await_option_sessions(options),
    exit_on_failure = PIDE_MCP.exit_on_failed_option_sessions(options),
    log, base_progress,
    (spec, progress) => Result.release(sessions.start_session(spec, progress)))

  def ready: Future[Unit] = startup.ready

  def shutdown(): Unit =
    PIDE_MCP_Util.release_combined(List(
      Exn.capture { scope.stop() },
      Exn.capture { scope.join() },
      Exn.capture { log("Stopping PIDE sessions...") },
      Exn.capture { Result.release(sessions.stop_sessions(None, base_progress)) }))

  def run(connectors: List[PIDE_AI_Connector[?]]): Unit = {
    val result = Exn.capture {
      POSIX_Interrupt.handler {
        log("Interrupt: shutting down...")
        stop()
      } {
        scope.fork("pide_ai_startup_sessions", startup)
        connectors_state.change(_ => connectors)
        for (connector <- connectors) scope.fork(connector.id, connector)
        scope.join_stop_request()
      }
    }
    val shutdown_result = Exn.capture { shutdown() }
    PIDE_MCP_Util.release_combined(List(result, shutdown_result))
  }
}
