"""Compare poster backgrounds while navigating the currently focused list."""
import argparse
import json
from pathlib import Path
import re
import statistics
import subprocess
import xml.etree.ElementTree as ET
from frame_statistics import frame_statistics

ROOT = Path(__file__).resolve().parents[1]
APP = "ru.seasonvar.tv"


def main():
    config = json.loads((ROOT / "toolchain.lock.json").read_text())["performance"]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--mode", choices=("plain", "blurred_poster"), required=True)
    parser.add_argument("--layout", choices=("strip", "grid"), required=True)
    parser.add_argument("--rounds", type=int, default=config["list_rounds"])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.rounds <= 10:
        parser.error("rounds must be between 1 and 10")
    adb = [str(ROOT / "scripts/android-env.sh"), "adb", "-s", args.device]

    def call(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=60)

    if "DEBUGGABLE" in call("shell", "dumpsys", "package", APP):
        raise SystemExit("Use the non-debuggable profile build")
    if "mWakefulness=Awake" not in call("shell", "dumpsys", "power"):
        raise SystemExit("Wake the device before measuring")

    def check():
        call("shell", "uiautomator", "dump", "/data/local/tmp/seasonvar-list-profile.xml")
        root = ET.fromstring(call("shell", "cat", "/data/local/tmp/seasonvar-list-profile.xml"))
        focus = next((n for n in root.iter("node") if n.get("focused") == "true"), None)
        if focus is None or focus.get("package") != APP or focus.get("clickable") != "true":
            raise SystemExit("Focus a series thumbnail before measuring")
        if any(n.get("text", "").startswith("Рецензии ·") for n in root.iter("node")):
            raise SystemExit("Return from details to the source list")
        bounds = [int(x) for x in re.findall(r"\d+", focus.get("bounds", ""))]
        screen = [int(x) for x in re.findall(r"\d+", next(root.iter("node")).get("bounds", ""))]
        if len(bounds) != 4 or len(screen) != 4 or bounds[3] - bounds[1] < (screen[3] - screen[1]) * 0.15:
            raise SystemExit("Navigation left the thumbnails; measurement stopped")
        log = call("logcat", "-d", "-s", "SeasonvarCatalogProfile:I", "*:S")
        markers = re.findall(r"layout=(strip|grid) backdrop=(true|false)", log)
        if not markers or markers[-1] != (args.layout, str(args.mode == "blurred_poster").lower()):
            raise SystemExit("The active layout or appearance does not match the requested measurement")
        return tuple((n.get("text", ""), n.get("content-desc", "")) for n in focus.iter("node"))

    check()
    interval = config["list_key_interval_seconds"]
    keys = ["DPAD_RIGHT", "DPAD_LEFT"] * 8
    workload = "; ".join("input keyevent KEYCODE_" + key + "; sleep " + str(interval) for key in keys)
    for _ in range(config["list_warmup_rounds"]):
        call("shell", workload)
        check()
    signature = check()
    args.output.mkdir(parents=True, exist_ok=True)
    reports = []
    for index in range(args.rounds):
        call("shell", "dumpsys", "gfxinfo", APP, "reset")
        call("shell", workload)
        raw = call("shell", "dumpsys", "gfxinfo", APP, "framestats")
        if check() != signature:
            raise SystemExit("The balanced navigation did not return to the same thumbnail")
        report = {"round": index + 1, **frame_statistics(raw)}
        memory = re.search(r"TOTAL PSS:\s+(\d+)", call("shell", "dumpsys", "meminfo", APP))
        if memory:
            report["pss_kib"] = int(memory[1])
        (args.output / ("frames-" + str(index + 1) + ".txt")).write_text(raw, encoding="utf-8")
        reports.append(report)
        print(json.dumps(report), flush=True)
    metrics = ("raw_p95_ms", "max_frame_ms", "pss_kib")
    summary = {"mode": args.mode, "layout": args.layout, "rounds": reports,
               "medians": {key: round(statistics.median(r[key] for r in reports if key in r), 2)
                           for key in metrics if any(key in r for r in reports)}}
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: summary[key] for key in ("mode", "layout", "medians")}), flush=True)


if __name__ == "__main__":
    main()
