#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def properties(path):
    return dict(
        line.split("=", 1)
        for line in path.read_text().splitlines()
        if "=" in line and not line.startswith("#")
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--name", required=True)
    args = parser.parse_args()
    android_user = Path(os.environ.get("ANDROID_USER_HOME", str(Path.home() / ".android")))
    avd_home = Path(os.environ.get("ANDROID_AVD_HOME", str(android_user / "avd")))
    metadata = properties(avd_home / (args.name + ".ini"))
    config = Path(metadata["path"]) / "config.ini"
    expected = json.loads((ROOT / "toolchain.lock.json").read_text())["avd"]["hardware"]
    before = config.read_text()
    pending = dict(expected)
    lines = []
    for line in before.splitlines():
        key = line.partition("=")[0]
        lines.append(key + "=" + pending.pop(key) if key in pending else line)
    lines.extend(key + "=" + value for key, value in pending.items())
    after = "\n".join(lines) + "\n"
    if after != before:
        config.write_text(after)
        print("Configured keyboard and pointer input for", args.name)
    else:
        print("AVD input is ready:", args.name)


if __name__ == "__main__":
    main()
