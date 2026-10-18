#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Bake the map atlas that ships inside the resource pack.

The China Edition ModSDK cannot upload a texture a script computed at run time,
so the full-screen map's background is a PNG baked here from the synthetic
workbench world (or, later, from exported real chunk data). ``diorama_map.json``
points at ``textures/ui/diorama_atlas``.

Usage::

    python3 tools/bake_atlas.py                      # 2x2 preview tiles -> 256px
    python3 tools/bake_atlas.py --grid 4 --samples 4 # 512px, full quality
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

from modCore import block_source, geometry, png  # noqa: E402
from preview_bake import bake_grid  # noqa: E402
from modCore.map_style import PREVIEW  # noqa: E402
from modCore.projection import Projection  # noqa: E402
FAST_SAMPLES = ((0.5, 0.5),)

RP = os.path.join(
    HERE, os.pardir, "DioramaMod", "diorama_resource_pack", "textures", "ui", "diorama_atlas.png"
)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Bake the shipped Diorama map atlas")
    parser.add_argument("--out", default=RP)
    parser.add_argument("--grid", type=int, default=2)
    parser.add_argument("--samples", type=int, default=1, choices=(1, 4))
    parser.add_argument("--pitch", type=float, default=50.0)
    args = parser.parse_args(argv)

    world = block_source.SyntheticWorld()
    geo = geometry.PREVIEW_GEOMETRY
    proj = Projection(args.pitch)
    tiles = max(1, args.grid)
    tile_u0 = -(tiles // 2)
    started = time.time()
    width, height, pixels = bake_grid(
        world,
        geo,
        proj,
        PREVIEW,
        tile_u0,
        0,
        tiles,
        tiles,
        FAST_SAMPLES if args.samples == 1 else None,
        False,
        lambda key: print("baking %r ..." % (key,)),
    )
    out = os.path.abspath(args.out)
    directory = os.path.dirname(out)
    if directory and not os.path.isdir(directory):
        os.makedirs(directory)
    png.write_png(out, width, height, png.abgr_rows(pixels, width, height))
    print("atlas %dx%d in %.1fs -> %s" % (width, height, time.time() - started, out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
