"""Measure the currently open series card in a profile build without starting video."""
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
    parser.add_argument("--mode", required=True,
                        help="Select this appearance in Settings and open a series card before running")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--rounds", type=int, default=config["details_rounds"])
    args = parser.parse_args()
    if not 1 <= args.rounds <= 10:
        parser.error("rounds must be between 1 and 10")
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [str(ROOT / "scripts/android-env.sh"), "adb", "-s", args.device]

    def call(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=60)

    if "DEBUGGABLE" in call("shell", "dumpsys", "package", APP):
        raise SystemExit("Use the non-debuggable profile build")
    if "mWakefulness=Awake" not in call("shell", "dumpsys", "power"):
        raise SystemExit("Wake the device before measuring")

    def ui():
        call("shell", "uiautomator", "dump", "/data/local/tmp/seasonvar-details-profile.xml")
        root = ET.fromstring(call("shell", "cat", "/data/local/tmp/seasonvar-details-profile.xml"))
        if not any(n.get("package") == APP for n in root.iter("node")):
            raise SystemExit("Seasonvar is no longer the active application; measurement stopped")
        return root

    def details(root):
        return any(n.get("text", "").startswith("Рецензии ·") for n in root.iter("node"))

    def markers():
        log = call("logcat", "-d", "-s", "SeasonvarDetailsTiming:I", "*:S")
        return [(line, int(match.group(1)), match.group(2)) for line in log.splitlines()
                if (match := re.search(r"ready_us=(\d+) appearance=(\w+)", line))]

    source_label = None

    def reopen():
        nonlocal source_label
        before = markers()
        previous = before[-1][0] if before else None
        call("shell", "input", "keyevent", "KEYCODE_BACK")
        root = ui()
        if details(root):
            raise SystemExit("Back did not return to the source list")
        focus = next((n for n in root.iter("node") if n.get("focused") == "true"), None)
        label = " ".join(n.get("text", "") + n.get("content-desc", "") for n in focus.iter("node")) if focus is not None else ""
        if focus is None or focus.get("clickable") != "true" or any(x in label for x in ("Показать", "Обновить", "Повторить", "Поиск", "Настройки")):
            raise SystemExit("The source series card is not focused")
        if source_label is None:
            source_label = label
        elif source_label != label:
            raise SystemExit("The focused source card changed; measurement stopped")
        bounds = [int(value) for value in re.findall(r"\d+", focus.get("bounds", ""))]
        if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
            raise SystemExit("The source card has no visible bounds")
        call("shell", "dumpsys", "gfxinfo", APP, "reset")
        call("shell", "input", "keyevent", "KEYCODE_DPAD_CENTER")
        for attempt in range(5):
            root = ui()
            values = markers()
            if details(root) and values and values[-1][0] != previous:
                _, value, mode = values[-1]
                if mode != args.mode:
                    raise SystemExit("The selected appearance does not match --mode")
                return value / 1000, call("shell", "dumpsys", "gfxinfo", APP, "framestats")
            if attempt < 4 and not details(root):
                call("shell", "input", "keyevent", "KEYCODE_DPAD_CENTER")
        raise SystemExit("No profile readiness marker for the opened card")

    if not details(ui()):
        raise SystemExit("Open a series card before measuring")
    for _ in range(config["details_warmup_opens"]):
        reopen()
    reports = []
    for index in range(args.rounds):
        ready, opening = reopen()
        (args.output / ("opening-frames-" + str(index + 1) + ".txt")).write_text(opening, encoding="utf-8")
        call("shell", "dumpsys", "gfxinfo", APP, "reset")
        keys = ["DPAD_DOWN"] + ["DPAD_RIGHT", "DPAD_LEFT"] * 8 + ["PAGE_DOWN", "PAGE_UP"]
        interval = config["details_key_interval_seconds"]
        workload = "; ".join("input keyevent KEYCODE_" + key + "; sleep " + str(interval) for key in keys)
        call("shell", workload)
        raw = call("shell", "dumpsys", "gfxinfo", APP, "framestats")
        if not details(ui()):
            raise SystemExit("Navigation left the series card; measurement stopped")
        (args.output / ("frames-" + str(index + 1) + ".txt")).write_text(raw, encoding="utf-8")
        report = frame_statistics(raw)
        report.update({"opening_" + key: value for key, value in frame_statistics(opening).items()})
        report.update(round=index + 1, ready_ms=round(ready, 2))
        memory = call("shell", "dumpsys", "meminfo", APP)
        for key, pattern in {"pss_kib": r"TOTAL PSS:\s+(\d+)", "java_kib": r"Java Heap:\s+(\d+)",
                             "native_kib": r"Native Heap:\s+(\d+)", "graphics_kib": r"Graphics:\s+(\d+)"}.items():
            match = re.search(pattern, memory)
            if match:
                report[key] = int(match[1])
        reports.append(report)
        print(json.dumps(report), flush=True)
    keys = ("ready_ms", "opening_raw_p95_ms", "opening_max_frame_ms", "raw_p95_ms", "max_frame_ms", "pss_kib", "java_kib", "native_kib", "graphics_kib")
    summary = {"mode": args.mode, "opening": "activate_focused_source_card", "rounds": reports,
               "medians": {key: round(statistics.median(r[key] for r in reports if key in r), 2)
                           for key in keys if any(key in r for r in reports)}}
    (args.output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"mode": args.mode, "medians": summary["medians"]}), flush=True)


if __name__ == "__main__":
    main()
