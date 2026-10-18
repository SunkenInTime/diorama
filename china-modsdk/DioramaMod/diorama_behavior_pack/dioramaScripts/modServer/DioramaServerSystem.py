# -*- coding: utf-8 -*-
"""``DioramaServerSystem`` - the server-side system registered from ``modMain``.

Responsibilities, in order of importance:

1. own the two things only the server can do - the entity scan for the radar,
   and block reads for map picks;
2. relay what the client cannot send itself - shared waypoints and chat lines;
3. keep a tick loop that is cheap when nothing needs doing.

Everything here is wrapped so that a single failing callback logs and returns
rather than propagating into the engine, because the ModSDK has no debugger and
an exception inside a tick is very hard to attribute after the fact.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import time

import mod.server.extraServerApi as serverApi

from dioramaScripts.modCommon import eventConfig as ev
from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import error, info, safe, warn
from dioramaScripts.modCore.block_source import NO_ROOF
from dioramaScripts.modCore.tile_key import TileKey
from dioramaScripts.modServer.components.MapServerComponent import MapServerComponent
from dioramaScripts.modServer.components.RadarServerComponent import RadarServerComponent
from dioramaScripts.modServer.components.WaypointServerComponent import WaypointServerComponent

ServerSystem = serverApi.GetServerSystemCls()

#: World height by dimension id, as the China Edition reports it. The Nether
#: has a bedrock roof; ``roof_for`` returns it so the map starts under it.
DIMENSION_BOUNDS = {
    0: (-64, 320, NO_ROOF),
    1: (0, 128, 127),
    2: (0, 256, NO_ROOF),
}
DEFAULT_BOUNDS = (0, 256, NO_ROOF)


class DioramaServerSystem(ServerSystem):
    """Server entry point; delegates to the three components."""

    def __init__(self, namespace, systemName):
        ServerSystem.__init__(self, namespace, systemName)
        self.api = serverApi
        self.level_id = serverApi.GetLevelId()
        self.min_y, self.max_y, self._roof = DEFAULT_BOUNDS
        self.top_y = self.max_y - 1
        self._dimension_id = 0
        self._timer = None
        self._last_tick = 0.0
        self._tick_count = 0
        self.radar = RadarServerComponent(self, modConfig.RADAR_INTERVAL)
        self.waypoints = WaypointServerComponent(self)
        self.map = MapServerComponent(self)
        self._listen_engine()
        self._listen_client()
        self._start_timer()
        info("DioramaServerSystem ready (levelId=%s)", self.level_id)

    # -- listeners -----------------------------------------------------

    def _listen_engine(self):
        engine_ns = serverApi.GetEngineNamespace()
        engine_sys = serverApi.GetEngineSystemName()
        pairs = (
            (ev.E_SERVER_CHAT, self.on_chat),
            (ev.E_ADD_SERVER_PLAYER, self.on_player_joined),
            (ev.E_PLAYER_DIE, self.on_player_died),
            (ev.E_DIMENSION_CHANGE, self.on_dimension_change),
            (ev.E_SCRIPT_TICK_SERVER, self.on_script_tick),
        )
        for name, handler in pairs:
            try:
                self.ListenForEvent(engine_ns, engine_sys, name, self, handler)
            except Exception as exc:  # noqa: BLE001 - optional events must not be fatal
                warn("could not listen for %s: %r", name, exc)

    def _listen_client(self):
        ns = modConfig.MOD_NAMESPACE
        client = modConfig.CLIENT_SYSTEM_NAME
        pairs = (
            (ev.EV_TILE_REQUEST, self.map.on_tile_request),
            (ev.EV_PICK_REQUEST, self.map.on_pick_request),
            (ev.EV_SHARE_WAYPOINT, self.waypoints.on_share),
            (ev.EV_SYNC_WAYPOINTS, self.waypoints.on_sync),
            (ev.EV_SYNC_SETTINGS, self.on_settings),
        )
        for name, handler in pairs:
            try:
                self.ListenForEvent(ns, client, name, self, handler)
            except Exception as exc:  # noqa: BLE001
                warn("could not listen for %s: %r", name, exc)

    def _start_timer(self):
        """A timer as well as the tick event: whichever the build supports."""
        game = None
        try:
            game = serverApi.GetEngineCompFactory().CreateGame(self.level_id)
        except Exception as exc:  # noqa: BLE001
            warn("CreateGame unavailable: %r", exc)
        if game is not None:
            try:
                self._timer = game.AddRepeatedTimer(modConfig.HUD_INTERVAL, self.on_timer)
            except Exception as exc:  # noqa: BLE001
                self._timer = None
                warn("AddRepeatedTimer unavailable: %r", exc)

    # -- engine callbacks ----------------------------------------------

    def on_script_tick(self, args=None):
        safe("OnScriptTickServer", self._tick)

    def on_timer(self, *args):
        safe("server timer", self._tick)

    def _tick(self):
        now = time.time()
        # The timer and the tick event may both fire; do the work once.
        if now - self._last_tick < modConfig.HUD_INTERVAL * 0.5:
            return
        self._last_tick = now
        self._tick_count += 1
        self.map.tick()

    def on_chat(self, args):
        safe("ServerChatEvent", self._handle_chat, args)

    def on_player_joined(self, args):
        def run():
            player_id = args.get("id") or args.get("playerId")
            if player_id:
                self.waypoints.on_player_joined(player_id)
                self.notify(
                    player_id,
                    ev.EV_NOTICE,
                    {"text": "Diorama 已启用 输入 diorama 查看帮助", "kind": "hello"},
                )

        safe("AddServerPlayerEvent", run)

    def on_player_died(self, args):
        """Tell only the dead player's client: the store is theirs to write."""
        def run():
            player_id = args.get("playerId") or args.get("id")
            if player_id:
                self.notify(player_id, ev.EV_COMMAND, {"text": "diorama death", "playerId": player_id})

        safe("PlayerDieEvent", run)

    def on_dimension_change(self, args):
        safe("DimensionChangeFinishServerEvent", self.radar.on_dimension_change, args)

    def on_settings(self, args):
        def run():
            self.radar.on_settings(args)
            self.map.on_settings(args)

        safe("diorama:sync_settings", run)

    # -- commands ------------------------------------------------------

    def _handle_chat(self, args):
        message = (args.get("message") or args.get("Message") or "").strip()
        player_id = args.get("playerId") or args.get("id")
        lowered = message.lower()
        if not lowered.startswith(("diorama", "#diorama")):
            return
        if player_id and lowered.startswith(("diorama", "#diorama")):
            # Cancel the chat line: it is a command, not something to broadcast.
            args["cancel"] = True
        body = lowered.replace("#diorama", "diorama", 1)[len("diorama") :].strip()
        self.waypoints.on_command({"playerId": player_id, "text": body})
        if not body:
            self.notify(
                player_id,
                ev.EV_NOTICE,
                {
                    "text": "diorama add|list|map|radar|pick|share",
                    "kind": "help",
                },
            )

    # -- helpers used by the components --------------------------------

    def player_ids(self):
        try:
            return list(serverApi.GetPlayerList() or [])
        except Exception as exc:  # noqa: BLE001
            warn("GetPlayerList failed: %r", exc)
            return []

    def notify(self, player_id, event_name, payload):
        if not player_id:
            return False
        try:
            self.NotifyToClient(player_id, event_name, payload)
            return True
        except Exception as exc:  # noqa: BLE001
            warn("NotifyToClient(%s) failed: %r", event_name, exc)
            return False

    def broadcast_chat(self, line):
        """Send a chat line, tolerating builds without a chat component."""
        factory = None
        try:
            factory = serverApi.GetEngineCompFactory()
        except Exception:  # noqa: BLE001
            return False
        sent = False
        for player_id in self.player_ids():
            comp = None
            for creator in ("CreateMsg", "CreateChat"):
                creator_fn = getattr(factory, creator, None)
                if creator_fn is None:
                    continue
                try:
                    comp = creator_fn(player_id)
                except Exception:  # noqa: BLE001
                    comp = None
                if comp is not None:
                    break
            if comp is None:
                continue
            for method in ("NotifyOneMessage", "SendMsg", "SendMessage"):
                fn = getattr(comp, method, None)
                if fn is None:
                    continue
                try:
                    fn(player_id, line)
                    sent = True
                    break
                except Exception:  # noqa: BLE001
                    continue
        return sent

    # -- world geometry -------------------------------------------------

    @property
    def dimension_id(self):
        return self._dimension_id

    def dimension_of(self, player_id):
        """The dimension a player is in, when the engine can say."""
        try:
            factory = serverApi.GetEngineCompFactory()
        except Exception:  # noqa: BLE001
            return self._dimension_id
        for creator in ("CreateDimension", "CreatePos"):
            fn = getattr(factory, creator, None)
            if fn is None:
                continue
            try:
                comp = fn(player_id)
            except Exception:  # noqa: BLE001
                continue
            for method in ("GetEntityDimensionId", "GetDimensionId"):
                getter = getattr(comp, method, None)
                if getter is None:
                    continue
                try:
                    return int(getter())
                except Exception:  # noqa: BLE001
                    continue
        return self._dimension_id

    def bounds_for(self, dimension_id):
        return DIMENSION_BOUNDS.get(int(dimension_id), DEFAULT_BOUNDS)

    def roof_for(self, dimension_id):
        return self.bounds_for(dimension_id)[2]

    def set_dimension(self, dimension_id):
        """Switch the geometry the components use; called on a dimension change."""
        self._dimension_id = int(dimension_id)
        self.min_y, self.max_y, self._roof = self.bounds_for(dimension_id)
        self.top_y = self.max_y - 1
        self.map._sources.clear()

    def pick_start_y(self, layer):
        if layer != TileKey.SURFACE:
            return min(int(layer), self.max_y)
        if self._roof != NO_ROOF:
            return min(self._roof, self.max_y)
        return self.top_y

    # -- teardown ------------------------------------------------------

    def Destroy(self):
        try:
            self.UnListenAllEvents()
        except Exception as exc:  # noqa: BLE001
            error("UnListenAllEvents failed: %r", exc)
        if self._timer is not None:
            try:
                serverApi.GetEngineCompFactory().CreateGame(self.level_id).CancelTimer(self._timer)
            except Exception:  # noqa: BLE001
                pass
        info("DioramaServerSystem destroyed after %d ticks", self._tick_count)

    def stats(self):
        return {
            "ticks": self._tick_count,
            "radar": self.radar.stats(),
            "waypoints": self.waypoints.stats(),
            "map": self.map.stats(),
        }
