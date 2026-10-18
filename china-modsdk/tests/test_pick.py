"""Tests for modCore.pick: what a map pixel shows, and column heights."""

import unittest

from _core import load

pick = load("pick")
block_source = load("block_source")
tile_key = load("tile_key")
geometry = load("geometry")
projection = load("projection")
map_style = load("map_style")

STONE = "minecraft:stone"
AIR = "minecraft:air"


def flat_source(top=10, **kwargs):
    return block_source.FunctionBlockSource(
        lambda x, y, z: STONE if y <= top else AIR,
        min_y=0,
        max_y=64,
        top_y=top + 1,
        **kwargs
    )


class MapPointTest(unittest.TestCase):
    def test_map_point_is_x_and_v(self):
        p = projection.Projection(50)
        self.assertEqual(pick.map_point(5, 11, 5.5, p), (5, p.v(11, 5.5)))

    def test_projection_for_reads_the_style_pitch(self):
        style = map_style.MapStyle(pitch_degrees=60)
        p = pick.projection_for(style)
        self.assertIsInstance(p, projection.Projection)
        self.assertEqual(p.pitch_degrees, 60.0)

    def test_projection_for_defaults_without_a_style(self):
        self.assertEqual(pick.projection_for(object()).pitch_degrees, 50.0)


class StandingSpotTest(unittest.TestCase):
    def setUp(self):
        self.p = projection.Projection(50)
        self.v = self.p.v(11, 5.5)

    def test_flat_ground_picks_the_cell_above_it(self):
        self.assertEqual(pick.standing_spot(flat_source(), 5.5, self.v, self.p), (5, 11, 5))

    def test_explicit_start_and_limit_are_honoured(self):
        source = flat_source()
        self.assertEqual(
            pick.standing_spot(source, 5.5, self.v, self.p, start_y=40, limit_y=0), (5, 11, 5)
        )
        self.assertIsNone(
            pick.standing_spot(source, 5.5, self.v, self.p, start_y=40, limit_y=50)
        )

    def test_water_surface_is_picked(self):
        source = block_source.FunctionBlockSource(
            lambda x, y, z: STONE if y <= 8 else ("minecraft:water" if y <= 10 else AIR),
            min_y=0,
            max_y=64,
            top_y=11,
        )
        self.assertEqual(
            pick.standing_spot(source, 5.5, self.v, self.p, start_y=40, limit_y=0), (5, 11, 5)
        )

    def test_missing_chunk_gives_no_pick(self):
        source = block_source.InMemoryBlockSource({(0, 10, 0): STONE}, loaded_chunks=[])
        self.assertIsNone(pick.standing_spot(source, 5.5, self.v, self.p, start_y=40, limit_y=0))

    def test_cave_slice_skips_the_rock_above(self):
        def generator(x, y, z):
            if 20 <= y <= 25:
                return AIR
            if y <= 48:
                return STONE
            return AIR

        source = block_source.FunctionBlockSource(generator, min_y=0, max_y=64, top_y=49)
        hit = pick.standing_spot(source, 5.5, self.p.v(49, 5.5), self.p, layer=48, start_y=48, limit_y=0)
        self.assertEqual(hit[1], 20)  # the open cell above the pocket floor at y=19

    def test_fog_of_war_hides_unseen_cave_blocks(self):
        p = projection.Projection(50)
        v = p.v(40, 5.5)
        seen = flat_source(seen_fn=lambda x, y, z: True)
        unseen = flat_source(seen_fn=lambda x, y, z: False)
        self.assertIsNotNone(
            pick.standing_spot(seen, 5.5, v, p, layer=48, fog_of_war=True, start_y=40, limit_y=0)
        )
        self.assertIsNone(
            pick.standing_spot(unseen, 5.5, v, p, layer=48, fog_of_war=True, start_y=40, limit_y=0)
        )

    def test_fog_of_war_is_ignored_on_the_surface(self):
        source = flat_source(seen_fn=lambda x, y, z: False)
        self.assertIsNotNone(
            pick.standing_spot(source, 5.5, self.v, self.p, fog_of_war=True, start_y=40, limit_y=0)
        )


class GroundHeightTest(unittest.TestCase):
    def test_finds_the_highest_non_air_block(self):
        source = block_source.InMemoryBlockSource(
            {(0, 5, 0): STONE, (0, 9, 0): STONE}, min_y=0, max_y=32, top_y=9
        )
        self.assertEqual(pick.ground_height(source, 0, 0), 9)

    def test_empty_column_reports_the_floor(self):
        source = block_source.InMemoryBlockSource({}, min_y=0, max_y=32)
        self.assertEqual(pick.ground_height(source, 0, 0), 0)

    def test_missing_chunk_gives_none(self):
        source = block_source.InMemoryBlockSource({}, loaded_chunks=[])
        self.assertIsNone(pick.ground_height(source, 0, 0))

    def test_explicit_top_is_respected(self):
        source = block_source.InMemoryBlockSource({(0, 20, 0): STONE}, min_y=0, max_y=32)
        self.assertEqual(pick.ground_height(source, 0, 0, top=10), 0)


class TileOfTest(unittest.TestCase):
    def test_zero_point(self):
        self.assertEqual(pick.tile_of(0, 0), tile_key.TileKey(tile_key.TileKey.SURFACE, 0, 0, 0))

    def test_negative_point_floors(self):
        self.assertEqual(
            pick.tile_of(-0.5, -0.5, geometry.GEOMETRY),
            tile_key.TileKey(tile_key.TileKey.SURFACE, -1, -1, 0),
        )

    def test_lod_scales_the_tile(self):
        key = pick.tile_of(10, 10, geometry.GEOMETRY, lod=1)
        self.assertEqual(key.lod, 1)
        self.assertEqual(key.tile_u, 0)
        self.assertEqual(key.tile_v, 0)

    def test_geometry_may_be_passed_positionally(self):
        # Back-compat form: tile_of(u, v, geometry).
        self.assertEqual(
            pick.tile_of(0, 0, geometry.PREVIEW_GEOMETRY),
            tile_key.TileKey(tile_key.TileKey.SURFACE, 0, 0, 0),
        )


if __name__ == "__main__":
    unittest.main()
