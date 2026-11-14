import unittest
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("frame_statistics", Path(__file__).resolve().parents[1] / "scripts/frame_statistics.py")
frames = importlib.util.module_from_spec(spec)
spec.loader.exec_module(frames)
frame_statistics = frames.frame_statistics


class FrameStatisticsTest(unittest.TestCase):
    def test_ignores_non_rendered_frames_and_preserves_millisecond_units(self):
        raw = """Total frames rendered: 2
Janky frames: 1
50th percentile: 16ms
Flags,IntendedVsync,FrameCompleted,
0,1000000,17000000,
1,2000000,900000000,
0,3000000,123000000,
0,0,10,
0,100,90,
"""
        report = frame_statistics(raw)
        self.assertEqual(2, report["frames"])
        self.assertEqual(2, report["frame_stats_count"])
        self.assertEqual(16, report["p50_ms"])
        self.assertEqual(120, report["raw_p95_ms"])
        self.assertEqual(1, report["frames_over_100ms"])

    def test_missing_frames_are_not_reported_as_zero_latency(self):
        self.assertEqual({}, frame_statistics("No rendered frames"))


if __name__ == "__main__":
    unittest.main()
