package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.bake.TileKey;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Portal pins kept to the maps ({@code portals-world}): a player with a dozen portals ahead, a
 * death marker and a few waypoints of their own. With Portals in the world off, the portal pins and
 * their beams leave the world and nothing else does, and the minimap and the map keep them.
 */
final class PortalsWorldTest {
	private static final String OVERWORLD = "minecraft:overworld";
	/** Colours no block or other pin uses, to find the pins' heads by. */
	private static final int PORTAL = 0xFF00FF;
	private static final int OWN = 0x00FFFF;

	private PortalsWorldTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("difficulty peaceful");
		context.setScreen(() -> null);
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		// Facing north, level, so what's ahead spreads across the view.
		world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 0");
		Settings before = context.computeOnClient(mc -> Settings.get());
		boolean portalPins = before.portalMarkers;
		boolean inWorld = before.portalMarkersInWorld;
		Settings.Beams beams = before.waypointBeams;
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		context.runOnClient(mc -> {
			Settings.get().portalMarkers = true;
			Settings.get().portalMarkersInWorld = true;
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
		});
		try {
			// A dozen portals ahead, each far enough from the next for a pin of its own.
			int portals = 0;
			for (int row = 0; row < 3; row++) {
				for (int column = 0; column < 4; column++) {
					int x = at.getX() - 62 + column * 40 + row * 6;
					int z = at.getZ() - 30 - row * 32;
					int y = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
					world.getServer().runCommand("fill " + x + " " + y + " " + z + " " + (x + 3) + " " + (y + 4) + " " + z + " obsidian");
					world.getServer().runCommand("fill " + (x + 1) + " " + (y + 1) + " " + z + " " + (x + 2) + " " + (y + 3) + " " + z + " nether_portal[axis=x]");
					portals++;
				}
			}
			int expected = portals;
			for (int i = 0; ; i++) {
				long pins = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.NETHER_PORTAL).count());
				if (pins == expected) {
					break;
				}
				if (i > 400) {
					throw new AssertionError(expected + " portal pins expected, got " + pins);
				}
				context.waitTick();
			}
			context.runOnClient(mc -> {
				WaypointStore store = WaypointStore.get();
				Waypoint death = new Waypoint(Waypoint.deathName(java.time.LocalTime.of(14, 5)), at.getX() + 12, at.getY(), at.getZ() - 48, OVERWORLD, 0xFF60646C);
				death.kind = Waypoint.Kind.DEATH;
				store.add(death);
				store.add(new Waypoint("Home", at.getX() - 30, at.getY(), at.getZ() - 44, OVERWORLD, 0xFF000000 | OWN));
				store.add(new Waypoint("Iron Farm", at.getX() + 44, at.getY(), at.getZ() - 80, OVERWORLD, Waypoint.PALETTE[3]));
				store.add(new Waypoint("Mine", at.getX() - 4, at.getY(), at.getZ() - 120, OVERWORLD, Waypoint.PALETTE[2]));
			});
			// Long enough for the commands' chat lines to fade.
			context.waitTicks(220);
			// As a player sees it, for the pictures.
			context.takeScreenshot("portals-world-01-on");
			context.runOnClient(mc -> Settings.get().portalMarkersInWorld = false);
			context.waitTicks(5);
			context.takeScreenshot("portals-world-02-off");

			// Then with the portal pins in a colour of their own, and a beam on every waypoint.
			context.runOnClient(mc -> {
				Settings.get().portalMarkersInWorld = true;
				Settings.get().waypointBeams = Settings.Beams.ALL;
				WaypointStore store = WaypointStore.get();
				store.all().stream().filter(w -> w.kind.isPortal()).forEach(w -> w.color = 0xFF000000 | PORTAL);
				store.changed();
			});
			context.waitTicks(5);
			BufferedImage on = read(context.takeScreenshot("portals-world-03-marked-on"));
			if (!has(world(on), PORTAL) || !has(world(on), OWN)) {
				throw new AssertionError("The portal pins and the player's waypoint aren't both drawn in the world with Portals in the world on");
			}
			context.runOnClient(mc -> Settings.get().portalMarkersInWorld = false);
			context.waitTicks(5);
			BufferedImage off = read(context.takeScreenshot("portals-world-04-marked-off"));
			if (has(world(off), PORTAL)) {
				throw new AssertionError("A portal pin or its beam is still drawn in the world with Portals in the world off");
			}
			if (!has(world(off), OWN)) {
				throw new AssertionError("Portals in the world took the player's own waypoint out of the world too");
			}
			if (!has(off, PORTAL)) {
				throw new AssertionError("Portals in the world took the portal pins off the minimap too");
			}

			context.setScreen(MapScreen::new);
			// Over the hint bar at the bottom, so no hover box covers the map.
			context.getInput().setCursorPos(1500, 880);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(at.getX(), Projection.v(at.getY(), at.getZ() - 60), 2, TileKey.SURFACE));
			settle(context, 60);
			long onMap = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).pins().stream().filter(p -> p.waypoint().kind.isPortal()).count());
			context.takeScreenshot("portals-world-05-map");
			context.setScreen(() -> null);
			if (onMap != expected) {
				throw new AssertionError("The map should still show all " + expected + " portal pins with Portals in the world off, but shows " + onMap);
			}

			// The setting, on the smallest window the settings are laid out for.
			context.getInput().resizeWindow(1280, 720);
			DioramaClientTest.setGuiScale(context, 3);
			context.setScreen(() -> new SettingsScreen(null));
			context.waitTicks(3);
			context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(3));
			context.waitTicks(5);
			context.takeScreenshot("portals-world-06-settings");
			context.setScreen(() -> null);
		} finally {
			context.getInput().resizeWindow(1600, 900);
			DioramaClientTest.setGuiScale(context, guiScale);
			context.runOnClient(mc -> {
				Settings.get().portalMarkers = portalPins;
				Settings.get().portalMarkersInWorld = inWorld;
				Settings.get().waypointBeams = beams;
				Settings.get().save();
				WaypointStore store = WaypointStore.get();
				store.all().stream().toList().forEach(store::remove);
			});
			// The portals too: the tests after this one count the pins they make.
			for (int row = 0; row < 3; row++) {
				for (int column = 0; column < 4; column++) {
					int x = at.getX() - 62 + column * 40 + row * 6;
					int z = at.getZ() - 30 - row * 32;
					world.getServer().runCommand("fill " + x + " " + (at.getY() - 40) + " " + z + " " + (x + 3) + " " + (at.getY() + 60) + " " + z + " air replace nether_portal");
					world.getServer().runCommand("fill " + x + " " + (at.getY() - 40) + " " + z + " " + (x + 3) + " " + (at.getY() + 60) + " " + z + " air replace obsidian");
				}
			}
			context.waitTicks(40);
		}
	}

	/** The screenshot without the quarter the minimap is in. */
	private static BufferedImage world(BufferedImage shot) {
		return shot.getSubimage(0, 0, shot.getWidth() * 3 / 4, shot.getHeight());
	}

	/** Whether enough of the picture is this colour to be a pin's head, and not a stray pixel. */
	private static boolean has(BufferedImage shot, int rgb) {
		int count = 0;
		for (int y = 0; y < shot.getHeight(); y++) {
			for (int x = 0; x < shot.getWidth(); x++) {
				int argb = shot.getRGB(x, y);
				if (Math.abs((argb >> 16 & 0xFF) - (rgb >> 16 & 0xFF)) <= 4 && Math.abs((argb >> 8 & 0xFF) - (rgb >> 8 & 0xFF)) <= 4 && Math.abs((argb & 0xFF) - (rgb & 0xFF)) <= 4) {
					count++;
				}
			}
		}
		return count >= 12;
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
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
