package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/**
 * Block and light lookups for one bake job. Decodes each section it touches once into a flat
 * array of block state ids. Not thread-safe; each job gets its own reader.
 *
 * <p>Lookups are the bake's inner loop, so they skip the work they can: a ray stays in one section
 * for several steps, so the last section's ids are kept at hand, and the chunks a job has seen sit
 * in a small table indexed by their coordinates, so moving between neighbouring chunks (shadow
 * and sky rays do, all the time) doesn't hash.
 */
public final class VoxelReader {
	public static final int UNKNOWN = -1;
	static final int AIR_ID = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
	/** The ids of a section with nothing in it. Shared and never written. */
	private static final int[] AIR_SECTION = new int[4096];
	/** The solid mask of a section with nothing solid in it. Shared and never written. */
	private static final long[] NOTHING_SOLID = new long[64];

	static {
		Arrays.fill(AIR_SECTION, AIR_ID);
	}

	/**
	 * The chunk table holds 2^TABLE_BITS chunks each way, by the low bits of their coordinates.
	 * A bake's reach is narrower than that, so its chunks rarely share a slot; when they do, the
	 * slot is filled again from {@link #cache}.
	 */
	private static final int TABLE_BITS = 5;
	private static final int TABLE_MASK = (1 << TABLE_BITS) - 1;

	private final ChunkSource source;
	/** The looks the ids are for, which also holds filled copycats' looks; null when only {@link #state} is used. */
	private final @Nullable BlockLooks looks;
	/** {@link BlockLooks#kind}, for {@link #solid}; null when only {@link #state} is used. */
	private final byte @Nullable [] kinds;
	/** {@link BlockLooks#shape}, so {@link #solid} can tell blocks smaller than a cube; may be null. */
	private final int @Nullable [] shapes;
	private final int minY;
	private final int maxY;
	/**
	 * Every chunk this job has asked the source for, null where it had none. A chunk is asked for
	 * once, so the whole job sees the same blocks even while chunks load and unload.
	 */
	private final Long2ObjectOpenHashMap<@Nullable Decoded> cache = new Long2ObjectOpenHashMap<>();
	private final int[] tableX = new int[1 << (2 * TABLE_BITS)];
	private final int[] tableZ = new int[1 << (2 * TABLE_BITS)];
	private final @Nullable Decoded[] tableChunk = new Decoded[1 << (2 * TABLE_BITS)];
	// The section the last block lookup landed in, in section coordinates, and its ids: null when
	// its chunk is missing.
	private int sectionX = Integer.MIN_VALUE;
	private int sectionY = Integer.MIN_VALUE;
	private int sectionZ = Integer.MIN_VALUE;
	private int @Nullable [] sectionIds;
	// The same for the last solid lookup, with its own section: shadow and sky rays interleave
	// with camera rays. The mask is null when the chunk is missing.
	private int solidX = Integer.MIN_VALUE;
	private int solidY = Integer.MIN_VALUE;
	private int solidZ = Integer.MIN_VALUE;
	private long @Nullable [] solidMask;
	private long @Nullable [] partialMask;

	VoxelReader(ChunkSource source) {
		this(source, null);
	}

	public VoxelReader(ChunkSource source, @Nullable BlockLooks looks) {
		this(source, looks, false);
	}

	/** With {@code shapes}, {@link #solid} tells blocks smaller than a cube apart. */
	public VoxelReader(ChunkSource source, @Nullable BlockLooks looks, boolean shapes) {
		this.source = source;
		this.looks = looks;
		this.kinds = looks == null ? null : looks.kind;
		this.shapes = looks == null || !shapes ? null : looks.shape;
		this.minY = source.minY();
		this.maxY = source.maxY();
		// No chunk has this x, so every slot starts empty.
		Arrays.fill(this.tableX, Integer.MIN_VALUE);
	}

	static final boolean DEBUG = dev.diorama.Diorama.DEBUG;
	final java.util.Set<Long> missing = DEBUG ? new java.util.HashSet<>() : null;

	/** Block state id at the position, {@link #AIR_ID} outside the world, or {@link #UNKNOWN}. */
	public int state(int x, int y, int z) {
		if (y < this.minY || y >= this.maxY) {
			return AIR_ID;
		}
		int sx = x >> 4;
		int sy = y >> 4;
		int sz = z >> 4;
		if (sx != this.sectionX || sy != this.sectionY || sz != this.sectionZ) {
			this.enterSection(sx, sy, sz);
		}
		int[] ids = this.sectionIds;
		return ids == null ? UNKNOWN : ids[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
	}

	private void enterSection(int sx, int sy, int sz) {
		Decoded chunk = this.chunk(sx, sz);
		if (chunk == null) {
			if (DEBUG) {
				this.missing.add(ChunkSnapshot.key(sx, sz));
			}
			this.sectionIds = null;
		} else {
			int[] ids = chunk.section(sy - chunk.snapshot.minSectionY);
			this.sectionIds = ids == null ? AIR_SECTION : ids;
		}
		this.sectionX = sx;
		this.sectionY = sy;
		this.sectionZ = sz;
	}

	/**
	 * 1 if the block is {@link BlockLooks#SOLID}, the kind that stops shadow and sky rays and
	 * casts ambient occlusion, 2 if it is solid but smaller than a cube (rays then need its
	 * shape), 0 if not, or {@link #UNKNOWN}. Reads a bit per block, built once per
	 * section, which keeps far more of the world in the CPU's caches than the ids do.
	 */
	public int solid(int x, int y, int z) {
		if (y < this.minY || y >= this.maxY) {
			return 0;
		}
		int sx = x >> 4;
		int sy = y >> 4;
		int sz = z >> 4;
		if (sx != this.solidX || sy != this.solidY || sz != this.solidZ) {
			this.enterSolidSection(sx, sy, sz);
		}
		long[] mask = this.solidMask;
		if (mask == null) {
			return UNKNOWN;
		}
		int i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
		if ((mask[i >> 6] >>> i & 1) == 0) {
			return 0;
		}
		return 1 + ((int) (this.partialMask[i >> 6] >>> i) & 1);
	}

	/**
	 * Whether an open cell is a pocket one block tall, with solid blocks right above and below it:
	 * a lone air bubble, a buried lava spring or a crawlspace. Under a roof, rays skipping rock
	 * skip these too, so they don't speckle solid rock with dots; the baker, its lifted rock and
	 * picking on the map must agree on them.
	 */
	boolean pocket(int x, int y, int z) {
		return this.solid(x, y + 1, z) > 0 && this.solid(x, y - 1, z) > 0;
	}

	/**
	 * Under a roof, the lowest y of the rock lifted off this column: the solid blocks from the cut
	 * at {@code startY} down to the first open space that isn't a {@link #pocket}. Light rays treat
	 * it as open. A pocket right under the cut would depend on the block above it, past the cut,
	 * where edits don't re-bake below, so the open cell just under the cut always ends it.
	 */
	int lid(int x, int z, int startY, int limitY) {
		int y = startY - 1;
		while (y >= limitY && (this.solid(x, y, z) > 0 || y + 1 < startY && this.pocket(x, y, z))) {
			y--;
		}
		return y + 1;
	}

	/** How much of a ceiling's underside a ray that has left the rock passes through. */
	static final int CEILING_UNDERSIDE = 2;

	/**
	 * Whether this block is in the bottom {@link #CEILING_UNDERSIDE} blocks of the rock lifted off
	 * its column ({@code lid}), with open space right below: the underside of a ceiling, or a block
	 * hanging from it. Walls and pillars have rock below them, so they stay; so does a column that
	 * is rock all the way down to {@code limitY}, where the lid ends without any open space.
	 */
	boolean ceilingUnderside(int x, int y, int z, int lid, int limitY) {
		return y >= lid && y < lid + CEILING_UNDERSIDE && lid > limitY && this.solid(x, lid - 1, z) == 0;
	}

	private void enterSolidSection(int sx, int sy, int sz) {
		Decoded chunk = this.chunk(sx, sz);
		if (chunk == null) {
			if (DEBUG) {
				this.missing.add(ChunkSnapshot.key(sx, sz));
			}
			this.solidMask = null;
		} else {
			int index = sy - chunk.snapshot.minSectionY;
			this.solidMask = chunk.solid(index, this.kinds, this.shapes);
			this.partialMask = chunk.partial(index);
		}
		this.solidX = sx;
		this.solidY = sy;
		this.solidZ = sz;
	}

	/**
	 * A section's {@link #solid} bits, indexed {@code (y << 8) | (z << 4) | x}: null when its chunk
	 * is missing, {@link #NOTHING_SOLID} when nothing in it is solid. Don't write to it.
	 */
	long @Nullable [] solidSection(int sx, int sy, int sz) {
		Decoded chunk = this.chunk(sx, sz);
		return chunk == null ? null : chunk.solid(sy - chunk.snapshot.minSectionY, this.kinds, this.shapes);
	}

	/**
	 * Which of a section's {@link #solidSection} bits are blocks smaller than a cube, indexed the
	 * same: null when its chunk is missing, {@link #NOTHING_SOLID} when there are none. Don't write to it.
	 */
	long @Nullable [] partialSection(int sx, int sy, int sz) {
		Decoded chunk = this.chunk(sx, sz);
		if (chunk == null) {
			return null;
		}
		int index = sy - chunk.snapshot.minSectionY;
		chunk.solid(index, this.kinds, this.shapes);
		return chunk.partial(index);
	}

	static boolean nothingSolid(long[] mask) {
		return mask == NOTHING_SOLID;
	}

	/**
	 * One above the highest {@link BlockLooks#SOLID} block in these chunks, or
	 * {@code Integer.MIN_VALUE} if they have none. Chunks missing from the source are left out:
	 * every lookup there is {@link #UNKNOWN}. If this job hasn't asked for one of the chunks yet,
	 * returns {@code Integer.MAX_VALUE} instead of asking, so the blocks a job sees don't depend on
	 * this call.
	 */
	int solidTop(int chunkX0, int chunkZ0, int chunkX1, int chunkZ1) {
		int top = Integer.MIN_VALUE;
		for (int cx = chunkX0; cx <= chunkX1; cx++) {
			for (int cz = chunkZ0; cz <= chunkZ1; cz++) {
				Decoded chunk;
				int slot = ((cx & TABLE_MASK) << TABLE_BITS) | (cz & TABLE_MASK);
				if (this.tableX[slot] == cx && this.tableZ[slot] == cz) {
					chunk = this.tableChunk[slot];
				} else {
					long key = ChunkSnapshot.key(cx, cz);
					if (!this.cache.containsKey(key)) {
						return Integer.MAX_VALUE;
					}
					chunk = this.cache.get(key);
				}
				if (chunk != null) {
					top = Math.max(top, chunk.solidTop(this.kinds, this.shapes));
				}
			}
		}
		return top;
	}

	int blockLight(int x, int y, int z) {
		if (y < this.minY || y >= this.maxY) {
			return 0;
		}
		Decoded chunk = this.chunk(x >> 4, z >> 4);
		if (chunk == null) {
			return 0;
		}
		return chunk.snapshot.blockLight((y >> 4) - chunk.snapshot.minSectionY, x & 15, y & 15, z & 15);
	}

	int skyLight(int x, int y, int z) {
		if (y < this.minY) {
			return 0;
		}
		if (y >= this.maxY) {
			return 15;
		}
		Decoded chunk = this.chunk(x >> 4, z >> 4);
		if (chunk == null) {
			return 15;
		}
		return chunk.snapshot.skyLight((y >> 4) - chunk.snapshot.minSectionY, x & 15, y & 15, z & 15);
	}

	/** The chunk snapshot holding this column, for biome tints. */
	@Nullable ChunkSnapshot snapshot(int x, int z) {
		Decoded chunk = this.chunk(x >> 4, z >> 4);
		return chunk == null ? null : chunk.snapshot;
	}

	private @Nullable Decoded chunk(int chunkX, int chunkZ) {
		int slot = ((chunkX & TABLE_MASK) << TABLE_BITS) | (chunkZ & TABLE_MASK);
		if (this.tableX[slot] == chunkX && this.tableZ[slot] == chunkZ) {
			return this.tableChunk[slot];
		}
		long key = ChunkSnapshot.key(chunkX, chunkZ);
		Decoded decoded = this.cache.get(key);
		if (decoded == null && !this.cache.containsKey(key)) {
			ChunkSnapshot snapshot = this.source.chunk(chunkX, chunkZ);
			decoded = snapshot == null ? null : new Decoded(snapshot, this.looks);
			this.cache.put(key, decoded);
		}
		this.tableX[slot] = chunkX;
		this.tableZ[slot] = chunkZ;
		this.tableChunk[slot] = decoded;
		return decoded;
	}

	private static final class Decoded {
		final ChunkSnapshot snapshot;
		final @Nullable BlockLooks looks;
		final int[][] sections;
		final boolean[] decoded;
		/** Per section, one bit per block, set where it is {@link BlockLooks#SOLID}; built when first read. */
		final long[][] solid;
		/** The same, set where a solid block is smaller than a cube. */
		final long[][] partial;
		/** See {@link VoxelReader#solidTop}; {@code Integer.MAX_VALUE} until worked out. */
		int solidTop = Integer.MAX_VALUE;

		Decoded(ChunkSnapshot snapshot, @Nullable BlockLooks looks) {
			this.snapshot = snapshot;
			this.looks = looks;
			this.sections = new int[snapshot.sectionCount()][];
			this.decoded = new boolean[snapshot.sectionCount()];
			this.solid = new long[snapshot.sectionCount()][];
			this.partial = new long[snapshot.sectionCount()][];
		}

		/** The section's solid mask, indexed as its ids. */
		long[] solid(int index, byte[] kinds, int @Nullable [] shapes) {
			if (index < 0 || index >= this.solid.length) {
				return NOTHING_SOLID;
			}
			long[] mask = this.solid[index];
			if (mask == null) {
				int[] ids = this.section(index);
				long[] partial = NOTHING_SOLID;
				if (ids == null) {
					mask = NOTHING_SOLID;
				} else {
					mask = new long[64];
					for (int i = 0; i < 4096; i++) {
						if (kinds[ids[i]] == BlockLooks.SOLID) {
							mask[i >> 6] |= 1L << i;
							if (shapes != null && shapes[ids[i]] != 0) {
								if (partial == NOTHING_SOLID) {
									partial = new long[64];
								}
								partial[i >> 6] |= 1L << i;
							}
						}
					}
				}
				this.partial[index] = partial;
				this.solid[index] = mask;
			}
			return mask;
		}

		/** The section's mask of solid blocks smaller than a cube; read after {@link #solid}. */
		long[] partial(int index) {
			return index < 0 || index >= this.partial.length || this.partial[index] == null ? NOTHING_SOLID : this.partial[index];
		}

		int solidTop(byte[] kinds, int @Nullable [] shapes) {
			if (this.solidTop == Integer.MAX_VALUE) {
				int top = Integer.MIN_VALUE;
				for (int index = this.sections.length - 1; index >= 0 && top == Integer.MIN_VALUE; index--) {
					long[] mask = this.solid(index, kinds, shapes);
					for (int word = 63; word >= 0; word--) {
						if (mask[word] != 0) {
							// Bits run x, then z, then y: 64 bits are a quarter of one y layer.
							int highest = (word << 6) + 63 - Long.numberOfLeadingZeros(mask[word]);
							top = ((this.snapshot.minSectionY + index) << 4) + (highest >> 8) + 1;
							break;
						}
					}
				}
				this.solidTop = top;
			}
			return this.solidTop;
		}

		int @Nullable [] section(int index) {
			if (index < 0 || index >= this.sections.length) {
				return null;
			}
			if (!this.decoded[index]) {
				this.decoded[index] = true;
				PalettedContainer<BlockState> states = this.snapshot.section(index);
				BlockState uniform = this.snapshot.uniformSection(index);
				if (uniform != null) {
					int[] ids = new int[4096];
					java.util.Arrays.fill(ids, BlockLooks.bakeId(uniform));
					this.sections[index] = ids;
				} else if (states != null) {
					int[] ids = new int[4096];
					BlockState lastState = null;
					int lastId = 0;
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								BlockState state = states.get(x, y, z);
								if (state != lastState) {
									lastState = state;
									lastId = BlockLooks.bakeId(state);
								}
								ids[(y << 8) | (z << 4) | x] = lastId;
							}
						}
					}
					this.sections[index] = ids;
				}
				ChunkSnapshot.SectionCopycats copycats = this.snapshot.copycats(index);
				int[] ids = this.sections[index];
				if (copycats != null && ids != null && this.looks != null) {
					// Filled copycats take their material's look, keeping their own outline.
					for (int i = 0; i < copycats.at().length; i++) {
						int at = copycats.at()[i];
						ids[at] = this.looks.copycat(ids[at], Block.BLOCK_STATE_REGISTRY.getId(copycats.material()[i]));
					}
				}
			}
			return this.sections[index];
		}
	}
}
