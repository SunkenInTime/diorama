# -*- coding: utf-8 -*-
"""Keeps the HUD screen's text and compass in step with the world.

Runs on the client tick, throttled to ``modConfig.HUD_INTERVAL``: reading the
player position, rotation and biome every frame would be wasted work for a
readout no human can read that fast.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import time

from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import warn
from dioramaScripts.modCore import bearing


class HudClientComponent(object):
    """Owns the HUD screen instance and refreshes it."""

    def __init__(self, client):
        self.client = client
        self.screen = None
        self._last_update = 0.0
        self._notice = ""
        self._notice_until = 0.0
        self._updates = 0

    # -- lifecycle -----------------------------------------------------

    def attach(self, screen):
        """Take ownership of a freshly created HUD screen."""
        self.screen = screen
        if screen is None:
            warn("HUD screen unavailable; the minimap will not draw")
            return None
        settings = self.client.settings.settings
        try:
            screen.remember_settings(settings)
            screen.apply_layout(settings)
            screen.install_handlers(
                on_open_map=self.client.open_map,
                on_add_waypoint=self.client.add_waypoint_here,
                on_toggle_minimap=self.client.toggle_minimap,
            )
        except Exception as exc:  # noqa: BLE001
            warn("HUD attach failed: %r", exc)
        return screen

    def detach(self):
        self.screen = None

    # -- refresh -------------------------------------------------------

    def tick(self):
        if self.screen is None:
            return
        now = time.time()
        if now - self._last_update < modConfig.HUD_INTERVAL:
            return
        self._last_update = now
        try:
            self._refresh()
        except Exception as exc:  # noqa: BLE001 - a bad frame must not stop the HUD
            warn("HUD refresh failed: %r", exc)

    def _refresh(self):
        screen = self.screen
        settings = self.client.settings.settings
        pos = self.client.player_position()
        degrees = self.client.player_bearing()
        if pos is not None:
            screen.set_readout(
                coords="%d, %d, %d" % (int(pos[0]), int(pos[1]), int(pos[2])),
                bearing="%s %s %.0f\u00b0"
                % (
                    bearing.bearing_arrow(degrees),
                    bearing.bearing_label(degrees),
                    degrees,
                ),
                biome=self.client.biome_name(),
            )
        if settings.compass:
            screen.set_compass(degrees)
        nearest = self.client.waypoints.nearest_to_player()
        screen.set_waypoint_line(
            self.client.waypoints.describe(nearest) if nearest is not None else ""
        )
        screen.set_tile_status(self.client.tile_status())
        if time.time() > self._notice_until:
            self._notice = ""
        screen.set_notice(self._notice)
        self._updates += 1

    # -- notices -------------------------------------------------------

    def notice(self, text, seconds=4.0):
        self._notice = text or ""
        self._notice_until = time.time() + float(seconds)
        if self.screen is not None:
            try:
                self.screen.set_notice(self._notice)
            except Exception:  # noqa: BLE001
                pass
        return self._notice

    def refresh_layout(self):
        if self.screen is not None:
            try:
                self.screen.remember_settings(self.client.settings.settings)
                self.screen.apply_layout(self.client.settings.settings)
            except Exception as exc:  # noqa: BLE001
                warn("HUD layout refresh failed: %r", exc)

    def stats(self):
        return {"updates": self._updates, "attached": self.screen is not None}
