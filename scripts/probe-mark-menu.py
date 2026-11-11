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
parser.add_argument("--mode", choices=("connection-checks", "proxy-settings", "catalog-layout", "mark-menu-layout", "details-progress-layout", "history-clear-layout", "ui-screens-layout", "ui-dialogs-layout", "series-list-engine", "shared-card-cache", "cache-memory"), default="mark-menu-layout")
args = parser.parse_args()
if not args.device.startswith("emulator-"):
    parser.error("This probe runs only on an emulator")
args.output.mkdir(parents=True, exist_ok=True)
adb = ["adb", "-s", args.device]
process = subprocess.Popen(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "mode", args.mode, args.runner],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
watchdog = threading.Timer(300 if args.mode in ("shared-card-cache", "cache-memory") else 180, process.kill)
watchdog.daemon = True
watchdog.start()
fields = {}
allowed = {"live_available", "live_blocked", "live_recovered", "proxy_saved", "proxy_cancelled", "proxy_fail_closed", "session_readable", "memory_measured", "read_only", "native_total_kib", "native_common_kib", "native_private_kib", "poster_kib", "poster_limit_kib", "poster_keys", "live_card_records", "native_shared", "private_cleared", "memory_bounded", "cache_records", "cache_heap_growth_kib", "descriptions_prefetched", "append_bounded", "window_bounded", "prefetch_toggle", "geometry_stable", "refresh_focus_preserved", "save_focus_preserved", "blocked_actions", "fixture_only",
           "focus_preserved", "progress_visible", "text_centered", "screens_verified", "dialogs_verified",
           "sort_reset", "filter_reset", "defaults_reset", "refresh_failure_preserved",
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
artifact_dir = "connection-checks-probe" if args.mode == "connection-checks" else "proxy-settings-probe" if args.mode == "proxy-settings" else "catalog-layout-probe" if args.mode == "catalog-layout" else "cache-memory-probe" if args.mode == "cache-memory" else "shared-card-cache-probe" if args.mode == "shared-card-cache" else "series-list-probe" if args.mode == "series-list-engine" else "ui-screens-probe" if args.mode in ("ui-screens-layout", "ui-dialogs-layout") else "details-progress-probe" if args.mode == "details-progress-layout" else "history-clear-probe" if args.mode == "history-clear-layout" else "mark-menu-probe"
if isinstance(remote, str) and re.fullmatch(r"/storage/emulated/0/Android/data/ru\.seasonvar\.tv/files/" + artifact_dir + r"/run-[0-9]+", remote):
    copied = subprocess.run(adb + ["pull", remote + "/.", str(args.output)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    fields["artifacts_copied"] = copied.returncode == 0
(args.output / "result.json").write_text(json.dumps(fields, indent=2))
print(json.dumps(fields, sort_keys=True))
checks = ("geometry_stable", "refresh_focus_preserved", "save_focus_preserved", "blocked_actions", "fixture_only", "script_success", "artifacts_copied")
if args.mode == "connection-checks":
    checks = ("geometry_stable", "refresh_focus_preserved", "live_available", "live_blocked", "live_recovered", "script_success", "artifacts_copied")
if args.mode == "proxy-settings":
    checks = ("proxy_saved", "proxy_cancelled", "proxy_fail_closed", "session_readable", "text_centered", "fixture_only", "script_success", "artifacts_copied")
if args.mode == "details-progress-layout":
    checks = ("geometry_stable", "focus_preserved", "progress_visible", "fixture_only", "script_success", "artifacts_copied")
if args.mode == "history-clear-layout":
    checks = ("geometry_stable", "text_centered", "focus_preserved", "blocked_actions", "fixture_only", "script_success", "artifacts_copied")
if args.mode == "ui-screens-layout":
    checks = ("screens_verified", "dialogs_verified", "text_centered", "fixture_only", "script_success", "artifacts_copied")
if args.mode == "ui-dialogs-layout":
    checks = ("dialogs_verified", "text_centered", "fixture_only", "script_success", "artifacts_copied")
if args.mode in ("series-list-engine", "shared-card-cache"):
    checks = ("descriptions_prefetched", "append_bounded", "window_bounded", "prefetch_toggle", "geometry_stable", "focus_preserved", "fixture_only", "script_success", "artifacts_copied")
if args.mode == "shared-card-cache":
    checks += ("native_shared", "private_cleared", "memory_bounded")
if args.mode == "catalog-layout":
    checks = ("sort_reset", "filter_reset", "defaults_reset", "refresh_failure_preserved",
              "window_bounded", "focus_preserved", "geometry_stable", "text_centered", "fixture_only", "script_success", "artifacts_copied")
if args.mode == "cache-memory":
    checks = ("memory_measured", "read_only", "script_success", "artifacts_copied")
if code or any(fields.get(key) is not True for key in checks):
    raise SystemExit(1)
