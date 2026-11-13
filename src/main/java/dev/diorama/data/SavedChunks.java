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
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The chunks in the world's save, in singleplayer: where the map gets blocks for places the client
 * never loaded, such as an area a pre-generation tool made. Chunks are read through the game's own
 * server, which owns the files: it hands back chunks it hasn't written yet, and its files never
 * change under a read. Nothing here loads a chunk into the world; before listing the files it has
 * the server write the chunks it holds, as its autosave would (see {@link #saveHeldChunks}).
 *
 * <p>Only the region files' headers are read directly, to list the chunks and when each was last
 * saved. The rest parses the save's chunk format, which has stayed the same from 1.18 on, with the
 * game's own codecs, rather than the game's chunk loader: that one builds a whole chunk and, before
 * 1.21.2, starts on its light and points of interest as it goes.
 *
 * <p>All reads block, so call them off the render thread, and from one thread at a time.
 */
public final class SavedChunks implements ChunkSave {
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
	/**
	 * The longest a listing waits for the server's chunks to reach its files. They took up to 1.3 s
	 * with Distant Horizons generating; a storage thread that reads keep too busy to write would
	 * otherwise hold up the whole fill, and what it hasn't written by then shows at the next listing.
	 */
	private static final long WRITES_WAIT_NANOS = 10_000_000_000L;

	private final Level level;
	private final ServerLevel serverLevel;
	private final ChunkMap chunkMap;
	private final Path regionFolder;
	private final ChunkTags tags;
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
		// The client's registries, so the biomes are the ones the map already knows.
		this.tags = new ChunkTags(level, withLight);
	}

	/**
	 * The save behind this level, or null when there's none to read: on a server, the save isn't
	 * here. Block light is always kept; {@code withLight} keeps the sky light too, which only a view
	 * from under a roof draws.
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
	@Override
	public void close() {
		this.closed = true;
	}

	/**
	 * Waits for the server's storage thread. A server shutting down drops the reads still queued
	 * without answering them, so this keeps checking whether to give up.
	 */
	private <T> @Nullable T await(CompletableFuture<T> future) {
		return this.await(future, -1);
	}

	/** The same, giving up after {@code nanos} as well, unless that's negative. */
	private <T> @Nullable T await(CompletableFuture<T> future, long nanos) {
		long start = System.nanoTime();
		while (!this.closed && (nanos < 0 || System.nanoTime() - start < nanos)) {
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

	@Override
	public Level level() {
		return this.level;
	}

	/** Whether the server behind this save is still the one running. */
	@Override
	public boolean isOpen() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		return server != null && server.isRunning() && server.getLevel(this.level.dimension()) == this.serverLevel;
	}

	public static long regionKey(int regionX, int regionZ) {
		return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
	}

	/**
	 * Every region file's chunks, by {@link #regionKey}: for each of its 32 × 32 chunks, indexed
	 * {@code (z << 5) | x}, when the save last wrote it in seconds, or 0 if it has none. The server
	 * writes the chunks it holds first (see {@link #saveHeldChunks}).
	 */
	@Override
	public Map<Long, int[]> scan() {
		this.saveHeldChunks();
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

	/**
	 * Has the server write the chunks it holds and hasn't saved yet, as its autosave does, and waits
	 * until they're in the files, for up to ten seconds, so this listing has them. The server writes
	 * those in time it has to spare and at its autosave; a busy one, such as one Distant Horizons has
	 * generating chunks near the player, leaves them unwritten for minutes, while the chunks it made
	 * further out are written as soon as it lets go of them: the map showed a ring of fog round the
	 * player with the land beyond it filled in. Xaero's World Map has the server write them too
	 * before it reads the save.
	 *
	 * <p>The server only hands the chunks to its storage thread, which writes them one by one after
	 * that. Listed before it's done, they'd be missing until the next listing a minute on, and the
	 * ring would show for that minute.
	 */
	private void saveHeldChunks() {
		try {
			long start = System.nanoTime();
			this.await(this.serverLevel.getServer().submit(() -> {
				long saving = System.nanoTime();
				this.serverLevel.getChunkSource().save(false);
				if (Diorama.DEBUG) {
					Diorama.LOGGER.info("Saved chunks: the server saved its loaded chunks in {} ms", (System.nanoTime() - saving) / 100_000 / 10.0);
				}
			}));
			// Another mod's storage is left to write in its own time, as before.
			if (!this.closed && this.chunkMap.chunkScanner() instanceof IOWorker storage) {
				this.await(storage.synchronize(false), WRITES_WAIT_NANOS);
			}
			if (Diorama.DEBUG) {
				Diorama.LOGGER.info("Saved chunks: the server's chunks were in its files after {} ms", (System.nanoTime() - start) / 100_000 / 10.0);
			}
		} catch (RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't have the server save its loaded chunks", e);
		}
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

	/**
	 * Whether the chunk is finished and how high it reaches, from its status and height maps alone,
	 * or null if the save has none. Much cheaper than {@link #read}.
	 */
	@Override
	public ChunkTags.@Nullable Summary summary(int chunkX, int chunkZ) {
		ChunkTags.Summary summary = this.summarize(chunkX, chunkZ);
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
	@Override
	public int top(int chunkX, int chunkZ) {
		short[] tops = this.tops.get(regionKey(chunkX >> 5, chunkZ >> 5));
		short top = tops == null ? UNKNOWN : tops[((chunkZ & 31) << 5) | (chunkX & 31)];
		if (top == UNKNOWN) {
			this.summary(chunkX, chunkZ);
			top = this.tops.get(regionKey(chunkX >> 5, chunkZ >> 5))[((chunkZ & 31) << 5) | (chunkX & 31)];
		}
		return top == NONE ? Integer.MIN_VALUE : top;
	}

	private ChunkTags.@Nullable Summary summarize(int chunkX, int chunkZ) {
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
		if (NbtUtils.getDataVersion(tag, -1) < ChunkTags.dataVersion()) {
			// Saved by an older version: its fields only mean what they say once it's upgraded.
			SavedChunk chunk = this.read(chunkX, chunkZ);
			return chunk == null ? null : chunk.finished() ? ChunkTags.summaryOf(chunk.surface(), chunk.solid()) : ChunkTags.Summary.UNFINISHED;
		}
		if (!ChunkTags.finished(tag)) {
			return ChunkTags.Summary.UNFINISHED;
		}
		int[] surface = this.tags.surface(tag);
		int[] solid = this.tags.solid(tag);
		return surface == null || solid == null ? ChunkTags.Summary.UNFINISHED : ChunkTags.summaryOf(surface, solid);
	}

	/** The chunk as saved, or null if the save has none or it can't be read. */
	@Override
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
	@Override
	public void forgetTops(long region) {
		this.tops.remove(region);
	}

	/** Lets go of every chunk kept. */
	@Override
	public void clear() {
		this.cache.clear();
	}

	/** Whether the game's server is falling behind, so it needs the disk and the processor more than the map does. */
	@Override
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

	private @Nullable SavedChunk load(int chunkX, int chunkZ) {
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		try {
			Optional<CompoundTag> read = this.await(this.chunkMap.read(pos));
			CompoundTag tag = read == null ? null : read.orElse(null);
			if (tag == null) {
				return null;
			}
			return this.tags.read(chunkX, chunkZ, ((ChunkMapInvoker) this.chunkMap).diorama$upgrade(tag));
		} catch (RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read saved chunk {}", pos, e);
			return null;
		}
	}
}
