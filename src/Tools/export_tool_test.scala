/*  Title:      PIDE_MCP/Tools/export_tool_test.scala

Unit tests for the pure parts of the export tool (export_tool.scala): pattern selection,
output paths, bytes and executable bit of written files, path resolution and printing
against the client's working directory, the pending-theory warning, the input schema.
Hermetic: entries are built in memory, files go to a fresh temporary directory.

Run: tests/test-export-tool  (isabelle java isabelle.pide.mcp.Export_Tool_Test)
*/

package isabelle.pide.mcp

import isabelle._

object Export_Tool_Test {
  private var passed = 0
  private var failed = List.empty[String]

  private def check(what: String, ok: => Boolean): Unit =
    Exn.capture(ok) match {
      case Exn.Res(true) => passed += 1
      case Exn.Res(false) => failed ::= what
      case Exn.Exn(exn) => failed ::= what + ": " + Exn.message(exn)
    }

  private def entry(
    theory: String,
    name: String,
    text: String,
    executable: Boolean = false,
    compressed: Boolean = false
  ): Export.Entry = {
    val bytes = Bytes(text)
    val body = if (compressed) bytes.compress() else bytes
    Export.Entry(Export.Entry_Name(session = Sessions.DRAFT, theory = theory, name = name),
      executable, compressed, body, XML.Cache.none)
  }

  private val sml = entry("Scratch.T", "code/t.ML", "structure T = struct end\n")
  private val hs = entry("Scratch.T", "code/h/Foo.hs", "module Foo where\n", compressed = true)
  private val sh = entry("Scratch.T", "code/run.sh", "#!/bin/sh\necho hi\n", executable = true)
  private val other = entry("Other.U", "code/u.ML", "val u = 1\n")
  private val all = List(sml, hs, sh, other)

  private def names(entries: List[Export.Entry]): List[String] = entries.map(_.compound_name)

  def test_select(): Unit = {
    check("select **", names(Export_Tool.select(all, List("Scratch.T:code/**"))) ==
      List("Scratch.T:code/t.ML", "Scratch.T:code/h/Foo.hs", "Scratch.T:code/run.sh"))
    check("select * stops at /", names(Export_Tool.select(all, List("Scratch.T:code/*"))) ==
      List("Scratch.T:code/t.ML", "Scratch.T:code/run.sh"))
    check("select exact", names(Export_Tool.select(all, List("Scratch.T:code/h/Foo.hs"))) ==
      List("Scratch.T:code/h/Foo.hs"))
    check("select any theory", Export_Tool.select(all, List("*:**")).length == 4)
    check("select several patterns",
      names(Export_Tool.select(all, List("Other.U:**", "Scratch.T:code/t.ML"))) ==
        List("Scratch.T:code/t.ML", "Other.U:code/u.ML"))
    check("select braces",
      names(Export_Tool.select(all, List("Scratch.T:code/{t.ML,run.sh}"))) ==
        List("Scratch.T:code/t.ML", "Scratch.T:code/run.sh"))
    check("select no match", Export_Tool.select(all, List("Nope:**")).isEmpty)
    check("select empty patterns", Export_Tool.select(all, Nil).isEmpty)
    check("theory name is not a prefix match",
      Export_Tool.select(all, List("Scratch:code/**")).isEmpty)
  }

  def test_write(dir: Path): Unit = {
    val progress = new Progress
    val p1 = Export_Tool.write_export(sml, dir, progress)
    check("path = dir/THEORY/NAME", p1 == dir + Path.explode("Scratch.T/code/t.ML"))
    check("bytes written", File.read(p1) == "structure T = struct end\n")
    check("not executable", !File.is_executable(p1))

    val p2 = Export_Tool.write_export(hs, dir, progress)
    check("nested path", p2 == dir + Path.explode("Scratch.T/code/h/Foo.hs"))
    check("compressed entry written uncompressed", File.read(p2) == "module Foo where\n")

    val p3 = Export_Tool.write_export(sh, dir, progress)
    check("executable bit set", File.is_executable(p3))

    // unchanged content is not rewritten (same as Export.export_files)
    val t0 = p1.file.lastModified
    p1.file.setLastModified(t0 - 10000)
    Export_Tool.write_export(sml, dir, progress)
    check("unchanged file not rewritten", p1.file.lastModified == t0 - 10000)

    // changed content is overwritten, and a stale executable bit is reset
    File.write(p1, "stale")
    File.set_executable(p1)
    Export_Tool.write_export(sml, dir, progress)
    check("changed file overwritten", File.read(p1) == "structure T = struct end\n")
    check("stale executable bit reset", !File.is_executable(p1))
  }

  def test_paths(dir: Path): Unit = {
    val cwd = dir + Path.explode("project")
    val inside = cwd + Path.explode("export/Scratch.T/code/t.ML")
    val outside = dir + Path.explode("elsewhere/x")

    PIDE_MCP_Util.with_client_dir(Some(cwd)) {
      check("relative out_dir resolves against client dir",
        PIDE_MCP_Util.path("export") == cwd + Path.explode("export"))
      check("absolute out_dir kept",
        PIDE_MCP_Util.path(outside.implode) == outside)
      check("printed relative to client dir",
        PIDE_MCP_Util.print_path(inside) == "export/Scratch.T/code/t.ML")
      check("printed absolute outside client dir",
        PIDE_MCP_Util.print_path(outside) == outside.absolute.implode)
    }
    PIDE_MCP_Util.with_client_dir(None) {
      check("no client dir: relative path stays relative",
        PIDE_MCP_Util.path("export") == Path.explode("export"))
      check("no client dir: printed absolute",
        PIDE_MCP_Util.print_path(Path.explode("export/a")) ==
          Path.explode("export/a").absolute.implode)
    }
  }

  def test_pending(): Unit = {
    val st = Document_Status.Node_Status
    check("finished and consolidated: not pending",
      !Export_Tool.is_pending(st.empty.copy(finished = 3), consolidated = true))
    check("unprocessed: pending",
      Export_Tool.is_pending(st.empty.copy(unprocessed = 1), consolidated = false))
    check("running: pending",
      Export_Tool.is_pending(st.empty.copy(running = 1), consolidated = true))
    check("not consolidated: pending",
      Export_Tool.is_pending(st.empty.copy(finished = 3), consolidated = false))
    check("failed but consolidated: not pending",
      !Export_Tool.is_pending(st.empty.copy(failed = 1, finished = 2), consolidated = true))
    val msg = Export_Tool.Pending("Scratch.T", 2, 1, consolidated = false).message
    check("pending message", msg.startsWith("Theory \"Scratch.T\" is still being processed " +
      "(2 unprocessed, 1 running)") && msg.contains("may be incomplete"))
    check("pending message, only consolidation missing",
      Export_Tool.Pending("Scratch.T", 0, 0, consolidated = false).message
        .contains("(not consolidated)"))
  }

  def test_schema(): Unit = {
    val tool = new Export_Tool
    check("tool name", tool.name == "export")
    val schema = tool.input_schema
    val props = JSON.Object.unapply(schema("properties")).get
    check("arguments as upstream", props.keySet == Set("session", "patterns", "out_dir"))
    check("no required arguments", !schema.isDefinedAt("required"))
    check("out_dir default", JSON.Object.unapply(props("out_dir")).get.get("default")
      .contains("export"))
    check("patterns: list of strings",
      JSON.Object.unapply(props("patterns")).get.get("type").contains("array"))
    check("patterns absent = list", tool.patterns_arg.get(JSON.Object.empty).isEmpty)
    check("out_dir absent = export", tool.out_dir_arg.get(JSON.Object.empty) == "export")
    check("registered as service",
      Isabelle_System.make_services(classOf[PIDE_MCP_Tools]).flatMap(_.entries)
        .exists(_.name == "export"))
  }

  def main(args: Array[String]): Unit =
    Command_Line.tool {
      Isabelle_System.with_tmp_dir("export_tool_test") { dir =>
        test_select()
        test_write(dir + Path.explode("write"))
        test_paths(dir)
        test_pending()
        test_schema()
      }
      for (f <- failed.reverse) Output.writeln("FAIL " + f, stdout = true)
      Output.writeln(s"$passed passed, ${failed.length} failed", stdout = true)
      if (failed.nonEmpty) sys.exit(1)
    }
}
