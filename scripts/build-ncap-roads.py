#!/usr/bin/env python3
"""Bake the NCAP road list into the app's bundled asset.

The list is maintained by hand in `tools/ncap-roads.tsv` (sources and dates are in its header):
no agency publishes a machine-readable feed, so the "feed" is a reviewed file. This script checks
it, sorts it, writes `app/src/main/assets/ncap_roads.tsv` plus a version file, and prints what the
workflow puts in the manifest. The app refreshes the hosted copy on launch, so a corrected list
reaches phones without an app release.

    VERSION=20261122 python3 scripts/build-ncap-roads.py     # or omit VERSION for today

Exit 3 means the list failed a check and nothing was written.
"""
import datetime
import hashlib
import os
import sys

SRC = "tools/ncap-roads.tsv"
ASSET = "app/src/main/assets/ncap_roads.tsv"
VERSION_FILE = "app/src/main/assets/ncap_roads_version.txt"
CITIES = {"MM", "QC", "MNL", "SJ", "MUNTI"}
MIN_ROWS = 40


def fail(msg):
    print(f"FAIL: {msg}", file=sys.stderr)
    sys.exit(3)


def main():
    rows = []
    with open(SRC, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 3:
                fail(f"{SRC}:{n}: expected 3 tab-separated columns, got {len(parts)}")
            road, city, alias = (p.strip() for p in parts)
            if not road or not alias:
                fail(f"{SRC}:{n}: empty road or alias")
            if city not in CITIES:
                fail(f"{SRC}:{n}: city {city!r} is not one of {sorted(CITIES)}")
            if alias != alias.lower() or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789 ñ" for c in alias):
                fail(f"{SRC}:{n}: alias {alias!r} is not normalized (lowercase, no punctuation)")
            rows.append((road, city, alias))

    if len(rows) < MIN_ROWS:
        fail(f"only {len(rows)} rows, expected at least {MIN_ROWS} (a truncated file is not published)")
    pairs = set()
    for road, city, alias in rows:
        if (city, alias) in pairs:
            fail(f"duplicate alias {alias!r} for {city}")
        pairs.add((city, alias))
    # One alias must not name two different roads in the same city.
    by_key = {}
    for road, city, alias in rows:
        by_key.setdefault((city, alias), set()).add(road)
    for (city, alias), roads in by_key.items():
        if len(roads) > 1:
            fail(f"alias {alias!r} maps to {sorted(roads)} in {city}")

    rows.sort()
    out = "".join(f"{r}\t{c}\t{a}\n" for r, c, a in rows)
    os.makedirs(os.path.dirname(ASSET), exist_ok=True)
    with open(ASSET, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    version = os.environ.get("VERSION") or datetime.date.today().strftime("%Y%m%d")
    if not (version.isdigit() and len(version) == 8):
        fail(f"VERSION {version!r} is not YYYYMMDD")
    with open(VERSION_FILE, "w", encoding="utf-8", newline="\n") as f:
        f.write(version + "\n")

    data = out.encode("utf-8")
    print(f"version={version}")
    print(f"rows={len(rows)}")
    print(f"sha256={hashlib.sha256(data).hexdigest()}")
    print(f"size={len(data)}")


if __name__ == "__main__":
    main()
