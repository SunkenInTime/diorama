# -*- coding: utf-8 -*-
"""Radar: other players and mobs on the map and around the player.

A port of the Fabric mod's ``Radar.java`` split into two halves:

* classification and the blip maths live here, in ``modCore``, so they are
  testable on the PC workbench and identical on the client and the server;
* the entity scan itself lives in ``modServer/components/RadarServerComponent``,
  because the China Edition ModSDK only exposes entity queries on the server
  (the client sees entities it has loaded, but the documented, reliable query
  set is server-side - see ``docs/ARCHITECTURE.md``).

The server classifies and projects each entity into *map space* (``u``, ``v``
and a height offset relative to the viewer) and sends plain dicts to the
client; the client only has to turn map space into UI pixels. That keeps the
one piece of logic that must agree between the two ends - entity kind - in a
single table.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

KIND_PASSIVE = "passive"
KIND_NEUTRAL = "neutral"
KIND_HOSTILE = "hostile"
KIND_PLAYER = "player"

#: Frame colours, picked to stay readable on grass and water.
KIND_COLOURS = {
    KIND_PASSIVE: 0xFF62D96B,
    KIND_NEUTRAL: 0xFFF2C14E,
    KIND_HOSTILE: 0xFFEF4B4B,
    KIND_PLAYER: 0xFFFFFFFF,
}

#: Draw order: mobs first, players last, so a player is never hidden by a crowd.
KIND_ORDER = (KIND_PASSIVE, KIND_NEUTRAL, KIND_HOSTILE, KIND_PLAYER)

#: Vertical distance, in blocks, past which a caret shows.
CARET_HEIGHT = 3.0
#: Things this far above or below fade but stay readable.
FADE_START = 8.0
FADE_RANGE = 40.0
MIN_ALPHA = 0.45

#: Entity identifiers that attack on sight. Bedrock/China ids; a few Java
#: spellings are listed too so fixtures written for either work.
HOSTILE_TYPES = frozenset(
    [
        "minecraft:zombie",
        "minecraft:husk",
        "minecraft:drowned",
        "minecraft:zombie_villager",
        "minecraft:skeleton",
        "minecraft:stray",
        "minecraft:bogged",
        "minecraft:wither_skeleton",
        "minecraft:creeper",
        "minecraft:spider",
        "minecraft:cave_spider",
        "minecraft:witch",
        "minecraft:slime",
        "minecraft:magma_cube",
        "minecraft:blaze",
        "minecraft:ghast",
        "minecraft:silverfish",
        "minecraft:endermite",
        "minecraft:guardian",
        "minecraft:elder_guardian",
        "minecraft:shulker",
        "minecraft:phantom",
        "minecraft:pillager",
        "minecraft:vindicator",
        "minecraft:evocation_illager",
        "minecraft:ravager",
        "minecraft:vex",
        "minecraft:wither",
        "minecraft:ender_dragon",
        "minecraft:hoglin",
        "minecraft:zoglin",
        "minecraft:piglin_brute",
    ]
)

#: Entity identifiers that only attack when provoked. Checked before
#: :data:`HOSTILE_TYPES`, because endermen and zombified piglins tick both boxes.
NEUTRAL_TYPES = frozenset(
    [
        "minecraft:enderman",
        "minecraft:zombie_pigman",
        "minecraft:zombified_piglin",
        "minecraft:polar_bear",
        "minecraft:wolf",
        "minecraft:llama",
        "minecraft:trader_llama",
        "minecraft:panda",
        "minecraft:bee",
        "minecraft:iron_golem",
        "minecraft:snow_golem",
        "minecraft:dolphin",
        "minecraft:piglin",
        "minecraft:goat",
        "minecraft:camel",
    ]
)

#: Names that are never shown; projectiles, items and effects are not mobs.
IGNORED_TYPES = frozenset(
    [
        "minecraft:item",
        "minecraft:xp_orb",
        "minecraft:arrow",
        "minecraft:snowball",
        "minecraft:throwable",
        "minecraft:armor_stand",
        "minecraft:falling_block",
        "minecraft:tnt",
        "minecraft:boat",
        "minecraft:minecart",
        "minecraft:painting",
        "minecraft:leash_knot",
        "minecraft:fireworks_rocket",
        "minecraft:lightning_bolt",
        "minecraft:area_effect_cloud",
        "minecraft:evocation_fang",
        "minecraft:ender_pearl",
        "minecraft:eye_of_ender_signal",
    ]
)


class Blip(object):
    """One entity placed on the map.

    ``u``/``v`` are map-space coordinates (see :mod:`modCore.projection`),
    ``dy`` is the entity's height relative to the viewing player, and ``size``
    is a UI pixel size the client may adjust for zoom.
    """

    __slots__ = ("entity_id", "kind", "name", "u", "v", "dy", "distance", "size", "dimension")

    def __init__(self, entity_id, kind, name, u, v, dy, distance, size=4.0, dimension=0):
        self.entity_id = entity_id
        self.kind = kind
        self.name = name
        self.u = u
        self.v = v
        self.dy = dy
        self.distance = distance
        self.size = size
        self.dimension = dimension

    def to_dict(self):
        return {
            "id": self.entity_id,
            "kind": self.kind,
            "name": self.name,
            "u": round(self.u, 3),
            "v": round(self.v, 3),
            "dy": round(self.dy, 3),
            "dist": round(self.distance, 2),
            "size": round(self.size, 2),
            "dim": self.dimension,
        }

    @classmethod
    def from_dict(cls, data):
        return cls(
            data.get("id", ""),
            data.get("kind", KIND_PASSIVE),
            data.get("name", ""),
            float(data.get("u", 0.0)),
            float(data.get("v", 0.0)),
            float(data.get("dy", 0.0)),
            float(data.get("dist", 0.0)),
            float(data.get("size", 4.0)),
            int(data.get("dim", 0)),
        )

    def alpha(self):
        """Things far above or below fade, but never below a readable alpha."""
        fade = max(0.0, abs(self.dy) - FADE_START) / FADE_RANGE
        return max(MIN_ALPHA, 1.0 - fade)

    def has_caret(self):
        return abs(self.dy) > CARET_HEIGHT

    def __repr__(self):
        return "Blip(%r, %r, u=%r, v=%r, dy=%r)" % (
            self.entity_id,
            self.kind,
            self.u,
            self.v,
            self.dy,
        )


def classify(entity_type, is_player=False):
    """The radar kind of an entity identifier, or ``None`` to leave it off."""
    if is_player or entity_type == "minecraft:player":
        return KIND_PLAYER
    if not entity_type:
        return None
    if entity_type in IGNORED_TYPES:
        return None
    if entity_type in NEUTRAL_TYPES:
        return KIND_NEUTRAL
    if entity_type in HOSTILE_TYPES:
        return KIND_HOSTILE
    # Unknown mobs are shown as passive rather than dropped: a new China
    # Edition mob the table has not seen should still appear on the radar.
    return KIND_PASSIVE


def enabled(kind, settings):
    """Whether the player's settings show this kind of blip."""
    if kind == KIND_PLAYER:
        return bool(getattr(settings, "radar_players", True))
    if kind == KIND_HOSTILE:
        return bool(getattr(settings, "radar_hostile", True))
    if kind == KIND_NEUTRAL:
        return bool(getattr(settings, "radar_neutral", True))
    return bool(getattr(settings, "radar_passive", True))


def size_for(kind, zoom, fullscreen):
    """Blip size in UI pixels at a given zoom."""
    if kind == KIND_PLAYER:
        return max(8.0, min(16.0, zoom * 3.0)) if fullscreen else 7.0
    return max(4.0, min(9.0, zoom * 2.2)) if fullscreen else 4.0


def collect(
    entities,
    viewer,
    settings,
    projection,
    radar_range=None,
    fullscreen=False,
    zoom=1.0,
    margin=12.0,
    bounds=None,
):
    """Turn raw server entity records into sorted :class:`Blip` objects.

    ``entities`` is an iterable of dicts with ``id``, ``type``, ``x``, ``y``,
    ``z``, ``name`` and optionally ``player`` (bool) and ``dim``. ``viewer`` is
    the same shape for the local player plus a ``yaw``. ``bounds`` is an
    optional ``(u0, v0, u1, v1)`` filter in map space.
    """
    if radar_range is None:
        radar_range = getattr(settings, "radar_range", 32)
    viewer_y = float(viewer.get("y", 0.0))
    blips = []
    for entity in entities:
        entity_id = entity.get("id")
        if not entity_id or entity_id == viewer.get("id"):
            continue
        if entity.get("dead"):
            continue
        if entity.get("invisible"):
            continue
        kind = classify(entity.get("type"), bool(entity.get("player")))
        if kind is None or not enabled(kind, settings):
            continue
        dy = float(entity.get("y", 0.0)) - viewer_y
        if abs(dy) > radar_range:
            continue
        u = float(entity.get("x", 0.0))
        v = projection.v(float(entity.get("y", 0.0)), float(entity.get("z", 0.0)))
        if bounds is not None:
            if u < bounds[0] - margin or u > bounds[2] + margin:
                continue
            if v < bounds[1] - margin or v > bounds[3] + margin:
                continue
        dx = float(entity.get("x", 0.0)) - float(viewer.get("x", 0.0))
        dz = float(entity.get("z", 0.0)) - float(viewer.get("z", 0.0))
        distance = (dx * dx + dy * dy + dz * dz) ** 0.5
        blips.append(
            Blip(
                entity_id,
                kind,
                entity.get("name") or entity.get("type") or "",
                u,
                v,
                dy,
                distance,
                size_for(kind, zoom, fullscreen),
                int(entity.get("dim", viewer.get("dim", 0))),
            )
        )
    order = dict((kind, index) for index, kind in enumerate(KIND_ORDER))
    blips.sort(key=lambda blip: order.get(blip.kind, 99))
    return blips


def under(blips, u, v, slack=3.0):
    """The blip at a map-space point, preferring players and then the nearest."""
    best = None
    best_distance = None
    order = dict((kind, index) for index, kind in enumerate(KIND_ORDER))
    for blip in blips:
        dx = blip.u - u
        dy = blip.v - v
        distance = (dx * dx + dy * dy) ** 0.5
        if distance > blip.size / 2.0 + slack:
            continue
        if best is None:
            best, best_distance = blip, distance
            continue
        better_rank = order.get(blip.kind, 99) > order.get(best.kind, 99)
        same_rank = order.get(blip.kind, 99) == order.get(best.kind, 99)
        if better_rank or (same_rank and distance < best_distance):
            best, best_distance = blip, distance
    return best


def describe(blip, lang="zh"):
    """A one-line description for a tooltip, e.g. ``Zombie · 23m · 上方 6``."""
    if lang == "zh":
        height = ""
        if blip.dy > 0.5:
            height = " \u00b7 \u4e0a\u65b9 %d" % int(round(blip.dy))
        elif blip.dy < -0.5:
            height = " \u00b7 \u4e0b\u65b9 %d" % int(round(-blip.dy))
        return "%s \u00b7 %dm%s" % (blip.name, int(round(blip.distance)), height)
    height = ""
    if blip.dy > 0.5:
        height = " \u00b7 %d above" % int(round(blip.dy))
    elif blip.dy < -0.5:
        height = " \u00b7 %d below" % int(round(-blip.dy))
    return "%s \u00b7 %dm%s" % (blip.name, int(round(blip.distance)), height)
