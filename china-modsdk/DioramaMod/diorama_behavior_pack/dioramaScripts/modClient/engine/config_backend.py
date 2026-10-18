# -*- coding: utf-8 -*-
"""A ``modCore.waypoint_store`` backend over the ModSDK local config component.

The China Edition has no filesystem for scripts, but it does have a documented
per-player persistent blob::

    comp = clientApi.GetEngineCompFactory().CreateConfig(localPlayerId)
    comp.SetConfigData(name, value)
    comp.GetConfigData(name)

Storing the whole waypoint JSON under one key keeps ``WaypointStore`` unchanged
from the Java port - including the per-entry tolerance and the "copy the
unreadable text aside before overwriting it" rule, which here means writing the
damaged text to a sibling key instead of a sibling file.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from dioramaScripts.modCommon.log import warn


class ConfigCompBackend(object):
    """Reads and writes one ``CreateConfig`` key."""

    def __init__(self, comp, key):
        self.comp = comp
        self.key = key
        self.last_backup = None

    def read(self):
        if self.comp is None:
            return ""
        try:
            value = self.comp.GetConfigData(self.key)
        except Exception as exc:  # noqa: BLE001
            warn("GetConfigData(%s) failed: %r", self.key, exc)
            return ""
        if isinstance(value, str):
            return value
        if value is None:
            return ""
        # Older builds happily store non-strings; coerce rather than crash.
        return "%s" % (value,)

    def write(self, text):
        if self.comp is None:
            return False
        try:
            self.comp.SetConfigData(self.key, text)
            return True
        except Exception as exc:  # noqa: BLE001 - a full config must not kill the HUD
            warn("SetConfigData(%s) failed: %r", self.key, exc)
            return False

    def backup(self, suffix):
        """Keep the unreadable text under ``<key>.broken.<suffix>``."""
        if self.comp is None:
            return None
        try:
            text = self.comp.GetConfigData(self.key)
        except Exception:  # noqa: BLE001
            return None
        if text is None:
            return None
        backup_key = "%s.broken.%s" % (self.key, suffix)
        try:
            self.comp.SetConfigData(backup_key, text)
            self.last_backup = backup_key
            return backup_key
        except Exception:  # noqa: BLE001
            return None


def make_backend(api, entity_id, key):
    """Build a backend, or a memory backend when the component is missing."""
    from dioramaScripts.modCore.waypoint_store import MemoryBackend

    try:
        factory = api.GetEngineCompFactory()
        comp = factory.CreateConfig(entity_id)
        return ConfigCompBackend(comp, key)
    except Exception as exc:  # noqa: BLE001
        warn("CreateConfig unavailable; settings will not persist: %r", exc)
        return MemoryBackend()
