#!/usr/bin/env python3
import argparse
import json
import platform
import subprocess
import tarfile
from pathlib import Path

from bootstrap import ROOT, download


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dir", type=Path, default=Path.home() / ".cache/seasonvar/downloads")
    args = parser.parse_args()
    architecture = {"aarch64": "arm64", "amd64": "x86_64"}.get(platform.machine(), platform.machine())
    host = platform.system().lower() + "-" + architecture
    packages = json.loads((ROOT / "toolchain.lock.json").read_text())["workflow_lint"]
    if host not in packages:
        raise SystemExit("Unsupported actionlint host: " + host)
    cache = args.cache_dir.expanduser().resolve()
    cache.mkdir(parents=True, exist_ok=True)
    archive = download(packages[host], cache)
    executable = ROOT / ".tools/actionlint"
    executable.parent.mkdir(exist_ok=True)
    with tarfile.open(archive) as source:
        executable.write_bytes(source.extractfile("actionlint").read())
    executable.chmod(0o755)
    workflows = sorted(str(path) for path in (ROOT / ".github/workflows").glob("*.yml"))
    subprocess.run([str(executable), "-shellcheck=", "-pyflakes=", *workflows], check=True)


if __name__ == "__main__":
    main()
