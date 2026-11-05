package dev.diorama.structure;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.render.MapViewport;
import dev.diorama.seed.StructureLocator;
import dev.diorama.seed.StructureLocator.Found;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Where the structures are in the world being looked at, worked out from its seed in the
 * background a tile at a time and kept until the seed or dimension changes. The map asks for the
 * tiles it is showing every frame ({@link #update}); the one nearest the middle of the view is
 * done first, and its structures turn up on the map as soon as it is.
 *
 * <p>Nothing here draws terrain or looks at the world's own chunks: a structure is a place and a name.
 */
public final class StructureMap {
	/** A tile is this many chunks across: sixteen blocks to a chunk, 4096 blocks a tile. */
	private static final int TILE_CHUNKS = 256;
	private static final int TILE_BLOCKS = TILE_CHUNKS * 16;
	/** The most tiles asked for at once: a view zoomed far out would otherwise want hundreds. */
	private static final int MAX_TILES = 36;
	/**
	 * How far from the player structures are looked for and shown, in blocks of the dimension
	 * shown. Walking uncovers more: the tiles in reach are asked for again from where the player is.
	 */
	public static final int RANGE = 5000;
	/** The height a view's rows are taken at to work out which part of the world it covers. */
	private static final int VIEW_Y = 64;

	private static final StructureMap INSTANCE = new StructureMap();

	/** One seed in one dimension: what the worker is finding structures for. */
	private record Job(String key, ResourceKey<Level> dimension, long seed) {
	}

	private final Object lock = new Object();
	private @Nullable Job job;
	/** The tiles the map is showing, nearest the middle first. */
	private List<Long> wanted = List.of();
	private final Map<Long, List<Found>> done = new ConcurrentHashMap<>();
	private @Nullable String failure;
	private @Nullable Thread worker;
	/** Where the player is, in the shown dimension's blocks; what {@link #RANGE} is measured from. */
	private volatile double playerX;
	private volatile double playerZ;

	private StructureMap() {
	}

	public static StructureMap get() {
		return INSTANCE;
	}

	/** The dimension's key, or null for one whose structures can't be placed. */
	public static @Nullable ResourceKey<Level> dimensionKey(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> Level.OVERWORLD;
			case "minecraft:the_nether" -> Level.NETHER;
			case "minecraft:the_end" -> Level.END;
			default -> null;
		};
	}

	/**
	 * Tells the worker what the map is showing: the dimension, the seed (null if there isn't one),
	 * the view and where the player is. Only tiles within {@link #RANGE} of the player are searched.
	 * Cheap enough for every frame. A different seed or dimension throws away what was found.
	 */
	public void update(String dimension, @Nullable Long seed, MapViewport.View view, double playerX, double playerZ) {
		this.playerX = playerX;
		this.playerZ = playerZ;
		ResourceKey<Level> key = dimensionKey(dimension);
		synchronized (this.lock) {
			if (key == null || seed == null) {
				this.reset(null);
				return;
			}
			String id = dimension + "|" + seed;
			if (this.job == null || !this.job.key().equals(id)) {
				this.reset(new Job(id, key, seed));
			}
			this.wanted = this.tilesInReach(view);
			if (this.worker == null) {
				this.worker = new Thread(this::run, "Diorama structures");
				this.worker.setDaemon(true);
				this.worker.start();
			}
			this.lock.notifyAll();
		}
	}

	/** Forgets everything found, for a world left or a structure setting that makes the search start over. */
	public void clear() {
		synchronized (this.lock) {
			this.reset(null);
		}
	}

	private void reset(@Nullable Job next) {
		this.job = next;
		this.done.clear();
		this.failure = null;
		this.wanted = List.of();
	}

	/** Whether a search is running or has run for what the map is showing. */
	public boolean active() {
		synchronized (this.lock) {
			return this.job != null;
		}
	}

	/** Tiles the map is showing that haven't been searched yet. */
	public int pendingTiles() {
		synchronized (this.lock) {
			int pending = 0;
			for (long tile : this.wanted) {
				if (!this.done.containsKey(tile)) {
					pending++;
				}
			}
			return pending;
		}
	}

	/** Structures found so far, in every tile searched. */
	public int found() {
		int total = 0;
		for (List<Found> tile : this.done.values()) {
			total += tile.size();
		}
		return total;
	}

	/** Why the search stopped, or null if it hasn't. */
	public @Nullable String failure() {
		synchronized (this.lock) {
			return this.failure;
		}
	}

	/**
	 * Hands over every structure found in the tiles the view covers that is within {@link #RANGE}
	 * of the player: one found earlier, from where the player was, drops out once they've walked away.
	 */
	public void forEach(MapViewport.View view, Consumer<Found> consumer) {
		double px = this.playerX;
		double pz = this.playerZ;
		double limit = (double) RANGE * RANGE;
		for (long tile : this.tilesInReach(view)) {
			List<Found> found = this.done.get(tile);
			if (found != null) {
				for (Found structure : found) {
					double dx = structure.x() - px;
					double dz = structure.z() - pz;
					if (dx * dx + dz * dz <= limit) {
						consumer.accept(structure);
					}
				}
			}
		}
	}

	/** The tiles the view covers that any part of lies within {@link #RANGE} of the player. */
	private List<Long> tilesInReach(MapViewport.View view) {
		double px = this.playerX;
		double pz = this.playerZ;
		List<Long> reach = new ArrayList<>();
		for (long tile : tilesAround(view)) {
			double minX = (double) tileX(tile) * TILE_BLOCKS;
			double minZ = (double) tileZ(tile) * TILE_BLOCKS;
			double dx = Math.max(Math.max(minX - px, px - (minX + TILE_BLOCKS)), 0);
			double dz = Math.max(Math.max(minZ - pz, pz - (minZ + TILE_BLOCKS)), 0);
			if (dx * dx + dz * dz <= (double) RANGE * RANGE) {
				reach.add(tile);
			}
		}
		return reach;
	}

	private @Nullable Long firstPending() {
		for (long tile : this.wanted) {
			if (!this.done.containsKey(tile)) {
				return tile;
			}
		}
		return null;
	}

	private void run() {
		StructureLocator locator = null;
		String locatorKey = null;
		while (true) {
			Job current;
			long tile;
			synchronized (this.lock) {
				while (true) {
					Long next = this.job != null && this.failure == null ? this.firstPending() : null;
					if (next != null) {
						current = this.job;
						tile = next;
						break;
					}
					try {
						this.lock.wait();
					} catch (InterruptedException e) {
						return;
					}
				}
			}
			try {
				if (locator == null || !current.key().equals(locatorKey)) {
					locator = null;
					locator = StructureLocator.create(StructureLocator.data().join(), current.dimension(), current.seed());
					locatorKey = current.key();
				}
				int minX = tileX(tile) * TILE_CHUNKS;
				int minZ = tileZ(tile) * TILE_CHUNKS;
				List<Found> found = locator.findIn(minX, minZ, minX + TILE_CHUNKS - 1, minZ + TILE_CHUNKS - 1);
				synchronized (this.lock) {
					// The map may have moved on to another seed while this was working.
					if (this.job == current) {
						this.done.put(tile, found);
					}
				}
			} catch (Throwable e) {
				Diorama.LOGGER.error("Couldn't find structures for {}", current.key(), e);
				synchronized (this.lock) {
					if (this.job == current) {
						this.failure = String.valueOf(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
					}
				}
			}
		}
	}

	/** The tiles a view covers, with a tile to spare all round for the slant, nearest the middle first and capped. */
	private static List<Long> tilesAround(MapViewport.View view) {
		double minU = view.minU();
		double maxU = minU + view.width() / view.zoom();
		double minZ = Projection.zAt(view.minV(), VIEW_Y);
		double maxZ = Projection.zAt(view.minV() + view.height() / view.zoom(), VIEW_Y);
		int x0 = Math.floorDiv((int) Math.floor(minU), TILE_BLOCKS) - 1;
		int x1 = Math.floorDiv((int) Math.floor(maxU), TILE_BLOCKS) + 1;
		int z0 = Math.floorDiv((int) Math.floor(minZ), TILE_BLOCKS) - 1;
		int z1 = Math.floorDiv((int) Math.floor(maxZ), TILE_BLOCKS) + 1;
		double centerX = view.centerU() / TILE_BLOCKS - 0.5;
		double centerZ = Projection.zAt(view.centerV(), VIEW_Y) / TILE_BLOCKS - 0.5;
		List<Long> tiles = new ArrayList<>();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				tiles.add(tile(x, z));
			}
		}
		tiles.sort(Comparator.comparingDouble(t -> Math.hypot(tileX(t) - centerX, tileZ(t) - centerZ)));
		return tiles.size() > MAX_TILES ? new ArrayList<>(tiles.subList(0, MAX_TILES)) : tiles;
	}

	private static long tile(int x, int z) {
		return (long) x << 32 | z & 0xFFFFFFFFL;
	}

	private static int tileX(long tile) {
		return (int) (tile >> 32);
	}

	private static int tileZ(long tile) {
		return (int) tile;
	}
}
