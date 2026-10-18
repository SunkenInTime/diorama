"""Tests for modCore.projection: the oblique camera and its pitch handling."""

import math
import unittest

from _core import load

projection = load("projection")


class ConstantsTest(unittest.TestCase):
    def test_pitch_range(self):
        self.assertEqual(projection.MIN_PITCH_DEGREES, 25.0)
        self.assertEqual(projection.MAX_PITCH_DEGREES, 70.0)
        self.assertEqual(projection.DEFAULT_PITCH_DEGREES, 50.0)

    def test_clamp(self):
        self.assertEqual(projection.clamp(5, 0, 10), 5)
        self.assertEqual(projection.clamp(-5, 0, 10), 0)
        self.assertEqual(projection.clamp(50, 0, 10), 10)


class NormalizePitchTest(unittest.TestCase):
    def test_clamps_both_ends(self):
        self.assertEqual(projection.normalize_pitch(0), projection.MIN_PITCH_DEGREES)
        self.assertEqual(projection.normalize_pitch(180), projection.MAX_PITCH_DEGREES)

    def test_keeps_values_inside_the_range(self):
        self.assertEqual(projection.normalize_pitch(45), 45.0)
        self.assertEqual(projection.normalize_pitch("60"), 60.0)

    def test_non_numeric_falls_back_to_the_default(self):
        for bad in (None, "north", object()):
            self.assertEqual(projection.normalize_pitch(bad), projection.DEFAULT_PITCH_DEGREES)

    def test_nan_and_infinity_fall_back(self):
        self.assertEqual(projection.normalize_pitch(float("nan")), projection.DEFAULT_PITCH_DEGREES)
        self.assertEqual(projection.normalize_pitch(float("inf")), projection.DEFAULT_PITCH_DEGREES)
        self.assertEqual(projection.normalize_pitch(float("-inf")), projection.DEFAULT_PITCH_DEGREES)


class ProjectionTest(unittest.TestCase):
    def setUp(self):
        self.p = projection.Projection(50)

    def test_trig_matches_the_pitch(self):
        self.assertAlmostEqual(self.p.sin, math.sin(math.radians(50)), places=12)
        self.assertAlmostEqual(self.p.cos, math.cos(math.radians(50)), places=12)

    def test_pitch_is_clamped_on_construction(self):
        self.assertEqual(projection.Projection(1).pitch_degrees, projection.MIN_PITCH_DEGREES)
        self.assertEqual(projection.Projection(89).pitch_degrees, projection.MAX_PITCH_DEGREES)

    def test_v_uses_sin_and_cos(self):
        self.assertAlmostEqual(self.p.v(64, 100), 100 * self.p.sin - 64 * self.p.cos, places=12)

    def test_z_at_inverts_v(self):
        for y in (-64, 0, 63.5, 320):
            for z in (-500, -1.25, 0, 7.5, 1234):
                self.assertAlmostEqual(self.p.z_at(self.p.v(y, z), y), z, places=9)

    def test_higher_blocks_move_up_the_screen(self):
        self.assertLess(self.p.v(65, 40), self.p.v(64, 40))

    def test_depth_is_negative_along_the_view_direction(self):
        self.assertAlmostEqual(self.p.depth(0, 0), 0.0, places=12)
        self.assertAlmostEqual(
            self.p.depth(7, -3), -(self.p.sin * 7 + self.p.cos * -3), places=12
        )
        # Farther north (smaller z) is farther from the camera.
        self.assertGreater(self.p.depth(0, -10), self.p.depth(0, 10))

    def test_refresh_repoints_in_place(self):
        other = projection.Projection(60)
        returned = self.p.refresh(60)
        self.assertIs(returned, self.p)
        self.assertEqual(self.p.pitch_degrees, 60.0)
        self.assertAlmostEqual(self.p.sin, other.sin, places=12)
        self.assertAlmostEqual(self.p.cos, other.cos, places=12)

    def test_refresh_clamps(self):
        self.p.refresh(200)
        self.assertEqual(self.p.pitch_degrees, projection.MAX_PITCH_DEGREES)

    def test_copy_is_independent(self):
        clone = self.p.copy()
        self.assertEqual(clone.pitch_degrees, self.p.pitch_degrees)
        clone.refresh(30)
        self.assertEqual(self.p.pitch_degrees, 50.0)
        self.assertNotEqual(clone.pitch_degrees, self.p.pitch_degrees)

    def test_make_factory(self):
        self.assertIsInstance(projection.make(45), projection.Projection)
        self.assertEqual(projection.make(45).pitch_degrees, 45.0)
        self.assertEqual(projection.make().pitch_degrees, projection.DEFAULT_PITCH_DEGREES)

    def test_repr_mentions_pitch(self):
        self.assertIn("50", repr(self.p))


if __name__ == "__main__":
    unittest.main()
