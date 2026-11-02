import base64
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("probe_video", Path(__file__).resolve().parents[1] / "scripts/probe-video.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class VideoUrlTests(unittest.TestCase):
    def test_preserves_encoded_filename_punctuation(self):
        url = "//media.example.test/One[Studio],.mp4?example=a%2Bb"
        encoded = base64.b64encode(url.encode()).decode()
        value = "#2" + encoded[:10] + "//b2xvbG8=" + encoded[10:]
        self.assertEqual(probe.media_url(value), "https://media.example.test/One%5BStudio%5D,.mp4?example=a%2Bb")

    def test_quality_boundary_is_not_a_filename_delimiter(self):
        value = "[480p]//media.example.test/One[Studio],.mp4,[720p]//media.example.test/two.mp4"
        self.assertEqual(probe.media_url(value), "https://media.example.test/One%5BStudio%5D,.mp4")

    def test_spaces_and_mirrors(self):
        value = "https://media.example.test/Truth or Dare.mp4 or https://other.example.test/two.mp4"
        self.assertEqual(probe.media_url(value), "https://media.example.test/Truth%20or%20Dare.mp4")

    def test_rejects_credentials_and_unknown_encoding(self):
        for value in ["https://user:example@media.example.test/one.mp4", "#3unsupported"]:
            with self.assertRaises(ValueError):
                probe.media_url(value)
