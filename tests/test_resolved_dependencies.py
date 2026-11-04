import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "resolved_dependencies", Path(__file__).resolve().parents[1] / "scripts/check-resolved-dependencies.py")
dependencies = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dependencies)
MINIMUMS = dependencies.minimum_versions("buildCryptoVersion=1.86\nguavaVersion=33.7.2\n")


def graph(lint_version="1.86", guava_jre="33.7.2-jre", guava_android="33.7.2-android"):
    entries = [
        {"dependency": "org.bouncycastle:" + module + ":" + version,
         "resolvedBy": [{"path": path, "configuration": configuration, "scope": "Unknown"}]}
        for module in sorted(dependencies.CRYPTO_MODULES)
        for path, configuration, version in [
            (":", "classpath", "1.86"), (":app", "androidLintTool", lint_version)]
    ]
    return entries + [
        {"dependency": "com.google.guava:guava:" + version,
         "resolvedBy": [{"path": path, "configuration": configuration, "scope": "Unknown"}]}
        for path, configuration, version in [
            (":", "classpath", guava_jre), (":app", "debugRuntimeClasspath", guava_android)]
    ]


class ResolvedDependenciesTests(unittest.TestCase):
    def test_partial_root_fix_does_not_hide_vulnerable_lint_dependencies(self):
        with self.assertRaisesRegex(ValueError, r"1\.80\.2.*androidLintTool"):
            dependencies.check_report(graph("1.80.2"), MINIMUMS)

    def test_all_build_lint_and_runtime_resolutions_are_accepted_after_fix(self):
        self.assertEqual(dependencies.check_report(graph(), MINIMUMS), 8)

    def test_newer_patched_versions_are_accepted(self):
        self.assertEqual(dependencies.check_report(graph("1.86.1"), MINIMUMS), 8)

    def test_missing_or_wrong_graph_is_not_a_success(self):
        for report in ([], {}, ["invalid"]):
            with self.subTest(report=report), self.assertRaises(ValueError):
                dependencies.check_report(report, MINIMUMS)

    def test_a_missing_component_is_not_a_success(self):
        report = [entry for entry in graph() if "bcprov-jdk18on" not in entry["dependency"]]
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            dependencies.check_report(report, MINIMUMS)

    def test_unrecognized_or_prerelease_version_is_not_a_success(self):
        for version in ("1.86-beta", "", "unknown"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                dependencies.check_report(graph(version), MINIMUMS)

    def test_unresolved_component_is_not_a_success(self):
        report = graph()
        report[0]["resolvedBy"] = []
        with self.assertRaisesRegex(ValueError, "origin"):
            dependencies.check_report(report, MINIMUMS)

    def test_vulnerable_guava_in_build_tools_is_rejected(self):
        with self.assertRaisesRegex(ValueError, r"33\.4\.0-jre.*classpath"):
            dependencies.check_report(graph(guava_jre="33.4.0-jre"), MINIMUMS)

    def test_a_patched_build_does_not_hide_vulnerable_android_runtime(self):
        with self.assertRaisesRegex(ValueError, "debugRuntimeClasspath"):
            dependencies.check_report(graph(guava_android="33.3.1-android"), MINIMUMS)

    def test_guava_prerelease_is_not_accepted_as_patched(self):
        with self.assertRaises(ValueError):
            dependencies.check_report(graph(guava_jre="33.7.2-beta-jre"), MINIMUMS)

    def test_missing_minimum_version_is_not_a_success(self):
        with self.assertRaisesRegex(ValueError, "guavaVersion"):
            dependencies.minimum_versions("buildCryptoVersion=1.86\n")
