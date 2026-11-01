#!/usr/bin/env python3
import argparse
import json
import os
import shutil
from pathlib import Path

from bootstrap import ROOT, download, verify


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dir", type=Path, default=Path.home() / ".cache/seasonvar/downloads")
    args = parser.parse_args()
    cache = args.cache_dir.expanduser().resolve()
    cache.mkdir(parents=True, exist_ok=True)
    gradle = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    lock = json.loads((ROOT / "toolchain.lock.json").read_text())
    for artifact in lock["gradle_artifacts"]:
        target = (
            gradle / "caches/modules-2/files-2.1" / artifact["group"]
            / artifact["artifact"] / artifact["version"] / artifact["sha1"] / artifact.get("cache_file", artifact["file"])
        )
        if verify(target, artifact):
            print("Cached", artifact["file"])
            continue
        incoming = download(artifact, cache)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(incoming, target)
        print("Cached", artifact["file"])


if __name__ == "__main__":
    main()
