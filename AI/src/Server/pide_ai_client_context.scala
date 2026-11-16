/*  Title:      AI/src/Server/pide_ai_client_context.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.ai

import isabelle._

object PIDE_AI_Client_Context {
  val Id = """[A-Za-z0-9_-]+""".r

  def random_id(): String = UUID.random_string()

  def current_path: Path = Path.current.absolute

  def apply(id: String, dir: Option[Path]): PIDE_AI_Client_Context = {
    if (!Id.matches(id)) error(s"Bad client id ${quote(id)}: expected [A-Za-z0-9_-]+")
    new PIDE_AI_Client_Context(id, dir.map(dir => if (dir.is_absolute && dir.is_dir) dir.canonical
      else error(s"Working directory ${quote(dir.implode)} is not an absolute path " +
        "to an existing directory")))
  }
}

class PIDE_AI_Client_Context private(val id: String, val cwd: Option[Path])
