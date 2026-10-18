# -*- coding: utf-8 -*-
"""Waypoints on the client: the store, the death marker and the share line.

This is the only side that writes waypoints. The store itself is the pure
:class:`modCore.waypoint_store.WaypointStore`, backed here by the ModSDK
``CreateConfig`` blob, so the Java port's tolerance rules (one bad entry costs
only itself, damaged text is copied aside, atomic replace, three death markers)
apply unchanged.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from dioramaScripts.modCommon import eventConfig as ev
from dioramaScripts.modCommon import modConfig
from dioramaScripts.modCommon.log import info, warn
from dioramaScripts.modCore import bearing
from dioramaScripts.modCore.waypoint import (
    DEATH_COLOUR,
    KIND_DEATH,
    PALETTE,
    Waypoint,
    dimension_name,
)
from dioramaScripts.modCore.waypoint_store import WaypointStore


class WaypointClientComponent(object):
    """The local waypoint store plus the operations the UI needs."""

    def __init__(self, client, backend):
        self.client = client
        self.store = WaypointStore(backend, world_key=client.world_key)
        #: Waypoints other players shared, keyed by id; never written to disk.
        self.shared = {}
        self._selected = None

    # -- queries -------------------------------------------------------

    def all(self):
        return self.store.all()

    def in_dimension(self, dimension=None):
        if dimension is None:
            dimension = self.client.dimension_id()
        return self.store.in_dimension(dimension)

    def visible_in_dimension(self, dimension=None):
        if dimension is None:
            dimension = self.client.dimension_id()
        return self.store.visible_in_dimension(dimension)

    def marker_waypoints(self):
        """Everything that should appear as a marker: local plus shared."""
        markers = list(self.visible_in_dimension())
        current = self.client.dimension_id()
        for data in self.shared.values():
            if int(data.get("dimension", 0)) == current:
                markers.append(data)
        return markers

    def by_id(self, waypoint_id):
        if not waypoint_id:
            return None
        mine = self.store.by_id(waypoint_id)
        if mine is not None:
            return mine
        data = self.shared.get(waypoint_id)
        if data is None:
            return None
        try:
            return Waypoint.from_dict(data)
        except (ValueError, TypeError):
            return None

    def nearest_to_player(self):
        pos = self.client.player_position()
        if pos is None:
            return None
        return self.store.nearest(
            pos[0] + 0.5, pos[1], pos[2] + 0.5, dimension=self.client.dimension_id()
        )

    def select(self, waypoint):
        self._selected = waypoint
        return waypoint

    def selected(self):
        return self._selected

    # -- mutations -----------------------------------------------------

    def add_at_player(self, name=None):
        pos = self.client.player_position()
        if pos is None:
            return None
        count = self.store.count() + 1
        waypoint = Waypoint(
            name=name or ("Waypoint %d" % count),
            x=int(pos[0]),
            y=int(pos[1]),
            z=int(pos[2]),
            dimension=self.client.dimension_id(),
            color=PALETTE[count % len(PALETTE)],
        )
        self.store.add(waypoint)
        self._announce("\u5df2\u521b\u5efa\u8def\u5f84\u70b9 %s" % waypoint.name)
        return waypoint

    def add_at(self, x, y, z, name=None, dimension=None):
        count = self.store.count() + 1
        waypoint = Waypoint(
            name=name or ("Waypoint %d" % count),
            x=int(x),
            y=int(y),
            z=int(z),
            dimension=dimension if dimension is not None else self.client.dimension_id(),
            color=PALETTE[count % len(PALETTE)],
        )
        self.store.add(waypoint)
        self._announce("\u5df2\u521b\u5efa\u8def\u5f84\u70b9 %s" % waypoint.name)
        return waypoint

    def rename(self, waypoint_id, name):
        waypoint = self.store.by_id(waypoint_id)
        if waypoint is None:
            return False
        waypoint.name = name or waypoint.name
        waypoint.initials = Waypoint(name=waypoint.name).initials
        self.store.changed()
        return True

    def remove(self, waypoint_id):
        if self.store.remove_by_id(waypoint_id):
            self._announce("\u5df2\u5220\u9664\u8def\u5f84\u70b9")
            return True
        return False

    def recolor(self, waypoint_id, color):
        waypoint = self.store.by_id(waypoint_id)
        if waypoint is None:
            return False
        waypoint.color = int(color)
        self.store.changed()
        return True

    def toggle_visible(self, waypoint_id):
        waypoint = self.store.by_id(waypoint_id)
        if waypoint is None:
            return False
        waypoint.visible = not waypoint.visible
        self.store.changed()
        return waypoint.visible

    # -- death markers --------------------------------------------------

    def on_player_died(self, args=None):
        """Record where the player died, if the setting asks for it."""
        if not self.client.settings.settings.death_markers:
            return None
        pos = self.client.player_position()
        if pos is None:
            return None
        count = sum(1 for w in self.store.all() if w.kind == KIND_DEATH) + 1
        waypoint = Waypoint(
            name="Death %d" % count,
            x=int(pos[0]),
            y=int(pos[1]),
            z=int(pos[2]),
            dimension=self.client.dimension_id(),
            color=DEATH_COLOUR,
            kind=KIND_DEATH,
        )
        self.store.add(waypoint)
        self._announce("\u5df2\u8bb0\u5f55\u6b7b\u4ea1\u70b9")
        return waypoint

    # -- sharing --------------------------------------------------------

    def share(self, waypoint_id):
        waypoint = self.by_id(waypoint_id)
        if waypoint is None:
            return False
        line = self.store.export_share_text(waypoint, modConfig.CHAT_PREFIX)
        self.client.notify_server(ev.EV_SHARE_WAYPOINT, {"waypoint": waypoint.to_dict()})
        self._announce(line)
        return True

    def on_shared(self, args):
        """The server relayed waypoints other players shared."""
        payload = (args or {}).get("shared")
        if not isinstance(payload, list):
            return
        for data in payload:
            if isinstance(data, dict) and data.get("id"):
                self.shared[data["id"]] = data
        info("shared waypoints: %d", len(self.shared))

    def on_chat_share(self, text):
        """A chat line that looks like a shared waypoint; ``[Add]`` it."""
        parsed = WaypointStore.parse_share_text(text, modConfig.CHAT_PREFIX)
        if parsed is None:
            return None
        self.shared[parsed.id] = parsed.to_dict()
        self._announce("\u5df2\u6dfb\u52a0\u5171\u4eab\u8def\u5f84\u70b9 %s" % parsed.name)
        return parsed

    def import_shared(self, waypoint_id):
        """Copy a shared waypoint into the local, saved store."""
        waypoint = self.by_id(waypoint_id)
        if waypoint is None:
            return None
        copy = waypoint.copy(id=None)
        copy.name = "%s (copy)" % waypoint.name
        copy.initials = Waypoint(name=copy.name).initials
        self.store.add(copy)
        return copy

    # -- formatting helpers ---------------------------------------------

    def bearing_to(self, waypoint):
        pos = self.client.player_position()
        if pos is None or waypoint is None:
            return None
        degrees = bearing.bearing_degrees(
            (pos[0], pos[2]), (waypoint.x + 0.5, waypoint.z + 0.5)
        )
        return degrees

    def describe(self, waypoint):
        if waypoint is None:
            return ""
        pos = self.client.player_position()
        if pos is None:
            return "%s (%s)" % (waypoint.name, waypoint.coordinate_text())
        distance = waypoint.distance_to(pos[0] + 0.5, pos[1], pos[2] + 0.5)
        degrees = self.bearing_to(waypoint)
        return "%s  %s  %s %s  %s" % (
            waypoint.name,
            waypoint.coordinate_text(),
            bearing.bearing_arrow(degrees),
            bearing.bearing_label(degrees),
            bearing.format_distance(distance),
        )

    def dimension_label(self, waypoint):
        return dimension_name(waypoint.dimension)

    def _announce(self, text):
        self.client.notice(text)

    def stats(self):
        return {
            "local": self.store.count(),
            "shared": len(self.shared),
            "damaged": self.store.damaged,
        }

    def on_dimension_change(self, args=None):
        """Markers are per dimension; drop the cache so the next draw rebuilds."""
        self._selected = None
