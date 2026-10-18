# -*- coding: utf-8 -*-
"""Waypoint sharing and cross-player relay.

The authoritative store lives on each client (it is that player's own file, and
the ModSDK has no server-side filesystem at all). The server only does what a
client cannot:

* relay a shared waypoint to everybody else, as a chat line plus an
  ``EV_WAYPOINTS`` update, so shared markers appear on other players' maps;
* answer a player joining with the shared waypoints already in flight;
* forward ``/diorama`` chat commands to the client that typed them, which is
  where the UI and the store live.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from dioramaScripts.modCommon import eventConfig as ev
from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import info
from dioramaScripts.modCore.waypoint import Waypoint


class WaypointServerComponent(object):
    """Holds the shared (not private) waypoints for the session."""

    def __init__(self, server):
        self.server = server
        #: playerId -> list of waypoint dicts that player shared
        self._shared = {}

    # -- from the client -----------------------------------------------

    def on_share(self, args):
        """``{"waypoint": {...}}`` from the client that owns the waypoint."""
        player_id = args.get("playerId") or args.get("__playerId")
        data = args.get("waypoint")
        if not data:
            return
        try:
            waypoint = Waypoint.from_dict(data)
        except (ValueError, TypeError, KeyError):
            return
        if player_id:
            bucket = self._shared.setdefault(player_id, [])
            bucket = [w for w in bucket if w.get("id") != waypoint.id]
            bucket.append(waypoint.to_dict())
            self._shared[player_id] = bucket[-32:]
        line = "[Diorama] %s @ %d %d %d dim=%d color=%d" % (
            waypoint.name,
            waypoint.x,
            waypoint.y,
            waypoint.z,
            waypoint.dimension,
            waypoint.color,
        )
        self._announce(player_id, line)
        self._broadcast_waypoints()

    def on_sync(self, args):
        """A client replaced its shared list wholesale."""
        player_id = args.get("playerId") or args.get("__playerId")
        if not player_id:
            return
        waypoints = args.get("waypoints")
        if not isinstance(waypoints, list):
            return
        self._shared[player_id] = waypoints[:64]
        self._broadcast_waypoints()

    def on_command(self, args):
        """``/diorama ...`` typed in chat; hand it back to the client."""
        player_id = args.get("playerId") or args.get("__playerId")
        if not player_id:
            return
        self.server.notify(
            player_id,
            ev.EV_COMMAND,
            {"text": args.get("text", ""), "playerId": player_id},
        )

    # -- to the client --------------------------------------------------

    def on_player_joined(self, player_id):
        self.server.notify(
            player_id, ev.EV_WAYPOINTS, {"shared": self.all_shared(), "scope": "shared"}
        )

    def all_shared(self):
        merged = {}
        for bucket in self._shared.values():
            for data in bucket:
                if isinstance(data, dict) and data.get("id"):
                    merged[data["id"]] = data
        return list(merged.values())

    def _broadcast_waypoints(self):
        payload = {"shared": self.all_shared(), "scope": "shared"}
        for player_id in self.server.player_ids():
            self.server.notify(player_id, ev.EV_WAYPOINTS, payload)

    def _announce(self, sender_id, line):
        """Show a chat line to everyone, falling back to an action-bar notice."""
        delivered = self.server.broadcast_chat(line)
        if not delivered:
            for player_id in self.server.player_ids():
                self.server.notify(player_id, ev.EV_NOTICE, {"text": line, "from": sender_id})
        info("waypoint shared by %s: %s", sender_id, line)

    def stats(self):
        return {"players_sharing": len(self._shared), "shared": len(self.all_shared())}


#: Kept for symmetry with the other components; not used server-side.
CHAT_PREFIX = modConfig.CHAT_PREFIX
