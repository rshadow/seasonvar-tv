"""Parse Android HWUI reports without retaining UI content."""
import math
import re


def frame_statistics(raw):
    report = {}
    for label, pattern in {
        "frames": r"Total frames rendered: (\d+)",
        "janky": r"Janky frames: (\d+)",
        "p50_ms": r"50th percentile: (\d+)ms",
        "p90_ms": r"90th percentile: (\d+)ms",
        "p99_ms": r"99th percentile: (\d+)ms",
    }.items():
        match = re.search(pattern, raw)
        if match:
            report[label] = int(match[1])
    durations = []
    columns = None
    for line in raw.splitlines():
        if line.startswith("Flags,") and "IntendedVsync" in line:
            columns = line.split(",")
        elif columns and line[:1].isdigit():
            values = dict(zip(columns, line.split(",")))
            if values.get("Flags") == "0":
                start, end = int(values["IntendedVsync"]), int(values["FrameCompleted"])
                if start > 0 and end > start:
                    durations.append((end - start) / 1_000_000)
    if durations:
        durations.sort()
        report.update(frame_stats_count=len(durations), max_frame_ms=round(durations[-1], 1),
                      raw_p95_ms=round(durations[math.ceil(len(durations) * 0.95) - 1], 2),
                      frames_over_100ms=sum(x > 100 for x in durations))
    return report
