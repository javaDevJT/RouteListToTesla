#!/usr/bin/env python3
"""Validate and emit the pinned US Census 2026 ZCTA representative-point TSV.

Usage: python3 scripts/census-postal-areas.py ARCHIVE

This is an offline, stdout-only transform. It makes no network requests and
creates no files. Redirect stdout to update the reviewed resource.
"""
import hashlib
import io
import math
import pathlib
import re
import sys
import zipfile

MAX_ARCHIVE_BYTES = 2_000_000
MAX_MEMBER_BYTES = 8 * 1024 * 1024
EXPECTED_ARCHIVE_SHA256 = "f1e9046b91f6e60686a99343cb2752834c02b0764939403b331c78c017e2c54c"
EXPECTED_MEMBER_SHA256 = "e85e5d6a3df1d4f0655dae4851383b0e8f68579fb0f3f62ef0f11928b70bbda4"
MEMBER_NAME = "2026_Gaz_zcta_national.txt"
EXPECTED_HEADER = "GEOID|GEOIDFQ|ALAND|AWATER|ALAND_SQMI|AWATER_SQMI|INTPTLAT|INTPTLONG"
EXPECTED_ROWS = 33_791
ZIP5 = re.compile(r"[0-9]{5}\Z")
GEOIDFQ = re.compile(r"860Z200US[0-9]{5}\Z")
UINT = re.compile(r"[0-9]+\Z")
DECIMAL = re.compile(r"[0-9]+(?:\.[0-9]*)?\Z")
LATITUDE = re.compile(r"[+-]?[0-9]{1,2}\.[0-9]{1,6}\Z")
LONGITUDE = re.compile(r"[+-]?[0-9]{1,3}\.[0-9]{1,6}\Z")


def read_source(path: str) -> tuple[bytes, bytes]:
    with pathlib.Path(path).open("rb") as source:
        archive_bytes = source.read(MAX_ARCHIVE_BYTES + 1)
    if len(archive_bytes) > MAX_ARCHIVE_BYTES:
        raise ValueError("Source archive exceeds the byte limit")
    if hashlib.sha256(archive_bytes).hexdigest() != EXPECTED_ARCHIVE_SHA256:
        raise ValueError("Source archive SHA-256 does not match the pinned 2026 file")

    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        members = archive.infolist()
        if len(members) != 1 or members[0].filename != MEMBER_NAME or members[0].is_dir():
            raise ValueError("Source archive has an unexpected member list")
        info = members[0]
        if info.file_size > MAX_MEMBER_BYTES:
            raise ValueError("Source text exceeds the byte limit")
        with archive.open(info) as member:
            member_bytes = member.read(MAX_MEMBER_BYTES + 1)
    if len(member_bytes) > MAX_MEMBER_BYTES or len(member_bytes) != info.file_size:
        raise ValueError("Source text is oversized or truncated")
    if hashlib.sha256(member_bytes).hexdigest() != EXPECTED_MEMBER_SHA256:
        raise ValueError("Source text SHA-256 does not match the pinned 2026 file")
    return archive_bytes, member_bytes


def transform(member_bytes: bytes) -> list[str]:
    text = member_bytes.decode("utf-8", errors="strict")
    if "\r" in text or not text.endswith("\n"):
        raise ValueError("Source text must use newline-terminated UTF-8 records")
    lines = text.splitlines()
    if not lines or lines[0] != EXPECTED_HEADER:
        raise ValueError("Unexpected Census Gazetteer schema")
    if len(lines) - 1 != EXPECTED_ROWS:
        raise ValueError("Unexpected national ZCTA record count")

    rows: list[tuple[str, float, float, str, str]] = []
    seen: set[str] = set()
    for line_number, line in enumerate(lines[1:], start=2):
        if len(line) > 256:
            raise ValueError(f"Oversized source row at line {line_number}")
        columns = line.split("|")
        if len(columns) != 8:
            raise ValueError(f"Unexpected source schema at line {line_number}")
        geoid, geoidfq, aland, awater, aland_sqmi, awater_sqmi, latitude_text, longitude_text = columns
        if not ZIP5.fullmatch(geoid) or not GEOIDFQ.fullmatch(geoidfq) or geoidfq != "860Z200US" + geoid:
            raise ValueError(f"Invalid ZCTA identifier at line {line_number}")
        if geoid in seen:
            raise ValueError(f"Duplicate ZCTA identifier at line {line_number}")
        seen.add(geoid)
        if any(not UINT.fullmatch(value) or int(value) > 2**63 - 1 for value in (aland, awater)):
            raise ValueError(f"Invalid integer area at line {line_number}")
        if any(not DECIMAL.fullmatch(value) or not math.isfinite(float(value)) or float(value) > 100_000_000
               for value in (aland_sqmi, awater_sqmi)):
            raise ValueError(f"Invalid square-mile area at line {line_number}")
        if not LATITUDE.fullmatch(latitude_text) or not LONGITUDE.fullmatch(longitude_text):
            raise ValueError(f"Invalid representative-point schema at line {line_number}")
        latitude = float(latitude_text)
        longitude = float(longitude_text)
        if not math.isfinite(latitude) or not math.isfinite(longitude) \
                or not -90.0 <= latitude <= 90.0 or not -180.0 <= longitude <= 180.0 \
                or (latitude == 0.0 and longitude == 0.0):
            raise ValueError(f"Invalid representative-point coordinate at line {line_number}")
        rows.append((geoid, latitude, longitude, f"{latitude:.6f}", f"{longitude:.6f}"))

    if len(rows) != EXPECTED_ROWS or len(seen) != EXPECTED_ROWS:
        raise ValueError("National ZCTA record count or uniqueness check failed")
    rows.sort(key=lambda row: (row[0], row[1], row[2]))
    return [f"{zip_code}\t{latitude_text}\t{longitude_text}" for zip_code, _, _, latitude_text, longitude_text in rows]


def main() -> None:
    if len(sys.argv) != 2:
        raise ValueError("Usage: census-postal-areas.py ARCHIVE")
    archive_bytes, member_bytes = read_source(sys.argv[1])
    rows = transform(member_bytes)
    header = (
        "# U.S. Census Bureau 2026 Gazetteer ZCTA representative points\n"
        f"# source_archive_sha256={hashlib.sha256(archive_bytes).hexdigest()}\n"
        f"# source_member={MEMBER_NAME}\n"
        f"# source_member_sha256={hashlib.sha256(member_bytes).hexdigest()}\n"
        f"# row_count={len(rows)}\n"
        "ZIP\tINTPTLAT\tINTPTLONG\n"
    )
    sys.stdout.write(header)
    sys.stdout.write("\n".join(rows) + "\n")


if __name__ == "__main__":
    main()
