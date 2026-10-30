package dev.diorama.render;

import dev.diorama.Claims;
import dev.diorama.Projection;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.Overlays;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileSurface;
import dev.diorama.render.gpu.MapTexture;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** A baked tile on the GPU, plus the coverage the fog reads. Render thread only. */
public final class MapTile {
	private @Nullable MapTexture texture;
	private byte @Nullable [] coverage;
	/** {@link Overlays} bits per pixel from the bakes that recorded them, or null. */
	private byte @Nullable [] info;
	private @Nullable MapTexture overlay;
	/** The overlay bits the overlay texture shows, or -1 when it has to be drawn again. */
	private int overlayShown = -1;
	private boolean empty = true;
	long lastDrawn;
	/** Land claims drawn over the tile, or null where it shows none. */
	private @Nullable MapTexture claims;
	/** The claims {@link #claims} shows, or null before they're first painted. */
	Claims.@Nullable Snapshot claimsPainted;
	/** Goes up whenever the tile's pixels change, as the ground the claims are painted on may move. */
	int pixelsRevision;
	/** When {@link #pixelsRevision} last went up. */
	long pixelsChanged;
	/** The {@link #pixelsRevision} {@link #claims} was painted for. */
	int claimsRevision;
	/** The ground under the tile's pixels, for lights; see {@link MapLevel#surface}. */
	@Nullable TileSurface surface;
	@Nullable CompletableFuture<@Nullable TileSurface> surfacePending;

	void upload(TileImage image) {
		if (image.isFull()) {
			this.coverage = image.coverage();
			this.info = image.info();
			this.overlayShown = -1;
			this.empty = isEmpty(this.coverage);
			if (this.empty) {
				this.close();
				return;
			}
			this.ensureTexture(image);
			int size = Projection.TILE_PIXELS;
			for (int level = 0; level < Mipmaps.LEVELS; level++) {
				this.texture.write(level, 0, 0, size >> level, image.mips()[level]);
			}
			return;
		}
		if (this.coverage == null) {
			return;
		}
		int cellsPerBlock = TileImage.BLOCK / (Projection.TILE_PIXELS / Projection.COVERAGE_CELLS);
		for (TileImage.Patch patch : image.patches()) {
			for (int y = 0; y < cellsPerBlock; y++) {
				System.arraycopy(
					patch.coverage(), y * cellsPerBlock,
					this.coverage, (patch.blockY() * cellsPerBlock + y) * Projection.COVERAGE_CELLS + patch.blockX() * cellsPerBlock,
					cellsPerBlock
				);
			}
		}
		this.empty = isEmpty(this.coverage);
		if (this.empty) {
			this.close();
			return;
		}
		if (this.ensureTexture(image)) {
			// A new texture over a previously empty tile starts blank everywhere outside the patches.
			int size = Projection.TILE_PIXELS;
			for (int level = 0; level < Mipmaps.LEVELS; level++) {
				this.texture.write(level, 0, 0, size >> level, new int[(size >> level) * (size >> level)]);
			}
		}
		for (TileImage.Patch patch : image.patches()) {
			for (int level = 0; level < Mipmaps.LEVELS; level++) {
				int edge = TileImage.BLOCK >> level;
				this.texture.write(level, patch.blockX() * edge, patch.blockY() * edge, edge, patch.mips()[level]);
			}
			if (patch.info() != null) {
				this.patchInfo(patch);
			}
		}
	}

	private void patchInfo(TileImage.Patch patch) {
		int size = Projection.TILE_PIXELS;
		int edge = TileImage.BLOCK;
		if (this.info == null) {
			this.info = new byte[size * size];
		}
		int x0 = patch.blockX() * edge;
		int y0 = patch.blockY() * edge;
		for (int y = 0; y < edge; y++) {
			System.arraycopy(patch.info(), y * edge, this.info, (y0 + y) * size + x0, edge);
		}
		if (this.overlay != null && this.overlayShown >= 0) {
			this.drawOverlay(x0, y0, edge);
		}
	}

	/**
	 * The overlay texture showing these {@link Overlays} bits, drawn over the tile, or null when
	 * this tile wasn't baked with overlay bits.
	 */
	public @Nullable MapTexture overlay(int shown) {
		if (this.info == null || shown == 0 || this.texture == null) {
			return null;
		}
		if (this.overlay == null) {
			this.overlay = MapTexture.create("diorama overlay", Projection.TILE_PIXELS, 1);
			this.overlayShown = -1;
		}
		if (this.overlayShown != shown) {
			this.overlayShown = shown;
			this.drawOverlay(0, 0, Projection.TILE_PIXELS);
		}
		return this.overlay;
	}

	private void drawOverlay(int x0, int y0, int edge) {
		int size = Projection.TILE_PIXELS;
		int[] pixels = new int[edge * edge];
		for (int y = 0; y < edge; y++) {
			for (int x = 0; x < edge; x++) {
				pixels[y * edge + x] = overlayColor(this.info[(y0 + y) * size + x0 + x], this.overlayShown);
			}
		}
		this.overlay.write(0, x0, y0, edge, pixels);
	}

	/** ABGR colour of one overlay pixel: slime chunks tinted green, spawn spots red or yellow, chunk lines white. */
	static int overlayColor(int bits, int shown) {
		bits &= shown;
		if ((bits & (Overlays.CHUNK_WEST | Overlays.CHUNK_NORTH)) != 0) {
			return 0xA0FFFFFF;
		}
		if ((bits & Overlays.SPAWN_ANY_TIME) != 0) {
			return 0xE02828FF;
		}
		if ((bits & Overlays.SPAWN_AT_NIGHT) != 0) {
			return 0xB020D0FF;
		}
		if ((bits & Overlays.SLIME) != 0) {
			return 0x7040F050;
		}
		return 0;
	}

	/** Shows freshly painted claims, or none: {@code painted} drawn on the pixels of {@code revision}. */
	void paintClaims(int @Nullable [] pixels, Claims.Snapshot painted, int revision) {
		this.claimsPainted = painted;
		this.claimsRevision = revision;
		if (pixels == null) {
			if (this.claims != null) {
				this.claims.close();
				this.claims = null;
			}
			return;
		}
		if (this.claims == null) {
			this.claims = MapTexture.create("diorama claims", Projection.TILE_PIXELS, 1);
		}
		this.claims.write(0, 0, 0, Projection.TILE_PIXELS, pixels);
	}

	public @Nullable MapTexture claims() {
		return this.texture == null ? null : this.claims;
	}

	/** Creates the GPU texture if needed; true if it was just created. */
	private boolean ensureTexture(TileImage image) {
		if (this.texture != null) {
			return false;
		}
		this.texture = MapTexture.create("diorama tile " + image.key(), Projection.TILE_PIXELS, Mipmaps.LEVELS);
		return true;
	}

	public @Nullable MapTexture texture() {
		return this.texture;
	}

	public boolean isEmpty() {
		return this.empty;
	}

	/** Known share of the coverage cell, 0..1. */
	public float coverage(int cellX, int cellY) {
		byte[] c = this.coverage;
		if (c == null) {
			return 0;
		}
		return (c[cellY * Projection.COVERAGE_CELLS + cellX] & 0xFF) / 255.0F;
	}

	/** The overlay bits recorded for one pixel, or -1 if this tile has none. For tests. */
	public int overlayBits(int px, int py) {
		return this.info == null ? -1 : this.info[py * Projection.TILE_PIXELS + px] & 0xFF;
	}

	public boolean hasCoverage() {
		return this.coverage != null;
	}

	void close() {
		if (this.claims != null) {
			this.claims.close();
			this.claims = null;
		}
		this.claimsPainted = null;
		if (this.overlay != null) {
			this.overlay.close();
			this.overlay = null;
		}
		if (this.texture != null) {
			this.texture.close();
			this.texture = null;
		}
	}

	private static boolean isEmpty(byte[] coverage) {
		for (byte b : coverage) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}
}
