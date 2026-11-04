import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "build_crypto", Path(__file__).resolve().parents[1] / "scripts/check-build-crypto.py")
crypto = importlib.util.module_from_spec(spec)
spec.loader.exec_module(crypto)


def graph(lint_version="1.86"):
    return [
        {"dependency": "org.bouncycastle:" + module + ":" + version,
         "resolvedBy": [{"path": path, "configuration": configuration, "scope": "Unknown"}]}
        for module in sorted(crypto.MODULES)
        for path, configuration, version in [
            (":", "classpath", "1.86"), (":app", "androidLintTool", lint_version)]
    ]


class BuildCryptoTests(unittest.TestCase):
    def test_partial_root_fix_does_not_hide_vulnerable_lint_dependencies(self):
        with self.assertRaisesRegex(ValueError, r"1\.80\.2.*androidLintTool"):
            crypto.check_report(graph("1.80.2"), "1.86")

    def test_all_build_and_lint_resolutions_are_accepted_after_fix(self):
        self.assertEqual(crypto.check_report(graph(), "1.86"), 6)

    def test_newer_patched_versions_are_accepted(self):
        self.assertEqual(crypto.check_report(graph("1.86.1"), "1.86"), 6)

    def test_missing_or_wrong_graph_is_not_a_success(self):
        for report in ([], {}, ["invalid"]):
            with self.subTest(report=report), self.assertRaises(ValueError):
                crypto.check_report(report, "1.86")

    def test_a_missing_crypto_component_is_not_a_success(self):
        report = [entry for entry in graph() if "bcprov-jdk18on" not in entry["dependency"]]
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            crypto.check_report(report, "1.86")

    def test_unrecognized_or_prerelease_version_is_not_a_success(self):
        for version in ("1.86-beta", "", "unknown"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                crypto.check_report(graph(version), "1.86")

    def test_unresolved_component_is_not_a_success(self):
        report = graph()
        report[0]["resolvedBy"] = []
        with self.assertRaisesRegex(ValueError, "origin"):
            crypto.check_report(report, "1.86")
