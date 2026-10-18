# -*- coding: utf-8 -*-
"""Diorama server entry for NetEase ModSDK.

The imports are intentionally guarded: this module can be imported by the stdlib
unit tests without a game runtime. Bind concrete ModSDK events in ``init`` on the
China development workbench, then notify the client with the tile payload.
"""
from __future__ import absolute_import
import json
from modCore.waypoint_store import WaypointStore

try:
    import mod.server.extraServerApi as serverApi
except ImportError:
    serverApi = None

_STORE = None

def init():
    global _STORE
    _STORE = WaypointStore()
    if serverApi is None:
        return False
    return True

def sample_region(player_id, center, radius=32):
    """Return a bounded request descriptor; actual block queries are adapter-owned."""
    r = max(8, min(int(radius), 96))
    return {"player": player_id, "center": {"x": int(center[0]), "y": int(center[1]), "z": int(center[2])}, "radius": r}

def notify_client(player_id, event_name, payload):
    if serverApi is None:
        return False
    try:
        # Replace with the exact registered custom event name in the workbench.
        serverApi.GetEngineCompFactory().CreateGame(player_id).NotifyToClient(event_name, payload)
        return True
    except Exception:
        return False
