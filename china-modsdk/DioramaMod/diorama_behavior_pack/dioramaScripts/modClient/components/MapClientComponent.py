# -*- coding: utf-8 -*-
"""Map tiles on the client: request them, keep them, and answer point queries.

Runtime tile baking is off by default on the server (see
``MapServerComponent.ALLOW_RUNTIME_BAKE``), so in the normal case this component
keeps an empty cache and the full-screen map draws the pre-baked atlas from the
resource pack. When baking *is* enabled, the tiles arrive as base64 PNG and are
kept here; :meth:`TileCache.export_png` writes one out on the PC workbench,
which is the same code path the preview tool uses.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import base64
import time

from dioramaScripts.modCommon import eventConfig as ev
from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import warn
from dioramaScripts.modCore import pick
from dioramaScripts.modCore.tile_key import TileKey


class TileCache(object):
    """Tiles received from the server, keyed by ``(layer, u, v, lod)``."""

    def __init__(self, limit=64):
        self.limit = int(limit)
        self.tiles = {}
        self.order = []

    def put(self, key, payload):
        self.tiles[key] = payload
        self.order.append(key)
        while len(self.order) > self.limit:
            oldest = self.order.pop(0)
            if oldest != key:
                self.tiles.pop(oldest, None)

    def get(self, key):
        return self.tiles.get(key)

    def has(self, key):
        return key in self.tiles

    def known(self, key):
        payload = self.tiles.get(key)
        if not payload:
            return 0.0
        return float(payload.get("known", 0.0))

    def export_png(self, key, path):
        """Write a received tile out as a PNG; used by the workbench and tests."""
        payload = self.tiles.get(key)
        if not payload or not payload.get("png"):
            return False
        data = base64.b64decode(payload["png"].encode("ascii"))
        handle = open(path, "wb")
        try:
            handle.write(data)
        finally:
            handle.close()
        return True

    def stats(self):
        return {"tiles": len(self.tiles), "limit": self.limit}


class MapClientComponent(object):
    """Asks the server for tiles and picks; owns the tile cache."""

    def __init__(self, client):
        self.client = client
        self.cache = TileCache()
        self.pending = {}
        self.last_pick = None
        self._last_request = 0.0
        self._refusals = 0

    # -- requests ------------------------------------------------------

    def request_tile(self, key, force=False):
        now = time.time()
        if not force and now - self._last_request < modConfig.TILE_INTERVAL:
            return False
        self._last_request = now
        cache_key = (key.layer, key.tile_u, key.tile_v, key.lod)
        self.pending[cache_key] = now
        self.client.notify_server(
            ev.EV_TILE_REQUEST,
            {
                "key": [key.layer, key.tile_u, key.tile_v, key.lod],
                "fog": bool(self.client.settings.settings.always_fog_of_war),
            },
        )
        return True

    def request_pick(self, u, v, layer=TileKey.SURFACE):
        self.client.notify_server(
            ev.EV_PICK_REQUEST, {"u": float(u), "v": float(v), "layer": int(layer)}
        )

    def on_tile(self, args):
        """``EV_TILE`` from the server: a tile, or a refusal with a reason."""
        payload = args or {}
        raw = payload.get("key")
        if not raw or len(raw) < 4:
            return None
        key = (int(raw[0]), int(raw[1]), int(raw[2]), int(raw[3]))
        self.pending.pop(key, None)
        if not payload.get("ok"):
            self._refusals += 1
            reason = payload.get("reason", "unknown")
            if self._refusals <= 2:
                warn("tile refused: %s (runtime baking is off by default)", reason)
            return None
        payload["received"] = time.time()
        self.cache.put(key, payload)
        return key

    def on_pick(self, args):
        """``EV_PICK_RESULT``: where a map point lands in the world."""
        payload = args or {}
        if payload.get("ok"):
            self.last_pick = (
                int(payload.get("x", 0)),
                int(payload.get("y", 0)),
                int(payload.get("z", 0)),
            )
            self.client.notice(
                "\u5730\u56fe\u5750\u6807 -> %d, %d, %d" % self.last_pick
            )
        else:
            self.client.notice("\u8be5\u4f4d\u7f6e\u65e0\u6570\u636e")
        return self.last_pick

    # -- local fallback -------------------------------------------------

    def local_standing_spot(self, u, v, layer=TileKey.SURFACE):
        """Answer a pick on the client, for when the server has not replied."""
        source = self.client.block_source()
        if source is None:
            return None
        return pick.standing_spot(
            source,
            u,
            v,
            self.client.projection(),
            layer=layer,
            geometry=self.client.geometry(),
        )

    def stats(self):
        return {
            "tiles": self.cache.stats(),
            "pending": len(self.pending),
            "refusals": self._refusals,
            "last_pick": self.last_pick,
        }
