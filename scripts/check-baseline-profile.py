"""Validate isolated generation metadata and the profile shipped in a release APK."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZipFile


def check_generation(metadata):
    if metadata.get("applicationId") != "ru.seasonvar.tv.baseline" or metadata.get("variantName") != "nonMinifiedRelease":
        raise ValueError("Generation must use the isolated nonMinifiedRelease application")


def check_apk(apk, source):
    rules = source.read_text(encoding="utf-8").splitlines()
    for required in ("Lru/seasonvar/tv/MainActivity;", "Lru/seasonvar/tv/ui/SeasonvarAppKt;"):
        if not any(required in rule for rule in rules):
            raise ValueError("Baseline Profile misses an application entry point")
    with ZipFile(apk) as archive:
        profile = archive.read("assets/dexopt/baseline.prof")
        metadata = archive.read("assets/dexopt/baseline.profm")
    if not profile.startswith(b"pro\0") or not 8 < len(profile) < 1_500_000:
        raise ValueError("Invalid binary profile or profile size")
    if not metadata.startswith(b"prm\0") or len(metadata) <= 8:
        raise ValueError("Invalid profile metadata")
    return {"profile_bytes": len(profile), "metadata_bytes": len(metadata),
            "application_rules": sum("Lru/seasonvar/tv/" in rule for rule in rules)}


def check_binary(apk, mapping, profgen):
    with tempfile.TemporaryDirectory(prefix="seasonvar-profile-") as directory:
        root = Path(directory)
        binary = root / "baseline.prof"
        decoded = root / "baseline.txt"
        with ZipFile(apk) as archive:
            binary.write_bytes(archive.read("assets/dexopt/baseline.prof"))
        subprocess.run([str(profgen), "dumpProfile", "--profile", str(binary), "--apk", str(apk),
                        "--map", str(mapping), "--output", str(decoded)], check=True, timeout=120,
                       stdout=subprocess.DEVNULL)
        rules = decoded.read_text(encoding="utf-8").splitlines()
    for prefix in ("Lru/seasonvar/tv/MainActivity;", "Lru/seasonvar/tv/ui/"):
        if not any(prefix in rule for rule in rules):
            raise ValueError("Binary profile misses application rules after R8")
    return sum("Lru/seasonvar/tv/" in rule for rule in rules)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    generation = modes.add_parser("generation")
    generation.add_argument("--metadata", type=Path, required=True)
    apk = modes.add_parser("apk")
    apk.add_argument("--apk", type=Path, required=True)
    apk.add_argument("--source", type=Path, required=True)
    apk.add_argument("--mapping", type=Path)
    apk.add_argument("--profgen", type=Path)
    args = parser.parse_args()
    try:
        if args.mode == "generation":
            check_generation(json.loads(args.metadata.read_text(encoding="utf-8")))
            print("Isolated generation application verified")
        else:
            result = check_apk(args.apk, args.source)
            if args.mapping is not None and args.profgen is not None:
                result["binary_application_rules"] = check_binary(args.apk, args.mapping, args.profgen)
            print(json.dumps(result, sort_keys=True))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error))


if __name__ == "__main__":
    main()
