package dev.diorama.data;

import dev.diorama.bake.ChunkSource;
import dev.diorama.waypoint.Portals;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * What a bake from the world's save reads: the map's own snapshot where it has one, since that's
 * the live world and the save can be behind it, else the chunk as saved. Any thread: reads take
 * turns, which they would on the server's one storage thread anyway, and bakes share what's read.
 */
public final class SavedChunkSource implements ChunkSource {
	/** Snapshots kept between tiles: neighbouring tiles read many of the same chunks. */
	private static final int CACHED_SNAPSHOTS = Runtime.getRuntime().maxMemory() > 3L << 30 ? 1536 : 512;

	private final ChunkSource live;
	private final SavedChunks save;
	private final Map<Long, @Nullable ChunkSnapshot> snapshots = new LinkedHashMap<>(CACHED_SNAPSHOTS, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, @Nullable ChunkSnapshot> eldest) {
			return this.size() > CACHED_SNAPSHOTS;
		}
	};

	/** Told the portals in each chunk read that has any, on the reading thread. */
	private final Consumer<List<Portals.Spot>> portals;

	public SavedChunkSource(ChunkSource live, SavedChunks save, Consumer<List<Portals.Spot>> portals) {
		this.live = live;
		this.save = save;
		this.portals = portals;
	}

	@Override
	public @Nullable ChunkSnapshot chunk(int chunkX, int chunkZ) {
		ChunkSnapshot live = this.live.chunk(chunkX, chunkZ);
		return live != null ? live : this.saved(chunkX, chunkZ);
	}

	private synchronized @Nullable ChunkSnapshot saved(int chunkX, int chunkZ) {
		long key = ChunkSnapshot.key(chunkX, chunkZ);
		if (this.snapshots.containsKey(key)) {
			return this.snapshots.get(key);
		}
		SavedChunk chunk = this.save.read(chunkX, chunkZ);
		ChunkSnapshot snapshot = null;
		if (chunk != null && chunk.finished()) {
			SavedChunk[] around = new SavedChunk[9];
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					around[(dx + 1) * 3 + dz + 1] = dx == 0 && dz == 0 ? chunk : this.save.read(chunkX + dx, chunkZ + dz);
				}
			}
			snapshot = ChunkSnapshot.fromSave(chunk, around, this.save.level());
			List<Portals.Spot> spots = Portals.find(snapshot);
			if (!spots.isEmpty()) {
				this.portals.accept(spots);
			}
		}
		this.snapshots.put(key, snapshot);
		return snapshot;
	}

	@Override
	public int topBelow(int chunkX, int chunkZ, int ceiling) {
		ChunkSnapshot live = this.live.chunk(chunkX, chunkZ);
		if (live != null) {
			return live.topBelow(ceiling);
		}
		int top;
		synchronized (this) {
			top = this.save.top(chunkX, chunkZ);
		}
		if (top == Integer.MIN_VALUE || top < ceiling) {
			return top == Integer.MIN_VALUE ? top : top + 1;
		}
		// Taller than the ceiling: which of its blocks stand below it takes the chunk itself.
		ChunkSnapshot chunk = this.chunk(chunkX, chunkZ);
		return chunk == null ? Integer.MIN_VALUE : chunk.topBelow(ceiling);
	}

	/** Forgets how tall a region's chunks are: its file changed. */
	public synchronized void forgetTops(long region) {
		this.save.forgetTops(region);
	}

	/** Lets go of everything read: the save may have moved on, and between jobs it's only memory. */
	public synchronized void clear() {
		this.snapshots.clear();
		this.save.clear();
	}

	@Override
	public int minY() {
		return this.live.minY();
	}

	@Override
	public int maxY() {
		return this.live.maxY();
	}

	/**
	 * The world's ceiling: how high this bake's chunks reach isn't known until it reads them, and
	 * each bake works that out for its own tile.
	 */
	@Override
	public int topY() {
		return this.live.maxY();
	}

	@Override
	public int roof() {
		return this.live.roof();
	}

	@Override
	public boolean isSeen(int x, int y, int z) {
		return this.live.isSeen(x, y, z);
	}
}
