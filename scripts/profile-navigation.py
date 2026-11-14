#!/usr/bin/env python3
"""Capture public-menu navigation on an optimized, profileable installation."""
import argparse
import json
from pathlib import Path
import subprocess
import time
from frame_statistics import frame_statistics

ROOT = Path(__file__).resolve().parents[1]
APP = "ru.seasonvar.tv"


def main():
    configuration = json.loads((ROOT / "toolchain.lock.json").read_text())["performance"]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--rounds", type=int, default=configuration["navigation_rounds"])
    parser.add_argument("--sample", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.rounds <= 10:
        parser.error("rounds must be between 1 and 10")
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [str(ROOT / "scripts/android-env.sh"), "adb", "-s", args.device]

    def call(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=60)

    power = call("shell", "dumpsys", "power")
    if "mWakefulness=Awake" not in power:
        raise SystemExit("Wake the device before measuring: make key KEY=WAKEUP DEVICE=...")
    package = call("shell", "dumpsys", "package", APP)
    if "DEBUGGABLE" in package:
        raise SystemExit("Install the non-debuggable profile build with make run-profile first")
    call("shell", "am", "force-stop", APP)
    startup = call("shell", "am", "start", "-W", "-n", APP + "/.MainActivity")
    (args.output / "startup.txt").write_text(startup)
    time.sleep(1)
    remote = "/data/local/tmp/seasonvar-navigation.trace"
    sampling = False
    reports = []
    try:
        if args.sample:
            call("shell", "am", "profile", "start", "--sampling", str(configuration["method_sample_interval_us"]), APP, remote)
            sampling = True
        for index in range(args.rounds):
            call("shell", "dumpsys", "gfxinfo", APP, "reset")
            keys = ["DPAD_DOWN"] * 6 + ["DPAD_UP"] * 6
            interval = configuration["navigation_key_interval_seconds"]
            command = "; ".join("input keyevent KEYCODE_" + key + "; sleep " + str(interval) for key in keys)
            call("shell", command)
            time.sleep(0.5)
            raw = call("shell", "dumpsys", "gfxinfo", APP, "framestats")
            (args.output / ("frames-" + str(index + 1) + ".txt")).write_text(raw)
            report = {"round": index + 1, "sampled": args.sample, **frame_statistics(raw)}
            reports.append(report)
            print(json.dumps(report), flush=True)
    finally:
        if sampling:
            call("shell", "am", "profile", "stop", APP)
            call("pull", remote, str(args.output / "navigation.trace"))
            call("shell", "rm", "-f", remote)
        (args.output / "summary.json").write_text(json.dumps(reports, indent=2) + "\n")


if __name__ == "__main__":
    main()
