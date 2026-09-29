#!/usr/bin/env python3
"""Run the OCR benchmark inside the final application image and score it."""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import uuid
from pathlib import Path
from types import ModuleType


ROOT = Path(__file__).resolve().parents[2]
BENCH = ROOT / "output/ocr-benchmark"
RUNNER_CLASS = "com/jtdev/routelisttotesla/OcrBenchmarkRunner.class"
RUN_LABEL = "org.openai.codex.ocr-benchmark-run"
CONTAINER_BENCH = "/workspace/output/ocr-benchmark"
CONTAINER_RESOURCES = "/workspace/src/test/resources"
CONTAINER_TEST_CLASSES = "/test-classes"
CONTAINER_OUTPUT = "/app/cache/runner.json"


def repository_file(value: Path, label: str, *, within_benchmark: bool = False) -> Path:
    candidate = value if value.is_absolute() else ROOT / value
    try:
        resolved = candidate.resolve(strict=True)
    except FileNotFoundError as exc:
        raise SystemExit(f"{label} does not exist: {candidate}") from exc
    if not resolved.is_file():
        raise SystemExit(f"{label} is not a file: {resolved}")
    if within_benchmark and not resolved.is_relative_to(BENCH.resolve()):
        raise SystemExit(f"{label} must be inside {BENCH}")
    return resolved


def container_benchmark_path(host_path: Path) -> str:
    relative = host_path.relative_to(BENCH.resolve()).as_posix()
    return f"{CONTAINER_BENCH}/{relative}"


def load_scorer() -> ModuleType:
    scorer_path = BENCH / "benchmark.py"
    spec = importlib.util.spec_from_file_location("ocr_benchmark_scorer", scorer_path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"Cannot load existing benchmark scorer: {scorer_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def docker_command(
    docker: str,
    image: str,
    manifest: str,
    test_classes: Path,
    container_name: str,
    volume_name: str,
    run_id: str,
    tesseract: str,
) -> list[str]:
    return [
        docker,
        "run",
        f"--name={container_name}",
        "--label",
        f"{RUN_LABEL}={run_id}",
        "--platform=linux/amd64",
        "--network=none",
        "--user=10001:10001",
        "--read-only",
        "--tmpfs",
        "/tmp:rw,nosuid,nodev,noexec,size=128m,mode=1777",
        "--cpus=2",
        "--memory=4g",
        "--mount",
        f"type=bind,source={BENCH.resolve()},target={CONTAINER_BENCH},readonly",
        "--mount",
        f"type=bind,source={(ROOT / 'src/test/resources').resolve()},target={CONTAINER_RESOURCES},readonly",
        "--mount",
        f"type=bind,source={test_classes},target={CONTAINER_TEST_CLASSES},readonly",
        "--mount",
        f"type=volume,source={volume_name},target=/app/cache",
        "--entrypoint=/bin/sh",
        image,
        "-c",
        'java "$@"; result=$?; printf "OCR_MEMORY_PEAK_BYTES="; '
        'cat /sys/fs/cgroup/memory.peak 2>/dev/null || true; exit "$result"',
        "ocr-benchmark",
        f"-Dloader.path={CONTAINER_TEST_CLASSES}",
        "-Dloader.main=com.jtdev.routelisttotesla.OcrBenchmarkRunner",
        f"-Docr.benchmark.manifest={manifest}",
        f"-Docr.benchmark.output={CONTAINER_OUTPUT}",
        f"-Docr.tesseract.executable={tesseract}",
        "-Docr.language=eng",
        "-Djava.io.tmpdir=/tmp",
        "-cp",
        "/app/app.jar",
        "org.springframework.boot.loader.launch.PropertiesLauncher",
    ]


def has_run_label(docker: str, object_type: str, name: str, run_id: str) -> bool:
    field = ".Labels" if object_type == "volume" else ".Config.Labels"
    template = f'{{{{ index {field} "{RUN_LABEL}" }}}}'
    inspected = subprocess.run(
        [docker, object_type, "inspect", "--format", template, name],
        capture_output=True,
        text=True,
        check=False,
    )
    return inspected.returncode == 0 and inspected.stdout.strip() == run_id


def resolve_local_image_id(docker: str, image_reference: str) -> str:
    inspected = subprocess.run(
        [docker, "image", "inspect", "--format", "{{.Id}}", image_reference],
        capture_output=True,
        text=True,
        check=False,
    )
    image_id = inspected.stdout.strip()
    if inspected.returncode != 0 or not re.fullmatch(r"sha256:[0-9a-f]{64}", image_id):
        detail = inspected.stderr.strip() or "Docker returned an invalid image ID"
        raise SystemExit(f"Could not resolve local immutable image ID for {image_reference}: {detail}")
    return image_id


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, help="final application image tag or digest")
    parser.add_argument(
        "--manifest",
        required=True,
        type=Path,
        help="manifest under output/ocr-benchmark/manifests (original, train, or held-out)",
    )
    parser.add_argument(
        "--expected",
        type=Path,
        help="separate expected rows JSON under output/ocr-benchmark; required for held-out suites",
    )
    parser.add_argument(
        "--tesseract",
        default="/app/ocr/run",
        help="production-strict three-engine wrapper executable inside the image",
    )
    parser.add_argument(
        "--report",
        required=True,
        type=Path,
        help="new host report path; raw runner JSON and container log are written alongside it",
    )
    parser.add_argument(
        "--baseline",
        type=Path,
        help="optional prior report for the existing benchmark regression checks",
    )
    parser.add_argument("--runtime-factor", type=float, default=1.5)
    parser.add_argument(
        "--diagnostic",
        action="store_true",
        help="write metrics without strict benchmark acceptance checks; runtime errors still fail",
    )
    parser.add_argument(
        "--fail-on-mismatch",
        action="store_true",
        help="also fail ordered-row mismatches in diagnostic mode (strict mode is the default)",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not args.image.strip():
        raise SystemExit("--image must not be empty")

    manifest = repository_file(args.manifest, "manifest", within_benchmark=True)
    expected = (
        repository_file(args.expected, "expected rows", within_benchmark=True)
        if args.expected
        else None
    )
    baseline = repository_file(args.baseline, "baseline") if args.baseline else None
    test_classes = ROOT / "target/test-classes"
    runner_class = test_classes / RUNNER_CLASS
    if not test_classes.is_dir() or not runner_class.is_file():
        raise SystemExit(
            "Missing target/test-classes OCR runner. Build a clean test-classes directory "
            "before running this helper."
        )
    runner_source = ROOT / "src/test/java/com/jtdev/routelisttotesla/OcrBenchmarkRunner.java"
    if "public class OcrBenchmarkRunner" not in runner_source.read_text(encoding="utf-8"):
        raise SystemExit(
            "OcrBenchmarkRunner must be a public class for reflective PropertiesLauncher access."
        )

    report_path = args.report if args.report.is_absolute() else ROOT / args.report
    report_path = report_path.resolve()
    raw_path = report_path.with_suffix(".runner.json")
    log_path = report_path.with_suffix(".container.log")
    if report_path.exists() or raw_path.exists() or log_path.exists():
        raise SystemExit(f"Refusing to overwrite existing benchmark output near: {report_path}")
    report_path.parent.mkdir(parents=True, exist_ok=True)

    docker = shutil.which("docker")
    if docker is None:
        raise SystemExit("Docker CLI is required to run the final-image OCR benchmark.")
    image_id = resolve_local_image_id(docker, args.image)
    run_id = uuid.uuid4().hex
    short_id = run_id[:16]
    container_name = f"ocr-final-image-{short_id}"
    volume_name = f"ocr-final-image-{short_id}"
    container_manifest = container_benchmark_path(manifest)
    command = docker_command(
        docker,
        image_id,
        container_manifest,
        test_classes.resolve(),
        container_name,
        volume_name,
        run_id,
        args.tesseract,
    )

    print("Running final-image OCR runner:", args.image, "=>", image_id, file=sys.stderr)
    print("Container manifest:", container_manifest, file=sys.stderr)
    print("Raw runner JSON:", raw_path, file=sys.stderr)
    print("Container log:", log_path, file=sys.stderr)

    old_umask = os.umask(0o077)
    volume_created = False
    try:
        with log_path.open("xb") as logs:
            volume_result = subprocess.run(
                [docker, "volume", "create", "--label", f"{RUN_LABEL}={run_id}", volume_name],
                stdout=logs,
                stderr=subprocess.STDOUT,
                check=False,
            )
            if volume_result.returncode != 0 or not has_run_label(
                docker, "volume", volume_name, run_id
            ):
                raise SystemExit(f"Could not create an owned temporary output volume; see {log_path}")
            volume_created = True

            completed = subprocess.run(command, stdout=logs, stderr=subprocess.STDOUT, check=False)
            if not has_run_label(docker, "container", container_name, run_id):
                raise SystemExit(f"Final-image container was not created; see {log_path}")

            copied = subprocess.run(
                [docker, "cp", f"{container_name}:{CONTAINER_OUTPUT}", str(raw_path)],
                stdout=logs,
                stderr=subprocess.STDOUT,
                check=False,
            )
            if copied.returncode == 0:
                os.chmod(raw_path, 0o600)
            if completed.returncode != 0:
                raise SystemExit(
                    f"Final-image OCR runner exited {completed.returncode}; see {log_path}"
                )
            if copied.returncode != 0:
                raise SystemExit(f"Could not copy raw runner JSON; see {log_path}")
    finally:
        try:
            if has_run_label(docker, "container", container_name, run_id):
                with log_path.open("ab") as logs:
                    removed = subprocess.run(
                        [docker, "rm", "--force", container_name],
                        stdout=logs,
                        stderr=subprocess.STDOUT,
                        check=False,
                    )
                    if removed.returncode != 0:
                        print(f"Could not remove temporary container {container_name}; see {log_path}", file=sys.stderr)
            if volume_created and has_run_label(docker, "volume", volume_name, run_id):
                with log_path.open("ab") as logs:
                    removed = subprocess.run(
                        [docker, "volume", "rm", volume_name],
                        stdout=logs,
                        stderr=subprocess.STDOUT,
                        check=False,
                    )
                    if removed.returncode != 0:
                        print(f"Could not remove temporary volume {volume_name}; see {log_path}", file=sys.stderr)
        finally:
            os.umask(old_umask)

    scorer = load_scorer()
    try:
        report, _ = scorer.score(manifest, raw_path, scorer.read_truth(expected))
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        raise SystemExit(f"Could not score runner output; raw output retained at {raw_path}: {exc}") from exc

    report["manifest"] = str(manifest)
    report["runnerReport"] = str(raw_path)
    report["image"] = {"requestedReference": args.image, "resolvedId": image_id}
    report["acceptanceMode"] = "diagnostic" if args.diagnostic else "strict"
    if args.diagnostic:
        failed = [f"OCR runtime error: {error}" for error in report.get("errors") or []]
        if args.fail_on_mismatch:
            failed.extend(scorer.ordered_row_mismatch_failures(report))
    else:
        failed = scorer.regressions(report, baseline, args.runtime_factor)
        failed.extend(scorer.strict_acceptance_failures(report))
    report["regressions"] = failed
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    metrics = report["metrics"]
    rows = metrics["rows"]
    print(
        f"cases={metrics['caseCount']} exactOrdered={metrics['exactOrderedCases']}/{metrics['caseCount']} "
        f"rows={rows['truePositive']} TP/{rows['falsePositive']} FP/{rows['falseNegative']} FN "
        f"precision={rows['precision']:.3f} recall={rows['recall']:.3f}"
    )
    print("report:", report_path)
    if report["errors"]:
        for error in report["errors"]:
            print("error", error)
    for failure in failed:
        print("REGRESSION:", failure)
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
