# -*- coding: utf-8 -*-
"""Where the baker reads blocks, light and biome tints from.

The baker only ever talks to a :class:`BlockSource`. Two implementations ship
here, both free of any ModSDK import:

* :class:`FunctionBlockSource` - blocks come from a callable. The PC workbench
  uses it with :class:`SyntheticWorld`, so a full bake can be exercised without
  the game or any fixture files.
* :class:`InMemoryBlockSource` - blocks come from a dict; used by unit tests and
  by the JSON fixture loader.

The in-game adapter (``modServer/engine/block_source.py``) implements the same
interface over ``CreateBlockInfo(levelId).GetBlockNew``.

Contract, which the baker relies on:

* ``state(x, y, z)`` returns a block identifier string, a ``(name, aux)``
  tuple, or ``None`` when the map has no data for that position (chunk not
  loaded). Outside the vertical range it must return ``minecraft:air``.
* ``sky_light`` / ``block_light`` may return ``None`` to mean "not available";
  the baker then falls back to its own ray-based sky occlusion and treats block
  light as absent.
* Nothing here may raise for an out-of-range coordinate: the baker probes
  neighbours one block outside the tile.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import math

from . import palette

#: ``roof`` value for a dimension with no ceiling (Overworld, End).
NO_ROOF = -2147483648

AIR = "minecraft:air"


class BlockSource(object):
    """Read-only block/light access for one bake."""

    min_y = 0
    max_y = 256
    roof = NO_ROOF
    top_y = 255

    def state(self, x, y, z):  # pragma: no cover - interface
        raise NotImplementedError

    def block_light(self, x, y, z):
        return None

    def sky_light(self, x, y, z):
        return None

    def biome_tint(self, x, z, kind):
        """Biome colour for one tint kind, 0..255 RGB."""
        return palette.DEFAULT_TINTS.get(kind)

    def is_seen(self, x, y, z):
        """Fair-play fog of war; every position is 'seen' unless a server says otherwise."""
        return True

    def chunk_loaded(self, x, z):
        return True


class FunctionBlockSource(BlockSource):
    """A source whose blocks are computed on demand by a callable.

    ``generator(x, y, z)`` returns a block id or ``(id, aux)``. Anything the
    generator does not care about should return ``AIR``.
    """

    def __init__(
        self,
        generator,
        min_y=0,
        max_y=128,
        top_y=None,
        roof=NO_ROOF,
        sky_light_fn=None,
        block_light_fn=None,
        seen_fn=None,
        loaded_fn=None,
        tint_fn=None,
    ):
        self._generator = generator
        self.min_y = int(min_y)
        self.max_y = int(max_y)
        self.top_y = int(top_y) if top_y is not None else self.max_y - 1
        self.roof = roof
        self._sky = sky_light_fn
        self._block = block_light_fn
        self._seen = seen_fn
        self._loaded = loaded_fn
        self._tint = tint_fn

    def state(self, x, y, z):
        if y < self.min_y or y >= self.max_y:
            return AIR
        if self._loaded is not None and not self._loaded(x, z):
            return None
        return self._generator(x, y, z)

    def sky_light(self, x, y, z):
        if self._sky is None:
            return None
        return self._sky(x, y, z)

    def block_light(self, x, y, z):
        if self._block is None:
            return None
        return self._block(x, y, z)

    def biome_tint(self, x, z, kind):
        if self._tint is None:
            return palette.DEFAULT_TINTS.get(kind)
        return self._tint(x, z, kind)

    def is_seen(self, x, y, z):
        if self._seen is None:
            return True
        return bool(self._seen(x, y, z))

    def chunk_loaded(self, x, z):
        if self._loaded is None:
            return True
        return bool(self._loaded(x, z))


class InMemoryBlockSource(BlockSource):
    """A source backed by ``{(x, y, z): block_id}``; missing positions are air."""

    def __init__(
        self,
        blocks=None,
        min_y=0,
        max_y=128,
        roof=NO_ROOF,
        sky_light=None,
        block_light=None,
        loaded_chunks=None,
        tints=None,
        top_y=None,
    ):
        self.blocks = dict(blocks or {})
        self.min_y = int(min_y)
        self.max_y = int(max_y)
        self.roof = roof
        self.sky = dict(sky_light or {})
        self.light = dict(block_light or {})
        self.loaded_chunks = set(loaded_chunks) if loaded_chunks is not None else None
        self.tints = dict(tints or {})
        self.top_y = int(top_y) if top_y is not None else self._compute_top_y()

    def _compute_top_y(self):
        top = self.min_y
        for (x, y, z) in self.blocks:
            if y > top:
                top = y
        return min(self.max_y - 1, top)

    def state(self, x, y, z):
        if y < self.min_y or y >= self.max_y:
            return AIR
        if self.loaded_chunks is not None and (x >> 4, z >> 4) not in self.loaded_chunks:
            return None
        return self.blocks.get((x, y, z), AIR)

    def sky_light(self, x, y, z):
        return self.sky.get((x, y, z))

    def block_light(self, x, y, z):
        return self.light.get((x, y, z))

    def biome_tint(self, x, z, kind):
        if kind in self.tints:
            return self.tints[kind]
        return palette.DEFAULT_TINTS.get(kind)

    @classmethod
    def from_rows(cls, rows, origin=(0, 0, 0), **kwargs):
        """Build from a list of ``(dx, dy, dz, id)`` rows, e.g. a JSON fixture."""
        ox, oy, oz = origin
        blocks = {}
        for row in rows:
            dx, dy, dz, block_id = row[0], row[1], row[2], row[3]
            blocks[(ox + dx, oy + dy, oz + dz)] = block_id
        return cls(blocks, **kwargs)


# --------------------------------------------------------------------------
# Synthetic world for the PC workbench
# --------------------------------------------------------------------------


class SyntheticWorld(object):
    """A small deterministic scene: hills, a pond, trees and a stone keep.

    Used by ``tools/preview_bake.py`` and by the baker tests. Every value is a
    pure function of the coordinates, so the same PNG comes out on any machine
    and Python version - which is what makes the preview usable as a
    regression check for the projection and shading code.
    """

    def __init__(self, radius_x=40, radius_z=90, z_origin=10, sea_level=6, max_height=44):
        self.radius_x = int(radius_x)
        self.radius_z = int(radius_z)
        self.z_origin = int(z_origin)
        self.sea_level = int(sea_level)
        self.max_height = int(max_height)
        self.min_y = 1
        self.max_y = self.max_height + 3
        self.top_y = self.max_height
        self._height_cache = {}
        self._keep_origin = (4, 30)  # x, z of the keep's north-west corner
        self._pond_centre = (18, 34)

    # -- terrain -------------------------------------------------------

    def height(self, x, z):
        cached = self._height_cache.get((x, z))
        if cached is not None:
            return cached
        base = 14.0
        rolling = (
            3.2 * math.sin(x * 0.11)
            + 2.4 * math.cos(z * 0.07)
            + 1.6 * math.sin((x + z) * 0.045)
        )
        hills = 6.0 * _value_noise(x * 0.035, z * 0.035)
        # A broad ridge along the north edge, so the keep sits on high ground.
        ridge = 5.0 * math.exp(-((z - (self.z_origin + 26)) ** 2) / 900.0)
        # A depression for the pond.
        pond_dx = x - self._pond_centre[0]
        pond_dz = z - self._pond_centre[1]
        pond = -4.2 * math.exp(-(pond_dx * pond_dx + pond_dz * pond_dz) / 90.0)
        value = base + rolling + hills + ridge + pond
        h = int(round(value))
        h = max(3, min(self.max_height - 6, h))
        self._height_cache[(x, z)] = h
        return h

    def block_at(self, x, y, z):
        if y < self.min_y or y >= self.max_y:
            return AIR
        if x < -self.radius_x or x > self.radius_x or z < self.z_origin or z > self.z_origin + self.radius_z:
            return None  # outside the generated region: no data
        h = self.height(x, z)
        # keep, then trees, then terrain, so structures win over terrain
        keep = self._keep_block(x, y, z, h)
        if keep is not None:
            return keep
        tree = self._tree_block(x, y, z, h)
        if tree is not None:
            return tree
        if y == self.min_y:
            return "minecraft:bedrock"
        if y > h:
            if y <= self.sea_level and h < self.sea_level and self._in_pond(x, z):
                return "minecraft:water"
            return AIR
        if y == h:
            if h <= self.sea_level - 1 and self._in_pond(x, z):
                return "minecraft:water" if y >= h - 1 else "minecraft:sand"
            if h >= 30:
                return ("minecraft:grass", 0)
            if y <= self.sea_level:
                return "minecraft:sand"
            return "minecraft:grass"
        if y >= h - 3:
            return "minecraft:dirt"
        if y >= h - 6 and (x * 7 + z * 13) % 11 == 0:
            return "minecraft:gravel"
        if (x * 31 + y * 17 + z * 7) % 97 == 0 and y < h - 8:
            return "minecraft:coal_ore"
        if (x * 13 + y * 29 + z * 41) % 211 == 0 and y < h - 12:
            return "minecraft:iron_ore"
        return "minecraft:stone"

    def _in_pond(self, x, z):
        dx = x - self._pond_centre[0]
        dz = z - self._pond_centre[1]
        return (dx * dx + dz * dz) <= 36

    # -- the keep ------------------------------------------------------

    def _keep_block(self, x, y, z, h):
        kx, kz = self._keep_origin
        # A 13x11 keep, walls 4 high, with a tower at the north-west corner.
        if not (kx <= x <= kx + 12 and kz <= z <= kz + 10):
            return None
        ground = self.height(kx + 6, kz + 5) + 1
        local_x = x - kx
        local_z = z - kz
        if y < ground:
            return None
        wall = local_x in (0, 12) or local_z in (0, 10)
        tower = 0 <= local_x <= 3 and 0 <= local_z <= 3
        if wall:
            if y < ground + 4:
                return "minecraft:stonebrick"
            # battlements
            return "minecraft:stonebrick" if (local_x + local_z) % 2 == 0 else AIR
        if tower:
            if y < ground + 8:
                return "minecraft:stonebrick"
            if y < ground + 10:
                return "minecraft:stonebrick" if (local_x + local_z) % 2 == 0 else AIR
            return AIR
        if y == ground and local_x == 6 and local_z == 5:
            return "minecraft:water"
        if y == ground and local_x in (5, 7) and local_z == 3:
            return "minecraft:oak_planks"
        if y == ground and local_x == 2 and local_z == 7:
            return "minecraft:crafting_table"
        if y <= ground - 1 and y >= ground - 2 and (local_x + local_z) % 4 == 0:
            return "minecraft:planks"
        return AIR if y > ground else None

    # -- trees ---------------------------------------------------------

    def _tree_block(self, x, y, z, h):
        for (tx, tz, trunk, leaf) in self.trees():
            if abs(x - tx) > 2 or abs(z - tz) > 2:
                continue
            base = self.height(tx, tz)
            if base <= self.sea_level:
                continue
            if x == tx and z == tz and base < y <= base + trunk:
                return "minecraft:log"
            dy = y - (base + trunk)
            if -2 <= dy <= 1:
                dx = x - tx
                dz = z - tz
                radius = 2 if dy <= 0 else 1
                if dy == -2 and abs(dx) + abs(dz) > 2:
                    continue
                if abs(dx) <= radius and abs(dz) <= radius:
                    if abs(dx) == 2 and abs(dz) == 2 and (dx + dz) % 2 == 0 and dy >= 0:
                        continue
                    return "minecraft:leaves"
        return None

    def trees(self):
        """Fixed tree positions inside the generated region."""
        return (
            (-12, 30, 5, "oak"),
            (-4, 44, 6, "oak"),
            (9, 24, 5, "birch"),
            (22, 52, 6, "oak"),
            (-20, 60, 5, "spruce"),
            (14, 70, 6, "oak"),
            (-8, 78, 5, "birch"),
            (26, 40, 5, "oak"),
        )

    # -- light ---------------------------------------------------------

    def sky_light(self, x, y, z):
        """15 in the open, 0 under anything solid - enough for a preview bake."""
        h = self.height(x, z)
        if y <= h:
            return 0
        for (tx, tz, trunk, leaf) in self.trees():
            base = self.height(tx, tz)
            if abs(x - tx) <= 2 and abs(z - tz) <= 2 and base + trunk <= y <= base + trunk + 2:
                return 4
        kx, kz = self._keep_origin
        if kx <= x <= kx + 12 and kz <= z <= kz + 10:
            ground = self.height(kx + 6, kz + 5) + 1
            if ground <= y <= ground + 8:
                return 6
        return 15

    def block_light(self, x, y, z):
        # The pond, the keep's torch-free floor and lava-free world: no emitters,
        # except a single lantern-ish block in the keep.
        kx, kz = self._keep_origin
        ground = self.height(kx + 6, kz + 5) + 1
        if kx + 5 <= x <= kx + 7 and kz + 3 <= z <= kz + 5 and ground <= y <= ground + 2:
            return 12
        return 0

    def source(self, seen=None):
        """A :class:`FunctionBlockSource` over this scene."""
        return FunctionBlockSource(
            self.block_at,
            min_y=self.min_y,
            max_y=self.max_y,
            top_y=self.top_y,
            roof=NO_ROOF,
            sky_light_fn=self.sky_light,
            block_light_fn=self.block_light,
            seen_fn=seen,
        )


def _value_noise(x, z):
    """Cheap deterministic 2D value noise in roughly ``[-1, 1]``."""
    xi = math.floor(x)
    zi = math.floor(z)
    fx = x - xi
    fz = z - zi
    # smoothstep
    sx = fx * fx * (3 - 2 * fx)
    sz = fz * fz * (3 - 2 * fz)
    a = _hash2(xi, zi)
    b = _hash2(xi + 1, zi)
    c = _hash2(xi, zi + 1)
    d = _hash2(xi + 1, zi + 1)
    top = a + (b - a) * sx
    bottom = c + (d - c) * sx
    return (top + (bottom - top) * sz) * 2.0 - 1.0


def _hash2(x, z):
    h = (int(x) * 374761393 + int(z) * 668265263) & 0xFFFFFFFF
    h = (h ^ (h >> 13)) * 1274126177 & 0xFFFFFFFF
    h ^= h >> 16
    return (h & 0xFFFF) / 65535.0
