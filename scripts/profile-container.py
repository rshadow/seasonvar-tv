#!/usr/bin/env python3
"""Measure hidden-list updates against the unchanged Compose container on an emulator."""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
APP = "ru.seasonvar.tv"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--theme", choices=("both", "plain", "glass"), default="both")
    parser.add_argument("--trace-mode", choices=("both", "on", "off"), default="both")
    parser.add_argument("--samples", type=int, default=48)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--asynchronous", action="store_true")
    parser.add_argument("--planner", action="store_true")
    parser.add_argument("--layout", choices=("strip", "grid"), default="strip")
    parser.add_argument("--prefetch", choices=("on", "off"), default="on")
    parser.add_argument("--lookup", action="store_true")
    args = parser.parse_args()
    if args.lookup and (args.planner or args.asynchronous):
        parser.error("lookup is a separate benchmark mode")
    if not args.device.startswith("emulator-"):
        parser.error("This fixture probe only supports an emulator")
    if not 12 <= args.samples <= 240 or not 1 <= args.rounds <= 5:
        parser.error("samples must be 12..240; rounds must be 1..5")
    if args.planner and (args.samples % 2 or args.layout == "grid" and args.samples > 96):
        parser.error("planner requires an even sample count; grid supports at most 96")
    adb = [str(ROOT / "scripts/android-env.sh"), "adb", "-s", args.device]

    def call(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=60)

    call("get-state")
    if "DEBUGGABLE" in call("shell", "dumpsys", "package", APP):
        raise SystemExit("Install the non-debuggable profile build first")
    if "mWakefulness=Awake" not in call("shell", "dumpsys", "power"):
        raise SystemExit("Wake the emulator before measuring")
    args.output.mkdir(parents=True, exist_ok=True)
    themes = ("plain", "glass") if args.theme == "both" else (args.theme,)
    modes = (False, True) if args.trace_mode == "both" else (args.trace_mode == "on",)
    reports = []
    for tracing in modes:
        for theme in themes:
            run = f"{theme}-{'trace' if tracing else 'frames'}-{uuid.uuid4().hex[:12]}"
            call("shell", "am", "force-stop", APP)
            port = int(call("forward", "tcp:0", f"localabstract:container-profile-{run}").strip())
            try:
                call("shell", "am", "start", "-W", "-n", APP + "/.probe.ContainerProfileActivity",
                     "--es", "theme", theme, "--ez", "trace", str(tracing).lower(),
                     "--ez", "asynchronous", str(args.asynchronous).lower(),
                     "--ez", "planner", str(args.planner).lower(), "--es", "layout", args.layout,
                     "--ez", "prefetch", str(args.prefetch == "on").lower(),
                     "--ez", "lookup", str(args.lookup).lower(),
                     "--ei", "samples", str(args.samples), "--ei", "rounds", str(args.rounds), "--es", "run", run)
                with socket.create_connection(("127.0.0.1", port), timeout=args.samples * args.rounds + 120) as connection:
                    with connection.makefile("r", encoding="utf-8") as stream:
                        line = stream.readline().strip()
                if line not in (f"complete {run}", f"failed {run}"):
                    raise RuntimeError("Missing profile completion signal")
                success = line.startswith("complete")
            finally:
                call("forward", "--remove", f"tcp:{port}")
            filename = "metrics.json" if success else "failure.json"
            remote = f"/sdcard/Android/data/{APP}/files/container-profile/{run}/{filename}"
            raw = call("shell", "cat", remote)
            report = json.loads(raw)
            target = args.output / (f"{theme}-{'trace' if tracing else 'frames'}.json")
            target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            if not success:
                raise RuntimeError(f"Probe failed; see {target}")
            reports.append(str(target))
            print(f"Completed {theme} tracing={tracing}: {target}", flush=True)
    (args.output / "runs.json").write_text(json.dumps(reports, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
