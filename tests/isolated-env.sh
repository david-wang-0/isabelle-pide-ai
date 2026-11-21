# -*- shell-script -*-
# Sourced by the tests: run Isabelle with THIS checkout as the only PIDE MCP component,
# isolated from the user's live Isabelle setup.
#
# - USER_HOME points to a fresh temporary home, so $ISABELLE_HOME_USER/etc/components and
#   etc/settings are ours: they register only this checkout (no live pide_mcp component,
#   no query host, no AFP).
# - ISABELLE_HEAPS_SYSTEM points to the user's real heap directory, read-only: the heaps
#   (HOL, ...) are found there, while anything built goes to the temporary ISABELLE_HEAPS.
# - XDG_RUNTIME_DIR is temporary too, so a `pide` daemon started here has its own socket
#   directory.
#
# Sets TEST_TMP (removed by the caller's trap) and exports USER_HOME, XDG_RUNTIME_DIR.

TEST_COMPONENT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REAL_HEAPS="$(isabelle getenv -b ISABELLE_HEAPS 2>/dev/null)"
ISABELLE_ID="$(isabelle getenv -b ISABELLE_IDENTIFIER 2>/dev/null)"

TEST_TMP="$(mktemp -d "${TMPDIR:-/tmp}/pide-mcp-test.XXXXXX")"
mkdir -p "$TEST_TMP/home/.isabelle/$ISABELLE_ID/etc" "$TEST_TMP/run"
chmod 700 "$TEST_TMP/run"
echo "$TEST_COMPONENT" > "$TEST_TMP/home/.isabelle/$ISABELLE_ID/etc/components"
echo "ISABELLE_HEAPS_SYSTEM=\"$REAL_HEAPS\"" > "$TEST_TMP/home/.isabelle/$ISABELLE_ID/etc/settings"

export USER_HOME="$TEST_TMP/home"
export XDG_RUNTIME_DIR="$TEST_TMP/run"
unset ISABELLE_KIT_PROJECT PIDE_AUTOSTART

# sanity: the PIDE MCP jar must be this checkout's
jar="$(isabelle getenv -b ISABELLE_PIDE_MCP_JAR)"
case "$jar" in
  "$TEST_COMPONENT"/*) ;;
  *) echo "isolated-env: ISABELLE_PIDE_MCP_JAR is $jar, not under $TEST_COMPONENT" >&2; exit 2 ;;
esac
