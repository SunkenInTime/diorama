# -*- coding: utf-8 -*-
"""How each block looks to the baker: kind, face colours, biome tint.

A trimmed, hand-written port of the Fabric mod's ``BlockLooks``. The Java class
indexes by numeric block-state id from the game's registry; the China Edition
ModSDK only hands out ``(name, aux)`` pairs (see
``modServer/engine/block_source.py``), so this table is keyed by identifier
instead.

Both Bedrock/China identifiers (``minecraft:grass``, ``minecraft:log``) and the
Java names the Fabric mod uses (``minecraft:grass_block``, ``minecraft:oak_log``)
are accepted, so fixtures written against either naming work.

Contents:

* :data:`KIND_*` - what the ray does when it meets the block.
* :data:`TINT_*` - which biome tint to multiply in, if any.
* :class:`BlockLook` - one row of the table.
* :func:`lookup` - table lookup with aux-value support and a safe default.
* :data:`VERIFY_NAMES` - names the table is expected to know; the validator and
  the unit tests assert these resolve, which catches typos in the table.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

# -- kinds -----------------------------------------------------------------

KIND_AIR = 0
KIND_SOLID = 1
KIND_WATER = 2
KIND_LAVA = 3
KIND_PLANT = 4
KIND_GLASS = 5

# -- tints -----------------------------------------------------------------

TINT_NONE = 0
TINT_GRASS = 1
TINT_FOLIAGE = 2
TINT_DRY_FOLIAGE = 3
TINT_WATER = 4

#: Fallback biome tints (temperate), used when the chunk has no biome data.
DEFAULT_TINTS = {
    TINT_GRASS: (0x91, 0xBD, 0x59),
    TINT_FOLIAGE: (0x77, 0xAB, 0x2F),
    TINT_DRY_FOLIAGE: (0xAE, 0xA4, 0x2A),
    TINT_WATER: (0x3F, 0x76, 0xE4),
}


class BlockLook(object):
    """One block's appearance.

    Colours may be given as ``0xRRGGBB`` ints or as ``(r, g, b)`` tuples; both
    are normalised here so the table below reads either way.
    """

    __slots__ = ("kind", "top", "side", "tint", "tint_weight")

    def __init__(self, kind, top, side=None, tint=TINT_NONE, tint_weight=0.0):
        self.kind = kind
        self.top = _rgb(top)
        self.side = self.top if side is None else _rgb(side)
        self.tint = tint
        self.tint_weight = float(tint_weight)

    def __repr__(self):
        return "BlockLook(kind=%r, top=%r, side=%r, tint=%r, tint_weight=%r)" % (
            self.kind,
            self.top,
            self.side,
            self.tint,
            self.tint_weight,
        )


def _rgb(value):
    if isinstance(value, (tuple, list)):
        return (int(value[0]) & 0xFF, int(value[1]) & 0xFF, int(value[2]) & 0xFF)
    value = int(value)
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF)


def _solid(top, side=None, tint=TINT_NONE, tint_weight=0.0):
    return BlockLook(KIND_SOLID, top, side, tint, tint_weight)


#: Block id -> look. Aux 0 entries live here.
TABLE = {
    # terrain
    "minecraft:air": BlockLook(KIND_AIR, (0, 0, 0)),
    "minecraft:stone": _solid(0x7D7D7D, 0x757575),
    "minecraft:cobblestone": _solid(0x7A7A7A, 0x6E6E6E),
    "minecraft:mossy_cobblestone": _solid(0x6C7A5A, 0x5F6B4E),
    "minecraft:stonebrick": _solid(0x7B7B7B, 0x737373),
    "minecraft:dirt": _solid(0x8B6B4A, 0x7A5C3E),
    "minecraft:grass": _solid(0x7FA84C, 0x8B6B4A, TINT_GRASS, 0.85),
    "minecraft:grass_block": _solid(0x7FA84C, 0x8B6B4A, TINT_GRASS, 0.85),
    "minecraft:grass_path": _solid(0x94794B, 0x8B6B4A),
    "minecraft:farmland": _solid(0x6B4F33, 0x6B4F33),
    "minecraft:sand": _solid(0xDBD3A0, 0xD3C88F),
    "minecraft:sandstone": _solid(0xD9D0A3, 0xD5CB9A),
    "minecraft:red_sand": _solid(0xBF6B2B, 0xB4631F),
    "minecraft:gravel": _solid(0x8A8483, 0x837D7C),
    "minecraft:clay": _solid(0xA4A8B8, 0x9CA0B0),
    "minecraft:bedrock": _solid(0x555555, 0x4C4C4C),
    "minecraft:obsidian": _solid(0x15111B, 0x120F18),
    "minecraft:netherrack": _solid(0x6F3634, 0x672F2D),
    "minecraft:soul_sand": _solid(0x51403A, 0x4A3A34),
    "minecraft:end_stone": _solid(0xDBDEA3, 0xD3D69A),
    "minecraft:snow": _solid(0xF0F5F8, 0xE6ECF0),
    "minecraft:snow_layer": _solid(0xF0F5F8, 0xE6ECF0),
    "minecraft:ice": BlockLook(KIND_GLASS, (0x99B9F0), (0x8EB0EC)),
    "minecraft:packed_ice": _solid(0x8FB6F0, 0x86ACEA),
    # ores and metal blocks
    "minecraft:coal_ore": _solid(0x747474, 0x6C6C6C),
    "minecraft:iron_ore": _solid(0x9A8F83, 0x91867A),
    "minecraft:gold_ore": _solid(0xA79449, 0x9E8B41),
    "minecraft:diamond_ore": _solid(0x8BBEC3, 0x82B4B9),
    "minecraft:emerald_ore": _solid(0x6FA96A, 0x66A061),
    "minecraft:redstone_ore": _solid(0x8C6B6B, 0x836262),
    "minecraft:lapis_ore": _solid(0x6E7B9B, 0x667292),
    "minecraft:iron_block": _solid(0xD8D8D8, 0xCFCFCF),
    "minecraft:gold_block": _solid(0xF6D84C, 0xEFD043),
    "minecraft:diamond_block": _solid(0x5FE3DA, 0x56DAD1),
    "minecraft:emerald_block": _solid(0x3FD46A, 0x37CC62),
    "minecraft:brick_block": _solid(0x9A5243, 0x924B3C),
    "minecraft:bricks": _solid(0x9A5243, 0x924B3C),
    "minecraft:hardened_clay": _solid(0x9C6C50, 0x946448),
    "minecraft:stained_hardened_clay": _solid(0x9C6C50, 0x946448),
    "minecraft:terracotta": _solid(0x9C6C50, 0x946448),
    # wood and plants (aux selects the species on Bedrock)
    "minecraft:log": _solid(0x8C7346, 0x6B5636, TINT_FOLIAGE, 0.15),
    "minecraft:log2": _solid(0x8C7346, 0x6B5636, TINT_FOLIAGE, 0.15),
    "minecraft:oak_log": _solid(0x8C7346, 0x6B5636, TINT_FOLIAGE, 0.15),
    "minecraft:birch_log": _solid(0xD8D4C8, 0xC8C3B4),
    "minecraft:spruce_log": _solid(0x6B4F2A, 0x533D20),
    "minecraft:jungle_log": _solid(0x9A7B4F, 0x7A6039),
    "minecraft:acacia_log": _solid(0x9C6B4A, 0x7C5439),
    "minecraft:dark_oak_log": _solid(0x503A24, 0x3E2D1C),
    "minecraft:planks": _solid(0xB08B4F, 0xA5824A),
    "minecraft:oak_planks": _solid(0xB08B4F, 0xA5824A),
    "minecraft:leaves": BlockLook(KIND_PLANT, (0x5BA03C), (0x4F8F34), TINT_FOLIAGE, 0.75),
    "minecraft:leaves2": BlockLook(KIND_PLANT, (0x5BA03C), (0x4F8F34), TINT_FOLIAGE, 0.75),
    "minecraft:oak_leaves": BlockLook(KIND_PLANT, (0x5BA03C), (0x4F8F34), TINT_FOLIAGE, 0.75),
    "minecraft:birch_leaves": BlockLook(KIND_PLANT, (0x6FAE4B), (0x64A043), TINT_FOLIAGE, 0.6),
    "minecraft:spruce_leaves": BlockLook(KIND_PLANT, (0x3F6B44), (0x365E3C), TINT_FOLIAGE, 0.4),
    "minecraft:tallgrass": BlockLook(KIND_PLANT, (0x7BA84C), tint=TINT_GRASS, tint_weight=0.85),
    "minecraft:double_plant": BlockLook(KIND_PLANT, (0x7BA84C), tint=TINT_GRASS, tint_weight=0.85),
    "minecraft:yellow_flower": BlockLook(KIND_PLANT, (0xE0C33C)),
    "minecraft:red_flower": BlockLook(KIND_PLANT, (0xC85A4A)),
    "minecraft:waterlily": BlockLook(KIND_PLANT, (0x4E8F3A)),
    "minecraft:reeds": BlockLook(KIND_PLANT, (0x8FB25C)),
    "minecraft:cactus": _solid(0x5B8C3A, 0x527F34),
    "minecraft:pumpkin": _solid(0xD98B29, 0xC97E24),
    "minecraft:melon_block": _solid(0x8FB03C, 0x84A336),
    "minecraft:hay_block": _solid(0xC9A422, 0xB8951D),
    "minecraft:bookshelf": _solid(0xB08B4F, 0x8C6C3C),
    "minecraft:crafting_table": _solid(0xA5824A, 0x8C6C3C),
    "minecraft:furnace": _solid(0x7A7A7A, 0x6E6E6E),
    "minecraft:chest": _solid(0xA5824A, 0x8C6C3C),
    # liquids
    "minecraft:water": BlockLook(KIND_WATER, (0x3F76E4), (0x3559D0), TINT_WATER, 0.6),
    "minecraft:flowing_water": BlockLook(KIND_WATER, (0x3F76E4), (0x3559D0), TINT_WATER, 0.6),
    "minecraft:lava": BlockLook(KIND_LAVA, (0xE06A18), (0xD05F12)),
    "minecraft:flowing_lava": BlockLook(KIND_LAVA, (0xE06A18), (0xD05F12)),
    # glass-likes
    "minecraft:glass": BlockLook(KIND_GLASS, (0xBFD9E8), (0xB2D0E2)),
    "minecraft:stained_glass": BlockLook(KIND_GLASS, (0xBFD9E8), (0xB2D0E2)),
    "minecraft:glass_pane": BlockLook(KIND_GLASS, (0xBFD9E8), (0xB2D0E2)),
    "minecraft:stained_glass_pane": BlockLook(KIND_GLASS, (0xBFD9E8), (0xB2D0E2)),
    "minecraft:torch": BlockLook(KIND_PLANT, (0xF0C64A)),
}

#: (name, aux) -> look, for Bedrock blocks whose aux selects a variant.
AUX_TABLE = {
    ("minecraft:stained_hardened_clay", 0): _solid(0xF0F0F0),
    ("minecraft:stained_hardened_clay", 1): _solid(0xE8A13A),
    ("minecraft:stained_hardened_clay", 2): _solid(0xB05FA6),
    ("minecraft:stained_hardened_clay", 3): _solid(0x6E8FC4),
    ("minecraft:stained_hardened_clay", 4): _solid(0xD8C53C),
    ("minecraft:stained_hardened_clay", 5): _solid(0x7CC45C),
    ("minecraft:stained_hardened_clay", 6): _solid(0xE090A8),
    ("minecraft:stained_hardened_clay", 7): _solid(0x54585C),
    ("minecraft:stained_hardened_clay", 8): _solid(0x9AA0A4),
    ("minecraft:stained_hardened_clay", 9): _solid(0x4E9A9A),
    ("minecraft:stained_hardened_clay", 10): _solid(0x7A4FA6),
    ("minecraft:stained_hardened_clay", 11): _solid(0x3C4FA6),
    ("minecraft:stained_hardened_clay", 12): _solid(0x6B5236),
    ("minecraft:stained_hardened_clay", 13): _solid(0x5C6B36),
    ("minecraft:stained_hardened_clay", 14): _solid(0xA84C4C),
    ("minecraft:stained_hardened_clay", 15): _solid(0x2B2B2B),
    # Bedrock leaves/logs carry the species in aux.
    ("minecraft:leaves", 0): BlockLook(KIND_PLANT, (0x5BA03C), (0x4F8F34), TINT_FOLIAGE, 0.75),
    ("minecraft:leaves", 1): BlockLook(KIND_PLANT, (0x3F6B44), (0x365E3C), TINT_FOLIAGE, 0.4),
    ("minecraft:leaves", 2): BlockLook(KIND_PLANT, (0x6FAE4B), (0x64A043), TINT_FOLIAGE, 0.6),
    ("minecraft:leaves", 3): BlockLook(KIND_PLANT, (0x5BA03C), (0x4F8F34), TINT_FOLIAGE, 0.7),
    ("minecraft:log", 0): _solid(0x8C7346, 0x6B5636, TINT_FOLIAGE, 0.15),
    ("minecraft:log", 1): _solid(0x6B4F2A, 0x533D20),
    ("minecraft:log", 2): _solid(0xD8D4C8, 0xC8C3B4),
    ("minecraft:log", 3): _solid(0x9A7B4F, 0x7A6039),
    ("minecraft:planks", 0): _solid(0xB08B4F, 0xA5824A),
    ("minecraft:planks", 1): _solid(0x6B4F2A, 0x533D20),
    ("minecraft:planks", 2): _solid(0xD8D4C8, 0xC8C3B4),
    ("minecraft:planks", 3): _solid(0x9A7B4F, 0x7A6039),
}

#: Java name -> Bedrock/China name, so fixtures can use either.
ALIASES = {
    "minecraft:grass_block": "minecraft:grass",
    "minecraft:bricks": "minecraft:brick_block",
    "minecraft:oak_log": "minecraft:log",
    "minecraft:oak_leaves": "minecraft:leaves",
    "minecraft:terracotta": "minecraft:hardened_clay",
    "minecraft:white_terracotta": "minecraft:stained_hardened_clay",
}

#: The default used for a solid block the table does not know. Catch-all grey:
#: wrong but readable, and ``lookup`` can be asked to report the miss instead.
UNKNOWN_SOLID = _solid(0x8A8A8A, 0x828282)

#: A small set the tests and the validator assert on, to catch table typos.
VERIFY_NAMES = (
    "minecraft:stone",
    "minecraft:dirt",
    "minecraft:grass",
    "minecraft:water",
    "minecraft:lava",
    "minecraft:sand",
    "minecraft:glass",
    "minecraft:leaves",
    "minecraft:log",
    "minecraft:snow",
    "minecraft:bedrock",
)


def lookup(name, aux=0, miss=None):
    """The :class:`BlockLook` for an identifier.

    ``name`` may be ``None`` (unknown chunk) in which case the caller must not
    call this at all; the baker checks for ``None`` first.

    ``miss`` is an optional set or list-like with ``add``; every identifier the
    table does not know is recorded there once, for the ``/diorama palette``
    debugging command and for the test that tracks table coverage.
    """
    if not name:
        return BlockLook(KIND_AIR, (0, 0, 0))
    key = name
    if aux:
        hit = AUX_TABLE.get((key, int(aux)))
        if hit is not None:
            return hit
    hit = TABLE.get(key)
    if hit is not None:
        return hit
    alias = ALIASES.get(key)
    if alias is not None:
        if aux:
            hit = AUX_TABLE.get((alias, int(aux)))
            if hit is not None:
                return hit
        hit = TABLE.get(alias)
        if hit is not None:
            return hit
    if miss is not None:
        try:
            miss.add(key)
        except AttributeError:
            pass
    return UNKNOWN_SOLID


def is_known(name):
    """True when :func:`lookup` would find a real row (used by the validator)."""
    if not name:
        return False
    return name in TABLE or name in ALIASES
