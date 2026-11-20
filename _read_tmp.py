import json
import subprocess
import sys
import urllib.request

TOK = subprocess.run(
    "sed -E 's#https://[^:]+:([^@]+)@github.com#\\1#' ~/.git-credentials | head -1",
    shell=True,
    capture_output=True,
    text=True,
).stdout.strip()
REPO = "happy-everyday-everyweek/coda"

for num in sys.argv[1:]:
    req = urllib.request.Request(
        "https://api.github.com/repos/%s/issues/%s" % (REPO, num),
        headers={"Authorization": "Bearer " + TOK, "Accept": "application/vnd.github+json"},
    )
    try:
        d = json.load(urllib.request.urlopen(req, timeout=30))
    except Exception as exc:  # noqa: BLE001
        print("=====", num, "ERR", exc)
        continue
    print("=====", num, d["title"])
    print((d.get("body") or "")[:650])