# -*- coding: utf-8 -*-
"""Settings on the client: load, save, and tell the server what it needs.

The ModSDK's only persistent client storage is
``CreateConfig(localPlayerId).GetConfigData/SetConfigData``, so the whole
settings object is JSON under one key. The radar subset is mirrored to the
server (``EV_SYNC_SETTINGS``) because the server is what scans entities.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import json

from dioramaScripts.modCommon import eventConfig as ev
from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import warn
from dioramaScripts.modCore.settings import RANGES, SIZES, Settings


class SettingsClientComponent(object):
    """Owns the player's :class:`~modCore.settings.Settings`."""

    def __init__(self, client, backend):
        self.client = client
        self.backend = backend
        self.settings = self._load()
        self._sync_to_server()

    # -- persistence ---------------------------------------------------

    def _load(self):
        try:
            text = self.backend.read()
        except Exception as exc:  # noqa: BLE001
            warn("settings read failed: %r", exc)
            text = ""
        if not text:
            return Settings()
        try:
            data = json.loads(text)
        except ValueError:
            warn("settings file is not JSON; using defaults")
            return Settings()
        return Settings.from_dict(data)

    def save(self):
        try:
            self.backend.write(json.dumps(self.settings.to_dict(), ensure_ascii=False))
        except Exception as exc:  # noqa: BLE001
            warn("settings write failed: %r", exc)
        self._sync_to_server()

    # -- mutation -------------------------------------------------------

    def toggle(self, name):
        """Flip a boolean setting by name; returns the new value."""
        current = getattr(self.settings, name, None)
        if current is None:
            return None
        value = not bool(current)
        setattr(self.settings, name, value)
        self.settings.clamp()
        self.save()
        return value

    def cycle(self, name, values):
        """Step a numeric/enumerated setting through ``values``."""
        current = getattr(self.settings, name, None)
        if current is None or not values:
            return None
        try:
            index = list(values).index(current)
        except ValueError:
            index = -1
        value = values[(index + 1) % len(values)]
        setattr(self.settings, name, value)
        self.settings.clamp()
        self.save()
        return value

    def cycle_radar_range(self):
        return self.cycle("radar_range", RANGES)

    def cycle_size(self):
        return self.cycle("minimap_size", SIZES)

    def cycle_zoom(self):
        return self.cycle("minimap_zoom", list(range(6)))

    def reset(self):
        self.settings = Settings()
        self.save()
        return self.settings

    # -- server mirror --------------------------------------------------

    def _sync_to_server(self):
        self.client.notify_server(ev.EV_SYNC_SETTINGS, {"settings": self.settings.to_server_dict()})

    def on_request(self, args):
        """The server or a screen asked for the current settings."""
        player_id = (args or {}).get("playerId")
        if player_id:
            self.client.notify(player_id, ev.EV_SETTINGS, {"settings": self.settings.to_dict()})
        return self.settings.to_dict()

    # -- convenience ----------------------------------------------------

    def style(self):
        return self.settings.style()

    def zoom(self):
        return self.settings.zoom()

    def is_fast(self):
        return self.settings.is_fast()

    def describe(self):
        """A compact multi-line summary for the settings screen."""
        s = self.settings
        lines = [
            "minimap=%s size=%d corner=%s zoom=%.2f"
            % (s.minimap, s.minimap_size, s.minimap_corner, self.zoom()),
            "coords=%s compass=%s biome=%s"
            % (s.coordinates, s.compass, s.biome),
            "radar players=%s hostile=%s neutral=%s passive=%s range=%d"
            % (
                s.radar_players,
                s.radar_hostile,
                s.radar_neutral,
                s.radar_passive,
                s.radar_range,
            ),
            "waypoints map=%s world=%s death=%s"
            % (s.waypoints_on_map, s.waypoints_in_world, s.death_markers),
            "outlines=%s rounded_fog=%s depth=%s quality=%s grid=%s"
            % (
                s.outlines,
                s.rounded_fog,
                s.depth_shading,
                s.quality,
                s.chunk_grid,
            ),
            "pitch=%.0f\u00b0 fog_of_war=%s" % (s.pitch_degrees, s.always_fog_of_war),
        ]
        return "\n".join(lines)


#: The config key the settings live under.
SETTINGS_KEY = modConfig.SETTINGS_CONFIG_KEY
