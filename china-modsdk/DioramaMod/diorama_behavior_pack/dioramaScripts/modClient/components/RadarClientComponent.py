# -*- coding: utf-8 -*-
"""Radar on the client: hold the blips the server sent, draw the near ones.

All classification and projection already happened server-side
(``modServer/components/RadarServerComponent``); this component only keeps the
latest list, exposes the blip nearest to a map point, and turns a blip into the
strings and sprite names the HUD needs.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from dioramaScripts.modCore import radar
from dioramaScripts.modCore.projection import Projection


class RadarClientComponent(object):
    """Keeps the newest blip list and answers HUD questions about it."""

    def __init__(self, client, limit=256):
        self.client = client
        self.limit = int(limit)
        self.blips = []
        self.viewer = {}
        self.pitch = 50.0
        self._last_update = 0.0

    def on_update(self, args):
        """``EV_RADAR`` from the server."""
        payload = args or {}
        raw = payload.get("blips") or []
        blips = []
        for data in raw[: self.limit]:
            try:
                blips.append(radar.Blip.from_dict(data))
            except (TypeError, ValueError):
                continue
        self.blips = blips
        self.viewer = payload.get("viewer") or {}
        try:
            self.pitch = float(payload.get("pitch", 50.0))
        except (TypeError, ValueError):
            self.pitch = 50.0
        self._last_update = payload.get("t", 0.0)
        return blips

    def projection(self):
        return Projection(self.pitch)

    def enabled(self):
        settings = self.client.settings.settings
        return bool(
            settings.radar_players
            or settings.radar_hostile
            or settings.radar_neutral
            or settings.radar_passive
        )

    def nearest(self, u, v, slack=None):
        """The blip nearest a map-space point, for a tap on the map."""
        if slack is None:
            slack = 3.0
        return radar.under(self.blips, u, v, slack)

    def describe(self, blip, lang="zh"):
        return radar.describe(blip, lang)

    def sprite_for(self, blip):
        """Which marker texture a blip should use."""
        if blip.kind == radar.KIND_PLAYER:
            return "textures/ui/diorama_marker_player"
        if blip.kind == radar.KIND_HOSTILE:
            return "textures/ui/diorama_marker_hostile"
        if blip.kind == radar.KIND_NEUTRAL:
            return "textures/ui/diorama_marker_neutral"
        return "textures/ui/diorama_marker_passive"

    def colour_for(self, blip):
        """ARGB int, for tinting a shared marker texture."""
        return radar.KIND_COLOURS.get(blip.kind, 0xFFFFFFFF)

    def counts(self):
        summary = {}
        for blip in self.blips:
            summary[blip.kind] = summary.get(blip.kind, 0) + 1
        return summary

    def stats(self):
        return {"blips": len(self.blips), "kinds": self.counts(), "pitch": self.pitch}
