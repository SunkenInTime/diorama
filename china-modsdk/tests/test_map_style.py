"""Tests for modCore.map_style: camera, grade, light and the fluent builders."""

import math
import unittest

from _core import load

map_style = load("map_style")
projection = load("projection")


class DefaultsTest(unittest.TestCase):
    def test_default_matches_the_mod_setting(self):
        style = map_style.DEFAULT
        self.assertEqual(style.name, "Default")
        self.assertEqual(style.pitch_degrees, 50.0)
        self.assertEqual(style.outline, 0.38)
        self.assertTrue(style.rounded_fog)

    def test_default_clear_water_matches_java_parity(self):
        # Java's MapStyle.DEFAULT has clearWater=true.
        self.assertTrue(map_style.DEFAULT.clear_water)
        self.assertFalse(map_style.BASELINE.clear_water)

    def test_sun_elevation_range(self):
        self.assertEqual(map_style.MapStyle.MIN_SUN_ELEVATION, 25.0)
        self.assertEqual(map_style.MapStyle.MAX_SUN_ELEVATION, 60.0)

    def test_baseline_flat_and_preview(self):
        self.assertEqual(map_style.BASELINE.pitch_degrees, 60.0)
        self.assertEqual(map_style.BASELINE.outline, 0.0)
        self.assertEqual(map_style.FLAT.name, "Flat")
        self.assertEqual(map_style.FLAT.outline, 0.38)
        self.assertEqual(map_style.PREVIEW.shadow_distance, 16.0)

    def test_constructor_clamps_values(self):
        style = map_style.MapStyle(
            pitch_degrees=100,
            outline=5.0,
            sky_occlusion=-1.0,
            sun_elevation=100.0,
            saturation=-2.0,
            exposure=-2.0,
            height_shade=-2.0,
            shadow_distance=-2.0,
        )
        self.assertEqual(style.pitch_degrees, projection.MAX_PITCH_DEGREES)
        self.assertEqual(style.outline, 1.0)
        self.assertEqual(style.sky_occlusion, 0.0)
        self.assertEqual(style.sun_elevation, map_style.MapStyle.MAX_SUN_ELEVATION)
        self.assertGreaterEqual(style.ambient, 0.0)
        self.assertGreaterEqual(style.height_shade, 0.0)
        self.assertEqual(style.shadow_distance, 0.0)

    def test_triples_are_coerced_to_float(self):
        style = map_style.MapStyle(sun_tint=[1, 2, 3])
        self.assertEqual(style.sun_tint, (1.0, 2.0, 3.0))

    def test_bad_triples_fall_back_to_white(self):
        style = map_style.MapStyle(sun_tint="nope")
        self.assertEqual(style.sun_tint, (1.0, 1.0, 1.0))


class DerivedTest(unittest.TestCase):
    def test_sun_direction_is_a_unit_vector(self):
        x, y, z = map_style.DEFAULT.sun_direction
        # The azimuth constants are rounded, so the vector is unit to ~1e-3.
        self.assertAlmostEqual(x * x + y * y + z * z, 1.0, delta=0.001)

    def test_sun_direction_azimuth_points_west_and_south(self):
        x, y, z = map_style.DEFAULT.sun_direction
        self.assertLess(x, 0.0)  # west
        self.assertGreater(z, 0.0)  # slightly south
        self.assertGreater(y, 0.0)  # above the horizon

    def test_shadow_reach_is_positive_ints(self):
        reach = map_style.DEFAULT.shadow_reach
        self.assertEqual(len(reach), 3)
        for value in reach:
            self.assertIsInstance(value, int)
            self.assertGreater(value, 0)

    def test_shorter_shadow_distance_reaches_less_far(self):
        long_reach = map_style.MapStyle(shadow_distance=64).shadow_reach
        short_reach = map_style.MapStyle(shadow_distance=8).shadow_reach
        self.assertLess(short_reach[0], long_reach[0])


class BuilderTest(unittest.TestCase):
    def test_builders_return_new_objects(self):
        base = map_style.DEFAULT
        changed = base.with_pitch(30)
        self.assertIsNot(changed, base)
        self.assertEqual(changed.pitch_degrees, 30.0)
        self.assertEqual(base.pitch_degrees, 50.0)

    def test_builders_clamp(self):
        self.assertEqual(map_style.DEFAULT.with_pitch(1).pitch_degrees, projection.MIN_PITCH_DEGREES)
        self.assertEqual(map_style.DEFAULT.with_outline(9).outline, 1.0)
        self.assertEqual(
            map_style.DEFAULT.with_sun_elevation(1).sun_elevation,
            map_style.MapStyle.MIN_SUN_ELEVATION,
        )
        self.assertEqual(map_style.DEFAULT.with_sky_occlusion(9).sky_occlusion, 1.0)

    def test_with_grade_sets_colours(self):
        style = map_style.DEFAULT.with_grade(2.0, 0.5, (1, 1, 1), (0, 0, 0))
        self.assertEqual(style.saturation, 2.0)
        self.assertEqual(style.exposure, 0.5)
        self.assertEqual(style.sun_tint, (1.0, 1.0, 1.0))
        self.assertEqual(style.shadow_tint, (0.0, 0.0, 0.0))

    def test_with_light(self):
        style = map_style.DEFAULT.with_light(0.1, 0.9)
        self.assertEqual(style.ambient, 0.1)
        self.assertEqual(style.sun_strength, 0.9)

    def test_with_clear_water_round_trips(self):
        self.assertFalse(map_style.DEFAULT.with_clear_water(False).clear_water)
        self.assertTrue(map_style.BASELINE.with_clear_water(True).clear_water)

    def test_with_name_keeps_everything_else(self):
        style = map_style.DEFAULT.with_name("Other")
        self.assertEqual(style.name, "Other")
        self.assertTrue(style.looks_like(map_style.DEFAULT))


class LookLikeTest(unittest.TestCase):
    def test_same_values_look_alike(self):
        clone = map_style.MapStyle.from_dict(map_style.DEFAULT.to_dict())
        self.assertTrue(map_style.DEFAULT.looks_like(clone))

    def test_name_and_shadow_distance_do_not_count(self):
        self.assertTrue(map_style.DEFAULT.looks_like(map_style.PREVIEW))

    def test_a_changed_knob_does_not_look_alike(self):
        self.assertFalse(map_style.DEFAULT.looks_like(map_style.DEFAULT.with_outline(0.0)))
        self.assertFalse(map_style.DEFAULT.looks_like(map_style.DEFAULT.with_pitch(60)))
        self.assertFalse(map_style.DEFAULT.looks_like(None))


class SerialisationTest(unittest.TestCase):
    def test_to_dict_covers_every_slot(self):
        data = map_style.DEFAULT.to_dict()
        self.assertEqual(sorted(data), sorted(map_style.MapStyle.__slots__))

    def test_round_trip(self):
        clone = map_style.MapStyle.from_dict(map_style.DEFAULT.to_dict())
        self.assertTrue(clone.looks_like(map_style.DEFAULT))
        self.assertEqual(clone.name, map_style.DEFAULT.name)

    def test_unknown_keys_are_ignored_and_missing_keys_default(self):
        clone = map_style.MapStyle.from_dict({"pitch_degrees": 60, "not_a_knob": 1})
        self.assertEqual(clone.pitch_degrees, 60.0)
        self.assertEqual(clone.outline, map_style.MapStyle().outline)

    def test_junk_returns_the_default(self):
        self.assertIs(map_style.MapStyle.from_dict("nope"), map_style.DEFAULT)
        self.assertIs(map_style.MapStyle.from_dict(None), map_style.DEFAULT)

    def test_repr_mentions_the_name(self):
        self.assertIn("Default", repr(map_style.DEFAULT))


if __name__ == "__main__":
    unittest.main()
