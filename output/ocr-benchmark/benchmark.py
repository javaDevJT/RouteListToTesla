#!/usr/bin/env python3
"""Generate deterministic itinerary screenshots and score the Java OCR runner."""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import statistics
import subprocess
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BENCH = ROOT / "output/ocr-benchmark"
TRAIN_IMAGES = BENCH / "fixtures/train"
MANIFEST = BENCH / "manifests/train.json"
EXPECTED = BENCH / "training-expected.json"
SPEC = BENCH / "train-spec.json"
RUNNER = ROOT / "src/test/java/com/jtdev/routelisttotesla/OcrBenchmarkRunner.java"
JAVA_SOURCES = [
    ROOT / "src/main/java/com/jtdev/routelisttotesla/model/PlaceCandidate.java",
    ROOT / "src/main/java/com/jtdev/routelisttotesla/service/AddressOcrService.java",
    ROOT / "src/main/java/com/jtdev/routelisttotesla/util/AddressExtractor.java",
    RUNNER,
]
UNIT = __import__("re").compile(r"\b(?:APT|APARTMENT|UNIT|STE|SUITE|BLDG|BUILDING|FLOOR|ROOM|RM|DEPT)\b|#\s*\w+", __import__("re").I)
OCR_ENGINES = ("tesseract", "paddleocr", "easyocr")


def row(street: str, city: str = "SOUTHFIELD", postal: str = "", packages: int = 1) -> dict:
    return {"street": street, "city": city, "postal": postal, "packages": packages}


def distractor(meta: str, delivery: str = "Deliver 1 package") -> dict:
    return {"distractor": meta, "delivery": delivery, "packages": 0}


def visible_text(item: dict) -> str | None:
    if not item.get("street") or not item.get("city"):
        return None
    parts = [item["street"], item["city"]]
    if item.get("postal"):
        parts.append(item["postal"])
    return ", ".join(parts)


def training_cases() -> list[dict]:
    cases: list[dict] = []

    def family(
        name: str,
        rows: list[dict],
        variant: dict,
        *,
        action: str = "accept",
        warnings: tuple[str, ...] = (),
        truth_indices: list[int] | None = None,
        default_state: str = "",
        base_profile: dict | None = None,
    ) -> None:
        base = {
            "width": 600,
            "height": 800,
            "theme": "dark",
            "fontScale": 1.0,
            "textTransform": "uppercase",
            "contrast": 1.0,
            "brightness": 1.0,
            "blur": 0,
            "jpegQuality": 0,
            "scale": 1.0,
            "scrollTop": 0,
        }
        if base_profile:
            base.update(base_profile)
        indices = truth_indices if truth_indices is not None else list(range(len(rows)))
        expected = [visible_text(rows[i]) for i in indices]
        expected = [value for value in expected if value is not None]
        for suffix, profile in (("base", base), ("variant", {**base, **variant})):
            case_id = f"{name}-{suffix}"
            cases.append(
                {
                    "id": case_id,
                    "family": name,
                    "layoutFamily": "itinerary-list",
                    "image": f"../fixtures/train/{case_id}.png",
                    "defaultState": default_state,
                    "action": action,
                    "expectedWarnings": list(warnings),
                    "rows": rows,
                    "expectedRows": expected,
                    "profile": profile,
                }
            )

    family(
        "single-digit-directional",
        [
            row("1 N MAIN ST", "DETROIT"),
            row("4 W 8 MILE RD"),
            row("28590 EVERGREEN RD"),
            row("74 W 9 MILE RD", "OAK PARK"),
        ],
        {"theme": "light", "fontScale": 1.08},
    )
    family(
        "fractional-house-number",
        [
            row("25 1/2 OAKWOOD AVE", "ROYAL OAK"),
            row("5½ W GRAND BLVD", "DETROIT"),
            row("51 3/4 HARBOR ST"),
        ],
        {"jpegQuality": 48},
    )
    family(
        "hyphenated-house-range",
        [
            row("100-104 W MAPLE RD", "BIRMINGHAM"),
            row("7-9 E MADISON ST", "DETROIT"),
            row("88-92 LINCOLN AVE", "FERNDALE"),
        ],
        {"contrast": 0.72, "brightness": 0.88},
    )
    family(
        "highway-ramp",
        [
            row("178 I-75 SERVICE DR", "DETROIT"),
            row("501 M-10 NORTHBOUND RAMP", "SOUTHFIELD"),
            row("622 US-24 ACCESS RD", "TROY"),
        ],
        {"width": 380, "fontScale": 1.0},
    )
    family(
        "mile-road-directionals",
        [
            row("58 W 12 MILE RD", "SOUTHFIELD"),
            row("213 E 9 MILE RD", "OAK PARK"),
            row("1940 W 14 MILE RD", "ROYAL OAK"),
        ],
        {"scale": 0.72, "jpegQuality": 72},
    )
    family(
        "prefix-directionals",
        [
            row("3900 N ELM ST", "DETROIT"),
            row("805 S OAK AVE", "PONTIAC"),
            row("440 E MAIN ST", "NORTHVILLE"),
        ],
        {"theme": "light", "contrast": 1.45},
    )
    family(
        "suffix-directionals",
        [
            row("700 GRAND RIVER AVE W", "NOVI"),
            row("422 RIVER RD NE", "FLINT"),
            row("49 PARK AVE S", "ROYAL OAK"),
        ],
        {"blur": 0.55},
    )
    family(
        "unit-labels-unit",
        [
            row("9145 HAYES RD UNIT 4", "SOUTHFIELD"),
            row("58 W 12 MILE RD UNIT 12", "SOUTHFIELD"),
            row("312 HARBOR ST UNIT B", "DETROIT"),
        ],
        {"jpegQuality": 55},
    )
    family(
        "unit-labels-apartment",
        [
            row("1660 WOODWARD AVE APT 12B", "DETROIT"),
            row("724 RIVARD ST APT 3", "DETROIT"),
            row("41 OAK ST APARTMENT 7", "FERNDALE"),
        ],
        {"width": 390, "fontScale": 1.04},
    )
    family(
        "unit-labels-suite",
        [
            row("28450 FRANKLIN RD STE 210", "SOUTHFIELD"),
            row("300 MAIN ST SUITE 5", "ROYAL OAK"),
            row("900 WOODWARD AVE STE 1200", "DETROIT"),
        ],
        {"fontScale": 1.14},
    )
    family(
        "unit-symbol-and-floor",
        [
            row("725 RIVARD ST #3", "DETROIT"),
            row("112 W HURON ST FL 2", "PONTIAC"),
            row("98 OAK AVE # 14", "BIRMINGHAM"),
        ],
        {"theme": "light", "scale": 0.86},
    )
    family(
        "unicode-apostrophes",
        [
            row("41 O’BRIEN BLVD", "DETROIT"),
            row("18 D’ARCADIA ST", "ROYAL OAK"),
            row("205 O'NEIL AVE", "FERNDALE"),
        ],
        {"fontScale": 1.08, "contrast": 1.25},
    )
    family(
        "neighbor-cities",
        [
            row("23205 SUTTON DR", "SOUTHFIELD"),
            row("18622 HESSEL AVE", "DETROIT"),
            row("901 MAIN ST", "ROYAL OAK"),
            row("73 MAPLE RD", "OAK PARK"),
        ],
        {"theme": "light", "textTransform": "none"},
    )
    family(
        "zip-inline",
        [
            row("26000 GREENFIELD RD", "OAK PARK, MI 48237"),
            row("501 MAIN ST", "ROYAL OAK, MI 48067"),
            row("12 MAPLE AVE", "DETROIT, MI 48201"),
        ],
        {"width": 390},
        default_state="MI",
    )
    family(
        "zip-neighbor-line",
        [
            row("3150 COOLIDGE HWY", "ROYAL OAK", "MI 48073"),
            row("88 PARK AVE", "FERNDALE", "MI 48220"),
            row("101 MAIN ST", "DETROIT", "MI 48201"),
        ],
        {"width": 370, "fontScale": 1.08},
        default_state="MI",
    )
    family(
        "mixed-case-abbreviations",
        [
            row("14020 Bramell", "Detroit"),
            row("12924 Ashton Rd", "Detroit"),
            row("16784 Blackstone St", "Detroit"),
        ],
        {"theme": "light", "textTransform": "none"},
    )
    family(
        "distractor-numerals",
        [
            distractor("# B.L16.OV • Scheduled 6:00 - 11:00 AM"),
            row("7825 CITY PARK DR", "DETROIT", packages=2),
            distractor("Deliver 1 package"),
            row("41 OAK ST", "FERNDALE"),
            distractor("Route 6 • 3 stops remaining"),
        ],
        {"contrast": 1.35, "fontScale": 0.96},
    )
    family(
        "duplicate-order",
        [
            row("1450 WALNUT ST", "DETROIT"),
            row("1388 MAPLE CT", "DETROIT"),
            row("1450 WALNUT ST", "DETROIT"),
            row("901 OAK DR", "DETROIT"),
        ],
        {"jpegQuality": 58},
    )
    family(
        "repeated-route-rows",
        [
            row("16784 BLACKSTONE ST", "DETROIT"),
            row("16574 BLACKSTONE ST", "DETROIT"),
            row("15907 BURT RD", "DETROIT"),
            row("16574 BLACKSTONE ST", "DETROIT"),
        ],
        {"blur": 0.38, "theme": "light"},
    )
    family(
        "wrapped-long-unit",
        [
            row("11237 WEST BLOOMFIELD LAKE ROAD SUITE 204", "WEST BLOOMFIELD"),
            row("4175 GRAND RIVER AVENUE APARTMENT 12B", "NOVI"),
            row("6100 TELEGRAPH ROAD UNIT 340", "BLOOMFIELD HILLS"),
        ],
        {"width": 360, "fontScale": 1.05},
    )
    family(
        "crop-top-review",
        [
            row("8119 PARTIAL VIEW RD", "DETROIT"),
            row("42 COMPLETE OAK ST", "DETROIT"),
            row("108 COMPLETE MAPLE AVE", "DETROIT"),
            row("6 COMPLETE PINE DR", "DETROIT"),
        ],
        {"scrollTop": 74},
        action="review",
        warnings=("top-row-cropped-incomplete",),
        truth_indices=[1, 2, 3],
        base_profile={"scrollTop": 58},
    )
    family(
        "crop-bottom-review",
        [
            row("21 COMPLETE OAK ST", "DETROIT"),
            row("92 COMPLETE MAPLE AVE", "DETROIT"),
            row("6 COMPLETE PINE DR", "DETROIT"),
            row("901 PARTIAL BOTTOM RD", "DETROIT"),
        ],
        {"height": 590},
        action="review",
        warnings=("bottom-row-cropped-incomplete",),
        truth_indices=[0, 1, 2],
        base_profile={"height": 620},
    )
    family(
        "reject-distractors-only",
        [
            distractor("# 18 • Scheduled 6:00 - 11:00 AM"),
            distractor("Deliver 2 packages"),
            distractor("Route 12 • Stop 4"),
        ],
        {"theme": "light", "fontScale": 1.12},
        action="reject",
        warnings=("no-complete-address",),
        truth_indices=[],
    )
    family(
        "compact-small-type",
        [
            row("7 S CEDAR LN", "DETROIT"),
            row("29 W MAPLE ST", "OAK PARK"),
            row("5 N PINE RD", "FERNDALE"),
        ],
        {"width": 420, "fontScale": 1.2, "theme": "light"},
    )
    return cases


def normalize(value: str) -> str:
    apostrophes = str.maketrans({"‘": "'", "’": "'"})
    return " ".join(value.translate(apostrophes).upper().split())


def run(command: list[str], cwd: Path) -> None:
    print("+", " ".join(str(part) for part in command))
    subprocess.run(command, cwd=cwd, check=True)


def java_binary(name: str) -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / name
        if candidate.exists():
            return str(candidate)
    found = shutil.which(name)
    if not found:
        raise SystemExit(f"{name} is required")
    return found


def apply_image_effects(case: dict) -> None:
    from PIL import Image, ImageEnhance, ImageFilter

    image_path = BENCH / case["image"].replace("../fixtures/", "fixtures/")
    with Image.open(image_path) as source:
        image = source.convert("RGB")
    profile = case["profile"]
    crop_top = int(profile.get("cropTopPx", 0))
    crop_bottom = int(profile.get("cropBottomPx", 0))
    if crop_top or crop_bottom:
        image = image.crop((0, crop_top, image.width, image.height - crop_bottom))
    scale = float(profile.get("scale", 1.0))
    if scale != 1.0:
        resample = getattr(Image, "Resampling", Image).LANCZOS
        image = image.resize((max(1, round(image.width * scale)), max(1, round(image.height * scale))), resample)
    contrast = float(profile.get("contrast", 1.0))
    brightness = float(profile.get("brightness", 1.0))
    blur = float(profile.get("blur", 0))
    if contrast != 1.0:
        image = ImageEnhance.Contrast(image).enhance(contrast)
    if brightness != 1.0:
        image = ImageEnhance.Brightness(image).enhance(brightness)
    if blur:
        image = image.filter(ImageFilter.GaussianBlur(blur))
    quality = int(profile.get("jpegQuality", 0))
    if quality:
        from io import BytesIO
        buffer = BytesIO()
        image.save(buffer, format="JPEG", quality=quality, optimize=False)
        buffer.seek(0)
        image = Image.open(buffer).convert("RGB")
    image.save(image_path, format="PNG", optimize=False)


def write_index(cases: list[dict]) -> None:
    cards = []
    for case in cases:
        rows = "<br>".join(case["expectedRows"]) or "(no complete address expected)"
        warnings = ", ".join(case["expectedWarnings"]) or "none"
        cards.append(
            "<article><h2>{}</h2><p>Family: {} · Action: {} · Warnings: {}</p>"
            "<img src=\"train/{}.png\" alt=\"{}\">"
            "<p class=\"truth\">{}</p></article>".format(
                case["id"], case["family"], case["action"], warnings,
                case["id"], case["id"], rows
            )
        )
    page = (
        "<!doctype html><meta charset=\"utf-8\"><title>OCR training fixtures</title>"
        "<style>body{font:16px system-ui;background:#eef;color:#17202a;margin:24px}"
        ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:20px}"
        "article{background:white;padding:14px;border-radius:8px}img{width:100%;height:auto}"
        ".truth{font-family:monospace;line-height:1.5}</style>"
        f"<h1>OCR training fixtures ({len(cases)} cases)</h1>"
        "<p>Synthetic training cases generated from train-spec.json.</p>"
        "<main class=\"grid\">" + "".join(cards) + "</main>"
    )
    (BENCH / "fixtures/index.html").write_text(page, encoding="utf-8")


def generate() -> None:
    cases = json.loads(SPEC.read_text(encoding="utf-8"))["cases"]
    TRAIN_IMAGES.mkdir(parents=True, exist_ok=True)
    (BENCH / "fixtures").mkdir(parents=True, exist_ok=True)
    (MANIFEST.parent).mkdir(parents=True, exist_ok=True)
    SPEC.write_text(json.dumps({"schemaVersion": 1, "cases": cases}, indent=2) + "\n", encoding="utf-8")
    expected = {case["id"]: case["expectedRows"] for case in cases}
    EXPECTED.write_text(json.dumps(expected, indent=2) + "\n", encoding="utf-8")
    manifest_cases = [
        {
            "id": case["id"],
            "image": case["image"],
            "defaultState": case["defaultState"],
            "family": case["family"],
            "action": case["action"],
            "expectedWarnings": case["expectedWarnings"],
        }
        for case in cases
    ]
    MANIFEST.write_text(json.dumps({"schemaVersion": 1, "cases": manifest_cases}, indent=2) + "\n", encoding="utf-8")
    renderer = BENCH / "render_fixtures.js"
    run(["node", str(renderer), "--spec", str(SPEC), "--outdir", str(TRAIN_IMAGES)], ROOT)
    for case in cases:
        apply_image_effects(case)
    write_index(cases)
    print(f"generated {len(cases)} synthetic training screenshots; families={len({c['family'] for c in cases})}")
    print(f"manifest: {MANIFEST.relative_to(ROOT)}")
    print(f"expected rows: {EXPECTED.relative_to(ROOT)}")
    print(f"visual index: {(BENCH / 'fixtures/index.html').relative_to(ROOT)}")


def read_truth(path: Path | None) -> dict[str, list[str]]:
    if path:
        truth = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(truth, dict):
            raise SystemExit("expected-addresses JSON must map case IDs to ordered row arrays")
        return truth
    original = json.loads((BENCH / "expected-addresses.json").read_text(encoding="utf-8"))
    truth = {
        "original-" + image.removeprefix("Screenshot_20250920-").removesuffix(".png"): rows
        for image, rows in original.items()
    }
    if EXPECTED.exists():
        truth.update(json.loads(EXPECTED.read_text(encoding="utf-8")))
    return truth


def has_ocr_candidate_metadata(candidate: dict) -> bool:
    if not isinstance(candidate, dict):
        return False
    agreement = candidate.get("ocrAgreement")
    alternatives = candidate.get("ocrAlternatives")
    return (
        type(agreement) is int
        and 0 <= agreement <= 3
        and type(candidate.get("ocrReviewRequired")) is bool
        and isinstance(alternatives, list)
        and all(isinstance(value, str) for value in alternatives)
    )


def engine_evidence_for(candidates: list[dict]) -> dict[str, dict[str, int]]:
    evidence = {engine: {"candidateRows": 0, "lineAlternatives": 0} for engine in OCR_ENGINES}
    for candidate in candidates:
        alternatives = candidate.get("ocrAlternatives", [])
        if not isinstance(alternatives, list):
            continue
        observed = set()
        for alternative in alternatives:
            if not isinstance(alternative, str):
                continue
            parts = alternative.split(" ", 3)
            if len(parts) != 4 or parts[0] != "line" or not parts[1].isdigit():
                continue
            engine = parts[2].removesuffix(":")
            if engine in evidence:
                evidence[engine]["lineAlternatives"] += 1
                observed.add(engine)
        for engine in observed:
            evidence[engine]["candidateRows"] += 1
    return evidence


def ordered_row_mismatch_failures(report: dict) -> list[str]:
    cases = report.get("cases")
    if not isinstance(cases, list):
        return ["ordered-row results are unavailable"]
    mismatched = [str(case.get("id", "<unknown>")) for case in cases if not case.get("exactOrdered")]
    return ["ordered-row mismatches: " + ", ".join(mismatched[:12])] if mismatched else []


def strict_acceptance_failures(report: dict) -> list[str]:
    metrics = report.get("metrics")
    cases = report.get("cases")
    if not isinstance(metrics, dict) or not isinstance(cases, list):
        return ["benchmark report is missing metrics or case results"]

    failed = ordered_row_mismatch_failures(report)
    if type(metrics.get("caseCount")) is not int or metrics["caseCount"] < 1:
        failed.append("benchmark suite must contain at least one case")

    consensus = metrics.get("ocrConsensus")
    if not isinstance(consensus, dict) or consensus.get("metadataAvailable") is not True:
        failed.append("strict OCR consensus metadata is unavailable")

    for label in ("units", "duplicates"):
        counts = metrics.get(label)
        false_positive = counts.get("falsePositive") if isinstance(counts, dict) else None
        false_negative = counts.get("falseNegative") if isinstance(counts, dict) else None
        if false_positive != 0 or false_negative != 0:
            failed.append(
                f"{label} require zero false positives and false negatives: "
                f"FP={false_positive}, FN={false_negative}"
            )

    for case in cases:
        case_consensus = case.get("ocrConsensus", {})
        candidate_rows = case_consensus.get("candidateRows", 0) if isinstance(case_consensus, dict) else 0
        unresolved_rows = case_consensus.get("unresolvedReviewRows", 0) if isinstance(case_consensus, dict) else 0
        if case.get("action") == "review" and candidate_rows > 0 and (unresolved_rows or 0) < 1:
            failed.append(f"review case {case.get('id', '<unknown>')} has visible rows but none marked for review")

    expected_rows = sum(
        len(case.get("expectedRows", []))
        for case in cases
        if isinstance(case.get("expectedRows", []), list)
    )
    if expected_rows:
        engine_evidence = consensus.get("engineEvidence", {}) if isinstance(consensus, dict) else {}
        missing = [
            engine
            for engine in OCR_ENGINES
            if not isinstance(engine_evidence, dict)
            or not isinstance(engine_evidence.get(engine), dict)
            or engine_evidence[engine].get("lineAlternatives", 0) < 1
        ]
        if missing:
            failed.append("no OCR alternative evidence across positive suite for: " + ", ".join(missing))
    return failed


def score(manifest_path: Path, runner_path: Path, truth: dict[str, list[str]]) -> tuple[dict, list[str]]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    result = json.loads(runner_path.read_text(encoding="utf-8"))
    metadata = {case["id"]: case for case in manifest["cases"]}
    observed = {case["id"]: case for case in result["cases"]}
    ids = [case["id"] for case in manifest["cases"]]
    missing = [case_id for case_id in ids if case_id not in observed or case_id not in truth]
    if missing:
        raise SystemExit("runner results or expected labels missing IDs: " + ", ".join(missing[:8]))

    engine_contract = result.get("engineContract")
    engines = engine_contract.get("engines") if isinstance(engine_contract, dict) else None
    runner_schema = result.get("schemaVersion")
    ocr_metadata_available = (
        type(runner_schema) is int
        and runner_schema >= 2
        and result.get("ocrStrictMode") is True
        and isinstance(engine_contract, dict)
        and type(engine_contract.get("schemaVersion")) is int
        and engine_contract.get("schemaVersion") == 1
        and isinstance(engines, list)
        and all(isinstance(engine, str) for engine in engines)
        and len(engines) == 3
        and set(engines) == {"tesseract", "paddleocr", "easyocr"}
        and all(
            has_ocr_candidate_metadata(candidate)
            for case_id in ids
            for candidate in observed[case_id].get("candidates", [])
        )
    )

    ordered = tp = fp = fn = 0
    unit_tp = unit_fp = unit_fn = 0
    dup_tp = dup_fp = dup_fn = 0
    exact_cases = 0
    review_exact = reject_clean = 0
    warning_expectations = 0
    runtimes: list[float] = []
    case_reports = []
    corpus_expected: list[str] = []
    corpus_observed: list[str] = []
    errors = []
    candidate_row_count = 0
    majority_supported_rows = 0
    unresolved_review_rows = 0
    engine_evidence = {engine: {"candidateRows": 0, "lineAlternatives": 0} for engine in OCR_ENGINES}

    for case_id in ids:
        meta = metadata[case_id]
        raw_expected = truth[case_id]
        output = observed[case_id]
        expected_rows = [normalize(value) for value in raw_expected]
        got_rows = [normalize(row.get("normalized") or row.get("text") or "") for row in output.get("candidates", [])]
        exact = expected_rows == got_rows and not output.get("error")
        ordered += int(exact)
        exact_cases += int(exact)
        action = meta.get("action", "accept").lower()
        review_exact += int(action == "review" and exact)
        reject_clean += int(action == "reject" and not got_rows and not output.get("error"))
        warning_expectations += len(meta.get("expectedWarnings", []))
        runtimes.append(float(output.get("elapsedMs", 0)))
        if output.get("error"):
            errors.append(f"{case_id}: {output['error']}")
        candidates = output.get("candidates", [])
        candidate_row_count += len(candidates)
        case_engine_evidence = engine_evidence_for(candidates)
        for engine in OCR_ENGINES:
            for key in ("candidateRows", "lineAlternatives"):
                engine_evidence[engine][key] += case_engine_evidence[engine][key]
        case_majority_supported = (
            sum(candidate["ocrAgreement"] >= 2 for candidate in candidates)
            if ocr_metadata_available
            else None
        )
        case_unresolved_review = (
            sum(candidate["ocrReviewRequired"] for candidate in candidates)
            if ocr_metadata_available
            else None
        )
        if ocr_metadata_available:
            majority_supported_rows += case_majority_supported
            unresolved_review_rows += case_unresolved_review

        expected_counts = Counter(expected_rows)
        got_counts = Counter(got_rows)
        common = expected_counts & got_counts
        case_tp = sum(common.values())
        tp += case_tp
        fp += sum(got_counts.values()) - case_tp
        fn += sum(expected_counts.values()) - case_tp
        for address, count in expected_counts.items():
            if UNIT.search(address):
                matched = min(count, got_counts[address])
                unit_tp += matched
                unit_fn += count - matched
        for address, count in got_counts.items():
            if UNIT.search(address):
                unit_fp += count - min(count, expected_counts[address])
        expected_repeats = sum(max(0, count - 1) for count in expected_counts.values())
        got_repeats = sum(max(0, count - 1) for count in got_counts.values())
        common_repeats = sum(min(max(0, expected_counts[value] - 1), max(0, got_counts[value] - 1)) for value in expected_counts)
        dup_tp += common_repeats
        dup_fp += got_repeats - common_repeats
        dup_fn += expected_repeats - common_repeats
        corpus_expected.extend(expected_rows)
        corpus_observed.extend(got_rows)
        case_reports.append(
            {
                "id": case_id,
                "family": meta.get("family", ""),
                "action": action,
                "expectedRows": raw_expected,
                "observedRows": [row.get("text", "") for row in candidates],
                "ocrConsensus": {
                    "metadataAvailable": ocr_metadata_available,
                "candidateRows": len(candidates),
                "majoritySupportedRows": case_majority_supported,
                "unresolvedReviewRows": case_unresolved_review,
                "engineEvidence": case_engine_evidence,
            },
                "ocrCandidates": [
                    {
                        "lineIndex": candidate.get("lineIndex"),
                        "text": candidate.get("text", ""),
                        "ocrAgreement": candidate.get("ocrAgreement") if ocr_metadata_available else None,
                        "ocrReviewRequired": candidate.get("ocrReviewRequired") if ocr_metadata_available else None,
                        "ocrAlternatives": candidate.get("ocrAlternatives") if ocr_metadata_available else None,
                    }
                    for candidate in candidates
                ],
                "exactOrdered": exact,
                "expectedWarnings": meta.get("expectedWarnings", []),
                "observedWarnings": output.get("warnings") if output.get("warningsAvailable") else None,
                "warningStatus": "available" if output.get("warningsAvailable") else "service-does-not-expose-warnings",
                "elapsedMs": float(output.get("elapsedMs", 0)),
                "error": output.get("error"),
            }
        )

    corpus_counts = Counter(corpus_expected)
    corpus_out_counts = Counter(corpus_observed)
    expected_global_repeats = sum(max(0, value - 1) for value in corpus_counts.values())
    output_global_repeats = sum(max(0, value - 1) for value in corpus_out_counts.values())
    matched_global_repeats = sum(
        min(max(0, corpus_counts[value] - 1), max(0, corpus_out_counts[value] - 1))
        for value in corpus_counts
    )
    runtimes_sorted = sorted(runtimes)
    p95_index = max(0, min(len(runtimes_sorted) - 1, math.ceil(len(runtimes_sorted) * 0.95) - 1)) if runtimes_sorted else 0
    metrics = {
        "caseCount": len(ids),
        "exactOrderedCases": exact_cases,
        "exactOrderedRate": exact_cases / len(ids) if ids else 1.0,
        "rows": {
            "truePositive": tp,
            "falsePositive": fp,
            "falseNegative": fn,
            "precision": tp / (tp + fp) if tp + fp else 1.0,
            "recall": tp / (tp + fn) if tp + fn else 1.0,
        },
        "units": {
            "truePositive": unit_tp,
            "falsePositive": unit_fp,
            "falseNegative": unit_fn,
            "precision": unit_tp / (unit_tp + unit_fp) if unit_tp + unit_fp else 1.0,
            "recall": unit_tp / (unit_tp + unit_fn) if unit_tp + unit_fn else 1.0,
        },
        "duplicates": {
            "truePositive": dup_tp,
            "falsePositive": dup_fp,
            "falseNegative": dup_fn,
            "precision": dup_tp / (dup_tp + dup_fp) if dup_tp + dup_fp else 1.0,
            "recall": dup_tp / (dup_tp + dup_fn) if dup_tp + dup_fn else 1.0,
            "expectedRepeatedRowsAcrossSuite": expected_global_repeats,
            "observedRepeatedRowsAcrossSuite": output_global_repeats,
            "matchedRepeatedRowsAcrossSuite": matched_global_repeats,
        },
        "ocrConsensus": {
            "metadataAvailable": ocr_metadata_available,
            "candidateRows": candidate_row_count,
            "majoritySupportedRows": majority_supported_rows if ocr_metadata_available else None,
            "unresolvedReviewRows": unresolved_review_rows if ocr_metadata_available else None,
            "engineEvidence": engine_evidence,
        },
        "expectedReviewWarnings": warning_expectations,
        "reviewCasesExactVisibleRows": review_exact,
        "rejectCasesWithNoCandidates": reject_clean,
        "runtimeMs": {
            "total": sum(runtimes),
            "mean": statistics.mean(runtimes) if runtimes else 0,
            "median": statistics.median(runtimes) if runtimes else 0,
            "p95": runtimes_sorted[p95_index] if runtimes_sorted else 0,
        },
        "serviceWarningApiAvailable": any(item["warningStatus"] == "available" for item in case_reports),
    }
    pipeline_metadata = {
        "strictMode": result.get("ocrStrictMode"),
        "executable": result.get("ocrExecutable"),
        "language": result.get("ocrLanguage"),
        "engineContract": engine_contract,
    }
    if not any(value is not None for value in pipeline_metadata.values()):
        pipeline_metadata = None
    return {
        "schemaVersion": 3,
        "ocrPipeline": pipeline_metadata,
        "metrics": metrics,
        "cases": case_reports,
        "errors": errors,
    }, ids


def regressions(current: dict, baseline_path: Path | None, runtime_factor: float) -> list[str]:
    failed = [f"OCR runtime error: {error}" for error in current.get("errors") or []]
    if baseline_path is None:
        return failed
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    now = current["metrics"]
    old = baseline["metrics"]
    checks = [
        ("exactOrderedCases", now["exactOrderedCases"], old["exactOrderedCases"]),
        ("row true positives", now["rows"]["truePositive"], old["rows"]["truePositive"]),
        ("row precision", now["rows"]["precision"], old["rows"]["precision"]),
        ("row recall", now["rows"]["recall"], old["rows"]["recall"]),
        ("unit true positives", now["units"]["truePositive"], old["units"]["truePositive"]),
        ("duplicate true positives", now["duplicates"]["truePositive"], old["duplicates"]["truePositive"]),
    ]
    failed.extend(
        f"{label} regressed: {value} < {old_value}"
        for label, value, old_value in checks
        if value < old_value
    )
    before = old["runtimeMs"]["total"]
    after = now["runtimeMs"]["total"]
    if before > 1000 and after > before * runtime_factor:
        failed.append(f"aggregate OCR runtime regressed: {after:.0f} ms > {runtime_factor:.2f}x {before:.0f} ms")
    return failed


def command_generate(_: argparse.Namespace) -> None:
    generate()


def command_run(args: argparse.Namespace) -> None:
    manifest = (ROOT / args.manifest).resolve() if not args.manifest.is_absolute() else args.manifest
    report_path = (ROOT / args.report).resolve() if not args.report.is_absolute() else args.report
    raw_path = report_path.with_suffix(".runner.json")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    raw_path.parent.mkdir(parents=True, exist_ok=True)

    java = java_binary("java")
    if args.classpath_file:
        cp_file = args.classpath_file.resolve()
        cp = cp_file.read_text(encoding="utf-8").strip().replace("\n", os.pathsep)
        classes = ROOT / "target/ocr-benchmark-classes"
        classes.mkdir(parents=True, exist_ok=True)
        run(
            [
                java_binary("javac"),
                "-encoding",
                "UTF-8",
                "-cp",
                cp,
                "-d",
                str(classes),
                *map(str, JAVA_SOURCES),
            ],
            ROOT,
        )
        classpath = os.pathsep.join((str(classes), cp))
        runner = [
            java,
            f"-Docr.benchmark.manifest={manifest}",
            f"-Docr.benchmark.output={raw_path}",
            f"-Docr.tesseract.executable={args.tesseract}",
            f"-Docr.language={args.language}",
            "-cp",
            classpath,
            "com.jtdev.routelisttotesla.OcrBenchmarkRunner",
        ]
    else:
        run([str(ROOT / "mvnw"), "-DskipTests", "package"], ROOT)
        jars = [path for path in (ROOT / "target").glob("*.jar") if not path.name.endswith(".original")]
        if not jars:
            raise SystemExit("Maven package did not produce an application JAR")
        app_jar = max(jars, key=lambda path: path.stat().st_mtime)
        runner = [
            java,
            f"-Dloader.path={ROOT / 'target/test-classes'}",
            "-Dloader.main=com.jtdev.routelisttotesla.OcrBenchmarkRunner",
            f"-Docr.benchmark.manifest={manifest}",
            f"-Docr.benchmark.output={raw_path}",
            f"-Docr.tesseract.executable={args.tesseract}",
            f"-Docr.language={args.language}",
            "-cp",
            str(app_jar),
            "org.springframework.boot.loader.launch.PropertiesLauncher",
        ]
    run(runner, ROOT)
    report, ids = score(manifest, raw_path, read_truth(args.expected))
    report["manifest"] = str(manifest)
    report["runnerReport"] = str(raw_path)
    failed = regressions(report, args.baseline, args.runtime_factor)
    if args.fail_on_mismatch:
        mismatched = [case["id"] for case in report["cases"] if not case["exactOrdered"]]
        if mismatched:
            failed.append("ordered-row mismatches: " + ", ".join(mismatched[:12]))
    report["regressions"] = failed
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    metrics = report["metrics"]
    rows = metrics["rows"]
    units = metrics["units"]
    duplicates = metrics["duplicates"]
    runtime = metrics["runtimeMs"]
    consensus = metrics["ocrConsensus"]
    consensus_status = "available" if consensus["metadataAvailable"] else "unavailable"
    print(
        f"ocrConsensus={consensus_status} "
        f"candidateRows={consensus['candidateRows']} "
        f"majoritySupportedRows={consensus['majoritySupportedRows']} "
        f"unresolvedReviewRows={consensus['unresolvedReviewRows']}"
    )
    print(
        f"cases={metrics['caseCount']} exactOrdered={metrics['exactOrderedCases']}/{metrics['caseCount']} "
        f"rows={rows['truePositive']} TP/{rows['falsePositive']} FP/{rows['falseNegative']} FN "
        f"precision={rows['precision']:.3f} recall={rows['recall']:.3f}"
    )
    print(
        f"units={units['truePositive']} TP/{units['falsePositive']} FP/{units['falseNegative']} FN "
        f"duplicates={duplicates['truePositive']} TP/{duplicates['falsePositive']} FP/{duplicates['falseNegative']} FN"
    )
    print(
        f"expectedReviewWarnings={metrics['expectedReviewWarnings']} "
        f"warningApi={'available' if metrics['serviceWarningApiAvailable'] else 'unavailable'} "
        f"runtimeMs total={runtime['total']:.0f} mean={runtime['mean']:.1f} p95={runtime['p95']:.1f}"
    )
    for case in report["cases"]:
        if not case["exactOrdered"]:
            print(f"mismatch {case['id']} expected={case['expectedRows']} observed={case['observedRows']}")
    if report["errors"]:
        for error in report["errors"]:
            print("error", error)
    print("report:", report_path)
    if failed:
        for failure in failed:
            print("REGRESSION:", failure)
        raise SystemExit(1)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(required=True)
    generate_parser = subparsers.add_parser("generate", help="render the deterministic training screenshot set")
    generate_parser.set_defaults(func=command_generate)
    run_parser = subparsers.add_parser("run", help="invoke the Java service runner and score its JSON output")
    run_parser.add_argument("--manifest", type=Path, default=MANIFEST)
    run_parser.add_argument("--report", type=Path, default=BENCH / "results/train-report.json")
    run_parser.add_argument("--expected", type=Path, help="separate expected rows JSON, required for held-out suites")
    run_parser.add_argument("--baseline", type=Path, help="fail if stable metrics regress from this prior report")
    run_parser.add_argument("--runtime-factor", type=float, default=1.5)
    run_parser.add_argument("--fail-on-mismatch", action="store_true")
    run_parser.add_argument("--classpath-file", type=Path, help="compile only the OCR runner sources against an existing classpath")
    run_parser.add_argument("--tesseract", default="tesseract")
    run_parser.add_argument("--language", default="eng")
    run_parser.set_defaults(func=command_run)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
