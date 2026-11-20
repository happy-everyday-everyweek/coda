import json
import subprocess
import urllib.error
import urllib.request

TOK = subprocess.run(
    "sed -E 's#https://[^:]+:([^@]+)@github.com#\\1#' ~/.git-credentials | head -1",
    shell=True,
    capture_output=True,
    text=True,
).stdout.strip()


def api(url):
    req = urllib.request.Request(
        url, headers={"Authorization": "Bearer " + TOK, "Accept": "application/vnd.github+json"}
    )
    return json.load(urllib.request.urlopen(req, timeout=30))


runs = api("https://api.github.com/repos/happy-everyday-everyweek/coda/actions/runs?branch=dev&per_page=6")[
    "workflow_runs"
]
bad = [r for r in runs if r["name"] == "Build" and r["conclusion"] == "failure"][0]
jobs = api(
    "https://api.github.com/repos/happy-everyday-everyweek/coda/actions/runs/%s/jobs" % bad["id"]
)["jobs"]
failed = [j for j in jobs if j["conclusion"] == "failure"]
jid = failed[0]["id"] if failed else None
if jid is None:
    print("no failed job")
    raise SystemExit(0)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


req = urllib.request.Request(
    "https://api.github.com/repos/happy-everyday-everyweek/coda/actions/jobs/%s/logs" % jid,
    headers={"Authorization": "Bearer " + TOK},
)
try:
    resp = urllib.request.build_opener(NoRedirect).open(req, timeout=30)
except urllib.error.HTTPError as e:
    resp = urllib.request.urlopen(e.headers.get("Location"), timeout=60)
for line in resp.read().decode("utf-8", "replace").splitlines():
    if "e: file" in line or "Could not resolve" in line or "Execution failed" in line:
        print(line[-400:])