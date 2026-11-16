import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

SPEC = importlib.util.spec_from_file_location("baseline_profile", Path(__file__).resolve().parents[1] / "scripts/check-baseline-profile.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
VERIFY_SPEC = importlib.util.spec_from_file_location("baseline_verify", Path(__file__).resolve().parents[1] / "scripts/verify-baseline-profile.py")
VERIFY = importlib.util.module_from_spec(VERIFY_SPEC)
VERIFY_SPEC.loader.exec_module(VERIFY)


class BaselineProfileTests(unittest.TestCase):
    def test_runtime_probe_rejects_a_physical_device_before_adb(self):
        with patch("sys.argv", ["probe", "--device", "physical-device", "--output", "unused.json"]), \
                patch.object(VERIFY.subprocess, "check_output") as execute, patch("sys.stderr"):
            with self.assertRaises(SystemExit):
                VERIFY.main()
            execute.assert_not_called()

    def test_runtime_probe_refuses_the_users_package_before_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "app/build/outputs/apk/benchmarkRelease"
            output.mkdir(parents=True)
            (output / "output-metadata.json").write_text(
                '{"applicationId":"ru.seasonvar.tv","variantName":"benchmarkRelease"}', encoding="utf-8")
            with patch("sys.argv", ["probe", "--device", "emulator-5554", "--output", str(root / "result.json")]), \
                    patch.object(VERIFY, "ROOT", root), patch.object(VERIFY.subprocess, "check_output") as execute:
                with self.assertRaises(SystemExit):
                    VERIFY.main()
                execute.assert_not_called()

    def test_generation_cannot_target_the_users_application(self):
        MODULE.check_generation({"applicationId": "ru.seasonvar.tv.baseline", "variantName": "nonMinifiedRelease"})
        for package, variant in [("ru.seasonvar.tv", "nonMinifiedRelease"), ("ru.seasonvar.tv.baseline", "release")]:
            with self.assertRaises(ValueError):
                MODULE.check_generation({"applicationId": package, "variantName": variant})

    def test_release_requires_own_rules_and_both_binary_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "baseline-prof.txt"
            apk = root / "app.apk"
            source.write_text("HSPLru/seasonvar/tv/MainActivity;->onCreate()V\nHSPLru/seasonvar/tv/ui/SeasonvarAppKt;->SeasonvarApp()V\n", encoding="utf-8")
            with ZipFile(apk, "w") as archive:
                archive.writestr("assets/dexopt/baseline.prof", b"pro\0" + bytes(12))
                archive.writestr("assets/dexopt/baseline.profm", b"prm\0" + bytes(12))
            self.assertEqual(2, MODULE.check_apk(apk, source)["application_rules"])
            source.write_text("HSPLandroidx/compose/runtime/Composer;->start()V\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                MODULE.check_apk(apk, source)
            source.write_text("HSPLru/seasonvar/tv/MainActivity;\nHSPLru/seasonvar/tv/ui/SeasonvarAppKt;\n", encoding="utf-8")
            with ZipFile(apk, "w") as archive:
                archive.writestr("assets/dexopt/baseline.prof", b"pro\0" + bytes(12))
            with self.assertRaises(KeyError):
                MODULE.check_apk(apk, source)
