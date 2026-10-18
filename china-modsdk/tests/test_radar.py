"""Tests for modCore.radar: entity classification and blip maths."""

import unittest

from _core import load

radar = load("radar")
projection = load("projection")


class Settings(object):
    def __init__(self, **values):
        self.radar_range = values.get("radar_range", 32)
        self.radar_players = values.get("radar_players", True)
        self.radar_hostile = values.get("radar_hostile", True)
        self.radar_neutral = values.get("radar_neutral", True)
        self.radar_passive = values.get("radar_passive", True)


class ClassifyTest(unittest.TestCase):
    def test_players(self):
        self.assertEqual(radar.classify("minecraft:player"), radar.KIND_PLAYER)
        self.assertEqual(radar.classify("minecraft:zombie", is_player=True), radar.KIND_PLAYER)

    def test_hostile(self):
        self.assertEqual(radar.classify("minecraft:zombie"), radar.KIND_HOSTILE)
        self.assertEqual(radar.classify("minecraft:creeper"), radar.KIND_HOSTILE)

    def test_neutral(self):
        self.assertEqual(radar.classify("minecraft:wolf"), radar.KIND_NEUTRAL)
        self.assertEqual(radar.classify("minecraft:iron_golem"), radar.KIND_NEUTRAL)

    def test_passive_is_the_fallback_for_unknown_mobs(self):
        self.assertEqual(radar.classify("minecraft:cow"), radar.KIND_PASSIVE)
        self.assertEqual(radar.classify("minecraft:new_china_mob"), radar.KIND_PASSIVE)

    def test_ignored_things_are_left_off(self):
        for entity_type in ("minecraft:item", "minecraft:xp_orb", "minecraft:arrow", "minecraft:boat"):
            self.assertIsNone(radar.classify(entity_type), entity_type)

    def test_empty_type_is_left_off(self):
        self.assertIsNone(radar.classify(None))
        self.assertIsNone(radar.classify(""))

    def test_colour_and_order_tables_cover_every_kind(self):
        for kind in (radar.KIND_PASSIVE, radar.KIND_NEUTRAL, radar.KIND_HOSTILE, radar.KIND_PLAYER):
            self.assertIn(kind, radar.KIND_COLOURS)
            self.assertIn(kind, radar.KIND_ORDER)


class EnabledTest(unittest.TestCase):
    def test_each_kind_reads_its_own_setting(self):
        settings = Settings(radar_players=False, radar_hostile=False)
        self.assertFalse(radar.enabled(radar.KIND_PLAYER, settings))
        self.assertFalse(radar.enabled(radar.KIND_HOSTILE, settings))
        self.assertTrue(radar.enabled(radar.KIND_NEUTRAL, settings))
        self.assertTrue(radar.enabled(radar.KIND_PASSIVE, settings))

    def test_missing_settings_default_to_on(self):
        self.assertTrue(radar.enabled(radar.KIND_HOSTILE, object()))


class SizeTest(unittest.TestCase):
    def test_player_is_marked_differently(self):
        self.assertEqual(radar.size_for(radar.KIND_PLAYER, 1.0, False), 7.0)
        self.assertEqual(radar.size_for(radar.KIND_HOSTILE, 1.0, False), 4.0)

    def test_fullscreen_size_scales_and_clamps(self):
        self.assertEqual(radar.size_for(radar.KIND_HOSTILE, 0.1, True), 4.0)
        self.assertLessEqual(radar.size_for(radar.KIND_HOSTILE, 100.0, True), 9.0)
        self.assertLessEqual(radar.size_for(radar.KIND_PLAYER, 100.0, True), 16.0)
        self.assertGreaterEqual(radar.size_for(radar.KIND_PLAYER, 0.1, True), 8.0)


class BlipTest(unittest.TestCase):
    def test_caret_and_alpha(self):
        near = radar.Blip("a", radar.KIND_HOSTILE, "Zombie", 0, 0, 1, 1)
        self.assertFalse(near.has_caret())
        self.assertEqual(near.alpha(), 1.0)

        far = radar.Blip("a", radar.KIND_HOSTILE, "Zombie", 0, 0, 100, 1)
        self.assertTrue(far.has_caret())
        self.assertGreaterEqual(far.alpha(), radar.MIN_ALPHA)

    def test_alpha_decreases_with_height_difference(self):
        low = radar.Blip("a", radar.KIND_HOSTILE, "x", 0, 0, 9, 1)
        high = radar.Blip("a", radar.KIND_HOSTILE, "x", 0, 0, 30, 1)
        self.assertGreater(low.alpha(), high.alpha())

    def test_round_trip_through_a_dict(self):
        blip = radar.Blip("e1", radar.KIND_PLAYER, "Steve", 1.5, -2.5, 3.0, 10.0, 5.0, 2)
        clone = radar.Blip.from_dict(blip.to_dict())
        self.assertEqual(clone.entity_id, blip.entity_id)
        self.assertEqual(clone.kind, blip.kind)
        self.assertAlmostEqual(clone.u, blip.u)
        self.assertAlmostEqual(clone.v, blip.v)
        self.assertAlmostEqual(clone.dy, blip.dy)
        self.assertEqual(clone.dimension, 2)


class CollectTest(unittest.TestCase):
    def setUp(self):
        self.p = projection.Projection(50)
        self.settings = Settings()
        self.viewer = {"id": "me", "x": 0, "y": 64, "z": 0}

    def test_places_entities_in_map_space(self):
        entities = [{"id": "e1", "type": "minecraft:zombie", "x": 5, "y": 65, "z": 5, "name": "Zed"}]
        blips = radar.collect(entities, self.viewer, self.settings, self.p)
        self.assertEqual(len(blips), 1)
        self.assertAlmostEqual(blips[0].u, 5.0)
        self.assertAlmostEqual(blips[0].v, self.p.v(65, 5))
        self.assertEqual(blips[0].kind, radar.KIND_HOSTILE)
        self.assertAlmostEqual(blips[0].distance, (25 + 1 + 25) ** 0.5, places=9)

    def test_skips_self_dead_invisible_and_ignored(self):
        entities = [
            {"id": "me", "type": "minecraft:player", "x": 0, "y": 64, "z": 0},
            {"id": "dead", "type": "minecraft:zombie", "x": 1, "y": 64, "z": 1, "dead": True},
            {"id": "ghost", "type": "minecraft:zombie", "x": 1, "y": 64, "z": 1, "invisible": True},
            {"id": "item", "type": "minecraft:item", "x": 1, "y": 64, "z": 1},
            {"id": "ok", "type": "minecraft:cow", "x": 2, "y": 64, "z": 2},
        ]
        blips = radar.collect(entities, self.viewer, self.settings, self.p)
        self.assertEqual([b.entity_id for b in blips], ["ok"])

    def test_range_filter_uses_the_height_difference(self):
        entities = [{"id": "high", "type": "minecraft:cow", "x": 0, "y": 200, "z": 0}]
        self.assertEqual(radar.collect(entities, self.viewer, self.settings, self.p), [])
        settings = Settings(radar_range=200)
        self.assertEqual(len(radar.collect(entities, self.viewer, settings, self.p)), 1)

    def test_settings_can_hide_a_kind(self):
        entities = [{"id": "z", "type": "minecraft:zombie", "x": 1, "y": 64, "z": 1}]
        settings = Settings(radar_hostile=False)
        self.assertEqual(radar.collect(entities, self.viewer, settings, self.p), [])

    def test_bounds_filter_in_map_space(self):
        entities = [
            {"id": "in", "type": "minecraft:cow", "x": 1, "y": 64, "z": 1},
            {"id": "out", "type": "minecraft:cow", "x": 100, "y": 64, "z": 100},
        ]
        # v for ground at y=64 is strongly negative, so the v window is wide.
        blips = radar.collect(entities, self.viewer, self.settings, self.p, bounds=(0, -100, 4, 4))
        self.assertEqual([b.entity_id for b in blips], ["in"])

    def test_players_sort_last(self):
        entities = [
            {"id": "p", "type": "minecraft:player", "x": 1, "y": 64, "z": 1, "player": True},
            {"id": "z", "type": "minecraft:zombie", "x": 2, "y": 64, "z": 2},
            {"id": "c", "type": "minecraft:cow", "x": 3, "y": 64, "z": 3},
        ]
        blips = radar.collect(entities, self.viewer, self.settings, self.p)
        self.assertEqual([blip.kind for blip in blips], [radar.KIND_PASSIVE, radar.KIND_HOSTILE, radar.KIND_PLAYER])

    def test_entity_dimension_defaults_to_the_viewer(self):
        viewer = dict(self.viewer, dim=1)
        entities = [{"id": "e", "type": "minecraft:cow", "x": 1, "y": 64, "z": 1}]
        blips = radar.collect(entities, viewer, self.settings, self.p)
        self.assertEqual(blips[0].dimension, 1)


class UnderTest(unittest.TestCase):
    def test_finds_a_blip_at_a_point(self):
        blip = radar.Blip("a", radar.KIND_HOSTILE, "Zombie", 10, 20, 0, 1, size=4.0)
        self.assertIs(radar.under([blip], 10, 20), blip)
        self.assertIsNone(radar.under([blip], 100, 100))

    def test_prefers_players_over_mobs(self):
        mob = radar.Blip("m", radar.KIND_HOSTILE, "Zombie", 0, 0, 0, 1, size=4.0)
        player = radar.Blip("p", radar.KIND_PLAYER, "Steve", 0.5, 0, 0, 1, size=4.0)
        self.assertIs(radar.under([mob, player], 0.25, 0), player)

    def test_prefers_the_nearest_of_the_same_kind(self):
        near = radar.Blip("n", radar.KIND_HOSTILE, "Zombie", 0, 0, 0, 1, size=4.0)
        far = radar.Blip("f", radar.KIND_HOSTILE, "Zombie", 2, 0, 0, 1, size=4.0)
        self.assertIs(radar.under([far, near], 0, 0), near)


class DescribeTest(unittest.TestCase):
    def test_english_description_mentions_height(self):
        above = radar.Blip("a", radar.KIND_HOSTILE, "Zombie", 0, 0, 6, 23)
        below = radar.Blip("a", radar.KIND_HOSTILE, "Zombie", 0, 0, -6, 23)
        self.assertIn("23m", radar.describe(above, "en"))
        self.assertIn("above", radar.describe(above, "en"))
        self.assertIn("below", radar.describe(below, "en"))

    def test_chinese_description_is_the_default(self):
        above = radar.Blip("a", radar.KIND_HOSTILE, "Zombie", 0, 0, 6, 23)
        text = radar.describe(above)
        self.assertIn("23m", text)
        self.assertIn("\u4e0a\u65b9", text)

    def test_level_blips_have_no_height_marker(self):
        level = radar.Blip("a", radar.KIND_HOSTILE, "Zombie", 0, 0, 0, 10)
        self.assertNotIn("above", radar.describe(level, "en"))


if __name__ == "__main__":
    unittest.main()
