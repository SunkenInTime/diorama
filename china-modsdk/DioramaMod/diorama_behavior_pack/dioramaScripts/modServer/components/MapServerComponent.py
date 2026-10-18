# -*- coding: utf-8 -*-
"""Map service: "what block is under this map pixel" and tile delivery.

Two jobs, deliberately separated by cost:

``EV_PICK_REQUEST``
    Walks one camera ray through the block component and answers with the
    standing position. A few hundred block reads; cheap enough to run inline
    from an event, and it is what makes "tap the map to place a waypoint" land
    on the ground rather than at an arbitrary height.

``EV_TILE_REQUEST``
    Bakes a tile. A full tile is 128x128 pixels in game geometry, and every
    pixel is a ray march over the block component: in pure Python that is
    seconds of work, and the ModSDK runs Python on the game thread. It is
    therefore **off by default** and answers with a refusal, so the map screen
    falls back to the pre-baked atlas shipped in the resource pack (built by
    ``tools/bake_atlas.py`` on the PC workbench). Turn it on
    (``ALLOW_RUNTIME_BAKE``, or ``server_api.get_level_id`` testing) only when
    you are deliberately measuring the cost on a quiet single-player world.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import base64
import time

from dioramaScripts.modCommon import eventConfig as ev
from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import info, warn
from dioramaScripts.modCore import pick
from dioramaScripts.modCore.baker import bake_tile
from dioramaScripts.modCore.geometry import GEOMETRY, Geometry
from dioramaScripts.modCore.projection import Projection
from dioramaScripts.modCore.settings import ServerSettingsView, style_for
from dioramaScripts.modCore.tile_key import TileKey


def _geometry_for(lod):
    """A runtime tile geometry that stays affordable in pure Python."""
    units = 32 * (1 << max(0, int(lod)))
    return Geometry(units, 1, 16, "runtime-lod%d" % (lod,))


class MapServerComponent(object):
    """Serves picks now, tiles when explicitly allowed."""

    #: Runtime tile baking is the one thing that can stall a tick; keep it off.
    ALLOW_RUNTIME_BAKE = False
    #: Tiles kept in memory per dimension before the oldest is dropped.
    TILE_CACHE_LIMIT = 64

    def __init__(self, server):
        self.server = server
        self._sources = {}
        self._tiles = {}
        self._requests = []
        self._settings = {}
        self._picks = 0
        self._bakes = 0
        self._refusals = 0
        self._last_work = 0.0

    # -- settings ------------------------------------------------------

    @staticmethod
    def _sender_id(args):
        args = args or {}
        return (args.get("__playerId") or args.get("playerId") or
                args.get("__id") or args.get("entityId") or args.get("id"))

    def on_settings(self, args):
        player_id = self._sender_id(args)
        if player_id:
            self._settings[player_id] = args.get("settings") or {}

    def _view_for(self, player_id):
        return ServerSettingsView(self._settings.get(player_id))

    def _dimension_for(self, player_id):
        resolver = getattr(self.server, "dimension_of", None)
        if player_id and callable(resolver):
            try:
                return int(resolver(player_id))
            except Exception:
                pass
        return self.server.dimension_id

    def _source_for(self, dimension_id):
        source = self._sources.get(dimension_id)
        if source is None:
            from dioramaScripts.modServer.engine.block_source import EngineBlockSource

            source = EngineBlockSource(
                self.server.level_id,
                dimension_id,
                api=self.server.api,
                min_y=self.server.min_y,
                max_y=self.server.max_y,
                roof=self.server.roof_for(dimension_id),
                top_y=(self.server.bounds_for(dimension_id)[1] - 1
                       if callable(getattr(self.server, "bounds_for", None))
                       else self.server.top_y),
            )
            self._sources[dimension_id] = source
        return source

    # -- picks ---------------------------------------------------------

    def on_pick_request(self, args):
        player_id = self._sender_id(args)
        try:
            u = float(args.get("u", 0.0))
            v = float(args.get("v", 0.0))
        except (TypeError, ValueError):
            return
        layer = args.get("layer", TileKey.SURFACE)
        if layer is None:
            layer = TileKey.SURFACE
        layer = int(layer)
        view = self._view_for(player_id)
        projection = Projection(view.pitch_degrees)
        dimension_id = self._dimension_for(player_id)
        source = self._source_for(dimension_id)
        spot = pick.standing_spot(
            source,
            u,
            v,
            projection,
            layer=layer,
            fog_of_war=bool(self._settings.get(player_id, {}).get("always_fog_of_war")),
            geometry=GEOMETRY,
            start_y=self.server.pick_start_y(layer),
        )
        self._picks += 1
        payload = {"ok": spot is not None, "u": u, "v": v, "layer": layer}
        if spot is not None:
            payload["x"], payload["y"], payload["z"] = spot
        if player_id:
            self.server.notify(player_id, ev.EV_PICK_RESULT, payload)
        return payload

    # -- tiles ---------------------------------------------------------

    def on_tile_request(self, args):
        player_id = self._sender_id(args)
        key = self._key_from_args(args)
        if key is None:
            return
        if not self.ALLOW_RUNTIME_BAKE:
            self._refusals += 1
            self.server.notify(
                player_id,
                ev.EV_TILE,
                {
                    "ok": False,
                    "reason": "runtime_bake_disabled",
                    "key": [key.layer, key.tile_u, key.tile_v, key.lod],
                },
            )
            return
        self._requests.append((player_id, key))
        info("tile queued: %r (%d waiting)", key, len(self._requests))

    def _key_from_args(self, args):
        raw = args.get("key")
        if isinstance(raw, (list, tuple)) and len(raw) == 4:
            try:
                return TileKey(int(raw[0]), int(raw[1]), int(raw[2]), int(raw[3]))
            except (TypeError, ValueError):
                return None
        try:
            return TileKey(
                int(args.get("layer", TileKey.SURFACE)),
                int(args["tile_u"]),
                int(args["tile_v"]),
                int(args.get("lod", 0)),
            )
        except (KeyError, TypeError, ValueError):
            return None

    def tick(self):
        """Process at most one queued tile per tick, and only with a budget."""
        if not self._requests:
            return
        now = time.time()
        if now - self._last_work < modConfig.BAKE_BUDGET_SECONDS:
            return
        self._last_work = now
        player_id, key = self._requests.pop(0)
        self.bake_and_send(player_id, key)

    def bake_and_send(self, player_id, key):
        dimension_id = self._dimension_for(player_id)
        cache_key = (dimension_id, key.cache_key())
        cached = self._tiles.get(cache_key)
        if cached is not None:
            if player_id:
                self.server.notify(player_id, ev.EV_TILE, cached)
            return cached
        view = self._view_for(player_id)
        geometry = _geometry_for(key.lod)
        started = time.time()
        try:
            image = bake_tile(
                key,
                self._source_for(dimension_id),
                style=style_for({"pitch_degrees": view.pitch_degrees}),
                geometry=geometry,
                projection=Projection(view.pitch_degrees),
                fast=True,
                fog_of_war=bool(self._settings.get(player_id, {}).get("always_fog_of_war")),
            )
        except Exception as exc:  # noqa: BLE001
            warn("tile bake failed: %r", exc)
            return None
        payload = {
            "ok": True,
            "key": [key.layer, key.tile_u, key.tile_v, key.lod],
            "size": image.size,
            "units": geometry.tile_units,
            "known": round(image.known_fraction(), 4),
            "png": base64.b64encode(tile_png_bytes(image)).decode("ascii"),
            "seconds": round(time.time() - started, 3),
        }
        if len(self._tiles) >= self.TILE_CACHE_LIMIT:
            self._tiles.clear()
        self._tiles[cache_key] = payload
        self._bakes += 1
        if player_id:
            self.server.notify(player_id, ev.EV_TILE, payload)
        return payload

    def stats(self):
        return {
            "picks": self._picks,
            "bakes": self._bakes,
            "refusals": self._refusals,
            "queued": len(self._requests),
            "sources": len(self._sources),
        }


def _png_bytes(image):
    """Render a TileImage to PNG bytes in memory (no filesystem involved).

    The ModSDK exposes no writable filesystem, so a runtime tile can only be
    sent somewhere as bytes. The encoding is the same 8-bit RGBA PNG that
    ``modCore.png`` writes for the workbench.
    """
    import io
    import struct
    import zlib

    from dioramaScripts.modCore.png import PNG_SIGNATURE, abgr_rows

    size = image.size
    rows = abgr_rows(image.pixels, size)
    raw = bytearray()
    for row in rows:
        raw.append(0)  # filter type 0 (None)
        for (red, green, blue, alpha) in row:
            raw.append(red & 0xFF)
            raw.append(green & 0xFF)
            raw.append(blue & 0xFF)
            raw.append(alpha & 0xFF)

    def chunk(tag, payload):
        return (
            struct.pack(b">I", len(payload))
            + tag
            + payload
            + struct.pack(b">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
        )

    out = io.BytesIO()
    out.write(PNG_SIGNATURE)
    out.write(chunk(b"IHDR", struct.pack(b">IIBBBBB", size, size, 8, 6, 0, 0, 0)))
    out.write(chunk(b"IDAT", zlib.compress(bytes(raw), 6)))
    out.write(chunk(b"IEND", b""))
    return out.getvalue()


def tile_png_bytes(image):
    """Public alias, also used by the workbench tools and the tests."""
    return _png_bytes(image)
