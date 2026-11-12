/*  Title:      PIDE_MCP/Server/pide_ai_protocol.scala
    Author:     Kevin Kappelmann

Protocols served on a TCP port and the client infos advertised for them.

Backport of PIDE AI's AI/src/Server/pide_ai_protocol.scala, pide_ai_client_info.scala and
General/pide_ai_registry.scala onto Isabelle2025-2: fixed lists of entries instead of
Isabelle services.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_AI_Registry {
  val all_option: String = "*"

  def make_table[A](
    kind: String,
    entries: List[A],
    id: A => String,
    include_ids: String,
    exclude_ids: String
  ): Map[String, A] = {
    val table = entries.foldLeft(Map.empty[String, A]) {
      case (table, entry) => if (table.isDefinedAt(id(entry)))
          error(s"Duplicate $kind: ${Library.quote(id(entry))}")
        else table + (id(entry) -> entry)
    }
    def select(ids: String): Set[String] =
      if (ids == all_option) table.keySet
      else {
        val list = space_explode(',', ids)
        val unknown = list.filterNot(table.isDefinedAt)
        if (unknown.nonEmpty)
          error(s"Unknown $kind(s): ${commas_quote(unknown)}. " +
            s"Available $kind(s): ${commas_quote(table.keys.toList.sorted)}")
        list.toSet
      }
    val included = select(include_ids)
    val excluded = select(exclude_ids)
    table.filter { (id, _) => included(id) && !excluded(id) }
  }
}


/* protocols */

object PIDE_AI_Protocol {
  val cmd = new PIDE_AI_Protocol("cmd", new PIDE_AI_Command_Endpoint(_, _, _, _))
  val mcp = new PIDE_AI_Protocol("mcp", new PIDE_MCP_Server(_, _, _, _))

  def entries: List[PIDE_AI_Protocol] = List(cmd, mcp)

  def make_table(options: Options): Map[String, PIDE_AI_Protocol] =
    PIDE_AI_Registry.make_table("protocol", entries, _.id,
      options.string("pide_ai_protocols_include"), options.string("pide_ai_protocols_exclude"))
}

class PIDE_AI_Protocol(
  val id: String,
  val endpoint: (PIDE_AI_Server, Line_Connection, PIDE_AI_Client_Context, Logger) =>
    PIDE_AI_Endpoint)


/* client infos */

object PIDE_AI_Client_Info {
  // TODO: update once non-localhost addresses are allowed
  val cli = new PIDE_AI_Client_Info("pide_ai_cli", PIDE_AI_Protocol.cmd, {
      case tcp: PIDE_AI_TCP_Connector => Some(
        s"CLI on port ${tcp.port}: `CLIENT -?` shows usage (clients in " +
        quote(File.platform_path(Path.explode("$PIDE_AI_HOME/clients"))) + ").")
      case _ => None
    })

  val mcp_proxy = new PIDE_AI_Client_Info("pide_mcp_proxy", PIDE_AI_Protocol.mcp, {
      case tcp: PIDE_AI_TCP_Connector => Some(
        "MCP: " + quote(File.platform_path(Path.explode("$ISABELLE_HOME/bin/isabelle"))) +
        s" pide_mcp -p ${tcp.port} -s.")
      case _ => None
    })

  def entries: List[PIDE_AI_Client_Info] = List(cli, mcp_proxy)

  def make_table(options: Options): Map[String, PIDE_AI_Client_Info] =
    PIDE_AI_Registry.make_table("client info", entries, _.id,
      options.string("pide_ai_client_infos_include"),
      options.string("pide_ai_client_infos_exclude"))
}

class PIDE_AI_Client_Info(
  val id: String,
  val protocol: PIDE_AI_Protocol,
  val access_note: PIDE_AI_Connector[?] => Option[String])
