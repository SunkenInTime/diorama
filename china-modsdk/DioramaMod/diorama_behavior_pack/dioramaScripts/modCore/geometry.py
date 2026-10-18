# -*- coding: utf-8 -*-
"""Tile geometry: how many map units and texture pixels a tile covers.

The Fabric mod hard-codes 128 map units and 4 texture pixels per unit (a
512x512 tile). Bakes at that size are far too slow for pure-Python code, so the
baker takes a :class:`Geometry` instead of reading the constants directly. The
runtime uses :data:`GEOMETRY`; the PC preview tool and the tests use a smaller
one and therefore bake in a second instead of minutes.
"""

from __future__ import absolute_import, division, print_function, unicode_literals


class Geometry(object):
    """Map units and pixels per tile edge."""

    __slots__ = ("tile_units", "pixels_per_unit", "coverage_cells", "name")

    def __init__(self, tile_units=128, pixels_per_unit=4, coverage_cells=64, name="default"):
        tile_units = int(tile_units)
        pixels_per_unit = int(pixels_per_unit)
        if tile_units <= 0 or pixels_per_unit <= 0:
            raise ValueError("geometry must be positive, got %r/%r" % (tile_units, pixels_per_unit))
        self.tile_units = tile_units
        self.pixels_per_unit = pixels_per_unit
        self.coverage_cells = max(1, int(coverage_cells))
        self.name = name

    @property
    def tile_pixels(self):
        return self.tile_units * self.pixels_per_unit

    @property
    def units_per_pixel(self):
        return 1.0 / self.pixels_per_unit

    @property
    def units_per_coverage_cell(self):
        return float(self.tile_units) / self.coverage_cells

    def __repr__(self):
        return "Geometry(tile_units=%d, pixels_per_unit=%d, coverage_cells=%d, name=%r)" % (
            self.tile_units,
            self.pixels_per_unit,
            self.coverage_cells,
            self.name,
        )


#: What the in-game client uses; matches Projection.TILE_UNITS / PIXELS_PER_UNIT.
GEOMETRY = Geometry(128, 4, 64, "runtime")

#: Small, fast geometry for the PC workbench, tests and doc screenshots.
#: 32 map units across, 4 pixels per unit: a 128x128 PNG for a scene about
#: 48 x 76 x 48 blocks, which a pure-Python bake finishes in seconds.
PREVIEW_GEOMETRY = Geometry(32, 4, 16, "preview")
