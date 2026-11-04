#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
MODULES = {"bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on"}


def version_parts(value):
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", value):
        raise ValueError("Unsupported build crypto version: " + value)
    parts = tuple(int(part) for part in value.split("."))
    return parts + (0,) * max(0, 3 - len(parts))


def check_report(report, minimum):
    if not isinstance(report, list) or not report:
        raise ValueError("Missing dependency-resolution graph")
    minimum_parts = version_parts(minimum)
    seen = set()
    failures = []
    checked = 0
    for entry in report:
        if not isinstance(entry, dict):
            raise ValueError("Invalid dependency-resolution entry")
        coordinate = entry.get("dependency", "")
        parts = coordinate.split(":")
        if len(parts) != 3 or parts[0] != "org.bouncycastle" or parts[1] not in MODULES:
            continue
        origins = entry.get("resolvedBy")
        if not isinstance(origins, list) or not origins:
            raise ValueError("Missing resolution origin for " + coordinate)
        seen.add(parts[1])
        checked += 1
        if version_parts(parts[2]) < minimum_parts:
            paths = ", ".join(str(origin.get("path", "?")) + "/" +
                              str(origin.get("configuration", "?")) for origin in origins)
            failures.append(coordinate + " in " + paths)
    if MODULES - seen:
        raise ValueError("Incomplete build crypto graph: " + ", ".join(sorted(MODULES - seen)))
    if failures:
        raise ValueError("Build crypto below " + minimum + ": " + "; ".join(failures))
    return checked


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path,
                        default=ROOT / "build/reports/dependency-graph-snapshots/dependency-resolution.json")
    args = parser.parse_args()
    try:
        properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
        match = re.search(r"^buildCryptoVersion=([^\s]+)$", properties, re.MULTILINE)
        if match is None:
            raise ValueError("Missing buildCryptoVersion in gradle.properties")
        minimum = match.group(1)
        checked = check_report(json.loads(args.report.read_text(encoding="utf-8")), minimum)
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print("Verified " + str(checked) + " build crypto resolutions at " + minimum + " or newer")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
