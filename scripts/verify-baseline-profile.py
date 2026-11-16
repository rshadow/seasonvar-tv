"""Verify APK profile installation and ART compilation in an isolated emulator package."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
APP = "ru.seasonvar.tv.baseline"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.device):
        parser.error("Profile verification only supports an emulator")
    directory = ROOT / "app/build/outputs/apk/benchmarkRelease"
    metadata = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
    if metadata.get("applicationId") != APP or metadata.get("variantName") != "benchmarkRelease":
        raise SystemExit("Refusing to replace the user's application")
    spec = importlib.util.spec_from_file_location("baseline_check", ROOT / "scripts/check-baseline-profile.py")
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    apk = directory / metadata["elements"][0]["outputFile"]
    result = checker.check_apk(apk, ROOT / "app/src/main/generated/baselineProfiles/baseline-prof.txt")
    result["binary_application_rules"] = checker.check_binary(apk,
        ROOT / "app/build/outputs/mapping/benchmarkRelease/mapping.txt",
        ROOT / ".tools/android-sdk/cmdline-tools/latest/bin/profgen")
    adb = [str(ROOT / "scripts/android-env.sh"), "adb", "-s", args.device]

    def call(*command):
        return subprocess.check_output(adb + list(command), text=True, encoding="utf-8", timeout=120)

    call("install", "-r", str(apk))
    installed = call("shell", "am", "broadcast", "-a", "androidx.profileinstaller.action.INSTALL_PROFILE",
                     "-n", APP + "/androidx.profileinstaller.ProfileInstallReceiver")
    if not re.search(r"Broadcast completed: result=1\b", installed):
        raise SystemExit("ProfileInstaller did not confirm profile installation")
    compiled = call("shell", "cmd", "package", "compile", "-m", "speed-profile", "-f", APP)
    if compiled.strip() != "Success":
        raise SystemExit("ART compilation did not succeed")
    package = call("shell", "dumpsys", "package", APP)
    if "[status=speed-profile]" not in package:
        raise SystemExit("ART did not report speed-profile")
    result.update({"package": APP, "installer_result": 1, "compiler_filter": "speed-profile"})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
