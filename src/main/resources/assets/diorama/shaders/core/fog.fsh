#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require
//?}

// Volumetric-looking fog over the parts of the map with no data.
//
// The fog is a height field: deep in unknown territory it stands fogHeight blocks tall, and it
// thins out toward known ground, with noise breaking up the edge. We look at it through the same
// tilted camera as the map, so we march a few height layers from the top down. A layer at height h
// sits above the ground point h * cos(pitch) further south, which is what makes the banks look
// like they rise off the terrain.

//? if >=26.3 {
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
//?} else if >=1.21.11 {
/*layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
*///?} else if <1.21.6 {
/*// Before 1.21.6 uniforms were set one by one, and only the ones the shader names.
uniform float GameTime;
uniform vec4 ColorModulator;
*///?} else {
/*// Before 1.21.11 the globals started at the screen size, and the transforms ended with a line width.
layout(std140) uniform Globals {
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};
*///?}

// Share of known ground per mask texel, in the red channel. Green is 1 where every texel this
// shader can read from a pixel in that texel is fully known, blue is 1 where every one is fully
// unknown. Alpha is 1 less the share of a remembered map under the fog.
uniform sampler2D Sampler0;
// Tileable noise covering NOISE_PERIOD map units, a different scale per channel.
uniform sampler2D Sampler1;

//? if >=26.3 {
layout(location = 0) in vec2 maskUv;
//?} else {
/*in vec2 maskUv;
*///?}
// rg: noise offset in eighths of the coarser noise period, in the top three bits of each; r's low
// five bits: how see-through the fog is, in 31sts (0 is opaque); b: cave flag (128), fog scale steps
// (bits 4-6) and sixteenths of a step (bits 0-3); a: mask texel size code (bits 4-7), the fast
// quality flag (4) and the smoke flag (1).
//? if >=26.3 {
layout(location = 1) in vec4 params;

layout(location = 0) out vec4 fragColor;
//?} else {
/*in vec4 params;

out vec4 fragColor;
*///?}

const float MASK_TEXELS = 256.0;
const float NOISE_PERIOD = 512.0;
// The default camera angle (50 degrees). At other angles the banks sit slightly too far north or south.
const float COS_PITCH = 0.643;
const int LAYERS = 8;
// Fast quality, for slower graphics cards: fewer layers, one blur, and no sun shading.
const int FAST_LAYERS = 2;
// How much of the fog a remembered map shows through.
const float MEMORY_SHOWS = 0.62;

// Surface fog banks are tall and soft. Cave fog hugs the ground with a tight edge, so a
// three-block tunnel you walked down stays readable instead of blurring into the fog.
float fogHeight;
float blurUnits;
float unitsPerUv;
vec2 noiseOrigin;
// The noise pattern grows by this when zoomed far out, so it keeps its size on screen instead of
// repeating every few pixels. The banks keep their size in blocks, so they stay a thin fringe
// around explored land and never bury it.
float fogScale;
float scaleSteps;
float scaleFraction;
float seconds;
bool fast;
// Every mask texel this pixel can read is fully known. Then every thickness is 0 whatever the
// noise, so the texture reads are skipped. The rest still runs: the lowest layers leave a faint
// veil even over known ground.
bool clear;
// Every mask texel this pixel can read is fully unknown: every known share is exactly 0, so the
// mask reads are skipped. The noise still shapes the fog there, so its reads stay.
bool deep;

// The known share at uv, without the read where the answer is already known.
float knownAt(vec2 uv) {
    return deep ? 0.0 : texture(Sampler0, uv).r;
}

// Known share blurred over a radius in map units, so chunk-shaped edges melt away.
float knownBlur(vec2 uv, float units) {
    float r = max(units / unitsPerUv, 1.0 / MASK_TEXELS);
    float k = texture(Sampler0, uv).r * 0.2;
    k += texture(Sampler0, uv + vec2(r, 0.0)).r * 0.1;
    k += texture(Sampler0, uv - vec2(r, 0.0)).r * 0.1;
    k += texture(Sampler0, uv + vec2(0.0, r)).r * 0.1;
    k += texture(Sampler0, uv - vec2(0.0, r)).r * 0.1;
    float d = r * 1.9;
    k += texture(Sampler0, uv + vec2(d, d)).r * 0.1;
    k += texture(Sampler0, uv + vec2(-d, d)).r * 0.1;
    k += texture(Sampler0, uv + vec2(d, -d)).r * 0.1;
    k += texture(Sampler0, uv + vec2(-d, -d)).r * 0.1;
    return k;
}

// Surface fog blends a near and a wide blur, so banks reach well over the edge of known land
// and the noise has room to billow. Cave fog stays tight to the explored tunnels.
float knownSoft(vec2 uv) {
    if (clear) {
        return 1.0;
    }
    if (deep) {
        return 0.0;
    }
    if (blurUnits < 5.0 || fast) {
        return knownBlur(uv, blurUnits);
    }
    return 0.5 * knownBlur(uv, blurUnits) + 0.5 * knownBlur(uv, blurUnits * 2.4);
}

float noiseLayer(vec2 n) {
    // Speeds are whole periods per 1200 s, so the loop of GameTime wraps without a jump.
    float a = texture(Sampler1, n + vec2(5.0, 2.0) * seconds / 1200.0).r;
    float b = texture(Sampler1, n + vec2(-8.0, 3.0) * seconds / 1200.0).g;
    float c = texture(Sampler1, n + vec2(13.0, -9.0) * seconds / 1200.0).b;
    return a * 0.55 + b * 0.3 + c * 0.15;
}

// Noise at the two whole scale steps around fogScale, blended, so zooming never pops.
float noiseAt(vec2 uv) {
    float coarsePeriod = NOISE_PERIOD * exp2(scaleSteps + 1.0);
    vec2 coarse = noiseOrigin + uv * unitsPerUv / coarsePeriod;
    // The fine noise has half the period, so it runs twice as far from the same origin.
    float fine = noiseLayer(coarse * 2.0);
    // At the detailed zoom and closer there is only the one scale: mix() would return fine anyway.
    if (scaleFraction == 0.0) {
        return fine;
    }
    return mix(fine, noiseLayer(coarse), scaleFraction);
}

float thicknessFrom(float known, float n) {
    float unknown = 1.0 - known;
    // Noise only shapes the edge; where everything is known there is no fog at all.
    float edge = smoothstep(0.0, 0.35, unknown);
    float amount = smoothstep(0.12, 0.75, unknown + (n - 0.5) * 1.5 * edge);
    return fogHeight * amount * (0.45 + 0.75 * n);
}

// From this known share up there is no fog whatever the noise: unknown + 0.75 * edge stays below
// the 0.12 where the amount starts, so the noise needn't be read.
const float NO_FOG_KNOWN = 0.95;

float thicknessAt(vec2 uv, float known) {
    return known >= NO_FOG_KNOWN ? 0.0 : thicknessFrom(known, noiseAt(uv));
}

float thickness(vec2 uv) {
    return thicknessAt(uv, knownSoft(uv));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    seconds = GameTime * 1200.0;
    // The red byte is the noise offset in its top three bits and, in its low five, how see-through
    // the whole fog is, in 31sts: 0 draws the fog as it is, and the minimap raises it to fade the map.
    vec2 offsetBytes = floor(params.rg * 255.0 + 0.5);
    noiseOrigin = floor(offsetBytes / 32.0) / 8.0;
    float fogOpacity = 1.0 - mod(offsetBytes.r, 32.0) / 31.0;
    float alphaCode = floor(params.a * 255.0 + 0.5);
    unitsPerUv = MASK_TEXELS * 2.0 * exp2(floor(alphaCode / 16.0));
    fast = mod(alphaCode, 16.0) >= 2.0;
    bool smoke = mod(alphaCode, 2.0) >= 1.0;
    float fogCode = floor(params.b * 255.0 + 0.5);
    bool cave = fogCode >= 128.0;
    fogCode = mod(fogCode, 128.0);
    scaleSteps = floor(fogCode / 16.0);
    scaleFraction = mod(fogCode, 16.0) / 16.0;
    fogScale = exp2(scaleSteps + scaleFraction);
    // MapViewport.fogReach repeats how far the reads below can reach (these sizes, the blur taps,
    // the layer shift and the sun probe), to know where the clear and deep flags are safe. Change both together.
    fogHeight = cave ? 4.0 : 10.0;
    blurUnits = cave ? 2.5 : 7.0;
    ivec2 texel = clamp(ivec2(floor(maskUv * MASK_TEXELS)), ivec2(0), ivec2(int(MASK_TEXELS) - 1));
    vec4 flags = texelFetch(Sampler0, texel, 0);
    clear = flags.g > 0.5;
    deep = flags.b > 0.5;

    vec3 lit = cave ? vec3(0.24, 0.26, 0.33) : vec3(0.97, 0.97, 0.99);
    vec3 shade = cave ? vec3(0.035, 0.04, 0.055) : vec3(0.48, 0.55, 0.67);
    if (smoke && !cave) {
        // Under a roof the banks are smoke, faintly warm from the lava below.
        lit = vec3(0.26, 0.22, 0.21);
        shade = vec3(0.07, 0.055, 0.055);
    }
    vec2 toSun = vec2(-1.0, -0.4) * (9.0 * fogScale / unitsPerUv);

    vec3 color = vec3(0.0);
    float alpha = 0.0;
    float jitter = hash(gl_FragCoord.xy) - 0.5;
    int layers = fast ? FAST_LAYERS : LAYERS;
    for (int i = 0; i < layers; i++) {
        float h = fogHeight * (1.0 - (float(i) + 0.5 + jitter * 0.6) / float(layers));
        vec2 ground = maskUv + vec2(0.0, h * COS_PITCH / unitsPerUv);
        float th = thickness(ground);
        float a = smoothstep(h - 1.4, h + 1.4, th);
        if (a <= 0.002) {
            continue;
        }
        // More fog between this point and the sun means it sits in shadow.
        float occlusion = 0.0;
        if (!fast) {
            vec2 probe = ground + toSun;
            float toward = thicknessAt(probe, clear ? 1.0 : knownAt(probe));
            occlusion = clamp((toward - th) / fogHeight * 2.2, 0.0, 1.0);
        }
        float light = clamp(0.2 + 0.7 * (h / fogHeight) + 0.3 - occlusion * 0.7, 0.0, 1.0);
        vec3 layerColor = mix(shade, lit, light);
        // Fewer layers each carry more, so the bank stays as thick.
        float layerAlpha = a * (fast ? 0.85 : 0.45);
        color += (1.0 - alpha) * layerAlpha * layerColor;
        alpha += (1.0 - alpha) * layerAlpha;
        if (alpha > 0.985) {
            break;
        }
    }

    // The ground under a thick bank is never visible.
    float soft = knownSoft(maskUv);
    float floorFog = smoothstep(0.5, 2.5, thicknessAt(maskUv, soft));
    color += (1.0 - alpha) * floorFog * shade;
    alpha += (1.0 - alpha) * floorFog;

    // Ground that is known right here stays visible; fog only rolls over its edges. Caves use the
    // sharp mask so narrow tunnels stay clear; the surface uses the blurred one so banks stay soft.
    float here = cave ? knownAt(maskUv) : soft;
    alpha *= 1.0 - 0.85 * smoothstep(0.45, 0.95, here);

    // Where there is no map at all, nothing should show through, or the edge of the data would
    // read as a hard line inside the bank.
    // The cover takes the colour the layers already have here, so it doesn't show as a band.
    float empty = 1.0 - smoothstep(0.3, 0.85, knownAt(maskUv));
    vec3 coverColor = alpha > 0.02 ? color / alpha : mix(shade, lit, 0.7);
    color += (1.0 - alpha) * empty * coverColor;
    alpha += (1.0 - alpha) * empty;

    if (alpha <= 0.002) {
        discard;
    }
    color += (hash(gl_FragCoord.yx) - 0.5) / 255.0;
    float memory = 1.0 - texture(Sampler0, maskUv).a;
    fragColor = vec4(color / alpha, alpha * fogOpacity * (1.0 - MEMORY_SHOWS * memory)) * ColorModulator;
}
