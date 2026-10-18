/**
 * Diorama Bedrock port — shared helpers.
 *
 * Ported in spirit from the Fabric mod's small utility classes (Projection / Settings /
 * WorldKey) but rewritten against the Bedrock Script API. Everything here is defensive:
 * a helper must never throw into an event handler.
 */

const PREFIX = "[diorama]";

export function log(...args) {
  try {
    console.log(PREFIX, ...args);
  } catch (_) {
    /* console is always present in practice; never let logging break script execution */
  }
}

export function warn(...args) {
  try {
    console.warn(PREFIX, ...args);
  } catch (_) {
    /* ignore */
  }
}

/** Runs fn() and returns its value, or `undefined` when it throws. */
export function safe(fn, context) {
  try {
    return fn();
  } catch (error) {
    warn(context ?? "safe()", error);
    return undefined;
  }
}

/** Subscribes only when the signal exists and is callable (API-version tolerance). */
export function subscribeSafe(signal, handler, name) {
  try {
    if (!signal || typeof signal.subscribe !== "function") {
      warn(`event unavailable: ${name}`);
      return false;
    }
    signal.subscribe(handler);
    return true;
  } catch (error) {
    warn(`subscribe failed: ${name}`, error);
    return false;
  }
}

export function clamp(value, min, max) {
  if (value < min) return min;
  if (value > max) return max;
  return value;
}

export function clampInt(value, min, max, fallback) {
  const n = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(n)) return fallback;
  return clamp(Math.trunc(n), min, max);
}

export function toBool(value, fallback) {
  if (typeof value === "boolean") return value;
  if (value === "true" || value === 1 || value === "1") return true;
  if (value === "false" || value === 0 || value === "0") return false;
  return fallback;
}

export function parseJson(text, fallback) {
  if (typeof text !== "string" || text.length === 0) return fallback;
  try {
    const value = JSON.parse(text);
    return value === null || value === undefined ? fallback : value;
  } catch (_) {
    return fallback;
  }
}

export function isFiniteVec(vec) {
  return (
    !!vec &&
    Number.isFinite(vec.x) &&
    Number.isFinite(vec.y) &&
    Number.isFinite(vec.z)
  );
}

export function distance3(a, b) {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  const dz = a.z - b.z;
  return Math.sqrt(dx * dx + dy * dy + dz * dz);
}

/** Chunk coordinates keyed for maps/sets, e.g. "3,-7". */
export function chunkKey(x, z) {
  return `${Math.floor(x / 16)},${Math.floor(z / 16)}`;
}

/** Bearing in degrees, 0 = north, 90 = east (Minecraft: north is -Z). */
export function bearingDegrees(from, to) {
  const dx = to.x - from.x;
  const dz = to.z - from.z;
  const deg = (Math.atan2(dx, -dz) * 180) / Math.PI;
  return (deg + 360) % 360;
}

const COMPASS_8 = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
const ARROWS_8 = ["↑", "↗", "→", "↘", "↓", "↙", "←", "↖"];

export function bearingLabel(deg) {
  const index = Math.round(((deg % 360) + 360) % 360 / 45) % 8;
  return COMPASS_8[index];
}

export function bearingArrow(deg) {
  const index = Math.round(((deg % 360) + 360) % 360 / 45) % 8;
  return ARROWS_8[index];
}

export function formatCoord(n) {
  return Math.round(n);
}

/** Small deterministic hash in [-1, 1], mirroring the mod's blockNoise(). */
export function blockNoise(x, y, z) {
  let h = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
  h = h | 0;
  h ^= h >>> 13;
  h = Math.imul(h, 0x5bd1e995);
  h ^= h >>> 15;
  return ((h & 0xffff) / 32767.5) - 1;
}

export function truncate(text, max) {
  if (typeof text !== "string") return "";
  return text.length <= max ? text : text.slice(0, Math.max(0, max - 1)) + "…";
}

export function nowMs() {
  try {
    return Date.now();
  } catch (_) {
    return 0;
  }
}
