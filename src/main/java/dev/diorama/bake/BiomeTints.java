package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Blends a snapshot's biome tints on a bake thread, the way the game blends them for its own
 * blocks: each column's tint is the average of the biome colors over the blend radius around
 * its top block, with biomes looked up through the same fuzzed zoom. Blending on the client
 * thread took most of its time while exploring, so snapshots only hold on to the biomes, their
 * own and their neighbours', as the game had them when the snapshot was taken.
 */
public final class BiomeTints {
	// The game's own resolvers (BiomeColors), written out: that class is the client's, and a
	// dedicated server blends tints for the web map too. A client puts the game's in their place
	// (see {@link #setGame}).
	private static final ColorResolver[] RESOLVERS = {
		Biome::getGrassColor,
		(biome, x, z) -> biome.getFoliageColor(),
		//? if >=1.21.5 {
		(biome, x, z) -> biome.getDryFoliageColor(),
		//?} else {
		/*// Dry foliage (leaf litter) came in 1.21.5; nothing reads this slot before.
		(biome, x, z) -> biome.getFoliageColor(),
		*///?}
		(biome, x, z) -> biome.getWaterColor(),
	};
	private static final int WATER = 3;

	private BiomeTints() {
	}

	/**
	 * The biome colour of water: the one the water's own tint code asks for, which a mod can make
	 * its own (Natural Waters). {@link BlockLooks#build} finds it out.
	 */
	public static ColorResolver water() {
		return RESOLVERS[WATER];
	}

	/**
	 * The client's biome colours, as the game holds them when {@link BlockLooks#build} asks: a mod
	 * can put its own in the game's place, as Serene Seasons does for grass and foliage by the
	 * season, and the map then has to blend the mod's to match the game (#389).
	 */
	static void setGame(ColorResolver grass, ColorResolver foliage, ColorResolver dryFoliage, ColorResolver water) {
		RESOLVERS[0] = grass;
		RESOLVERS[1] = foliage;
		RESOLVERS[2] = dryFoliage;
		RESOLVERS[WATER] = water;
	}

	/** The snapshot's tints, blended now if no bake has needed them yet. Any thread. */
	public static ChunkSnapshot.Tints of(ChunkSnapshot snapshot) {
		ChunkSnapshot.Tints tints = snapshot.tints();
		if (tints == null) {
			// Two bakes may both blend; they read the same biomes, so either result will do.
			// A turned view blends its chunk, and turns what that gives.
			ChunkSnapshot chunk = snapshot.chunk();
			tints = blend(chunk);
			chunk.setTints(tints);
			if (chunk != snapshot) {
				tints = snapshot.tints();
			}
		}
		return tints;
	}

	/** Blends the snapshot's tints. Any thread. */
	public static ChunkSnapshot.Tints blend(ChunkSnapshot snapshot) {
		// Where the game had no chunk, the lookup gives null, and the blend leaves that block out:
		// counting the biome the game assumes there, Plains, tinted the edge of the loaded area (#290).
		BiomeManager biomes = new BiomeManager(snapshot::knownNoiseBiome, snapshot.biomeZoomSeed);
		int[][] colors = new int[RESOLVERS.length][256];
		int radius = snapshot.blendRadius;
		int[] red = new int[RESOLVERS.length];
		int[] green = new int[RESOLVERS.length];
		int[] blue = new int[RESOLVERS.length];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// Neighbouring columns share most of the blocks they blend, at the same height unless the
		// ground steps, so each block's colours are worked out once per height.
		int size = 16 + radius * 2;
		int[] heights = new int[size * size];
		java.util.Arrays.fill(heights, Integer.MIN_VALUE);
		int[][] sampled = new int[RESOLVERS.length][size * size];
		boolean[] known = new boolean[size * size];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int bx = (snapshot.chunkX << 4) + x;
				int bz = (snapshot.chunkZ << 4) + z;
				int y = snapshot.surface(x, z);
				java.util.Arrays.fill(red, 0);
				java.util.Arrays.fill(green, 0);
				java.util.Arrays.fill(blue, 0);
				int count = 0;
				for (int dz = -radius; dz <= radius; dz++) {
					for (int dx = -radius; dx <= radius; dx++) {
						int cell = (z + dz + radius) * size + x + dx + radius;
						if (heights[cell] != y) {
							heights[cell] = y;
							Holder<Biome> biome = biomes.getBiome(pos.set(bx + dx, y, bz + dz));
							known[cell] = biome != null;
							for (int r = 0; biome != null && r < RESOLVERS.length; r++) {
								sampled[r][cell] = RESOLVERS[r].getColor(biome.value(), bx + dx, bz + dz);
							}
						}
						if (known[cell]) {
							count++;
							for (int r = 0; r < RESOLVERS.length; r++) {
								int color = sampled[r][cell];
								red[r] += ARGB.red(color);
								green[r] += ARGB.green(color);
								blue[r] += ARGB.blue(color);
							}
						}
					}
				}
				if (count == 0) {
					// Every lookup landed in a chunk the game didn't have, which only happens next to
					// one with the blend off or small: the column's own biome, without the fuzz.
					Biome own = snapshot.noiseBiome(bx >> 2, y >> 2, bz >> 2).value();
					count = 1;
					for (int r = 0; r < RESOLVERS.length; r++) {
						int color = RESOLVERS[r].getColor(own, bx, bz);
						red[r] = ARGB.red(color);
						green[r] = ARGB.green(color);
						blue[r] = ARGB.blue(color);
					}
				}
				int i = (z << 4) | x;
				for (int r = 0; r < RESOLVERS.length; r++) {
					//? if >=1.21 {
					colors[r][i] = ARGB.color(red[r] / count, green[r] / count, blue[r] / count) & 0xFFFFFF;
					//?} else {
					/*// There was no colour without alpha yet; the alpha is masked off anyway.
					colors[r][i] = ARGB.color(255, red[r] / count, green[r] / count, blue[r] / count) & 0xFFFFFF;
					*///?}
				}
			}
		}
		return new ChunkSnapshot.Tints(colors[0], colors[1], colors[2], colors[WATER]);
	}
}
