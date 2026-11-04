#!/usr/bin/env python3
"""Build a newer test-signed APK without editing the project's release version."""
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
configuration = (ROOT / "app/build.gradle.kts").read_text()
version_match = re.search(r'versionName = "(\d+)\.(\d+)\.(\d+)"', configuration)
code_match = re.search(r'versionCode = (\d+)', configuration)
if not version_match or not code_match:
    raise SystemExit("Cannot determine the project's version")
major, minor, patch = map(int, version_match.groups())
version = f"{major}.{minor}.{patch + 1}"
code = int(code_match[1]) + 1
output = ROOT / "build/update-probe"
output.mkdir(parents=True, exist_ok=True)
init = output / "fixture.init.gradle"
init.write_text("""gradle.beforeProject { project ->
    project.plugins.withId('com.android.application') {
        project.androidComponents.finalizeDsl { android ->
            android.defaultConfig.versionCode = %d
            android.defaultConfig.versionName = '%s'
        }
    }
}
""" % (code, version))
subprocess.run([str(ROOT / "scripts/android-env.sh"), "./gradlew", "--console=plain",
                "--init-script", str(init), ":app:assembleDebug"], cwd=ROOT, check=True)
shutil.copyfile(ROOT / "app/build/outputs/apk/debug/app-debug.apk", output / "fixture.apk")
print(f"Prepared test update {version} ({code}); not a release")
