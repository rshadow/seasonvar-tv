#!/usr/bin/env python3
import argparse
import json
import re
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True)
    parser.add_argument("--runner", required=True)
    args = parser.parse_args()
    if not args.device.startswith("emulator-"):
        parser.error("Use an emulator for premium probes")
    result = subprocess.run(["adb", "-s", args.device, "shell", "am", "instrument", "-w", "-e",
                             "mode", "account-premium", args.runner], capture_output=True, text=True, timeout=120)
    required = {"home_verified", "resume_verified", "premium_verified", "profile_verified",
                "refresh_keeps_session", "probe_success"}
    fields = {}
    for line in result.stdout.splitlines():
        if not line.startswith("INSTRUMENTATION_RESULT: "):
            continue
        key, separator, value = line.removeprefix("INSTRUMENTATION_RESULT: ").partition("=")
        if separator and key in required and value in ("true", "false"):
            fields[key] = value == "true"
        elif key == "premium_until" and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", value):
            fields[key] = value
        elif key == "failure_type" and re.fullmatch(r"[A-Za-z]+", value):
            fields[key] = value
        elif key in ("phase", "premium_kind") and re.fullmatch(r"[A-Za-z_]+", value):
            fields[key] = value
        elif key == "menu_label" and re.fullmatch(r"Премиум[А-Яа-яЁё0-9 .:()\u00a0-]*", value):
            fields[key] = value
    print(json.dumps(fields, sort_keys=True))
    if result.returncode or not all(fields.get(key) is True for key in required):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
