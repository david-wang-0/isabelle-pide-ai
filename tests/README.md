# Tests of the `export` tool backport (and their helpers)

All of them run this checkout in an isolated Isabelle user home (`isolated-env.sh`): a
temporary `USER_HOME` whose `etc/components` lists only this checkout, the user's heap
directory as read-only `ISABELLE_HEAPS_SYSTEM`, a temporary `XDG_RUNTIME_DIR`. The user's
live PIDE MCP install, its components file and its running `pide` daemons are not touched.

| Test | What | Run |
|---|---|---|
| `test-export-tool` | unit tests of `src/Tools/export_tool.scala` (`src/Tools/export_tool_test.scala`): pattern selection, output paths, bytes and executable bit, path resolution and printing, the pending warning, input schema and service registration | `tests/test-export-tool` |
| `test_memguard.py` | the memory guard used by the e2e (stdlib unittest; allocators bounded at 400 MB) | `python3 -I tests/test_memguard.py` |
| `e2e-export-tool` | end to end through isabelle-kit's `pide` client and a daemon of its own (instance `exports-test`) on a throwaway HOL project: tool listed, list, write, byte-identical to `isabelle export` and ROOT `export_files`, the pending case, daemon stopped | `tests/e2e-export-tool` (`KEEP=1` keeps the temp dir) |

Verify: `tests/test-export-tool && python3 -I tests/test_memguard.py && tests/e2e-export-tool`.

## Memory

The e2e runs one PIDE session on HOL and, before it, one `isabelle build` of a one-theory
session. Heaps are capped in the isolated settings (JVM `-Xmx1536m` with G1, Poly/ML
`--maxheap 3G`). `prlimit --as` cannot be used: Poly/ML 64_32 needs more than 16 GB of
virtual address space to initialise, the JVM about 6 GB to load its classes.

On top of that, `memguard.py` watches every process the e2e starts (found by the
`MEMGUARD_TAG` environment variable, so also the daemon, which leaves the process tree)
and, above 9 GB of total RSS (`MEMGUARD_LIMIT_MB`), kills them and fails the test.
`E2E_GUARD_SELFTEST=1 tests/e2e-export-tool` proves that wiring on a bounded allocator
against a 150 MB limit (expected: FAIL with the guard's message, exit 1).
