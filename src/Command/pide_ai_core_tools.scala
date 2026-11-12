/*  Title:      PIDE_MCP/Command/pide_ai_core_tools.scala
    Author:     Kevin Kappelmann

Tools of the command protocol that act on the server: list_extensions, stop_server.

Backport of PIDE AI's AI/src/Tools/list_extensions_tool.scala and stop_server_tool.scala onto
Isabelle2025-2. Unlike upstream, these tools are only served on command connections, not as
MCP tools, so the MCP tool list of this version stays unchanged. There are no services in
this version: list_extensions always reports an empty list of services.
*/

package isabelle.pide.mcp

import isabelle._

abstract class PIDE_AI_Core_Tool(name: String) extends PIDE_MCP_Tool(name) {
  def handle_server(
    server: PIDE_AI_Server,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    Result.Error(s"Tool ${quote(name)} is only available via the command protocol")
}

object List_Extensions_Tool extends PIDE_AI_Core_Tool("list_extensions") {
  def description: String = "List services and tools."

  private val names_arg = PIDE_MCP_Tool_Arg.opt_strings(
    "names", "Extensions to list. Default: all.")
  private val show_description_arg = PIDE_MCP_Tool_Arg.bool_default(
    "show_description", "", true)
  private val show_input_schema_arg = PIDE_MCP_Tool_Arg.bool_default(
    "show_input_schema", "", false)

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(names_arg, show_description_arg, show_input_schema_arg))

  def handle_server(
    server: PIDE_AI_Server,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val tools = PIDE_AI_Command_Endpoint.tools(server)
      val names = names_arg.get(args).map(_.toSet)
      for (names <- names) {
        val unknown = names.filterNot(name => tools.exists(_.name == name))
        if (unknown.nonEmpty) error(s"Unknown extension(s): ${commas_quote(unknown.toList.sorted)}")
      }
      val show_description = show_description_arg.get(args)
      val show_input_schema = show_input_schema_arg.get(args)
      def entry(tool: PIDE_MCP_Tool): JSON.T =
        if (!show_description && !show_input_schema) tool.name
        else {
          JSON_Object("name" -> tool.name) ++
          JSON.optional("description" -> Option.when(show_description)(tool.description)) ++
          JSON.optional("inputSchema" -> Option.when(show_input_schema)(tool.input_schema))
        }
      JSON_Object("services" -> List.empty[JSON.T],
        "tools" -> tools.filter(tool => names.forall(_(tool.name))).map(entry))
    }
}

object Stop_Server_Tool extends PIDE_AI_Core_Tool("stop_server") {
  def description: String = "Stop PIDE AI server."

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(Nil)

  def handle_server(
    server: PIDE_AI_Server,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result = {
    server.stop()
    Result.Res("Stopping server")
  }
}
