package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.Facing;
import dev.diorama.Projection;
import dev.diorama.bake.Mipmaps;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
 * fitted to the new view, sharp, under low fog you can see through. The fog goes as real tiles land
 * on top. A memory can't be right in detail, since the old tiles show the world from another view,
 * so it stays too soft to pass for the map; nothing that needs an exact place reads it.
 *
 * <p>A folder with no tiles of its own remembers whichever folder beside it, of the same kind of
 * view, shows the most, and notes that in {@code redraw-from}. That's always a folder of real tiles,
 * so nothing is remembered twice over, and it works out the same for a dimension the player was away
 * from when the label changed. The note stays when the redraw ends, as a place the save has no
 * chunks for has nothing else.
 *
 * <p>Render thread, except where a method says otherwise.
 */
public final class MapMemory {
	private static final String NOTE = "redraw-from";
	/** When the map moved away from a folder, in seconds; see {@link #noteLeft}. */
	static final String LEFT = "left-at";
	/** Texture pixels per tile edge: a remembered tile is shown as sharp as it was saved. */
	private static final int EDGE = Projection.TILE_PIXELS;
	/** Cells per edge of what's known of a tile, for the fog mask, which reads it far coarser. */
	private static final int KNOWN_EDGE = EDGE / 4;
	private static final int SHRINK = EDGE / KNOWN_EDGE;
	/** Remembered tiles kept on the GPU, a megabyte and a half each, unless more are in use: as many as a big view shows. */
	private static final int MAX_TILES = 64;
	private static final long IN_USE_MS = 1000;
	private static final int UPLOADS_PER_FRAME = 4;
	private static final int MAX_LOADING = 8;
	/**
	 * What's known of this many remembered tiles is kept, 16 KB each: the fog mask reaches well past
	 * the view, and a view's mask takes a few hundred at most.
	 */
	private static final int MAX_KNOWN = 768;
	private static final int MAX_KNOWN_LOADING = 16;
	/** The height memories are fitted at: higher ground lands a little off, which softness hides. */
	private static final int FIT_Y = 64;

	private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "diorama-map-memory");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private static final class Entry {
		final @Nullable MapTexture texture;
		/** When a view last drew it: a frame may still hold it for a moment after. */
		long drawnMs;

		Entry(@Nullable MapTexture texture) {
			this.texture = texture;
		}
	}

	/** A tile read off the disk: its mip levels, made on the loader thread, and what's known of it. */
	private record Loaded(TileKey key, int @Nullable [] @Nullable [] mips, byte @Nullable [] known) {
	}

	/** The remembered tiles saved at an overview level, listed on the loader thread. */
	private record Listed(int lod, Set<TileKey> keys) {
	}

	private final TileStore store;
	private final double sin;
	private final double cos;
	/** Which way the remembered map faces, and how far the map has turned from it. */
	private final Facing facing;
	private final int quarterTurns;
	private final LinkedHashMap<TileKey, Entry> tiles = new LinkedHashMap<>(16, 0.75F, true);
	private final Set<TileKey> loading = new HashSet<>();
	private final ConcurrentLinkedQueue<Loaded> loaded = new ConcurrentLinkedQueue<>();
	/**
	 * What's known of remembered tiles, kept apart from their textures: the fog mask reads it around
	 * the view too, where no texture is drawn. Most recently read last.
	 */
	private final LinkedHashMap<TileKey, byte[]> knownTiles = new LinkedHashMap<>(16, 0.75F, true);
	private final Set<TileKey> knownLoading = new HashSet<>();
	private final ConcurrentLinkedQueue<Loaded> knownLoaded = new ConcurrentLinkedQueue<>();
	/** Which tiles the remembered map has at each overview level, once listed. */
	private final Map<Integer, Set<TileKey>> savedTiles = new HashMap<>();
	private final Set<Integer> listing = new HashSet<>();
	private final ConcurrentLinkedQueue<Listed> listed = new ConcurrentLinkedQueue<>();
	/**
	 * Overview tiles the store made since it was opened: the levels a map from an older version
	 * didn't have. A level listed, or a tile found missing, before then is looked for again.
	 */
	private final ConcurrentLinkedQueue<TileKey> made = new ConcurrentLinkedQueue<>();
	private @Nullable TileKey lastKnownKey;
	private byte @Nullable [] lastKnown;
	private long version;
	private long shown;
	private volatile boolean closed;

	private MapMemory(Path folder, TileLabel label) {
		this.store = new TileStore(folder);
		this.store.onOverviewChanged(this.made::add);
		this.sin = Math.sin(Math.toRadians(label.pitch()));
		this.cos = Math.cos(Math.toRadians(label.pitch()));
		this.facing = label.facing();
		this.quarterTurns = Math.floorMod(Projection.facing().turns - label.facing().turns, 4);
	}

	/**
	 * What {@code folder}'s map remembers, or null if it has nothing to remember. Only a map that
	 * saves to the folder notes it: {@code keep} is false for a map that just looks at it.
	 */
	static @Nullable MapMemory open(Path folder, boolean keep) {
		String from = note(folder);
		if (from != null && !Files.isDirectory(folder.resolveSibling(from))) {
			from = null;
		}
		if (from == null && !hasTiles(folder)) {
			from = fullestBeside(folder);
			if (from != null && keep) {
				try {
					Files.createDirectories(folder);
					Files.writeString(folder.resolve(NOTE), from, StandardCharsets.UTF_8);
				} catch (IOException e) {
					Diorama.LOGGER.warn("Couldn't note what {} remembers", folder, e);
				}
			}
		}
		TileLabel label = from == null ? null : TileLabel.parse(from);
		return label == null || !Files.isDirectory(folder.resolveSibling(from)) ? null : new MapMemory(folder.resolveSibling(from), label);
	}

	/**
	 * Notes that the map moved away from a folder, for the save's chunks to be checked when it comes
	 * back. A note nothing has read yet stays: everything since that time is still to be checked.
	 */
	static void noteLeft(Path folder) {
		try {
			if (Files.isDirectory(folder) && !Files.exists(folder.resolve(LEFT))) {
				Files.writeString(folder.resolve(LEFT), Long.toString(System.currentTimeMillis() / 1000), StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't note leaving {}", folder, e);
		}
	}

	/** The folder beside this one that shows the same kind of view and the most, or null if none shows anything. */
	private static @Nullable String fullestBeside(Path folder) {
		TileLabel label = TileLabel.parse(folder.getFileName().toString());
		Path parent = folder.getParent();
		if (label == null || parent == null || !Files.isDirectory(parent)) {
			return null;
		}
		String best = null;
		long most = 0;
		try (Stream<Path> folders = Files.list(parent)) {
			for (Path other : folders.toList()) {
				String name = other.getFileName().toString();
				TileLabel otherLabel = TileLabel.parse(name);
				long count = otherLabel != null && label.canShow(otherLabel) && !other.equals(folder) ? tileCount(other) : 0;
				if (count > most) {
					most = count;
					best = name;
				}
			}
		} catch (IOException | java.io.UncheckedIOException e) {
			Diorama.LOGGER.warn("Couldn't look beside {}", folder, e);
		}
		return best;
	}

	private static @Nullable String note(Path folder) {
		try {
			return Files.isRegularFile(folder.resolve(NOTE)) ? Files.readString(folder.resolve(NOTE), StandardCharsets.UTF_8).trim() : null;
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't read what {} remembers", folder, e);
			return null;
		}
	}

	private static boolean hasTiles(Path folder) {
		if (!Files.isDirectory(folder)) {
			return false;
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.anyMatch(file -> file.getFileName().toString().endsWith(".omt"));
		} catch (IOException | java.io.UncheckedIOException e) {
			return true;
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

	/** Goes up when more of the memory becomes known, for a fog mask built on it. */
	long version() {
		return this.version;
	}

	/** For fog-mask's check: a point of the map as it's drawn now, in the old map. */
	public double[] toOldForTests(double u, double v) {
		return this.toOld(u, v);
	}

	/** For fog-mask's check: the remembered tiles saved at an overview level, or null if not listed yet. */
	public @Nullable Set<TileKey> savedForTests(int lod) {
		return this.savedTiles.get(lod);
	}

	/** For fog-mask's check: what's known of a remembered tile, or null if it isn't read. Render thread. */
	public byte @Nullable [] knownForTests(TileKey key) {
		return this.knownTiles.get(key);
	}

	/** For tests: how many remembered tiles with something on them are loaded. */
	public long shownTiles() {
		return this.shown;
	}

	/**
	 * Draws the memories under a view, and asks for the ones that aren't here yet: at the overview
	 * level the real tiles are drawn at, and sampled as they are.
	 */
	/**
	 * The overview level a view draws memories at: the real tiles' {@code lod}, or coarser where
	 * that would take more tiles than are kept, as a big screen zoomed in can.
	 */
	int lod(MapViewport.View view, int lod) {
		double maxU = view.minU() + view.width() / view.zoom();
		double maxV = view.minV() + view.height() / view.zoom();
		double[] a = this.toOld(view.minU(), view.minV());
		double[] b = this.toOld(maxU, maxV);
		while (lod < TileKey.MAX_LOD) {
			double units = (double) Projection.TILE_UNITS * (1 << lod);
			double tiles = (Math.abs(a[0] - b[0]) / units + 2) * (Math.abs(a[1] - b[1]) / units + 2);
			if (tiles <= MAX_TILES * 3 / 4) {
				break;
			}
			lod++;
		}
		return lod;
	}

	void draw(MapCanvas canvas, MapViewport.View view, int tint, int realLod, Sampling sampling) {
		long now = System.currentTimeMillis();
		this.takeLoaded(now);
		int lod = this.lod(view, realLod);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double maxU = view.minU() + view.width() / view.zoom();
		double maxV = view.minV() + view.height() / view.zoom();
		// The view's corners in the old map, which may lie on its side.
		double[] a = this.toOld(view.minU(), view.minV());
		double[] b = this.toOld(maxU, maxV);
		int tu0 = (int) Math.floor(Math.min(a[0], b[0]) / units);
		int tu1 = (int) Math.floor(Math.max(a[0], b[0]) / units);
		int tv0 = (int) Math.floor(Math.min(a[1], b[1]) / units);
		int tv1 = (int) Math.floor(Math.max(a[1], b[1]) / units);
		Circle circle = view.round() ? Circle.in(view) : null;
		for (int tu = tu0; tu <= tu1; tu++) {
			for (int tv = tv0; tv <= tv1; tv++) {
				TileKey key = new TileKey(TileKey.SURFACE, tu, tv, lod);
				Entry entry = this.tiles.get(key);
				if (entry == null) {
					this.load(key);
				}
				if (entry == null) {
					continue;
				}
				// In view, even with nothing on it: it stays, or it would be read again every frame.
				entry.drawnMs = now;
				if (entry.texture == null) {
					continue;
				}
				double[] from = this.fromOld(tu * units, tv * units);
				double[] to = this.fromOld((tu + 1) * units, (tv + 1) * units);
				float x0 = view.screenX(Math.min(from[0], to[0]));
				float x1 = view.screenX(Math.max(from[0], to[0]));
				float y0 = view.screenY(Math.min(from[1], to[1]));
				float y1 = view.screenY(Math.max(from[1], to[1]));
				if (circle == null) {
					canvas.rect(entry.texture, sampling, x0, y0, x1, y1, 0, 0, 1, 1, tint);
				} else {
					Circle.Quads inside = circle.clip(x0, y0, x1, y1, 0, 0, 1, 1);
					if (inside != null) {
						canvas.quad(entry.texture, sampling, inside.corners(), inside.uvs(), tint);
					}
				}
			}
		}
		this.evict(now);
	}

	/**
	 * Which share of map point {@code (u, v)} the memory shows at overview level {@code lod}, 0 to 1,
	 * or NaN where that isn't read yet: the remembered map has a tile there, or may have. A tile not
	 * read yet is no sign of a tile with nothing on it, so NaN mustn't be taken for 0. {@link #ask}
	 * reads them.
	 */
	float known(int lod, double u, double v) {
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double[] old = this.toOld(u, v);
		int tu = (int) Math.floor(old[0] / units);
		int tv = (int) Math.floor(old[1] / units);
		TileKey key = new TileKey(TileKey.SURFACE, tu, tv, lod);
		byte[] known;
		if (key.equals(this.lastKnownKey)) {
			known = this.lastKnown;
		} else {
			Set<TileKey> saved = this.savedTiles.get(lod);
			if (saved == null) {
				return Float.NaN;
			}
			if (!saved.contains(key)) {
				return 0;
			}
			known = this.knownTiles.get(key);
			if (known == null) {
				return Float.NaN;
			}
			this.lastKnownKey = key;
			this.lastKnown = known;
		}
		int x = (int) Math.min(KNOWN_EDGE - 1L, (long) ((old[0] - tu * units) / units * KNOWN_EDGE));
		int y = (int) Math.min(KNOWN_EDGE - 1L, (long) ((old[1] - tv * units) / units * KNOWN_EDGE));
		return (known[y * KNOWN_EDGE + x] & 0xFF) / 255.0F;
	}

	/**
	 * Reads what's known of the remembered tiles over a part of the map as drawn now, at overview
	 * level {@code lod}, nearest its middle first, for {@link #known}: a few at a time, and
	 * {@link #version} goes up as they land, for the caller to ask again. Asks for nothing over a part
	 * with more tiles than are kept.
	 */
	void ask(int lod, double minU, double minV, double maxU, double maxV) {
		Set<TileKey> saved = this.savedTiles.get(lod);
		if (saved == null) {
			if (this.listing.add(lod)) {
				LOADER.execute(() -> this.listed.add(new Listed(lod, this.closed ? Set.of() : this.store.saved(lod))));
			}
			return;
		}
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double[] a = this.toOld(minU, minV);
		double[] b = this.toOld(maxU, maxV);
		int tu0 = (int) Math.floor(Math.min(a[0], b[0]) / units);
		int tu1 = (int) Math.floor(Math.max(a[0], b[0]) / units);
		int tv0 = (int) Math.floor(Math.min(a[1], b[1]) / units);
		int tv1 = (int) Math.floor(Math.max(a[1], b[1]) / units);
		if ((long) (tu1 - tu0 + 1) * (tv1 - tv0 + 1) > MAX_KNOWN / 2) {
			return;
		}
		double midU = (tu0 + tu1 + 1) * 0.5;
		double midV = (tv0 + tv1 + 1) * 0.5;
		List<TileKey> wanted = new ArrayList<>();
		for (int tu = tu0; tu <= tu1; tu++) {
			for (int tv = tv0; tv <= tv1; tv++) {
				TileKey key = new TileKey(TileKey.SURFACE, tu, tv, lod);
				if (saved.contains(key) && !this.knownTiles.containsKey(key) && !this.knownLoading.contains(key) && !this.loading.contains(key)) {
					wanted.add(key);
				}
			}
		}
		wanted.sort(Comparator.comparingDouble(key -> Math.hypot(key.tileU() + 0.5 - midU, key.tileV() + 0.5 - midV)));
		for (TileKey key : wanted) {
			if (this.knownLoading.size() >= MAX_KNOWN_LOADING) {
				break;
			}
			this.knownLoading.add(key);
			LOADER.execute(() -> {
				byte[] known = null;
				try {
					int[] pixels = this.closed ? null : this.store.read(key);
					known = pixels == null ? new byte[KNOWN_EDGE * KNOWN_EDGE] : known(pixels);
				} catch (RuntimeException e) {
					Diorama.LOGGER.warn("Couldn't load the remembered tile {}", key, e);
				}
				this.knownLoaded.add(new Loaded(key, null, known));
			});
		}
	}

	/**
	 * Whether the remembered map shows anything of a chunk whose ground reaches up to {@code top},
	 * by its middle and corners. Not on the render thread: it can wait for the disk.
	 */
	boolean showsChunk(int chunkX, int chunkZ, int top) {
		int x0 = chunkX << 4;
		int z0 = chunkZ << 4;
		for (int[] at : new int[][] {{8, 8}, {0, 0}, {15, 0}, {0, 15}, {15, 15}}) {
			double worldX = x0 + at[0] + 0.5;
			double worldZ = z0 + at[1] + 0.5;
			double u = this.facing.x(worldX, worldZ);
			double v = this.facing.z(worldX, worldZ) * this.sin - top * this.cos;
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

	/** A point of the map as it's drawn now, in the old map, for ground at {@link #FIT_Y}. */
	private double[] toOld(double u, double v) {
		Facing now = Projection.facing();
		double z = Projection.zAt(v, FIT_Y);
		double worldX = now.worldX(u, z);
		double worldZ = now.worldZ(u, z);
		return new double[] {this.facing.x(worldX, worldZ), this.facing.z(worldX, worldZ) * this.sin - FIT_Y * this.cos};
	}

	/** A point of the old map on the map as it's drawn now. */
	private double[] fromOld(double u, double v) {
		double z = (v + FIT_Y * this.cos) / this.sin;
		double worldX = this.facing.worldX(u, z);
		double worldZ = this.facing.worldZ(u, z);
		return new double[] {Projection.u(worldX, worldZ), Projection.v(worldX, FIT_Y, worldZ)};
	}

	private void load(TileKey key) {
		// A few at a time: each waits as a megabyte and a third until it's uploaded.
		if (this.loading.size() >= MAX_LOADING || !this.loading.add(key)) {
			return;
		}
		LOADER.execute(() -> {
			int[][] mips = null;
			byte[] known = null;
			try {
				int[] saved = this.closed ? null : this.store.read(key);
				if (saved != null) {
					known = known(saved);
					mips = Mipmaps.build(turned(saved, this.quarterTurns), EDGE);
				}
			} catch (RuntimeException e) {
				Diorama.LOGGER.warn("Couldn't load the remembered tile {}", key, e);
			}
			this.loaded.add(new Loaded(key, mips, known));
		});
	}

	/** How much of each of a tile's {@link #KNOWN_EDGE} cells is known. */
	private static byte[] known(int[] tile) {
		byte[] known = new byte[KNOWN_EDGE * KNOWN_EDGE];
		for (int y = 0; y < KNOWN_EDGE; y++) {
			for (int x = 0; x < KNOWN_EDGE; x++) {
				int a = 0;
				for (int dy = 0; dy < SHRINK; dy++) {
					for (int dx = 0; dx < SHRINK; dx++) {
						a += tile[(y * SHRINK + dy) * EDGE + x * SHRINK + dx] >>> 24;
					}
				}
				known[y * KNOWN_EDGE + x] = (byte) (a / (SHRINK * SHRINK));
			}
		}
		return known;
	}

	/**
	 * A square picture turned for a map turned this many quarter turns: the map turns one way, so
	 * what's on it turns the other, counter-clockwise. A copy either way: the saved one isn't ours.
	 */
	private static int[] turned(int[] pixels, int quarterTurns) {
		if (quarterTurns == 0) {
			return pixels.clone();
		}
		int[] out = new int[pixels.length];
		int last = EDGE - 1;
		for (int y = 0; y < EDGE; y++) {
			for (int x = 0; x < EDGE; x++) {
				int to = switch (quarterTurns) {
					case 1 -> (last - x) * EDGE + y;
					case 2 -> (last - y) * EDGE + last - x;
					default -> x * EDGE + last - y;
				};
				out[to] = pixels[y * EDGE + x];
			}
		}
		return out;
	}

	/** Uploads what the loader finished, a few tiles a frame: each is a megabyte and a third. */
	private void takeLoaded(long now) {
		TileKey fresh;
		while ((fresh = this.made.poll()) != null) {
			this.savedTiles.remove(fresh.lod());
			this.listing.remove(fresh.lod());
			Entry missing = this.tiles.get(fresh);
			if (missing != null && missing.texture == null) {
				this.tiles.remove(fresh);
			}
			this.version++;
		}
		Listed list;
		while ((list = this.listed.poll()) != null) {
			this.savedTiles.put(list.lod(), list.keys());
			this.version++;
		}
		Loaded done;
		while ((done = this.knownLoaded.poll()) != null) {
			this.knownLoading.remove(done.key());
			if (done.known() != null) {
				this.keepKnown(done.key(), done.known());
			}
		}
		int uploads = 0;
		while (uploads < UPLOADS_PER_FRAME && (done = this.loaded.poll()) != null) {
			this.loading.remove(done.key());
			MapTexture texture = null;
			if (done.mips() != null && !this.closed) {
				texture = MapTexture.create("diorama map memory", EDGE, Mipmaps.LEVELS);
				for (int level = 0; level < Mipmaps.LEVELS; level++) {
					texture.write(level, 0, 0, EDGE >> level, done.mips()[level]);
				}
				this.shown++;
				uploads++;
			}
			if (done.known() != null) {
				this.keepKnown(done.key(), done.known());
			}
			Entry entry = new Entry(texture);
			// As if drawn now: a view that asked for it hasn't drawn it yet, and mustn't lose it first.
			entry.drawnMs = now;
			this.tiles.put(done.key(), entry);
			this.version++;
		}
	}

	private void keepKnown(TileKey key, byte[] known) {
		this.knownTiles.put(key, known);
		this.lastKnownKey = null;
		this.version++;
		Iterator<TileKey> it = this.knownTiles.keySet().iterator();
		while (this.knownTiles.size() > MAX_KNOWN && it.hasNext()) {
			it.next();
			it.remove();
		}
	}

	/** Lets go of tiles no view has drawn for a moment: a frame can hold a texture past the call that drew it. */
	private void evict(long now) {
		Iterator<Map.Entry<TileKey, Entry>> it = this.tiles.entrySet().iterator();
		while (this.tiles.size() > MAX_TILES && it.hasNext()) {
			Entry entry = it.next().getValue();
			if (now - entry.drawnMs < IN_USE_MS) {
				continue;
			}
			if (entry.texture != null) {
				entry.texture.close();
			}
			it.remove();
			this.version++;
		}
	}

	void close() {
		this.closed = true;
		this.store.close();
		for (Entry entry : this.tiles.values()) {
			if (entry.texture != null) {
				entry.texture.close();
			}
		}
		this.tiles.clear();
	}
}
