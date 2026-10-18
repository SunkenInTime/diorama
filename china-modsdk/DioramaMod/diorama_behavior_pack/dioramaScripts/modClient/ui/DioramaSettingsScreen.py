# -*- coding: utf-8 -*-
"""Settings screen: one row per knob, all of them buttons.

Using buttons rather than sliders/toggles keeps the JSON small and the Python
trivial: every control either flips a boolean or steps a value, both of which
:class:`~modClient.components.SettingsClientComponent` already implements.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from dioramaScripts.modCommon import modConfig
from dioramaScripts.modClient.ui import ui_base

ScreenNode = ui_base.screen_node_cls()

#: Content root declared in diorama_settings.json's $screen_content.
CONTENT_ROOT = "/settings_root"

#: Toggle path -> settings field name.
TOGGLES = (
    ("/rows/row_minimap/toggle", "minimap"),
    ("/rows/row_compass/toggle", "compass"),
    ("/rows/row_coordinates/toggle", "coordinates"),
    ("/rows/row_biome/toggle", "biome"),
    ("/rows/row_radar_players/toggle", "radar_players"),
    ("/rows/row_radar_hostile/toggle", "radar_hostile"),
    ("/rows/row_radar_neutral/toggle", "radar_neutral"),
    ("/rows/row_radar_passive/toggle", "radar_passive"),
    ("/rows/row_death/toggle", "death_markers"),
    ("/rows/row_outlines/toggle", "outlines"),
    ("/rows/row_rounded_fog/toggle", "rounded_fog"),
    ("/rows/row_depth/toggle", "depth_shading"),
    ("/rows/row_grid/toggle", "chunk_grid"),
    ("/rows/row_fog_of_war/toggle", "always_fog_of_war"),
)

#: Value path -> (settings field, cycler method name on the component).
VALUES = (
    ("/rows/row_range/value", "radar_range", "cycle_radar_range"),
    ("/rows/row_size/value", "minimap_size", "cycle_size"),
    ("/rows/row_zoom/value", "minimap_zoom", "cycle_zoom"),
)


class DioramaSettingsScreen(ScreenNode):
    """Every Diorama setting on one screen."""

    USES_BASE_SCREEN = True
    CONTENT_ROOT = CONTENT_ROOT

    def __init__(self, namespace, name, param):
        ScreenNode.__init__(self, namespace, name, param)
        self._params = param or {}

    def Create(self):
        return True

    def install_handlers(self, on_toggle, on_cycle, on_reset, on_close):
        for path, field in TOGGLES:
            ui_base.on_touch_up(self, path, self._make_toggle(on_toggle, field))
        for path, field, cycler in VALUES:
            ui_base.on_touch_up(self, path, self._make_cycle(on_cycle, field, cycler))
        if on_reset is not None:
            ui_base.on_touch_up(self, "/button_row/reset_button", on_reset)
        if on_close is not None:
            ui_base.on_touch_up(self, "/button_row/done_button", on_close)

    @staticmethod
    def _make_toggle(on_toggle, field):
        def handler(*_args):
            if on_toggle is not None:
                on_toggle(field)

        return handler

    @staticmethod
    def _make_cycle(on_cycle, field, cycler):
        def handler(*_args):
            if on_cycle is not None:
                on_cycle(field, cycler)

        return handler

    def render(self, settings):
        """Refresh every label from the current settings object."""
        for path, field in TOGGLES:
            value = getattr(settings, field, None)
            ui_base.set_text(self, path + "/label", self._on_off(value))
        for path, field, cycler in VALUES:
            ui_base.set_text(self, path + "/label", "%s" % (getattr(settings, field, "?"),))
        ui_base.set_text(self, "/summary_label", _summary(settings))
        return True

    @staticmethod
    def _on_off(value):
        return "\u5f00" if value else "\u5173"


def _summary(settings):
    return "radar=%d  pitch=%.0f\u00b0  quality=%s" % (
        getattr(settings, "radar_range", 32),
        getattr(settings, "pitch_degrees", 50.0),
        getattr(settings, "quality", "fancy"),
    )


UI_KEY = modConfig.UI_SETTINGS
