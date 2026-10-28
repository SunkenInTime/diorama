package dev.diorama.test;

import com.mojang.datafixers.util.Pair;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.ChunkSource;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipInputStream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * RESEARCH for #152, not for merging: how Diorama tiles look when baked from Xaero's World Map's
 * multiplayer files instead of real chunks.
 *
 * <p>Joins a dedicated server with Xaero's World Map installed, visits a few places, bakes the
 * tiles round each from the real chunks, leaves (Xaero's map saves its regions), joins again,
 * reads Xaero's region files, rebuilds chunks from them two ways and bakes the same tiles from
 * those. Pictures land in {@code xaero-map-import/}. Opt-in: {@code DIORAMA_TESTS=xaero-map-import},
 * with Xaero's World Map loaded ({@code -Dfabric.addMods}). {@code DIORAMA_XAERO_SPOTS} picks the
 * places: {@code x z}, {@code biome:<id>} or {@code village}, separated by {@code ;}.
 */
final class XaeroMapImportExperiment {
	/** One column as Xaero's World Map keeps it. */
	record Column(BlockState state, int height, int top, BlockState[] overlays, ResourceKey<Biome> biome, int light) {
	}

	private XaeroMapImportExperiment() {
	}

	static void run(ClientGameTestContext context, java.util.Properties properties) {
		String env = System.getenv("DIORAMA_XAERO_SPOTS");
		String[] spots = (env == null || env.isBlank() ? "village;biome:minecraft:forest;biome:minecraft:jagged_peaks;biome:minecraft:beach" : env).split(";");
		properties.setProperty("view-distance", "16");
		properties.setProperty("simulation-distance", "8");
		Path out = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("xaero-map-import"));
		Path xaero = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("xaero").resolve("world-map"));
		int renderDistance = context.computeOnClient(mc -> mc.options.renderDistance().get());
		context.runOnClient(mc -> mc.options.renderDistance().set(16));
		StringBuilder log = new StringBuilder();
		List<String> names = new ArrayList<>();
		List<List<TileKey>> keys = new ArrayList<>();
		List<int[][]> normal = new ArrayList<>();
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed(System.getProperty("diorama.seed", "diorama")))
			.createServer(properties)) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				server.runCommand("time set noon");
				server.runCommand("weather clear");
				server.runCommand("gamerule doDaylightCycle false");
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 150, 2400);
				for (int i = 0; i < spots.length; i++) {
					String spot = spots[i].trim();
					int[] at = server.computeOnServer(s -> find(s.overworld(), spot));
					if (at == null) {
						log.append(spot).append(": not found\n");
						continue;
					}
					server.runCommand("tp @a " + at[0] + " 200 " + at[1]);
					context.waitTicks(60);
					waitForChunks(context);
					String name = String.format(Locale.ROOT, "%d-%s", i, spot.replaceAll("[^a-z0-9]+", "_"));
					List<TileKey> tiles = tilesAround(context, at[0], at[1]);
					int[][] baked = context.computeOnClient(mc -> {
						int[][] pixels = new int[tiles.size()][];
						for (int t = 0; t < tiles.size(); t++) {
							pixels[t] = TileBaker.bake(tiles.get(t), MapSession.get().map(), BlockLooks.get(), null, false).mips()[0];
						}
						return pixels;
					});
					names.add(name);
					keys.add(tiles);
					normal.add(baked);
					log.append(name).append(": at ").append(at[0]).append(' ').append(at[1]).append('\n');
				}
				// Xaero's map writes what it has seen a little behind the chunks arriving.
				settleTicks(context, 600);
			}
			// Leaving the server is when Xaero's World Map saves every region it has.
			context.waitFor(mc -> regionFiles(xaero).size() > 0, 1200);
			context.waitTicks(200);
			try (TestDedicatedServerConnection connection = server.connect()) {
				context.waitFor(mc -> MapSession.get() != null && mc.level != null, 2400);
				List<Path> files = regionFiles(xaero);
				long bytes = 0;
				for (Path f : files) {
					bytes += f.toFile().length();
				}
				long readStart = System.nanoTime();
				Map<Long, Column[]> columns = context.computeOnClient(mc -> read(files, mc.level));
				long readNanos = System.nanoTime() - readStart;
				log.append(String.format(Locale.ROOT, "xaero: %d region files, %d KB, %d chunks, read in %d ms%n",
					files.size(), bytes / 1024, columns.size(), readNanos / 1_000_000));
				for (boolean guess : new boolean[] {false, true}) {
					long buildStart = System.nanoTime();
					ChunkSource source = context.computeOnClient(mc -> rebuild(columns, mc.level, guess));
					log.append(String.format(Locale.ROOT, "rebuild %s: %d ms%n", guess ? "guessed" : "raw", (System.nanoTime() - buildStart) / 1_000_000));
					for (int i = 0; i < names.size(); i++) {
						List<TileKey> tiles = keys.get(i);
						long bakeStart = System.nanoTime();
						int[][] baked = context.computeOnClient(mc -> {
							int[][] pixels = new int[tiles.size()][];
							for (int t = 0; t < tiles.size(); t++) {
								pixels[t] = TileBaker.bake(tiles.get(t), source, BlockLooks.get(), null, false).mips()[0];
							}
							return pixels;
						});
						log.append(String.format(Locale.ROOT, "%s %s: %d ms per tile, %s%n", names.get(i), guess ? "guessed" : "raw",
							(System.nanoTime() - bakeStart) / 1_000_000 / tiles.size(), compare(normal.get(i), baked)));
						write(out.resolve(names.get(i) + (guess ? "-guessed.png" : "-raw.png")), tiles, baked);
						if (guess) {
							write(out.resolve(names.get(i) + "-normal.png"), tiles, normal.get(i));
						}
					}
				}
			}
		} finally {
			context.runOnClient(mc -> mc.options.renderDistance().set(renderDistance));
		}
		try {
			Files.createDirectories(out);
			Files.writeString(out.resolve("summary.txt"), log.toString());
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		dev.diorama.Diorama.LOGGER.info("Xaero map import experiment:\n{}", log);
	}

	private static int[] find(net.minecraft.server.level.ServerLevel level, String spot) {
		if (spot.equals("village")) {
			BlockPos pos = level.findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, 100, false);
			return pos == null ? null : new int[] {pos.getX(), pos.getZ()};
		}
		if (spot.startsWith("biome:")) {
			ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, Identifier.parse(spot.substring(6)));
			Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(b -> b.is(key), BlockPos.ZERO.atY(70), 6400, 32, 64);
			return found == null ? null : new int[] {found.getFirst().getX(), found.getFirst().getZ()};
		}
		String[] xz = spot.split(" ");
		return new int[] {Integer.parseInt(xz[0]), Integer.parseInt(xz[1])};
	}

	/** Waits until the map has stopped gaining chunks for a few seconds. */
	private static void waitForChunks(ClientGameTestContext context) {
		int last = -1;
		for (int i = 0; i < 60; i++) {
			context.waitTicks(60);
			int now = context.computeOnClient(mc -> MapSession.get().map().chunkCount());
			if (now == last && now > 600) {
				break;
			}
			last = now;
		}
		settleTicks(context, 100);
	}

	private static void settleTicks(ClientGameTestContext context, int ticks) {
		context.waitTicks(ticks);
	}

	/** The 3 x 3 sky view tiles round a column, at its ground height. */
	private static List<TileKey> tilesAround(ClientGameTestContext context, int x, int z) {
		int[] centre = context.computeOnClient(mc -> {
			int y = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
			return new int[] {Math.floorDiv(x, Projection.TILE_UNITS), (int) Math.floor(Projection.v(y, z) / Projection.TILE_UNITS)};
		});
		List<TileKey> tiles = new ArrayList<>();
		for (int dv = -1; dv <= 1; dv++) {
			for (int du = -1; du <= 1; du++) {
				tiles.add(new TileKey(TileKey.SURFACE, centre[0] + du, centre[1] + dv));
			}
		}
		return tiles;
	}

	/** The overworld's region files under any server's folder, leaving out cave layers. */
	private static List<Path> regionFiles(Path xaero) {
		if (!Files.isDirectory(xaero)) {
			return List.of();
		}
		try (var walk = Files.walk(xaero)) {
			return walk.filter(p -> p.getFileName().toString().matches("-?\\d+_-?\\d+\\.zip"))
				.filter(p -> p.toString().replace('\\', '/').contains("/null/") && !p.toString().replace('\\', '/').contains("/caves/"))
				.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	// --- Reading Xaero's World Map region files (format 8.8, as 1.47.0 writes them) ---

	static Map<Long, Column[]> read(List<Path> files, ClientLevel level) {
		HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
		Map<Long, Column[]> out = new HashMap<>();
		for (Path file : files) {
			String[] name = file.getFileName().toString().replace(".zip", "").split("_");
			try {
				readRegion(file, Integer.parseInt(name[0]), Integer.parseInt(name[1]), blocks, out);
			} catch (IOException e) {
				throw new java.io.UncheckedIOException("Reading " + file, e);
			}
		}
		return out;
	}

	private static void readRegion(Path file, int regionX, int regionZ, HolderGetter<Block> blocks, Map<Long, Column[]> out) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			zip.getNextEntry();
			DataInputStream in = new DataInputStream(zip);
			int first = in.read();
			int minor = 0;
			int major = 0;
			if (first == 255) {
				int version = in.readInt();
				minor = version & 0xFFFF;
				major = version >> 16 & 0xFFFF;
				first = -1;
			}
			if (major != 8 || minor != 8) {
				throw new IOException("Unknown format " + major + "." + minor);
			}
			List<BlockState> palette = new ArrayList<>();
			List<ResourceKey<Biome>> biomes = new ArrayList<>();
			while (true) {
				int coords = first == -1 ? in.read() : first;
				first = -1;
				if (coords == -1) {
					break;
				}
				int o = coords >> 4;
				int p = coords & 15;
				for (int i = 0; i < 4; i++) {
					for (int j = 0; j < 4; j++) {
						int next = in.readInt();
						if (next == -1) {
							continue;
						}
						Column[] columns = new Column[256];
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								columns[(z << 4) | x] = readColumn(next, in, blocks, palette, biomes);
								next = Integer.MIN_VALUE;
							}
						}
						in.read(); // how Xaero read the world
						in.readInt(); // cave start
						in.read(); // cave depth
						out.put(ChunkSnapshot.key((regionX * 8 + o) * 4 + i, (regionZ * 8 + p) * 4 + j), columns);
					}
				}
			}
		}
	}

	private static Column readColumn(int first, DataInputStream in, HolderGetter<Block> blocks, List<BlockState> palette, List<ResourceKey<Biome>> biomes) throws IOException {
		int flags = first != Integer.MIN_VALUE ? first : in.readInt();
		BlockState state;
		if ((flags & 1) != 0) {
			state = (flags & 0x200000) != 0 ? newState(in, blocks, palette) : palette.get(in.readInt());
		} else {
			state = Blocks.GRASS_BLOCK.defaultBlockState();
		}
		int height;
		if ((flags & 64) != 0) {
			height = in.read();
		} else {
			int bits = flags >> 12 & 0xFF | (flags >> 25 & 15) << 8;
			height = bits << 20 >> 20;
		}
		// Written as one byte: only how far above the block it is survives.
		int top = (flags & 0x1000000) != 0 ? height + ((in.read() - height) & 0xFF) : height;
		BlockState[] overlays = new BlockState[0];
		if ((flags & 2) != 0) {
			int count = in.read();
			overlays = new BlockState[count];
			for (int i = 0; i < count; i++) {
				int o = in.readInt();
				BlockState overlay;
				if ((o & 1) != 0) {
					overlay = (o & 1024) != 0 ? newState(in, blocks, palette) : palette.get(in.readInt());
				} else {
					overlay = Blocks.WATER.defaultBlockState();
				}
				if ((o & 4) != 0) {
					in.readInt();
				}
				overlays[i] = overlay;
			}
		}
		ResourceKey<Biome> biome = null;
		if ((flags & 0x100000) != 0) {
			if ((flags & 0x400000) != 0) {
				String id = (flags & 0x800000) != 0 ? "minecraft:plains" : in.readUTF();
				if ((flags & 0x800000) != 0) {
					in.readInt();
				}
				biome = ResourceKey.create(Registries.BIOME, Identifier.parse(id));
				biomes.add(biome);
			} else {
				biome = biomes.get(in.readInt());
			}
		}
		return new Column(state, height, top, overlays, biome, flags >> 8 & 15);
	}

	private static BlockState newState(DataInputStream in, HolderGetter<Block> blocks, List<BlockState> palette) throws IOException {
		CompoundTag tag = NbtIo.read(in);
		BlockState state = NbtUtils.readBlockState(blocks, tag);
		palette.add(state);
		return state;
	}

	// --- Rebuilding chunks from the columns ---

	/**
	 * Chunks rebuilt from Xaero's columns. Raw: each column's top block from its height down to
	 * its lowest neighbour, translucent layers above. Guessed adds what a player would expect
	 * under the top: dirt under grass, sandstone under sand, the ground under a tree's leaves.
	 */
	static ChunkSource rebuild(Map<Long, Column[]> columns, ClientLevel level, boolean guess) {
		PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
		Registry<Biome> biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
		Holder<Biome> plains = biomeRegistry.getOrThrow(Biomes.PLAINS);
		Map<Long, PalettedContainer<Holder<Biome>>> biomes = new HashMap<>();
		for (Map.Entry<Long, Column[]> e : columns.entrySet()) {
			PalettedContainer<Holder<Biome>> container = factory.createForBiomes();
			for (int qx = 0; qx < 4; qx++) {
				for (int qz = 0; qz < 4; qz++) {
					Column c = e.getValue()[((qz * 4 + 1) << 4) | (qx * 4 + 1)];
					Holder<Biome> biome = c == null || c.biome() == null ? plains : biomeRegistry.get(c.biome()).<Holder<Biome>>map(h -> h).orElse(plains);
					for (int qy = 0; qy < 4; qy++) {
						container.set(qx, qy, qz, biome);
					}
				}
			}
			biomes.put(e.getKey(), container);
		}
		Rebuilder rebuilder = new Rebuilder(columns, guess);
		int sectionCount = level.getSectionsCount();
		int minY = level.getMinY();
		Map<Long, ChunkSnapshot> chunks = new HashMap<>();
		int top = minY;
		for (Map.Entry<Long, Column[]> e : columns.entrySet()) {
			int chunkX = (int) (e.getKey() >> 32);
			int chunkZ = (int) (long) e.getKey();
			PalettedContainer<BlockState>[] states = new PalettedContainer[sectionCount];
			DataLayer[] blockLight = new DataLayer[sectionCount];
			int[] surface = new int[256];
			int[] solid = new int[256];
			for (int x = 0; x < 16; x++) {
				for (int z = 0; z < 16; z++) {
					Column c = e.getValue()[(z << 4) | x];
					if (c == null || c.state().isAir()) {
						surface[(z << 4) | x] = minY;
						solid[(z << 4) | x] = minY;
						continue;
					}
					int wx = (chunkX << 4) + x;
					int wz = (chunkZ << 4) + z;
					List<int[]> placed = new ArrayList<>();
					List<BlockState> placedStates = new ArrayList<>();
					rebuilder.column(c, wx, wz, (y, state) -> {
						placed.add(new int[] {y});
						placedStates.add(state);
					});
					for (int k = 0; k < placed.size(); k++) {
						int y = placed.get(k)[0];
						int section = (y - minY) >> 4;
						if (section < 0 || section >= sectionCount) {
							continue;
						}
						if (states[section] == null) {
							states[section] = factory.createForBlockStates();
						}
						states[section].set(x, y & 15, z, placedStates.get(k));
					}
					if (c.light() > 0) {
						for (int y = c.height() + 1; y <= c.top() + 1; y++) {
							int section = (y - minY) >> 4;
							if (section < 0 || section >= sectionCount) {
								continue;
							}
							if (blockLight[section] == null) {
								blockLight[section] = new DataLayer();
							}
							blockLight[section].set(x, y & 15, z, c.light());
						}
					}
					surface[(z << 4) | x] = c.top() + 1;
					solid[(z << 4) | x] = c.top() + 1;
					top = Math.max(top, c.top() + 1);
				}
			}
			LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
			PalettedContainer<Holder<Biome>> biome = biomes.get(e.getKey());
			for (int s = 0; s < sectionCount; s++) {
				sections[s] = new LevelChunkSection(states[s] != null ? states[s] : factory.createForBlockStates(), biome);
			}
			PalettedContainerRO<Holder<Biome>>[][] around = new PalettedContainerRO[9][];
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					PalettedContainer<Holder<Biome>> b = biomes.get(ChunkSnapshot.key(chunkX + dx, chunkZ + dz));
					if (b != null) {
						PalettedContainerRO<Holder<Biome>>[] perSection = new PalettedContainerRO[sectionCount];
						java.util.Arrays.fill(perSection, b);
						around[(dx + 1) * 3 + dz + 1] = perSection;
					}
				}
			}
			chunks.put(e.getKey(), ChunkSnapshot.built(chunkX, chunkZ, sections, around, blockLight, new DataLayer[sectionCount], surface, solid, level));
		}
		int topY = Math.min(level.getMaxY() + 1, top + 1);
		int maxY = level.getMaxY() + 1;
		return new ChunkSource() {
			@Override
			public ChunkSnapshot chunk(int chunkX, int chunkZ) {
				return chunks.get(ChunkSnapshot.key(chunkX, chunkZ));
			}

			@Override
			public int minY() {
				return minY;
			}

			@Override
			public int maxY() {
				return maxY;
			}

			@Override
			public int topY() {
				return topY;
			}

			@Override
			public boolean isSeen(int x, int y, int z) {
				return true;
			}
		};
	}

	interface Place {
		void at(int y, BlockState state);
	}

	private static final class Rebuilder {
		private final Map<Long, Column[]> columns;
		private final boolean guess;

		Rebuilder(Map<Long, Column[]> columns, boolean guess) {
			this.columns = columns;
			this.guess = guess;
		}

		Column at(int x, int z) {
			Column[] chunk = this.columns.get(ChunkSnapshot.key(x >> 4, z >> 4));
			return chunk == null ? null : chunk[((z & 15) << 4) | (x & 15)];
		}

		private boolean canopy(Column c) {
			return this.guess && c.state().is(BlockTags.LEAVES);
		}

		/** Where a column's ground is: its block, or for leaves the ground guessed under them. */
		int ground(Column c, int x, int z) {
			if (!this.canopy(c)) {
				return c.height();
			}
			return this.groundUnder(c, x, z)[0];
		}

		/** The ground under a tree's leaves, from the nearest columns that aren't leaves: {y, index into a sample}. */
		private final Map<Long, int[]> under = new HashMap<>();
		private final Map<Long, BlockState> underState = new HashMap<>();

		private int[] groundUnder(Column c, int x, int z) {
			long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
			int[] known = this.under.get(key);
			if (known != null) {
				return known;
			}
			List<Integer> heights = new ArrayList<>();
			Map<BlockState, Integer> counts = new HashMap<>();
			for (int r = 1; r <= 8 && heights.size() < 4; r++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
							continue;
						}
						Column n = this.at(x + dx, z + dz);
						if (n != null && !n.state().is(BlockTags.LEAVES) && !n.state().isAir() && n.height() < c.height() - 2) {
							heights.add(n.height());
							counts.merge(n.state(), 1, Integer::sum);
						}
					}
				}
			}
			int y;
			BlockState state = Blocks.GRASS_BLOCK.defaultBlockState();
			if (heights.isEmpty()) {
				y = c.height() - 6;
			} else {
				heights.sort(Integer::compare);
				y = Math.min(heights.get(heights.size() / 2), c.height() - 3);
				state = counts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
				if (!state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
					state = Blocks.GRASS_BLOCK.defaultBlockState();
				}
			}
			int[] result = {y};
			this.under.put(key, result);
			this.underState.put(key, state);
			return result;
		}

		void column(Column c, int x, int z, Place place) {
			// How far down this column's sides can be seen: to the lowest ground round it.
			int bottom = Integer.MAX_VALUE;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					Column n = dx == 0 && dz == 0 ? null : this.at(x + dx, z + dz);
					bottom = Math.min(bottom, n == null || n.state().isAir() ? c.height() - 4 : this.ground(n, x + dx, z + dz));
				}
			}
			int groundTop;
			BlockState ground;
			if (this.canopy(c)) {
				int[] g = this.groundUnder(c, x, z);
				int leavesBottom = Math.max(g[0] + 1, c.height() - 2);
				for (int y = c.height(); y >= leavesBottom; y--) {
					place.at(y, c.state());
				}
				groundTop = g[0];
				ground = this.underState.get(((long) x << 32) | (z & 0xFFFFFFFFL));
			} else {
				groundTop = c.height();
				ground = c.state();
			}
			bottom = Math.min(bottom - 1, groundTop - 1);
			for (int y = groundTop, depth = 0; y >= bottom; y--, depth++) {
				place.at(y, this.guess ? below(ground, depth) : ground);
			}
			// Water and other translucent layers above the block, topmost first.
			for (int y = c.top(), i = 0; y > c.height(); y--, i++) {
				BlockState[] overlays = c.overlays();
				if (overlays.length == 0) {
					break;
				}
				place.at(y, overlays[Math.min(i, overlays.length - 1)]);
			}
		}

		/** What's {@code depth} blocks under the top of a column whose top block is {@code top}. */
		private static BlockState below(BlockState top, int depth) {
			if (depth == 0) {
				return top;
			}
			Block b = top.getBlock();
			if (b == Blocks.GRASS_BLOCK || b == Blocks.PODZOL || b == Blocks.MYCELIUM || b == Blocks.DIRT_PATH || b == Blocks.FARMLAND
				|| b == Blocks.COARSE_DIRT || b == Blocks.ROOTED_DIRT || b == Blocks.MOSS_BLOCK || b == Blocks.DIRT || b == Blocks.MUD) {
				return depth <= 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
			}
			if (b == Blocks.SAND || b == Blocks.SUSPICIOUS_SAND) {
				return depth <= 3 ? Blocks.SAND.defaultBlockState() : Blocks.SANDSTONE.defaultBlockState();
			}
			if (b == Blocks.RED_SAND) {
				return depth <= 3 ? Blocks.RED_SAND.defaultBlockState() : Blocks.RED_SANDSTONE.defaultBlockState();
			}
			if (b == Blocks.GRAVEL) {
				return depth <= 2 ? Blocks.GRAVEL.defaultBlockState() : Blocks.STONE.defaultBlockState();
			}
			if (b == Blocks.SNOW_BLOCK || b == Blocks.POWDER_SNOW) {
				return depth <= 1 ? top : Blocks.STONE.defaultBlockState();
			}
			if (b instanceof SnowLayerBlock) {
				return depth == 1 ? Blocks.GRASS_BLOCK.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SNOWY, true) : below(Blocks.GRASS_BLOCK.defaultBlockState(), depth - 1);
			}
			if (b instanceof CropBlock) {
				return depth == 1 ? Blocks.FARMLAND.defaultBlockState() : below(Blocks.DIRT.defaultBlockState(), depth - 1);
			}
			if (!top.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
				// Flowers, carpets, rails, slabs, a roof's stairs: something stands under them.
				return below(Blocks.GRASS_BLOCK.defaultBlockState(), depth - 1);
			}
			return top;
		}
	}

	// --- Pictures ---

	private static String compare(int[][] a, int[][] b) {
		long both = 0;
		long onlyA = 0;
		long onlyB = 0;
		double diff = 0;
		for (int t = 0; t < a.length; t++) {
			for (int p = 0; p < a[t].length; p++) {
				boolean ka = (a[t][p] >>> 24) > 0;
				boolean kb = (b[t][p] >>> 24) > 0;
				if (ka && kb) {
					both++;
					for (int s = 0; s < 24; s += 8) {
						diff += Math.abs((a[t][p] >> s & 0xFF) - (b[t][p] >> s & 0xFF));
					}
				} else if (ka) {
					onlyA++;
				} else if (kb) {
					onlyB++;
				}
			}
		}
		return String.format(Locale.ROOT, "mean channel difference %.1f/255 over %d pixels, %d only in the normal bake, %d only in the import",
			both == 0 ? 0 : diff / both / 3, both, onlyA, onlyB);
	}

	private static void write(Path file, List<TileKey> tiles, int[][] pixels) {
		int size = Projection.TILE_PIXELS;
		int minU = tiles.stream().mapToInt(TileKey::tileU).min().orElse(0);
		int minV = tiles.stream().mapToInt(TileKey::tileV).min().orElse(0);
		int maxU = tiles.stream().mapToInt(TileKey::tileU).max().orElse(0);
		int maxV = tiles.stream().mapToInt(TileKey::tileV).max().orElse(0);
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage((maxU - minU + 1) * size, (maxV - minV + 1) * size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		for (int t = 0; t < tiles.size(); t++) {
			int ox = (tiles.get(t).tileU() - minU) * size;
			int oy = (tiles.get(t).tileV() - minV) * size;
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					int abgr = pixels[t][y * size + x];
					int argb = (abgr & 0xFF00FF00) | (abgr & 0xFF) << 16 | (abgr >> 16 & 0xFF);
					image.setRGB(ox + x, oy + y, argb);
				}
			}
		}
		try {
			Files.createDirectories(file.getParent());
			javax.imageio.ImageIO.write(image, "png", file.toFile());
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}
}
