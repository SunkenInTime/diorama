package dev.diorama.data;

import dev.diorama.Diorama;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * The biome at the surface of land the player has been to, kept with the map on a server, where
 * there's no world save to ask ({@link SavedBiomes}): what the map's tooltip names over land whose
 * chunks are gone. The map's tiles are pictures and keep no biomes.
 *
 * <p>One biome for every 4 × 4 columns, the size the game keeps biomes at, taken at the top block
 * of the middle of each when the client has the chunk. A file holds a region of 32 × 32 chunks: the
 * names of its biomes, then a number for each of its 128 × 128 cells, compressed. That came to
 * about a byte a chunk on a generated 26.3 world, against some 3,300 for the chunk's pictures.
 *
 * <p>For the client thread, which never waits on the disk: files are read and written on a thread
 * of their own. A region answers nothing until its file is read; what's seen meanwhile is kept and
 * goes on top of what the file had.
 */
public final class SeenBiomes {
	private static final int MAGIC = 0x44424D31;
	/** Cells across a region: 32 chunks of 4. */
	private static final int CELLS = 128;
	/** Regions kept in memory, 32 KB each; more while they wait to be read or written. */
	private static final int KEPT = 32;
	/** One thread, so a region's reads and writes happen in the order they were asked for. */
	private static final ExecutorService DISK = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Diorama biomes");
		thread.setDaemon(true);
		return thread;
	});

	/** A region's cells, 0 for one never seen, else one more than the index of its biome's name. */
	private static final class Region {
		final short[] cells = new short[CELLS * CELLS];
		final List<String> names = new ArrayList<>();
		final Map<String, Integer> numbers = new HashMap<>();
		/** Whether the file has been read into this. */
		boolean read;
		/** Seen since the last write that went well. */
		boolean changed;
		/** A write is on its way. */
		boolean writing;

		/** The number of a biome's name here, or 0 when it can't be kept. */
		short number(String name) {
			Integer known = this.numbers.get(name);
			if (known == null) {
				if (name.length() > 255 || this.names.size() >= Short.MAX_VALUE) {
					return 0;
				}
				this.names.add(name);
				known = this.names.size();
				this.numbers.put(name, known);
			}
			return known.shortValue();
		}
	}

	/** A region as its file has it. */
	private record Saved(List<String> names, short[] cells) {
	}

	private final Path folder;
	private final Map<Long, Region> regions = new LinkedHashMap<>(KEPT, 0.75F, true);

	public SeenBiomes(Path folder) {
		this.folder = folder;
	}

	/** Takes the surface biomes of a chunk the client has. */
	public void record(LevelChunk chunk) {
		int chunkX = chunk.getPos().x();
		int chunkZ = chunk.getPos().z();
		Region region = this.region(chunkX >> 5, chunkZ >> 5);
		for (int z = 0; z < 4; z++) {
			for (int x = 0; x < 4; x++) {
				// The height map gives the first open block: the one under it is the surface.
				int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x * 4 + 2, z * 4 + 2) - 1;
				int quartX = chunkX * 4 + x;
				int quartZ = chunkZ * 4 + z;
				String name = chunk.getNoiseBiome(quartX, y >> 2, quartZ).unwrapKey().map(key -> key.identifier().toString()).orElse(null);
				short number = name == null ? 0 : region.number(name);
				int cell = cell(quartX, quartZ);
				if (number != 0 && region.cells[cell] != number) {
					region.cells[cell] = number;
					region.changed = true;
				}
			}
		}
	}

	/**
	 * The name of the biome seen at the surface over this block, like {@code minecraft:river}; null
	 * where none was, and for a moment while the region's file is read.
	 */
	public @Nullable String at(int blockX, int blockZ) {
		Region region = this.region(blockX >> 9, blockZ >> 9);
		int number = region.cells[cell(blockX >> 2, blockZ >> 2)];
		return number <= 0 || number > region.names.size() ? null : region.names.get(number - 1);
	}

	/** Has the regions that changed written, in the background. */
	public void save() {
		this.regions.forEach(this::write);
	}

	/** Writes what changed and waits for it, briefly: the map is closing. */
	public void close() {
		this.save();
		try {
			DISK.submit(() -> {
			}).get(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
			Diorama.LOGGER.warn("Couldn't finish saving the biomes in {}", this.folder, e);
		}
	}

	private static int cell(int quartX, int quartZ) {
		return ((quartZ & (CELLS - 1)) * CELLS) | (quartX & (CELLS - 1));
	}

	private Region region(int regionX, int regionZ) {
		long key = SavedChunks.regionKey(regionX, regionZ);
		Region region = this.regions.get(key);
		if (region != null) {
			return region;
		}
		Region made = new Region();
		this.regions.put(key, made);
		Path file = this.file(key);
		CompletableFuture.supplyAsync(() -> read(file), DISK).thenAcceptAsync(saved -> merge(made, saved), Minecraft.getInstance());
		// The ones used longest ago go, once they're read and have nothing left to write: one that
		// changed is written first and goes another time, so a write that fails loses nothing.
		Iterator<Map.Entry<Long, Region>> eldest = this.regions.entrySet().iterator();
		for (int over = this.regions.size() - KEPT; over > 0 && eldest.hasNext(); ) {
			Map.Entry<Long, Region> entry = eldest.next();
			Region old = entry.getValue();
			if (old == made) {
				continue;
			}
			if (old.read && !old.changed && !old.writing) {
				eldest.remove();
				over--;
			} else {
				this.write(entry.getKey(), old);
			}
		}
		return made;
	}

	/** Puts what the file had under what's been seen since the region was made. Client thread. */
	private static void merge(Region region, @Nullable Saved saved) {
		if (saved != null) {
			short[] numbers = new short[saved.names().size() + 1];
			for (int i = 0; i < saved.names().size(); i++) {
				numbers[i + 1] = region.number(saved.names().get(i));
			}
			for (int i = 0; i < region.cells.length; i++) {
				if (region.cells[i] == 0) {
					region.cells[i] = numbers[saved.cells()[i]];
				}
			}
		}
		region.read = true;
	}

	private Path file(long key) {
		return this.folder.resolve("b." + (int) (key >> 32) + "." + (int) key + ".bin");
	}

	/** The region as saved; null where there's no file, or one that doesn't read. Disk thread. */
	private static @Nullable Saved read(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("not a biome file");
			}
			int count = in.readUnsignedShort();
			List<String> names = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				names.add(in.readUTF());
			}
			short[] cells = new short[CELLS * CELLS];
			for (int i = 0; i < cells.length; i++) {
				int number = in.readShort();
				cells[i] = number < 0 || number > count ? 0 : (short) number;
			}
			return new Saved(names, cells);
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read the biomes in {}", file, e);
			return null;
		}
	}

	/**
	 * Has a region written if it changed, once its file has been read: before that it doesn't hold
	 * what the file has. A write that fails leaves it changed, for the next save to try again.
	 */
	private void write(long key, Region region) {
		if (!region.changed || !region.read || region.writing) {
			return;
		}
		Saved copy = new Saved(List.copyOf(region.names), region.cells.clone());
		Path file = this.file(key);
		region.changed = false;
		region.writing = true;
		CompletableFuture.supplyAsync(() -> this.write(file, copy), DISK).thenAcceptAsync(written -> {
			region.writing = false;
			region.changed |= !written;
		}, Minecraft.getInstance());
	}

	/** Disk thread. */
	private boolean write(Path file, Saved region) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
				out.writeInt(MAGIC);
				out.writeShort(region.names().size());
				for (String name : region.names()) {
					out.writeUTF(name);
				}
				for (short number : region.cells()) {
					out.writeShort(number);
				}
			}
			Files.createDirectories(this.folder);
			// Whole or not at all: a file cut short by a crash would lose the region.
			Path part = file.resolveSibling(file.getFileName() + ".part");
			Files.write(part, bytes.toByteArray());
			try {
				Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't save the biomes in {}", file, e);
			return false;
		}
	}
}
