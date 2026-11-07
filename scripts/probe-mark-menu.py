#!/usr/bin/env python3
"""Verify layout and focus with anonymous local states in an emulator."""
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
parser.add_argument("--mode", choices=("mark-menu-layout", "details-progress-layout"), default="mark-menu-layout")
args = parser.parse_args()
if not args.device.startswith("emulator-"):
    parser.error("This probe runs only on an emulator")
args.output.mkdir(parents=True, exist_ok=True)
adb = ["adb", "-s", args.device]
process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "mode", args.mode, args.runner],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
watchdog = threading.Timer(180, process.kill)
watchdog.daemon = True
watchdog.start()
fields = {}
allowed = {"geometry_stable", "refresh_focus_preserved", "save_focus_preserved", "blocked_actions", "fixture_only",
           "focus_preserved", "progress_visible",
           "script_success", "failure_type", "failure_phase", "failure_line", "failure_geometry", "failure_focus", "failure_counts", "artifacts"}
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
artifact_dir = "details-progress-probe" if args.mode == "details-progress-layout" else "mark-menu-probe"
if isinstance(remote, str) and re.fullmatch(r"/storage/emulated/0/Android/data/ru\.seasonvar\.tv/files/" + artifact_dir + r"/run-[0-9]+", remote):
    copied = subprocess.run(adb + ["pull", remote + "/.", str(args.output)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    fields["artifacts_copied"] = copied.returncode == 0
(args.output / "result.json").write_text(json.dumps(fields, indent=2))
print(json.dumps(fields, sort_keys=True))
checks = ("geometry_stable", "refresh_focus_preserved", "save_focus_preserved", "blocked_actions", "fixture_only", "script_success", "artifacts_copied")
if args.mode == "details-progress-layout":
    checks = ("geometry_stable", "focus_preserved", "progress_visible", "fixture_only", "script_success", "artifacts_copied")
if code or any(fields.get(key) is not True for key in checks):
    raise SystemExit(1)
