# -*- coding: utf-8 -*-
"""Server half of the radar: scan, classify, project, push to each client.

The client cannot be trusted with - and on the China Edition cannot cheaply
perform - an area entity query, so the scan happens here once per
``modConfig.RADAR_INTERVAL`` seconds per player. What crosses the wire is the
already-classified, already-projected blip list (see
``modCore.radar.Blip.to_dict``), so the client only has to draw.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import time

from dioramaScripts.modCommon import eventConfig as ev
from dioramaScripts.modCommon.log import error
from dioramaScripts.modCore import radar
from dioramaScripts.modCore.projection import Projection
from dioramaScripts.modCore.settings import ServerSettingsView


class RadarServerComponent(object):
    """Keeps per-player radar settings and pushes blips on a timer."""

    def __init__(self, server, interval=0.5):
        self.server = server
        self.interval = float(interval)
        self._settings = {}
        self._last_scan = 0.0
        self._query = None
        self._scans = 0
        self._errors = 0

    # -- events --------------------------------------------------------

    def on_settings(self, args):
        """A client sent the radar-related part of its settings."""
        player_id = args.get("playerId") or args.get("__playerId")
        if not player_id:
            return
        self._settings[player_id] = args.get("settings") or {}

    def on_player_left(self, player_id):
        self._settings.pop(player_id, None)

    def on_dimension_change(self, args):
        """Entity ids and coordinates are per dimension; rescan immediately."""
        self._settings.pop(args.get("playerId"), None)
        self._last_scan = 0.0

    # -- scan ----------------------------------------------------------

    def tick(self):
        now = time.time()
        if now - self._last_scan < self.interval:
            return
        self._last_scan = now
        if self._query is None:
            try:
                from dioramaScripts.modServer.engine.entity_query import EntityQuery

                self._query = EntityQuery(
                    self.server.api, self.server.level_id, self.server.dimension_id
                )
            except Exception as exc:  # noqa: BLE001
                error("radar query unavailable: %r", exc)
                self._query = False
                return
        if self._query is False:
            return
        try:
            self.scan_once()
        except Exception as exc:  # noqa: BLE001 - one bad scan must not stop later ones
            self._errors += 1
            error("radar scan failed: %r", exc)

    def scan_once(self):
        for player_id in self.server.player_ids():
            record = self._query.describe(player_id)
            if record is None:
                continue
            view = ServerSettingsView(self._settings.get(player_id))
            projection = Projection(view.pitch_degrees)
            radius = view.radar_range + 16
            nearby = self._query.collect(
                (record["x"], record["y"], record["z"]), radius, player_id
            )
            blips = radar.collect(
                nearby,
                record,
                view,
                projection,
                radar_range=view.radar_range,
                fullscreen=False,
                zoom=1.0,
            )
            self.server.notify(
                player_id,
                ev.EV_RADAR,
                {
                    "viewer": record,
                    "blips": [blip.to_dict() for blip in blips],
                    "pitch": projection.pitch_degrees,
                },
            )
            self._scans += 1

    def stats(self):
        return {"scans": self._scans, "errors": self._errors, "players": len(self._settings)}
