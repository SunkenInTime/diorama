# -*- coding: utf-8 -*-
"""A tiny PNG writer, so the workbench needs no Pillow.

Only what the preview tool and the texture generator need: 8-bit RGB or RGBA,
no interlace, filter type 0 on every row. The output is a normal PNG that any
viewer or browser opens, which is what makes the bake output reviewable.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import struct
import zlib

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def _chunk(tag, payload):
    return (
        struct.pack(b">I", len(payload))
        + tag
        + payload
        + struct.pack(b">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
    )


def write_png(path, width, height, rows):
    """Write an 8-bit RGBA PNG.

    ``rows`` is an iterable of ``height`` rows, each row an iterable of
    ``(r, g, b, a)`` tuples or of packed RGBA ints (``0xRRGGBBAA``). Values are
    clamped, so the baker's float maths cannot produce a corrupt file.
    """
    if width <= 0 or height <= 0:
        raise ValueError("empty image: %dx%d" % (width, height))
    raw = bytearray()
    count = 0
    for row in rows:
        if count >= height:
            break
        raw.append(0)  # filter type 0 (None)
        for pixel in row:
            if isinstance(pixel, int):
                raw.append((pixel >> 24) & 0xFF)
                raw.append((pixel >> 16) & 0xFF)
                raw.append((pixel >> 8) & 0xFF)
                raw.append(pixel & 0xFF)
            else:
                r, g, b = pixel[0], pixel[1], pixel[2]
                a = pixel[3] if len(pixel) > 3 else 255
                raw.append(_clamp(r))
                raw.append(_clamp(g))
                raw.append(_clamp(b))
                raw.append(_clamp(a))
        count += 1
    if count != height:
        raise ValueError("expected %d rows, got %d" % (height, count))
    payload = (
        PNG_SIGNATURE
        + _chunk(b"IHDR", struct.pack(b">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + _chunk(b"IDAT", zlib.compress(bytes(raw), 6))
        + _chunk(b"IEND", b"")
    )
    handle = open(path, "wb")
    try:
        handle.write(payload)
    finally:
        handle.close()
    return path


def _clamp(value):
    value = int(value)
    if value < 0:
        return 0
    if value > 255:
        return 255
    return value


def write_indexed_square(path, size, colour_fn):
    """Small helper for the UI texture generator: one solid or patterned square."""
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            row.append(colour_fn(x, y, size))
        rows.append(row)
    return write_png(path, size, size, rows)


def abgr_rows(pixels, size, height=None):
    """Turn a baker pixel buffer (packed ABGR) into PNG-ordered RGBA rows.

    ``size`` is the width; ``height`` defaults to a square image, which is what
    a single tile is. A stitched atlas is still square today, but the parameter
    keeps the door open for an oblong one.
    """
    if height is None:
        height = size
    rows = []
    for y in range(height):
        base = y * size
        row = []
        for x in range(size):
            value = pixels[base + x]
            a = (value >> 24) & 0xFF
            b = (value >> 16) & 0xFF
            g = (value >> 8) & 0xFF
            r = value & 0xFF
            row.append((r, g, b, a))
        rows.append(row)
    return rows
