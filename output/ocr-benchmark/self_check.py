#!/usr/bin/env python3
"""Synthetic scorer and acceptance checks; does not run OCR or read fixtures."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path

import benchmark


def score_case(
    expected: list[str],
    observed: list[str],
    *,
    action: str = "accept",
    review: bool = False,
    metadata: bool = True,
    alternatives_by_row: list[list[str]] | None = None,
) -> dict:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        manifest = root / "manifest.json"
        runner = root / "runner.json"
        manifest.write_text(
            json.dumps({"cases": [{"id": "synthetic", "action": action, "expectedWarnings": []}]}),
            encoding="utf-8",
        )
        candidates = []
        for index, text in enumerate(observed, 1):
            candidate = {"text": text, "normalized": text, "lineIndex": index}
            if metadata:
                engines = alternatives_by_row[index - 1] if alternatives_by_row else benchmark.OCR_ENGINES
                candidate.update(
                    {
                        "ocrAgreement": len(engines),
                        "ocrReviewRequired": review or len(engines) == 1,
                        "ocrAlternatives": [f"line {index} {engine}: {text}" for engine in engines],
                    }
                )
            candidates.append(candidate)
        runner.write_text(
            json.dumps(
                {
                    "schemaVersion": 2,
                    "ocrStrictMode": True,
                    "engineContract": {"schemaVersion": 1, "engines": list(benchmark.OCR_ENGINES)},
                    "cases": [{"id": "synthetic", "candidates": candidates, "elapsedMs": 1}],
                }
            ),
            encoding="utf-8",
        )
        report, _ = benchmark.score(manifest, runner, {"synthetic": expected})
        return report


def assert_failure(report: dict, phrase: str) -> None:
    failures = benchmark.strict_acceptance_failures(report)
    assert any(phrase in failure for failure in failures), failures


def main() -> None:
    address_with_unit = "123 MAIN ST, SOUTHFIELD, APT 4"
    base = score_case([address_with_unit], [address_with_unit])
    assert benchmark.strict_acceptance_failures(base) == []

    assert_failure(score_case([address_with_unit], [address_with_unit], action="review"), "marked for review")
    assert_failure(score_case([address_with_unit], [address_with_unit], metadata=False), "metadata is unavailable")

    row_mismatch = score_case([address_with_unit], [])
    assert_failure(row_mismatch, "ordered-row mismatches")

    missing_unit = score_case([address_with_unit, address_with_unit], [address_with_unit])
    assert missing_unit["metrics"]["units"]["falseNegative"] == 1
    assert missing_unit["metrics"]["duplicates"]["falseNegative"] == 1
    assert_failure(missing_unit, "units require zero")
    assert_failure(missing_unit, "duplicates require zero")

    extra_unit = score_case(["123 MAIN ST, SOUTHFIELD"], [address_with_unit])
    assert extra_unit["metrics"]["units"]["falsePositive"] == 1
    assert_failure(extra_unit, "units require zero")

    extra_repeat = score_case([address_with_unit], [address_with_unit, address_with_unit])
    assert extra_repeat["metrics"]["duplicates"]["falsePositive"] == 1
    assert_failure(extra_repeat, "duplicates require zero")

    no_engine_evidence = score_case([address_with_unit], [address_with_unit])
    no_engine_evidence["metrics"]["ocrConsensus"]["engineEvidence"]["easyocr"]["lineAlternatives"] = 0
    assert_failure(no_engine_evidence, "easyocr")

    partial_engine_evidence = score_case(
        [address_with_unit, "456 OAK ST, DETROIT"],
        [address_with_unit, "456 OAK ST, DETROIT"],
        alternatives_by_row=[["tesseract", "paddleocr"], ["easyocr"]],
    )
    assert benchmark.strict_acceptance_failures(partial_engine_evidence) == []

    negative_only = score_case([], [])
    assert benchmark.strict_acceptance_failures(negative_only) == []

    runtime_errors = benchmark.regressions({"errors": ["synthetic: RuntimeException"]}, None, 1.5)
    assert any("OCR runtime error" in failure for failure in runtime_errors), runtime_errors

    review_ok = score_case([address_with_unit], [address_with_unit], action="review", review=True)
    assert benchmark.strict_acceptance_failures(review_ok) == []
    print("OCR benchmark acceptance self-check passed")


if __name__ == "__main__":
    main()
