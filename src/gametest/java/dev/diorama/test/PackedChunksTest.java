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
import net.minecraft.world.level.chunk.PalettedContainer;

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
		DioramaClientTest.settle(context, 40);
		// The snapshots themselves: the game can still change a chunk before it's left behind, and the
		// map then has another snapshot there, which says nothing about packing.
		ChunkSnapshot[] chunks = context.computeOnClient(mc -> chunks(cx, cz));
		long[] before = context.computeOnClient(mc -> digests(chunks));
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

		long start = System.nanoTime();
		ChunkSnapshot[] now = context.computeOnClient(mc -> chunks(cx, cz));
		long[] after = context.computeOnClient(mc -> digests(chunks));
		long readMs = (System.nanoTime() - start) / 1_000_000;
		int same = 0;
		for (int i = 0; i < before.length; i++) {
			if (now[i] != chunks[i]) {
				continue;
			}
			same++;
			if (before[i] != after[i]) {
				throw new AssertionError("Chunk " + (cx - RADIUS + i / side()) + ", " + (cz - RADIUS + i % side()) + " reads differently once packed");
			}
		}
		if (same < before.length / 2) {
			throw new AssertionError("Only " + same + " of " + before.length + " chunks kept their snapshot until they were left behind");
		}
		long[] stats2 = ChunkSnapshot.packStats();
		if (stats2[3] == stats1[3]) {
			throw new AssertionError("Reading packed chunks unpacked none");
		}
		if (context.computeOnClient(mc -> count(cx, cz, true)) != side() * side()) {
			throw new AssertionError("Reading packed chunks left them unpacked");
		}

		// The map turned east over them: those tiles were never drawn, and the chunks are packed.
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(1));
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(HOME, HOME), Projection.v(HOME, 80, HOME), 1.0, TileKey.SURFACE));
		DioramaClientTest.settle(context, 60);
		context.takeScreenshot("packed-chunks-turned");
		long[] stats3 = ChunkSnapshot.packStats();
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).turn(-1));
		context.setScreen(() -> null);
		DioramaClientTest.settle(context, 20);

		long packed = stats1[0] - stats0[0];
		List<String> results = new ArrayList<>();
		results.add("chunks compared: " + same);
		results.add("packed since the section began: " + packed + " chunks, " + (packed == 0 ? 0 : (stats1[1] - stats0[1]) / packed) + " bytes each, " + (packed == 0 ? 0 : (stats1[2] - stats0[2]) / packed / 1000) + " us each");
		results.add("the compared chunks packed: " + bytes / before.length + " bytes each");
		results.add("unpacked to compare: " + (stats2[3] - stats1[3]) + " chunks, " + (stats2[4] - stats1[4]) / Math.max(1, stats2[3] - stats1[3]) / 1000 + " us each; reading them all took " + readMs + " ms");
		results.add("unpacked to draw the turned map: " + (stats3[3] - stats2[3]) + " chunks");
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

	/** Every block and light value of each snapshot, as one number. Client thread. */
	private static long[] digests(ChunkSnapshot[] chunks) {
		long[] out = new long[chunks.length];
		for (int at = 0; at < chunks.length; at++) {
			{
				ChunkSnapshot chunk = chunks[at];
				long digest = 17;
				for (int i = 0; i < chunk.sectionCount(); i++) {
					PalettedContainer<BlockState> states = chunk.section(i);
					BlockState uniform = chunk.uniformSection(i);
					digest = digest * 31 + (uniform == null ? -1 : Block.getId(uniform));
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								int id = states == null ? 0 : Block.getId(states.get(x, y, z));
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
