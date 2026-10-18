# -*- coding: utf-8 -*-
"""Small deterministic helpers shared by the baker and the overlays."""

from __future__ import absolute_import, division, print_function, unicode_literals

import math

#: 32-bit mask, because Python ints do not wrap the way Java's do.
_MASK = 0xFFFFFFFF
#: Multiplier from the mod's ``blockNoise`` (Knuth / murmur-ish constant).
_MULT = 0x5BD1E995


def block_noise(x, y, z):
    """A fixed brightness nudge in ``[-1, 1]`` for one block position.

    Same shape as the Fabric mod's helper: a cheap spatial hash so a field of a
    single block type does not render as a flat sheet. Deterministic, so a
    re-bake of the same blocks produces the same picture.
    """
    h = (int(x) * 73856093) ^ (int(y) * 19349663) ^ (int(z) * 83492791)
    h &= _MASK
    h ^= h >> 13
    h = (h * _MULT) & _MASK
    h ^= h >> 15
    return ((h & 0xFFFF) / 32767.5) - 1.0


def smoothstep(edge0, edge1, value):
    if edge1 == edge0:
        return 0.0
    t = (value - edge0) / (edge1 - edge0)
    t = min(1.0, max(0.0, t))
    return t * t * (3 - 2 * t)


def srgb_to_linear_table():
    """256-entry sRGB -> linear lookup, as used by the baker."""
    table = []
    for i in range(256):
        v = i / 255.0
        if v <= 0.04045:
            table.append(v / 12.92)
        else:
            table.append(math.pow((v + 0.055) / 1.055, 2.4))
    return table


def linear_to_srgb_table(steps=4096):
    """``steps``-entry linear -> 8-bit sRGB lookup."""
    table = []
    for i in range(steps):
        v = i / float(steps - 1)
        if v <= 0.0031308:
            s = v * 12.92
        else:
            s = 1.055 * math.pow(v, 1.0 / 2.4) - 0.055
        table.append(int(round(min(1.0, max(0.0, s)) * 255)))
    return table


#: Built once at import; the baker indexes them per channel.
SRGB_TO_LINEAR = srgb_to_linear_table()
LINEAR_TO_SRGB = linear_to_srgb_table()


def linear_to_srgb(value):
    """Quantise a linear 0..1 channel through the shared lookup table."""
    if value <= 0.0:
        return 0
    if value >= 1.0:
        return 255
    return LINEAR_TO_SRGB[int(value * (len(LINEAR_TO_SRGB) - 1) + 0.5)]
