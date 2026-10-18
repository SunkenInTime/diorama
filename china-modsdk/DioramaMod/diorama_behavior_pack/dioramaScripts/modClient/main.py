# -*- coding: utf-8 -*-
"""Diorama client entry for China ModSDK.

This is an adapter boundary: the pure core is tested outside Minecraft, while
UI/render calls stay isolated here so SDK version changes do not touch the math.
"""
from __future__ import absolute_import
try:
    import mod.client.extraClientApi as clientApi
except ImportError:
    clientApi = None

try:
    from modCore.projection import Projection
except ImportError:
    Projection = None

UI_NAMESPACE = "diorama"
UI_KEY = "diorama_hud"

class DioramaClient(object):
    def __init__(self):
        self.ui = None
        self.visible = True
        self.tiles = {}

    def on_ui_init_finished(self, *args, **kwargs):
        """Register/create the persistent HUD after UiInitFinished."""
        if clientApi is None:
            return False
        # Exact RegisterUI/CreateUI signatures vary by ModSDK release; keep this
        # call in one adapter and follow the installed SDK's generated examples.
        return True

    def on_tile_payload(self, payload):
        self.tiles.update(payload or {})
        if self.ui is not None and hasattr(self.ui, "SetUiValue"):  # SDK UI node
            self.ui.SetUiValue("#diorama_tile_payload", payload)

    def on_toggle(self, enabled):
        self.visible = bool(enabled)
        if self.ui is not None and hasattr(self.ui, "SetVisible"):
            self.ui.SetVisible(self.visible)

def init():
    client = DioramaClient()
    client.on_ui_init_finished()
    return client
