package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Maps;
import dev.diorama.WorldKey;
import dev.diorama.addon.AddonWorlds;
import dev.diorama.addon.Addons;
import dev.diorama.api.BlockCoord;
import dev.diorama.api.DimensionId;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.Handle;
import dev.diorama.api.MapWorld;
import dev.diorama.api.NewWaypoint;
import dev.diorama.api.WaypointChange;
import dev.diorama.api.WaypointChanges;
import dev.diorama.api.WaypointUpdate;
import dev.diorama.api.WorldId;
import dev.diorama.api.WorldListener;
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
import dev.diorama.waypoint.Waystones;
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
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The addon API's waypoints and worlds ({@code addon-api}), through the test addons, which only
 * know the API, and probes: addons the test makes itself ({@link Probe}). Everything addons are
 * told comes from Diorama's client tick, so each check lets a tick go by ({@link #told}) before it
 * reads what was heard.
 *
 * <p>In the first world ({@link #firstWorld}): the addon was told the world opened, and of each
 * change of dimension and of saved map, with no waypoint events for the switch; it reads what the
 * waypoint list shows; a waypoint it adds is in the list, on the map, in the world and in
 * waypoints.json after one save; what the player adds, renames, hides and deletes in Diorama's
 * own screens, clicked and typed, reaches it as events with no mod ID, each with the one save it
 * always had; a folder renamed is a change to each waypoint in it; its own edits reach it with its
 * mod ID, one save and one event a batch, echoes after, with what it read at the time; three new
 * folders in one batch write folders.json once; it can't change an automatic pin; a batch that
 * throws (an exception, or ThreadDeath) changes nothing and switches its addon off; a listener
 * that throws is switched off and the store carries on; a callback making 55 edits is fine, and
 * one that answers its own changes for ever is stopped by depth; a pending Waystones change
 * reaches the listeners there before another subscribes or the map switches, and a new listener
 * hears nothing from before, even subscribing mid-delivery; an addon's edit in its mapChanged
 * comes after every addon's mapChanged; Diorama's list and editor follow an addon's delete; two
 * waypoints with one ID behave as documented; a waystone placed again shows in all(); all()
 * hands out one list until something changes; all(), get() and edit() from another thread throw;
 * a world handle whose store was replaced, or whose world isn't known for a moment, throws and
 * writes nothing. Leaving closes the world ({@link #left}), and the world opened again
 * ({@link #reopened}) has the same IDs and still has the addon's waypoint. {@link #measure}
 * counts what reading and editing cost.
 */
final class AddonWaypointsTest {
	/** Pure cyan, which no block in the test world is, to find the addon's pin in the world by. */
	private static final int PIN = 0x00FFFF;
	private static final String FROM_ADDON = "From addon";
	private static WorldId worldId;
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
		told(context);
		List<String> events = context.computeOnClient(mc -> List.copyOf(TestAddon.worldEvents()));
		worldId = context.computeOnClient(mc -> TestAddon.world().worldId());
		List<String> expected = List.of("opened " + worldId + " minecraft:overworld main", "mapChanged " + worldId + " minecraft:the_nether main",
			"mapChanged " + worldId + " minecraft:overworld main");
		if (!events.equals(expected) || !worldId.toString().matches("[0-9a-f]{32}")) {
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
		folders(context, wrong);
		echo(context, wrong);
		automatic(context, wrong);
		sameList(context, wrong);
		threads(context, wrong);
		throwing(context, wrong);
		throwingBatches(context, wrong);
		fanOut(context, wrong);
		looping(context, wrong);
		pending(context, wrong);
		duplicates(context, wrong);
		waystonePlacedAgain(context, wrong);
		screens(context, wrong);
		savedMaps(context, wrong);
		staleWorlds(context, wrong);
		if (!wrong.isEmpty()) {
			throw new AssertionError("Addon waypoints in the first world:\n  " + String.join("\n  ", wrong));
		}
		Diorama.LOGGER.info("addon-api waypoints: first world checks passed");
	}

	/** Lets Diorama's client tick go by, which is where everything queued for addons is told, and waits until nothing is left. */
	private static void told(ClientGameTestContext context) {
		context.waitTicks(1);
		context.waitFor(mc -> AddonWorlds.queuedForTests() == 0, 100);
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
					out.add(w.id + " " + w.name + " " + w.dimension + " " + w.x + "," + w.y + "," + w.z + " " + Integer.toHexString(w.color & 0xFFFFFF));
				}
			}
			return String.join("; ", out);
		});
		String read = context.computeOnClient(mc -> {
			List<String> out = new ArrayList<>();
			for (dev.diorama.api.Waypoint w : TestAddon.world().waypoints().all()) {
				out.add(w.id() + " " + w.name() + " " + w.dimension() + " " + w.pos().x() + "," + w.pos().y() + "," + w.pos().z() + " " + Integer.toHexString(w.rgb()));
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
		told(context);
	}

	/**
	 * A waypoint the addon adds: one save, in the file with its mod ID, in the list, on the map and
	 * in the world. A batch of three is one save and one event; three new folders in one batch
	 * write folders.json once.
	 */
	private static void adds(ClientGameTestContext context, List<String> wrong) {
		Path file = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int hx = at.getX() - 4;
		int hz = at.getZ() - 16;
		int hy = context.computeOnClient(mc -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, hx, hz));
		context.runOnClient(mc -> TestAddon.forgetHeard());
		int savesBefore = context.computeOnClient(mc -> WaypointStore.saves());
		// Nothing is told from inside edit(): only at the tick. Read in the same call, as on
		// NeoForge and Forge the game ticks on between the test's calls.
		List<String> heardAtOnce = new ArrayList<>();
		fromAddon = context.computeOnClient(mc -> {
			UUID[] id = new UUID[1];
			TestAddon.world().waypoints().edit(edits -> id[0] = edits.add(NewWaypoint.builder(FROM_ADDON, DimensionId.OVERWORLD, BlockCoord.of(hx, hy, hz))
				.rgb(PIN).initials("TA").build()));
			heardAtOnce.addAll(TestAddon.heard());
			return id[0];
		});
		int saves = context.computeOnClient(mc -> WaypointStore.saves()) - savesBefore;
		told(context);
		String json = text(file);
		boolean inFile = json.contains(fromAddon.toString()) && json.contains("\"addedBy\": \"diorama_test_addon\"");
		List<String> heard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		Optional<String> addedBy = context.computeOnClient(mc -> TestAddon.world().waypoints().get(fromAddon).flatMap(dev.diorama.api.Waypoint::addedBy));
		int rgb = context.computeOnClient(mc -> TestAddon.world().waypoints().get(fromAddon).map(dev.diorama.api.Waypoint::rgb).orElse(-1));
		if (saves != 1 || !inFile || !heardAtOnce.isEmpty() || !heard.equals(List.of("diorama_test_addon ADDED  > " + FROM_ADDON)) || !addedBy.equals(Optional.of("diorama_test_addon"))
			|| rgb != PIN) {
			wrong.add("Adding one waypoint: " + saves + " saves, in waypoints.json with its mod ID " + inFile + ", heard inside edit() " + heardAtOnce + ", heard at the tick " + heard
				+ ", addedBy " + addedBy + ", rgb " + Integer.toHexString(rgb));
		}

		// A batch of three: two added, the first renamed. One save, one event, the rename folded in.
		context.runOnClient(mc -> TestAddon.forgetHeard());
		int batchBefore = context.computeOnClient(mc -> WaypointStore.saves());
		int[] events = new int[1];
		Handle counting = context.computeOnClient(mc -> {
			Handle handle = TestAddon.world().waypoints().listen(changes -> events[0]++);
			TestAddon.world().waypoints().edit(edits -> {
				UUID first = edits.add(NewWaypoint.builder("Batch one", DimensionId.OVERWORLD, BlockCoord.of(hx + 2, hy, hz)).build());
				edits.add(NewWaypoint.builder("Batch two", DimensionId.OVERWORLD, BlockCoord.of(hx + 4, hy, hz)).folder("Addon folder").build());
				edits.update(first, WaypointUpdate.builder().name("Batch one renamed").build());
			});
			return handle;
		});
		told(context);
		context.runOnClient(mc -> counting.close());
		int batchSaves = context.computeOnClient(mc -> WaypointStore.saves()) - batchBefore;
		List<String> batchHeard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		boolean folderMade = context.computeOnClient(mc -> WaypointStore.get().customFolders().contains("Addon folder"));
		if (batchSaves != 1 || events[0] != 1 || !batchHeard.equals(List.of("diorama_test_addon ADDED  > Batch one renamed", "diorama_test_addon ADDED  > Batch two")) || !folderMade) {
			wrong.add("A batch of three changes: " + batchSaves + " saves, " + events[0] + " events, heard " + batchHeard + ", folder made " + folderMade);
		}

		// Three new folders in one batch: folders.json once, waypoints.json once.
		int folderSavesBefore = context.computeOnClient(mc -> WaypointStore.folderSaves());
		int threeBefore = context.computeOnClient(mc -> WaypointStore.saves());
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> {
			for (String folder : List.of("Batch folder A", "Batch folder B", "Batch folder C")) {
				edits.add(NewWaypoint.builder("In " + folder, DimensionId.OVERWORLD, BlockCoord.of(hx + 6, hy, hz)).folder(folder).build());
			}
		}));
		int folderSaves = context.computeOnClient(mc -> WaypointStore.folderSaves()) - folderSavesBefore;
		int threeSaves = context.computeOnClient(mc -> WaypointStore.saves()) - threeBefore;
		boolean allThree = context.computeOnClient(mc -> WaypointStore.get().customFolders().containsAll(List.of("Batch folder A", "Batch folder B", "Batch folder C")));
		if (folderSaves != 1 || threeSaves != 1 || !allThree) {
			wrong.add("Three new folders in one batch: folders.json written " + folderSaves + " times, waypoints.json " + threeSaves + ", all three made " + allThree);
		}
		Diorama.LOGGER.info("addon-api waypoints: three new folders in one batch wrote folders.json {} time(s), waypoints.json {}", folderSaves, threeSaves);
		context.runOnClient(mc -> {
			TestAddon.world().waypoints().edit(edits -> {
				for (dev.diorama.api.Waypoint w : TestAddon.world().waypoints().all()) {
					if (w.name().startsWith("Batch ") || w.name().startsWith("In Batch folder")) {
						edits.remove(w.id());
					}
				}
			});
			for (String folder : List.of("Batch folder A", "Batch folder B", "Batch folder C")) {
				WaypointStore.get().deleteFolder(folder);
			}
		});
		told(context);

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
		told(context);

		List<String> heard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		List<String> expected = List.of("- ADDED  > Player made", "- CHANGED Player made > Player renamed", "- CHANGED Player renamed > Player renamed (hidden)",
			"- REMOVED Player renamed > ");
		if (!heard.equals(expected) || !saves.equals(List.of(1, 2, 3, 4))) {
			wrong.add("The player's add, rename, hide and delete: heard " + heard + ", expected " + expected + "; saves after each " + saves);
		}
		Diorama.LOGGER.info("addon-api waypoints: the player's own edits were heard as {}, saves after each {}", heard, saves);
	}

	/** Renaming a folder is a change to each waypoint in it, which is how a sync hears it; hiding a whole folder isn't one. */
	private static void folders(ClientGameTestContext context, List<String> wrong) {
		Probe probe = Probe.make(context, "folders", true);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		context.runOnClient(mc -> probe.world.waypoints().edit(edits -> {
			edits.add(NewWaypoint.builder("Foldered one", DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 3, at.getY(), at.getZ())).folder("Sync folder").build());
			edits.add(NewWaypoint.builder("Foldered two", DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 4, at.getY(), at.getZ())).folder("Sync folder").build());
		}));
		told(context);
		context.runOnClient(mc -> {
			probe.log.clear();
			WaypointStore.get().renameFolder("Sync folder", "Renamed folder");
		});
		told(context);
		List<String> renamed = context.computeOnClient(mc -> List.copyOf(probe.log));
		context.runOnClient(mc -> {
			probe.log.clear();
			WaypointStore.get().setFolderHidden(WaypointStore.customKey("Renamed folder"), true);
			WaypointStore.get().setFolderHidden(WaypointStore.customKey("Renamed folder"), false);
		});
		told(context);
		List<String> hid = context.computeOnClient(mc -> List.copyOf(probe.log));
		List<String> expected = List.of("- CHANGED Foldered one [Sync folder] > Foldered one [Renamed folder]", "- CHANGED Foldered two [Sync folder] > Foldered two [Renamed folder]");
		if (!renamed.equals(expected) || !hid.isEmpty()) {
			wrong.add("Renaming a folder: heard " + renamed + ", expected " + expected + "; hiding and showing it heard " + hid);
		}
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.name.startsWith("Foldered ")).toList().forEach(store::remove);
			store.deleteFolder("Renamed folder");
		});
		told(context);
		probe.forget(context);
	}

	/**
	 * The test addon renames a waypoint called "Echo me" from inside its listener: the add is heard
	 * first, then the rename. Inside the listener it reads the store as it is then: "Echo me" when
	 * told of the add (the rename comes after it reads), "Echoed" when told of the rename.
	 */
	private static void echo(ClientGameTestContext context, List<String> wrong) {
		context.runOnClient(mc -> TestAddon.forgetHeard());
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int before = context.computeOnClient(mc -> WaypointStore.saves());
		int size = context.computeOnClient(mc -> TestAddon.world().waypoints().all().size());
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> edits.add(NewWaypoint.builder(TestAddon.ECHO, DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ() + 3)).build())));
		told(context);
		int saves = context.computeOnClient(mc -> WaypointStore.saves()) - before;
		List<String> heard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		List<String> reads = context.computeOnClient(mc -> List.copyOf(TestAddon.readHeard()));
		List<Integer> sizes = context.computeOnClient(mc -> List.copyOf(TestAddon.sizesHeard()));
		String stored = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.name.startsWith("Echo")).map(w -> w.name).toList().toString());
		List<String> expected = List.of("diorama_test_addon ADDED  > " + TestAddon.ECHO, "diorama_test_addon CHANGED " + TestAddon.ECHO + " > " + TestAddon.ECHOED);
		if (!heard.equals(expected) || saves != 2 || !stored.equals("[" + TestAddon.ECHOED + "]") || !reads.equals(List.of(TestAddon.ECHO, TestAddon.ECHOED))
			|| !sizes.equals(List.of(size + 1, size + 1))) {
			wrong.add("A rename from inside the listener: heard " + heard + ", expected " + expected + ", " + saves + " saves, stored " + stored + "; read inside the listener " + reads
				+ " with all() at " + sizes + ", expected [" + TestAddon.ECHO + ", " + TestAddon.ECHOED + "] at " + (size + 1));
		}
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.name.equals(TestAddon.ECHOED)).toList().forEach(store::remove);
		});
		told(context);
	}

	/** Automatic pins are read but not written: the editor throws, which the addon catches here to carry on. */
	private static void automatic(ClientGameTestContext context, List<String> wrong) {
		context.runOnClient(mc -> Waypoints.addTemporary(mc.player.blockPosition().east(3), "minecraft:overworld"));
		UUID temporary = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.TEMPORARY).findFirst().orElseThrow().id);
		String kind = context.computeOnClient(mc -> TestAddon.world().waypoints().get(temporary).map(w -> w.kind().name()).orElse("none"));
		int saves = context.computeOnClient(mc -> WaypointStore.saves());
		String update = context.computeOnClient(mc -> {
			String[] out = {"nothing"};
			TestAddon.world().waypoints().edit(edits -> out[0] = thrown(() -> edits.update(temporary, WaypointUpdate.builder().name("Mine now").build())));
			return out[0];
		});
		String remove = context.computeOnClient(mc -> {
			String[] out = {"nothing"};
			TestAddon.world().waypoints().edit(edits -> out[0] = thrown(() -> edits.remove(temporary)));
			return out[0];
		});
		boolean unchanged = context.computeOnClient(mc -> WaypointStore.saves() == saves && WaypointStore.get().all().stream().noneMatch(w -> w.name.equals("Mine now"))
			&& WaypointStore.get().all().stream().anyMatch(w -> w.id.equals(temporary)));
		boolean running = context.computeOnClient(mc -> TestAddon.listening().isOpen());
		if (!kind.equals("TEMPORARY") || !update.startsWith("IllegalArgumentException") || !remove.startsWith("IllegalArgumentException") || !unchanged || !running) {
			wrong.add("A temporary waypoint: read as " + kind + "; updating it threw " + update + ", removing it threw " + remove + "; store unchanged " + unchanged
				+ ", the addon still running " + running);
		}
		Diorama.LOGGER.info("addon-api waypoints: changing a temporary waypoint threw {}", update);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.kind == Waypoint.Kind.TEMPORARY).toList().forEach(store::remove);
		});
		told(context);
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
		told(context);
	}

	/**
	 * all(), get() and edit() from another thread throw, on a Waypoints taken on the client
	 * thread. From a thread of the test's own: Fabric's game-test thread can't reach the game at
	 * all, so it would fail on that before Diorama's check.
	 */
	private static void threads(ClientGameTestContext context, List<String> wrong) {
		dev.diorama.api.Waypoints waypoints = context.computeOnClient(mc -> TestAddon.world().waypoints());
		String[] calls = new String[3];
		Thread other = new Thread(() -> {
			calls[0] = thrown(waypoints::all);
			calls[1] = thrown(() -> waypoints.get(fromAddon));
			calls[2] = thrown(() -> waypoints.edit(edits -> edits.remove(fromAddon)));
		}, "Diorama test, not the client thread");
		other.start();
		try {
			other.join(10_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		String all = calls[0];
		String get = calls[1];
		String edit = calls[2];
		boolean kept = context.computeOnClient(mc -> TestAddon.world().waypoints().get(fromAddon).isPresent());
		String thread = other.getName();
		for (String call : List.of(all, get, edit)) {
			if (call == null || !call.startsWith("IllegalStateException: Diorama's API is only for the client thread")) {
				wrong.add("all(), get() and edit() from " + thread + " threw " + all + " / " + get + " / " + edit);
				break;
			}
		}
		if (!kept) {
			wrong.add("edit() from " + thread + " removed the addon's waypoint");
		}
	}

	/** A listener that throws is switched off, with one log line; the change stands and the other addons still hear it. */
	private static void throwing(ClientGameTestContext context, List<String> wrong) {
		context.runOnClient(mc -> TestAddon.forgetHeard());
		boolean before = context.computeOnClient(mc -> ThrowingAddon.listening() != null && ThrowingAddon.listening().isOpen() && ThrowingAddon.world().isOpen());
		context.runOnClient(mc -> ThrowingAddon.arm());
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> edits.add(NewWaypoint.builder("Trigger", DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 1, at.getY(), at.getZ() + 1)).build())));
		told(context);
		boolean after = context.computeOnClient(mc -> ThrowingAddon.listening().isOpen() || ThrowingAddon.world().isOpen());
		List<String> heard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		boolean stored = context.computeOnClient(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.name.equals("Trigger")));
		// Then the store carries on: the next change goes through and is heard.
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.name.equals("Trigger")).toList().forEach(store::remove);
		});
		told(context);
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

	/**
	 * An edit batch that throws, an ordinary exception and then ThreadDeath: nothing of it is
	 * applied or saved, the folder it named isn't made, the exception reaches the caller, and the
	 * addon is switched off, its handles closed; the exception with one log line, ThreadDeath with
	 * none, as the JVM's own errors are never logged.
	 */
	@SuppressWarnings("removal")
	private static void throwingBatches(ClientGameTestContext context, List<String> wrong) {
		for (boolean death : new boolean[] {false, true}) {
			Probe probe = Probe.make(context, death ? "batch_death" : "batch_exception", true);
			int saves = context.computeOnClient(mc -> WaypointStore.saves());
			int folderSaves = context.computeOnClient(mc -> WaypointStore.folderSaves());
			List<String> folders = context.computeOnClient(mc -> WaypointStore.get().customFolders());
			int count = context.computeOnClient(mc -> WaypointStore.get().all().size());
			String threw = context.computeOnClient(mc -> {
				try {
					probe.world.waypoints().edit(edits -> {
						edits.add(NewWaypoint.builder("Never kept", DimensionId.OVERWORLD, BlockCoord.of(0, 64, 0)).folder("Rollback folder").build());
						edits.update(fromAddon, WaypointUpdate.builder().name("Never renamed").folder("Rollback folder").build());
						if (death) {
							throw new ThreadDeath();
						}
						throw new IllegalStateException("A batch that throws on purpose");
					});
					return "nothing";
				} catch (Throwable thrown) {
					return thrown.getClass().getSimpleName() + ": " + thrown.getMessage();
				}
			});
			told(context);
			boolean unchanged = context.computeOnClient(mc -> WaypointStore.saves() == saves && WaypointStore.folderSaves() == folderSaves
				&& WaypointStore.get().customFolders().equals(folders) && WaypointStore.get().all().size() == count
				&& WaypointStore.get().all().stream().noneMatch(w -> w.name.equals("Never kept") || w.name.equals("Never renamed")));
			boolean off = context.computeOnClient(mc -> !probe.onWorld.isOpen() && !probe.listening.isOpen() && !probe.world.isOpen());
			long lines = log(context).lines().filter(line -> line.contains("Diorama addon " + probe.modId + " failed in a Waypoints.edit batch")).count();
			String expected = death ? "ThreadDeath: null" : "IllegalStateException: A batch that throws on purpose";
			if (!threw.equals(expected) || !unchanged || !off || lines != (death ? 0 : 1)) {
				wrong.add("A batch that throws " + (death ? "ThreadDeath" : "an exception") + ": the caller got " + threw + ", expected " + expected + "; nothing changed, folders included, "
					+ unchanged + "; the addon switched off " + off + "; " + lines + " log lines");
			}
			Diorama.LOGGER.info("addon-api waypoints: a batch that threw {} changed nothing, switched off {}", threw, off);
			probe.forget(context);
		}
	}

	/**
	 * An addon that answers one change with 55 edits, and each of those with a rename: all 111 go
	 * through, as they are only one and two answers deep, and the addon stays on.
	 */
	private static void fanOut(ClientGameTestContext context, List<String> wrong) {
		Probe probe = Probe.make(context, "fan", true);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		probe.onChange = changes -> {
			for (WaypointChange change : changes.changes()) {
				if (change.type() != WaypointChange.Type.ADDED) {
					continue;
				}
				String name = change.after().get().name();
				if (name.equals("Fan out")) {
					for (int i = 0; i < 55; i++) {
						int n = i;
						probe.world.waypoints().edit(edits -> edits.add(NewWaypoint.builder("Fan " + n, DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 40 + n, 70, at.getZ() + 40)).build()));
					}
				} else if (name.startsWith("Fan ")) {
					probe.world.waypoints().edit(edits -> edits.update(change.id(), WaypointUpdate.builder().name("Fanned " + name.substring(4)).build()));
				}
			}
		};
		int before = context.computeOnClient(mc -> WaypointStore.saves());
		context.runOnClient(mc -> probe.world.waypoints().edit(edits -> edits.add(NewWaypoint.builder("Fan out", DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 40, 70, at.getZ() + 41)).build())));
		told(context);
		int saves = context.computeOnClient(mc -> WaypointStore.saves()) - before;
		long fanned = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.name.startsWith("Fanned ")).count());
		boolean on = context.computeOnClient(mc -> probe.onWorld.isOpen() && probe.listening.isOpen());
		if (saves != 111 || fanned != 55 || !on) {
			wrong.add("55 edits from one callback, each answered with a rename: " + saves + " saves (expected 111), " + fanned + " renamed (expected 55), the addon still on " + on);
		}
		Diorama.LOGGER.info("addon-api waypoints: 55 edits from one callback and their 55 renames: {} saves, addon on {}", saves, on);
		probe.onChange = null;
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.name.startsWith("Fan")).toList().forEach(store::remove);
		});
		told(context);
		probe.forget(context);
	}

	/**
	 * One that answers its own changes for ever: the add and 50 renames, each one answer deeper,
	 * go through and save; the 51st answer is refused, which switches the addon off with one log
	 * line, and the store keeps "Loop 50".
	 */
	private static void looping(ClientGameTestContext context, List<String> wrong) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int savesBefore = context.computeOnClient(mc -> WaypointStore.saves());
		UUID id = context.computeOnClient(mc -> LoopingAddon.start(at.getX() - 2, at.getY(), at.getZ() + 2));
		told(context);
		int renames = context.computeOnClient(mc -> LoopingAddon.renames());
		int saves = context.computeOnClient(mc -> WaypointStore.saves()) - savesBefore;
		String name = context.computeOnClient(mc -> TestAddon.world().waypoints().get(id).map(dev.diorama.api.Waypoint::name).orElse("gone"));
		String log = log(context);
		long lines = log.lines().filter(line -> line.contains("Diorama addon diorama_test_addon_looping failed in")).count();
		boolean why = log.contains("past Waypoints.MAX_EDIT_DEPTH (50)");
		// The game goes on: a tick later the client still runs and the store takes changes.
		context.waitTicks(2);
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> edits.remove(id)));
		told(context);
		boolean removed = context.computeOnClient(mc -> TestAddon.world().waypoints().get(id).isEmpty());
		if (renames != 50 || !name.equals("Loop 50") || saves != 51 || lines != 1 || !why || !removed) {
			wrong.add("The listener that answers its own changes: " + renames + " renames (expected 50), named " + name + ", " + saves + " saves (expected 51), " + lines
				+ " log lines, cause logged " + why + ", removed after " + removed);
		}
		Diorama.LOGGER.info("addon-api waypoints: the looping listener was stopped after {} renames and {} saves", renames, saves);
	}

	/**
	 * A change Diorama found outside the store (a waystone) that no one was told yet reaches A
	 * when B subscribes, and B doesn't hear it. Then B subscribes from inside A's listener while
	 * another change waits: B hears neither, only what is made after it subscribed.
	 */
	private static void pending(ClientGameTestContext context, List<String> wrong) {
		Probe a = Probe.make(context, "a", true);
		Probe b = Probe.make(context, "b", false);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		context.runOnClient(mc -> {
			Waypoint stone = new Waypoint("Pending stone", at.getX() + 7, at.getY(), at.getZ() + 7, "minecraft:overworld", 0xFF8E4EC6);
			Waystones.knownForTests(stone);
			b.listening = b.world.waypoints().listen(b::heard);
		});
		told(context);
		List<String> aHeard = context.computeOnClient(mc -> List.copyOf(a.log));
		List<String> bHeard = context.computeOnClient(mc -> List.copyOf(b.log));
		if (!aHeard.equals(List.of("- ADDED  > Pending stone")) || !bHeard.isEmpty()) {
			wrong.add("A waystone not yet told when another listener subscribed: A heard " + aHeard + " (expected the waystone), B heard " + bHeard + " (expected nothing)");
		}

		context.runOnClient(mc -> {
			b.listening.close();
			a.log.clear();
			b.log.clear();
			a.onChange = changes -> {
				for (WaypointChange change : changes.changes()) {
					if (change.after().map(w -> w.name().equals("Sub one")).orElse(false)) {
						b.listening = b.world.waypoints().listen(b::heard);
						a.world.waypoints().edit(edits -> edits.add(NewWaypoint.builder("Sub three", DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 9, at.getY(), at.getZ())).build()));
					}
				}
			};
			a.world.waypoints().edit(edits -> edits.add(NewWaypoint.builder("Sub one", DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 8, at.getY(), at.getZ())).build()));
			a.world.waypoints().edit(edits -> edits.add(NewWaypoint.builder("Sub two", DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 8, at.getY(), at.getZ() + 1)).build()));
		});
		told(context);
		aHeard = context.computeOnClient(mc -> List.copyOf(a.log));
		bHeard = context.computeOnClient(mc -> List.copyOf(b.log));
		String by = a.modId;
		List<String> aExpected = List.of(by + " ADDED  > Sub one", by + " ADDED  > Sub two", by + " ADDED  > Sub three");
		List<String> bExpected = List.of(by + " ADDED  > Sub three");
		if (!aHeard.equals(aExpected) || !bHeard.equals(bExpected)) {
			wrong.add("B subscribing inside A's listener: A heard " + aHeard + ", expected " + aExpected + "; B heard " + bHeard + ", expected " + bExpected);
		}
		Diorama.LOGGER.info("addon-api waypoints: subscribing mid-delivery, A heard {}, B heard {}", aHeard, bHeard);
		a.onChange = null;
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.name.startsWith("Sub ")).toList().forEach(store::remove);
			Waystones.clear();
		});
		told(context);
		a.forget(context);
		b.forget(context);
	}

	/**
	 * Two waypoints with one ID, as a hand-edited file can have: all() lists both, get() and the
	 * editor go by the first; removing it leaves the second answering to the ID, and an update then
	 * changes the second, in the store too.
	 */
	private static void duplicates(ClientGameTestContext context, List<String> wrong) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		UUID id = context.computeOnClient(mc -> {
			Waypoint one = new Waypoint("Twin", at.getX() + 11, at.getY(), at.getZ(), "minecraft:overworld", Waypoint.PALETTE[2]);
			Waypoint two = new Waypoint("Twin", at.getX() + 11, at.getY(), at.getZ(), "minecraft:overworld", Waypoint.PALETTE[2]);
			two.id = one.id;
			two.created = one.created;
			WaypointStore.get().add(one);
			WaypointStore.get().add(two);
			return one.id;
		});
		told(context);
		String read = context.computeOnClient(mc -> {
			var waypoints = TestAddon.world().waypoints();
			long listed = waypoints.all().stream().filter(w -> w.id().equals(id)).count();
			boolean[] removed = new boolean[1];
			waypoints.edit(edits -> removed[0] = edits.remove(id));
			long stored = WaypointStore.get().all().stream().filter(w -> w.id.equals(id)).count();
			String after = waypoints.get(id).map(dev.diorama.api.Waypoint::name).orElse("gone");
			boolean[] updated = new boolean[1];
			waypoints.edit(edits -> updated[0] = edits.update(id, WaypointUpdate.builder().name("Twin renamed").build()));
			String storedName = WaypointStore.get().all().stream().filter(w -> w.id.equals(id)).map(w -> w.name).toList().toString();
			String readName = waypoints.get(id).map(dev.diorama.api.Waypoint::name).orElse("gone");
			return "listed " + listed + ", removed " + removed[0] + ", left in the store " + stored + ", get() then " + after + ", updated " + updated[0] + ", stored " + storedName
				+ ", get() then " + readName;
		});
		String expected = "listed 2, removed true, left in the store 1, get() then Twin, updated true, stored [Twin renamed], get() then Twin renamed";
		if (!read.equals(expected)) {
			wrong.add("Two waypoints with one ID: " + read + "; expected " + expected);
		}
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.id.equals(id)).toList().forEach(store::remove);
		});
		told(context);
	}

	/**
	 * A waystone picked up with Silk Touch leaves all(); placed again at its spot, which no event
	 * says, it comes back within a second or so, and listeners hear it.
	 */
	private static void waystonePlacedAgain(ClientGameTestContext context, List<String> wrong) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		UUID id = context.computeOnClient(mc -> {
			Waypoint stone = new Waypoint("Placed stone", at.getX() - 7, at.getY(), at.getZ() + 7, "minecraft:overworld", 0xFF8E4EC6);
			Waystones.knownForTests(stone);
			return stone.id;
		});
		told(context);
		boolean first = context.computeOnClient(mc -> TestAddon.world().waypoints().get(id).isPresent());
		context.runOnClient(mc -> Waystones.pickedUpForTests(id, false));
		told(context);
		boolean pickedUp = context.computeOnClient(mc -> TestAddon.world().waypoints().get(id).isEmpty());
		context.runOnClient(mc -> TestAddon.forgetHeard());
		context.runOnClient(mc -> Waystones.pickedUpForTests(id, true));
		boolean back = false;
		long start = System.nanoTime();
		for (int i = 0; i < 60 && !back; i++) {
			context.waitTicks(5);
			back = context.computeOnClient(mc -> TestAddon.world().waypoints().all().stream().anyMatch(w -> w.id().equals(id)));
		}
		long millis = (System.nanoTime() - start) / 1_000_000;
		told(context);
		List<String> heard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		if (!first || !pickedUp || !back || !heard.contains("- ADDED  > Placed stone")) {
			wrong.add("A waystone placed again: listed at first " + first + ", gone when picked up " + pickedUp + ", back in all() " + back + " after " + millis + " ms, heard " + heard);
		}
		Diorama.LOGGER.info("addon-api waypoints: a waystone placed again was back in all() after {} ms", millis);
		context.runOnClient(mc -> Waystones.clear());
		told(context);
	}

	/**
	 * Diorama's own screens follow an addon's delete while they're open: the list drops the row,
	 * and the editor's Save closes it without saving, as it does with no world, rather than save
	 * or bring back a waypoint that's gone.
	 */
	private static void screens(ClientGameTestContext context, List<String> wrong) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		Path file = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
		UUID doomed = context.computeOnClient(mc -> {
			UUID[] id = new UUID[1];
			TestAddon.world().waypoints().edit(edits -> id[0] = edits.add(NewWaypoint.builder("Doomed row", DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 2, at.getY(), at.getZ() - 2)).build()));
			return id[0];
		});
		told(context);
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(3);
		boolean listed = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).shown().stream().anyMatch(w -> w.id.equals(doomed)));
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> edits.remove(doomed)));
		context.waitTicks(3);
		boolean stillListed = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).shown().stream().anyMatch(w -> w.id.equals(doomed)));
		context.takeScreenshot("addon-api-15-list-after-addon-delete");
		if (!listed || stillListed) {
			wrong.add("The waypoint list open while an addon deletes a row: listed before " + listed + ", still listed after " + stillListed);
		}

		Waypoint editing = context.computeOnClient(mc -> {
			UUID[] id = new UUID[1];
			TestAddon.world().waypoints().edit(edits -> id[0] = edits.add(NewWaypoint.builder("Edited away", DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 3, at.getY(), at.getZ() - 3)).build()));
			return WaypointStore.get().all().stream().filter(w -> w.id.equals(id[0])).findFirst().orElseThrow();
		});
		told(context);
		context.setScreen(() -> new WaypointEditScreen(null, editing, false));
		context.waitTicks(3);
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> edits.remove(editing.id)));
		told(context);
		DioramaClientTest.typeInto(context, 0, "Brought back");
		int saves = context.computeOnClient(mc -> WaypointStore.saves());
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.waitTicks(3);
		int saved = context.computeOnClient(mc -> WaypointStore.saves()) - saves;
		boolean closed = context.computeOnClient(mc -> mc.gui.screen() == null);
		boolean back = context.computeOnClient(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.id.equals(editing.id) || w.name.equals("Brought back")));
		boolean inFile = text(file).contains("Brought back") || text(file).contains(editing.id.toString());
		if (saved != 0 || !closed || back || inFile) {
			wrong.add("The editor's Save on a waypoint an addon deleted: " + saved + " saves (expected 0), closed " + closed + ", back in the store " + back + ", in the file " + inFile);
		}
		context.setScreen(() -> null);
	}

	/**
	 * A switch of saved map is told as mapChanged, with the other map's waypoints in all() and no
	 * waypoint events; a waypoint added on the second map stays with it. A waystone found just
	 * before the switch is told before it; an addon that adds a waypoint in its own mapChanged has
	 * it told after every addon's mapChanged.
	 */
	private static void savedMaps(ClientGameTestContext context, List<String> wrong) {
		String key = context.computeOnClient(mc -> WorldKey.current());
		int eventsBefore = context.computeOnClient(mc -> TestAddon.worldEvents().size());
		Probe a = Probe.make(context, "switch_a", true);
		Probe b = Probe.make(context, "switch_b", true);
		BlockPos here = context.computeOnClient(mc -> mc.player.blockPosition());
		boolean[] once = new boolean[1];
		a.onMapChanged = world -> {
			if (!once[0]) {
				once[0] = true;
				world.waypoints().edit(edits -> edits.add(NewWaypoint.builder("Made on switch", DimensionId.OVERWORLD, BlockCoord.of(here.getX() + 1, here.getY(), here.getZ() + 1)).build()));
			}
		};
		context.runOnClient(mc -> TestAddon.forgetHeard());
		String second = context.computeOnClient(mc -> {
			Waystones.knownForTests(new Waypoint("Switch stone", here.getX() - 9, here.getY(), here.getZ(), "minecraft:overworld", 0xFF8E4EC6));
			return Maps.of(key).create("minecraft:overworld", "Second");
		});
		context.waitFor(mc -> MapSession.get() != null, 2400);
		context.waitTicks(2);
		told(context);
		List<String> aHeard = context.computeOnClient(mc -> List.copyOf(a.log));
		List<String> bHeard = context.computeOnClient(mc -> List.copyOf(b.log));
		List<String> orderExpected = List.of("- ADDED  > Switch stone", "mapChanged minecraft:overworld " + second, a.modId + " ADDED  > Made on switch");
		if (!aHeard.equals(orderExpected) || !bHeard.equals(orderExpected)) {
			wrong.add("Around a switch of saved map: A heard " + aHeard + ", B heard " + bHeard + ", each expected " + orderExpected);
		}
		Diorama.LOGGER.info("addon-api waypoints: around a switch of saved map, B heard {}", bHeard);
		a.onMapChanged = null;
		String mapIdThere = context.computeOnClient(mc -> TestAddon.world().mapId().toString());
		boolean fromAddonGone = context.computeOnClient(mc -> TestAddon.world().waypoints().get(fromAddon).isEmpty());
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		context.runOnClient(mc -> TestAddon.world().waypoints().edit(edits -> edits.add(NewWaypoint.builder("On the second map", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ())).build())));
		String madeOn = context.computeOnClient(mc -> TestAddon.world().waypoints().all().stream().filter(w -> w.name().equals("On the second map")).map(w -> w.mapId().toString()).findFirst().orElse("none"));
		String stoneMap = context.computeOnClient(mc -> TestAddon.world().waypoints().all().stream().filter(w -> w.name().equals("Switch stone")).map(w -> w.mapId().toString()).findFirst().orElse("none"));
		context.runOnClient(mc -> Maps.of(key).use("minecraft:overworld", Maps.MAIN));
		context.waitFor(mc -> MapSession.get() != null, 2400);
		context.waitTicks(2);
		told(context);
		boolean secondGone = context.computeOnClient(mc -> TestAddon.world().waypoints().all().stream().noneMatch(w -> w.name().equals("On the second map")));
		boolean fromAddonBack = context.computeOnClient(mc -> TestAddon.world().waypoints().get(fromAddon).isPresent());
		List<String> events = context.computeOnClient(mc -> List.copyOf(TestAddon.worldEvents().subList(eventsBefore, TestAddon.worldEvents().size())));
		List<String> heard = context.computeOnClient(mc -> List.copyOf(TestAddon.heard()));
		List<String> expected = List.of("mapChanged " + worldId + " minecraft:overworld " + second, "mapChanged " + worldId + " minecraft:overworld main");
		List<String> heardExpected = List.of("- ADDED  > Switch stone", a.modId + " ADDED  > Made on switch", "diorama_test_addon ADDED  > On the second map");
		if (second == null || !events.equals(expected) || !mapIdThere.equals(second) || !fromAddonGone || !madeOn.equals(second) || !secondGone || !fromAddonBack
			|| !heard.equals(heardExpected) || !stoneMap.equals("main")) {
			wrong.add("Switching saved maps: events " + events + ", expected " + expected + "; map ID there " + mapIdThere + ", the addon's waypoint gone there " + fromAddonGone
				+ ", one made there on " + madeOn + ", gone after switching back " + secondGone + ", the addon's back " + fromAddonBack + ", heard " + heard + ", expected "
				+ heardExpected + ", the waystone on map " + stoneMap);
		}
		context.runOnClient(mc -> Waystones.clear());
		told(context);
		a.forget(context);
		b.forget(context);
		// The second map goes, and the waypoints on it.
		context.runOnClient(mc -> Maps.of(key).delete("minecraft:overworld", second));
		// The main map read its chunks again on the switch back; the checks after this one want it full.
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		DioramaClientTest.settle(context, 40);
		Diorama.LOGGER.info("addon-api waypoints: switching saved maps told {}", events);
	}

	/**
	 * A world handle Diorama no longer uses throws and writes nothing: one whose store was
	 * replaced by a new one for the same world (as joining it again without leaving, which a
	 * server network does), whose file keeps the pin the new store saved; and one whose world isn't
	 * known for a moment (a server naming its next level), called before Diorama's tick has
	 * noticed. The world closes, and opens again once known.
	 */
	private static void staleWorlds(ClientGameTestContext context, List<String> wrong) {
		Path file = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		MapWorld old = context.computeOnClient(mc -> TestAddon.world());
		dev.diorama.api.Waypoints oldWaypoints = context.computeOnClient(mc -> old.waypoints());
		int eventsBefore = context.computeOnClient(mc -> TestAddon.worldEvents().size());
		String replaced = context.computeOnClient(mc -> {
			WaypointStore.onLeave();
			WaypointStore fresh = WaypointStore.get();
			fresh.add(new Waypoint("Kept by the new store", at.getX() + 5, at.getY(), at.getZ() + 5, "minecraft:overworld", Waypoint.PALETTE[5]));
			int saves = WaypointStore.saves();
			String all = thrown(oldWaypoints::all);
			String edit = thrown(() -> oldWaypoints.edit(edits -> edits.add(NewWaypoint.builder("Ghost", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ())).build())));
			return "all() " + all + "; edit() " + edit + "; open " + old.isOpen() + "; saves " + (WaypointStore.saves() - saves);
		});
		String json = text(file);
		boolean kept = json.contains("Kept by the new store") && !json.contains("Ghost");
		told(context);
		List<String> events = context.computeOnClient(mc -> List.copyOf(TestAddon.worldEvents().subList(eventsBefore, TestAddon.worldEvents().size())));
		List<String> reopenedExpected = List.of("closed " + worldId + " false", "opened " + worldId + " minecraft:overworld main");
		if (!replaced.matches("all\\(\\) IllegalStateException: This MapWorld is closed.*; edit\\(\\) IllegalStateException: This MapWorld is closed.*; open false; saves 0") || !kept
			|| !events.equals(reopenedExpected)) {
			wrong.add("A world handle after its store was replaced: " + replaced + "; the new store's pin kept in the file and no ghost " + kept + "; world events " + events + ", expected "
				+ reopenedExpected);
		}
		Diorama.LOGGER.info("addon-api waypoints: a world handle after its store was replaced: {}", replaced);

		MapWorld now = context.computeOnClient(mc -> TestAddon.world());
		dev.diorama.api.Waypoints nowWaypoints = context.computeOnClient(mc -> now.waypoints());
		int eventsThen = context.computeOnClient(mc -> TestAddon.worldEvents().size());
		String unknown = context.computeOnClient(mc -> {
			WorldKey.unknownForTests = true;
			int saves = WaypointStore.saves();
			// Before any tick: what the addon's own tick would see.
			String all = thrown(nowWaypoints::all);
			String edit = thrown(() -> nowWaypoints.edit(edits -> edits.add(NewWaypoint.builder("Ghost", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ())).build())));
			return "all() " + all + "; edit() " + edit + "; open " + now.isOpen() + "; saves " + (WaypointStore.saves() - saves);
		});
		told(context);
		context.waitTicks(5);
		List<String> whileUnknown = context.computeOnClient(mc -> List.copyOf(TestAddon.worldEvents().subList(eventsThen, TestAddon.worldEvents().size())));
		context.runOnClient(mc -> WorldKey.unknownForTests = false);
		told(context);
		List<String> afterKnown = context.computeOnClient(mc -> List.copyOf(TestAddon.worldEvents().subList(eventsThen, TestAddon.worldEvents().size())));
		boolean ghost = text(file).contains("Ghost");
		if (!unknown.matches("all\\(\\) IllegalStateException: This MapWorld is closed.*; edit\\(\\) IllegalStateException: This MapWorld is closed.*; open false; saves 0") || ghost
			|| !whileUnknown.equals(List.of("closed " + worldId + " false")) || !afterKnown.equals(List.of("closed " + worldId + " false", "opened " + worldId + " minecraft:overworld main"))) {
			wrong.add("A world handle while the world isn't known: " + unknown + "; a ghost in the file " + ghost + "; world events while unknown " + whileUnknown + ", once known again "
				+ afterKnown);
		}
		Diorama.LOGGER.info("addon-api waypoints: while the world wasn't known: {}; world events {}", unknown, afterKnown);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.name.equals("Kept by the new store")).toList().forEach(store::remove);
		});
		told(context);
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

	/** At the title screen after leaving: the world closed, and calls on it throw. */
	static void left(ClientGameTestContext context) {
		told(context);
		List<String> events = context.computeOnClient(mc -> List.copyOf(TestAddon.worldEvents()));
		String last = events.get(events.size() - 1);
		boolean open = context.computeOnClient(mc -> TestAddon.world().isOpen());
		WorldId id = context.computeOnClient(mc -> TestAddon.world().worldId());
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
	 * listener, then with one open again, so the order they're measured in shows apart from the
	 * listener. The draw paths don't know about addons, so they should match, and match master.
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
			Handle again = context.computeOnClient(mc -> TestAddon.world().waypoints().listen(changes -> {
			}));
			long minimapAgain = AllocProbe.MINIMAP.count(context, "listening again", 200);
			long waypointsAgain = AllocProbe.WAYPOINTS.count(context, "listening again", 200);
			context.runOnClient(mc -> again.close());
			Diorama.LOGGER.info("addon-api alloc: medians a frame, listener open {}: minimap {} listening, {} not, {} listening again; world waypoints {} listening, {} not, {} listening again",
				listening, minimapListening, minimapNone, minimapAgain, waypointsListening, waypointsNone, waypointsAgain);
		}
		context.runOnClient(mc -> pins.forEach(WaypointStore.get()::remove));
	}
	//?}

	/**
	 * Bytes and time of reading and editing with 1,000 waypoints, logged: all() and get() when
	 * nothing changed, with a listener open and with none, and one batch of 10 changes with no
	 * listener and with two, timed with the tick that tells them.
	 */
	static void measure(ClientGameTestContext context) {
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
		told(context);
		// frames() closed the test addon's listener on Fabric from 1.21.6; elsewhere it's still open.
		Handle listener = context.computeOnClient(mc -> TestAddon.listening().isOpen() ? null : TestAddon.world().waypoints().listen(changes -> {
		}));
		Diorama.LOGGER.info("addon-api measure: {}", reads(context, ids, "a listener open"));
		context.runOnClient(mc -> {
			if (listener != null) {
				listener.close();
			}
			if (TestAddon.listening().isOpen()) {
				TestAddon.listening().close();
			}
		});
		Diorama.LOGGER.info("addon-api measure: {}", reads(context, ids, "no listener"));
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
		told(context);
	}

	/** Bytes a call of all() and get() with nothing changed, over 100,000 calls each after 20,000 to warm up. */
	private static String reads(ClientGameTestContext context, List<UUID> ids, String setting) {
		return context.computeOnClient(mc -> {
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
			return String.format(Locale.ROOT, "%s, %d waypoints: all() %.3f bytes a call (%d in all), get() %.3f bytes a call (%d in all) over %d calls each (counter alone %d bytes; sink %d)",
				setting, waypoints.all().size(), (all - empty) / (double) calls, all - empty, (get - empty) / (double) calls, get - empty, calls, empty, sink);
		});
	}

	/**
	 * Median time and bytes of one edit batch of 10 renames and the tick that tells it, over 40
	 * batches after 10 to warm up.
	 */
	private static String editCost(ClientGameTestContext context, List<UUID> ids, int listeners) {
		return context.computeOnClient(mc -> {
			var waypoints = TestAddon.world().waypoints();
			int[] heard = new int[1];
			List<Handle> handles = new ArrayList<>();
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
				// What Diorama's tick does with it: tells the listeners.
				AddonWorlds.tick();
				nanos[round] = System.nanoTime() - start;
				bytes[round] = threads.getCurrentThreadAllocatedBytes() - startBytes;
			}
			handles.forEach(Handle::close);
			long[] n = java.util.Arrays.copyOfRange(nanos, 10, 50);
			long[] b = java.util.Arrays.copyOfRange(bytes, 10, 50);
			java.util.Arrays.sort(n);
			java.util.Arrays.sort(b);
			return String.format(Locale.ROOT, "median %.2f ms (%.2f to %.2f), %d bytes allocated (%d to %d); listeners heard %d changes",
				n[n.length / 2] / 1e6, n[0] / 1e6, n[n.length - 1] / 1e6, b[b.length / 2], b[0], b[b.length - 1], heard[0]);
		});
	}

	/**
	 * An addon the test makes itself ({@link Addons#foundForTests}), which logs what it's told in
	 * the order it's told: "opened", "mapChanged <dimension> <map>", "closed", and one line per
	 * waypoint changed, "<mod or -> <type> <name [folder]> > <name [folder]>".
	 */
	static final class Probe {
		final String modId;
		final boolean listen;
		final List<String> log = new ArrayList<>();
		DioramaApi api;
		Handle onWorld;
		MapWorld world;
		Handle listening;
		/** Run after a change is logged; null for nothing. */
		Consumer<WaypointChanges> onChange;
		/** Run after a mapChanged is logged; null for nothing. */
		Consumer<MapWorld> onMapChanged;

		private Probe(String name, boolean listen) {
			this.modId = "diorama_test_probe_" + name;
			this.listen = listen;
		}

		/** Makes the addon and lets the tick tell it the world is open; with {@code listen}, it listens from then on. */
		static Probe make(ClientGameTestContext context, String name, boolean listen) {
			Probe probe = new Probe(name, listen);
			context.runOnClient(mc -> Addons.foundForTests(new Addons.Found(probe.modId, "Probe " + name, () -> api -> {
				probe.api = api;
				probe.onWorld = api.onWorld(new WorldListener() {
					@Override
					public void opened(MapWorld opened) {
						probe.world = opened;
						if (probe.listen) {
							probe.listening = opened.waypoints().listen(probe::heard);
						}
					}

					@Override
					public void mapChanged(MapWorld changed) {
						probe.log.add("mapChanged " + changed.dimension() + " " + changed.mapId());
						if (probe.onMapChanged != null) {
							probe.onMapChanged.accept(changed);
						}
					}

					@Override
					public void closed(MapWorld closed) {
						probe.log.add("closed");
					}
				});
			})));
			told(context);
			if (context.computeOnClient(mc -> probe.world == null)) {
				throw new AssertionError("The probe " + probe.modId + " wasn't told the world is open");
			}
			return probe;
		}

		void heard(WaypointChanges changes) {
			for (WaypointChange change : changes.changes()) {
				this.log.add(changes.by().orElse("-") + " " + change.type() + " " + change.before().map(Probe::describe).orElse("") + " > "
					+ change.after().map(Probe::describe).orElse(""));
			}
			if (this.onChange != null) {
				this.onChange.accept(changes);
			}
		}

		private static String describe(dev.diorama.api.Waypoint waypoint) {
			return waypoint.name() + waypoint.folder().map(folder -> " [" + folder + "]").orElse("");
		}

		/** Closes what it holds and drops it from Diorama's addons, so later sections and the settings' Addons tab don't see it. */
		void forget(ClientGameTestContext context) {
			context.runOnClient(mc -> {
				if (this.onWorld != null) {
					this.onWorld.close();
				}
				Addons.forgetForTests(this.modId);
			});
		}
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
