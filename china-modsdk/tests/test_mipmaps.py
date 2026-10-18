"""Tests for modCore.mipmaps: the alpha-weighted tile pyramid."""

import unittest

from _core import load

mipmaps = load("mipmaps")

RED = 0xFF0000FF  # ABGR: a=FF, b=00, g=00, r=FF
WHITE = 0xFFFFFFFF
BLACK = 0xFF000000


class HalfTest(unittest.TestCase):
    def test_uniform_opaque_pixels_are_unchanged(self):
        pixels, size = mipmaps.half([RED] * 4, 2)
        self.assertEqual(size, 1)
        self.assertEqual(pixels, [RED])

    def test_transparent_pixels_do_not_darken_known_ones(self):
        pixels, _ = mipmaps.half([WHITE, 0, 0, 0], 2)
        self.assertEqual(pixels[0], 0x3FFFFFFF)

    def test_all_transparent_averages_to_zero(self):
        pixels, _ = mipmaps.half([0, 0, 0, 0], 2)
        self.assertEqual(pixels, [0])

    def test_alpha_is_the_average_of_the_four_alphas(self):
        pixels, _ = mipmaps.half([BLACK, 0x80000000, 0, 0], 2)
        self.assertEqual(pixels[0] >> 24, (255 + 128) // 4)

    def test_colour_is_alpha_weighted(self):
        # Opaque white plus transparent pixels: the colour stays white, not grey.
        pixels, _ = mipmaps.half([WHITE, 0, 0, 0], 2)
        self.assertEqual(pixels[0] & 0xFF, 255)
        self.assertEqual((pixels[0] >> 8) & 0xFF, 255)
        self.assertEqual((pixels[0] >> 16) & 0xFF, 255)

    def test_mixes_two_opaque_colours(self):
        red = 0xFF0000FF
        white = WHITE
        pixels, _ = mipmaps.half([red, white, 0, 0], 2)
        self.assertEqual(pixels[0] & 0xFF, 255)  # r
        self.assertEqual((pixels[0] >> 8) & 0xFF, 127)  # g
        self.assertEqual((pixels[0] >> 16) & 0xFF, 127)  # b

    def test_size_too_small_returns_empty(self):
        self.assertEqual(mipmaps.half([WHITE], 1), ([], 0))

    def test_pack_and_unpack_round_trip(self):
        self.assertEqual(mipmaps._pack(*mipmaps._unpack(0xFF3366AA)), 0xFF3366AA)


class BuildTest(unittest.TestCase):
    def test_chain_halves_to_one_pixel(self):
        chain = mipmaps.build([WHITE] * 16, 4)
        self.assertEqual([len(level) for level in chain], [16, 4, 1])

    def test_level_zero_is_the_input(self):
        pixels = [WHITE] * 16
        self.assertIs(mipmaps.build(pixels, 4)[0], pixels)

    def test_uniform_colour_survives_every_level(self):
        for level in mipmaps.build([RED] * 64, 8):
            for pixel in level:
                self.assertEqual(pixel, RED)

    def test_levels_cap_the_chain(self):
        chain = mipmaps.build([WHITE] * 64, 8, levels=2)
        self.assertEqual([len(level) for level in chain], [64, 16])

    def test_non_power_of_two_stops_cleanly(self):
        chain = mipmaps.build([WHITE] * 9, 3)
        self.assertEqual(chain[0], [WHITE] * 9)
        self.assertTrue(all(len(level) > 0 for level in chain))


class SampleTest(unittest.TestCase):
    def setUp(self):
        self.chain = mipmaps.build([WHITE] * 16, 4)

    def test_picks_the_requested_level(self):
        self.assertEqual(mipmaps.sample(self.chain, 0, 0, 0), self.chain[0][0])
        self.assertEqual(mipmaps.sample(self.chain, 1, 0, 0), self.chain[1][0])
        self.assertEqual(mipmaps.sample(self.chain, 2, 0, 0), self.chain[2][0])

    def test_level_is_clamped(self):
        self.assertEqual(mipmaps.sample(self.chain, -5, 0, 0), self.chain[0][0])
        self.assertEqual(mipmaps.sample(self.chain, 99, 0, 0), self.chain[-1][0])

    def test_coordinates_are_clamped(self):
        self.assertEqual(mipmaps.sample(self.chain, 0, -1, -1), self.chain[0][0])
        self.assertEqual(mipmaps.sample(self.chain, 0, 99, 99), self.chain[0][-1])

    def test_level_sizes_follow_the_chain(self):
        # Level 1 of a size-4 chain is 2x2.
        self.assertEqual(mipmaps.sample(self.chain, 1, 1, 1), self.chain[1][3])


if __name__ == "__main__":
    unittest.main()
