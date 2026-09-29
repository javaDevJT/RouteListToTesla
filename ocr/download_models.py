#!/usr/bin/env python3
"""Install pinned OCR model files and emit their absolute runtime paths.

RapidOCR models are copied from the pinned Python wheel. EasyOCR models are
downloaded from their upstream release archives. In both cases, the extracted
model bytes are checked against ``models.json`` before they are installed.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path


CHUNK_SIZE = 1024 * 1024


def digest_file(path: Path) -> tuple[str, str, int]:
    sha256 = hashlib.sha256()
    md5 = hashlib.md5()
    size = 0
    with path.open("rb") as stream:
        while chunk := stream.read(CHUNK_SIZE):
            sha256.update(chunk)
            md5.update(chunk)
            size += len(chunk)
    return sha256.hexdigest(), md5.hexdigest(), size


def verify_file(path: Path, artifact: dict, *, check_md5: bool = False) -> None:
    sha256, md5, size = digest_file(path)
    if size != artifact["bytes"] or sha256 != artifact["sha256"]:
        raise ValueError(
            f"Pinned model verification failed for {path.name}: "
            f"size={size}, sha256={sha256}"
        )
    if check_md5 and md5 != artifact["md5"]:
        raise ValueError(f"Pinned MD5 verification failed for {path.name}: {md5}")


def atomic_install(source: Path, destination: Path, artifact: dict, *, check_md5: bool = False) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    verify_file(source, artifact, check_md5=check_md5)
    temporary = destination.with_name(f".{destination.name}.tmp")
    try:
        shutil.copyfile(source, temporary)
        verify_file(temporary, artifact, check_md5=check_md5)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def download_to(url: str, destination: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "RouteListToTesla-OCR-model-installer/1"})
    with urllib.request.urlopen(request, timeout=120) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output, length=CHUNK_SIZE)


def installed_version(distribution: str) -> str:
    try:
        return importlib.metadata.version(distribution)
    except importlib.metadata.PackageNotFoundError as error:
        raise RuntimeError(f"Required package is not installed: {distribution}") from error


def install_rapidocr_models(metadata: dict, directory: Path) -> dict[str, str]:
    expected_version = metadata["package_versions"]["rapidocr"]
    actual_version = installed_version("rapidocr")
    if actual_version != expected_version:
        raise RuntimeError(f"rapidocr version mismatch: expected {expected_version}, found {actual_version}")

    import rapidocr

    package_root = Path(rapidocr.__file__).resolve().parent
    installed: dict[str, str] = {}
    for artifact in metadata["model_artifacts"]["rapidocr"]:
        source = package_root / artifact["package_path"]
        if not source.is_file():
            raise FileNotFoundError(f"RapidOCR wheel model is missing: {source}")
        target = directory / artifact["relative_path"]
        atomic_install(source, target, artifact)
        installed[artifact["stage"]] = str(target.resolve())
    return installed


def extract_member(archive: Path, member_name: str, destination: Path) -> None:
    with zipfile.ZipFile(archive) as bundle:
        matches = [entry for entry in bundle.infolist() if entry.filename == member_name]
        if len(matches) != 1 or matches[0].is_dir():
            raise ValueError(f"Expected exactly one {member_name!r} member in {archive.name}")
        with bundle.open(matches[0]) as source, destination.open("wb") as output:
            shutil.copyfileobj(source, output, length=CHUNK_SIZE)


def install_easyocr_models(metadata: dict, directory: Path) -> dict[str, str]:
    expected_version = metadata["package_versions"]["easyocr"]
    actual_version = installed_version("easyocr")
    if actual_version != expected_version:
        raise RuntimeError(f"easyocr version mismatch: expected {expected_version}, found {actual_version}")

    installed: dict[str, str] = {}
    for artifact in metadata["model_artifacts"]["easyocr"]:
        target = directory / artifact["relative_path"]
        if target.is_file():
            try:
                verify_file(target, artifact, check_md5=True)
                installed[artifact["filename"]] = str(target.resolve())
                continue
            except ValueError:
                pass

        target.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="easyocr-model-") as temporary_directory:
            temporary = Path(temporary_directory)
            archive = temporary / "model.zip"
            extracted = temporary / artifact["filename"]
            download_to(artifact["source_url"], archive)
            extract_member(archive, artifact["archive_member"], extracted)
            atomic_install(extracted, target, artifact, check_md5=True)
        installed[artifact["filename"]] = str(target.resolve())
    return installed


def update_runtime_manifest(metadata: dict, directory: Path, paths: dict[str, dict[str, str]]) -> dict:
    model_root = (directory / "rapidocr").resolve()
    params = metadata["rapidocr_params"]
    params["Global.model_root_dir"] = str(model_root)
    for stage, path in paths["rapidocr"].items():
        params[f"{stage}.model_path"] = path

    easyocr_root = (directory / "easyocr").resolve()
    easyocr_user_network = easyocr_root / "user_network"
    easyocr_user_network.mkdir(parents=True, exist_ok=True)
    metadata["easyocr_model_directory"] = str(easyocr_root)
    metadata["easyocr_user_network_directory"] = str(easyocr_user_network)
    metadata["resolved_model_paths"] = paths
    return metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True, help="Root directory for installed model files")
    parser.add_argument("--manifest", type=Path, required=True, help="Pinned model metadata input and runtime manifest output")
    args = parser.parse_args()

    manifest_path = args.manifest.resolve()
    model_directory = args.directory.resolve()
    with manifest_path.open(encoding="utf-8") as stream:
        metadata = json.load(stream)

    paths = {
        "rapidocr": install_rapidocr_models(metadata, model_directory),
        "easyocr": install_easyocr_models(metadata, model_directory),
    }
    runtime_manifest = update_runtime_manifest(metadata, model_directory, paths)

    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = manifest_path.with_name(f".{manifest_path.name}.tmp")
    try:
        with temporary.open("w", encoding="utf-8") as stream:
            json.dump(runtime_manifest, stream, indent=2, sort_keys=True)
            stream.write("\n")
        os.replace(temporary, manifest_path)
    finally:
        temporary.unlink(missing_ok=True)

    print(f"Installed and verified {sum(map(len, paths.values()))} OCR models under {model_directory}")
    print(f"Wrote runtime manifest to {manifest_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"OCR model setup failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
