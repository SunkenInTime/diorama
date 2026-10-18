# -*- coding: utf-8 -*-
"""The Diorama HUD screen: minimap frame, compass and the readout labels.

The Python side owns only what changes: which corner the frame sits in, what
the labels say, and which compass sprite shows. Everything about the initial
look lives in ``resource_pack/ui/diorama_hud.json``, which is the split the
China Edition UI documentation recommends (JSON for layout, Python for state).
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import warn
from dioramaScripts.modClient.ui import ui_base

ScreenNode = ui_base.screen_node_cls()

#: Content root declared in diorama_hud.json's $screen_content.
CONTENT_ROOT = "/hud_root"

COMPASS_DIRECTIONS = ("n", "ne", "e", "se", "s", "sw", "w", "nw")

#: Where each corner puts the frame, as an ``(anchor, offset sign)`` pair.
CORNER_ANCHORS = {
    "top_right": ("top_right", (-1, 1)),
    "top_left": ("top_left", (1, 1)),
    "bottom_right": ("bottom_right", (-1, -1)),
    "bottom_left": ("bottom_left", (1, -1)),
}


class DioramaHudScreen(ScreenNode):
    """Always-on HUD: the minimap frame plus coordinates, compass and biome."""

    USES_BASE_SCREEN = True
    CONTENT_ROOT = CONTENT_ROOT

    def __init__(self, namespace, name, param):
        ScreenNode.__init__(self, namespace, name, param)
        self._params = param or {}
        self._layout_applied = False

    # -- lifecycle -----------------------------------------------------

    def Create(self):
        warn("DioramaHudScreen created") if False else None
        return True

    def Update(self):
        return None

    # -- layout --------------------------------------------------------

    def apply_layout(self, settings):
        """Move and resize the minimap to match the settings."""
        anchor, sign = CORNER_ANCHORS.get(
            getattr(settings, "minimap_corner", "top_right"), ("top_right", (-1, 1))
        )
        size = getattr(settings, "minimap_size", 128)
        margin = 8
        offset = (sign[0] * margin, sign[1] * margin)
        node = ui_base.control(self, "/minimap")
        if node is not None:
            for name in ("SetAnchorFrom", "SetAnchorTo"):
                fn = getattr(node, name, None)
                if fn is not None:
                    try:
                        fn(anchor)
                    except Exception:  # noqa: BLE001 - cosmetic only
                        pass
            for name, value in (
                ("SetPosition", offset),
                ("SetSize", (float(size), float(size))),
            ):
                fn = getattr(node, name, None)
                if fn is None:
                    continue
                try:
                    fn(value)
                except Exception:  # noqa: BLE001
                    pass
        self._layout_applied = True
        self.set_visible(bool(getattr(settings, "minimap", True)))

    def set_visible(self, visible):
        for path in ("/minimap", "/coord_label", "/bearing_label", "/biome_label"):
            ui_base.set_visible(self, path, bool(visible))
        return True

    # -- content -------------------------------------------------------

    def set_readout(self, coords=None, bearing=None, biome=None):
        settings = getattr(self, "_settings", None)
        if coords is not None and (settings is None or getattr(settings, "coordinates", True)):
            ui_base.set_text(self, "/coord_label", coords)
        if bearing is not None and (settings is None or getattr(settings, "compass", True)):
            ui_base.set_text(self, "/bearing_label", bearing)
        if biome is not None and (settings is None or getattr(settings, "biome", True)):
            ui_base.set_text(self, "/biome_label", biome)

    def remember_settings(self, settings):
        self._settings = settings

    def set_compass(self, degrees):
        """Pick the compass sprite for a bearing (no rotation API needed)."""
        index = int(round(((degrees % 360.0) + 360.0) % 360.0 / 45.0)) % 8
        sprite = "textures/ui/diorama_compass_%s" % COMPASS_DIRECTIONS[index]
        return ui_base.set_sprite(self, "/minimap/compass", sprite)

    def set_waypoint_line(self, text):
        return ui_base.set_text(self, "/waypoint_label", text or "")

    def set_notice(self, text):
        return ui_base.set_text(self, "/notice_label", text or "")

    def set_tile_status(self, text):
        return ui_base.set_text(self, "/tile_status_label", text or "")

    # -- hooks the client system installs -------------------------------

    def install_handlers(self, on_open_map=None, on_add_waypoint=None, on_toggle_minimap=None):
        if on_open_map is not None:
            ui_base.on_touch_up(self, "/minimap", on_open_map)
        if on_add_waypoint is not None:
            ui_base.on_touch_up(self, "/add_button", on_add_waypoint)
        if on_toggle_minimap is not None:
            ui_base.on_touch_up(self, "/hide_button", on_toggle_minimap)


#: The key the client system registers this screen under.
UI_KEY = modConfig.UI_HUD
