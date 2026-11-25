package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Maps;
import dev.diorama.WorldKey;
import dev.diorama.api.BlockCoord;
import dev.diorama.api.DimensionId;
import dev.diorama.api.MapWorld;
import dev.diorama.api.NewWaypoint;
import dev.diorama.api.WaypointUpdate;
import dev.diorama.bake.TileKey;
import dev.diorama.testaddon.TestAddon;
import dev.diorama.testaddon.looping.LoopingAddon;
import dev.diorama.testaddon.throwing.ThrowingAddon;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.Waypoints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The addon API's waypoints and worlds ({@code addon-api}), through the test addons, which only
 * know the API. In the first world ({@link #firstWorld}): the addon was told the world opened,
 * and of each change of dimension and of saved map, with no waypoint events for the switch; it
 * reads what the waypoint list shows; a waypoint it adds is in the list, on the map, in the world
 * and in waypoints.json after one save; what the player adds, renames, hides and deletes in
 * Diorama's own screens, clicked and typed, reaches it as events with no mod ID, each with the one
 * save it always had; its own edits reach it with its mod ID, one save and one event a batch,
 * echoes from inside the listener after; it can't change an automatic pin, and a batch that
 * throws changes nothing; a listener that throws is switched off and the store carries on; one
 * that answers its own changes for ever is stopped after 50 rounds; all() hands out one list
 * until something changes; calls from another thread throw. Leaving closes the world
 * ({@link #left}), and the world opened again ({@link #reopened}) has the same IDs and still
 * has the addon's waypoint. {@link #measure} counts what reading and editing cost.
 */
final class AddonWaypointsTest {
	/** Pure cyan, which no block in the test world is, to find the addon's pin in the world by. */
	private static final int PIN = 0xFF00FFFF;
	private static final String FROM_ADDON = "From addon";
	private static String worldId;
	private static UUID fromAddon;

	private AddonWaypointsTest() {
	}

	/** At the title screen: no world yet, so nothing opened. */
	static void titleScreen(ClientGameTestContext context) {
		List<String> events = context.computeOnClient(mc -> List.copyOf(TestAddon.worldEvents()));
		if (!events.isEmpty()) {
			throw new AssertionError("The test addon was told about a world at the title screen: " + events);
		}
	}

	static void firstWorld(ClientGameTestContext context, TestSingleplayerContext world) {
		List<String> wrong = new ArrayList<>();
		// AddonApiTest.firstWorld went to the Nether and back.
		List<String> events = context.computeOnClient(mc -> List.copyOf(TestAddon.worldEvents()));
		worldId = context.computeOnClient(mc -> TestAddon.world().worldId());
		List<String> expected = List.of("opened " + worldId + " minecraft:overworld main", "mapChanged " + worldId + " minecraft:the_nether main",
			"mapChanged " + worldId + " minecraft:overworld main");
		if (!events.equals(expected) || !worldId.matches("[0-9a-f]{32}")) {
			wrong.add("World events in the first world, there and back to the Nether: " + events + ", expected " + expected);
		}
		Diorama.LOGGER.info("addon-api waypoints: world events {}", events);

		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		context.setScreen(() -> null);
		context.waitFor(mc -> mc.player.onGround(), 600);
		reads(context, wrong);
		adds(context, wrong);
		player(context, wrong);
		echo(context, wrong);
		automatic(context, wrong);
		sameList(context, wrong);
		threads(context, wrong);
		throwing(context, wrong);
		looping(context, wrong);
		savedMaps(context, wrong);
		if (!wrong.isEmpty()) {
			throw new AssertionError("Addon waypoints in the first world:\n  " + String.join("\n  ", wrong));
		}
		Diorama.LOGGER.info("addon-api waypoints: first world checks passed");
	}

	/** all() is what the waypoint list shows: the store's waypoints and its waystones, in that order. */
	private static void reads(ClientGameTestContext context, List<String> wrong) {
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			BlockPos at = mc.player.blockPosition();
			store.add(new Waypoint("Read one", at.getX() + 5, at.getY(), at.getZ(), "minecraft:overworld", Waypoint.PALETTE[3]));
			store.add(new Waypoint("Read two", at.getX(), at.getY(), at.getZ() + 5, "minecraft:the_nether", Waypoint.PALETTE[4]));
		});
		String listed = context.computeOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			List<String> out = new ArrayList<>();
			for (List<Waypoint> list : List.of(store.all(), store.waystones())) {
				for (Waypoint w : list) {
					out.add(w.id + " " + w.name + " " + w.dimension + " " + w.x + "," + w.y + "," + w.z);
				}
			}
			return String.join("; ", out);
		});
		String read = context.computeOnClient(mc -> {
			List<String> out = new ArrayList<>();
			for (dev.diorama.api.Waypoint w : TestAddon.world().waypoints().all()) {
				out.add(w.id() + " " + w.name() + " " + w.dimension() + " " + w.pos().x() + "," + w.pos().y() + "," + w.pos().z());
			}
			return String.join("; ", out);
		});
		if (!read.equals(listed) || !read.contains("Read two minecraft:the_nether")) {
			wrong.add("all() reads " + read + "; the list has " + listed);
		}
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.name.startsWith("Read ")).toList().forEach(store::remove);
		});
	}

	/** A waypoint the addon adds: one save, in the file with its mod ID, in the list, on the map and in the world. */
	private static void adds(ClientGameTestContext context, List<String> wrong) {
		Path file = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int hx = at.getX() - 4;
		int hz = at.getZ() - 16;
		int hy = context.computeOnClient(mc -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, hx, hz));
		context.runOnClient(mc -> TestAddon.forgetHeard());
		int savesBefore = context.computeOnClient(mc -> WaypointStore.saves());
		fromAddon = context.computeOnClient(mc -> {
			UUID[] id = new UUID[1];
			TestAddon.world().waypoints().edit(edits -> id[0] = edits.add(NewWaypoint.builder(FROM_ADDON, DimensionId.OVERWORLD, BlockCoord.of(hx, hy, hz))
				.color(PIN).initials("TA").build()));
			return id[0];
		});
		int saves = context.computeOnClient(mc -> WaypointStore.saves()) - savesBefore;
		String json = text(file);
		boolean inFile = json.contains(fromAddon.toString()) && json.contains("\"addedBy\": \"diorama_test_addon\"");
		List<String> heard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		Optional<String> addedBy = context.computeOnClient(mc -> TestAddon.world().waypoints().get(fromAddon).flatMap(dev.diorama.api.Waypoint::addedBy));
		if (saves != 1 || !inFile || !heard.equals(List.of("diorama_test_addon ADDED  > " + FROM_ADDON)) || !addedBy.equals(Optional.of("diorama_test_addon"))) {
			wrong.add("Adding one waypoint: " + saves + " saves, in waypoints.json with its mod ID " + inFile + ", heard " + heard + ", addedBy " + addedBy);
		}

		// A batch of three: two added, the first renamed. One save, one event, the rename folded in.
		context.runOnClient(mc -> TestAddon.forgetHeard());
		int batchBefore = context.computeOnClient(mc -> WaypointStore.saves());
		int[] events = new int[1];
		context.runOnClient(mc -> {
			var counting = TestAddon.world().waypoints().listen(changes -> events[0]++);
			TestAddon.world().waypoints().edit(edits -> {
				UUID first = edits.add(NewWaypoint.builder("Batch one", DimensionId.OVERWORLD, BlockCoord.of(hx + 2, hy, hz)).build());
				edits.add(NewWaypoint.builder("Batch two", DimensionId.OVERWORLD, BlockCoord.of(hx + 4, hy, hz)).folder("Addon folder").build());
				edits.update(first, WaypointUpdate.builder().name("Batch one renamed").build());
			});
			counting.close();
		});
		int batchSaves = context.computeOnClient(mc -> WaypointStore.saves()) - batchBefore;
		List<String> batchHeard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		boolean folderMade = context.computeOnClient(mc -> WaypointStore.get().customFolders().contains("Addon folder"));
		if (batchSaves != 1 || events[0] != 1 || !batchHeard.equals(List.of("diorama_test_addon ADDED  > Batch one renamed", "diorama_test_addon ADDED  > Batch two")) || !folderMade) {
			wrong.add("A batch of three changes: " + batchSaves + " saves, " + events[0] + " events, heard " + batchHeard + ", folder made " + folderMade);
		}
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> {
			for (dev.diorama.api.Waypoint w : TestAddon.world().waypoints().all()) {
				if (w.name().startsWith("Batch ")) {
					edits.remove(w.id());
				}
			}
		}));

		// In the list.
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("addon-api-10-list");
		boolean listed = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).shown().stream().anyMatch(w -> w.id.equals(fromAddon)));
		// On the map.
		context.setScreen(MapScreen::new);
		DioramaClientTest.lookAtPlayer(context, 4.0, TileKey.SURFACE);
		DioramaClientTest.settle(context, 30);
		context.takeScreenshot("addon-api-11-map");
		boolean onMap = context.computeOnClient(mc -> {
			for (WaypointOverlay.Pin pin : ((MapScreen) mc.gui.screen()).pins()) {
				if (pin.waypoint().id.equals(fromAddon)) {
					return true;
				}
			}
			return false;
		});
		context.setScreen(() -> null);
		// In the world.
		BlockPos pin = new BlockPos(hx, hy, hz);
		//? if >=26.2 {
		context.getInput().lookAt(pin);
		//?} else {
		/*context.runOnClient(mc -> mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, net.minecraft.world.phys.Vec3.atCenterOf(pin)));
		*///?}
		context.waitTicks(10);
		BufferedImage shot = read(context.takeScreenshot("addon-api-12-world"));
		boolean inWorld = has(shot.getSubimage(0, 0, shot.getWidth() * 3 / 4, shot.getHeight()), PIN);
		if (!listed || !onMap || !inWorld) {
			wrong.add("The addon's waypoint: in the list " + listed + ", on the map " + onMap + ", in the world " + inWorld);
		}
		Diorama.LOGGER.info("addon-api waypoints: added {} with one save, in the list, on the map and in the world", fromAddon);
	}

	/**
	 * The player adds, renames, hides and deletes a waypoint in Diorama's screens, with real clicks
	 * and typing: the addon hears each, with no mod ID, and each saves once, as before.
	 */
	private static void player(ClientGameTestContext context, List<String> wrong) {
		context.runOnClient(mc -> TestAddon.forgetHeard());
		List<Integer> saves = new ArrayList<>();
		int start = context.computeOnClient(mc -> WaypointStore.saves());

		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(3);
		DioramaClientTest.clickButton(context, "diorama.waypoint.new");
		context.waitTicks(3);
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointEditScreen)) {
			wrong.add("New waypoint in the list didn't open the editor");
			context.setScreen(() -> null);
			return;
		}
		DioramaClientTest.typeInto(context, 0, "Player made");
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.waitTicks(3);
		saves.add(context.computeOnClient(mc -> WaypointStore.saves()) - start);

		Waypoint made = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.name.equals("Player made")).findFirst().orElse(null));
		if (made == null) {
			wrong.add("Saving the editor didn't add the player's waypoint");
			context.setScreen(() -> null);
			return;
		}
		select(context, made);
		DioramaClientTest.clickButton(context, "diorama.waypoints.edit");
		context.waitTicks(3);
		DioramaClientTest.typeInto(context, 0, "Player renamed");
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.waitTicks(3);
		saves.add(context.computeOnClient(mc -> WaypointStore.saves()) - start);

		// The row's own Hide, not the folder's at the top.
		int[] row = select(context, made);
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> rowButton(mc.gui.screen(), "diorama.waypoints.hide", row[1])));
		context.waitTicks(3);
		saves.add(context.computeOnClient(mc -> WaypointStore.saves()) - start);
		context.takeScreenshot("addon-api-13-player-hid");

		select(context, made);
		DioramaClientTest.clickButton(context, "diorama.waypoint.delete");
		DioramaClientTest.clickButton(context, "diorama.menu.delete_confirm");
		context.waitTicks(3);
		saves.add(context.computeOnClient(mc -> WaypointStore.saves()) - start);
		context.setScreen(() -> null);

		List<String> heard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		List<String> expected = List.of("- ADDED  > Player made", "- CHANGED Player made > Player renamed", "- CHANGED Player renamed > Player renamed (hidden)",
			"- REMOVED Player renamed > ");
		if (!heard.equals(expected) || !saves.equals(List.of(1, 2, 3, 4))) {
			wrong.add("The player's add, rename, hide and delete: heard " + heard + ", expected " + expected + "; saves after each " + saves);
		}
		Diorama.LOGGER.info("addon-api waypoints: the player's own edits were heard as {}, saves after each {}", heard, saves);
	}

	/** The button with this label on the row at this height, in GUI pixels. */
	private static net.minecraft.client.gui.components.Button rowButton(net.minecraft.client.gui.screens.Screen screen, String key, int rowY) {
		String label = net.minecraft.network.chat.Component.translatable(key).getString();
		for (var child : screen.children()) {
			if (child instanceof net.minecraft.client.gui.components.Button button && button.visible && button.getMessage().getString().equals(label)
				&& Math.abs(button.getY() + button.getHeight() / 2 - rowY) < 12) {
				return button;
			}
		}
		throw new AssertionError("No " + label + " button on the row at " + rowY);
	}

	/** Clicks the waypoint's row in the list, opening the list if it isn't up; returns where. */
	private static int[] select(ClientGameTestContext context, Waypoint waypoint) {
		if (!context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointListScreen)) {
			context.setScreen(() -> new WaypointListScreen(null));
			context.waitTicks(3);
		}
		int[] row = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(waypoint));
		if (row == null) {
			throw new AssertionError("The row of " + waypoint.name + " isn't in view");
		}
		DioramaClientTest.clickAtGui(context, row[0], row[1]);
		return row;
	}

	/**
	 * The test addon renames a waypoint called "Echo me" from inside its listener: the add is heard
	 * first, then the rename, and the listener sees the rename already in all().
	 */
	private static void echo(ClientGameTestContext context, List<String> wrong) {
		context.runOnClient(mc -> TestAddon.forgetHeard());
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int before = context.computeOnClient(mc -> WaypointStore.saves());
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> edits.add(NewWaypoint.builder(TestAddon.ECHO, DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ() + 3)).build())));
		int saves = context.computeOnClient(mc -> WaypointStore.saves()) - before;
		List<String> heard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		String stored = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.name.startsWith("Echo")).map(w -> w.name).toList().toString());
		List<String> expected = List.of("diorama_test_addon ADDED  > " + TestAddon.ECHO, "diorama_test_addon CHANGED " + TestAddon.ECHO + " > " + TestAddon.ECHOED);
		if (!heard.equals(expected) || saves != 2 || !stored.equals("[" + TestAddon.ECHOED + "]")) {
			wrong.add("A rename from inside the listener: heard " + heard + ", expected " + expected + ", " + saves + " saves, stored " + stored);
		}
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.name.equals(TestAddon.ECHOED)).toList().forEach(store::remove);
		});
	}

	/** Automatic pins are read but not written, and a batch that throws changes nothing. */
	private static void automatic(ClientGameTestContext context, List<String> wrong) {
		context.runOnClient(mc -> Waypoints.addTemporary(mc.player.blockPosition().east(3), "minecraft:overworld"));
		UUID temporary = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.TEMPORARY).findFirst().orElseThrow().id);
		String kind = context.computeOnClient(mc -> TestAddon.world().waypoints().get(temporary).map(w -> w.kind().name()).orElse("none"));
		int count = context.computeOnClient(mc -> WaypointStore.get().all().size());
		int saves = context.computeOnClient(mc -> WaypointStore.saves());
		String update = context.computeOnClient(mc -> thrown(() -> TestAddon.world().waypoints().edit(edits -> {
			edits.add(NewWaypoint.builder("Never added", DimensionId.OVERWORLD, BlockCoord.of(0, 64, 0)).build());
			edits.update(temporary, WaypointUpdate.builder().name("Mine now").build());
		})));
		String remove = context.computeOnClient(mc -> thrown(() -> TestAddon.world().waypoints().edit(edits -> edits.remove(temporary))));
		boolean unchanged = context.computeOnClient(mc -> WaypointStore.get().all().size() == count && WaypointStore.saves() == saves
			&& WaypointStore.get().all().stream().noneMatch(w -> w.name.equals("Never added") || w.name.equals("Mine now")));
		if (!kind.equals("TEMPORARY") || !update.startsWith("IllegalArgumentException") || !remove.startsWith("IllegalArgumentException") || !unchanged) {
			wrong.add("A temporary waypoint: read as " + kind + "; updating it threw " + update + ", removing it threw " + remove + "; store unchanged " + unchanged);
		}
		Diorama.LOGGER.info("addon-api waypoints: changing a temporary waypoint threw {}", update);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.kind == Waypoint.Kind.TEMPORARY).toList().forEach(store::remove);
		});
	}

	/** all() is one list until a change, and a change that changes nothing keeps it. */
	private static void sameList(ClientGameTestContext context, List<String> wrong) {
		String result = context.computeOnClient(mc -> {
			var waypoints = TestAddon.world().waypoints();
			List<dev.diorama.api.Waypoint> first = waypoints.all();
			List<dev.diorama.api.Waypoint> second = waypoints.all();
			WaypointStore.get().changed();
			List<dev.diorama.api.Waypoint> afterNothing = waypoints.all();
			waypoints.edit(edits -> edits.update(fromAddon, WaypointUpdate.builder().initials("TB").build()));
			List<dev.diorama.api.Waypoint> afterChange = waypoints.all();
			boolean unmodifiable;
			try {
				afterChange.clear();
				unmodifiable = false;
			} catch (UnsupportedOperationException expected) {
				unmodifiable = true;
			}
			return (first == second) + " " + (second == afterNothing) + " " + (afterNothing != afterChange) + " " + unmodifiable;
		});
		if (!result.equals("true true true true")) {
			wrong.add("all(): same list twice, after a change of nothing, a new one after a change, unmodifiable: " + result);
		}
	}

	/** all() from another thread throws. */
	private static void threads(ClientGameTestContext context, List<String> wrong) {
		MapWorld world = context.computeOnClient(mc -> TestAddon.world());
		String off = thrown(() -> world.waypoints().all());
		if (!off.startsWith("IllegalStateException")) {
			wrong.add("all() from " + Thread.currentThread().getName() + " threw " + off);
		}
	}

	/** A listener that throws is switched off, with one log line; the change stands and the other addons still hear it. */
	private static void throwing(ClientGameTestContext context, List<String> wrong) {
		context.runOnClient(mc -> TestAddon.forgetHeard());
		boolean before = context.computeOnClient(mc -> ThrowingAddon.listening() != null && ThrowingAddon.listening().isOpen() && ThrowingAddon.world().isOpen());
		context.runOnClient(mc -> ThrowingAddon.arm());
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> edits.add(NewWaypoint.builder("Trigger", DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 1, at.getY(), at.getZ() + 1)).build())));
		boolean after = context.computeOnClient(mc -> ThrowingAddon.listening().isOpen() || ThrowingAddon.world().isOpen());
		List<String> heard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		boolean stored = context.computeOnClient(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.name.equals("Trigger")));
		// Then the store carries on: the next change goes through and is heard.
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.name.equals("Trigger")).toList().forEach(store::remove);
		});
		List<String> heardAfter = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		String log = log(context);
		long lines = log.lines().filter(line -> line.contains("Diorama addon diorama_test_addon_throwing failed in a WaypointListener")).count();
		boolean why = log.contains("IllegalStateException: Diorama test addon: a waypoint listener that throws on purpose");
		if (!before || after || !heard.equals(List.of("diorama_test_addon ADDED  > Trigger")) || !stored || lines != 1 || !why
			|| !heardAfter.equals(List.of("diorama_test_addon ADDED  > Trigger", "- REMOVED Trigger > "))) {
			wrong.add("The listener that throws: open before " + before + ", open after " + after + ", heard " + heard + " then " + heardAfter
				+ ", stored " + stored + ", " + lines + " log lines, cause logged " + why);
		}
	}

	/** One that answers its own changes for ever is stopped at the 51st round, switched off, and the store keeps the 49th rename. */
	private static void looping(ClientGameTestContext context, List<String> wrong) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int savesBefore = context.computeOnClient(mc -> WaypointStore.saves());
		UUID id = context.computeOnClient(mc -> LoopingAddon.start(at.getX() - 2, at.getY(), at.getZ() + 2));
		int renames = context.computeOnClient(mc -> LoopingAddon.renames());
		int saves = context.computeOnClient(mc -> WaypointStore.saves()) - savesBefore;
		String name = context.computeOnClient(mc -> TestAddon.world().waypoints().get(id).map(dev.diorama.api.Waypoint::name).orElse("gone"));
		String log = log(context);
		long lines = log.lines().filter(line -> line.contains("Diorama addon diorama_test_addon_looping failed in a WaypointListener")).count();
		boolean why = log.contains("Waypoint listeners answered changes with changes 50 rounds in a row");
		// The game goes on: a tick later the client still runs and the store takes changes.
		context.waitTicks(2);
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> edits.remove(id)));
		boolean removed = context.computeOnClient(mc -> TestAddon.world().waypoints().get(id).isEmpty());
		if (renames != 49 || !name.equals("Loop 49") || saves != 50 || lines != 1 || !why || !removed) {
			wrong.add("The listener that answers its own changes: " + renames + " renames, named " + name + ", " + saves + " saves, " + lines + " log lines, cause logged "
				+ why + ", removed after " + removed);
		}
		Diorama.LOGGER.info("addon-api waypoints: the looping listener was stopped after {} renames and {} saves", renames, saves);
	}

	/**
	 * A switch of saved map is told as mapChanged, with the other map's waypoints in all() and no
	 * waypoint events; a waypoint added on the second map stays with it.
	 */
	private static void savedMaps(ClientGameTestContext context, List<String> wrong) {
		String key = context.computeOnClient(mc -> WorldKey.current());
		int eventsBefore = context.computeOnClient(mc -> TestAddon.worldEvents().size());
		context.runOnClient(mc -> TestAddon.forgetHeard());
		String second = context.computeOnClient(mc -> Maps.of(key).create("minecraft:overworld", "Second"));
		context.waitFor(mc -> MapSession.get() != null, 2400);
		context.waitTicks(2);
		String mapIdThere = context.computeOnClient(mc -> TestAddon.world().mapId());
		boolean fromAddonGone = context.computeOnClient(mc -> TestAddon.world().waypoints().get(fromAddon).isEmpty());
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> edits.add(NewWaypoint.builder("On the second map", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ())).build())));
		String madeOn = context.computeOnClient(mc -> TestAddon.world().waypoints().all().stream().filter(w -> w.name().equals("On the second map")).map(dev.diorama.api.Waypoint::mapId).findFirst().orElse("none"));
		context.runOnClient(mc -> Maps.of(key).use("minecraft:overworld", Maps.MAIN));
		context.waitFor(mc -> MapSession.get() != null, 2400);
		context.waitTicks(2);
		boolean secondGone = context.computeOnClient(mc -> TestAddon.world().waypoints().all().stream().noneMatch(w -> w.name().equals("On the second map")));
		boolean fromAddonBack = context.computeOnClient(mc -> TestAddon.world().waypoints().get(fromAddon).isPresent());
		List<String> events = context.computeOnClient(mc -> List.copyOf(TestAddon.worldEvents().subList(eventsBefore, TestAddon.worldEvents().size())));
		List<String> heard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		List<String> expected = List.of("mapChanged " + worldId + " minecraft:overworld " + second, "mapChanged " + worldId + " minecraft:overworld main");
		if (second == null || !events.equals(expected) || !mapIdThere.equals(second) || !fromAddonGone || !madeOn.equals(second) || !secondGone || !fromAddonBack
			|| !heard.equals(List.of("diorama_test_addon ADDED  > On the second map"))) {
			wrong.add("Switching saved maps: events " + events + ", expected " + expected + "; map ID there " + mapIdThere + ", the addon's waypoint gone there " + fromAddonGone
				+ ", one made there on " + madeOn + ", gone after switching back " + secondGone + ", the addon's back " + fromAddonBack + ", heard " + heard);
		}
		// The second map goes, and the waypoint on it.
		context.runOnClient(mc -> Maps.of(key).delete("minecraft:overworld", second));
		// The main map read its chunks again on the switch back; the checks after this one want it full.
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		DioramaClientTest.settle(context, 40);
		Diorama.LOGGER.info("addon-api waypoints: switching saved maps told {}", events);
	}

	/** At the title screen after leaving: the world closed, and calls on it throw. */
	static void left(ClientGameTestContext context) {
		List<String> events = context.computeOnClient(mc -> List.copyOf(TestAddon.worldEvents()));
		String last = events.get(events.size() - 1);
		boolean open = context.computeOnClient(mc -> TestAddon.world().isOpen());
		String id = context.computeOnClient(mc -> TestAddon.world().worldId());
		String call = context.computeOnClient(mc -> thrown(() -> TestAddon.world().waypoints()));
		if (!last.equals("closed " + worldId + " false") || open || !id.equals(worldId) || !call.startsWith("IllegalStateException")) {
			throw new AssertionError("After leaving: last world event " + last + ", world open " + open + ", its ID " + id + ", waypoints() threw " + call);
		}
		Diorama.LOGGER.info("addon-api waypoints: leaving closed the world ({}), and waypoints() on it threw {}", last, call);
	}

	/** In the world opened again: opened with the same IDs, and the addon's waypoint is still there, still its. */
	static void reopened(ClientGameTestContext context) {
		context.setScreen(() -> null);
		context.waitFor(mc -> MapSession.get() != null && TestAddon.world().isOpen(), 2400);
		List<String> events = context.computeOnClient(mc -> List.copyOf(TestAddon.worldEvents()));
		String last = events.get(events.size() - 1);
		Optional<String> addedBy = context.computeOnClient(mc -> TestAddon.world().waypoints().get(fromAddon).flatMap(dev.diorama.api.Waypoint::addedBy));
		String name = context.computeOnClient(mc -> TestAddon.world().waypoints().get(fromAddon).map(dev.diorama.api.Waypoint::name).orElse("gone"));
		if (!last.equals("opened " + worldId + " minecraft:overworld main") || !addedBy.equals(Optional.of("diorama_test_addon")) || !name.equals(FROM_ADDON)) {
			throw new AssertionError("In the world opened again: last world event " + last + ", the addon's waypoint " + name + " added by " + addedBy);
		}
		Diorama.LOGGER.info("addon-api waypoints: the world opened again as {}, with the addon's waypoint", last);
	}

	//? if >=1.21.6 && fabric {
	/**
	 * Bytes the minimap's and the world waypoints' drawing allocate a frame with three waypoints in
	 * view, the world paused: with the test addon's listener open and idle, then with no waypoint
	 * listener. The draw paths don't know about addons, so the two should match, and match master.
	 */
	static void frames(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		context.setScreen(() -> null);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		List<Waypoint> pins = context.computeOnClient(mc -> {
			List<Waypoint> made = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				int x = at.getX() - 6 + i * 6;
				int z = at.getZ() - 18;
				Waypoint pin = new Waypoint("Frame " + i, x, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z, "minecraft:overworld", Waypoint.PALETTE[i]);
				WaypointStore.get().add(pin);
				made.add(pin);
			}
			return made;
		});
		BlockPos middle = new BlockPos(pins.get(1).x, pins.get(1).y, pins.get(1).z);
		//? if >=26.2 {
		context.getInput().lookAt(middle);
		//?} else {
		/*context.runOnClient(mc -> mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, net.minecraft.world.phys.Vec3.atCenterOf(middle)));
		*///?}
		context.runOnClient(mc -> {
			AllocProbe.MINIMAP.attach();
			AllocProbe.WAYPOINTS.attach();
		});
		DioramaClientTest.settle(context, 20);
		context.takeScreenshot("addon-api-14-frames");
		boolean listening = context.computeOnClient(mc -> TestAddon.listening().isOpen());
		try (WorldPause paused = WorldPause.pause(context)) {
			AllocProbe.MINIMAP.count(context, "warm-up", 150);
			AllocProbe.WAYPOINTS.count(context, "warm-up", 150);
			long minimapListening = AllocProbe.MINIMAP.count(context, "addon listening, idle", 200);
			long waypointsListening = AllocProbe.WAYPOINTS.count(context, "addon listening, idle", 200);
			context.runOnClient(mc -> TestAddon.listening().close());
			long minimapNone = AllocProbe.MINIMAP.count(context, "no waypoint listener", 200);
			long waypointsNone = AllocProbe.WAYPOINTS.count(context, "no waypoint listener", 200);
			Diorama.LOGGER.info("addon-api alloc: medians a frame, listener open {}: minimap {} listening, {} not; world waypoints {} listening, {} not",
				listening, minimapListening, minimapNone, waypointsListening, waypointsNone);
		}
		context.runOnClient(mc -> pins.forEach(WaypointStore.get()::remove));
	}
	//?}

	/**
	 * Bytes and time of reading and editing with 1,000 waypoints, logged: all() and get() when
	 * nothing changed, and one batch of 10 changes with no listener and with two.
	 */
	static void measure(ClientGameTestContext context) {
		context.runOnClient(mc -> TestAddon.listening().close());
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		List<UUID> ids = context.computeOnClient(mc -> {
			List<UUID> made = new ArrayList<>();
			TestAddon.world().waypoints().edit(edits -> {
				for (int i = 0; i < 1000; i++) {
					made.add(edits.add(NewWaypoint.builder("Many " + i, DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 2000 + i, 70, at.getZ() + 2000)).build()));
				}
			});
			return made;
		});
		String reads = context.computeOnClient(mc -> {
			var waypoints = TestAddon.world().waypoints();
			com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			UUID probe = ids.get(500);
			long sink = 0;
			for (int i = 0; i < 20_000; i++) {
				sink += waypoints.all().size() + (waypoints.get(probe).isPresent() ? 1 : 0);
			}
			int calls = 100_000;
			long start = threads.getCurrentThreadAllocatedBytes();
			for (int i = 0; i < calls; i++) {
				sink += waypoints.all().size();
			}
			long all = threads.getCurrentThreadAllocatedBytes() - start;
			start = threads.getCurrentThreadAllocatedBytes();
			for (int i = 0; i < calls; i++) {
				sink += waypoints.get(probe).isPresent() ? 1 : 0;
			}
			long get = threads.getCurrentThreadAllocatedBytes() - start;
			// The counter itself costs a few bytes a read; measured with nothing between.
			start = threads.getCurrentThreadAllocatedBytes();
			long empty = threads.getCurrentThreadAllocatedBytes() - start;
			return String.format(Locale.ROOT, "%d waypoints: all() %.3f bytes a call, get() %.3f bytes a call over %d calls each (counter alone %d bytes; sink %d)",
				waypoints.all().size(), (all - empty) / (double) calls, (get - empty) / (double) calls, calls, empty, sink);
		});
		Diorama.LOGGER.info("addon-api measure: {}", reads);
		// What Diorama's own change already costs: one save of the file and nothing compared.
		String save = context.computeOnClient(mc -> {
			long[] nanos = new long[50];
			for (int round = 0; round < 50; round++) {
				long start = System.nanoTime();
				WaypointStore.get().changed();
				nanos[round] = System.nanoTime() - start;
			}
			long[] n = java.util.Arrays.copyOfRange(nanos, 10, 50);
			java.util.Arrays.sort(n);
			return String.format(Locale.ROOT, "median %.2f ms (%.2f to %.2f)", n[n.length / 2] / 1e6, n[0] / 1e6, n[n.length - 1] / 1e6);
		});
		Diorama.LOGGER.info("addon-api measure: Diorama's own changed() with 1,000 waypoints (one save), no listener: {}", save);
		String none = editCost(context, ids, 0);
		String two = editCost(context, ids, 2);
		Diorama.LOGGER.info("addon-api measure: one batch of 10 changes with 1,000 waypoints, no listener: {}", none);
		Diorama.LOGGER.info("addon-api measure: one batch of 10 changes with 1,000 waypoints, two listeners: {}", two);
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> ids.forEach(edits::remove)));
	}

	/** Median time and bytes of one edit batch of 10 renames, over 40 batches after 10 to warm up. */
	private static String editCost(ClientGameTestContext context, List<UUID> ids, int listeners) {
		return context.computeOnClient(mc -> {
			var waypoints = TestAddon.world().waypoints();
			int[] heard = new int[1];
			List<dev.diorama.api.Handle> handles = new ArrayList<>();
			for (int i = 0; i < listeners; i++) {
				handles.add(waypoints.listen(changes -> heard[0] += changes.changes().size()));
			}
			com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			long[] nanos = new long[50];
			long[] bytes = new long[50];
			for (int round = 0; round < 50; round++) {
				int r = round;
				long startBytes = threads.getCurrentThreadAllocatedBytes();
				long start = System.nanoTime();
				waypoints.edit(edits -> {
					for (int i = 0; i < 10; i++) {
						// Named for this run too, so every batch changes all ten.
						edits.update(ids.get(r * 10 + i), WaypointUpdate.builder().name("Renamed " + listeners + " " + r + " " + i).build());
					}
				});
				nanos[round] = System.nanoTime() - start;
				bytes[round] = threads.getCurrentThreadAllocatedBytes() - startBytes;
			}
			handles.forEach(dev.diorama.api.Handle::close);
			long[] n = java.util.Arrays.copyOfRange(nanos, 10, 50);
			long[] b = java.util.Arrays.copyOfRange(bytes, 10, 50);
			java.util.Arrays.sort(n);
			java.util.Arrays.sort(b);
			return String.format(Locale.ROOT, "median %.2f ms (%.2f to %.2f), %d bytes allocated (%d to %d); listeners heard %d changes",
				n[n.length / 2] / 1e6, n[0] / 1e6, n[n.length - 1] / 1e6, b[b.length / 2], b[0], b[b.length - 1], heard[0]);
		});
	}

	private static String thrown(Runnable call) {
		try {
			call.run();
			return "nothing";
		} catch (RuntimeException e) {
			return e.getClass().getSimpleName() + ": " + e.getMessage();
		}
	}

	private static String log(ClientGameTestContext context) {
		return context.computeOnClient(mc -> text(mc.gameDirectory.toPath().resolve("logs/latest.log")));
	}

	private static String text(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
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
}
