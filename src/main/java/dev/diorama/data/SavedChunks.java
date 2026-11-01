package dev.diorama.data;

import com.mojang.serialization.Codec;
import dev.diorama.Diorama;
import dev.diorama.mixin.ChunkMapInvoker;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The chunks in the world's save, in singleplayer: where the map gets blocks for places the client
 * never loaded, such as an area a pre-generation tool made. Chunks are read through the game's own
 * server, which owns the files: it hands back chunks it hasn't written yet, and its files never
 * change under a read. Reading is all this does; nothing here loads a chunk into the world.
 *
 * <p>Only the region files' headers are read directly, to list the chunks and when each was last
 * saved. The rest parses the save's chunk format, which has stayed the same from 1.18 on, with the
 * game's own codecs, rather than the game's chunk loader: that one builds a whole chunk and, before
 * 1.21.2, starts on its light and points of interest as it goes.
 *
 * <p>All reads block, so call them off the render thread, and from one thread at a time.
 */
public final class SavedChunks {
	private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d{1,8})\\.(-?\\d{1,8})\\.mca");
	/**
	 * Chunks kept between reads: with memory to spare, enough for the two columns of tiles nearest,
	 * so a column drawn after the last reads no chunk twice, and for the biomes round each. In the
	 * launcher's usual 2 GB they'd push the game into long collections, and one column has to do.
	 */
	private static final int CACHED_CHUNKS = Runtime.getRuntime().maxMemory() > 3L << 30 ? 2048 : 768;
	/** In {@link #tops}: this chunk's top isn't known yet, or it has nothing finished to draw. */
	private static final short UNKNOWN = Short.MIN_VALUE;
	private static final short NONE = Short.MIN_VALUE + 1;
	/** A server tick has 50 ms; past this the game is close to slowing down. */
	private static final long SERVER_BEHIND_NANOS = 40_000_000;

	private final Level level;
	private final ServerLevel serverLevel;
	private final ChunkMap chunkMap;
	private final Path regionFolder;
	private final boolean withLight;
	private final Codec<PalettedContainer<BlockState>> blockStates;
	private final Codec<PalettedContainerRO<Holder<Biome>>> biomes;
	private final int heightBits;
	private final Map<Long, @Nullable SavedChunk> cache = new LinkedHashMap<>(CACHED_CHUNKS, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, @Nullable SavedChunk> eldest) {
			return this.size() > CACHED_CHUNKS;
		}
	};
	/** Each chunk's highest block by {@link #regionKey}, indexed {@code (z << 5) | x}; see {@link #top}. */
	private final Map<Long, short[]> tops = new HashMap<>();
	/** Set when the map stops reading: reads waiting on a server that's shutting down give up. */
	private volatile boolean closed;

	private SavedChunks(Level level, ServerLevel serverLevel, Path regionFolder, boolean withLight) {
		this.level = level;
		this.serverLevel = serverLevel;
		this.chunkMap = serverLevel.getChunkSource().chunkMap;
		this.regionFolder = regionFolder;
		this.withLight = withLight;
		// The client's registries, so the biomes are the ones the map already knows.
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

	/**
	 * The save behind this level, or null when there's none to read: on a server, the save isn't
	 * here. {@code withLight} keeps the save's light, which only a view from under a roof draws.
	 */
	public static @Nullable SavedChunks open(ClientLevel level, boolean withLight) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return null;
		}
		ServerLevel serverLevel = server.getLevel(level.dimension());
		if (serverLevel == null) {
			return null;
		}
		Path folder = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("region");
		return new SavedChunks(level, serverLevel, folder, withLight);
	}

	/** Stops reading; reads in progress return nothing. */
	public void close() {
		this.closed = true;
	}

	/**
	 * Waits for the server's storage thread. A server shutting down drops the reads still queued
	 * without answering them, so this keeps checking whether to give up.
	 */
	private <T> @Nullable T await(CompletableFuture<T> future) {
		while (!this.closed) {
			try {
				return future.get(100, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				// Keep waiting.
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			} catch (ExecutionException e) {
				throw new CompletionException(e.getCause());
			}
		}
		return null;
	}

	public Level level() {
		return this.level;
	}

	/** Whether the server behind this save is still the one running. */
	public boolean isOpen() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		return server != null && server.isRunning() && server.getLevel(this.level.dimension()) == this.serverLevel;
	}

	public static long regionKey(int regionX, int regionZ) {
		return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
	}

	/**
	 * Every region file's chunks, by {@link #regionKey}: for each of its 32 × 32 chunks, indexed
	 * {@code (z << 5) | x}, when the save last wrote it in seconds, or 0 if it has none. Chunks the
	 * server hasn't written to its files yet are left out until it does.
	 */
	public Map<Long, int[]> scan() {
		Map<Long, int[]> regions = new HashMap<>();
		if (!Files.isDirectory(this.regionFolder)) {
			return regions;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(this.regionFolder, "r.*.mca")) {
			for (Path file : files) {
				Matcher m = REGION_FILE.matcher(file.getFileName().toString());
				if (!m.matches()) {
					continue;
				}
				int[] times = readHeader(file);
				if (times != null) {
					regions.put(regionKey(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), times);
				}
			}
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't list the saved chunks in {}", this.regionFolder, e);
		}
		return regions;
	}

	/** A region file's chunk save times, from its header: 4 KiB of locations, then 4 KiB of times. */
	private static int @Nullable [] readHeader(Path file) {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer header = ByteBuffer.allocate(8192);
			while (header.hasRemaining() && channel.read(header) >= 0) {
			}
			if (header.hasRemaining()) {
				// New and still empty, or cut short.
				return null;
			}
			header.flip();
			int[] times = new int[1024];
			for (int i = 0; i < 1024; i++) {
				int location = header.getInt(i * 4);
				int time = header.getInt(4096 + i * 4);
				times[i] = location == 0 ? 0 : Math.max(1, time);
			}
			return times;
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't read the region file header of {}", file, e);
			return null;
		}
	}

	/** What a chunk's save says about it without reading its blocks; see {@link #summary}. */
	public record Summary(boolean finished, int maxY, int floorY) {
		private static final Summary UNFINISHED = new Summary(false, Integer.MIN_VALUE, Integer.MIN_VALUE);
	}

	/**
	 * Whether the chunk is finished and how high it reaches, from its status and height maps alone,
	 * or null if the save has none. Much cheaper than {@link #read}.
	 */
	public @Nullable Summary summary(int chunkX, int chunkZ) {
		Summary summary = this.summarize(chunkX, chunkZ);
		short top = summary == null || !summary.finished() || summary.maxY() <= this.level.getMinY() ? NONE : (short) summary.maxY();
		this.tops.computeIfAbsent(regionKey(chunkX >> 5, chunkZ >> 5), k -> {
			short[] tops = new short[1024];
			java.util.Arrays.fill(tops, UNKNOWN);
			return tops;
		})[((chunkZ & 31) << 5) | (chunkX & 31)] = top;
		return summary;
	}

	/**
	 * The chunk's highest block, or {@code Integer.MIN_VALUE} if it has nothing finished to draw.
	 * Bakes ask this of every chunk that could reach into a tile, far more than they read, so the
	 * answer is kept until {@link #forgetTops}.
	 */
	public int top(int chunkX, int chunkZ) {
		short[] tops = this.tops.get(regionKey(chunkX >> 5, chunkZ >> 5));
		short top = tops == null ? UNKNOWN : tops[((chunkZ & 31) << 5) | (chunkX & 31)];
		if (top == UNKNOWN) {
			this.summary(chunkX, chunkZ);
			top = this.tops.get(regionKey(chunkX >> 5, chunkZ >> 5))[((chunkZ & 31) << 5) | (chunkX & 31)];
		}
		return top == NONE ? Integer.MIN_VALUE : top;
	}

	private @Nullable Summary summarize(int chunkX, int chunkZ) {
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		CollectFields fields = new CollectFields(
			new FieldSelector(IntTag.TYPE, "DataVersion"),
			new FieldSelector(StringTag.TYPE, "Status"),
			new FieldSelector(CompoundTag.TYPE, "Heightmaps"),
			new FieldSelector(CompoundTag.TYPE, "below_zero_retrogen")
		);
		try {
			this.await(this.chunkMap.chunkScanner().scanChunk(pos, fields));
		} catch (RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't scan saved chunk {}", pos, e);
			return null;
		}
		if (!(fields.getResult() instanceof CompoundTag tag) || tag.isEmpty()) {
			return null;
		}
		if (NbtUtils.getDataVersion(tag, -1) < dataVersion()) {
			// Saved by an older version: its fields only mean what they say once it's upgraded.
			SavedChunk chunk = this.read(chunkX, chunkZ);
			return chunk == null ? null : chunk.finished() ? summaryOf(chunk.surface(), chunk.solid()) : Summary.UNFINISHED;
		}
		if (!finished(tag)) {
			return Summary.UNFINISHED;
		}
		CompoundTag heightmaps = compound(tag, "Heightmaps");
		int[] surface = this.heights(longs(heightmaps, Heightmap.Types.WORLD_SURFACE.getSerializationKey()));
		int[] solid = this.heights(longs(heightmaps, Heightmap.Types.MOTION_BLOCKING.getSerializationKey()));
		return surface == null || solid == null ? Summary.UNFINISHED : summaryOf(surface, solid);
	}

	private static Summary summaryOf(int[] surface, int[] solid) {
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
	private static boolean finished(CompoundTag tag) {
		String status = string(tag, "Status");
		if ("empty".equals(status) || "minecraft:empty".equals(status)) {
			status = string(compound(tag, "below_zero_retrogen"), "target_status");
			return status.endsWith("heightmaps") || status.endsWith("initialize_light") || status.endsWith("light") || status.endsWith("spawn") || status.endsWith("full");
		}
		return status.equals("full") || status.equals("minecraft:full");
	}

	/** The chunk as saved, or null if the save has none or it can't be read. */
	public @Nullable SavedChunk read(int chunkX, int chunkZ) {
		long key = ChunkSnapshot.key(chunkX, chunkZ);
		if (this.cache.containsKey(key)) {
			return this.cache.get(key);
		}
		SavedChunk chunk = this.load(chunkX, chunkZ);
		if (!this.closed) {
			this.cache.put(key, chunk);
		}
		return chunk;
	}

	/** Forgets the region's chunk tops: its file changed. */
	public void forgetTops(long region) {
		this.tops.remove(region);
	}

	/** Lets go of every chunk kept. */
	public void clear() {
		this.cache.clear();
	}

	/** Whether the game's server is falling behind, so it needs the disk and the processor more than the map does. */
	public boolean serverBehind() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null || Minecraft.getInstance().isPaused()) {
			return false;
		}
		//? if >=1.21 {
		return server.getAverageTickTimeNanos() > SERVER_BEHIND_NANOS;
		//?} else {
		/*return server.getAverageTickTime() * 1_000_000 > SERVER_BEHIND_NANOS;
		*///?}
	}

	@SuppressWarnings("unchecked")
	private @Nullable SavedChunk load(int chunkX, int chunkZ) {
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		try {
			Optional<CompoundTag> read = this.await(this.chunkMap.read(pos));
			CompoundTag tag = read == null ? null : read.orElse(null);
			if (tag == null) {
				return null;
			}
			tag = ((ChunkMapInvoker) this.chunkMap).diorama$upgrade(tag);
			boolean finished = finished(tag);
			int count = this.level.getSectionsCount();
			int minSection = this.level.getMinSectionY();
			PalettedContainer<BlockState>[] states = new PalettedContainer[count];
			PalettedContainerRO<Holder<Biome>>[] biomes = new PalettedContainerRO[count];
			DataLayer[] blockLight = new DataLayer[count];
			DataLayer[] skyLight = new DataLayer[count];
			ListTag sections = list(tag, "sections");
			for (int s = 0; s < sections.size(); s++) {
				CompoundTag section = compound(sections, s);
				int i = sectionY(section) - minSection;
				if (i < 0 || i >= count) {
					continue;
				}
				if (finished) {
					states[i] = this.blockStates.parse(NbtOps.INSTANCE, compound(section, "block_states")).resultOrPartial(e -> {
					}).orElse(null);
				}
				biomes[i] = this.biomes.parse(NbtOps.INSTANCE, compound(section, "biomes")).resultOrPartial(e -> {
				}).orElse(null);
				if (finished && this.withLight) {
					blockLight[i] = light(section, "BlockLight");
					skyLight[i] = light(section, "SkyLight");
				}
			}
			CompoundTag heightmaps = compound(tag, "Heightmaps");
			int[] surface = finished ? this.heights(longs(heightmaps, Heightmap.Types.WORLD_SURFACE.getSerializationKey())) : null;
			int[] solid = finished ? this.heights(longs(heightmaps, Heightmap.Types.MOTION_BLOCKING.getSerializationKey())) : null;
			finished &= surface != null && solid != null;
			return new SavedChunk(chunkX, chunkZ, finished, states, biomes, blockLight, skyLight, finished ? surface : new int[0], finished ? solid : new int[0]);
		} catch (RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read saved chunk {}", pos, e);
			return null;
		}
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

	private static @Nullable DataLayer light(CompoundTag section, String name) {
		byte[] bytes = bytes(section, name);
		return bytes == null || bytes.length != DataLayer.SIZE ? null : new DataLayer(bytes);
	}

	private static int dataVersion() {
		//? if >=1.21.6 {
		return SharedConstants.getCurrentVersion().dataVersion().version();
		//?} else {
		/*return SharedConstants.getCurrentVersion().getDataVersion().getVersion();
		*///?}
	}

	// Reading NBT: its getters changed in 1.21.5. A missing value reads as an empty one.

	//? if >=1.21.5 {
	private static CompoundTag compound(CompoundTag tag, String name) {
		return tag.getCompoundOrEmpty(name);
	}

	private static CompoundTag compound(ListTag list, int index) {
		return list.getCompoundOrEmpty(index);
	}

	private static ListTag list(CompoundTag tag, String name) {
		return tag.getListOrEmpty(name);
	}

	private static String string(CompoundTag tag, String name) {
		return tag.getStringOr(name, "");
	}

	private static int sectionY(CompoundTag section) {
		return section.getByteOr("Y", (byte) 0);
	}

	private static byte @Nullable [] bytes(CompoundTag tag, String name) {
		return tag.getByteArray(name).orElse(null);
	}

	private static long @Nullable [] longs(CompoundTag tag, String name) {
		return tag.getLongArray(name).orElse(null);
	}
	//?} else {
	/*private static CompoundTag compound(CompoundTag tag, String name) {
		return tag.getCompound(name);
	}

	private static CompoundTag compound(ListTag list, int index) {
		return list.getCompound(index);
	}

	private static ListTag list(CompoundTag tag, String name) {
		return tag.getList(name, Tag.TAG_COMPOUND);
	}

	private static String string(CompoundTag tag, String name) {
		return tag.getString(name);
	}

	private static int sectionY(CompoundTag section) {
		return section.getByte("Y");
	}

	private static byte @Nullable [] bytes(CompoundTag tag, String name) {
		return tag.contains(name, Tag.TAG_BYTE_ARRAY) ? tag.getByteArray(name) : null;
	}

	private static long @Nullable [] longs(CompoundTag tag, String name) {
		return tag.contains(name, Tag.TAG_LONG_ARRAY) ? tag.getLongArray(name) : null;
	}
	*///?}
}
