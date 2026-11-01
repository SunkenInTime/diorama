package dev.diorama.bake;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import org.jspecify.annotations.Nullable;

/**
 * The {@link TileSurface}s of the tiles under an area of the map, {@code columns} by
 * {@code rows} of them from tile ({@code tileU0}, {@code tileV0}); null where a tile has none
 * yet. Looks the ground up by map pixel. Immutable, so any thread may read it.
 */
public record SurfaceArea(int tileU0, int tileV0, int columns, int rows, @Nullable TileSurface[] tiles) {
	private @Nullable TileSurface at(int pu, int pv) {
		int size = Projection.TILE_PIXELS;
		int column = Math.floorDiv(pu, size) - this.tileU0;
		int row = Math.floorDiv(pv, size) - this.tileV0;
		return column < 0 || row < 0 || column >= this.columns || row >= this.rows ? null : this.tiles[row * this.columns + column];
	}

	private static int index(TileSurface surface, int pu, int pv) {
		return surface.index(Math.floorMod(pu, Projection.TILE_PIXELS), Math.floorMod(pv, Projection.TILE_PIXELS));
	}

	/** The ground's world y at map pixel (pu, pv), or NaN. */
	public float y(int pu, int pv) {
		TileSurface surface = this.at(pu, pv);
		int i = surface == null ? -1 : index(surface, pu, pv);
		return i < 0 ? Float.NaN : surface.y()[i];
	}

	/** Whether the ground at map pixel (pu, pv), which has some, is a top face (else a south one). */
	public boolean top(int pu, int pv) {
		TileSurface surface = this.at(pu, pv);
		return surface.top()[index(surface, pu, pv)];
	}

	/** The block light level at map pixel (pu, pv) of a cave slice, which has ground, 0..15. */
	public int blockLight(int pu, int pv) {
		TileSurface surface = this.at(pu, pv);
		return surface.light()[index(surface, pu, pv)] & 15;
	}

	/**
	 * Map pixel (pu, pv) of a cave slice lit as its tile was, but with block light
	 * {@code blockLight}: an ABGR pixel in the style's grade and outline, {@code alpha} (0..255) of
	 * the tile pixel's own alpha.
	 */
	public int relit(int pu, int pv, float blockLight, int alpha, MapStyle style, float[] scratch) {
		TileSurface surface = this.at(pu, pv);
		int i = index(surface, pu, pv);
		surface.caveLight(i, blockLight, scratch);
		int albedo = surface.albedo()[i];
		float[] lin = TileBaker.SRGB_TO_LINEAR;
		int pixel = TileBaker.abgr(style, lin[albedo >> 16 & 0xFF] * scratch[0], lin[albedo >> 8 & 0xFF] * scratch[1], lin[albedo & 0xFF] * scratch[2], alpha * (albedo >>> 24) / 255);
		return surface.outlined()[i] ? TileBaker.darken(pixel, 1 - style.outline()) : pixel;
	}
}
