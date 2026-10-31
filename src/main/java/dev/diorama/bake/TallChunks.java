package dev.diorama.bake;

import dev.diorama.Projection;
import dev.diorama.data.ChunkSnapshot;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jspecify.annotations.Nullable;

/**
 * What a tile's rays need to know about builds above {@link #ORDINARY_TOP}, which only a world made
 * taller by a data pack has room for.
 *
 * <p>Rays start above a tile's tallest block, and the map tilts, so the higher that is the further
 * south they start: from a build at y 2000, some 1,600 blocks. Those chunks are rarely known, and
 * a ray that reaches a chunk the map has no data for shows nothing, so one tall build would blank
 * its tile columns for as far as it reaches. Without data for a chunk the map already takes it to
 * be no taller than the tile; here only what stands below {@link #ORDINARY_TOP} counts for that,
 * and rays above {@link #missingTop} pass through missing chunks.
 */
public final class TallChunks {
	/** One above the highest y of a world no data pack has changed. */
	public static final int ORDINARY_TOP = 320;

	/** Chunks the map has no data for are taken to hold nothing from this height up. */
	final int missingTop;
	private final int firstChunkX;
	/** Per chunk column, the chunks reaching above {@link #ORDINARY_TOP}, southmost first: z, then top. */
	private final int[][] tall;

	private TallChunks(int missingTop, int firstChunkX, int[][] tall) {
		this.missingTop = missingTop;
		this.firstChunkX = firstChunkX;
		this.tall = tall;
	}

	/**
	 * For a tile whose rays start at {@code startY} in a view from the sky, or null if that isn't
	 * above {@link #ORDINARY_TOP}.
	 */
	static @Nullable TallChunks around(TileKey key, ChunkSource source, int startY) {
		if (startY <= ORDINARY_TOP) {
			return null;
		}
		// Rays reach the bottom of the world only through what's known of it.
		int missingTop = Math.min(startY, Math.max(source.minY() + 1, TileBaker.localTop(key, source, 0, ORDINARY_TOP)));
		double minV = key.minV();
		double maxV = minV + Projection.TILE_UNITS;
		// One column and row past the tile: the bake draws a pixel past its edges for outlines.
		int cx0 = Math.floorDiv((int) Math.floor(key.minU()) - 1, 16);
		int cx1 = Math.floorDiv((int) Math.ceil(key.minU() + Projection.TILE_UNITS) + 1, 16);
		int cz0 = Math.floorDiv((int) Math.floor(Projection.zAt(minV - 1, missingTop)), 16);
		int cz1 = Math.floorDiv((int) Math.ceil(Projection.zAt(maxV + 1, startY)), 16);
		int[][] tall = new int[cx1 - cx0 + 1][];
		for (int cx = cx0; cx <= cx1; cx++) {
			IntArrayList column = new IntArrayList();
			for (int cz = cz1; cz >= cz0; cz--) {
				ChunkSnapshot chunk = source.chunk(cx, cz);
				if (chunk != null && chunk.maxY >= ORDINARY_TOP) {
					column.add(cz);
					column.add(chunk.maxY + 1);
				}
			}
			tall[cx - cx0] = column.toIntArray();
		}
		return new TallChunks(missingTop, cx0, tall);
	}

	/**
	 * Where the ray through map point {@code (x, v)} can first meet a block, as a height on the
	 * ray: it saves walking all the air above. The ray's own chunks decide, not the tile's tallest.
	 */
	int rayStart(int x, double v, double sin, double cos) {
		int[] chunks = this.tall[(x >> 4) - this.firstChunkX];
		for (int i = 0; i < chunks.length; i += 2) {
			int top = chunks[i + 1];
			if ((chunks[i] << 4) * sin - v < top * cos) {
				// The ray leaves this chunk below its top, so it comes in at the top or at the south edge.
				double southEdge = (((chunks[i] + 1) << 4) * sin - v) / cos;
				return Math.max(this.missingTop, Math.min(top, (int) Math.ceil(southEdge)));
			}
		}
		return this.missingTop;
	}
}
