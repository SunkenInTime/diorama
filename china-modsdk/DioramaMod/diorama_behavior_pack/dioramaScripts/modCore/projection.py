# -*- coding: utf-8 -*-
"""Oblique camera maths.

A direct port of the Fabric mod's ``Projection.java``. The camera looks north
and down by ``pitch`` from the horizon, so a map pixel shows block tops and
their south faces. Map space is measured in blocks:

    u = x                        east is right
    v = z * sin - y * cos        south is down; higher blocks move up the screen

A point on map row ``v`` and height ``y`` lies at world
``z = (v + y * cos) / sin``, which is what :meth:`Projection.z_at` returns.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import math

MIN_PITCH_DEGREES = 25.0
MAX_PITCH_DEGREES = 70.0
DEFAULT_PITCH_DEGREES = 50.0


def clamp(value, low, high):
    """Plain clamp; kept here so the package has no cross imports for this."""
    if value < low:
        return low
    if value > high:
        return high
    return value


def normalize_pitch(degrees):
    """A pitch the camera can actually use.

    Non-numeric, NaN and infinite input falls back to the default instead of
    raising: these values come from UI controls and hand-edited config files.
    """
    try:
        value = float(degrees)
    except (TypeError, ValueError):
        return DEFAULT_PITCH_DEGREES
    if math.isnan(value) or math.isinf(value):
        return DEFAULT_PITCH_DEGREES
    return clamp(value, MIN_PITCH_DEGREES, MAX_PITCH_DEGREES)


class Projection(object):
    """An immutable-ish camera angle; build a new one when the pitch changes.

    Instances are cheap (three attributes); the baker takes one as an argument
    rather than reading a global, which keeps bakes deterministic when the
    player changes the pitch mid-bake.
    """

    __slots__ = ("pitch_degrees", "sin", "cos")

    def __init__(self, pitch_degrees=DEFAULT_PITCH_DEGREES):
        pitch = normalize_pitch(pitch_degrees)
        radians = math.radians(pitch)
        self.pitch_degrees = pitch
        self.sin = math.sin(radians)
        self.cos = math.cos(radians)

    def v(self, y, z):
        """Screen row for a block at height ``y`` and world ``z``."""
        return z * self.sin - y * self.cos

    def z_at(self, v, y):
        """World ``z`` where the ray through map row ``v`` crosses height ``y``."""
        return (v + y * self.cos) / self.sin

    def depth(self, y, z):
        """Camera depth along the view direction; bigger is farther away."""
        return -(self.sin * y + self.cos * z)

    def refresh(self, pitch_degrees):
        """Re-point this instance at a new angle (used by the settings screen)."""
        pitch = normalize_pitch(pitch_degrees)
        radians = math.radians(pitch)
        self.pitch_degrees = pitch
        self.sin = math.sin(radians)
        self.cos = math.cos(radians)
        return self

    def copy(self):
        other = Projection.__new__(Projection)
        other.pitch_degrees = self.pitch_degrees
        other.sin = self.sin
        other.cos = self.cos
        return other

    def __repr__(self):
        return "Projection(pitch=%r, sin=%r, cos=%r)" % (
            self.pitch_degrees,
            self.sin,
            self.cos,
        )


def make(pitch_degrees=DEFAULT_PITCH_DEGREES):
    """Convenience factory, mirroring the Bedrock port's ``makeProjection``."""
    return Projection(pitch_degrees)
