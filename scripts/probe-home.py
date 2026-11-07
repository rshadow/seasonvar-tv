#!/usr/bin/env python3
"""Verify launcher publication and playback without changing account marks."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import threading

parser = argparse.ArgumentParser()
parser.add_argument("--device", required=True)
parser.add_argument("--runner", required=True)
parser.add_argument("--output", required=True, type=Path)
args = parser.parse_args()
if not args.device.startswith("emulator-"):
    parser.error("Use an emulator for home channel verification")
args.output.mkdir(parents=True, exist_ok=True)
adb = ["adb", "-s", args.device]
process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "mode", "home-channels", args.runner],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
watchdog = threading.Timer(300, process.kill)
watchdog.daemon = True
watchdog.start()
required = {"stable_programs", "three_channels", "guards_verified", "resume_pause_seek_back",
            "disable_clears_cards", "no_account_marks_written", "settings_restored", "script_success"}
fields = {}
for line in process.stdout:
    if line.startswith("INSTRUMENTATION_STATUS: phase="):
        phase = line.strip().split("=", 1)[1]
        if re.fullmatch(r"[a-z_]+", phase):
            print("phase=" + phase, flush=True)
    if line.startswith("INSTRUMENTATION_RESULT: "):
        key, _, value = line.strip().removeprefix("INSTRUMENTATION_RESULT: ").partition("=")
        if key in required | {"failure_type", "failure_line", "artifacts"} and re.fullmatch(r"[A-Za-z0-9_./:-]+", value):
            fields[key] = value == "true" if value in ("true", "false") else value
code = process.wait(timeout=10)
watchdog.cancel()
remote = fields.pop("artifacts", None)
if isinstance(remote, str) and re.fullmatch(r"/storage/emulated/0/Android/data/ru\.seasonvar\.tv/files/home-probe/run-[0-9]+", remote):
    subprocess.run(adb + ["pull", remote + "/.", str(args.output)], check=True, stdout=subprocess.DEVNULL)
(args.output / "result.json").write_text(json.dumps(fields, indent=2))
print(json.dumps(fields, sort_keys=True))
if code or any(fields.get(key) is not True for key in required):
    raise SystemExit(1)
print("Inspect launcher and video PNGs; provider rows and player states do not verify visible UI.")
