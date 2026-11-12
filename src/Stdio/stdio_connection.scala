/*  Title:      PIDE_MCP/Stdio/stdio_connection.scala
    Author:     Kevin Kappelmann

Backported from PIDE AI (upstream AI/src/Stdio/stdio_connection.scala) onto Isabelle2025-2.
*/

package isabelle.pide.mcp

import isabelle._

class Stdio_Connection(log: Logger) extends Line_Connection(System.in.nn, System.out.nn, log) {
  val remote = "stdin/stdout"
  def shutdown_output(): Unit = ()
}
