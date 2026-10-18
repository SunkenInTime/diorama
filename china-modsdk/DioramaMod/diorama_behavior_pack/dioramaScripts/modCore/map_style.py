# -*- coding: utf-8 -*-
"""How the map looks, ported from the Fabric mod's ``MapStyle.java``.

Carries the camera pitch, the colour grade, the sun and shadow tints, the
outline strength and the depth-shading knobs. ``DEFAULT`` is the look players
get; ``FLAT`` is the pre-depth-shading look and is what the "Depth shading"
setting turns off.
"""

from __future__ import absolute_import, division, print_function, unicode_literals

from .projection import MAX_PITCH_DEGREES, MIN_PITCH_DEGREES, clamp, normalize_pitch


def _triple(values):
    """Coerce a colour tuple, falling back to white rather than raising."""
    if isinstance(values, (list, tuple)) and len(values) == 3:
        return (float(values[0]), float(values[1]), float(values[2]))
    return (1.0, 1.0, 1.0)


class MapStyle(object):
    """The look one bake is traced with."""

    MIN_SUN_ELEVATION = 25.0
    MAX_SUN_ELEVATION = 60.0

    __slots__ = (
        "name",
        "pitch_degrees",
        "saturation",
        "exposure",
        "sun_tint",
        "shadow_tint",
        "outline",
        "block_variation",
        "rounded_fog",
        "clear_water",
        "sky_occlusion",
        "sun_elevation",
        "ambient",
        "sun_strength",
        "height_shade",
        "shadow_distance",
    )

    def __init__(
        self,
        name="Default",
        pitch_degrees=50.0,
        saturation=1.35,
        exposure=1.16,
        sun_tint=(1.14, 1.0, 0.8),
        shadow_tint=(0.74, 0.86, 1.18),
        outline=0.38,
        block_variation=0.0,
        rounded_fog=True,
        clear_water=True,
        sky_occlusion=0.85,
        sun_elevation=38.0,
        ambient=0.34,
        sun_strength=0.62,
        height_shade=0.22,
        shadow_distance=40.0,
    ):
        self.name = name
        self.pitch_degrees = normalize_pitch(pitch_degrees)
        self.saturation = float(saturation)
        self.exposure = float(exposure)
        self.sun_tint = _triple(sun_tint)
        self.shadow_tint = _triple(shadow_tint)
        self.outline = clamp(float(outline), 0.0, 1.0)
        self.block_variation = max(0.0, float(block_variation))
        self.rounded_fog = bool(rounded_fog)
        self.clear_water = bool(clear_water)
        self.sky_occlusion = clamp(float(sky_occlusion), 0.0, 1.0)
        self.sun_elevation = clamp(
            float(sun_elevation), self.MIN_SUN_ELEVATION, self.MAX_SUN_ELEVATION
        )
        self.ambient = max(0.0, float(ambient))
        self.sun_strength = max(0.0, float(sun_strength))
        self.height_shade = max(0.0, float(height_shade))
        self.shadow_distance = max(0.0, float(shadow_distance))

    # -- derived values -------------------------------------------------

    @property
    def sun_direction(self):
        """Unit vector toward the sun; azimuth is fixed, elevation is per style."""
        import math

        elevation = math.radians(self.sun_elevation)
        return (
            math.cos(elevation) * SUN_AZIMUTH_X,
            math.sin(elevation),
            math.cos(elevation) * SUN_AZIMUTH_Z,
        )

    @property
    def shadow_reach(self):
        """How far east/north/down from a block its shadow can land."""
        import math

        cos_e = math.cos(math.radians(self.MIN_SUN_ELEVATION))
        sin_e = math.sin(math.radians(self.MAX_SUN_ELEVATION))
        return (
            int(math.ceil(self.shadow_distance * cos_e * -SUN_AZIMUTH_X)) + 1,
            int(math.ceil(self.shadow_distance * sin_e)) + 1,
            int(math.ceil(self.shadow_distance * cos_e * SUN_AZIMUTH_Z)) + 1,
        )

    # -- fluent builders -------------------------------------------------

    def _copy(self, **changes):
        values = dict(
            name=self.name,
            pitch_degrees=self.pitch_degrees,
            saturation=self.saturation,
            exposure=self.exposure,
            sun_tint=self.sun_tint,
            shadow_tint=self.shadow_tint,
            outline=self.outline,
            block_variation=self.block_variation,
            rounded_fog=self.rounded_fog,
            clear_water=self.clear_water,
            sky_occlusion=self.sky_occlusion,
            sun_elevation=self.sun_elevation,
            ambient=self.ambient,
            sun_strength=self.sun_strength,
            height_shade=self.height_shade,
            shadow_distance=self.shadow_distance,
        )
        values.update(changes)
        return MapStyle(**values)

    def with_name(self, name):
        return self._copy(name=name)

    def with_pitch(self, degrees):
        return self._copy(pitch_degrees=normalize_pitch(degrees))

    def with_outline(self, outline):
        return self._copy(outline=outline)

    def with_grade(self, saturation, exposure, sun_tint, shadow_tint):
        return self._copy(
            saturation=saturation, exposure=exposure, sun_tint=sun_tint, shadow_tint=shadow_tint
        )

    def with_sky_occlusion(self, amount):
        return self._copy(sky_occlusion=amount)

    def with_light(self, ambient, sun_strength):
        return self._copy(ambient=ambient, sun_strength=sun_strength)

    def with_height_shade(self, amount):
        return self._copy(height_shade=amount)

    def with_sun_elevation(self, degrees):
        return self._copy(sun_elevation=degrees)

    def with_shadow_distance(self, distance):
        return self._copy(shadow_distance=distance)

    def with_rounded_fog(self, rounded):
        return self._copy(rounded_fog=rounded)

    def with_clear_water(self, clear):
        return self._copy(clear_water=bool(clear))

    def looks_like(self, other):
        """True when tiles baked under one look the same under the other."""
        if other is None:
            return False
        keys = (
            "pitch_degrees",
            "saturation",
            "exposure",
            "sun_tint",
            "shadow_tint",
            "outline",
            "block_variation",
            "rounded_fog",
            "clear_water",
            "sky_occlusion",
            "sun_elevation",
            "ambient",
            "sun_strength",
            "height_shade",
        )
        for key in keys:
            if getattr(self, key) != getattr(other, key):
                return False
        return True

    def to_dict(self):
        return dict((slot, getattr(self, slot)) for slot in self.__slots__)

    @classmethod
    def from_dict(cls, data):
        """Rebuild a style from JSON; unknown keys are ignored, missing keys default."""
        if not isinstance(data, dict):
            return DEFAULT
        kwargs = {}
        for slot in cls.__slots__:
            if slot in data:
                kwargs[slot] = data[slot]
        try:
            return cls(**kwargs)
        except (TypeError, ValueError):
            return DEFAULT

    def __repr__(self):
        return "MapStyle(name=%r, pitch=%r, outline=%r)" % (
            self.name,
            self.pitch_degrees,
            self.outline,
        )


#: The sun is in the west, slightly south, so shadows fall to the east.
SUN_AZIMUTH_X = -0.878
SUN_AZIMUTH_Z = 0.479

#: The look before depth shading: crisp outlines and a rounded fog edge.
BASELINE = MapStyle(
    name="Baseline",
    pitch_degrees=60.0,
    saturation=1.18,
    exposure=1.06,
    sun_tint=(1.06, 1.0, 0.9),
    shadow_tint=(0.9, 0.95, 1.08),
    outline=0.0,
    block_variation=0.0,
    rounded_fog=False,
    clear_water=False,
    sky_occlusion=0.0,
    sun_elevation=51.3,
    ambient=0.5,
    sun_strength=0.484,
    height_shade=0.0,
)

FLAT = BASELINE.with_name("Flat").with_outline(0.38).with_rounded_fog(True)

#: What players get.
DEFAULT = (
    FLAT.with_name("Default")
    .with_pitch(50.0)
    .with_grade(1.35, 1.16, (1.14, 1.0, 0.8), (0.74, 0.86, 1.18))
    .with_sky_occlusion(0.85)
    .with_sun_elevation(38.0)
    .with_light(0.34, 0.62)
    .with_height_shade(0.22)
    .with_clear_water(True)
)

#: Fast variant used by the PC preview tool; same look, shorter shadow rays.
PREVIEW = DEFAULT.with_name("Preview").with_shadow_distance(16.0)
