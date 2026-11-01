#version 330
// Two dialects, picked per version: 26.3 compiles through SPIR-V and matches the values passed
// between stages by location; before it, OpenGL compiles this text as it is and binds by name.
// (Kept here rather than in the include, which isn't processed per version.)
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
#define LOC(n) layout(location = n)
#define VERTEX_INDEX gl_VertexIndex
#if !defined(SKY_PASS) && !defined(SHADOW) && !defined(WATER) && !defined(TINTED)
#define VOXEL_WALK
#endif

#include <diorama:bake.glsl>
//?} else {
/*#define LOC(n)
#define VERTEX_INDEX gl_VertexID
#if !defined(SKY_PASS) && !defined(SHADOW) && !defined(WATER) && !defined(TINTED)
#define VOXEL_WALK
#endif

#moj_import <diorama:bake.glsl>
*///?}

// The same lighting as TileBaker.shade, per fragment. One fragment of the double-size target is
// one of the tracer's four samples per pixel.
#ifdef SKY_PASS
LOC(0) flat in float cornerSky;
layout(location = 0) out vec4 fragColor;
#elif !defined(SHADOW)
uniform sampler2D ShadowMap;

LOC(0) in vec3 localPos;
LOC(1) in vec2 faceUv;
LOC(2) flat in vec4 faceColor;
LOC(3) flat in vec4 faceOcclusion;
LOC(4) flat in vec4 faceLight;
LOC(5) flat in ivec2 faceInfo;
LOC(6) flat in vec4 faceSky;
LOC(7) flat in ivec3 faceCell;
LOC(8) flat in ivec4 faceHaze;

layout(location = 0) out vec4 fragColor;
#if !defined(WATER) && !defined(TINTED)
// The sample's overlay bits over 255, its surface's albedo brightness, and 1 on a floor, where
// rays that met nothing end: the composite needs these to tint and to mix water like the tracer.
layout(location = 1) out vec4 fragAux;
#endif

const int FLAG_UNKNOWN = 1;
const int FLAG_LAVA = 2;
const int FLAG_CUT = 4;
const int FLAG_OVERLAY = 16;
const int FLAG_SLIME = 32;
const int FLAG_SPAWN_AT_NIGHT = 64;
const int FLAG_SPAWN_ANY_TIME = 128;
const int FACE_FLOOR = 3;

float bilinear(vec4 c, vec2 f) {
    float top = mix(c.x, c.y, f.x);
    float bottom = mix(c.z, c.w, f.x);
    return mix(top, bottom, f.y);
}

vec3 to_linear(vec3 s) {
    return mix(s / 12.92, pow((s + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), s));
}

float luminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// How much closer to the sun the nearest caster over p is, in blocks.
float caster_gap(vec3 p) {
    float su = dot(p, SunU.xyz) + SunU.w;
    float sv = dot(p, SunV.xyz) + SunV.w;
    vec2 uv = vec2(su * SunScale.x, sv * SunScale.y) * 0.5;
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
        return 0.0;
    }
    float occluder = mix(SunScale.z, SunScale.w, texture(ShadowMap, uv).r);
    return occluder - dot(p, SunDir.xyz);
}

// 1 where the sun reaches p, 0 where a block within the shadow distance is in the way. The
// tracer aims each sample's shadow ray at a slightly different sun; a ray toward a nudged sun
// drifts by the nudge times the distance to the caster, so look again that far over.
float sunlight(vec3 p, vec3 jitter) {
    float gap = caster_gap(p);
    if (gap <= Shadow.x || gap >= SunDir.w) {
        return 1.0;
    }
    float nudged = caster_gap(p + jitter * gap);
    return nudged > Shadow.x && nudged < SunDir.w ? 0.0 : 1.0;
}

// Corner occlusion as it dims the sunlight, as TileBaker.shade; the sky light gets the deeper of it and ao.
float ao_sun(float ao) {
    return max(0.0, 1.0 - Shadow.y * (1.0 - ao));
}

#ifdef VOXEL_WALK
// Sunlight at p, but where the caster nearest the sun is past the shadow's reach a nearer block
// can still be in the way: a lower building in front of a tower, or a shelf under a higher one in
// the Nether. The map only keeps the one, so walk toward the sun from o as TileBaker.blocked does.
// The table has no shapes, so a smaller block counts as a cube, or with cubesOnly as open.
float far_sunlight(vec3 p, vec3 o, vec3 jitter, bool cubesOnly) {
    if (caster_gap(p) >= SunDir.w) {
        return blocked(o, SunDir.xyz + jitter, SunDir.w, 96, cubesOnly) ? 0.0 : 1.0;
    }
    return sunlight(p, jitter);
}
#endif

// Sky view: the direct and ambient light on a face, before its colour.
vec3 sun_light(vec3 p, bool top, float ao, float sky) {
    int sampleIndex = (int(gl_FragCoord.x) & 1) + 2 * (int(gl_FragCoord.y) & 1);
    vec3 jitter = Jitter[sampleIndex].xyz;
    vec3 sun = SunDir.xyz + jitter;
    float lambert = top ? 1.0 : max(0.0, sun.z) / sun.y;
    float direct = 0.0;
    if (lambert > 0.0) {
        // Off the face a little, toward the open side, so the face doesn't shade itself.
        vec3 normal = top ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
#ifdef VOXEL_WALK
        // From the face itself, as the tracer starts these rays, past smaller blocks: a ray over a
        // slab's open half mustn't be shaded by it, and a slab's own top starts inside its cell.
        direct = far_sunlight(p + normal * 0.06, p + normal * 1e-4, jitter, true) * Light.y * lambert * ao_sun(ao);
#else
        direct = sunlight(p + normal * 0.06, jitter) * Light.y * lambert * ao_sun(ao);
#endif
    }
    float ambient = Light.x * min(ao, ao_sun(ao));
    if (Light.z > 0.0) {
        ambient *= 1.0 - Light.z * (1.0 - (0.15 + 0.85 * sky));
    }
    vec3 l = ambient * ShadowTint.rgb + direct * SunTint.rgb;
    if (Light.w != 0.0) {
        l *= 1.0 + Light.w * clamp((p.y - 64.0) / 64.0, -1.0, 1.0);
    }
    return l;
}

// Under a roof, as TileBaker.shade: lit like the surface with the lid off, direct light from above
// casting shadows under ledges and down pillars, the fill weaker where little of the open space
// above is seen, and block light glowing on top.
vec3 lid_light(vec3 p, bool top, float ao, float sky) {
    int sampleIndex = (int(gl_FragCoord.x) & 1) + 2 * (int(gl_FragCoord.y) & 1);
    vec3 jitter = Jitter[sampleIndex].xyz;
    vec3 sun = SunDir.xyz + jitter;
    float lambert = top ? 1.0 : max(0.0, sun.z) / sun.y;
    float direct = 0.0;
    if (lambert > 0.0) {
        // The tracer starts these shadow rays on the cube's face, a smaller block's too.
        vec3 normal = top ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
        vec3 o = top ? vec3(p.x, float(faceCell.y) + 1.0, p.z) : vec3(p.x, p.y, float(faceCell.z) + 1.0);
#ifdef VOXEL_WALK
        direct = far_sunlight(o + normal * 0.06, o + normal * 1e-4, jitter, false) * Light.y * lambert * ao_sun(ao);
#else
        direct = sunlight(o + normal * 0.06, jitter) * Light.y * lambert * ao_sun(ao);
#endif
    }
    float ambient = Light.x * min(ao, ao_sun(ao)) * (1.0 - Light.z * (1.0 - (0.15 + 0.85 * sky)));
    float warm = pow(faceLight.y * 255.0 / 15.0, 1.5) * Lid.w;
    return ambient * ShadowTint.rgb + direct * SunTint.rgb + warm * vec3(0.9, 0.75, 0.5) * ao;
}

vec3 haze_corner(int c) {
    return to_linear(vec3(float(c & 255), float((c >> 8) & 255), float((c >> 16) & 255)) / 255.0);
}

// Under a roof: the lower the ground, the more of the biome's fog lies in front, as TileBaker.shade.
vec3 hazed(vec3 c, float y) {
    float below = Lid.x - y;
    if (below <= 0.0) {
        return c;
    }
    float f = min(Lid.z, 1.0 - exp(-below / Lid.y));
    vec3 h = mix(mix(haze_corner(faceHaze.x), haze_corner(faceHaze.y), faceUv.x), mix(haze_corner(faceHaze.z), haze_corner(faceHaze.w), faceUv.x), faceUv.y);
    // Each fog keeps its hue but no more than this light, as TileBaker.hazeColor.
    h *= HazeLight.x * min(1.0, HazeLight.y / max(1e-6, luminance(h)));
    return c + (h - c) * f;
}

// Caves and under a roof: the game's block and sky light in the cell the face looks into.
vec3 lit_light(vec3 p, float ao) {
    float bl = faceLight.y * 255.0 / 15.0;
    float sl = faceLight.x * 255.0 / 15.0 * 0.8;
    float warm = pow(bl, 1.5);
    float cool = pow(sl, 1.5);
    // Unlit caves stay readable; torches, lava and daylight make them glow.
    float base = 0.42;
    vec3 l = (base * vec3(0.92, 0.96, 1.08) + warm * vec3(0.9, 0.75, 0.5) + cool * vec3(0.6, 0.65, 0.75)) * ao;
    if (Mode.y > 0.5) {
        // Deeper below the cut reads darker.
        l *= 1.0 - min(0.5, (Mode.z - p.y) / 60.0);
    }
    // Bright light eases toward a ceiling instead of washing blocks out, keeping its colour, as Lighting.cave.
    float peak = max(l.r, max(l.g, l.b));
    if (peak > 0.5) {
        l *= (0.5 + 0.3 * (1.0 - exp(-(peak - 0.5) / 0.3))) / peak;
    }
    return l;
}
#endif

void main() {
#if !defined(SKY_PASS) && !defined(SHADOW) && !defined(WATER) && !defined(TINTED)
    // 26.2 on Vulkan numbers a shader's outputs in the order the shader first writes them,
    // whatever their layout says, so write them in location order before anything else.
    fragColor = vec4(0.0);
    fragAux = vec4(0.0);
#endif
#ifdef SKY_PASS
    fragColor = vec4(cornerSky, 0.0, 0.0, 1.0);
#elif !defined(SHADOW)
    bool top = faceInfo.x == 0;
    vec3 albedo = to_linear(faceColor.rgb) * 2.0;
#ifdef WATER
    // The water's colour and the light on its surface; the composite mixes it by depth.
    float lit;
    if (Mode.x > 0.5) {
        float levels = max(faceLight.y * 255.0, faceLight.x * 255.0 * 0.8);
        lit = 0.42 + pow(levels / 15.0, 1.5) * 0.8;
    } else {
        vec3 normal = top ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
        float ambient = Light.x;
        if (Light.z > 0.0) {
            ambient *= 1.0 - Light.z * (1.0 - (0.15 + 0.85 * bilinear(faceSky, faceUv)));
        }
        lit = ambient + sunlight(localPos + normal * 0.06, vec3(0.0)) * Light.y;
        if (Light.w != 0.0) {
            lit *= 1.0 + Light.w * clamp((localPos.y - 64.0) / 64.0, -1.0, 1.0);
        }
    }
    fragColor = vec4(albedo, lit);
#elif defined(TINTED)
    // Plants and glass, added up near to far as the tracer's ray meets them: their unlit colour
    // by their weight, which the blend scales by what the ones in front let through. The
    // composite lights the sum by the surface behind, as the tracer does.
    float w = faceColor.a;
    fragColor = vec4(albedo * w, w);
#else
    int flags = faceInfo.y;
    // The tracer records overlay bits at its first sample, (0.375, 0.125) in the pixel; this
    // sample's centre is at (0.25, 0.25), a quarter of a sample to the left and below.
    vec3 firstSample = localPos + dFdx(localPos) * 0.25 - dFdy(localPos) * 0.25;
    float bits = 0.0;
    if ((flags & FLAG_OVERLAY) != 0) {
        // TileBaker.overlayBits. The origin is a multiple of 16, so local cells sit in chunks as world ones do.
        vec3 f = firstSample - vec3(faceCell);
        int b = 0;
        if ((faceCell.x & 15) == 0 && f.x < 0.25) {
            b |= 1;
        }
        if (top && (faceCell.z & 15) == 0 && f.z < 1.0 / (4.0 * Camera.x)) {
            b |= 2;
        }
        if ((flags & FLAG_SLIME) != 0) {
            b |= 4;
        }
        if (top && (flags & (FLAG_SPAWN_AT_NIGHT | FLAG_SPAWN_ANY_TIME)) != 0
            && f.x >= 0.3 && f.x < 0.7 && f.z >= 0.3 && f.z < 0.7) {
            b |= (flags & FLAG_SPAWN_ANY_TIME) != 0 ? 16 : 8;
        }
        bits = float(b) / 255.0;
    }
    if ((flags & FLAG_UNKNOWN) != 0) {
        // A chunk the map has no data for, or unseen under fog of war: the pixel stays unknown.
        fragColor = vec4(0.0);
        fragAux = vec4(0.0);
        return;
    }
    if (faceInfo.x == FACE_FLOOR) {
        // Rays that reached the view's limit: the cut face if still in rock, else the void.
        fragColor = vec4((flags & FLAG_CUT) != 0 ? vec3(0.0065, 0.0068, 0.0085) : vec3(0.01, 0.01, 0.015), 1.0);
        fragAux = vec4(0.0, 0.0, 1.0, 0.0);
        return;
    }
    float ao = bilinear(faceOcclusion, faceUv);
    bool lid = Mode.w > 0.5;
    vec3 l = lid ? lid_light(localPos, top, ao, bilinear(faceSky, faceUv))
        : Mode.x > 0.5 ? lit_light(localPos, ao) : sun_light(localPos, top, ao, bilinear(faceSky, faceUv));
    float emission = faceColor.a;
    if (emission > 0.0) {
        // In the fog, small lights like shroomlights and glowstone glow softer, so they don't
        // speckle the view; lava keeps its full glow.
        l = max(l, vec3(lid && (flags & FLAG_LAVA) == 0 ? 0.3 + emission * 0.45 : 0.35 + emission * 0.85));
    }
    if (!top) {
        // Faces that aren't up read darker, the way Minecraft shades its own block sides.
        l *= 0.82;
    }
    vec3 shaded = albedo * l;
    fragColor = vec4(lid ? hazed(shaded, localPos.y) : shaded, 1.0);
    fragAux = vec4(bits, luminance(albedo), 0.0, 0.0);
#endif
#endif
}
