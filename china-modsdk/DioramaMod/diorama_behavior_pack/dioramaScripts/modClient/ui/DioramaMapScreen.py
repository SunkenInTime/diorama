# -*- coding: utf-8 -*-
"""Full-screen map: the pre-baked atlas plus waypoint and radar markers.

The background is a PNG atlas built off-line by ``tools/bake_atlas.py`` and
shipped in the resource pack, because the ModSDK has no way to upload a texture
that a script computed at run time. Live data still arrives two ways:

* markers are ``image`` controls cloned into the marker panel, positioned from
  map coordinates with the same projection the baker uses;
* tapping the map asks the server what block is under that pixel
  (``EV_PICK_REQUEST``) so a waypoint lands on the ground.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import warn
from dioramaScripts.modClient.ui import ui_base

ScreenNode = ui_base.screen_node_cls()

#: Content root declared in diorama_map.json's $screen_content.
CONTENT_ROOT = "/map_root"

#: Marker texture per waypoint kind / radar kind.
WAYPOINT_SPRITE = "textures/ui/diorama_marker_waypoint"
DEATH_SPRITE = "textures/ui/diorama_marker_death"
PLAYER_SPRITE = "textures/ui/diorama_marker_player"

#: How many cloned marker controls to keep before reuse.
MARKER_LIMIT = 96


class DioramaMapScreen(ScreenNode):
    """Pan/zoom map with markers."""

    USES_BASE_SCREEN = True
    CONTENT_ROOT = CONTENT_ROOT

    def __init__(self, namespace, name, param):
        ScreenNode.__init__(self, namespace, name, param)
        self._params = param or {}
        self._markers = []
        #: Map-space centre and zoom, in pixels per map unit.
        self.centre_u = 0.0
        self.centre_v = 0.0
        self.pixels_per_unit = 1.0
        self._handlers = {}

    def Create(self):
        return True

    # -- handlers ------------------------------------------------------

    def install_handlers(self, handlers):
        """``handlers`` maps a button path suffix to a zero-arg callable."""
        self._handlers = dict(handlers or {})
        for path, callback in self._handlers.items():
            ui_base.on_touch_up(self, path, self._wrap(path, callback))

    def _wrap(self, path, callback):
        def handler(*_args):
            try:
                callback()
            except Exception as exc:  # noqa: BLE001 - a UI callback must not throw
                warn("map handler %s failed: %r", path, exc)

        return handler

    # -- view ----------------------------------------------------------

    def set_view(self, centre_u, centre_v, pixels_per_unit):
        self.centre_u = float(centre_u)
        self.centre_v = float(centre_v)
        self.pixels_per_unit = max(0.05, float(pixels_per_unit))
        self._apply_atlas_offset()

    def zoom_by(self, factor):
        self.set_view(
            self.centre_u, self.centre_v, self.pixels_per_unit * float(factor)
        )
        self.set_footer("zoom %.2f px/block" % self.pixels_per_unit)

    def _apply_atlas_offset(self):
        node = ui_base.control(self, "/map_panel/atlas")
        if node is None:
            return False
        fn = getattr(node, "SetPosition", None)
        if fn is None:
            return False
        try:
            fn((-self.centre_u * self.pixels_per_unit,
                -self.centre_v * self.pixels_per_unit))
            return True
        except Exception:  # noqa: BLE001
            return False

    # -- text ----------------------------------------------------------

    def set_title(self, text):
        return ui_base.set_text(self, "/title_label", text)

    def set_footer(self, text):
        return ui_base.set_text(self, "/footer_label", text)

    def set_atlas_visible(self, visible):
        return ui_base.set_visible(self, "/map_panel/atlas", bool(visible))

    def set_grid_visible(self, visible):
        return ui_base.set_visible(self, "/map_panel/grid", bool(visible))

    # -- markers -------------------------------------------------------

    def render_markers(self, markers):
        """Draw ``markers``, each a dict with ``u``, ``v``, ``sprite``, ``label``.

        Markers are cloned controls; when there are more than
        :data:`MARKER_LIMIT`, the extras are dropped rather than allowed to make
        the screen unusably large.
        """
        markers = list(markers or [])[:MARKER_LIMIT]
        self._clear_markers()
        for index, marker in enumerate(markers):
            path = ui_base.clone(
                self, "/map_panel/markers/marker_template", "/map_panel/markers", "m%d" % index
            )
            if path is None:
                break
            self._markers.append(path)
            ui_base.set_sprite(self, path, marker.get("sprite", WAYPOINT_SPRITE))
            ui_base.set_text(self, path + "/label", marker.get("label", ""))
            offset = self._marker_offset(marker)
            node = ui_base.control(self, path)
            fn = getattr(node, "SetPosition", None)
            if fn is not None:
                try:
                    fn(offset)
                except Exception:  # noqa: BLE001
                    pass
        return len(self._markers)

    def _marker_offset(self, marker):
        dx = (float(marker.get("u", 0.0)) - self.centre_u) * self.pixels_per_unit
        dy = (float(marker.get("v", 0.0)) - self.centre_v) * self.pixels_per_unit
        return (dx, dy)

    def _clear_markers(self):
        for path in self._markers:
            ui_base.remove(self, path, "/map_panel/markers")
        self._markers = []

    # -- map coordinates from a tap -------------------------------------

    def screen_to_map(self, x, y, origin=(0.0, 0.0)):
        """Map-space point for a tap at screen ``(x, y)`` inside the panel."""
        ou, ov = origin
        u = self.centre_u + (float(x) - ou) / self.pixels_per_unit
        v = self.centre_v + (float(y) - ov) / self.pixels_per_unit
        return (u, v)

    def install_map_tap(self, panel_path, callback):
        """Route a tap on the map panel to ``callback(u, v)``."""
        node = ui_base.as_button(self, panel_path)
        if node is None:
            return False

        def handler(args):
            data = args or {}
            x = data.get("TouchPosX", data.get("x", 0.0))
            y = data.get("TouchPosY", data.get("y", 0.0))
            u, v = self.screen_to_map(x, y)
            callback(u, v)

        return ui_base.on_touch_up(self, panel_path, handler)


UI_KEY = modConfig.UI_MAP
