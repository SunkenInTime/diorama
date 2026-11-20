package dev.diorama.bake;

import dev.diorama.Projection;
import dev.diorama.data.ChunkSnapshot;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Finds the block a map pixel shows, by walking the same camera ray the baker traces. The map is
 * a top view, so a screen point alone doesn't say how high the ground is; this does.
 *
 * <p>One pick serves the map points of one tile, where rays start at the same height and the
 * chunks read stay decoded from one point to the next.
 */
public final class MapPick {
	/** {@link #pick} found a block to stand on: {@link #y} and {@link #z} say where. */
	public static final int FOUND = 0;
	/** The ray found nothing to stand on, or fog of war hides it. */
	public static final int NOTHING = 1;
	/** The ray ran into a chunk the map has no data for. */
	public static final int UNKNOWN = 2;

	private final ChunkSource source;
	private final BlockLooks looks;
	private final VoxelReader reader;
	private final boolean fogOfWar;
	private final boolean underRoof;
	private final boolean cave;
	private final boolean roofless;
	private final int startY;
	private final int limitY;
	private final int base;
	private final int rockLimit;
	private final boolean raised;
	/** A cave slice in the depth look; see {@link TileBaker#depthLook}. */
	private final boolean depthLook;
	/** Builds above an ordinary world's top, as the baker has them; null when no ray starts up there. */
	private final @Nullable TallChunks tall;
	private final TileKey tile;
	/** The saved tile's spots, in the sky view: see {@link #passWhereSaved}. */
	private int @Nullable [] savedSpots;
	/** Where the last {@link #FOUND} pick stands; its x is the picked column. */
	public int y;
	public int z;
	/** The last pick's first and last z, which bound the blocks it read, and whether one was in a chunk the map has no data for. */
	int firstZ;
	int lastZ;
	boolean readUnknown;

	/** A pick for map points in {@code tile}, which must be a level-0 tile. */
	public MapPick(ChunkSource source, BlockLooks looks, TileKey tile, boolean fogOfWar) {
		this(source, looks, tile, fogOfWar, false);
	}

	/**
	 * A pick whose rays start at the top of the world, not at the top of the chunks around the
	 * tile: for a source that loads each chunk when a ray first reaches it, where looking at them
	 * all first would load hundreds. The rays walk more air first and reach the same blocks, unless
	 * something taller than the map had seen when it drew them now stands in front.
	 */
	public static MapPick fromWorldTop(ChunkSource source, BlockLooks looks, TileKey tile, boolean fogOfWar) {
		return new MapPick(source, looks, tile, fogOfWar, true);
	}

	private MapPick(ChunkSource source, BlockLooks looks, TileKey tile, boolean fogOfWar, boolean fromWorldTop) {
		this.source = source;
		this.looks = looks;
		this.tile = tile;
		this.reader = new VoxelReader(source, looks);
		this.fogOfWar = fogOfWar;
		this.underRoof = source.roof() != ChunkSource.NO_ROOF;
		this.cave = tile.isCave();
		this.roofless = !this.cave && this.underRoof;
		// Start where the baker starts for this tile, so whatever the map draws can be picked.
		this.base = this.cave ? Math.min(tile.layer(), source.maxY()) : Integer.MIN_VALUE;
		this.depthLook = this.cave && !this.underRoof && source.depthCaves();
		this.raised = this.depthLook && tile.lod() == 0;
		this.startY = this.cave ? Math.min(this.base + (this.raised ? CaveCut.LIFT : 0), source.maxY())
			: this.roofless ? Math.min(source.roof(), source.maxY()) : fromWorldTop ? source.maxY() : TileBaker.localTop(tile, source, 0);
		this.limitY = this.cave ? Math.max(source.minY(), this.base - TileBaker.CAVE_DEPTH) : source.minY();
		this.rockLimit = this.depthLook ? this.base - TileBaker.CAVE_ROCK : Integer.MIN_VALUE;
		this.tall = this.cave || this.roofless || fromWorldTop ? null : TallChunks.around(tile, source, this.startY);
	}

	/** Where this pick's rays start, and whether builds above the world's top move that per ray. */
	public int startY() {
		return this.startY;
	}

	public boolean hasTall() {
		return this.tall != null;
	}

	/**
	 * Whether a pick reads nothing but blocks of its own column along its ray, and the saved spots
	 * where those are in chunks the map has no data for: the sky view outside a roof, from one height.
	 */
	boolean columnOnly() {
		return !this.cave && !this.roofless && this.tall == null && !this.raised;
	}

	int limitY() {
		return this.limitY;
	}

	Looks looks() {
		return this.looks;
	}

	/** The chunk holding a column, as this pick reads it. */
	@Nullable ChunkSnapshot snapshot(int x, int z) {
		return this.reader.snapshot(x, z);
	}

	/**
	 * Lets rays through chunks the map has no data for above where the saved tile's rays stood,
	 * as the baker does (see {@link TileSpots#passedWhenSaved}). Sky view only.
	 */
	public MapPick passWhereSaved(int @Nullable [] spots) {
		this.savedSpots = this.cave || this.roofless || this.tile.lod() != 0 ? null : spots;
		return this;
	}

	/**
	 * The position just above the first solid block or water surface the ray through map point
	 * {@code (u, v)} reaches, or null where the map has no data.
	 */
	public static @Nullable BlockPos standingSpot(ChunkSource source, BlockLooks looks, double u, double v, int layer, boolean fogOfWar) {
		TileKey tile = new TileKey(layer, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
		MapPick pick = new MapPick(source, looks, tile, fogOfWar);
		return pick.pick(u, v) == FOUND ? new BlockPos((int) Math.floor(u), pick.y, pick.z) : null;
	}

	/**
	 * Walks the ray through map point {@code (u, v)}, which must lie in this pick's tile, down to
	 * the first solid block or water surface. Returns {@link #FOUND}, {@link #NOTHING} or
	 * {@link #UNKNOWN}.
	 */
	public int pick(double u, double v) {
		double sin = Projection.sin();
		double cos = Projection.cos();
		int x = (int) Math.floor(u);
		int top = this.tall == null ? this.startY : this.tall.rayStart(x, v, sin, cos);
		double z0 = (v + top * cos) / sin;
		int by = top - 1;
		int bz = (int) Math.floor(z0);
		double tMaxY = 1 / sin;
		double tMaxZ = (z0 - bz) / cos;
		this.firstZ = bz;
		this.lastZ = bz;
		this.readUnknown = false;
		boolean skippingRock = this.cave || this.roofless;
		CaveCut cut = this.raised ? new CaveCut(this.reader, this.base, x, (int) Math.floor((v + this.limitY * cos) / sin) - 2, x, bz + 1) : null;
		while (by >= this.limitY && !(skippingRock && by < this.rockLimit)) {
			if (cut != null && by >= cut.at(x, bz)) {
				// Cut away: rock, as the baker has it.
				skippingRock = true;
				if (tMaxY < tMaxZ) {
					by--;
					tMaxY += 1 / sin;
				} else {
					bz--;
					tMaxZ += 1 / cos;
				}
				continue;
			}
			int id = this.reader.state(x, by, bz);
			this.lastZ = bz;
			if (id == VoxelReader.UNKNOWN) {
				this.readUnknown = true;
				if ((this.tall == null || by < this.tall.missingTop) && !TileSpots.passedWhenSaved(this.tile, this.savedSpots, x, by, bz)) {
					return UNKNOWN;
				}
				id = VoxelReader.AIR_ID;
			}
			byte kind = this.looks.kind[id];
			if (skippingRock && kind != BlockLooks.SOLID && !(this.underRoof && by + 1 < this.startY && this.reader.pocket(x, by, bz))) {
				skippingRock = false;
			}
			boolean underside = !skippingRock && (this.underRoof || this.depthLook) && (this.cave || this.roofless) && kind == BlockLooks.SOLID
				&& this.reader.ceilingUnderside(x, by, bz, this.reader.lid(x, bz, cut == null ? this.startY : cut.at(x, bz), this.limitY), this.limitY);
			// The baker passes through the underside of a ceiling once the ray has left the rock.
			if (!skippingRock && !underside && (kind == BlockLooks.SOLID || kind == BlockLooks.LAVA || kind == BlockLooks.WATER)) {
				// Fog of war: a cave the player hasn't seen can't be picked, or its floor would leak.
				if ((this.cave || this.roofless) && this.fogOfWar && !this.source.isSeen(x, by, bz) && !this.source.isSeen(x, by + 1, bz)) {
					return NOTHING;
				}
				this.y = by + 1;
				this.z = bz;
				return FOUND;
			}
			if (tMaxY < tMaxZ) {
				by--;
				tMaxY += 1 / sin;
			} else {
				bz--;
				tMaxZ += 1 / cos;
			}
		}
		return NOTHING;
	}
}
