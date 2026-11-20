package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * What {@link GpuMesher} found for each column of x the last time it meshed a tile, so the next
 * mesh of that tile takes the columns nothing has changed under instead of walking them again.
 * While the land around a tile loads it is meshed about ten times, and most of its columns come
 * out the same each time.
 *
 * <p>A column's camera faces are worked out from the blocks, light and biome tints of the cells
 * its rays reach and the cells next to those, and from the tile's own settings. So a column keeps
 * the chunks under that reach, each by its {@link ChunkSnapshot#blocksToken}, light tokens and
 * tints, and is taken again only when the tile's settings are equal and every one of those chunks
 * is: the same tokens (the same blocks and light) and equal tints. Where its rays reached a chunk
 * the map has no data for, what it shows there comes from the tile's saved spots in that column,
 * which must be the same too. Only the sky view outside a roof is kept: cave slices and lifted
 * views read more.
 */
final class MeshColumns {
	/**
	 * How many ints the kept tiles may hold in all, about 16 MB for camera faces and as much for
	 * shadow casters: some 40 tiles, a flight's worth. The least recently meshed go first.
	 */
	private static final long MAX_INTS = 4L << 20;

	private static final java.util.LinkedHashMap<TileKey, Tile> TILES = new java.util.LinkedHashMap<>(64, 0.75F, true);
	private static long ints;

	private MeshColumns() {
	}

	/** The tile's settings a column's faces depend on besides its chunks. */
	record Settings(Looks looks, Object style, @Nullable Overlays overlays, double sin, double cos, Object facing, int startY, int limitY,
		int rockLimit, double minV, int passesMissingFrom) {
	}

	/** One column as last meshed; see {@link #matches}. */
	static final class Column {
		/** Its faces, packed (see {@code Quads.copyOut}): opaque, then water, then plants and glass. */
		Slabs.Run faces;
		final int[] counts = new int[3];
		boolean traceInstead;
		int lowestY;
		/** Whether its rays reached any cell, and the cells' extent. */
		boolean reached;
		int y0;
		int y1;
		int z0;
		int z1;
		/** The chunks under its reach: x from cx0, z from cz0, {@link #DEPS} entries each. */
		int cx0;
		int cz0;
		int cxCount;
		int czCount;
		@Nullable Object[] deps;
		/**
		 * Where its rays reached a chunk the map has no data for, what they did there came from the
		 * tile's saved spots in this column (see {@link TileSpots#passedWhenSaved}): those, or null
		 * where there were none.
		 */
		boolean usesSpots;
		Slabs.@Nullable Run spots;

		/** The same column with its ints in other runs; nothing writes a kept column once a tile holds it. */
		Column with(Slabs.Run faces, Slabs.@Nullable Run spots) {
			Column copy = new Column();
			copy.faces = faces;
			System.arraycopy(this.counts, 0, copy.counts, 0, 3);
			copy.traceInstead = this.traceInstead;
			copy.lowestY = this.lowestY;
			copy.reached = this.reached;
			copy.y0 = this.y0;
			copy.y1 = this.y1;
			copy.z0 = this.z0;
			copy.z1 = this.z1;
			copy.cx0 = this.cx0;
			copy.cz0 = this.cz0;
			copy.cxCount = this.cxCount;
			copy.czCount = this.czCount;
			copy.deps = this.deps;
			copy.usesSpots = this.usesSpots;
			copy.spots = spots;
			return copy;
		}
	}

	/** Per chunk: its blocks, block light and sky light tokens, and its tints (null where none were blended). */
	static final int DEPS = 4;

	static final class Tile {
		final Settings settings;
		final int x0;
		final @Nullable Column[] columns;
		long size;
		/** The oldest {@link Slabs} array its columns' ints are in. */
		long oldest = Slabs.serial();

		Tile(Settings settings, int x0, int width) {
			this.settings = settings;
			this.x0 = x0;
			this.columns = new Column[width];
		}

		/** Notes a column kept in it. */
		void add(Column column) {
			this.size += column.faces.length + column.deps.length;
			this.oldest = Math.min(this.oldest, column.faces.serial);
			if (column.spots != null) {
				this.oldest = Math.min(this.oldest, column.spots.serial);
			}
		}
	}

	static synchronized @Nullable Tile get(TileKey key) {
		Tile tile = TILES.get(key);
		return tile != null && Slabs.live(tile.oldest) ? tile : null;
	}

	static synchronized void put(TileKey key, Tile tile) {
		Tile old = TILES.put(key, tile);
		if (old != null) {
			ints -= old.size;
		}
		ints += tile.size;
		java.util.Iterator<Tile> it = TILES.values().iterator();
		while (ints > MAX_INTS && it.hasNext()) {
			Tile t = it.next();
			if (t == tile) {
				break;
			}
			ints -= t.size;
			it.remove();
		}
		// Tiles whose ints are in arrays the store has moved on from: dropped, so the arrays can go.
		for (it = TILES.values().iterator(); it.hasNext(); ) {
			Tile t = it.next();
			if (!Slabs.live(t.oldest)) {
				ints -= t.size;
				it.remove();
			}
		}
	}

	/** What a tile's shadow casters depend on besides the chunks: their region, as {@link GpuMesher}'s casters work it out. */
	record CasterSettings(Looks looks, int x0, int x1, int z0, int z1, int bottom, int top) {
	}

	/** One x of a tile's shadow casters as last meshed: its faces, z by z, packed (see {@code Quads.copyCasters}), and the chunks they came from. */
	static final class CasterRow {
		Slabs.Run faces;
		int count;
		int highest;
		/** The chunks of x - 1 and x, z by z from {@link #cz0}, by blocks token; {@link ChunkBakeData#MISSING} where none. */
		int cz0;
		int czCount;
		Object[] chunks;

		/** The same row with its faces in another run. */
		CasterRow with(Slabs.Run faces) {
			CasterRow copy = new CasterRow();
			copy.faces = faces;
			copy.count = this.count;
			copy.highest = this.highest;
			copy.cz0 = this.cz0;
			copy.czCount = this.czCount;
			copy.chunks = this.chunks;
			return copy;
		}
	}

	static final class CasterTile {
		final CasterSettings settings;
		final @Nullable CasterRow[] rows;
		long size;
		/** The oldest {@link Slabs} array its rows' ints are in. */
		long oldest = Slabs.serial();

		CasterTile(CasterSettings settings, int width) {
			this.settings = settings;
			this.rows = new CasterRow[width];
		}

		/** Notes a row kept in it. */
		void add(CasterRow row) {
			this.size += row.faces.length + row.chunks.length;
			this.oldest = Math.min(this.oldest, row.faces.serial);
		}
	}

	private static final java.util.LinkedHashMap<TileKey, CasterTile> CASTERS = new java.util.LinkedHashMap<>(64, 0.75F, true);
	private static long casterInts;

	static synchronized @Nullable CasterTile casters(TileKey key, CasterSettings settings) {
		CasterTile tile = CASTERS.get(key);
		return tile != null && tile.settings.equals(settings) && Slabs.live(tile.oldest) ? tile : null;
	}

	static synchronized void put(TileKey key, CasterTile tile) {
		CasterTile old = CASTERS.put(key, tile);
		if (old != null) {
			casterInts -= old.size;
		}
		casterInts += tile.size;
		java.util.Iterator<CasterTile> it = CASTERS.values().iterator();
		while (casterInts > MAX_INTS && it.hasNext()) {
			CasterTile t = it.next();
			if (t == tile) {
				break;
			}
			casterInts -= t.size;
			it.remove();
		}
		for (it = CASTERS.values().iterator(); it.hasNext(); ) {
			CasterTile t = it.next();
			if (!Slabs.live(t.oldest)) {
				casterInts -= t.size;
				it.remove();
			}
		}
	}

	/**
	 * Records the chunks an x of shadow casters from z {@code z0} to {@code z1} came from: its own and,
	 * for the faces on a chunk's west or south edge, the chunk west of it and the one south of the last.
	 */
	static void depend(CasterRow row, VoxelReader reader, int x, int z0, int z1) {
		row.cz0 = z0 >> 4;
		row.czCount = ((z1 + 1) >> 4) - row.cz0 + 1;
		row.chunks = new Object[row.czCount * 2];
		fill(row.chunks, reader, x, row.cz0, row.czCount);
	}

	static boolean matches(CasterRow row, VoxelReader reader, int x) {
		for (int k = 0; k < row.czCount; k++) {
			for (int w = 0; w < 2; w++) {
				ChunkSnapshot chunk = reader.snapshot(x - 1 + w, (row.cz0 + k) << 4);
				if ((chunk == null ? ChunkBakeData.MISSING : chunk.blocksToken()) != row.chunks[k * 2 + w]) {
					return false;
				}
			}
		}
		return true;
	}

	private static void fill(Object[] chunks, VoxelReader reader, int x, int cz0, int count) {
		for (int k = 0; k < count; k++) {
			for (int w = 0; w < 2; w++) {
				ChunkSnapshot chunk = reader.snapshot(x - 1 + w, (cz0 + k) << 4);
				chunks[k * 2 + w] = chunk == null ? ChunkBakeData.MISSING : chunk.blocksToken();
			}
		}
	}

	/** Forgets every tile; nothing else depends on them. */
	static synchronized void clear() {
		TILES.clear();
		ints = 0;
		CASTERS.clear();
		casterInts = 0;
	}

	/** Records which chunks a column reached and what they hold now, as the reader has them. */
	static void depend(Column column, VoxelReader reader, int x, int z0, int z1) {
		int cx0 = (x - 1) >> 4;
		int cx1 = (x + 1) >> 4;
		int cz0 = (z0 - 1) >> 4;
		int cz1 = (z1 + 1) >> 4;
		column.cx0 = cx0;
		column.cz0 = cz0;
		column.cxCount = cx1 - cx0 + 1;
		column.czCount = cz1 - cz0 + 1;
		Object[] deps = new Object[column.cxCount * column.czCount * DEPS];
		int k = 0;
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				ChunkSnapshot chunk = reader.snapshot(cx << 4, cz << 4);
				if (chunk == null) {
					deps[k] = ChunkBakeData.MISSING;
				} else {
					deps[k] = chunk.blocksToken();
					deps[k + 1] = chunk.blockLightToken();
					deps[k + 2] = chunk.skyLightToken();
					deps[k + 3] = chunk.tints();
				}
				k += DEPS;
			}
		}
		column.deps = deps;
	}

	/** The saved spots of the tile's block column {@code column}, top row first, kept, or null where there are none. */
	static Slabs.@Nullable Run spotColumn(int @Nullable [] spots, int column) {
		if (spots == null || column < 0 || column >= TileSpots.COLUMNS) {
			return null;
		}
		int[] out = scratch(TileSpots.ROWS);
		for (int row = 0; row < TileSpots.ROWS; row++) {
			out[row] = spots[row * TileSpots.COLUMNS + column];
		}
		return Slabs.keep(out, 0, TileSpots.ROWS);
	}

	static boolean sameSpots(Slabs.@Nullable Run kept, int @Nullable [] spots, int column) {
		if (spots == null || column < 0 || column >= TileSpots.COLUMNS) {
			return kept == null;
		}
		if (kept == null) {
			return false;
		}
		int[] slab = kept.ints();
		int at = kept.offset;
		for (int row = 0; row < TileSpots.ROWS; row++) {
			if (spots[row * TileSpots.COLUMNS + column] != slab[at + row]) {
				return false;
			}
		}
		return true;
	}

	private static final ThreadLocal<int[]> SCRATCH = ThreadLocal.withInitial(() -> new int[4096]);

	/** This thread's array of at least {@code length} ints, for packing a run before {@link Slabs#keep} copies it. */
	static int[] scratch(int length) {
		int[] scratch = SCRATCH.get();
		if (scratch.length < length) {
			scratch = new int[Math.max(length, scratch.length * 2)];
			SCRATCH.set(scratch);
		}
		return scratch;
	}

	/**
	 * Whether every chunk the column reached holds what it did, as this reader has them, and the
	 * saved spots it read, if any, are the same: {@code spots} as the tile's block column {@code column}.
	 */
	static boolean matches(Column column, VoxelReader reader, int @Nullable [] spots, int spotColumn) {
		if (column.usesSpots && !sameSpots(column.spots, spots, spotColumn)) {
			return false;
		}
		Object[] deps = column.deps;
		int k = 0;
		for (int cx = column.cx0; cx < column.cx0 + column.cxCount; cx++) {
			for (int cz = column.cz0; cz < column.cz0 + column.czCount; cz++) {
				ChunkSnapshot chunk = reader.snapshot(cx << 4, cz << 4);
				if (chunk == null) {
					if (deps[k] != ChunkBakeData.MISSING) {
						return false;
					}
				} else if (deps[k] != chunk.blocksToken() || deps[k + 1] != chunk.blockLightToken() || deps[k + 2] != chunk.skyLightToken()
					|| !sameTints((ChunkSnapshot.Tints) deps[k + 3], chunk)) {
					return false;
				}
				k += DEPS;
			}
		}
		return true;
	}

	/**
	 * Whether the chunk's tints are what the column was meshed with. Tints are blended from the
	 * chunk's biomes, so a chunk none were blended for when the column was meshed gave it none.
	 */
	private static boolean sameTints(ChunkSnapshot.@Nullable Tints kept, ChunkSnapshot chunk) {
		if (kept == null) {
			return true;
		}
		ChunkSnapshot.Tints now = chunk.tints();
		if (now == kept) {
			return true;
		}
		if (now == null) {
			now = BiomeTints.of(chunk);
		}
		return Arrays.equals(now.grass(), kept.grass()) && Arrays.equals(now.foliage(), kept.foliage())
			&& Arrays.equals(now.dryFoliage(), kept.dryFoliage()) && Arrays.equals(now.water(), kept.water());
	}
}
