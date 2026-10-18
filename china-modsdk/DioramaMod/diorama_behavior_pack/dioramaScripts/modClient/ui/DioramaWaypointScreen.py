# -*- coding: utf-8 -*-
"""Waypoint list screen: add, edit, share, import and delete.

Rows are cloned from ``/list_panel/row_template``; each row is a button whose
touch-up selects that waypoint. The action buttons act on the selection, which
keeps the number of controls constant no matter how many waypoints exist - the
same trick the Fabric mod's list screen uses with its rows.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import warn
from dioramaScripts.modClient.ui import ui_base

ScreenNode = ui_base.screen_node_cls()

ROW_LIMIT = 24


class DioramaWaypointScreen(ScreenNode):
    """The waypoint list and its actions."""

    USES_BASE_SCREEN = True

    def __init__(self, namespace, name, param):
        ScreenNode.__init__(self, namespace, name, param)
        self._params = param or {}
        #: Row index -> waypoint id, filled by :meth:`render`.
        self.selection_map = {}
        self.selected_id = None
        self._rows = []

    def Create(self):
        return True

    # -- handlers ------------------------------------------------------

    def install_handlers(self, on_select, on_add, on_share, on_import, on_delete, on_close, on_recolor=None):
        if on_add is not None:
            ui_base.on_touch_up(self, "/add_button", self._guard(on_add))
        if on_share is not None:
            ui_base.on_touch_up(self, "/share_button", self._guard(lambda: on_share(self.selected_id)))
        if on_import is not None:
            ui_base.on_touch_up(self, "/import_button", self._guard(lambda: on_import(self.selected_id)))
        if on_delete is not None:
            ui_base.on_touch_up(self, "/delete_button", self._guard(lambda: on_delete(self.selected_id)))
        if on_recolor is not None:
            ui_base.on_touch_up(self, "/recolor_button", self._guard(lambda: on_recolor(self.selected_id)))
        if on_close is not None:
            ui_base.on_touch_up(self, "/close_button", self._guard(on_close))
        self._on_select = on_select

    def _guard(self, callback):
        def handler(*_args):
            try:
                callback()
            except Exception as exc:  # noqa: BLE001
                warn("waypoint handler failed: %r", exc)

        return handler

    # -- rendering -----------------------------------------------------

    def render(self, waypoints, describe=None, selected_id=None):
        """Show ``waypoints`` (a list of :class:`modCore.waypoint.Waypoint`)."""
        self._clear_rows()
        self.selection_map = {}
        self.selected_id = selected_id
        waypoints = list(waypoints or [])[:ROW_LIMIT]
        for index, waypoint in enumerate(waypoints):
            path = ui_base.clone(
                self, "/list_panel/row_template", "/list_panel", "row%d" % index
            )
            if path is None:
                break
            self._rows.append(path)
            self.selection_map[index] = waypoint.id
            text = describe(waypoint) if describe is not None else waypoint.name
            ui_base.set_text(self, path + "/row_label", text)
            ui_base.set_text(self, path + "/row_initial", waypoint.label())
            marker = " \u25c0" if waypoint.id == selected_id else ""
            ui_base.set_text(self, path + "/row_selected", marker)
            self._install_row(path, waypoint.id)
        ui_base.set_text(self, "/count_label", "%d / %d" % (len(waypoints), ROW_LIMIT))
        return len(self._rows)

    def _install_row(self, path, waypoint_id):
        on_select = getattr(self, "_on_select", None)

        def handler(*_args):
            self.selected_id = waypoint_id
            if on_select is not None:
                try:
                    on_select(waypoint_id)
                except Exception as exc:  # noqa: BLE001
                    warn("select handler failed: %r", exc)

        ui_base.on_touch_up(self, path, handler)

    def _clear_rows(self):
        for path in self._rows:
            ui_base.remove(self, path, "/list_panel")
        self._rows = []

    def set_hint(self, text):
        return ui_base.set_text(self, "/hint_label", text)

    def set_selected(self, waypoint_id):
        self.selected_id = waypoint_id
        return True


UI_KEY = modConfig.UI_WAYPOINTS
