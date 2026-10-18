# -*- coding: utf-8 -*-
"""A :class:`modCore.block_source.BlockSource` over the ModSDK block component.

Uses the documented server-side call::

    comp = serverApi.GetEngineCompFactory().CreateBlockInfo(levelId)
    comp.GetBlockNew((x, y, z), dimensionId)   # -> {"name": ..., "aux": ...} or None

``None`` means the chunk is not loaded, which the baker treats as "no data" and
turns into fog rather than into a guessed block.

Speed: the baker probes the same cells many times (a ray, its neighbours for
ambient occlusion, then the shadow ray), so this adapter caches aggressively and
remembers chunks that came back unloaded so the second probe is a set lookup.

See ``docs/API_ASSUMPTIONS.md``: ``GetBlockLight`` / ``GetBlockSkyLight`` and
``GetTopBlockHeight`` are used through ``getattr`` because their presence in the
China Edition build this targets is not verified.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from dioramaScripts.modCommon.log import warn
from dioramaScripts.modCore import palette
from dioramaScripts.modCore.block_source import AIR, NO_ROOF, BlockSource

#: How many cells the block cache may hold before it is dropped wholesale.
CACHE_LIMIT = 200000
#: How many unloaded chunk columns to remember.
UNLOADED_LIMIT = 8192


def _call(obj, name, *args):
    """Call ``obj.name(*args)``, returning ``None`` when it is absent or fails."""
    method = getattr(obj, name, None)
    if method is None:
        return None
    try:
        return method(*args)
    except Exception:  # noqa: BLE001 - engine calls fail on unloaded chunks
        return None


class EngineBlockSource(BlockSource):
    """Blocks and light from one dimension, read through the engine."""

    def __init__(
        self,
        level_id,
        dimension_id=0,
        api=None,
        min_y=0,
        max_y=256,
        roof=NO_ROOF,
        top_y=255,
        use_top_height=True,
    ):
        if api is None:
            import mod.server.extraServerApi as api  # noqa: F401 - deferred import
        self._api = api
        self.level_id = level_id
        self.dimension_id = int(dimension_id)
        self.min_y = int(min_y)
        self.max_y = int(max_y)
        self.roof = roof
        self.top_y = int(top_y)
        self.use_top_height = bool(use_top_height)
        self._blocks = {}
        self._unloaded = set()
        self._top_cache = {}
        self._light_available = None
        self._sky_available = None
        factory = getattr(api, "GetEngineCompFactory", None)
        self._factory = factory() if factory is not None else None
        self._block_info = (
            _call(self._factory, "CreateBlockInfo", level_id) if self._factory is not None else None
        )
        if self._block_info is None:
            warn("CreateBlockInfo unavailable; the map will have no data")

    # -- blocks --------------------------------------------------------

    def state(self, x, y, z):
        if y < self.min_y or y >= self.max_y:
            return AIR
        if self._block_info is None:
            return None
        cell = (x, y, z)
        cached = self._blocks.get(cell)
        if cached is not None:
            return cached
        column = (x >> 4, z >> 4)
        if column in self._unloaded:
            return None
        data = _call(self._block_info, "GetBlockNew", cell, self.dimension_id)
        if not data:
            self._remember_unloaded(column)
            return None
        name = data.get("name") if hasattr(data, "get") else None
        if not name:
            self._remember_unloaded(column)
            return None
        value = (name, int(data.get("aux", 0) or 0))
        if len(self._blocks) >= CACHE_LIMIT:
            self._blocks.clear()
        self._blocks[cell] = value
        return value

    def _remember_unloaded(self, column):
        if len(self._unloaded) >= UNLOADED_LIMIT:
            self._unloaded.clear()
        self._unloaded.add(column)

    def top_at(self, x, z):
        """Highest non-air block in a column, or ``None`` when unknown.

        ``GetTopBlockHeight`` is a documented server API, but on a build that
        does not have it this returns ``None`` and the baker falls back to the
        global ``top_y`` - correct, just slower.
        """
        if not self.use_top_height or self._block_info is None:
            return None
        column = (x, z)
        if column in self._top_cache:
            return self._top_cache[column]
        height = _call(self._block_info, "GetTopBlockHeight", column, self.dimension_id)
        if height is None:
            self.use_top_height = False
            return None
        height = int(height)
        if len(self._top_cache) >= CACHE_LIMIT:
            self._top_cache.clear()
        self._top_cache[column] = height
        return height

    # -- light ---------------------------------------------------------

    def block_light(self, x, y, z):
        if self._block_info is None or y < self.min_y or y >= self.max_y:
            return None
        if self._light_available is False:
            return None
        value = _call(self._block_info, "GetBlockLight", (x, y, z), self.dimension_id)
        if value is None:
            self._light_available = False
            return None
        self._light_available = True
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    def sky_light(self, x, y, z):
        if self._block_info is None:
            return None
        if y >= self.max_y:
            return 15
        if y < self.min_y:
            return 0
        if self._sky_available is False:
            return None
        value = _call(self._block_info, "GetBlockSkyLight", (x, y, z), self.dimension_id)
        if value is None:
            value = _call(self._block_info, "GetSkyLight", (x, y, z), self.dimension_id)
        if value is None:
            self._sky_available = False
            return None
        self._sky_available = True
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    # -- misc ----------------------------------------------------------

    def is_known_block(self, name):
        return palette.is_known(name)

    def forget(self):
        """Drop the caches; call on a dimension change or when chunks reload."""
        self._blocks.clear()
        self._unloaded.clear()
        self._top_cache.clear()

    def debug_stats(self):
        return {
            "cached_blocks": len(self._blocks),
            "unloaded_columns": len(self._unloaded),
            "cached_columns": len(self._top_cache),
            "block_light": self._light_available,
            "sky_light": self._sky_available,
            "top_height": self.use_top_height,
        }
