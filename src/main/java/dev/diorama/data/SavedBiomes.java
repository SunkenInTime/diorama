package dev.diorama.data;

import dev.diorama.Diorama;
import dev.diorama.mixin.ChunkMapInvoker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.jspecify.annotations.Nullable;

/**
 * The biome at a spot the client has no chunk for, from the world's save, in singleplayer: what
 * the map's tooltip names over land the player has left. The client only has biomes for the chunks
 * around the player, and the map's tiles keep none.
 *
 * <p>A chunk is read through the game's own server, as {@link SavedChunks} reads: nothing is loaded
 * into the world, and a chunk the server hasn't written yet comes back all the same. Only its biomes
 * are parsed. The read happens off the render thread, so the first answer for a chunk is null, and
 * the biome is there a moment later. Everything else here is for the render thread.
 */
public final class SavedBiomes {
	/** Chunks kept: far more than the mouse crosses between two looks at the map. */
	private static final int KEPT = 256;
	/** How long before a chunk the save didn't have, or had without biomes of its own, is asked for again: the server may have written it since. */
	private static final long ASK_AGAIN_NANOS = 10_000_000_000L;

	/** One chunk's biomes per section: null while they're being read, and where the save has none. */
	private static final class Read {
		@Nullable PalettedContainerRO<Holder<Biome>> @Nullable [] sections;
		boolean reading = true;
		long asked = System.nanoTime();
	}

	private final ClientLevel level;
	private final IntegratedServer server;
	private final ServerLevel serverLevel;
	private final ChunkMap chunkMap;
	private final ChunkTags tags;
	private final Map<Long, Read> chunks = new LinkedHashMap<>(KEPT, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Read> eldest) {
			return this.size() > KEPT;
		}
	};

	private SavedBiomes(ClientLevel level, IntegratedServer server, ServerLevel serverLevel) {
		this.level = level;
		this.server = server;
		this.serverLevel = serverLevel;
		this.chunkMap = serverLevel.getChunkSource().chunkMap;
		// The client's registries, so the biomes are the ones the tooltip can name.
		this.tags = new ChunkTags(level, false);
	}

	/** The save behind this level, or null when there's none to read: on a server, the save isn't here. */
	public static @Nullable SavedBiomes open(ClientLevel level) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		ServerLevel serverLevel = server == null ? null : server.getLevel(level.dimension());
		return serverLevel == null ? null : new SavedBiomes(level, server, serverLevel);
	}

	public ClientLevel level() {
		return this.level;
	}

	/**
	 * The biome the game would name at this block, or null while the save is being read, and where
	 * it has no chunk. The game picks the biome from one of the eight 4-block cells around the block,
	 * so every chunk one of those is in has to be in the save.
	 */
	public @Nullable Holder<Biome> at(BlockPos pos) {
		if (!this.server.isRunning() || this.server.getLevel(this.level.dimension()) != this.serverLevel) {
			return null;
		}
		int cellX = (pos.getX() - 2) >> 2;
		int cellY = (pos.getY() - 2) >> 2;
		int cellZ = (pos.getZ() - 2) >> 2;
		// Ask for every chunk at once, not one a frame.
		boolean known = true;
		for (int chunkX = cellX >> 2; chunkX <= ((cellX + 1) >> 2); chunkX++) {
			for (int chunkZ = cellZ >> 2; chunkZ <= ((cellZ + 1) >> 2); chunkZ++) {
				known &= this.sections(chunkX, chunkZ) != null;
			}
		}
		if (!known) {
			return null;
		}
		for (int i = 0; i < 8; i++) {
			if (this.cell(cellX + (i & 1), cellY + (i >> 1 & 1), cellZ + (i >> 2)) == null) {
				return null;
			}
		}
		return this.level.getBiomeManager().withDifferentSource((x, y, z) -> Objects.requireNonNull(this.cell(x, y, z))).getBiome(pos);
	}

	/** The biome of a 4-block cell as saved, y kept inside the world like the game's own chunk lookup; null where the save has none. */
	private @Nullable Holder<Biome> cell(int quartX, int quartY, int quartZ) {
		Read entry = this.chunks.get(ChunkSnapshot.key(quartX >> 2, quartZ >> 2));
		PalettedContainerRO<Holder<Biome>>[] sections = entry == null ? null : entry.sections;
		if (sections == null) {
			return null;
		}
		int minQuart = this.level.getMinSectionY() << 2;
		int y = Mth.clamp(quartY, minQuart, minQuart + (sections.length << 2) - 1);
		PalettedContainerRO<Holder<Biome>> section = sections[(y >> 2) - this.level.getMinSectionY()];
		return section == null ? null : section.get(quartX & 3, y & 3, quartZ & 3);
	}

	/** A chunk's biomes, or null while they aren't known; asks the save for them then. */
	private @Nullable PalettedContainerRO<Holder<Biome>> @Nullable [] sections(int chunkX, int chunkZ) {
		long key = ChunkSnapshot.key(chunkX, chunkZ);
		Read known = this.chunks.get(key);
		if (known != null && (known.reading || known.sections != null || System.nanoTime() - known.asked < ASK_AGAIN_NANOS)) {
			return known.sections;
		}
		Read entry = new Read();
		this.chunks.put(key, entry);
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		// Parsed on a background thread: the read is answered on the server's storage thread.
		this.chunkMap.read(pos).thenApplyAsync(read -> {
			CompoundTag tag = read.orElse(null);
			if (tag == null) {
				return null;
			}
			tag = ((ChunkMapInvoker) this.chunkMap).diorama$upgrade(tag);
			// A chunk the world has only begun has no biomes of its own yet: it's asked for again later, like one the save lacks.
			if (!ChunkTags.hasBiomes(tag)) {
				return null;
			}
			PalettedContainerRO<Holder<Biome>>[] sections = this.tags.biomes(tag);
			for (PalettedContainerRO<Holder<Biome>> section : sections) {
				if (section != null) {
					return sections;
				}
			}
			return null;
		}).exceptionally(t -> {
			Diorama.LOGGER.warn("Couldn't read the biomes of saved chunk {}", pos, t);
			return null;
		}).thenAcceptAsync(sections -> {
			entry.sections = sections;
			entry.reading = false;
		}, Minecraft.getInstance());
		return null;
	}
}
