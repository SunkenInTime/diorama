package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Maps;
import dev.diorama.WorldKey;
import dev.diorama.addon.Addons;
import dev.diorama.api.BlockCoord;
import dev.diorama.api.DimensionId;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.EditResult;
import dev.diorama.api.Hud;
import dev.diorama.api.MapId;
import dev.diorama.api.NewWaypoint;
import dev.diorama.api.WaypointUpdate;
import dev.diorama.api.World;
import dev.diorama.api.WorldId;
import dev.diorama.bake.TileKey;
import dev.diorama.testaddon.TestAddon;
import dev.diorama.testaddon.sync.OtherMod;
import dev.diorama.testaddon.sync.PollingSync;
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
import java.util.Arrays;
import java.util.EnumSet;
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
 * The addon API's waypoints ({@code addon-api}): an addon reads the world and the waypoints when
 * it wants and edits them in batches; Diorama never calls it back. Driven through the test addon,
 * which knows only the API, and probes, addons the test makes itself ({@link Probe}).
 *
 * <p>At the title screen ({@link #titleScreen}) there's no world. In the first world
 * ({@link #firstWorld}): {@code world()} names it and is one value until something moves;
 * {@code all()} reads what the waypoint list shows; an addon's waypoint is in the list, on the
 * map, in the world and in waypoints.json after one save, three new folders write folders.json
 * once; {@code revision()} moves for each change made in Diorama's own screens and for a
 * Waystones change, and not for a batch that changes nothing; {@code world()} moves with the
 * dimension and the saved map; a batch for another world, or whose world or store changed while it
 * was being made, throws and writes nothing; one that throws switches its addon off; a disk that
 * refuses the write reports {@code saved() == false}; automatic pins are read-only; a file with
 * two waypoints of one ID shows only the first; every call from another thread throws; Diorama's
 * list drops a row an addon deleted, even after the player clicked another row first, and the
 * map's menu and the editor refuse a waypoint that's gone; and the test addon's polling sync
 * keeps a pretend map mod in step both ways without echoes. Everything the section adds it takes
 * away again, as the waypoints section runs in this world next. After leaving ({@link #left}) and
 * in the world opened again ({@link #reopened}) the ID is the same. {@link #measure} and
 * {@link #frames} measure what it costs.
 */
final class AddonWaypointsTest {
	/** Pure cyan, which no block in the test world is, to find the addon's pin in the world by. */
	private static final int PIN = 0x00FFFF;
	private static final String FROM_ADDON = "From addon";
	private static WorldId worldId;

	private AddonWaypointsTest() {
	}

	private static DioramaApi api() {
		return TestAddon.api();
	}

	private static dev.diorama.api.Waypoints waypoints() {
		return TestAddon.api().waypoints();
	}

	private static long revision(ClientGameTestContext context) {
		return context.computeOnClient(mc -> waypoints().revision());
	}

	/** At the title screen: no world, no waypoints, revision 0, and an edit for any world throws. */
	static void titleScreen(ClientGameTestContext context) {
		String read = context.computeOnClient(mc -> api().world() + " " + waypoints().all() + " " + waypoints().revision() + " "
			+ thrown(() -> waypoints().edit(WorldId.of("0123456789abcdef0123456789abcdef"), edits -> {
			})));
		if (!read.startsWith("Optional.empty [] 0 IllegalStateException: No world is open")) {
			throw new AssertionError("At the title screen: world, all(), revision() and an edit read " + read);
		}
	}

	static void firstWorld(ClientGameTestContext context, TestSingleplayerContext world) {
		List<String> wrong = new ArrayList<>();
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		context.setScreen(() -> null);
		context.waitFor(mc -> mc.player.onGround(), 600);
		worlds(context, world, wrong);
		reads(context, wrong);
		adds(context, wrong);
		player(context, wrong);
		otherChanges(context, wrong);
		sameList(context, wrong);
		staleWorlds(context, wrong);
		throwing(context, wrong);
		diskFull(context, wrong);
		readOnly(context, wrong);
		duplicates(context, wrong);
		threads(context, wrong);
		listScreen(context, wrong);
		mapMenu(context, wrong);
		editor(context, wrong);
		sync(context, wrong);
		cleanUp(context, wrong);
		if (!wrong.isEmpty()) {
			throw new AssertionError("Addon waypoints in the first world:\n  " + String.join("\n  ", wrong));
		}
		Diorama.LOGGER.info("addon-api waypoints: first world checks passed");
	}

	/**
	 * {@code world()} names the world, is the same instance call after call, and moves to a new value
	 * with the dimension and back to an equal one.
	 */
	private static void worlds(ClientGameTestContext context, TestSingleplayerContext server, List<String> wrong) {
		String here = context.computeOnClient(mc -> {
			Optional<World> one = api().world();
			Optional<World> two = api().world();
			return one.map(w -> w.dimension() + " " + w.mapId() + " " + w.mapId(DimensionId.NETHER)).orElse("none") + " " + (one == two);
		});
		worldId = context.computeOnClient(mc -> api().world().orElseThrow().worldId());
		if (!here.equals("minecraft:overworld main main true") || !worldId.toString().matches("[0-9a-f]{32}")) {
			wrong.add("world() in the Overworld: " + here + ", ID " + worldId);
		}
		World before = context.computeOnClient(mc -> api().world().orElseThrow());
		long revision = revision(context);
		BlockPos home = context.computeOnClient(mc -> mc.player.blockPosition());
		server.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 120 0");
		context.waitFor(mc -> mc.level.dimension() == net.minecraft.world.level.Level.NETHER && MapSession.get() != null, 2400);
		context.waitTicks(20);
		World nether = context.computeOnClient(mc -> api().world().orElseThrow());
		long netherRevision = revision(context);
		server.getServer().runCommand("execute in minecraft:overworld run tp @a " + home.getX() + " " + home.getY() + " " + home.getZ());
		context.waitFor(mc -> mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD && MapSession.get() != null
			&& MapSession.get().map().chunkCount() > 400, 2400);
		DioramaClientTest.settle(context, 40);
		context.waitFor(mc -> mc.player.onGround(), 600);
		World back = context.computeOnClient(mc -> api().world().orElseThrow());
		// A dimension change doesn't change what all() lists (every dimension's waypoints are in it): world() says it.
		if (!nether.dimension().equals(DimensionId.NETHER) || nether.equals(before) || !back.equals(before) || !nether.worldId().equals(worldId)
			|| netherRevision != revision) {
			wrong.add("world() through a change of dimension: " + before + " > " + nether + " > " + back + "; revision " + revision + " > " + netherRevision
				+ " (expected unchanged: all() lists every dimension)");
		}
		Diorama.LOGGER.info("addon-api waypoints: world() {} > {} > {}", before, nether, back);
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
			for (dev.diorama.api.Waypoint w : waypoints().all()) {
				out.add(w.id() + " " + w.name() + " " + w.dimension() + " " + w.pos().x() + "," + w.pos().y() + "," + w.pos().z() + " " + Integer.toHexString(w.rgb()));
			}
			return String.join("; ", out);
		});
		if (!read.equals(listed) || !read.contains("Read two minecraft:the_nether")) {
			wrong.add("all() reads " + read + "; the list has " + listed);
		}
		removeNamed(context, "Read ");
	}

	/**
	 * A waypoint the addon adds: one save, in the file with its mod ID, its ID in the result, in
	 * the list, on the map and in the world; still there when the store is read again from the
	 * file. Three new folders in one batch write folders.json once and waypoints.json once.
	 */
	private static void adds(ClientGameTestContext context, List<String> wrong) {
		Path file = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int hx = at.getX() - 4;
		int hz = at.getZ() - 16;
		int hy = context.computeOnClient(mc -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, hx, hz));
		int savesBefore = context.computeOnClient(mc -> WaypointStore.saves());
		long revisionBefore = revision(context);
		String added = context.computeOnClient(mc -> {
			EditResult result = waypoints().edit(worldId, edits -> edits.add(NewWaypoint.builder(FROM_ADDON, DimensionId.OVERWORLD, BlockCoord.of(hx, hy, hz))
				.rgb(PIN).initials("TA").build()));
			UUID id = result.added().get(0);
			return id + " " + result.changed() + " " + result.saved() + " " + (waypoints().revision() != revisionBefore) + " "
				+ waypoints().get(id).flatMap(dev.diorama.api.Waypoint::addedBy).orElse("none") + " " + waypoints().get(id).map(w -> Integer.toHexString(w.rgb())).orElse("none");
		});
		UUID fromAddon = UUID.fromString(added.split(" ")[0]);
		int saves = context.computeOnClient(mc -> WaypointStore.saves()) - savesBefore;
		String json = text(file);
		boolean inFile = json.contains(fromAddon.toString()) && json.contains("\"addedBy\": \"diorama_test_addon\"");
		if (saves != 1 || !inFile || !added.endsWith(" true true true diorama_test_addon ffff")) {
			wrong.add("Adding one waypoint: " + saves + " saves, in waypoints.json with its mod ID " + inFile + "; ID, changed, saved, revision moved, addedBy, rgb: " + added);
		}

		// Read again from the file, as joining the world again does.
		String reloaded = context.computeOnClient(mc -> {
			WaypointStore.onLeave();
			return waypoints().get(fromAddon).map(w -> w.name() + " " + w.addedBy().orElse("none")).orElse("gone");
		});
		if (!reloaded.equals(FROM_ADDON + " diorama_test_addon")) {
			wrong.add("The addon's waypoint read again from waypoints.json: " + reloaded);
		}

		int folderSavesBefore = context.computeOnClient(mc -> WaypointStore.folderSaves());
		int threeBefore = context.computeOnClient(mc -> WaypointStore.saves());
		List<UUID> three = context.computeOnClient(mc -> waypoints().edit(worldId, edits -> {
			for (String folder : List.of("Batch folder A", "Batch folder B", "Batch folder C")) {
				edits.add(NewWaypoint.builder("In " + folder, DimensionId.OVERWORLD, BlockCoord.of(hx + 6, hy, hz)).folder(folder).build());
			}
		}).added());
		int folderSaves = context.computeOnClient(mc -> WaypointStore.folderSaves()) - folderSavesBefore;
		int threeSaves = context.computeOnClient(mc -> WaypointStore.saves()) - threeBefore;
		String inFolders = context.computeOnClient(mc -> {
			List<String> out = new ArrayList<>();
			for (UUID id : three) {
				out.add(waypoints().get(id).flatMap(dev.diorama.api.Waypoint::folder).orElse("none"));
			}
			return out.toString();
		});
		if (folderSaves != 1 || threeSaves != 1 || !inFolders.equals("[Batch folder A, Batch folder B, Batch folder C]")) {
			wrong.add("Three new folders in one batch: folders.json written " + folderSaves + " times, waypoints.json " + threeSaves + ", the waypoints in " + inFolders);
		}
		Diorama.LOGGER.info("addon-api waypoints: three new folders in one batch wrote folders.json {} time(s), waypoints.json {}", folderSaves, threeSaves);
		context.runOnClient(mc -> {
			waypoints().edit(worldId, edits -> three.forEach(edits::remove));
			for (String folder : List.of("Batch folder A", "Batch folder B", "Batch folder C")) {
				WaypointStore.get().deleteFolder(folder);
			}
		});

		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("addon-api-10-list");
		boolean listed = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).shown().stream().anyMatch(w -> w.id.equals(fromAddon)));
		context.setScreen(MapScreen::new);
		DioramaClientTest.lookAtPlayer(context, 4.0, TileKey.SURFACE);
		DioramaClientTest.settle(context, 30);
		context.takeScreenshot("addon-api-11-map");
		boolean onMap = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).pins().stream().anyMatch(pin -> pin.waypoint().id.equals(fromAddon)));
		context.setScreen(() -> null);
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
	 * and typing: each moves revision() and saves once, as on master.
	 */
	private static void player(ClientGameTestContext context, List<String> wrong) {
		List<Integer> saves = new ArrayList<>();
		List<String> moved = new ArrayList<>();
		int start = context.computeOnClient(mc -> WaypointStore.saves());
		long[] last = {revision(context)};
		Runnable note = () -> {
			saves.add(context.computeOnClient(mc -> WaypointStore.saves()) - start);
			long now = revision(context);
			moved.add(Boolean.toString(now != last[0]));
			last[0] = now;
		};

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
		note.run();

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
		note.run();

		// The row's own Hide, not the folder's at the top.
		int[] row = select(context, made);
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> rowButton(mc.gui.screen(), "diorama.waypoints.hide", row[1])));
		context.waitTicks(3);
		note.run();
		context.takeScreenshot("addon-api-13-player-hid");

		select(context, made);
		DioramaClientTest.clickButton(context, "diorama.waypoint.delete");
		DioramaClientTest.clickButton(context, "diorama.menu.delete_confirm");
		context.waitTicks(3);
		note.run();
		context.setScreen(() -> null);
		if (!moved.equals(List.of("true", "true", "true", "true")) || !saves.equals(List.of(1, 2, 3, 4))) {
			wrong.add("The player's add, rename, hide and delete: revision moved " + moved + "; saves after each " + saves + " (expected 1, 2, 3, 4, as on master)");
		}
		Diorama.LOGGER.info("addon-api waypoints: the player's add, rename, hide and delete moved revision() {}, saves after each {}", moved, saves);
	}

	/**
	 * revision() also moves for a move to a folder, a folder renamed and deleted, a saved map
	 * switched, and a waystone found (each through what Diorama's screens and the Waystones bridge
	 * call); it doesn't for hiding a whole folder or Diorama saving with nothing changed, nor for
	 * an addon's batch that changes nothing, which saves nothing either.
	 */
	private static void otherChanges(ClientGameTestContext context, List<String> wrong) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		Waypoint mine = context.computeOnClient(mc -> {
			Waypoint w = new Waypoint("Folder mover", at.getX() + 3, at.getY(), at.getZ(), "minecraft:overworld", Waypoint.PALETTE[2]);
			WaypointStore.get().add(w);
			WaypointStore.get().addFolder("Moved into");
			return w;
		});
		List<String> results = new ArrayList<>();
		check(context, results, "moved to a folder", true, mc -> {
			// What the list's Move does.
			mine.folder = "Moved into";
			WaypointStore.get().changed();
		});
		check(context, results, "folder renamed", true, mc -> WaypointStore.get().renameFolder("Moved into", "Renamed folder"));
		check(context, results, "folder hidden", false, mc -> WaypointStore.get().setFolderHidden(WaypointStore.customKey("Renamed folder"), true));
		check(context, results, "folder shown", false, mc -> WaypointStore.get().setFolderHidden(WaypointStore.customKey("Renamed folder"), false));
		check(context, results, "folder deleted", true, mc -> WaypointStore.get().deleteFolder("Renamed folder"));
		check(context, results, "saved with nothing changed", false, mc -> WaypointStore.get().changed());
		UUID[] stone = new UUID[1];
		check(context, results, "a waystone found", true, mc -> {
			Waypoint w = new Waypoint("Test stone", at.getX() - 7, at.getY(), at.getZ() + 7, "minecraft:overworld", 0xFF8E4EC6);
			stone[0] = w.id;
			Waystones.knownForTests(w);
		});
		check(context, results, "a waystone picked up", true, mc -> Waystones.pickedUpForTests(stone[0], false));
		// Placed again where it stood, which no event says: found when the HUD next lists the waystones.
		long beforePlaced = revision(context);
		context.runOnClient(mc -> Waystones.pickedUpForTests(stone[0], true));
		context.waitTicks(5);
		boolean placedAgain = context.computeOnClient(mc -> waypoints().revision() != beforePlaced && waypoints().get(stone[0]).isPresent());
		results.add("a waystone placed again " + (placedAgain ? "ok" : "WRONG (revision or all() didn't follow)"));
		context.runOnClient(mc -> Waystones.clear());

		// A batch that changes nothing: the name it has, folder in other capitals, the colour it has.
		UUID own = context.computeOnClient(mc -> waypoints().edit(worldId, edits -> edits.add(NewWaypoint.builder("Same as ever", DimensionId.OVERWORLD,
			BlockCoord.of(at.getX(), at.getY(), at.getZ() + 4)).folder("Kept folder").build())).added().get(0));
		String noop = context.computeOnClient(mc -> {
			long before = waypoints().revision();
			int saves = WaypointStore.saves();
			int folderSaves = WaypointStore.folderSaves();
			EditResult result = waypoints().edit(worldId, edits -> edits.update(own, WaypointUpdate.builder().name("Same as ever").folder("kept FOLDER")
				.rgb(waypoints().get(own).orElseThrow().rgb()).visible(true).build()));
			return "changed " + result.changed() + ", saved " + result.saved() + ", added " + result.added() + ", revision moved " + (waypoints().revision() != before)
				+ ", saves " + (WaypointStore.saves() - saves) + ", folder saves " + (WaypointStore.folderSaves() - folderSaves);
		});
		if (!noop.equals("changed false, saved true, added [], revision moved false, saves 0, folder saves 0")) {
			wrong.add("A batch that changes nothing: " + noop);
		}

		// A switch of saved map: world() names the new map, all() lists its waypoints, revision moves; and back.
		String key = context.computeOnClient(mc -> WorldKey.current());
		long beforeSwitch = revision(context);
		World mainWorld = context.computeOnClient(mc -> api().world().orElseThrow());
		String second = context.computeOnClient(mc -> Maps.of(key).create("minecraft:overworld", "Second"));
		context.waitFor(mc -> MapSession.get() != null, 2400);
		context.waitTicks(2);
		String there = context.computeOnClient(mc -> api().world().orElseThrow().mapId() + " " + (waypoints().revision() != beforeSwitch) + " "
			+ waypoints().get(own).isPresent());
		context.runOnClient(mc -> Maps.of(key).use("minecraft:overworld", Maps.MAIN));
		context.waitFor(mc -> MapSession.get() != null, 2400);
		context.waitTicks(2);
		String backHome = context.computeOnClient(mc -> api().world().orElseThrow().equals(mainWorld) + " " + waypoints().get(own).isPresent());
		if (second == null || !there.equals(second + " true false") || !backHome.equals("true true")) {
			wrong.add("A switch of saved map: on the second, map ID, revision moved, the main map's waypoint listed: " + there + " (expected " + second
				+ " true false); back on the main, world() as before and the waypoint listed: " + backHome);
		}
		context.runOnClient(mc -> Maps.of(key).delete("minecraft:overworld", second));
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		DioramaClientTest.settle(context, 40);

		for (String result : results) {
			if (!result.endsWith(" ok")) {
				wrong.add("revision() when " + result);
			}
		}
		Diorama.LOGGER.info("addon-api waypoints: revision() {}; a batch that changes nothing: {}", results, noop);
		context.runOnClient(mc -> {
			waypoints().edit(worldId, edits -> edits.remove(own));
			WaypointStore.get().deleteFolder("Kept folder");
			WaypointStore.get().remove(mine);
		});
	}

	/** Runs one change and notes whether revision() moved as expected. */
	private static void check(ClientGameTestContext context, List<String> results, String what, boolean moves, Consumer<net.minecraft.client.Minecraft> change) {
		boolean moved = context.computeOnClient(mc -> {
			long before = waypoints().revision();
			change.accept(mc);
			return waypoints().revision() != before;
		});
		results.add(what + (moved == moves ? " ok" : " WRONG (moved " + moved + ")"));
	}

	/** all() is one list until a change, a change that changes nothing keeps it, and it can't be changed. */
	private static void sameList(ClientGameTestContext context, List<String> wrong) {
		UUID id = addOne(context, "Same list", 0, 6);
		String result = context.computeOnClient(mc -> {
			List<dev.diorama.api.Waypoint> first = waypoints().all();
			List<dev.diorama.api.Waypoint> second = waypoints().all();
			WaypointStore.get().changed();
			List<dev.diorama.api.Waypoint> afterNothing = waypoints().all();
			waypoints().edit(worldId, edits -> edits.update(id, WaypointUpdate.builder().initials("SL").build()));
			List<dev.diorama.api.Waypoint> afterChange = waypoints().all();
			String unmodifiable = thrown(afterChange::clear);
			return (first == second) + " " + (second == afterNothing) + " " + (afterNothing != afterChange) + " " + unmodifiable.startsWith("UnsupportedOperationException");
		});
		if (!result.equals("true true true true")) {
			wrong.add("all(): same list twice, after a change of nothing, a new one after a change, unmodifiable: " + result);
		}
		removeIds(context, List.of(id));
	}

	/**
	 * world() is empty while Diorama doesn't know which world it is, and equal again after. An edit
	 * for another world throws before running the batch; one whose world goes unknown, or whose
	 * store is replaced, while its batch runs throws after it and writes nothing. Neither switches
	 * the addon off. A store replaced keeps world() equal and moves revision().
	 */
	private static void staleWorlds(ClientGameTestContext context, List<String> wrong) {
		Path file = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		String unknown = context.computeOnClient(mc -> {
			WorldKey.unknownForTests = true;
			try {
				return api().world().isPresent() + " " + waypoints().all().size() + " " + waypoints().revision() + " " + waypoints().get(UUID.randomUUID()).isPresent();
			} finally {
				WorldKey.unknownForTests = false;
			}
		});
		boolean again = context.computeOnClient(mc -> api().world().map(w -> w.worldId().equals(worldId)).orElse(false));
		if (!unknown.equals("false 0 0 false") || !again) {
			wrong.add("While the world isn't known: world() present, all() size, revision(), get() present: " + unknown + " (expected false 0 0 false); known again " + again);
		}

		List<String> refused = new ArrayList<>();
		int savesBefore = context.computeOnClient(mc -> WaypointStore.saves());
		boolean[] ran = new boolean[1];
		refused.add(context.computeOnClient(mc -> thrown(() -> waypoints().edit(WorldId.of("0123456789abcdef0123456789abcdef"), edits -> {
			ran[0] = true;
			edits.add(NewWaypoint.builder("Ghost", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ())).build());
		}))));
		refused.add(context.computeOnClient(mc -> thrown(() -> waypoints().edit(worldId, edits -> {
			edits.add(NewWaypoint.builder("Ghost", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ())).build());
			WorldKey.unknownForTests = true;
		}))));
		context.runOnClient(mc -> WorldKey.unknownForTests = false);
		String replacedRevisions = context.computeOnClient(mc -> {
			long before = waypoints().revision();
			World world = api().world().orElseThrow();
			String thrown = thrown(() -> waypoints().edit(worldId, edits -> {
				edits.add(NewWaypoint.builder("Ghost", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ())).build());
				// The player joining the world again, as a server network does without leaving.
				WaypointStore.onLeave();
				WaypointStore.get();
			}));
			refused.add(thrown);
			return (waypoints().revision() != before) + " " + api().world().orElseThrow().equals(world);
		});
		int saves = context.computeOnClient(mc -> WaypointStore.saves()) - savesBefore;
		boolean ghost = text(file).contains("Ghost") || context.computeOnClient(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.name.equals("Ghost")));
		boolean on = context.computeOnClient(mc -> running("diorama_test_addon"));
		if (ran[0] || !refused.get(0).startsWith("IllegalStateException: World 0123456789abcdef0123456789abcdef isn't the world open now")
			|| !refused.get(1).startsWith("IllegalStateException: No world is open") || !refused.get(2).startsWith("IllegalStateException: World " + worldId + "'s waypoints were loaded again")
			|| saves != 0 || ghost || !on || !replacedRevisions.equals("true true")) {
			wrong.add("Batches for a world not open: the batch ran for another world " + ran[0] + "; threw " + refused + "; " + saves + " saves, a ghost written " + ghost
				+ ", the addon still on " + on + "; after the store was replaced, revision moved and world() equal: " + replacedRevisions);
		}
		Diorama.LOGGER.info("addon-api waypoints: batches for a world not open threw {}", refused);
	}

	/**
	 * A batch that throws, an ordinary exception and then ThreadDeath: nothing of it is applied or
	 * saved, the folder it named isn't made, the exception reaches the caller, and the addon is
	 * switched off, its HUD handle closed; the exception with one log line, ThreadDeath with none.
	 */
	@SuppressWarnings("removal")
	private static void throwing(ClientGameTestContext context, List<String> wrong) {
		UUID existing = addOne(context, "Never renamed?", 2, 6);
		for (boolean death : new boolean[] {false, true}) {
			Probe probe = Probe.make(context, death ? "batch_death" : "batch_exception");
			dev.diorama.api.Handle hid = context.computeOnClient(mc -> probe.api.hud().hide(EnumSet.of(Hud.Part.WORLD_WAYPOINTS)));
			String before = context.computeOnClient(mc -> WaypointStore.saves() + " " + WaypointStore.folderSaves() + " " + WaypointStore.get().customFolders() + " "
				+ WaypointStore.get().all().size() + " " + waypoints().revision());
			String threw = context.computeOnClient(mc -> {
				try {
					probe.api.waypoints().edit(worldId, edits -> {
						edits.add(NewWaypoint.builder("Never kept", DimensionId.OVERWORLD, BlockCoord.of(0, 64, 0)).folder("Rollback folder").build());
						edits.update(existing, WaypointUpdate.builder().name("Never renamed").folder("Rollback folder").build());
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
			String after = context.computeOnClient(mc -> WaypointStore.saves() + " " + WaypointStore.folderSaves() + " " + WaypointStore.get().customFolders() + " "
				+ WaypointStore.get().all().size() + " " + waypoints().revision());
			boolean off = context.computeOnClient(mc -> !running(probe.modId) && !hid.isOpen());
			String later = context.computeOnClient(mc -> {
				EditResult result = probe.api.waypoints().edit(worldId, edits -> edits.add(NewWaypoint.builder("After off", DimensionId.OVERWORLD, BlockCoord.of(0, 64, 0)).build()));
				return result.changed() + " " + result.added();
			});
			long lines = log(context).lines().filter(line -> line.contains("Diorama addon " + probe.modId + " failed in a Waypoints.edit batch")).count();
			String expected = death ? "ThreadDeath: null" : "IllegalStateException: A batch that throws on purpose";
			if (!threw.equals(expected) || !before.equals(after) || !off || lines != (death ? 0 : 1) || !later.equals("false []")) {
				wrong.add("A batch that throws " + (death ? "ThreadDeath" : "an exception") + ": the caller got " + threw + ", expected " + expected + "; saves, folder saves, folders, count, revision "
					+ before + " > " + after + "; switched off with its handle closed " + off + "; " + lines + " log lines; a later batch changed, added " + later);
			}
			Diorama.LOGGER.info("addon-api waypoints: a batch that threw {} changed nothing, switched off {}", threw, off);
			probe.forget(context);
		}
		removeIds(context, List.of(existing));
	}

	/**
	 * A disk that refuses waypoints.json (its temporary file's name taken by a folder): the batch
	 * reports saved() false and changed() true, the waypoint is kept in memory as the player's own
	 * edits are, and the next save that works writes it.
	 */
	private static void diskFull(ClientGameTestContext context, List<String> wrong) {
		Path file = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
		Path blocker = file.resolveSibling("waypoints.json.tmp");
		try {
			Files.createDirectories(blocker);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		String result;
		try {
			result = context.computeOnClient(mc -> {
				EditResult edit = waypoints().edit(worldId, edits -> edits.add(NewWaypoint.builder("Unsaved", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ() + 7)).build()));
				return edit.changed() + " " + edit.saved() + " " + waypoints().get(edit.added().get(0)).isPresent();
			});
		} finally {
			try {
				Files.delete(blocker);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		boolean inFileBefore = text(file).contains("\"Unsaved\"");
		context.runOnClient(mc -> WaypointStore.get().changed());
		boolean inFileAfter = text(file).contains("\"Unsaved\"");
		if (!result.equals("true false true") || inFileBefore || !inFileAfter) {
			wrong.add("A batch the disk refused: changed, saved, kept in memory " + result + " (expected true false true); in the file before " + inFileBefore + ", after the next save " + inFileAfter);
		}
		Diorama.LOGGER.info("addon-api waypoints: a batch the disk refused reported changed, saved, kept: {}", result);
		removeNamed(context, "Unsaved");
	}

	/** Automatic pins are read but not written: a temporary pin and a waystone; the addon catches the refusals and carries on. */
	private static void readOnly(ClientGameTestContext context, List<String> wrong) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		context.runOnClient(mc -> Waypoints.addTemporary(at.east(3), "minecraft:overworld"));
		UUID temporary = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.TEMPORARY).findFirst().orElseThrow().id);
		UUID stone = context.computeOnClient(mc -> {
			Waypoint w = new Waypoint("Read-only stone", at.getX() - 5, at.getY(), at.getZ() + 5, "minecraft:overworld", 0xFF8E4EC6);
			Waystones.knownForTests(w);
			return w.id;
		});
		int saves = context.computeOnClient(mc -> WaypointStore.saves());
		String refused = context.computeOnClient(mc -> {
			List<String> out = new ArrayList<>();
			out.add(waypoints().get(temporary).map(w -> w.kind().name()).orElse("none"));
			out.add(waypoints().get(stone).map(w -> w.kind().name()).orElse("none"));
			EditResult result = waypoints().edit(worldId, edits -> {
				out.add(thrown(() -> edits.update(temporary, WaypointUpdate.builder().name("Mine now").build())).split(":")[0]);
				out.add(thrown(() -> edits.remove(temporary)).split(":")[0]);
				out.add(thrown(() -> edits.update(stone, WaypointUpdate.builder().name("Mine now").build())).split(":")[0]);
			});
			out.add(Boolean.toString(result.changed()));
			return String.join(" ", out);
		});
		boolean unchanged = context.computeOnClient(mc -> WaypointStore.saves() == saves && WaypointStore.get().all().stream().noneMatch(w -> w.name.equals("Mine now")));
		boolean on = context.computeOnClient(mc -> running("diorama_test_addon"));
		if (!refused.equals("TEMPORARY WAYSTONE IllegalArgumentException IllegalArgumentException IllegalArgumentException false") || !unchanged || !on) {
			wrong.add("Automatic pins: read and refused " + refused + "; store unchanged " + unchanged + ", the addon still on " + on);
		}
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.kind == Waypoint.Kind.TEMPORARY).toList().forEach(store::remove);
			Waystones.clear();
		});
	}

	/**
	 * Two waypoints with one ID, as a hand-edited file can have: all() and get() show only the
	 * first; an edit reaches only the first; the second stays as it was until the first is gone.
	 */
	private static void duplicates(ClientGameTestContext context, List<String> wrong) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		UUID id = context.computeOnClient(mc -> {
			Waypoint one = new Waypoint("Twin", at.getX() + 11, at.getY(), at.getZ(), "minecraft:overworld", Waypoint.PALETTE[2]);
			Waypoint two = new Waypoint("Twin", at.getX() + 12, at.getY(), at.getZ(), "minecraft:overworld", Waypoint.PALETTE[2]);
			two.id = one.id;
			two.created = one.created;
			WaypointStore.get().add(one);
			WaypointStore.get().add(two);
			return one.id;
		});
		String read = context.computeOnClient(mc -> {
			long listed = waypoints().all().stream().filter(w -> w.id().equals(id)).count();
			int x = waypoints().get(id).map(w -> w.pos().x()).orElse(0) - at.getX();
			waypoints().edit(worldId, edits -> edits.update(id, WaypointUpdate.builder().name("Twin renamed").build()));
			String stored = WaypointStore.get().all().stream().filter(w -> w.id.equals(id)).map(w -> w.name).toList().toString();
			boolean[] removed = new boolean[1];
			waypoints().edit(worldId, edits -> removed[0] = edits.remove(id));
			String left = WaypointStore.get().all().stream().filter(w -> w.id.equals(id)).map(w -> w.name + "@" + (w.x - at.getX())).toList().toString();
			String shownNow = waypoints().get(id).map(w -> w.name() + "@" + (w.pos().x() - at.getX())).orElse("gone");
			return "listed " + listed + ", get() at +" + x + ", stored after a rename " + stored + ", removed " + removed[0] + ", left " + left + ", get() then " + shownNow;
		});
		String expected = "listed 1, get() at +11, stored after a rename [Twin renamed, Twin], removed true, left [Twin@12], get() then Twin@12";
		if (!read.equals(expected)) {
			wrong.add("Two waypoints with one ID: " + read + "; expected " + expected);
		}
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.id.equals(id)).toList().forEach(store::remove);
		});
	}

	/**
	 * world(), all(), get(), revision() and edit() from another thread throw. From a thread of the
	 * test's own: Fabric's game-test thread can't reach the game at all. A call that hangs is
	 * reported by name rather than lost.
	 */
	private static void threads(ClientGameTestContext context, List<String> wrong) {
		DioramaApi api = context.computeOnClient(mc -> api());
		dev.diorama.api.Waypoints waypoints = context.computeOnClient(mc -> api.waypoints());
		String[] calls = new String[5];
		Thread other = new Thread(() -> {
			calls[0] = thrown(api::world);
			calls[1] = thrown(waypoints::all);
			calls[2] = thrown(() -> waypoints.get(UUID.randomUUID()));
			calls[3] = thrown(waypoints::revision);
			calls[4] = thrown(() -> waypoints.edit(worldId, edits -> {
			}));
		}, "Diorama test, not the client thread");
		other.start();
		try {
			other.join(10_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		String[] names = {"world()", "all()", "get()", "revision()", "edit()"};
		for (int i = 0; i < calls.length; i++) {
			String call = calls[i];
			if (call == null) {
				wrong.add(names[i] + " from " + other.getName() + " didn't return within 10 s" + (other.isAlive() ? ", and the thread still runs" : ""));
			} else if (!call.startsWith("IllegalStateException: Diorama's API is only for the client thread")) {
				wrong.add(names[i] + " from " + other.getName() + " threw " + call);
			}
		}
		Diorama.LOGGER.info("addon-api waypoints: from another thread {}", Arrays.toString(calls));
	}

	/**
	 * Diorama's list drops a row an addon deleted while it's open, in the order that used to keep
	 * it: the addon deletes one row, then before the list's next tick the player hides another.
	 * The search box keeps its text, focus and caret through the rows being made again.
	 */
	private static void listScreen(ClientGameTestContext context, List<String> wrong) {
		UUID doomed = addOne(context, "Doomed row", 2, -2);
		UUID other = addOne(context, "Other row", 3, -3);
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(3);
		boolean listed = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).shown().stream().anyMatch(w -> w.id.equals(doomed)));
		Waypoint otherWaypoint = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.id.equals(other)).findFirst().orElseThrow());
		int[] row = select(context, otherWaypoint);
		// In one go on the client, so no tick comes between, whichever loader: the addon deletes, then the player clicks Hide on the other row.
		context.runOnClient(mc -> {
			waypoints().edit(worldId, edits -> edits.remove(doomed));
			DioramaClientTest.press(rowButton(mc.gui.screen(), "diorama.waypoints.hide", row[1]));
		});
		context.waitTicks(3);
		String after = context.computeOnClient(mc -> {
			WaypointListScreen list = (WaypointListScreen) mc.gui.screen();
			return list.shown().stream().anyMatch(w -> w.id.equals(doomed)) + " " + WaypointStore.get().all().stream().filter(w -> w.id.equals(other)).allMatch(w -> !w.visible);
		});
		context.takeScreenshot("addon-api-15-list-after-addon-delete");
		if (!listed || !after.equals("false true")) {
			wrong.add("The waypoint list while an addon deletes a row and the player hides another: listed before " + listed + "; still listed, the other hidden: " + after
				+ " (expected false true)");
		}

		// Typing in the search box while an addon changes the waypoints: the rows are made again, and the box keeps its text, focus and caret.
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.editBoxes(mc.gui.screen()).get(0)));
		context.getInput().typeChars("row");
		context.waitTicks(2);
		context.runOnClient(mc -> DioramaClientTest.editBoxes(mc.gui.screen()).get(0).setCursorPosition(1));
		context.runOnClient(mc -> waypoints().edit(worldId, edits -> edits.update(other, WaypointUpdate.builder().name("Other row renamed").build())));
		context.waitTicks(3);
		String search = context.computeOnClient(mc -> {
			var box = DioramaClientTest.editBoxes(mc.gui.screen()).get(0);
			WaypointListScreen list = (WaypointListScreen) mc.gui.screen();
			return box.getValue() + " " + (mc.gui.screen().getFocused() == box) + " " + box.getCursorPosition() + " "
				+ list.shown().stream().anyMatch(w -> w.name.equals("Other row renamed"));
		});
		if (!search.equals("row true 1 true")) {
			wrong.add("The search box through rows made again: text, focused, caret, renamed row shown " + search + " (expected row true 1 true)");
		}
		context.setScreen(() -> null);
		removeIds(context, List.of(other));
	}

	/**
	 * The map's right-click menu refuses a waypoint an addon deleted: a pin drawn before the delete
	 * gets no menu, and a menu opened before it does nothing when clicked.
	 */
	private static void mapMenu(ClientGameTestContext context, List<String> wrong) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = at.getX() + 4;
		int z = at.getZ() - 4;
		int y = context.computeOnClient(mc -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
		UUID first = context.computeOnClient(mc -> waypoints().edit(worldId, edits -> edits.add(NewWaypoint.builder("Menu pin", DimensionId.OVERWORLD, BlockCoord.of(x, y, z)).build())).added().get(0));
		context.setScreen(MapScreen::new);
		DioramaClientTest.lookAtPlayer(context, 4.0, TileKey.SURFACE);
		DioramaClientTest.settle(context, 20);
		// The pin as drawn; then, before another frame, the addon deletes it and the player right-clicks it.
		String stale = context.computeOnClient(mc -> {
			MapScreen map = (MapScreen) mc.gui.screen();
			WaypointOverlay.Pin pin = map.pins().stream().filter(p -> p.waypoint().id.equals(first)).findFirst().orElse(null);
			if (pin == null) {
				return "no pin drawn";
			}
			waypoints().edit(worldId, edits -> edits.remove(first));
			map.openMenuForTests(pin.x(), pin.y());
			return map.menu() == null ? "no menu" : map.menu().labels().toString();
		});
		context.setScreen(() -> null);

		UUID second = context.computeOnClient(mc -> waypoints().edit(worldId, edits -> edits.add(NewWaypoint.builder("Menu pin two", DimensionId.OVERWORLD, BlockCoord.of(x, y, z)).build())).added().get(0));
		context.setScreen(MapScreen::new);
		DioramaClientTest.lookAtPlayer(context, 4.0, TileKey.SURFACE);
		DioramaClientTest.settle(context, 20);
		DioramaClientTest.rightClickPin(context, new int[] {x, y, z});
		List<String> labels = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).menu() == null ? List.of() : ((MapScreen) mc.gui.screen()).menu().labels());
		String hide = net.minecraft.network.chat.Component.translatable("diorama.waypoints.hide").getString();
		int saves = context.computeOnClient(mc -> WaypointStore.saves());
		String clicked = context.computeOnClient(mc -> {
			MapScreen map = (MapScreen) mc.gui.screen();
			if (map.menu() == null || map.menu().centreOf(hide) == null) {
				return "no Hide in the menu";
			}
			float[] centre = map.menu().centreOf(hide);
			waypoints().edit(worldId, edits -> edits.remove(second));
			map.menu().click(centre[0], centre[1]);
			return "clicked";
		});
		boolean nothing = context.computeOnClient(mc -> WaypointStore.saves() == saves + 1 && WaypointStore.get().all().stream().noneMatch(w -> w.id.equals(second)));
		context.setScreen(() -> null);
		if (stale.equals("no pin drawn") || stale.contains("Menu pin") || !labels.contains("Menu pin two") || !clicked.equals("clicked") || !nothing) {
			wrong.add("The map's menu and a pin an addon deleted: right-clicking the pin drawn before gave " + stale + " (expected no waypoint menu); the menu opened before "
				+ labels + ", Hide after the delete " + clicked + ", nothing saved but the delete and the waypoint still gone " + nothing);
		}
	}

	/**
	 * The editor refuses a waypoint an addon deleted while it was open: its Save closes it without
	 * saving, rather than change a waypoint that's gone or bring it back. (The list's Edit and the
	 * map's pin and menu don't open it for one that's gone.)
	 */
	private static void editor(ClientGameTestContext context, List<String> wrong) {
		Path file = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
		UUID editing = addOne(context, "Edited away", 3, -3);
		Waypoint editingWaypoint = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.id.equals(editing)).findFirst().orElseThrow());
		context.setScreen(() -> new WaypointEditScreen(null, editingWaypoint, false));
		context.waitTicks(3);
		removeIds(context, List.of(editing));
		DioramaClientTest.typeInto(context, 0, "Brought back");
		int saves = context.computeOnClient(mc -> WaypointStore.saves());
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.waitTicks(3);
		int saved = context.computeOnClient(mc -> WaypointStore.saves()) - saves;
		boolean closed = context.computeOnClient(mc -> mc.gui.screen() == null);
		boolean back = context.computeOnClient(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.id.equals(editing) || w.name.equals("Brought back")));
		boolean inFile = text(file).contains("Brought back");
		if (saved != 0 || !closed || back || inFile) {
			wrong.add("The editor's Save on a waypoint an addon deleted while it was open: " + saved
				+ " saves (expected 0), closed " + closed + ", back in the store " + back + ", in the file " + inFile);
		}
		context.setScreen(() -> null);
	}

	/**
	 * The test addon's polling sync with its pretend map mod: add, rename, hide, move to a folder
	 * and delete, each way, reach the other side within a few ticks; then, ticked on with nothing
	 * changing, it makes no edit and no save, and the revision and the other mod stay put.
	 */
	private static void sync(ClientGameTestContext context, List<String> wrong) {
		PollingSync sync = context.computeOnClient(mc -> TestAddon.sync());
		OtherMod other = sync.other();
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		tick(context, sync, 3);
		List<String> steps = new ArrayList<>();

		// Diorama to the other mod.
		Waypoint mine = context.computeOnClient(mc -> {
			Waypoint w = new Waypoint("Synced here", at.getX() + 6, at.getY(), at.getZ() + 6, "minecraft:overworld", Waypoint.PALETTE[1]);
			WaypointStore.get().add(w);
			return w;
		});
		tick(context, sync, 3);
		steps.add("added in Diorama " + (other.named("Synced here") != null));
		context.runOnClient(mc -> {
			mine.name = "Synced renamed";
			WaypointStore.get().changed();
		});
		tick(context, sync, 3);
		String theirs = other.named("Synced renamed");
		steps.add("renamed in Diorama " + (theirs != null));
		context.runOnClient(mc -> WaypointStore.get().setVisible(mine, false));
		tick(context, sync, 3);
		steps.add("hidden in Diorama " + (theirs != null && other.pins().get(theirs) != null && !other.pins().get(theirs).visible()));
		context.runOnClient(mc -> {
			WaypointStore.get().addFolder("Sync folder");
			mine.folder = "Sync folder";
			WaypointStore.get().changed();
		});
		tick(context, sync, 3);
		steps.add("moved to a folder in Diorama " + (theirs != null && other.pins().get(theirs) != null && other.pins().get(theirs).folder().equals("Sync folder")));
		context.runOnClient(mc -> WaypointStore.get().remove(mine));
		tick(context, sync, 3);
		steps.add("deleted in Diorama " + (theirs != null && !other.pins().containsKey(theirs)));

		// The other mod to Diorama.
		String id = context.computeOnClient(mc -> other.add(new OtherMod.Pin("From other", at.getX() - 6, at.getY(), at.getZ() - 6, "minecraft:overworld", 0x46A758, true, "")));
		tick(context, sync, 3);
		Optional<dev.diorama.api.Waypoint> copy = context.computeOnClient(mc -> waypoints().all().stream().filter(w -> w.name().equals("From other")).findFirst());
		steps.add("added in the other mod " + copy.map(w -> w.addedBy().orElse("") + " " + Integer.toHexString(w.rgb())).orElse("missing"));
		UUID copyId = copy.map(dev.diorama.api.Waypoint::id).orElse(null);
		context.runOnClient(mc -> other.put(id, other.pins().get(id).renamed("Other renamed")));
		tick(context, sync, 3);
		steps.add("renamed in the other mod " + context.computeOnClient(mc -> copyId != null && waypoints().get(copyId).map(w -> w.name().equals("Other renamed")).orElse(false)));
		context.runOnClient(mc -> other.put(id, other.pins().get(id).shown(false)));
		tick(context, sync, 3);
		steps.add("hidden in the other mod " + context.computeOnClient(mc -> copyId != null && waypoints().get(copyId).map(w -> !w.visible()).orElse(false)));
		context.runOnClient(mc -> other.put(id, other.pins().get(id).inFolder("Other folder")));
		tick(context, sync, 3);
		steps.add("moved to a folder in the other mod " + context.computeOnClient(mc -> copyId != null
			&& waypoints().get(copyId).flatMap(dev.diorama.api.Waypoint::folder).map(f -> f.equals("Other folder")).orElse(false)));

		// Nothing changing: no echo of its own edits, no work.
		// (How many times it compared is only logged: an automatic pin found meanwhile, a portal's say, moves the revision with nothing to sync.)
		int passes = context.computeOnClient(mc -> sync.passes());
		String idle = context.computeOnClient(mc -> sync.edits() + " " + other.version() + " " + WaypointStore.saves());
		tick(context, sync, 20);
		String idleAfter = context.computeOnClient(mc -> sync.edits() + " " + other.version() + " " + WaypointStore.saves());
		int idlePasses = context.computeOnClient(mc -> sync.passes()) - passes;

		context.runOnClient(mc -> other.remove(id));
		tick(context, sync, 3);
		steps.add("deleted in the other mod " + context.computeOnClient(mc -> copyId != null && waypoints().get(copyId).isEmpty()));
		List<String> expected = List.of("added in Diorama true", "renamed in Diorama true", "hidden in Diorama true", "moved to a folder in Diorama true", "deleted in Diorama true",
			"added in the other mod diorama_test_addon 46a758", "renamed in the other mod true", "hidden in the other mod true", "moved to a folder in the other mod true",
			"deleted in the other mod true");
		if (!steps.equals(expected) || !idle.equals(idleAfter) || !sync.lastProblem().isEmpty()) {
			wrong.add("The polling sync: " + steps + ", expected " + expected + "; its edits, the other mod's version and saves before 20 idle ticks " + idle + ", after " + idleAfter
				+ "; problem " + sync.lastProblem());
		}
		Diorama.LOGGER.info("addon-api waypoints: polling sync {}; {} edits in all; 20 idle ticks: {} > {}, {} comparisons", steps, sync.edits(), idle, idleAfter, idlePasses);
		context.runOnClient(mc -> {
			WaypointStore.get().deleteFolder("Sync folder");
			WaypointStore.get().deleteFolder("Other folder");
		});
		tick(context, sync, 3);
	}

	/** Ticks the sync from its own tick, as an addon's client tick would, once a game tick. */
	private static void tick(ClientGameTestContext context, PollingSync sync, int ticks) {
		for (int i = 0; i < ticks; i++) {
			context.runOnClient(mc -> sync.tick());
			context.waitTicks(1);
		}
	}

	/** The addon's waypoint goes, so the waypoints section, which runs in this world next, counts only its own. */
	private static void cleanUp(ClientGameTestContext context, List<String> wrong) {
		removeNamed(context, FROM_ADDON);
		long left = context.computeOnClient(mc -> waypoints().all().stream().filter(w -> w.addedBy().isPresent()).count());
		if (left != 0) {
			wrong.add("Waypoints added by addons left in the world after the section: " + left);
		}
	}

	/** At the title screen after leaving: no world, so nothing to read, and an edit for the world left throws. */
	static void left(ClientGameTestContext context) {
		String read = context.computeOnClient(mc -> api().world() + " " + waypoints().all().size() + " " + waypoints().revision() + " "
			+ thrown(() -> waypoints().edit(worldId, edits -> {
			})));
		if (!read.startsWith("Optional.empty 0 0 IllegalStateException: No world is open")) {
			throw new AssertionError("After leaving: world(), all() size, revision(), an edit for the world left: " + read);
		}
		Diorama.LOGGER.info("addon-api waypoints: after leaving, {}", read);
	}

	/** In the world opened again: the same ID, and nothing the section added. */
	static void reopened(ClientGameTestContext context) {
		context.setScreen(() -> null);
		context.waitFor(mc -> MapSession.get() != null && api().world().isPresent(), 2400);
		WorldId again = context.computeOnClient(mc -> api().world().orElseThrow().worldId());
		long added = context.computeOnClient(mc -> waypoints().all().stream().filter(w -> w.addedBy().isPresent()).count());
		if (!again.equals(worldId) || added != 0) {
			throw new AssertionError("In the world opened again: its ID " + again + " (first time " + worldId + "), waypoints added by addons " + added);
		}
		Diorama.LOGGER.info("addon-api waypoints: the world opened again as {}", again);
	}

	//? if >=1.21.6 && fabric {
	private static boolean syncTicking;
	private static boolean syncHooked;

	/**
	 * Bytes the minimap's and the world waypoints' drawing allocate a frame with three waypoints in
	 * view, the world paused: with the test addon's polling sync ticking from the client tick, then
	 * without it, then with it again, so the order they're measured in shows apart from the sync.
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
			if (!syncHooked) {
				syncHooked = true;
				net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
					if (syncTicking) {
						TestAddon.sync().tick();
					}
				});
			}
			syncTicking = true;
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
		try (WorldPause paused = WorldPause.pause(context)) {
			AllocProbe.MINIMAP.count(context, "warm-up", 150);
			AllocProbe.WAYPOINTS.count(context, "warm-up", 150);
			int passes = context.computeOnClient(mc -> TestAddon.sync().passes());
			long minimapPolling = AllocProbe.MINIMAP.count(context, "polling sync ticking", 200);
			long waypointsPolling = AllocProbe.WAYPOINTS.count(context, "polling sync ticking", 200);
			int passesWhile = context.computeOnClient(mc -> TestAddon.sync().passes()) - passes;
			context.runOnClient(mc -> syncTicking = false);
			long minimapNone = AllocProbe.MINIMAP.count(context, "no addon polling", 200);
			long waypointsNone = AllocProbe.WAYPOINTS.count(context, "no addon polling", 200);
			context.runOnClient(mc -> syncTicking = true);
			long minimapAgain = AllocProbe.MINIMAP.count(context, "polling again", 200);
			long waypointsAgain = AllocProbe.WAYPOINTS.count(context, "polling again", 200);
			Diorama.LOGGER.info("addon-api alloc: medians a frame: minimap {} polling, {} not, {} polling again; world waypoints {} polling, {} not, {} polling again; sync passes while idle {}",
				minimapPolling, minimapNone, minimapAgain, waypointsPolling, waypointsNone, waypointsAgain, passesWhile);
		}
		context.runOnClient(mc -> {
			syncTicking = false;
			pins.forEach(WaypointStore.get()::remove);
			TestAddon.sync().tick();
		});
	}
	//?}

	/**
	 * Bytes and time of reading and editing with 1,000 waypoints, logged: world(), all(), get() and
	 * revision() over 100,000 calls each with nothing changed, and one batch of 10 renames.
	 */
	static void measure(ClientGameTestContext context) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		WorldId id = context.computeOnClient(mc -> api().world().orElseThrow().worldId());
		List<UUID> ids = context.computeOnClient(mc -> waypoints().edit(id, edits -> {
			for (int i = 0; i < 1000; i++) {
				edits.add(NewWaypoint.builder("Many " + i, DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 2000 + i, 70, at.getZ() + 2000)).build());
			}
		}).added());
		Diorama.LOGGER.info("addon-api measure: {}", readCost(context, ids));
		Diorama.LOGGER.info("addon-api measure: one batch of 10 renames with 1,000 waypoints: {}", editCost(context, id, ids));
		String save = context.computeOnClient(mc -> {
			long[] nanos = new long[50];
			for (int round = 0; round < 50; round++) {
				long start = System.nanoTime();
				WaypointStore.get().changed();
				nanos[round] = System.nanoTime() - start;
			}
			long[] n = Arrays.copyOfRange(nanos, 10, 50);
			Arrays.sort(n);
			return String.format(Locale.ROOT, "median %.2f ms (%.2f to %.2f)", n[n.length / 2] / 1e6, n[0] / 1e6, n[n.length - 1] / 1e6);
		});
		Diorama.LOGGER.info("addon-api measure: Diorama's own changed() with 1,000 waypoints (one save): {}", save);
		context.runOnClient(mc -> waypoints().edit(id, edits -> ids.forEach(edits::remove)));
	}

	/** Bytes a call of world(), all(), get() and revision() with nothing changed, over 100,000 calls each after 20,000 to warm up. */
	private static String readCost(ClientGameTestContext context, List<UUID> ids) {
		return context.computeOnClient(mc -> {
			DioramaApi api = api();
			dev.diorama.api.Waypoints waypoints = api.waypoints();
			com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			UUID probe = ids.get(500);
			long sink = 0;
			for (int i = 0; i < 20_000; i++) {
				sink += waypoints.all().size() + (waypoints.get(probe).isPresent() ? 1 : 0) + waypoints.revision() + (api.world().isPresent() ? 1 : 0);
			}
			int calls = 100_000;
			long empty = threads.getCurrentThreadAllocatedBytes();
			empty = threads.getCurrentThreadAllocatedBytes() - empty;
			long start = threads.getCurrentThreadAllocatedBytes();
			for (int i = 0; i < calls; i++) {
				sink += api.world().isPresent() ? 1 : 0;
			}
			long world = threads.getCurrentThreadAllocatedBytes() - start - empty;
			start = threads.getCurrentThreadAllocatedBytes();
			for (int i = 0; i < calls; i++) {
				sink += waypoints.all().size();
			}
			long all = threads.getCurrentThreadAllocatedBytes() - start - empty;
			start = threads.getCurrentThreadAllocatedBytes();
			for (int i = 0; i < calls; i++) {
				sink += waypoints.get(probe).isPresent() ? 1 : 0;
			}
			long get = threads.getCurrentThreadAllocatedBytes() - start - empty;
			start = threads.getCurrentThreadAllocatedBytes();
			for (int i = 0; i < calls; i++) {
				sink += waypoints.revision();
			}
			long revision = threads.getCurrentThreadAllocatedBytes() - start - empty;
			return String.format(Locale.ROOT, "%d waypoints, bytes over %d calls each: world() %d, all() %d, get() %d, revision() %d (counter alone %d; sink %d)",
				waypoints.all().size(), calls, world, all, get, revision, empty, sink);
		});
	}

	/** Median time and bytes of one batch of 10 renames, over 40 batches after 10 to warm up. */
	private static String editCost(ClientGameTestContext context, WorldId id, List<UUID> ids) {
		return context.computeOnClient(mc -> {
			dev.diorama.api.Waypoints waypoints = waypoints();
			com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			long[] nanos = new long[50];
			long[] bytes = new long[50];
			for (int round = 0; round < 50; round++) {
				int r = round;
				long startBytes = threads.getCurrentThreadAllocatedBytes();
				long start = System.nanoTime();
				waypoints.edit(id, edits -> {
					for (int i = 0; i < 10; i++) {
						edits.update(ids.get(r * 10 + i), WaypointUpdate.builder().name("Renamed " + r + " " + i).build());
					}
				});
				// What a sync does next: reads the list again.
				waypoints.all();
				nanos[round] = System.nanoTime() - start;
				bytes[round] = threads.getCurrentThreadAllocatedBytes() - startBytes;
			}
			long[] n = Arrays.copyOfRange(nanos, 10, 50);
			long[] b = Arrays.copyOfRange(bytes, 10, 50);
			Arrays.sort(n);
			Arrays.sort(b);
			return String.format(Locale.ROOT, "median %.2f ms (%.2f to %.2f), %d bytes (%d to %d), the save and all() read after included",
				n[n.length / 2] / 1e6, n[0] / 1e6, n[n.length - 1] / 1e6, b[b.length / 2], b[0], b[b.length - 1]);
		});
	}

	/** Adds one waypoint through the test addon, this far east and south of the player, and returns its ID. */
	private static UUID addOne(ClientGameTestContext context, String name, int east, int south) {
		return context.computeOnClient(mc -> {
			BlockPos at = mc.player.blockPosition();
			return waypoints().edit(worldId, edits -> edits.add(NewWaypoint.builder(name, DimensionId.OVERWORLD, BlockCoord.of(at.getX() + east, at.getY(), at.getZ() + south)).build()))
				.added().get(0);
		});
	}

	private static void removeIds(ClientGameTestContext context, List<UUID> ids) {
		context.runOnClient(mc -> waypoints().edit(worldId, edits -> ids.forEach(edits::remove)));
	}

	/** Removes every one of the store's waypoints whose name starts so. */
	private static void removeNamed(ClientGameTestContext context, String prefix) {
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.name.startsWith(prefix)).toList().forEach(store::remove);
		});
	}

	private static boolean running(String modId) {
		return Addons.listed().stream().anyMatch(addon -> addon.modId().equals(modId) && addon.running());
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
	 * An addon the test makes itself ({@link Addons#foundForTests}): it keeps the API it's handed,
	 * and does nothing else.
	 */
	static final class Probe {
		final String modId;
		DioramaApi api;

		private Probe(String name) {
			this.modId = "diorama_test_probe_" + name;
		}

		static Probe make(ClientGameTestContext context, String name) {
			Probe probe = new Probe(name);
			context.runOnClient(mc -> Addons.foundForTests(new Addons.Found(probe.modId, "Probe " + name, () -> api -> probe.api = api)));
			if (context.computeOnClient(mc -> probe.api == null)) {
				throw new AssertionError("The probe " + probe.modId + " wasn't made");
			}
			return probe;
		}

		/** Drops it from Diorama's addons, so later sections and the settings' Addons tab don't see it. */
		void forget(ClientGameTestContext context) {
			context.runOnClient(mc -> Addons.forgetForTests(this.modId));
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
