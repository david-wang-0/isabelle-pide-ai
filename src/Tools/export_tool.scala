/*  Title:      PIDE_MCP/Tools/export_tool.scala
    Author:     Kevin Kappelmann

List or write the theory exports (e.g. generated code) of a running session.

Backport of PIDE AI's AI/src/Tools/export_tool.scala (added in upstream d872aee, revised in
41bdfda; state of main 10fac37) onto Isabelle2025-2. Same tool name, arguments and result
shape as upstream. Differences:
  - There is no node cache in this version: exports are read per theory node directly from
    the snapshot (Document.State.node_exports), as upstream did before 41bdfda.
  - The client's working directory comes from PIDE_MCP_Util.client_dir (set for command
    connections over TCP); without one (stdio MCP), relative paths resolve against the
    server's working directory and written paths are printed absolute, as upstream.
  - Extra, outside the result: a progress warning for each loaded theory that is still
    being processed, since its exports may then be incomplete (the result itself does not
    say so; check get_state or get_session_state first).
*/

package isabelle.pide.mcp

import isabelle._

object Export_Tool {
  def write_export(entry: Export.Entry, dir: Path, progress: Progress): Path = {
    progress.expose_interrupt()
    val path = dir + entry.entry_name.make_path()
    progress.echo("export " + path + (if (entry.executable) " (executable)" else ""))
    Isabelle_System.make_directory(path.dir)
    val bytes = entry.bytes
    if (!path.is_file || Bytes.read(path) != bytes) Bytes.write(path, bytes)
    File.set_executable(path, reset = !entry.executable)
    path
  }

  def theory_nodes(snapshot: Document.Snapshot): List[Document.Node.Name] =
    snapshot.version.nodes.iterator.collect { case (name, _) if name.is_theory => name }.toList

  def snapshot_exports(snapshot: Document.Snapshot): List[Export.Entry] =
    theory_nodes(snapshot).flatMap(name =>
      snapshot.state.node_exports(snapshot.version, name).iterator.map(_._2))
      .sortBy(entry => (entry.entry_name.theory, entry.entry_name.name))

  def exports(session: PIDE_MCP_Session): List[Export.Entry] =
    snapshot_exports(session.snapshot())

  def select(entries: List[Export.Entry], patterns: List[String]): List[Export.Entry] = {
    val matcher = Export.make_matcher(patterns)
    entries.filter(entry => matcher(entry.entry_name))
  }

  def write_exports(
    session: PIDE_MCP_Session,
    patterns: List[String],
    out_dir: Path,
    progress: Progress
  ): List[Path] = select(exports(session), patterns).map(write_export(_, out_dir, progress))


  /* theories still being processed (extra: progress warnings only) */

  sealed case class Pending(theory: String, unprocessed: Int, running: Int, consolidated: Boolean) {
    def message: String =
      "Theory " + quote(theory) + " is still being processed (" +
        List(
          Option.when(unprocessed > 0)(unprocessed.toString + " unprocessed"),
          Option.when(running > 0)(running.toString + " running"),
          Option.when(unprocessed == 0 && running == 0 && !consolidated)("not consolidated"))
          .flatten.mkString(", ") +
        "): its exports may be incomplete. Check get_state and retry."
  }

  def is_pending(status: Document_Status.Node_Status, consolidated: Boolean): Boolean =
    status.unprocessed > 0 || status.running > 0 || !consolidated

  def pending(snapshot: Document.Snapshot): List[Pending] = {
    val now = Date.now()
    for {
      name <- theory_nodes(snapshot)
      if PIDE_MCP_Util.is_loaded_dynamic(snapshot.version.nodes(name))
      status = Document_Status.Node_Status.make(now, snapshot.state, snapshot.version, name)
      consolidated = snapshot.state.node_consolidated(snapshot.version, name)
      if is_pending(status, consolidated)
    } yield Pending(name.theory, status.unprocessed, status.running, consolidated)
  }

  def warn_pending(session: PIDE_MCP_Session, progress: Progress): Unit = {
    val snapshot = session.snapshot()
    if (snapshot.is_outdated)
      progress.echo_warning("The document has pending edits: exports may be incomplete.")
    for (p <- pending(snapshot)) progress.echo_warning(p.message)
  }
}

class Export_Tool extends PIDE_MCP_Tool("export") {
  def description: String = "List or write exports (e.g. code)."

  val patterns_arg = PIDE_MCP_Tool_Arg.opt_strings("patterns",
    s"Write exports matching THEORY:NAME patterns (e.g. ${quote("Scratch:code/**")}). " +
      "Default: list exports.")
  val out_dir_arg = PIDE_MCP_Tool_Arg.string_default("out_dir", "",
    Export.default_export_dir.implode)

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, patterns_arg, out_dir_arg))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val session = PIDE_MCP_Tool_Util.running_session_param(sessions, args)
      Export_Tool.warn_pending(session, progress)
      patterns_arg.get(args) match {
        case None => Export_Tool.exports(session).map(_.compound_name)
        case Some(patterns) =>
          Export_Tool.write_exports(session, patterns,
            PIDE_MCP_Util.path(out_dir_arg.get(args)), progress)
          .map(PIDE_MCP_Util.print_path)
      }
    }
}

class Export_Tools extends PIDE_MCP_Tools(new Export_Tool)
