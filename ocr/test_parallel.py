import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from consensus import (
    ENGINES,
    ENGINE_WORKER_CLEANUP_SECONDS,
    ENGINE_WORKER_DEADLINE_SECONDS,
    OcrTimeout,
    _run_engine_worker,
    _run_engine_workers,
)


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
    def test_worker_deadline_keeps_cleanup_reserve_under_java_budget(self):
        self.assertEqual(60, ENGINE_WORKER_DEADLINE_SECONDS)
        self.assertEqual(2, ENGINE_WORKER_CLEANUP_SECONDS)
        self.assertGreater(75, ENGINE_WORKER_DEADLINE_SECONDS + ENGINE_WORKER_CLEANUP_SECONDS)

    def test_tesseract_timeout_writes_a_safe_engine_result(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "models.json"
            result = Path(directory) / "result.json"
            manifest.write_text("{}")
            private_output = b"123 MAIN ST SECRET"
            timeout = subprocess.TimeoutExpired(
                ["tesseract"], 35, output=private_output, stderr=private_output
            )

            with patch("consensus.tesseract_lines", side_effect=timeout):
                _run_engine_worker("tesseract", "unused.png", str(manifest), str(result))

            serialized = result.read_text()
            self.assertEqual({"engine": "tesseract", "failure": "timeout"}, json.loads(serialized))
            self.assertNotIn("123 MAIN ST", serialized)
            self.assertNotIn("SECRET", serialized)

    def test_engine_timeout_result_is_engine_specific_and_aborts_consensus(self):
        timeout_engine = ENGINES[-1]
        timeout_worker = (
            "import json,pathlib,sys; engine,result=sys.argv[1:3]; "
            "pathlib.Path(result).write_text(json.dumps({'engine':engine,'failure':'timeout'}))"
        )
        success_worker = (
            "import json,pathlib,sys; engine,result=sys.argv[1:3]; "
            "pathlib.Path(result).write_text(json.dumps({'engine':engine,'engineMilliseconds':1,'lines':[]}))"
        )
        commands = {
            engine: [sys.executable, "-c", timeout_worker if engine == timeout_engine else success_worker, engine]
            for engine in ENGINES
        }

        # This test isolates result propagation; live process-group cleanup is covered below.
        with patch("consensus._stop_workers") as stop_workers:
            with self.assertRaises(OcrTimeout) as raised:
                _run_engine_workers(commands, timeout=5)
        stop_workers.assert_called_once()

        self.assertEqual("engine", raised.exception.scope)
        self.assertEqual((timeout_engine,), raised.exception.engines)
        self.assertEqual(
            f"Consensus OCR failed: Timeout scope=engine engines={timeout_engine}",
            raised.exception.safe_message(),
        )

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

            with self.assertRaises(OcrTimeout) as raised:
                _run_engine_workers(commands, timeout=2.5)

            self.assertEqual("shared_deadline", raised.exception.scope)
            self.assertEqual((timeout_engine,), raised.exception.engines)

            for path in (parent_pid, child_pid):
                pid = int(path.read_text())
                end = time.monotonic() + 2
                while _process_is_running(pid) and time.monotonic() < end:
                    time.sleep(0.05)
                self.assertFalse(_process_is_running(pid), f"worker process {pid} survived timeout cleanup")


if __name__ == "__main__":
    unittest.main()
