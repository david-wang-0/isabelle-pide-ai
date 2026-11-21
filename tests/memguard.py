#!/usr/bin/env python3
"""memguard -- kill a test's processes when their total RSS exceeds a limit.

The test exports MEMGUARD_TAG=<unique value> before starting anything, so every process it
starts inherits it, including those that leave its process tree (the `pide` client starts
its daemon in a new session and exits, so the daemon, its JVM and Poly/ML are reparented
to init and a parent-pid walk would miss them). The guard finds the processes by that tag
in /proc/PID/environ (own user only) and sums their RSS every INTERVAL seconds.

Above LIMIT it writes the reason to MARKER (if given) and to stderr, sends SIGTERM to the
main process (the test script, whose trap reports the failure), then SIGKILL to every other
tagged process except itself, and SIGKILL to the main process too if it is still alive after
GRACE seconds. It exits when the main process is gone, printing the peak total RSS seen.

usage: memguard.py --tag TAG --main-pid PID --limit-mb N [--interval S] [--grace S] [--marker FILE]
Stdlib only. Tests: test_memguard.py.
"""

import argparse
import os
import signal
import sys
import time

PAGE = os.sysconf("SC_PAGE_SIZE")


def tagged_pids(tag, proc="/proc"):
    """PIDs of own-user processes whose environment has MEMGUARD_TAG=tag."""
    needle = b"MEMGUARD_TAG=" + tag.encode()
    me = os.getpid()
    pids = []
    for name in os.listdir(proc):
        if not name.isdigit() or int(name) == me:
            continue
        try:
            with open(os.path.join(proc, name, "environ"), "rb") as f:
                env = f.read().split(b"\0")
        except OSError:  # gone, or another user's
            continue
        if needle in env:
            pids.append(int(name))
    return pids


def rss_bytes(pid, proc="/proc"):
    try:
        with open(os.path.join(proc, str(pid), "statm")) as f:
            return int(f.read().split()[1]) * PAGE
    except (OSError, IndexError, ValueError):
        return 0


def total_rss(pids, proc="/proc"):
    return sum(rss_bytes(pid, proc) for pid in pids)


def alive(pid):
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    try:  # a zombie counts as gone
        with open(f"/proc/{pid}/stat") as f:
            return f.read().rsplit(")", 1)[1].split()[0] != "Z"
    except OSError:
        return False


def kill(pid, sig):
    try:
        os.kill(pid, sig)
    except (ProcessLookupError, PermissionError):
        pass


def trip(tag, main_pid, used, limit, marker, grace):
    msg = (f"memguard: tagged processes use {used / 2**30:.2f} GB RSS, over the limit of "
           f"{limit / 2**30:.2f} GB: killing them and failing the test")
    print(msg, file=sys.stderr, flush=True)
    if marker:
        with open(marker, "w") as f:
            f.write(msg + "\n")
    # TERM the main process FIRST: a bash script waiting on a foreground child then runs
    # its TERM trap as soon as that child dies, before `set -e` could exit on the child's
    # failure (killing the child first lost that race: exit 137, no failure message)
    kill(main_pid, signal.SIGTERM)
    time.sleep(0.1)
    # kill everything but the main process, repeatedly: children may still be forking
    for _ in range(5):
        others = [p for p in tagged_pids(tag) if p != main_pid]
        if not others:
            break
        for pid in others:
            kill(pid, signal.SIGKILL)
        time.sleep(0.2)
    deadline = time.monotonic() + grace
    while alive(main_pid) and time.monotonic() < deadline:
        time.sleep(0.2)
    if alive(main_pid):
        kill(main_pid, signal.SIGKILL)


def run(tag, main_pid, limit, interval, grace, marker):
    peak = 0
    while alive(main_pid):
        pids = tagged_pids(tag)
        used = total_rss(pids)
        peak = max(peak, used)
        if used > limit:
            trip(tag, main_pid, used, limit, marker, grace)
            return 1
        time.sleep(interval)
    print(f"memguard: peak {peak / 2**30:.2f} GB RSS (limit {limit / 2**30:.2f} GB)",
          file=sys.stderr, flush=True)
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--tag", required=True)
    ap.add_argument("--main-pid", type=int, required=True)
    ap.add_argument("--limit-mb", type=int, required=True)
    ap.add_argument("--interval", type=float, default=1.0)
    ap.add_argument("--grace", type=float, default=10.0)
    ap.add_argument("--marker")
    a = ap.parse_args(argv)
    return run(a.tag, a.main_pid, a.limit_mb << 20, a.interval, a.grace, a.marker)


if __name__ == "__main__":
    sys.exit(main())
