package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.render.Pins;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointIconScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Initials of one to four letters (opt-in: {@code long-initials}): pins in the world, on the square
 * and round minimap and on the map, badges at the minimap's edge and in the waypoint list, and the
 * editor, for the pictures. Then initials typed in small letters, which stay as typed.
 */
final class LongInitialsTest {
	private LongInitialsTest() {
	}

	static void photos(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("difficulty peaceful");
		// The showcase and icon-waypoints leave the player spectating in the air, where this would wait for the ground forever.
		if (context.computeOnClient(mc -> mc.player.isSpectator())) {
			throw new AssertionError("Run long-initials without showcase or icon-waypoints: they leave the player spectating");
		}
		// The same spot every run. Where the player spawns changes from run to run, and with it the
		// ground the pins stand on: one spot put MUSH's head over the right end of WOOD's.
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
			String[][] pins = {{"Home", "H"}, {"Iron Farm", "IF"}, {"Mob Grinder", "MOB"}, {"Iron Farm 2", "IRF2"}, {"Woodland Mansion", "WOOD"}, {"Mushroom Island", "MUSH"}};
			for (int i = 0; i < pins.length; i++) {
				int x = at.getX() - 15 + i * 6;
				int z = at.getZ() - 18 - (i % 2) * 6;
				Waypoint waypoint = new Waypoint(pins[i][0], x, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z, overworld, Waypoint.PALETTE[(i * 3 + 1) % Waypoint.PALETTE.length]);
				waypoint.initials = pins[i][1];
				store.add(waypoint);
			}
			// Far away, so the minimap shows them as badges at its edge.
			String[][] far = {{"Stronghold", "END"}, {"Ancient City", "WARD"}, {"Village", "V"}};
			for (int i = 0; i < far.length; i++) {
				double angle = 0.9 + i * 1.7;
				Waypoint waypoint = new Waypoint(far[i][0], at.getX() + (int) (Math.cos(angle) * 600), at.getY(), at.getZ() + (int) (Math.sin(angle) * 600), overworld, Waypoint.PALETTE[(i * 5 + 2) % Waypoint.PALETTE.length]);
				waypoint.initials = far[i][1];
				store.add(waypoint);
			}
		});
		context.waitTicks(10);
		context.takeScreenshot("initials-world");
		context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.ROUND);
		context.waitTicks(10);
		context.takeScreenshot("initials-round");
		context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.SQUARE);

		context.setScreen(MapScreen::new);
		// Over the hint bar at the bottom, so no hover box covers the map.
		context.getInput().setCursorPos(1500, 880);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(at.getX(), Projection.v(at.getY(), at.getZ() - 20), 4.0, TileKey.SURFACE));
		settle(context, 60);
		context.takeScreenshot("initials-map");
		// The cursor over the right end of a wide pin's head points at that pin.
		String pointed = context.computeOnClient(mc -> {
			for (WaypointOverlay.Pin pin : ((MapScreen) mc.gui.screen()).pins()) {
				if (pin.waypoint().initials.equals("WOOD") && !pin.clamped()) {
					float end = pin.x() + (Pins.badgeWidth(pin.waypoint(), (int) pin.size()) / 2.0F - 2) * pin.scale();
					WaypointOverlay.Pin under = WaypointOverlay.under(((MapScreen) mc.gui.screen()).pins(), end, pin.headY());
					return under == null ? "nothing" : under.waypoint().initials;
				}
			}
			return "no WOOD pin on the map";
		});
		if (!pointed.equals("WOOD")) {
			throw new AssertionError("The right end of WOOD's pin on the map points at " + pointed);
		}
		context.setScreen(() -> null);

		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("initials-list");
		Waypoint wood = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.initials.equals("WOOD")).findFirst().orElseThrow());
		context.setScreen(() -> new WaypointEditScreen(null, wood, false));
		context.waitTicks(5);
		context.takeScreenshot("initials-editor");
		context.setScreen(() -> new WaypointIconScreen(null, wood, "", id -> { }));
		context.waitTicks(5);
		context.takeScreenshot("initials-icon-picker");
		context.setScreen(() -> null);

		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
		});
		typedInSmallLetters(context, world, at);
	}

	/** Initials typed into the editor and saved with its button, then every place that shows them. */
	private static void typedInSmallLetters(ClientGameTestContext context, TestSingleplayerContext world, BlockPos at) {
		String[][] pins = {{"Сана", "СаН"}, {"Waypoint 427", "WwW"}, {"Гаражы", "гар"}, {"Pigs", "gypq"}, {"Base", "ab"}};
		// Checked at the end, so a failing run still has the pictures of what went wrong.
		java.util.List<String> changed = new java.util.ArrayList<>();
		for (int i = 0; i < pins.length; i++) {
			int index = i;
			Waypoint waypoint = context.computeOnClient(mc -> {
				int x = at.getX() - 15 + index * 7;
				int z = at.getZ() - 18 - (index % 2) * 6;
				return new Waypoint(pins[index][0], x, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z, "minecraft:overworld", Waypoint.PALETTE[(index * 3 + 1) % Waypoint.PALETTE.length]);
			});
			context.setScreen(() -> new WaypointEditScreen(null, waypoint, true));
			context.waitTicks(3);
			DioramaClientTest.typeInto(context, 1, pins[i][1]);
			context.takeScreenshot("typed-initials-editor-" + i);
			DioramaClientTest.clickButton(context, "diorama.waypoint.save");
			context.waitTicks(3);
			if (!waypoint.initials.equals(pins[i][1])) {
				changed.add(pins[i][1] + " saved as " + waypoint.initials);
			}
		}
		// Moving the mouse to Save turned the player: facing the pins again.
		world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 15");
		context.waitTicks(220);
		context.takeScreenshot("typed-initials-world");
		context.setScreen(MapScreen::new);
		context.getInput().setCursorPos(1500, 880);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(at.getX(), Projection.v(at.getY(), at.getZ() - 20), 4.0, TileKey.SURFACE));
		settle(context, 60);
		context.takeScreenshot("typed-initials-map");
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("typed-initials-list");

		// Saving an imported waypoint's editor without touching its initials leaves them as they came.
		Waypoint imported = context.computeOnClient(mc -> WaypointStore.get().all().get(0));
		context.runOnClient(mc -> imported.initials = "Ал");
		context.setScreen(() -> new WaypointEditScreen(null, imported, false));
		context.waitTicks(3);
		context.takeScreenshot("typed-initials-reopened");
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.waitTicks(3);
		if (!imported.initials.equals("Ал")) {
			changed.add("Ал saved as " + imported.initials);
		}
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
		});
		if (!changed.isEmpty()) {
			throw new AssertionError("The editor changed what was typed: " + changed);
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
