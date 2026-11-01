package dev.diorama.bake;

import dev.diorama.MapStyle;

/**
 * How a surface is lit in a map style: out in the open by the sun, the sky, the shade where
 * blocks meet, the block's own glow, a little brightness with height, and darker sides; in a cave
 * slice by the game's own light. The terrain tiles and the lights drawn over them both light
 * here, so they always look alike.
 */
final class Lighting {
	/** Cave light up to here is kept as it is; above, it eases toward {@link #CAVE_CEILING}. */
	private static final float CAVE_KNEE = 0.5F;
	private static final float CAVE_CEILING = 0.8F;

	private final MapStyle style;

	Lighting(MapStyle style) {
		this.style = style;
	}

	/**
	 * The light falling on a surface, in linear light, into {@code out}: {@code lambert} is the
	 * sun on it relative to a level top face (0 where the sun is behind it), {@code shadowed}
	 * whether something stands between it and the sun, {@code ao} the corner occlusion,
	 * {@code sky} how much open sky it sees (0..1), {@code emission} its own glow (0..1),
	 * {@code facing} how much its side is darkened (1 for a top) and {@code y} its height.
	 */
	void surface(double lambert, boolean shadowed, float ao, float sky, float emission, float facing, double y, float[] out) {
		MapStyle style = this.style;
		// Shade in corners and creases dims the sunlight too as far as the style asks, and the sky
		// light by at least as much as always.
		float aoSun = Math.max(0, 1 - style.cornerOcclusion() * (1 - ao));
		float aoSky = Math.min(ao, aoSun);
		float direct = lambert > 0 && !shadowed ? (float) (style.sunStrength() * lambert) * aoSun : 0;
		// The surface map is a daytime view lit only by the sun and the sky, so it depends on
		// blocks alone and light updates never make its colours stale.
		float ambient = style.ambient() * aoSky;
		float occlusion = style.skyOcclusion();
		if (occlusion > 0) {
			// Less sky overhead, less ambient light: under trees, down in valleys and at the
			// foot of walls. This is what makes the levels of the world read apart.
			ambient *= 1 - occlusion * (1 - (0.15F + 0.85F * sky));
		}
		float[] skyTint = style.shadowTint();
		float[] sunTint = style.sunTint();
		float r = ambient * skyTint[0] + direct * sunTint[0];
		float g = ambient * skyTint[1] + direct * sunTint[1];
		float b = ambient * skyTint[2] + direct * sunTint[2];
		float heightShade = style.heightShade();
		if (heightShade != 0) {
			// High ground a little brighter, low ground darker, so hills and valleys still read
			// apart when zoomed out too far to see their shadows.
			float above = (float) Math.max(-1, Math.min(1, (y - 64) / 64));
			float f = 1 + heightShade * above;
			r *= f;
			g *= f;
			b *= f;
		}
		if (emission > 0) {
			float glow = 0.35F + emission * 0.85F;
			r = Math.max(r, glow);
			g = Math.max(g, glow);
			b = Math.max(b, glow);
		}
		out[0] = r * facing;
		out[1] = g * facing;
		out[2] = b * facing;
	}

	/**
	 * The light on a surface in a cave slice, lit by the game's own light, into {@code out}:
	 * block and sky light levels (0..15; block light may fall between levels, where a moving light
	 * is smoothed) in the open cell in front of it, its corner occlusion,
	 * how much depth below the cut dims it ({@code fade}, 1 for none), its own glow (0..1) and
	 * how much its side is darkened (1 for a top).
	 */
	/** How bright block and sky light make water in a cave slice; it takes no tint or ceiling. */
	static float caveWater(float blockLight, int skyLight) {
		return 0.42F + (float) Math.pow(Math.max(blockLight, skyLight * 0.8F) / 15.0F, 1.5) * 0.8F;
	}

	static void cave(float blockLight, int skyLight, float ao, float fade, float emission, float facing, float[] out) {
		float bl = blockLight / 15.0F;
		float sl = skyLight / 15.0F * 0.8F;
		float warm = (float) Math.pow(bl, 1.5);
		float cool = (float) Math.pow(sl, 1.5);
		// Unlit caves stay readable; torches, lava and daylight make them glow.
		float base = 0.42F;
		float r = (base * 0.92F + warm * 0.9F + cool * 0.6F) * ao * fade;
		float g = (base * 0.96F + warm * 0.75F + cool * 0.65F) * ao * fade;
		float b = (base * 1.08F + warm * 0.5F + cool * 0.75F) * ao * fade;
		// Bright light eases toward a ceiling instead of washing blocks out, keeping its colour.
		float peak = Math.max(r, Math.max(g, b));
		if (peak > CAVE_KNEE) {
			float eased = CAVE_KNEE + (CAVE_CEILING - CAVE_KNEE) * (float) (1 - Math.exp(-(peak - CAVE_KNEE) / (CAVE_CEILING - CAVE_KNEE)));
			r *= eased / peak;
			g *= eased / peak;
			b *= eased / peak;
		}
		if (emission > 0) {
			float glow = 0.35F + emission * 0.85F;
			r = Math.max(r, glow);
			g = Math.max(g, glow);
			b = Math.max(b, glow);
		}
		out[0] = r * facing;
		out[1] = g * facing;
		out[2] = b * facing;
	}
}
