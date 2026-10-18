"""Tests for modCore.settings: clamped player settings and the derived style."""

import unittest

from _core import load

settings = load("settings")
map_style = load("map_style")
projection = load("projection")


class DefaultsTest(unittest.TestCase):
    def test_default_values(self):
        value = settings.Settings()
        self.assertTrue(value.minimap)
        self.assertEqual(value.minimap_corner, "top_right")
        self.assertEqual(value.minimap_zoom, 3)
        self.assertEqual(value.minimap_size, settings.SIZES[1])
        self.assertEqual(value.radar_range, 32)
        self.assertEqual(value.quality, settings.QUALITY_FANCY)
        self.assertTrue(value.depth_shading)
        self.assertFalse(value.always_fog_of_war)
        self.assertEqual(value.pitch_degrees, 50.0)

    def test_constructor_accepts_known_keys_only(self):
        value = settings.Settings(minimap=False, not_a_setting=1)
        self.assertFalse(value.minimap)
        self.assertFalse(hasattr(value, "not_a_setting"))

    def test_derived_zoom_and_nearest(self):
        value = settings.Settings(minimap_zoom=3, radar_range=32, minimap_size=128)
        self.assertEqual(value.zoom(), settings.ZOOMS[3])
        self.assertEqual(value.nearest_range(), 32)
        self.assertEqual(value.nearest_size(), 128)

    def test_nearest_snaps_to_the_closest_known_step(self):
        self.assertEqual(settings.Settings(radar_range=100).radar_range, 128)
        self.assertEqual(settings.Settings(minimap_size=1000).minimap_size, settings.SIZES[-1])

    def test_zoom_index_is_clamped(self):
        self.assertEqual(settings.Settings(minimap_zoom=99).minimap_zoom, len(settings.ZOOMS) - 1)
        self.assertEqual(settings.Settings(minimap_zoom=-5).minimap_zoom, 0)

    def test_unknown_corner_falls_back(self):
        self.assertEqual(settings.Settings(minimap_corner="middle").minimap_corner, "top_right")

    def test_junk_numbers_fall_back(self):
        value = settings.Settings(radar_range="junk", minimap_size="junk", pitch_degrees="junk")
        self.assertEqual(value.radar_range, 32)
        self.assertEqual(value.minimap_size, settings.SIZES[1])
        self.assertEqual(value.pitch_degrees, 50.0)

    def test_pitch_is_clamped(self):
        self.assertEqual(settings.Settings(pitch_degrees=1).pitch_degrees, projection.MIN_PITCH_DEGREES)
        self.assertEqual(settings.Settings(pitch_degrees=100).pitch_degrees, projection.MAX_PITCH_DEGREES)


class BooleanTest(unittest.TestCase):
    def test_truthy_and_falsy_strings(self):
        self.assertFalse(settings.Settings(minimap="no").minimap)
        self.assertFalse(settings.Settings(minimap="false").minimap)
        self.assertFalse(settings.Settings(minimap=0).minimap)
        self.assertTrue(settings.Settings(minimap="yes").minimap)
        self.assertTrue(settings.Settings(minimap="true").minimap)
        self.assertTrue(settings.Settings(minimap=1).minimap)

    def test_none_is_false_for_a_flag(self):
        self.assertFalse(settings.Settings(biome=None).biome)

    def test_quality_must_be_a_known_value(self):
        self.assertEqual(settings.Settings(quality="nonsense").quality, settings.QUALITY_FANCY)
        self.assertEqual(settings.Settings(quality=settings.QUALITY_FAST).quality, settings.QUALITY_FAST)


class StyleTest(unittest.TestCase):
    def test_default_style_is_the_depth_look(self):
        style = settings.Settings().style()
        self.assertEqual(style.name, "Settings")
        self.assertEqual(style.sky_occlusion, map_style.DEFAULT.sky_occlusion)
        self.assertTrue(style.rounded_fog)

    def test_depth_shading_off_uses_flat(self):
        style = settings.Settings(depth_shading=False).style()
        self.assertEqual(style.sky_occlusion, map_style.FLAT.sky_occlusion)
        self.assertEqual(style.outline, map_style.FLAT.outline)

    def test_outlines_can_be_turned_off(self):
        self.assertEqual(settings.Settings(outlines=False).style().outline, 0.0)

    def test_rounded_fog_flag_is_carried(self):
        self.assertFalse(settings.Settings(rounded_fog=False).style().rounded_fog)

    def test_fast_quality_shortens_shadows(self):
        style = settings.Settings(quality=settings.QUALITY_FAST).style()
        self.assertEqual(style.shadow_distance, 16.0)
        self.assertTrue(settings.Settings(quality=settings.QUALITY_FAST).is_fast())
        self.assertFalse(settings.Settings().is_fast())

    def test_pitch_is_carried_into_the_style(self):
        self.assertEqual(settings.Settings(pitch_degrees=60).style().pitch_degrees, 60.0)

    def test_style_for_accepts_settings_a_dict_and_none(self):
        self.assertEqual(settings.style_for(settings.Settings()).name, "Settings")
        self.assertEqual(settings.style_for({"depth_shading": False}).name, "Settings")
        self.assertIs(settings.style_for(None), map_style.DEFAULT)


class SerialisationTest(unittest.TestCase):
    def test_to_dict_covers_every_slot(self):
        self.assertEqual(sorted(settings.Settings().to_dict()), sorted(settings.Settings.__slots__))

    def test_round_trip(self):
        value = settings.Settings(minimap=False, radar_range=64, quality=settings.QUALITY_FAST)
        clone = settings.Settings.from_dict(value.to_dict())
        self.assertEqual(clone.to_dict(), value.to_dict())

    def test_from_dict_junk_returns_defaults(self):
        for bad in ("nope", None, 42):
            self.assertTrue(settings.Settings.from_dict(bad).minimap)

    def test_from_dict_ignores_unknown_keys(self):
        clone = settings.Settings.from_dict({"minimap": False, "nope": 1})
        self.assertFalse(clone.minimap)

    def test_to_server_dict_is_the_radar_subset(self):
        data = settings.Settings().to_server_dict()
        self.assertEqual(
            sorted(data),
            sorted(
                [
                    "radar_players",
                    "radar_hostile",
                    "radar_neutral",
                    "radar_passive",
                    "radar_range",
                    "pitch_degrees",
                    "always_fog_of_war",
                ]
            ),
        )

    def test_repr_mentions_range(self):
        self.assertIn("32", repr(settings.Settings()))


class ServerSettingsViewTest(unittest.TestCase):
    def test_parses_a_synced_dict(self):
        view = settings.ServerSettingsView(
            {"radar_hostile": "false", "radar_range": "64", "pitch_degrees": "70"}
        )
        self.assertFalse(view.radar_hostile)
        self.assertTrue(view.radar_passive)
        self.assertEqual(view.radar_range, 64)
        self.assertEqual(view.pitch_degrees, 70.0)

    def test_defaults_and_junk(self):
        view = settings.ServerSettingsView()
        self.assertTrue(view.radar_hostile)
        self.assertEqual(view.radar_range, 32)
        self.assertEqual(view.pitch_degrees, 50.0)
        self.assertEqual(settings.ServerSettingsView({"radar_range": None}).radar_range, 32)

    def test_radar_accepts_the_view(self):
        radar = load("radar")
        self.assertTrue(radar.enabled(radar.KIND_HOSTILE, settings.ServerSettingsView()))
        view = settings.ServerSettingsView({"radar_hostile": False})
        self.assertFalse(radar.enabled(radar.KIND_HOSTILE, view))


if __name__ == "__main__":
    unittest.main()
