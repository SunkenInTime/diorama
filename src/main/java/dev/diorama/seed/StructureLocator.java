package dev.diorama.seed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import dev.diorama.Diorama;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.datafix.DataFixers;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Works out where a world's structures are from its seed alone, with Minecraft's own placement
 * rules and no server: the grid each kind of structure is spread on, the odds that a cell gets one,
 * the keep-away zones between kinds, and whether the biome there allows it. Nothing is built, so
 * this is a place on the map and not the structure's exact rooms, and a few rules that need the
 * structure itself made (how flat the ground must be, say) are only approximated.
 *
 * <p>The structure data comes from the game's own data pack, loaded once by {@link #data}: the
 * client has no structures of its own, and the lookup the terrain code uses has no biome tags,
 * which is what says where a structure may stand. Vanilla's three dimensions only.
 *
 * <p>One instance is one seed in one dimension. {@link #findIn} is for a single worker thread.
 */
public final class StructureLocator {
	/** A structure found: its registry name without the namespace, and a spot on the map. */
	public record Found(String structure, int x, int y, int z) {
	}

	/** Heights for structures that don't stand on the ground. */
	private static final Map<String, Integer> FIXED_Y = Map.ofEntries(
		Map.entry("mineshaft", 50), Map.entry("mineshaft_mesa", 50),
		Map.entry("buried_treasure", 62), Map.entry("stronghold", 0),
		Map.entry("ancient_city", -27), Map.entry("trial_chambers", -30),
		Map.entry("fortress", 64), Map.entry("bastion_remnant", 33),
		Map.entry("nether_fossil", 64), Map.entry("ruined_portal_nether", 64));
	/** Where the icon of an underground structure is drawn: on the ground above it, as the map's top-down view would. */
	private static final int DRAWN_Y = 64;
	/** The most the ground can be below this for an End city to be built. */
	private static final int END_CITY_MIN_HEIGHT = 60;
	/** A monument only stands where every biome this close is deep ocean. */
	private static final int MONUMENT_RADIUS = 29;
	private static final int SEA_LEVEL = 63;

	/**
	 * The game's world generation data and, to build structures that have to be made to know what
	 * they hold (an End city's ship), its structure templates; null if those couldn't be set up.
	 */
	public record Data(RegistryAccess.Frozen registries, @Nullable StructureTemplateManager templates) {
	}

	/** The template of an End city's ship: a city that has one has an elytra. */
	private static final Identifier SHIP = Identifier.withDefaultNamespace("end_city/ship");

	private static CompletableFuture<Data> data;

	private final Data worldgen;
	private final ResourceKey<Level> dimension;
	private final long seed;
	private final NoiseBasedChunkGenerator generator;
	private final RandomState random;
	private final ChunkGeneratorStructureState state;
	private final BiomeResolver biomes;
	private final LevelHeightAccessor height;

	private StructureLocator(Data data, ResourceKey<Level> dimension, long seed, NoiseBasedChunkGenerator generator, RandomState random,
		ChunkGeneratorStructureState state, LevelHeightAccessor height) {
		this.worldgen = data;
		this.dimension = dimension;
		this.seed = seed;
		this.generator = generator;
		this.random = random;
		this.state = state;
		this.height = height;
		this.biomes = generator.getBiomeSource().createUncachedResolver(random);
	}

	/** Whether this dimension's structures can be placed: only the three the game comes with. */
	public static boolean supports(ResourceKey<Level> dimension) {
		return dimension == Level.OVERWORLD || dimension == Level.NETHER || dimension == Level.END;
	}

	/**
	 * The game's world generation data from its own data pack, with tags: loaded on a worker thread
	 * the first time and kept. Takes a couple of seconds.
	 */
	public static synchronized CompletableFuture<Data> data() {
		if (data == null || data.isCompletedExceptionally()) {
			data = CompletableFuture.supplyAsync(StructureLocator::load);
		}
		return data;
	}

	private static Data load() {
		PackRepository repository = ServerPacksSource.createVanillaTrustedRepository();
		repository.reload();
		repository.setSelected(List.of("vanilla"));
		// Never closed: the templates read from it for as long as the game runs.
		CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, repository.openAllSelected());
		RegistryAccess.Frozen builtIn = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, builtIn);
		List<HolderLookup.RegistryLookup<?>> context = TagLoader.buildUpdatedLookups(builtIn, staticTags);
		RegistryAccess.Frozen registries = RegistryDataLoader.load(resources, context, RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
		StructureTemplateManager templates = null;
		try {
			templates = new StructureTemplateManager(resources, emptySave(), DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't set up structure templates; End cities are placed without their ships", e);
		}
		return new Data(registries, templates);
	}

	/**
	 * A world save with nothing in it, in a temporary folder removed when the game closes: the
	 * template manager wants one to find the folder custom templates would be in, and there are none.
	 */
	private static LevelStorageSource.LevelStorageAccess emptySave() throws IOException {
		Path folder = Files.createTempDirectory("diorama-structures");
		LevelStorageSource.LevelStorageAccess save = LevelStorageSource.createDefault(folder).createAccess("templates");
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				save.close();
				try (Stream<Path> files = Files.walk(folder)) {
					files.sorted(Comparator.reverseOrder()).forEach(file -> file.toFile().delete());
				}
			} catch (IOException | RuntimeException e) {
				// A temporary folder left behind is harmless.
			}
		}, "Diorama structures cleanup"));
		return save;
	}

	/** The ground height range the game uses for a dimension. */
	private static LevelHeightAccessor heights(ResourceKey<Level> dimension) {
		return dimension == Level.OVERWORLD ? LevelHeightAccessor.create(-64, 384) : LevelHeightAccessor.create(0, 256);
	}

	public static StructureLocator create(Data data, ResourceKey<Level> dimension, long seed) {
		RegistryAccess.Frozen registries = data.registries();
		if (!supports(dimension)) {
			throw new IllegalArgumentException("No structures for " + dimension.identifier());
		}
		HolderGetter<Biome> biomes = registries.lookupOrThrow(Registries.BIOME);
		ResourceKey<NoiseGeneratorSettings> settingsKey;
		BiomeSource source;
		if (dimension == Level.OVERWORLD) {
			settingsKey = NoiseGeneratorSettings.OVERWORLD;
			source = MultiNoiseBiomeSource.createFromList(new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD, biomes).parameters());
		} else if (dimension == Level.NETHER) {
			settingsKey = NoiseGeneratorSettings.NETHER;
			source = MultiNoiseBiomeSource.createFromList(new MultiNoiseBiomeSourceParameterList(MultiNoiseBiomeSourceParameterList.Preset.NETHER, biomes).parameters());
		} else {
			settingsKey = NoiseGeneratorSettings.END;
			source = TheEndBiomeSource.create(biomes);
		}
		Holder<NoiseGeneratorSettings> settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey);
		NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(source, settings);
		RandomState random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings.value());
		ChunkGeneratorStructureState state = generator.createState(registries.lookupOrThrow(Registries.STRUCTURE_SET), random, seed);
		// Stronghold rings are worked out on first use and take a moment; do it here, not mid-search.
		state.ensureStructuresGenerated();
		return new StructureLocator(data, dimension, seed, generator, random, state, heights(dimension));
	}

	/** The structures whose spot is in the chunks from min to max, both ends included. */
	public List<Found> findIn(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
		List<Found> found = new ArrayList<>();
		for (Holder<StructureSet> holder : this.state.possibleStructureSets()) {
			StructureSet set = holder.value();
			StructurePlacement placement = set.placement();
			if (placement instanceof RandomSpreadStructurePlacement spread) {
				int spacing = spread.spacing();
				for (int regionX = Math.floorDiv(minChunkX, spacing); regionX <= Math.floorDiv(maxChunkX, spacing); regionX++) {
					for (int regionZ = Math.floorDiv(minChunkZ, spacing); regionZ <= Math.floorDiv(maxChunkZ, spacing); regionZ++) {
						ChunkPos chunk = spread.getPotentialStructureChunk(this.seed, regionX * spacing, regionZ * spacing);
						if (chunk.x() >= minChunkX && chunk.x() <= maxChunkX && chunk.z() >= minChunkZ && chunk.z() <= maxChunkZ
							&& spread.isStructureChunk(this.state, chunk.x(), chunk.z())) {
							this.place(set, chunk, found);
						}
					}
				}
			} else if (placement instanceof ConcentricRingsStructurePlacement rings) {
				List<ChunkPos> positions = this.state.getRingPositionsFor(rings);
				if (positions != null) {
					for (ChunkPos chunk : positions) {
						if (chunk.x() >= minChunkX && chunk.x() <= maxChunkX && chunk.z() >= minChunkZ && chunk.z() <= maxChunkZ) {
							this.place(set, chunk, found);
						}
					}
				}
			}
		}
		return found;
	}

	/**
	 * A set picks the one of its structures whose biomes fit the spot. Rings (strongholds) were
	 * already put in a fitting biome when they were worked out.
	 */
	private void place(StructureSet set, ChunkPos chunk, List<Found> out) {
		int x = chunk.getMiddleBlockX();
		int z = chunk.getMiddleBlockZ();
		for (StructureSet.StructureSelectionEntry entry : set.structures()) {
			Holder<Structure> holder = entry.structure();
			String name = holder.unwrapKey().map(key -> key.identifier().getPath()).orElse(null);
			if (name == null) {
				continue;
			}
			if (name.equals("end_city") && this.worldgen.templates() != null) {
				Found city = this.endCity(holder.value(), chunk);
				if (city != null) {
					out.add(city);
					return;
				}
				continue;
			}
			int ground = this.ground(name, x, z);
			if (ground == Integer.MIN_VALUE || !this.fits(name, holder.value(), x, ground, z)) {
				continue;
			}
			out.add(new Found(name, x, FIXED_Y.containsKey(name) ? DRAWN_Y : ground, z));
			return;
		}
	}

	/**
	 * An End city made the way the game makes it, to see whether it has a ship: if so, the spot
	 * returned is the ship's (and its elytra's) rather than the city's. Null if the city can't be built here.
	 */
	private @Nullable Found endCity(Structure structure, ChunkPos chunk) {
		Structure.GenerationContext context = new Structure.GenerationContext(this.worldgen.registries(), this.generator, this.generator.getBiomeSource(),
			this.random.createClimateSampler(SamplerContext.EMPTY_UNCACHED), this.random, this.worldgen.templates(), this.seed, chunk, this.height, biome -> true);
		Optional<Structure.GenerationStub> stub = structure.findValidGenerationPoint(context);
		if (stub.isEmpty()) {
			return null;
		}
		BlockPos at = stub.get().position();
		if (!this.fits("end_city", structure, at.getX(), at.getY(), at.getZ())) {
			return null;
		}
		StructureTemplate ship = this.worldgen.templates().get(SHIP).orElse(null);
		for (StructurePiece piece : stub.get().getPiecesBuilder().build().pieces()) {
			if (ship != null && piece instanceof TemplateStructurePiece templated && templated.template() == ship) {
				BlockPos centre = piece.getBoundingBox().getCenter();
				return new Found("end_ship", centre.getX(), centre.getY(), centre.getZ());
			}
		}
		return new Found("end_city", at.getX(), at.getY(), at.getZ());
	}

	/** The height to test the biome at, or {@link Integer#MIN_VALUE} if the structure can't stand here. */
	private int ground(String name, int x, int z) {
		Integer fixed = FIXED_Y.get(name);
		if (fixed != null) {
			return fixed;
		}
		if (this.dimension == Level.NETHER) {
			return DRAWN_Y;
		}
		int surface = this.generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, this.height, this.random);
		if (this.dimension == Level.END && name.equals("end_city") && surface < END_CITY_MIN_HEIGHT) {
			return Integer.MIN_VALUE;
		}
		return surface;
	}

	private boolean fits(String name, Structure structure, int x, int y, int z) {
		if (name.equals("stronghold")) {
			return true;
		}
		if (name.equals("monument")) {
			return this.biomes.getBiomesWithin(x, SEA_LEVEL, z, MONUMENT_RADIUS).stream().allMatch(structure.biomes()::contains);
		}
		return structure.biomes().contains(this.biomes.getNoiseBiome(x >> 2, y >> 2, z >> 2));
	}
}
