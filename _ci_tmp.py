import json
import re
import subprocess
import urllib.request

TOK = subprocess.run(
    "sed -E 's#https://[^:]+:([^@]+)@github.com#\\1#' ~/.git-credentials | head -1",
    shell=True,
    capture_output=True,
    text=True,
).stdout.strip()
REPO = "happy-everyday-everyweek/coda"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect)
sha = subprocess.run(
    "cd /home/zcode-mobile-ui && git rev-parse HEAD",
    shell=True,
    capture_output=True,
    text=True,
).stdout.strip()
req = urllib.request.Request(
    "https://api.github.com/repos/%s/actions/runs?head_sha=%s&per_page=5" % (REPO, sha),
    headers={"Authorization": "Bearer " + TOK, "Accept": "application/vnd.github+json"},
)
runs = json.load(urllib.request.urlopen(req, timeout=30))["workflow_runs"]
build = [r for r in runs if r["name"] == "Build"]
if not build:
    print("no build run")
    raise SystemExit(0)
run_id = build[0]["id"]
jobs = json.load(
    urllib.request.urlopen(
        urllib.request.Request(
            "https://api.github.com/repos/%s/actions/runs/%s/jobs" % (REPO, run_id),
            headers={"Authorization": "Bearer " + TOK},
        ),
        timeout=30,
    )
)["jobs"]
for job in jobs:
    print("JOB", job["name"], job["conclusion"])
    for step in job["steps"]:
        print("  STEP", step["name"], step["conclusion"])
    if job["conclusion"] != "failure":
        continue
    log_req = urllib.request.Request(
        "https://api.github.com/repos/%s/actions/jobs/%s/logs" % (REPO, job["id"]),
        headers={"Authorization": "Bearer " + TOK},
    )
    try:
        opener.open(log_req, timeout=30)
    except urllib.error.HTTPError as exc:
        location = exc.headers.get("Location")
        if not location:
            print("no location", exc.code)
            continue
        text = urllib.request.urlopen(
            urllib.request.Request(location, headers={"User-Agent": "Mozilla/5.0"}),
            timeout=60,
        ).read().decode("utf-8", "replace")
        for line in text.splitlines():
            if re.search(r"(e: file|e: /|error:|FAILURE|What went wrong|Execution failed)", line):
                print(line[:400])