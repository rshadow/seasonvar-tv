#!/usr/bin/env python3
"""Explicit emulator history write: Naruto, season one, episode one, 2x2."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import threading

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--device", required=True)
parser.add_argument("--runner", required=True)
parser.add_argument("--output", required=True, type=Path)
args = parser.parse_args()
if not args.device.startswith("emulator-"):
    parser.error("Use an emulator with an existing authorized Seasonvar session.")
args.output.mkdir(parents=True, exist_ok=True)
adb = ["adb", "-s", args.device]
process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-e", "mode", "history-playback", args.runner],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
watchdog = threading.Timer(180, process.kill)
watchdog.daemon = True
watchdog.start()
allowed = {"probe_success", "history_recorded_once", "no_permanent_mark_written", "pause_seek_back_verified",
           "local_progress_restored", "failure_type", "phase", "artifacts"}
fields = {}
for line in process.stdout:
    if line.startswith("INSTRUMENTATION_RESULT: "):
        key, separator, value = line.strip().removeprefix("INSTRUMENTATION_RESULT: ").partition("=")
        if separator and key in allowed and re.fullmatch(r"[A-Za-z0-9_./:-]+", value):
            fields[key] = value == "true" if value in ("true", "false") else value
code = process.wait(timeout=10)
watchdog.cancel()
remote = fields.pop("artifacts", None)
if isinstance(remote, str) and re.fullmatch(r"/storage/emulated/0/Android/data/ru\.seasonvar\.tv/files/history-probe/run-[0-9]+", remote):
    subprocess.run(adb + ["pull", remote + "/.", str(args.output)], check=True, stdout=subprocess.DEVNULL)
(args.output / "result.json").write_text(json.dumps(fields, indent=2))
print(json.dumps(fields, sort_keys=True))
if code or any(fields.get(key) is not True for key in allowed - {"failure_type", "phase", "artifacts"}):
    raise SystemExit(1)
print("Inspect the PNG frames to verify visible video.")
