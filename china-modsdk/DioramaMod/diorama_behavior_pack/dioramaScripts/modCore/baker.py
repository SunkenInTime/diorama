# -*- coding: utf-8 -*-
"""Ray traces one map tile through the block data.

A port of the Fabric mod's ``TileBaker.java`` onto the :class:`BlockSource`
interface. Every texture pixel fires one or more rays along the camera
direction; the camera faces due north, so a ray never changes ``x`` and the
walk is a 2D DDA over ``(y, z)``.

Lighting, in the order it is applied:

1. **albedo** - the block's top or south-face colour, with the biome tint
   mixed in by the block's ``tint_weight``.
2. **block variation** - a fixed per-block brightness nudge so a field of one
   block is not a flat sheet.
3. **ambient occlusion** - corners whose neighbours are solid darken.
4. **sky occlusion** - how much sky the spot can see, from ``sky_light`` when
   the source has it and from short rays when it does not.
5. **sun and shadow** - a second ray toward a low western sun; a solid block
   along it multiplies the direct term down to the ambient tint.
6. **water** - colour absorbed by depth, so shallows stay turquoise.
7. **grade** - saturation and exposure, in linear light, then sRGB.
8. **outline** - pixels just behind a much nearer surface are darkened, which
   is what makes silhouettes read.

The result is a :class:`TileImage`: packed ABGR pixels plus a coverage byte per
coverage cell so the HUD can place its fog edge.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import math

from . import mipmaps, palette
from .geometry import GEOMETRY
from .map_style import DEFAULT as DEFAULT_STYLE
from .projection import Projection
from .tile_key import TileKey

#: Rotated-grid sample offsets inside a pixel.
SAMPLES = ((0.375, 0.125), (0.875, 0.375), (0.125, 0.625), (0.625, 0.875))
#: One sample in the middle of the pixel: a quarter of the work, jagged edges.
FAST_SAMPLES = ((0.5, 0.5),)

#: Ambient occlusion levels, indexed by how many of the four neighbours are open
#: (0 = boxed in, 4 = fully open).
AO_LEVELS = (0.58, 0.72, 0.86, 0.95, 1.0)
#: How much nearer a neighbouring pixel must be, in blocks, to trigger an outline.
OUTLINE_DEPTH = 1.5
#: How far below the cut a cave slice looks.
CAVE_DEPTH = 48
#: How far sky occlusion looks overhead when the source has no sky light.
SKY_REACH = 12

SKY_RAYS = (
    (0.0, 1.0, 0.0),
    (0.5, 0.75, 0.0),
    (-0.5, 0.75, 0.0),
    (0.0, 0.75, 0.5),
    (0.0, 0.75, -0.5),
    (0.35, 0.5, 0.35),
    (-0.35, 0.5, 0.35),
    (0.35, 0.5, -0.35),
    (-0.35, 0.5, -0.35),
)


def abgr(red, green, blue, alpha=255):
    """Pack a linear-or-sRGB colour into the ABGR int the GPU texture expects."""
    return (
        ((int(alpha) & 0xFF) << 24)
        | ((int(blue) & 0xFF) << 16)
        | ((int(green) & 0xFF) << 8)
        | (int(red) & 0xFF)
    )


def unpack_abgr(value):
    """``(red, green, blue, alpha)`` from a packed ABGR int."""
    return (
        value & 0xFF,
        (value >> 8) & 0xFF,
        (value >> 16) & 0xFF,
        (value >> 24) & 0xFF,
    )


class TileImage(object):
    """Baked pixels for one tile.

    ``pixels`` are packed ABGR ints, ``coverage`` is one byte per coverage cell
    holding the share of known pixels (0..255) so the HUD knows where its fog
    goes, and ``size`` is the tile edge in pixels.
    """

    __slots__ = ("key", "pixels", "coverage", "size", "geometry", "generation", "_mips")

    def __init__(self, key, pixels, coverage, size, geometry=GEOMETRY, generation=0):
        self.key = key
        self.pixels = pixels
        self.coverage = coverage
        self.size = size
        self.geometry = geometry
        self.generation = generation
        self._mips = None

    def mips(self):
        if self._mips is None:
            self._mips = mipmaps.build(self.pixels, self.size)
        return self._mips

    def with_generation(self, generation):
        self.generation = generation
        return self

    def coverage_at(self, x, y):
        """Coverage 0..255 at a pixel position (nearest coverage cell)."""
        if not self.coverage:
            return 255
        cells = self.geometry.coverage_cells
        step = float(self.size) / cells
        cx = int(x / step) if step else 0
        cy = int(y / step) if step else 0
        if cx < 0:
            cx = 0
        elif cx >= cells:
            cx = cells - 1
        if cy < 0:
            cy = 0
        elif cy >= cells:
            cy = cells - 1
        return self.coverage[cy * cells + cx]

    def known_fraction(self):
        """Share of pixels the bake had data for, 0..1; used by the fog and tests."""
        if not self.coverage:
            return 1.0
        total = 0
        for value in self.coverage:
            total += value
        return total / float(len(self.coverage) * 255)

    def __repr__(self):
        return "TileImage(%r, size=%d, generation=%d)" % (self.key, self.size, self.generation)


class TraceResult(object):
    """What one ray found; a small mutable record to avoid tuple churn."""

    __slots__ = (
        "red",
        "green",
        "blue",
        "alpha",
        "depth",
        "hit_solid",
        "hit_top",
        "block",
        "x",
        "y",
        "z",
        "fx",
        "fz",
    )

    def __init__(self):
        self.red = 0.0
        self.green = 0.0
        self.blue = 0.0
        self.alpha = 255
        self.depth = 0.0
        self.hit_solid = False
        self.hit_top = False
        self.block = None
        self.x = 0
        self.y = 0
        self.z = 0
        self.fx = 0.0
        self.fz = 0.0


class Baker(object):
    """Traces one tile. One instance per bake; not thread safe."""

    def __init__(
        self,
        key,
        source,
        style=DEFAULT_STYLE,
        geometry=GEOMETRY,
        projection=None,
        fog_of_war=False,
        height_reference=None,
        height_range=64.0,
    ):
        if not isinstance(key, TileKey):
            raise TypeError("key must be a TileKey")
        self.key = key
        self.source = source
        self.style = style
        self.geometry = geometry
        self.projection = projection or Projection(getattr(style, "pitch_degrees", 50.0))
        self.cave = key.is_cave()
        self.roofless = (not self.cave) and getattr(source, "roof", None) not in (None, -2147483648)
        self.lit = self.cave or self.roofless
        self.fog_of_war = self.lit and bool(fog_of_war)
        self.height_reference = (
            float(height_reference)
            if height_reference is not None
            else (source.min_y + source.max_y) / 2.0
        )
        self.height_range = max(1.0, float(height_range))
        sun_x, sun_y, sun_z = style.sun_direction
        self.sun = (sun_x, sun_y, sun_z)
        self.shadow_distance = style.shadow_distance
        self.sin = self.projection.sin
        self.cos = self.projection.cos
        self.global_top = int(getattr(source, "top_y", source.max_y - 1))
        self.start_y = self._start_y()
        self.limit_y = self._limit_y(self.start_y)
        self.depth_origin = self.projection.depth(
            self.start_y, self.projection.z_at(key.min_v(geometry), self.start_y)
        )
        self._shadow_cache = {}
        self._sky_cache = {}
        self._miss = set()
        self._result = TraceResult()

    # -- setup helpers -------------------------------------------------

    def _start_y(self):
        max_y = int(self.source.max_y)
        if self.cave:
            return min(int(self.key.layer), max_y)
        if self.roofless:
            return min(int(getattr(self.source, "roof", max_y)), max_y)
        return self.local_top()

    def _limit_y(self, start_y):
        if self.cave:
            return max(int(self.source.min_y), start_y - CAVE_DEPTH)
        return int(self.source.min_y)

    def local_top(self, more_north=0):
        """One above the tallest block a ray of this tile can meet.

        Uses ``source.top_at(x, z)`` when the source can answer cheaply (the
        synthetic world can; the ModSDK adapter usually cannot and returns
        ``None``, in which case the global ``top_y`` is used instead - correct
        but it makes the rays start higher and walk through more empty air).
        """
        global_top = min(int(self.source.max_y), self.global_top)
        top_at = getattr(self.source, "top_at", None)
        if top_at is None:
            return global_top
        reach_x, reach_y, reach_z = self.style.shadow_reach
        min_u = self.key.min_u(self.geometry)
        min_v = self.key.min_v(self.geometry)
        units = self.key.units(self.geometry)
        x0 = int(math.floor(min_u)) - 1
        x1 = int(math.ceil(min_u + units)) + reach_x + 1
        z0 = int(math.floor(self.projection.z_at(min_v, self.source.min_y))) - 1 - more_north
        z1 = int(math.ceil(self.projection.z_at(min_v + units, global_top))) + reach_z + 1
        top = None
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                height = top_at(x, z)
                if height is None:
                    return global_top
                if top is None or height > top:
                    top = height
        if top is None:
            return global_top
        return min(global_top, top + 1)

    # -- block helpers -------------------------------------------------

    def _state(self, x, y, z):
        return self.source.state(x, y, z)

    def _kind(self, x, y, z):
        """Look up the block kind, or ``None`` when the chunk is unknown."""
        state = self.source.state(x, y, z)
        if state is None:
            return None, None, None
        if isinstance(state, tuple):
            name, aux = state[0], state[1]
        else:
            name, aux = state, 0
        look = palette.lookup(name, aux, self._miss)
        return look.kind, look, name

    def _solid_at(self, x, y, z):
        kind, _look, _name = self._kind(x, y, z)
        return kind in (palette.KIND_SOLID, palette.KIND_GLASS, palette.KIND_LAVA)

    # -- trace ---------------------------------------------------------

    def trace(self, u, v):
        """Walk one ray; returns a :class:`TraceResult` or ``None`` for no data."""
        sin = self.sin
        cos = self.cos
        result = self._result
        x = int(math.floor(u))
        fx = u - x
        y0 = float(self.start_y)
        z0 = (v + y0 * cos) / sin

        by = self.start_y - 1
        bz = int(math.floor(z0))
        t_max_y = (y0 - by) / sin
        t_max_z = (z0 - bz) / cos
        t_delta_y = 1.0 / sin
        t_delta_z = 1.0 / cos
        t = 0.0
        entered_top = True

        skipping_rock = self.lit
        over_r = over_g = over_b = 0.0
        through = 1.0
        water_id = None
        water_t = 0.0
        water_by = 0
        water_bz = 0

        limit_y = self.limit_y
        while by >= limit_y:
            state = self.source.state(x, by, bz)
            if state is None:
                return None
            if isinstance(state, tuple):
                name, aux = state[0], state[1]
            else:
                name, aux = state, 0
            look = palette.lookup(name, aux, self._miss)
            kind = look.kind

            if skipping_rock:
                if kind == palette.KIND_SOLID:
                    if t_max_y < t_max_z:
                        by -= 1
                        t = t_max_y
                        t_max_y += t_delta_y
                        entered_top = True
                    else:
                        bz -= 1
                        t = t_max_z
                        t_max_z += t_delta_z
                        entered_top = False
                    continue
                skipping_rock = False

            if kind == palette.KIND_SOLID or kind == palette.KIND_LAVA:
                if self.fog_of_war and not self._seen_face(x, by, bz, entered_top):
                    return None
                hit_y = y0 - t * sin
                hit_z = z0 - t * cos
                _set(
                    result,
                    kind == palette.KIND_SOLID,
                    entered_top,
                    name,
                    x,
                    by,
                    bz,
                    fx,
                    hit_z - bz,
                )
                colour = self._shade(look, x, by, bz, fx, hit_y, hit_z, entered_top)
                if water_id is not None:
                    # Shade the bottom first, then absorb it through the water
                    # column the ray crossed before reaching it.
                    colour = self._mix_water(
                        water_id, x, water_by, water_bz, water_t, t, colour
                    )
                if through < 1.0:
                    # Plants and glass on the way down tint the surface below.
                    light = _luminance(colour) / max(0.05, _luminance(self._albedo_rgb(look, x, bz, entered_top)))
                    colour = (
                        colour[0] * through + over_r * light,
                        colour[1] * through + over_g * light,
                        colour[2] * through + over_b * light,
                    )
                result.depth = self.projection.depth(hit_y, hit_z) - self.depth_origin
                result.red, result.green, result.blue = colour
                result.alpha = 255
                return result

            if kind == palette.KIND_WATER:
                if water_id is None:
                    water_id = look
                    water_t = t
                    water_by = by
                    water_bz = bz
            elif kind == palette.KIND_PLANT or kind == palette.KIND_GLASS:
                weight = 0.4 if kind == palette.KIND_PLANT else 0.25
                albedo = self._albedo_rgb(look, x, bz, True)
                over_r += through * weight * albedo[0]
                over_g += through * weight * albedo[1]
                over_b += through * weight * albedo[2]
                through *= 1.0 - weight

            if t_max_y < t_max_z:
                by -= 1
                t = t_max_y
                t_max_y += t_delta_y
                entered_top = True
            else:
                bz -= 1
                t = t_max_z
                t_max_z += t_delta_z
                entered_top = False

        # Ran out of world below the tile.
        if self.fog_of_war and (skipping_rock or water_id is None or not self._seen_water(x, water_by, water_bz)):
            return None
        _set(result, False, False, None, x, limit_y, bz, fx, 0.0)
        result.depth = self.projection.depth(y0 - t * sin, z0 - t * cos) - self.depth_origin
        if skipping_rock:
            # Solid rock all the way down the slice: draw the cut face.
            result.red, result.green, result.blue = (0.0065, 0.0068, 0.0085)
        elif water_id is not None:
            colour = self._mix_water(
                water_id, x, water_by, water_bz, water_t, water_t + 64.0, (0.0, 0.0, 0.0)
            )
            result.red, result.green, result.blue = colour
        else:
            result.red, result.green, result.blue = (0.01, 0.01, 0.015)
        result.alpha = 255
        return result

    def _seen_face(self, x, y, z, entered_top):
        if entered_top:
            return self.source.is_seen(x, y + 1, z)
        return self.source.is_seen(x, y, z + 1)

    def _seen_water(self, x, y, z):
        return self.source.is_seen(x, y, z)

    # -- shading -------------------------------------------------------

    def _albedo_rgb(self, look, x, z, top):
        """Linear-light block colour with the biome tint mixed in."""
        from .noise import SRGB_TO_LINEAR

        base = look.top if top else look.side
        weight = look.tint_weight
        if weight <= 0.0 or look.tint == palette.TINT_NONE:
            return (
                SRGB_TO_LINEAR[base[0]],
                SRGB_TO_LINEAR[base[1]],
                SRGB_TO_LINEAR[base[2]],
            )
        tint = self.source.biome_tint(x, z, look.tint) or palette.DEFAULT_TINTS.get(look.tint)
        tint = _rgb_tuple(tint)
        keep = 1.0 - weight
        return (
            SRGB_TO_LINEAR[base[0]] * keep + SRGB_TO_LINEAR[base[0]] * SRGB_TO_LINEAR[tint[0]] * weight,
            SRGB_TO_LINEAR[base[1]] * keep + SRGB_TO_LINEAR[base[1]] * SRGB_TO_LINEAR[tint[1]] * weight,
            SRGB_TO_LINEAR[base[2]] * keep + SRGB_TO_LINEAR[base[2]] * SRGB_TO_LINEAR[tint[2]] * weight,
        )

    def _shade(self, look, x, y, z, fx, hit_y, hit_z, top):
        """Full lighting for a hit block; returns a linear RGB triple."""
        albedo = list(self._albedo_rgb(look, x, z, top))
        variation = self.style.block_variation
        if variation > 0.0:
            from .noise import block_noise

            nudge = 1.0 + variation * block_noise(x, y, z)
            albedo[0] *= nudge
            albedo[1] *= nudge
            albedo[2] *= nudge

        # The open cell in front of the face the ray hit.
        front_y = y + 1 if top else y
        front_z = z if top else z + 1
        ao = self._ambient_occlusion(x, front_y, front_z, top)
        sky = self._sky_factor(x, front_y, front_z)
        sun = self._sun_factor(x, front_y, front_z, top)

        ambient = self.style.ambient
        direct = self.style.sun_strength * sun
        # Sky occlusion only eats the ambient term: the sun is a directional
        # light and should not care how much other sky the spot can see.
        sky_term = 1.0 - self.style.sky_occlusion * (1.0 - sky)
        ambient_term = ambient * sky_term * ao
        direct_term = direct * ao

        # Higher ground is a little brighter, so hills still read when zoomed out.
        if self.style.height_shade > 0.0:
            offset = (y - self.height_reference) / self.height_range
            offset = max(-1.0, min(1.0, offset))
            height_term = 1.0 + self.style.height_shade * offset
            ambient_term *= height_term
            direct_term *= height_term

        return (
            albedo[0] * (ambient_term * self.style.shadow_tint[0] + direct_term * self.style.sun_tint[0]),
            albedo[1] * (ambient_term * self.style.shadow_tint[1] + direct_term * self.style.sun_tint[1]),
            albedo[2] * (ambient_term * self.style.shadow_tint[2] + direct_term * self.style.sun_tint[2]),
        )

    def _ambient_occlusion(self, x, y, z, top):
        open_count = 0
        if top:
            probes = ((x - 1, y, z), (x + 1, y, z), (x, y, z - 1), (x, y, z + 1))
        else:
            probes = ((x - 1, y, z), (x + 1, y, z), (x, y, z + 1), (x, y + 1, z))
        for (px, py, pz) in probes:
            if not self._solid_at(px, py, pz):
                open_count += 1
        return AO_LEVELS[open_count]

    def _sky_factor(self, x, y, z):
        """0..1 sky visibility at a cell, cached for the bake."""
        key = (x, y, z)
        cached = self._sky_cache.get(key)
        if cached is not None:
            return cached
        light = self.source.sky_light(x, y, z)
        if light is not None:
            factor = max(0.0, min(1.0, light / 15.0))
        elif not self.style.sky_occlusion:
            factor = 1.0
        else:
            factor = self._sky_by_rays(x, y, z)
        self._sky_cache[key] = factor
        return factor

    def _sky_by_rays(self, x, y, z):
        open_rays = 0
        for (dx, dy, dz) in SKY_RAYS:
            blocked = False
            for step in range(1, SKY_REACH + 1):
                sx = int(math.floor(x + 0.5 + dx * step))
                sy = int(math.floor(y + 0.5 + dy * step))
                sz = int(math.floor(z + 0.5 + dz * step))
                if sy >= self.source.max_y:
                    break
                if self._solid_at(sx, sy, sz):
                    blocked = True
                    break
            if not blocked:
                open_rays += 1
        return open_rays / float(len(SKY_RAYS))

    def _sun_factor(self, x, y, z, top):
        """1 in sunlight, 0 in shadow; the direct term is scaled by it."""
        if self.shadow_distance <= 0.0 or self.style.sun_strength <= 0.0:
            return 1.0
        key = (x, y, z, top)
        cached = self._shadow_cache.get(key)
        if cached is not None:
            return cached
        sun_x, sun_y, sun_z = self.sun
        steps = int(self.shadow_distance)
        factor = 1.0
        for step in range(1, steps + 1):
            sx = int(math.floor(x + 0.5 + sun_x * step))
            sy = int(math.floor(y + 0.5 + sun_y * step))
            sz = int(math.floor(z + 0.5 + sun_z * step))
            if sy >= self.source.max_y:
                break
            kind, _look, _name = self._kind(sx, sy, sz)
            if kind is None:
                # No data toward the sun: assume lit, so an unloaded chunk does
                # not paint a hard shadow band over everything west of it.
                break
            if kind == palette.KIND_SOLID:
                factor = 0.0
                break
        self._shadow_cache[key] = factor
        return factor

    def _mix_water(self, water_look, x, by, bz, t_entry, t_hit, bottom):
        """Absorb the bottom colour through the water the ray crossed."""
        depth = max(0.0, t_hit - t_entry) * self.cos
        if depth <= 0.0:
            return bottom
        from .noise import SRGB_TO_LINEAR

        water = self._albedo_rgb(water_look, x, bz, True)
        # Clear water absorbs per channel, so shallow water looks turquoise and
        # deep water blue instead of an even wash of the water colour.
        if self.style.clear_water:
            absorb = (
                1.0 - math.exp(-depth * 0.42),
                1.0 - math.exp(-depth * 0.14),
                1.0 - math.exp(-depth * 0.06),
            )
        else:
            absorb = (0.62, 0.62, 0.62)
        return (
            bottom[0] * (1.0 - absorb[0]) + water[0] * 1.35 * absorb[0],
            bottom[1] * (1.0 - absorb[1]) + water[1] * 1.20 * absorb[1],
            bottom[2] * (1.0 - absorb[2]) + water[2] * 1.05 * absorb[2],
        )

    # -- bake ----------------------------------------------------------

    def bake(self, samples=None, fast=False, saved=None, progress=None, progress_every=32):
        """Bake the whole tile.

        ``saved`` is the last saved pixel buffer for this tile; wherever the
        fresh bake has less known data than the saved pixel, the saved one is
        kept. That shows places whose chunks are not loaded and stops a bake at
        the edge of loaded chunks from erasing what was saved.
        """
        size = self.key.pixels(self.geometry)
        offsets = FAST_SAMPLES if fast else (SAMPLES if samples is None else samples)
        pixels = [0] * (size * size)
        heights = [None] * (size * size)
        min_u = self.key.min_u(self.geometry)
        min_v = self.key.min_v(self.geometry)
        units_per_pixel = 1.0 / self.geometry.pixels_per_unit
        max_y = self.start_y
        for py in range(size):
            row = py * size
            if progress is not None and progress_every and py % progress_every == 0:
                progress(py, size)
            for px in range(size):
                red = green = blue = 0.0
                depth = 0.0
                known = 0
                for (ox, oy) in offsets:
                    u = min_u + (px + ox) * units_per_pixel
                    v = min_v + (py + oy) * units_per_pixel
                    result = self.trace(u, v)
                    if result is None:
                        continue
                    red += result.red
                    green += result.green
                    blue += result.blue
                    depth += result.depth
                    known += 1
                if known > 0:
                    red /= known
                    green /= known
                    blue /= known
                    alpha = known * 255 // len(offsets)
                    colour = self._grade(red, green, blue)
                    pixels[row + px] = abgr(colour[0], colour[1], colour[2], alpha)
                    heights[row + px] = depth / known
        if saved is not None:
            _merge_saved(pixels, saved, size)
        if self.style.outline > 0.0:
            _outline(pixels, heights, size, self.style.outline)
        return TileImage(
            self.key,
            pixels,
            self._coverage(pixels, size),
            size,
            self.geometry,
        )

    def _grade(self, red, green, blue):
        """Saturation and exposure in linear light, then back to sRGB."""
        from .noise import linear_to_srgb

        luminance = _luminance((red, green, blue))
        saturation = self.style.saturation
        exposure = self.style.exposure
        out = []
        for value in (red, green, blue):
            graded = (value + (value - luminance) * (saturation - 1.0)) * exposure
            out.append(linear_to_srgb(graded))
        return out

    def _coverage(self, pixels, size):
        """Share of known pixels per coverage cell, 0..255."""
        cells = self.geometry.coverage_cells
        coverage = bytearray(cells * cells)
        per_cell = size // cells
        if per_cell <= 0:
            for index in range(len(coverage)):
                coverage[index] = 255
            return coverage
        for cy in range(cells):
            for cx in range(cells):
                known = 0
                total = 0
                for y in range(cy * per_cell, (cy + 1) * per_cell):
                    row = y * size
                    for x in range(cx * per_cell, (cx + 1) * per_cell):
                        total += 1
                        if (pixels[row + x] >> 24) & 0xFF:
                            known += 1
                coverage[cy * cells + cx] = 0 if total == 0 else known * 255 // total
        return coverage


# --------------------------------------------------------------------------
# module-level helpers and the public entry points
# --------------------------------------------------------------------------


def _set(result, hit_solid, hit_top, name, x, y, z, fx, fz):
    result.hit_solid = hit_solid
    result.hit_top = hit_top
    result.block = name
    result.x = x
    result.y = y
    result.z = z
    result.fx = fx
    result.fz = fz


def _luminance(colour):
    return 0.2126 * colour[0] + 0.7152 * colour[1] + 0.0722 * colour[2]


def _rgb_tuple(value):
    if isinstance(value, tuple):
        return value
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF)


def _merge_saved(pixels, saved, size):
    """Keep the saved pixel wherever the fresh one is less known."""
    limit = min(len(pixels), len(saved))
    for index in range(limit):
        old = saved[index]
        if ((old >> 24) & 0xFF) > ((pixels[index] >> 24) & 0xFF):
            pixels[index] = old


def _outline(pixels, heights, size, strength):
    """Darken pixels that sit just behind a much nearer neighbour."""
    keep = 1.0 - strength
    for y in range(size):
        row = y * size
        for x in range(size):
            index = row + x
            pixel = pixels[index]
            if not pixel:
                continue
            here = heights[index]
            if here is None:
                continue
            if x > 0 and _nearer(heights[index - 1], here):
                pass
            elif x < size - 1 and _nearer(heights[index + 1], here):
                pass
            elif y > 0 and _nearer(heights[index - size], here):
                pass
            elif y < size - 1 and _nearer(heights[index + size], here):
                pass
            else:
                continue
            alpha = pixel & 0xFF000000
            red = int((pixel & 0xFF) * keep)
            green = int(((pixel >> 8) & 0xFF) * keep)
            blue = int(((pixel >> 16) & 0xFF) * keep)
            pixels[index] = alpha | (blue << 16) | (green << 8) | red


def _nearer(neighbour, here):
    if neighbour is None:
        return False
    return here - neighbour > OUTLINE_DEPTH


def bake_tile(
    key,
    source,
    style=DEFAULT_STYLE,
    geometry=GEOMETRY,
    projection=None,
    samples=None,
    fast=False,
    saved=None,
    fog_of_war=False,
    progress=None,
    height_reference=None,
    height_range=64.0,
):
    """Bake one tile; the module-level convenience wrapper around :class:`Baker`."""
    baker = Baker(
        key,
        source,
        style=style,
        geometry=geometry,
        projection=projection,
        fog_of_war=fog_of_war,
        height_reference=height_reference,
        height_range=height_range,
    )
    return baker.bake(samples=samples, fast=fast, saved=saved, progress=progress)
