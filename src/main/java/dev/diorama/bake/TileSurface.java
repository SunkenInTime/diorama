package dev.diorama.bake;

/**
 * What the camera sees at each pixel of a cave slice's tile, for lights drawn over it: where the
 * ground is, which way it faces, its colour, and what lit it. Made by the tiles' own tracer and
 * lighting, so it matches their pixels.
 *
 * <p>Per pixel: {@code y} is the ground's world y (its x is the pixel's u, and its z follows from
 * u, v and y), NaN where the map knows nothing; {@code top} whether it's a top face (else a south
 * one); {@code albedo} the pixel as drawn with its light taken out but a side's darkening kept,
 * in sRGB as 0xAARRGGBB with the pixel's alpha, so water keeps its depth and edges their
 * coverage; {@code light}, what lit it there ({@link #caveLight}), so a moving light can light it
 * again; and {@code outlined}, whether the style's outline darkens it.
 */
public record TileSurface(int revision, float[] y, boolean[] top, int[] albedo, int[] light, boolean[] outlined) {
	/**
	 * Packs what lights a surface in a cave slice: block and sky light levels (0..15), corner
	 * occlusion and depth fade (0..1), and the block's own light level (0..15).
	 */
	static int caveLight(int blockLight, int skyLight, float ao, float fade, int emission) {
		return blockLight | skyLight << 4 | emission << 8 | Math.round(ao * 255) << 12 | Math.round(fade * 255) << 20;
	}

	/**
	 * The light on the surface at pixel index {@code i} of a cave slice, lit as the tile was but
	 * with block light {@code blockLight}, in linear light; see {@link #caveLightOf}.
	 */
	void caveLight(int i, float blockLight, float[] out) {
		caveLightOf(this.light[i], blockLight, out);
	}

	/**
	 * The light {@code packed} ({@link #caveLight}) gives with block light {@code blockLight},
	 * without a side's darkening, in linear light; see {@link Lighting#cave}.
	 */
	static void caveLightOf(int packed, float blockLight, float[] out) {
		Lighting.cave(blockLight, packed >> 4 & 15, (packed >> 12 & 255) / 255.0F, (packed >> 20 & 255) / 255.0F, (packed >> 8 & 15) / 15.0F, 1, out);
	}

}
