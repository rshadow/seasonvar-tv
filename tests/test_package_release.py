import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("package_release", Path(__file__).resolve().parents[1] / "scripts/package-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleasePackageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.apk = self.root / "input.apk"
        self.apk.write_bytes(b"synthetic APK content")
        self.output = self.root / "release"
        self.metadata = {"applicationId": "ru.seasonvar.tv", "elements": [{"versionName": "0.2.0", "versionCode": 1}]}

    def test_packages_apk_with_matching_checksum(self):
        result = release.package_release(self.metadata, self.apk, self.output, "v0.2.0")
        self.assertEqual(result.name, "seasonvar-tv-0.2.0-universal.apk")
        self.assertEqual(result.read_bytes(), self.apk.read_bytes())
        expected = hashlib.sha256(self.apk.read_bytes()).hexdigest() + "  " + result.name + "\n"
        self.assertEqual((self.output / "SHA256SUMS").read_text(), expected)

    def test_rejects_tag_mismatch_before_writing(self):
        with self.assertRaises(ValueError):
            release.package_release(self.metadata, self.apk, self.output, "v0.3.0")
        self.assertFalse(self.output.exists())

    def test_rejects_unsafe_or_missing_semantic_version(self):
        for version in ("../../escape", "0.2.0/extra", "0.2", "0.2.0+\n", "v0.2.0"):
            self.metadata["elements"][0]["versionName"] = version
            with self.assertRaises(ValueError):
                release.package_release(self.metadata, self.apk, self.output)
        self.assertFalse(self.output.exists())

    def test_rejects_other_apps_and_split_apks(self):
        for metadata in ({"applicationId": "other.app", "elements": self.metadata["elements"]},
                         {"applicationId": "ru.seasonvar.tv", "elements": []}):
            with self.assertRaises(ValueError):
                release.package_release(metadata, self.apk, self.output)

    def test_prerelease_replaces_previous_package(self):
        release.package_release(self.metadata, self.apk, self.output)
        self.metadata["elements"][0]["versionName"] = "0.3.0-beta.1"
        result = release.package_release(self.metadata, self.apk, self.output, "v0.3.0-beta.1")
        self.assertEqual(result.name, "seasonvar-tv-0.3.0-beta.1-universal.apk")
        self.assertEqual([path.name for path in self.output.glob("*.apk")], [result.name])
