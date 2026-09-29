#!/usr/bin/env python3
"""Check the shipped Java/OCR runtime without credentials or network access."""
import argparse
import json
import subprocess


parser = argparse.ArgumentParser()
parser.add_argument("image")
args = parser.parse_args()
inspect = json.loads(subprocess.check_output(["docker", "image", "inspect", args.image], text=True))[0]
assert inspect["Config"]["User"] == "10001:10001", "runtime must use the unprivileged router user"
check = r'''
import importlib.metadata as metadata
import json, os, pathlib, subprocess, sys, tempfile, time, urllib.error, urllib.request
assert os.getuid() == 10001
java = subprocess.check_output(["java", "--version"], text=True).splitlines()[0]
assert java.startswith("openjdk 25.")
modules = subprocess.check_output(["java", "--list-modules"], text=True).splitlines()
names = {m.split("@")[0] for m in modules}
assert {"java.base", "java.desktop", "java.net.http", "java.naming", "jdk.crypto.ec"} <= names
assert "jdk.compiler" not in names, "runtime must not contain the Java compiler"
assert not pathlib.Path("/opt/java/bin/javac").exists()
assert not pathlib.Path("/build").exists()
assert not pathlib.Path("/usr/bin/mvn").exists()
subprocess.run(["/opt/ocr/bin/pip", "check"], check=True, stdout=subprocess.DEVNULL)
size = sum(p.stat().st_size for p in pathlib.Path("/opt/java").rglob("*") if p.is_file())
with tempfile.TemporaryFile() as log:
    app = subprocess.Popen(["java", "-Dgoogle.api.key=synthetic-offline-test", "-jar", "/app/app.jar"],
        stdout=log, stderr=subprocess.STDOUT)
    try:
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        http = urllib.request.build_opener(NoRedirect)
        def request(path, data=None):
            try:
                response = http.open(urllib.request.Request(
                    "http://127.0.0.1:10088" + path, data=data,
                    headers={"Accept":"application/json", "Content-Type":"application/json"}), timeout=3)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                return response.status, response.read(), response.headers.get("Location")
        for attempt in range(90):
            if app.poll() is not None:
                log.seek(0)
                raise AssertionError("Application startup failed: " + log.read().decode(errors="replace")[-6000:])
            try:
                status, body, _ = request("/login")
                if status == 200:
                    break
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(1)
        else:
            raise AssertionError("Application did not become ready in 90 seconds")
        assert b"Continue with TAP" in body, "TAP login must render using the packaged runtime"
        for path in ["/route/vehicles", "/route/session/load", "/route/auto-navigate/active"]:
            status, _, location = request(path)
            assert status == 401 or (status == 302 and location in ["/login", "http://127.0.0.1:10088/login"]), (path, status, location)
        status, _, _ = request("/route/cache/check", b"{}")
        assert status == 403, ("missing CSRF", status)
    finally:
        app.terminate()
        try:
            app.wait(timeout=15)
        except subprocess.TimeoutExpired:
            app.kill()
            app.wait()
print(json.dumps({"uid":os.getuid(), "java":java, "jreBytes":size, "modules":modules,
    "python":sys.version.split()[0], "tesseract":subprocess.check_output(["tesseract","--version"],text=True).splitlines()[0],
    "ocr":{p:metadata.version(p) for p in ["torch","torchvision","rapidocr","easyocr","pillow-heif","onnxruntime"]},
    "pipCheck":True, "compilerAbsent":True, "applicationStartup":True,
    "unauthenticatedReadDenied":True, "csrfEnforced":True}))
'''
result = subprocess.check_output([
    "docker", "run", "--rm", "--network=none", "--read-only",
    "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=128m,mode=1777",
    "--tmpfs", "/app/cache:rw,nosuid,nodev,noexec,size=16m,uid=10001,gid=10001",
    "--cpus=2", "--memory=4g", "--entrypoint=/opt/ocr/bin/python",
    args.image, "-c", check,
], text=True)
report = json.loads(result)
report.update({"imageId":inspect["Id"], "imageBytes":inspect["Size"], "requestedImage":args.image})
print(json.dumps(report, indent=2))
