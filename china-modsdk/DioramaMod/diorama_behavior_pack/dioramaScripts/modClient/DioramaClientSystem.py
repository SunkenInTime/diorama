# -*- coding: utf-8 -*-
"""``DioramaClientSystem`` - the client-side system registered from ``modMain``.

Wiring, in the order it happens:

``__init__``
    build the components, register the four UI screens, subscribe to the engine
    and to the server's custom events, and start the HUD timer.
``UiInitFinished``
    create the HUD screen (``isHud: 1``, so it does not block input) and hand it
    to :class:`~modClient.components.HudClientComponent`.
client tick
    refresh the HUD readout; the radar list itself is updated by the server's
    ``EV_RADAR`` pushes.

The full-screen map, waypoint list and settings screens are pushed on demand
and popped by their own close buttons.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import time

import mod.client.extraClientApi as clientApi

from dioramaScripts.modCommon import eventConfig as ev
from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import error, info, safe, warn
from dioramaScripts.modCore import bearing
from dioramaScripts.modCore.geometry import GEOMETRY
from dioramaScripts.modCore.projection import Projection
from dioramaScripts.modCore.tile_key import TileKey
from dioramaScripts.modClient.components.HudClientComponent import HudClientComponent
from dioramaScripts.modClient.components.MapClientComponent import MapClientComponent
from dioramaScripts.modClient.components.RadarClientComponent import RadarClientComponent
from dioramaScripts.modClient.components.SettingsClientComponent import SettingsClientComponent
from dioramaScripts.modClient.components.WaypointClientComponent import WaypointClientComponent
from dioramaScripts.modClient.engine.client_blocks import ClientBlockSource, ClientPlayer
from dioramaScripts.modClient.engine.config_backend import make_backend

ClientSystem = clientApi.GetClientSystemCls()


class DioramaClientSystem(ClientSystem):
    """Client entry point; owns the components and the screens."""

    def __init__(self, namespace, systemName):
        ClientSystem.__init__(self, namespace, systemName)
        self.api = clientApi
        self.level_id = clientApi.GetLevelId()
        self.local_player_id = clientApi.GetLocalPlayerId()
        self.player = ClientPlayer(clientApi, self.local_player_id)
        self._world_key = ""
        self._block_source = None
        self._hud_screen = None
        self._map_screen = None
        self._waypoint_screen = None
        self._settings_screen = None
        self._timer = None
        self._last_tick = 0.0
        self._tick_count = 0

        self.settings = SettingsClientComponent(
            self, make_backend(clientApi, self.local_player_id, modConfig.SETTINGS_CONFIG_KEY)
        )
        self.waypoints = WaypointClientComponent(
            self, make_backend(clientApi, self.local_player_id, modConfig.WAYPOINT_CONFIG_KEY)
        )
        self.radar = RadarClientComponent(self)
        self.map = MapClientComponent(self)
        self.hud = HudClientComponent(self)

        self._register_ui()
        self._listen_engine()
        self._listen_server()
        self._start_timer()
        info("DioramaClientSystem ready (playerId=%s)", self.local_player_id)

    # -- world identity -------------------------------------------------

    @property
    def world_key(self):
        """A stable key for the world, so waypoints do not mix between saves."""
        if not self._world_key:
            self._world_key = self._read_world_key()
        return self._world_key

    def _read_world_key(self):
        try:
            game = clientApi.GetEngineCompFactory().CreateGame(self.level_id)
        except Exception:  # noqa: BLE001
            return "local"
        for method in ("GetSeed", "GetLevelSeed", "GetWorldSeed"):
            fn = getattr(game, method, None)
            if fn is None:
                continue
            try:
                seed = fn()
            except Exception:  # noqa: BLE001
                continue
            if seed is not None:
                return "seed-%s" % (seed,)
        return "local"

    # -- UI registration ------------------------------------------------

    def _register_ui(self):
        try:
            from dioramaScripts.modClient.ui.DioramaHudScreen import DioramaHudScreen
            from dioramaScripts.modClient.ui.DioramaMapScreen import DioramaMapScreen
            from dioramaScripts.modClient.ui.DioramaSettingsScreen import DioramaSettingsScreen
            from dioramaScripts.modClient.ui.DioramaWaypointScreen import DioramaWaypointScreen
        except Exception as exc:  # noqa: BLE001 - a broken screen must not kill the mod
            error("could not import UI screens: %r", exc)
            return
        self._screen_classes = {
            modConfig.UI_HUD: DioramaHudScreen,
            modConfig.UI_MAP: DioramaMapScreen,
            modConfig.UI_WAYPOINTS: DioramaWaypointScreen,
            modConfig.UI_SETTINGS: DioramaSettingsScreen,
        }
        defs = {
            modConfig.UI_HUD: modConfig.UI_DEF_HUD,
            modConfig.UI_MAP: modConfig.UI_DEF_MAP,
            modConfig.UI_WAYPOINTS: modConfig.UI_DEF_WAYPOINTS,
            modConfig.UI_SETTINGS: modConfig.UI_DEF_SETTINGS,
        }
        for key, cls in self._screen_classes.items():
            try:
                clientApi.RegisterUI(modConfig.MOD_NAMESPACE, key, cls, defs[key])
            except Exception as exc:  # noqa: BLE001
                warn("RegisterUI(%s) failed: %r", key, exc)

    # -- listeners ------------------------------------------------------

    def _listen_engine(self):
        engine_ns = clientApi.GetEngineNamespace()
        engine_sys = clientApi.GetEngineSystemName()
        pairs = (
            (ev.E_UI_INIT_FINISHED, self.on_ui_init_finished),
            (ev.E_SCRIPT_TICK_CLIENT, self.on_script_tick),
            (ev.E_DIMENSION_CHANGE_CLIENT, self.on_dimension_change),
            (ev.E_LOCAL_PLAYER_STOP_LOADING, self.on_local_player_stop_loading),
        )
        for name, handler in pairs:
            try:
                self.ListenForEvent(engine_ns, engine_sys, name, self, handler)
            except Exception as exc:  # noqa: BLE001
                warn("could not listen for %s: %r", name, exc)

    def _listen_server(self):
        ns = modConfig.MOD_NAMESPACE
        server = modConfig.SERVER_SYSTEM_NAME
        pairs = (
            (ev.EV_RADAR, self.radar.on_update),
            (ev.EV_TILE, self.map.on_tile),
            (ev.EV_PICK_RESULT, self.map.on_pick),
            (ev.EV_WAYPOINTS, self.waypoints.on_shared),
            (ev.EV_SETTINGS, self.settings.on_request),
            (ev.EV_NOTICE, self.on_notice),
            (ev.EV_COMMAND, self.on_command),
        )
        for name, handler in pairs:
            try:
                self.ListenForEvent(ns, server, name, self, handler)
            except Exception as exc:  # noqa: BLE001
                warn("could not listen for %s: %r", name, exc)

    def _start_timer(self):
        try:
            game = clientApi.GetEngineCompFactory().CreateGame(self.level_id)
            self._timer = game.AddRepeatedTimer(modConfig.HUD_INTERVAL, self.on_timer)
        except Exception as exc:  # noqa: BLE001
            self._timer = None
            warn("client timer unavailable: %r", exc)

    # -- engine callbacks ----------------------------------------------

    def on_ui_init_finished(self, args=None):
        safe("UiInitFinished", self._create_hud)

    def on_local_player_stop_loading(self, args=None):
        safe("OnLocalPlayerStopLoading", self._create_hud)

    def on_script_tick(self, args=None):
        safe("OnScriptTickClient", self._tick)

    def on_timer(self, *args):
        safe("client timer", self._tick)

    def _tick(self):
        now = time.time()
        if now - self._last_tick < modConfig.HUD_INTERVAL * 0.5:
            return
        self._last_tick = now
        self._tick_count += 1
        self.hud.tick()

    def on_dimension_change(self, args=None):
        def run():
            self._block_source = None
            self.waypoints.on_dimension_change(args)
            self.hud.notice("\u5df2\u5207\u6362\u7ef4\u5ea6")
            self.notify_server(
                ev.EV_SYNC_SETTINGS, {"settings": self.settings.settings.to_server_dict()}
            )

        safe("DimensionChangeClientEvent", run)

    def on_notice(self, args):
        text = (args or {}).get("text")
        if text:
            self.hud.notice(text)

    # -- commands -------------------------------------------------------

    def on_command(self, args):
        """A ``diorama ...`` command, forwarded by the server."""
        safe("diorama command", self._run_command, (args or {}).get("text", ""))

    def _run_command(self, text):
        parts = ("%s" % (text or "",)).strip().lower().split()
        verb = parts[0] if parts else ""
        if verb in ("", "help"):
            self.hud.notice("diorama add | list | map | radar | pick | share")
        elif verb == "add":
            name = " ".join(parts[1:]) or None
            self.waypoints.add_at_player(name)
        elif verb == "list":
            self.open_waypoints()
        elif verb == "map":
            self.open_map()
        elif verb == "radar":
            self.hud.notice("radar: %s" % (self.radar.counts(),))
        elif verb == "pick":
            pos = self.player_position()
            if pos is None:
                return
            projection = self.projection()
            u, v = projection.world_to_map(pos[0], pos[1], pos[2])
            key = TileKey(TileKey.SURFACE, 0, 0, 0).tile_for(u, v, GEOMETRY)
            self.map.request_pick(u, v, key.layer)
        elif verb == "share":
            nearest = self.waypoints.nearest_to_player()
            if nearest is not None:
                self.waypoints.share(nearest.id)
        elif verb == "death":
            self.waypoints.on_player_died()
        elif verb == "waypoints":
            self.open_waypoints()
        else:
            self.hud.notice("\u672a\u77e5\u547d\u4ee4: %s" % verb)

    # -- screens --------------------------------------------------------

    def _create_hud(self):
        if self._hud_screen is not None:
            return self._hud_screen
        try:
            screen = clientApi.CreateUI(
                modConfig.MOD_NAMESPACE, modConfig.UI_HUD, {"isHud": 1}
            )
        except Exception as exc:  # noqa: BLE001
            warn("CreateUI(hud) failed: %r", exc)
            return None
        self._hud_screen = self.hud.attach(screen)
        return self._hud_screen

    def _push(self, ui_key, params=None):
        try:
            clientApi.PushScreen(modConfig.MOD_NAMESPACE, ui_key)
            return clientApi.GetUI(modConfig.MOD_NAMESPACE, ui_key)
        except Exception:  # noqa: BLE001 - fall back to CreateUI
            pass
        try:
            return clientApi.CreateUI(modConfig.MOD_NAMESPACE, ui_key, params or {"isHud": 0})
        except Exception as exc:  # noqa: BLE001
            warn("could not open %s: %r", ui_key, exc)
            return None

    def open_map(self):
        screen = self._push(modConfig.UI_MAP)
        self._map_screen = screen
        if screen is None:
            self.hud.notice("\u5730\u56fe\u754c\u9762\u4e0d\u53ef\u7528")
            return None
        safe("map install", self._install_map_handlers, screen)
        return screen

    def _install_map_handlers(self, screen):
        screen.install_handlers(
            {
                "/button_row/zoom_in_button": lambda: screen.zoom_by(1.25),
                "/button_row/zoom_out_button": lambda: screen.zoom_by(0.8),
                "/button_row/recenter_button": self.recenter_map,
                "/button_row/waypoint_button": self.open_waypoints,
                "/button_row/settings_button": self.open_settings,
                "/button_row/close_button": self.close_top,
            }
        )
        screen.install_map_tap("/map_panel", self.on_map_tap)
        screen.set_title("Diorama")
        screen.set_footer("\u62d6\u52a8\u6eda\u8f6e\u7f29\u653e  \u70b9\u51fb\u5730\u56fe\u6dfb\u52a0\u8def\u5f84\u70b9")
        screen.set_atlas_visible(self.settings.settings.show_atlas)
        screen.set_grid_visible(self.settings.settings.chunk_grid)
        self._render_map_markers(screen)

    def recenter_map(self):
        pos = self.player_position()
        if pos is None or self._map_screen is None:
            return
        projection = self.projection()
        self._map_screen.set_view(pos[0], projection.v(pos[1], pos[2]), self.settings.zoom())

    def on_map_tap(self, u, v):
        pos = self.map.local_standing_spot(u, v)
        if pos is not None:
            self.waypoints.add_at(pos[0], pos[1], pos[2], name=None)
            self.hud.notice("\u5df2\u5728 %d, %d, %d \u6dfb\u52a0\u8def\u5f84\u70b9" % pos)
        else:
            self.map.request_pick(u, v)
            self.hud.notice("\u6b63\u5728\u67e5\u8be2\u8be5\u4f4d\u7f6e\u2026")

    def _render_map_markers(self, screen):
        projection = self.projection()
        markers = []
        for waypoint in self.waypoints.marker_waypoints():
            if isinstance(waypoint, dict):
                u = float(waypoint.get("x", 0))
                v = projection.v(float(waypoint.get("y", 0)), float(waypoint.get("z", 0)))
                label = waypoint.get("initials") or waypoint.get("name", "?")
                sprite = "textures/ui/diorama_marker_death" if waypoint.get("kind") == "death" else "textures/ui/diorama_marker_waypoint"
            else:
                u = float(waypoint.x)
                v = projection.v(float(waypoint.y), float(waypoint.z))
                label = waypoint.label()
                sprite = (
                    "textures/ui/diorama_marker_death"
                    if waypoint.kind == "death"
                    else "textures/ui/diorama_marker_waypoint"
                )
            markers.append({"u": u, "v": v, "label": label, "sprite": sprite})
        for blip in self.radar.blips:
            markers.append(
                {
                    "u": blip.u,
                    "v": blip.v,
                    "label": "",
                    "sprite": self.radar.sprite_for(blip),
                }
            )
        screen.render_markers(markers)

    def open_waypoints(self):
        screen = self._push(modConfig.UI_WAYPOINTS)
        self._waypoint_screen = screen
        if screen is None:
            return None
        safe(
            "waypoint install",
            screen.install_handlers,
            self._select_waypoint,
            self.add_waypoint_here,
            self.share_waypoint,
            self.import_waypoint,
            self.delete_waypoint,
            self.close_top,
            self.recolor_waypoint,
        )
        self._render_waypoints(screen)
        return screen

    def _render_waypoints(self, screen):
        waypoints = self.waypoints.in_dimension()
        screen.render(waypoints, self.waypoints.describe)
        screen.set_hint(
            "\u5171\u4eab=%d  \u672c\u5730=%d" % (len(self.waypoints.shared), len(waypoints))
        )

    def _select_waypoint(self, waypoint_id):
        waypoint = self.waypoints.by_id(waypoint_id)
        self.waypoints.select(waypoint)
        if self._waypoint_screen is not None:
            self._waypoint_screen.set_selected(waypoint_id)
        self.hud.notice(self.waypoints.describe(waypoint))

    def open_settings(self):
        screen = self._push(modConfig.UI_SETTINGS)
        self._settings_screen = screen
        if screen is None:
            return None
        safe(
            "settings install",
            screen.install_handlers,
            self.toggle_setting,
            self.cycle_setting,
            self.reset_settings,
            self.close_top,
        )
        screen.render(self.settings.settings)
        return screen

    def close_top(self):
        try:
            clientApi.PopScreen()
        except Exception as exc:  # noqa: BLE001
            warn("PopScreen failed: %r", exc)
        self._map_screen = None
        self._waypoint_screen = None
        self._settings_screen = None

    # -- actions --------------------------------------------------------

    def add_waypoint_here(self):
        waypoint = self.waypoints.add_at_player()
        self.waypoints.select(waypoint)
        return waypoint

    def share_waypoint(self, waypoint_id=None):
        waypoint_id = waypoint_id or self.waypoints.selected()
        if waypoint_id is None:
            self.hud.notice("\u5148\u9009\u4e2d\u4e00\u4e2a\u8def\u5f84\u70b9")
            return False
        target = waypoint_id.id if hasattr(waypoint_id, "id") else waypoint_id
        return self.waypoints.share(target)

    def import_waypoint(self, waypoint_id=None):
        target = waypoint_id or (self.waypoints.selected().id if self.waypoints.selected() else None)
        if target is None:
            return None
        return self.waypoints.import_shared(target)

    def delete_waypoint(self, waypoint_id=None):
        target = waypoint_id or (self.waypoints.selected().id if self.waypoints.selected() else None)
        if target is None:
            return False
        removed = self.waypoints.remove(target)
        if self._waypoint_screen is not None:
            self._render_waypoints(self._waypoint_screen)
        return removed

    def recolor_waypoint(self, waypoint_id=None):
        target = waypoint_id or (self.waypoints.selected().id if self.waypoints.selected() else None)
        if target is None:
            return False
        waypoint = self.waypoints.by_id(target)
        if waypoint is None:
            return False
        from dioramaScripts.modCore.waypoint import PALETTE

        index = 0
        for position, colour in enumerate(PALETTE):
            if colour == waypoint.color:
                index = position
                break
        return self.waypoints.recolor(target, PALETTE[(index + 1) % len(PALETTE)])

    def toggle_minimap(self):
        value = self.settings.toggle("minimap")
        self.hud.refresh_layout()
        return value

    def toggle_setting(self, field):
        self.settings.toggle(field)
        self.hud.refresh_layout()
        if self._settings_screen is not None:
            self._settings_screen.render(self.settings.settings)

    def cycle_setting(self, field, cycler):
        method = getattr(self.settings, cycler, None)
        if method is not None:
            method()
        self.hud.refresh_layout()
        if self._settings_screen is not None:
            self._settings_screen.render(self.settings.settings)

    def reset_settings(self):
        self.settings.reset()
        self.hud.refresh_layout()
        if self._settings_screen is not None:
            self._settings_screen.render(self.settings.settings)

    # -- world access ---------------------------------------------------

    def player_position(self):
        return self.player.foot_pos()

    def player_bearing(self):
        return self.player.bearing()

    def dimension_id(self):
        return self.player.dimension_id()

    def biome_name(self):
        return self.player.biome_name()

    def projection(self):
        return Projection(self.settings.settings.pitch_degrees)

    def geometry(self):
        return GEOMETRY

    def block_source(self):
        if self._block_source is None:
            try:
                self._block_source = ClientBlockSource(self.level_id, clientApi)
            except Exception as exc:  # noqa: BLE001
                warn("client block source unavailable: %r", exc)
                return None
        return self._block_source

    def tile_status(self):
        pos = self.player_position()
        if pos is None:
            return ""
        key = TileKey(TileKey.SURFACE, 0, 0, 0).tile_for(pos[0], pos[2], GEOMETRY)
        state = "ok" if self.map.cache.has((key.layer, key.tile_u, key.tile_v, key.lod)) else "offline"
        return "map:%s  %s  %s" % (
            state,
            self.world_key,
            bearing.bearing_label(self.player_bearing()),
        )

    # -- messaging ------------------------------------------------------

    def notify_server(self, event_name, payload):
        try:
            self.NotifyToServer(event_name, payload)
            return True
        except Exception as exc:  # noqa: BLE001
            warn("NotifyToServer(%s) failed: %r", event_name, exc)
            return False

    def notify(self, player_id, event_name, payload):
        if player_id == self.local_player_id:
            return self.notify_server(event_name, payload)
        return False

    def notice(self, text):
        return self.hud.notice(text)

    # -- teardown -------------------------------------------------------

    def Destroy(self):
        try:
            self.UnListenAllEvents()
        except Exception as exc:  # noqa: BLE001
            error("UnListenAllEvents failed: %r", exc)
        if self._timer is not None:
            try:
                clientApi.GetEngineCompFactory().CreateGame(self.level_id).CancelTimer(self._timer)
            except Exception:  # noqa: BLE001
                pass
        info("DioramaClientSystem destroyed after %d ticks", self._tick_count)

    def stats(self):
        return {
            "ticks": self._tick_count,
            "hud": self.hud.stats(),
            "radar": self.radar.stats(),
            "map": self.map.stats(),
            "waypoints": self.waypoints.stats(),
            "settings": self.settings.settings.to_dict(),
        }
