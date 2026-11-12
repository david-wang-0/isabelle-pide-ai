/*  Title:      PIDE_MCP/Server/pide_ai_client_connector.scala
    Author:     Kevin Kappelmann

Client side of a PIDE AI server.

Backported from PIDE AI (upstream AI/src/Server/pide_ai_client_connector.scala) onto Isabelle2025-2.
*/

package isabelle.pide.mcp

import isabelle._

abstract class PIDE_AI_Client_Connector {
  def remote: String
  def connect(protocol: PIDE_AI_Protocol, context: PIDE_AI_Client_Context): Line_Connection
}
