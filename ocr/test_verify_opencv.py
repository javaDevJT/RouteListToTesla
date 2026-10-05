from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).parent))
import verify_opencv as verifier


class OpenCvVerificationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.prefix = Path(self.directory.name)
        self.package = self.prefix / "lib" / "cv2"
        self.package.mkdir(parents=True)
        (self.package / "__init__.py").touch()
        (self.package / "cv2.test.so").touch()
        self.cv2 = SimpleNamespace(__file__=str(self.package / "__init__.py"),
            __version__="5.0.0", CAP_FFMPEG=1900,
            getBuildInformation=lambda: "Video I/O:\n    Images: YES\n",
            videoio_registry=SimpleNamespace(getBackends=lambda: (2000, 2200),
                                             hasBackend=lambda value: False))
        self.ldd = patch.object(verifier.subprocess, "run", return_value=
            subprocess.CompletedProcess([], 0, "libc.so.6 => /lib/libc.so.6", ""))
        self.ldd.start()
        self.addCleanup(self.ldd.stop)

    def test_missing_text_still_requires_registry_and_native_inspection(self):
        report = verifier.verify(self.cv2, self.prefix)
        self.assertFalse(report["ffmpegAvailable"])
        self.assertEqual(report["nativeExtensions"], 1)
        self.assertEqual(report["ffmpegConfigurationValues"], [])
        verifier.subprocess.run.assert_called_once()

    def test_disabled_configuration_spellings(self):
        for value in ("NO", "OFF", "false", "DISABLED"):
            with self.subTest(value=value):
                self.cv2.getBuildInformation = lambda: f"    FFmpeg: {value}\n"
                verifier.verify(self.cv2, self.prefix)

    def test_enabled_or_unknown_configuration_is_rejected(self):
        for value in ("YES", "ON", "true", "unknown"):
            with self.subTest(value=value):
                self.cv2.getBuildInformation = lambda: f"    FFMPEG: {value}\n"
                with self.assertRaises(RuntimeError):
                    verifier.verify(self.cv2, self.prefix)

    def test_registered_but_unavailable_backend_is_rejected(self):
        self.cv2.videoio_registry.getBackends = lambda: (1900, 2000)
        with self.assertRaisesRegex(RuntimeError, "registers"):
            verifier.verify(self.cv2, self.prefix)

    def test_available_backend_is_rejected(self):
        self.cv2.videoio_registry.hasBackend = lambda value: True
        with self.assertRaisesRegex(RuntimeError, "provides"):
            verifier.verify(self.cv2, self.prefix)

    def test_ffmpeg_library_or_plugin_is_rejected(self):
        for filename in ("libavcodec-a123.so.62", "libopencv_videoio_ffmpeg.so"):
            with self.subTest(filename=filename):
                library = self.prefix / filename
                library.touch()
                with self.assertRaisesRegex(RuntimeError, "packaged"):
                    verifier.verify(self.cv2, self.prefix)
                library.unlink()

    def test_missing_or_ffmpeg_linked_dependencies_are_rejected(self):
        for output in ("libz.so.1 => not found", "libavutil.so.60 => /lib/libavutil.so.60"):
            with self.subTest(output=output):
                verifier.subprocess.run.return_value.stdout = output
                with self.assertRaisesRegex(RuntimeError, "dependencies"):
                    verifier.verify(self.cv2, self.prefix)


if __name__ == "__main__":
    unittest.main()
