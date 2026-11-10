#!/usr/bin/env python3
import argparse
import json
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--device", required=True)
parser.add_argument("--runner", required=True)
args = parser.parse_args()
if not args.device.startswith("emulator-"):
    parser.error("Use an emulator")
result = subprocess.run(["adb", "-s", args.device, "shell", "am", "instrument", "-w", "-r",
                         "-e", "mode", "catalog-live", args.runner], capture_output=True, text=True, timeout=300)
report = {}
for line in result.stdout.splitlines():
    if line.startswith("INSTRUMENTATION_RESULT: result_json="):
        report = json.loads(line.split("=", 1)[1])
print(json.dumps(report, sort_keys=True))
if result.returncode or report.get("probe_success") is not True:
    raise SystemExit(1)
