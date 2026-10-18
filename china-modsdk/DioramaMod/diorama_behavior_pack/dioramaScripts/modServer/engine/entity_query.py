# -*- coding: utf-8 -*-
"""Entity scanning for the radar, over the ModSDK game/entity components.

The radar runs on the server because that is where the documented, complete
entity query is: ``CreateGame(levelId).GetEntitiesInSquareArea``. The client
only sees entities it has loaded, and the ModSDK does not document a client-side
area query, so the server classifies and projects the blips and sends plain
dicts (see ``eventConfig.EV_RADAR``).

Every entity attribute is read through a component created with
``CreateXxx(entityId)``; each call is wrapped so a dead or exotic entity cannot
take the scan down.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from dioramaScripts.modCommon.log import warn


def _call(obj, name, *args):
    if obj is None:
        return None
    method = getattr(obj, name, None)
    if method is None:
        return None
    try:
        return method(*args)
    except Exception:  # noqa: BLE001
        return None


class EntityQuery(object):
    """Reads entity positions/types for one level.

    ``api`` is ``mod.server.extraServerApi``; passing it in keeps this file
    importable (and therefore unit-testable) outside the engine.
    """

    def __init__(self, api, level_id, dimension_id=0):
        self._api = api
        self.level_id = level_id
        self.dimension_id = int(dimension_id)
        factory = getattr(api, "GetEngineCompFactory", None)
        self._factory = factory() if factory is not None else None
        self._game = _call(self._factory, "CreateGame", level_id) if self._factory else None
        self._area_supported = self._game is not None

    # -- queries -------------------------------------------------------

    def ids_in_area(self, centre, radius, entity_id=None):
        """Entity ids within ``radius`` blocks of ``centre`` (x, y, z).

        Tries the documented 3-argument form first; some builds want the
        entity id as the first argument, so that is the fallback.
        """
        if not self._area_supported:
            return []
        cx, cy, cz = centre
        start = (cx - radius, cy - radius, cz - radius)
        end = (cx + radius, cy + radius, cz + radius)
        ids = _call(self._game, "GetEntitiesInSquareArea", entity_id, start, end)
        if ids is None:
            ids = _call(self._game, "GetEntitiesInSquareArea", start, end)
        if ids is None:
            self._area_supported = False
            warn("GetEntitiesInSquareArea unavailable; radar disabled")
            return []
        if isinstance(ids, dict):
            ids = list(ids.keys())
        return list(ids or [])

    def describe(self, entity_id):
        """A plain dict for one entity, or ``None`` when it cannot be read."""
        if not entity_id:
            return None
        foot = _call(_call(self._factory, "CreatePos", entity_id), "GetFootPos")
        if not foot or len(foot) < 3:
            return None
        engine_type = _call(_call(self._factory, "CreateEngineType", entity_id), "GetEngineTypeStr")
        alive = _call(_call(self._factory, "CreateEngineType", entity_id), "IsEntityAlive", entity_id)
        if alive is False:
            return None
        name = _call(_call(self._factory, "CreateName", entity_id), "GetName")
        rot = _call(_call(self._factory, "CreateRot", entity_id), "GetRot")
        return {
            "id": entity_id,
            "type": engine_type or "",
            "x": float(foot[0]),
            "y": float(foot[1]),
            "z": float(foot[2]),
            "name": name or engine_type or "",
            "player": engine_type == "minecraft:player",
            "yaw": float(rot[1]) if rot and len(rot) > 1 else 0.0,
            "dim": self.dimension_id,
        }

    def player_ids(self):
        getter = getattr(self._api, "GetPlayerList", None)
        if getter is None:
            return []
        try:
            return list(getter() or [])
        except Exception:  # noqa: BLE001
            return []

    def collect(self, centre, radius, entity_id=None):
        """Every readable entity in the area, local player excluded by the caller."""
        records = []
        for candidate in self.ids_in_area(centre, radius, entity_id):
            record = self.describe(candidate)
            if record is not None:
                records.append(record)
        return records
