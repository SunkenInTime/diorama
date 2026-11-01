package dev.diorama.bake;

/**
 * What the camera sees at each pixel of the part of a tile moving things need, for drawing them
 * into it: where the ground is, which way it faces, its colour, and what lit it. Made by the
 * tiles' own tracer and lighting, so it matches their pixels.
 *
 * <p>Per pixel: {@code y} is the ground's world y (its x is the pixel's u, and its z follows from
 * u, v and y), NaN where the map knows nothing; {@code top} whether it's a top face (else a south
 * one); {@code sun}, on a surface tile, the share of its light from the sun, 0..255, 0 where the
 * world's own blocks shade it, {@code near} the camera depth of the nearest of the pixel's samples, as a tile pixel at a block's edge shows that block even if its middle misses (NaN where none hits), and {@code albedo} the pixel as an ABGR pixel with the sun taken out, 0 on water. On a cave slice, {@code albedo} is the pixel as drawn with its
 * light taken out but a side's darkening kept, in sRGB as 0xAARRGGBB with the pixel's alpha, so
 * water keeps its depth and edges their coverage; {@code light}, what lit it there
 * ({@link #caveLight}), so a moving light can light it again; and {@code outlined}, whether the
 * style's outline darkens it.
 */
public record TileSurface(int revision, int x0, int y0, int width, int height, float[] y, float[] near, boolean[] top, byte[] sun, int[] albedo, int[] light, boolean[] outlined) {
	/** The arrays' index for tile pixel (px, py), or -1 outside the part of the tile this covers. */
	int index(int px, int py) {
		int x = px - this.x0;
		int y = py - this.y0;
		return x < 0 || y < 0 || x >= this.width || y >= this.height ? -1 : y * this.width + x;
	}

	/** Whether this covers tile pixels (x0, y0) to (x1, y1). */
	public boolean covers(int x0, int y0, int x1, int y1) {
		return x0 >= this.x0 && y0 >= this.y0 && x1 <= this.x0 + this.width && y1 <= this.y0 + this.height;
	}

	/** Whether this shares any pixel with tile pixels (x0, y0) to (x1, y1). */
	public boolean touches(int x0, int y0, int x1, int y1) {
		return x0 < this.x0 + this.width && this.x0 < x1 && y0 < this.y0 + this.height && this.y0 < y1;
	}

	/** Marks packed light ({@link #caveLight}) as water's, which {@link Lighting#caveWater} lights. */
	static final int WATER = 1 << 28;

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
		if ((packed & WATER) != 0) {
			java.util.Arrays.fill(out, 0, 3, Lighting.caveWater(blockLight, packed >> 4 & 15) * (packed >> 20 & 255) / 255.0F);
			return;
		}
		Lighting.cave(blockLight, packed >> 4 & 15, (packed >> 12 & 255) / 255.0F, (packed >> 20 & 255) / 255.0F, (packed >> 8 & 15) / 15.0F, 1, out);
	}

}
