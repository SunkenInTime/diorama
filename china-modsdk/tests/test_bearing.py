"""Tests for modCore.bearing: bearings, compass labels and coordinate text."""

import math
import unittest

from _core import load

bearing = load("bearing")


class BearingTest(unittest.TestCase):
    def test_cardinal_directions(self):
        origin = (0, 0)
        self.assertAlmostEqual(bearing.bearing_degrees(origin, (0, -1)), 0.0)
        self.assertAlmostEqual(bearing.bearing_degrees(origin, (1, 0)), 90.0)
        self.assertAlmostEqual(bearing.bearing_degrees(origin, (0, 1)), 180.0)
        self.assertAlmostEqual(bearing.bearing_degrees(origin, (-1, 0)), 270.0)

    def test_north_is_negative_z(self):
        self.assertAlmostEqual(bearing.bearing_degrees((5, 5), (5, -5)), 0.0)

    def test_diagonals(self):
        for target, expected in (((1, -1), 45.0), ((1, 1), 135.0), ((-1, 1), 225.0), ((-1, -1), 315.0)):
            self.assertAlmostEqual(bearing.bearing_degrees((0, 0), target), expected, places=9)

    def test_result_is_always_in_range(self):
        for target in ((1, 1), (-1, 1), (-1, -1), (1, -1), (0, 1), (0, -1)):
            degrees = bearing.bearing_degrees((0, 0), target)
            self.assertGreaterEqual(degrees, 0.0)
            self.assertLess(degrees, 360.0)


class LabelTest(unittest.TestCase):
    ENGLISH = {0: "N", 45: "NE", 90: "E", 135: "SE", 180: "S", 225: "SW", 270: "W", 315: "NW"}

    def test_english_labels(self):
        for degrees, label in self.ENGLISH.items():
            self.assertEqual(bearing.bearing_label(degrees, "en"), label)

    def test_default_language_is_chinese(self):
        self.assertEqual(bearing.bearing_label(0), "\u5317")
        self.assertEqual(bearing.bearing_label(90), "\u4e1c")
        self.assertEqual(bearing.bearing_label(180), "\u5357")
        self.assertEqual(bearing.bearing_label(270), "\u897f")

    def test_label_wraps_around(self):
        self.assertEqual(bearing.bearing_label(360, "en"), "N")
        self.assertEqual(bearing.bearing_label(-45, "en"), "NW")
        self.assertEqual(bearing.bearing_label(720, "en"), "N")

    def test_eight_point_table_has_eight_entries(self):
        self.assertEqual(len(bearing.COMPASS_8), 8)
        self.assertEqual(len(bearing.COMPASS_8_ZH), 8)
        self.assertEqual(len(bearing.ARROWS_8), 8)

    def test_arrow_matches_label_point(self):
        self.assertEqual(bearing.bearing_arrow(0), "\u2191")
        self.assertEqual(bearing.bearing_arrow(90), "\u2192")
        self.assertEqual(bearing.bearing_arrow(270), "\u2190")


class DistanceTest(unittest.TestCase):
    def test_distance3(self):
        self.assertAlmostEqual(bearing.distance3((0, 0, 0), (3, 4, 12)), 13.0)

    def test_distance2_ignores_height(self):
        self.assertAlmostEqual(bearing.distance2((0, 0), (3, 4)), 5.0)
        self.assertAlmostEqual(bearing.distance2((0, 0), (0, 0)), 0.0)


class FormattingTest(unittest.TestCase):
    def test_format_coord_rounds_to_whole_blocks(self):
        self.assertEqual(bearing.format_coord(1.4), 1)
        self.assertEqual(bearing.format_coord(1.6), 2)
        self.assertEqual(bearing.format_coord(-1.6), -2)

    def test_format_distance(self):
        self.assertEqual(bearing.format_distance(12.4), "12m")
        self.assertEqual(bearing.format_distance(12.6), "13m")

    def test_truncate_short_text_is_untouched(self):
        self.assertEqual(bearing.truncate("hello", 5), "hello")
        self.assertEqual(bearing.truncate("hi", 5), "hi")

    def test_truncate_long_text_gets_an_ellipsis(self):
        self.assertEqual(bearing.truncate("hello", 4), "hel\u2026")
        self.assertEqual(len(bearing.truncate("hello", 4)), 4)

    def test_truncate_coerces_non_strings(self):
        self.assertEqual(bearing.truncate(12345, 4), "123\u2026")
        self.assertEqual(bearing.truncate(None, 8), "None")


if __name__ == "__main__":
    unittest.main()
