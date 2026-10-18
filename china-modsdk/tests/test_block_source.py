"""Tests for modCore.block_source: the block/light interface and its fakes."""

import unittest

from _core import load

block_source = load("block_source")
palette = load("palette")

STONE = "minecraft:stone"
AIR = "minecraft:air"


class BaseContractTest(unittest.TestCase):
    def test_defaults(self):
        source = block_source.BlockSource()
        self.assertEqual(source.min_y, 0)
        self.assertEqual(source.max_y, 256)
        self.assertEqual(source.roof, block_source.NO_ROOF)
        self.assertTrue(source.is_seen(0, 0, 0))
        self.assertTrue(source.chunk_loaded(0, 0))
        self.assertIsNone(source.sky_light(0, 0, 0))
        self.assertIsNone(source.block_light(0, 0, 0))

    def test_default_biome_tint_is_the_temperate_fallback(self):
        source = block_source.BlockSource()
        self.assertEqual(
            source.biome_tint(0, 0, palette.TINT_GRASS),
            palette.DEFAULT_TINTS[palette.TINT_GRASS],
        )

    def test_state_must_be_overridden(self):
        with self.assertRaises(NotImplementedError):
            block_source.BlockSource().state(0, 0, 0)


class InMemorySourceTest(unittest.TestCase):
    def test_values_and_vertical_bounds(self):
        source = block_source.InMemoryBlockSource(
            {(0, 5, 0): STONE}, min_y=0, max_y=16
        )
        self.assertEqual(source.state(0, 5, 0), STONE)
        self.assertEqual(source.state(0, 4, 0), AIR)
        self.assertEqual(source.state(0, 16, 0), AIR)
        self.assertEqual(source.state(0, -1, 0), AIR)

    def test_loaded_chunks_gate_the_data(self):
        source = block_source.InMemoryBlockSource(
            {(0, 5, 0): STONE, (16, 5, 0): STONE}, loaded_chunks=[(0, 0)]
        )
        self.assertEqual(source.state(0, 5, 0), STONE)
        self.assertIsNone(source.state(16, 5, 0))

    def test_top_y_is_the_tallest_block(self):
        source = block_source.InMemoryBlockSource({(0, 5, 0): STONE, (1, 9, 1): STONE})
        self.assertEqual(source.top_y, 9)

    def test_top_y_is_clamped_to_the_world(self):
        source = block_source.InMemoryBlockSource({(0, 500, 0): STONE}, max_y=64)
        self.assertEqual(source.top_y, 63)

    def test_light_tables(self):
        source = block_source.InMemoryBlockSource(
            {}, sky_light={(0, 5, 0): 12}, block_light={(0, 5, 0): 3}
        )
        self.assertEqual(source.sky_light(0, 5, 0), 12)
        self.assertEqual(source.block_light(0, 5, 0), 3)
        self.assertIsNone(source.sky_light(9, 9, 9))

    def test_tint_override(self):
        source = block_source.InMemoryBlockSource(
            {}, tints={palette.TINT_GRASS: (1, 2, 3)}
        )
        self.assertEqual(source.biome_tint(0, 0, palette.TINT_GRASS), (1, 2, 3))
        self.assertEqual(
            source.biome_tint(0, 0, palette.TINT_WATER),
            palette.DEFAULT_TINTS[palette.TINT_WATER],
        )

    def test_from_rows_offsets_by_origin(self):
        source = block_source.InMemoryBlockSource.from_rows(
            [(1, 2, 3, STONE)], origin=(10, 20, 30)
        )
        self.assertEqual(source.state(11, 22, 33), STONE)
        self.assertEqual(source.state(1, 2, 3), AIR)


class FunctionSourceTest(unittest.TestCase):
    def _source(self, **kwargs):
        return block_source.FunctionBlockSource(
            lambda x, y, z: STONE if y <= 10 else AIR,
            min_y=0,
            max_y=64,
            **kwargs
        )

    def test_generator_values_and_bounds(self):
        source = self._source()
        self.assertEqual(source.state(0, 10, 0), STONE)
        self.assertEqual(source.state(0, 11, 0), AIR)
        self.assertEqual(source.state(0, 64, 0), AIR)
        self.assertEqual(source.state(0, -1, 0), AIR)

    def test_top_y_defaults_to_one_below_max(self):
        self.assertEqual(self._source().top_y, 63)

    def test_top_y_override(self):
        self.assertEqual(self._source(top_y=11).top_y, 11)

    def test_loaded_fn_gates_the_data(self):
        source = self._source(loaded_fn=lambda x, z: x >= 0)
        self.assertEqual(source.state(0, 5, 0), STONE)
        self.assertIsNone(source.state(-1, 5, 0))

    def test_seen_fn_defaults_to_true(self):
        self.assertTrue(self._source().is_seen(0, 0, 0))
        source = self._source(seen_fn=lambda x, y, z: y > 0)
        self.assertFalse(source.is_seen(0, 0, 0))
        self.assertTrue(source.is_seen(0, 1, 0))

    def test_light_and_tint_functions(self):
        source = self._source(
            sky_light_fn=lambda x, y, z: 15,
            block_light_fn=lambda x, y, z: 7,
            tint_fn=lambda x, z, kind: (9, 8, 7),
        )
        self.assertEqual(source.sky_light(0, 0, 0), 15)
        self.assertEqual(source.block_light(0, 0, 0), 7)
        self.assertEqual(source.biome_tint(0, 0, palette.TINT_GRASS), (9, 8, 7))

    def test_without_functions_lights_are_absent(self):
        source = self._source()
        self.assertIsNone(source.sky_light(0, 0, 0))
        self.assertIsNone(source.block_light(0, 0, 0))


class SyntheticWorldTest(unittest.TestCase):
    def setUp(self):
        self.world = block_source.SyntheticWorld(radius_x=12, radius_z=24, z_origin=0)

    def test_height_is_deterministic_and_bounded(self):
        first = self.world.height(3, 5)
        self.assertEqual(first, self.world.height(3, 5))
        self.assertGreaterEqual(first, 3)
        self.assertLessEqual(first, self.world.max_height - 6)

    def test_bedrock_floor(self):
        self.assertEqual(self.world.block_at(0, self.world.min_y, 5), "minecraft:bedrock")

    def test_terrain_surface_is_solid(self):
        h = self.world.height(0, 10)
        self.assertNotEqual(self.world.block_at(0, h, 10), AIR)

    def test_outside_the_region_has_no_data(self):
        self.assertIsNone(self.world.block_at(1000, 10, 10))

    def test_sky_light_is_open_above_ground(self):
        h = self.world.height(0, 10)
        self.assertEqual(self.world.sky_light(0, h + 5, 10), 15)
        self.assertEqual(self.world.sky_light(0, h, 10), 0)

    def test_source_wraps_the_world(self):
        source = self.world.source()
        self.assertIsInstance(source, block_source.FunctionBlockSource)
        self.assertEqual(source.min_y, self.world.min_y)
        self.assertEqual(source.top_y, self.world.top_y)
        self.assertEqual(source.state(0, self.world.min_y, 10), "minecraft:bedrock")

    def test_source_seen_hook(self):
        seen = lambda x, y, z: False
        source = self.world.source(seen=seen)
        self.assertFalse(source.is_seen(0, 5, 5))


if __name__ == "__main__":
    unittest.main()
