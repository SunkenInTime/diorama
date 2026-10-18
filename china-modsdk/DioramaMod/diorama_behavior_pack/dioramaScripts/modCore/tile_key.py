# -*- coding: utf-8 -*-
"""One tile of the map, ported from the Fabric mod's ``TileKey.java``.

``layer`` is :data:`TileKey.SURFACE` for the sky view, or the cut height for a
cave slice. ``lod`` 0 is a baked tile; each level above covers twice the
distance in the same number of pixels, made by shrinking its four children, so
the map can be zoomed far out without baking another tile.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from .geometry import GEOMETRY


class TileKey(object):
    __slots__ = ("layer", "tile_u", "tile_v", "lod")

    #: Sentinel layer for the surface (sky) view. Chosen to match the Java
    #: ``Integer.MIN_VALUE`` so tile caches written by the mod could be reused.
    SURFACE = -2147483648
    #: Coarsest overview level: one tile covers 4096 blocks across.
    MAX_LOD = 5

    def __init__(self, layer, tile_u, tile_v, lod=0):
        self.layer = int(layer)
        self.tile_u = int(tile_u)
        self.tile_v = int(tile_v)
        self.lod = int(lod)

    def is_cave(self):
        return self.layer != self.SURFACE

    def units(self, geometry=GEOMETRY):
        """Map units across this tile."""
        return float(geometry.tile_units) * (1 << self.lod)

    def pixels(self, geometry=GEOMETRY):
        """Texture pixels across this tile."""
        return geometry.tile_pixels * (1 << self.lod)

    def min_u(self, geometry=GEOMETRY):
        return self.tile_u * self.units(geometry)

    def min_v(self, geometry=GEOMETRY):
        return self.tile_v * self.units(geometry)

    def parent(self):
        """The overview tile one level up that contains this one."""
        return TileKey(self.layer, self.tile_u // 2, self.tile_v // 2, self.lod + 1)

    def children(self):
        """The four tiles one level down; only meaningful when ``lod > 0``."""
        lod = self.lod - 1
        u0 = self.tile_u * 2
        v0 = self.tile_v * 2
        return (
            TileKey(self.layer, u0, v0, lod),
            TileKey(self.layer, u0 + 1, v0, lod),
            TileKey(self.layer, u0, v0 + 1, lod),
            TileKey(self.layer, u0 + 1, v0 + 1, lod),
        )

    def tile_for(self, u, v, geometry=GEOMETRY, lod=0):
        """The level-``lod`` tile that contains map point ``(u, v)``."""
        units = float(geometry.tile_units) * (1 << lod)
        return TileKey(self.layer, int(_floor(u / units)), int(_floor(v / units)), lod)

    def cache_key(self):
        """A hashable key for dictionaries, also used as the on-disk stem."""
        return (self.layer, self.tile_u, self.tile_v, self.lod)

    def file_stem(self):
        """Filesystem-safe stem, e.g. ``surface_3_-7_l0`` / ``cave64_0_0_l1``."""
        layer = "surface" if not self.is_cave() else "cave%d" % self.layer
        return "%s_%d_%d_l%d" % (layer, self.tile_u, self.tile_v, self.lod)

    @classmethod
    def parse_stem(cls, stem):
        """Inverse of :meth:`file_stem`; raises ``ValueError`` on junk."""
        parts = stem.split("_")
        if len(parts) != 4 or not parts[3].startswith("l"):
            raise ValueError("not a tile stem: %r" % (stem,))
        layer_name, tile_u, tile_v, lod = parts
        if layer_name == "surface":
            layer = cls.SURFACE
        elif layer_name.startswith("cave"):
            layer = int(layer_name[4:])
        else:
            raise ValueError("not a tile stem: %r" % (stem,))
        return cls(layer, int(tile_u), int(tile_v), int(lod[1:]))

    def __eq__(self, other):
        if not isinstance(other, TileKey):
            return NotImplemented
        return self.cache_key() == other.cache_key()

    def __ne__(self, other):
        result = self.__eq__(other)
        if result is NotImplemented:
            return result
        return not result

    def __hash__(self):
        return hash(self.cache_key())

    def __repr__(self):
        return "TileKey(layer=%r, tile_u=%r, tile_v=%r, lod=%r)" % (
            self.layer,
            self.tile_u,
            self.tile_v,
            self.lod,
        )


def _floor(value):
    floored = int(value)
    return floored - 1 if value < floored else floored
