package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.Facing;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.CraftBlocks;
import dev.diorama.bake.TileKey;
import dev.diorama.craft.Body;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A Create Aeronautics airship left behind on a real server (opt-in: {@code aeronautics-server},
 * with {@code --mods aeronautics --env DIORAMA_REAL_SERVER=1}). An airship with a hollow balloon,
 * sails and a deck is built in the air from the console and made into a ship with physics paused.
 * The player looks at it on the map from beside it, from 500 blocks away where the server no
 * longer sends it, after leaving and joining again that far away, and after coming back. Each
 * time the map's ship must be made of the blocks it was built from.
 *
 * <p>Run it with {@code --client-mods} naming a mod with blocks of its own (Macaw's Bridges
 * needs no other mod), which the server then doesn't have, as players add mods to a pack: the
 * game numbers blocks the server's way while joined and its own way again on leaving, and the
 * ship the map kept was saved as other blocks then (#420).
 */
final class AeronauticsServerTest {
	private static final String CONTAINER = "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";
	private static final int FAR = 500;

	private AeronauticsServerTest() {
	}

	static void run(ClientGameTestContext context) {
		if (System.getenv("DIORAMA_REAL_SERVER") == null) {
			throw new AssertionError("aeronautics-server needs a real server: run it with --mods aeronautics --env DIORAMA_REAL_SERVER=1");
		}
		if (!context.computeOnClient(mc -> type(CONTAINER) != null)) {
			throw new AssertionError("DIORAMA_TESTS=aeronautics-server needs Create Aeronautics and Sable installed");
		}
		List<String> wrong = new ArrayList<>();
		Facing savedFacing = context.computeOnClient(mc -> Projection.facing());
		try (RealServer server = RealServer.start(false)) {
			server.command("time set noon");
			server.command("gamerule doDaylightCycle false");
			server.join(context);
			String me = context.computeOnClient(mc -> mc.player.getScoreboardName());
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
			int x = context.computeOnClient(mc -> mc.player.blockPosition().getX());
			int z = context.computeOnClient(mc -> mc.player.blockPosition().getZ());
			server.command("gamemode spectator " + me);
			server.commandAndWait("tp " + me + " " + x + " 150 " + (z + 12), "Teleported " + me, 120);
			// The airship, 30 blocks east: a hollow white balloon with yellow stripes and blue inside,
			// a wooden deck hung under it on fence posts, and a row of sails along each side.
			int sx = x + 30;
			server.command("fill " + (sx - 8) + " 152 " + (z - 4) + " " + (sx + 8) + " 158 " + (z + 4) + " aeronautics:white_envelope hollow");
			server.command("fill " + (sx - 8) + " 155 " + (z - 4) + " " + (sx + 8) + " 155 " + (z - 4) + " aeronautics:yellow_envelope");
			server.command("fill " + (sx - 8) + " 155 " + (z + 4) + " " + (sx + 8) + " 155 " + (z + 4) + " aeronautics:yellow_envelope");
			server.command("fill " + (sx - 2) + " 153 " + (z - 1) + " " + (sx + 2) + " 153 " + (z + 1) + " blue_wool");
			server.command("fill " + (sx - 4) + " 146 " + (z - 2) + " " + (sx + 4) + " 146 " + (z + 2) + " oak_planks");
			for (int dx = -4; dx <= 4; dx += 8) {
				for (int dz = -2; dz <= 2; dz += 4) {
					server.command("fill " + (sx + dx) + " 147 " + (z + dz) + " " + (sx + dx) + " 151 " + (z + dz) + " oak_fence");
				}
			}
			server.command("fill " + (sx - 3) + " 146 " + (z - 3) + " " + (sx + 3) + " 148 " + (z - 3) + " simulated:white_symmetric_sail[axis=z]");
			server.command("fill " + (sx - 3) + " 146 " + (z + 3) + " " + (sx + 3) + " 148 " + (z + 3) + " simulated:red_symmetric_sail[axis=z]");
			context.waitTicks(100);
			double[] spot = {sx + 0.5, 152, z + 0.5};
			photo(context, "00-blocks", spot, 4);

			server.command("sable paused true");
			server.command("sable assemble area " + (sx - 8) + " 146 " + (z - 4) + " " + (sx + 8) + " 158 " + (z + 4));
			context.waitFor(mc -> ships(mc).size() == 1, 1200);
			UUID id = context.computeOnClient(mc -> (UUID) call(ships(mc).get(0), "getUniqueId"));
			context.waitTicks(200);
			// Asked after each photo: the map makes its picture of a ship while it looks at it.
			photo(context, "01-near", spot, 4);
			String whole = made(context, id, "01-near");
			// One of the ship's own blocks, and its number while this server is joined.
			BlockState balloon = context.computeOnClient(mc -> {
				CraftBlocks blocks = MapSession.get().crafts().shown(id).blocks();
				for (int cell = 0; cell < blocks.volume(); cell++) {
					BlockState state = Block.BLOCK_STATE_REGISTRY.byId(blocks.state(cell));
					if (blocks.state(cell) != 0 && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("aeronautics")) {
						return state;
					}
				}
				throw new AssertionError("The map's ship has none of the balloon it was built with: " + whole);
			});
			int joined = context.computeOnClient(mc -> Block.BLOCK_STATE_REGISTRY.getId(balloon));
			if (whole == null) {
				wrong.add("beside the ship, the map draws no blocks for it");
			}

			// Past what the server sends: the map keeps the ship where it was left.
			server.commandAndWait("tp " + me + " " + x + " 150 " + (z + FAR), "Teleported " + me, 120);
			context.waitTicks(400);
			unsent(context, "after going " + FAR + " blocks away");
			photo(context, "02-far", spot, 4);
			expect(wrong, whole, made(context, id, "02-far"), "after going " + FAR + " blocks away");

			RealServer.leave(context);
			// What this test is for: out of the server, the game numbers the ship's blocks another way.
			int left = context.computeOnClient(mc -> Block.BLOCK_STATE_REGISTRY.getId(balloon));
			if (left == joined) {
				throw new AssertionError("The game numbers blocks as the server does, so leaving changes nothing: run aeronautics-server with "
					+ "--client-mods naming a mod with blocks of its own that the server won't have");
			}
			server.join(context);
			context.waitFor(mc -> MapSession.get() != null, 2400);
			context.waitTicks(400);
			unsent(context, "after joining again " + FAR + " blocks away");
			photo(context, "03-rejoined-far", spot, 4);
			expect(wrong, whole, made(context, id, "03-rejoined-far"), "after joining again " + FAR + " blocks away");

			server.commandAndWait("tp " + me + " " + x + " 150 " + (z + 12), "Teleported " + me, 120);
			context.waitTicks(400);
			photo(context, "04-came-back", spot, 4);
			expect(wrong, whole, made(context, id, "04-came-back"), "after coming back");
			RealServer.leave(context);
		} finally {
			FacingTest.turnTo(context, savedFacing);
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("The airship on the map is wrong:\n" + String.join("\n", wrong));
		}
	}

	/** The server mustn't be sending the ship: the map's then is the one it kept, which is what's being asked about. */
	private static void unsent(ClientGameTestContext context, String when) {
		if (!context.computeOnClient(mc -> ships(mc).isEmpty())) {
			throw new AssertionError("The server still sends the ship " + when + ", so the map's isn't the one it kept");
		}
	}

	private static void expect(List<String> wrong, String whole, String now, String when) {
		if (whole != null && !whole.equals(now)) {
			wrong.add(when + " the map draws the ship from " + now + "; it was built from " + whole);
		}
	}

	/** The blocks the map's picture of the ship is made from, by name with how many, or null with no ship on the map. */
	static String made(ClientGameTestContext context, UUID id, String stage) {
		String[] made = {null};
		for (int i = 0; i < 20 && made[0] == null; i++) {
			made[0] = context.computeOnClient(mc -> {
				Body.Blocks shown = MapSession.get().crafts().shown(id);
				if (shown == null) {
					return null;
				}
				CraftBlocks blocks = shown.blocks();
				Map<String, Integer> names = new TreeMap<>();
				for (int cell = 0; cell < blocks.volume(); cell++) {
					if (blocks.state(cell) != 0) {
						names.merge(BuiltInRegistries.BLOCK.getKey(Block.BLOCK_STATE_REGISTRY.byId(blocks.state(cell)).getBlock()).toString(), 1, Integer::sum);
					}
				}
				return names.toString();
			});
			if (made[0] == null) {
				context.waitTicks(20);
			}
		}
		Diorama.LOGGER.info("{}: the map's craft {} is made of {}", stage, id, made[0]);
		return made[0];
	}

	private static void photo(ClientGameTestContext context, String name, double[] at, double zoom) {
		FacingTest.turnTo(context, Facing.NORTH);
		context.setScreen(MapScreen::new);
		context.getInput().setCursorPos(1595, 250);
		for (int i = 0; i < 2; i++) {
			context.runOnClient(mc -> {
				if (mc.gui.screen() instanceof MapScreen screen) {
					screen.lookAt(Projection.u(at[0], at[2]), Projection.v(at[0], at[1], at[2]), zoom, TileKey.SURFACE);
				}
			});
			context.waitTicks(40);
			for (int wait = 0; wait < 60 && context.computeOnClient(mc -> MapSession.get().map().bakingCount() != 0); wait++) {
				context.waitTicks(20);
			}
			context.waitTicks(60);
		}
		context.takeScreenshot("aeronautics-server-" + name);
		context.setScreen(() -> null);
	}

	private static List<Object> ships(Minecraft mc) {
		try {
			Object container = type(CONTAINER).getMethod("getContainer", ClientLevel.class).invoke(null, mc.level);
			return container == null ? List.of() : new ArrayList<>((List<?>) call(container, "getAllSubLevels"));
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static Class<?> type(String name) {
		try {
			return Class.forName(name);
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	private static Object call(Object owner, String name) {
		try {
			Method method = owner.getClass().getMethod(name);
			method.setAccessible(true);
			return method.invoke(owner);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(name + " on " + owner.getClass().getName(), e);
		}
	}
}
