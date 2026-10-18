# -*- coding: utf-8 -*-
"""modCore - dependency-free Diorama map maths.

Nothing in this package imports ``mod.*`` (the NetEase ModSDK). That is on
purpose: every module here must be importable on a plain PC Python workbench,
under both CPython 2.7 (the interpreter the China Edition ships) and CPython 3,
so the projection, baker, waypoint store and radar maths can be unit tested
without launching the game.

See ``docs/ARCHITECTURE.md`` for how ``modCore`` talks to ``modServer`` /
``modClient`` through the adapters in ``modServer/engine`` and
``modClient/engine``.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

__all__ = [
    "bearing",
    "block_source",
    "baker",
    "geometry",
    "map_style",
    "mipmaps",
    "noise",
    "palette",
    "pick",
    "png",
    "projection",
    "radar",
    "tile_key",
    "waypoint",
    "waypoint_store",
]
