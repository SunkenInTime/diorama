/**
 * Oblique camera maths — a direct port of the Fabric mod's `Projection.java`.
 *
 * The camera looks north and down by `pitch`. Map space is measured in blocks:
 *   u = x                       (east is right)
 *   v = z * sin - y * cos       (south is down; higher blocks move up the screen)
 *
 * A point on the map row `v` and height `y` lies at world z = (v + y * cos) / sin.
 */

import { clamp } from "./util.js";

export const TILE_UNITS = 128; // map units per tile edge, kept for parity with the mod
export const PIXELS_PER_UNIT = 4; // texture pixels per map unit, kept for parity
export const TILE_PIXELS = TILE_UNITS * PIXELS_PER_UNIT;

export const MIN_PITCH_DEGREES = 25;
export const MAX_PITCH_DEGREES = 70;
export const DEFAULT_PITCH_DEGREES = 50;

export function normalizePitch(degrees) {
  const n = typeof degrees === "number" ? degrees : Number(degrees);
  if (!Number.isFinite(n)) return DEFAULT_PITCH_DEGREES;
  return clamp(n, MIN_PITCH_DEGREES, MAX_PITCH_DEGREES);
}

/** Builds an immutable projection for one camera angle. */
export function makeProjection(pitchDegrees) {
  const pitch = normalizePitch(pitchDegrees);
  const radians = (pitch * Math.PI) / 180;
  const sin = Math.sin(radians);
  const cos = Math.cos(radians);
  return {
    pitchDegrees: pitch,
    sin,
    cos,
    /** Screen row for a block at height y, world z. */
    v(y, z) {
      return z * sin - y * cos;
    },
    /** World z where a ray through map row v crosses height y. */
    zAt(v, y) {
      return (v + y * cos) / sin;
    },
  };
}
