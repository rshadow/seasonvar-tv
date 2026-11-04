import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "resolved_dependencies", Path(__file__).resolve().parents[1] / "scripts/check-resolved-dependencies.py")
dependencies = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dependencies)
MINIMUMS = dependencies.minimum_versions(
    "buildCryptoVersion=1.86\nguavaVersion=33.7.2\nbuildJdomVersion=2.0.6.1\n"
    "buildJose4jVersion=0.9.6\nbuildHttpClientVersion=4.5.14\n")


def graph(lint_version="1.86", guava_jre="33.7.2-jre", guava_android="33.7.2-android",
          jdom_version="2.0.6.1", jose4j_version="0.9.6", httpclient_version="4.5.14"):
    entries = [
        {"dependency": "org.bouncycastle:" + module + ":" + version,
         "resolvedBy": [{"path": path, "configuration": configuration, "scope": "Unknown"}]}
        for module in sorted(dependencies.CRYPTO_MODULES)
        for path, configuration, version in [
            (":", "classpath", "1.86"), (":app", "androidLintTool", lint_version)]
    ] + [
        {"dependency": "org.jdom:jdom2:" + jdom_version,
         "resolvedBy": [{"path": ":", "configuration": "classpath", "scope": "Unknown"}]},
        {"dependency": "org.bitbucket.b_c:jose4j:" + jose4j_version,
         "resolvedBy": [{"path": ":", "configuration": "classpath", "scope": "Unknown"}]},
    ]
    entries.append({"dependency": "org.apache.httpcomponents:httpclient:" + httpclient_version,
                    "resolvedBy": [{"path": ":", "configuration": "classpath"}]})
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
        self.assertEqual(dependencies.check_report(graph(), MINIMUMS), 11)

    def test_newer_patched_versions_are_accepted(self):
        self.assertEqual(dependencies.check_report(graph("1.86.1"), MINIMUMS), 11)

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

    def test_vulnerable_jdom_in_agp_classpath_is_rejected(self):
        with self.assertRaisesRegex(ValueError, r"org\.jdom:jdom2:2\.0\.6 in :/classpath"):
            dependencies.check_report(graph(jdom_version="2.0.6"), MINIMUMS)

    def test_patched_root_does_not_hide_old_jdom_in_another_configuration(self):
        report = graph()
        report.append({"dependency": "org.jdom:jdom2:2.0.6",
                       "resolvedBy": [{"path": ":app", "configuration": "androidLintTool"}]})
        with self.assertRaisesRegex(ValueError, "androidLintTool"):
            dependencies.check_report(report, MINIMUMS)

    def test_vulnerable_jose4j_is_rejected_in_each_resolution(self):
        old_lint = {"dependency": "org.bitbucket.b_c:jose4j:0.9.5",
                    "resolvedBy": [{"path": ":app", "configuration": "androidLintTool"}]}
        for configuration, report in (
                ("classpath", graph(jose4j_version="0.9.5")),
                ("androidLintTool", graph() + [old_lint])):
            with self.subTest(configuration=configuration), self.assertRaisesRegex(
                    ValueError, r"org\.bitbucket\.b_c:jose4j:0\.9\.5 .*" + configuration):
                dependencies.check_report(report, MINIMUMS)

    def test_patched_agp_does_not_hide_vulnerable_httpclient_in_lint(self):
        old_lint = {"dependency": "org.apache.httpcomponents:httpclient:4.5.6",
                    "resolvedBy": [{"path": ":app", "configuration": "androidLintTool"}]}
        for configuration, report in (
                ("classpath", graph(httpclient_version="4.5.6")),
                ("androidLintTool", graph() + [old_lint])):
            with self.subTest(configuration=configuration), self.assertRaisesRegex(
                    ValueError, r"org\.apache\.httpcomponents:httpclient:4\.5\.6 .*" + configuration):
                dependencies.check_report(report, MINIMUMS)
