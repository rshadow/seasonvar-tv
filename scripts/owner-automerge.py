#!/usr/bin/env python3
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

OWNER_ID = 737152
ACTIONS_APP_ID = 15368
BASE = "main"
MAIN_WORKFLOWS = ("android.yml", "codeql.yml", "dependency-graph.yml")


class GitHubError(Exception):
    def __init__(self, status):
        self.status = status
        super().__init__("GitHub API returned HTTP " + str(status))


class Api:
    def __init__(self, repository, token):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("Invalid repository")
        self.base = "https://api.github.com/repos/" + repository
        self.token = token

    def __call__(self, method, path, payload=None):
        body = None if payload is None else json.dumps(payload).encode()
        request = urllib.request.Request(self.base + path, data=body, method=method, headers={
            "Authorization": "Bearer " + self.token,
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "seasonvar-owner-automerge",
        })
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                content = response.read()
                return json.loads(content) if content else None
        except urllib.error.HTTPError as error:
            raise GitHubError(error.code) from None


def eligible(pr):
    return (pr.get("user", {}).get("id") == OWNER_ID and pr.get("state") == "open"
            and not pr.get("draft", True) and pr.get("base", {}).get("ref") == BASE)


def required_checks(policy):
    rule = next(rule for rule in policy["rules"] if rule["type"] == "required_status_checks")
    return {check["context"] for check in rule["parameters"]["required_status_checks"]}


def protected(rules, required):
    types = {rule["type"] for rule in rules}
    if not {"pull_request", "deletion", "non_fast_forward", "code_scanning"} <= types:
        return False
    enforced = set()
    for rule in rules:
        parameters = rule.get("parameters", {})
        if rule["type"] == "required_status_checks" and parameters.get("strict_required_status_checks_policy"):
            enforced.update(check["context"] for check in parameters["required_status_checks"]
                            if check.get("integration_id") == ACTIONS_APP_ID)
    if not required <= enforced:
        return False
    return any(tool.get("tool") == "CodeQL" and tool.get("security_alerts_threshold") in
               {"high_or_higher", "medium_or_higher", "all"} and tool.get("alerts_threshold") in
               {"errors", "errors_and_warnings", "all"}
               for rule in rules if rule["type"] == "code_scanning"
               for tool in rule.get("parameters", {}).get("code_scanning_tools", []))


def checks_passed(checks, statuses, required):
    latest_checks = {}
    for check in sorted(checks, key=lambda item: item["id"], reverse=True):
        latest_checks.setdefault(check["name"], check)
    latest_statuses = {}
    for status in sorted(statuses, key=lambda item: item["id"], reverse=True):
        latest_statuses.setdefault(status["context"], status)
    for name in required:
        if name in latest_statuses:
            status = latest_statuses[name]
            if status["state"] != "success" or status.get("creator", {}).get("login") != "github-actions[bot]":
                return False
        else:
            check = latest_checks.get(name)
            if not check or check["status"] != "completed" or check["conclusion"] != "success":
                return False
            if check.get("app", {}).get("id") != ACTIONS_APP_ID:
                return False
    return (all(check["status"] == "completed" and check["conclusion"] in {"success", "neutral", "skipped"}
                for name, check in latest_checks.items() if name != "Merge owner PRs")
            and all(status["state"] == "success" for status in latest_statuses.values()))


def run(api, required, emit=print):
    rules = api("GET", "/rules/branches/main")
    if not protected(rules, required):
        emit("Auto-merge inactive: import the main protection rules first")
        return []
    merged = []
    pulls = api("GET", "/pulls?state=open&base=main&per_page=100")
    for summary in pulls:
        if not eligible(summary):
            continue
        number = summary["number"]
        pr = api("GET", "/pulls/" + str(number))
        if not eligible(pr) or pr.get("mergeable") is not True:
            continue
        sha = pr["head"]["sha"]
        checks = api("GET", "/commits/" + sha + "/check-runs?filter=latest&per_page=100")["check_runs"]
        statuses = api("GET", "/commits/" + sha + "/statuses?per_page=100")
        if not checks_passed(checks, statuses, required):
            emit("PR #" + str(number) + " is waiting for successful checks")
            continue
        try:
            result = api("PUT", "/pulls/" + str(number) + "/merge", {"sha": sha, "merge_method": "merge"})
        except GitHubError as error:
            if error.status in {405, 409, 422}:
                emit("PR #" + str(number) + " changed or is blocked by merge requirements")
                continue
            raise
        if not result.get("merged"):
            continue
        merged.append(number)
        emit("Merged owner PR #" + str(number))
        # Bot-authenticated merges do not trigger the normal push workflows.
        for workflow in MAIN_WORKFLOWS:
            api("POST", "/actions/workflows/" + workflow + "/dispatches", {"ref": BASE})
        break  # A second PR needs checks against the new base before it can merge.
    return merged


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("This command runs only inside GitHub Actions")
    token = os.environ.get("GH_TOKEN")
    if not token:
        raise SystemExit("GitHub Actions token is missing")
    policy = json.loads((Path(__file__).resolve().parents[1] / ".github/rulesets/main.json").read_text())
    try:
        run(Api(os.environ["GITHUB_REPOSITORY"], token), required_checks(policy))
    except (GitHubError, OSError, ValueError, KeyError) as error:
        raise SystemExit(type(error).__name__ + ": auto-merge could not complete; inspect workflow permissions and main checks") from None


if __name__ == "__main__":
    main()
