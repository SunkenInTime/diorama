package dev.diorama.bake;

import dev.diorama.Projection;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Where the right-click menu has a player stand, for every block column and pixel row of a
 * surface tile, saved with the tile. The map keeps showing a saved tile after its chunks are
 * gone (a restart, a trip far away), and the menu needs the chunks to work out how high the
 * ground is; these answers stand in for them.
 *
 * <p>One int per sample, column by column within a row: {@link #UNKNOWN}, {@link #NOTHING}, or a
 * spot. A spot holds its y and how many blocks short of the ray's crossing of that height its z
 * is (more than none when the ray hit the side of a block), so it reads back with the sample's own
 * row and takes three bytes.
 */
public final class TileSpots {
	public static final int COLUMNS = Projection.TILE_UNITS;
	public static final int ROWS = Projection.TILE_PIXELS;
	/** No answer: the chunks weren't there when the tile was baked. */
	public static final int UNKNOWN = 0;
	/** Nothing to stand on. */
	public static final int NOTHING = 1 << 24;
	private static final int SPOT = 2 << 24;
	/**
	 * In a mesh's {@link GpuMesher.Mesh#spots} only: the ray ran into a chunk the map has no data for,
	 * where {@link #fromMesh} keeps the saved answer. {@link #UNKNOWN} there is a spot too far to encode.
	 */
	static final int MISSING = 3 << 24;
	/** Columns under a patch: a patch is {@link TileImage#BLOCK} pixels square. */
	public static final int PATCH_COLUMNS = TileImage.BLOCK / Projection.PIXELS_PER_UNIT;

	private TileSpots() {
	}

	/**
	 * The answers for a bake of a surface tile: every sample of a full image, or those under its
	 * patches (the rest stay {@link #UNKNOWN}). Where the chunks are missing, the answer saved
	 * before stays, as the bake keeps saved pixels. Null when that leaves nothing known, so a tile
	 * saved before spots isn't written again for no gain.
	 */
	public static int @Nullable [] pick(TileImage image, ChunkSource source, BlockLooks looks, boolean fogOfWar, int @Nullable [] saved) {
		if (!image.isFull() && image.patches().isEmpty()) {
			return null;
		}
		TileKey key = image.key();
		MapPick pick = new MapPick(source, looks, key, fogOfWar).passWhereSaved(saved);
		int[] spots = new int[COLUMNS * ROWS];
		boolean known = false;
		if (image.isFull()) {
			known = pick(key, pick, spots, saved, 0, 0, COLUMNS, ROWS);
		} else {
			for (TileImage.Patch patch : image.patches()) {
				known |= pick(key, pick, spots, saved, patchColumn(patch), patch.blockY() * TileImage.BLOCK, PATCH_COLUMNS, TileImage.BLOCK);
			}
		}
		return known ? spots : null;
	}

	/**
	 * The answers for a full image from a mesh's {@link GpuMesher.Mesh#spots}: where a ray ran into
	 * a chunk the map has no data for, the answer saved before stays, as {@link #pick} has it.
	 */
	public static int @Nullable [] fromMesh(int[] spots, int @Nullable [] saved) {
		boolean known = false;
		for (int i = 0; i < spots.length; i++) {
			if (spots[i] == MISSING) {
				spots[i] = saved == null ? UNKNOWN : saved[i];
			}
			known |= spots[i] != UNKNOWN;
		}
		return known ? spots : null;
	}

	/** The first column under a patch. */
	public static int patchColumn(TileImage.Patch patch) {
		return patch.blockX() * PATCH_COLUMNS;
	}

	/** Fills in a rectangle of samples; returns whether any is known. */
	private static boolean pick(TileKey key, MapPick pick, int[] spots, int @Nullable [] saved, int column0, int row0, int columns, int rows) {
		boolean known = false;
		for (int row = row0; row < row0 + rows; row++) {
			double v = v(key, row);
			for (int column = column0; column < column0 + columns; column++) {
				int i = row * COLUMNS + column;
				spots[i] = switch (pick.pick(key.minU() + column + 0.5, v)) {
					case MapPick.FOUND -> encode(pick.y, pick.z, v);
					case MapPick.NOTHING -> NOTHING;
					default -> saved == null ? UNKNOWN : saved[i];
				};
				known |= spots[i] != UNKNOWN;
			}
		}
		return known;
	}

	/**
	 * The saved spot at map point {@code (u, v)} of a level-0 surface tile, or null where nothing
	 * was saved or there's nothing to stand on.
	 */
	public static @Nullable BlockPos spot(TileKey key, int @Nullable [] spots, double u, double v) {
		if (spots == null) {
			return null;
		}
		int i = index(key, u, v);
		int value = spots[i];
		if ((value & ~0xFFFFFF) != SPOT) {
			return null;
		}
		int column = i % COLUMNS;
		int row = i / COLUMNS;
		int y = (short) value;
		int z = (int) Math.floor(Projection.zAt(v(key, row), y)) - ((value >>> 16) & 0xFF);
		return new BlockPos((int) Math.floor(key.minU()) + column, y, z);
	}

	/**
	 * Whether the spots answer for map point {@code (u, v)} of a level-0 surface tile: they were
	 * saved, and the chunks were there when that part was baked.
	 */
	public static boolean known(TileKey key, int @Nullable [] spots, double u, double v) {
		return spots != null && spots[index(key, u, v)] != UNKNOWN;
	}

	/**
	 * The z of the block a sample stands on, or {@link Integer#MIN_VALUE} where the sample has no
	 * spot (unknown, or nothing to stand on).
	 */
	public static int z(TileKey key, int[] spots, int column, int row) {
		int value = spots[row * COLUMNS + column];
		if ((value & ~0xFFFFFF) != SPOT) {
			return Integer.MIN_VALUE;
		}
		return (int) Math.floor(Projection.zAt(v(key, row), (short) value)) - ((value >>> 16) & 0xFF);
	}

	/** The sample at map point {@code (u, v)} of a level-0 tile. */
	private static int index(TileKey key, double u, double v) {
		// Clamped as longs (Math.clamp is Java 21; the 1.20.1 jar is built for 17).
		int column = (int) Math.max(0L, Math.min(COLUMNS - 1L, (long) Math.floor(u - key.minU())));
		int row = (int) Math.max(0L, Math.min(ROWS - 1L, (long) Math.floor((v - key.minV()) * Projection.PIXELS_PER_UNIT)));
		return row * COLUMNS + column;
	}

	/** The map v a sample answers for: the middle of its pixel row. */
	private static double v(TileKey key, int row) {
		return v(key.minV(), row);
	}

	/** The v of a row's samples in a tile whose v starts at {@code minV}. */
	static double v(double minV, int row) {
		return minV + (row + 0.5) / Projection.PIXELS_PER_UNIT;
	}

	/**
	 * The middle of the pixel row map row {@code v} falls in. The map shows one colour per pixel
	 * row, so a pick there answers for what the pixel shows, and live picks agree with saved ones.
	 */
	public static double rowMiddle(double v) {
		return (Math.floor(v * Projection.PIXELS_PER_UNIT) + 0.5) / Projection.PIXELS_PER_UNIT;
	}

	/**
	 * Whether the saved ray through block column {@code x} and map row {@code v} of a level-0
	 * surface tile passed through height {@code y} on its way down: it stood on something lower, so
	 * whatever it crossed up there was open when the tile was saved.
	 *
	 * <p>A ray that reaches a chunk the map has no data for can take the cell as open on that word.
	 * Rays start at the top of the tallest chunk around the tile, so where a hill or a tower stands
	 * nearby they cross chunks well south of what they land on; on a server those are often chunks
	 * that are never sent while the player is close. Without this, the fresh pixel comes out less
	 * known than the saved one, the saved one stays, and nothing built there since ever shows.
	 * Below the saved spot the cell stays unknown: the saved ray may have landed in that chunk. A ray
	 * goes on through glass and plants, so glass or a tall plant in a chunk taken as open draws as
	 * air until the chunk is back; only its tint is lost.
	 */
	private static boolean passedWhenSaved(TileKey key, int @Nullable [] spots, int x, int y, double v) {
		if (spots == null) {
			return false;
		}
		long column = x - (long) Math.floor(key.minU());
		long row = (long) Math.floor((v - key.minV()) * Projection.PIXELS_PER_UNIT);
		if (column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) {
			return false;
		}
		int value = spots[(int) row * COLUMNS + (int) column];
		return (value & ~0xFFFFFF) == SPOT && y > (short) value;
	}

	/**
	 * Whether every saved ray of the tile's pixel rows crossing block {@code (x, y, z)} passed
	 * through it, so the block can be taken as open; false if none of them is in the tile. A spot
	 * comes from the middle of its row, while a pixel's samples spread across it, so one row's word
	 * isn't enough: at a tower's edge the middle can pass while a sample hits. Only rows whose middle
	 * crosses the block speak for it, so at least one has to, and every row reaching it has to agree.
	 * The tracer, the GPU mesher and the pick all ask this, so they agree.
	 */
	public static boolean passedWhenSaved(TileKey key, int @Nullable [] spots, int x, int y, int z) {
		if (spots == null) {
			return false;
		}
		double sin = Projection.sin();
		double cos = Projection.cos();
		double first = Math.max(key.minV(), z * sin - (y + 1) * cos);
		double last = Math.min(key.minV() + Projection.TILE_UNITS, (z + 1) * sin - y * cos);
		if (first >= last) {
			return false;
		}
		double step = 1.0 / Projection.PIXELS_PER_UNIT;
		boolean crossed = false;
		for (double v = rowMiddle(first); v - step / 2 < last; v += step) {
			if (!passedWhenSaved(key, spots, x, y, v)) {
				return false;
			}
			crossed |= v >= first && v < last;
		}
		return crossed;
	}

	static int encode(int y, int z, double v) {
		int back = (int) Math.floor(Projection.zAt(v, y)) - z;
		if (back < 0 || back > 0xFF || y != (short) y) {
			return UNKNOWN;
		}
		return SPOT | back << 16 | (y & 0xFFFF);
	}
}
