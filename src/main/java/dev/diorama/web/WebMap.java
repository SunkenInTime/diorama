package dev.diorama.web;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.ChunkSource;
import dev.diorama.bake.JarModels;
import dev.diorama.bake.TableLooks;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.VanillaTable;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.ChunkTags;
import dev.diorama.data.SavedChunkSource;
import dev.diorama.share.ServerLocks;
import dev.diorama.share.ShareServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The server's web map: Diorama's tracer drawing the world from its save, in vanilla colours, on
 * one background thread, into JPEG tiles a browser can pan, as BlueMap and Dynmap draw theirs.
 * Off unless the owner turns it on ({@link WebSettings}); dedicated servers only.
 *
 * <p>The first pass draws every tile with land the shared map shows (by default, land players
 * spent time near: vanilla's inhabited time). After that, at most every 5 minutes, a pass draws
 * the tiles of chunks the region files say were saved since the last pass with other blocks or
 * biomes; so a build shows once the server has saved it, with nothing to press.
 * {@code /diorama-web redraw} draws everything again.
 *
 * <p>It shows no more than the map in the game would: a dimension the server's settings turn the
 * map off in ({@link ServerLocks}) isn't drawn, and the pictures it had are deleted; see
 * {@link #leftOut}.
 *
 * <p>Reads only the region files ({@link ServerSave}), so it never touches loaded chunks or the
 * server's storage thread, and names no client class.
 */
public final class WebMap {
	/** How often the region files are looked at for saved chunks between passes. */
	private static final long CHECK_MS = 30_000;
	/**
	 * The least time from one pass's start to the next: vanilla's autosave interval. A running
	 * server saves busy chunks (farms, redstone) every minute or so; this redraws them at most this
	 * often rather than every time.
	 */
	private static final long PASS_GAP_MS = 5 * 60_000;
	/** A tick past this and the server is close to slowing down: the map waits. */
	private static final long SERVER_BEHIND_NANOS = 40_000_000;
	/** Blend radius for biome tints: vanilla's default. */
	private static final int BLEND_RADIUS = 2;
	/** The most zoom levels out from the map's own tiles. */
	private static final int MAX_ZOOM = 10;
	/** A picture's file name: its place across and down. */
	private static final java.util.regex.Pattern TILE_NAME = java.util.regex.Pattern.compile("-?[0-9]+_-?[0-9]+[.]jpg");

	private static @Nullable WebMap running;

	private final MinecraftServer server;
	private final WebSettings settings;
	private final VanillaTable table;
	private final Path modsFolder;
	final Path folder;
	private final Map<String, Dimension> dimensions = new ConcurrentHashMap<>();
	/** The folders, in lower case, of the dimensions the settings keep off the map: the map's web server serves nothing in them. */
	private final Set<String> closed = ConcurrentHashMap.newKeySet();
	/** The folders a warning has been logged for, with pictures that couldn't be deleted. */
	private final Set<String> warnedKept = new HashSet<>();
	private final Object wake = new Object();
	private final Thread thread;
	private volatile @Nullable WebServer http;
	private volatile boolean stopping;
	/** An operator asked to draw everything again; taken by the next pass. */
	private final java.util.concurrent.atomic.AtomicBoolean redrawAll = new java.util.concurrent.atomic.AtomicBoolean();
	/** A full redraw that didn't complete: done again at the next pass, after the usual wait. */
	private boolean redrawOwed;
	private volatile String status = "starting";
	/** Passes over every dimension finished since the server started. */
	private volatile int passes;

	private WebMap(MinecraftServer server, WebSettings settings, VanillaTable table, Path modsFolder) {
		this.server = server;
		this.settings = settings;
		this.table = table;
		this.modsFolder = modsFolder;
		this.folder = settings.folder() != null ? settings.folder() : server.getWorldPath(LevelResource.ROOT).resolve("diorama-web");
		this.thread = new Thread(this::run, "Diorama web map");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.MIN_PRIORITY);
	}

	/** Starts the web map if the settings turn it on. Server thread, once the server has started. */
	public static synchronized void started(MinecraftServer server, Path configFolder, Path modsFolder) {
		if (!server.isDedicatedServer()) {
			return;
		}
		WebSettings settings = WebSettings.load(configFolder);
		if (!settings.enabled()) {
			Diorama.LOGGER.info("Diorama's web map is off (web-map in config/{})", WebSettings.FILE);
			return;
		}
		VanillaTable table = VanillaTable.shipped();
		if (table == null) {
			Diorama.LOGGER.warn("Diorama's web map can't start: this jar has no vanilla colour table");
			return;
		}
		WebMap map = new WebMap(server, settings, table, modsFolder);
		if (map.start()) {
			running = map;
		}
	}

	/** Stops the web map, if it runs. Server thread. */
	public static synchronized void stopped() {
		WebMap map = running;
		running = null;
		if (map != null) {
			map.stop();
		}
	}

	/** The running web map, or null. */
	public static @Nullable WebMap get() {
		return running;
	}

	/** Starts drawing and serving; false (logged) if the folder can't be written. */
	private boolean start() {
		try {
			Files.createDirectories(this.folder);
			try (InputStream page = WebMap.class.getResourceAsStream("/diorama/web/index.html")) {
				if (page != null) {
					Files.copy(page, this.folder.resolve("index.html"), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch (IOException e) {
			Diorama.LOGGER.warn("Diorama's web map can't write to {}, so it doesn't start", this.folder, e);
			return false;
		}
		this.thread.start();
		Diorama.LOGGER.info("Diorama's web map is drawing into {}", this.folder);
		return true;
	}

	private void stop() {
		this.stopping = true;
		synchronized (this.wake) {
			this.wake.notifyAll();
		}
		try {
			this.thread.join(10_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		WebServer http = this.http;
		if (http != null) {
			http.stop();
		}
	}

	/** Draws everything again, starting now. */
	public void redrawAll() {
		this.redrawAll.set(true);
		synchronized (this.wake) {
			this.wake.notifyAll();
		}
	}

	/** How many passes over every dimension have finished since the server started. */
	public int passes() {
		return this.passes;
	}

	/** What the map is doing, for the status command. */
	public String status() {
		return this.status;
	}

	/**
	 * Whether the server's settings keep a dimension off the web map: one with the map off
	 * ({@code map-off-dimensions}), or one under a roof, as the Nether, while cave maps aren't allowed
	 * or are fog of war. There the map in the game shows only what each player has seen, and a page
	 * for everyone can't know what one player has seen.
	 */
	private boolean leftOut(ServerLevel level) {
		ServerLocks locks = ShareServer.locks(this.server);
		return locks.mapOffDimensions().contains(level.dimension().identifier().toString())
			|| level.dimensionType().hasCeiling() && (!locks.caveMapsAllowed() || locks.caveFogOfWar());
	}

	/**
	 * Takes a dimension off the web map: its pictures, and when it was last drawn, so it's drawn
	 * whole if the settings allow it again. Only the map's own pictures are deleted, in the map's own
	 * folders (none reached through a link). Whatever can't be deleted, the map's web server no
	 * longer serves, and it's tried again at the next pass.
	 */
	private void remove(String id, Properties progress) {
		this.dimensions.remove(id);
		if (progress.remove(id) != null) {
			this.saveProgress(progress);
		}
		String name = folderName(id);
		Path dimension = this.folder.resolve(name);
		// An ID from the settings file that isn't one folder's name inside the map's folder names nothing of the map's.
		if (!this.folder.equals(dimension.getParent()) || !name.equals(String.valueOf(dimension.getFileName()))) {
			return;
		}
		this.closed.add(name.toLowerCase(Locale.ROOT));
		if (!Files.isDirectory(dimension, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		int deleted = 0;
		int kept = 0;
		for (int zoom = 0; zoom <= MAX_ZOOM; zoom++) {
			Path level = dimension.resolve(Integer.toString(zoom));
			if (!Files.isDirectory(level, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			try (DirectoryStream<Path> files = Files.newDirectoryStream(level, "*.jpg")) {
				for (Path file : files) {
					if (TILE_NAME.matcher(file.getFileName().toString()).matches()) {
						try {
							Files.delete(file);
							deleted++;
						} catch (IOException e) {
							kept++;
						}
					}
				}
				deleteIfEmpty(level);
			} catch (IOException e) {
				kept++;
			}
		}
		try {
			deleteIfEmpty(dimension);
		} catch (IOException e) {
			kept++;
		}
		if (deleted > 0) {
			Diorama.LOGGER.info("Web map: deleted the {} pictures of {}, which this server's settings keep off it", deleted, id);
		}
		if (kept > 0 && this.warnedKept.add(name)) {
			Diorama.LOGGER.warn("Web map: couldn't delete {} of the pictures or folders of {} in {}, which this server's settings keep off the map. Diorama's own web server "
				+ "doesn't serve them; a web server of your own still would, so delete that folder yourself", kept, id, dimension);
		}
	}

	/** Deletes a folder with nothing in it; one with someone else's files stays. */
	private static void deleteIfEmpty(Path folder) throws IOException {
		try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
			if (files.iterator().hasNext()) {
				return;
			}
		}
		Files.delete(folder);
	}

	/** The folder of a dimension's pictures, in the web map's folder. */
	private static String folderName(String id) {
		return id.replace(':', '_').replace('/', '_');
	}

	private void run() {
		try {
			Properties progress = this.loadProgress();
			// Before anything is served: what the settings keep off the map is deleted, and the map
			// file lists what's left as it is on disk.
			List<String> out = new ArrayList<>();
			for (ServerLevel level : this.server.getAllLevels()) {
				String id = level.dimension().identifier().toString();
				if (this.leftOut(level)) {
					out.add(id);
					this.remove(id, progress);
				} else {
					this.dimensions.computeIfAbsent(id, key -> new Dimension(this, level, key));
				}
			}
			// A dimension turned off that isn't in this world now (its mod or data pack taken out) may still have pictures.
			for (String id : ShareServer.locks(this.server).mapOffDimensions()) {
				if (!out.contains(id)) {
					this.remove(id, progress);
				}
			}
			if (!out.isEmpty()) {
				Diorama.LOGGER.info("Web map: not drawing {}, which this server's settings keep off the map (map-off-dimensions, or cave maps locked for a dimension under a roof)", String.join(", ", out));
			}
			this.writeMapJson();
			if (this.settings.port() > 0 && !this.stopping) {
				this.http = WebServer.start(this.folder, this.settings.address(), this.settings.port(), this.closed::contains);
				if (this.stopping) {
					// The server stopped while the pictures were being deleted, and has looked for this already.
					WebServer late = this.http;
					if (late != null) {
						late.stop();
					}
					return;
				}
				if (this.http != null) {
					Diorama.LOGGER.info("Diorama's web map is served at http://{}:{}/", this.settings.address().isEmpty() ? "<this server>" : this.settings.address(), this.settings.port());
				}
			}
			this.colourMaps();
			JarModels jars = JarModels.of(this.modsFolder);
			TableLooks looks = new TableLooks(this.table, jars);
			Diorama.LOGGER.info("Web map colours: vanilla table of {}; block models from {} mod jars", this.table.describe(), jars.jarCount());
			// Drawn under another share-land: land the map shows now may be missing, or land it
			// no longer shows still drawn. Everything again.
			String land = this.settings.everyChunk() ? "generated" : "visited";
			if (!land.equals(progress.getProperty("share-land", land))) {
				Diorama.LOGGER.info("share-land changed to {}: drawing the whole web map again", land);
				this.redrawAll.set(true);
			} else {
				progress.setProperty("share-land", land);
			}
			while (!this.stopping) {
				boolean all = this.redrawAll.getAndSet(false) || this.redrawOwed;
				this.redrawOwed = false;
				long passStarted = System.currentTimeMillis();
				boolean complete = true;
				for (ServerLevel level : this.server.getAllLevels()) {
					if (this.stopping) {
						break;
					}
					String id = level.dimension().identifier().toString();
					if (this.leftOut(level)) {
						// A dimension a mod added since, or pictures that couldn't be deleted before.
						this.remove(id, progress);
						continue;
					}
					Dimension dimension = this.dimensions.computeIfAbsent(id, key -> new Dimension(this, level, key));
					long last = all ? -1 : Long.parseLong(progress.getProperty(id, "-1"));
					long started = dimension.pass(looks, last);
					if (started >= 0) {
						progress.setProperty(id, Long.toString(started));
						this.saveProgress(progress);
					} else if (all) {
						complete = false;
					}
				}
				if (all && complete && !this.stopping) {
					// Only now is the map drawn under this share-land: a redraw cut short starts over.
					progress.setProperty("share-land", land);
					this.saveProgress(progress);
				} else if (all && !this.stopping) {
					// Out of disk, say: again, but not at once.
					this.redrawOwed = true;
				}
				this.writeMapJson();
				this.passes++;
				this.status = "up to date; draws what was saved since at most every " + PASS_GAP_MS / 60_000 + " minutes";
				this.waitForChanges(progress, passStarted + PASS_GAP_MS);
			}
			jars.close();
		} catch (RuntimeException | Error e) {
			this.status = "stopped by an error: " + e;
			Diorama.LOGGER.error("Diorama's web map stopped", e);
		}
	}

	/**
	 * Waits until {@code notBefore} at least, then until a region file has a chunk saved since its
	 * dimension's last pass; or until a redraw is asked for.
	 */
	private void waitForChanges(Properties progress, long notBefore) {
		while (!this.stopping && !this.redrawAll.get()) {
			long wait = Math.max(CHECK_MS, notBefore - System.currentTimeMillis());
			synchronized (this.wake) {
				try {
					this.wake.wait(wait);
				} catch (InterruptedException e) {
					return;
				}
			}
			if (this.stopping || this.redrawAll.get() || System.currentTimeMillis() < notBefore) {
				continue;
			}
			if (this.redrawOwed) {
				return;
			}
			for (Dimension dimension : this.dimensions.values()) {
				if (dimension.savedSince(Long.parseLong(progress.getProperty(dimension.id, "-1")))) {
					return;
				}
			}
		}
	}

	/**
	 * A dedicated server never loads the grass and foliage colour maps (they're textures); without
	 * them every biome's grass is black. Fills them from the table, which keeps them.
	 */
	private void colourMaps() {
		if (GrassColor.get(0.5, 1.0) == 0) {
			int[] grass = this.table.colourMap(VanillaTable.GRASS);
			if (grass != null) {
				GrassColor.init(grass);
			}
		}
		if (FoliageColor.get(0.5, 1.0) == 0) {
			int[] foliage = this.table.colourMap(VanillaTable.FOLIAGE);
			if (foliage != null) {
				FoliageColor.init(foliage);
			}
		}
		//? if >=1.21.5 {
		if (net.minecraft.world.level.DryFoliageColor.get(0.5, 1.0) == 0) {
			int[] dry = this.table.colourMap(VanillaTable.DRY_FOLIAGE);
			if (dry != null) {
				net.minecraft.world.level.DryFoliageColor.init(dry);
			}
		}
		//?}
	}

	boolean serverBehind() {
		//? if >=1.21 {
		return this.server.getAverageTickTimeNanos() > SERVER_BEHIND_NANOS;
		//?} else {
		/*return this.server.getAverageTickTime() * 1_000_000 > SERVER_BEHIND_NANOS;
		*///?}
	}

	double averageTickMs() {
		//? if >=1.21 {
		return this.server.getAverageTickTimeNanos() / 1e6;
		//?} else {
		/*return this.server.getAverageTickTime();
		*///?}
	}

	boolean stopping() {
		return this.stopping || this.redrawAll.get();
	}

	/** Whether the server is shutting down (not a redraw asked for). */
	boolean shuttingDown() {
		return this.stopping;
	}

	void setStatus(String status) {
		this.status = status;
	}

	private Properties loadProgress() {
		Properties progress = new Properties();
		Path file = this.folder.resolve("progress.properties");
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				progress.load(reader);
			} catch (IOException | IllegalArgumentException e) {
				Diorama.LOGGER.warn("Couldn't read the web map's progress in {}; drawing everything again", file, e);
				progress.clear();
			}
		}
		return progress;
	}

	private void saveProgress(Properties progress) {
		Path file = this.folder.resolve("progress.properties");
		Path part = this.folder.resolve("progress.properties.part");
		try (Writer writer = Files.newBufferedWriter(part, StandardCharsets.UTF_8)) {
			progress.store(writer, "When each dimension's last web map pass started, in the region files' seconds");
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't save the web map's progress in {}", file, e);
			return;
		}
		try {
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't save the web map's progress in {}", file, e);
		}
	}

	/** What the page reads first: the projection, and each dimension's tiles at each zoom. */
	void writeMapJson() {
		StringBuilder json = new StringBuilder();
		json.append("{\"tileUnits\":").append(Projection.TILE_UNITS)
			.append(",\"tilePixels\":").append(Projection.TILE_PIXELS)
			.append(",\"sin\":").append(String.format(Locale.ROOT, "%.6f", Projection.sin()))
			.append(",\"cos\":").append(String.format(Locale.ROOT, "%.6f", Projection.cos()))
			.append(",\"background\":\"#").append(String.format(Locale.ROOT, "%06x", WebTiles.BACKGROUND)).append('"')
			.append(",\"updated\":").append(System.currentTimeMillis())
			.append(",\"dimensions\":[");
		List<Dimension> list = new ArrayList<>(this.dimensions.values());
		list.sort(Comparator.comparing(d -> d.order() + d.id));
		boolean first = true;
		for (Dimension dimension : list) {
			if (!first) {
				json.append(',');
			}
			first = false;
			dimension.json(json);
		}
		json.append("]}");
		Path file = this.folder.resolve("map.json");
		Path part = this.folder.resolve("map.json.part");
		try {
			Files.writeString(part, json, StandardCharsets.UTF_8);
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't write the web map's map.json", e);
		}
	}

	/** One dimension's web map: its save, which chunks count, and the tiles on disk. */
	static final class Dimension {
		private final WebMap map;
		private final ServerLevel level;
		final String id;
		private final Path folder;
		private final ServerSave save;
		private final SavedChunkSource source;
		/** Per region, per chunk: whether it's been looked at, and whether the map shows it. */
		private final Map<Long, long[]> shown = new HashMap<>();
		/**
		 * Each drawn chunk's blocks and height as last drawn since the server started, by
		 * {@link ChunkSnapshot#key}: a chunk saved with the same blocks needs no redraw, and one that
		 * got lower still redraws the tiles it used to reach.
		 */
		private final Map<Long, Drawn> drawn = new HashMap<>();
		/** Tiles on disk, per zoom level, as {@link #key}. */
		@SuppressWarnings("unchecked")
		private final Set<Long>[] tiles = new Set[MAX_ZOOM + 1];
		private int zoomLevels;

		Dimension(WebMap map, ServerLevel level, String id) {
			this.map = map;
			this.level = level;
			this.id = id;
			this.folder = map.folder.resolve(folderName(id));
			this.save = ServerSave.of(level);
			int roof = level.dimensionType().hasCeiling() ? level.getMinY() + level.dimensionType().logicalHeight() : ChunkSource.NO_ROOF;
			int minY = level.getMinY();
			int maxY = level.getMaxY() + 1;
			ChunkSource bounds = new ChunkSource() {
				@Override
				public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
					return null;
				}

				@Override
				public int minY() {
					return minY;
				}

				@Override
				public int maxY() {
					return maxY;
				}

				@Override
				public int topY() {
					return maxY;
				}

				@Override
				public int roof() {
					return roof;
				}

				@Override
				public boolean isSeen(int x, int y, int z) {
					return true;
				}
			};
			this.source = new SavedChunkSource(bounds, this.save, BLEND_RADIUS);
			boolean everyChunk = map.settings.everyChunk();
			this.source.allow((chunkX, chunkZ, top) -> everyChunk || this.shows(chunkX, chunkZ));
			for (int z = 0; z <= MAX_ZOOM; z++) {
				this.tiles[z] = new HashSet<>();
				Path zoom = this.folder.resolve(Integer.toString(z));
				if (!Files.isDirectory(zoom)) {
					continue;
				}
				try (DirectoryStream<Path> files = Files.newDirectoryStream(zoom, "*.jpg")) {
					for (Path file : files) {
						String name = file.getFileName().toString();
						int sep = name.indexOf('_');
						try {
							if (sep > 0) {
								this.tiles[z].add(key(Integer.parseInt(name.substring(0, sep)), Integer.parseInt(name.substring(sep + 1, name.length() - 4))));
							}
						} catch (NumberFormatException e) {
							// Not one of the map's tiles: someone else's file.
						}
					}
				} catch (IOException e) {
					Diorama.LOGGER.warn("Couldn't list the web map's tiles in {}", zoom, e);
				}
				if (!this.tiles[z].isEmpty()) {
					this.zoomLevels = z + 1;
				}
			}
		}

		/** A chunk as last drawn: its {@link ServerSave.Info#blocks}, highest block and floor. */
		private record Drawn(int blocks, int top, int floor) {
		}

		/** Overworld first, then the Nether, the End, and modded ones. */
		String order() {
			return this.id.equals("minecraft:overworld") ? "0" : this.id.equals("minecraft:the_nether") ? "1" : this.id.equals("minecraft:the_end") ? "2" : "3";
		}

		static long key(int u, int v) {
			return ((long) u << 32) | (v & 0xFFFFFFFFL);
		}

		/** Whether players spent time near the chunk, as its save says; looked up once, then kept. */
		private boolean shows(int chunkX, int chunkZ) {
			long[] bits = this.shown.computeIfAbsent(ServerSave.regionKey(chunkX >> 5, chunkZ >> 5), k -> new long[32]);
			int i = ((chunkZ & 31) << 5) | (chunkX & 31);
			long looked = 1L << (i & 31) << 32;
			long yes = 1L << (i & 31);
			if ((bits[i >> 5] & looked) == 0) {
				ServerSave.Info info = this.save.info(chunkX, chunkZ);
				boolean visited = info != null && info.summary().finished() && info.inhabited() > 0;
				bits[i >> 5] |= looked | (visited ? yes : 0);
			}
			return (bits[i >> 5] & yes) != 0;
		}

		private void forget(int chunkX, int chunkZ) {
			long[] bits = this.shown.get(ServerSave.regionKey(chunkX >> 5, chunkZ >> 5));
			if (bits != null) {
				int i = ((chunkZ & 31) << 5) | (chunkX & 31);
				bits[i >> 5] &= ~((1L << (i & 31) << 32) | (1L << (i & 31)));
			}
		}

		/** Whether any chunk was saved at or after {@code since} (region file seconds). */
		boolean savedSince(long since) {
			for (int[] times : this.save.scan().values()) {
				for (int time : times) {
					if (time != 0 && time >= since) {
						return true;
					}
				}
			}
			return false;
		}

		/**
		 * Draws the tiles of every chunk saved at or after {@code last} (all of them when it's
		 * negative). Returns when the pass started in region file seconds, or -1 if it was cut short.
		 */
		long pass(TableLooks looks, long last) {
			long started = System.currentTimeMillis() / 1000;
			long wallStart = System.nanoTime();
			// Read before the headers: a header that can't be read counts as a failed read too.
			int failedReads = this.save.failedReads();
			Map<Long, int[]> regions = this.save.scan();
			this.source.clear();
			Set<Long> dirty = new HashSet<>();
			if (last < 0) {
				// Every tile already drawn too, so ones with nothing left to show go.
				dirty.addAll(this.tiles[0]);
			}
			boolean failed = false;
			int changed = 0;
			int counted = 0;
			for (Map.Entry<Long, int[]> region : regions.entrySet()) {
				if (this.map.shuttingDown()) {
					// Reading every chunk of a big world takes a while: not past the server's shutdown.
					return -1;
				}
				int regionX = (int) (region.getKey() >> 32);
				int regionZ = (int) (long) region.getKey();
				int[] times = region.getValue();
				boolean any = false;
				for (int i = 0; i < 1024; i++) {
					if (times[i] == 0 || last >= 0 && times[i] < last) {
						continue;
					}
					any = true;
					int chunkX = regionX * 32 + (i & 31);
					int chunkZ = regionZ * 32 + (i >> 5);
					boolean wasShown = last >= 0 && (this.map.settings.everyChunk() || this.shows(chunkX, chunkZ));
					this.forget(chunkX, chunkZ);
					changed++;
					ServerSave.Info info = this.save.info(chunkX, chunkZ);
					if (info == null || !info.summary().finished() || !this.map.settings.everyChunk() && info.inhabited() <= 0) {
						// No longer on the map (reset, trimmed): where it was drawn is drawn again.
						Drawn gone = this.drawn.remove(ChunkSnapshot.key(chunkX, chunkZ));
						if (gone != null) {
							this.markTiles(chunkX, chunkZ, gone.top(), gone.floor(), dirty);
						} else if (wasShown) {
							this.markTiles(chunkX, chunkZ, this.level.getMaxY(), this.level.getMinY(), dirty);
						}
						continue;
					}
					ChunkTags.Summary summary = info.summary();
					long chunk = ChunkSnapshot.key(chunkX, chunkZ);
					Drawn before = this.drawn.get(chunk);
					int hash = info.blocks();
					this.drawn.put(chunk, new Drawn(hash, summary.maxY(), summary.floorY()));
					// Saved again with the same blocks, still on the map: nothing to draw.
					if (last >= 0 && wasShown && before != null && before.blocks() == hash) {
						continue;
					}
					counted++;
					// As high as it was before, or the world's top if that isn't known: a tower taken
					// down leaves rows of tiles its new height doesn't reach.
					int top = before != null ? Math.max(before.top(), summary.maxY()) : last >= 0 ? this.level.getMaxY() : summary.maxY();
					// And as low: ground raised over a pit leaves rows its new floor doesn't reach.
					int floor = before != null ? Math.min(before.floor(), summary.floorY()) : last >= 0 ? this.level.getMinY() : summary.floorY();
					this.markTiles(chunkX, chunkZ, top, floor, dirty);
				}
				if (any) {
					this.source.forgetTops(region.getKey());
				}
			}
			if (dirty.isEmpty()) {
				if (this.save.failedReads() != failedReads) {
					this.drawn.clear();
					return -1;
				}
				return last < 0 || changed > 0 ? started : -1;
			}
			// Nearest the middle of the world first, so the map fills in from spawn outwards.
			List<Long> order = new ArrayList<>(dirty);
			order.sort(Comparator.comparingLong(k -> {
				long u = k >> 32;
				long v = (int) (long) k;
				return u * u + v * v;
			}));
			ThreadMXBean threads = ManagementFactory.getThreadMXBean();
			long cpuStart = threads.isCurrentThreadCpuTimeSupported() ? threads.getCurrentThreadCpuTime() : -1;
			long bytes = 0;
			int drawn = 0;
			int empty = 0;
			double tickSum = 0;
			double tickMax = 0;
			for (long key : order) {
				if (this.map.stopping()) {
					Diorama.LOGGER.info("Web map pass of {} cut short after {} of {} tiles", this.id, drawn + empty, order.size());
					return -1;
				}
				while ((this.map.serverBehind() || this.map.server.isCurrentlySaving()) && !this.map.stopping()) {
					// The server needs the processor more: a tick running late, or a save, which a backup
					// can be waiting on.
					this.map.setStatus("waiting: the server is behind or saving");
					sleep(1000);
				}
				this.map.setStatus(String.format(Locale.ROOT, "drawing %s: tile %d of %d", this.id, drawn + empty + 1, order.size()));
				int u = (int) (key >> 32);
				int v = (int) key;
				int[] pixels;
				try {
					pixels = TileBaker.reference(new TileKey(TileKey.SURFACE, u, v), this.source, looks, false, null, null);
				} catch (RuntimeException | LinkageError | StackOverflowError e) {
					// A block a mod draws in a way the server can't: this tile stays as it was, and the rest
					// are drawn. It's tried again when its land is saved again or on a redraw.
					Diorama.LOGGER.warn("Couldn't draw web map tile {} {},{}", this.id, u, v, e);
					continue;
				}
				try {
					long size = WebTiles.write(WebTiles.file(this.folder, 0, u, v), pixels);
					bytes += size;
					if (size > 0) {
						drawn++;
						this.tiles[0].add(key);
					} else {
						empty++;
						this.tiles[0].remove(key);
					}
				} catch (IOException e) {
					failed = true;
					Diorama.LOGGER.warn("Couldn't write web map tile {} {},{}", this.id, u, v, e);
				}
				double tick = this.map.averageTickMs();
				tickSum += tick;
				tickMax = Math.max(tickMax, tick);
				if ((drawn + empty) % 50 == 0) {
					this.map.writeMapJson();
				}
			}
			long overviewBytes = this.zoomOut(dirty);
			failed |= overviewBytes < 0;
			overviewBytes = Math.max(0, overviewBytes);
			long cpu = cpuStart < 0 ? -1 : threads.getCurrentThreadCpuTime() - cpuStart;
			double seconds = (System.nanoTime() - wallStart) / 1e9;
			Diorama.LOGGER.info(String.format(Locale.ROOT,
				"Web map pass of %s: %d chunks saved since the last, %d with new blocks on the map; %d tiles drawn, %d empty, %d zoom levels; %.1f s (%.2f s of CPU a tile); "
					+ "%.1f MB written (%.0f KB a tile, %.1f MB zoomed out); server tick %.1f ms average, %.1f ms worst while drawing",
				this.id, changed, counted, drawn, empty, this.zoomLevels, seconds, cpu < 0 ? -1 : cpu / 1e9 / Math.max(1, drawn + empty),
				(bytes + overviewBytes) / 1e6, drawn == 0 ? 0 : bytes / 1e3 / drawn, overviewBytes / 1e6, tickSum / Math.max(1, drawn + empty), tickMax));
			this.source.clear();
			// A chunk that couldn't be read, being written each time it was tried, say: read again.
			failed |= this.save.failedReads() != failedReads;
			if (failed) {
				// Out of disk, say: the same chunks again next pass, drawn whatever their blocks.
				this.drawn.clear();
				return -1;
			}
			return started;
		}

		/** The tiles a chunk's blocks can show in, or cast shadows into. */
		private void markTiles(int chunkX, int chunkZ, int maxY, int floorY, Set<Long> dirty) {
			double sin = Projection.sin();
			double cos = Projection.cos();
			int x0 = chunkX * 16 - Math.abs(TileBaker.SHADOW_REACH_X) - 1;
			int x1 = chunkX * 16 + 16 + Math.abs(TileBaker.SHADOW_REACH_X) + 1;
			int z0 = chunkZ * 16 - Math.abs(TileBaker.SHADOW_REACH_Z) - 1;
			int z1 = chunkZ * 16 + 16 + Math.abs(TileBaker.SHADOW_REACH_Z) + 1;
			int top = maxY + 1;
			// Under a roof the map looks down from below it, as far as the dimension's floor; the
			// height maps there stop at the roof.
			int bottom = this.level.dimensionType().hasCeiling() ? this.level.getMinY() : Math.max(floorY, this.level.getMinY());
			double v0 = z0 * sin - (top + TileBaker.SHADOW_REACH_Y) * cos;
			double v1 = z1 * sin - bottom * cos;
			int u0 = Math.floorDiv(x0, Projection.TILE_UNITS);
			int u1 = Math.floorDiv(x1, Projection.TILE_UNITS);
			int tv0 = (int) Math.floor(v0 / Projection.TILE_UNITS);
			int tv1 = (int) Math.floor(v1 / Projection.TILE_UNITS);
			for (int u = u0; u <= u1; u++) {
				for (int v = tv0; v <= tv1; v++) {
					dirty.add(key(u, v));
				}
			}
		}

		/** Remakes the zoomed-out tiles over the ones drawn, adding levels as the map grows; the bytes written, or -1 if one failed. */
		private long zoomOut(Set<Long> drawn) {
			int levels = 1;
			int minU = Integer.MAX_VALUE;
			int maxU = Integer.MIN_VALUE;
			int minV = Integer.MAX_VALUE;
			int maxV = Integer.MIN_VALUE;
			for (long key : this.tiles[0]) {
				int u = (int) (key >> 32);
				int v = (int) key;
				minU = Math.min(minU, u);
				maxU = Math.max(maxU, u);
				minV = Math.min(minV, v);
				maxV = Math.max(maxV, v);
			}
			// Out until the whole map fits in a few tiles across.
			int span = this.tiles[0].isEmpty() ? 1 : Math.max(maxU - minU + 1, maxV - minV + 1);
			while (levels <= MAX_ZOOM && (span >> (levels - 1)) > 3) {
				levels++;
			}
			long bytes = 0;
			boolean failed = false;
			Set<Long> below = drawn;
			for (int zoom = 1; zoom < levels; zoom++) {
				Set<Long> here = new HashSet<>();
				// A level the map hasn't had yet is made whole.
				for (long key : zoom >= this.zoomLevels ? this.tiles[zoom - 1] : below) {
					here.add(key(Math.floorDiv((int) (key >> 32), 2), Math.floorDiv((int) key, 2)));
				}
				for (long key : here) {
					int u = (int) (key >> 32);
					int v = (int) key;
					Path[] children = new Path[4];
					for (int i = 0; i < 4; i++) {
						children[i] = WebTiles.file(this.folder, zoom - 1, u * 2 + (i & 1), v * 2 + (i >> 1));
					}
					try {
						long size = WebTiles.shrink(WebTiles.file(this.folder, zoom, u, v), children);
						bytes += size;
						if (size > 0) {
							this.tiles[zoom].add(key);
						} else {
							this.tiles[zoom].remove(key);
						}
					} catch (IOException e) {
						failed = true;
						Diorama.LOGGER.warn("Couldn't write web map tile {} zoom {} {},{}", this.id, zoom, u, v, e);
					}
				}
				below = here;
			}
			// The map got smaller (land it no longer shows): the levels over the new top go. One that
			// can't be deleted stays listed, and is tried again next pass.
			int kept = levels;
			for (int zoom = levels; zoom < this.zoomLevels && zoom <= MAX_ZOOM; zoom++) {
				for (java.util.Iterator<Long> it = this.tiles[zoom].iterator(); it.hasNext();) {
					long key = it.next();
					try {
						Files.deleteIfExists(WebTiles.file(this.folder, zoom, (int) (key >> 32), (int) key));
						it.remove();
					} catch (IOException e) {
						failed = true;
					}
				}
				if (!this.tiles[zoom].isEmpty()) {
					kept = zoom + 1;
				}
			}
			this.zoomLevels = kept;
			return failed ? -1 : bytes;
		}

		void json(StringBuilder json) {
			json.append("{\"id\":\"").append(this.id).append("\",\"folder\":\"").append(this.folder.getFileName())
				.append("\",\"zoomLevels\":").append(Math.max(1, this.zoomLevels)).append(",\"versions\":true,\"tiles\":[");
			for (int z = 0; z < Math.max(1, this.zoomLevels); z++) {
				if (z > 0) {
					json.append(',');
				}
				json.append('[');
				boolean first = true;
				for (long key : this.tiles[z]) {
					if (!first) {
						json.append(',');
					}
					first = false;
					int u = (int) (key >> 32);
					int v = (int) key;
					// When the tile was last written: the page asks for it with this, so a browser keeps a
					// picture until it changes rather than asking again each visit.
					long version;
					try {
						version = Files.getLastModifiedTime(WebTiles.file(this.folder, z, u, v)).toMillis() / 1000;
					} catch (IOException e) {
						version = 0;
					}
					json.append(u).append(',').append(v).append(',').append(version);
				}
				json.append(']');
			}
			json.append("]}");
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
