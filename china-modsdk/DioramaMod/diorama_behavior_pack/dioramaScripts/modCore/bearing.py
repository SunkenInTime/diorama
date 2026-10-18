# -*- coding: utf-8 -*-
"""Bearings, compass labels and coordinate formatting.

Shared by the HUD, the waypoint arrows and the radar descriptions. Minecraft
north is ``-Z``, so a bearing of 0 degrees is north and 90 is east.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import math

try:  # Python 2 / 3 text handling
    string_types = (str, unicode)  # noqa: F821 - only defined on Python 2
except NameError:  # pragma: no cover - Python 3
    string_types = (str,)

COMPASS_8 = ("N", "NE", "E", "SE", "S", "SW", "W", "NW")
ARROWS_8 = ("\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196")
#: The same eight points in Chinese, for the zh_CN UI.
COMPASS_8_ZH = ("\u5317", "\u4e1c\u5317", "\u4e1c", "\u4e1c\u5357", "\u5357", "\u897f\u5357", "\u897f", "\u897f\u5317")


def bearing_degrees(frm, to):
    """Bearing from one ``(x, z)`` point to another, 0 = north, 90 = east."""
    dx = to[0] - frm[0]
    dz = to[1] - frm[1]
    degrees = math.degrees(math.atan2(dx, -dz))
    return (degrees + 360.0) % 360.0


def bearing_label(degrees, lang="zh"):
    """Eight-point compass name for a bearing."""
    index = int(round(((degrees % 360.0) + 360.0) % 360.0 / 45.0)) % 8
    if lang == "zh":
        return COMPASS_8_ZH[index]
    return COMPASS_8[index]


def bearing_arrow(degrees):
    """A Unicode arrow for a bearing, for labels with no image behind them."""
    index = int(round(((degrees % 360.0) + 360.0) % 360.0 / 45.0)) % 8
    return ARROWS_8[index]


def distance3(a, b):
    dx = a[0] - b[0]
    dy = a[1] - b[1]
    dz = a[2] - b[2]
    return math.sqrt(dx * dx + dy * dy + dz * dz)


def distance2(a, b):
    dx = a[0] - b[0]
    dz = a[1] - b[1]
    return math.sqrt(dx * dx + dz * dz)


def format_coord(value):
    """Coordinates read as block corners, not floats."""
    return int(round(value))


def format_distance(meters):
    return "%dm" % int(round(meters))


def truncate(text, maximum):
    if not isinstance(text, string_types):
        text = "%s" % (text,)
    if len(text) <= maximum:
        return text
    return text[: max(0, maximum - 1)] + "\u2026"
