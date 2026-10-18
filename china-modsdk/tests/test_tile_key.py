"""Tests for modCore.tile_key: tile identity, LOD sizing and disk stems."""

import unittest

from _core import load

tile_key = load("tile_key")
geometry = load("geometry")


class TileKeyTest(unittest.TestCase):
    def test_surface_sentinel_matches_java(self):
        self.assertEqual(tile_key.TileKey.SURFACE, -2147483648)
        self.assertEqual(tile_key.TileKey.MAX_LOD, 5)

    def test_surface_is_not_a_cave(self):
        self.assertFalse(tile_key.TileKey(tile_key.TileKey.SURFACE, 0, 0).is_cave())
        self.assertTrue(tile_key.TileKey(48, 0, 0).is_cave())

    def test_units_and_pixels_double_per_lod(self):
        base = geometry.GEOMETRY
        for lod in range(0, tile_key.TileKey.MAX_LOD + 1):
            key = tile_key.TileKey(tile_key.TileKey.SURFACE, 0, 0, lod)
            self.assertEqual(key.units(base), base.tile_units * (1 << lod))
            self.assertEqual(key.pixels(base), base.tile_pixels * (1 << lod))

    def test_min_u_and_min_v(self):
        key = tile_key.TileKey(tile_key.TileKey.SURFACE, 3, -7, 1)
        self.assertEqual(key.min_u(geometry.GEOMETRY), 3 * 256.0)
        self.assertEqual(key.min_v(geometry.GEOMETRY), -7 * 256.0)

    def test_parent_uses_floor_division(self):
        key = tile_key.TileKey(tile_key.TileKey.SURFACE, 3, -7, 0)
        parent = key.parent()
        self.assertEqual((parent.tile_u, parent.tile_v, parent.lod), (1, -4, 1))
        self.assertEqual(parent.layer, key.layer)

    def test_children_are_the_four_quadrants_one_level_down(self):
        key = tile_key.TileKey(tile_key.TileKey.SURFACE, 1, -4, 2)
        children = key.children()
        self.assertEqual(
            set((c.tile_u, c.tile_v, c.lod) for c in children),
            {(2, -8, 1), (3, -8, 1), (2, -7, 1), (3, -7, 1)},
        )
        for child in children:
            self.assertEqual(child.parent(), key)

    def test_tile_for_floors_negative_points(self):
        key = tile_key.TileKey(tile_key.TileKey.SURFACE, 0, 0)
        self.assertEqual(key.tile_for(-0.001, -0.001, geometry.GEOMETRY), tile_key.TileKey(tile_key.TileKey.SURFACE, -1, -1))
        self.assertEqual(key.tile_for(0.0, 0.0, geometry.GEOMETRY), tile_key.TileKey(tile_key.TileKey.SURFACE, 0, 0))
        self.assertEqual(key.tile_for(129.0, 1.0, geometry.GEOMETRY), tile_key.TileKey(tile_key.TileKey.SURFACE, 1, 0))

    def test_cache_key_and_equality(self):
        a = tile_key.TileKey(tile_key.TileKey.SURFACE, 2, 3, 1)
        b = tile_key.TileKey(tile_key.TileKey.SURFACE, 2, 3, 1)
        c = tile_key.TileKey(tile_key.TileKey.SURFACE, 2, 3, 0)
        self.assertEqual(a, b)
        self.assertEqual(hash(a), hash(b))
        self.assertNotEqual(a, c)
        self.assertEqual(a.cache_key(), (tile_key.TileKey.SURFACE, 2, 3, 1))
        self.assertEqual(len({a, b, c}), 2)
        self.assertFalse(a == "not a tile")

    def test_file_stem_round_trip(self):
        for key in (
            tile_key.TileKey(tile_key.TileKey.SURFACE, 3, -7, 0),
            tile_key.TileKey(tile_key.TileKey.SURFACE, -1, 2, 5),
            tile_key.TileKey(64, 0, 0, 1),
        ):
            self.assertEqual(tile_key.TileKey.parse_stem(key.file_stem()), key)

    def test_file_stem_names_surface_and_cave(self):
        self.assertEqual(tile_key.TileKey(tile_key.TileKey.SURFACE, 3, -7, 0).file_stem(), "surface_3_-7_l0")
        self.assertEqual(tile_key.TileKey(64, 0, 0, 1).file_stem(), "cave64_0_0_l1")

    def test_parse_stem_rejects_junk(self):
        for junk in ("surface_3_-7", "surface_3_-7_x0", "nope_0_0_l0", "surface_a_b_l0"):
            with self.assertRaises(ValueError):
                tile_key.TileKey.parse_stem(junk)

    def test_repr_mentions_fields(self):
        self.assertIn("lod=", repr(tile_key.TileKey(tile_key.TileKey.SURFACE, 1, 2, 3)))


if __name__ == "__main__":
    unittest.main()
