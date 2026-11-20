package dev.diorama.bake;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.data.ChunkSnapshot;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Builds the block faces a tile shows, for drawing it on the GPU instead of tracing it.
 *
 * <p>The camera looks north and down, so a camera ray only ever moves down and north: it can
 * enter a block through its top or its south face, never another. A cell is reached when the cell
 * above or the cell south of it was reached and let the ray through. Walking each column of x
 * that way, top to bottom and south to north, finds exactly the faces the tracer's camera rays
 * can hit, without looking at anything buried. Faces of chunks the map has no data for are kept
 * as "unknown", so their pixels stay unknown the way a traced ray that runs into one does.
 *
 * <p>Cave slices and the sky view under a roof start inside rock, as the tracer's rays do: a ray
 * passes through solid blocks from where it starts until it first finds open space, and a ray
 * that never does shows the cut face. Those views are lit by the game's block and sky light,
 * which is recorded per face; with fog of war, faces the player hasn't seen stay unknown.
 *
 * <p>What belongs to a spot in the world rather than to the camera is worked out here once per
 * face: colour with biome tint, corner ambient occlusion, and in cave views the light. The GPU
 * adds sky occlusion (from the solid blocks around the tile, which this also packs), the sun and
 * its shadow, and the style's grade.
 */
public final class GpuMesher {
	/** Faces the camera can see. */
	public static final int TOP = 0;
	public static final int SOUTH = 1;
	/** Faces only the shadow map needs: they face the sun, which is in the west. */
	public static final int WEST = 2;
	/** The bottom of a cell in the lowest row a view reaches: where rays that met nothing end. */
	public static final int FLOOR = 3;

	/** {@link Quads} flags. */
	public static final int UNKNOWN = 1;
	public static final int LAVA = 2;
	/** A floor where the ray was still in rock: the tracer's cut face. */
	public static final int CUT = 4;
	/** A floor where the ray was in open space: the tracer's near-black void. */
	public static final int VOID = 8;
	/** A solid face whose pixels record {@link Overlays} bits; the GPU adds the chunk lines. */
	public static final int OVERLAY = 16;
	/** {@link Overlays#SLIME}, {@link Overlays#SPAWN_AT_NIGHT} and {@link Overlays#SPAWN_ANY_TIME} for the face. */
	public static final int SLIME = 32;
	public static final int SPAWN_AT_NIGHT = 64;
	public static final int SPAWN_ANY_TIME = 128;
	/** A water surface: the sky pass reads its light over the top of the cell it entered. */
	public static final int WATER_FACE = 256;

	private static final byte NO_WATER = 0;
	private static final byte SEEN_WATER = 1;
	private static final byte UNSEEN_WATER = 2;

	/** The section table's entries for sections without solid bits: a missing chunk, or nothing solid. */
	public static final int SECTION_UNKNOWN = 0xFFFFFFFE;
	public static final int SECTION_EMPTY = 0xFFFFFFFF;
	/** On a section's table entry: 128 more words follow its solid bits, marking the smaller blocks. */
	public static final int SECTION_PARTIAL = 0x80000000;

	private GpuMesher() {
	}

	/** Whether the walk takes rows of nothing but open air in one step. Only for checking that meshes come out the same either way. */
	public static boolean AIR_ROWS = true;

	/**
	 * Ints per face in a {@link Quads} list: cell, face, colour, occlusion, light, flags, then the
	 * face's box as {@link ShapeFaces} packs it, two ints in 128ths of a block, then under a roof
	 * the biome fog at its four corners (see {@link Quads#haze}). A cube's faces cover the whole
	 * cell; a smaller block's cover a piece of one of its boxes.
	 */
	public static final int STRIDE = 14;

	/**
	 * A growable list of faces, {@link #STRIDE} ints each: see {@link #add}. A tile's lists run to
	 * tens of MB but only hold its faces until {@code GpuTileBaker} packs them, so they live outside
	 * the Java heap, made for each mesh and freed once packed ({@link Mesh#freeFaces}). On the heap
	 * the collector had to copy them; kept for reuse, each thread's lists held on to the biggest
	 * tile it had ever built, which crowded the game's heap.
	 */
	public static final class Quads {
		/** Room a list starts with: 4096 faces, 224 KB. */
		private static final int FIRST_INTS = STRIDE * 4096;
		private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

		/** The native memory, apart from the list, so the cleaner can free it if nothing else did. */
		private static final class Memory implements Runnable {
			java.nio.@Nullable IntBuffer data;

			@Override
			public void run() {
				if (this.data != null) {
					MemoryUtil.memFree(this.data);
					this.data = null;
				}
			}
		}

		private final Memory memory = new Memory();
		private final java.lang.ref.Cleaner.Cleanable cleanable;
		public int count;

		Quads() {
			this.memory.data = MemoryUtil.memAllocInt(FIRST_INTS);
			// A mesh dropped before it was packed, after a failure say, is freed when it's collected.
			this.cleanable = CLEANER.register(this, this.memory);
		}

		/** Int {@code i} of the faces, {@link #STRIDE} to a face. */
		public int get(int i) {
			return this.memory.data.get(i);
		}

		/** Frees the list; reading it afterwards fails. Runs at most once, however often it's called. */
		void free() {
			this.cleanable.clean();
		}

		/** Puts the faces in this order: face {@code q} becomes the one {@code faces[q]} was. */
		void reorder(int[] faces) {
			java.nio.IntBuffer from = this.memory.data;
			java.nio.IntBuffer to = MemoryUtil.memAllocInt(from.capacity());
			long src = MemoryUtil.memAddress(from);
			long dst = MemoryUtil.memAddress(to);
			for (int q = 0; q < faces.length; q++) {
				MemoryUtil.memCopy(src + (long) faces[q] * STRIDE * Integer.BYTES, dst + (long) q * STRIDE * Integer.BYTES, STRIDE * Integer.BYTES);
			}
			this.memory.data = to;
			MemoryUtil.memFree(from);
		}

		/**
		 * One face of block {@code (x, y, z)}. {@code color} is sRGB {@code 0xAARRGGBB} where alpha
		 * carries the face's own weight (plants and glass) or emission; {@code ao} packs the four
		 * corners a byte each, in the order of {@link GpuMesher#aoTop}. {@code light} is the block
		 * and sky light in cave views: sky in the top byte, block light in the next; in the sky view
		 * only the block light, which lights the map at night.
		 */
		void add(int x, int y, int z, int face, int color, int ao, int light, int flags) {
			this.add(x, y, z, face, color, ao, light, flags, ShapeFaces.FULL_LO, ShapeFaces.FULL_HI);
		}

		/** A face on part of a block smaller than a cube, {@code lo} and {@code hi} as {@link ShapeFaces} packs them. */
		void add(int x, int y, int z, int face, int color, int ao, int light, int flags, int lo, int hi) {
			int i = this.count * STRIDE;
			java.nio.IntBuffer data = this.memory.data;
			if (i + STRIDE > data.capacity()) {
				data = MemoryUtil.memRealloc(data, data.capacity() * 2);
				this.memory.data = data;
			}
			data.put(i, x);
			data.put(i + 1, y);
			data.put(i + 2, z);
			data.put(i + 3, face);
			data.put(i + 4, color);
			data.put(i + 5, ao);
			data.put(i + 6, light);
			data.put(i + 7, flags);
			data.put(i + 8, lo);
			data.put(i + 9, hi);
			data.put(i + 10, 0);
			data.put(i + 11, 0);
			data.put(i + 12, 0);
			data.put(i + 13, 0);
			this.count++;
		}

		/**
		 * The last face's biome fog under a roof, at its cube face's corners in the order of
		 * {@link GpuMesher#aoTop}: sRGB bytes, red lowest, of TileBaker's blend before its light is limited.
		 */
		void haze(int a, int b, int c, int d) {
			int i = (this.count - 1) * STRIDE;
			java.nio.IntBuffer data = this.memory.data;
			data.put(i + 10, a);
			data.put(i + 11, b);
			data.put(i + 12, c);
			data.put(i + 13, d);
		}
	}

	/** Everything one tile draws, plus the region its shadow casters cover. */
	public static final class Mesh {
		public final TileKey key;
		/** Where camera rays start, one above the first row they walk. */
		public final int topY;
		/** The lowest row camera rays walk. */
		public final int minY;
		public final double sin;
		public final double cos;
		/** Lit by block and sky light rather than the sun: cave slices, and the view under a roof. */
		public final boolean lit;
		/**
		 * Under a roof, cave slices too: lit from above as if the rock over the first open space
		 * were lifted off, with shadows and sky occlusion, and fading into the biome's fog.
		 */
		public boolean lifted;
		/** A cave slice in the depth look; see {@link TileBaker#depthLook}. */
		public boolean depthLook;
		/** The roof's y, where the fog under it is measured from; see {@link ChunkSource#roof}. */
		public int roof;
		public final boolean cave;
		/** Rays start below the view's limit, so the tracer bakes nothing: every pixel unknown. */
		public final boolean empty;
		/** Something in view the GPU can't draw as the tracer does: trace the tile instead. */
		public boolean traceInstead;
		/** Solid and lava faces, faces of unknown chunks, and floors. */
		public final Quads opaque;
		/** Water a ray enters, through its top or south face. */
		public final Quads water;
		/** Plants and glass, which tint what is behind them; sorted far to near before drawing. */
		public final Quads tinted;
		/** Sun-facing faces of solid blocks, for the shadow map. */
		public final Quads casters;
		/** Lowest y of a solid face the camera sees, or {@code Integer.MAX_VALUE} for none. */
		public int lowestY = Integer.MAX_VALUE;
		/** Positions on the GPU are relative to this: the tile's west edge, and a section-aligned z. */
		public final int originX;
		public int originZ;
		// The caster region, in blocks.
		public int casterX0;
		public int casterX1;
		public int casterZ0;
		public int casterZ1;
		public int casterY0;
		public int casterY1;
		/**
		 * The solid blocks around the camera's faces, for sky occlusion and shadow walks on the GPU:
		 * a table of {@code voxelSize[0] * [1] * [2]} sections (x fastest, then z, then y) holding
		 * each one's offset into the same array or a {@code SECTION_*} value, then 128 words of solid
		 * bits per section that has any. Where an offset carries {@link #SECTION_PARTIAL}, 128 more
		 * words follow marking which of those blocks are smaller than a cube. Null in lit views,
		 * which don't use sky occlusion.
		 */
		public int[] voxels;
		public int voxelCount;
		/** The table's first section, relative to the origin's sections, and its size in sections. */
		public final int[] voxelOrigin = new int[3];
		public final int[] voxelSize = new int[3];
		/** One above the tallest block a sky ray can meet. */
		public int rayTop;
		/** Time spent building, for the experiment's numbers. */
		public long buildNanos;
		/** What to record for the overlays, or null for nothing. */
		public @Nullable Overlays overlays;
		private final java.util.Map<Long, Boolean> slimeChunks = new java.util.HashMap<>();

		Mesh(TileKey key, int topY, int minY, double sin, double cos, boolean lit, boolean cave, boolean empty) {
			this.opaque = new Quads();
			this.water = new Quads();
			this.tinted = new Quads();
			this.casters = new Quads();
			this.key = key;
			this.topY = topY;
			this.minY = minY;
			this.sin = sin;
			this.cos = cos;
			this.lit = lit;
			this.cave = cave;
			this.empty = empty;
			this.originX = (int) Math.floor(key.minU());
		}

		/** Frees the face lists, once they're packed; the counts stay. */
		public void freeFaces() {
			this.opaque.free();
			this.water.free();
			this.tinted.free();
			this.casters.free();
		}
	}

	/** The z of the first and last cell of row {@code y} whose map rows overlap {@code [minV, maxV)}. */
	private static int zLow(double minV, int y, double sin, double cos) {
		return (int) Math.floor((minV + y * cos) / sin) - 1;
	}

	private static int zHigh(double maxV, int y, double sin, double cos) {
		return (int) Math.floor((maxV + (y + 1) * cos) / sin) + 1;
	}

	/**
	 * {@code fogOfWar} and {@code overlays} as for {@link TileBaker#bake}: cave views may only show
	 * what the player has seen, and overlay bits are recorded when {@code overlays} isn't null.
	 */
	public static Mesh build(TileKey key, ChunkSource source, BlockLooks looks, boolean fogOfWar, @Nullable Overlays overlays) {
		return build(key, source, looks, fogOfWar, overlays, null);
	}

	/**
	 * Same, with the saved tile's {@link TileSpots}: cells of chunks the map has no data for are
	 * open where every saved ray through them stood lower (see {@link TileSpots#passedWhenSaved}),
	 * as the tracer has them.
	 */
	public static Mesh build(TileKey key, ChunkSource source, BlockLooks looks, boolean fogOfWar, @Nullable Overlays overlays, int @Nullable [] savedSpots) {
		long start = System.nanoTime();
		TileBaker helper = TileBaker.forMeshing(key, source, looks, fogOfWar, savedSpots);
		VoxelReader reader = helper.reader;
		MapStyle style = MapStyle.get();
		double sin = Projection.sin();
		double cos = Projection.cos();
		int startY = helper.startY;
		int limitY = helper.limitY;
		boolean lit = helper.lit;
		boolean fog = helper.fogOfWar;
		Mesh mesh = new Mesh(key, startY, limitY, sin, cos, lit, helper.cave, startY <= limitY);
		mesh.overlays = overlays;
		boolean lifted = helper.lifted;
		mesh.lifted = lifted;
		mesh.depthLook = helper.depthLook;
		if (helper.caveCut != null) {
			// Ground raised over the cut is traced: the mesh has rays start at one height.
			mesh.traceInstead = true;
			return mesh;
		}
		mesh.roof = source.roof();
		// Under a lifted lid nothing above the cut is left to block light.
		mesh.rayTop = lifted ? Math.min(helper.rayTop, startY) : helper.rayTop;

		double minV = key.minV();
		double maxV = minV + Projection.TILE_UNITS;
		int x0 = mesh.originX;
		int x1 = x0 + Projection.TILE_UNITS;
		int zBase = zLow(minV, limitY, sin, cos) - 2;
		int zSpan = zHigh(maxV, startY, sin, cos) - zBase + 3;
		// Per row: reached in open space, reached while still in the rock a cave view starts in,
		// and whether the open cell was water, so only the face a ray first enters water by is drawn.
		boolean[] aboveOpen = new boolean[zSpan];
		boolean[] hereOpen = new boolean[zSpan];
		boolean[] aboveRock = new boolean[zSpan];
		boolean[] hereRock = new boolean[zSpan];
		// Which of the rays still in the rock: see RockRays. Up to MAX ranges a cell, or ALL.
		double cot = cos / sin;
		double[][] aboveRockU = new double[zSpan][RockRays.MAX * 2];
		double[][] hereRockU = new double[zSpan][RockRays.MAX * 2];
		int[] aboveRockN = new int[zSpan];
		int[] hereRockN = new int[zSpan];
		double[] rockIn = new double[RockRays.MAX * 2];
		int[] pieces = new int[RockRays.MAX_PIECES * 2];
		boolean[] aboveWater = new boolean[zSpan];
		boolean[] hereWater = new boolean[zSpan];
		// Under fog of war, the first water the ray entered: none yet, at a cell the player has
		// seen, or not. The tracer shows a ray that then meets nothing only in the seen case.
		byte[] aboveFirstWater = new byte[zSpan];
		byte[] hereFirstWater = new byte[zSpan];
		// The camera's faces' extent, for the sky occlusion region.
		int fx0 = Integer.MAX_VALUE;
		int fx1 = Integer.MIN_VALUE;
		int fy0 = Integer.MAX_VALUE;
		int fy1 = Integer.MIN_VALUE;
		int fz0 = Integer.MAX_VALUE;
		int fz1 = Integer.MIN_VALUE;

		// In the sky view a row of cells that are all air, reached from above at its south end, is
		// reached throughout and adds nothing but its extent: those come in one step. Per cell of
		// the column's x, the height from which everything up is air (VoxelReader.openFrom).
		boolean airRows = AIR_ROWS && !lit && !lifted && !fog && looks.kind[VoxelReader.AIR_ID] == BlockLooks.AIR;
		int[] openFrom = airRows ? new int[zSpan] : null;
		// Each column is walked by a method of its own: called a hundred and thirty times a mesh, it is
		// compiled early, and a branch it hasn't seen yet costs one column in the interpreter, not a tile.
		Walk walk = new Walk(mesh, helper, reader, source, looks, style, sin, cos, cot, startY, limitY, lit, fog, lifted, minV, maxV, x0, zBase, zSpan,
			airRows, openFrom, aboveOpen, hereOpen, aboveRock, hereRock, aboveRockU, hereRockU, aboveRockN, hereRockN, rockIn, pieces,
			aboveWater, hereWater, aboveFirstWater, hereFirstWater);
		// One column more each side: the bake draws a pixel past the tile's edges for outlines.
		for (int x = x0 - 1; x <= x1 && !mesh.empty; x++) {
			walk.column(x);
		}
		fx0 = walk.fx0;
		fx1 = walk.fx1;
		fy0 = walk.fy0;
		fy1 = walk.fy1;
		fz0 = walk.fz0;
		fz1 = walk.fz1;
		if (!lit || lifted) {
			casters(mesh, helper, reader, looks, style, x0, x1, minV, maxV, source.minY());
			if (fx0 <= fx1) {
				voxels(mesh, helper, reader, fx0, fx1, fy0, fy1, fz0, fz1);
			}
		} else {
			// No sun in these views, so no shadow map; its region just has to be sane.
			mesh.casterX0 = x0 - 1;
			mesh.casterX1 = x1 + 2;
			mesh.casterZ0 = zBase;
			mesh.casterZ1 = zBase + zSpan;
			mesh.casterY0 = limitY;
			mesh.casterY1 = startY;
		}
		mesh.originZ = Math.floorDiv(Math.min(zBase, mesh.casterZ0), 16) * 16;
		if (mesh.voxels != null) {
			// The table was placed before the origin was known: make it relative now.
			mesh.voxelOrigin[2] -= mesh.originZ >> 4;
		}
		sortNearToFar(mesh.tinted, sin, cos);
		mesh.buildNanos = System.nanoTime() - start;
		return mesh;
	}

	/**
	 * Walks one column of x of a mesh at a time: what {@link #build} works out once for the tile, the
	 * rows of cell state the walk reuses from column to column, and the camera's faces' extent so far.
	 */
	private static final class Walk {
		private final Mesh mesh;
		private final TileBaker helper;
		private final VoxelReader reader;
		private final ChunkSource source;
		private final BlockLooks looks;
		private final MapStyle style;
		private final double sin;
		private final double cos;
		private final double cot;
		private final int startY;
		private final int limitY;
		private final boolean lit;
		private final boolean fog;
		private final boolean lifted;
		private final double minV;
		private final double maxV;
		private final int x0;
		private final int zBase;
		private final int zSpan;
		private final boolean airRows;
		private final int @Nullable [] openFrom;
		private final boolean[] aboveOpen;
		private final boolean[] hereOpen;
		private final boolean[] aboveRock;
		private final boolean[] hereRock;
		private final double[][] aboveRockU;
		private final double[][] hereRockU;
		private final int[] aboveRockN;
		private final int[] hereRockN;
		private final double[] rockIn;
		private final int[] pieces;
		private final boolean[] aboveWater;
		private final boolean[] hereWater;
		private final byte[] aboveFirstWater;
		private final byte[] hereFirstWater;
		int fx0 = Integer.MAX_VALUE;
		int fx1 = Integer.MIN_VALUE;
		int fy0 = Integer.MAX_VALUE;
		int fy1 = Integer.MIN_VALUE;
		int fz0 = Integer.MAX_VALUE;
		int fz1 = Integer.MIN_VALUE;

		Walk(Mesh mesh, TileBaker helper, VoxelReader reader, ChunkSource source, BlockLooks looks, MapStyle style, double sin, double cos, double cot, int startY, int limitY, boolean lit, boolean fog, boolean lifted, double minV, double maxV, int x0, int zBase, int zSpan, boolean airRows, int @Nullable [] openFrom, boolean[] aboveOpen, boolean[] hereOpen, boolean[] aboveRock, boolean[] hereRock, double[][] aboveRockU, double[][] hereRockU, int[] aboveRockN, int[] hereRockN, double[] rockIn, int[] pieces, boolean[] aboveWater, boolean[] hereWater, byte[] aboveFirstWater, byte[] hereFirstWater) {
			this.mesh = mesh;
			this.helper = helper;
			this.reader = reader;
			this.source = source;
			this.looks = looks;
			this.style = style;
			this.sin = sin;
			this.cos = cos;
			this.cot = cot;
			this.startY = startY;
			this.limitY = limitY;
			this.lit = lit;
			this.fog = fog;
			this.lifted = lifted;
			this.minV = minV;
			this.maxV = maxV;
			this.x0 = x0;
			this.zBase = zBase;
			this.zSpan = zSpan;
			this.airRows = airRows;
			this.openFrom = openFrom;
			this.aboveOpen = aboveOpen;
			this.hereOpen = hereOpen;
			this.aboveRock = aboveRock;
			this.hereRock = hereRock;
			this.aboveRockU = aboveRockU;
			this.hereRockU = hereRockU;
			this.aboveRockN = aboveRockN;
			this.hereRockN = hereRockN;
			this.rockIn = rockIn;
			this.pieces = pieces;
			this.aboveWater = aboveWater;
			this.hereWater = hereWater;
			this.aboveFirstWater = aboveFirstWater;
			this.hereFirstWater = hereFirstWater;
		}

		/** Walks column {@code x}: its camera faces into the mesh. */
		void column(int x) {
			Mesh mesh = this.mesh;
			TileBaker helper = this.helper;
			VoxelReader reader = this.reader;
			ChunkSource source = this.source;
			BlockLooks looks = this.looks;
			MapStyle style = this.style;
			double sin = this.sin;
			double cos = this.cos;
			double cot = this.cot;
			int startY = this.startY;
			int limitY = this.limitY;
			boolean lit = this.lit;
			boolean fog = this.fog;
			boolean lifted = this.lifted;
			double minV = this.minV;
			double maxV = this.maxV;
			int x0 = this.x0;
			int zBase = this.zBase;
			int zSpan = this.zSpan;
			boolean airRows = this.airRows;
			int[] openFrom = this.openFrom;
			boolean[] aboveOpen = this.aboveOpen;
			boolean[] hereOpen = this.hereOpen;
			boolean[] aboveRock = this.aboveRock;
			boolean[] hereRock = this.hereRock;
			double[][] aboveRockU = this.aboveRockU;
			double[][] hereRockU = this.hereRockU;
			int[] aboveRockN = this.aboveRockN;
			int[] hereRockN = this.hereRockN;
			double[] rockIn = this.rockIn;
			int[] pieces = this.pieces;
			boolean[] aboveWater = this.aboveWater;
			boolean[] hereWater = this.hereWater;
			byte[] aboveFirstWater = this.aboveFirstWater;
			byte[] hereFirstWater = this.hereFirstWater;
			int fx0 = this.fx0;
			int fx1 = this.fx1;
			int fy0 = this.fy0;
			int fy1 = this.fy1;
			int fz0 = this.fz0;
			int fz1 = this.fz1;
		boolean airRow = airRows;
		if (airRow) {
			int[] open = null;
			for (int i = 0; i < zSpan; i++) {
				int z = zBase + i;
				if (i == 0 || (z & 15) == 0) {
					open = reader.openFrom(x >> 4, z >> 4);
				}
				openFrom[i] = open == null ? Integer.MAX_VALUE : open[((z & 15) << 4) | (x & 15)];
			}
		}
		// Where the rays start: open air above the tallest block, or inside rock at a cut.
		java.util.Arrays.fill(aboveOpen, !lit);
		java.util.Arrays.fill(aboveRock, lit);
		java.util.Arrays.fill(aboveRockN, RockRays.ALL);
		java.util.Arrays.fill(aboveWater, false);
		java.util.Arrays.fill(aboveFirstWater, NO_WATER);
		// The cells of the row above that rays reached, as indices: none below or past these can
		// pass rays down. Every cell starts reached.
		int aboveLo = 0;
		int aboveHi = zSpan - 1;
		for (int y = startY - 1; y >= limitY; y--) {
			int hereLo = Integer.MAX_VALUE;
			int hereHi = -1;
			int lo = zLow(minV, y, sin, cos);
			int hi = zHigh(maxV, y, sin, cos);
			java.util.Arrays.fill(hereOpen, false);
			java.util.Arrays.fill(hereRock, false);
			java.util.Arrays.fill(hereRockN, 0);
			java.util.Arrays.fill(hereWater, false);
			java.util.Arrays.fill(hereFirstWater, NO_WATER);
			boolean any = false;
			if (airRow) {
				// Until the first row that isn't all air from its south end on, which ends them.
				airRow = aboveOpen[hi - zBase];
				for (int i = lo - zBase; i <= hi - zBase && airRow; i++) {
					airRow = openFrom[i] <= y;
				}
				if (airRow) {
					java.util.Arrays.fill(hereOpen, lo - zBase, hi - zBase + 1, true);
					hereLo = lo - zBase;
					hereHi = hi - zBase;
					any = true;
					fy0 = Math.min(fy0, y);
					fy1 = Math.max(fy1, y + 1);
					fx0 = Math.min(fx0, x);
					fx1 = Math.max(fx1, x);
					fz0 = Math.min(fz0, lo);
					fz1 = Math.max(fz1, hi);
				}
			}
			// Cells south of what the row above reached can only be reached from the south, which
			// starts nowhere; north of it, only along an unbroken run from the south.
			for (int z = airRow ? lo - 1 : Math.min(hi, zBase + aboveHi); z >= lo; z--) {
				int i = z - zBase;
				if (i < aboveLo && !(z < hi && (hereOpen[i + 1] || hereRock[i + 1]))) {
					break;
				}
				boolean openAbove = aboveOpen[i];
				boolean openSouth = z < hi && hereOpen[i + 1];
				boolean rockAbove = aboveRock[i];
				boolean rockSouth = z < hi && hereRock[i + 1];
				boolean fromAbove = openAbove || rockAbove;
				boolean fromSouth = openSouth || rockSouth;
				if (!fromAbove && !fromSouth) {
					continue;
				}
				int rockN = 0;
				if (rockAbove || rockSouth) {
					rockN = RockRays.enter(rockIn, aboveRockU[i], rockAbove ? aboveRockN[i] : 0, rockSouth ? hereRockU[i + 1] : null, rockSouth ? hereRockN[i + 1] : 0, y, z, cot);
					if (rockN > RockRays.MAX) {
						// More ranges than a cell keeps: rare enough to trace.
						mesh.traceInstead = true;
						rockN = RockRays.ALL;
					}
				}
				int id = reader.state(x, y, z);
				if (id == VoxelReader.UNKNOWN) {
					if (!helper.passesMissing(y) && !helper.passedWhenSaved(x, y, z)) {
						if (fromAbove) {
							mesh.opaque.add(x, y, z, TOP, 0, 0, 0, UNKNOWN);
						}
						if (fromSouth) {
							mesh.opaque.add(x, y, z, SOUTH, 0, 0, 0, UNKNOWN);
						}
						continue;
					}
					id = VoxelReader.AIR_ID;
				}
				byte kind = looks.kind[id];
				// Smaller than a cube (only solid and see-through blocks have shapes): a ray
				// meets one of its boxes or goes on beside them, as TileBaker.enterShape.
				int shape = kind == BlockLooks.SOLID || kind == BlockLooks.GLASS ? looks.shape[id] : 0;
				if (lifted && rockN != 0 && kind != BlockLooks.SOLID && y + 1 < startY && reader.pocket(x, y, z)) {
					// Under a roof a one-block pocket in the rock is rock to rays still in it, so
					// buried lava springs don't speckle it; open rays meet what's there.
					keepRock(hereRock, hereRockU, hereRockN, i, rockIn, rockN);
					any = true;
					if (openAbove || openSouth) {
						if (kind == BlockLooks.LAVA) {
							if (openAbove) {
								solidFace(mesh, helper, source, looks, style, id, x, y, z, true, kind, lit, fog, ShapeFaces.FULL_LO, ShapeFaces.FULL_HI, rockIn, rockN, cot, pieces);
							}
							if (openSouth) {
								solidFace(mesh, helper, source, looks, style, id, x, y, z, false, kind, lit, fog, ShapeFaces.FULL_LO, ShapeFaces.FULL_HI, rockIn, rockN, cot, pieces);
							}
							mesh.lowestY = Math.min(mesh.lowestY, y);
						} else if (kind == BlockLooks.AIR || (kind == BlockLooks.PLANT || kind == BlockLooks.GLASS) && shape == 0) {
							hereOpen[i] = true;
							if (fog) {
								byte a = openAbove ? aboveFirstWater[i] : NO_WATER;
								byte b = openSouth ? hereFirstWater[i + 1] : NO_WATER;
								hereFirstWater[i] = a == SEEN_WATER || b == SEEN_WATER ? SEEN_WATER : a == UNSEEN_WATER || b == UNSEEN_WATER ? UNSEEN_WATER : NO_WATER;
							}
							if (kind != BlockLooks.AIR) {
								// Roots, fungi, vines and fire in a gap: they tint only the open rays.
								helper.albedo(id, x, z, true);
								int color = encode(helper.albedoR, helper.albedoG, helper.albedoB, kind == BlockLooks.PLANT ? 102 : 64);
								if (openAbove) {
									tintedFace(mesh.tinted, x, y, z, true, color, rockIn, rockN, cot, pieces);
								}
								if (openSouth) {
									tintedFace(mesh.tinted, x, y, z, false, color, rockIn, rockN, cot, pieces);
								}
							}
						} else {
							// Water, or a see-through block smaller than a cube, in a pocket that rays
							// of both kinds reach.
							mesh.traceInstead = true;
						}
					}
				} else if (shape != 0) {
					ShapeFaces sh = looks.faces(shape);
					int wet = looks.wetShape[id];
					boolean passes = false;
					boolean reached;
					boolean traced = false;
					boolean underside = false;
					if (kind == BlockLooks.SOLID) {
						if (rockN != 0) {
							// Inside the rock below a cut, any solid block is rock.
							keepRock(hereRock, hereRockU, hereRockN, i, rockIn, rockN);
							any = true;
						}
						reached = openAbove || openSouth;
						if (reached && (lifted || helper.depthLook) && reader.ceilingUnderside(x, y, z, helper.lid(x, z), limitY)) {
							// The bottom of a ceiling: rays that have left the rock pass
							// through it, as the tracer's do, so it doesn't float over the floor.
							passes = true;
							underside = true;
							if (wet >= 0) {
								// They'd come out in water.
								mesh.traceInstead = true;
							}
						} else if (reached) {
							mesh.lowestY = Math.min(mesh.lowestY, y);
							// A piece on the cell's top or south side is met only by rays coming in that way, one
							// inside the cell by rays from either, and none by rays still in the rock.
							for (int b = 0; b < sh.top.length; b += 2) {
								if (openAbove || ShapeFaces.inset(sh.top[b + 1], true)) {
									solidFace(mesh, helper, source, looks, style, id, x, y, z, true, kind, lit, fog, sh.top[b], sh.top[b + 1], rockIn, rockN, cot, pieces);
								}
							}
							for (int b = 0; b < sh.south.length; b += 2) {
								if (openSouth || ShapeFaces.inset(sh.south[b + 1], false)) {
									solidFace(mesh, helper, source, looks, style, id, x, y, z, false, kind, lit, fog, sh.south[b], sh.south[b + 1], rockIn, rockN, cot, pieces);
								}
							}
							// Rays that miss its boxes go on, even past one filling the floor: a ray coming in near
							// the north side leaves before it gets down to the box.
							passes = true;
							if (wet >= 0 && rockN != 0 && (wet(reader, looks, x, y - 1, z) || wet(reader, looks, x, y, z - 1))) {
								// Rays from the rock leave it dry, into water whose surface the next cell won't draw
								// for them, as it's drawn here for the others.
								traced = true;
								mesh.traceInstead = true;
							}
						}
					} else {
						reached = fromAbove || fromSouth;
						if (reached) {
							// Only what a ray meets first in the cell: the tracer tints a ray once for
							// the block it meets.
							helper.albedo(id, x, z, true);
							int color = encode(helper.albedoR, helper.albedoG, helper.albedoB, 64);
							int[][] seen = sh.seen(sin, cos);
							for (int b = 0; b < seen[0].length; b += 2) {
								if (fromAbove || ShapeFaces.inset(seen[0][b + 1], true)) {
									mesh.tinted.add(x, y, z, TOP, color, 0, 0, 0, seen[0][b], seen[0][b + 1]);
								}
							}
							for (int b = 0; b < seen[1].length; b += 2) {
								if (fromSouth || ShapeFaces.inset(seen[1][b + 1], false)) {
									mesh.tinted.add(x, y, z, SOUTH, color, 0, 0, 0, seen[1][b], seen[1][b + 1]);
								}
							}
							passes = true;
						}
					}
					if (passes) {
						// Rays that miss the boxes go on through the cell.
						hereOpen[i] = true;
						any = true;
						if (fog) {
							byte a = openAbove ? aboveFirstWater[i] : NO_WATER;
							byte c = openSouth ? hereFirstWater[i + 1] : NO_WATER;
							hereFirstWater[i] = a == SEEN_WATER || c == SEEN_WATER ? SEEN_WATER : a == UNSEEN_WATER || c == UNSEEN_WATER ? UNSEEN_WATER : NO_WATER;
						}
					}
					if (reached && !traced && !underside && wet >= 0) {
						int[] wetTop = sh.wetTop;
						int[] wetSouth = sh.wetSouth;
						if (kind == BlockLooks.SOLID && rockN != 0) {
							wetTop = outsideRock(sh.wetTop, true, rockIn, rockN, y, z, cot, pieces);
							wetSouth = outsideRock(sh.wetSouth, false, rockIn, rockN, y, z, cot, pieces);
						}
						// Waterlogged: in water from where the ray came into the cell, but dry
						// where it meets the block right there.
						waterCell(mesh, helper, reader, source, wet, x, y, z, i, fromAbove, fromSouth, openAbove, openSouth,
							aboveWater, hereWater, hereFirstWater, lit, fog, wetTop, wetSouth);
						if (sh.wetCut) {
							// Rays that came in dry where the block is level with the water are in
							// water again in the next cell, so that one draws its own surface; where
							// this cell's water is drawn, it's nearer and wins.
							hereWater[i] = false;
						}
					}
					if (fog && hereRock[i] && hereOpen[i] && hereFirstWater[i] != NO_WATER) {
						// The rays from the rock it lets through haven't been in water, the others
						// have, here or before: one state for both doesn't hold.
						mesh.traceInstead = true;
					}
				} else if (kind == BlockLooks.SOLID) {
					if (rockN != 0) {
						// Still in the rock below the cut.
						keepRock(hereRock, hereRockU, hereRockN, i, rockIn, rockN);
						any = true;
					}
					if ((openAbove || openSouth) && (lifted || helper.depthLook) && reader.ceilingUnderside(x, y, z, helper.lid(x, z), limitY)) {
						// The bottom of a ceiling: rays that have left the rock pass
						// through it, as the tracer's do, so it doesn't float over the floor.
						hereOpen[i] = true;
						any = true;
						if (fog) {
							byte a = openAbove ? aboveFirstWater[i] : NO_WATER;
							byte b = openSouth ? hereFirstWater[i + 1] : NO_WATER;
							hereFirstWater[i] = a == SEEN_WATER || b == SEEN_WATER ? SEEN_WATER : a == UNSEEN_WATER || b == UNSEEN_WATER ? UNSEEN_WATER : NO_WATER;
						}
					} else {
						if (openAbove) {
							solidFace(mesh, helper, source, looks, style, id, x, y, z, true, kind, lit, fog, ShapeFaces.FULL_LO, ShapeFaces.FULL_HI, rockIn, rockN, cot, pieces);
						}
						if (openSouth) {
							solidFace(mesh, helper, source, looks, style, id, x, y, z, false, kind, lit, fog, ShapeFaces.FULL_LO, ShapeFaces.FULL_HI, rockIn, rockN, cot, pieces);
						}
						mesh.lowestY = Math.min(mesh.lowestY, y);
					}
				} else if (kind == BlockLooks.LAVA) {
					// Lava stops a ray whether it came through open space or out of the rock.
					if (fromAbove) {
						solidFace(mesh, helper, source, looks, style, id, x, y, z, true, kind, lit, fog);
					}
					if (fromSouth) {
						solidFace(mesh, helper, source, looks, style, id, x, y, z, false, kind, lit, fog);
					}
					mesh.lowestY = Math.min(mesh.lowestY, y);
				} else {
					hereOpen[i] = true;
					any = true;
					if (fog) {
						// Rays from above and from the south are different rays through the same
						// cell; where they disagree, the seen one wins.
						byte a = openAbove ? aboveFirstWater[i] : NO_WATER;
						byte b = openSouth ? hereFirstWater[i + 1] : NO_WATER;
						hereFirstWater[i] = a == SEEN_WATER || b == SEEN_WATER ? SEEN_WATER : a == UNSEEN_WATER || b == UNSEEN_WATER ? UNSEEN_WATER : NO_WATER;
					}
					if (kind == BlockLooks.WATER) {
						waterCell(mesh, helper, reader, source, id, x, y, z, i, fromAbove, fromSouth, openAbove, openSouth,
							aboveWater, hereWater, hereFirstWater, lit, fog);
					} else if (kind == BlockLooks.PLANT || kind == BlockLooks.GLASS) {
						helper.albedo(id, x, z, true);
						int weight = kind == BlockLooks.PLANT ? 102 : 64;
						int color = encode(helper.albedoR, helper.albedoG, helper.albedoB, weight);
						if (fromAbove) {
							mesh.tinted.add(x, y, z, TOP, color, 0, 0, 0);
						}
						if (fromSouth) {
							mesh.tinted.add(x, y, z, SOUTH, color, 0, 0, 0);
						}
					}
				}
				if (y < fy0) {
					fy0 = y;
				}
				if (y + 1 > fy1) {
					fy1 = y + 1;
				}
				fx0 = Math.min(fx0, x);
				fx1 = Math.max(fx1, x);
				fz0 = Math.min(fz0, z);
				fz1 = Math.max(fz1, z);
				hereLo = Math.min(hereLo, i);
				hereHi = Math.max(hereHi, i);
			}
			if (y == helper.rockLimit && y > limitY) {
				// Rays still in the rock this far below the cut stop on the cut face, as the tracer's do.
				for (int z = hi; z >= lo; z--) {
					int i = z - zBase;
					if (!hereRock[i]) {
						continue;
					}
					if (hereOpen[i] && hereRockN[i] == RockRays.ALL) {
						mesh.traceInstead = true;
					} else if (hereOpen[i]) {
						int n = RockRays.pieces(pieces, 0, true, FLOOR_LO, FLOOR_HI, hereRockU[i], hereRockN[i], y, z, cot, true);
						for (int b = 0; b < n; b += 2) {
							mesh.opaque.add(x, y, z, FLOOR, 0, 0, 0, fog ? UNKNOWN : CUT, pieces[b], pieces[b + 1]);
						}
					} else {
						mesh.opaque.add(x, y, z, FLOOR, 0, 0, 0, fog ? UNKNOWN : CUT);
					}
					hereRock[i] = false;
					hereRockN[i] = 0;
				}
			}
			if (y == limitY) {
				// Rays still going: they end at the view's limit, on the cut face or the void.
				for (int z = hi; z >= lo; z--) {
					int i = z - zBase;
					if (hereRock[i] || hereOpen[i]) {
						int open = fog && hereFirstWater[i] != SEEN_WATER ? UNKNOWN : VOID;
						if (hereRock[i] && hereOpen[i] && hereRockN[i] != RockRays.ALL) {
							// Out of a smaller block both reach: the cut face for rays still in the rock, the void
							// for the others.
							int n = RockRays.pieces(pieces, 0, true, FLOOR_LO, FLOOR_HI, hereRockU[i], hereRockN[i], y, z, cot, true);
							for (int b = 0; b < n; b += 2) {
								mesh.opaque.add(x, y, z, FLOOR, 0, 0, 0, fog ? UNKNOWN : CUT, pieces[b], pieces[b + 1]);
							}
							n = RockRays.pieces(pieces, 0, true, FLOOR_LO, FLOOR_HI, hereRockU[i], hereRockN[i], y, z, cot, false);
							for (int b = 0; b < n; b += 2) {
								mesh.opaque.add(x, y, z, FLOOR, 0, 0, 0, open, pieces[b], pieces[b + 1]);
							}
						} else {
							mesh.opaque.add(x, y, z, FLOOR, 0, 0, 0, hereRock[i] ? (fog ? UNKNOWN : CUT) : open);
						}
					}
				}
			}
			boolean[] t = aboveOpen;
			aboveOpen = hereOpen;
			hereOpen = t;
			t = aboveRock;
			aboveRock = hereRock;
			hereRock = t;
			double[][] ru = aboveRockU;
			aboveRockU = hereRockU;
			hereRockU = ru;
			int[] rn = aboveRockN;
			aboveRockN = hereRockN;
			hereRockN = rn;
			t = aboveWater;
			aboveWater = hereWater;
			hereWater = t;
			byte[] w = aboveFirstWater;
			aboveFirstWater = hereFirstWater;
			hereFirstWater = w;
			aboveLo = hereLo;
			aboveHi = hereHi;
			if (!any) {
				break;
			}
		}
			this.fx0 = fx0;
			this.fx1 = fx1;
			this.fy0 = fy0;
			this.fy1 = fy1;
			this.fz0 = fz0;
			this.fz1 = fz1;
		}
	}

	/** A water cell a ray reaches: its top or south face where the ray first enters water. */
	private static void waterCell(
		Mesh mesh, TileBaker helper, VoxelReader reader, ChunkSource source, int id, int x, int y, int z, int i, boolean fromAbove, boolean fromSouth,
		boolean openAbove, boolean openSouth, boolean[] aboveWater, boolean[] hereWater, byte[] hereFirstWater, boolean lit, boolean fog
	) {
		waterCell(mesh, helper, reader, source, id, x, y, z, i, fromAbove, fromSouth, openAbove, openSouth, aboveWater, hereWater, hereFirstWater, lit, fog,
			null, null);
	}

	/** With {@code top} and {@code south} the pieces of the water's faces to draw (lo, hi pairs), or null for whole ones. */
	private static void waterCell(
		Mesh mesh, TileBaker helper, VoxelReader reader, ChunkSource source, int id, int x, int y, int z, int i, boolean fromAbove, boolean fromSouth,
		boolean openAbove, boolean openSouth, boolean[] aboveWater, boolean[] hereWater, byte[] hereFirstWater, boolean lit, boolean fog,
		int @Nullable [] top, int @Nullable [] south
	) {
		hereWater[i] = true;
		boolean entersTop = fromAbove && !(openAbove && aboveWater[i]);
		boolean entersSouth = fromSouth && !(openSouth && hereWater[i + 1]);
		if (fog && hereFirstWater[i] == NO_WATER) {
			hereFirstWater[i] = source.isSeen(x, y, z) ? SEEN_WATER : UNSEEN_WATER;
		}
		if (entersTop || entersSouth) {
			helper.albedo(id, x, z, true);
			int color = encode(helper.albedoR, helper.albedoG, helper.albedoB, 255);
			// Lit like the tracer: by the light just above the cell it entered.
			int light = lit ? light(reader, x, y + 1, z) : 0;
			if (entersTop) {
				waterFaces(mesh.water, x, y, z, TOP, color, light, top);
			}
			if (entersSouth) {
				waterFaces(mesh.water, x, y, z, SOUTH, color, light, south);
			}
		}
	}

	private static void waterFaces(Quads water, int x, int y, int z, int face, int color, int light, int @Nullable [] pieces) {
		if (pieces == null) {
			water.add(x, y, z, face, color, 0, light, WATER_FACE);
			return;
		}
		for (int b = 0; b < pieces.length; b += 2) {
			water.add(x, y, z, face, color, 0, light, WATER_FACE, pieces[b], pieces[b + 1]);
		}
	}

	/** A cell's floor, as a top piece at height 0: see {@link RockRays#pieces}. */
	private static final int FLOOR_LO = ShapeFaces.pack(0, 0, 0);
	private static final int FLOOR_HI = ShapeFaces.pack(ShapeFaces.UNIT, 0, ShapeFaces.UNIT);

	/** Notes the rays still in the rock that cell i passes on. */
	private static void keepRock(boolean[] rock, double[][] u, int[] n, int i, double[] in, int count) {
		rock[i] = true;
		n[i] = count;
		if (count > 0) {
			System.arraycopy(in, 0, u[i], 0, count * 2);
		}
	}

	/** Whether the cell holds water, as water or waterlogged. */
	private static boolean wet(VoxelReader reader, BlockLooks looks, int x, int y, int z) {
		int id = reader.state(x, y, z);
		return id != VoxelReader.UNKNOWN && (looks.kind[id] == BlockLooks.WATER || looks.wetShape[id] >= 0);
	}

	/** {@code faces}' parts the rays that aren't in the rock meet, as lo, hi pairs. */
	private static int[] outsideRock(int[] faces, boolean top, double[] rock, int rockN, int y, int z, double cot, int[] pieces) {
		int[] out = new int[faces.length * RockRays.MAX_PIECES];
		int n = 0;
		for (int b = 0; b < faces.length; b += 2) {
			int end = RockRays.pieces(pieces, 0, top, faces[b], faces[b + 1], rock, rockN, y, z, cot, false);
			System.arraycopy(pieces, 0, out, n, end);
			n += end;
		}
		return java.util.Arrays.copyOf(out, n);
	}

	/** A plant or glass cell's top or south face, less where rays still in the rock cross it. */
	private static void tintedFace(Quads tinted, int x, int y, int z, boolean top, int color, double[] rock, int rockN, double cot, int[] pieces) {
		int n = RockRays.pieces(pieces, 0, top, ShapeFaces.FULL_LO, ShapeFaces.FULL_HI, rock, rockN, y, z, cot, false);
		for (int b = 0; b < n; b += 2) {
			tinted.add(x, y, z, top ? TOP : SOUTH, color, 0, 0, 0, pieces[b], pieces[b + 1]);
		}
	}

	/** A face piece, less where rays still in the rock meet it: they go on through. */
	private static void solidFace(
		Mesh mesh, TileBaker helper, ChunkSource source, BlockLooks looks, MapStyle style, int id, int x, int y, int z, boolean top, byte kind, boolean lit, boolean fog,
		int lo, int hi, double[] rock, int rockN, double cot, int[] pieces
	) {
		if (rockN == 0) {
			solidFace(mesh, helper, source, looks, style, id, x, y, z, top, kind, lit, fog, lo, hi);
			return;
		}
		int n = RockRays.pieces(pieces, 0, top, lo, hi, rock, rockN, y, z, cot, false);
		for (int b = 0; b < n; b += 2) {
			solidFace(mesh, helper, source, looks, style, id, x, y, z, top, kind, lit, fog, pieces[b], pieces[b + 1]);
		}
	}

	/** A solid or lava face, or an unknown one when fog of war hides it. */
	private static void solidFace(
		Mesh mesh, TileBaker helper, ChunkSource source, BlockLooks looks, MapStyle style, int id, int x, int y, int z, boolean top, byte kind, boolean lit, boolean fog
	) {
		solidFace(mesh, helper, source, looks, style, id, x, y, z, top, kind, lit, fog, ShapeFaces.FULL_LO, ShapeFaces.FULL_HI);
	}

	/**
	 * On a piece of a smaller block's face, shaded as the cube's face would be at the same place:
	 * TileBaker.shade reads occlusion, light and sky from the cell in front of the cube's face.
	 */
	private static void solidFace(
		Mesh mesh, TileBaker helper, ChunkSource source, BlockLooks looks, MapStyle style, int id, int x, int y, int z, boolean top, byte kind, boolean lit, boolean fog,
		int lo, int hi
	) {
		int face = top ? TOP : SOUTH;
		if (fog && !source.isSeen(x, y, z) && !(top ? source.isSeen(x, y + 1, z) : source.isSeen(x, y, z + 1))) {
			mesh.opaque.add(x, y, z, face, 0, 0, 0, UNKNOWN, lo, hi);
			return;
		}
		int color = solidColor(helper, id, x, y, z, top, looks.emission[id], kind, style);
		int ao = top ? aoTop(helper, x, y + 1, z) : aoSouth(helper, x, y, z + 1);
		// The sky view keeps only the block light, which lights the map at night (TileBaker.outBlockLight).
		int light = lit ? (top ? light(helper.reader, x, y + 1, z) : light(helper.reader, x, y, z + 1))
			: (top ? helper.reader.blockLight(x, y + 1, z) : helper.reader.blockLight(x, y, z + 1)) << 16;
		if (mesh.depthLook && !top) {
			light |= helper.wallDepth(x, y, z) << 8;
		}
		int flags = kind == BlockLooks.LAVA ? LAVA : 0;
		if (mesh.overlays != null && kind == BlockLooks.SOLID) {
			flags |= overlayFlags(mesh, helper.reader, looks, id, x, y, z, top);
		}
		mesh.opaque.add(x, y, z, face, color, ao, light, flags, lo, hi);
		if (mesh.lifted) {
			// TileBaker.shade reads the fog where the ray lands: on a top face its plane, which for a
			// cube is the bottom of the cell above, and on a south face its plane in z, below its top.
			double unit = ShapeFaces.UNIT;
			if (top) {
				double fy = y + (hi >> 8 & 255) / unit;
				mesh.opaque.haze(fogAt(helper, x, fy, z), fogAt(helper, x + 1, fy, z), fogAt(helper, x, fy, z + 1), fogAt(helper, x + 1, fy, z + 1));
			} else {
				double fy = y + 0.5;
				double fz = z + (hi >> 16 & 255) / unit;
				int west = fogAt(helper, x, fy, fz);
				int east = fogAt(helper, x + 1, fy, fz);
				mesh.opaque.haze(west, east, west, east);
			}
		}
	}

	/** TileBaker's blend of the biome fogs at a point, as sRGB bytes, red lowest. */
	private static int fogAt(TileBaker helper, double x, double y, double z) {
		helper.fogBlend(x, y, z);
		return srgb(helper.hazeR) | srgb(helper.hazeG) << 8 | srgb(helper.hazeB) << 16;
	}

	/** TileBaker.overlayBits for a face, all but the chunk lines, which depend on where in the block a pixel is. */
	private static int overlayFlags(Mesh mesh, VoxelReader reader, BlockLooks looks, int id, int x, int y, int z, boolean top) {
		int flags = OVERLAY;
		Overlays overlays = mesh.overlays;
		Long seed = overlays.slimeSeed();
		if (seed != null) {
			int cx = x >> 4;
			int cz = z >> 4;
			dev.diorama.Facing facing = Projection.facing();
			if (mesh.slimeChunks.computeIfAbsent(((long) cx << 32) | (cz & 0xFFFFFFFFL), k -> Overlays.slimeChunk(seed, facing.worldBlockX(cx, cz), facing.worldBlockZ(cx, cz)))) {
				flags |= SLIME;
			}
		}
		// A dot in the middle of each top face a monster could spawn on, by the tracer's rule.
		if (overlays.spawnSpots() && top && looks.spawnOn[id]) {
			int feet = reader.state(x, y + 1, z);
			int head = reader.state(x, y + 2, z);
			if (feet != VoxelReader.UNKNOWN && head != VoxelReader.UNKNOWN && looks.spawnIn[feet] && looks.spawnIn[head]
				&& reader.blockLight(x, y + 1, z) == 0) {
				flags |= reader.skyLight(x, y + 1, z) <= Overlays.DAYLIGHT_SPAWN_LIMIT ? SPAWN_ANY_TIME : SPAWN_AT_NIGHT;
			}
		}
		return flags;
	}

	/** Sky light in the top byte, block light in the next: the open cell a face looks into. */
	private static int light(VoxelReader reader, int x, int y, int z) {
		return reader.skyLight(x, y, z) << 24 | reader.blockLight(x, y, z) << 16;
	}

	/**
	 * Copies the solid bits of every section a sky ray from the camera's faces can reach, as the
	 * tracer reads them, for the GPU to cast the same rays.
	 */
	private static void voxels(Mesh mesh, TileBaker helper, VoxelReader reader, int fx0, int fx1, int fy0, int fy1, int fz0, int fz1) {
		int reach = TileBaker.SKY_REACH + 1;
		// Where the shadow map's caster is past the shadow's reach, the GPU walks shadow rays
		// through the table too (see bake.fsh), as far as the tracer's go toward the sun: west, a
		// little south, and up.
		double elevation = Math.toRadians(MapStyle.get().sunElevation());
		double flat = TileBaker.SHADOW_DISTANCE * Math.cos(elevation);
		int west = Math.max(reach, (int) Math.ceil(flat * -TileBaker.SUN_AZIMUTH_X) + 2);
		int south = Math.max(reach, (int) Math.ceil(flat * TileBaker.SUN_AZIMUTH_Z) + 2);
		int up = Math.max(reach, (int) Math.ceil(TileBaker.SHADOW_DISTANCE * Math.sin(elevation)) + 2);
		int sx0 = (fx0 - west) >> 4;
		int sx1 = (fx1 + reach) >> 4;
		int sz0 = (fz0 - reach) >> 4;
		int sz1 = (fz1 + south) >> 4;
		int sy0 = (fy0 - 2) >> 4;
		int sy1 = (Math.min(fy1 + up, mesh.rayTop) + 1) >> 4;
		int nx = sx1 - sx0 + 1;
		int ny = sy1 - sy0 + 1;
		int nz = sz1 - sz0 + 1;
		int tableSize = nx * ny * nz;
		int[] out = new int[tableSize + 128 * 64];
		int next = tableSize;
		for (int sy = sy0; sy <= sy1; sy++) {
			for (int sz = sz0; sz <= sz1; sz++) {
				for (int sx = sx0; sx <= sx1; sx++) {
					int index = ((sy - sy0) * nz + (sz - sz0)) * nx + (sx - sx0);
					long[] mask = reader.solidSection(sx, sy, sz);
					if (mask == null) {
						out[index] = SECTION_UNKNOWN;
						continue;
					}
					if (mesh.lifted) {
						mask = withoutLid(mask, helper, sx, sy, sz);
					}
					if (VoxelReader.nothingSolid(mask)) {
						out[index] = SECTION_EMPTY;
						continue;
					}
					// On the surface the GPU's shadow walk passes smaller blocks (see bake.fsh), so mark them.
					long[] partial = mesh.lifted ? null : reader.partialSection(sx, sy, sz);
					boolean marked = partial != null && !VoxelReader.nothingSolid(partial);
					if (next + 256 > out.length) {
						out = java.util.Arrays.copyOf(out, out.length * 2);
					}
					out[index] = marked ? next | SECTION_PARTIAL : next;
					for (int w = 0; w < 64; w++) {
						out[next++] = (int) mask[w];
						out[next++] = (int) (mask[w] >>> 32);
					}
					if (marked) {
						for (int w = 0; w < 64; w++) {
							out[next++] = (int) partial[w];
							out[next++] = (int) (partial[w] >>> 32);
						}
					}
				}
			}
		}
		mesh.voxels = out;
		mesh.voxelCount = next;
		mesh.voxelOrigin[0] = sx0 - (mesh.originX >> 4);
		mesh.voxelOrigin[1] = sy0;
		mesh.voxelOrigin[2] = sz0;
		mesh.voxelSize[0] = nx;
		mesh.voxelSize[1] = ny;
		mesh.voxelSize[2] = nz;
	}

	/** A section's solid bits less the rock lifted off its columns (TileBaker.lid), which lets light through. */
	private static long[] withoutLid(long[] mask, TileBaker helper, int sx, int sy, int sz) {
		long[] out = null;
		int y0 = sy << 4;
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				for (int ly = Math.max(0, helper.lid((sx << 4) + lx, (sz << 4) + lz) - y0); ly < 16; ly++) {
					int i = (ly << 8) | (lz << 4) | lx;
					if ((mask[i >> 6] >>> i & 1) != 0) {
						if (out == null) {
							out = mask.clone();
						}
						out[i >> 6] &= ~(1L << i);
					}
				}
			}
		}
		return out == null ? mask : out;
	}

	/**
	 * Sun-facing faces of solid blocks that can shade this tile: west of it, since the sun is in
	 * the west, and a little south. Each column is scanned from its top down to the lowest face
	 * the camera sees.
	 */
	private static void casters(Mesh mesh, TileBaker helper, VoxelReader reader, BlockLooks looks, MapStyle style, int x0, int x1, double minV, double maxV, int minY) {
		boolean lifted = mesh.lifted;
		double elevation = Math.toRadians(style.sunElevation());
		double flat = TileBaker.SHADOW_DISTANCE * Math.cos(elevation);
		int reachX = (int) Math.ceil(flat * 0.878) + 1;
		int reachZ = (int) Math.ceil(flat * 0.479) + 1;
		int reachY = (int) Math.ceil(TileBaker.SHADOW_DISTANCE * Math.sin(elevation)) + 1;
		int cx0 = x0 - reachX;
		int cx1 = x1 + 2;
		// With nothing solid in view the region still has to exist: it places the tile's origin.
		int bottom = mesh.lowestY == Integer.MAX_VALUE ? Math.max(minY, mesh.topY - 1) : Math.max(minY, mesh.lowestY);
		int cz0 = zLow(minV, bottom, mesh.sin, mesh.cos) - 1;
		int cz1 = zHigh(maxV, mesh.topY, mesh.sin, mesh.cos) + reachZ;
		int highest = Integer.MIN_VALUE;
		for (int x = cx0; x <= cx1 && mesh.lowestY != Integer.MAX_VALUE; x++) {
			// The chunk the last z was in and its caster faces: sixteen z a chunk.
			int casterChunkZ = Integer.MIN_VALUE;
			ChunkBakeData.CasterChunk casterChunk = null;
			for (int z = cz0; z <= cz1; z++) {
				ChunkSnapshot chunk = reader.snapshot(x, z);
				if (chunk == null) {
					continue;
				}
				// One above the column's top block: nothing above it casts, nor under a roof the rock
				// lifted off the column.
				int top = Math.min(chunk.surface(x & 15, z & 15) + 1, mesh.topY + reachY);
				if (lifted) {
					top = Math.min(top, helper.lid(x, z) - 1);
				} else {
					if (z >> 4 != casterChunkZ) {
						casterChunkZ = z >> 4;
						casterChunk = reader.casterChunk(x >> 4, casterChunkZ, bottom);
					}
					if (casterChunk != null) {
						int column = ((z & 15) << 4) | (x & 15);
						highest = casters(mesh, looks, casterChunk.faces(), casterChunk.offsets()[column], casterChunk.offsets()[column + 1], x, z, top, bottom,
							reader.minY(), highest);
						continue;
					}
				}
				long[] mayCast = null;
				int castY = Integer.MIN_VALUE;
				for (int y = top; y >= bottom; y--) {
					if (!lifted) {
						// Most of a column is rock under rock, which casts nothing: skip those blocks
						// on a bit each instead of looking at them and their neighbours.
						if (y >> 4 != castY) {
							castY = y >> 4;
							mayCast = reader.casterSection(x >> 4, castY, z >> 4);
						}
						int i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
						if (mayCast != null && (mayCast[i >> 6] >>> i & 1) == 0) {
							continue;
						}
					}
					int solid = reader.solid(x, y, z);
					if (solid == 2) {
						// Smaller than a cube: shadow rays only stop at its boxes (TileBaker.meetsShape).
						int id = reader.state(x, y, z);
						int shape = id < 0 ? 0 : looks.shape[id];
						if (shape != 0) {
							int[] boxes = looks.faces(shape).boxes;
							for (int b = 0; b < boxes.length; b += 2) {
								mesh.casters.add(x, y, z, TOP, 0, 0, 0, 0, boxes[b], boxes[b + 1]);
								mesh.casters.add(x, y, z, WEST, 0, 0, 0, 0, boxes[b], boxes[b + 1]);
								mesh.casters.add(x, y, z, SOUTH, 0, 0, 0, 0, boxes[b], boxes[b + 1]);
							}
							highest = Math.max(highest, y + 1);
						}
						continue;
					}
					if (solid != 1) {
						continue;
					}
					if (reader.solid(x, y + 1, z) != 1 || lifted && y + 1 >= helper.lid(x, z)) {
						mesh.casters.add(x, y, z, TOP, 0, 0, 0, 0);
						highest = Math.max(highest, y + 1);
					}
					if (reader.solid(x - 1, y, z) != 1 || lifted && y >= helper.lid(x - 1, z)) {
						mesh.casters.add(x, y, z, WEST, 0, 0, 0, 0);
					}
					if (reader.solid(x, y, z + 1) != 1 || lifted && y >= helper.lid(x, z + 1)) {
						mesh.casters.add(x, y, z, SOUTH, 0, 0, 0, 0);
					}
				}
			}
		}
		mesh.casterX0 = cx0;
		mesh.casterX1 = cx1 + 1;
		mesh.casterZ0 = cz0;
		mesh.casterZ1 = cz1 + 1;
		mesh.casterY0 = bottom;
		mesh.casterY1 = Math.max(highest, mesh.topY);
	}

	/**
	 * Adds a column's caster faces from {@code top} down to {@code bottom}, from what {@link VoxelReader#casterChunk}
	 * found ({@code faces} from {@code from} to {@code to}): the faces the scan above adds, in its order.
	 * Returns the new {@code highest}.
	 */
	private static int casters(Mesh mesh, BlockLooks looks, int[] faces, int from, int to, int x, int z, int top, int bottom, int minY, int highest) {
		Quads casters = mesh.casters;
		for (int k = from; k < to; k++) {
			int entry = faces[k];
			int y = (entry >> 4) + minY;
			int bits = entry & 15;
			if (y < bottom) {
				break;
			}
			if (bits == ChunkBakeData.SHAPE) {
				int id = faces[++k];
				if (y > top) {
					continue;
				}
				int[] boxes = looks.faces(looks.shape[id]).boxes;
				for (int b = 0; b < boxes.length; b += 2) {
					casters.add(x, y, z, TOP, 0, 0, 0, 0, boxes[b], boxes[b + 1]);
					casters.add(x, y, z, WEST, 0, 0, 0, 0, boxes[b], boxes[b + 1]);
					casters.add(x, y, z, SOUTH, 0, 0, 0, 0, boxes[b], boxes[b + 1]);
				}
				highest = Math.max(highest, y + 1);
				continue;
			}
			if (y > top) {
				continue;
			}
			if ((bits & ChunkBakeData.TOP) != 0) {
				casters.add(x, y, z, TOP, 0, 0, 0, 0);
				highest = Math.max(highest, y + 1);
			}
			if ((bits & ChunkBakeData.WEST) != 0) {
				casters.add(x, y, z, WEST, 0, 0, 0, 0);
			}
			if ((bits & ChunkBakeData.SOUTH) != 0) {
				casters.add(x, y, z, SOUTH, 0, 0, 0, 0);
			}
		}
		return highest;
	}

	/**
	 * Sorts faces by camera depth, nearest first: plants and glass are added up in the order the
	 * tracer's ray meets them, each weighted by how much light the ones in front let through.
	 */
	private static void sortNearToFar(Quads quads, double sin, double cos) {
		int n = quads.count;
		if (n < 2) {
			return;
		}
		long[] order = new long[n];
		for (int q = 0; q < n; q++) {
			int i = q * STRIDE;
			// Where the face's piece is: in its plane, in the middle.
			int lo = quads.get(i + 8);
			int hi = quads.get(i + 9);
			double y0 = (lo >> 8 & 255) / (double) ShapeFaces.UNIT;
			double z0 = (lo >> 16 & 255) / (double) ShapeFaces.UNIT;
			double y1 = (hi >> 8 & 255) / (double) ShapeFaces.UNIT;
			double z1 = (hi >> 16 & 255) / (double) ShapeFaces.UNIT;
			double y = quads.get(i + 1);
			double z = quads.get(i + 2);
			// A branch, not a boolean: Java 21.0.8 and older can crash compiling `face == TOP` kept as
			// a value (JDK-8358534), and Minecraft's launcher gives 1.20.5 to 1.21.11 Java 21.0.7.
			if (quads.get(i + 3) == TOP) {
				y += y1;
				z += (z0 + z1) / 2;
			} else {
				y += (y0 + y1) / 2;
				z += z1;
			}
			double depth = -(sin * y + cos * z);
			long d = (long) (depth * 1024);
			order[q] = (d << 24) | q;
		}
		java.util.Arrays.sort(order);
		int[] faces = new int[n];
		for (int q = 0; q < n; q++) {
			faces[q] = (int) (order[q] & 0xFFFFFF);
		}
		quads.reorder(faces);
	}

	private static int solidColor(TileBaker helper, int id, int x, int y, int z, boolean top, int emission, byte kind, MapStyle style) {
		helper.albedo(id, x, z, top);
		float r = helper.albedoR;
		float g = helper.albedoG;
		float b = helper.albedoB;
		float variation = style.blockVariation();
		if (variation > 0) {
			float nudge = 1 + variation * TileBaker.blockNoise(x, y, z);
			r *= nudge;
			g *= nudge;
			b *= nudge;
		}
		if (kind == BlockLooks.LAVA) {
			// In the Nether's fog a lava sea reads as one glow rather than a field of speckles.
			float crust = 1 + (helper.lifted ? 0.1F : 0.3F) * TileBaker.blockNoise(x, y, z);
			r *= crust;
			g *= crust * crust;
			b *= crust;
		}
		// Emission rides in alpha, 0..15 scaled up.
		return encode(r, g, b, emission * 17);
	}

	/** Linear colour, halved for headroom, to sRGB bytes; alpha as given. */
	private static int encode(float r, float g, float b, int alpha) {
		return (alpha << 24) | (srgb(r * 0.5F) << 16) | (srgb(g * 0.5F) << 8) | srgb(b * 0.5F);
	}

	private static int srgb(float linear) {
		double v = Math.max(0, Math.min(1, linear));
		double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
		return (int) Math.round(s * 255);
	}

	/** The four corners of a face, a byte each from the top. */
	private static int pack(float a, float b, float c, float d) {
		return (byte4(a) << 24) | (byte4(b) << 16) | (byte4(c) << 8) | byte4(d);
	}

	private static int byte4(float v) {
		return Math.round(Math.max(0, Math.min(1, v)) * 255);
	}

	/** Corner occlusion on a top face, from the eight blocks around the cell {@code (x, y, z)} above it: nw, ne, sw, se. */
	private static int aoTop(TileBaker h, int x, int y, int z) {
		boolean n = h.occludes(x, y, z - 1);
		boolean s = h.occludes(x, y, z + 1);
		boolean w = h.occludes(x - 1, y, z);
		boolean e = h.occludes(x + 1, y, z);
		return pack(
			TileBaker.corner(w, n, h.occludes(x - 1, y, z - 1)),
			TileBaker.corner(e, n, h.occludes(x + 1, y, z - 1)),
			TileBaker.corner(w, s, h.occludes(x - 1, y, z + 1)),
			TileBaker.corner(e, s, h.occludes(x + 1, y, z + 1))
		);
	}

	/** Same for a south face; {@code z} is the open layer in front of it: top west, top east, bottom west, bottom east. */
	private static int aoSouth(TileBaker h, int x, int y, int z) {
		boolean up = h.occludes(x, y + 1, z);
		boolean down = h.occludes(x, y - 1, z);
		boolean w = h.occludes(x - 1, y, z);
		boolean e = h.occludes(x + 1, y, z);
		return pack(
			TileBaker.corner(w, up, h.occludes(x - 1, y + 1, z)),
			TileBaker.corner(e, up, h.occludes(x + 1, y + 1, z)),
			TileBaker.corner(w, down, h.occludes(x - 1, y - 1, z)),
			TileBaker.corner(e, down, h.occludes(x + 1, y - 1, z))
		);
	}
}
