"""Tests for modCore.baker: the ray walk, tile bake and the TileImage wrapper."""

import unittest

from _core import load

baker = load("baker")
block_source = load("block_source")
geometry = load("geometry")
projection = load("projection")
tile_key = load("tile_key")
map_style = load("map_style")

STONE = "minecraft:stone"
AIR = "minecraft:air"

SURFACE_KEY = tile_key.TileKey(tile_key.TileKey.SURFACE, 0, 0, 0)
TINY = geometry.Geometry(4, 1, 2, "tiny")


def flat_source(top=10, **kwargs):
    return block_source.FunctionBlockSource(
        lambda x, y, z: STONE if y <= top else AIR,
        min_y=0,
        max_y=64,
        top_y=top + 1,
        **kwargs
    )


class ColourTest(unittest.TestCase):
    def test_abgr_packs_channels(self):
        self.assertEqual(baker.abgr(0x11, 0x22, 0x33, 0x44), 0x44332211)

    def test_default_alpha_is_opaque(self):
        self.assertEqual(baker.abgr(1, 2, 3) >> 24, 255)

    def test_pack_and_unpack_round_trip(self):
        value = baker.abgr(1, 2, 3, 4)
        self.assertEqual(baker.unpack_abgr(value), (1, 2, 3, 4))

    def test_unpack_masks_each_channel(self):
        self.assertEqual(baker.unpack_abgr(0xFFFFFFFF), (255, 255, 255, 255))

    def test_abgr_masks_each_channel_to_eight_bits(self):
        # 300 -> 0x2C red, -5 -> 0xFB green, 999 -> 0xE7 blue and alpha.
        self.assertEqual(baker.abgr(300, -5, 999, 999), 0xE7E7FB2C)


class TraceResultTest(unittest.TestCase):
    def test_defaults(self):
        result = baker.TraceResult()
        self.assertEqual((result.red, result.green, result.blue), (0.0, 0.0, 0.0))
        self.assertEqual(result.alpha, 255)
        self.assertFalse(result.hit_solid)
        self.assertFalse(result.hit_top)
        self.assertIsNone(result.block)


class BakerSetupTest(unittest.TestCase):
    def test_key_must_be_a_tile_key(self):
        with self.assertRaises(TypeError):
            baker.Baker("not a tile", flat_source())

    def test_defaults_come_from_the_style(self):
        instance = baker.Baker(SURFACE_KEY, flat_source(), geometry=TINY)
        self.assertEqual(instance.projection.pitch_degrees, map_style.DEFAULT.pitch_degrees)
        self.assertFalse(instance.cave)
        self.assertTrue(instance.roofless is False)


class TraceTest(unittest.TestCase):
    def setUp(self):
        self.p = projection.Projection(50)

    def test_flat_ground_hits_the_top_face(self):
        instance = baker.Baker(SURFACE_KEY, flat_source(top=10), geometry=TINY)
        result = instance.trace(0.5, self.p.v(11, 5.5))
        self.assertIsNotNone(result)
        self.assertEqual(result.y, 10)
        self.assertTrue(result.hit_top)
        self.assertTrue(result.hit_solid)
        self.assertEqual(result.block, STONE)
        self.assertEqual(result.alpha, 255)
        self.assertGreater(result.red + result.green + result.blue, 0.0)

    def test_missing_chunk_traces_to_none(self):
        source = block_source.InMemoryBlockSource({(0, 10, 0): STONE}, loaded_chunks=[])
        instance = baker.Baker(SURFACE_KEY, source, geometry=TINY)
        self.assertIsNone(instance.trace(0.5, self.p.v(11, 5.5)))

    def test_running_out_of_world_returns_a_dim_result(self):
        source = block_source.FunctionBlockSource(lambda x, y, z: AIR, min_y=0, max_y=64, top_y=63)
        instance = baker.Baker(SURFACE_KEY, source, geometry=TINY)
        result = instance.trace(0.5, self.p.v(11, 5.5))
        self.assertIsNotNone(result)
        self.assertFalse(result.hit_solid)
        self.assertIsNone(result.block)
        self.assertLess(result.red, 0.05)

    def test_cave_slice_skips_solid_rock(self):
        def generator(x, y, z):
            if 20 <= y <= 25:
                return AIR
            if y <= 48:
                return STONE
            return AIR

        source = block_source.FunctionBlockSource(generator, min_y=0, max_y=64, top_y=49)
        key = tile_key.TileKey(48, 0, 0, 0)
        instance = baker.Baker(key, source, geometry=TINY)
        result = instance.trace(0.5, self.p.v(49, 5.5))
        self.assertIsNotNone(result)
        self.assertEqual(result.y, 19)
        self.assertTrue(result.hit_top)

    def test_projection_override_is_used(self):
        steep = projection.Projection(70)
        instance = baker.Baker(SURFACE_KEY, flat_source(top=10), geometry=TINY, projection=steep)
        self.assertIs(instance.projection, steep)
        self.assertAlmostEqual(instance.sin, steep.sin, places=12)


class BakeTest(unittest.TestCase):
    def test_bake_returns_a_filled_tile_image(self):
        image = baker.bake_tile(SURFACE_KEY, flat_source(top=10), geometry=TINY, fast=True)
        self.assertIsInstance(image, baker.TileImage)
        self.assertEqual(image.size, 4)
        self.assertEqual(len(image.pixels), 16)
        self.assertEqual(image.key, SURFACE_KEY)
        self.assertTrue(all(pixel >> 24 == 255 for pixel in image.pixels))

    def test_bake_with_no_data_is_transparent(self):
        # A block exists so the ray actually starts and asks the source; the
        # chunk is unloaded, so every state() call returns None.
        source = block_source.InMemoryBlockSource({(0, 10, 0): STONE}, loaded_chunks=[])
        image = baker.bake_tile(SURFACE_KEY, source, geometry=TINY, fast=True)
        self.assertTrue(all(pixel == 0 for pixel in image.pixels))
        self.assertAlmostEqual(image.known_fraction(), 0.0)

    def test_saved_pixels_win_where_the_fresh_bake_is_unknown(self):
        source = block_source.InMemoryBlockSource({(0, 10, 0): STONE}, loaded_chunks=[])
        saved = [0xFF336699] * 16
        image = baker.bake_tile(SURFACE_KEY, source, geometry=TINY, fast=True, saved=saved)
        self.assertEqual(image.pixels, saved)

    def test_saved_pixels_do_not_override_a_known_fresh_pixel(self):
        saved = [0xFF000000] * 16
        image = baker.bake_tile(SURFACE_KEY, flat_source(top=10), geometry=TINY, fast=True, saved=saved)
        self.assertTrue(all(pixel >> 24 == 255 for pixel in image.pixels))
        self.assertNotEqual(image.pixels, saved)

    def test_progress_is_reported_on_the_requested_rows(self):
        calls = []
        instance = baker.Baker(SURFACE_KEY, flat_source(top=10), geometry=TINY)
        instance.bake(fast=True, progress=lambda row, size: calls.append((row, size)), progress_every=2)
        self.assertEqual(calls, [(0, 4), (2, 4)])

    def test_progress_defaults_to_every_32_rows(self):
        calls = []
        instance = baker.Baker(SURFACE_KEY, flat_source(top=10), geometry=TINY)
        instance.bake(fast=True, progress=lambda row, size: calls.append(row))
        self.assertEqual(calls, [0])

    def test_fast_and_four_sample_bakes_both_fill_the_tile(self):
        image = baker.bake_tile(SURFACE_KEY, flat_source(top=10), geometry=TINY, fast=False)
        self.assertTrue(all(pixel >> 24 == 255 for pixel in image.pixels))


class TileImageTest(unittest.TestCase):
    def setUp(self):
        self.image = baker.bake_tile(SURFACE_KEY, flat_source(top=10), geometry=TINY, fast=True)

    def test_mips_are_built_once_and_cached(self):
        first = self.image.mips()
        self.assertIs(self.image.mips(), first)
        self.assertEqual(len(first), 3)  # 4 -> 2 -> 1

    def test_with_generation_returns_self(self):
        self.assertIs(self.image.with_generation(7), self.image)
        self.assertEqual(self.image.generation, 7)

    def test_coverage_at_is_opaque_when_all_known(self):
        self.assertEqual(self.image.coverage_at(0, 0), 255)
        self.assertEqual(self.image.coverage_at(99, 99), 255)

    def test_coverage_at_without_coverage_is_255(self):
        empty = baker.TileImage(SURFACE_KEY, [0] * 16, [], 4, TINY)
        self.assertEqual(empty.coverage_at(0, 0), 255)

    def test_known_fraction(self):
        self.assertAlmostEqual(self.image.known_fraction(), 1.0)

    def test_repr_mentions_the_size(self):
        self.assertIn("size=4", repr(self.image))


if __name__ == "__main__":
    unittest.main()
