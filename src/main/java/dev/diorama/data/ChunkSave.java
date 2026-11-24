package dev.diorama.data;

import java.util.Collection;
import java.util.Map;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Where the map fills in from, for land the client never loaded: the world's save in singleplayer
 * ({@link SavedChunks}), or on a server with Diorama the land it shares ({@code
 * dev.diorama.share.SharedChunks}). Read by one fill at a time, from its own threads.
 */
public interface ChunkSave {
	Level level();

	/** Whether this is still the save of the world being played. */
	boolean isOpen();

	/**
	 * Every region's chunks, by {@link SavedChunks#regionKey}: for each of its 32 × 32 chunks,
	 * indexed {@code (z << 5) | x}, when the save last wrote it in seconds, or 0 if it has none
	 * to draw. The arrays are for reading only: a save may hand out the ones it keeps.
	 */
	Map<Long, int[]> scan();

	/** Whether the chunk is finished and how high it reaches, or null if there's none. Cheap. */
	ChunkTags.@Nullable Summary summary(int chunkX, int chunkZ);

	/** The chunk's highest block, or {@code Integer.MIN_VALUE} if it has nothing finished to draw. */
	int top(int chunkX, int chunkZ);

	/** The chunk, or null if there's none or it can't be read. Blocks. */
	@Nullable SavedChunk read(int chunkX, int chunkZ);

	/**
	 * The save's clock, in seconds: what {@link #scan} measures chunks' save times in. A server's
	 * clock can differ from this computer's.
	 */
	default int now() {
		return (int) (System.currentTimeMillis() / 1000);
	}

	/** Looks for chunks saved since the last {@link #scan}, if that takes asking someone. */
	default void refresh() {
	}

	/** Whether {@link #prefetch} does anything: working out what to prefetch costs reads of its own. */
	default boolean prefetches() {
		return false;
	}

	/** Says which chunks will be read soon, by {@link ChunkSnapshot#key}, most wanted first. */
	default void prefetch(Collection<Long> chunks, boolean urgent) {
	}

	/** Forgets the region's chunk tops: it changed. */
	void forgetTops(long region);

	/** Lets go of every chunk kept. */
	void clear();

	/** Stops reading; reads in progress return nothing. */
	void close();

	/** Whether the game's server is falling behind, so it needs the disk and the processor more than the map does. */
	boolean serverBehind();

	/** A read that couldn't be answered in time; the tile it was for is drawn later. */
	final class Late extends RuntimeException {
		public Late(String message) {
			super(message, null, false, false);
		}
	}
}
