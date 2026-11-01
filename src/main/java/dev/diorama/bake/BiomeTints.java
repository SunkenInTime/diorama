package dev.diorama.bake;

import dev.diorama.data.ChunkSnapshot;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
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
	private static final ColorResolver[] RESOLVERS = {
		BiomeColors.GRASS_COLOR_RESOLVER,
		BiomeColors.FOLIAGE_COLOR_RESOLVER,
		//? if >=1.21.5 {
		BiomeColors.DRY_FOLIAGE_COLOR_RESOLVER,
		//?} else {
		/*// Dry foliage (leaf litter) came in 1.21.5; nothing reads this slot before.
		BiomeColors.FOLIAGE_COLOR_RESOLVER,
		*///?}
		BiomeColors.WATER_COLOR_RESOLVER,
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

	static void setWater(ColorResolver resolver) {
		RESOLVERS[WATER] = resolver;
	}

	/** The snapshot's tints, blended now if no bake has needed them yet. Any thread. */
	public static ChunkSnapshot.Tints of(ChunkSnapshot snapshot) {
		ChunkSnapshot.Tints tints = snapshot.tints();
		if (tints == null) {
			// Two bakes may both blend; they read the same biomes, so either result will do.
			tints = blend(snapshot);
			snapshot.setTints(tints);
		}
		return tints;
	}

	/** Blends the snapshot's tints. Any thread. */
	public static ChunkSnapshot.Tints blend(ChunkSnapshot snapshot) {
		BiomeManager biomes = new BiomeManager(snapshot::noiseBiome, snapshot.biomeZoomSeed);
		int[][] colors = new int[RESOLVERS.length][256];
		int radius = snapshot.blendRadius;
		int count = (radius * 2 + 1) * (radius * 2 + 1);
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
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int bx = (snapshot.chunkX << 4) + x;
				int bz = (snapshot.chunkZ << 4) + z;
				int y = snapshot.surface(x, z);
				java.util.Arrays.fill(red, 0);
				java.util.Arrays.fill(green, 0);
				java.util.Arrays.fill(blue, 0);
				for (int dz = -radius; dz <= radius; dz++) {
					for (int dx = -radius; dx <= radius; dx++) {
						int cell = (z + dz + radius) * size + x + dx + radius;
						if (heights[cell] != y) {
							heights[cell] = y;
							Biome biome = biomes.getBiome(pos.set(bx + dx, y, bz + dz)).value();
							for (int r = 0; r < RESOLVERS.length; r++) {
								sampled[r][cell] = RESOLVERS[r].getColor(biome, bx + dx, bz + dz);
							}
						}
						for (int r = 0; r < RESOLVERS.length; r++) {
							int color = sampled[r][cell];
							red[r] += ARGB.red(color);
							green[r] += ARGB.green(color);
							blue[r] += ARGB.blue(color);
						}
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
