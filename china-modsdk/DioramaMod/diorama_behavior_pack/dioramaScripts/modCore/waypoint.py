# -*- coding: utf-8 -*-
"""A marked place, ported from the Fabric mod's ``Waypoint.java``.

Kept deliberately dumb and JSON-friendly: the same dict shape is what the
client stores locally, what the server relays when one player shares a
waypoint in chat, and what the unit tests round-trip.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

import random
import time

try:  # Python 2 has a separate long type
    integer_types = (int, long)  # noqa: F821 - only defined on Python 2
except NameError:  # pragma: no cover - Python 3
    integer_types = (int,)

KIND_MANUAL = "manual"
KIND_DEATH = "death"

#: Colours a waypoint can take, chosen to stay readable on grass and water.
PALETTE = (
    0xFFE5484D,
    0xFFF76B15,
    0xFFFFC53D,
    0xFF46A758,
    0xFF12A594,
    0xFF0090FF,
    0xFF3E63DD,
    0xFF8E4EC6,
    0xFFD6409F,
    0xFFFFFFFF,
    0xFFB0B4BA,
    0xFF60646C,
    0xFF8D6B4B,
    0xFF99D52A,
    0xFF7CE2FE,
    0xFFF0C0D8,
)

DEATH_COLOUR = 0xFF60646C

#: How many death markers to keep per world; older ones drop as new ones come in.
DEATHS_KEPT = 3

#: Dimensions in the China Edition, as the integer ids the ModSDK uses.
DIMENSION_OVERWORLD = 0
DIMENSION_NETHER = 1
DIMENSION_END = 2

DIMENSION_NAMES = {
    DIMENSION_OVERWORLD: "minecraft:overworld",
    DIMENSION_NETHER: "minecraft:nether",
    DIMENSION_END: "minecraft:the_end",
}


def dimension_name(dimension_id):
    """``0`` -> ``minecraft:overworld``; unknown ids keep their number."""
    try:
        value = int(dimension_id)
    except (TypeError, ValueError):
        return "%s" % (dimension_id,)
    return DIMENSION_NAMES.get(value, "dimension:%d" % value)


def dimension_id(value, default=DIMENSION_OVERWORLD):
    """Accept either an int id or a name and return the int id."""
    if isinstance(value, integer_types):
        return int(value)
    text = "%s" % (value,)
    for key, name in DIMENSION_NAMES.items():
        if name == text:
            return key
    if text.startswith("dimension:"):
        try:
            return int(text.split(":", 1)[1])
        except (IndexError, ValueError):
            return default
    try:
        return int(text)
    except (TypeError, ValueError):
        return default


def initials_for(name):
    """First letter of up to two words: "Home" is H, "Iron Farm" is IF."""
    out = []
    for word in ("%s" % (name or "",)).split():
        if word and len(out) < 2:
            out.append(word[0].upper())
    return "".join(out) if out else "?"


def new_id():
    """A short unique id; ``uuid`` is importable in the ModSDK but this is smaller."""
    return "%08x%04x" % (int(time.time()) & 0xFFFFFFFF, random.randint(0, 0xFFFF))


class Waypoint(object):
    """One marked place; mutable so the edit screen can change it in place."""

    __slots__ = (
        "id",
        "name",
        "initials",
        "x",
        "y",
        "z",
        "dimension",
        "color",
        "visible",
        "kind",
        "created",
    )

    def __init__(
        self,
        name="",
        x=0,
        y=0,
        z=0,
        dimension=DIMENSION_OVERWORLD,
        color=None,
        kind=KIND_MANUAL,
        waypoint_id=None,
        visible=True,
    ):
        self.id = waypoint_id or new_id()
        self.name = name or "Waypoint"
        self.initials = initials_for(self.name)
        self.x = int(x)
        self.y = int(y)
        self.z = int(z)
        self.dimension = int(dimension)
        self.color = PALETTE[0] if color is None else int(color)
        self.visible = bool(visible)
        self.kind = kind
        self.created = int(time.time() * 1000)

    # -- presentation --------------------------------------------------

    def label(self):
        return self.initials or initials_for(self.name)

    def coordinate_text(self):
        return "%d, %d, %d" % (self.x, self.y, self.z)

    def distance_to(self, px, py, pz):
        dx = self.x + 0.5 - px
        dy = self.y - py
        dz = self.z + 0.5 - pz
        return (dx * dx + dy * dy + dz * dz) ** 0.5

    # -- serialisation -------------------------------------------------

    def to_dict(self):
        return {
            "id": self.id,
            "name": self.name,
            "initials": self.initials,
            "x": self.x,
            "y": self.y,
            "z": self.z,
            "dimension": self.dimension,
            "color": self.color,
            "visible": self.visible,
            "kind": self.kind,
            "created": self.created,
        }

    @classmethod
    def from_dict(cls, data):
        """Rebuild from JSON; anything missing gets a sane default.

        Raises ``ValueError`` only when the entry has no coordinates at all,
        which is how the store decides an entry is junk worth backing up.
        """
        if not isinstance(data, dict):
            raise ValueError("waypoint entry is not an object")
        if "x" not in data or "z" not in data:
            raise ValueError("waypoint entry has no position")
        waypoint = cls(
            name=data.get("name") or "Waypoint",
            x=int(data.get("x", 0)),
            y=int(data.get("y", 0)),
            z=int(data.get("z", 0)),
            dimension=dimension_id(data.get("dimension", DIMENSION_OVERWORLD)),
            color=data.get("color"),
            kind=data.get("kind") or KIND_MANUAL,
            waypoint_id=data.get("id"),
            visible=data.get("visible", True),
        )
        if data.get("initials"):
            waypoint.initials = data["initials"]
        if data.get("created"):
            waypoint.created = int(data["created"])
        return waypoint

    def copy(self, **changes):
        data = self.to_dict()
        data.update(changes)
        return Waypoint.from_dict(data)

    def __repr__(self):
        return "Waypoint(%r, %d, %d, %d, dim=%d, kind=%r)" % (
            self.name,
            self.x,
            self.y,
            self.z,
            self.dimension,
            self.kind,
        )
