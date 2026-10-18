# -*- coding: utf-8 -*-
"""Mip chain for a baked tile, ported from the Fabric mod's ``Mipmaps.java``.

Level 0 is the tile itself; each level above is half the size, made by
averaging 2x2 blocks of the level below. Alpha is averaged too, so a mip pixel
is as transparent as the coverage it represents - that is what makes the fog
edge fade instead of stair-stepping.
"""

from __future__ import absolute_import, division, print_function, unicode_literals


def _unpack(abgr):
    alpha = (abgr >> 24) & 0xFF
    blue = (abgr >> 16) & 0xFF
    green = (abgr >> 8) & 0xFF
    red = abgr & 0xFF
    return red, green, blue, alpha


def _pack(red, green, blue, alpha):
    return (
        ((int(alpha) & 0xFF) << 24)
        | ((int(blue) & 0xFF) << 16)
        | ((int(green) & 0xFF) << 8)
        | (int(red) & 0xFF)
    )


def half(pixels, size):
    """One halving step; ``size`` is the edge of ``pixels``."""
    out_size = size // 2
    if out_size <= 0:
        return [], 0
    out = [0] * (out_size * out_size)
    for y in range(out_size):
        row = y * out_size
        src0 = (y * 2) * size
        src1 = (y * 2 + 1) * size
        for x in range(out_size):
            x0 = x * 2
            r = g = b = a = 0
            for index in (src0 + x0, src0 + x0 + 1, src1 + x0, src1 + x0 + 1):
                pr, pg, pb, pa = _unpack(pixels[index])
                r += pr * pa
                g += pg * pa
                b += pb * pa
                a += pa
            if a <= 0:
                out[row + x] = 0
            else:
                out[row + x] = _pack(r // a, g // a, b // a, a // 4)
    return out, out_size


def build(pixels, size, levels=None):
    """The mip chain for a full tile.

    Returns a list whose first entry is ``pixels`` (level 0) and whose last is
    a single pixel. ``levels`` caps the chain, which the level-of-detail tiles
    use to avoid building mips they will never sample.
    """
    chain = [pixels]
    current = pixels
    current_size = size
    while current_size > 1:
        if levels is not None and len(chain) >= levels:
            break
        current, current_size = half(current, current_size)
        if current_size <= 0:
            break
        chain.append(current)
    return chain


def sample(chain, level, x, y):
    """Nearest sample of ``chain`` at ``level``, clamped to the level's size."""
    level = max(0, min(len(chain) - 1, int(level)))
    pixels = chain[level]
    size = 1 << (len(chain) - 1 - level)
    if x < 0:
        x = 0
    if y < 0:
        y = 0
    if x >= size:
        x = size - 1
    if y >= size:
        y = size - 1
    return pixels[y * size + x]
