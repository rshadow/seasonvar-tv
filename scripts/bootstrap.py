#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import platform
import shutil
import stat
import sys
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / ".tools"


def fingerprint(package):
    return package["checksum"]["value"]


def verify(path, package):
    if not path.is_file() or path.stat().st_size != package["size"]:
        return False
    digest = hashlib.new(package["checksum"]["algorithm"])
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest() == fingerprint(package)


def download(package, cache):
    name = package["url"].rsplit("/", 1)[-1]
    target = cache / name
    if verify(target, package):
        return target
    partial = target.with_name(target.name + ".part")
    print("Downloading", name, flush=True)
    subprocess.run(
        [
            "curl", "--fail", "--location", "--silent", "--show-error",
            "--retry", "2", "--connect-timeout", "15", "--max-time", "1800",
            "--output", str(partial), package["url"],
        ],
        check=True,
    )
    if not verify(partial, package):
        raise RuntimeError("Archive size or checksum mismatch: " + name)
    partial.replace(target)
    return target


def extract_zip(archive, destination):
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            target = destination / entry.filename
            if not target.resolve().is_relative_to(destination.resolve()):
                raise RuntimeError("Archive entry escapes extraction directory")
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            mode = entry.external_attr >> 16
            if stat.S_ISLNK(mode):
                link = source.read(entry).decode("utf-8")
                if not (target.parent / link).resolve().is_relative_to(destination.resolve()):
                    raise RuntimeError("Archive symlink escapes extraction directory")
                target.symlink_to(link)
            else:
                with source.open(entry) as incoming, target.open("wb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
                if mode:
                    target.chmod(stat.S_IMODE(mode))


def install(package, target, cache, licenses, jdk=False):
    marker = target / ".seasonvar-package.json"
    if marker.is_file() and json.loads(marker.read_text())["checksum"] == fingerprint(package):
        print("Ready", target.relative_to(ROOT), flush=True)
        return
    if target.exists():
        raise RuntimeError("Unmanaged or different tool already exists: " + str(target))
    archive = download(package, cache)
    with tempfile.TemporaryDirectory(prefix="seasonvar-extract-", dir=cache) as temporary:
        stage = Path(temporary)
        if jdk:
            with tarfile.open(archive) as source:
                source.extractall(stage, filter="data")
            candidates = [
                p for pattern in ("*/Contents/Home", "*")
                for p in stage.glob(pattern) if (p / "bin/java").is_file()
            ]
            if len(candidates) != 1:
                raise RuntimeError("Unexpected JDK archive layout")
            source_dir = candidates[0]
        else:
            extract_zip(archive, stage)
            source_dir = stage / package["root"]
            if not (source_dir / "source.properties").is_file():
                raise RuntimeError("SDK archive has no source.properties")
            metadata = package["package_xml"]
            license_ref = ET.fromstring(metadata).find("localPackage/uses-license")
            if license_ref is not None:
                license_id = license_ref.attrib["ref"]
                node = ET.Element("license", {"id": license_id, "type": "text"})
                node.text = licenses[license_id]
                metadata = metadata.replace(
                    "<localPackage", ET.tostring(node, encoding="unicode") + "<localPackage", 1
                )
            (source_dir / "package.xml").write_text(metadata, encoding="utf-8")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(source_dir), str(target))
        marker.write_text(json.dumps({"checksum": fingerprint(package)}) + "\n")
    print("Installed", target.relative_to(ROOT), flush=True)


def main():
    if sys.version_info < (3, 12):
        raise SystemExit("Python 3.12+ is required")
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache-dir", type=Path, default=Path.home() / ".cache/seasonvar/downloads")
    args = parser.parse_args()
    if not shutil.which("curl"):
        raise SystemExit("curl is required")
    architecture = platform.machine().lower()
    architecture = {"aarch64": "arm64", "amd64": "x86_64"}.get(architecture, architecture)
    host = platform.system().lower() + "-" + architecture
    lock = json.loads((ROOT / "toolchain.lock.json").read_text())
    if host not in lock["hosts"]:
        raise SystemExit("Supported hosts: " + ", ".join(lock["hosts"]))
    cache = args.cache_dir.expanduser().resolve()
    cache.mkdir(parents=True, exist_ok=True)
    selected = lock["hosts"][host]
    licenses = lock["licenses"]
    install(selected["jdk"], TOOLS / "jdk", cache, licenses, jdk=True)
    sdk = TOOLS / "android-sdk"
    packages = selected["sdk"] + [selected["image"]]
    for package in packages:
        install(package, sdk / package["destination"], cache, licenses)
    previous_sdk = Path(os.environ.get(
        "ANDROID_HOME",
        str(Path.home() / ("Library/Android/sdk" if platform.system() == "Darwin" else "Android/Sdk")),
    ))
    if previous_sdk != sdk and (previous_sdk / "licenses").is_dir():
        shutil.copytree(previous_sdk / "licenses", sdk / "licenses", dirs_exist_ok=True)
    print("Toolchain is ready. Run make licenses if SDK licenses have not been accepted.")


if __name__ == "__main__":
    main()
