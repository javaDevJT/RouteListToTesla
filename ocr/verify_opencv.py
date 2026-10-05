"""Verify that the installed OpenCV cannot provide an FFmpeg backend."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys


def verify(cv2, prefix):
    prefix = Path(prefix).resolve()
    module = Path(cv2.__file__).resolve()
    if not module.is_relative_to(prefix):
        raise RuntimeError("OpenCV was imported from outside the OCR environment")

    info = cv2.getBuildInformation()
    values = re.findall(r"^\s*FFMPEG:\s*(\S+)", info, re.MULTILINE | re.IGNORECASE)
    if len(values) > 1 or any(value.upper() not in {"NO", "OFF", "FALSE", "DISABLED"}
                              for value in values):
        raise RuntimeError(f"OpenCV reports enabled or ambiguous FFmpeg support: {values}")

    backend = int(cv2.CAP_FFMPEG)
    registered = [int(value) for value in cv2.videoio_registry.getBackends()]
    available = bool(cv2.videoio_registry.hasBackend(backend))
    if backend in registered or available:
        raise RuntimeError(f"OpenCV registers or provides the FFmpeg backend: "
                           f"registered={registered}, available={available}")

    extensions = sorted(module.parent.rglob("cv2*.so"))
    if not extensions:
        raise RuntimeError("Installed OpenCV native extension was not found")
    forbidden = re.compile(r"ffmpeg|(?:^|[/\s])(?:lib)?(?:avcodec|avformat|avutil|avdevice|"
                           r"avfilter|swscale|swresample|postproc)(?:[-_.\s]|$)", re.IGNORECASE)
    for library in prefix.rglob("*.so*"):
        if forbidden.search(library.name):
            raise RuntimeError(f"FFmpeg library or plugin is packaged: {library.name}")
    for extension in extensions:
        linked = subprocess.run(["ldd", str(extension)], capture_output=True, text=True,
                                timeout=30, check=True)
        if "not found" in linked.stdout or forbidden.search(linked.stdout):
            raise RuntimeError("OpenCV native dependencies are missing or include FFmpeg: "
                               + linked.stdout.strip())

    return {"version": cv2.__version__, "module": str(module), "ffmpegBackendId": backend,
            "registeredBackends": registered, "ffmpegAvailable": available,
            "ffmpegConfigurationValues": values, "nativeExtensions": len(extensions),
            "packagedFfmpegLibraries": 0, "linkedFfmpegLibraries": 0}


def main():
    # Registry priorities can conceal a compiled backend. Inspect in a clean environment.
    for key in list(os.environ):
        if key.startswith("OPENCV_VIDEOIO_PRIORITY_") or key == "OPENCV_VIDEOIO_PLUGIN_PATH":
            del os.environ[key]
    import cv2

    try:
        print(json.dumps(verify(cv2, sys.prefix)))
    except Exception as error:
        print(json.dumps({"error": str(error), "buildInformation": cv2.getBuildInformation()}),
              file=sys.stderr)
        raise


if __name__ == "__main__":
    main()
