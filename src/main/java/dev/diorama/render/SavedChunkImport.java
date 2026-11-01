package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.SavedChunkSource;
import dev.diorama.data.SavedChunks;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Draws the chunks in the world's save onto the saved map, so the map fills in without the player
 * walking there, whether they played before installing the mod or a pre-generation tool (Chunky
 * and the like) made the area. Singleplayer only: on a server the save isn't here (see
 * {@link SavedChunks}). Only the sky view: reading cave slices from the save would show caves the
 * player never found, so those keep showing only what the player has been near.
 *
 * <p>Runs on a low-priority thread, a region file at a time, the region nearest what the player
 * is looking at first; a map panned to another region takes over after the tiles in progress. Tiles
 * are drawn on low-priority workers: a few on the GPU, one traced while playing, more with the game
 * paused, and fewer on a machine with few cores. Each tile is merged into the saved map like any bake, and pictures that are already whole stay as
 * they are. Progress is kept per chunk in the sky view's folder (a folder for another kind of view
 * starts over), so quitting costs a few tiles, and a later visit only draws what's new. Saves are
 * listed again every so often, so a pre-generation running while the player plays shows up as it
 * goes.
 */
public final class SavedChunkImport {
	/** Waits this long into a level, so as not to compete with the game getting it ready. */
	private static final long START_DELAY_MS = 5_000;
	private static final long RESCAN_MS = 60_000;
	private static final long SAVE_PROGRESS_MS = 10_000;
	/**
	 * How long a region waits after a live bake got in the way of one of its tiles. Around a busy
	 * farm that can go on for as long as the player stays, and the rest of the save shouldn't wait.
	 */
	private static final long RETRY_MS = 30_000;
	private static final long BUSY_WAIT_MS = 250;
	/** Tiles a fill may leave waiting for the disk, at a megabyte or so each. */
	private static final int MAX_UNWRITTEN = 32;
	/** A height to turn a map row into a place in the world, for picking the nearest region. */
	private static final int TYPICAL_Y = 64;
	/** The most tiles drawn at once: as many as the map's bakers leave room for. */
	private static final int WORKERS = Integer.getInteger("diorama.savedChunkWorkers", Math.max(1, MapLevel.BAKER_COUNT - 1));
	/** The same while playing: the GPU does most of the work of a tile, and tracing costs frame rate per worker. */
	private static final int GPU_WORKERS = Math.min(WORKERS, Integer.getInteger("diorama.savedChunkGpuWorkers", 2));
	/**
	 * On a machine with few cores even one busy thread costs the game frames, and so does what a
	 * tile leaves behind for the disk and the collector, so while playing a worker rests three
	 * times as long as its tile took.
	 */
	private static final boolean PACED = MapLevel.BAKER_COUNT == 1;

	private final MapLevel map;
	private final SavedChunks save;
	private final SavedChunkSource source;
	private final Thread thread;
	private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS, task -> {
		Thread thread = new Thread(task, "diorama-saved-chunks-worker");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private final CountDownLatch stop = new CountDownLatch(1);
	private volatile boolean idle;
	private volatile boolean rescanAsked;

	// The import thread's own from here on.
	/** The folder progress is kept for; a different one means a different kind of view. */
	private @Nullable Path folder;
	private volatile @Nullable Progress progress;
	/** The save's chunks by {@link SavedChunks#regionKey}, as of the last listing; see {@link SavedChunks#scan}. */
	private Map<Long, int[]> regions = Map.of();
	private long lastScan;
	/** When the last listing started, in seconds: chunk heights are at least this recent. */
	private int scannedAt;
	private long lastSave;
	/** Regions with chunks to draw, as of the last listing. */
	private final Set<Long> pending = new HashSet<>();
	/** Regions part way through. */
	private final Map<Long, Plan> plans = new HashMap<>();
	/** Regions waiting to try tiles again, and until when. */
	private final Map<Long, Long> heldUntil = new HashMap<>();
	/**
	 * Tiles drawn from the save so far, and how recent what they read is, in seconds: a tile shared
	 * by two regions is drawn once.
	 */
	private final Map<TileKey, Integer> drawn = new ConcurrentHashMap<>();
	private int drawnCount;
	private long drawNanos;

	private SavedChunkImport(MapLevel map, SavedChunks save) {
		this.map = map;
		this.save = save;
		this.source = new SavedChunkSource(map, save);
		this.thread = new Thread(this::run, "diorama-saved-chunks");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.MIN_PRIORITY);
	}

	/** Starts drawing the save behind this map, or returns null when there's no save here. */
	public static @Nullable SavedChunkImport start(MapLevel map, ClientLevel level) {
		SavedChunks save = SavedChunks.open(level, map.roofless());
		if (save == null) {
			return null;
		}
		SavedChunkImport job = new SavedChunkImport(map, save);
		map.watchLiveChunks(true);
		job.thread.start();
		return job;
	}

	/** Stops after the tile in progress. Doesn't wait for it, so leaving a dimension never hangs on a bake. */
	public void stop() {
		this.map.watchLiveChunks(false);
		this.save.close();
		this.stop.countDown();
	}

	/** For tests: whether everything in the save as of the last listing is drawn. */
	public boolean idle() {
		return this.idle && !this.rescanAsked;
	}

	/** For tests: lists the save now rather than at the next listing. */
	public void rescanSoon() {
		this.rescanAsked = true;
	}

	/** For tests: how many tiles were drawn from the save. */
	public int drawnCount() {
		return this.drawnCount;
	}

	/** For tests: whether this run drew a tile from the save. */
	public boolean drew(TileKey key) {
		return this.drawn.containsKey(key);
	}

	/** For tests: whether a chunk has been handled from the save, not only seen live. */
	public boolean drewChunk(int chunkX, int chunkZ) {
		Progress progress = this.progress;
		return progress != null && progress.time(chunkX >> 5, chunkZ >> 5, index(chunkX, chunkZ)) > 0;
	}

	private boolean stopped() {
		return this.stop.getCount() == 0;
	}

	/** Waits, and says whether the map stopped meanwhile. */
	private boolean pause(long ms) {
		try {
			return this.stop.await(ms, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return true;
		}
	}

	private void run() {
		boolean stopped = this.pause(START_DELAY_MS);
		while (!stopped) {
			try {
				this.step();
			} catch (BlockLooks.StatesRenumbered e) {
				// Stopped by a resource reload; the next tile bakes with the new looks.
			} catch (Throwable t) {
				if (!this.stopped()) {
					Diorama.LOGGER.error("Drawing saved chunks failed", t);
					this.pause(RESCAN_MS);
				}
			}
			stopped = this.stopped();
		}
		this.workers.shutdown();
		if (this.progress != null) {
			this.progress.saveIfChanged();
		}
	}

	private void step() {
		Path folder = this.map.importFolder();
		BlockLooks looks = BlockLooks.get();
		if (folder == null || looks == null || !this.save.isOpen()) {
			this.pause(1000);
			return;
		}
		if (!folder.equals(this.folder)) {
			if (this.progress != null) {
				this.progress.saveIfChanged();
			}
			this.folder = folder;
			this.progress = Progress.load(folder.resolve("saved-chunks.bin"));
			this.plans.clear();
			this.heldUntil.clear();
			this.drawn.clear();
			this.lastScan = 0;
		}
		Progress progress = this.progress;
		this.map.takeLiveChunks(key -> {
			int cx = (int) (key >> 32);
			int cz = (int) (long) key;
			progress.mark(cx >> 5, cz >> 5, index(cx, cz), Progress.LIVE);
		});
		long now = System.currentTimeMillis();
		if (now - this.lastScan >= RESCAN_MS || this.rescanAsked) {
			this.idle = false;
			this.rescanAsked = false;
			this.lastScan = now;
			this.scannedAt = (int) (now / 1000);
			Map<Long, int[]> before = this.regions;
			this.regions = this.save.scan();
			for (Map.Entry<Long, int[]> region : this.regions.entrySet()) {
				if (!Arrays.equals(region.getValue(), before.get(region.getKey()))) {
					this.source.forgetTops(region.getKey());
				}
			}
			this.pending.clear();
			for (Map.Entry<Long, int[]> region : this.regions.entrySet()) {
				if (progress.hasWork(region.getKey(), region.getValue())) {
					this.pending.add(region.getKey());
				}
			}
		}
		Long next = this.nearestPending();
		if (next == null) {
			progress.saveIfChanged();
			this.source.clear();
			if (this.pending.isEmpty() && !this.idle) {
				this.idle = true;
				if (this.drawnCount > 0) {
					Diorama.LOGGER.info("Saved chunks: drew {} tiles, {} ms each", this.drawnCount, this.drawNanos / this.drawnCount / 1_000_000);
				}
			}
			this.pause(100);
			return;
		}
		this.draw(next, looks, folder, progress);
	}

	/** The region to draw next: the one nearest what the player is looking at. */
	private @Nullable Long nearestPending() {
		double[] focus = this.map.importFocus();
		double x;
		double z;
		if (focus != null) {
			x = focus[0];
			z = Projection.zAt(focus[1], TYPICAL_Y);
		} else {
			LocalPlayer player = Minecraft.getInstance().player;
			x = player == null ? 0 : player.getX();
			z = player == null ? 0 : player.getZ();
		}
		Long best = null;
		double bestDistance = Double.MAX_VALUE;
		long now = System.currentTimeMillis();
		for (long region : this.pending) {
			if (this.heldUntil.getOrDefault(region, 0L) > now) {
				continue;
			}
			double dx = (region >> 32) * 512.0 + 256 - x;
			double dz = (int) region * 512.0 + 256 - z;
			double distance = dx * dx + dz * dz;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = region;
			}
		}
		return best;
	}

	/** Draws a region, or as much of it as it can before something else needs doing. */
	private void draw(long region, BlockLooks looks, Path folder, Progress progress) {
		// Everything read from here on, heights too, is at least this recent, which is what makes a
		// tile drawn below good enough for a chunk saved before then, here or in the region next door.
		this.source.clear();
		int readSince = Math.min(this.scannedAt, (int) (System.currentTimeMillis() / 1000));
		Plan plan = this.plans.get(region);
		if (plan == null) {
			plan = this.plan(region, progress);
			if (plan == null) {
				return;
			}
			this.plans.put(region, plan);
		}
		List<TileKey> retry = new ArrayList<>();
		CompletionService<Drawn> drawing = new ExecutorCompletionService<>(this.workers);
		int running = 0;
		boolean leaving = false;
		RuntimeException failure = null;
		while (running > 0 || !leaving && !plan.order.isEmpty()) {
			leaving |= this.stopped() || !folder.equals(this.map.importFolder());
			while (!leaving && running < this.workersNow() && !plan.order.isEmpty() && !this.save.serverBehind() && this.map.unwrittenTiles() < MAX_UNWRITTEN) {
				Long nearest = this.nearestPending();
				if (nearest != null && nearest != region) {
					// Looking somewhere else now. The plan keeps its place for later.
					leaving = true;
					break;
				}
				TileKey key = plan.order.remove(plan.order.size() - 1);
				if (this.needsDrawing(key, plan.tiles.get(key), plan, progress)) {
					drawing.submit(() -> {
						long start = System.nanoTime();
						try {
							boolean taken = this.map.bakeFromSave(key, this.source, looks);
							long nanos = System.nanoTime() - start;
							if (PACED && !Minecraft.getInstance().isPaused()) {
								this.pause(3 * nanos / 1_000_000);
							}
							return new Drawn(key, taken, nanos, null);
						} catch (RuntimeException e) {
							return new Drawn(key, false, 0, e);
						}
					});
					running++;
				} else {
					this.tileDone(key, plan, progress);
				}
			}
			if (running == 0) {
				if (!leaving && !plan.order.isEmpty()) {
					this.pause(BUSY_WAIT_MS);
				}
				continue;
			}
			Drawn done;
			try {
				done = drawing.take().get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (ExecutionException e) {
				// The worker catches what a tile throws: this is something else, and nothing to wait on.
				throw new CompletionException(e.getCause());
			}
			running--;
			if (done.failure() != null && failure == null) {
				failure = done.failure();
				leaving = true;
			}
			if (this.stopped()) {
				// Reads were cut short, so its chunks may not be in the tile.
				continue;
			}
			if (!done.taken()) {
				retry.add(done.key());
				continue;
			}
			this.drawNanos += done.nanos();
			this.drawnCount++;
			this.drawn.put(done.key(), readSince);
			this.tileDone(done.key(), plan, progress);
		}
		if (leaving) {
			plan.order.addAll(retry);
			if (failure != null) {
				throw failure;
			}
			return;
		}
		if (!retry.isEmpty()) {
			plan.order.addAll(retry);
			this.heldUntil.put(region, System.currentTimeMillis() + RETRY_MS);
			return;
		}
		this.plans.remove(region);
		this.pending.remove(region);
		this.heldUntil.remove(region);
		progress.saveIfChanged();
	}

	/** How many tiles may be drawing right now. */
	private int workersNow() {
		return Minecraft.getInstance().isPaused() ? WORKERS : this.map.savedTilesOnGpu() ? GPU_WORKERS : 1;
	}

	/** A tile is drawn or needs no drawing: the chunks that were only waiting for it are done. */
	private void tileDone(TileKey key, Plan plan, Progress progress) {
		for (long chunk : plan.tiles.get(key)) {
			if (plan.waiting.merge(chunk, -1, Integer::sum) == 0) {
				int cx = (int) (chunk >> 32);
				int cz = (int) chunk;
				progress.mark(cx >> 5, cz >> 5, index(cx, cz), plan.times.get(chunk));
			}
		}
		if (System.currentTimeMillis() - this.lastSave > SAVE_PROGRESS_MS) {
			progress.saveIfChanged();
			this.lastSave = System.currentTimeMillis();
		}
	}

	/** A worker's tile, whether the map took it, how long it took, and what stopped it if anything did. */
	private record Drawn(TileKey key, boolean taken, long nanos, @Nullable RuntimeException failure) {
	}

	/**
	 * Whether a tile needs baking for these chunks. Not if this run drew it after they were saved.
	 * Nor if the saved picture has no gaps and none of the chunks was drawn from the save before:
	 * there's nothing new to show, and a picture that's whole stays what it is.
	 */
	private boolean needsDrawing(TileKey key, Set<Long> chunks, Plan plan, Progress progress) {
		Integer drawnAt = this.drawn.get(key);
		boolean first = true;
		boolean changedSince = false;
		for (long chunk : chunks) {
			int cx = (int) (chunk >> 32);
			int cz = (int) chunk;
			first &= progress.time(cx >> 5, cz >> 5, index(cx, cz)) == 0;
			changedSince |= drawnAt == null || plan.times.get(chunk) >= drawnAt;
		}
		return changedSince && !(first && this.map.pictureWhole(key));
	}

	/** Which tiles a region's new or changed chunks show in, or null if the map stopped. */
	private @Nullable Plan plan(long region, Progress progress) {
		int regionX = (int) (region >> 32);
		int regionZ = (int) region;
		int[] times = this.regions.get(region);
		Plan plan = new Plan();
		for (int i = 0; i < 1024; i++) {
			int time = times[i];
			if (!progress.isWork(regionX, regionZ, i, time)) {
				continue;
			}
			while (!this.stopped() && this.save.serverBehind()) {
				this.pause(BUSY_WAIT_MS);
			}
			if (this.stopped()) {
				return null;
			}
			int cx = (regionX << 5) | (i & 31);
			int cz = (regionZ << 5) | (i >> 5);
			SavedChunks.Summary summary = this.save.summary(cx, cz);
			if (this.stopped()) {
				// That read may have been cut short, which says nothing about the chunk.
				return null;
			}
			if (summary == null || !summary.finished()) {
				// Nothing to draw yet. Once it's finished, the save writes it again.
				progress.mark(regionX, regionZ, i, time);
				continue;
			}
			long chunk = ChunkSnapshot.key(cx, cz);
			plan.times.put(chunk, time);
			for (TileKey key : this.tilesShowing(cx, cz, summary)) {
				plan.tiles.computeIfAbsent(key, k -> new HashSet<>()).add(chunk);
				plan.waiting.merge(chunk, 1, Integer::sum);
			}
		}
		plan.order.addAll(plan.tiles.keySet());
		// Taken from the end: one column of tiles after another, west to east, each north to south, so
		// the chunks read for a tile serve the tiles beside it.
		plan.order.sort(Comparator.comparingInt(TileKey::tileU).thenComparingInt(TileKey::tileV).reversed());
		return plan;
	}

	/**
	 * The sky view tiles a chunk can show in, as {@link MapLevel} works it out for a newly loaded
	 * chunk: its own blocks, plus the shadows and sky occlusion they cast on blocks nearby.
	 */
	private List<TileKey> tilesShowing(int cx, int cz, SavedChunks.Summary summary) {
		int x0 = cx << 4;
		int z0 = cz << 4;
		int x1 = x0 + 15;
		int z1 = z0 + 15;
		double top = summary.maxY() + 2;
		double bottom = summary.floorY() - 1 - TileBaker.SHADOW_REACH_Y - TileBaker.SKY_REACH;
		if (this.map.roofless()) {
			// Under a roof the view starts at the roof and can reach any floor below it.
			top = Math.min(top, this.map.roof());
			bottom = this.map.minY() - 1;
		}
		double u0 = x0 - 1 - TileBaker.SKY_REACH;
		double u1 = x1 + 2 + Math.max(TileBaker.SHADOW_REACH_X, TileBaker.SKY_REACH);
		double v0 = Projection.v(top, z0 - 1 - Math.max(TileBaker.SHADOW_REACH_Z, TileBaker.SKY_REACH));
		double v1 = Projection.v(bottom, z1 + 2 + TileBaker.SKY_REACH);
		double units = Projection.TILE_UNITS;
		List<TileKey> keys = new ArrayList<>();
		for (int tu = (int) Math.floor(u0 / units); tu <= (int) Math.floor(u1 / units); tu++) {
			for (int tv = (int) Math.floor(v0 / units); tv <= (int) Math.floor(v1 / units); tv++) {
				keys.add(new TileKey(TileKey.SURFACE, tu, tv));
			}
		}
		return keys;
	}

	private static int index(int cx, int cz) {
		return ((cz & 31) << 5) | (cx & 31);
	}

	/** The tiles a region still has to draw. */
	private static final class Plan {
		/** Tiles left to draw; taken from the end. */
		final List<TileKey> order = new ArrayList<>();
		/** The region's new or changed chunks each tile shows. */
		final Map<TileKey, Set<Long>> tiles = new HashMap<>();
		/** How many of its tiles each of those chunks still waits for; it's done when none are left. */
		final Map<Long, Integer> waiting = new HashMap<>();
		/** When the save last wrote each of those chunks. */
		final Map<Long, Integer> times = new HashMap<>();
	}

	/**
	 * Which saved chunks need no drawing: for each, when the save had last written it at the time,
	 * or {@link #LIVE}. Kept in {@code saved-chunks.bin}: {@code DSC1}, then deflated, the region
	 * count and, per region, its x, z and 1024 values.
	 */
	private static final class Progress {
		private static final int MAGIC = 0x44534331;
		/**
		 * The map saw this chunk live and saved its tiles, and the game saves a chunk it had loaded
		 * a little later: not news. Without this, every place the player walks would be drawn twice.
		 */
		static final int LIVE = -1;
		private final Path file;
		private final Map<Long, int[]> times = new HashMap<>();
		private boolean changed;

		private Progress(Path file) {
			this.file = file;
		}

		static Progress load(Path file) {
			Progress progress = new Progress(file);
			if (!Files.exists(file)) {
				return progress;
			}
			try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
				if (in.readInt() != MAGIC) {
					return progress;
				}
				DataInputStream data = new DataInputStream(new InflaterInputStream(in));
				int count = data.readInt();
				for (int r = 0; r < count; r++) {
					int x = data.readInt();
					int z = data.readInt();
					int[] times = new int[1024];
					for (int i = 0; i < 1024; i++) {
						times[i] = data.readInt();
					}
					progress.times.put(SavedChunks.regionKey(x, z), times);
				}
			} catch (IOException e) {
				Diorama.LOGGER.warn("Couldn't read which saved chunks are drawn from {}; drawing them again", file, e);
				progress.times.clear();
			}
			return progress;
		}

		int time(int regionX, int regionZ, int index) {
			int[] times = this.times.get(SavedChunks.regionKey(regionX, regionZ));
			return times == null ? 0 : times[index];
		}

		boolean isWork(int regionX, int regionZ, int index, int savedTime) {
			if (savedTime == 0) {
				return false;
			}
			int time = this.time(regionX, regionZ, index);
			return time != LIVE && time != savedTime;
		}

		boolean hasWork(long region, int[] savedTimes) {
			int regionX = (int) (region >> 32);
			int regionZ = (int) region;
			for (int i = 0; i < 1024; i++) {
				if (this.isWork(regionX, regionZ, i, savedTimes[i])) {
					return true;
				}
			}
			return false;
		}

		void mark(int regionX, int regionZ, int index, int time) {
			int[] times = this.times.computeIfAbsent(SavedChunks.regionKey(regionX, regionZ), k -> new int[1024]);
			if (times[index] != time && times[index] != LIVE) {
				times[index] = time;
				this.changed = true;
			}
		}

		void saveIfChanged() {
			if (!this.changed) {
				return;
			}
			try {
				Files.createDirectories(this.file.getParent());
				Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
				try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) {
					out.writeInt(MAGIC);
					DeflaterOutputStream deflated = new DeflaterOutputStream(out);
					DataOutputStream data = new DataOutputStream(deflated);
					data.writeInt(this.times.size());
					for (Map.Entry<Long, int[]> e : this.times.entrySet()) {
						data.writeInt((int) (e.getKey() >> 32));
						data.writeInt((int) (long) e.getKey());
						for (int time : e.getValue()) {
							data.writeInt(time);
						}
					}
					data.flush();
					deflated.finish();
				}
				Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				this.changed = false;
			} catch (IOException e) {
				Diorama.LOGGER.warn("Couldn't save which saved chunks are drawn to {}", this.file, e);
			}
		}
	}
}
