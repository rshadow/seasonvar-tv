#!/usr/bin/env python3
import argparse
import json
import re
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True)
    parser.add_argument("--runner", required=True)
    parser.add_argument("--mode", choices=("inspect", "roundtrip"), default="inspect")
    args = parser.parse_args()
    result = subprocess.run(["adb", "-s", args.device, "shell", "am", "instrument", "-w", "-r",
                             "-e", "mode", args.mode, args.runner], capture_output=True, text=True, timeout=120)
    fields = {}
    allowed = {"authenticated", "existing_playback_mark", "mark_declaration_present", "mark_parsed",
               "probe_success", "post_verified", "original_restored", "concurrent_change_left_intact",
               "mark_field_names", "href_type", "translation_type", "phase", "failure_type"}
    for line in result.stdout.splitlines():
        if not line.startswith("INSTRUMENTATION_RESULT: "):
            continue
        key, separator, value = line.removeprefix("INSTRUMENTATION_RESULT: ").partition("=")
        if key not in allowed or not separator:
            continue
        if value in ("true", "false"):
            fields[key] = value == "true"
        elif re.fullmatch(r"[A-Za-z0-9_,]+", value):
            fields[key] = value
    print(json.dumps(fields, sort_keys=True))
    success = result.returncode == 0 and fields.get("probe_success") is True
    if args.mode == "roundtrip":
        success = success and fields.get("post_verified") is True and (
            fields.get("original_restored") is True or fields.get("concurrent_change_left_intact") is True)
    if not success:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
