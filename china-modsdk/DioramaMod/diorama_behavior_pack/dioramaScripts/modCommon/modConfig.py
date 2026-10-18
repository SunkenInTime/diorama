# -*- coding: utf-8 -*-
"""Mod identity and the numbers the rest of the scripts share.

The values here must match the resource pack and the UI JSON: the namespace is
what ``RegisterSystem``, ``RegisterUI`` and the ``"namespace"`` key in every UI
file use, and the class paths are what ``modMain.py`` registers.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

# -- identity ---------------------------------------------------------------

#: ModSDK namespace. Also the resource pack UI namespace.
MOD_NAMESPACE = "diorama"
MOD_VERSION = "0.1.0"

#: The folder the scripts live in, i.e. the first component of every import.
SCRIPT_PACKAGE = "dioramaScripts"

# -- systems ----------------------------------------------------------------

SERVER_SYSTEM_NAME = "DioramaServerSystem"
CLIENT_SYSTEM_NAME = "DioramaClientSystem"

SERVER_SYSTEM_CLS_PATH = SCRIPT_PACKAGE + ".modServer.DioramaServerSystem.DioramaServerSystem"
CLIENT_SYSTEM_CLS_PATH = SCRIPT_PACKAGE + ".modClient.DioramaClientSystem.DioramaClientSystem"

# -- UI ---------------------------------------------------------------------

#: UI keys passed to ``clientApi.RegisterUI`` / ``CreateUI``.
UI_HUD = "diorama_hud"
UI_MAP = "diorama_map"
UI_WAYPOINTS = "diorama_waypoints"
UI_SETTINGS = "diorama_settings"
UI_LABEL = "diorama_label"

#: ``RegisterUI(..., uiScreenDef)`` takes ``<namespace>.<ui file name>``; the
#: JSON file, its ``"namespace"`` key and the Python file share the name.
UI_DEF_HUD = MOD_NAMESPACE + "." + UI_HUD
UI_DEF_MAP = MOD_NAMESPACE + "." + UI_MAP
UI_DEF_WAYPOINTS = MOD_NAMESPACE + "." + UI_WAYPOINTS
UI_DEF_SETTINGS = MOD_NAMESPACE + "." + UI_SETTINGS
UI_DEF_LABEL = MOD_NAMESPACE + "." + UI_LABEL

#: Path prefix a screen needs when it inherits ``common.base_screen`` and
#: therefore the safe-zone wrapper. See docs/API_ASSUMPTIONS.md.
BASE_SCREEN_PATH = (
    "/variables_button_mappings_and_controls/safezone_screen_matrix"
    "/inner_matrix/safezone_screen_panel/root_screen_panel"
)

# -- map --------------------------------------------------------------------

#: Map geometry used in game. modCore.geometry has the same defaults.
TILE_UNITS = 128
PIXELS_PER_UNIT = 4
COVERAGE_CELLS = 64

#: Highest level of detail (zoom-out) the cache keeps.
MAX_LOD = 5

# -- timing -----------------------------------------------------------------

#: How often the HUD readout is refreshed, in seconds.
HUD_INTERVAL = 0.25

#: How often the radar scan runs, in seconds.
RADAR_INTERVAL = 0.5

#: How often the client asks the server for a fresh tile.
TILE_INTERVAL = 2.0

#: Milliseconds (approximate; ModSDK timers take seconds) a bake may spend
#: before it yields and is rescheduled.
BAKE_BUDGET_SECONDS = 0.02

# -- radar ------------------------------------------------------------------

#: Radar height ranges the settings screen offers, in blocks.
RADAR_RANGES = (16, 32, 64, 128)

# -- waypoints --------------------------------------------------------------

#: Key the client keeps the waypoint JSON under (see ConfigCompBackend).
WAYPOINT_CONFIG_KEY = "diorama.waypoints"

#: Key the client keeps the settings JSON under.
SETTINGS_CONFIG_KEY = "diorama.settings"

#: Chat prefix for both commands and shared waypoint lines.
CHAT_PREFIX = "[Diorama]"

#: Commands accepted in chat, without the leading slash.
COMMAND_HELP = "diorama"
COMMAND_ADD = "diorama add"
COMMAND_LIST = "diorama list"
COMMAND_MAP = "diorama map"
COMMAND_RADAR = "diorama radar"
COMMAND_PICK = "diorama pick"
