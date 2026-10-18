# -*- coding: utf-8 -*-
"""Engine <-> mod events used by Diorama, in one place.

The engine events are the names from the China Edition ModAPI event lists; the
custom events are ours and travel over ``NotifyToClient`` / ``NotifyToServer``.
Every custom name is namespaced with ``diorama:`` so it cannot collide with
another mod's event on the same engine namespace.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

# -- engine events, server --------------------------------------------------

#: Chat message from a player; used for the ``/diorama`` commands and sharing.
E_SERVER_CHAT = "ServerChatEvent"
#: A player joined; used to send the settings and the waypoint list.
E_ADD_SERVER_PLAYER = "AddServerPlayerEvent"
#: A player died; used to drop a death marker.
E_PLAYER_DIE = "PlayerDieEvent"
#: A player changed dimension; used to reset the map cache.
E_DIMENSION_CHANGE = "DimensionChangeFinishServerEvent"
#: Per-tick hook. Whether this fires automatically or must be listened for is
#: an unverified assumption - see docs/API_ASSUMPTIONS.md.
E_SCRIPT_TICK_SERVER = "OnScriptTickServer"

# -- engine events, client --------------------------------------------------

#: UI framework finished initialising; register and create screens here.
E_UI_INIT_FINISHED = "UiInitFinished"
#: A dimension change finished on the client.
E_DIMENSION_CHANGE_CLIENT = "DimensionChangeClientEvent"
#: Loading screen is gone; first safe moment to create the HUD.
E_LOCAL_PLAYER_STOP_LOADING = "OnLocalPlayerStopLoading"
#: Per-frame hook; see the note on E_SCRIPT_TICK_SERVER.
E_SCRIPT_TICK_CLIENT = "OnScriptTickClient"

# -- custom events, server -> client ---------------------------------------

#: Radar blips for one player: {"blips": [...], "viewer": {...}}
EV_RADAR = "diorama:radar"
#: A baked tile or a refusal: {"key": [...], "png": "<base64>", ...}
EV_TILE = "diorama:tile"
#: The shared waypoint list: {"waypoints": [...]}
EV_WAYPOINTS = "diorama:waypoints"
#: Player settings echo: {"settings": {...}}
EV_SETTINGS = "diorama:settings"
#: A short message to show in the action bar.
EV_NOTICE = "diorama:notice"

# -- custom events, client -> server ---------------------------------------

#: Ask for a tile: {"key": [layer, u, v, lod], "fog": bool}
EV_TILE_REQUEST = "diorama:tile_request"
#: Ask where a map point lands: {"u": float, "v": float, "layer": int}
EV_PICK_REQUEST = "diorama:pick_request"
#: The answer: {"x": int, "y": int, "z": int} or {"ok": False}
EV_PICK_RESULT = "diorama:pick_result"
#: Share a waypoint in chat: {"waypoint": {...}}
EV_SHARE_WAYPOINT = "diorama:share_waypoint"
#: Push a changed waypoint list to the server (for other players' radar).
EV_SYNC_WAYPOINTS = "diorama:sync_waypoints"
#: Persist settings that affect the server side of the radar.
EV_SYNC_SETTINGS = "diorama:sync_settings"
#: A radio-style command typed in chat, forwarded from server to client.
EV_COMMAND = "diorama:command"
