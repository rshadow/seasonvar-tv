#!/usr/bin/env python3
import argparse
import json
import re
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True)
    parser.add_argument("--runner", required=True)
    parser.add_argument("--mode", choices=("inspect", "roundtrip", "delete-roundtrip", "status-roundtrip", "catalog-badges", "smart-inspect", "season-messages"), default="inspect")
    parser.add_argument("--target-season", default="")
    args = parser.parse_args()
    if args.target_season and not re.fullmatch(r"[1-9][0-9]*", args.target_season):
        parser.error("Expected a numeric season ID")
    command = ["adb", "-s", args.device, "shell", "am", "instrument", "-w", "-r", "-e", "mode", args.mode]
    if args.target_season:
        command += ["-e", "target-season", args.target_season]
    command.append(args.runner)
    result = subprocess.run(command, capture_output=True, text=True, timeout=300 if args.mode == "smart-inspect" else 120)
    fields = {}
    allowed = {"authenticated", "existing_playback_mark", "mark_declaration_present", "mark_parsed",
               "probe_success", "post_verified", "statuses_verified", "original_restored", "concurrent_change_left_intact",
               "removed_from_lists", "mark_field_names", "href_type", "translation_type", "phase", "failure_type"}
    allowed.update({"smart_mark_count", "smart_duration_ms", "smart_mark_requests", "smart_history_requests",
                    "smart_season_requests", "smart_total_requests", "smart_request_peak", "history_seasons",
                    "smart_count", "smart_incomplete", "smart_failures", "history_count", "history_has_dates", "history_has_more", "smart_target_found",
                    "smart_target_episode", "smart_target_seek_ms", "history_target_found"})
    allowed.update(prefix + suffix for prefix in ("today", "updates") for suffix in
                   ("_target_found", "_classes", "_child_classes", "_attributes", "_script_variables", "_marker_parents", "_target_index", "_watching_parsed"))
    allowed.update(("read_only", "want_example_playback_mark", "want_example_status"))
    allowed.update(tab + suffix for tab in ("new", "nonew", "want", "watched")
                   for suffix in ("_rows", "_season_messages", "_messages_without_counter", "_messages_zero_counter", "_messages_older_season"))
    allowed.update("season_" + season + suffix for season in ("5", "6")
                   for suffix in ("_listed", "_message", "_playback_mark", "_status"))
    for line in result.stdout.splitlines():
        if not line.startswith("INSTRUMENTATION_RESULT: "):
            continue
        key, separator, value = line.removeprefix("INSTRUMENTATION_RESULT: ").partition("=")
        if key not in allowed or not separator:
            continue
        if value in ("true", "false"):
            fields[key] = value == "true"
        elif re.fullmatch(r"[A-Za-z0-9_.,:-]+", value):
            fields[key] = value
    print(json.dumps(fields, sort_keys=True))
    success = result.returncode == 0 and fields.get("probe_success") is True
    if args.mode in ("roundtrip", "delete-roundtrip", "status-roundtrip"):
        success = success and fields.get("post_verified") is True and (
            fields.get("original_restored") is True or fields.get("concurrent_change_left_intact") is True)
    if args.mode == "delete-roundtrip":
        success = success and fields.get("removed_from_lists") is True
    if not success:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
