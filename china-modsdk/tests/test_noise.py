"""Tests for modCore.noise: the block hash, smoothstep and the sRGB tables."""

import math
import unittest

from _core import load

noise = load("noise")


class BlockNoiseTest(unittest.TestCase):
    #: Values from an independent 32-bit C-semantics implementation of the same
    #: Java hash (int wrapping and logical shifts).
    KNOWN = {
        (0, 0, 0): -1.0,
        (1, 2, 3): -0.8213473716334783,
        (10, 64, -5): -0.561852445258259,
        (-7, 13, 21): 0.8666361486228733,
        (123456, 64, -987654): 0.9004501411459525,
    }

    def test_known_values(self):
        for (x, y, z), expected in self.KNOWN.items():
            self.assertAlmostEqual(noise.block_noise(x, y, z), expected, places=12)

    def test_is_deterministic(self):
        self.assertEqual(noise.block_noise(3, 4, 5), noise.block_noise(3, 4, 5))

    def test_neighbouring_blocks_differ(self):
        self.assertNotEqual(noise.block_noise(3, 4, 5), noise.block_noise(5, 4, 3))

    def test_stays_in_range(self):
        for x in range(-8, 9):
            for y in (0, 17, 64, 200):
                for z in range(-8, 9):
                    value = noise.block_noise(x, y, z)
                    self.assertGreaterEqual(value, -1.0)
                    self.assertLessEqual(value, 1.0 + 1e-9)

    def test_accepts_string_numerals(self):
        self.assertEqual(noise.block_noise("1", "2", "3"), noise.block_noise(1, 2, 3))


class SmoothstepTest(unittest.TestCase):
    def test_clamps_outside_and_midpoint(self):
        self.assertEqual(noise.smoothstep(0, 1, -5), 0.0)
        self.assertEqual(noise.smoothstep(0, 1, 5), 1.0)
        self.assertAlmostEqual(noise.smoothstep(0, 1, 0.5), 0.5, places=12)

    def test_degenerate_range_is_zero(self):
        self.assertEqual(noise.smoothstep(3, 3, 3), 0.0)

    def test_is_monotonic(self):
        values = [noise.smoothstep(0, 10, t / 10.0) for t in range(11)]
        self.assertEqual(values, sorted(values))


class SrgbTablesTest(unittest.TestCase):
    def test_table_sizes(self):
        self.assertEqual(len(noise.SRGB_TO_LINEAR), 256)
        self.assertEqual(len(noise.LINEAR_TO_SRGB), 4096)

    def test_srgb_to_linear_endpoints(self):
        self.assertAlmostEqual(noise.SRGB_TO_LINEAR[0], 0.0, places=12)
        self.assertAlmostEqual(noise.SRGB_TO_LINEAR[255], 1.0, places=6)

    def test_srgb_to_linear_is_increasing(self):
        table = noise.SRGB_TO_LINEAR
        for i in range(1, 256):
            self.assertGreater(table[i], table[i - 1])

    def test_linear_to_srgb_endpoints(self):
        self.assertEqual(noise.LINEAR_TO_SRGB[0], 0)
        self.assertEqual(noise.LINEAR_TO_SRGB[-1], 255)

    def test_linear_to_srgb_helper_clamps(self):
        self.assertEqual(noise.linear_to_srgb(-1.0), 0)
        self.assertEqual(noise.linear_to_srgb(2.0), 255)
        self.assertEqual(noise.linear_to_srgb(0.0), 0)
        self.assertEqual(noise.linear_to_srgb(1.0), 255)

    def test_linear_to_srgb_helper_is_monotonic(self):
        previous = -1
        for step in range(0, 101):
            value = noise.linear_to_srgb(step / 100.0)
            self.assertGreaterEqual(value, previous)
            previous = value

    def test_custom_table_steps(self):
        table = noise.linear_to_srgb_table(16)
        self.assertEqual(len(table), 16)
        self.assertEqual(table[0], 0)
        self.assertEqual(table[-1], 255)

    def test_srgb_conversion_matches_the_formula(self):
        # 0.5 in sRGB maps to about 0.214 in linear.
        expected = math.pow(((128 / 255.0) + 0.055) / 1.055, 2.4)
        self.assertAlmostEqual(noise.SRGB_TO_LINEAR[128], expected, places=9)


if __name__ == "__main__":
    unittest.main()
