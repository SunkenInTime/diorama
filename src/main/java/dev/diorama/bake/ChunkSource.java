package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import org.jspecify.annotations.Nullable;

/** Where bake threads read chunk snapshots from. Must be safe to call from any thread. */
public interface ChunkSource {
	@Nullable ChunkSnapshot chunk(int chunkX, int chunkZ);

	/**
	 * See {@link ChunkSnapshot#topBelow}, or {@code Integer.MIN_VALUE} without a chunk. Bakes ask this
	 * of many more chunks than they end up reading, so a source that has to load chunks answers it
	 * without loading them.
	 */
	default int topBelow(int chunkX, int chunkZ, int ceiling) {
		ChunkSnapshot chunk = this.chunk(chunkX, chunkZ);
		return chunk == null ? Integer.MIN_VALUE : chunk.topBelow(ceiling);
	}

	/** Lowest block y in this dimension. */
	int minY();

	/** One above the highest block y in this dimension. */
	int maxY();

	/** One above the highest non-air block seen so far. */
	int topY();

	/**
	 * In a dimension with a roof (the Nether), the y just above the top of the space under it;
	 * {@link #NO_ROOF} otherwise. The sky view of such a dimension looks down from under the roof.
	 */
	default int roof() {
		return NO_ROOF;
	}

	int NO_ROOF = Integer.MIN_VALUE;

	boolean isSeen(int x, int y, int z);
}
