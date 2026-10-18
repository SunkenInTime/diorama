#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Bake Diorama tiles on the PC, with no game and no ModSDK.

This is the workbench half of the story the README tells: the runtime cannot
upload a texture a script computed, so the map a player sees on the full-screen
map is an *atlas baked here* and shipped in the resource pack. The same baker
runs at runtime for picks - this tool is how its output is reviewed.

Examples::

    python3 tools/preview_bake.py                      # one tile, docs/preview.png
    python3 tools/preview_bake.py --grid 2 --samples 1 # a 2x2 tile stitched image
    python3 tools/preview_bake.py --cave 48            # a cave slice at y=48
    python3 tools/preview_bake.py --overlay waypoints  # markers on top
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import argparse
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.join(
    HERE, os.pardir, "DioramaMod", "diorama_behavior_pack", "dioramaScripts"
)
if SCRIPTS not in sys.path:
    sys.path.insert(0, SCRIPTS)

from modCore import baker, block_source, geometry, png, projection, radar, tile_key  # noqa: E402
from modCore.map_style import PREVIEW  # noqa: E402

SAMPLES = ((0.5, 0.5),)
FOUR_SAMPLES = baker.SAMPLES


def _hex(colour):
    return ((colour >> 16) & 0xFF, (colour >> 8) & 0xFF, colour & 0xFF)


def bake_grid(world, geo, proj, style, tile_u0, tile_v0, tiles_x, tiles_y, samples, fog=False,
              progress=None):
    """Bake a block of tiles and stitch them into one ``(size, pixels)`` buffer."""
    source = world.source()
    per_tile = geo.tile_pixels
    width = per_tile * tiles_x
    height = per_tile * tiles_y
    out = [0] * (width * height)
    for ty in range(tiles_y):
        for tx in range(tiles_x):
            key = tile_key.TileKey(tile_key.TileKey.SURFACE, tile_u0 + tx, tile_v0 + ty, 0)
            if progress is not None:
                progress(key)
            image = baker.bake_tile(
                key,
                source,
                style=style,
                geometry=geo,
                projection=proj,
                samples=samples,
                fog_of_war=fog,
            )
            for y in range(per_tile):
                src = y * per_tile
                dst = (ty * per_tile + y) * width + tx * per_tile
                out[dst : dst + per_tile] = image.pixels[src : src + per_tile]
    return width, height, out


def draw_dot(pixels, size, x, y, colour, radius=2, outline=(16, 18, 22)):
    """A crude filled circle, so overlays are visible without an image library."""
    cx = int(round(x))
    cy = int(round(y))
    inner = _hex(colour)
    for dy in range(-radius - 1, radius + 2):
        for dx in range(-radius - 1, radius + 2):
            distance = (dx * dx + dy * dy) ** 0.5
            if distance > radius + 1:
                continue
            px = cx + dx
            py = cy + dy
            if px < 0 or py < 0 or px >= size[0] or py >= size[1]:
                continue
            index = py * size[0] + px
            if distance > radius:
                pixels[index] = baker.abgr(outline[0], outline[1], outline[2], 255)
            else:
                pixels[index] = baker.abgr(inner[0], inner[1], inner[2], 255)


def overlay_waypoints(pixels, size, world, geo, proj, origin_u, origin_v):
    """Markers from modCore.waypoint plus a radar blip, for a docs screenshot."""
    from modCore.waypoint import PALETTE, Waypoint

    waypoints = [
        Waypoint("Home", 6, 0, 34, color=PALETTE[0]),
        Waypoint("Keep", 10, 0, 35, color=PALETTE[3]),
        Waypoint("Mine", -14, 0, 62, color=PALETTE[5]),
    ]
    for waypoint in waypoints:
        y = world.height(waypoint.x, waypoint.z) + 1
        u = waypoint.x - origin_u
        v = proj.v(y, waypoint.z) - origin_v
        draw_dot(pixels, size, u * geo.pixels_per_unit, v * geo.pixels_per_unit, waypoint.color)

    viewer = {"id": "self", "type": "minecraft:player", "x": 0.0, "y": 0.0, "z": 30.0}
    viewer["y"] = float(world.height(0, 30) + 1)
    entities = [
        {"id": "z1", "type": "minecraft:zombie", "name": "Zombie", "x": 8.0,
         "y": viewer["y"], "z": 40.0},
        {"id": "c1", "type": "minecraft:cow", "name": "Cow", "x": -10.0,
         "y": viewer["y"], "z": 48.0},
        {"id": "p1", "type": "minecraft:player", "name": "Alex", "x": -18.0,
         "y": viewer["y"] + 6, "z": 36.0, "player": True},
    ]

    class _S(object):
        radar_range = 128
        radar_players = True
        radar_hostile = True
        radar_neutral = True
        radar_passive = True

    blips = radar.collect(entities, viewer, _S(), proj, radar_range=128)
    for blip in blips:
        u = blip.u - origin_u
        v = blip.v - origin_v
        draw_dot(
            pixels,
            size,
            u * geo.pixels_per_unit,
            v * geo.pixels_per_unit,
            radar.KIND_COLOURS.get(blip.kind, 0xFFFFFFFF),
            radius=2,
        )
    return waypoints, blips


def main(argv=None):
    parser = argparse.ArgumentParser(description="Bake a Diorama preview PNG")
    parser.add_argument("--out", default=os.path.join(HERE, os.pardir, "docs", "preview.png"))
    parser.add_argument("--grid", type=int, default=2, help="tiles per side (default 2)")
    parser.add_argument("--samples", type=int, default=1, choices=(1, 4),
                        help="rays per pixel (1 fast, 4 full quality)")
    parser.add_argument("--cave", type=int, default=None, help="bake a cave slice at this y")
    parser.add_argument("--pitch", type=float, default=50.0)
    parser.add_argument("--fog", action="store_true", help="apply fair-play fog of war")
    parser.add_argument("--overlay", choices=("none", "waypoints"), default="none")
    args = parser.parse_args(argv)

    world = block_source.SyntheticWorld()
    geo = geometry.PREVIEW_GEOMETRY
    proj = projection.Projection(args.pitch)
    style = PREVIEW
    samples = SAMPLES if args.samples == 1 else FOUR_SAMPLES

    tiles = max(1, args.grid)
    # Centre the view on x=0 by starting half a grid to the left.
    tile_u0 = -(tiles // 2)
    tile_v0 = 0
    origin_u = tile_u0 * geo.tile_units
    origin_v = tile_v0 * geo.tile_units

    started = time.time()

    def progress(key):
        print("baking %r ..." % (key,))

    if args.cave is None:
        width, height, pixels = bake_grid(
            world, geo, proj, style, tile_u0, tile_v0, tiles, tiles, samples, args.fog, progress
        )
    else:
        per_tile = geo.tile_pixels
        width = per_tile * tiles
        height = per_tile * tiles
        pixels = [0] * (width * height)
        source = world.source()
        for ty in range(tiles):
            for tx in range(tiles):
                key = tile_key.TileKey(args.cave, tile_u0 + tx, tile_v0 + ty, 0)
                progress(key)
                image = baker.bake_tile(
                    key, source, style=style, geometry=geo, projection=proj,
                    samples=samples, fog_of_war=args.fog,
                )
                for y in range(per_tile):
                    src = y * per_tile
                    dst = (ty * per_tile + y) * width + tx * per_tile
                    pixels[dst : dst + per_tile] = image.pixels[src : src + per_tile]

    size = (width, height)
    if args.overlay == "waypoints":
        overlay_waypoints(pixels, size, world, geo, proj, origin_u, origin_v)

    out = os.path.abspath(args.out)
    directory = os.path.dirname(out)
    if directory and not os.path.isdir(directory):
        os.makedirs(directory)
    png.write_png(out, width, height, png.abgr_rows(pixels, width))
    elapsed = time.time() - started
    print("baked %dx%d in %.1fs -> %s" % (width, height, elapsed, out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
