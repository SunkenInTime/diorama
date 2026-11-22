package dev.diorama.test;

import dev.diorama.Facing;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.StructureTags;

/**
 * The minimap with Lock North off, turning with the player: pictures of a full turn in front of
 * {@link ShowcaseScene}'s castle, a village behind, square and round, at the default zoom, zoomed
 * out into the fog and enlarged, and with the map facing south. Opt-in with
 * {@code DIORAMA_TESTS=minimap-turn}, for pictures.
 */
final class MinimapTurnTest {
	/** Degrees between pictures of the turn: eight ways to look, or with {@code DIORAMA_GIF=1} the frames of a GIF. */
	private static final int STEP = "1".equals(System.getenv("DIORAMA_GIF")) ? 5 : 45;
	private static final double[] ZOOMS = {2, 0.5};

	private MinimapTurnTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		context.getInput().resizeWindow(1280, 720);
		world.getServer().runCommand("difficulty peaceful");
		BlockPos village = world.getServer().computeOnServer(server ->
			server.overworld().findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, 100, false));
		if (village == null) {
			throw new AssertionError("No village near spawn");
		}
		int cx = village.getX();
		int cz = village.getZ() - 110;
		world.getServer().runCommand("gamemode creative @a");
		world.getServer().runCommand("tp @a " + cx + " 200 " + (cz + 40));
		context.runOnClient(mc -> mc.player.getAbilities().flying = true);
		context.waitTicks(100);
		ShowcaseScene scene = world.getServer().computeOnServer(server -> ShowcaseScene.site(server.overworld(), cx, cz));
		world.getServer().runOnServer(server -> scene.castle("waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper_stairs"));
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("gamerule advance_time false");
		// On the road in front of the gate: walls and towers at the default zoom, the village when zoomed out.
		int standZ = cz + Integer.getInteger("diorama.turnStandZ", 47);
		world.getServer().runCommand("execute positioned " + cx + " 0 " + standZ + " positioned over world_surface run tp @a ~0.5 ~ ~0.5 180 0");
		context.runOnClient(mc -> mc.player.getAbilities().flying = false);
		context.waitTicks(60);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.add(new Waypoint("Keep", cx, scene.base() + 1, cz, "minecraft:overworld", Waypoint.PALETTE[3]));
			store.add(new Waypoint("Village", village.getX(), village.getY(), village.getZ(), "minecraft:overworld", Waypoint.PALETTE[1]));
			store.add(new Waypoint("East", cx + 300, scene.base(), cz, "minecraft:overworld", Waypoint.PALETTE[5]));
			Settings.get().minimapSize = 96;
		});
		context.getInput().setCursorPos(0, 0);
		DioramaClientTest.settle(context, 100);
		// What building the castle dropped: a hundred dots that hide the map under them.
		world.getServer().runCommand("kill @e[type=item]");
		context.waitTicks(10);

		// As it is today, for the comparison.
		turn(context, "locked", Settings.Shape.SQUARE, 2);
		for (Settings.Shape shape : Settings.Shape.values()) {
			for (double zoom : ZOOMS) {
				turn(context, "turning", shape, zoom);
			}
		}
		// With the map facing south, the turn starts from there.
		FacingTest.turnTo(context, Facing.SOUTH);
		DioramaClientTest.settle(context, 100);
		turn(context, "turning-south", Settings.Shape.SQUARE, 2);
		FacingTest.turnTo(context, Facing.NORTH);
		DioramaClientTest.settle(context, 100);
		// Past the land the map knows, for the fog, and enlarged.
		turn(context, "turning", Settings.Shape.SQUARE, 0.1875);
		turn(context, "turning", Settings.Shape.ROUND, 0.1875);
		context.runOnClient(mc -> Settings.get().minimapSize = 192);
		turn(context, "turning-large", Settings.Shape.SQUARE, 2);
		context.runOnClient(mc -> Settings.get().minimapSize = 96);
		context.runOnClient(mc -> Settings.get().minimapLockNorth = true);
	}

	/** Frames of a full turn. */
	private static void turn(ClientGameTestContext context, String name, Settings.Shape shape, double zoom) {
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapShape = shape;
			s.minimapPixelsPerBlock = zoom;
			s.minimapLockNorth = name.equals("locked");
		});
		String shots = String.format(Locale.ROOT, "minimap-turn-%s-%s-%s", name, shape.name().toLowerCase(Locale.ROOT), zoom);
		// Minecraft's yaw: 180 looks north, 270 east, 0 south, 90 west.
		look(context, 180);
		DioramaClientTest.settle(context, 40);
		context.waitTicks(40);
		for (int frame = 0; frame < 360 / STEP; frame++) {
			look(context, 180 + frame * STEP);
			context.waitTicks(2);
			context.takeScreenshot(String.format(Locale.ROOT, "%s-%03d", shots, frame));
		}
	}

	private static void look(ClientGameTestContext context, float yaw) {
		context.runOnClient(mc -> {
			mc.player.setYRot(yaw);
			mc.player.yRotO = yaw;
			mc.player.setXRot(0);
			mc.player.xRotO = 0;
		});
	}
}
