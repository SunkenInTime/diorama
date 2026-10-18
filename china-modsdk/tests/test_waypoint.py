"""Tests for modCore.waypoint: waypoint values, ids and dimension mapping."""

import unittest

from _core import load

waypoint = load("waypoint")


class DimensionTest(unittest.TestCase):
    def test_name_for_known_ids(self):
        self.assertEqual(waypoint.dimension_name(0), "minecraft:overworld")
        self.assertEqual(waypoint.dimension_name(1), "minecraft:nether")
        self.assertEqual(waypoint.dimension_name(2), "minecraft:the_end")

    def test_unknown_id_keeps_its_number(self):
        self.assertEqual(waypoint.dimension_name(7), "dimension:7")

    def test_non_numeric_name_is_returned_as_text(self):
        self.assertEqual(waypoint.dimension_name("minecraft:overworld"), "minecraft:overworld")

    def test_id_accepts_names_and_numbers(self):
        self.assertEqual(waypoint.dimension_id(1), 1)
        self.assertEqual(waypoint.dimension_id("minecraft:nether"), 1)
        self.assertEqual(waypoint.dimension_id("minecraft:the_end"), 2)
        self.assertEqual(waypoint.dimension_id("dimension:5"), 5)
        self.assertEqual(waypoint.dimension_id("4"), 4)

    def test_id_falls_back_on_junk(self):
        self.assertEqual(waypoint.dimension_id("nonsense"), waypoint.DIMENSION_OVERWORLD)
        self.assertEqual(waypoint.dimension_id(None), waypoint.DIMENSION_OVERWORLD)
        self.assertEqual(waypoint.dimension_id("dimension:x"), waypoint.DIMENSION_OVERWORLD)
        self.assertEqual(waypoint.dimension_id("", default=2), 2)


class InitialsTest(unittest.TestCase):
    def test_one_or_two_words(self):
        self.assertEqual(waypoint.initials_for("Home"), "H")
        self.assertEqual(waypoint.initials_for("Iron Farm"), "IF")
        self.assertEqual(waypoint.initials_for("a b c"), "AB")

    def test_falls_back_to_question_mark(self):
        self.assertEqual(waypoint.initials_for(""), "?")
        self.assertEqual(waypoint.initials_for("   "), "?")
        self.assertEqual(waypoint.initials_for(None), "?")


class WaypointTest(unittest.TestCase):
    def test_defaults(self):
        mark = waypoint.Waypoint()
        self.assertEqual(mark.name, "Waypoint")
        self.assertEqual((mark.x, mark.y, mark.z), (0, 0, 0))
        self.assertEqual(mark.dimension, waypoint.DIMENSION_OVERWORLD)
        self.assertEqual(mark.kind, waypoint.KIND_MANUAL)
        self.assertTrue(mark.visible)
        self.assertTrue(mark.id)
        self.assertGreater(mark.created, 0)

    def test_given_values(self):
        mark = waypoint.Waypoint("Home", 10, 64, -20, dimension=2, color=0xFF0000FF)
        self.assertEqual((mark.x, mark.y, mark.z, mark.dimension), (10, 64, -20, 2))
        self.assertEqual(mark.color, 0xFF0000FF)
        self.assertEqual(mark.initials, "H")
        self.assertEqual(mark.label(), "H")

    def test_coordinate_text(self):
        self.assertEqual(waypoint.Waypoint("x", 10, 64, -20).coordinate_text(), "10, 64, -20")

    def test_distance_to_the_block_centre(self):
        mark = waypoint.Waypoint("origin", 0, 64, 0)
        self.assertAlmostEqual(mark.distance_to(0, 64, 0), 0.5 ** 0.5, places=9)
        self.assertAlmostEqual(mark.distance_to(0.5, 64, 0.5), 0.0, places=9)
        self.assertAlmostEqual(waypoint.Waypoint("", 0, 0, 0).distance_to(3.5, 4, 0.5), 5.0, places=9)

    def test_ids_are_unique(self):
        ids = set(waypoint.Waypoint("x").id for _ in range(50))
        self.assertEqual(len(ids), 50)

    def test_palette_and_death_constants(self):
        self.assertEqual(len(waypoint.PALETTE), 16)
        self.assertEqual(waypoint.DEATHS_KEPT, 3)
        self.assertEqual(waypoint.DEATH_COLOUR, 0xFF60646C)
        for colour in waypoint.PALETTE:
            self.assertEqual(colour >> 24, 0xFF)


class SerialisationTest(unittest.TestCase):
    def test_to_dict_from_dict_round_trip(self):
        mark = waypoint.Waypoint("Iron Farm", 1, 2, 3, dimension=1, color=0xFF12A594)
        mark.kind = waypoint.KIND_DEATH
        mark.visible = False
        data = mark.to_dict()
        clone = waypoint.Waypoint.from_dict(data)
        self.assertEqual(clone.id, mark.id)
        self.assertEqual(clone.name, mark.name)
        self.assertEqual((clone.x, clone.y, clone.z), (mark.x, mark.y, mark.z))
        self.assertEqual(clone.dimension, mark.dimension)
        self.assertEqual(clone.color, mark.color)
        self.assertEqual(clone.kind, mark.kind)
        self.assertEqual(clone.visible, mark.visible)
        self.assertEqual(clone.created, mark.created)

    def test_from_dict_defaults_missing_fields(self):
        clone = waypoint.Waypoint.from_dict({"x": 5, "z": -5})
        self.assertEqual(clone.name, "Waypoint")
        self.assertEqual(clone.y, 0)
        self.assertEqual(clone.dimension, waypoint.DIMENSION_OVERWORLD)
        self.assertEqual(clone.initials, "W")

    def test_from_dict_accepts_a_name_for_the_dimension(self):
        clone = waypoint.Waypoint.from_dict({"x": 0, "z": 0, "dimension": "minecraft:the_end"})
        self.assertEqual(clone.dimension, 2)

    def test_from_dict_rejects_entries_without_a_position(self):
        for bad in ({}, {"x": 1}, {"z": 1}, "nonsense", None):
            with self.assertRaises(ValueError):
                waypoint.Waypoint.from_dict(bad)

    def test_copy_changes_fields(self):
        mark = waypoint.Waypoint("Home", 1, 2, 3)
        moved = mark.copy(x=9, name="Base")
        self.assertEqual(moved.x, 9)
        self.assertEqual(moved.name, "Base")
        self.assertEqual(mark.x, 1)
        self.assertEqual(mark.name, "Home")
        self.assertEqual(moved.id, mark.id)

    def test_repr_mentions_position(self):
        self.assertIn("64", repr(waypoint.Waypoint("x", 1, 64, 3)))


if __name__ == "__main__":
    unittest.main()
