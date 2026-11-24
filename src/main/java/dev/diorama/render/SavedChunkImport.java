package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.Facing;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSave;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.ChunkTags;
import dev.diorama.data.SavedChunkSource;
import dev.diorama.data.SavedChunks;
import dev.diorama.share.SharedChunks;
import dev.diorama.waypoint.Portals;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Draws the chunks in the world's save onto the saved map, so the map fills in without the player
 * walking there, whether they played before installing the mod or a pre-generation tool (Chunky
 * and the like) made the area, and redraws a map whose tiles are labelled differently now (see
 * {@link MapMemory}). Singleplayer only: on a server the save isn't here (see
 * {@link SavedChunks}). Only the sky view: reading cave slices from the save would show caves the
 * player never found, so those keep showing only what the player has been near.
 *
 * <p>Runs on a low-priority thread, the tiles a view is showing first, then a region file at a
 * time, the region nearest what the player is looking at first; a map panned to another region
 * takes over after the tiles in progress. Tiles are drawn on a low-priority worker while playing,
 * more with the game paused, and on the GPU once it has shown it draws them right. Each tile is
 * merged into the saved map like any bake, and pictures that are already whole stay as they are.
 * Progress is kept per chunk in the sky view's folder (a folder for another kind of view starts
 * over), so quitting costs a few tiles, and a later visit only draws what's new. Saves are listed
 * again every so often, so a pre-generation running while the player plays shows up as it goes.
 */
public final class SavedChunkImport {
	/** Waits this long into a level, so as not to compete with the game getting it ready. */
	private static final long START_DELAY_MS = 5_000;
	private static final long RESCAN_MS = 60_000;
	/**
	 * A listing asked for waits until the last one is this old. A server sends its regions' tables
	 * one at a time, for minutes on a big world, and each asks for a listing: taken as they come,
	 * every one of them had the fill go through all the regions it knew again (#451).
	 */
	private static final long ASKED_RESCAN_MS = 1_000;
	private static final long SAVE_PROGRESS_MS = 10_000;
	/**
	 * How long a region waits after a live bake got in the way of one of its tiles. Around a busy
	 * farm that can go on for as long as the player stays, and the rest of the save shouldn't wait.
	 */
	private static final long RETRY_MS = 30_000;
	private static final long BUSY_WAIT_MS = 250;
	/** How full the heap may be for a tile to start, and the longest a tile waits for it. */
	private static final double MAX_HEAP_SHARE = 0.9;
	private static final long MAX_HEAP_WAIT_MS = 2_000;
	/** Tiles a fill may leave waiting for the disk, at a megabyte or so each. */
	private static final int MAX_UNWRITTEN = 32;
	/** A height to turn a map row into a place in the world, for picking the nearest region. */
	private static final int TYPICAL_Y = 64;
	/**
	 * Tiles drawn at once with the game paused or the map open: as many as the map's bakers leave
	 * room for. While playing it's one, as each worker more costs frame rate and, in the launcher's
	 * usual 2 GB, full collections, whether the tiles go to the GPU or the tracer.
	 */
	private static final int WORKERS = Math.max(1, MapLevel.BAKER_COUNT - 1);
	/**
	 * On a machine with few cores even one busy thread costs the game frames, and so does what a
	 * tile leaves behind for the disk and the collector, so while playing a worker rests three
	 * times as long as its tile took.
	 */
	private static final boolean PACED = MapLevel.BAKER_COUNT == 1;

	/** Held by the fill keeping its progress in each folder; see {@link #takeFolder}. */
	private static final Map<Path, ReentrantLock> FOLDER_LOCKS = new ConcurrentHashMap<>();
	/** How long a fill waits for an earlier one in its folder before the log says so. */
	private static final long FOLDER_WAIT_WARN_MS = 30_000;

	private final MapLevel map;
	private final ChunkSave save;
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
	/** Draw only what the map remembers: it's being redrawn, and isn't to fill in past that. */
	private volatile boolean onlyRemembered;
	private final Map<Long, Boolean> remembered = new ConcurrentHashMap<>();

	// The import thread's own from here on.
	/** The folder progress is kept for; a different one means a different kind of view. */
	private @Nullable Path folder;
	/** The lock on {@link #folder} this thread holds while it may save progress there. */
	private @Nullable ReentrantLock folderLock;
	private volatile @Nullable Progress progress;
	/** The save's chunks by {@link SavedChunks#regionKey}, as of the last listing; see {@link SavedChunks#scan}. */
	private Map<Long, int[]> regions = Map.of();
	private long lastScan;
	private long heapFullSince;
	/** Tiles views asked for, each looked at once unless it couldn't be drawn. */
	private final Set<TileKey> viewChecked = new HashSet<>();
	/** When the last listing started, in seconds: chunk heights are at least this recent. */
	private int scannedAt;
	private long lastSave;
	/** Regions with chunks to draw, as of the last listing. */
	private final Set<Long> pending = new HashSet<>();
	/** Chunks with something to draw as of the last listing, of how many are in the save. */
	private volatile int chunksToDo;
	private volatile int chunksInSave;
	private volatile int chunksDone;
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
	/** How many tiles the log has been told were drawn. */
	private int loggedCount;
	private volatile int listings;

	private SavedChunkImport(MapLevel map, ChunkSave save, Consumer<List<Portals.Spot>> portals) {
		this.map = map;
		this.save = save;
		this.source = new SavedChunkSource(map.world(), save, portals);
		this.thread = new Thread(this::run, "diorama-saved-chunks");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.MIN_PRIORITY);
	}

	/**
	 * Whether the map's folder has chunks drawn before tiles kept their block light, which a sky view
	 * with a night draws again once (see {@link Progress}), so the save is read for them even where
	 * the map doesn't fill in from it.
	 */
	public static boolean needsRelight(MapLevel map, ClientLevel level) {
		Path folder = map.importFolder();
		if (folder == null || map.roofless() || level.dimensionType().hasFixedTime()) {
			return false;
		}
		try (java.io.DataInputStream in = new java.io.DataInputStream(Files.newInputStream(folder.resolve("saved-chunks.bin")))) {
			int magic = in.readInt();
			if (magic == Progress.BEFORE_LIGHT) {
				return true;
			}
			if (magic != Progress.MAGIC) {
				return false;
			}
			// A run that stopped partway left some to do.
			java.io.DataInputStream data = new java.io.DataInputStream(new java.util.zip.InflaterInputStream(in));
			int count = data.readInt();
			for (int r = 0; r < count; r++) {
				data.readInt();
				data.readInt();
				for (int i = 0; i < 1024; i++) {
					if (data.readInt() == Progress.RELIGHT) {
						return true;
					}
				}
			}
			return false;
		} catch (java.io.IOException e) {
			return false;
		}
	}

	/** Starts drawing the save behind this map, or returns null when there's no save here. */
	public static @Nullable SavedChunkImport start(MapLevel map, ClientLevel level, Consumer<List<Portals.Spot>> portals) {
		SavedChunks save = SavedChunks.open(level, map.roofless());
		return save == null ? null : start(map, save, portals);
	}

	/**
	 * Starts drawing the land the server shares onto this map, or returns null when it shares none.
	 * Only the view from the sky: under a roof the view is a cave view, and those stay the player's.
	 */
	public static @Nullable SavedChunkImport startShared(MapLevel map, ClientLevel level, Consumer<List<Portals.Spot>> portals) {
		if (map.roofless()) {
			return null;
		}
		SharedChunks save = SharedChunks.open(level);
		if (save == null) {
			return null;
		}
		SavedChunkImport job = start(map, save, portals);
		save.onChange(job::rescanSoon);
		return job;
	}

	private static SavedChunkImport start(MapLevel map, ChunkSave save, Consumer<List<Portals.Spot>> portals) {
		SavedChunkImport job = new SavedChunkImport(map, save, portals);
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

	/** Sets whether to draw only what the map remembers, or everything in the save. */
	public void onlyRemembered(boolean only) {
		if (this.onlyRemembered != only) {
			this.onlyRemembered = only;
			this.rescanAsked = true;
			// A tile spans many chunks: those next to what's remembered mustn't come with it.
			this.remembered.clear();
			// What was drawn before left out what's allowed now, or the other way round.
			this.drawn.clear();
			this.source.allow(only ? this::remembered : (chunkX, chunkZ, top) -> true);
		}
	}

	/** Whether the map remembers a saved chunk, each asked once, as a bake reads it many times. */
	private boolean remembered(int chunkX, int chunkZ, int top) {
		// Ground this map had before tiles kept their light is drawn again for it.
		Progress progress = this.progress;
		if (progress != null && progress.relight.contains(ChunkSnapshot.key(chunkX, chunkZ))) {
			return true;
		}
		MapMemory memory = this.map.memory();
		return memory != null && this.remembered.computeIfAbsent(ChunkSnapshot.key(chunkX, chunkZ), k -> memory.showsChunk(chunkX, chunkZ, top));
	}

	/** How many chunks are left to draw. */
	public int chunksLeft() {
		return this.idle ? 0 : Math.max(0, this.chunksToDo - this.chunksDone);
	}

	/** How far through the save this is, 0 to 99: it isn't done until {@link #chunksLeft} says so. */
	public int percentDone() {
		int all = this.chunksInSave;
		return all == 0 ? 0 : Math.min(99, 100 * (all - this.chunksLeft()) / all);
	}

	/** For tests: whether everything in the save as of the last listing is drawn. */
	public boolean idle() {
		return this.idle && !this.rescanAsked;
	}

	/**
	 * Asks the server for what's changed in the land it shares, which comes in as tables (see
	 * {@link #rescanSoon}): chunks saved since they were drawn are drawn again. In singleplayer the
	 * save is listed every minute anyway.
	 */
	public void refresh() {
		this.save.refresh();
	}

	/** Lists the save again soon: for tests, and as tables of the land a server shares come in. */
	public void rescanSoon() {
		this.rescanAsked = true;
	}

	/** For tests: how many times the save has been listed. */
	public int listings() {
		return this.listings;
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

	/** For tests: whether the fill's thread and its workers have all ended since it was stopped. */
	public boolean finished() {
		return !this.thread.isAlive() && this.workers.isTerminated();
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
		try {
			this.workers.shutdown();
			if (this.progress != null) {
				this.progress.saveIfChanged();
			}
		} finally {
			this.leaveFolder();
		}
	}

	/**
	 * Takes the folder's lock, which this fill's thread holds for as long as it may save progress
	 * there, once the fill before it there has let go. A fill stops without waiting for its tile in
	 * progress, so leaving never waits on a bake, and saves its progress once that tile is done.
	 * Back in the same world or dimension seconds later, the new fill would otherwise read the
	 * progress from before that save, and the old fill's save would land on top of the new one's.
	 * The wait is on this fill's own thread, with no time limit: going ahead early is what it's there
	 * to stop. Says false if this fill was stopped meanwhile.
	 */
	private boolean takeFolder(Path folder) {
		ReentrantLock lock = FOLDER_LOCKS.computeIfAbsent(folder, key -> new ReentrantLock());
		long warnAt = System.currentTimeMillis() + FOLDER_WAIT_WARN_MS;
		while (!lock.tryLock()) {
			if (this.pause(100)) {
				return false;
			}
			if (warnAt != 0 && System.currentTimeMillis() > warnAt) {
				Diorama.LOGGER.warn("Drawing saved chunks into {} waits for an earlier fill there to finish", folder);
				warnAt = 0;
			}
		}
		this.folderLock = lock;
		return true;
	}

	/** Lets go of the folder's lock: this fill won't save progress there again. */
	private void leaveFolder() {
		ReentrantLock lock = this.folderLock;
		this.folderLock = null;
		if (lock != null && lock.isHeldByCurrentThread()) {
			lock.unlock();
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
			// Nothing saves there again once the lock is let go, not even a save that just failed.
			this.progress = null;
			this.folder = null;
			this.leaveFolder();
			if (!this.takeFolder(folder)) {
				return;
			}
			this.folder = folder;
			// A sky view with a night draws block light, which tiles drawn before it was kept lack.
			boolean night = !this.map.roofless() && !this.save.level().dimensionType().hasFixedTime();
			this.progress = Progress.load(folder.resolve("saved-chunks.bin"), night);
			this.plans.clear();
			this.heldUntil.clear();
			this.drawn.clear();
			this.viewChecked.clear();
			this.remembered.clear();
			this.lastScan = 0;
		}
		Progress progress = this.progress;
		this.map.takeLiveChunks(key -> {
			int cx = (int) (key >> 32);
			int cz = (int) (long) key;
			progress.mark(cx >> 5, cz >> 5, index(cx, cz), Progress.LIVE);
		});
		long now = System.currentTimeMillis();
		if (now - this.lastScan >= (this.rescanAsked ? ASKED_RESCAN_MS : RESCAN_MS)) {
			this.idle = false;
			this.rescanAsked = false;
			this.lastScan = now;
			this.listings++;
			this.scannedAt = this.save.now();
			Map<Long, int[]> before = this.regions;
			this.regions = this.save.scan();
			if (progress.back(this.regions)) {
				// Before anything can leave this folder again: that note has to be the next one.
				progress.saveIfChanged();
			}
			for (Map.Entry<Long, int[]> region : this.regions.entrySet()) {
				if (!Arrays.equals(region.getValue(), before.get(region.getKey()))) {
					this.source.forgetTops(region.getKey());
				}
			}
			this.pending.clear();
			int toDo = 0;
			int inSave = 0;
			for (Map.Entry<Long, int[]> region : this.regions.entrySet()) {
				if (progress.hasWork(region.getKey(), region.getValue(), this.onlyRemembered)) {
					this.pending.add(region.getKey());
				}
				int regionX = (int) (region.getKey() >> 32);
				int regionZ = (int) (long) region.getKey();
				for (int i = 0; i < 1024; i++) {
					inSave += region.getValue()[i] == 0 ? 0 : 1;
					toDo += progress.isWork(regionX, regionZ, i, region.getValue()[i], this.onlyRemembered) ? 1 : 0;
				}
			}
			this.chunksToDo = toDo;
			this.chunksInSave = inSave;
			this.chunksDone = 0;
			if (Diorama.DEBUG) {
				Diorama.LOGGER.info("Saved chunks: listed {} regions, {} with work, {} chunks to draw", this.regions.size(), this.pending.size(), toDo);
			}
		}
		Long next = this.nearestPending();
		if (next == null) {
			progress.saveIfChanged();
			this.source.clear();
			if (this.pending.isEmpty() && !this.idle) {
				this.idle = true;
				// Once for what it drew, not again after each listing that found nothing new.
				if (this.drawnCount > this.loggedCount) {
					this.loggedCount = this.drawnCount;
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
			Facing facing = Projection.facing();
			double turnedZ = Projection.zAt(focus[1], TYPICAL_Y);
			x = facing.worldX(focus[0], turnedZ);
			z = facing.worldZ(focus[0], turnedZ);
		} else {
			Entity viewer = MapSession.viewer();
			x = viewer == null ? 0 : viewer.getX();
			z = viewer == null ? 0 : viewer.getZ();
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

	/**
	 * The next tile a view is showing that this run hasn't drawn, in ground with work to do, or null.
	 * Not when redrawing only what's remembered: the regions' plans know what that is. A whole picture
	 * stays, as a plan would leave it.
	 */
	private @Nullable TileKey nextViewTile(Set<TileKey> inProgress) {
		if (this.onlyRemembered) {
			return null;
		}
		for (TileKey key : this.map.viewTiles()) {
			if (this.drawn.containsKey(key) || inProgress.contains(key)) {
				continue;
			}
			Facing facing = Projection.facing();
			double u = key.minU() + key.units() / 2;
			double z = Projection.zAt(key.minV() + key.units() / 2, TYPICAL_Y);
			int regionX = (int) Math.floor(facing.worldX(u, z)) >> 9;
			int regionZ = (int) Math.floor(facing.worldZ(u, z)) >> 9;
			// Its region can get work later, so it's only looked at, once, when there is some.
			if (this.pending.contains(SavedChunks.regionKey(regionX, regionZ)) && this.viewChecked.add(key) && !this.map.pictureWhole(key)) {
				// From a server, the blocks behind what's on screen come before anything else.
				if (this.save.prefetches()) {
					this.save.prefetch(this.chunksUnder(Set.of(key), regionX, regionZ, facing), true);
				}
				return key;
			}
		}
		return null;
	}

	/** Draws a region, or as much of it as it can before something else needs doing. */
	private void draw(long region, BlockLooks looks, Path folder, Progress progress) {
		// Everything read from here on, heights too, is at least this recent, which is what makes a
		// tile drawn below good enough for a chunk saved before then, here or in the region next door.
		this.source.clear();
		int readSince = Math.min(this.scannedAt, this.save.now());
		Plan plan = this.plans.get(region);
		if (plan == null || plan.onlyRemembered != this.onlyRemembered || plan.facing != Projection.facing()) {
			plan = this.plan(region, progress);
			if (plan == null) {
				return;
			}
			this.plans.put(region, plan);
		}
		List<TileKey> retry = new ArrayList<>();
		CompletionService<Drawn> drawing = new ExecutorCompletionService<>(this.workers);
		Set<TileKey> inProgress = new HashSet<>();
		Set<TileKey> forViews = new HashSet<>();
		int running = 0;
		boolean leaving = false;
		RuntimeException failure = null;
		while (running > 0 || !leaving && !plan.order.isEmpty()) {
			leaving |= this.stopped() || !folder.equals(this.map.importFolder()) || plan.onlyRemembered != this.onlyRemembered || plan.facing != Projection.facing();
			while (!leaving && running < this.workersNow() && !this.save.serverBehind()
				&& this.map.unwrittenTiles() < MAX_UNWRITTEN && !memoryTight()) {
				TileKey viewTile = this.nextViewTile(inProgress);
				if (viewTile != null) {
					inProgress.add(viewTile);
					forViews.add(viewTile);
					this.submit(drawing, viewTile, looks, plan.facing);
					running++;
					continue;
				}
				if (plan.order.isEmpty()) {
					break;
				}
				Long nearest = this.nearestPending();
				if (nearest != null && nearest != region) {
					// Looking somewhere else now. The plan keeps its place for later.
					leaving = true;
					break;
				}
				TileKey key = plan.order.get(plan.order.size() - 1);
				if (inProgress.contains(key)) {
					// A view asked for it first. Its answer says whether this comes back.
					break;
				}
				plan.order.remove(plan.order.size() - 1);
				if (this.needsDrawing(key, plan.tiles.get(key), plan, progress)) {
					inProgress.add(key);
					this.submit(drawing, key, looks, plan.facing);
					running++;
				} else {
					this.tileDone(key, plan, progress);
				}
			}
			if (running == 0) {
				if (!leaving && !plan.order.isEmpty()) {
					if (heapNearlyFull()) {
						this.source.clear();
					}
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
			inProgress.remove(done.key());
			if (done.failure() != null && failure == null) {
				failure = done.failure();
				leaving = true;
			}
			if (this.stopped()) {
				// Reads were cut short, so its chunks may not be in the tile.
				continue;
			}
			// A tile a view asked for owes the plan nothing: if the plan has it, it finds it drawn.
			boolean planned = !forViews.remove(done.key());
			if (!done.taken()) {
				// One a view asked for stays checked: around a busy farm that could go on forever, and
				// its region's plan comes to it, with the wait that has.
				if (planned) {
					retry.add(done.key());
				}
				continue;
			}
			this.drawNanos += done.nanos();
			this.drawnCount++;
			this.drawn.put(done.key(), readSince);
			if (planned) {
				this.tileDone(done.key(), plan, progress);
			}
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

	private static boolean heapNearlyFull() {
		Runtime runtime = Runtime.getRuntime();
		return runtime.totalMemory() - runtime.freeMemory() > runtime.maxMemory() * MAX_HEAP_SHARE;
	}

	/**
	 * Whether to hold the next tile because the heap is nearly full, which could tip the game into a
	 * long collection. Only for a moment: the collector usually frees it, and in a heap that stays
	 * full, as a big modpack's can, a fill that waits for it would never finish.
	 */
	private boolean memoryTight() {
		if (!heapNearlyFull()) {
			this.heapFullSince = 0;
			return false;
		}
		long now = System.currentTimeMillis();
		if (this.heapFullSince == 0) {
			this.heapFullSince = now;
		}
		if (now - this.heapFullSince > MAX_HEAP_WAIT_MS) {
			this.heapFullSince = now;
			return false;
		}
		return true;
	}

	private void submit(CompletionService<Drawn> drawing, TileKey key, BlockLooks looks, Facing facing) {
		drawing.submit(() -> {
			long start = System.nanoTime();
			try {
				boolean taken = this.map.bakeFromSave(key, this.source, looks, facing);
				long nanos = System.nanoTime() - start;
				if (PACED && playing()) {
					this.pause(3 * nanos / 1_000_000);
				}
				return new Drawn(key, taken, nanos, null);
			} catch (ChunkSave.Late e) {
				// The server hasn't sent a chunk yet: drawn again later, like a tile a live bake got to first.
				return new Drawn(key, false, 0, null);
			} catch (RuntimeException e) {
				return new Drawn(key, false, 0, e);
			}
		});
	}

	/** How many tiles may be drawing right now. */
	private int workersNow() {
		return playing() ? 1 : WORKERS;
	}

	/**
	 * Whether the frames are the player's to spend on the game. Not with the game paused, nor on the
	 * map, which doesn't pause it: the player is looking at what the fill is drawing.
	 */
	public static boolean playing() {
		Minecraft mc = Minecraft.getInstance();
		return !mc.isPaused() && !(mc.gui.screen() instanceof MapScreen);
	}

	/** A tile is drawn or needs no drawing: the chunks that were only waiting for it are done. */
	private void tileDone(TileKey key, Plan plan, Progress progress) {
		for (long chunk : plan.tiles.get(key)) {
			if (plan.waiting.merge(chunk, -1, Integer::sum) == 0) {
				this.chunksDone++;
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
		Plan plan = new Plan(this.onlyRemembered, Projection.facing());
		MapMemory memory = plan.onlyRemembered ? this.map.memory() : null;
		for (int i = 0; i < 1024; i++) {
			int time = times[i];
			if (!progress.isWork(regionX, regionZ, i, time, plan.onlyRemembered)) {
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
			ChunkTags.Summary summary = this.save.summary(cx, cz);
			if (this.stopped()) {
				// That read may have been cut short, which says nothing about the chunk.
				return null;
			}
			if (summary == null || !summary.finished()) {
				// Nothing to draw yet. Once it's finished, the save writes it again.
				progress.mark(regionX, regionZ, i, time);
				this.chunksDone++;
				continue;
			}
			// Ground this map already had is drawn again for its light even where it doesn't fill in.
			if (plan.onlyRemembered && progress.time(regionX, regionZ, i) != Progress.RELIGHT
				&& (memory == null || !memory.showsChunk(cx, cz, summary.maxY()))) {
				progress.mark(regionX, regionZ, i, Progress.NOT_REMEMBERED);
				this.chunksDone++;
				continue;
			}
			long chunk = ChunkSnapshot.key(cx, cz);
			plan.times.put(chunk, time);
			for (TileKey key : this.tilesShowing(cx, cz, summary, plan.facing)) {
				plan.tiles.computeIfAbsent(key, k -> new HashSet<>()).add(chunk);
				plan.waiting.merge(chunk, 1, Integer::sum);
			}
		}
		plan.order.addAll(plan.tiles.keySet());
		// Taken from the end: one column of tiles after another, west to east, each north to south, so
		// the chunks read for a tile serve the tiles beside it.
		plan.order.sort(Comparator.comparingInt(TileKey::tileU).thenComparingInt(TileKey::tileV).reversed());
		if (!plan.tiles.isEmpty() && this.save.prefetches()) {
			this.save.prefetch(this.chunksUnder(plan.tiles.keySet(), regionX, regionZ, plan.facing), false);
		}
		return plan;
	}

	/**
	 * The chunks drawing {@code tiles} reads, of those in the region and the regions around it, with
	 * a ring around them for the biomes their edges blend with: west to east, each north to south,
	 * the way a plan's tiles are drawn.
	 */
	private List<Long> chunksUnder(Set<TileKey> tiles, int regionX, int regionZ, Facing facing) {
		Set<Long> under = new HashSet<>();
		for (int rx = regionX - 1; rx <= regionX + 1; rx++) {
			for (int rz = regionZ - 1; rz <= regionZ + 1; rz++) {
				for (int i = 0; i < 1024; i++) {
					int cx = (rx << 5) | (i & 31);
					int cz = (rz << 5) | (i >> 5);
					ChunkTags.Summary summary = this.save.summary(cx, cz);
					if (summary == null || !summary.finished()) {
						continue;
					}
					for (TileKey key : this.tilesShowing(cx, cz, summary, facing)) {
						if (tiles.contains(key)) {
							for (int dx = -1; dx <= 1; dx++) {
								for (int dz = -1; dz <= 1; dz++) {
									under.add(ChunkSnapshot.key(cx + dx, cz + dz));
								}
							}
							break;
						}
					}
				}
			}
		}
		List<Long> ordered = new ArrayList<>(under);
		ordered.sort(Comparator.comparingInt((Long key) -> (int) (key >> 32)).thenComparingInt(key -> (int) (long) key));
		return ordered;
	}

	/**
	 * The sky view tiles a chunk can show in, as {@link MapLevel} works it out for a newly loaded
	 * chunk: its own blocks, plus the shadows and sky occlusion they cast on blocks nearby.
	 */
	private List<TileKey> tilesShowing(int cx, int cz, ChunkTags.Summary summary, Facing facing) {
		int x0 = facing.blockX(cx, cz) << 4;
		int z0 = facing.blockZ(cx, cz) << 4;
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
		/** Made for redrawing only what's remembered; a change of mind makes a new plan. */
		final boolean onlyRemembered;
		/** Which way the tiles below face: a chunk shows in others from another side, so a turn makes a new plan. */
		final Facing facing;
		/** Tiles left to draw; taken from the end. */
		final List<TileKey> order = new ArrayList<>();
		/** The region's new or changed chunks each tile shows. */
		final Map<TileKey, Set<Long>> tiles = new HashMap<>();
		/** How many of its tiles each of those chunks still waits for; it's done when none are left. */
		final Map<Long, Integer> waiting = new HashMap<>();
		/** When the save last wrote each of those chunks. */
		final Map<Long, Integer> times = new HashMap<>();

		Plan(boolean onlyRemembered, Facing facing) {
			this.onlyRemembered = onlyRemembered;
			this.facing = facing;
		}
	}

	/**
	 * Which saved chunks need no drawing: for each, when the save had last written it at the time,
	 * {@link #LIVE}, {@link #NOT_REMEMBERED} or {@link #CHANGED}. Kept in {@code saved-chunks.bin}:
	 * {@code DSC2}, then deflated, the region count and, per region, its x, z and 1024 values.
	 * {@code DSC1} is the same from before tiles kept their block light: in a sky view that gets
	 * dark at night, everything it drew counts as {@link #RELIGHT}, so it's all drawn once more,
	 * with its light (#204).
	 */
	private static final class Progress {
		private static final int MAGIC = 0x44534332;
		private static final int BEFORE_LIGHT = 0x44534331;
		/**
		 * The map saw this chunk live and saved its tiles, and the game saves a chunk it had loaded
		 * a little later: not news. Without this, every place the player walks would be drawn twice.
		 */
		static final int LIVE = -1;
		/** Not on the map that's being redrawn, so it isn't drawn, unless the map starts filling in. */
		static final int NOT_REMEMBERED = -2;
		/** Drawn once, and changed since: no save time, so it's work, and not a first drawing either. */
		static final int CHANGED = 1;
		/**
		 * Drawn before tiles kept their block light: work once, to draw it with its light, on any map
		 * that has it, whether or not that map fills in from the save.
		 */
		static final int RELIGHT = 2;
		private final Path file;
		private final Map<Long, int[]> times = new HashMap<>();
		/**
		 * The chunks marked {@link #RELIGHT} when this was read, kept for the whole run: the map had
		 * them, so a map that doesn't fill in may still read them, and the chunks around them, for
		 * their light. By {@link ChunkSnapshot#key}.
		 */
		final it.unimi.dsi.fastutil.longs.LongOpenHashSet relight = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		private boolean changed;
		/** When the map last moved away from this folder, in seconds, or 0 if it's been here since. */
		private int leftAt;
		private boolean leftChecked;

		private Progress(Path file) {
			this.file = file;
		}

		static Progress load(Path file, boolean night) {
			Progress progress = new Progress(file);
			Path left = file.resolveSibling(MapMemory.LEFT);
			try {
				if (Files.isRegularFile(left)) {
					progress.leftAt = Integer.parseInt(Files.readString(left).trim());
				}
			} catch (IOException | NumberFormatException e) {
				Diorama.LOGGER.warn("Couldn't read when the map left {}", left, e);
			}
			if (!Files.exists(file)) {
				return progress;
			}
			try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
				int magic = in.readInt();
				if (magic != MAGIC && magic != BEFORE_LIGHT) {
					return progress;
				}
				boolean relight = magic == BEFORE_LIGHT && night;
				DataInputStream data = new DataInputStream(new InflaterInputStream(in));
				int count = data.readInt();
				for (int r = 0; r < count; r++) {
					int x = data.readInt();
					int z = data.readInt();
					int[] times = new int[1024];
					for (int i = 0; i < 1024; i++) {
						times[i] = data.readInt();
						// Drawn there before, live or from the save: draw it again. What wasn't drawn stays so.
						if (relight && (times[i] > RELIGHT || times[i] == LIVE || times[i] == CHANGED)) {
							times[i] = RELIGHT;
							progress.changed = true;
						}
						if (times[i] == RELIGHT) {
							progress.relight.add(ChunkSnapshot.key(x << 5 | i & 31, z << 5 | i >> 5));
						}
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

		boolean isWork(int regionX, int regionZ, int index, int savedTime, boolean onlyRemembered) {
			if (savedTime == 0) {
				return false;
			}
			int time = this.time(regionX, regionZ, index);
			return time != LIVE && time != savedTime && !(onlyRemembered && time == NOT_REMEMBERED);
		}

		boolean hasWork(long region, int[] savedTimes, boolean onlyRemembered) {
			int regionX = (int) (region >> 32);
			int regionZ = (int) region;
			for (int i = 0; i < 1024; i++) {
				if (this.isWork(regionX, regionZ, i, savedTimes[i], onlyRemembered)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * The map came back to this folder. A chunk it saw live is only known to be drawn as it was when
		 * the map left, so one the save has written since is work again.
		 */
		boolean back(Map<Long, int[]> regions) {
			if (this.leftAt == 0) {
				return false;
			}
			for (Map.Entry<Long, int[]> region : regions.entrySet()) {
				int[] times = this.times.get(region.getKey());
				for (int i = 0; times != null && i < 1024; i++) {
					if (times[i] == LIVE && region.getValue()[i] >= this.leftAt) {
						times[i] = CHANGED;
					}
				}
			}
			this.leftAt = 0;
			this.leftChecked = true;
			this.changed = true;
			return true;
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
				if (this.leftChecked) {
					Files.deleteIfExists(this.file.resolveSibling(MapMemory.LEFT));
					this.leftChecked = false;
				}
			} catch (IOException e) {
				Diorama.LOGGER.warn("Couldn't save which saved chunks are drawn to {}", this.file, e);
			}
		}
	}
}
