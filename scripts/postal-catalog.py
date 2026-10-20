#!/usr/bin/env python3
"""Deterministically inspect/emit the vendored GeoNames postal evidence snapshot.

Usage: python3 scripts/postal-catalog.py ARCHIVE [START COUNT]
This tool writes no files and makes no network calls. Data imports are reviewed
and applied as ordinary source changes; production never downloads the dataset.
"""
import hashlib
import io
import json
import math
import re
import sys
import zipfile

source = open(sys.argv[1], "rb").read(2_000_001)
if len(source) > 2_000_000:
    raise ValueError("Archive exceeds import bound")
with zipfile.ZipFile(io.BytesIO(source)) as archive:
    info = archive.getinfo("US.txt")
    if info.file_size > 10_000_000:
        raise ValueError("Postal text exceeds import bound")
    raw = archive.read(info)
rows = set()
for line in raw.decode("utf-8").splitlines():
    columns = line.split("\t")
    if len(columns) != 12 or columns[0] != "US":
        raise ValueError("Unexpected source schema")
    country, code, city, _, state, _, _, _, _, lat, lon, accuracy = columns
    if (not re.fullmatch(r"[0-9]{5}", code) or not re.fullmatch(r"[A-Z]{2}", state)
            or not city or accuracy != "4"):
        continue
    latitude, longitude = float(lat), float(lon)
    if (not math.isfinite(latitude) or not math.isfinite(longitude)
            or abs(latitude) > 90 or abs(longitude) > 180
            or (latitude == 0 and longitude == 0)):
        raise ValueError("Invalid source coordinate")
    rows.add("\t".join((code, state, city, lat, lon, accuracy)))
lines = sorted(rows)
data = ("\n".join(lines) + "\n").encode("utf-8")
if len(sys.argv) == 4:
    start, count = int(sys.argv[2]), int(sys.argv[3])
    if start < 0 or not 1 <= count <= 2000:
        raise ValueError("Invalid chunk bounds")
    sys.stdout.write("\n".join(lines[start:start + count]) + "\n")
else:
    print(json.dumps({"sourceZipSha256": hashlib.sha256(source).hexdigest(),
                      "sourceTextSha256": hashlib.sha256(raw).hexdigest(),
                      "catalogSha256": hashlib.sha256(data).hexdigest(),
                      "sourceRows": len(raw.decode("utf-8").splitlines()),
                      "catalogRows": len(lines), "catalogBytes": len(data)}))
