package dev.diorama.render;

import dev.diorama.Claims;
import dev.diorama.Diorama;
import dev.diorama.Facing;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.ChunkSource;
import dev.diorama.bake.Overlays;
import dev.diorama.bake.PinnedSource;
import dev.diorama.bake.TallChunks;
import dev.diorama.bake.CaveCut;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.SurfaceArea;
import dev.diorama.bake.TileSurface;
import dev.diorama.bake.TileSpots;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.SeenBlocks;
import dev.diorama.data.TileLabel;
import dev.diorama.data.TileStore;
import dev.diorama.data.WorldSavePick;
import dev.diorama.render.gpu.MapTexture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Everything the map knows about one dimension: chunk snapshots, baked tiles, and which tiles
 * need baking. Tiles are baked on demand when a view asks for them, and re-baked shortly after
 * the chunks under them change. As a {@link ChunkSource} it answers in turned space, which is what
 * bakes and picks read; {@link #world()} has the world's own chunks.
 */
public final class MapLevel implements ChunkSource {
	private static final int MAX_CHUNKS = 4096;
	/** Tiles kept on the GPU, about 1.4 MB each; past this, the least recently drawn go down to {@link #KEPT_TILES}. */
	private static final int MAX_GPU_TILES = 256;
	/** As many tiles as stay on the GPU for certain: a view that wants more loses some now and then. */
	static final int KEPT_TILES = MAX_GPU_TILES * 3 / 4;
	/** A changed tile waits this long for more changes before re-baking... */
	private static final long QUIET_MS = 350;
	/** ...but never longer than this. */
	private static final long MAX_WAIT_MS = 1500;

	private static final AtomicInteger THREAD_ID = new AtomicInteger();
	static final int BAKER_COUNT = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 3));
	private static final ExecutorService BAKERS = Executors.newFixedThreadPool(
		BAKER_COUNT,
		task -> {
			Thread thread = new Thread(task, "diorama-baker-" + THREAD_ID.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY + 1);
			return thread;
		}
	);
	/** Packs the snapshots of chunks the game has unloaded, see {@link #packFar}. */
	private static final ExecutorService PACKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "diorama-chunk-pack");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	/** Finds the ground under lights, beside the bakers: a tile bake never queues behind it. */
	private static final ExecutorService SURFACES = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "diorama-surface");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	/** The least time between a tile's surfaces while its pixels keep changing, as with moving vines or water. */
	private static final long SURFACE_INTERVAL_MS = 500;
	/** How long a request for part of a tile counts: long enough for every light's turn, short enough to let a walked-away part go. */
	private static final long SURFACE_WANTED_MS = 1000;
	/** How long a surface stays after the last light left it, so a light going back and forth doesn't remake it. */
	private static final long SURFACE_KEEP_MS = 5000;
	/**
	 * Bakes the GPU self-check's tiles both ways, one at a time, beside the bakers: the map gets
	 * those tiles traced as usual, so checking never makes it wait.
	 */
	private static final ExecutorService CHECKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "diorama-gpu-check");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private final int minY;
	private final int maxY;
	private final ConcurrentHashMap<Long, ChunkSnapshot> chunks = new ConcurrentHashMap<>();
	private volatile int topY = Integer.MIN_VALUE;
	private final SeenBlocks seen;
	/** Where this dimension's map is saved, or null when there's no world to save it for. */
	private final @Nullable Path folder;
	/**
	 * Only shows what's saved in {@link #folder}, for a dimension the player isn't in: tiles load off
	 * the disk as overview tiles do, nothing is baked, and nothing here writes to the folder but
	 * {@link TileStore}, filling in overview levels an older version left out.
	 */
	private final boolean savedOnly;
	/**
	 * Saved tiles for the current camera angle, or null when nothing is saved. Changed on the
	 * render thread; {@link SavedChunkImport} reads it from its own.
	 */
	private volatile @Nullable TileStore store;
	/** The folder {@link #store} saves into; see {@link #storeName()}. */
	private volatile String storeName = "";
	/**
	 * The saved views from on top of the roof as they're baked now, opened with the first one
	 * baked; see {@link #roofStore}. Render thread.
	 */
	private @Nullable TileStore roofStore;
	private String roofStoreName = "";
	/** The tiles this map had under another label, until it's redrawn; null if there are none. */
	private volatile @Nullable MapMemory memory;
	private volatile boolean fogOfWar;
	/** See {@link ChunkSource#depthCaves}. */
	private volatile boolean depthCaves;
	/** What bakes record for the map overlays, or null when no overlay is on. */
	private volatile @Nullable Overlays overlays;
	/**
	 * Bumped whenever tiles baked before stop being valid: new block colours, or cave slices
	 * switching to or from fog of war. Bakes that started under an older value are dropped.
	 * Volatile: the GPU baking check reads them from baker threads.
	 */
	private volatile int surfaceGeneration;
	private volatile int caveGeneration;

	// Render thread only below here.
	private final Map<TileKey, MapTile> tiles = new HashMap<>();
	/** The tiles lights have asked for surfaces of and may hold one. */
	private final Set<TileKey> surfaced = new HashSet<>();
	/** Goes up whenever a tile's coverage changes or a tile comes or goes; see {@link #coverageVersion()}. */
	private long coverageVersion;
	private final Map<TileKey, Dirty> dirty = new HashMap<>();
	private final Map<TileKey, Double> wanted = new HashMap<>();
	private final Set<TileKey> inFlight = new HashSet<>();
	private final ConcurrentLinkedQueue<TileImage> finished = new ConcurrentLinkedQueue<>();
	/** Tiles drawn from the world's save, waiting for the render thread to take them. */
	private final ConcurrentLinkedQueue<SavedTile> fromSave = new ConcurrentLinkedQueue<>();
	/** How long a tile from the save waits for the GPU before it's traced instead. */
	private static final long GPU_WAIT_NANOS = 10_000_000_000L;
	/** The tiles {@link SavedChunkImport} is baking, and those a live bake finished for since it started. */
	private final Set<TileKey> savedTilesBaking = ConcurrentHashMap.newKeySet();
	private final Set<TileKey> savedTilesRaced = ConcurrentHashMap.newKeySet();
	/** Chunks loaded live whose tiles may not be saved yet, while {@link SavedChunkImport} is watching. */
	private final Set<Long> liveChunks = new HashSet<>();
	/** Chunks loaded live with their tiles saved, for {@link SavedChunkImport} to take. */
	private final ConcurrentLinkedQueue<Long> liveChunksSaved = new ConcurrentLinkedQueue<>();
	private volatile boolean watchingLiveChunks;
	/** The live chunks are checked this often: they're as many as the player loads. */
	private static final long LIVE_CHECK_MS = 2000;
	private long lastLiveCheckMs;
	/** A view's priority for a tile just outside it, and past that the further away it is. */
	public static final double OUTSIDE_VIEW = 100_000;
	/** As many as a 4K screen shows at the zoom overview tiles start from. */
	private static final int MAX_VIEW_TILES = 256;
	/** The detailed sky view tiles under what the views last showed, most wanted first; see {@link #importFocus()}. */
	private volatile List<TileKey> viewTiles = List.of();
	/** Where the views last looked, in map space, and when; see {@link #importFocus()}. */
	private volatile double focusU;
	private volatile double focusV;
	private volatile long focusMs;
	/** Overview tiles the writer rebuilt; reloaded when next wanted. */
	private final ConcurrentLinkedQueue<TileKey> overviewChanged = new ConcurrentLinkedQueue<>();
	/** Tiles loaded off the disk with nothing saved there; not asked for again until that changes. */
	private final Set<TileKey> emptyTiles = new HashSet<>();
	/**
	 * Surface tiles whose chunks changed since the tile was last baked, and when. They're baked in
	 * the background and saved, so the saved map covers everywhere the player has been, not only
	 * the parts some map happened to show.
	 */
	private final Map<TileKey, Long> unsaved = new HashMap<>();
	/** Background bakes in flight: saved when done, but not kept on the GPU. */
	private final Set<TileKey> backgroundBakes = new HashSet<>();
	/** A changed tile waits this long for more changes before a background bake. */
	private static final long BACKGROUND_QUIET_MS = 2000;
	/**
	 * Set when a trim couldn't get the snapshots down because their tiles still wait to be saved.
	 * New snapshots then don't start another try each; the next one comes {@link #TRIM_RETRY_MS}
	 * later, since a trim looks at every snapshot.
	 */
	private boolean trimStuck;
	private long lastTrimMs;
	private static final long TRIM_RETRY_MS = 1000;
	/** Where the player was at the last {@link #put}; trims keep the snapshots nearest it. */
	private int playerChunkX;
	private int playerChunkZ;
	private long frame;
	private volatile boolean closed;
	/** Tiles whose claims are being painted. */
	private final Set<TileKey> claimsInFlight = new HashSet<>();
	private final ConcurrentLinkedQueue<PaintedClaims> paintedClaims = new ConcurrentLinkedQueue<>();

	/** Claims painted for a tile, on its pixels as they were at {@code revision}. */
	private record PaintedClaims(TileKey key, MapTile tile, Claims.Snapshot claims, int revision, int @Nullable [] pixels) {
	}

	/** At most this many tiles' claims are painted at once, beside the bakes. */
	private static final int CLAIM_PAINTS = 2;
	/** A tile whose pixels changed has its claims painted again at most this often. */
	private static final long CLAIM_REPAINT_MS = 2000;

	/** See {@link ChunkSource#roof()}. */
	private final int roof;

	public MapLevel(int minY, int maxY, int roof, @Nullable Path folder) {
		this(minY, maxY, roof, folder, false);
	}

	private MapLevel(int minY, int maxY, int roof, @Nullable Path folder, boolean savedOnly) {
		this.minY = minY;
		this.maxY = maxY;
		this.roof = roof;
		this.folder = folder;
		this.savedOnly = savedOnly;
		this.store = this.openStore();
		this.seen = folder == null || savedOnly ? new SeenBlocks() : SeenBlocks.load(folder.resolve("seen.bin"));
	}

	/**
	 * The map saved in a folder, to look at from another dimension. Only the sky view is saved, and
	 * what kind of view that was is told by the folder: a dimension the player isn't in has no level
	 * to ask whether it has a roof, and a modded one could. The roof's height doesn't matter here.
	 */
	public static MapLevel saved(Path folder) {
		boolean roofed = false;
		if (Files.isDirectory(folder)) {
			try (Stream<Path> views = Files.list(folder)) {
				roofed = views.anyMatch(view -> view.getFileName().toString().startsWith("under-roof-"));
			} catch (IOException | java.io.UncheckedIOException e) {
				Diorama.LOGGER.warn("Couldn't look through the map in {}", folder, e);
			}
		}
		return new MapLevel(0, 0, roofed ? 0 : NO_ROOF, folder, true);
	}

	@Override
	public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
		Facing facing = Projection.facing();
		ChunkSnapshot chunk = this.worldChunk(facing.worldBlockX(chunkX, chunkZ), facing.worldBlockZ(chunkX, chunkZ));
		return chunk == null ? null : chunk.turned(facing);
	}

	/** The chunk at a place in the world, as it is. */
	public @Nullable ChunkSnapshot worldChunk(int chunkX, int chunkZ) {
		return this.chunks.get(ChunkSnapshot.key(chunkX, chunkZ));
	}

	/** This map's chunks at their places in the world. */
	public ChunkSource world() {
		return this.world;
	}

	private final ChunkSource world = new ChunkSource() {
		@Override
		public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
			return MapLevel.this.worldChunk(chunkX, chunkZ);
		}

		@Override
		public int minY() {
			return MapLevel.this.minY;
		}

		@Override
		public int maxY() {
			return MapLevel.this.maxY;
		}

		@Override
		public int topY() {
			return MapLevel.this.topY;
		}

		@Override
		public int roof() {
			return MapLevel.this.roof;
		}

		@Override
		public boolean depthCaves() {
			return MapLevel.this.depthCaves;
		}

		@Override
		public boolean isSeen(int x, int y, int z) {
			return MapLevel.this.seen.isSeen(x, y, z);
		}
	};

	@Override
	public int minY() {
		return this.minY;
	}

	@Override
	public int maxY() {
		return this.maxY;
	}

	@Override
	public int topY() {
		return this.topY;
	}

	@Override
	public int roof() {
		return this.roof;
	}

	/** Whether the sky view is drawn from under a roof, lit as if the rock over the first open space were lifted off. */
	public boolean roofless() {
		return this.roof != NO_ROOF;
	}

	public boolean fogOfWar() {
		return this.fogOfWar;
	}

	@Override
	public boolean depthCaves() {
		return this.depthCaves;
	}

	/** Switches cave slices between the depth look and seeing through rock, dropping cave tiles drawn the other way. */
	public void setDepthCaves(boolean value) {
		if (this.depthCaves == value) {
			return;
		}
		this.depthCaves = value;
		if (this.roofless()) {
			// Under a roof every view is lifted, which the depth look leaves alone.
			return;
		}
		this.caveGeneration++;
		this.dropTiles(true);
	}

	@Override
	public boolean isSeen(int x, int y, int z) {
		Facing facing = Projection.facing();
		return this.seen.isSeen(facing.worldBlockX(x, z), y, facing.worldBlockZ(x, z));
	}

	public SeenBlocks seen() {
		return this.seen;
	}

	/** Switches cave slices between full and fog of war, dropping cave tiles baked the other way. */
	public void setFogOfWar(boolean value) {
		if (this.fogOfWar == value) {
			return;
		}
		this.fogOfWar = value;
		this.caveGeneration++;
		this.dropTiles(true);
		// Under a roof the sky view follows fog of war too, and keeps its own saved tiles for it.
		this.switchStoreIfNeeded();
	}

	/**
	 * What bakes record for the overlays. Turning overlays on, turning spawn spots on, or a new
	 * slime seed bakes the tiles in view again, including any bake already under way, so they
	 * record current bits. Switching between overlays a bake already recorded needs no bake.
	 */
	public void setOverlays(@Nullable Overlays overlays) {
		if (java.util.Objects.equals(overlays, this.overlays)) {
			return;
		}
		this.overlays = overlays;
		if (overlays == null) {
			return;
		}
		int blocks = (Projection.TILE_PIXELS / TileImage.BLOCK) * (Projection.TILE_PIXELS / TileImage.BLOCK);
		Set<TileKey> keys = new HashSet<>(this.tiles.keySet());
		keys.addAll(this.inFlight);
		for (TileKey key : keys) {
			if (key.lod() == 0) {
				this.dirty.computeIfAbsent(key, k -> new Dirty(0)).blocks.set(0, blocks);
			}
		}
	}

	/** Whether spawn spots are recorded, so light changes have to refresh the surface. */
	public boolean spawnSpots() {
		Overlays o = this.overlays;
		return o != null && o.spawnSpots();
	}

	/** The saved tiles for how tiles are baked now, and what they remember from before. */
	private @Nullable TileStore openStore() {
		if (this.folder == null) {
			return null;
		}
		this.storeName = this.storeName();
		TileStore opened = new TileStore(this.folder.resolve(this.storeName));
		opened.onOverviewChanged(this.overviewChanged::add);
		this.memory = MapMemory.open(this.folder.resolve(this.storeName), !this.savedOnly);
		return opened;
	}

	/** The folder the sky view's tiles are saved in; see {@link TileLabel}. */
	private String storeName() {
		return new TileLabel(this.roofless(), Math.round(dev.diorama.MapStyle.get().pitchDegrees()), this.roofless() && this.fogOfWar, Projection.facing()).folder();
	}

	/**
	 * Whether a cave slice is cut above the roof, as the map shows it to a player standing on top
	 * of the Nether: the roof's bedrock and what's built on it. Those are the only cave slices
	 * saved. The sky view there looks from under the roof, so it never shows them, and a slice of
	 * flat bedrock takes little room on disk. The slices under a roof and in other dimensions
	 * aren't saved, as there's one for every 4 blocks of height and each is a map of its own.
	 */
	private boolean onRoof(TileKey key) {
		return key.isCave() && this.roofless() && key.layer() > this.roof;
	}

	/**
	 * Where a slice from on top of the roof ({@link #onRoof}) is saved, or null for any other tile
	 * and when this map saves nothing. It's saved as it's baked for a view and merged with what was
	 * saved, like the sky view, so what the player mapped up there is still on the map after its
	 * chunks are gone. Slices baked another way (fog of war, the tilt, the facing) have another
	 * folder; a bake still running for the old one is dropped by its generation. Render thread.
	 */
	private @Nullable TileStore roofStore(TileKey key) {
		if (this.folder == null || this.savedOnly || this.closed || key.lod() != 0 || !this.onRoof(key)) {
			return null;
		}
		String name = TileLabel.onRoof(Math.round(dev.diorama.MapStyle.get().pitchDegrees()), Projection.facing(), this.fogOfWar);
		if (this.roofStore == null || !name.equals(this.roofStoreName)) {
			if (this.roofStore != null) {
				this.roofStore.close();
			}
			this.roofStore = new TileStore(this.folder.resolve(name));
			this.roofStoreName = name;
		}
		return this.roofStore;
	}

	/** Moves to another folder if how tiles are baked changed, and queues everything loaded to be saved there. */
	private void switchStoreIfNeeded() {
		if (this.store == null || this.storeName().equals(this.storeName)) {
			return;
		}
		this.store.close();
		if (this.memory != null) {
			this.memory.close();
		}
		MapMemory.noteLeft(this.folder.resolve(this.storeName));
		this.store = this.openStore();
		this.unsaved.clear();
		this.emptyTiles.clear();
		// Chunks count as seen live per kind of view: what's saved for the old one isn't for this one.
		this.liveChunksSaved.clear();
		if (this.watchingLiveChunks) {
			this.liveChunks.addAll(this.chunks.keySet());
		}
		for (ChunkSnapshot snapshot : this.chunks.values()) {
			int x0 = snapshot.chunkX << 4;
			int z0 = snapshot.chunkZ << 4;
			this.invalidate(x0, Math.min(snapshot.floorY, this.topY), this.minY, z0, x0 + 15, this.newChunkTop(snapshot), this.topY, z0 + 15, true, true);
		}
	}

	/** How high sky-view rays could have stopped at a chunk that was unknown, or can meet it now that it isn't. */
	private int newChunkTop(ChunkSnapshot snapshot) {
		return Math.max(snapshot.maxY + 1, Math.min(this.topY, TallChunks.ORDINARY_TOP));
	}

	/** Block colours or the map style changed: every tile has to be baked again. */
	public void colorsChanged() {
		this.surfaceGeneration++;
		this.caveGeneration++;
		this.dropTiles(false);
		this.switchStoreIfNeeded();
	}

	private void dropTiles(boolean cavesOnly) {
		Iterator<Map.Entry<TileKey, MapTile>> it = this.tiles.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<TileKey, MapTile> e = it.next();
			if (!cavesOnly || e.getKey().isCave() || this.roofless()) {
				e.getValue().close();
				it.remove();
				this.dirty.remove(e.getKey());
				this.coverageVersion++;
			}
		}
	}

	/**
	 * A number that changes whenever what {@link #peek} returns could show different coverage,
	 * so a view can keep its fog mask until it does.
	 */
	public long coverageVersion() {
		return this.coverageVersion;
	}

	private int generation(TileKey key) {
		return key.isCave() || this.roofless() ? this.caveGeneration : this.surfaceGeneration;
	}

	/** Newly seen blocks: cave slices around them can show more now. */
	public void revealed(int x0, int y0, int z0, int x1, int y1, int z1) {
		if (this.fogOfWar) {
			this.invalidate(x0, y0, y0, z0, x1, y1, y1, z1, false, true);
		}
	}

	/** Light changed in these sections; only cave slices use block and sky light. */
	public void lightChanged(int chunkX, int chunkZ, int y0, int y1) {
		int x0 = chunkX << 4;
		int z0 = chunkZ << 4;
		this.invalidate(x0, y0, y0, z0, x0 + 15, y1, y1, z0 + 15, false, true);
	}

	public int chunkCount() {
		return this.chunks.size();
	}

	/** The chunks held now, by {@link ChunkSnapshot#key}, at their places in the world: a copy that stays as it is. */
	public Map<Long, ChunkSnapshot> chunksNow() {
		return new HashMap<>(this.chunks);
	}

	/**
	 * The map units a cave slice cut at {@code layer} can show of the chunks held now, as
	 * {u0, v0, u1, v1}: every chunk's box from the slice's floor to its lifted top. Null with no chunks.
	 */
	public double @Nullable [] sliceBounds(int layer) {
		double u0 = Double.POSITIVE_INFINITY;
		double v0 = Double.POSITIVE_INFINITY;
		double u1 = Double.NEGATIVE_INFINITY;
		double v1 = Double.NEGATIVE_INFINITY;
		for (ChunkSnapshot chunk : this.chunks.values()) {
			double[] box = sliceBox(chunk, layer);
			u0 = Math.min(u0, box[0]);
			v0 = Math.min(v0, box[1]);
			u1 = Math.max(u1, box[2]);
			v1 = Math.max(v1, box[3]);
		}
		return u0 > u1 ? null : new double[] {u0, v0, u1, v1};
	}

	/** The map units one chunk can show in a cave slice cut at {@code layer}, as {u0, v0, u1, v1}. */
	public static double[] sliceBox(ChunkSnapshot chunk, int layer) {
		double top = layer + CaveCut.LIFT + 1;
		double bottom = layer - TileBaker.CAVE_DEPTH - 1;
		double u0 = Double.POSITIVE_INFINITY;
		double v0 = Double.POSITIVE_INFINITY;
		double u1 = Double.NEGATIVE_INFINITY;
		double v1 = Double.NEGATIVE_INFINITY;
		for (int corner = 0; corner < 4; corner++) {
			double x = chunk.chunkX * 16 + (corner & 1) * 16;
			double z = chunk.chunkZ * 16 + (corner >> 1) * 16;
			u0 = Math.min(u0, Projection.u(x, z));
			u1 = Math.max(u1, Projection.u(x, z));
			v0 = Math.min(v0, Projection.v(x, top, z));
			v1 = Math.max(v1, Projection.v(x, bottom, z));
		}
		return new double[] {u0, v0, u1, v1};
	}

	/**
	 * Stores a fresh snapshot and marks the parts of tiles that can look different because of it.
	 *
	 * @param changed the blocks that changed, as {x0, y0, z0, x1, y1, z1} inclusive plus a last
	 *     element of {@link #CHANGED_SURFACE} and {@link #CHANGED_SURFACE_LIGHT} flags; null when
	 *     the whole chunk is new
	 */
	public void put(ChunkSnapshot snapshot, int @Nullable [] changed, int playerChunkX, int playerChunkZ) {
		ChunkSnapshot before = this.chunks.get(snapshot.key());
		if (before != null) {
			// Before it's visible to bakes, so a bake can share what was worked out from the same blocks.
			snapshot.replacing(before);
		}
		this.chunks.put(snapshot.key(), snapshot);
		if (changed == null && this.watchingLiveChunks) {
			this.liveChunks.add(snapshot.key());
		}
		if (snapshot.maxY != Integer.MIN_VALUE && snapshot.maxY + 1 > this.topY) {
			this.topY = Math.min(this.maxY, snapshot.maxY + 1);
		}
		if (changed == null) {
			int x0 = snapshot.chunkX << 4;
			int z0 = snapshot.chunkZ << 4;
			// Rays from any height could have stopped at this chunk while it was unknown. The sky
			// view only reaches down to the surface; cave slices can be anywhere below it.
			this.invalidate(x0, Math.min(snapshot.floorY, this.topY), this.minY, z0, x0 + 15, this.newChunkTop(snapshot), this.topY, z0 + 15, true, true);
		} else {
			// Under a roof the sky view is lit by block light, so light is part of what's saved; and
			// it's saved with the sky view for the night, where it reaches the surface (MapSession says).
			boolean terrain = (changed[6] & (CHANGED_SURFACE | CHANGED_BLOCK_LIGHT)) != 0 || this.roofless() && (changed[6] & CHANGED_SURFACE_LIGHT) != 0;
			boolean surface = terrain || (changed[6] & CHANGED_SURFACE_LIGHT) != 0;
			this.invalidate(changed[0], changed[1], changed[1], changed[2], changed[3], changed[4], changed[4], changed[5], surface, terrain);
		}
		this.playerChunkX = playerChunkX;
		this.playerChunkZ = playerChunkZ;
		if (this.chunks.size() > MAX_CHUNKS && !this.trimStuck) {
			this.trimChunks();
		}
	}

	/** Change flag: blocks the sky view can show changed, so its tiles re-bake and get saved. */
	public static final int CHANGED_SURFACE = 1;
	/** Change flag: only light changed, which the sky view shows only through spawn spots. */
	public static final int CHANGED_SURFACE_LIGHT = 2;
	/**
	 * Change flag: block light changed near enough the surface to show, in a dimension whose sky
	 * view keeps it for the night ({@code BlockLight}).
	 */
	public static final int CHANGED_BLOCK_LIGHT = 4;

	/** Light from a changed block reaches this far, which matters for cave slices. */
	private static final int LIGHT_REACH = 15;

	/**
	 * Marks tile blocks that can look different after blocks in this box changed. {@code y0} and
	 * {@code y1} are the box's bottom and top for the sky view, {@code caveY0} and {@code caveY1} for
	 * cave slices. {@code terrain} is
	 * false when only light changed: the sky view's pixels stay the same, so nothing to save.
	 */
	private void invalidate(int worldX0, int y0, int caveY0, int worldZ0, int worldX1, int y1, int caveY1, int worldZ1, boolean visibleFromSky, boolean terrain) {
		Facing facing = Projection.facing();
		int x0 = Math.min(facing.blockX(worldX0, worldZ0), facing.blockX(worldX1, worldZ1));
		int x1 = Math.max(facing.blockX(worldX0, worldZ0), facing.blockX(worldX1, worldZ1));
		int z0 = Math.min(facing.blockZ(worldX0, worldZ0), facing.blockZ(worldX1, worldZ1));
		int z1 = Math.max(facing.blockZ(worldX0, worldZ0), facing.blockZ(worldX1, worldZ1));
		Set<Integer> layers = new HashSet<>();
		for (TileKey key : this.tiles.keySet()) {
			layers.add(key.layer());
		}
		for (TileKey key : this.inFlight) {
			layers.add(key.layer());
		}
		if (this.store != null) {
			layers.add(TileKey.SURFACE);
		}
		long now = System.currentTimeMillis();
		for (int layer : layers) {
			// Under a roof the sky view is lit by block light like a cave, so light reaches it the same way.
			boolean cave = layer != TileKey.SURFACE || this.roofless();
			if (!cave && !visibleFromSky) {
				continue;
			}
			// Under a roof light also falls from above, so shadows reach as far as on the surface.
			boolean shadows = !cave || this.roofless();
			int reachX = Math.max(cave ? LIGHT_REACH : 0, shadows ? TileBaker.SHADOW_REACH_X : 0);
			int reachZ = Math.max(cave ? LIGHT_REACH : 0, shadows ? TileBaker.SHADOW_REACH_Z : 0);
			int reachY = Math.max(cave ? LIGHT_REACH : 0, shadows ? TileBaker.SHADOW_REACH_Y : 0);
			double top = (cave ? caveY1 : y1) + 2 + (cave ? LIGHT_REACH : 0);
			double bottom = (cave ? caveY0 : y0) - 1 - reachY;
			if (layer != TileKey.SURFACE) {
				// The depth look raises the cut over steps, except under a roof, where views are lifted.
				top = Math.min(top, layer + (this.depthCaves && !this.roofless() ? CaveCut.LIFT : 0));
				bottom = Math.max(bottom, layer - TileBaker.CAVE_DEPTH - 1);
				if (top < bottom) {
					continue;
				}
			} else if (cave) {
				top = Math.min(top, this.roof);
			}
			if (this.roofless() && visibleFromSky && terrain && y0 < this.roof) {
				// Opening or filling a pocket under the roof can move where a column's lifted rock
				// ends, far below the edit, and with it the shadows that rock casts. Re-bake down to
				// the floor. Light changes, newly seen blocks and edits above the roof can't.
				bottom = layer != TileKey.SURFACE ? layer - TileBaker.CAVE_DEPTH - 1 : this.minY - 1;
			}
			// The sky view's sky occlusion looks this far round every block, so a change reaches back.
			int around = cave ? LIGHT_REACH : TileBaker.SKY_REACH;
			if (!cave) {
				bottom = Math.min(bottom, (cave ? caveY0 : y0) - 1 - TileBaker.SKY_REACH);
			}
			double u0 = x0 - 1 - around;
			double u1 = x1 + 2 + Math.max(reachX, cave ? 0 : TileBaker.SKY_REACH);
			double v0 = Projection.v(top, z0 - 1 - Math.max(reachZ, cave ? 0 : TileBaker.SKY_REACH));
			double v1 = Projection.v(bottom, z1 + 2 + around);
			this.markDirty(layer, u0, v0, u1, v1, now, terrain);
		}
	}

	private void markDirty(int layer, double u0, double v0, double u1, double v1, long now, boolean terrain) {
		double units = Projection.TILE_UNITS;
		double blockUnits = (double) TileImage.BLOCK / Projection.PIXELS_PER_UNIT;
		int perRow = Projection.TILE_PIXELS / TileImage.BLOCK;
		int tu0 = (int) Math.floor(u0 / units);
		int tu1 = (int) Math.floor(u1 / units);
		int tv0 = (int) Math.floor(v0 / units);
		int tv1 = (int) Math.floor(v1 / units);
		for (int tu = tu0; tu <= tu1; tu++) {
			for (int tv = tv0; tv <= tv1; tv++) {
				TileKey key = new TileKey(layer, tu, tv);
				if (layer == TileKey.SURFACE && this.store != null && terrain) {
					this.unsaved.put(key, now);
				}
				if (!this.tiles.containsKey(key) && !this.inFlight.contains(key)) {
					continue;
				}
				int bx0 = Math.max(0, (int) Math.floor((u0 - tu * units) / blockUnits));
				int bx1 = Math.min(perRow - 1, (int) Math.floor((u1 - tu * units) / blockUnits));
				int by0 = Math.max(0, (int) Math.floor((v0 - tv * units) / blockUnits));
				int by1 = Math.min(perRow - 1, (int) Math.floor((v1 - tv * units) / blockUnits));
				Dirty d = this.dirty.computeIfAbsent(key, k -> new Dirty(now));
				d.last = now;
				for (int by = by0; by <= by1; by++) {
					d.blocks.set(by * perRow + bx0, by * perRow + bx1 + 1);
				}
			}
		}
	}

	/** Blocks of a tile waiting to be re-baked, and since when. */
	private static final class Dirty {
		final BitSet blocks = new BitSet();
		final long first;
		long last;

		Dirty(long now) {
			this.first = now;
			this.last = now;
		}
	}

	/**
	 * Packs the snapshots further than {@code radius} chunks from the player's chunk: the game has
	 * unloaded those chunks, so nothing replaces their snapshots, and they are only read again if
	 * their tiles are redrawn. See {@link ChunkSnapshot#pack}. The client thread, now and then.
	 */
	public void packFar(int playerChunkX, int playerChunkZ, int radius) {
		for (ChunkSnapshot snapshot : this.chunks.values()) {
			if ((Math.abs(snapshot.chunkX - playerChunkX) > radius || Math.abs(snapshot.chunkZ - playerChunkZ) > radius) && snapshot.startPacking()) {
				PACKER.execute(snapshot::pack);
			}
		}
	}

	private void trimChunks() {
		int playerChunkX = this.playerChunkX;
		int playerChunkZ = this.playerChunkZ;
		List<ChunkSnapshot> all = new ArrayList<>(this.chunks.values());
		all.sort(Comparator.comparingLong(s -> -(sq(s.chunkX - playerChunkX) + sq(s.chunkZ - playerChunkZ))));
		int excess = all.size() - MAX_CHUNKS * 3 / 4;
		for (int i = 0; i < all.size() && excess > 0; i++) {
			// A chunk under a tile still waiting for its background bake stays until it's saved.
			if (!this.waitingToSave(all.get(i))) {
				this.chunks.remove(all.get(i).key());
				excess--;
			}
		}
		this.trimStuck = excess > 0;
		this.lastTrimMs = System.currentTimeMillis();
	}

	/**
	 * Whether a surface tile this chunk shows in still has to be baked and saved: it changed since
	 * its last bake, or its bake is running and may still read this chunk.
	 */
	private boolean waitingToSave(ChunkSnapshot snapshot) {
		if (this.unsaved.isEmpty() && this.inFlight.isEmpty() || snapshot.maxY == Integer.MIN_VALUE) {
			return false;
		}
		double units = Projection.TILE_UNITS;
		ChunkSnapshot turned = snapshot.turned(Projection.facing());
		int x0 = turned.chunkX << 4;
		int z0 = turned.chunkZ << 4;
		int tu0 = (int) Math.floor(x0 / units);
		int tu1 = (int) Math.floor((x0 + 16) / units);
		// Under a roof the chunk's height is the roof's: what shows can be anywhere down to the floor.
		int tv0 = (int) Math.floor(Projection.v(this.roofless() ? Math.min(snapshot.maxY + 1, this.roof) : snapshot.maxY + 1, z0) / units);
		int tv1 = (int) Math.floor(Projection.v(this.roofless() ? this.minY : snapshot.floorY, z0 + 16) / units);
		for (int tu = tu0; tu <= tu1; tu++) {
			for (int tv = tv0; tv <= tv1; tv++) {
				TileKey key = new TileKey(TileKey.SURFACE, tu, tv);
				if (this.unsaved.containsKey(key) || this.inFlight.contains(key)) {
					return true;
				}
			}
		}
		return false;
	}

	private static long sq(long v) {
		return v * v;
	}

	/**
	 * Returns the tile for drawing if one has been baked, and records that a view wants it.
	 * {@code priority} is lower for tiles that matter more, such as those near the view center.
	 */
	public @Nullable MapTile want(TileKey key, double priority) {
		this.wanted.merge(key, priority, Math::min);
		MapTile tile = this.tiles.get(key);
		if (tile != null) {
			tile.lastDrawn = this.frame;
		}
		return tile;
	}

	/**
	 * A tile at another zoom level standing in for one that hasn't loaded yet, if it's in memory:
	 * counted as drawn so it stays, and nothing is asked for, so the stand-in costs no loading.
	 */
	public @Nullable MapTile standIn(TileKey key) {
		MapTile tile = this.tiles.get(key);
		if (tile != null) {
			tile.lastDrawn = this.frame;
		}
		return tile;
	}

	/** The baked tile, if any, without asking for it to be baked. */
	public @Nullable MapTile peek(TileKey key) {
		return this.tiles.get(key);
	}

	/** The surfaces of the {@code layer} tiles under map pixels (pu0, pv0) to (pu1, pv1); see {@link #surface}. Render thread. */
	public SurfaceArea surfaces(int layer, int pu0, int pv0, int pu1, int pv1) {
		int size = Projection.TILE_PIXELS;
		int tu0 = Math.floorDiv(pu0, size);
		int tv0 = Math.floorDiv(pv0, size);
		// Never fewer than no tiles, for an area given upside down or back to front.
		int columns = Math.max(0, Math.floorDiv(pu1 - 1, size) - tu0 + 1);
		int rows = Math.max(0, Math.floorDiv(pv1 - 1, size) - tv0 + 1);
		TileSurface[] tiles = new TileSurface[columns * rows];
		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < columns; column++) {
				int minPu = (tu0 + column) * size;
				int minPv = (tv0 + row) * size;
				tiles[row * columns + column] = this.surface(
					new TileKey(layer, tu0 + column, tv0 + row, 0),
					Math.max(pu0 - minPu, 0), Math.max(pv0 - minPv, 0), Math.min(pu1 - minPu, size), Math.min(pv1 - minPv, size)
				);
			}
		}
		return new SurfaceArea(tu0, tv0, columns, rows, tiles);
	}

	/**
	 * What the camera sees at tile pixels (x0, y0) to (x1, y1) of a baked tile, with
	 * what other lights asked for lately and a little around them so a walking light seldom outgrows it. Made on the surface thread, again
	 * if the light moves out of it or at most every {@link #SURFACE_INTERVAL_MS} if its pixels
	 * change. Until a new one is ready the last one stands in; null before the first, and for
	 * tiles that have none (overviews, under a roof). Render thread.
	 */
	private @Nullable TileSurface surface(TileKey key, int x0, int y0, int x1, int y1) {
		MapTile tile = this.tiles.get(key);
		if (tile == null || key.lod() != 0 || this.roofless()) {
			return null;
		}
		long now = System.currentTimeMillis();
		tile.surfaceAsked = now;
		this.surfaced.add(key);
		if (tile.surfacePending != null && tile.surfacePending.isDone()) {
			TileSurface made = tile.surfacePending.getNow(null);
			tile.surfacePending = null;
			if (made != null) {
				tile.surface = made;
			}
		}
		BlockLooks looks = BlockLooks.get();
		TileSurface surface = tile.surface;
		int[] wanted = tile.surfaceWanted;
		if (wanted == null || now - tile.surfaceWantedSince > SURFACE_WANTED_MS) {
			wanted = tile.surfaceWanted = new int[] {x0, y0, x1, y1};
			tile.surfaceWantedSince = now;
		} else {
			wanted[0] = Math.min(wanted[0], x0);
			wanted[1] = Math.min(wanted[1], y0);
			wanted[2] = Math.max(wanted[2], x1);
			wanted[3] = Math.max(wanted[3], y1);
		}
		boolean outgrown = surface == null || !surface.covers(wanted[0], wanted[1], wanted[2], wanted[3]);
		boolean stale = surface != null && surface.revision() != tile.surfaceChanges && now - tile.surfaceStarted >= SURFACE_INTERVAL_MS;
		if (tile.surfacePending == null && looks != null && (outgrown || stale)) {
			int revision = tile.surfaceChanges;
			boolean fog = this.fogOfWar;
			int size = Projection.TILE_PIXELS;
			int edge = TileImage.BLOCK;
			int ax0 = Math.max(0, Math.floorDiv(wanted[0], edge) * edge - edge);
			int ay0 = Math.max(0, Math.floorDiv(wanted[1], edge) * edge - edge);
			int ax1 = Math.min(size, (Math.floorDiv(wanted[2] - 1, edge) + 2) * edge);
			int ay1 = Math.min(size, (Math.floorDiv(wanted[3] - 1, edge) + 2) * edge);
			tile.surfaceStarted = now;
			tile.surfacePendingArea = new int[] {ax0, ay0, ax1, ay1};
			tile.surfacePending = CompletableFuture.supplyAsync(() -> {
				try {
					return TileBaker.surface(key, revision, this, looks, fog, ax0, ay0, ax1 - ax0, ay1 - ay0);
				} catch (RuntimeException e) {
					Diorama.LOGGER.warn("Couldn't find the ground under tile {}", key, e);
					return null;
				}
			}, SURFACES);
		}
		return surface;
	}

	/** Frees the surfaces no light has asked for lately, and skips those still queued. Render thread. */
	private void releaseSurfaces() {
		long now = System.currentTimeMillis();
		for (Iterator<TileKey> it = this.surfaced.iterator(); it.hasNext(); ) {
			MapTile tile = this.tiles.get(it.next());
			if (tile == null) {
				it.remove();
			} else if (now - tile.surfaceAsked > SURFACE_KEEP_MS) {
				if (tile.surfacePending != null) {
					tile.surfacePending.cancel(false);
					tile.surfacePending = null;
				}
				tile.surface = null;
				it.remove();
			}
		}
	}

	/** Whether overview tiles exist for zooming far out: only when the map is saved. */
	public boolean hasOverviews() {
		return this.store != null;
	}

	/** The saved sky view, or null when nothing is saved. Render thread. */
	public @Nullable TileStore savedMap() {
		return this.store;
	}

	/** What this map remembers from before its tiles' label changed, or null for nothing. */
	public @Nullable MapMemory memory() {
		return this.memory;
	}

	/** Uploads finished bakes and starts new ones. Call regularly on the render thread. */
	public void update() {
		this.frame++;
		this.releaseSurfaces();
		TileKey changedOverview;
		while ((changedOverview = this.overviewChanged.poll()) != null) {
			this.emptyTiles.remove(changedOverview);
			// A load still in flight may have read the old pixels: load it again after.
			if (this.tiles.containsKey(changedOverview) || this.inFlight.contains(changedOverview)) {
				this.dirty.computeIfAbsent(changedOverview, k -> new Dirty(0)).blocks.set(0);
			}
		}
		this.takeFinished(Integer.MAX_VALUE);
		SavedTile savedTile;
		while ((savedTile = this.fromSave.poll()) != null) {
			savedTile.taken().complete(this.tileFromSave(savedTile.image(), savedTile.store()));
		}
		PaintedClaims painted;
		while ((painted = this.paintedClaims.poll()) != null) {
			this.claimsInFlight.remove(painted.key());
			// Not for a tile evicted meanwhile, nor one put in its place.
			if (this.tiles.get(painted.key()) == painted.tile() && !this.closed) {
				painted.tile().paintClaims(painted.pixels(), painted.claims(), painted.revision());
			}
		}
		if (this.trimStuck && System.currentTimeMillis() - this.lastTrimMs >= TRIM_RETRY_MS) {
			this.trimChunks();
		}
		if (System.currentTimeMillis() - this.lastLiveCheckMs >= LIVE_CHECK_MS) {
			this.lastLiveCheckMs = System.currentTimeMillis();
			this.handOverLiveChunks();
		}

		BlockLooks looks = BlockLooks.get();
		if (this.savedOnly || looks != null && this.topY != Integer.MIN_VALUE) {
			long now = System.currentTimeMillis();
			int limit = 6;
			List<Map.Entry<TileKey, Double>> order = new ArrayList<>(this.wanted.entrySet());
			order.sort(Map.Entry.comparingByValue());
			if (!order.isEmpty()) {
				TileKey middle = order.get(0).getKey();
				this.focusU = middle.minU() + middle.units() / 2.0;
				this.focusV = middle.minV() + middle.units() / 2.0;
				this.focusMs = now;
				List<TileKey> inView = new ArrayList<>();
				for (Map.Entry<TileKey, Double> entry : order) {
					TileKey key = entry.getKey();
					if (entry.getValue() >= OUTSIDE_VIEW || key.isCave() || inView.size() >= MAX_VIEW_TILES) {
						continue;
					}
					if (key.lod() == 0) {
						inView.add(key);
					} else if (key.lod() == 1) {
						// A view on the first overview level shows the four detailed tiles under each of
						// its tiles, and the save's chunks are drawn onto those.
						for (int i = 0; i < 4; i++) {
							inView.add(new TileKey(key.layer(), key.tileU() * 2 + (i & 1), key.tileV() * 2 + (i >> 1)));
						}
					}
				}
				this.viewTiles = inView;
			}
			for (Map.Entry<TileKey, Double> entry : order) {
				if (this.inFlight.size() >= limit) {
					if (SLOT_STATS) {
						this.slotsFull++;
					}
					break;
				}
				TileKey key = entry.getKey();
				if (this.inFlight.contains(key)) {
					continue;
				}
				boolean missing = !this.tiles.containsKey(key);
				if (key.lod() > 0 || this.savedOnly) {
					// Overview tiles come from disk, shrunk from saved tiles; nothing to bake.
					if ((missing || this.dirty.containsKey(key)) && this.store != null && !this.emptyTiles.contains(key)) {
						this.dirty.remove(key);
						this.loadSaved(key, this.store);
					}
					continue;
				}
				Dirty d = this.dirty.get(key);
				boolean ready = d != null && (now - d.last > QUIET_MS || now - d.first > MAX_WAIT_MS);
				if (missing || ready) {
					this.dirty.remove(key);
					this.unsaved.remove(key);
					this.bake(key, looks, missing || d.blocks.cardinality() > 160 ? null : d.blocks);
				}
			}
			this.bakeUnsaved(looks, now);
		}
		this.wanted.clear();
		this.evict();
	}

	/**
	 * Takes up to {@code most} finished bakes, oldest first. Frames take one each between updates,
	 * so a tile's upload doesn't land on the same frame as the next one's, or as the tick's work;
	 * {@link #update} takes whatever is left. Render thread.
	 */
	public void takeFinished(int most) {
		TileImage image;
		for (int taken = 0; taken < most && (image = this.finished.poll()) != null; taken++) {
			this.bakeFinished(image);
			// After the image reached the store, so a tile from the save either reads it or hears of it.
			if (this.savedTilesBaking.contains(image.key())) {
				this.savedTilesRaced.add(image.key());
			}
		}
	}

	/** Takes a finished bake: shows it if a map still wants it, and saves it. */
	private void bakeFinished(TileImage image) {
		this.inFlight.remove(image.key());
		if (SLOT_STATS) {
			this.slotReleased(image.key());
		}
		boolean background = this.backgroundBakes.remove(image.key());
		if (this.closed) {
			return;
		}
		if (image.generation() != this.generation(image.key())) {
			// Baked under old colours or the other cave mode; bake it again.
			MapTile stale = this.tiles.remove(image.key());
			if (stale != null) {
				stale.close();
				this.coverageVersion++;
			}
			if (background) {
				this.unsaved.put(image.key(), 0L);
			}
			return;
		}
		TileStore onRoof = this.roofStore(image.key());
		if (onRoof != null) {
			onRoof.baked(image);
		}
		MapTile tile = this.tiles.get(image.key());
		if (background && tile == null) {
			// Baked only to be saved; a map that wants it will bake it again.
			if (this.store != null) {
				this.store.baked(image);
				this.pixelsMoved(image.key());
			}
			return;
		}
		if (tile == null) {
			if ((image.key().lod() > 0 || this.savedOnly) && !image.isFull()) {
				if (!this.dirty.containsKey(image.key())) {
					this.emptyTiles.add(image.key());
				}
			}
			if (!image.isFull()) {
				// The tile was evicted while its patch baked.
				return;
			}
			tile = new MapTile();
			// Drawn as of now. A view that zoomed away before the bake was done never draws it, and
			// left at 0 it counted as unused since the game started: the next trim dropped it, and
			// the view baked it again on its way back, every time.
			tile.lastDrawn = this.frame;
			this.tiles.put(image.key(), tile);
		}
		tile.upload(image);
		this.pixelsMoved(image.key());
		this.coverageVersion++;
		if (this.store != null && !this.savedOnly && !image.key().isCave() && image.key().lod() == 0) {
			this.store.baked(image);
		}
	}

	/**
	 * Takes a tile drawn from the save, unless the live world got in its way. A full bake replaces
	 * what's saved, and each started from the tile as it was then: letting either bake over the
	 * other would lose what the other drew, and the live one must win. So a tile with a live bake
	 * running, or one that finished since this one started, goes back to be drawn again after it.
	 * A live bake that's only due is no reason: it starts from this tile, and re-bakes what changed.
	 * Render thread.
	 */
	private boolean tileFromSave(TileImage image, TileStore drawnFor) {
		TileKey key = image.key();
		TileStore store = this.store;
		// A map turned while this was drawn has another store: the tile looks the old way.
		if (store != drawnFor || this.closed || image.generation() != this.generation(key) || this.savedTilesRaced.contains(key) || this.inFlight.contains(key)) {
			return false;
		}
		MapTile tile = this.tiles.get(key);
		if (tile != null) {
			tile.upload(image);
			this.coverageVersion++;
			if (this.overlays != null) {
				// This bake recorded no overlays, and needs a live one to get them back.
				int blocks = (Projection.TILE_PIXELS / TileImage.BLOCK) * (Projection.TILE_PIXELS / TileImage.BLOCK);
				this.dirty.computeIfAbsent(key, k -> new Dirty(0)).blocks.set(0, blocks);
			}
		}
		this.pixelsMoved(key);
		store.baked(image);
		return true;
	}

	/**
	 * Experiment only, with {@code -Ddiorama.slotStats=true}: how long tiles hold a bake slot, from
	 * being scheduled to their result reaching {@link #update}, and how often a wanted tile found
	 * every slot taken.
	 */
	private static final boolean SLOT_STATS = Boolean.getBoolean("diorama.slotStats");
	private final Map<TileKey, Long> slotTaken = new HashMap<>();
	private final long[] slotNanos = new long[2];
	private final int[] slotCount = new int[2];
	private int slotsFull;

	private void slotReleased(TileKey key) {
		Long taken = this.slotTaken.remove(key);
		if (taken == null) {
			return;
		}
		int kind = key.isCave() ? 1 : 0;
		this.slotNanos[kind] += System.nanoTime() - taken;
		this.slotCount[kind]++;
		if ((this.slotCount[0] + this.slotCount[1]) % 50 == 0) {
			Diorama.LOGGER.info("SLOTS held ms: surface {} over {}, cave {} over {}; updates with every slot taken: {}",
				this.slotCount[0] == 0 ? 0 : this.slotNanos[0] / this.slotCount[0] / 1_000_000, this.slotCount[0],
				this.slotCount[1] == 0 ? 0 : this.slotNanos[1] / this.slotCount[1] / 1_000_000, this.slotCount[1], this.slotsFull);
		}
	}

	/** Bakes one tile on a baker thread: all of it if {@code blocks} is null, else those blocks. */
	private void bake(TileKey key, BlockLooks looks, @Nullable BitSet blocks) {
		this.inFlight.add(key);
		if (SLOT_STATS) {
			this.slotTaken.put(key, System.nanoTime());
		}
		int generation = this.generation(key);
		boolean fog = this.fogOfWar;
		Overlays overlays = this.overlays;
		TileStore store = key.isCave() ? null : this.store;
		// What the bake merges with: a slice from on top of the roof has saved pixels too, but no spots or block light.
		TileStore saveTo = key.isCave() ? this.roofStore(key) : store;
		// As TileBaker decides: under a roof (the Nether) every view is lit as if the roof were
		// lifted off, cave slices too; elsewhere a cave slice is lit by block light. Fog of war only
		// changes those two.
		int view = this.roof() != dev.diorama.bake.ChunkSource.NO_ROOF ? dev.diorama.render.gpu.GpuBakeCheck.UNDER_ROOF
			: key.isCave() ? dev.diorama.render.gpu.GpuBakeCheck.CAVE : dev.diorama.render.gpu.GpuBakeCheck.SKY;
		dev.diorama.render.gpu.GpuBakeCheck.Mode gpu = dev.diorama.render.gpu.GpuBakeCheck.mode(view, view != dev.diorama.render.gpu.GpuBakeCheck.SKY && fog, overlays != null);
		if (gpu == dev.diorama.render.gpu.GpuBakeCheck.Mode.GPU) {
			this.bakeOnGpu(key, looks, generation, fog, overlays, store, saveTo);
			return;
		}
		if (gpu == dev.diorama.render.gpu.GpuBakeCheck.Mode.CHECK) {
			// Traced for the map below, and checked on the side.
			this.checkOnGpu(key, looks, generation, fog, overlays, view);
		}
		BAKERS.execute(() -> {
			try {
				long start = System.nanoTime();
				TileStore.Saved saved = saveTo == null ? null : saveTo.readSaved(key);
				ChunkSource source = this.sourceFor(store);
				TileImage image = TileBaker.bake(key, source, looks, blocks, fog, pixelsOf(saved), lightOf(saved), spotsOf(saved), overlays);
				long spots = System.nanoTime();
				this.finished.add(this.withSpots(image, source, looks, fog, store).withGeneration(generation));
				logBakeTime(System.nanoTime() - start, System.nanoTime() - spots, blocks == null);
			} catch (Throwable t) {
				logFailed("Failed to bake tile {}", key, t);
				this.finished.add(TileImage.partial(key, List.of()).withGeneration(generation));
			}
		});
	}

	/** A bake stopped by block states being numbered again (see {@link BlockLooks#bakeId}) says so only in debug. */
	private static void logFailed(String message, TileKey key, Throwable t) {
		if (t instanceof BlockLooks.StatesRenumbered) {
			Diorama.LOGGER.debug(message + ": block states were being numbered again", key);
		} else {
			Diorama.LOGGER.error(message, key, t);
		}
	}

	/**
	 * GPU baking: the mesh is built on a baker thread, drawn on the render thread, and the pixels
	 * come back here a frame or two later. Always the whole tile, which costs the GPU little.
	 * Falls back to tracing if the mesher failed or the GPU didn't draw it.
	 */
	private void bakeOnGpu(
		TileKey key, BlockLooks looks, int generation, boolean fog, @Nullable Overlays overlays, @Nullable TileStore store, @Nullable TileStore saveTo
	) {
		BAKERS.execute(() -> {
			try {
				TileStore.Saved saved = saveTo == null ? null : saveTo.readSaved(key);
				ChunkSource source = this.sourceFor(store);
				dev.diorama.bake.GpuMesher.Mesh mesh;
				try {
					mesh = dev.diorama.bake.GpuMesher.build(key, source, looks, fog, overlays, spotsOf(saved), store != null);
				} catch (BlockLooks.StatesRenumbered e) {
					throw e;
				} catch (Throwable t) {
					Diorama.LOGGER.error("Failed to mesh tile {}; tracing it", key, t);
					dev.diorama.render.gpu.GpuBakeCheck.drawn(false);
					TileImage image = TileBaker.bake(key, source, looks, null, fog, pixelsOf(saved), lightOf(saved), spotsOf(saved), overlays);
					this.finished.add(this.withSpots(image, source, looks, fog, store).withGeneration(generation));
					return;
				}
				// Only the spots outlive the mesh: it is freed once drawn.
				int[] meshSpots = mesh.spots;
				dev.diorama.render.gpu.GpuTileBaker.submit(mesh, result -> BAKERS.execute(() -> {
					try {
						if (!result.declined()) {
							dev.diorama.render.gpu.GpuBakeCheck.drawn(result.pixels() != null);
						}
						TileImage image = result.pixels() == null
							? TileBaker.bake(key, source, looks, null, fog, pixelsOf(saved), lightOf(saved), spotsOf(saved), overlays)
							: TileBaker.gpuImage(key, result.pixels(), pixelsOf(saved), result.info(), result.blockLight(), lightOf(saved));
						// The mesh's walk found the spots in the chunks the GPU drew; else they're picked now.
						image = result.pixels() != null && meshSpots != null
							? image.withSpots(TileSpots.fromMesh(meshSpots, store.spots(key)))
							: this.withSpots(image, source, looks, fog, store);
						this.finished.add(image.withGeneration(generation));
					} catch (Throwable t) {
						logFailed("Failed to finish GPU tile {}", key, t);
						this.finished.add(TileImage.partial(key, List.of()).withGeneration(generation));
					}
				}));
			} catch (Throwable t) {
				logFailed("Failed to mesh tile {}", key, t);
				this.finished.add(TileImage.partial(key, List.of()).withGeneration(generation));
			}
		});
	}

	/**
	 * A self-check tile: baked on the GPU and traced the same way, from the same chunks, and
	 * compared (see {@link dev.diorama.render.gpu.GpuBakeCheck}). Nothing here reaches the map,
	 * which traces the tile as usual meanwhile.
	 */
	private void checkOnGpu(TileKey key, BlockLooks looks, int generation, boolean fog, @Nullable Overlays overlays, int view) {
		CHECKER.execute(() -> {
			boolean answered = false;
			boolean meshed = false;
			try {
				// Both bakes read the same chunks, even while more are arriving, so only the GPU
				// can make them differ.
				dev.diorama.bake.ChunkSource source = new dev.diorama.bake.PinnedSource(this);
				// The mesher, the tracer and the GPU's uniforms each read the look, in that order:
				// if the last saw the same as before the first, the two bakes show one look.
				dev.diorama.MapStyle look = dev.diorama.MapStyle.get();
				dev.diorama.bake.GpuMesher.Mesh mesh = dev.diorama.bake.GpuMesher.build(key, source, looks, fog, overlays);
				meshed = true;
				byte[] info = overlays != null ? new byte[Projection.TILE_PIXELS * Projection.TILE_PIXELS] : null;
				int[] traced = TileBaker.reference(key, source, looks, fog, overlays, info);
				// An empty mesh, or one left to the tracer, never reaches the GPU.
				if (!mesh.empty && !mesh.traceInstead) {
					dev.diorama.render.gpu.GpuBakeCheck.checking(view, overlays != null);
				}
				dev.diorama.render.gpu.GpuTileBaker.submit(mesh, result -> CHECKER.execute(() -> {
					if (result.pixels() != null && !mesh.empty) {
						dev.diorama.render.gpu.GpuBakeCheck.readBack(view, overlays != null);
					}
					if (result.pixels() == null) {
						dev.diorama.render.gpu.GpuBakeCheck.undrawn(view, overlays != null, !result.declined());
					} else if (this.generation(key) == generation && look.equals(result.look())) {
						dev.diorama.render.gpu.GpuBakeCheck.compare(view, result.pixels(), traced, result.info(), info, result.timing().frameNanos());
					} else {
						// The map's look changed while it was out: the two may have baked different looks.
						dev.diorama.render.gpu.GpuBakeCheck.undrawn(view, overlays != null, false);
					}
				}));
				// From here the callback answers the check.
				answered = true;
			} catch (Throwable t) {
				if (!answered) {
					// The check tile never reached the GPU: hand its turn back. If the mesher failed, it
					// counts against GPU baking, or every tile of this view would try and fail again.
					dev.diorama.render.gpu.GpuBakeCheck.undrawn(view, overlays != null, !meshed && !(t instanceof BlockLooks.StatesRenumbered));
				}
				logFailed("Failed to check tile {} on the GPU", key, t);
			}
		});
	}

	/**
	 * What a bake reads chunks from. A tile to be saved gets its {@link TileSpots} too, from the
	 * mesher's walk or in a second pass, which has to see the same chunks, or a block changed in
	 * between would save pixels showing it with spots that don't.
	 */
	private ChunkSource sourceFor(@Nullable TileStore store) {
		return store == null ? this : new PinnedSource(this);
	}

	/**
	 * A bake of a tile to be saved, with its {@link TileSpots} worked out from the chunks the bake
	 * read, so the right-click menu still works there once they're gone. Baker threads.
	 */
	private TileImage withSpots(TileImage image, ChunkSource source, BlockLooks looks, boolean fog, @Nullable TileStore store) {
		if (store == null) {
			return image;
		}
		return image.withSpots(TileSpots.pick(image, source, looks, fog, store.spots(image.key())));
	}

	/**
	 * What the map knows of a point: where a player would stand there, or null for nowhere.
	 * {@code unsaved} when the map shows something there but doesn't know where its ground is.
	 */
	public record Spot(@Nullable BlockPos pos, boolean unsaved) {
		public static final Spot NOTHING = new Spot(null, false);
	}

	/**
	 * Where a player would stand at a map point by the spots saved with its tile, for where the
	 * chunks are gone. Reads the disk off the render thread.
	 *
	 * <p>A tile saved before tiles kept their spots (Diorama 0.3.0 and older) has none. Where it
	 * shows something, {@code readWorld} works the spot out from the world's chunks in
	 * singleplayer; elsewhere, or where the save has nothing either, the answer is {@code unsaved}.
	 */
	public CompletableFuture<Spot> savedSpot(int layer, double u, double v, BlockLooks looks, boolean readWorld) {
		TileStore store = this.store;
		if (store == null || layer != TileKey.SURFACE) {
			return CompletableFuture.completedFuture(Spot.NOTHING);
		}
		TileKey key = new TileKey(layer, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
		WorldSavePick world = readWorld ? WorldSavePick.current() : null;
		boolean fog = this.fogOfWar;
		Facing facing = Projection.facing();
		return CompletableFuture.supplyAsync(() -> {
			int[] spots = store.spots(key);
			if (TileSpots.known(key, spots, u, v)) {
				BlockPos spot = TileSpots.spot(key, spots, u, v);
				return CompletableFuture.completedFuture(new Spot(spot == null ? null : facing.worldPos(spot), false));
			}
			if (!drawn(store.read(key), key, u, v)) {
				return CompletableFuture.completedFuture(Spot.NOTHING);
			}
			if (world == null) {
				return CompletableFuture.completedFuture(new Spot(null, true));
			}
			// Nothing found in the save, where the map shows something: say to come back, not that nothing's there.
			return world.pick(this.world, facing, looks, u, v, fog).thenApply(pos -> new Spot(pos, pos == null));
		}).thenCompose(spot -> spot).exceptionally(t -> {
			Diorama.LOGGER.warn("Couldn't read the saved spots of tile {}", key, t);
			return Spot.NOTHING;
		});
	}

	/** Whether a saved tile shows anything at map point {@code (u, v)}. */
	private static boolean drawn(int @Nullable [] pixels, TileKey key, double u, double v) {
		if (pixels == null) {
			return false;
		}
		int size = Projection.TILE_PIXELS;
		int x = (int) Math.max(0L, Math.min(size - 1L, (long) Math.floor((u - key.minU()) * Projection.PIXELS_PER_UNIT)));
		int y = (int) Math.max(0L, Math.min(size - 1L, (long) Math.floor((v - key.minV()) * Projection.PIXELS_PER_UNIT)));
		return (pixels[y * size + x] >>> 24) != 0;
	}

	/**
	 * The land claims to draw over a tile, or null for none. Asks for them to be painted, off the
	 * render thread, when the claims under the tile changed or the tile did; the old ones show
	 * meanwhile. Only the saved sky view knows which chunk each pixel shows.
	 */
	public @Nullable MapTexture claims(TileKey key, MapTile tile) {
		TileStore store = this.store;
		if (store == null || key.isCave()) {
			return null;
		}
		Claims.Snapshot claims = Claims.get();
		Claims.Snapshot painted = tile.claimsPainted;
		boolean moved = tile.claimsRevision != tile.pixelsRevision;
		if (painted == claims && !moved) {
			return tile.claims();
		}
		// The chunks the tile could show, and the columns past its sides that its edges look at:
		// its columns, and the rows' ground anywhere from the bottom of the world to the top.
		int tx0 = Math.floorDiv((int) Math.floor(key.minU()), 16) - 1;
		int tx1 = Math.floorDiv((int) Math.ceil(key.minU() + key.units()), 16) + 1;
		int tz0 = Math.floorDiv((int) Math.floor(Projection.zAt(key.minV(), this.minY)), 16);
		int tz1 = Math.floorDiv((int) Math.ceil(Projection.zAt(key.minV() + key.units(), this.maxY)), 16);
		Facing facing = Projection.facing();
		int cx0 = Math.min(facing.worldBlockX(tx0, tz0), facing.worldBlockX(tx1, tz1));
		int cx1 = Math.max(facing.worldBlockX(tx0, tz0), facing.worldBlockX(tx1, tz1));
		int cz0 = Math.min(facing.worldBlockZ(tx0, tz0), facing.worldBlockZ(tx1, tz1));
		int cz1 = Math.max(facing.worldBlockZ(tx0, tz0), facing.worldBlockZ(tx1, tz1));
		boolean near = claims.mayHaveAny(cx0, cz0, cx1, cz1);
		boolean changed = painted == null ? near : claims.differsIn(painted, cx0, cz0, cx1, cz1);
		if (!changed && !(moved && near)) {
			// Nothing claimed under this tile changed, and its pixels didn't, or nothing is claimed near it.
			if (painted == null) {
				tile.paintClaims(null, claims, tile.pixelsRevision);
			} else {
				tile.claimsPainted = claims;
				if (!near) {
					tile.claimsRevision = tile.pixelsRevision;
				}
			}
			return tile.claims();
		}
		// New claims at once; moved pixels after a pause, as a tile in view can change every few ticks.
		boolean due = changed || System.currentTimeMillis() - tile.pixelsChanged > CLAIM_REPAINT_MS;
		if (due && !this.claimsInFlight.contains(key) && this.claimsInFlight.size() < CLAIM_PAINTS) {
			this.claimsInFlight.add(key);
			int revision = tile.pixelsRevision;
			BAKERS.execute(() -> {
				int[] pixels = null;
				try {
					pixels = this.paintClaims(store, key, claims);
				} catch (Throwable t) {
					Diorama.LOGGER.error("Failed to paint the claims on tile {}", key, t);
				}
				this.paintedClaims.add(new PaintedClaims(key, tile, claims, revision, pixels));
			});
		}
		return tile.claims();
	}

	/**
	 * A tile's pixels changed, so may the ground its claims are painted on, and the edge rows the
	 * tiles above and below it paint their own edges against.
	 */
	private void pixelsMoved(TileKey key) {
		long now = System.currentTimeMillis();
		for (int dv = -1; dv <= 1; dv++) {
			MapTile tile = this.tiles.get(new TileKey(key.layer(), key.tileU(), key.tileV() + dv, key.lod()));
			if (tile != null) {
				// The repaint waits from the first change it hasn't painted, so a tile changing all
				// the time still gets one.
				if (tile.claimsRevision == tile.pixelsRevision) {
					tile.pixelsChanged = now;
				}
				tile.pixelsRevision++;
			}
		}
	}

	/**
	 * For tests: makes the overview tiles from level 1 up to {@code key} over its area look saved
	 * before chunks were kept, and reloads {@code key}. Not on the render thread.
	 */
	public void forgetChunks(TileKey key) {
		TileStore store = this.store;
		if (store == null) {
			return;
		}
		for (int lod = 1; lod <= key.lod(); lod++) {
			int span = 1 << (key.lod() - lod);
			for (int du = 0; du < span; du++) {
				for (int dv = 0; dv < span; dv++) {
					store.forgetChunks(new TileKey(key.layer(), key.tileU() * span + du, key.tileV() * span + dv, lod));
				}
			}
		}
		this.overviewChanged.add(key);
	}

	/** For tests: makes a level-0 tile look saved before tiles kept their spots, on disk too. Not on the render thread. */
	public void forgetSpots(TileKey key) {
		TileStore store = this.store;
		if (store != null) {
			store.forgetSpots(key);
		}
	}

	/** For tests: whether a level-0 tile has spots saved. Not on the render thread. */
	public boolean hasSavedSpots(TileKey key) {
		TileStore store = this.store;
		return store != null && store.spots(key) != null;
	}

	/** For tests: the claims a tile shows by the current claims, painted on this thread; null for none. */
	public int @Nullable [] claimPixels(TileKey key) {
		TileStore store = this.store;
		return store == null ? null : this.paintClaims(store, key, Claims.get());
	}

	/** A tile's claims, with its edge rows told apart from the tiles above and below it. Not on the render thread. */
	private int @Nullable [] paintClaims(TileStore store, TileKey key, Claims.Snapshot claims) {
		byte[] chunks = store.chunks(key);
		if (chunks == null) {
			return null;
		}
		TileKey north = new TileKey(key.layer(), key.tileU(), key.tileV() - 1, key.lod());
		TileKey south = new TileKey(key.layer(), key.tileU(), key.tileV() + 1, key.lod());
		return ClaimPaint.paint(key, chunks, store.chunks(north), store.chunks(south), claims);
	}

	private void loadSaved(TileKey key, TileStore store) {
		this.inFlight.add(key);
		int generation = this.generation(key);
		BAKERS.execute(() -> {
			TileStore.Saved saved = null;
			try {
				saved = store.readSaved(key);
			} catch (Throwable t) {
				Diorama.LOGGER.error("Failed to load saved tile {}", key, t);
			}
			this.finished.add((saved == null ? TileImage.partial(key, List.of()) : TileBaker.image(key, saved.pixels()).withBlockLight(saved.blockLight())).withGeneration(generation));
		});
	}

	/**
	 * Bakes changed surface tiles no map is showing, so they get saved. Leaves one baker free, so
	 * tiles a map is waiting for still come first.
	 */
	private void bakeUnsaved(BlockLooks looks, long now) {
		if (this.unsaved.isEmpty() || this.inFlight.size() >= Math.max(1, BAKER_COUNT - 1)) {
			return;
		}
		Iterator<Map.Entry<TileKey, Long>> it = this.unsaved.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<TileKey, Long> e = it.next();
			TileKey key = e.getKey();
			if (now - e.getValue() < BACKGROUND_QUIET_MS || this.inFlight.contains(key)) {
				continue;
			}
			it.remove();
			this.dirty.remove(key);
			if (!this.tiles.containsKey(key)) {
				this.backgroundBakes.add(key);
			}
			this.bake(key, looks, null);
			return;
		}
	}

	private static final java.util.concurrent.atomic.AtomicLong BAKE_NANOS = new java.util.concurrent.atomic.AtomicLong();
	private static final AtomicInteger BAKE_COUNT = new AtomicInteger();
	/** The part of {@link #BAKE_NANOS} spent in {@link #withSpots}. */
	private static final java.util.concurrent.atomic.AtomicLong SPOT_NANOS = new java.util.concurrent.atomic.AtomicLong();

	private static final AtomicInteger PARTIAL_COUNT = new AtomicInteger();

	private static void logBakeTime(long nanos, long spotNanos, boolean full) {
		if (!full) {
			if (PARTIAL_COUNT.incrementAndGet() % 50 == 0) {
				Diorama.LOGGER.info("Re-baked {} partial tiles", PARTIAL_COUNT.get());
			}
			return;
		}
		long total = BAKE_NANOS.addAndGet(nanos);
		long spots = SPOT_NANOS.addAndGet(spotNanos);
		int count = BAKE_COUNT.incrementAndGet();
		if (count % 25 == 0) {
			Diorama.LOGGER.info("Baked {} full tiles, {} ms average, {} ms of it finding spots", count, total / count / 1_000_000, spots / count / 1_000_000);
		}
	}

	private void evict() {
		int resident = 0;
		for (MapTile tile : this.tiles.values()) {
			if (!tile.isEmpty()) {
				resident++;
			}
		}
		// Over the limit the least recently drawn go, down to KEPT_TILES. The look every 600 updates
		// is only for tiles nothing has drawn in a while: it used to trim to KEPT_TILES too, so a view
		// showing more than that lost tiles it was drawing, and baked them again, every half minute.
		boolean over = resident > MAX_GPU_TILES;
		if (!over && this.frame % 600 != 0) {
			return;
		}
		List<Map.Entry<TileKey, MapTile>> entries = new ArrayList<>(this.tiles.entrySet());
		entries.sort(Comparator.comparingLong(e -> e.getValue().lastDrawn));
		Iterator<Map.Entry<TileKey, MapTile>> it = entries.iterator();
		while (it.hasNext()) {
			Map.Entry<TileKey, MapTile> e = it.next();
			boolean stale = this.frame - e.getValue().lastDrawn > 3600;
			if ((resident <= KEPT_TILES || !over) && !stale) {
				break;
			}
			if (!e.getValue().isEmpty()) {
				resident--;
			}
			e.getValue().close();
			this.tiles.remove(e.getKey());
			this.coverageVersion++;
			if (this.dirty.remove(e.getKey()) != null && this.store != null && !e.getKey().isCave() && e.getKey().lod() == 0) {
				// Changed since it was last baked: bake it once more in the background so the change is saved.
				this.unsaved.put(e.getKey(), 0L);
			}
		}
	}

	/** Saves fog-of-war progress if it changed. Call now and then; tiles save themselves. */
	public void saveSeen() {
		if (this.folder != null && !this.savedOnly) {
			this.seen.save(this.folder.resolve("seen.bin"));
		}
	}

	public void close() {
		if (this.closed) {
			return;
		}
		if (this.store != null && !this.savedOnly) {
			this.saveLastBakes(this.store);
		}
		this.closed = true;
		if (this.store != null) {
			this.store.close();
		}
		if (this.roofStore != null) {
			this.roofStore.close();
		}
		if (this.memory != null) {
			this.memory.close();
		}
		this.saveSeen();
		for (MapTile tile : this.tiles.values()) {
			tile.close();
		}
		this.tiles.clear();
		this.coverageVersion++;
		this.chunks.clear();
	}

	/**
	 * Saves the bakes that are kept and finished since the last update, waiting a moment for the ones still
	 * running: they hold the latest look of their tiles.
	 */
	private void saveLastBakes(TileStore store) {
		long deadline = System.currentTimeMillis() + 3000;
		while (true) {
			// GPU bakes need frames this wait holds up: trace those tiles instead, including ones
			// whose mesh a baker thread finishes while it waits.
			dev.diorama.render.gpu.GpuTileBaker.cancelAll();
			TileImage image;
			while ((image = this.finished.poll()) != null) {
				this.inFlight.remove(image.key());
				if (image.key().lod() == 0 && image.generation() == this.generation(image.key())) {
					TileStore saveTo = image.key().isCave() ? this.roofStore(image.key()) : store;
					if (saveTo != null) {
						saveTo.baked(image);
					}
				}
			}
			boolean baking = this.inFlight.stream().anyMatch(key -> key.lod() == 0 && (!key.isCave() || this.onRoof(key)));
			if (!baking || System.currentTimeMillis() > deadline) {
				return;
			}
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	public int bakingCount() {
		return this.inFlight.size();
	}

	/** A sky view tile {@link SavedChunkImport} drew, and the answer to whether the map took it. */
	private record SavedTile(TileImage image, TileStore store, CompletableFuture<Boolean> taken) {
	}

	/**
	 * The folder {@link SavedChunkImport} keeps its progress in: the one the sky view's tiles are
	 * saved in, or null when chunks from the save mustn't be drawn there. With nothing saved there's
	 * nowhere to draw them, and under a roof with fog of war the sky view may only hold what the
	 * player saw, which the save can't say.
	 */
	public @Nullable Path importFolder() {
		if (this.closed || this.folder == null || this.store == null || this.roofless() && this.fogOfWar) {
			return null;
		}
		return this.folder.resolve(this.storeName);
	}

	/** Where a view looked in the last moments, as map space {u, v}, or null if none did. */
	double @Nullable [] importFocus() {
		return System.currentTimeMillis() - this.focusMs > 1000 ? null : new double[] {this.focusU, this.focusV};
	}

	/** The detailed sky view tiles a view showed in the last moments, most wanted first. */
	List<TileKey> viewTiles() {
		return System.currentTimeMillis() - this.focusMs > 1000 ? List.of() : this.viewTiles;
	}

	/**
	 * Starts or stops noting which chunks load live, for {@link #takeLiveChunks}. Render thread. A
	 * chunk's tiles can still be waiting to be saved when the player leaves; such a chunk is never
	 * handed over, so the next visit draws it from the save instead of leaving a hole.
	 */
	void watchLiveChunks(boolean watch) {
		this.watchingLiveChunks = watch;
		this.liveChunks.clear();
		if (watch) {
			this.liveChunks.addAll(this.chunks.keySet());
		}
	}

	/** Moves live chunks whose tiles are saved along to {@link #takeLiveChunks}. A trim only drops saved ones. */
	private void handOverLiveChunks() {
		Iterator<Long> it = this.liveChunks.iterator();
		while (it.hasNext()) {
			long key = it.next();
			ChunkSnapshot snapshot = this.chunks.get(key);
			if (snapshot == null || !this.waitingToSave(snapshot)) {
				this.liveChunksSaved.add(key);
				it.remove();
			}
		}
	}

	/** Hands over the chunks seen live with their tiles saved since the last call. */
	void takeLiveChunks(java.util.function.LongConsumer consumer) {
		Long key;
		while ((key = this.liveChunksSaved.poll()) != null) {
			consumer.accept(key);
		}
	}

	/** Whether a sky view tile is saved with no gaps in its picture. */
	boolean pictureWhole(TileKey key) {
		TileStore store = this.store;
		int[] pixels = store == null ? null : store.read(key);
		if (pixels == null) {
			return false;
		}
		for (int pixel : pixels) {
			if ((pixel >>> 24) != 0xFF) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Bakes a sky view tile from {@code save} on the calling thread, merged with what's saved like
	 * any bake, and waits for the render thread to take it. Returns whether it did; see
	 * {@link #tileFromSave}. Any thread, one bake per tile at a time.
	 */
	boolean bakeFromSave(TileKey key, ChunkSource save, BlockLooks looks, Facing facing) {
		TileStore store = this.store;
		// The facing after the store: it changes first, so a store still current when the tile is
		// taken was read with the facing it's for. A map turned since the tile was asked for
		// wants another tile: what asked has to hear it wasn't drawn.
		if (store == null || Projection.facing() != facing) {
			return false;
		}
		// As for a live bake to be saved: the pixels and the spots have to see the same chunks.
		ChunkSource source = new PinnedSource(new dev.diorama.bake.TurnedSource(save, facing));
		int generation = this.generation(key);
		this.savedTilesRaced.remove(key);
		this.savedTilesBaking.add(key);
		try {
			TileStore.Saved saved = store.readSaved(key);
			TileImage image = this.drawFromSave(key, source, looks, saved);
			image = image.withSpots(TileSpots.pick(image, source, looks, false, store.spots(key))).withGeneration(generation);
			CompletableFuture<Boolean> taken = new CompletableFuture<>();
			this.fromSave.add(new SavedTile(image, store, taken));
			Boolean accepted = this.awaitWhileOpen(taken);
			return accepted != null && accepted;
		} finally {
			this.savedTilesBaking.remove(key);
		}
	}

	/** How many tiles are in memory waiting for the disk, which a fill must not outrun. */
	int unwrittenTiles() {
		TileStore store = this.store;
		return store == null ? 0 : store.unwrittenTiles();
	}

	/** Whether tiles from the save go to the GPU right now, or to the tracer. */
	private boolean savedTilesOnGpu() {
		int view = this.roofless() ? dev.diorama.render.gpu.GpuBakeCheck.UNDER_ROOF : dev.diorama.render.gpu.GpuBakeCheck.SKY;
		return dev.diorama.render.gpu.GpuBakeCheck.trusted(view) && dev.diorama.render.gpu.GpuTileBaker.keepsUpInBackground();
	}

	/** A sky view tile from the save: on the GPU once this GPU has shown it draws them right, else traced. */
	private TileImage drawFromSave(TileKey key, ChunkSource source, BlockLooks looks, TileStore.@Nullable Saved saved) {
		int view = this.roofless() ? dev.diorama.render.gpu.GpuBakeCheck.UNDER_ROOF : dev.diorama.render.gpu.GpuBakeCheck.SKY;
		if (this.savedTilesOnGpu()) {
			try {
				CompletableFuture<dev.diorama.render.gpu.GpuTileBaker.Result> drawn = new CompletableFuture<>();
				Runnable takeBack = dev.diorama.render.gpu.GpuTileBaker.submitBackground(dev.diorama.bake.GpuMesher.build(key, source, looks, false, null), drawn::complete);
				dev.diorama.render.gpu.GpuTileBaker.Result result = this.awaitGpu(drawn, view);
				if (result == null) {
					takeBack.run();
				}
				if (result != null && result.pixels() != null) {
					dev.diorama.render.gpu.GpuBakeCheck.drawn(true);
					// The check may have failed this GPU while the tile was out.
					if (dev.diorama.render.gpu.GpuBakeCheck.trusted(view)) {
						return TileBaker.gpuImage(key, result.pixels(), pixelsOf(saved), result.info(), result.blockLight(), lightOf(saved));
					}
				}
				if (result != null && !result.declined()) {
					dev.diorama.render.gpu.GpuBakeCheck.drawn(false);
				}
			} catch (BlockLooks.StatesRenumbered | dev.diorama.data.ChunkSave.Late e) {
				throw e;
			} catch (RuntimeException e) {
				Diorama.LOGGER.warn("Failed to mesh tile {} from the save; tracing it", key, e);
				dev.diorama.render.gpu.GpuBakeCheck.drawn(false);
			}
		}
		return TileBaker.bake(key, source, looks, null, false, pixelsOf(saved), lightOf(saved), null, null);
	}

	private static int @Nullable [] pixelsOf(TileStore.@Nullable Saved saved) {
		return saved == null ? null : saved.pixels();
	}

	private static byte @Nullable [] lightOf(TileStore.@Nullable Saved saved) {
		return saved == null ? null : saved.blockLight();
	}

	private static int @Nullable [] spotsOf(TileStore.@Nullable Saved saved) {
		return saved == null ? null : saved.spots();
	}

	/**
	 * A GPU bake's answer, or null if it stops being worth waiting for: GPU baking was turned off,
	 * or the frames that draw it aren't coming, and a tile from the save has nothing to wait for but
	 * the tracer.
	 */
	private dev.diorama.render.gpu.GpuTileBaker.@Nullable Result awaitGpu(CompletableFuture<dev.diorama.render.gpu.GpuTileBaker.Result> drawn, int view) {
		long deadline = System.nanoTime() + GPU_WAIT_NANOS;
		while (!this.closed && dev.diorama.render.gpu.GpuBakeCheck.trusted(view) && System.nanoTime() < deadline) {
			try {
				return drawn.get(50, java.util.concurrent.TimeUnit.MILLISECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				// It comes with a coming frame.
			} catch (InterruptedException | java.util.concurrent.ExecutionException e) {
				return null;
			}
		}
		return null;
	}

	/** What the render thread answers, or null once the map closes, when it may never answer. */
	private <T> @Nullable T awaitWhileOpen(CompletableFuture<T> answer) {
		while (!this.closed) {
			try {
				return answer.get(50, java.util.concurrent.TimeUnit.MILLISECONDS);
			} catch (java.util.concurrent.TimeoutException e) {
				// It comes with a coming frame.
			} catch (InterruptedException | java.util.concurrent.ExecutionException e) {
				return null;
			}
		}
		return null;
	}
}
