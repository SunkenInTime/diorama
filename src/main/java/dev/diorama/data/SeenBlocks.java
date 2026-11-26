package dev.diorama.data;

import dev.diorama.Diorama;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;

/**
 * Which blocks the player has had line of sight to, one bit per block. The client thread is the
 * only writer and bits are only ever set, so bake threads can read without locking; at worst
 * they see a block as unseen for one more bake. Saved to disk so fog-of-war progress survives
 * leaving the world, on a thread of its own: a well-explored world's table takes tens of
 * milliseconds to compress.
 */
public final class SeenBlocks {
	private static final int MAGIC = 0x4F4D5331;
	/** One thread, so saves reach the file in the order they were taken. */
	private static final ExecutorService DISK = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "diorama-seen-writer");
		thread.setDaemon(true);
		return thread;
	});

	private final ConcurrentHashMap<Long, long[]> sections = new ConcurrentHashMap<>();
	/**
	 * Set after a bit is, and taken back by the disk thread just before it lists the table: a bit
	 * that write misses was set after that, so it leaves the table changed for the next one.
	 */
	private final AtomicBoolean changed = new AtomicBoolean();
	/** A save is waiting behind the one being written. It reads the table when it starts, so one is enough. */
	private final AtomicBoolean queued = new AtomicBoolean();
	private volatile @Nullable WriteHook writeHook;

	public boolean isSeen(int x, int y, int z) {
		long[] bits = this.sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
		if (bits == null) {
			return false;
		}
		int i = index(x, y, z);
		return (bits[i >>> 6] & (1L << i)) != 0;
	}

	/** Marks a block seen; true if it wasn't before. */
	public boolean markSeen(int x, int y, int z) {
		long[] bits = this.sections.computeIfAbsent(SectionPos.asLong(x >> 4, y >> 4, z >> 4), k -> new long[64]);
		int i = index(x, y, z);
		long mask = 1L << i;
		if ((bits[i >>> 6] & mask) != 0) {
			return false;
		}
		bits[i >>> 6] |= mask;
		this.changed.set(true);
		return true;
	}

	private static int index(int x, int y, int z) {
		return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
	}

	public static SeenBlocks load(Path file) {
		SeenBlocks seen = new SeenBlocks();
		if (!Files.isRegularFile(file)) {
			return seen;
		}
		try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(new InflaterInputStream(in))) {
			if (data.readInt() != MAGIC) {
				return seen;
			}
			int count = data.readInt();
			// A section's key and bits in one read, not 65: each read is a trip through the inflater.
			ByteBuffer section = ByteBuffer.allocate(Long.BYTES * 65);
			for (int s = 0; s < count; s++) {
				data.readFully(section.array());
				long key = section.getLong(0);
				long[] bits = new long[64];
				for (int i = 0; i < 64; i++) {
					bits[i] = section.getLong(Long.BYTES * (i + 1));
				}
				seen.sections.put(key, bits);
			}
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't read fog-of-war progress from {}", file, e);
		}
		return seen;
	}

	/**
	 * Has the bits written in the background if any changed since the last save. Client thread; the
	 * disk thread lists, compresses and writes them. With a save already waiting to be written this
	 * does nothing: that one will take the table as it is when it starts.
	 */
	public void save(Path file) {
		if (this.changed.get() && this.queued.compareAndSet(false, true)) {
			DISK.execute(() -> {
				this.queued.set(false);
				this.writeIfChanged(file);
			});
		}
	}

	/**
	 * The map is closing: writes what's changed once every save before it has finished, so one
	 * that failed is tried again, and waits for that. Waits through an interrupt too: the map
	 * reopened before the write lands would read the older file and later save over this one.
	 */
	public void close(Path file) {
		Future<?> last = DISK.submit(() -> this.writeIfChanged(file));
		boolean interrupted = false;
		while (true) {
			try {
				last.get();
				break;
			} catch (InterruptedException e) {
				interrupted = true;
			} catch (ExecutionException e) {
				Diorama.LOGGER.warn("Couldn't finish saving fog-of-war progress to {}", file, e);
				break;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	/** Disk thread. On failure the table counts as changed, so the next save tries again. */
	private void writeIfChanged(Path file) {
		if (!this.changed.getAndSet(false)) {
			return;
		}
		try {
			WriteHook hook = this.writeHook;
			if (hook != null) {
				hook.beforeWrite();
			}
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			// Listed first, so the count matches the sections written while the client thread adds more.
			List<Map.Entry<Long, long[]>> entries = new ArrayList<>(this.sections.entrySet());
			// The buffer hands the compressor 32 KB at a time instead of each 8-byte long.
			try (OutputStream out = Files.newOutputStream(temp); DataOutputStream data = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(out), 1 << 15))) {
				data.writeInt(MAGIC);
				data.writeInt(entries.size());
				for (Map.Entry<Long, long[]> entry : entries) {
					data.writeLong(entry.getKey());
					for (long word : entry.getValue()) {
						data.writeLong(word);
					}
				}
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			this.changed.set(true);
			Diorama.LOGGER.error("Couldn't save fog-of-war progress to {}", file, e);
		}
	}

	/** For tests: runs on the disk thread before each write; throwing fails that write, as a full disk would. */
	public void beforeEachWrite(@Nullable WriteHook hook) {
		this.writeHook = hook;
	}

	/** For tests: see {@link #beforeEachWrite}. */
	@FunctionalInterface
	public interface WriteHook {
		void beforeWrite() throws IOException;
	}
}
