package dev.diorama.share;

import dev.diorama.Diorama;
import dev.diorama.MapSession;
import dev.diorama.data.ChunkSave;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.ChunkTags;
import dev.diorama.data.SavedChunk;
import dev.diorama.data.SavedChunks;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.InflaterInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The land a server with Diorama shares, as a save the map fills in from: the same fill that draws
 * the world's save in singleplayer ({@code SavedChunkImport}) draws these, with this player's own
 * resource pack, facing and style, and keeps them in this server's map folder like land the player
 * walked. Only the sky view; caves stay what the player found.
 *
 * <p>The server lists the level's regions, then sends each region's table: which chunks it shares,
 * when each was saved, how high it reaches. That's all a fill needs to plan its tiles. Blocks come
 * when a tile is about to be drawn: the chunks behind the tiles on screen first, then a region's at
 * a time, nearest first, at the rate the server allows. Reads block until the chunk comes, from the
 * fill's own threads; messages arrive on the client thread.
 */
public final class SharedChunks implements ChunkSave {
	/** How long the first listing waits for the server, and a read for its chunk. */
	private static final long LIST_WAIT_MS = 30_000;
	private static final long FIRST_TABLES_WAIT_MS = 5_000;
	private static final long READ_WAIT_MS = 30_000;
	/** An ask not answered in this long is asked again: the server drops asks past its queue's length. */
	private static final long ASK_AGAIN_MS = 60_000;
	private static final int TABLES_PER_ASK = 256;
	/** The map asks for what changed at most this often. */
	private static final long REFRESH_MS = 10_000;
	/** The most NBT one shared chunk may unpack to; a whole chunk's sections with light are about 100 KB. */
	private static final long NBT_QUOTA = 8L << 20;
	private static final int KEPT_CHUNKS = 8192;
	private static final int PARSED_CHUNKS = Runtime.getRuntime().maxMemory() > 3L << 30 ? 2048 : 768;

	private static volatile @Nullable SharedChunks current;

	private final ClientLevel level;
	private final String dimension;
	private final ChunkTags tags;
	private final Object lock = new Object();
	private volatile boolean closed;
	private volatile @Nullable Runnable changed;

	/** The server's clock less this computer's, in milliseconds, as of its last list of regions. */
	private volatile long serverClock;
	/** When the map last asked for what changed, so a burst of map openings asks once. */
	private long refreshedAt;

	// Guarded by lock.
	private @Nullable List<Long> regions;
	private boolean regionsAsked;
	private long regionsAskedAt;
	/** Asked for the regions again: every region's table is asked for again when they come. */
	private boolean refreshing;
	/**
	 * A refresh's tables, kept until the server says the round is done and then taken together. A
	 * tile spanning two regions drawn with one region's new table and the other's old one would be
	 * marked drawn as of now, and the other's change, saved a moment before, would never reach it.
	 */
	private @Nullable Map<Long, Table> round;
	/** The refresh round under way, numbered so a late answer to an earlier one isn't taken for it. */
	private int roundId;
	private long roundStartedAt;
	/**
	 * The server's time, in seconds, that every table in use is at least as recent as: when it
	 * listed the regions before they were asked for. 0 before the first listing.
	 */
	private volatile int tablesAsOf;
	/** The same for the round under way, taken on once it's done. */
	private int roundAsOf;
	private final Map<Long, Table> tables = new HashMap<>();
	/** When each region's table was asked for: one that hasn't come in {@link #ASK_AGAIN_MS} is asked for again. */
	private final Map<Long, Long> tablesAsked = new HashMap<>();
	/** Chunks asked for or come, by {@link ChunkSnapshot#key}. */
	private final Map<Long, Asked> chunks = new LinkedHashMap<>(KEPT_CHUNKS, 0.75F, true);

	/** The fill's own: chunks read, as the fill reads the same ones for neighbouring tiles. */
	private final Map<Long, @Nullable SavedChunk> parsed = new LinkedHashMap<>(PARSED_CHUNKS, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, @Nullable SavedChunk> eldest) {
			return this.size() > PARSED_CHUNKS;
		}
	};
	/** What {@link #parsed} holds was read from saves of these times. */
	private final Map<Long, Integer> parsedTimes = new HashMap<>();

	/** One region's table, indexed {@code (z << 5) | x}. */
	private record Table(int[] times, short[] top, short[] floor, int stamp) {
		/**
		 * This table, with each chunk reaching as high and as low as it did in {@code before} too:
		 * the tiles a removed tower showed in are drawn again, without it. Kept for the session, so
		 * a later round can't drop the old height before the fill has drawn from it.
		 */
		Table reaching(Table before) {
			short[] top = this.top.clone();
			short[] floor = this.floor.clone();
			for (int i = 0; i < this.times.length; i++) {
				if (this.times[i] != 0 && before.times[i] != 0) {
					top[i] = (short) Math.max(top[i], before.top[i]);
					floor[i] = (short) Math.min(floor[i], before.floor[i]);
				}
			}
			return new Table(this.times, top, floor, this.stamp);
		}

	}

	/** A chunk asked for, when, and its answer: the packed chunk and when it was saved, or null if it isn't shared. */
	private static final class Asked {
		final CompletableFuture<Answer> answer = new CompletableFuture<>();
		long askedAt;
	}

	/** {@code packed} is null when the server doesn't share the chunk, as of the save at {@code time}. */
	private record Answer(byte @Nullable [] packed, int time) {
	}

	private SharedChunks(ClientLevel level) {
		this.level = level;
		this.dimension = level.dimension().identifier().toString();
		this.tags = new ChunkTags(level, false);
	}

	/** The server's shared land in {@code level}, or null if the server doesn't share. Client thread. */
	public static @Nullable SharedChunks open(ClientLevel level) {
		ShareSettings settings = ShareClient.settings();
		if (settings == null || !settings.share() || Minecraft.getInstance().getSingleplayerServer() != null) {
			return null;
		}
		SharedChunks before = current;
		if (before != null) {
			before.close();
		}
		SharedChunks shared = new SharedChunks(level);
		current = shared;
		return shared;
	}

	/** For tests: the land the server shares in the level the player is in, or null. */
	public static @Nullable SharedChunks current() {
		return current;
	}

	/** Called whenever new land to draw comes. */
	public void onChange(Runnable changed) {
		this.changed = changed;
	}

	/** The player left the server or the level: what's waiting gives up. */
	static void closeCurrent() {
		SharedChunks shared = current;
		if (shared != null) {
			shared.close();
		}
	}

	@Override
	public Level level() {
		return this.level;
	}

	@Override
	public boolean isOpen() {
		Minecraft mc = Minecraft.getInstance();
		return !this.closed && current == this && mc.level == this.level && mc.getConnection() != null;
	}

	@Override
	public void close() {
		this.closed = true;
		if (current == this) {
			current = null;
		}
		synchronized (this.lock) {
			for (Asked asked : this.chunks.values()) {
				asked.answer.completeExceptionally(new Late("left the server"));
			}
			this.lock.notifyAll();
		}
	}

	@Override
	public Map<Long, int[]> scan() {
		long deadline = System.currentTimeMillis() + LIST_WAIT_MS;
		List<Long> toAsk;
		synchronized (this.lock) {
			if (this.regions == null && (!this.regionsAsked || System.currentTimeMillis() - this.regionsAskedAt > ASK_AGAIN_MS)) {
				this.regionsAsked = true;
				this.regionsAskedAt = System.currentTimeMillis();
				this.send(out -> {
					out.writeByte(ShareProtocol.REGIONS);
					out.writeUTF(this.dimension);
				});
			}
			while (this.regions == null && !this.closed && System.currentTimeMillis() < deadline) {
				this.waitLock(deadline);
			}
			if (this.regions == null) {
				return Map.of();
			}
			toAsk = new ArrayList<>();
			for (long region : this.regions) {
				Long askedAt = this.tablesAsked.get(region);
				if (!this.tables.containsKey(region) && (askedAt == null || System.currentTimeMillis() - askedAt > ASK_AGAIN_MS)) {
					this.tablesAsked.put(region, System.currentTimeMillis());
					toAsk.add(region);
				}
			}
		}
		if (!toAsk.isEmpty()) {
			this.askTables(toAsk, 0);
			long first = System.currentTimeMillis() + FIRST_TABLES_WAIT_MS;
			synchronized (this.lock) {
				while (this.tables.isEmpty() && !this.closed && System.currentTimeMillis() < first) {
					this.waitLock(first);
				}
			}
		}
		Map<Long, int[]> times = new HashMap<>();
		synchronized (this.lock) {
			for (Map.Entry<Long, Table> table : this.tables.entrySet()) {
				// A table's times never change once it's here: a newer table takes its place.
				times.put(table.getKey(), table.getValue().times());
			}
		}
		return times;
	}

	@Override
	public int now() {
		// Not the clock: a tile drawn while a round's tables wait is drawn from the older ones.
		int asOf = this.tablesAsOf;
		return asOf != 0 ? asOf : (int) ((System.currentTimeMillis() + this.serverClock) / 1000);
	}

	/**
	 * Asks the server for the regions and every region's table again. Each chunk saved since this
	 * player's map drew it is drawn again; the rest isn't sent again. Client thread.
	 */
	@Override
	public void refresh() {
		long now = System.currentTimeMillis();
		synchronized (this.lock) {
			// One round at a time; one the server never finished is given up after a minute.
			boolean roundOpen = (this.refreshing || this.round != null) && now - this.roundStartedAt < ASK_AGAIN_MS;
			if (this.regions == null || roundOpen || now - this.refreshedAt < REFRESH_MS) {
				return;
			}
			this.refreshedAt = now;
			this.roundStartedAt = now;
			this.refreshing = true;
			this.round = null;
			this.roundId++;
		}
		if (Diorama.DEBUG) {
			Diorama.LOGGER.info("Shared map: asking what changed");
		}
		this.send(out -> {
			out.writeByte(ShareProtocol.REGIONS);
			out.writeUTF(this.dimension);
		});
	}

	/**
	 * Asks for regions' tables, the ones nearest the player first. {@code round}, if not 0, is the
	 * refresh round these are for: its last ask says so, and the server answers with the round done.
	 */
	private void askTables(List<Long> regions, int round) {
		Entity viewer = MapSession.viewer();
		double x = viewer == null ? 0 : viewer.getX();
		double z = viewer == null ? 0 : viewer.getZ();
		regions.sort(Comparator.comparingDouble(region -> {
			double dx = (region >> 32) * 512.0 + 256 - x;
			double dz = (int) (long) region * 512.0 + 256 - z;
			return dx * dx + dz * dz;
		}));
		// A round with no regions still sends its last ask, for the server to say it's done.
		for (int from = 0; from < regions.size() || from == 0 && round != 0; from += TABLES_PER_ASK) {
			List<Long> part = regions.subList(from, Math.min(regions.size(), from + TABLES_PER_ASK));
			boolean last = from + TABLES_PER_ASK >= regions.size();
			int[] stamps = new int[part.size()];
			synchronized (this.lock) {
				for (int i = 0; i < part.size(); i++) {
					Table known = this.tables.get(part.get(i));
					stamps[i] = known == null ? 0 : known.stamp();
				}
			}
			this.send(out -> {
				out.writeByte(ShareProtocol.TABLES);
				out.writeUTF(this.dimension);
				out.writeInt(round);
				out.writeBoolean(last);
				out.writeShort(part.size());
				for (int i = 0; i < part.size(); i++) {
					out.writeInt((int) (part.get(i) >> 32));
					out.writeInt((int) (long) part.get(i));
					// The server answers only if something there was saved since.
					out.writeInt(stamps[i]);
				}
			});
		}
	}

	private void waitLock(long deadline) {
		try {
			this.lock.wait(Math.max(1, Math.min(100, deadline - System.currentTimeMillis())));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.closed = true;
		}
	}

	@Override
	public ChunkTags.@Nullable Summary summary(int chunkX, int chunkZ) {
		synchronized (this.lock) {
			Table table = this.tables.get(SavedChunks.regionKey(chunkX >> 5, chunkZ >> 5));
			int i = ((chunkZ & 31) << 5) | (chunkX & 31);
			return table == null || table.times()[i] == 0 ? null : new ChunkTags.Summary(true, table.top()[i], table.floor()[i]);
		}
	}

	/**
	 * A bake reads this for every chunk its tile can show. A chunk in a region whose table hasn't
	 * come yet isn't known to be empty: the tile is drawn later, when it is, rather than saved
	 * without it.
	 */
	@Override
	public int top(int chunkX, int chunkZ) {
		this.knownOrLate(chunkX, chunkZ);
		ChunkTags.Summary summary = this.summary(chunkX, chunkZ);
		return summary == null ? Integer.MIN_VALUE : summary.maxY();
	}

	/** Throws {@link Late} if the chunk's region is in the server's list but its table hasn't come. */
	private void knownOrLate(int chunkX, int chunkZ) {
		long region = SavedChunks.regionKey(chunkX >> 5, chunkZ >> 5);
		synchronized (this.lock) {
			if (this.regions != null && !this.tables.containsKey(region) && this.regions.contains(region)) {
				throw new Late("region " + (chunkX >> 5) + ", " + (chunkZ >> 5) + " hasn't come yet");
			}
		}
	}

	/** When the server last saved a shared chunk, or 0 if it doesn't share it. */
	private int time(int chunkX, int chunkZ) {
		synchronized (this.lock) {
			Table table = this.tables.get(SavedChunks.regionKey(chunkX >> 5, chunkZ >> 5));
			return table == null ? 0 : table.times()[((chunkZ & 31) << 5) | (chunkX & 31)];
		}
	}

	@Override
	public boolean prefetches() {
		return true;
	}

	@Override
	public void prefetch(Collection<Long> wanted, boolean urgent) {
		List<Long> ask = new ArrayList<>();
		synchronized (this.lock) {
			long now = System.currentTimeMillis();
			for (long key : wanted) {
				if (this.time((int) (key >> 32), (int) key) == 0) {
					continue;
				}
				if (this.needsAsking(key, now)) {
					ask.add(key);
				} else if (urgent) {
					// Already asked for in the background: asked again as urgent, it moves up the server's queue.
					Asked asked = this.chunks.get(key);
					if (asked != null && !asked.answer.isDone()) {
						ask.add(key);
					}
				}
			}
		}
		this.askChunks(ask, urgent);
	}

	/** Whether a chunk has to be asked for: never asked, an answer older than its table, or an ask gone unanswered. Under the lock. */
	private boolean needsAsking(long key, long now) {
		Asked asked = this.chunks.get(key);
		if (asked != null && asked.answer.isDone() && !asked.answer.isCompletedExceptionally()) {
			Answer answer = asked.answer.getNow(null);
			if (answer != null && answer.time() >= this.time((int) (key >> 32), (int) key)) {
				return false;
			}
		} else if (asked != null && !asked.answer.isDone() && now - asked.askedAt < ASK_AGAIN_MS) {
			return false;
		}
		Asked fresh = asked != null && !asked.answer.isDone() ? asked : new Asked();
		fresh.askedAt = now;
		this.chunks.put(key, fresh);
		return true;
	}

	private void askChunks(List<Long> keys, boolean urgent) {
		for (int from = 0; from < keys.size(); from += ShareProtocol.MAX_ASK) {
			List<Long> part = keys.subList(from, Math.min(keys.size(), from + ShareProtocol.MAX_ASK));
			this.send(out -> {
				out.writeByte(ShareProtocol.CHUNKS);
				out.writeUTF(this.dimension);
				out.writeBoolean(urgent);
				out.writeShort(part.size());
				for (long key : part) {
					out.writeInt((int) (key >> 32));
					out.writeInt((int) key);
				}
			});
		}
	}

	@Override
	public @Nullable SavedChunk read(int chunkX, int chunkZ) {
		if (this.closed) {
			throw new Late("left the server");
		}
		long key = ChunkSnapshot.key(chunkX, chunkZ);
		this.knownOrLate(chunkX, chunkZ);
		int time = this.time(chunkX, chunkZ);
		if (time == 0) {
			return null;
		}
		if (this.parsed.containsKey(key) && this.parsedTimes.getOrDefault(key, 0) >= time) {
			return this.parsed.get(key);
		}
		Asked asked;
		synchronized (this.lock) {
			if (this.needsAsking(key, System.currentTimeMillis())) {
				this.askChunks(List.of(key), false);
			}
			asked = this.chunks.get(key);
		}
		Answer answer;
		try {
			answer = asked.answer.get(READ_WAIT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new Late("chunk " + chunkX + ", " + chunkZ + " didn't come in time");
		} catch (ExecutionException e) {
			throw new Late("chunk " + chunkX + ", " + chunkZ + ": " + e.getCause().getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new Late("interrupted");
		}
		SavedChunk chunk = answer.packed() == null ? null : this.unpack(chunkX, chunkZ, answer.packed());
		this.parsed.put(key, chunk);
		this.parsedTimes.put(key, answer.time());
		return chunk;
	}

	private @Nullable SavedChunk unpack(int chunkX, int chunkZ, byte[] packed) {
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(packed)))) {
			// A server's message, so the NBT it holds is held to what a chunk could be.
			//? if >=1.21 {
			CompoundTag tag = NbtIo.read(in, net.minecraft.nbt.NbtAccounter.create(NBT_QUOTA));
			//?} else {
			/*CompoundTag tag = NbtIo.read(in, new net.minecraft.nbt.NbtAccounter(NBT_QUOTA));
			*///?}
			return this.tags.read(chunkX, chunkZ, tag);
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read shared chunk {}, {}", chunkX, chunkZ, e);
			return null;
		}
	}

	@Override
	public void forgetTops(long region) {
	}

	@Override
	public void clear() {
		this.parsed.clear();
		this.parsedTimes.clear();
	}

	@Override
	public boolean serverBehind() {
		return false;
	}

	/** A message from the server about shared land. Client thread. */
	static void receive(int kind, DataInputStream in) throws IOException {
		SharedChunks shared = current;
		String dimension = in.readUTF();
		if (shared == null || !shared.dimension.equals(dimension)) {
			return;
		}
		switch (kind) {
			case ShareProtocol.REGIONS -> shared.regions(in);
			case ShareProtocol.TABLES -> shared.table(in);
			case ShareProtocol.CHUNKS -> shared.chunk(in);
			case ShareProtocol.TABLES_DONE -> shared.roundDone(in.readInt());
			default -> {
			}
		}
	}

	private void regions(DataInputStream in) throws IOException {
		long serverTime = in.readLong();
		this.serverClock = serverTime - System.currentTimeMillis();
		int listedAt = (int) (serverTime / 1000);
		int count = in.readInt();
		// Never more than the message holds, whatever it says.
		if (count < 0 || count > in.available() / 8) {
			throw new IOException("a list of " + count + " regions in " + in.available() + " bytes");
		}
		List<Long> regions = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			regions.add(SavedChunks.regionKey(in.readInt(), in.readInt()));
		}
		boolean again;
		synchronized (this.lock) {
			this.regions = regions;
			again = this.refreshing;
			this.refreshing = false;
			if (again) {
				this.roundAsOf = listedAt;
			} else if (this.tablesAsOf == 0) {
				// The first listing: every table is asked for after it.
				this.tablesAsOf = listedAt;
			}
			if (again) {
				long now = System.currentTimeMillis();
				for (long region : regions) {
					this.tablesAsked.put(region, now);
				}
				this.round = new HashMap<>();
				this.roundStartedAt = System.currentTimeMillis();
			}
			this.lock.notifyAll();
		}
		if (again) {
			if (Diorama.DEBUG) {
				Diorama.LOGGER.info("Shared map: {} regions listed; asking for their tables", regions.size());
			}
			this.askTables(new ArrayList<>(regions), this.roundId);
		}
	}

	private void table(DataInputStream in) throws IOException {
		int regionX = in.readInt();
		int regionZ = in.readInt();
		int stamp = in.readInt();
		int[] times = new int[1024];
		short[] top = new short[1024];
		short[] floor = new short[1024];
		java.util.zip.Inflater inflater = new java.util.zip.Inflater();
		try {
			DataInputStream packed = new DataInputStream(new InflaterInputStream(in, inflater));
			for (int i = 0; i < 1024; i++) {
				times[i] = packed.readInt();
				if (times[i] != 0) {
					top[i] = packed.readShort();
					floor[i] = packed.readShort();
				}
			}
		} finally {
			inflater.end();
		}
		Table table = new Table(times, top, floor, stamp);
		long key = SavedChunks.regionKey(regionX, regionZ);
		synchronized (this.lock) {
			// Every table while a round is open, new regions' too: drawn with some new tables and
			// some old, a tile could miss a change for good.
			if (this.round != null) {
				this.round.put(key, table);
				// Still coming: a round is given up only after a minute with nothing from it.
				this.roundStartedAt = System.currentTimeMillis();
				if (Diorama.DEBUG) {
					Diorama.LOGGER.info("Shared map: region {}, {} changed", regionX, regionZ);
				}
				return;
			}
			this.tables.put(key, table);
			this.lock.notifyAll();
		}
		this.changed();
	}

	/** The server sent every table of the round: a refresh's tables go in together. */
	private void roundDone(int id) {
		synchronized (this.lock) {
			if (this.round == null || id != this.roundId) {
				return;
			}
			if (Diorama.DEBUG) {
				Diorama.LOGGER.info("Shared map: {} changed regions taken in", this.round.size());
			}
			for (Map.Entry<Long, Table> changed : this.round.entrySet()) {
				Table before = this.tables.get(changed.getKey());
				this.tables.put(changed.getKey(), before == null ? changed.getValue() : changed.getValue().reaching(before));
			}
			this.round = null;
			this.tablesAsOf = this.roundAsOf;
			this.lock.notifyAll();
		}
		this.changed();
	}

	private void chunk(DataInputStream in) throws IOException {
		int chunkX = in.readInt();
		int chunkZ = in.readInt();
		int time = in.readInt();
		int length = in.readInt();
		if (length < 0 || length > ShareProtocol.MAX_CHUNK_BYTES || length > in.available()) {
			throw new IOException("a chunk of " + length + " bytes in " + in.available());
		}
		byte[] packed = new byte[length];
		in.readFully(packed);
		synchronized (this.lock) {
			long key = ChunkSnapshot.key(chunkX, chunkZ);
			Asked asked = this.chunks.get(key);
			if (asked == null || asked.answer.isDone()) {
				asked = new Asked();
				this.chunks.put(key, asked);
			}
			asked.answer.complete(new Answer(length == 0 ? null : packed, time));
			this.trim();
		}
	}

	/**
	 * Keeps at most {@link #KEPT_CHUNKS} answers, the ones read longest ago going first. Asks still
	 * waiting stay, however old: a read is waiting on them.
	 */
	private void trim() {
		if (this.chunks.size() <= KEPT_CHUNKS) {
			return;
		}
		var it = this.chunks.values().iterator();
		while (it.hasNext() && this.chunks.size() > KEPT_CHUNKS) {
			if (it.next().answer.isDone()) {
				it.remove();
			}
		}
	}

	private void changed() {
		Runnable changed = this.changed;
		if (changed != null) {
			changed.run();
		}
	}

	private interface Writer {
		void write(DataOutputStream out) throws IOException;
	}

	/** Sends a message to the server, from the client thread. */
	private void send(Writer writer) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			writer.write(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		byte[] data = bytes.toByteArray();
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (!this.closed && current == this && mc.getConnection() != null) {
				ShareProtocol.sendToServer(data);
			}
		});
	}
}
