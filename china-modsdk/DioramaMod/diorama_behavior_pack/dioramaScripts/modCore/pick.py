# -*- coding: utf-8 -*-
"""What a map pixel shows, ported from the Fabric mod's ``MapPick.java``.

The map is an oblique top view, so a screen point alone does not say how high
the ground is. Picking walks the same camera ray the baker traces and returns
the block just above the first solid block or water surface it reaches. That is
what makes "right-click the map to add a waypoint" land on the surface the
player can actually stand on rather than at some arbitrary height.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import math

from . import palette
from .geometry import GEOMETRY, Geometry
from .projection import Projection
from .tile_key import TileKey


def map_point(x, y, z, projection):
    """The ``(u, v)`` map point of a block position at height ``y``."""
    return (x, projection.v(y, z))


def standing_spot(
    source,
    u,
    v,
    projection,
    layer=TileKey.SURFACE,
    fog_of_war=False,
    geometry=GEOMETRY,
    start_y=None,
    limit_y=None,
    cave_depth=48,
):
    """The position just above the first solid/water block the ray reaches.

    Returns ``(x, y, z)`` or ``None`` where the map has no data. ``projection``
    must be the same one the tile was baked with, or the pick lands somewhere
    else on the ground.
    """
    sin = projection.sin
    cos = projection.cos
    cave = layer != TileKey.SURFACE
    roofless = (not cave) and getattr(source, "roof", None) not in (None, -2147483648)

    if start_y is None:
        if cave:
            start_y = min(int(layer), int(source.max_y))
        elif roofless:
            start_y = min(int(getattr(source, "roof", source.max_y)), int(source.max_y))
        else:
            start_y = min(int(source.max_y), int(getattr(source, "top_y", source.max_y - 1)))
    if limit_y is None:
        if cave:
            limit_y = max(int(source.min_y), start_y - cave_depth)
        else:
            limit_y = int(source.min_y)

    x = int(math.floor(u))
    z0 = (v + start_y * cos) / sin
    by = start_y - 1
    bz = int(math.floor(z0))
    t_max_y = 1.0 / sin
    t_max_z = (z0 - bz) / cos
    skipping_rock = cave or roofless

    while by >= limit_y:
        state = source.state(x, by, bz)
        if state is None:
            return None
        if isinstance(state, tuple):
            name, aux = state[0], state[1]
        else:
            name, aux = state, 0
        look = palette.lookup(name, aux)
        if skipping_rock and look.kind != palette.KIND_SOLID:
            skipping_rock = False
        if not skipping_rock and look.kind in (
            palette.KIND_SOLID,
            palette.KIND_LAVA,
            palette.KIND_WATER,
        ):
            if (cave or roofless) and fog_of_war:
                if not (source.is_seen(x, by, bz) or source.is_seen(x, by + 1, bz)):
                    return None
            return (x, by + 1, bz)
        if t_max_y < t_max_z:
            by -= 1
            t_max_y += 1.0 / sin
        else:
            bz -= 1
            t_max_z += 1.0 / cos
    return None


def ground_height(source, x, z, top=None):
    """The highest non-air block at a column, or ``None`` when there is no data.

    The ModSDK adapter answers this with ``GetTopBlockHeight`` when the engine
    exposes it; this generic version walks down from ``top`` and is what the
    workbench and the unit tests use.
    """
    if top is None:
        top = int(getattr(source, "top_y", source.max_y - 1))
    for y in range(min(top, source.max_y - 1), source.min_y - 1, -1):
        state = source.state(x, y, z)
        if state is None:
            return None
        if isinstance(state, tuple):
            name, aux = state[0], state[1]
        else:
            name, aux = state, 0
        if palette.lookup(name, aux).kind != palette.KIND_AIR:
            return y
    return source.min_y


def tile_of(u, v, layer=TileKey.SURFACE, geometry=GEOMETRY, lod=0):
    """The tile that contains map point ``(u, v)``.

    For compatibility with the convenient ``tile_of(u, v, geometry)`` form,
    a Geometry passed as the third argument is treated as ``geometry``.
    """
    if isinstance(layer, Geometry):
        geometry, layer = layer, TileKey.SURFACE
    return TileKey(layer, 0, 0, lod).tile_for(u, v, geometry, lod)


def projection_for(style):
    return Projection(getattr(style, "pitch_degrees", 50.0))
