package dev.diorama.data;

import dev.diorama.Facing;
import dev.diorama.bake.TallChunks;
import dev.diorama.mixin.BiomeManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

/**
 * A private copy of one chunk's blocks, light and biomes, safe to read from bake threads.
 * Taken on the client thread, which only copies; the biome tints are blended later, on the bake
 * thread that first needs them (see {@code BiomeTints}).
 */
public final class ChunkSnapshot {
	public final int chunkX;
	public final int chunkZ;
	public final int minSectionY;
	/** The blocks of a section that has more than one block state. Read only. */
	public interface Blocks {
		/** The block at a place in the section, in the chunk's own layout. */
		BlockState get(int x, int y, int z);

		/** Whether any block of the section may pass; false means none does. */
		boolean maybeHas(java.util.function.Predicate<BlockState> predicate);

		/** See {@link ChunkSnapshot#packed}. */
		@Nullable Packed packed();
	}

	/** A section's blocks in a copy of the game's own container. */
	private record Copied(PalettedContainer<BlockState> states) implements Blocks {
		@Override
		public BlockState get(int x, int y, int z) {
			return this.states.get(x, y, z);
		}

		@Override
		public boolean maybeHas(java.util.function.Predicate<BlockState> predicate) {
			return this.states.maybeHas(predicate);
		}

		@Override
		public @Nullable Packed packed() {
			return Sections.packed(this.states);
		}
	}

	/**
	 * What takes the memory: blocks per section (null for sections that are only air or a single
	 * block) and light. Never changes once the snapshot is made. One that was packed also has the
	 * blended tints the snapshot had by then, which went into the pack with it.
	 */
	record Body(@Nullable Blocks[] blocks, @Nullable DataLayer[] blockLight, @Nullable DataLayer[] skyLight, @Nullable Tints tints) {
	}

	/** Which sections have block states of their own: neither only air nor a single block. */
	private final boolean[] mixed;
	/** The blocks and light, until {@link #pack} packs them; a view reads its chunk's. */
	private volatile @Nullable Body body;
	/** The blocks and light packed; null until then. */
	private volatile @Nullable ChunkPack pack;
	/** The packed blocks and light as last unpacked, for as long as something still reads them. */
	private volatile java.lang.ref.@Nullable WeakReference<Body> unpacked;
	/** Set by the first {@link #startPacking}. */
	private final java.util.concurrent.atomic.AtomicBoolean packing = new java.util.concurrent.atomic.AtomicBoolean();
	/** The one block filling a section, for sections made of a single block. */
	private final @Nullable BlockState[] uniform;
	private final boolean hasSkyLight;
	/**
	 * How far from a column its blended tint reads biomes: the game's largest blend radius, 7, plus
	 * the 5 blocks its biome lookup fuzzes positions by. Never further than a neighbouring chunk.
	 */
	public static final int BIOME_REACH = 12;
	/**
	 * Biome sections of this chunk and its eight neighbours as the game had them when the snapshot
	 * was taken, indexed {@code (dx + 1) * 3 + dz + 1}; null where the game had no chunk.
	 */
	private final @Nullable PalettedContainerRO<Holder<Biome>>[] @Nullable [] biomes;
	/** Top block y per column, indexed {@code (z << 4) | x}: where the column's tints are sampled. */
	private final int[] surface = new int[256];
	/** The hashed seed the client's biome lookups fuzz positions with. */
	public final long biomeZoomSeed;
	/** The player's biome blend radius when the snapshot was taken. */
	public final int blendRadius;
	/** The biome the game assumes where no chunk is loaded. */
	public final Holder<Biome> fallbackBiome;
	/**
	 * Blended biome tints, worked out on a bake thread the first time a bake needs them. Those
	 * worked out before the snapshot was packed are in the pack; see {@link #tints()}.
	 */
	private volatile @Nullable Tints tints;
	/** Highest non-air block y, or {@code Integer.MIN_VALUE} when the chunk is empty. */
	public final int maxY;
	/** A conservative low point of the visible surface, used to bound tile invalidation. */
	public final int floorY;
	/** Per section, the filled copycats in it; null when the chunk has none, as nearly every chunk. */
	private final @Nullable SectionCopycats @Nullable [] copycats;
	/** Which way a turned view looks, north for the chunk itself; see {@link #turned(Facing)}. */
	private final Facing facing;
	private final @Nullable ChunkSnapshot base;
	/** The views made so far, by turns. */
	private final @Nullable ChunkSnapshot @Nullable [] views;

	/** This chunk in turned space. Bakes of neighbouring tiles share the view and its tints. Any thread. */
	public ChunkSnapshot turned(Facing facing) {
		if (facing == Facing.NORTH) {
			return this;
		}
		ChunkSnapshot view = this.views[facing.turns];
		if (view == null) {
			// Two bakes may both make one; either will do.
			view = new ChunkSnapshot(this, facing);
			this.views[facing.turns] = view;
		}
		return view;
	}

	private ChunkSnapshot(ChunkSnapshot base, Facing facing) {
		this.base = base;
		this.facing = facing;
		this.views = null;
		this.chunkX = facing.blockX(base.chunkX, base.chunkZ);
		this.chunkZ = facing.blockZ(base.chunkX, base.chunkZ);
		this.minSectionY = base.minSectionY;
		this.mixed = base.mixed;
		this.uniform = base.uniform;
		this.hasSkyLight = base.hasSkyLight;
		this.biomes = base.biomes;
		this.biomeZoomSeed = base.biomeZoomSeed;
		this.blendRadius = base.blendRadius;
		this.fallbackBiome = base.fallbackBiome;
		this.maxY = base.maxY;
		this.floorY = base.floorY;
		this.copycats = turnedCopycats(base.copycats, facing);
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				this.surface[(z << 4) | x] = base.surface(this.localX(x, z), this.localZ(x, z));
			}
		}
	}

	/** The world's column in this chunk for a column of this view. */
	private int localX(int x, int z) {
		return this.facing.worldBlockX(x, z) & 15;
	}

	private int localZ(int x, int z) {
		return this.facing.worldBlockZ(x, z) & 15;
	}

	private static @Nullable SectionCopycats @Nullable [] turnedCopycats(@Nullable SectionCopycats @Nullable [] copycats, Facing facing) {
		if (copycats == null) {
			return null;
		}
		SectionCopycats[] out = new SectionCopycats[copycats.length];
		for (int i = 0; i < copycats.length; i++) {
			if (copycats[i] != null) {
				int[] at = copycats[i].at().clone();
				for (int k = 0; k < at.length; k++) {
					int x = at[k] & 15;
					int z = at[k] >> 4 & 15;
					at[k] = at[k] & ~0xFF | (facing.blockZ(x, z) & 15) << 4 | facing.blockX(x, z) & 15;
				}
				BlockState[] materials = copycats[i].material().clone();
				for (int k = 0; k < materials.length; k++) {
					materials[k] = facing.turned(materials[k]);
				}
				out[i] = new SectionCopycats(at, materials);
			}
		}
		return out;
	}

	/** The block at a place in the view, from a section's {@link #section} blocks. */
	public BlockState state(Blocks states, int x, int y, int z) {
		return this.facing.turned(states.get(this.localX(x, z), y, this.localZ(x, z)));
	}

	/** The chunk itself, of a turned view. */
	public ChunkSnapshot chunk() {
		return this.base != null ? this.base : this;
	}

	/** A block as this view has it. */
	public BlockState turned(BlockState state) {
		return this.facing.turned(state);
	}

	@SuppressWarnings("unchecked")
	private ChunkSnapshot(LevelChunk chunk, ClientLevel level) {
		this.facing = Facing.NORTH;
		this.base = null;
		this.views = new ChunkSnapshot[4];
		this.chunkX = chunk.getPos().x();
		this.chunkZ = chunk.getPos().z();
		this.minSectionY = chunk.getMinSectionY();
		LevelChunkSection[] sections = chunk.getSections();
		PalettedContainer<BlockState>[] ownStates = new PalettedContainer[sections.length];
		this.uniform = new BlockState[sections.length];
		// The game never writes into a section's biome container: new biomes arrive in a new one,
		// swapped in. So holding the containers keeps the biomes as they are now, without copying.
		this.biomes = new PalettedContainerRO[9][];
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				ChunkAccess around = dx == 0 && dz == 0 ? chunk : level.getChunkSource().getChunk(this.chunkX + dx, this.chunkZ + dz, ChunkStatus.BIOMES, false);
				if (around != null) {
					LevelChunkSection[] aroundSections = around.getSections();
					PalettedContainerRO<Holder<Biome>>[] biomes = new PalettedContainerRO[aroundSections.length];
					for (int i = 0; i < aroundSections.length; i++) {
						biomes[i] = aroundSections[i] == null ? null : aroundSections[i].getBiomes();
					}
					this.biomes[(dx + 1) * 3 + dz + 1] = biomes;
				}
			}
		}
		this.biomeZoomSeed = ((BiomeManagerAccessor) level.getBiomeManager()).diorama$biomeZoomSeed();
		this.blendRadius = Minecraft.getInstance().options.biomeBlendRadius().get();
		this.fallbackBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
		this.hasSkyLight = level.dimensionType().hasSkyLight();
		DataLayer[] blockLight = new DataLayer[sections.length];
		DataLayer[] skyLight = new DataLayer[sections.length];
		LevelLightEngine light = level.getLightEngine();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section != null && !section.hasOnlyAir()) {
				PalettedContainer<BlockState> states = section.getStates();
				// A single-block section's palette copies as itself, and a later chunk packet can
				// rewrite it in place. Keep just the block so the snapshot never changes under a bake.
				BlockState single = Sections.singleValue(states);
				if (single != null) {
					this.uniform[i] = single;
				} else {
					ownStates[i] = states.copy();
				}
			}
			SectionPos pos = SectionPos.of(this.chunkX, this.minSectionY + i, this.chunkZ);
			DataLayer block = light.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
			DataLayer sky = light.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
			blockLight[i] = block == null ? null : block.copy();
			skyLight[i] = sky == null ? null : sky.copy();
		}
		this.mixed = mixed(ownStates);
		this.body = new Body(copied(ownStates), blockLight, skyLight, null);

		int top = Integer.MIN_VALUE;
		int floor = Integer.MAX_VALUE;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				int solid = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				top = Math.max(top, surface);
				floor = Math.min(floor, solid);
				this.surface[(z << 4) | x] = surface;
			}
		}
		this.maxY = top <= chunk.getMinY() ? Integer.MIN_VALUE : top;
		this.floorY = floorY(floor, chunk.getMinY());
		this.copycats = Copycats.INSTALLED ? copycats(chunk, sections.length) : null;
	}

	/**
	 * Just the blocks of a chunk from the singleplayer server, for {@link WorldSavePick}: no light,
	 * biomes or copycats, so nothing to draw with. A chunk the world never finished generating
	 * reads as empty, as the client never had it.
	 */
	@SuppressWarnings("unchecked")
	private ChunkSnapshot(ChunkAccess chunk, ClientLevel level, boolean full) {
		this.facing = Facing.NORTH;
		this.base = null;
		this.views = new ChunkSnapshot[4];
		this.chunkX = chunk.getPos().x();
		this.chunkZ = chunk.getPos().z();
		this.minSectionY = chunk.getMinSectionY();
		LevelChunkSection[] sections = chunk.getSections();
		PalettedContainer<BlockState>[] ownStates = new PalettedContainer[sections.length];
		this.uniform = new BlockState[sections.length];
		for (int i = 0; full && i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section != null && !section.hasOnlyAir()) {
				PalettedContainer<BlockState> states = section.getStates();
				BlockState single = Sections.singleValue(states);
				if (single != null) {
					this.uniform[i] = single;
				} else {
					ownStates[i] = states.copy();
				}
			}
		}
		this.mixed = mixed(ownStates);
		this.body = new Body(copied(ownStates), new DataLayer[sections.length], new DataLayer[sections.length], null);
		this.biomes = new PalettedContainerRO[9][];
		this.biomeZoomSeed = 0;
		this.blendRadius = 0;
		this.fallbackBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
		this.hasSkyLight = false;
		int top = Integer.MIN_VALUE;
		for (int z = 0; full && z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
				top = Math.max(top, surface);
				this.surface[(z << 4) | x] = surface;
			}
		}
		this.maxY = top <= chunk.getMinY() ? Integer.MIN_VALUE : top;
		this.floorY = chunk.getMinY();
		this.copycats = null;
	}

	/**
	 * The blocks of a chunk the singleplayer server read from the world's save; see the
	 * constructor. Call on the server thread.
	 */
	public static ChunkSnapshot fromServer(ChunkAccess chunk, ClientLevel level) {
		return new ChunkSnapshot(chunk, level, chunk.getPersistedStatus().isOrAfter(ChunkStatus.FULL));
	}

	/**
	 * A chunk read from the world's save, for places the client never loaded. {@code around} is it
	 * and its eight neighbours as read, indexed like {@link #biomes}, null where the save has none:
	 * only their biomes are used. Copycats keep their material in block entity data, which isn't
	 * read, so they show as the empty block.
	 */
	@SuppressWarnings("unchecked")
	private ChunkSnapshot(SavedChunk chunk, @Nullable SavedChunk[] around, Level level, int blendRadius) {
		this.facing = Facing.NORTH;
		this.base = null;
		this.views = new ChunkSnapshot[4];
		this.chunkX = chunk.chunkX();
		this.chunkZ = chunk.chunkZ();
		this.minSectionY = level.getMinSectionY();
		int count = chunk.states().length;
		PalettedContainer<BlockState>[] ownStates = new PalettedContainer[count];
		this.uniform = new BlockState[count];
		for (int i = 0; i < count; i++) {
			PalettedContainer<BlockState> states = chunk.states()[i];
			if (states != null) {
				// Read from the save for this snapshot alone: nothing else holds or changes it.
				BlockState single = Sections.singleValue(states);
				if (single == null) {
					ownStates[i] = states;
				} else if (!single.isAir()) {
					this.uniform[i] = single;
				}
			}
		}
		this.biomes = new PalettedContainerRO[9][];
		for (int i = 0; i < 9; i++) {
			if (around[i] != null) {
				this.biomes[i] = around[i].biomes();
			}
		}
		this.biomeZoomSeed = ((BiomeManagerAccessor) level.getBiomeManager()).diorama$biomeZoomSeed();
		this.blendRadius = blendRadius;
		this.fallbackBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
		this.hasSkyLight = level.dimensionType().hasSkyLight();
		this.mixed = mixed(ownStates);
		this.body = new Body(copied(ownStates), chunk.blockLight(), chunk.skyLight(), null);
		int top = Integer.MIN_VALUE;
		int floor = Integer.MAX_VALUE;
		for (int i = 0; i < 256; i++) {
			top = Math.max(top, chunk.surface()[i]);
			floor = Math.min(floor, chunk.solid()[i]);
			this.surface[i] = chunk.surface()[i];
		}
		this.maxY = top <= level.getMinY() ? Integer.MIN_VALUE : top;
		this.floorY = floorY(floor, level.getMinY());
		this.copycats = null;
	}

	/** A chunk read from the world's save, its tints blended as the player's options ask; see the constructor. */
	public static ChunkSnapshot fromSave(SavedChunk chunk, @Nullable SavedChunk[] around, Level level) {
		return new ChunkSnapshot(chunk, around, level, Minecraft.getInstance().options.biomeBlendRadius().get());
	}

	/** {@link #fromSave} with the tints blended over {@code blendRadius} blocks, where there are no options: on a server. */
	public static ChunkSnapshot fromSave(SavedChunk chunk, @Nullable SavedChunk[] around, Level level, int blendRadius) {
		return new ChunkSnapshot(chunk, around, level, blendRadius);
	}

	/** {@link #floorY} from the lowest motion-blocking height in the chunk. */
	private static int floorY(int solid, int minY) {
		// A build over the whole chunk, higher than any ground, hides nothing: the map tilts, so what's
		// under it shows from far to the south.
		if (solid == Integer.MAX_VALUE || solid > TallChunks.ORDINARY_TOP) {
			return minY;
		}
		// Water and shallow overhangs can hide blocks below the motion-blocking height.
		return solid - 24;
	}

	/** What the chunk's copycats are filled with, by section, or null if none are filled. */
	@SuppressWarnings("unchecked")
	private static @Nullable SectionCopycats @Nullable [] copycats(LevelChunk chunk, int sectionCount) {
		java.util.List<Integer>[] at = null;
		java.util.List<BlockState>[] materials = null;
		for (BlockEntity entity : chunk.getBlockEntities().values()) {
			BlockState material = Copycats.material(entity);
			if (material == null) {
				continue;
			}
			BlockPos pos = entity.getBlockPos();
			int section = (pos.getY() >> 4) - chunk.getMinSectionY();
			if (section < 0 || section >= sectionCount) {
				continue;
			}
			if (at == null) {
				at = new java.util.List[sectionCount];
				materials = new java.util.List[sectionCount];
			}
			if (at[section] == null) {
				at[section] = new java.util.ArrayList<>();
				materials[section] = new java.util.ArrayList<>();
			}
			at[section].add(((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15));
			materials[section].add(material);
		}
		if (at == null) {
			return null;
		}
		SectionCopycats[] out = new SectionCopycats[sectionCount];
		for (int i = 0; i < sectionCount; i++) {
			if (at[i] != null) {
				out[i] = new SectionCopycats(at[i].stream().mapToInt(Integer::intValue).toArray(), materials[i].toArray(new BlockState[0]));
			}
		}
		return out;
	}

	/**
	 * The filled copycats in one section: where each is, indexed {@code (y << 8) | (z << 4) | x}
	 * like the section's ids, and the block it's filled with.
	 */
	public record SectionCopycats(int[] at, BlockState[] material) {
	}

	/** The filled copycats in a section, or null if it has none. */
	public @Nullable SectionCopycats copycats(int index) {
		return this.copycats == null ? null : this.copycats[index];
	}

	/** What the copycat at this position in the chunk was filled with, or null if there's none filled. */
	public @Nullable BlockState copycatMaterial(int x, int y, int z) {
		int section = (y >> 4) - this.minSectionY;
		SectionCopycats in = this.copycats == null || section < 0 || section >= this.copycats.length ? null : this.copycats[section];
		if (in != null) {
			int at = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
			for (int i = 0; i < in.at.length; i++) {
				if (in.at[i] == at) {
					return in.material[i];
				}
			}
		}
		return null;
	}

	public static ChunkSnapshot take(LevelChunk chunk, ClientLevel level) {
		return new ChunkSnapshot(chunk, level);
	}

	private static @Nullable Blocks[] copied(@Nullable PalettedContainer<BlockState>[] states) {
		Blocks[] blocks = new Blocks[states.length];
		for (int i = 0; i < states.length; i++) {
			blocks[i] = states[i] == null ? null : new Copied(states[i]);
		}
		return blocks;
	}

	private static boolean[] mixed(@Nullable PalettedContainer<BlockState>[] states) {
		boolean[] mixed = new boolean[states.length];
		for (int i = 0; i < states.length; i++) {
			mixed[i] = states[i] != null;
		}
		return mixed;
	}

	/** The chunk's blocks and light, unpacked first if they were packed. Any thread. */
	private Body body() {
		ChunkSnapshot chunk = this.base != null ? this.base : this;
		Body body = chunk.body;
		return body != null ? body : chunk.unpackedBody();
	}

	private Body unpackedBody() {
		java.lang.ref.WeakReference<Body> last = this.unpacked;
		Body body = last == null ? null : last.get();
		if (body != null) {
			return body;
		}
		// One at a time, so bakes of neighbouring tiles that reach here together share one.
		synchronized (this) {
			last = this.unpacked;
			body = last == null ? null : last.get();
			if (body == null) {
				body = this.pack.unpack(this.mixed);
				this.unpacked = new java.lang.ref.WeakReference<>(body);
			}
			return body;
		}
	}

	/**
	 * The blocks and light, to keep a reference to while reading them: packed ones then stay
	 * unpacked for as long, where otherwise they go with the next collection. Any thread.
	 */
	public Object hold() {
		return this.body();
	}

	/**
	 * Whether to pack this one, asked once it's far from every chunk the game has loaded: true the
	 * first time, for a chunk itself with anything worth packing. The caller then has {@link #pack}
	 * run on the packing thread.
	 */
	public boolean startPacking() {
		return this.base == null && this.body != null && this.packing.compareAndSet(false, true);
	}

	/** Whether the blocks and light are packed. */
	public boolean isPacked() {
		return this.chunk().body == null;
	}

	/** The size of the packed blocks and light in bytes, 0 if they aren't packed. */
	public int packedSize() {
		ChunkPack pack = this.chunk().pack;
		return pack == null ? 0 : pack.size();
	}

	/** Forgets the blocks and light as last unpacked, so the next read unpacks them again. For tests. */
	public void forgetUnpacked() {
		this.chunk().unpacked = null;
	}

	/** How many snapshots were packed and unpacked since the game started, and how long both took, for tests and the debug log. */
	public static long[] packStats() {
		return new long[] {ChunkPack.PACKED.get(), ChunkPack.PACKED_BYTES.get(), ChunkPack.PACK_NANOS.get(), ChunkPack.UNPACKED.get(), ChunkPack.UNPACK_NANOS.get()};
	}

	/**
	 * Packs the blocks and light, about a tenth of their size, to be unpacked whenever something
	 * reads them again: a redraw after the game has unloaded the chunk, which is rare. Whatever
	 * reads them meanwhile keeps reading what it has. The packing thread only, after {@link #startPacking}.
	 */
	public void pack() {
		Body body = this.body;
		if (body == null) {
			return;
		}
		Tints tints = this.tints;
		ChunkPack pack = ChunkPack.pack(body, tints);
		if (pack == null) {
			return;
		}
		// What the next snapshot here compares with: a chunk loaded anew, which bakes afresh anyway.
		this.replaced = null;
		// Views are cheap to make again, and hold tints of their own.
		java.util.Arrays.fill(this.views, null);
		this.pack = pack;
		this.unpacked = new java.lang.ref.WeakReference<>(new Body(body.blocks(), body.blockLight(), body.skyLight(), tints));
		// Last: a reader that finds no body finds the pack.
		this.body = null;
		// Tints a bake set meanwhile stay: they aren't in the pack.
		if (this.tints == tints) {
			this.tints = null;
		}
	}

	public int sectionCount() {
		return this.mixed.length;
	}

	/** The section's blocks, or null for one that is only air or a single block. */
	public @Nullable Blocks section(int index) {
		return this.mixed[index] ? this.body().blocks()[index] : null;
	}

	/** Whether {@link #section} has blocks for the section, without reading them. */
	public boolean hasBlocks(int index) {
		return this.mixed[index];
	}

	public @Nullable BlockState uniformSection(int index) {
		return this.uniform[index];
	}

	/**
	 * A {@link #section}'s blocks as its container packs them, for readers that look each palette
	 * entry up once instead of every block: one palette index per block in the chunk's own layout,
	 * indexed {@code (y << 8) | (z << 4) | x}, so a turned view's blocks are at {@link #columns}
	 * and its states need {@link #turned(BlockState)}. Null for a section without a container, or
	 * where the container can't be read that way. Read only.
	 */
	public @Nullable Packed packed(int index) {
		Blocks blocks = this.section(index);
		return blocks == null ? null : blocks.packed();
	}

	/** See {@link #packed}. A place in the palette that nothing picks can be null. */
	public record Packed(net.minecraft.util.BitStorage storage, @Nullable BlockState[] palette) {
	}

	/** {@link #columns} per facing, by turns. */
	private static final int[][] COLUMNS = new int[4][];

	static {
		for (Facing facing : Facing.values()) {
			int[] columns = new int[256];
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					columns[(z << 4) | x] = ((facing.worldBlockZ(x, z) & 15) << 4) | (facing.worldBlockX(x, z) & 15);
				}
			}
			COLUMNS[facing.turns] = columns;
		}
	}

	/**
	 * Where each column of this view is in the chunk's own layout: entry {@code (z << 4) | x} of the
	 * view holds the chunk's {@code (z << 4) | x}. Null for the chunk itself, which needs none.
	 */
	public int @Nullable [] columns() {
		return this.facing == Facing.NORTH ? null : COLUMNS[this.facing.turns];
	}

	public int blockLight(int sectionIndex, int x, int y, int z) {
		DataLayer layer = this.body().blockLight()[sectionIndex];
		return layer == null ? 0 : layer.get(this.localX(x, z), y, this.localZ(x, z));
	}

	public int skyLight(int sectionIndex, int x, int y, int z) {
		if (!this.hasSkyLight) {
			return 0;
		}
		int turnedX = x;
		x = this.localX(turnedX, z);
		z = this.localZ(turnedX, z);
		DataLayer[] skyLight = this.body().skyLight();
		DataLayer layer = skyLight[sectionIndex];
		if (layer != null) {
			return layer.get(x, y, z);
		}
		// Same as vanilla: a section without stored sky light takes the bottom layer of the
		// nearest stored section above it, and is fully lit if there is none.
		for (int i = sectionIndex + 1; i < skyLight.length; i++) {
			if (skyLight[i] != null) {
				return skyLight[i].get(x, 0, z);
			}
		}
		return 15;
	}

	/**
	 * The biome at a quart (4-block) position in this chunk or a neighbouring one, as the game had
	 * it when the snapshot was taken: y clamped to the world's height like the game's own chunk
	 * lookup, and the game's fallback biome where it had no chunk.
	 */
	public Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
		Holder<Biome> biome = this.knownNoiseBiome(quartX, quartY, quartZ);
		return biome == null ? this.fallbackBiome : biome;
	}

	/**
	 * Like {@link #noiseBiome}, but null where the game had no chunk, rather than the biome it
	 * assumes there.
	 */
	public @Nullable Holder<Biome> knownNoiseBiome(int quartX, int quartY, int quartZ) {
		if (this.base != null) {
			return this.base.knownNoiseBiome(this.facing.worldBlockX(quartX, quartZ), quartY, this.facing.worldBlockZ(quartX, quartZ));
		}
		int dx = (quartX >> 2) - this.chunkX;
		int dz = (quartZ >> 2) - this.chunkZ;
		PalettedContainerRO<Holder<Biome>>[] sections = dx < -1 || dx > 1 || dz < -1 || dz > 1 ? null : this.biomes[(dx + 1) * 3 + dz + 1];
		if (sections == null) {
			return null;
		}
		int minQuart = this.minSectionY << 2;
		int y = Mth.clamp(quartY, minQuart, minQuart + (sections.length << 2) - 1);
		PalettedContainerRO<Holder<Biome>> section = sections[(y >> 2) - this.minSectionY];
		return section == null ? null : section.get(quartX & 3, y & 3, quartZ & 3);
	}

	/** Whether the game had the neighbouring chunk at this offset when the snapshot was taken. */
	public boolean hasBiomesAround(int dx, int dz) {
		if (this.base != null) {
			// Offsets turn about the origin, without the block formula's -1.
			return this.base.hasBiomesAround((int) this.facing.worldX(dx, dz), (int) this.facing.worldZ(dx, dz));
		}
		return this.biomes[(dx + 1) * 3 + dz + 1] != null;
	}

	/**
	 * One above the tallest block below {@code ceiling}, as far as the columns' tops tell, or
	 * {@code Integer.MIN_VALUE} if none is.
	 */
	public int topBelow(int ceiling) {
		if (this.maxY < ceiling) {
			return this.maxY == Integer.MIN_VALUE ? Integer.MIN_VALUE : this.maxY + 1;
		}
		int top = Integer.MIN_VALUE;
		for (int y : this.surface) {
			if (y < ceiling) {
				top = Math.max(top, y + 1);
			}
		}
		return top;
	}

	/** The bottom y of the lowest section with blocks in it, from the one holding {@code y} up; {@code y} if none has. */
	public int firstSectionFrom(int y) {
		for (int i = Math.max(0, (y >> 4) - this.minSectionY); i < this.mixed.length; i++) {
			if (this.mixed[i] || this.uniform[i] != null) {
				return (this.minSectionY + i) << 4;
			}
		}
		return y;
	}

	/** One above the highest section with blocks in it below the one holding {@code y}; {@code Integer.MIN_VALUE} if none has. */
	public int lastSectionTopBelow(int y) {
		for (int i = Math.min(this.mixed.length, (y >> 4) - this.minSectionY) - 1; i >= 0; i--) {
			if (this.mixed[i] || this.uniform[i] != null) {
				return (this.minSectionY + i + 1) << 4;
			}
		}
		return Integer.MIN_VALUE;
	}

	/** The column's top block y, where its tints are sampled. */
	public int surface(int x, int z) {
		return this.surface[((z & 15) << 4) | (x & 15)];
	}

	public @Nullable Tints tints() {
		if (this.base == null) {
			Tints tints = this.tints;
			// Packed with the blocks and light, and unpacked with them: a bake that asks reads those too.
			return tints != null || this.body != null ? tints : this.body().tints();
		}
		Tints from = this.tints == null ? this.base.tints() : null;
		if (from != null) {
			// The chunk's own tints, turned, so every facing shows the same colours.
			this.tints = new Tints(this.turnedColumns(from.grass()), this.turnedColumns(from.foliage()), this.turnedColumns(from.dryFoliage()), this.turnedColumns(from.water()));
		}
		return this.tints;
	}

	private int[] turnedColumns(int[] columns) {
		int[] out = new int[256];
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				out[(z << 4) | x] = columns[(this.localZ(x, z) << 4) | this.localX(x, z)];
			}
		}
		return out;
	}

	public void setTints(Tints tints) {
		this.tints = tints;
	}

	/**
	 * Stands for this snapshot's blocks: snapshots with the same token hold the same blocks, column
	 * tops and copycats, so whatever is worked out from those alone holds for all of them. A snapshot
	 * that replaces one with the same blocks (light updates take new ones all the time) takes over
	 * its token (see {@link #replacing}); otherwise every snapshot has its own.
	 */
	private Object blocksToken = new Object();
	/** What bakes worked out from this snapshot's blocks, kept for the next bake; see {@code ChunkBakeData}. */
	private java.util.concurrent.atomic.AtomicReference<@Nullable Object> bakeData = new java.util.concurrent.atomic.AtomicReference<>();
	/** The snapshot this one replaced, until the first bake that asks for a token compares the two. */
	private volatile @Nullable ChunkSnapshot replaced;

	/**
	 * Notes that this snapshot replaces {@code before}. Comparing them takes a bake thread's time,
	 * not the client thread's: the first token or bake data asked for compares them, and takes over
	 * both when the blocks are the same. The one replaced takes over nothing more from its own
	 * predecessor; whatever still holds it keeps a token of its own. Call before bakes can see this one.
	 */
	public void replacing(ChunkSnapshot before) {
		before.replaced = null;
		if (this.base == null && before.base == null) {
			this.replaced = before;
		}
	}

	public Object blocksToken() {
		this.compare();
		return this.blocksToken;
	}

	/** See {@link #blocksToken}; the holder of bake data shared with every snapshot of the same blocks. */
	public java.util.concurrent.atomic.AtomicReference<@Nullable Object> bakeData() {
		this.compare();
		return this.bakeData;
	}

	/** Takes over what is the same as in the snapshot this one replaced, once. */
	private void compare() {
		if (this.replaced == null) {
			return;
		}
		synchronized (this) {
			ChunkSnapshot before = this.replaced;
			if (before == null) {
				return;
			}
			before.compare();
			if (this.sameBlocks(before)) {
				this.blocksToken = before.blocksToken;
				// The same holder, so what either's bakes keep from here on serves both.
				this.bakeData = before.bakeData;
			}
			// Published by this write: a bake that reads null reads the token as set here.
			this.replaced = null;
		}
	}

	/**
	 * Whether {@code before} holds the same blocks: every section the same block or the same packed
	 * palette indices with the same palette, the same column tops and no copycats.
	 */
	private boolean sameBlocks(ChunkSnapshot before) {
		// Packed ones aren't unpacked to find out: the game has unloaded their chunk, and its next
		// snapshot is of a chunk loaded anew.
		Body body = this.body;
		Body bodyBefore = before.body;
		if (body == null || bodyBefore == null || this.chunkX != before.chunkX || this.chunkZ != before.chunkZ
			|| this.minSectionY != before.minSectionY || this.copycats != null || before.copycats != null
			|| this.mixed.length != before.mixed.length || !java.util.Arrays.equals(this.surface, before.surface)) {
			return false;
		}
		for (int i = 0; i < this.mixed.length; i++) {
			if (this.uniform[i] != before.uniform[i]) {
				return false;
			}
			Blocks mine = body.blocks()[i];
			Blocks theirs = bodyBefore.blocks()[i];
			if (mine == theirs) {
				continue;
			}
			if (mine == null || theirs == null || !samePacked(mine.packed(), theirs.packed())) {
				return false;
			}
		}
		return true;
	}

	private static boolean samePacked(@Nullable Packed a, @Nullable Packed b) {
		if (a == null || b == null || a.storage().getBits() != b.storage().getBits() || a.storage().getSize() != b.storage().getSize()) {
			return false;
		}
		int size = a.palette().length;
		if (size != b.palette().length || !java.util.Arrays.equals(a.storage().getRaw(), b.storage().getRaw())) {
			return false;
		}
		for (int p = 0; p < size; p++) {
			if (a.palette()[p] != b.palette()[p]) {
				return false;
			}
		}
		return true;
	}

	/** Blended biome tints per column, as 0xRRGGBB, indexed {@code (z << 4) | x}. */
	public record Tints(int[] grass, int[] foliage, int[] dryFoliage, int[] water) {
	}

	public long key() {
		return key(this.chunkX, this.chunkZ);
	}

	public static long key(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}
}
