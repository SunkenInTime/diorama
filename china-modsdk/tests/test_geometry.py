"""Tests for modCore.geometry: tile size, pixels per unit, coverage cells."""

import unittest

from _core import load

geometry = load("geometry")


class GeometryTest(unittest.TestCase):
    def test_defaults_match_the_mod(self):
        default = geometry.Geometry()
        self.assertEqual(default.tile_units, 128)
        self.assertEqual(default.pixels_per_unit, 4)
        self.assertEqual(default.coverage_cells, 64)
        self.assertEqual(default.tile_pixels, 512)

    def test_derived_values(self):
        small = geometry.Geometry(8, 2, 4, "small")
        self.assertEqual(small.tile_pixels, 16)
        self.assertAlmostEqual(small.units_per_pixel, 0.5)
        self.assertAlmostEqual(small.units_per_coverage_cell, 2.0)

    def test_units_per_coverage_cell_is_units_over_cells(self):
        value = geometry.Geometry(128, 4, 64)
        self.assertAlmostEqual(value.units_per_coverage_cell, 2.0)

    def test_rejects_non_positive_sizes(self):
        with self.assertRaises(ValueError):
            geometry.Geometry(0, 4)
        with self.assertRaises(ValueError):
            geometry.Geometry(128, 0)
        with self.assertRaises(ValueError):
            geometry.Geometry(-1, -1)

    def test_coverage_cells_is_at_least_one(self):
        self.assertEqual(geometry.Geometry(8, 1, 0).coverage_cells, 1)

    def test_values_are_coerced_to_int(self):
        value = geometry.Geometry("16", "2", "4")
        self.assertEqual((value.tile_units, value.pixels_per_unit, value.coverage_cells), (16, 2, 4))

    def test_runtime_and_preview_presets(self):
        self.assertEqual(geometry.GEOMETRY.tile_units, 128)
        self.assertEqual(geometry.GEOMETRY.pixels_per_unit, 4)
        self.assertEqual(geometry.PREVIEW_GEOMETRY.tile_units, 32)
        self.assertEqual(geometry.PREVIEW_GEOMETRY.name, "preview")

    def test_repr_names_the_shape(self):
        self.assertIn("16", repr(geometry.Geometry(16, 2)))
        self.assertIn("preview", repr(geometry.PREVIEW_GEOMETRY))


if __name__ == "__main__":
    unittest.main()
