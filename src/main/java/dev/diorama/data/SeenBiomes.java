package dev.diorama.data;

import dev.diorama.Diorama;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
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
 * <p>For the client thread. Files are small, so they're read and written there, a region at a time.
 */
public final class SeenBiomes {
	private static final int MAGIC = 0x44424D31;
	/** Cells across a region: 32 chunks of 4. */
	private static final int CELLS = 128;
	/** Regions kept in memory, 32 KB each. */
	private static final int KEPT = 32;

	/** A region's cells, 0 for one never seen, else one more than the index of its biome's name. */
	private static final class Region {
		final short[] cells = new short[CELLS * CELLS];
		final List<String> names = new ArrayList<>();
		final Map<String, Integer> numbers = new HashMap<>();
		boolean changed;

		short number(String name) {
			Integer known = this.numbers.get(name);
			if (known == null) {
				this.names.add(name);
				known = this.names.size();
				this.numbers.put(name, known);
			}
			return known.shortValue();
		}
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
				int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x * 4 + 2, z * 4 + 2);
				int quartX = chunkX * 4 + x;
				int quartZ = chunkZ * 4 + z;
				String name = chunk.getNoiseBiome(quartX, y >> 2, quartZ).unwrapKey().map(key -> key.identifier().toString()).orElse(null);
				if (name == null || name.length() > 255 || region.names.size() >= Short.MAX_VALUE && !region.numbers.containsKey(name)) {
					continue;
				}
				short number = region.number(name);
				int cell = cell(quartX, quartZ);
				if (region.cells[cell] != number) {
					region.cells[cell] = number;
					region.changed = true;
				}
			}
		}
	}

	/** The name of the biome seen at the surface over this block, like {@code minecraft:river}; null where none was. */
	public @Nullable String at(int blockX, int blockZ) {
		Region region = this.region(blockX >> 9, blockZ >> 9);
		int number = region.cells[cell(blockX >> 2, blockZ >> 2)];
		return number <= 0 || number > region.names.size() ? null : region.names.get(number - 1);
	}

	/** Writes the regions that changed. */
	public void save() {
		for (Map.Entry<Long, Region> entry : this.regions.entrySet()) {
			this.save(entry.getKey(), entry.getValue());
		}
	}

	private static int cell(int quartX, int quartZ) {
		return ((quartZ & (CELLS - 1)) * CELLS) | (quartX & (CELLS - 1));
	}

	private Region region(int regionX, int regionZ) {
		long key = SavedChunks.regionKey(regionX, regionZ);
		Region region = this.regions.get(key);
		if (region == null) {
			region = this.read(key);
			this.regions.put(key, region);
			// The one used longest ago goes, written first if it changed.
			Iterator<Map.Entry<Long, Region>> eldest = this.regions.entrySet().iterator();
			while (this.regions.size() > KEPT && eldest.hasNext()) {
				Map.Entry<Long, Region> entry = eldest.next();
				this.save(entry.getKey(), entry.getValue());
				eldest.remove();
			}
		}
		return region;
	}

	private Path file(long key) {
		return this.folder.resolve("b." + (int) (key >> 32) + "." + (int) key + ".bin");
	}

	/** The region as saved; empty where there's no file, or one that doesn't read. */
	private Region read(long key) {
		Region region = new Region();
		Path file = this.file(key);
		if (!Files.isRegularFile(file)) {
			return region;
		}
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("not a biome file");
			}
			int names = in.readUnsignedShort();
			for (int i = 0; i < names; i++) {
				region.number(in.readUTF());
			}
			for (int i = 0; i < region.cells.length; i++) {
				int number = in.readShort();
				region.cells[i] = number < 0 || number > names ? 0 : (short) number;
			}
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read the biomes in {}", file, e);
			return new Region();
		}
		return region;
	}

	private void save(long key, Region region) {
		if (!region.changed) {
			return;
		}
		Path file = this.file(key);
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
				out.writeInt(MAGIC);
				out.writeShort(region.names.size());
				for (String name : region.names) {
					out.writeUTF(name);
				}
				for (short number : region.cells) {
					out.writeShort(number);
				}
			}
			Files.createDirectories(this.folder);
			// Whole or not at all: a file cut short by a crash would lose the region.
			Path part = file.resolveSibling(file.getFileName() + ".part");
			Files.write(part, bytes.toByteArray());
			Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
			region.changed = false;
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't save the biomes in {}", file, e);
		}
	}
}
