package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapSession;
import dev.diorama.data.ChunkSnapshot;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A chunk that changes a little ({@code kept-sections}): the map's new snapshot of it keeps the
 * sections that didn't change from the one before, and still reads, block for block and light for
 * light, like one copied whole.
 */
final class KeptSectionsTest {
	private KeptSectionsTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
		// A light in place of the ground beside the player: one block in a section that has others, and light around it.
		BlockPos lamp = feet.offset(2, -1, 0);
		int cx = lamp.getX() >> 4;
		int cz = lamp.getZ() >> 4;
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().worldChunk(cx, cz) != null, 1200);
		DioramaClientTest.settle(context, 20);
		BlockState ground = world.getServer().computeOnServer(server -> server.overworld().getBlockState(lamp));
		ChunkSnapshot old = context.computeOnClient(mc -> MapSession.get().map().worldChunk(cx, cz));
		long[] before = ChunkSnapshot.takeStats();
		world.getServer().runOnServer(server -> server.overworld().setBlockAndUpdate(lamp, Blocks.GLOWSTONE.defaultBlockState()));
		context.waitFor(mc -> stateAt(lamp) == Blocks.GLOWSTONE.defaultBlockState(), 1200);
		// Until the light has settled and the map has taken it.
		context.waitTicks(60);
		long[] after = ChunkSnapshot.takeStats();
		// In the chunk itself: sections of the snapshot from before the lamp that the one now still has.
		int[] shared = context.computeOnClient(mc -> {
			ChunkSnapshot now = MapSession.get().map().worldChunk(cx, cz);
			int lampSection = (lamp.getY() >> 4) - now.minSectionY;
			int same = 0;
			int mixed = 0;
			for (int i = 0; i < now.sectionCount(); i++) {
				if (old.hasBlocks(i) && now.hasBlocks(i)) {
					mixed++;
					same += old.section(i) == now.section(i) ? 1 : 0;
				}
			}
			return new int[] {same, mixed, old.section(lampSection) == now.section(lampSection) ? 1 : 0};
		});
		String differs = context.computeOnClient(mc -> {
			ChunkSnapshot kept = MapSession.get().map().worldChunk(cx, cz);
			ChunkSnapshot whole = ChunkSnapshot.take(mc.level.getChunk(cx, cz), mc.level);
			return difference(kept, whole);
		});
		Diorama.LOGGER.info("KEPT-SECTIONS sections kept {}, copied {}; light layers kept {}, copied {}", after[0] - before[0], after[1] - before[1], after[2] - before[2], after[3] - before[3]);
		if (differs != null) {
			throw new AssertionError("The map's snapshot of a changed chunk isn't what the game has: " + differs);
		}
		if (shared[2] != 0) {
			throw new AssertionError("The section the lamp went into was kept from before it");
		}
		if (shared[1] < 2 || shared[0] != shared[1] - 1) {
			throw new AssertionError("Of the chunk's " + shared[1] + " sections with blocks, " + shared[0] + " were kept after a lamp went into one");
		}
		if (after[2] == before[2]) {
			throw new AssertionError("A lamp placed in one section had every light layer copied again");
		}
		world.getServer().runOnServer(server -> server.overworld().setBlockAndUpdate(lamp, ground));
		context.waitTicks(40);
	}

	private static BlockState stateAt(BlockPos at) {
		ChunkSnapshot chunk = MapSession.get().map().worldChunk(at.getX() >> 4, at.getZ() >> 4);
		int section = chunk == null ? -1 : (at.getY() >> 4) - chunk.minSectionY;
		ChunkSnapshot.Blocks blocks = section < 0 || section >= chunk.sectionCount() ? null : chunk.section(section);
		return blocks == null ? null : blocks.get(at.getX() & 15, at.getY() & 15, at.getZ() & 15);
	}

	/** Where two snapshots of one chunk read differently, or null if nowhere. */
	private static String difference(ChunkSnapshot a, ChunkSnapshot b) {
		if (a.sectionCount() != b.sectionCount()) {
			return "sections";
		}
		for (int i = 0; i < a.sectionCount(); i++) {
			ChunkSnapshot.Blocks as = a.section(i);
			ChunkSnapshot.Blocks bs = b.section(i);
			if ((as == null) != (bs == null) || a.uniformSection(i) != b.uniformSection(i)) {
				return "the kind of section " + i;
			}
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (as != null && as.get(x, y, z) != bs.get(x, y, z)) {
							return "the block at " + x + ", " + y + ", " + z + " of section " + i;
						}
						if (a.blockLight(i, x, y, z) != b.blockLight(i, x, y, z) || a.skyLight(i, x, y, z) != b.skyLight(i, x, y, z)) {
							return "the light at " + x + ", " + y + ", " + z + " of section " + i;
						}
					}
				}
			}
		}
		return null;
	}
}
