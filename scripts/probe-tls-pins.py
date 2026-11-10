#!/usr/bin/env python3
import argparse
import json
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True)
    parser.add_argument("--runner", required=True)
    args = parser.parse_args()
    if not args.device.startswith("emulator-"):
        parser.error("Use an emulator for TLS probes")
    result = subprocess.run(["adb", "-s", args.device, "shell", "am", "instrument", "-w", "-e",
                             "mode", "tls-pins", args.runner], capture_output=True, text=True, timeout=300)
    hosts = ("seasonvar.ru", "www.seasonvar.ru", "cdn.svstat.net", "github.com", "api.github.com",
             "objects.githubusercontent.com", "release-assets.githubusercontent.com", "dns.quad9.net")
    required = {host.replace(".", "_") + "_verified" for host in hosts}
    required.add("release_notes_verified")
    allowed = required | {"probe_success", "phase", "failure_type"}
    fields = {}
    for line in result.stdout.splitlines():
        if line.startswith("INSTRUMENTATION_RESULT: "):
            key, separator, value = line.removeprefix("INSTRUMENTATION_RESULT: ").partition("=")
            if separator and key in allowed:
                fields[key] = value == "true" if value in ("true", "false") else value
    print(json.dumps(fields, sort_keys=True))
    if result.returncode or fields.get("probe_success") is not True or not all(fields.get(key) is True for key in required):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
