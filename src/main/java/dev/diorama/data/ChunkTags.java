package dev.diorama.data;

import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Reads a chunk as the save stores it, which has stayed the same from 1.18 on, with the game's own
 * codecs rather than its chunk loader: that one builds a whole chunk and, before 1.21.2, starts on
 * its light and points of interest as it goes. The world's save in singleplayer ({@link SavedChunks})
 * and the chunks a server shares both come this way. No client classes, so the server half uses it
 * too. One thread at a time.
 */
public final class ChunkTags {
	private final Level level;
	private final boolean withLight;
	private final Codec<PalettedContainer<BlockState>> blockStates;
	private final Codec<PalettedContainerRO<Holder<Biome>>> biomes;
	private final int heightBits;

	/** What a chunk's save says about it without reading its blocks. */
	public record Summary(boolean finished, int maxY, int floorY) {
		public static final Summary UNFINISHED = new Summary(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
	}

	/**
	 * For chunks of {@code level}, whose registries name the biomes. Block light is always kept;
	 * {@code withLight} keeps the sky light too, which only a view from under a roof draws.
	 */
	public ChunkTags(Level level, boolean withLight) {
		this.level = level;
		this.withLight = withLight;
		//? if >=1.21.9 {
		this.blockStates = level.palettedContainerFactory().blockStatesContainerCodec();
		this.biomes = level.palettedContainerFactory().biomeContainerCodec();
		//?} else {
		/*this.blockStates = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
		//? if >=1.21.2 {
		net.minecraft.core.Registry<Biome> registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
		Holder<Biome> plains = registry.getOrThrow(Biomes.PLAINS);
		//?} else {
		net.minecraft.core.Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
		Holder<Biome> plains = registry.getHolderOrThrow(Biomes.PLAINS);
		//?}
		this.biomes = PalettedContainer.codecRO(registry.asHolderIdMap(), registry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, plains);
		*///?}
		this.heightBits = Mth.ceillog2(level.getHeight() + 1);
	}

	public Codec<PalettedContainer<BlockState>> blockStates() {
		return this.blockStates;
	}

	/** The chunk in {@code tag}, already brought up to this version. */
	@SuppressWarnings("unchecked")
	public SavedChunk read(int chunkX, int chunkZ, CompoundTag tag) {
		boolean finished = finished(tag);
		int count = this.level.getSectionsCount();
		int minSection = this.level.getMinSectionY();
		PalettedContainer<BlockState>[] states = new PalettedContainer[count];
		PalettedContainerRO<Holder<Biome>>[] biomes = this.biomes(tag);
		DataLayer[] blockLight = new DataLayer[count];
		DataLayer[] skyLight = new DataLayer[count];
		ListTag sections = Nbt.list(tag, "sections");
		for (int s = 0; s < sections.size(); s++) {
			CompoundTag section = Nbt.compound(sections, s);
			int i = Nbt.sectionY(section) - minSection;
			if (i < 0 || i >= count) {
				continue;
			}
			if (finished) {
				states[i] = this.blockStates(section);
			}
			if (finished) {
				// Block light lights every view at night; sky light only the view from under a roof.
				blockLight[i] = light(section, "BlockLight");
				if (this.withLight) {
					skyLight[i] = light(section, "SkyLight");
				}
			}
		}
		int[] surface = finished ? this.surface(tag) : null;
		int[] solid = finished ? this.solid(tag) : null;
		if (finished && surface == null && solid == null && !Nbt.compound(tag, "Heightmaps").contains(Heightmap.Types.WORLD_SURFACE.getSerializationKey())) {
			// A shared chunk comes without its height maps: they follow from its blocks.
			surface = heights(states, minSection, Heightmap.Types.WORLD_SURFACE);
			solid = heights(states, minSection, Heightmap.Types.MOTION_BLOCKING);
		}
		finished &= surface != null && solid != null;
		return new SavedChunk(chunkX, chunkZ, finished, states, biomes, blockLight, skyLight, finished ? surface : new int[0], finished ? solid : new int[0]);
	}

	/** Only the biomes of the chunk in {@code tag}, already brought up to this version: per section, null where the save has none. */
	@SuppressWarnings("unchecked")
	public @Nullable PalettedContainerRO<Holder<Biome>>[] biomes(CompoundTag tag) {
		int count = this.level.getSectionsCount();
		int minSection = this.level.getMinSectionY();
		PalettedContainerRO<Holder<Biome>>[] biomes = new PalettedContainerRO[count];
		ListTag sections = Nbt.list(tag, "sections");
		for (int s = 0; s < sections.size(); s++) {
			CompoundTag section = Nbt.compound(sections, s);
			int i = Nbt.sectionY(section) - minSection;
			if (i >= 0 && i < count) {
				biomes[i] = this.biomes.parse(NbtOps.INSTANCE, Nbt.compound(section, "biomes")).resultOrPartial(e -> {
				}).orElse(null);
			}
		}
		return biomes;
	}

	/** A saved section's blocks, or null if it has none or they can't be read. */
	public @Nullable PalettedContainer<BlockState> blockStates(CompoundTag section) {
		return this.blockStates.parse(NbtOps.INSTANCE, Nbt.compound(section, "block_states")).resultOrPartial(e -> {
		}).orElse(null);
	}

	/** Each column's top block y, or null if the chunk's save has no such height map. */
	public int @Nullable [] surface(CompoundTag tag) {
		return this.heights(Nbt.longs(Nbt.compound(tag, "Heightmaps"), Heightmap.Types.WORLD_SURFACE.getSerializationKey()));
	}

	/** Each column's top block that stops movement or holds fluid, or null if the save has none. */
	public int @Nullable [] solid(CompoundTag tag) {
		return this.heights(Nbt.longs(Nbt.compound(tag, "Heightmaps"), Heightmap.Types.MOTION_BLOCKING.getSerializationKey()));
	}

	/** A saved height map as each column's top block y, or null if it's missing or the wrong size. */
	private int @Nullable [] heights(long @Nullable [] packed) {
		if (packed == null) {
			return null;
		}
		try {
			SimpleBitStorage storage = new SimpleBitStorage(this.heightBits, 256, packed);
			int[] heights = new int[256];
			int minY = this.level.getMinY();
			for (int i = 0; i < 256; i++) {
				// Saved as the first free y above the column, counted from the bottom of the world.
				heights[i] = storage.get(i) + minY - 1;
			}
			return heights;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Each column's top block of the kind a height map counts, from the sections there are: below
	 * the lowest of them if a column has none there.
	 */
	private int[] heights(PalettedContainer<BlockState>[] states, int minSection, Heightmap.Types type) {
		java.util.function.Predicate<BlockState> counts = type.isOpaque();
		int lowest = Integer.MAX_VALUE;
		for (int i = 0; i < states.length; i++) {
			if (states[i] != null) {
				lowest = Math.min(lowest, (i + minSection) * 16 - 1);
			}
		}
		int[] heights = new int[256];
		java.util.Arrays.fill(heights, lowest == Integer.MAX_VALUE ? this.level.getMinY() - 1 : lowest);
		for (int column = 0; column < 256; column++) {
			int x = column & 15;
			int z = column >> 4;
			found:
			for (int i = states.length - 1; i >= 0; i--) {
				if (states[i] == null) {
					continue;
				}
				for (int y = 15; y >= 0; y--) {
					if (counts.test(states[i].get(x, y, z))) {
						heights[column] = (i + minSection) * 16 + y;
						break found;
					}
				}
			}
		}
		return heights;
	}

	/** How high a finished chunk reaches and how deep its sky view can see. */
	public static Summary summaryOf(int[] surface, int[] solid) {
		int top = Integer.MIN_VALUE;
		int floor = Integer.MAX_VALUE;
		for (int i = 0; i < 256; i++) {
			top = Math.max(top, surface[i]);
			floor = Math.min(floor, solid[i]);
		}
		// Water and shallow overhangs can hide blocks below the height map, as in ChunkSnapshot.
		return new Summary(true, top, floor - 24);
	}

	/**
	 * Whether a chunk's blocks are final. A pre-generator's terrain-only chunks aren't: they have
	 * no trees, ores or villages yet and no light, and look different once the game finishes them.
	 * A chunk from before 1.18 reads as not even started once upgraded, which only means the game
	 * has yet to add the new depths under it; {@code below_zero_retrogen} names what it really was.
	 */
	public static boolean finished(CompoundTag tag) {
		String status = Nbt.string(tag, "Status");
		if ("empty".equals(status) || "minecraft:empty".equals(status)) {
			status = Nbt.string(Nbt.compound(tag, "below_zero_retrogen"), "target_status");
			return status.endsWith("heightmaps") || status.endsWith("initialize_light") || status.endsWith("light") || status.endsWith("spawn") || status.endsWith("full");
		}
		return status.equals("full") || status.equals("minecraft:full");
	}

	private static @Nullable DataLayer light(CompoundTag section, String name) {
		byte[] bytes = Nbt.bytes(section, name);
		return bytes == null || bytes.length != DataLayer.SIZE ? null : new DataLayer(bytes);
	}

	/** This version's chunk data version: older chunks need upgrading before their fields mean what they say. */
	public static int dataVersion() {
		//? if >=1.21.6 {
		return SharedConstants.getCurrentVersion().dataVersion().version();
		//?} else {
		/*return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
		*///?}
	}
}
