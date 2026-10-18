# -*- coding: utf-8 -*-
"""Client-side block reads, for picks and for the local cave slice.

The client block component is documented as::

    comp = clientApi.GetEngineCompFactory().CreateBlockInfo(levelId)
    comp.GetBlock((x, y, z))       # -> (name, aux) for the current dimension

which is enough for everything Diorama does on the client: reading the few
blocks the HUD needs for the "what am I standing on" line and for a local pick
when the server has not answered yet. It is *not* enough to bake a tile at a
useful speed, which is why runtime baking is a server/workbench concern.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from dioramaScripts.modCommon.log import warn
from dioramaScripts.modCore.block_source import AIR, NO_ROOF, BlockSource


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


class ClientBlockSource(BlockSource):
    """Blocks from the client's loaded chunks."""

    def __init__(self, level_id, api=None, min_y=-64, max_y=320, roof=NO_ROOF, top_y=255):
        if api is None:
            import mod.client.extraClientApi as api  # noqa: F401 - deferred import
        self._api = api
        self.level_id = level_id
        self.min_y = int(min_y)
        self.max_y = int(max_y)
        self.roof = roof
        self.top_y = int(top_y)
        factory = getattr(api, "GetEngineCompFactory", None)
        self._block_info = _call(factory(), "CreateBlockInfo", level_id) if factory else None
        self._cache = {}
        if self._block_info is None:
            warn("client CreateBlockInfo unavailable")

    def state(self, x, y, z):
        if y < self.min_y or y >= self.max_y:
            return AIR
        if self._block_info is None:
            return None
        cell = (x, y, z)
        if cell in self._cache:
            return self._cache[cell]
        value = _call(self._block_info, "GetBlock", cell)
        if value is None:
            return None
        if isinstance(value, (list, tuple)) and value:
            name = value[0]
            aux = int(value[1]) if len(value) > 1 and value[1] is not None else 0
            result = (name, aux)
        elif isinstance(value, dict):
            result = (value.get("name"), int(value.get("aux", 0) or 0))
        else:
            result = (value, 0)
        if len(self._cache) > 65536:
            self._cache.clear()
        self._cache[cell] = result
        return result


class ClientPlayer(object):
    """Position, rotation and dimension of the local player, guarded."""

    def __init__(self, api, player_id):
        self._api = api
        self.player_id = player_id
        factory = getattr(api, "GetEngineCompFactory", None)
        self._factory = factory() if factory is not None else None

    def foot_pos(self):
        comp = _call(self._factory, "CreatePos", self.player_id)
        pos = _call(comp, "GetFootPos")
        if pos and len(pos) >= 3:
            return (float(pos[0]), float(pos[1]), float(pos[2]))
        return None

    def rotation(self):
        comp = _call(self._factory, "CreateRot", self.player_id)
        rot = _call(comp, "GetRot")
        if rot and len(rot) >= 2:
            return (float(rot[0]), float(rot[1]))
        return (0.0, 0.0)

    def yaw(self):
        return self.rotation()[1]

    def bearing(self):
        """Compass bearing in degrees, 0 = north, 90 = east."""
        return (self.yaw() + 180.0) % 360.0

    def dimension_id(self):
        comp = _call(self._factory, "CreateDimension", self.player_id)
        for method in ("GetEntityDimensionId", "GetDimensionId"):
            value = _call(comp, method)
            if value is not None:
                try:
                    return int(value)
                except (TypeError, ValueError):
                    pass
        return 0

    def biome_name(self):
        comp = _call(self._factory, "CreateBiome", self.player_id)
        for method in ("GetBiomeName", "GetBiome"):
            value = _call(comp, method)
            if value:
                return "%s" % (value,)
        return ""
