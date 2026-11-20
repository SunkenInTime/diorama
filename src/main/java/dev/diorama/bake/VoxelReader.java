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
 * Block and light lookups for one bake job. Reads each section it touches through the chunk
 * snapshot's own packed copy, with the id of each palette entry looked up once: the registry's
 * hash lookup, per block, was most of what meshing a tile cost. Not thread-safe; each job gets
 * its own reader.
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
	private static final int[] AIR_IDS = new int[4096];
	/** A section with nothing in it. */
	private static final Section AIR_SECTION = new Section(AIR_ID, null, null, null, null);
	/**
	 * A section whose palette can't be numbered whole (the registry itself, in a section of over
	 * 256 kinds of block), read block by block, and only as far as each lookup needs.
	 */
	private static final Section BY_BLOCK = new Section(-1, null, null, null, null);
	/** The solid mask of a section with nothing solid in it. Shared and never written. */
	private static final long[] NOTHING_SOLID = new long[64];
	/**
	 * The solid mask of a section of a single block that isn't solid. Shared and never written; not
	 * {@link #NOTHING_SOLID}, which {@link #nothingSolid} tells apart, so the GPU's table holds these
	 * sections as it always has.
	 */
	private static final long[] NONE_OF_ONE = new long[64];
	/** Every bit set: a section of a single solid block. Shared and never written. */
	private static final long[] ALL_SOLID = new long[64];

	static {
		Arrays.fill(ALL_SOLID, -1L);
		Arrays.fill(AIR_IDS, AIR_ID);
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
	private final @Nullable Looks looks;
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
	/** A section's palette indices, unpacked while reading it. */
	private final int[] unpacked = new int[4096];

	VoxelReader(ChunkSource source) {
		this(source, null);
	}

	public VoxelReader(ChunkSource source, @Nullable Looks looks) {
		this(source, looks, false);
	}

	/** With {@code shapes}, {@link #solid} tells blocks smaller than a cube apart. */
	public VoxelReader(ChunkSource source, @Nullable Looks looks, boolean shapes) {
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
			this.sectionIds = chunk.ids(sy - chunk.snapshot.minSectionY, this.unpacked);
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
	 * {@link #solid} above 0 for the nine cells around {@code (x, y, z)} in its layer of y, a bit
	 * each: the row at z - 1 in bits 0 to 2, z in 3 to 5, z + 1 in 6 to 8, each from x - 1 up. Read
	 * from the section's bits in one go; -1 where the nine aren't all in one section, which
	 * {@link #solid} answers one at a time.
	 */
	int ringXZ(int x, int y, int z) {
		int lx = x & 15;
		int lz = z & 15;
		if (lx == 0 || lx == 15 || lz == 0 || lz == 15 || y < this.minY || y >= this.maxY) {
			return -1;
		}
		long[] mask = this.solidMaskAt(x >> 4, y >> 4, z >> 4);
		if (mask == null) {
			return 0;
		}
		int i = ((y & 15) << 8) | (lz << 4) | lx;
		return three(mask, i - 17) | three(mask, i - 1) << 3 | three(mask, i + 15) << 6;
	}

	/**
	 * The same for the nine cells around {@code (x, y, z)} in its layer of z: the row at y + 1 in
	 * bits 0 to 2, y in 3 to 5, y - 1 in 6 to 8.
	 */
	int ringXY(int x, int y, int z) {
		int lx = x & 15;
		int ly = y & 15;
		if (lx == 0 || lx == 15 || ly == 0 || ly == 15 || y < this.minY || y >= this.maxY) {
			return -1;
		}
		long[] mask = this.solidMaskAt(x >> 4, y >> 4, z >> 4);
		if (mask == null) {
			return 0;
		}
		int i = (ly << 8) | ((z & 15) << 4) | lx;
		return three(mask, i + 255) | three(mask, i - 1) << 3 | three(mask, i - 257) << 6;
	}

	/** Bits {@code i} to {@code i + 2} of a mask, which lie in one word. */
	private static int three(long[] mask, int i) {
		return (int) (mask[i >> 6] >>> i) & 7;
	}

	/** The solid mask of a section, as {@link #solid} reads it: null where the chunk is missing. */
	private long @Nullable [] solidMaskAt(int sx, int sy, int sz) {
		if (sx != this.solidX || sy != this.solidY || sz != this.solidZ) {
			this.enterSolidSection(sx, sy, sz);
		}
		return this.solidMask;
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
			this.solidMask = chunk.solid(index, this.kinds, this.shapes, this.unpacked);
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
		return chunk == null ? null : chunk.solid(sy - chunk.snapshot.minSectionY, this.kinds, this.shapes, this.unpacked);
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
		chunk.solid(index, this.kinds, this.shapes, this.unpacked);
		return chunk.partial(index);
	}

	/** The bits of a solid mask where x is 0. */
	private static final long WEST_EDGE = 0x0001_0001_0001_0001L;

	/**
	 * For {@link GpuMesher}'s shadow casters: which of a section's blocks may cast, one bit per
	 * block indexed as {@link #solidSection}. A block without its bit isn't solid, or is a whole
	 * cube with whole cubes above, west and south of it, which gives no caster face. Neighbours in
	 * chunks this job hasn't read yet count as open, so asking reads no chunk the casters wouldn't.
	 * Null where the chunk is missing or the section is outside the world. Don't write to it.
	 */
	public long @Nullable [] casterSection(int sx, int sy, int sz) {
		Decoded chunk = this.chunk(sx, sz);
		if (chunk == null) {
			return null;
		}
		int index = sy - chunk.snapshot.minSectionY;
		if (index < 0 || index >= chunk.solid.length) {
			return null;
		}
		long[] out = chunk.casters[index];
		if (out == null) {
			long[] solid = chunk.solid(index, this.kinds, this.shapes, this.unpacked);
			long[] partial = chunk.partial(index);
			// The sections above, west and south: solid bits, then smaller blocks; null where none is read.
			int upIndex = index + 1 < chunk.solid.length ? index + 1 : -1;
			long[] upSolid = upIndex < 0 ? null : chunk.solid(upIndex, this.kinds, this.shapes, this.unpacked);
			long[] upPartial = upIndex < 0 ? null : chunk.partial(upIndex);
			Decoded west = this.peek(sx - 1, sz);
			int westIndex = west == null ? -1 : sy - west.snapshot.minSectionY;
			boolean westRead = westIndex >= 0 && westIndex < west.solid.length;
			long[] westSolid = westRead ? west.solid(westIndex, this.kinds, this.shapes, this.unpacked) : null;
			long[] westPartial = westRead ? west.partial(westIndex) : null;
			Decoded south = this.peek(sx, sz + 1);
			int southIndex = south == null ? -1 : sy - south.snapshot.minSectionY;
			boolean southRead = southIndex >= 0 && southIndex < south.solid.length;
			long[] southSolid = southRead ? south.solid(southIndex, this.kinds, this.shapes, this.unpacked) : null;
			long[] southPartial = southRead ? south.partial(southIndex) : null;
			out = new long[64];
			for (int w = 0; w < 64; w++) {
				long cubes = solid[w] & ~partial[w];
				// Four words to a layer of y, four rows of z to a word, x along each row.
				long above = w < 60 ? solid[w + 4] & ~partial[w + 4] : upSolid == null ? 0 : upSolid[w - 60] & ~upPartial[w - 60];
				long westCubes = westSolid == null ? 0 : westSolid[w] & ~westPartial[w];
				long westOf = (cubes << 1) & ~WEST_EDGE | (westCubes >>> 15 & WEST_EDGE);
				long nextRow = (w & 3) < 3 ? solid[w + 1] & ~partial[w + 1] : southSolid == null ? 0 : southSolid[w - 3] & ~southPartial[w - 3];
				long southOf = cubes >>> 16 | nextRow << 48;
				out[w] = solid[w] & ~(cubes & above & westOf & southOf);
			}
			chunk.casters[index] = out;
		}
		return out;
	}

	/**
	 * Whether bakes share what they work out from a chunk's blocks ({@link ChunkBakeData}). Only
	 * for checking that they come out the same either way.
	 */
	public static boolean SHARED = true;

	/**
	 * The shadow caster faces of the chunk at these chunk coordinates as {@link GpuMesher}'s casters
	 * find them, every column from the block above its top down to {@code bottom} at least, from what
	 * an earlier bake found if it still holds, else scanned now. Null where the chunk is missing or
	 * nothing is shared.
	 */
	ChunkBakeData.@Nullable CasterChunk casterChunk(int cx, int cz, int bottom) {
		Decoded chunk = this.chunk(cx, cz);
		if (chunk == null || chunk.shared == null || chunk.shared.shapes == null) {
			return null;
		}
		ChunkBakeData.CasterChunk known = chunk.shared.casters;
		if (known != null && known.downTo() <= bottom && (known.west() == null || known.west() == this.blocksToken(cx - 1, cz))
			&& (known.south() == null || known.south() == this.blocksToken(cx, cz + 1))) {
			return known;
		}
		// Tiles nearby reach down about as far: a little further now saves scanning it all again.
		int downTo = Math.max(this.minY, Math.min(bottom, known == null ? bottom : known.downTo()) - 16);
		int[] offsets = new int[257];
		int[] faces = new int[1024];
		int length = 0;
		boolean west = false;
		boolean south = false;
		for (int column = 0; column < 256; column++) {
			offsets[column] = length;
			int x = (cx << 4) | (column & 15);
			int z = (cz << 4) | (column >> 4);
			long[] mayCast = null;
			int castY = Integer.MIN_VALUE;
			for (int y = chunk.snapshot.surface(column & 15, column >> 4) + 1; y >= downTo; y--) {
				// As GpuMesher.casters: rock under rock casts nothing, which these bits skip.
				if (y >> 4 != castY) {
					castY = y >> 4;
					mayCast = this.casterSection(cx, castY, cz);
				}
				int i = ((y & 15) << 8) | column;
				if (mayCast != null && (mayCast[i >> 6] >>> i & 1) == 0) {
					continue;
				}
				int solid = this.solid(x, y, z);
				int bits;
				int id = 0;
				if (solid == 2) {
					id = this.state(x, y, z);
					if (id < 0 || this.shapes[id] == 0) {
						continue;
					}
					bits = ChunkBakeData.SHAPE;
				} else if (solid == 1) {
					bits = 0;
					if (this.solid(x, y + 1, z) != 1) {
						bits |= ChunkBakeData.TOP;
					}
					west |= (x & 15) == 0;
					if (this.solid(x - 1, y, z) != 1) {
						bits |= ChunkBakeData.WEST;
					}
					south |= (z & 15) == 15;
					if (this.solid(x, y, z + 1) != 1) {
						bits |= ChunkBakeData.SOUTH;
					}
					if (bits == 0) {
						continue;
					}
				} else {
					continue;
				}
				if (length + 2 > faces.length) {
					faces = java.util.Arrays.copyOf(faces, faces.length * 2);
				}
				faces[length++] = (y - this.minY) << 4 | bits;
				if (bits == ChunkBakeData.SHAPE) {
					faces[length++] = id;
				}
			}
		}
		offsets[256] = length;
		ChunkBakeData.CasterChunk made = new ChunkBakeData.CasterChunk(downTo, offsets, java.util.Arrays.copyOf(faces, length),
			west ? this.blocksToken(cx - 1, cz) : null, south ? this.blocksToken(cx, cz + 1) : null);
		chunk.shared.casters = made;
		return made;
	}

	/**
	 * See {@link ChunkBakeData#openFrom}: for the chunk at these chunk coordinates as this job reads
	 * it, per column {@code (z << 4) | x}, the lowest y from which every cell up is {@link Looks#AIR}
	 * as {@link #state} reads it. Null where the chunk is missing or nothing is shared.
	 */
	int @Nullable [] openFrom(int chunkX, int chunkZ) {
		Decoded chunk = this.chunk(chunkX, chunkZ);
		if (chunk == null || chunk.shared == null) {
			return null;
		}
		int[] open = chunk.shared.openFrom;
		if (open == null) {
			open = new int[256];
			java.util.Arrays.fill(open, Integer.MIN_VALUE);
			int left = 256;
			byte[] kinds = this.kinds;
			for (int index = chunk.sections.length - 1; index >= 0 && left > 0; index--) {
				Section section = chunk.section(index);
				if (section == AIR_SECTION) {
					continue;
				}
				int y0 = (chunk.snapshot.minSectionY + index) << 4;
				int[] ids = chunk.ids(index, this.unpacked);
				for (int column = 0; column < 256; column++) {
					if (open[column] != Integer.MIN_VALUE) {
						continue;
					}
					for (int y = 15; y >= 0; y--) {
						if (kinds[ids[(y << 8) | column]] != Looks.AIR) {
							open[column] = y0 + y + 1;
							left--;
							break;
						}
					}
				}
			}
			for (int column = 0; column < 256; column++) {
				if (open[column] == Integer.MIN_VALUE) {
					open[column] = this.minY;
				}
			}
			chunk.shared.openFrom = open;
		}
		return open;
	}

	/** The {@link ChunkSnapshot#blocksToken} of a chunk as this job reads it, {@link ChunkBakeData#MISSING} where it has none. */
	private Object blocksToken(int chunkX, int chunkZ) {
		Decoded chunk = this.chunk(chunkX, chunkZ);
		return chunk == null ? ChunkBakeData.MISSING : chunk.snapshot.blocksToken();
	}

	int minY() {
		return this.minY;
	}

	/** A chunk this job has already read, without asking the source for one it hasn't: null then, or where it had none. */
	private @Nullable Decoded peek(int chunkX, int chunkZ) {
		int slot = ((chunkX & TABLE_MASK) << TABLE_BITS) | (chunkZ & TABLE_MASK);
		if (this.tableX[slot] == chunkX && this.tableZ[slot] == chunkZ) {
			return this.tableChunk[slot];
		}
		return this.cache.get(ChunkSnapshot.key(chunkX, chunkZ));
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
					top = Math.max(top, chunk.solidTop(this.kinds, this.shapes, this.unpacked));
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
			decoded = snapshot == null ? null : new Decoded(snapshot, this.looks,
				this.looks == null || this.kinds == null ? null : ChunkBakeData.of(snapshot, this.looks, this.kinds, this.shapes));
			this.cache.put(key, decoded);
		}
		this.tableX[slot] = chunkX;
		this.tableZ[slot] = chunkZ;
		this.tableChunk[slot] = decoded;
		return decoded;
	}

	/**
	 * What a section holds: one block, every block's id already (copycats, and palettes too big to
	 * number whole), or the snapshot's packed palette indices with an id per palette entry.
	 */
	private static final class Section {
		/** The id filling the section, or -1. */
		final int single;
		/** Every block's id, where it was decoded block by block; otherwise null. */
		final int @Nullable [] ids;
		/** Otherwise the palette index of each block, and each palette entry's id. */
		final net.minecraft.util.@Nullable BitStorage storage;
		final int @Nullable [] palette;
		/** For a turned view, where its columns are in the storage: see {@link ChunkSnapshot#columns}. */
		final int @Nullable [] columns;

		Section(int single, int @Nullable [] ids, net.minecraft.util.@Nullable BitStorage storage, int @Nullable [] palette, int @Nullable [] columns) {
			this.single = single;
			this.ids = ids;
			this.storage = storage;
			this.palette = palette;
			this.columns = columns;
		}
	}

	private static final class Decoded {
		final ChunkSnapshot snapshot;
		final @Nullable Looks looks;
		final @Nullable Section[] sections;
		/** Per section, every block's id, indexed {@code (y << 8) | (z << 4) | x}; made when {@link #state} first reads it. */
		final int[][] ids;
		/** Per section, one bit per block, set where it is {@link BlockLooks#SOLID}; built when first read. */
		final long[][] solid;
		/** The same, set where a solid block is smaller than a cube. */
		final long[][] partial;
		/** See {@link VoxelReader#casterSection}; built when first read. */
		final long[][] casters;
		/** See {@link VoxelReader#solidTop}; {@code Integer.MAX_VALUE} until worked out. */
		int solidTop = Integer.MAX_VALUE;

		/** What earlier bakes worked out from these blocks under these looks, shared with them; null where nothing is shared. */
		final @Nullable ChunkBakeData shared;

		Decoded(ChunkSnapshot snapshot, @Nullable Looks looks, @Nullable ChunkBakeData shared) {
			this.snapshot = snapshot;
			this.looks = looks;
			this.shared = shared;
			this.sections = new Section[snapshot.sectionCount()];
			this.ids = new int[snapshot.sectionCount()][];
			this.solid = new long[snapshot.sectionCount()][];
			this.partial = new long[snapshot.sectionCount()][];
			this.casters = new long[snapshot.sectionCount()][];
		}

		/** The section's solid mask, indexed as its ids. */
		long[] solid(int index, byte[] kinds, int @Nullable [] shapes, int[] unpacked) {
			if (index < 0 || index >= this.solid.length) {
				return NOTHING_SOLID;
			}
			long[] mask = this.solid[index];
			if (mask == null && this.shared != null && (mask = this.shared.solid.get(index)) != null) {
				// Published after its partial mask, so that is there too.
				this.partial[index] = this.shared.partial.get(index);
				this.solid[index] = mask;
			}
			if (mask == null) {
				long[] partial = NOTHING_SOLID;
				Section section = this.section(index);
				if (section == AIR_SECTION) {
					mask = NOTHING_SOLID;
				} else if (section.single >= 0) {
					int id = section.single;
					mask = kinds[id] == BlockLooks.SOLID ? ALL_SOLID : NONE_OF_ONE;
					if (mask == ALL_SOLID && shapes != null && shapes[id] != 0) {
						partial = ALL_SOLID;
					}
				} else if (section == BY_BLOCK && this.ids[index] == null) {
					// Straight from the blocks, without numbering every block of the section.
					PalettedContainer<BlockState> states = this.snapshot.section(index);
					mask = new long[64];
					BlockState last = null;
					int f = 0;
					for (int i = 0; i < 4096; i++) {
						BlockState state = this.snapshot.state(states, i & 15, i >> 8, i >> 4 & 15);
						if (state != last) {
							last = state;
							f = solidFlags(this.id(state), kinds, shapes);
						}
						mask[i >> 6] |= (long) (f & 1) << i;
						if (f > 1) {
							if (partial == NOTHING_SOLID) {
								partial = new long[64];
							}
							partial[i >> 6] |= 1L << i;
						}
					}
				} else {
					// Shadow and sky rays through a section need no more than these bits, so most
					// sections a bake reads are never numbered block by block. Sections with
					// copycats go through their ids, which carry the copycats' materials.
					int[] ids = section == BY_BLOCK ? this.ids[index] : section.ids;
					int[] columns = section.columns;
					// Per palette entry (per block with ids): 1 if solid, 3 if solid and smaller than a cube.
					byte[] flags;
					if (ids == null) {
						section.storage.unpack(unpacked);
						int[] palette = section.palette;
						flags = new byte[palette.length];
						for (int p = 0; p < palette.length; p++) {
							flags[p] = solidFlags(palette[p], kinds, shapes);
						}
					} else {
						columns = null;
						flags = null;
					}
					mask = new long[64];
					for (int w = 0; w < 64; w++) {
						long solid = 0;
						long smaller = 0;
						for (int b = 0; b < 64; b++) {
							int i = (w << 6) | b;
							int f = flags == null ? solidFlags(ids[i], kinds, shapes) : flags[unpacked[columns == null ? i : (i & 0xF00) | columns[i & 0xFF]]];
							solid |= (long) (f & 1) << b;
							smaller |= (long) (f >> 1) << b;
						}
						mask[w] = solid;
						if (smaller != 0) {
							if (partial == NOTHING_SOLID) {
								partial = new long[64];
							}
							partial[w] = smaller;
						}
					}
				}
				this.partial[index] = partial;
				this.solid[index] = mask;
				if (this.shared != null) {
					this.shared.partial.set(index, partial);
					this.shared.solid.set(index, mask);
				}
			}
			return mask;
		}

		private static byte solidFlags(int id, byte[] kinds, int @Nullable [] shapes) {
			if (kinds[id] != BlockLooks.SOLID) {
				return 0;
			}
			return shapes != null && shapes[id] != 0 ? (byte) 3 : (byte) 1;
		}

		/** The section's mask of solid blocks smaller than a cube; read after {@link #solid}. */
		long[] partial(int index) {
			return index < 0 || index >= this.partial.length || this.partial[index] == null ? NOTHING_SOLID : this.partial[index];
		}

		int solidTop(byte[] kinds, int @Nullable [] shapes, int[] unpacked) {
			if (this.solidTop == Integer.MAX_VALUE) {
				int top = Integer.MIN_VALUE;
				for (int index = this.sections.length - 1; index >= 0 && top == Integer.MIN_VALUE; index--) {
					long[] mask = this.solid(index, kinds, shapes, unpacked);
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

		/**
		 * The section's ids, decoded once for this job: a ray stays in a section for many steps, and
		 * an array read is the fastest lookup.
		 */
		int[] ids(int index, int[] unpacked) {
			if (index < 0 || index >= this.ids.length) {
				return AIR_IDS;
			}
			int[] ids = this.ids[index];
			if (ids == null) {
				Section section = this.section(index);
				if (section == AIR_SECTION) {
					ids = AIR_IDS;
				} else if (section.ids != null) {
					ids = section.ids;
				} else if (section == BY_BLOCK) {
					ids = this.byBlock(index);
				} else if (section.single >= 0) {
					ids = new int[4096];
					Arrays.fill(ids, section.single);
				} else {
					ids = new int[4096];
					int[] palette = section.palette;
					int[] columns = section.columns;
					if (columns == null) {
						section.storage.unpack(ids);
						for (int i = 0; i < 4096; i++) {
							ids[i] = palette[ids[i]];
						}
					} else {
						section.storage.unpack(unpacked);
						for (int i = 0; i < 4096; i++) {
							ids[i] = palette[unpacked[(i & 0xF00) | columns[i & 0xFF]]];
						}
					}
				}
				this.ids[index] = ids;
			}
			return ids;
		}

		/** The state's id for a bake, with its look worked out when there are looks to read. */
		private int id(BlockState state) {
			return this.looks == null ? BlockLooks.bakeId(state) : this.looks.bakeLookId(state);
		}

		/** What the section holds: {@link #AIR_SECTION} where it has no blocks. */
		Section section(int index) {
			if (index < 0 || index >= this.sections.length) {
				return AIR_SECTION;
			}
			Section section = this.sections[index];
			if (section == null) {
				section = this.read(index);
				this.sections[index] = section;
			}
			return section;
		}

		private Section read(int index) {
			PalettedContainer<BlockState> states = this.snapshot.section(index);
			BlockState uniform = this.snapshot.uniformSection(index);
			if (states == null && uniform == null) {
				return AIR_SECTION;
			}
			ChunkSnapshot.SectionCopycats copycats = this.looks == null ? null : this.snapshot.copycats(index);
			if (copycats == null) {
				if (uniform != null) {
					return new Section(this.id(this.snapshot.turned(uniform)), null, null, null, null);
				}
				ChunkSnapshot.Packed packed = this.snapshot.packed(index);
				int[] palette = packed == null ? null : this.paletteIds(packed);
				return palette != null ? new Section(-1, null, packed.storage(), palette, this.snapshot.columns()) : BY_BLOCK;
			}
			// Filled copycats take their material's look, so their sections are numbered block by
			// block up front: the solid bits come from these ids too.
			int[] ids;
			if (uniform != null) {
				ids = new int[4096];
				Arrays.fill(ids, this.id(this.snapshot.turned(uniform)));
			} else {
				ids = this.byBlock(index);
			}
			for (int i = 0; i < copycats.at().length; i++) {
				int at = copycats.at()[i];
				ids[at] = this.looks.copycat(ids[at], Block.BLOCK_STATE_REGISTRY.getId(copycats.material()[i]));
			}
			return new Section(-1, ids, null, null, null);
		}

		/** Every block's id in a section with a container, looked up block by block. */
		private int[] byBlock(int index) {
			PalettedContainer<BlockState> states = this.snapshot.section(index);
			int[] ids = new int[4096];
			BlockState lastState = null;
			int lastId = 0;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = this.snapshot.state(states, x, y, z);
						if (state != lastState) {
							lastState = state;
							lastId = this.id(state);
						}
						ids[(y << 8) | (z << 4) | x] = lastId;
					}
				}
			}
			return ids;
		}

		/**
		 * The id of each of the section's palette entries, as this view has them, with room for
		 * every index its storage can hold; null if the palette is too big to number this way.
		 */
		private int @Nullable [] paletteIds(ChunkSnapshot.Packed packed) {
			int bits = packed.storage().getBits();
			int size = packed.palette().getSize();
			if (bits > 8 || size > 1 << bits) {
				return null;
			}
			int[] ids = new int[1 << bits];
			Arrays.fill(ids, AIR_ID);
			for (int p = 0; p < size; p++) {
				BlockState state = packed.palette().valueFor(p);
				if (state != null) {
					ids[p] = this.id(this.snapshot.turned(state));
				}
			}
			return ids;
		}
	}
}
