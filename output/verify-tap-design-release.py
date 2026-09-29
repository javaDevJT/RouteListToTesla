"""Read-only public release checks; never sends a vehicle or route request."""
import hashlib
import json
import sys
from pathlib import Path
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
BASE = "https://teslarouter.javadevjt.tech"
RELEASE = sys.argv[1] if len(sys.argv) > 1 else "20260927-tap-design"

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def fetch(path, method="GET"):
    try:
        response = urllib.request.build_opener(NoRedirect).open(
            urllib.request.Request(BASE + path, method=method,
                headers={"User-Agent": "Mozilla/5.0 TeslaRouter release verification"}), timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.headers, response.read()

status, headers, login = fetch("/login")
assert status == 200 and RELEASE.encode() in login
assert b"Continue with TAP" in login and b"/css/tap-theme.css" in login
assert "default-src 'self'" in headers["Content-Security-Policy"]
assets = {}
for name in ["css/tap-theme.css", "fonts/ibm-plex-sans.woff2",
             "fonts/space-grotesk.woff2", "fonts/ibm-plex-mono.woff2"]:
    status, headers, body = fetch("/" + name)
    source = (ROOT / "src/main/resources/static" / name).read_bytes()
    assert status == 200 and body == source, name
    if name.endswith(".woff2"):
        assert body[:4] == b"wOF2"
    assets[name] = hashlib.sha256(body).hexdigest()
protected = {}
for path in ["/route/vehicles", "/route/session/load", "/fonts/not-approved.woff2"]:
    status, headers, body = fetch(path)
    assert status == 302 and headers["Location"] == "/login", path
    protected[path] = status
status, _, _ = fetch("/css/tap-theme.css", "POST")
assert status == 403
print(json.dumps({"release": RELEASE, "login": 200,
    "assets": assets, "protected": protected, "assetPostWithoutCsrf": status}))
