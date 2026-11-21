#!/usr/bin/env python3
"""Tests of memguard.py (stdlib unittest). Hermetic and cheap: every allocator stops by
itself at a small bound (MAX_MB), so a broken guard cannot hurt the machine.

Run: python3 -I tests/test_memguard.py
"""

import os
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
GUARD = os.path.join(HERE, "memguard.py")
sys.path.insert(0, HERE)
import memguard  # noqa: E402

MAX_MB = 400  # hard bound of every allocator below

# allocate 20 MB per step up to MB megabytes (touching the pages), then idle
ALLOC = """
import sys, time
mb = int(sys.argv[1]); chunks = []
while len(chunks) * 20 < mb:
    chunks.append(bytearray(20 << 20)); time.sleep(0.05)
time.sleep(float(sys.argv[2]))
"""


def env_with(tag):
    env = dict(os.environ)
    if tag is None:
        env.pop("MEMGUARD_TAG", None)
    else:
        env["MEMGUARD_TAG"] = tag
    return env


def read(path):
    with open(path) as f:
        return f.read()


def alloc_cmd(mb, idle):
    assert mb <= MAX_MB
    return [sys.executable, "-I", "-c", ALLOC, str(mb), str(idle)]


class MemguardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="memguard-test.")
        self.tag = "t-" + uuid.uuid4().hex
        self.procs = []

    def tearDown(self):
        for pid in memguard.tagged_pids(self.tag):
            memguard.kill(pid, signal.SIGKILL)
        for p in self.procs:
            if p.poll() is None:
                p.kill()
            p.wait()
            if p.stderr:
                p.stderr.close()
        subprocess.run(["rm", "-rf", self.tmp])

    def spawn(self, cmd, tag, **kw):
        p = subprocess.Popen(cmd, env=env_with(tag), **kw)
        self.procs.append(p)
        return p

    def main_script(self, body):
        """A tagged bash 'test script' with a TERM trap, as the e2e uses."""
        trapped = os.path.join(self.tmp, "trapped")
        script = (f"trap 'echo term > {trapped}; exit 1' TERM\n{body}\n"
                  "for i in $(seq 1 300); do sleep 0.1; done\n")
        return self.spawn(["bash", "-c", script], self.tag), trapped

    def guard(self, main_pid, limit_mb, marker):
        return self.spawn([sys.executable, "-I", GUARD, "--tag", self.tag,
                           "--main-pid", str(main_pid), "--limit-mb", str(limit_mb),
                           "--interval", "0.3", "--grace", "3", "--marker", marker],
                          self.tag, stderr=subprocess.PIPE, text=True)

    def test_tagged_pids_selects_by_tag(self):
        tagged = self.spawn(["sleep", "30"], self.tag)
        untagged = self.spawn(["sleep", "30"], None)
        other = self.spawn(["sleep", "30"], self.tag + "x")
        time.sleep(0.3)
        pids = memguard.tagged_pids(self.tag)
        self.assertIn(tagged.pid, pids)
        self.assertNotIn(untagged.pid, pids)
        self.assertNotIn(other.pid, pids)
        self.assertNotIn(os.getpid(), pids)

    def test_rss_of_allocator(self):
        p = self.spawn(alloc_cmd(100, 30), self.tag)
        deadline = time.monotonic() + 15
        while memguard.rss_bytes(p.pid) < 90 << 20 and time.monotonic() < deadline:
            time.sleep(0.2)
        self.assertGreaterEqual(memguard.rss_bytes(p.pid), 90 << 20)
        self.assertEqual(memguard.rss_bytes(999999999), 0)

    def test_trips_on_reparented_allocator(self):
        """The allocator leaves the process tree (setsid, parent exits), as the pide
        daemon does; the guard still finds it by tag, kills it and fails the main script."""
        marker = os.path.join(self.tmp, "marker")
        pidfile = os.path.join(self.tmp, "alloc.pid")
        cmd = shlex.join(alloc_cmd(MAX_MB, 60))
        main, trapped = self.main_script(
            f"( setsid {cmd} & echo $! > {pidfile} ) ; sleep 0.2")
        g = self.guard(main.pid, 150, marker)
        t0 = time.monotonic()
        rc = g.wait(timeout=60)
        elapsed = time.monotonic() - t0
        main.wait(timeout=10)
        alloc_pid = int(read(pidfile))
        self.assertEqual(rc, 1)
        self.assertIn("over the limit", g.stderr.read())
        g.stderr.close()
        self.assertTrue(os.path.exists(marker))
        self.assertEqual(read(trapped).strip(), "term")
        self.assertFalse(memguard.alive(alloc_pid))
        self.assertEqual(memguard.tagged_pids(self.tag), [])
        self.assertLess(elapsed, 30)

    def test_set_e_script_reports_through_its_trap(self):
        """Regression (2026-11-21): a `set -e` script whose foreground child is killed must
        still run its TERM trap, not exit on the child's status first."""
        marker = os.path.join(self.tmp, "marker")
        out = os.path.join(self.tmp, "out")
        cmd = shlex.join(alloc_cmd(300, 60))
        script = (f"set -e\ntrap 'echo trapped > {out}; exit 1' TERM\n{cmd}\n"
                  f"echo continued > {out}\n")
        main = self.spawn(["bash", "-c", script], self.tag)
        g = self.guard(main.pid, 150, marker)
        self.assertEqual(g.wait(timeout=60), 1)
        main.wait(timeout=10)
        self.assertEqual(main.returncode, 1)
        self.assertEqual(read(out).strip(), "trapped")

    def test_main_that_ignores_term_is_killed(self):
        marker = os.path.join(self.tmp, "marker")
        cmd = shlex.join(alloc_cmd(300, 60))
        main = self.spawn(["bash", "-c", f"trap '' TERM; {cmd}; sleep 30"], self.tag)
        g = self.guard(main.pid, 150, marker)
        self.assertEqual(g.wait(timeout=60), 1)
        main.wait(timeout=10)
        self.assertEqual(main.returncode, -signal.SIGKILL)

    def test_no_trip_below_limit_and_untagged_ignored(self):
        marker = os.path.join(self.tmp, "marker")
        untagged = self.spawn(alloc_cmd(200, 30), None)
        main = self.spawn(["bash", "-c", shlex.join(alloc_cmd(40, 2))], self.tag)
        g = self.guard(main.pid, 150, marker)
        self.assertEqual(g.wait(timeout=60), 0)  # exits when the main process is gone
        self.assertRegex(g.stderr.read(), r"memguard: peak 0\.\d\d GB RSS")
        self.assertFalse(os.path.exists(marker))
        self.assertIsNone(untagged.poll())


if __name__ == "__main__":
    unittest.main(verbosity=2)
