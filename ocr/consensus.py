#!/usr/bin/env python3
"""Offline, independent OCR engines with spatial alignment and literal majority voting."""

from __future__ import annotations

import contextlib
import csv
import difflib
import io
import json
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

ENGINES = ("tesseract", "paddleocr", "easyocr")
# Reserve time under Java's 75-second process deadline for worker cleanup and response generation.
ENGINE_WORKER_DEADLINE_SECONDS = 60
ENGINE_WORKER_CLEANUP_SECONDS = 2


class OcrTimeout(Exception):
    def __init__(self, scope: str, engines: tuple[str, ...]):
        if scope not in {"engine", "shared_deadline"}:
            raise ValueError("Unknown OCR timeout scope")
        requested = set(engines)
        self.scope = scope
        self.engines = tuple(engine for engine in ENGINES if engine in requested)
        super().__init__()

    def safe_message(self) -> str:
        engines = ",".join(self.engines) or "none"
        return f"Consensus OCR failed: Timeout scope={self.scope} engines={engines}"


def canonical(text: str) -> str:
    # Only typography/whitespace equivalence: never substitute letters or digits.
    return " ".join(text.translate(str.maketrans({"’": "'", "‘": "'"})).upper().split())


def tokens(text: str) -> list[str]:
    return re.findall(r"[\w]+(?:['/-][\w]+)*|[^\w\s]", canonical(text))


def join_tokens(values: list[str]) -> str:
    value = " ".join(filter(None, values))
    return re.sub(r"\s+([,.;:])", r"\1", value)


@dataclass
class Line:
    engine: str
    text: str
    left: float
    top: float
    width: float
    height: float

    @property
    def middle(self) -> float:
        return self.top + self.height / 2


def joined_line(engine: str, parts: list[Line]) -> Line:
    parts = sorted(parts, key=lambda part: part.left)
    left = min(part.left for part in parts)
    top = min(part.top for part in parts)
    right = max(part.left + part.width for part in parts)
    bottom = max(part.top + part.height for part in parts)
    return Line(engine, " ".join(part.text.strip() for part in parts), left, top,
                right - left, bottom - top)


def group_words(engine: str, parts: list[Line]) -> list[Line]:
    """Unify word and line detectors for the single-column itinerary screenshots."""
    groups: list[list[Line]] = []
    for part in sorted((part for part in parts if part.text.strip()), key=lambda line: (line.middle, line.left)):
        match = next((group for group in reversed(groups)
                      if abs(part.middle - group[0].middle)
                      <= max(3, .45 * min(part.height, group[0].height))), None)
        if match is None:
            groups.append([part])
        else:
            match.append(part)
    return [joined_line(engine, group) for group in groups]


def tesseract_lines(path: str) -> list[Line]:
    from PIL import Image
    # Small screenshots benefit from larger glyphs. Keep the deep-engine inputs and memory unchanged.
    with Image.open(path) as source, tempfile.TemporaryDirectory(prefix="ocr-tesseract-") as directory:
        scale = 1.5 if source.width < 900 else 1.0
        input_path = path
        if scale > 1:
            input_path = str(Path(directory) / "scaled.png")
            resized = source.convert("RGB").resize(
                (round(source.width * scale), round(source.height * scale)), Image.Resampling.LANCZOS)
            resized.save(input_path)
            resized.close()
        result = subprocess.run(
            [os.environ.get("OCR_TESSERACT_BINARY", "/usr/bin/tesseract"), input_path, "stdout",
             "--psm", "11", "-l", "eng", "tsv"], capture_output=True, text=True,
            timeout=35, check=True)
    groups: dict[tuple[str, ...], list[Line]] = {}
    for row in csv.DictReader(io.StringIO(result.stdout), delimiter="\t"):
        if row.get("level") != "5" or not row.get("text", "").strip():
            continue
        key = tuple(row[name] for name in ("page_num", "block_num", "par_num", "line_num"))
        groups.setdefault(key, []).append(Line(
            "tesseract", row["text"], float(row["left"]) / scale, float(row["top"]) / scale,
            float(row["width"]) / scale, float(row["height"]) / scale))
    return [joined_line("tesseract", group) for group in groups.values()]


def box_line(engine: str, box, text: str) -> Line:
    xs, ys = [float(point[0]) for point in box], [float(point[1]) for point in box]
    return Line(engine, text, min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys))


def paddle_lines(path: str, manifest: dict) -> list[Line]:
    from rapidocr import RapidOCR
    engine = RapidOCR(params=manifest["rapidocr_params"])
    result = engine(path)
    if result.txts is None:
        return []
    return group_words("paddleocr", [box_line("paddleocr", box, text)
                                    for box, text in zip(result.boxes, result.txts)])


def easy_lines(path: str, manifest: dict) -> list[Line]:
    from PIL import Image
    import torch
    import easyocr
    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    directory = manifest["easyocr_model_directory"]
    engine = easyocr.Reader(["en"], gpu=False, verbose=False, download_enabled=False,
                            model_storage_directory=directory,
                            user_network_directory=str(Path(directory) / "user_network"))
    # Bound both side length and area; square images need the same memory headroom
    # as phone screenshots. EasyOCR returns boxes in original image coordinates.
    with Image.open(path) as source:
        aspect = max(source.size) / min(source.size)
        canvas_size = min(1280, int((800_000 * aspect) ** .5))
    result = engine.readtext(path, detail=1, paragraph=False, batch_size=1, workers=0,
                             canvas_size=canvas_size)
    return group_words("easyocr", [box_line("easyocr", box, text)
                                  for box, text, _confidence in result])


def align_rows(lines: list[Line]) -> list[list[Line]]:
    """One row per engine per spatial group; repeated stops remain separate rows."""
    groups: list[list[Line]] = []
    for line in sorted(lines, key=lambda row: (row.middle, ENGINES.index(row.engine))):
        candidates = [group for group in groups
                      if all(row.engine != line.engine for row in group)
                      and abs(line.middle - sum(row.middle for row in group) / len(group))
                      <= max(4, .55 * min(line.height, min(row.height for row in group)))
                      and any(difflib.SequenceMatcher(None, canonical(line.text), canonical(row.text),
                                                     autojunk=False).ratio() >= .3 for row in group)]
        if candidates:
            nearest = min(candidates, key=lambda group:
                          abs(line.middle - sum(row.middle for row in group) / len(group)))
            nearest.append(line)
        else:
            groups.append([line])
    return sorted(groups, key=lambda group: min(row.top for row in group))


def project(base: list[str], other: list[str]) -> tuple[list[str], dict[int, str]]:
    values = [""] * len(base)
    insertions: dict[int, str] = {}
    for operation, a, b, c, d in difflib.SequenceMatcher(None, base, other, autojunk=False).get_opcodes():
        if operation == "equal":
            values[a:b] = other[c:d]
        elif operation == "insert":
            insertions[a] = join_tokens(other[c:d])
        elif operation == "replace":
            if b - a == d - c:
                values[a:b] = other[c:d]
            else:
                values[a] = join_tokens(other[c:d])
    return values, insertions


def omits_words(chosen: str, alternative: str) -> bool:
    """Missing text is weaker evidence than a different reading of visible text."""
    base, other = tokens(chosen), tokens(alternative)
    for operation, a, b, c, d in difflib.SequenceMatcher(None, base, other, autojunk=False).get_opcodes():
        if operation in ("insert", "replace") and d - c > b - a:
            if any(re.search(r"\w", word) for word in other[c:d]):
                return True
    return False


def vote(rows: list[Line]) -> tuple[str, int, bool]:
    """A missing engine abstains. A disagreement without two votes stays unresolved."""
    names = [row.engine for row in rows]
    if not names or len(set(names)) != len(names) or not set(names).issubset(ENGINES):
        raise ValueError("Votes must come from distinct known engines")
    readings = [canonical(row.text) for row in rows]
    # Resolve attached house-number/directional spacing only when another engine
    # actually observed the separated form. The literal alternatives stay intact.
    observed = set(readings)
    for index, reading in enumerate(readings):
        separated = re.sub(r"^(\d+)([NSEW])(?=\s)", r"\1 \2", reading)
        if separated in observed:
            readings[index] = separated
    winner, count = Counter(readings).most_common(1)[0]
    if count >= 2:
        return winner, count, any(omits_words(winner, reading) for reading in readings)
    if len(rows) == 1:
        return readings[0], 1, True
    all_tokens = [tokens(reading) for reading in readings]
    # Select the observed reading nearest its peers as an alignment anchor, not as truth.
    anchor = max(range(len(rows)), key=lambda index: sum(
        difflib.SequenceMatcher(None, all_tokens[index], peer, autojunk=False).ratio()
        for peer in all_tokens))
    base = all_tokens[anchor]
    projections = [project(base, reading) for reading in all_tokens]
    support = 3
    unresolved = False
    result: list[str] = []
    for index in range(len(base) + 1):
        for is_insertion in (True, False):
            if not is_insertion and index == len(base):
                continue
            choices = [inserts.get(index, "") if is_insertion else values[index]
                       for values, inserts in projections]
            value, votes = Counter(choices).most_common(1)[0]
            if votes < 2:
                value = choices[anchor]
                unresolved = True
            if value or votes < len(rows):
                support = min(support, votes)
            if value:
                result.append(value)
    text = join_tokens(result)
    unresolved |= any(omits_words(text, reading) for reading in readings)
    return text, support, unresolved


def consensus(lines: list[Line]) -> dict:
    tsv = ["level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext"]
    evidence = {}
    for group in align_rows(lines):
        text, agreement, review = vote(group)
        if not text:
            continue
        index = len(evidence) + 1
        bounds = joined_line("consensus", group)
        text = text.replace("\t", " ").replace("\n", " ")
        tsv.append("\t".join(map(str, [5, 1, 1, 1, index, 1, round(bounds.left),
                                      round(bounds.top), round(bounds.width), round(bounds.height),
                                      agreement * 100 / 3, text])))
        evidence[str(index)] = {"agreement": agreement, "reviewRequired": review,
                                "alternatives": [{"engine": row.engine, "text": row.text}
                                                 for row in sorted(group, key=lambda row: ENGINES.index(row.engine))]}
    return {"schemaVersion": 1, "engines": list(ENGINES), "tsv": "\n".join(tsv) + "\n",
            "lineEvidence": evidence}


class InvalidImageError(Exception):
    """Input rejected before any OCR engine is loaded."""


@contextlib.contextmanager
def normalized_input(path: str):
    if Path(path).suffix.lower() not in {".heic", ".heif"}:
        yield path
        return
    from PIL import Image, ImageOps
    from pillow_heif import register_heif_opener
    register_heif_opener(thumbnails=False)
    # Java owns this private job directory and removes it even after a timeout.
    with tempfile.TemporaryDirectory(prefix="normalized-", dir=Path(path).parent) as directory:
        converted = str(Path(directory) / "image.png")
        try:
            with Image.open(path) as source:
                if source.format != "HEIF" or not (0 < source.width * source.height <= 20_000_000):
                    raise ValueError("Unsupported image or dimensions")
                with ImageOps.exif_transpose(source).convert("RGB") as image:
                    image.info.clear()
                    image.save(converted, "PNG", compress_level=1)
        except (OSError, ValueError, EOFError, Image.DecompressionBombError) as error:
            raise InvalidImageError() from error
        yield converted


def _stop_workers(workers: dict[str, subprocess.Popen]) -> None:
    def group_alive(process: subprocess.Popen) -> bool:
        if os.name != "posix":
            return process.poll() is None
        try:
            os.killpg(process.pid, 0)
            return True
        except ProcessLookupError:
            return False

    def signal_worker(process: subprocess.Popen, sig: int) -> None:
        try:
            if os.name == "posix":
                os.killpg(process.pid, sig)
            elif process.poll() is None:
                process.terminate() if sig == signal.SIGTERM else process.kill()
        except ProcessLookupError:
            pass

    for process in workers.values():
        signal_worker(process, signal.SIGTERM)

    stop_deadline = time.monotonic() + ENGINE_WORKER_CLEANUP_SECONDS
    for process in workers.values():
        try:
            process.wait(timeout=max(0, stop_deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            pass
    while os.name == "posix" and time.monotonic() < stop_deadline and any(group_alive(process) for process in workers.values()):
        time.sleep(min(0.05, max(0, stop_deadline - time.monotonic())))
    for process in workers.values():
        if os.name == "posix":
            signal_worker(process, signal.SIGKILL)
        elif process.poll() is None:
            process.kill()
    reap_deadline = time.monotonic() + 1
    for process in workers.values():
        if process.poll() is None:
            with contextlib.suppress(subprocess.TimeoutExpired):
                process.wait(timeout=max(0, reap_deadline - time.monotonic()))


def _run_engine_workers(commands: dict[str, list[str]], timeout: float = ENGINE_WORKER_DEADLINE_SECONDS):
    if set(commands) != set(ENGINES):
        raise ValueError("Expected one command per OCR engine")
    if timeout <= 0:
        raise OcrTimeout("shared_deadline", ())

    workers: dict[str, subprocess.Popen] = {}
    results = {}
    deadline = time.monotonic() + timeout
    with tempfile.TemporaryDirectory(prefix="ocr-workers-") as directory:
        result_paths = {engine: Path(directory) / f"{engine}.json" for engine in ENGINES}
        try:
            for engine in ENGINES:
                workers[engine] = subprocess.Popen(
                    [*commands[engine], str(result_paths[engine])],
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    start_new_session=os.name == "posix",
                )

            for engine in ENGINES:
                process = workers[engine]
                try:
                    process.wait(timeout=max(0, deadline - time.monotonic()))
                except subprocess.TimeoutExpired:
                    active_engines = tuple(
                        name for name in ENGINES if workers[name].poll() is None
                    )
                    raise OcrTimeout("shared_deadline", active_engines) from None
                if process.returncode != 0:
                    raise RuntimeError(f"{engine} worker failed with exit status {process.returncode}")

                payload = json.loads(result_paths[engine].read_text())
                if not isinstance(payload, dict) or payload.get("engine") != engine:
                    raise ValueError(f"Invalid response from {engine} worker")
                if payload.get("failure") == "timeout":
                    if set(payload) != {"engine", "failure"}:
                        raise ValueError(f"Invalid timeout response from {engine} worker")
                    raise OcrTimeout("engine", (engine,))
                if "failure" in payload:
                    raise ValueError(f"Invalid response from {engine} worker")
                raw_lines = payload.get("lines")
                elapsed = payload.get("engineMilliseconds")
                if not isinstance(raw_lines, list) or not isinstance(elapsed, int) or elapsed < 0:
                    raise ValueError(f"Invalid response from {engine} worker")
                lines = []
                for raw_line in raw_lines:
                    if not isinstance(raw_line, dict) or raw_line.get("engine") != engine:
                        raise ValueError(f"Invalid line from {engine} worker")
                    lines.append(Line(**raw_line))
                results[engine] = (lines, elapsed)
                if time.monotonic() > deadline:
                    active_engines = tuple(
                        name for name in ENGINES if workers[name].poll() is None
                    )
                    raise OcrTimeout("shared_deadline", active_engines)
            return results
        except BaseException:
            _stop_workers(workers)
            raise


def _run_engine_worker(engine: str, path: str, manifest_path: str, result_path: str) -> None:
    if engine not in ENGINES:
        raise ValueError("Unknown OCR engine")
    manifest = json.loads(Path(manifest_path).read_text())
    recognize = {
        "tesseract": lambda: tesseract_lines(path),
        "paddleocr": lambda: paddle_lines(path, manifest),
        "easyocr": lambda: easy_lines(path, manifest),
    }[engine]
    before = time.monotonic()
    try:
        with contextlib.redirect_stdout(sys.stderr):
            lines = recognize()
    except subprocess.TimeoutExpired:
        # Timeout arguments can contain captured OCR output; write only a fixed safe marker.
        Path(result_path).write_text(json.dumps({"engine": engine, "failure": "timeout"}))
        return
    Path(result_path).write_text(json.dumps({
        "engine": engine,
        "engineMilliseconds": round((time.monotonic() - before) * 1000),
        "lines": [line.__dict__ for line in lines],
    }, ensure_ascii=False))


def main() -> None:
    deadline = time.monotonic() + ENGINE_WORKER_DEADLINE_SECONDS
    if len(sys.argv) == 6 and sys.argv[1] == "--engine":
        _run_engine_worker(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
        return
    if len(sys.argv) < 3 or sys.argv[2] != "stdout":
        raise ValueError("Expected image path and stdout destination")
    original_path = str(Path(sys.argv[1]).resolve(strict=True))
    manifest_path = Path(os.environ.get("OCR_MODEL_MANIFEST", "/app/ocr/models.json"))
    start = time.monotonic()
    lines = []
    timings = {}
    # Model runtimes sometimes print diagnostics. Keep stdout exclusively the JSON protocol.
    with contextlib.redirect_stdout(sys.stderr), normalized_input(original_path) as path:
        commands = {
            engine: [sys.executable, str(Path(__file__).resolve()), "--engine", engine, path, str(manifest_path)]
            for engine in ENGINES
        }
        worker_results = _run_engine_workers(commands, timeout=deadline - time.monotonic())
        for name in ENGINES:
            lines.extend(worker_results[name][0])
            timings[name] = worker_results[name][1]
    result = consensus(lines)
    result["engineMilliseconds"] = timings
    result["elapsedMilliseconds"] = round((time.monotonic() - start) * 1000)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except OcrTimeout as error:
        print(error.safe_message(), file=sys.stderr)
        sys.exit(2)
    except InvalidImageError:
        print("Image decoding failed or image exceeds pixel limit.", file=sys.stderr)
        sys.exit(65)
    except Exception as error:
        # Do not include OCR text or image bytes in application logs.
        print(f"Consensus OCR failed: {type(error).__name__}", file=sys.stderr)
        sys.exit(2)
