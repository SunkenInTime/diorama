package dev.diorama.share;

import dev.diorama.Diorama;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Diorama's half on a dedicated server: it tells each player with Diorama what the server shares
 * of its map, and answers their Diorama's questions about it from the world's save. It never draws
 * anything and stores nothing; the world save is the data. Loads on a dedicated server, so nothing
 * here may name a client class. The loader's common entrypoint calls in from the server's events.
 *
 * <p>Players ask for regions and chunks ({@link ShareProtocol}). The answers come from one worker
 * thread of the share's own, in turns between players, each player at most at the rate the settings
 * allow: reading the save, packing the chunk ({@link ChunkPacker}) and sending it. A chunk loaded
 * right now is read from memory, as it may be newer than its save. Only chunks the share rule allows
 * go out, only of the level the player is in, and only the view from the sky.
 *
 * <p>Only dedicated servers share. In singleplayer the client reads the save itself, and a LAN
 * world's host has no settings file for it.
 */
public final class ShareServer {
	/** The most chunks a player can have waiting; older asks past this are dropped, and asked again. */
	private static final int MAX_WAITING = 16384;
	/** The most tables a player can have waiting: a world 64,000 blocks across has about 16,000 regions. */
	private static final int MAX_TABLES = 16384;
	/** How long a read waits for the server's storage or its thread. */
	private static final long READ_SECONDS = 30;
	/** Chunks read at once: one trip to the server thread for the loaded ones, the rest read together. */
	private static final int BATCH = 16;
	private static volatile @Nullable ShareServer running;
	/** The settings the running server locks, dedicated or not; null if it locks none. */
	private static volatile @Nullable Locked locked;

	private record Locked(MinecraftServer server, ServerLocks locks) {
	}

	private final MinecraftServer server;
	private final ShareSettings settings;
	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
	/** The share worker's own from here on. */
	private final Map<ServerLevel, RegionTables> tables = new HashMap<>();
	private final Map<ServerLevel, ChunkPacker> packers = new HashMap<>();
	/** The tags of the chunks being sent, for the packer's neighbours. */
	private Map<Long, CompoundTag> batch = Map.of();
	private final Thread worker;
	private final Object wake = new Object();
	private volatile boolean stopped;

	private ShareServer(MinecraftServer server, ShareSettings settings) {
		this.server = server;
		this.settings = settings;
		this.worker = new Thread(this::work, "diorama-share");
		this.worker.setDaemon(true);
		this.worker.setPriority(Thread.NORM_PRIORITY - 1);
	}

	/** The server started; {@code configFolder} is the loader's config folder. */
	public static void started(MinecraftServer server, Path configFolder) {
		if (!server.isDedicatedServer()) {
			// Singleplayer shares nothing, but a modpack's own file can still lock settings.
			lock(server, ServerLocks.load(configFolder, false));
			return;
		}
		ShareSettings settings = ShareSettings.load(configFolder);
		// After the share settings, which write the file the first time.
		lock(server, ServerLocks.load(configFolder, true));
		ShareServer share = new ShareServer(server, settings);
		running = share;
		if (settings.share()) {
			share.worker.start();
		}
		Diorama.LOGGER.info("Diorama on the server: {} ({} land, up to {} KB/s a player)",
			settings.share() ? "sharing the map with players who have Diorama" : "not sharing the map",
			settings.land().key(), settings.kilobytesPerSecond());
	}

	private static void lock(MinecraftServer server, ServerLocks locks) {
		locked = locks.any() ? new Locked(server, locks) : null;
		if (locks.any()) {
			Diorama.LOGGER.info("Diorama on the server: locking map settings for players with Diorama: {}", locks);
		}
	}

	/** Whether this server's settings turn the map off in a level's dimension. */
	public static boolean mapOff(ServerLevel level) {
		return locks(level.getServer()).mapOffDimensions().contains(level.dimension().identifier().toString());
	}

	/** The map settings this server decides for its players; {@link ServerLocks#NONE} if it locks none. */
	public static ServerLocks locks(MinecraftServer server) {
		Locked locks = locked;
		return locks != null && locks.server == server ? locks.locks : ServerLocks.NONE;
	}

	public static void stopped(MinecraftServer server) {
		Locked locks = locked;
		if (locks != null && locks.server == server) {
			locked = null;
		}
		ShareServer share = running;
		if (share != null && share.server == server) {
			running = null;
			share.stopped = true;
			share.worker.interrupt();
		}
	}

	/**
	 * A player whose client has Diorama's channel joined, or said it has the channel. Server thread.
	 * Tells them the settings it locks, if any, and on a dedicated server what it shares, which also
	 * tells their Diorama this server has Diorama.
	 */
	public static void listening(ServerPlayer player) {
		Locked locks = locked;
		if (locks != null && player.level().getServer() == locks.server) {
			ShareProtocol.send(player, locks.locks.encode());
		}
		ShareServer share = running;
		if (share == null || player.level().getServer() != share.server) {
			return;
		}
		ShareProtocol.send(player, share.settings.encode());
	}

	/** A message from a player's Diorama. Server thread. */
	public static void receive(ServerPlayer player, byte[] data) {
		ShareServer share = running;
		if (share == null || !share.settings.share() || data.length == 0 || player.level().getServer() != share.server) {
			return;
		}
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			int kind = in.readUnsignedByte();
			String dimension = in.readUTF();
			Session session = share.sessions.computeIfAbsent(player.getUUID(), id -> new Session(player, share.settings.kilobytesPerSecond()));
			session.player = player;
			switch (kind) {
				case ShareProtocol.REGIONS -> session.askRegions(dimension);
				case ShareProtocol.TABLES -> {
					int round = in.readInt();
					boolean last = in.readBoolean();
					int count = in.readUnsignedShort();
					Map<Long, Integer> regions = new LinkedHashMap<>();
					for (int i = 0; i < count; i++) {
						regions.put(RegionTables.key(in.readInt(), in.readInt()), in.readInt());
					}
					session.askTables(dimension, regions, last ? round : 0);
				}
				case ShareProtocol.CHUNKS -> {
					boolean urgent = in.readBoolean();
					int count = in.readUnsignedShort();
					long[] chunks = new long[count];
					for (int i = 0; i < count; i++) {
						chunks[i] = ShareProtocol.chunkKey(in.readInt(), in.readInt());
					}
					session.chunks(dimension, chunks, urgent);
				}
				default -> {
					return;
				}
			}
		} catch (IOException e) {
			Diorama.LOGGER.debug("A malformed share message from {}", player.getName().getString());
			return;
		}
		synchronized (share.wake) {
			share.wake.notifyAll();
		}
	}

	/** One player's asks and allowance. Fields are guarded by the session. */
	private static final class Session {
		volatile ServerPlayer player;
		/** Levels whose regions were asked for: a few at most, as one answer serves them all. */
		private final Deque<String> regionAsks = new ArrayDeque<>();
		/**
		 * Regions whose tables were asked for, in order, for {@link #tablesDimension}, each with the
		 * stamp of the table the client has of it; and after a refresh round's last region, a mark
		 * ({@link #mark}) to say the round is done once everything before it is sent.
		 */
		private final LinkedHashMap<Long, Integer> tables = new LinkedHashMap<>();
		/** The round mark waiting in {@link #tables}, if any: a client has one round open at a time. */
		private long waitingMark = mark(0);
		private String tablesDimension = "";
		private long listedAt = System.nanoTime() - 10_000_000_000L;
		/** Chunks asked for, in the order they go out, for {@link #dimension}. */
		private final LinkedHashSet<Long> chunks = new LinkedHashSet<>();
		private String dimension = "";
		private final double bytesPerSecond;
		private double allowance;
		private long refilledAt = System.nanoTime();
		// Totals for the log, the worker's own.
		long sentChunks;
		long sentBytes;
		long tableBytes;
		long refused;
		long workNanos;
		long startedAt = System.nanoTime();

		Session(ServerPlayer player, int kilobytesPerSecond) {
			this.player = player;
			this.bytesPerSecond = kilobytesPerSecond * 1024.0;
			this.allowance = this.bytesPerSecond;
		}

		synchronized void askRegions(String dimension) {
			if (this.regionAsks.size() < 4) {
				this.regionAsks.add(dimension);
			}
		}

		/** The next level whose regions to list, if the allowance has room and the last list wasn't moments ago. */
		synchronized @Nullable String nextRegionsAsk() {
			this.refill();
			long now = System.nanoTime();
			if (this.regionAsks.isEmpty() || this.allowance <= 0 || now - this.listedAt < 2_000_000_000L) {
				return null;
			}
			this.listedAt = now;
			return this.regionAsks.poll();
		}

		/** More tables to send, past what's waiting, up to {@link #MAX_TABLES}; {@code round}, if not 0, ends a refresh round. */
		synchronized void askTables(String dimension, Map<Long, Integer> regions, int round) {
			if (!dimension.equals(this.tablesDimension)) {
				this.tables.clear();
				this.tablesDimension = dimension;
			}
			for (Map.Entry<Long, Integer> region : regions.entrySet()) {
				if (this.tables.size() >= MAX_TABLES && !this.tables.containsKey(region.getKey())) {
					break;
				}
				this.tables.put(region.getKey(), region.getValue());
			}
			if (round != 0) {
				// Only the newest round's mark waits: the client has given up on any before it.
				this.tables.remove(this.waitingMark);
				this.waitingMark = mark(round);
				this.tables.put(this.waitingMark, round);
			}
		}

		/** The next table to send and its level, if the allowance has room; tables count against it like chunks. */
		synchronized long @Nullable [] nextTable() {
			this.refill();
			if (this.allowance <= 0 || this.tables.isEmpty()) {
				return null;
			}
			var it = this.tables.entrySet().iterator();
			Map.Entry<Long, Integer> next = it.next();
			it.remove();
			return new long[] {next.getKey(), next.getValue()};
		}

		synchronized String tablesDimension() {
			return this.tablesDimension;
		}

		/** A refresh round's end in the queue: no region has x {@code Integer.MIN_VALUE}. */
		static long mark(int round) {
			return ((long) Integer.MIN_VALUE << 32) | (round & 0xFFFFFFFFL);
		}

		static boolean isMark(long key) {
			return (int) (key >> 32) == Integer.MIN_VALUE;
		}

		private void refill() {
			long now = System.nanoTime();
			this.allowance = Math.min(this.bytesPerSecond, this.allowance + (now - this.refilledAt) / 1e9 * this.bytesPerSecond);
			this.refilledAt = now;
		}

		/** Urgent ones go first, in their order; the rest after what's waiting. */
		synchronized void chunks(String dimension, long[] asked, boolean urgent) {
			if (!dimension.equals(this.dimension)) {
				this.chunks.clear();
				this.dimension = dimension;
			}
			// Past MAX_WAITING, what's at the end goes: the oldest asks that aren't urgent. The client
			// asks again for what it still wants.
			if (urgent) {
				LinkedHashSet<Long> rest = new LinkedHashSet<>(this.chunks);
				this.chunks.clear();
				for (long chunk : asked) {
					if (this.chunks.size() < MAX_WAITING) {
						this.chunks.add(chunk);
					}
				}
				for (long chunk : rest) {
					if (this.chunks.size() >= MAX_WAITING) {
						break;
					}
					this.chunks.add(chunk);
				}
			} else {
				for (long chunk : asked) {
					if (this.chunks.size() >= MAX_WAITING) {
						break;
					}
					this.chunks.add(chunk);
				}
			}
		}

		/** The next chunks to send, up to {@code most}, if the allowance has room. */
		synchronized List<Long> nextChunks(int most) {
			this.refill();
			List<Long> next = new ArrayList<>(most);
			if (this.allowance <= 0) {
				return next;
			}
			var it = this.chunks.iterator();
			while (it.hasNext() && next.size() < most) {
				next.add(it.next());
				it.remove();
			}
			return next;
		}

		synchronized String dimension() {
			return this.dimension;
		}

		synchronized void spent(int bytes) {
			this.allowance -= bytes;
		}

	}

	private void work() {
		while (!this.stopped) {
			boolean busy = false;
			try {
				for (Map.Entry<UUID, Session> entry : this.sessions.entrySet()) {
					Session session = entry.getValue();
					if (session.player.hasDisconnected()) {
						this.sessions.remove(entry.getKey());
						this.logSession(session);
						continue;
					}
					String regionsOf = session.nextRegionsAsk();
					if (regionsOf != null) {
						this.regions(session, regionsOf);
						busy = true;
						continue;
					}
					long[] table = session.nextTable();
					if (table != null) {
						String dimension = session.tablesDimension();
						if (Session.isMark(table[0])) {
							// Every table of the round went out before this.
							int round = (int) table[1];
							this.send(session, out -> {
								out.writeByte(ShareProtocol.TABLES_DONE);
								out.writeUTF(dimension);
								out.writeInt(round);
							});
						} else {
							this.table(session, dimension, table[0], (int) table[1]);
						}
						busy = true;
						continue;
					}
					List<Long> chunks = session.nextChunks(BATCH);
					if (!chunks.isEmpty()) {
						this.serve(session, chunks);
						busy = true;
					}
				}
				if (!busy) {
					synchronized (this.wake) {
						// The allowance refills meanwhile.
						this.wake.wait(50);
					}
				}
			} catch (InterruptedException e) {
				return;
			} catch (RuntimeException e) {
				if (!this.stopped) {
					Diorama.LOGGER.error("Sharing the map failed", e);
				}
			}
		}
	}

	private void logSession(Session session) {
		if (session.sentChunks + session.refused == 0) {
			return;
		}
		double seconds = (System.nanoTime() - session.startedAt) / 1e9;
		Diorama.LOGGER.info("Shared map with {}: {} chunks, {} KB ({} bytes a chunk) and {} KB of region tables, {} not shared, {} ms of the share thread a chunk, over {} s",
			session.player.getName().getString(), session.sentChunks, session.sentBytes / 1024,
			session.sentChunks == 0 ? 0 : session.sentBytes / session.sentChunks, session.tableBytes / 1024, session.refused,
			session.sentChunks == 0 ? 0 : String.format(java.util.Locale.ROOT, "%.2f", session.workNanos / 1e6 / session.sentChunks), Math.round(seconds));
	}

	/** The level a player asked about, if it's the one they're in. */
	private @Nullable ServerLevel levelOf(Session session, String dimension) {
		ServerLevel level = (ServerLevel) session.player.level();
		// Under a roof the map's view is a cave view, and caves stay each player's own.
		// And where the server turns the map off, there's none to share.
		return level.dimension().identifier().toString().equals(dimension) && !level.dimensionType().hasCeiling() && !mapOff(level) ? level : null;
	}

	private RegionTables tables(ServerLevel level) {
		return this.tables.computeIfAbsent(level, RegionTables::new);
	}

	/** Lists the regions the level's save has. */
	private void regions(Session session, String dimension) {
		ServerLevel level = this.levelOf(session, dimension);
		if (level == null) {
			return;
		}
		List<Long> found = this.tables(level).regions();
		int sent = this.send(session, out -> {
			out.writeByte(ShareProtocol.REGIONS);
			out.writeUTF(dimension);
			// The clock the save's times are in, for the client to tell how recent its drawing is.
			out.writeLong(System.currentTimeMillis());
			out.writeInt(found.size());
			for (long region : found) {
				out.writeInt((int) (region >> 32));
				out.writeInt((int) region);
			}
		});
		session.tableBytes += sent;
		session.spent(sent);
	}

	/** Sends a region's table: for each chunk the share rule allows, when it was saved, how high it reaches and how deep it shows. */
	private void table(Session session, String dimension, long region, int known) {
		ServerLevel level = this.levelOf(session, dimension);
		if (level == null) {
			return;
		}
		boolean generated = this.settings.land() == ShareSettings.Land.GENERATED;
		int regionX = (int) (region >> 32);
		int regionZ = (int) region;
		RegionTables.Table table = this.tables(level).table(regionX, regionZ, generated, true);
		// The table's stamp: any chunk saved again, or scanned again after a failure, changes it.
		int stamp = java.util.Arrays.hashCode(table.saved);
		stamp = stamp == 0 ? 1 : stamp;
		if (known != 0 && stamp == known) {
			// Nothing in it saved since the client's table. One it has no table of yet always gets one:
			// tiles beside a region wait for its table.
			return;
		}
		int sentStamp = stamp;
		int sent = this.send(session, out -> {
			out.writeByte(ShareProtocol.TABLES);
			out.writeUTF(dimension);
			out.writeInt(regionX);
			out.writeInt(regionZ);
			out.writeInt(sentStamp);
			java.util.zip.Deflater deflater = new java.util.zip.Deflater();
			try {
				DeflaterOutputStream deflated = new DeflaterOutputStream(out, deflater);
				DataOutputStream packed = new DataOutputStream(deflated);
				for (int i = 0; i < 1024; i++) {
					packed.writeInt(table.shared[i]);
					if (table.shared[i] != 0) {
						packed.writeShort(table.top[i]);
						packed.writeShort(table.floor[i]);
					}
				}
				packed.flush();
				deflated.finish();
			} finally {
				deflater.end();
			}
		});
		session.tableBytes += sent;
		session.spent(sent);
	}

	/**
	 * Sends a few chunks, or says they're not shared: those loaded right now copied in one trip to
	 * the server thread, the rest read from the save all at once.
	 */
	private void serve(Session session, List<Long> chunks) {
		long start = System.nanoTime();
		String dimension = session.dimension();
		ServerLevel level = this.levelOf(session, dimension);
		if (level == null) {
			return;
		}
		boolean generated = this.settings.land() == ShareSettings.Land.GENERATED;
		RegionTables tables = this.tables(level);
		Map<Long, Integer> times = new LinkedHashMap<>();
		for (long chunk : chunks) {
			int chunkX = (int) (chunk >> 32);
			int chunkZ = (int) (long) chunk;
			times.put(chunk, tables.table(chunkX >> 5, chunkZ >> 5, generated).shared[((chunkZ & 31) << 5) | (chunkX & 31)]);
		}
		List<Long> wanted = times.entrySet().stream().filter(e -> e.getValue() != 0).map(Map.Entry::getKey).toList();
		Map<Long, CompoundTag> tags = this.tags(level, tables, wanted);
		this.batch = tags;
		this.packer(level, tables).forgetIfOld();
		try {
			int packed = 0;
			for (long chunk : chunks) {
				int chunkX = (int) (chunk >> 32);
				int chunkZ = (int) (long) chunk;
				CompoundTag tag = tags.get(chunk);
				int time = times.get(chunk);
				if (tag == null && time != 0) {
					// Shared, but it couldn't be read just now: no answer, so the client asks again.
					continue;
				}
				byte[] body = tag != null && RegionTables.allowed(tag, generated) ? this.packer(level, tables).pack(chunkX, chunkZ, tag) : null;
				if (body != null && body.length > ShareProtocol.MAX_CHUNK_BYTES) {
					body = null;
				}
				byte[] sending = body;
				int sent = this.send(session, out -> {
					out.writeByte(ShareProtocol.CHUNKS);
					out.writeUTF(dimension);
					out.writeInt(chunkX);
					out.writeInt(chunkZ);
					// The save time even for a refusal: the client asks again once that changes.
					out.writeInt(time);
					out.writeInt(sending == null ? 0 : sending.length);
					if (sending != null) {
						out.write(sending);
					}
				});
				session.spent(sent);
				if (body == null) {
					session.refused++;
				} else {
					session.sentChunks++;
					session.sentBytes += sent;
					packed++;
				}
			}
			if (packed > 0) {
				session.workNanos += System.nanoTime() - start;
			}
		} finally {
			this.batch = Map.of();
		}
	}

	/** The chunks' tags, brought up to this version: loaded ones as they are now, the rest as saved. */
	private Map<Long, CompoundTag> tags(ServerLevel level, RegionTables tables, List<Long> chunks) {
		Map<Long, CompoundTag> tags = new HashMap<>();
		if (chunks.isEmpty()) {
			return tags;
		}
		try {
			Map<Long, Object> copies = this.server.submit(() -> {
				Map<Long, Object> loaded = new HashMap<>();
				for (long chunk : chunks) {
					LevelChunk found = level.getChunkSource().getChunkNow((int) (chunk >> 32), (int) (long) chunk);
					if (found != null) {
						loaded.put(chunk, copy(level, found));
					}
				}
				return loaded;
			}).get(READ_SECONDS, TimeUnit.SECONDS);
			Map<Long, CompletableFuture<java.util.Optional<CompoundTag>>> reads = new LinkedHashMap<>();
			for (long chunk : chunks) {
				if (!copies.containsKey(chunk)) {
					reads.put(chunk, tables.readLater(new ChunkPos((int) (chunk >> 32), (int) (long) chunk)));
				}
			}
			// One at a time: a chunk that can't be read leaves the rest of the batch alone.
			for (Map.Entry<Long, Object> copy : copies.entrySet()) {
				try {
					tags.put(copy.getKey(), written(copy.getValue()));
				} catch (RuntimeException e) {
					this.couldNotRead(copy.getKey(), e);
				}
			}
			for (Map.Entry<Long, CompletableFuture<java.util.Optional<CompoundTag>>> read : reads.entrySet()) {
				try {
					java.util.Optional<CompoundTag> saved = read.getValue().get(READ_SECONDS, TimeUnit.SECONDS);
					if (saved.isPresent()) {
						tags.put(read.getKey(), tables.upgrade(saved.get()));
					}
				} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException e) {
					this.couldNotRead(read.getKey(), e);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			if (!this.stopped) {
				Diorama.LOGGER.warn("Couldn't read {} chunks to share them", chunks.size(), e);
			}
		}
		return tags;
	}

	private void couldNotRead(long chunk, Exception e) {
		if (!this.stopped) {
			Diorama.LOGGER.warn("Couldn't read chunk {}, {} to share it", (int) (chunk >> 32), (int) (long) chunk, e);
		}
	}

	private ChunkPacker packer(ServerLevel level, RegionTables tables) {
		return this.packers.computeIfAbsent(level, l -> new ChunkPacker(l, tables.tags(), (x, z) -> {
			// A neighbour in the same batch is already here.
			CompoundTag known = this.batch.get(ShareProtocol.chunkKey(x, z));
			return known != null ? known : this.tag(l, tables, x, z);
		}));
	}

	/**
	 * A chunk's tag, brought up to this version: from memory if it's loaded, as it may be newer than
	 * its save, else as saved. Null if there's neither.
	 */
	private @Nullable CompoundTag tag(ServerLevel level, RegionTables tables, int chunkX, int chunkZ) {
		try {
			CompletableFuture<@Nullable Object> loaded = this.server.submit(() -> {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				return chunk == null ? null : copy(level, chunk);
			});
			Object copy = loaded.get(READ_SECONDS, TimeUnit.SECONDS);
			if (copy != null) {
				return written(copy);
			}
			return tables.read(new ChunkPos(chunkX, chunkZ));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			if (!this.stopped) {
				Diorama.LOGGER.warn("Couldn't read chunk {}, {} to share it", chunkX, chunkZ, e);
			}
			return null;
		}
	}

	/** What saving a loaded chunk would write, taken on the server thread. */
	private static Object copy(ServerLevel level, LevelChunk chunk) {
		//? if >=1.21.2 {
		return net.minecraft.world.level.chunk.storage.SerializableChunkData.copyOf(level, chunk);
		//?} else {
		/*return net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk);
		*///?}
	}

	/** The tag of a {@link #copy}, off the server thread. */
	private static CompoundTag written(Object copy) {
		//? if >=1.21.2 {
		return ((net.minecraft.world.level.chunk.storage.SerializableChunkData) copy).write();
		//?} else {
		/*return (CompoundTag) copy;
		*///?}
	}

	private interface Writer {
		void write(DataOutputStream out) throws IOException;
	}

	/** Sends a message and returns its size. */
	private int send(Session session, Writer writer) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			writer.write(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		byte[] data = bytes.toByteArray();
		ShareProtocol.send(session.player, data);
		return data.length;
	}
}
