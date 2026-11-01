package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.data.TileLabel;
import dev.diorama.data.TileStore;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * What a map remembers while it's redrawn after its tiles' label changed: the tiles it had before,
 * fitted to the new view, soft and muted, under the map's clouds. The clouds go as real tiles land
 * on top. A memory can't be right in detail, since the old tiles show the world from another view,
 * so it stays too soft to pass for the map; nothing that needs an exact place reads it.
 *
 * <p>The folder being redrawn notes the folder it remembers in {@code redraw-from}. That's always a
 * folder of real tiles: redrawing a map that's being redrawn remembers whichever of the two shows
 * more. The note stays when the redraw ends, as a place the save has no chunks for has nothing else.
 *
 * <p>Render thread, except where a method says otherwise.
 */
public final class MapMemory {
	private static final String NOTE = "redraw-from";
	/** Texture pixels per tile edge. */
	private static final int EDGE = Projection.TILE_PIXELS / 4;
	private static final int SHRINK = Projection.TILE_PIXELS / EDGE;
	/** Remembered tiles kept on the GPU, each a tenth of a megabyte or so. */
	private static final int MAX_TILES = 96;
	/** The height memories are fitted at: higher ground lands a little off, which softness hides. */
	private static final int FIT_Y = 64;
	/** Screen pixels a memory's pixel spans at least, for softness. */
	private static final double SOFT_PIXELS = 3;
	/** The least overview level drawn, however far in the view is. */
	private static final int MIN_LOD = 2;
	/** How much of its colour a memory keeps, so it reads as a memory next to real tiles. */
	private static final float COLOUR = 0.5F;

	private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "diorama-map-memory");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private record Entry(@Nullable MapTexture texture, byte @Nullable [] known) {
	}

	private record Loaded(TileKey key, int @Nullable [] pixels, byte @Nullable [] known) {
	}

	private final TileStore store;
	private final double sin;
	private final double cos;
	private final LinkedHashMap<TileKey, Entry> tiles = new LinkedHashMap<>(16, 0.75F, true);
	private final Set<TileKey> loading = new HashSet<>();
	private final ConcurrentLinkedQueue<Loaded> loaded = new ConcurrentLinkedQueue<>();
	private long version;
	private volatile boolean closed;

	private MapMemory(Path folder, TileLabel label) {
		this.store = new TileStore(folder);
		this.sin = Math.sin(Math.toRadians(label.pitch()));
		this.cos = Math.cos(Math.toRadians(label.pitch()));
	}

	/** What {@code folder}'s map remembers, or null if it has nothing to remember. */
	static @Nullable MapMemory open(Path folder) {
		String from = note(folder);
		TileLabel label = from == null ? null : TileLabel.parse(from);
		return label == null || !Files.isDirectory(folder.resolveSibling(from)) ? null : new MapMemory(folder.resolveSibling(from), label);
	}

	/**
	 * The map is moving from folder {@code from} to {@code to}: notes what {@code to} is to remember,
	 * if it has no tiles of its own yet and {@code from} shows the same kind of view.
	 */
	static void noteMove(Path from, Path to) {
		TileLabel fromLabel = TileLabel.parse(from.getFileName().toString());
		TileLabel toLabel = TileLabel.parse(to.getFileName().toString());
		if (fromLabel == null || toLabel == null || !toLabel.canShow(fromLabel) || note(to) != null || tileCount(to) > 0) {
			return;
		}
		String source = from.getFileName().toString();
		String beyond = note(from);
		if (beyond != null && !beyond.equals(to.getFileName().toString()) && tileCount(from.resolveSibling(beyond)) > tileCount(from)) {
			source = beyond;
		}
		if (tileCount(from.resolveSibling(source)) == 0) {
			return;
		}
		try {
			Files.createDirectories(to);
			Files.writeString(to.resolve(NOTE), source, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't note what {} remembers", to, e);
		}
	}

	private static @Nullable String note(Path folder) {
		try {
			return Files.isRegularFile(folder.resolve(NOTE)) ? Files.readString(folder.resolve(NOTE), StandardCharsets.UTF_8).trim() : null;
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't read what {} remembers", folder, e);
			return null;
		}
	}

	private static long tileCount(Path folder) {
		if (!Files.isDirectory(folder)) {
			return 0;
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(file -> file.getFileName().toString().endsWith(".omt")).count();
		} catch (IOException | java.io.UncheckedIOException e) {
			return 0;
		}
	}

	/** The overview level a view draws memories from. */
	private static int lod(MapViewport.View view) {
		double blocksPerPixel = SOFT_PIXELS / (view.zoom() * Minecraft.getInstance().getWindow().getGuiScale());
		int lod = MIN_LOD;
		while (lod < TileKey.MAX_LOD && (1 << lod) < blocksPerPixel) {
			lod++;
		}
		return lod;
	}

	/** Goes up when more of the memory becomes known, for a fog mask built on it. */
	public long version() {
		return this.version;
	}

	/** Draws the memories under a view, and asks for the ones that aren't here yet. */
	void draw(MapCanvas canvas, MapViewport.View view, int tint) {
		this.takeLoaded();
		int lod = lod(view);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double minV = this.oldV(view.minV());
		double maxV = this.oldV(view.minV() + view.height() / view.zoom());
		int tu1 = (int) Math.floor((view.minU() + view.width() / view.zoom()) / units);
		int tv1 = (int) Math.floor(maxV / units);
		Circle circle = view.round() ? Circle.in(view) : null;
		for (int tu = (int) Math.floor(view.minU() / units); tu <= tu1; tu++) {
			for (int tv = (int) Math.floor(minV / units); tv <= tv1; tv++) {
				TileKey key = new TileKey(TileKey.SURFACE, tu, tv, lod);
				Entry entry = this.tiles.get(key);
				if (entry == null) {
					this.load(key);
				}
				if (entry == null || entry.texture() == null) {
					continue;
				}
				float x0 = view.screenX(tu * units);
				float x1 = view.screenX((tu + 1) * units);
				float y0 = view.screenY(this.newV(tv * units));
				float y1 = view.screenY(this.newV((tv + 1) * units));
				if (circle == null) {
					canvas.rect(entry.texture(), Sampling.TILE_SMOOTH, x0, y0, x1, y1, 0, 0, 1, 1, tint);
				} else {
					Circle.Quads inside = circle.clip(x0, y0, x1, y1, 0, 0, 1, 1);
					if (inside != null) {
						canvas.quad(entry.texture(), Sampling.TILE_SMOOTH, inside.corners(), inside.uvs(), tint);
					}
				}
			}
		}
		this.evict();
	}

	/** Which share of map point {@code (u, v)} a view's memory shows, 0 to 1, with what's loaded so far. */
	float known(MapViewport.View view, double u, double v) {
		int lod = lod(view);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double oldV = this.oldV(v);
		int tu = (int) Math.floor(u / units);
		int tv = (int) Math.floor(oldV / units);
		Entry entry = this.tiles.get(new TileKey(TileKey.SURFACE, tu, tv, lod));
		if (entry == null || entry.known() == null) {
			return 0;
		}
		int x = (int) Math.min(EDGE - 1L, (long) ((u - tu * units) / units * EDGE));
		int y = (int) Math.min(EDGE - 1L, (long) ((oldV - tv * units) / units * EDGE));
		return (entry.known()[y * EDGE + x] & 0xFF) / 255.0F;
	}

	/**
	 * Whether the remembered map shows anything of a chunk whose ground reaches up to {@code top},
	 * by its middle and corners. Not on the render thread: it can wait for the disk.
	 */
	boolean showsChunk(int chunkX, int chunkZ, int top) {
		int x0 = chunkX << 4;
		int z0 = chunkZ << 4;
		for (int[] at : new int[][] {{8, 8}, {0, 0}, {15, 0}, {0, 15}, {15, 15}}) {
			double u = x0 + at[0] + 0.5;
			double v = (z0 + at[1] + 0.5) * this.sin - top * this.cos;
			TileKey key = new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
			int[] pixels = this.store.read(key);
			if (pixels != null) {
				int size = Projection.TILE_PIXELS;
				int x = (int) Math.min(size - 1L, (long) ((u - key.minU()) * Projection.PIXELS_PER_UNIT));
				int y = (int) Math.min(size - 1L, (long) ((v - key.minV()) * Projection.PIXELS_PER_UNIT));
				if ((pixels[y * size + x] >>> 24) != 0) {
					return true;
				}
			}
		}
		return false;
	}

	/** The old map's row at a row of the map as it's drawn now. */
	private double oldV(double v) {
		return Projection.zAt(v, FIT_Y) * this.sin - FIT_Y * this.cos;
	}

	private double newV(double oldV) {
		return Projection.v(FIT_Y, (oldV + FIT_Y * this.cos) / this.sin);
	}

	private void load(TileKey key) {
		if (!this.loading.add(key)) {
			return;
		}
		LOADER.execute(() -> {
			int[] pixels = null;
			byte[] known = null;
			try {
				int[] saved = this.closed ? null : this.store.read(key);
				if (saved != null) {
					known = new byte[EDGE * EDGE];
					pixels = shrink(saved, known);
				}
			} catch (RuntimeException e) {
				Diorama.LOGGER.warn("Couldn't load the remembered tile {}", key, e);
			}
			this.loaded.add(new Loaded(key, pixels, known));
		});
	}

	/** A tile at {@link #EDGE} pixels, with its colour muted, and how much of each pixel is known. */
	private static int[] shrink(int[] tile, byte[] known) {
		int size = Projection.TILE_PIXELS;
		int[] out = new int[EDGE * EDGE];
		for (int y = 0; y < EDGE; y++) {
			for (int x = 0; x < EDGE; x++) {
				long r = 0;
				long g = 0;
				long b = 0;
				long a = 0;
				for (int dy = 0; dy < SHRINK; dy++) {
					int row = (y * SHRINK + dy) * size + x * SHRINK;
					for (int dx = 0; dx < SHRINK; dx++) {
						int pixel = tile[row + dx];
						int alpha = pixel >>> 24;
						r += (pixel & 0xFF) * alpha;
						g += (pixel >> 8 & 0xFF) * alpha;
						b += (pixel >> 16 & 0xFF) * alpha;
						a += alpha;
					}
				}
				if (a > 0) {
					float red = (float) r / a;
					float green = (float) g / a;
					float blue = (float) b / a;
					float grey = (red + green + blue) / 3;
					int alpha = (int) (a / (SHRINK * SHRINK));
					out[y * EDGE + x] = alpha << 24 | mute(blue, grey) << 16 | mute(green, grey) << 8 | mute(red, grey);
					known[y * EDGE + x] = (byte) alpha;
				}
			}
		}
		return out;
	}

	private static int mute(float channel, float grey) {
		return Math.round(grey + (channel - grey) * COLOUR);
	}

	private void takeLoaded() {
		Loaded done;
		while ((done = this.loaded.poll()) != null) {
			this.loading.remove(done.key());
			MapTexture texture = null;
			if (done.pixels() != null && !this.closed) {
				texture = MapTexture.create("diorama map memory", EDGE, 1);
				texture.write(0, 0, 0, EDGE, done.pixels());
			}
			this.tiles.put(done.key(), new Entry(texture, done.known()));
			this.version++;
		}
	}

	private void evict() {
		Iterator<Map.Entry<TileKey, Entry>> it = this.tiles.entrySet().iterator();
		while (this.tiles.size() > MAX_TILES && it.hasNext()) {
			Entry entry = it.next().getValue();
			if (entry.texture() != null) {
				entry.texture().close();
			}
			it.remove();
		}
	}

	void close() {
		this.closed = true;
		this.store.close();
		for (Entry entry : this.tiles.values()) {
			if (entry.texture() != null) {
				entry.texture().close();
			}
		}
		this.tiles.clear();
	}
}
