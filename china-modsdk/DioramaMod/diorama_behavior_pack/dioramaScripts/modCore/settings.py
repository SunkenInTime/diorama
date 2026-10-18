# -*- coding: utf-8 -*-
"""Player settings, ported from the Fabric mod's ``Settings.java``.

The Java mod writes ``.minecraft/diorama/config.json``. The China Edition has
no filesystem, so the client keeps this dict in ``CreateConfig`` local storage
(see ``modClient/engine/config_backend.py``) and syncs the radar-related subset
to the server, which is the only side that can see the entities.

Every field is clamped on load, because these values come back from a JSON blob
that a player can hand-edit and from UI controls that can send junk.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from .map_style import DEFAULT, FLAT
from .projection import MAX_PITCH_DEGREES, MIN_PITCH_DEGREES

#: Minimap zoom steps, in UI pixels per block.
ZOOMS = (0.75, 1.0, 1.5, 2.0, 3.0, 4.0)
#: Radar height ranges, in blocks.
RANGES = (16, 32, 64, 128)
#: Minimap sizes in UI pixels.
SIZES = (96, 128, 176)
#: Corner names, matching the ``anchor_from``/``anchor_to`` pairs in the HUD JSON.
CORNERS = ("top_right", "top_left", "bottom_right", "bottom_left")

QUALITY_FANCY = "fancy"
QUALITY_FAST = "fast"


class Settings(object):
    """Everything the settings screen can change."""

    __slots__ = (
        "minimap",
        "minimap_size",
        "minimap_corner",
        "minimap_zoom",
        "compass",
        "coordinates",
        "biome",
        "auto_caves",
        "radar_players",
        "radar_hostile",
        "radar_neutral",
        "radar_passive",
        "radar_range",
        "waypoints_on_map",
        "waypoints_in_world",
        "death_markers",
        "outlines",
        "rounded_fog",
        "depth_shading",
        "always_fog_of_war",
        "quality",
        "chunk_grid",
        "show_atlas",
        "pitch_degrees",
    )

    def __init__(self, **values):
        self.minimap = True
        self.minimap_size = SIZES[1]
        self.minimap_corner = "top_right"
        self.minimap_zoom = 3
        self.compass = True
        self.coordinates = True
        self.biome = True
        self.auto_caves = True

        self.radar_players = True
        self.radar_hostile = True
        self.radar_neutral = True
        self.radar_passive = True
        self.radar_range = 32

        self.waypoints_on_map = True
        self.waypoints_in_world = True
        self.death_markers = True

        self.outlines = True
        self.rounded_fog = True
        self.depth_shading = True
        self.always_fog_of_war = False
        self.quality = QUALITY_FANCY
        self.chunk_grid = False
        self.show_atlas = True
        self.pitch_degrees = 50.0

        for key, value in values.items():
            if key in self.__slots__:
                setattr(self, key, value)
        self.clamp()

    # -- derived -------------------------------------------------------

    def zoom(self):
        """UI pixels per block for the current minimap zoom step."""
        index = max(0, min(len(ZOOMS) - 1, self.minimap_zoom))
        return ZOOMS[index]

    def nearest_range(self):
        best, best_delta = RANGES[0], None
        for value in RANGES:
            delta = abs(value - self.radar_range)
            if best_delta is None or delta < best_delta:
                best, best_delta = value, delta
        return best

    def nearest_size(self):
        best, best_delta = SIZES[1], None
        for value in SIZES:
            delta = abs(value - self.minimap_size)
            if best_delta is None or delta < best_delta:
                best, best_delta = value, delta
        return best

    def style(self):
        """The map look these settings ask for."""
        base = DEFAULT if self.depth_shading else FLAT
        style = base.with_name("Settings").with_pitch(self.pitch_degrees)
        if not self.outlines:
            style = style.with_outline(0.0)
        style = style.with_rounded_fog(bool(self.rounded_fog))
        if self.quality == QUALITY_FAST:
            style = style.with_shadow_distance(16.0)
        return style

    def is_fast(self):
        return self.quality == QUALITY_FAST

    # -- validation ----------------------------------------------------

    def clamp(self):
        self.minimap = _as_bool(self.minimap, True)
        self.compass = _as_bool(self.compass, True)
        self.coordinates = _as_bool(self.coordinates, True)
        self.biome = _as_bool(self.biome, True)
        self.auto_caves = _as_bool(self.auto_caves, True)
        self.radar_players = _as_bool(self.radar_players, True)
        self.radar_hostile = _as_bool(self.radar_hostile, True)
        self.radar_neutral = _as_bool(self.radar_neutral, True)
        self.radar_passive = _as_bool(self.radar_passive, True)
        self.waypoints_on_map = _as_bool(self.waypoints_on_map, True)
        self.waypoints_in_world = _as_bool(self.waypoints_in_world, True)
        self.death_markers = _as_bool(self.death_markers, True)
        self.outlines = _as_bool(self.outlines, True)
        self.rounded_fog = _as_bool(self.rounded_fog, True)
        self.depth_shading = _as_bool(self.depth_shading, True)
        self.always_fog_of_war = _as_bool(self.always_fog_of_war, False)
        self.chunk_grid = _as_bool(self.chunk_grid, False)
        self.show_atlas = _as_bool(self.show_atlas, True)

        if self.minimap_corner not in CORNERS:
            self.minimap_corner = "top_right"
        try:
            self.minimap_zoom = max(0, min(len(ZOOMS) - 1, int(self.minimap_zoom)))
        except (TypeError, ValueError):
            self.minimap_zoom = 3
        if self.quality not in (QUALITY_FANCY, QUALITY_FAST):
            self.quality = QUALITY_FANCY
        try:
            self.radar_range = int(self.radar_range)
        except (TypeError, ValueError):
            self.radar_range = 32
        self.radar_range = self.nearest_range()
        try:
            self.minimap_size = int(self.minimap_size)
        except (TypeError, ValueError):
            self.minimap_size = SIZES[1]
        self.minimap_size = self.nearest_size()
        try:
            pitch = float(self.pitch_degrees)
        except (TypeError, ValueError):
            pitch = 50.0
        self.pitch_degrees = max(MIN_PITCH_DEGREES, min(MAX_PITCH_DEGREES, pitch))
        return self

    # -- serialisation -------------------------------------------------

    def to_dict(self):
        return dict((key, getattr(self, key)) for key in self.__slots__)

    def to_server_dict(self):
        """Only the fields the server's radar scan needs."""
        return {
            "radar_players": self.radar_players,
            "radar_hostile": self.radar_hostile,
            "radar_neutral": self.radar_neutral,
            "radar_passive": self.radar_passive,
            "radar_range": self.radar_range,
            "pitch_degrees": self.pitch_degrees,
            "always_fog_of_war": self.always_fog_of_war,
        }

    @classmethod
    def from_dict(cls, data):
        if not isinstance(data, dict):
            return cls()
        values = {}
        for key in cls.__slots__:
            if key in data:
                values[key] = data[key]
        return cls(**values)

    def __repr__(self):
        return "Settings(minimap=%r, radar_range=%r, quality=%r)" % (
            self.minimap,
            self.radar_range,
            self.quality,
        )


class ServerSettingsView(object):
    """A read-only view of the server-side subset, for :mod:`modCore.radar`.

    ``radar.collect`` only ever reads attributes, so this duck-types Settings
    without pulling the whole client config across the wire.
    """

    __slots__ = (
        "radar_players",
        "radar_hostile",
        "radar_neutral",
        "radar_passive",
        "radar_range",
        "pitch_degrees",
    )

    def __init__(self, data=None):
        data = data or {}
        self.radar_players = bool(data.get("radar_players", True) in (True, 1, "1", "true", "True"))
        self.radar_hostile = bool(data.get("radar_hostile", True) in (True, 1, "1", "true", "True"))
        self.radar_neutral = bool(data.get("radar_neutral", True) in (True, 1, "1", "true", "True"))
        self.radar_passive = bool(data.get("radar_passive", True) in (True, 1, "1", "true", "True"))
        self.radar_range = int(data.get("radar_range", 32) or 32)
        self.pitch_degrees = float(data.get("pitch_degrees", 50.0) or 50.0)


def _as_bool(value, fallback):
    """Booleans as they come back from JSON and from UI controls."""
    if isinstance(value, bool):
        return value
    if value in (1, "1", "true", "True", "yes"):
        return True
    if value in (0, "0", "false", "False", "no", None):
        return False
    return bool(value) if value is not None else fallback


def style_for(settings):
    """Accept a :class:`Settings`, a dict or ``None`` and return a MapStyle."""
    if isinstance(settings, Settings):
        return settings.style()
    if isinstance(settings, dict):
        return Settings.from_dict(settings).style()
    return DEFAULT
