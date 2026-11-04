#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
CRYPTO_MODULES = {"bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on"}


def version_parts(value):
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", value):
        raise ValueError("Unsupported dependency version: " + value)
    parts = tuple(int(part) for part in value.split("."))
    return parts + (0,) * max(0, 3 - len(parts))


def minimum_versions(properties):
    def required(name):
        match = re.search(r"^" + re.escape(name) + r"=([^\s]+)$", properties, re.MULTILINE)
        if match is None:
            raise ValueError("Missing " + name + " in gradle.properties")
        version_parts(match.group(1))
        return match.group(1)
    crypto = required("buildCryptoVersion")
    result = {"org.bouncycastle:" + module: crypto for module in CRYPTO_MODULES}
    result["com.google.guava:guava"] = required("guavaVersion")
    result["org.jdom:jdom2"] = required("buildJdomVersion")
    return result


def check_report(report, minimums):
    if not isinstance(report, list) or not report:
        raise ValueError("Missing dependency-resolution graph")
    seen = set()
    failures = []
    checked = 0
    for entry in report:
        if not isinstance(entry, dict):
            raise ValueError("Invalid dependency-resolution entry")
        coordinate = entry.get("dependency", "")
        parts = coordinate.split(":")
        if len(parts) != 3:
            continue
        module = ":".join(parts[:2])
        if module not in minimums:
            continue
        origins = entry.get("resolvedBy")
        if not isinstance(origins, list) or not origins:
            raise ValueError("Missing resolution origin for " + coordinate)
        seen.add(module)
        checked += 1
        version = parts[2]
        if module == "com.google.guava:guava":
            version = version.removesuffix("-jre").removesuffix("-android")
        if version_parts(version) < version_parts(minimums[module]):
            paths = ", ".join(str(origin.get("path", "?")) + "/" +
                              str(origin.get("configuration", "?")) for origin in origins)
            failures.append(coordinate + " in " + paths)
    if minimums.keys() - seen:
        raise ValueError("Incomplete dependency graph: " + ", ".join(sorted(minimums.keys() - seen)))
    if failures:
        raise ValueError("Vulnerable resolved dependencies: " + "; ".join(failures))
    return checked


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path,
                        default=ROOT / "build/reports/dependency-graph-snapshots/dependency-resolution.json")
    args = parser.parse_args()
    try:
        properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
        minimums = minimum_versions(properties)
        checked = check_report(json.loads(args.report.read_text(encoding="utf-8")), minimums)
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print("Verified " + str(checked) + " resolved dependency entries against patched minimum versions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
