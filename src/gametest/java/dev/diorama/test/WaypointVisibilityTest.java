package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.NewWaypointsScreen;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.WorldKey;
import dev.diorama.bake.TileKey;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waypoints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Where each waypoint shows ({@code waypoint-visibility}, #479 and #440): a waypoint ahead of the
 * player, its three Visibility boxes in the editor clicked the way a player clicks them, and after
 * each save whether its pin is in the world, on the minimap and on the full map, read off the
 * screen and the map's pins. Unticking all three hides it, and Show brings it back where it was.
 * Then the New waypoints screen from the Waypoints tab, and a waypoint made after it; and the file:
 * the three saved and read back, and a file from before them read as shown everywhere.
 */
final class WaypointVisibilityTest {
	private static final String OVERWORLD = "minecraft:overworld";
	/** A colour no block or other pin uses, to find the pin's head by. */
	private static final int OWN = 0x00FFFF;
	private static final String WORLD = "World";
	private static final String MINIMAP = "Minimap";
	private static final String MAP = "Map";

	private WaypointVisibilityTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		context.setScreen(() -> null);
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		// Facing north, level, with Home ahead and a little left, near enough for the minimap.
		world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 0");
		Settings before = context.computeOnClient(mc -> Settings.get());
		boolean inWorld = before.waypointsInWorld;
		boolean onMap = before.waypointsOnMap;
		Settings.Beams beams = before.waypointBeams;
		int distance = before.waypointDistance;
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		Waypoint home = new Waypoint("Home", at.getX() - 30, at.getY(), at.getZ() - 44, OVERWORLD, 0xFF000000 | OWN);
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.waypointsInWorld = true;
			s.waypointsOnMap = true;
			// No beam: it's in the pin's colour too, and would count as the pin.
			s.waypointBeams = Settings.Beams.OFF;
			s.waypointDistance = 0;
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
			store.add(home);
		});
		try {
			// Long enough for the commands' chat lines to fade.
			context.waitTicks(220);
			expect(context, home, "01-everywhere", true, true, true);

			// One box at a time, unticked in the editor and saved.
			edit(context, home, "02-editor-not-world", WORLD);
			expect(context, home, "03-not-world", false, true, true);
			listShot(context, home, "04-list-not-world", "diorama.waypoint.not_world");
			edit(context, home, "05-editor-not-minimap", WORLD, MINIMAP);
			expect(context, home, "06-not-minimap", true, false, true);
			edit(context, home, "07-editor-not-map", MINIMAP, MAP);
			expect(context, home, "08-not-map", true, true, false);
			edit(context, home, "09-editor-map-only", WORLD, MINIMAP, MAP);
			expect(context, home, "10-map-only", false, false, true);
			listShot(context, home, "11-list-map-only", "diorama.waypoint.only_map");
			// The last box unticked: hidden, as the list's Hide hides it.
			edit(context, home, "12-editor-none", MAP);
			if (context.computeOnClient(mc -> home.visible)) {
				throw new AssertionError("Unticking all three boxes should hide the waypoint");
			}
			expect(context, home, "13-hidden", false, false, false);
			listShot(context, home, "14-list-hidden", "diorama.waypoint.hidden");
			// Show in the list puts it back where it was: on the map alone.
			context.runOnClient(mc -> WaypointStore.get().setVisible(home, true));
			expect(context, home, "15-shown-again", false, false, true);
			// A hidden waypoint's editor has no box ticked; ticking one shows it there.
			context.runOnClient(mc -> WaypointStore.get().setVisible(home, false));
			context.setScreen(() -> new WaypointEditScreen(null, home, false));
			context.waitTicks(3);
			String ticked = context.computeOnClient(mc -> ticked(mc));
			if (!ticked.equals("")) {
				throw new AssertionError("A hidden waypoint's editor should have no box ticked, and has " + ticked);
			}
			clickBox(context, MINIMAP);
			DioramaClientTest.clickButton(context, "diorama.waypoint.save");
			expect(context, home, "16-minimap-only", false, true, false);

			savedAndRead(context, home);
			newWaypoints(context, guiScale);
			legacyFile(context);
		} finally {
			context.setScreen(() -> null);
			context.getInput().resizeWindow(1600, 900);
			DioramaClientTest.setGuiScale(context, guiScale);
			context.runOnClient(mc -> {
				Settings s = Settings.get();
				s.waypointsInWorld = inWorld;
				s.waypointsOnMap = onMap;
				s.waypointBeams = beams;
				s.waypointDistance = distance;
				s.newWaypointInWorld = true;
				s.newWaypointOnMinimap = true;
				s.newWaypointOnMap = true;
				s.save();
				WaypointStore store = WaypointStore.get();
				store.all().stream().toList().forEach(store::remove);
			});
			context.waitTicks(5);
		}
	}

	/** Opens the editor on {@code waypoint}, clicks each box named, as a player does, and saves. */
	private static void edit(ClientGameTestContext context, Waypoint waypoint, String shot, String... boxes) {
		context.setScreen(() -> new WaypointEditScreen(null, waypoint, false));
		context.waitTicks(3);
		for (String box : boxes) {
			clickBox(context, box);
		}
		// Off the boxes, so no tooltip covers the form.
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
		context.takeScreenshot("waypoint-visibility-" + shot);
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
	}

	/** The waypoint shows where expected, and only there: its pin in the world, on the minimap, and among the full map's pins. */
	private static void expect(ClientGameTestContext context, Waypoint waypoint, String shot, boolean world, boolean minimap, boolean map) {
		context.setScreen(() -> null);
		context.waitTicks(5);
		// The cursor moved for the screens turns the camera once they close: facing north again, level,
		// with Home ahead, so a pin missing from the world is one that isn't drawn.
		context.runOnClient(mc -> {
			mc.player.setYRot(180);
			mc.player.setXRot(0);
			mc.player.yRotO = 180;
			mc.player.xRotO = 0;
		});
		context.waitTicks(3);
		BufferedImage picture = read(context.takeScreenshot("waypoint-visibility-" + shot));
		boolean ahead = context.computeOnClient(mc -> dev.diorama.waypoint.WorldProjection.project(waypoint.x + 0.5, waypoint.y + 1.0, waypoint.z + 0.5,
			mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()) != null);
		if (!ahead) {
			throw new AssertionError("Home isn't in front of the camera, so the world check can't see it (" + shot + ")");
		}
		List<String> wrong = new ArrayList<>();
		if (has(world(picture), OWN) != world) {
			wrong.add(world ? "not in the world" : "in the world");
		}
		if (has(minimap(picture), OWN) != minimap) {
			wrong.add(minimap ? "not on the minimap" : "on the minimap");
		}
		context.setScreen(MapScreen::new);
		// Over the hint bar at the bottom, so no hover box covers the map.
		context.getInput().setCursorPos(1500, 880);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(waypoint.x + 0.5, waypoint.z + 0.5),
			Projection.v(waypoint.x + 0.5, waypoint.y, waypoint.z + 0.5), 2, TileKey.SURFACE));
		DioramaClientTest.settle(context, 20);
		boolean pinned = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).pins().stream().anyMatch(p -> p.waypoint().id.equals(waypoint.id)));
		BufferedImage onMap = read(context.takeScreenshot("waypoint-visibility-" + shot + "-map"));
		context.setScreen(() -> null);
		if (pinned != map || has(onMap, OWN) != map) {
			wrong.add(map ? "not on the map" : "on the map");
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("Home set to show in the world " + waypoint.showInWorld + ", on the minimap " + waypoint.showOnMinimap
				+ ", on the map " + waypoint.showOnMap + (waypoint.visible ? "" : ", hidden") + " is " + wrong + " (" + shot + ")");
		}
	}

	/** The waypoint list, with the row saying where its waypoint shows. */
	private static void listShot(ClientGameTestContext context, Waypoint waypoint, String shot, String key) {
		String said = WaypointListScreen.places(waypoint);
		if (!key.equals(said)) {
			throw new AssertionError("The list should say " + key + " of Home, and says " + said);
		}
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
		context.takeScreenshot("waypoint-visibility-" + shot);
		context.setScreen(() -> null);
	}

	/** Saved and read back: a store opened on the world's file has Home where it shows, and hidden or not. */
	private static void savedAndRead(ClientGameTestContext context, Waypoint home) {
		context.runOnClient(mc -> {
			WaypointStore fresh = WaypointStore.open(WaypointStore.get().worldKey());
			Waypoint read = fresh.all().stream().filter(w -> w.id.equals(home.id)).findFirst()
				.orElseThrow(() -> new AssertionError("Home isn't in the saved file"));
			if (read.visible != home.visible || read.showInWorld != home.showInWorld || read.showOnMinimap != home.showOnMinimap || read.showOnMap != home.showOnMap) {
				throw new AssertionError("Home read back as visible " + read.visible + ", in the world " + read.showInWorld + ", on the minimap "
					+ read.showOnMinimap + ", on the map " + read.showOnMap + "; saved as " + home.visible + ", " + home.showInWorld + ", "
					+ home.showOnMinimap + ", " + home.showOnMap);
			}
		});
	}

	/**
	 * New waypoints on the Waypoints tab, on the smallest window the settings are laid out for:
	 * World and Minimap unticked there, a waypoint made with the new-waypoint key's editor starts on
	 * the map alone; with all three unticked one starts hidden.
	 */
	private static void newWaypoints(ClientGameTestContext context, int guiScale) {
		context.getInput().resizeWindow(1280, 720);
		DioramaClientTest.setGuiScale(context, 3);
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(3);
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(3));
		context.waitTicks(5);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
		context.takeScreenshot("waypoint-visibility-17-settings");
		AbstractWidget open = context.computeOnClient(mc -> DioramaClientTest.widget(mc, "New waypoints"));
		if (open == null) {
			throw new AssertionError("The Waypoints tab has no New waypoints button");
		}
		// Above Done, which it would run into on a short window.
		int[] bottoms = context.computeOnClient(mc -> new int[] {open.getY() + open.getHeight(), DioramaClientTest.buttonLabelled(mc.gui.screen(), "Done").getY()});
		DioramaClientTest.clickAt(context, open);
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof NewWaypointsScreen)) {
			throw new AssertionError("New waypoints opened " + context.computeOnClient(mc -> String.valueOf(mc.gui.screen())));
		}
		clickBox(context, WORLD);
		clickBox(context, MINIMAP);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
		context.takeScreenshot("waypoint-visibility-18-new-waypoints");
		boolean[] set = context.computeOnClient(mc -> new boolean[] {Settings.get().newWaypointInWorld, Settings.get().newWaypointOnMinimap, Settings.get().newWaypointOnMap});
		if (set[0] || set[1] || !set[2]) {
			throw new AssertionError("Unticking World and Minimap left new waypoints in the world " + set[0] + ", on the minimap " + set[1] + ", on the map " + set[2]);
		}
		// Done goes back to the settings.
		DioramaClientTest.clickButton(context, "gui.done");
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof SettingsScreen)) {
			throw new AssertionError("Done on New waypoints should go back to the settings");
		}
		context.setScreen(() -> null);
		context.getInput().resizeWindow(1600, 900);
		DioramaClientTest.setGuiScale(context, guiScale);
		context.waitTicks(5);
		if (bottoms[0] > bottoms[1]) {
			throw new AssertionError("New waypoints reaches " + bottoms[0] + ", past Done at " + bottoms[1]);
		}

		Waypoint made = madeNew(context, "19-made-map-only");
		if (!made.visible || made.showInWorld || made.showOnMinimap || !made.showOnMap) {
			throw new AssertionError("A new waypoint should start on the map alone, and is visible " + made.visible + ", in the world " + made.showInWorld
				+ ", on the minimap " + made.showOnMinimap + ", on the map " + made.showOnMap);
		}
		context.runOnClient(mc -> Settings.get().newWaypointOnMap = false);
		Waypoint hidden = madeNew(context, "20-made-hidden");
		if (hidden.visible) {
			throw new AssertionError("With all three unticked, a new waypoint should start hidden");
		}
		// Temporary waypoints are for heading somewhere now: everywhere, whatever new ones are set to.
		Waypoint temporary = context.computeOnClient(mc -> {
			Waypoints.addTemporary(mc.player.blockPosition().offset(6, 0, -6), OVERWORLD);
			return WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.TEMPORARY).max(Comparator.comparingLong(w -> w.created)).orElseThrow();
		});
		if (!temporary.visible || !temporary.showInWorld || !temporary.showOnMinimap || !temporary.showOnMap) {
			throw new AssertionError("A temporary waypoint should show everywhere whatever new waypoints are set to");
		}
		context.runOnClient(mc -> {
			Settings.get().newWaypointInWorld = true;
			Settings.get().newWaypointOnMinimap = true;
			Settings.get().newWaypointOnMap = true;
			WaypointStore store = WaypointStore.get();
			store.remove(made);
			store.remove(hidden);
			store.remove(temporary);
		});
	}

	/** A waypoint made where the player stands, with its editor, the way the new-waypoint key makes one; saved as it opened. */
	private static Waypoint madeNew(ClientGameTestContext context, String shot) {
		Waypoint fresh = context.computeOnClient(mc -> Waypoints.atPlayer());
		context.setScreen(() -> new WaypointEditScreen(null, fresh, true));
		context.waitTicks(3);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
		context.takeScreenshot("waypoint-visibility-" + shot);
		String ticked = context.computeOnClient(mc -> ticked(mc));
		String wanted = context.computeOnClient(mc -> (Settings.get().newWaypointInWorld ? WORLD + " " : "") + (Settings.get().newWaypointOnMinimap ? MINIMAP + " " : "")
			+ (Settings.get().newWaypointOnMap ? MAP + " " : "")).trim();
		if (!ticked.equals(wanted)) {
			throw new AssertionError("A new waypoint's editor has " + ticked + " ticked, for " + wanted);
		}
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		if (!context.computeOnClient(mc -> WaypointStore.get().all().contains(fresh))) {
			throw new AssertionError("Save didn't add the new waypoint");
		}
		return fresh;
	}

	/**
	 * A waypoints file from before the three were saved: its waypoints show everywhere, the hidden
	 * one stays hidden; and one a hand edit turned off everywhere reads as hidden, shown everywhere
	 * once Show is pressed.
	 */
	private static void legacyFile(ClientGameTestContext context) {
		String key = "visibility-test-" + UUID.randomUUID();
		Path folder = context.computeOnClient(mc -> WorldKey.folder(key));
		try {
			Files.createDirectories(folder);
			Files.writeString(folder.resolve("waypoints.json"), """
				[
				  {"name": "Old", "x": 1, "y": 64, "z": 2, "dimension": "minecraft:overworld", "color": -1, "visible": true},
				  {"name": "Old hidden", "x": 3, "y": 64, "z": 4, "dimension": "minecraft:overworld", "color": -1, "visible": false},
				  {"name": "Nowhere", "x": 5, "y": 64, "z": 6, "dimension": "minecraft:overworld", "color": -1, "visible": true,
				   "showInWorld": false, "showOnMinimap": false, "showOnMap": false}
				]
				""", StandardCharsets.UTF_8);
			context.runOnClient(mc -> {
				List<Waypoint> read = WaypointStore.open(key).all();
				if (read.size() != 3) {
					throw new AssertionError("The old file read as " + read.size() + " waypoints");
				}
				for (Waypoint w : read) {
					boolean visible = w.name.equals("Old");
					if (w.visible != visible || !w.showInWorld || !w.showOnMinimap || !w.showOnMap) {
						throw new AssertionError(w.name + " read as visible " + w.visible + ", in the world " + w.showInWorld + ", on the minimap "
							+ w.showOnMinimap + ", on the map " + w.showOnMap);
					}
				}
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			try (Stream<Path> files = Files.walk(folder)) {
				for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			} catch (IOException e) {
				dev.diorama.Diorama.LOGGER.warn("waypoint-visibility: couldn't delete {}", folder, e);
			}
		}
	}

	/** The open screen's ticked checkboxes, by label, in order, space-separated. */
	private static String ticked(Minecraft mc) {
		StringBuilder out = new StringBuilder();
		for (var child : mc.gui.screen().children()) {
			if (child instanceof Checkbox box && box.selected()) {
				out.append(out.isEmpty() ? "" : " ").append(box.getMessage().getString());
			}
		}
		return out.toString();
	}

	/** Clicks the checkbox labelled exactly {@code label}, as a player does. */
	private static void clickBox(ClientGameTestContext context, String label) {
		Checkbox box = context.computeOnClient(mc -> checkbox(mc, label));
		if (box == null) {
			throw new AssertionError("No " + label + " box on " + context.computeOnClient(mc -> String.valueOf(mc.gui.screen())));
		}
		boolean was = context.computeOnClient(mc -> box.selected());
		DioramaClientTest.clickAt(context, box);
		if (context.computeOnClient(mc -> box.selected()) == was) {
			throw new AssertionError("Clicking " + label + " didn't change it");
		}
	}

	private static @Nullable Checkbox checkbox(Minecraft mc, String label) {
		for (var child : mc.gui.screen().children()) {
			if (child instanceof Checkbox box && box.getMessage().getString().equals(label)) {
				return box;
			}
		}
		return null;
	}

	/** The screenshot without the quarter the minimap is in. */
	private static BufferedImage world(BufferedImage shot) {
		return shot.getSubimage(0, 0, shot.getWidth() * 3 / 4, shot.getHeight());
	}

	/** The corner of the screenshot the minimap is in, and nothing of the world the other check covers. */
	private static BufferedImage minimap(BufferedImage shot) {
		return shot.getSubimage(shot.getWidth() * 3 / 4, 0, shot.getWidth() / 4, shot.getHeight() / 2);
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
}
