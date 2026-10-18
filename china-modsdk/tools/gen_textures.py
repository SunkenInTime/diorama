#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate the Diorama UI textures and both pack icons.

Everything is drawn in code so the textures are reproducible and reviewable in
a diff, and so the workbench needs no image editor or third-party library:
:mod:`modCore.png` writes the files.

Usage::

    python3 tools/gen_textures.py [--out <resource pack>] [--force]

By default the icons are only written when missing; ``--force`` rewrites them.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MOD_ROOT = os.path.join(HERE, os.pardir, "DioramaMod")
SCRIPTS = os.path.join(MOD_ROOT, "diorama_behavior_pack", "dioramaScripts")
if SCRIPTS not in sys.path:
    sys.path.insert(0, SCRIPTS)

from modCore import png  # noqa: E402

RP = os.path.join(MOD_ROOT, "diorama_resource_pack")
BP = os.path.join(MOD_ROOT, "diorama_behavior_pack")


def _clamp(value):
    return 0 if value < 0 else (255 if value > 255 else int(value))


def _blend(base, over, alpha):
    return tuple(
        _clamp(base[i] * (1.0 - alpha) + over[i] * alpha) for i in range(3)
    ) + (255,)


# -- individual textures ----------------------------------------------------


def panel_pixel(x, y, size):
    edge = 1
    if x < edge or y < edge or x >= size - edge or y >= size - edge:
        return (86, 92, 104, 255)
    return (24, 27, 32, 235)


def button_pixel(x, y, size, hover=False, pressed=False):
    base = (72, 78, 90) if not hover else (96, 104, 118)
    if pressed:
        base = (52, 57, 66)
    edge = 1
    if x < edge or y < edge or x >= size - edge or y >= size - edge:
        return (18, 20, 24, 255)
    return (base[0], base[1], base[2], 255)


def marker(colour, size=12, ring=(16, 18, 22)):
    def pixel(x, y, _size):
        cx = cy = (size - 1) / 2.0
        dx = x - cx
        dy = y - cy
        distance = (dx * dx + dy * dy) ** 0.5
        if distance > size / 2.0:
            return (0, 0, 0, 0)
        if distance > size / 2.0 - 1.2:
            return (ring[0], ring[1], ring[2], 255)
        return (colour[0], colour[1], colour[2], 255)

    return pixel


def compass(colour, index, size=16):
    """A triangle pointing at one of the eight compass directions."""
    import math

    angle = math.radians(index * 45.0)
    direction = (math.sin(angle), -math.cos(angle))
    normal = (-direction[1], direction[0])

    def pixel(x, y, _size):
        cx = cy = (size - 1) / 2.0
        px = x - cx
        py = y - cy
        along = px * direction[0] + py * direction[1]
        across = abs(px * normal[0] + py * normal[1])
        if along < -1.0 or along > size / 2.0 - 1.0:
            return (0, 0, 0, 0)
        half = (size / 2.0 - 1.0 - along) * 0.55
        if across > half:
            return (0, 0, 0, 0)
        if across > half - 1.0 or along > size / 2.0 - 2.0:
            return (18, 20, 24, 255)
        return (colour[0], colour[1], colour[2], 255)

    return pixel


def badge(size=32):
    gold = (240, 190, 70)

    def pixel(x, y, _size):
        cx = cy = (size - 1) / 2.0
        # A diamond with a dark centre dot: an angular badge for an angular map.
        distance = abs(x - cx) + abs(y - cy)
        if distance > size / 2.0 - 1.0:
            return (0, 0, 0, 0)
        if distance > size / 2.0 - 3.0:
            return (24, 27, 32, 255)
        if distance < 2.0:
            return (24, 27, 32, 255)
        return (gold[0], gold[1], gold[2], 255)

    return pixel


def grid(size=16):
    def pixel(x, y, _size):
        if x == 0 or y == 0:
            return (255, 255, 255, 60)
        return (0, 0, 0, 0)

    return pixel


def solid(colour, alpha=255):
    def pixel(x, y, _size):
        return (colour[0], colour[1], colour[2], alpha)

    return pixel


def pack_icon(size=64):
    """A tiny isometric-looking scene, so the pack is recognisable in a list."""
    grass = (108, 158, 74)
    grass_side = (122, 92, 58)
    stone = (124, 124, 124)
    water = (63, 118, 228)
    sky = (26, 30, 38)

    def pixel(x, y, _size):
        if y < size * 0.34:
            return (sky[0], sky[1], sky[2], 255)
        if y > size * 0.78:
            return (water[0], water[1], water[2], 255)
        # A couple of block-sized plates for a diorama feel.
        if 12 <= x < 30 and 20 <= y < 34:
            return (grass[0], grass[1], grass[2], 255)
        if 12 <= x < 30 and 34 <= y < 42:
            return (grass_side[0], grass_side[1], grass_side[2], 255)
        if 34 <= x < 52 and 26 <= y < 40:
            return (stone[0], stone[1], stone[2], 255)
        if 34 <= x < 52 and 40 <= y < 46:
            return (90, 90, 90, 255)
        return (grass[0], grass[1], grass[2], 255)

    return pixel


# -- writing ----------------------------------------------------------------


def ensure(path, pixel, size, force, written):
    if os.path.exists(path) and not force:
        return False
    directory = os.path.dirname(path)
    if directory and not os.path.isdir(directory):
        os.makedirs(directory)
    png.write_indexed_square(path, size, pixel)
    written.append(path)
    return True


def main(argv=None):
    parser = argparse.ArgumentParser(description="Generate Diorama UI textures")
    parser.add_argument("--out", default=RP, help="resource pack directory")
    parser.add_argument("--force", action="store_true", help="overwrite existing files")
    args = parser.parse_args(argv)

    ui = os.path.join(args.out, "textures", "ui")
    written = []

    ensure(os.path.join(ui, "diorama_panel.png"), panel_pixel, 16, args.force, written)
    ensure(
        os.path.join(ui, "diorama_button.png"),
        lambda x, y, s: button_pixel(x, y, s),
        16,
        args.force,
        written,
    )
    ensure(
        os.path.join(ui, "diorama_button_hover.png"),
        lambda x, y, s: button_pixel(x, y, s, hover=True),
        16,
        args.force,
        written,
    )
    ensure(os.path.join(ui, "diorama_badge.png"), badge(), 32, args.force, written)
    ensure(os.path.join(ui, "diorama_grid.png"), grid(), 16, args.force, written)
    ensure(
        os.path.join(ui, "diorama_tile.png"),
        solid((255, 255, 255), 255),
        4,
        args.force,
        written,
    )

    ensure(
        os.path.join(ui, "diorama_marker_waypoint.png"),
        marker((235, 235, 240)),
        12,
        args.force,
        written,
    )
    ensure(
        os.path.join(ui, "diorama_marker_death.png"),
        marker((150, 152, 158)),
        12,
        args.force,
        written,
    )
    ensure(
        os.path.join(ui, "diorama_marker_player.png"),
        marker((255, 255, 255)),
        12,
        args.force,
        written,
    )
    ensure(
        os.path.join(ui, "diorama_marker_hostile.png"),
        marker((239, 75, 75)),
        12,
        args.force,
        written,
    )
    ensure(
        os.path.join(ui, "diorama_marker_neutral.png"),
        marker((242, 193, 78)),
        12,
        args.force,
        written,
    )
    ensure(
        os.path.join(ui, "diorama_marker_passive.png"),
        marker((98, 217, 107)),
        12,
        args.force,
        written,
    )

    directions = ("n", "ne", "e", "se", "s", "sw", "w", "nw")
    for index, name in enumerate(directions):
        ensure(
            os.path.join(ui, "diorama_compass_%s.png" % name),
            compass((240, 190, 70), index),
            16,
            args.force,
            written,
        )

    # A transparent atlas placeholder: tools/bake_atlas.py fills it in, but the
    # UI JSON must never point at a texture that does not exist.
    ensure(
        os.path.join(ui, "diorama_atlas.png"),
        solid((0, 0, 0), 0),
        16,
        args.force,
        written,
    )

    for pack in (args.out, BP):
        ensure(os.path.join(pack, "pack_icon.png"), pack_icon(), 64, args.force, written)

    for path in written:
        print("wrote %s" % (path,))
    if not written:
        print("nothing to do (use --force to rewrite)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
