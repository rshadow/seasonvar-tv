import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("owner_automerge", ROOT / "scripts/owner-automerge.py")
automation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(automation)
POLICY = json.loads((ROOT / ".github/rulesets/main.json").read_text())
REQUIRED = automation.required_checks(POLICY)


def pull(number=7, author=737152):
    return {"number": number, "state": "open", "draft": False, "user": {"id": author},
            "base": {"ref": "main"}, "head": {"sha": "a" * 40}, "mergeable": True}


def successful_checks():
    return [{"id": index, "name": name, "status": "completed", "conclusion": "success",
             "app": {"id": 15368}} for index, name in enumerate(sorted(REQUIRED - {"Dependency snapshot"}), 1)]


def successful_statuses():
    return [{"id": 10, "context": "Dependency snapshot", "state": "success", "creator": {"login": "github-actions[bot]"}}]


class FakeApi:
    def __init__(self):
        self.rules = copy.deepcopy(POLICY["rules"])
        self.pulls = [pull()]
        self.checks = successful_checks()
        self.statuses = successful_statuses()
        self.calls = []
        self.merge_error = None
        self.mergeable = True

    def __call__(self, method, path, payload=None):
        self.calls.append((method, path, payload))
        if path == "/rules/branches/main": return self.rules
        if path.startswith("/pulls?"): return self.pulls
        if method == "GET" and path.startswith("/pulls/"):
            value = next(p for p in self.pulls if p["number"] == int(path.rsplit("/", 1)[1]))
            return dict(value, mergeable=self.mergeable)
        if "/check-runs?" in path: return {"check_runs": self.checks}
        if "/statuses?" in path: return self.statuses
        if method == "PUT":
            if self.merge_error: raise automation.GitHubError(self.merge_error)
            return {"merged": True}
        if method == "POST" and "/dispatches" in path: return None
        raise AssertionError((method, path))


class OwnerAutoMergeTests(unittest.TestCase):
    def test_only_owner_non_draft_open_main_prs_are_eligible(self):
        self.assertTrue(automation.eligible(pull()))
        for changed in [pull(author=49699333), dict(pull(), draft=True), dict(pull(), state="closed"),
                        dict(pull(), base={"ref": "other"})]:
            self.assertFalse(automation.eligible(changed))

    def test_missing_branch_protection_cannot_be_bypassed_even_with_all_green_checks(self):
        api = FakeApi(); api.rules = []
        self.assertEqual(automation.run(api, REQUIRED, lambda _: None), [])
        self.assertEqual(len(api.calls), 1)

    def test_missing_required_check_source_or_codeql_threshold_blocks_merging(self):
        for change in ["source", "strict", "codeql"]:
            rules = copy.deepcopy(POLICY["rules"])
            checks = next(r for r in rules if r["type"] == "required_status_checks")["parameters"]
            if change == "source": checks["required_status_checks"][0]["integration_id"] = 999
            if change == "strict": checks["strict_required_status_checks_policy"] = False
            if change == "codeql": next(r for r in rules if r["type"] == "code_scanning")["parameters"]["code_scanning_tools"] = []
            self.assertFalse(automation.protected(rules, REQUIRED))

    def test_all_seven_checks_and_latest_snapshot_must_pass(self):
        checks, statuses = successful_checks(), successful_statuses()
        self.assertTrue(automation.checks_passed(checks, statuses, REQUIRED))
        self.assertFalse(automation.checks_passed(checks[:-1], statuses, REQUIRED))
        self.assertFalse(automation.checks_passed(checks, [], REQUIRED))
        statuses.append(dict(statuses[0], id=11, state="failure"))
        self.assertFalse(automation.checks_passed(checks, statuses, REQUIRED))

    def test_pending_cancelled_or_failed_check_is_not_a_success(self):
        for conclusion in [None, "cancelled", "failure", "timed_out", "skipped"]:
            checks = successful_checks()
            checks[0] = dict(checks[0], conclusion=conclusion)
            self.assertFalse(automation.checks_passed(checks, successful_statuses(), REQUIRED))

    def test_unexpected_required_check_or_snapshot_reporter_is_rejected(self):
        checks = successful_checks(); checks[0]["app"]["id"] = 999
        self.assertFalse(automation.checks_passed(checks, successful_statuses(), REQUIRED))
        statuses = successful_statuses(); statuses[0]["creator"]["login"] = "other"
        self.assertFalse(automation.checks_passed(successful_checks(), statuses, REQUIRED))

    def test_additional_failed_checks_and_statuses_also_block(self):
        checks = successful_checks() + [{"id": 99, "name": "Extra", "status": "completed", "conclusion": "failure"}]
        self.assertFalse(automation.checks_passed(checks, successful_statuses(), REQUIRED))
        statuses = successful_statuses() + [{"id": 99, "context": "Extra", "state": "pending"}]
        self.assertFalse(automation.checks_passed(successful_checks(), statuses, REQUIRED))

    def test_bot_prs_are_not_merged_or_read_as_owner_prs(self):
        api = FakeApi(); api.pulls = [pull(author=49699333)]
        self.assertEqual(automation.run(api, REQUIRED, lambda _: None), [])
        self.assertFalse(any(m != "GET" or p.startswith("/pulls/") for m, p, _ in api.calls))

    def test_merge_is_bound_to_verified_head_then_all_main_workflows_are_dispatched(self):
        api = FakeApi()
        self.assertEqual(automation.run(api, REQUIRED, lambda _: None), [7])
        write = next(c for c in api.calls if c[0] == "PUT")
        self.assertEqual(write, ("PUT", "/pulls/7/merge", {"sha": "a" * 40, "merge_method": "merge"}))
        dispatches = [(p, body) for method, p, body in api.calls if method == "POST"]
        self.assertEqual(dispatches, [("/actions/workflows/" + name + "/dispatches", {"ref": "main"})
                                      for name in automation.MAIN_WORKFLOWS])

    def test_a_changed_head_or_conflict_does_not_merge_or_dispatch(self):
        for status in [405, 409, 422]:
            api = FakeApi(); api.merge_error = status
            self.assertEqual(automation.run(api, REQUIRED, lambda _: None), [])
            self.assertFalse(any(method == "POST" for method, _, _ in api.calls))

    def test_unknown_mergeability_waits_and_permission_errors_are_not_reported_as_success(self):
        api = FakeApi(); api.mergeable = None
        self.assertEqual(automation.run(api, REQUIRED, lambda _: None), [])
        api = FakeApi(); api.merge_error = 403
        with self.assertRaises(automation.GitHubError): automation.run(api, REQUIRED, lambda _: None)

    def test_second_pr_needs_checks_against_the_new_base(self):
        api = FakeApi(); api.pulls.append(pull(number=8))
        self.assertEqual(automation.run(api, REQUIRED, lambda _: None), [7])
        self.assertEqual(sum(method == "PUT" for method, _, _ in api.calls), 1)
