/*  Title:      PIDE_MCP/Server/pide_ai_endpoint.scala
    Author:     Kevin Kappelmann

Server side of one connection.

Backported from PIDE AI (upstream AI/src/Server/pide_ai_endpoint.scala) onto Isabelle2025-2.
*/

package isabelle.pide.mcp

import isabelle._

abstract class PIDE_AI_Endpoint(
  val server: PIDE_AI_Server,
  val connection: Line_Connection,
  val context: PIDE_AI_Client_Context
) extends Task[Unit]
