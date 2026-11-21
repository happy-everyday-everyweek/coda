#!/usr/bin/env python3
"""version-code.py 的编号规则。

三条硬要求：同一秒内正式版高于 dev、下一秒的 dev 能盖过上一秒的正式版、
以及同一分钟内的两次构建不能算出同一个号（这正是原来按分钟截断的缺陷）。
"""
import datetime
import importlib.util
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "version-code.py")
SPEC = importlib.util.spec_from_file_location("version_code", TOOL)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

EPOCH = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)


class VersionCodeTest(unittest.TestCase):
    def compute(self, channel, seconds):
        return MODULE.compute(channel, EPOCH + datetime.timedelta(seconds=seconds))

    def test_release_beats_dev_within_the_same_second(self):
        self.assertGreater(self.compute("release", 60), self.compute("dev", 60))

    def test_next_second_dev_beats_previous_release(self):
        self.assertGreater(self.compute("dev", 61), self.compute("release", 60))

    def test_two_builds_in_the_same_minute_differ(self):
        self.assertNotEqual(self.compute("dev", 60), self.compute("dev", 61))

    def test_unknown_channel_is_rejected(self):
        with self.assertRaises(ValueError):
            self.compute("beta", 0)

    def test_before_epoch_is_rejected(self):
        with self.assertRaises(ValueError):
            self.compute("dev", -1)

    def test_bad_at_is_reported_not_raised(self):
        result = subprocess.run(
            [sys.executable, TOOL, "--channel", "dev", "--at", "not-a-date"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(1, result.returncode)
        self.assertIn("error:", result.stderr)
        self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)