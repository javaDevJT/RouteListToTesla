import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

from consensus import ENGINES, _run_engine_workers


def _process_is_running(pid):
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    if sys.platform.startswith("linux"):
        try:
            state = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0]
        except FileNotFoundError:
            return False
        return state not in {"Z", "X"}
    return True


class ParallelEngineTest(unittest.TestCase):
    def test_workers_overlap_keep_engine_order_and_propagate_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            worker = """
import json, pathlib, sys, time
engine, started, finished, output = sys.argv[1:]
pathlib.Path(started).write_text(str(time.monotonic()))
time.sleep(1)
pathlib.Path(finished).write_text(str(time.monotonic()))
line = {"engine": engine, "text": engine.upper(), "left": 1, "top": 2, "width": 3, "height": 4}
pathlib.Path(output).write_text(json.dumps({"engine": engine, "engineMilliseconds": 1000, "lines": [line]}))
"""
            commands = {
                engine: [
                    sys.executable,
                    "-c",
                    worker,
                    engine,
                    str(root / f"{engine}.started"),
                    str(root / f"{engine}.finished"),
                ]
                for engine in reversed(ENGINES)
            }
            results = _run_engine_workers(commands, timeout=5)

            starts = [float((root / f"{engine}.started").read_text()) for engine in ENGINES]
            finishes = [float((root / f"{engine}.finished").read_text()) for engine in ENGINES]
            self.assertLess(max(starts), min(finishes))
            self.assertEqual(list(ENGINES), list(results))
            self.assertEqual([engine.upper() for engine in ENGINES], [results[engine][0][0].text for engine in ENGINES])

            fail = "import sys, time; time.sleep(0.5); sys.exit(7)"
            sleep = "import os, pathlib, sys, time; pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(30)"
            failures = {
                ENGINES[0]: [sys.executable, "-c", fail],
                **{
                    engine: [sys.executable, "-c", sleep, str(root / f"{engine}.pid")]
                    for engine in ENGINES[1:]
                },
            }
            with self.assertRaisesRegex(RuntimeError, ENGINES[0]):
                _run_engine_workers(failures, timeout=5)
            for engine in ENGINES[1:]:
                pid = int((root / f"{engine}.pid").read_text())
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)

    def test_timeout_kills_worker_descendants_that_ignore_terminate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            child = """
import os, pathlib, signal, sys, time
signal.signal(signal.SIGTERM, signal.SIG_IGN)
time.sleep(30)
"""
            stubborn = """
import os, pathlib, subprocess, sys, time
child = subprocess.Popen([sys.executable, "-c", sys.argv[1], sys.argv[2]])
pathlib.Path(sys.argv[2]).write_text(str(child.pid))
pathlib.Path(sys.argv[3]).write_text(str(os.getpid()))
time.sleep(30)
"""
            quick = """
import json, pathlib, sys
engine, result = sys.argv[1:]
line = {"engine": engine, "text": engine, "left": 1, "top": 2, "width": 3, "height": 4}
pathlib.Path(result).write_text(json.dumps({"engine": engine, "engineMilliseconds": 1, "lines": [line]}))
"""
            timeout_engine = ENGINES[0]
            parent_pid = root / f"{timeout_engine}.parent.pid"
            child_pid = root / f"{timeout_engine}.child.pid"
            commands = {
                timeout_engine: [sys.executable, "-c", stubborn, child, str(child_pid), str(parent_pid)],
                **{engine: [sys.executable, "-c", quick, engine] for engine in ENGINES[1:]},
            }

            with self.assertRaises(subprocess.TimeoutExpired):
                _run_engine_workers(commands, timeout=2.5)

            for path in (parent_pid, child_pid):
                pid = int(path.read_text())
                end = time.monotonic() + 2
                while _process_is_running(pid) and time.monotonic() < end:
                    time.sleep(0.05)
                self.assertFalse(_process_is_running(pid), f"worker process {pid} survived timeout cleanup")


if __name__ == "__main__":
    unittest.main()
