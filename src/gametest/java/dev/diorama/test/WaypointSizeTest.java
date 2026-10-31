package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.bake.TileKey;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Waypoint sizes (opt-in: {@code waypoint-size}): pins with one to four letters, an icon and a
 * headstone in the world, on the square and round minimap and on the map, at each size and GUI
 * scale, for the pictures. The cursor has to find a pin where it's drawn, at every size.
 */
final class WaypointSizeTest {
	/** The spot's own colour, which no other pin uses, for measuring where the point lands. */
	private static final int PROBE = 0xFF00FF;
	private static final int[][] SIZES = {
		{2, 50, 100, 150, 200},
		{3, 33, 67, 100, 133, 167, 200},
		{4, 25, 50, 75, 100, 125, 150, 175, 200}
	};

	private WaypointSizeTest() {
	}

	static void photos(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("difficulty peaceful");
		if (context.computeOnClient(mc -> mc.player.isSpectator())) {
			throw new AssertionError("Run waypoint-size without showcase or icon-waypoints: they leave the player spectating");
		}
		int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, -2, -2));
		world.getServer().runCommand("tp @a -1.5 " + ground + " -1.5");
		context.waitFor(mc -> mc.player.onGround() && mc.player.blockPosition().getX() == -2 && mc.player.blockPosition().getZ() == -2, 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		// Facing north, looking a little down at the pins ahead.
		world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 15");
		// Long enough for the commands' chat lines to fade.
		context.waitTicks(220);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			String overworld = "minecraft:overworld";
			String[][] pins = {{"Home", "H"}, {"Iron Farm", "IF"}, {"Mob Grinder", "MOB"}, {"Woodland Mansion", "WOOD"}, {"Farm", "F"}, {"Death", ""}};
			for (int i = 0; i < pins.length; i++) {
				int x = at.getX() - 12 + i * 5;
				int z = at.getZ() - 14 - (i % 2) * 6;
				Waypoint waypoint = new Waypoint(pins[i][0], x, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z, overworld, Waypoint.PALETTE[(i * 3 + 1) % Waypoint.PALETTE.length]);
				waypoint.initials = pins[i][1];
				if (pins[i][0].equals("Farm")) {
					waypoint.icon = "diorama:farm";
				}
				if (pins[i][0].equals("Death")) {
					waypoint.kind = Waypoint.Kind.DEATH;
				}
				store.add(waypoint);
			}
			Waypoint probe = new Waypoint("Probe", at.getX() + 42, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX() + 42, at.getZ() + 12), at.getZ() + 12, overworld, PROBE);
			probe.initials = "P";
			store.add(probe);
			// Far away, so the minimap shows them as badges at its edge.
			String[][] far = {{"Stronghold", "END"}, {"Ancient City", "WARD"}, {"Village", "V"}};
			for (int i = 0; i < far.length; i++) {
				double angle = 0.9 + i * 1.7;
				Waypoint waypoint = new Waypoint(far[i][0], at.getX() + (int) (Math.cos(angle) * 600), at.getY(), at.getZ() + (int) (Math.sin(angle) * 600), overworld, Waypoint.PALETTE[(i * 5 + 2) % Waypoint.PALETTE.length]);
				waypoint.initials = far[i][1];
				store.add(waypoint);
			}
		});
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		// Big enough for a GUI scale of 4, which a smaller window clamps to 3.
		context.getInput().resizeWindow(1920, 1080);
		context.waitTicks(10);
		for (int[] row : SIZES) {
			int gui = row[0];
			DioramaClientTest.setGuiScale(context, gui);
			for (int i = 1; i < row.length; i++) {
				int size = row[i];
				String name = "gui" + gui + "-" + size;
				context.runOnClient(mc -> {
					Settings settings = Settings.get();
					settings.waypointMinimapSize = size;
					settings.waypointMapSize = size;
					settings.waypointWorldSize = size;
					settings.minimapShape = Settings.Shape.SQUARE;
				});
				context.waitTicks(4);
				context.takeScreenshot("size-world-" + name);
				context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.ROUND);
				context.waitTicks(4);
				context.takeScreenshot("size-round-" + name);
				context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.SQUARE);
				context.setScreen(MapScreen::new);
				// Over the hint bar at the bottom, so no hover box covers the map.
				context.getInput().setCursorPos(1500, 1070);
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(at.getX(), Projection.v(at.getY(), at.getZ() - 4), 3.0, TileKey.SURFACE));
				settle(context, 30);
				context.takeScreenshot("size-map-" + name);
				pointing(context, size);
				context.setScreen(() -> null);
			}
		}
		DioramaClientTest.setGuiScale(context, 3);
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(3);
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(3));
		context.waitTicks(5);
		context.takeScreenshot("size-settings");
		context.setScreen(() -> null);

		DioramaClientTest.setGuiScale(context, guiScale);
		context.getInput().resizeWindow(1600, 900);
		context.waitTicks(10);
		context.runOnClient(mc -> {
			Settings settings = Settings.get();
			settings.waypointMinimapSize = 67;
			settings.waypointMapSize = 67;
			settings.waypointWorldSize = 67;
			settings.save();
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
		});
	}

	/** The top of a pin's head answers the cursor, and just past it doesn't, whatever the size. */
	private static void pointing(ClientGameTestContext context, int size) {
		String wrong = context.computeOnClient(mc -> {
			java.util.List<WaypointOverlay.Pin> pins = ((MapScreen) mc.gui.screen()).pins();
			for (WaypointOverlay.Pin pin : pins) {
				if (pin.clamped() || !pin.waypoint().initials.equals("P")) {
					continue;
				}
				// A large pin's top row is 21 pixels above its point, and the cursor reaches 2 past it.
				WaypointOverlay.Pin inside = WaypointOverlay.under(pins, pin.x(), pin.y() - 22 * pin.scale());
				WaypointOverlay.Pin outside = WaypointOverlay.under(pins, pin.x(), pin.y() - 24 * pin.scale());
				if (inside != pin) {
					return "The top of the pin's head does not answer the cursor";
				}
				return outside == pin ? "The pin answers the cursor above its head" : null;
			}
			return "No probe pin on the map";
		});
		if (wrong != null) {
			throw new AssertionError(wrong + " at " + size + "%");
		}
	}

	/** Waits until no tile has been baking for a while. */
	private static void settle(ClientGameTestContext context, int minTicks) {
		context.waitTicks(minTicks);
		try (WorldPause paused = WorldPause.pause(context)) {
			int quiet = 0;
			for (int i = 0; i < 1200 && quiet < 30; i++) {
				context.waitTick();
				boolean baking = context.computeOnClient(mc -> MapSession.get() != null && MapSession.get().map().bakingCount() > 0);
				quiet = baking ? 0 : quiet + 1;
			}
		}
	}
}
