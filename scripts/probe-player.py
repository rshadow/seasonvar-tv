#!/usr/bin/env python3
"""Run an emulator video probe and collect frames for mandatory visual review."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import threading

parser = argparse.ArgumentParser()
parser.add_argument("--device", required=True)
parser.add_argument("--runner", required=True)
parser.add_argument("--page", required=True)
parser.add_argument("--season-page", required=True)
parser.add_argument("--output", required=True, type=Path)
args = parser.parse_args()
if not args.device.startswith("emulator-"):
    parser.error("The player probe runs only on an emulator")
args.output.mkdir(parents=True, exist_ok=True)
adb = ["adb", "-s", args.device]
process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "mode", "player-auto-next",
                                  "-e", "page", args.page, "-e", "season-page", args.season_page, args.runner], stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True)
watchdog = threading.Timer(600, process.kill)
watchdog.daemon = True
watchdog.start()
fields = {}
allowed = {"phase", "default_enabled", "automatic_next", "disabled_stays", "last_stays", "last_closes",
           "default_season_enabled", "season_disabled_stays", "automatic_season", "season_pause_seek",
           "no_account_marks_written", "script_success", "explicit_resume_verified", "position_restored", "settings_restored", "failure_type", "activity_state", "offer_visible", "artifacts"}
for line in process.stdout:
    if line.startswith("INSTRUMENTATION_STATUS: phase="):
        phase = line.strip().split("=", 1)[1]
        if re.fullmatch(r"[a-z_]+", phase):
            print("phase=" + phase, flush=True)
    if line.startswith("INSTRUMENTATION_RESULT: "):
        key, separator, value = line.strip().removeprefix("INSTRUMENTATION_RESULT: ").partition("=")
        if separator and key in allowed and re.fullmatch(r"[A-Za-z0-9_./:-]+", value):
            fields[key] = value == "true" if value in ("true", "false") else value
code = process.wait(timeout=10)
watchdog.cancel()
remote = fields.pop("artifacts", None)
if isinstance(remote, str) and re.fullmatch(r"/storage/emulated/0/Android/data/ru\.seasonvar\.tv/files/player-probe/run-[0-9]+", remote):
    subprocess.run(adb + ["pull", remote + "/.", str(args.output)], check=True, stdout=subprocess.DEVNULL)
(args.output / "result.json").write_text(json.dumps(fields, indent=2))
print(json.dumps(fields, sort_keys=True))
if fields.get("automatic_season") is not True or fields.get("season_disabled_stays") is not True or fields.get("season_pause_seek") is not True:
    raise SystemExit(1)
if fields.get("settings_restored") is not True:
    raise SystemExit(1)
if code or fields.get("script_success") is not True or fields.get("position_restored") is not True or fields.get("explicit_resume_verified") is not True:
    raise SystemExit(1)
print("Inspect the captured PNG frames; player states alone do not verify visible video.")
