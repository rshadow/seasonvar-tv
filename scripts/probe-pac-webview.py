#!/usr/bin/env python3
"""Check PAC activation with the emulator WebView provider temporarily disabled."""
import argparse
import json
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--device", required=True)
parser.add_argument("--runner", required=True)
args = parser.parse_args()
if not args.device.startswith("emulator-"):
    parser.error("Use an emulator")
adb = ["adb", "-s", args.device]
status = subprocess.run(adb + ["shell", "dumpsys", "webviewupdate"], check=True, capture_output=True, text=True).stdout
provider = re.search(r"Current WebView package \(name, version\): \(([A-Za-z0-9_.]+),", status)
if not provider:
    raise SystemExit("No enabled WebView provider to restore")
package = provider.group(1)
try:
    subprocess.run(adb + ["shell", "pm", "disable-user", "--user", "0", package], check=True, stdout=subprocess.DEVNULL)
    completed = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "mode", "pac-no-webview", args.runner],
                               capture_output=True, text=True, timeout=60)
    success = completed.returncode == 0 and "INSTRUMENTATION_RESULT: missing_webview_keeps_previous_proxy=true" in completed.stdout
    print(json.dumps({"missing_webview_keeps_previous_proxy": success}))
finally:
    subprocess.run(adb + ["shell", "am", "force-stop", args.runner.split(".test/", 1)[0]], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(adb + ["shell", "pm", "enable", "--user", "0", package], check=True, stdout=subprocess.DEVNULL)
if not success:
    raise SystemExit(1)
