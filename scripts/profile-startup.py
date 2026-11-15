#!/usr/bin/env python3
"""Measure real process-cold MainActivity launches without changing saved preferences."""
import argparse
import json
from pathlib import Path
import re
import statistics
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
APP = "ru.seasonvar.tv"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--pages", nargs="+", choices=("saved", "today", "settings"),
                        default=("saved", "today", "settings"))
    args = parser.parse_args()
    if not args.device.startswith("emulator-") or not 1 <= args.rounds <= 10:
        parser.error("Use an emulator and 1..10 rounds")
    adb = [str(ROOT / "scripts/android-env.sh"), "adb", "-s", args.device]

    def call(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=60)

    call("get-state")
    if "DEBUGGABLE" in call("shell", "dumpsys", "package", APP):
        raise SystemExit("Install the non-debuggable profile build first")
    if "mWakefulness=Awake" not in call("shell", "dumpsys", "power"):
        raise SystemExit("Wake the emulator before measuring")
    args.output.mkdir(parents=True, exist_ok=True)
    reports = []
    for trial in range(args.rounds):
        for page in args.pages:
            run = f"{page}-{uuid.uuid4().hex[:12]}"
            call("shell", "am", "force-stop", APP)
            process = subprocess.run(adb + ["shell", "pidof", APP], text=True, capture_output=True, timeout=60)
            if process.returncode not in (0, 1) or process.stdout.strip():
                raise RuntimeError("App process still exists after force-stop")
            command = ["shell", "am", "start", "-W", "-n", APP + "/.MainActivity",
                       "--es", "startup_probe_run", run]
            if page != "saved":
                command += ["--es", "startup_probe_start", page]
            timing = call(*command)
            if re.search(r"^Status: ok$", timing, re.MULTILINE) is None:
                raise RuntimeError("Activity launch did not succeed")
            call("shell", "am", "broadcast", "-n", APP + "/.probe.StartupReportReceiver",
                 "--es", "run", run)
            report = json.loads(call("shell", "cat",
                                    f"/sdcard/Android/data/{APP}/files/startup-profile/{run}/metrics.json"))
            report.update(page=page, trial=trial, process_absent_before_launch=True, activity_timing={
                key: int(value) for key, value in re.findall(r"^(TotalTime|WaitTime): (\d+)$", timing, re.MULTILINE)
            })
            launch = re.search(r"^LaunchState: (.+)$", timing, re.MULTILINE)
            report["launch_state"] = launch.group(1) if launch else None
            reports.append(report)
            (args.output / f"{page}-{trial}.json").write_text(json.dumps(report, indent=2) + "\n")
            print(f"Completed {page} trial={trial}: {report['activity_timing']} "
                  f"ready={not report['timed_out']}", flush=True)
    (args.output / "runs.json").write_text(json.dumps(reports, indent=2) + "\n")
    for page in args.pages:
        samples = [r for r in reports if r["page"] == page]
        times = [r["activity_timing"]["TotalTime"] for r in samples
                 if r["launch_state"] == "COLD" and "TotalTime" in r["activity_timing"]]
        print(f"{page}: TTID median={statistics.median(times) if times else None}ms "
              f"valid={len(times)}/{len(samples)}")


if __name__ == "__main__":
    main()
