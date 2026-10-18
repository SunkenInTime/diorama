# -*- coding: utf-8 -*-
"""Waypoint persistence, ported from the Fabric mod's ``WaypointStore.java``.

The store holds the waypoints of one world and saves them as JSON. The Java
version writes ``.minecraft/diorama/<world>/waypoints.json``; the China Edition
ModSDK has no filesystem access at all, so the JSON text is handed to a
*backend* instead:

* :class:`MemoryBackend` - tests.
* :class:`FileBackend` - the PC workbench, which writes real files so the same
  store code can be exercised outside the game.
* ``ConfigCompBackend`` (in ``modClient/engine/config_backend.py``) - the game,
  over ``CreateConfig(localPlayerId).GetConfigData`` / ``SetConfigData``.

Robustness rules, all ported from the Java version because they are the reason
it survives hand-edited files:

* a single unreadable entry costs only itself;
* if anything could not be read, the raw text is copied aside before the next
  save, so whatever was lost is still recoverable;
* writes go to a temporary name and are moved over the old one, so a crash
  cannot leave half a file;
* death markers are capped at :data:`modCore.waypoint.DEATHS_KEPT`.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import json
import os
import time

from .waypoint import DEATHS_KEPT, KIND_DEATH, Waypoint

#: The key a ConfigCompBackend uses; also the file stem for FileBackend.
STORE_NAME = "diorama.waypoints"


class MemoryBackend(object):
    """A backend that keeps the JSON text in memory."""

    def __init__(self, text=""):
        self.text = text or ""

    def read(self):
        return self.text

    def write(self, text):
        self.text = text
        return True

    def backup(self, suffix):
        """Remember the unreadable text under a side key, like the Java copy."""
        self.text = self.text  # keep it available for inspection
        self.last_backup = (suffix, self.text)
        return True


class FileBackend(object):
    """A backend backed by one file, with an atomic replace on write."""

    def __init__(self, path):
        self.path = path
        self.last_backup = None

    def read(self):
        if not os.path.isfile(self.path):
            return ""
        handle = open(self.path, "rb")
        try:
            data = handle.read()
        finally:
            handle.close()
        if isinstance(data, bytes):
            data = data.decode("utf-8")
        return data

    def write(self, text):
        directory = os.path.dirname(self.path)
        if directory and not os.path.isdir(directory):
            os.makedirs(directory)
        if isinstance(text, bytes):
            text = text.decode("utf-8")
        temp = self.path + ".tmp"
        handle = open(temp, "wb")
        try:
            handle.write(text.encode("utf-8"))
        finally:
            handle.close()
        replace = getattr(os, "replace", None)
        if replace is not None:
            replace(temp, self.path)
        else:  # Python 2
            if os.path.exists(self.path):
                os.remove(self.path)
            os.rename(temp, self.path)
        return True

    def backup(self, suffix):
        if not os.path.isfile(self.path):
            return None
        target = "%s.unreadable-%s" % (self.path, suffix)
        source = open(self.path, "rb")
        try:
            data = source.read()
        finally:
            source.close()
        target_handle = open(target, "wb")
        try:
            target_handle.write(data)
        finally:
            target_handle.close()
        self.last_backup = target
        return target


class WaypointStore(object):
    """The waypoints of one world, saved through a backend."""

    def __init__(self, backend, world_key="world"):
        self.backend = backend
        self.world_key = world_key
        self.waypoints = []
        self.damaged = False
        self.load()

    # -- reading -------------------------------------------------------

    def load(self):
        """Read the store; one bad entry costs only itself."""
        self.waypoints = []
        self.damaged = False
        try:
            text = self.backend.read()
        except Exception:  # noqa: BLE001 - a broken backend must not kill the mod
            self.damaged = True
            return self
        if not text:
            return self
        try:
            root = json.loads(text)
        except ValueError:
            self.damaged = True
            self._backup()
            return self
        if isinstance(root, dict):
            # A world file wrapped in metadata, or a ConfigComp value.
            root = root.get("waypoints", [])
        if not isinstance(root, list):
            self.damaged = True
            self._backup()
            return self
        for element in root:
            try:
                self.waypoints.append(Waypoint.from_dict(element))
            except (ValueError, TypeError, KeyError):
                self.damaged = True
        if self.damaged:
            self._backup()
        return self

    def _backup(self):
        try:
            self.backend.backup("%d" % int(time.time() * 1000))
        except Exception:  # noqa: BLE001
            pass

    # -- queries -------------------------------------------------------

    def all(self):
        return list(self.waypoints)

    def in_dimension(self, dimension):
        return [w for w in self.waypoints if w.dimension == dimension]

    def visible_in_dimension(self, dimension):
        return [w for w in self.waypoints if w.dimension == dimension and w.visible]

    def by_id(self, waypoint_id):
        for waypoint in self.waypoints:
            if waypoint.id == waypoint_id:
                return waypoint
        return None

    def nearest(self, x, y, z, dimension=None, maximum=None):
        best = None
        best_distance = None
        for waypoint in self.waypoints:
            if dimension is not None and waypoint.dimension != dimension:
                continue
            distance = waypoint.distance_to(x, y, z)
            if maximum is not None and distance > maximum:
                continue
            if best_distance is None or distance < best_distance:
                best, best_distance = waypoint, distance
        return best

    def count(self):
        return len(self.waypoints)

    # -- writing -------------------------------------------------------

    def add(self, waypoint):
        self.waypoints.append(waypoint)
        if waypoint.kind == KIND_DEATH:
            self._trim_deaths()
        self.save()
        return waypoint

    def remove(self, waypoint):
        if waypoint in self.waypoints:
            self.waypoints.remove(waypoint)
            self.save()
            return True
        return False

    def remove_by_id(self, waypoint_id):
        waypoint = self.by_id(waypoint_id)
        return self.remove(waypoint) if waypoint is not None else False

    def changed(self):
        self.save()

    def replace_all(self, waypoints):
        self.waypoints = list(waypoints)
        self._trim_deaths()
        self.save()

    def _trim_deaths(self):
        deaths = sorted(
            [w for w in self.waypoints if w.kind == KIND_DEATH], key=lambda w: w.created
        )
        while len(deaths) > DEATHS_KEPT:
            oldest = deaths.pop(0)
            self.waypoints.remove(oldest)

    def to_json(self):
        return json.dumps([w.to_dict() for w in self.waypoints], ensure_ascii=False, indent=1)

    def save(self):
        try:
            return self.backend.write(self.to_json())
        except Exception:  # noqa: BLE001 - a full disk must not kill the mod
            return False

    # -- sharing -------------------------------------------------------

    def export_share_text(self, waypoint, prefix="[Diorama]"):
        """The chat line one player sends to share a waypoint."""
        return "%s %s @ %d %d %d dim=%d color=%d" % (
            prefix,
            waypoint.name,
            waypoint.x,
            waypoint.y,
            waypoint.z,
            waypoint.dimension,
            waypoint.color,
        )

    @staticmethod
    def parse_share_text(text, prefix="[Diorama]"):
        """Parse a shared line back into a :class:`Waypoint`, or ``None``.

        Layout: ``[Diorama] <name> @ <x> <y> <z> dim=<d> color=<c>``. The name
        may contain spaces, so the position is found by splitting on the last
        ``@``; bare integers in the tail are coordinates, ``key=value`` tokens
        are metadata.
        """
        if not text or "@" not in text:
            return None
        if prefix and prefix in text:
            text = text.split(prefix, 1)[1]
        name, rest = text.rsplit("@", 1)
        name = name.strip()
        if name.lower().startswith("diorama"):
            name = name[len("Diorama") :].strip()
        name = name or "Shared"
        coords = []
        dimension = 0
        color = None
        for token in rest.split():
            if "=" in token:
                key, _, value = token.partition("=")
                try:
                    number = int(value)
                except ValueError:
                    continue
                if key == "dim":
                    dimension = number
                elif key == "color":
                    color = number
            else:
                try:
                    coords.append(int(token))
                except ValueError:
                    pass
        if len(coords) < 3:
            return None
        return Waypoint(
            name=name,
            x=coords[0],
            y=coords[1],
            z=coords[2],
            dimension=dimension,
            color=color,
        )
