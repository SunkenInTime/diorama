package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Chunks the game has unloaded ({@code packed-chunks}): the map packs what it keeps of them, and
 * what it reads back is the same, block for block and light for light. The map turned over them
 * then draws them from what was packed.
 */
final class PackedChunksTest {
	/** Far from anything the other sections visit. */
	private static final int HOME = 11000;
	/** Chunks each way from the player's that are compared. */
	private static final int RADIUS = 3;

	private PackedChunksTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("tp @a " + HOME + " 200 " + HOME);
		context.waitTicks(100);
		DioramaClientTest.waitUntilGenerated(context, world);
		int cx = HOME >> 4;
		int cz = HOME >> 4;
		context.waitFor(mc -> MapSession.get() != null && count(cx, cz, false) == side() * side(), 2400);
		// A block state the game never numbered, as some mods put in the world (see UnnumberedStatesTest):
		// packed chunks have to hand back that very state.
		net.minecraft.world.level.block.state.StateDefinition<Block, BlockState> definition = new net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState>(net.minecraft.world.level.block.Blocks.GOLD_BLOCK).create(Block::defaultBlockState, BlockState::new);
		definition.getPossibleStates().forEach(BlockState::initCache);
		BlockState unnumbered = definition.any();
		net.minecraft.core.BlockPos odd = new net.minecraft.core.BlockPos(HOME + 3, 150, HOME + 5);
		context.runOnClient(mc -> mc.level.setBlock(odd, unnumbered, 3));
		context.waitFor(mc -> stateAt(odd) == unnumbered, 1200);
		DioramaClientTest.settle(context, 40);
		// The snapshots themselves: the game can still change a chunk before it's left behind, and the
		// map then has another snapshot there, which says nothing about packing.
		ChunkSnapshot[] chunks = context.computeOnClient(mc -> chunks(cx, cz));
		long[] before = context.computeOnClient(mc -> digests(chunks));
		long[] tintsBefore = context.computeOnClient(mc -> tints(chunks));
		long[] stats0 = ChunkSnapshot.packStats();

		// Far enough that the game unloads them, near enough that the map still keeps them.
		int away = context.computeOnClient(mc -> HOME + (mc.options.renderDistance().get() + 3 + RADIUS + 4) * 16);
		world.getServer().runCommand("tp @a " + away + " 200 " + HOME);
		context.waitTicks(100);
		DioramaClientTest.waitUntilGenerated(context, world);
		int kept = context.computeOnClient(mc -> count(cx, cz, false) + count(cx, cz, true));
		if (kept != side() * side()) {
			throw new AssertionError("The map kept " + kept + " of the " + side() * side() + " chunks left behind");
		}
		context.waitFor(mc -> count(cx, cz, true) == side() * side(), 1200);
		long[] stats1 = ChunkSnapshot.packStats();
		int bytes = context.computeOnClient(mc -> {
			int sum = 0;
			for (int dx = -RADIUS; dx <= RADIUS; dx++) {
				for (int dz = -RADIUS; dz <= RADIUS; dz++) {
					sum += MapSession.get().map().worldChunk(cx + dx, cz + dz).packedSize();
				}
			}
			return sum;
		});

		// Read from the pack, not from what the collector hasn't taken yet.
		context.runOnClient(mc -> forget(chunks));
		long[] stats1b = ChunkSnapshot.packStats();
		long start = System.nanoTime();
		ChunkSnapshot[] now = context.computeOnClient(mc -> chunks(cx, cz));
		long[] after = context.computeOnClient(mc -> digests(chunks));
		long[] tintsAfter = context.computeOnClient(mc -> tints(chunks));
		long readMs = (System.nanoTime() - start) / 1_000_000;
		int same = 0;
		int tinted = 0;
		for (int i = 0; i < before.length; i++) {
			if (now[i] != chunks[i]) {
				continue;
			}
			same++;
			if (before[i] != after[i]) {
				throw new AssertionError("Chunk " + (cx - RADIUS + i / side()) + ", " + (cz - RADIUS + i % side()) + " reads differently once packed");
			}
			// Tints blended later than the first look aren't known from before.
			if (tintsBefore[i] != 0) {
				tinted++;
				if (tintsBefore[i] != tintsAfter[i]) {
					throw new AssertionError("Chunk " + (cx - RADIUS + i / side()) + ", " + (cz - RADIUS + i % side()) + " has other tints once packed");
				}
			}
		}
		if (same < before.length / 2) {
			throw new AssertionError("Only " + same + " of " + before.length + " chunks kept their snapshot until they were left behind");
		}
		if (tinted == 0) {
			throw new AssertionError("None of the chunks left behind had their tints blended: this wouldn't show the tints being packed");
		}
		long[] stats2 = ChunkSnapshot.packStats();
		if (stats2[3] - stats1b[3] < same) {
			throw new AssertionError("Reading " + same + " packed chunks unpacked " + (stats2[3] - stats1b[3]));
		}
		if (context.computeOnClient(mc -> count(cx, cz, true)) != side() * side()) {
			throw new AssertionError("Reading packed chunks left them unpacked");
		}
		if (context.computeOnClient(mc -> stateAt(odd) != unnumbered)) {
			throw new AssertionError("A packed chunk lost the block state the game never numbered");
		}

		// The map turned east over them: those tiles were never drawn, and the chunks are packed.
		context.runOnClient(mc -> forget(chunks));
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(1));
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(HOME, HOME), Projection.v(HOME, 80, HOME), 1.0, TileKey.SURFACE));
		DioramaClientTest.settle(context, 60);
		context.takeScreenshot("packed-chunks-turned");
		long[] stats3 = ChunkSnapshot.packStats();
		TileKey turned = context.computeOnClient(mc -> new TileKey(TileKey.SURFACE, (int) Math.floor(Projection.u(HOME, HOME) / Projection.TILE_UNITS), (int) Math.floor(Projection.v(HOME, 80, HOME) / Projection.TILE_UNITS)));
		int drawn = context.computeOnClient(mc -> {
			int[] pixels = MapSession.get().map().savedMap().read(turned);
			int count = 0;
			if (pixels != null) {
				for (int pixel : pixels) {
					if ((pixel >>> 24) != 0) {
						count++;
					}
				}
			}
			return count;
		});
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(-1));
		context.setScreen(() -> null);
		DioramaClientTest.settle(context, 20);

		long packed = stats1[0] - stats0[0];
		List<String> results = new ArrayList<>();
		results.add("chunks compared: " + same + ", with tints: " + tinted);
		results.add("packed since the section began: " + packed + " chunks, " + (packed == 0 ? 0 : (stats1[1] - stats0[1]) / packed) + " bytes each, " + (packed == 0 ? 0 : (stats1[2] - stats0[2]) / packed / 1000) + " us each");
		results.add("the compared chunks packed: " + bytes / before.length + " bytes each");
		results.add("unpacked to compare: " + (stats2[3] - stats1b[3]) + " chunks, " + (stats2[4] - stats1b[4]) / Math.max(1, stats2[3] - stats1b[3]) / 1000 + " us each; reading them all took " + readMs + " ms");
		results.add("unpacked to draw the turned map: " + (stats3[3] - stats2[3]) + " chunks; its tile over them has " + drawn + " pixels drawn");
		for (String result : results) {
			Diorama.LOGGER.info("PACKED-CHUNKS {}", result);
		}
		try {
			Path out = Path.of(System.getProperty("user.dir"), "screenshots", "packed-chunks.txt");
			Files.createDirectories(out.getParent());
			Files.writeString(out, String.join("\n", results) + "\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (stats3[3] == stats2[3]) {
			throw new AssertionError("Drawing the turned map over packed chunks unpacked none");
		}
		if (drawn == 0) {
			throw new AssertionError("The turned map's tile " + turned + " over the packed chunks shows nothing");
		}
	}

	private static int side() {
		return 2 * RADIUS + 1;
	}

	/** How many of the compared chunks the map has, packed or not. Client thread. */
	private static int count(int cx, int cz, boolean packed) {
		int count = 0;
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				ChunkSnapshot chunk = MapSession.get().map().worldChunk(cx + dx, cz + dz);
				if (chunk != null && chunk.isPacked() == packed) {
					count++;
				}
			}
		}
		return count;
	}

	/** The block the map's snapshot has at a place, null if it has no snapshot or only air there. Client thread. */
	private static BlockState stateAt(net.minecraft.core.BlockPos at) {
		ChunkSnapshot chunk = MapSession.get().map().worldChunk(at.getX() >> 4, at.getZ() >> 4);
		int section = chunk == null ? -1 : (at.getY() >> 4) - chunk.minSectionY;
		ChunkSnapshot.Blocks blocks = section < 0 || section >= chunk.sectionCount() ? null : chunk.section(section);
		return blocks == null ? null : blocks.get(at.getX() & 15, at.getY() & 15, at.getZ() & 15);
	}

	private static void forget(ChunkSnapshot[] chunks) {
		for (ChunkSnapshot chunk : chunks) {
			if (chunk.isPacked()) {
				chunk.forgetUnpacked();
			}
		}
	}

	/** The map's snapshots of the compared chunks. Client thread. */
	private static ChunkSnapshot[] chunks(int cx, int cz) {
		ChunkSnapshot[] out = new ChunkSnapshot[side() * side()];
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				out[(dx + RADIUS) * side() + dz + RADIUS] = MapSession.get().map().worldChunk(cx + dx, cz + dz);
			}
		}
		return out;
	}

	/** Each snapshot's blended tints as one number, 0 where it has none. Client thread. */
	private static long[] tints(ChunkSnapshot[] chunks) {
		long[] out = new long[chunks.length];
		for (int i = 0; i < chunks.length; i++) {
			ChunkSnapshot.Tints tints = chunks[i].tints();
			if (tints != null) {
				long digest = 17;
				for (int[] tint : new int[][] {tints.grass(), tints.foliage(), tints.dryFoliage(), tints.water()}) {
					digest = digest * 31 + java.util.Arrays.hashCode(tint);
				}
				out[i] = digest == 0 ? 1 : digest;
			}
		}
		return out;
	}

	/** Every block and light value of each snapshot, as one number. Client thread. */
	private static long[] digests(ChunkSnapshot[] chunks) {
		long[] out = new long[chunks.length];
		for (int at = 0; at < chunks.length; at++) {
			{
				ChunkSnapshot chunk = chunks[at];
				long digest = 17;
				for (int i = 0; i < chunk.sectionCount(); i++) {
					ChunkSnapshot.Blocks states = chunk.section(i);
					BlockState uniform = chunk.uniformSection(i);
					// The states themselves, not their numbers: these stay put for as long as the game runs.
					digest = digest * 31 + (uniform == null ? -1 : System.identityHashCode(uniform));
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								long id = states == null ? 0 : System.identityHashCode(states.get(x, y, z));
								digest = digest * 31 + (id << 8 | chunk.blockLight(i, x, y, z) << 4 | chunk.skyLight(i, x, y, z));
							}
						}
					}
				}
				out[at] = digest;
			}
		}
		return out;
	}
}
