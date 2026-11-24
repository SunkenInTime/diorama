package dev.diorama.data;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Set;
import java.util.function.DoubleConsumer;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * Saves part of the map as a PNG, one row of tiles at a time, so a map of any size fits in memory:
 * the sky view from the tiles on disk, or any other {@link Tiles}.
 *
 * <p>Areas are in map pixels at one overview level: at level {@code lod} a pixel covers
 * {@code 2^lod / PIXELS_PER_UNIT} map units, so level 0 is the map's full detail, four pixels per
 * block, and each level up halves that.
 */
public final class ImageExport {
	private static final int TILE = Projection.TILE_PIXELS;
	/** The coarsest level an export offers: half a pixel per block. */
	public static final int COARSEST = 3;
	/** No export is larger than this many pixels a side; most viewers and chat apps give up past it. */
	public static final int MAX_EDGE = 16384;
	/** The detail an export starts at is the sharpest that keeps its long side within this. */
	public static final int DEFAULT_EDGE = 8192;
	/** Whole-map exports get this many map units of fog around what's explored, so its edge shows. */
	public static final double WHOLE_MAP_MARGIN = 32;
	/**
	 * Bytes per pixel of a saved PNG with fog, by level, measured on Hermitcraft: finer levels
	 * compress better, since neighbouring pixels repeat.
	 */
	private static final double[] BYTES_PER_PIXEL = {1.1, 2.0, 2.3, 2.4};

	private ImageExport() {
	}

	/** A rectangle of map pixels at overview level {@code lod}. */
	public record Area(int lod, long x, long y, int width, int height) {
		/** The same part of the map at another level, grown to whole pixels there. */
		public Area at(int lod) {
			if (lod == this.lod) {
				return this;
			}
			double scale = Math.pow(2, this.lod - lod);
			long x0 = (long) Math.floor(this.x * scale);
			long y0 = (long) Math.floor(this.y * scale);
			long x1 = (long) Math.ceil((this.x + this.width) * scale);
			long y1 = (long) Math.ceil((this.y + this.height) * scale);
			return new Area(lod, x0, y0, (int) Math.max(1, x1 - x0), (int) Math.max(1, y1 - y0));
		}

		/** The area covering map units {@code (u0, v0)} to {@code (u1, v1)} at a level. */
		public static Area ofUnits(int lod, double u0, double v0, double u1, double v1) {
			double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << lod);
			long x0 = (long) Math.floor(Math.min(u0, u1) * perUnit);
			long y0 = (long) Math.floor(Math.min(v0, v1) * perUnit);
			long x1 = (long) Math.ceil(Math.max(u0, u1) * perUnit);
			long y1 = (long) Math.ceil(Math.max(v0, v1) * perUnit);
			return new Area(lod, x0, y0, (int) Math.max(1, x1 - x0), (int) Math.max(1, y1 - y0));
		}

		/**
		 * The pixels whose middles are within map units {@code (u0, v0)} to {@code (u1, v1)} at a
		 * level: an edge that falls inside a pixel goes to whichever side holds more of it, so no row
		 * or column of the image is wholly outside the units asked for. Units narrower than a pixel
		 * that hold no middle get the one pixel their own middle is in.
		 */
		public static Area within(int lod, double u0, double v0, double u1, double v1) {
			double perUnit = Projection.PIXELS_PER_UNIT / (double) (1 << lod);
			long[] x = within(Math.min(u0, u1) * perUnit, Math.max(u0, u1) * perUnit);
			long[] y = within(Math.min(v0, v1) * perUnit, Math.max(v0, v1) * perUnit);
			return new Area(lod, x[0], y[0], (int) Math.min(Integer.MAX_VALUE, x[1] - x[0]), (int) Math.min(Integer.MAX_VALUE, y[1] - y[0]));
		}

		/** The pixels with their middles from {@code from} to {@code to}, first and one past the last. */
		private static long[] within(double from, double to) {
			long first = (long) Math.ceil(from - 0.5);
			long end = (long) Math.ceil(to - 0.5);
			if (end <= first) {
				first = (long) Math.floor((from + to) / 2);
				end = first + 1;
			}
			return new long[] {first, end};
		}

		/** Map units across one pixel. */
		public double unitsPerPixel() {
			return (double) (1 << this.lod) / Projection.PIXELS_PER_UNIT;
		}

		/** The area in map units: {u0, v0, u1, v1}. */
		public double[] units() {
			double k = this.unitsPerPixel();
			return new double[] {this.x * k, this.y * k, (this.x + this.width) * k, (this.y + this.height) * k};
		}

		public long pixels() {
			return (long) this.width * this.height;
		}
	}

	/**
	 * The levels the map units {@code (u0, v0)} to {@code (u1, v1)} can be saved at, finest first:
	 * those from 0 to {@link #COARSEST} whose image fits {@link #MAX_EDGE}. Empty for an area too big
	 * for any of them.
	 */
	public static java.util.List<Integer> levels(double u0, double v0, double u1, double v1) {
		java.util.List<Integer> levels = new java.util.ArrayList<>();
		for (int lod = 0; lod <= COARSEST; lod++) {
			Area area = Area.ofUnits(lod, u0, v0, u1, v1);
			if (Math.max(area.width(), area.height()) <= MAX_EDGE) {
				levels.add(lod);
			}
		}
		return levels;
	}

	/** The level an export of these map units starts at: the finest within {@link #DEFAULT_EDGE}, else the coarsest allowed. */
	public static int defaultLevel(double u0, double v0, double u1, double v1) {
		java.util.List<Integer> levels = levels(u0, v0, u1, v1);
		for (int lod : levels) {
			Area area = Area.ofUnits(lod, u0, v0, u1, v1);
			if (Math.max(area.width(), area.height()) <= DEFAULT_EDGE) {
				return lod;
			}
		}
		return levels.isEmpty() ? COARSEST : levels.get(levels.size() - 1);
	}

	/** Roughly how big the PNG of {@code area} will be, with fog. */
	public static long estimateBytes(Area area) {
		return Math.round(area.pixels() * BYTES_PER_PIXEL[Math.min(area.lod(), BYTES_PER_PIXEL.length - 1)]);
	}

	/**
	 * Everything mapped, at level {@code lod}: the smallest rectangle holding every known pixel,
	 * found on a coarse overview level and so a few pixels generous. Null if nothing is mapped.
	 */
	public static @Nullable Area explored(TileStore store, int lod) {
		int coarse = Math.min(TileKey.MAX_LOD, lod + 3);
		Set<TileKey> keys = store.saved(coarse);
		long minX = Long.MAX_VALUE;
		long minY = Long.MAX_VALUE;
		long maxX = Long.MIN_VALUE;
		long maxY = Long.MIN_VALUE;
		for (TileKey key : keys) {
			int[] pixels = store.read(key);
			if (pixels == null) {
				continue;
			}
			for (int y = 0; y < TILE; y++) {
				for (int x = 0; x < TILE; x++) {
					if ((pixels[y * TILE + x] >>> 24) != 0) {
						long gx = (long) key.tileU() * TILE + x;
						long gy = (long) key.tileV() * TILE + y;
						minX = Math.min(minX, gx);
						minY = Math.min(minY, gy);
						maxX = Math.max(maxX, gx);
						maxY = Math.max(maxY, gy);
					}
				}
			}
		}
		if (minX > maxX) {
			return null;
		}
		long width = maxX - minX + 1;
		long height = maxY - minY + 1;
		if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
			throw new IllegalStateException("Mapped area too large");
		}
		return new Area(coarse, minX, minY, (int) width, (int) height).at(lod);
	}

	/**
	 * Where an export's tiles come from: the saved sky view, or a view that isn't saved and is drawn
	 * for the export. Read from several threads at once.
	 */
	public interface Tiles {
		/** The tile at an overview level, or null where nothing is mapped. */
		TileStore.@Nullable Saved tile(int tileU, int tileV, int lod);

		/**
		 * Gets {@code area} ready before anything is read, where that takes long, reporting progress
		 * from 0 to 1.
		 */
		default void prepare(Area area, DoubleConsumer progress) {
		}

		/** The share of an export's time {@link #prepare} takes, 0 to 1, for its progress. */
		default double prepareShare() {
			return 0;
		}

		/** The saved sky view. */
		static Tiles of(TileStore store) {
			return (tileU, tileV, lod) -> store.readSaved(new TileKey(TileKey.SURFACE, tileU, tileV, lod));
		}
	}

	/** What is done to each row of map pixels before it's saved: a backdrop, the fog. */
	public interface Finish {
		/** Leaves unmapped ground see-through. */
		Finish NONE = (abgr, y, x0) -> {
		};

		/** Changes the row's ABGR pixels in place: row {@code y} of the map from pixel {@code x0}. */
		void row(int[] abgr, long y, long x0);

		/** This, then {@code next}, on each row. */
		default Finish then(Finish next) {
			return (abgr, y, x0) -> {
				this.row(abgr, y, x0);
				next.row(abgr, y, x0);
			};
		}

		/** The map over an opaque ARGB colour. */
		static Finish over(int argb) {
			return (abgr, y, x0) -> {
				for (int x = 0; x < abgr.length; x++) {
					abgr[x] = ImageExport.over(abgr[x], argb);
				}
			};
		}
	}

	/**
	 * Writes {@code area} as a PNG to {@code file}, each row finished by {@code finish}. Reports
	 * progress from 0 to 1. Slow for big areas; not on the render thread.
	 */
	public static void write(TileStore store, Area area, Path file, Finish finish, DoubleConsumer progress) throws IOException {
		write(store, area, file, finish, 0, progress);
	}

	/**
	 * The same, the tiles drawn {@code night} into the night as the map draws them
	 * ({@link dev.diorama.render.NightLook}), before {@code finish}.
	 */
	public static void write(TileStore store, Area area, Path file, Finish finish, float night, DoubleConsumer progress) throws IOException {
		write(Tiles.of(store), area, file, finish, night, progress);
	}

	/** The same from any tiles. */
	public static void write(Tiles source, Area area, Path file, Finish finish, float night, DoubleConsumer progress) throws IOException {
		int lod = area.lod();
		long tileV0 = Math.floorDiv(area.y(), TILE);
		long tileV1 = Math.floorDiv(area.y() + area.height() - 1, TILE);
		long tileU0 = Math.floorDiv(area.x(), TILE);
		long tileU1 = Math.floorDiv(area.x() + area.width() - 1, TILE);
		int columns = (int) (tileU1 - tileU0 + 1);
		int[][] tiles = new int[columns][];
		byte[][] lights = new byte[columns][];
		int[][] band = new int[TILE][];
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.createDirectories(file.toAbsolutePath().getParent());
		try (OutputStream stream = Files.newOutputStream(temp); PngWriter png = new PngWriter(stream, area.width(), area.height())) {
			png.text("Software", "Diorama");
			for (long tv = tileV0; tv <= tileV1; tv++) {
				for (int c = 0; c < columns; c++) {
					TileStore.Saved saved = source.tile((int) (tileU0 + c), (int) tv, lod);
					tiles[c] = saved == null ? null : saved.pixels();
					lights[c] = saved == null ? null : saved.blockLight();
				}
				long rowStart = Math.max(area.y(), tv * TILE);
				int rows = (int) (Math.min(area.y() + area.height(), (tv + 1) * TILE) - rowStart);
				// Rows are finished in parallel: the fog takes most of the time.
				IntStream.range(0, rows).parallel().forEach(r -> {
					int[] row = band[r] == null || band[r].length != area.width() ? new int[area.width()] : band[r];
					band[r] = row;
					long gy = rowStart + r;
					int ty = (int) (gy - Math.floorDiv(gy, TILE) * TILE);
					for (int c = 0; c < columns; c++) {
						long tileX0 = (tileU0 + c) * TILE;
						int from = (int) Math.max(0, area.x() - tileX0);
						int to = (int) Math.min(TILE, area.x() + area.width() - tileX0);
						int at = (int) (tileX0 + from - area.x());
						int[] tile = tiles[c];
						if (tile == null) {
							Arrays.fill(row, at, at + to - from, 0);
						} else {
							System.arraycopy(tile, ty * TILE + from, row, at, to - from);
							if (night > 0) {
								byte[] light = lights[c];
								for (int x = from; x < to; x++) {
									row[at + x - from] = dev.diorama.render.NightLook.light(row[at + x - from], light == null ? 0 : lightAt(light, x, ty), night);
								}
							}
						}
					}
					finish.row(row, gy, area.x());
				});
				for (int r = 0; r < rows; r++) {
					png.row(band[r], 0);
				}
				progress.accept((double) (tv - tileV0 + 1) / (tileV1 - tileV0 + 1));
			}
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(temp);
			throw e;
		}
		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(temp);
			throw e;
		}
	}

	/** A tile's block light (0..1) under its pixel (x, y), blended between the values around it. */
	private static float lightAt(byte[] light, int x, int y) {
		int edge = dev.diorama.bake.BlockLight.EDGE;
		float fx = (x + 0.5F) / dev.diorama.bake.BlockLight.SCALE - 0.5F;
		float fy = (y + 0.5F) / dev.diorama.bake.BlockLight.SCALE - 0.5F;
		int x0 = (int) Math.floor(fx);
		int y0 = (int) Math.floor(fy);
		float tx = fx - x0;
		float ty = fy - y0;
		int xa = Math.max(0, x0);
		int xb = Math.min(edge - 1, x0 + 1);
		int ya = Math.max(0, y0);
		int yb = Math.min(edge - 1, y0 + 1);
		float top = (light[ya * edge + xa] & 0xFF) * (1 - tx) + (light[ya * edge + xb] & 0xFF) * tx;
		float bottom = (light[yb * edge + xa] & 0xFF) * (1 - tx) + (light[yb * edge + xb] & 0xFF) * tx;
		return (top * (1 - ty) + bottom * ty) / 255;
	}

	/**
	 * Deletes the half-written files exports leave in a folder when the game quits partway
	 * through one: they're named after the image with ".tmp" on the end.
	 */
	public static void deleteUnfinished(Path folder, String prefix) {
		if (!Files.isDirectory(folder)) {
			return;
		}
		try (java.util.stream.Stream<Path> files = Files.list(folder)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();
				if (name.startsWith(prefix) && name.endsWith(".png.tmp")) {
					Files.deleteIfExists(file);
				}
			}
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't clear unfinished exports in {}", folder, e);
		}
	}

	/** An ABGR pixel drawn over an opaque ARGB colour, as ABGR. */
	static int over(int abgr, int argb) {
		int a = abgr >>> 24;
		if (a == 255) {
			return abgr;
		}
		int br = (argb >> 16) & 0xFF;
		int bg = (argb >> 8) & 0xFF;
		int bb = argb & 0xFF;
		int r = ((abgr & 0xFF) * a + br * (255 - a)) / 255;
		int g = (((abgr >> 8) & 0xFF) * a + bg * (255 - a)) / 255;
		int b = (((abgr >> 16) & 0xFF) * a + bb * (255 - a)) / 255;
		return 0xFF000000 | (b << 16) | (g << 8) | r;
	}
}
