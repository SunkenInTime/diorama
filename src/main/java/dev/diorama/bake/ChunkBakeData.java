package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.jspecify.annotations.Nullable;

/**
 * What bakes work out from one chunk's blocks under one set of looks, kept with the snapshot (see
 * {@link ChunkSnapshot#blocksToken}) so the next bake of any tile over it starts from it instead of
 * working it out again. A tile is meshed about ten times while the land around it loads, and its
 * shadow casters reach over dozens of chunks the neighbouring tiles mesh too.
 *
 * <p>Everything here is a function of the chunk's blocks and the looks alone, or, for a caster
 * column on the chunk's west or south edge, of those and the blocks of the neighbour it reads,
 * which the column records by token. Entries are made whole and then published, so bakes on
 * several threads can share them; two bakes making the same entry make the same thing.
 *
 * <p>Only the {@link #KEPT} made last hold anything: the map keeps thousands of snapshots, and
 * masks for all of them would sit on the heap for good. An older one is emptied, and a bake that
 * comes to it works its entries out again, as for a chunk it never had.
 */
final class ChunkBakeData {
	/** Stands for a neighbour the bake had no chunk for. */
	static final Object MISSING = new Object();
	/**
	 * How many hold their entries at once: about the chunks a 16-chunk render distance loads, and at
	 * most about fifty megabytes of masks where every section is mixed.
	 */
	static final int KEPT = 1024;
	/** Those made, oldest first. */
	private static final java.util.concurrent.ConcurrentLinkedQueue<ChunkBakeData> MADE = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private static final java.util.concurrent.atomic.AtomicInteger COUNT = new java.util.concurrent.atomic.AtomicInteger();

	final Looks looks;
	final byte[] kinds;
	final int @Nullable [] shapes;
	/** Per section: the solid mask and its smaller-than-a-cube mask, as {@link VoxelReader} builds them. */
	final AtomicReferenceArray<long @Nullable []> solid;
	final AtomicReferenceArray<long @Nullable []> partial;
	/**
	 * Per column {@code (z << 4) | x}: one above its highest block that isn't {@link Looks#AIR} to these
	 * looks, the world's bottom where there's none; null until worked out. Every cell from there up is air.
	 */
	volatile int @Nullable [] openFrom;
	/** The chunk's shadow caster faces, see {@link CasterChunk}; null until a bake needs them. */
	volatile @Nullable CasterChunk casters;

	private ChunkBakeData(Looks looks, byte[] kinds, int @Nullable [] shapes, int sections) {
		this.looks = looks;
		this.kinds = kinds;
		this.shapes = shapes;
		this.solid = new AtomicReferenceArray<>(sections);
		this.partial = new AtomicReferenceArray<>(sections);
	}

	/**
	 * The data for this snapshot's blocks under these looks, made if there's none yet or what there
	 * is was made under other looks. Null where nothing is shared: snapshots with copycats, whose
	 * ids the looks make up as they go.
	 */
	static @Nullable ChunkBakeData of(ChunkSnapshot snapshot, Looks looks, byte[] kinds, int @Nullable [] shapes) {
		if (!VoxelReader.SHARED) {
			return null;
		}
		for (int i = 0; i < snapshot.sectionCount(); i++) {
			if (snapshot.copycats(i) != null) {
				return null;
			}
		}
		java.util.concurrent.atomic.AtomicReference<@Nullable Object> holder = snapshot.bakeData();
		while (true) {
			Object now = holder.get();
			// One with and one without shapes: the tracer's readers tell smaller blocks apart, the map's picks don't.
			if (now instanceof ChunkBakeData[] pair) {
				ChunkBakeData found = pair[shapes == null ? 0 : 1];
				if (found != null && found.looks == looks && found.kinds == kinds && found.shapes == shapes) {
					return found;
				}
			}
			ChunkBakeData made = new ChunkBakeData(looks, kinds, shapes, snapshot.sectionCount());
			ChunkBakeData[] pair = now instanceof ChunkBakeData[] old ? old.clone() : new ChunkBakeData[2];
			if (pair[shapes == null ? 1 : 0] != null && pair[shapes == null ? 1 : 0].looks != looks) {
				pair[shapes == null ? 1 : 0] = null;
			}
			pair[shapes == null ? 0 : 1] = made;
			if (holder.compareAndSet(now, pair)) {
				MADE.add(made);
				if (COUNT.incrementAndGet() > KEPT) {
					ChunkBakeData oldest = MADE.poll();
					if (oldest != null) {
						COUNT.decrementAndGet();
						oldest.empty();
					}
				}
				return made;
			}
		}
	}

	/** Drops every entry. A bake reading one at the time may still see it, which holds as it was. */
	private void empty() {
		this.openFrom = null;
		this.casters = null;
		for (int i = 0; i < this.solid.length(); i++) {
			this.solid.set(i, null);
			this.partial.set(i, null);
		}
	}

	/**
	 * The chunk's shadow caster faces, every column from the block above its top down to
	 * {@link #downTo}, in one array: column {@code (z << 4) | x} from {@code offsets[column]} to
	 * {@code offsets[column + 1]}, per block with faces from the top down, {@code (y - minY) << 4 | faces}
	 * with faces {@link #TOP}, {@link #WEST}, {@link #SOUTH}, or {@link #SHAPE} followed by the
	 * block's id, whose shape's boxes each give all three. Columns on the chunk's west or south edge
	 * read the neighbour there; when they did, its token is kept, and the faces hold only while the
	 * neighbour does. One object a chunk: kept per column, they were half a million, which the
	 * collector had to trace.
	 */
	record CasterChunk(int downTo, int[] offsets, int[] faces, @Nullable Object west, @Nullable Object south) {
	}

	static final int TOP = 1;
	static final int WEST = 2;
	static final int SOUTH = 4;
	static final int SHAPE = 8;
}
