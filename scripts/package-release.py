#!/usr/bin/env python3
import hashlib
import json
import os
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def package_release(metadata, apk, output, tag="", notes=""):
    if metadata["applicationId"] != "ru.seasonvar.tv" or len(metadata["elements"]) != 1:
        raise ValueError("Expected a single Seasonvar TV release APK")
    version = metadata["elements"][0]["versionName"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", version):
        raise ValueError("Expected a semantic release version")
    if tag and tag != "v" + version:
        raise ValueError("Release tag must match APK versionName: v" + version)
    lines = [line.strip() for line in notes.splitlines() if line.strip()]
    if not lines or any(not line.startswith("- ") or not 1 <= len(line[2:].split()) <= 10
                        for line in lines):
        raise ValueError("Release notes must be a bullet list with 1 to 10 words per item")
    contents = apk.read_bytes()
    digest = hashlib.sha256(contents).hexdigest()
    output.mkdir(parents=True, exist_ok=True)
    for previous in output.glob("seasonvar-tv-*.apk"):
        previous.unlink()
    name = "seasonvar-tv-" + version + "-universal.apk"
    shutil.copyfile(apk, output / name)
    (output / "SHA256SUMS").write_text(digest + "  " + name + "\n")
    (output / "NOTES.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return output / name


def main():
    directory = ROOT / "app/build/outputs/apk/release"
    metadata = json.loads((directory / "output-metadata.json").read_text())
    try:
        version = metadata["elements"][0]["versionName"]
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", version):
            raise ValueError("Expected a semantic release version")
        notes = (ROOT / "docs/releases" / (version + ".md")).read_text(encoding="utf-8")
        result = package_release(metadata, directory / "app-release.apk", ROOT / "build/release",
                                 os.environ.get("RELEASE_TAG", ""), notes=notes)
    except (OSError, ValueError) as error:
        raise SystemExit(str(error))
    print("Release package: " + str(result.relative_to(ROOT)))


if __name__ == "__main__":
    main()
