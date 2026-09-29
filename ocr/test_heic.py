"""Decoder checks run in the release image, with no OCR models or network."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image
from pillow_heif import register_heif_opener
from consensus import InvalidImageError, normalized_input


class HeicTest(unittest.TestCase):
    def test_decodes_strips_metadata_and_cleans_up(self):
        register_heif_opener()
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "photo.HEIC"
            with Image.new("RGB", (80, 40), (220, 60, 30)) as image:
                exif = Image.Exif()
                exif[270] = "private source metadata"
                image.save(source, format="HEIF", quality=95, exif=exif)
            with normalized_input(str(source)) as decoded:
                with Image.open(decoded) as image:
                    self.assertEqual(image.format, "PNG")
                    self.assertEqual(image.size, (80, 40))
                    self.assertEqual(image.mode, "RGB")
                    self.assertGreater(image.getpixel((10, 10))[0], 150)
                    self.assertFalse(image.info)
            self.assertFalse(Path(decoded).exists())
            self.assertEqual(list(Path(directory).iterdir()), [source])

    def test_applies_any_remaining_exif_orientation(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Image.new("RGB", (80, 40), "white")
            source.format = "HEIF"
            source.getexif()[274] = 6
            with patch("PIL.Image.open", return_value=source):
                with normalized_input(str(Path(directory) / "rotated.heic")) as decoded:
                    # Inspect outside the patched loader.
                    pixels = Path(decoded).read_bytes()
            import io
            with Image.open(io.BytesIO(pixels)) as image:
                self.assertEqual(image.size, (40, 80))
                self.assertFalse(image.getexif())

    def test_malformed_input_is_rejected_and_cleaned_up(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "bad.heic"
            source.write_bytes(b'\0\0\0\x14ftypheic\0\0\0\0mif1')
            with self.assertRaises(InvalidImageError):
                with normalized_input(str(source)):
                    self.fail("malformed pixels reached OCR")
            self.assertEqual(list(Path(directory).iterdir()), [source])

    def test_oversized_header_is_rejected_before_pixel_decode(self):
        from unittest.mock import MagicMock
        source = MagicMock(format="HEIF", width=5000, height=5000)
        source.__enter__.return_value = source
        with tempfile.TemporaryDirectory() as directory:
            with patch("PIL.Image.open", return_value=source), patch("PIL.ImageOps.exif_transpose") as decode:
                with self.assertRaises(InvalidImageError):
                    with normalized_input(str(Path(directory) / "large.heic")):
                        self.fail("oversized image reached OCR")
                decode.assert_not_called()
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_engine_error_is_not_misclassified_as_bad_input(self):
        register_heif_opener()
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "photo.heic"
            with Image.new("RGB", (32, 32), "white") as image:
                image.save(source, format="HEIF")
            with self.assertRaisesRegex(OSError, "engine failed"):
                with normalized_input(str(source)) as decoded:
                    raise OSError("engine failed")
            self.assertFalse(Path(decoded).exists())


if __name__ == "__main__":
    unittest.main()
