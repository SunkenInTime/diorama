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
import dev.diorama.api.WaypointBatch;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Waystones change, not for a Waystones list sent again as it was, and not for a batch that
 * changes nothing; a waystone placed again shows through the API with nothing drawn;
 * {@code world()} moves with the dimension and the saved maps, and lists them; a batch prepared
 * for another world or saved map, or for a revision gone, applies nothing (two waypoints of one
 * ID, the first deleted before the batch arrives, included), and {@code revision()} moves as
 * automatic pins appear, move, hide and show; a step naming a waypoint {@code all()} doesn't list
 * throws; bad values throw while the batch is built; a disk that refuses the write reports
 * {@code saved() == false}, and a refused folders.json is written by the next save; a folder
 * folders.json doesn't have is made only by a step that names it, and taking a waypoint out of
 * one is saved; automatic pins are read-only; a file with two waypoints of one ID shows only the
 * first; every call from another thread throws; Diorama's list drops a row an addon deleted, even
 * after the player clicked another row first, and its Delete never moves to a twin with the same
 * ID; the map's menu refuses a waypoint or waystone that's gone, and the editor saves only what
 * the player changed in it, each coordinate on its own; and the test addon's polling sync keeps a
 * pretend map mod in step both ways without echoes, through a failed save, a waypoint ID on two
 * saved maps or two dimensions, a refused batch, automatic pins appearing mid-pass, a saved map
 * deleted and names too long for Diorama. Everything the section adds it takes away again, as the waypoints section runs in this
 * world next. After leaving ({@link #left}) and
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

	/** Applies a batch for the world and revision open now, as an addon that read them and built the batch in one tick does. Client thread. */
	private static EditResult apply(WaypointBatch.Builder batch) {
		return waypoints().edit(api().world().orElseThrow(), waypoints().revision(), batch.build());
	}

	private static WaypointBatch.Builder batch() {
		return WaypointBatch.builder();
	}

	/** A world no player is in. */
	private static World nowhere() {
		return new World(WorldId.of("0123456789abcdef0123456789abcdef"), DimensionId.OVERWORLD, Map.of(), Map.of());
	}

	/** At the title screen: no world, no waypoints, revision 0, and a batch for any world applies nothing. */
	static void titleScreen(ClientGameTestContext context) {
		String read = context.computeOnClient(mc -> {
			EditResult result = waypoints().edit(nowhere(), 0, batch().add(NewWaypoint.builder("Nowhere", DimensionId.OVERWORLD, BlockCoord.of(0, 64, 0)).build()).build());
			return api().world() + " " + waypoints().all() + " " + waypoints().revision() + " applied " + result.applied() + " changed " + result.changed() + " added "
				+ result.added();
		});
		if (!read.equals("Optional.empty [] 0 applied false changed false added []")) {
			throw new AssertionError("At the title screen: world, all(), revision() and a batch read " + read);
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
		staleRevision(context, wrong);
		badValues(context, wrong);
		diskFull(context, wrong);
		foldersOwed(context, wrong);
		danglingFolders(context, wrong);
		readOnly(context, wrong);
		duplicates(context, wrong);
		threads(context, wrong);
		listScreen(context, wrong);
		listTwins(context, wrong);
		mapMenu(context, wrong);
		editor(context, wrong);
		sync(context, wrong);
		syncFailedSave(context, wrong);
		syncSavedMaps(context, wrong);
		syncSameIdElsewhere(context, wrong);
		syncAutomaticPins(context, wrong);
		syncTooLong(context, wrong);
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
		// World's own checks: a null dimension is refused even paired with MAIN, which it doesn't keep.
		String nullKey = context.computeOnClient(mc -> {
			Map<DimensionId, MapId> maps = new HashMap<>();
			maps.put(null, MapId.MAIN);
			return thrown(() -> new World(worldId, DimensionId.OVERWORLD, maps, Map.of()));
		});
		if (!nullKey.startsWith("NullPointerException")) {
			wrong.add("new World with a null dimension paired with MAIN: " + nullKey + " (expected NullPointerException)");
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
			EditResult result = apply(batch().add(NewWaypoint.builder(FROM_ADDON, DimensionId.OVERWORLD, BlockCoord.of(hx, hy, hz)).rgb(PIN).initials("TA").build()));
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
		List<UUID> three = context.computeOnClient(mc -> {
			WaypointBatch.Builder batch = batch();
			for (String folder : List.of("Batch folder A", "Batch folder B", "Batch folder C")) {
				batch.add(NewWaypoint.builder("In " + folder, DimensionId.OVERWORLD, BlockCoord.of(hx + 6, hy, hz)).folder(folder).build());
			}
			return apply(batch).added();
		});
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
			WaypointBatch.Builder batch = batch();
			three.forEach(batch::remove);
			apply(batch);
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
		// Placed again where it stood, which no event says. Read through the API alone, with nothing drawing the waystones: no screen, and the minimap
		// and world waypoints hidden.
		dev.diorama.api.Handle hud = context.computeOnClient(mc -> TestAddon.hide(Hud.Part.MINIMAP, Hud.Part.WORLD_WAYPOINTS));
		long beforePlaced = revision(context);
		context.runOnClient(mc -> Waystones.pickedUpForTests(stone[0], true));
		context.waitTicks(3);
		String placedAgain = context.computeOnClient(mc -> "revision moved " + (waypoints().revision() != beforePlaced) + ", in all() " + waypoints().get(stone[0]).isPresent());
		context.runOnClient(mc -> hud.close());
		results.add("a waystone placed again, read through the API with nothing drawn: " + placedAgain + (placedAgain.equals("revision moved true, in all() true") ? " ok" : " WRONG"));
		context.runOnClient(mc -> Waystones.clear());
		// Waystones sends a kind's whole list again, as on every join: the same list moves nothing; one waystone renamed does. Sharestones, as
		// with the Waystones mod installed Diorama drops ordinary waystones that mod doesn't know of, about once a second.
		UUID[] listed = new UUID[2];
		context.runOnClient(mc -> {
			List<Waypoint> sent = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				Waypoint w = new Waypoint("Listed stone " + i, at.getX() - 7 - i, at.getY(), at.getZ() + 9, "minecraft:overworld", 0xFF8E4EC6);
				listed[i] = w.id;
				sent.add(w);
			}
			Waystones.listForTests("waystones:white_sharestone", sent);
		});
		check(context, results, "a Waystones list sent again as it was", false, mc -> Waystones.listForTests("waystones:white_sharestone", List.of(
			stoneLike(listed[0], "Listed stone 0", at.getX() - 7, at.getY(), at.getZ() + 9), stoneLike(listed[1], "Listed stone 1", at.getX() - 8, at.getY(), at.getZ() + 9))));
		check(context, results, "a Waystones list sent again with one renamed", true, mc -> Waystones.listForTests("waystones:white_sharestone", List.of(
			stoneLike(listed[0], "Listed stone renamed", at.getX() - 7, at.getY(), at.getZ() + 9), stoneLike(listed[1], "Listed stone 1", at.getX() - 8, at.getY(), at.getZ() + 9))));
		context.runOnClient(mc -> Waystones.clear());

		// A batch that changes nothing: the name it has, folder in other capitals, the colour it has.
		UUID own = context.computeOnClient(mc -> apply(batch().add(NewWaypoint.builder("Same as ever", DimensionId.OVERWORLD,
			BlockCoord.of(at.getX(), at.getY(), at.getZ() + 4)).folder("Kept folder").build())).added().get(0));
		String noop = context.computeOnClient(mc -> {
			long before = waypoints().revision();
			int saves = WaypointStore.saves();
			int folderSaves = WaypointStore.folderSaves();
			EditResult result = apply(batch().update(own, WaypointUpdate.builder().name("Same as ever").folder("kept FOLDER")
				.rgb(waypoints().get(own).orElseThrow().rgb()).visible(true).build()));
			return "changed " + result.changed() + ", saved " + result.saved() + ", added " + result.added() + ", revision moved " + (waypoints().revision() != before)
				+ ", saves " + (WaypointStore.saves() - saves) + ", folder saves " + (WaypointStore.folderSaves() - folderSaves);
		});
		if (!noop.equals("changed false, saved true, added [], revision moved false, saves 0, folder saves 0")) {
			wrong.add("A batch that changes nothing: " + noop);
		}
		// Renamed and back, and moved through a new folder and back, in one batch: nothing changed, so no save, no folder made, the revision put, and it says so.
		String there = context.computeOnClient(mc -> {
			long before = waypoints().revision();
			int saves = WaypointStore.saves();
			int folderSaves = WaypointStore.folderSaves();
			EditResult result = apply(batch().update(own, WaypointUpdate.builder().name("Renamed for a moment").folder("Passing folder").build())
				.update(own, WaypointUpdate.builder().name("Same as ever").folder("Kept folder").build()));
			return "changed " + result.changed() + ", revision moved " + (waypoints().revision() != before) + ", saves " + (WaypointStore.saves() - saves) + ", folder saves "
				+ (WaypointStore.folderSaves() - folderSaves) + ", passing folder made " + (WaypointStore.get().folderName("Passing folder") != null);
		});
		if (!there.equals("changed false, revision moved false, saves 0, folder saves 0, passing folder made false")) {
			wrong.add("A batch that renames a waypoint and back: " + there + " (expected changed false, revision moved false, saves 0, folder saves 0, passing folder made false)");
		}

		// A switch of saved map: world() names the new map, all() lists its waypoints, revision moves; and back.
		String key = context.computeOnClient(mc -> WorldKey.current());
		long beforeSwitch = revision(context);
		World mainWorld = context.computeOnClient(mc -> api().world().orElseThrow());
		String second = context.computeOnClient(mc -> Maps.of(key).create("minecraft:overworld", "Second"));
		context.waitFor(mc -> MapSession.get() != null, 2400);
		context.waitTicks(2);
		String onSecond = context.computeOnClient(mc -> api().world().orElseThrow().mapId() + " " + (waypoints().revision() != beforeSwitch) + " "
			+ waypoints().get(own).isPresent());
		String listedMaps = context.computeOnClient(mc -> api().world().orElseThrow().mapIds(DimensionId.OVERWORLD).toString());
		context.runOnClient(mc -> Maps.of(key).use("minecraft:overworld", Maps.MAIN));
		context.waitFor(mc -> MapSession.get() != null, 2400);
		context.waitTicks(2);
		String backHome = context.computeOnClient(mc -> api().world().orElseThrow().equals(mainWorld) + " " + waypoints().get(own).isPresent());
		if (second == null || !onSecond.equals(second + " true false") || !backHome.equals("false true") || !listedMaps.equals("[main, " + second + "]")) {
			wrong.add("A switch of saved map: on the second, map ID, revision moved, the main map's waypoint listed: " + onSecond + " (expected " + second
				+ " true false); the saved maps listed " + listedMaps + "; back on the main, world() as before (no: it lists the second map now) and the waypoint listed: "
				+ backHome);
		}
		context.runOnClient(mc -> Maps.of(key).delete("minecraft:overworld", second));
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		DioramaClientTest.settle(context, 40);
		String afterDelete = context.computeOnClient(mc -> api().world().orElseThrow().mapIds(DimensionId.OVERWORLD) + " " + api().world().orElseThrow().equals(mainWorld));
		if (!afterDelete.equals("[main] true")) {
			wrong.add("After the second saved map was deleted, world() lists " + afterDelete + " (expected [main] true: the same as before it was made)");
		}

		for (String result : results) {
			if (!result.endsWith(" ok")) {
				wrong.add("revision() when " + result);
			}
		}
		Diorama.LOGGER.info("addon-api waypoints: revision() {}; a batch that changes nothing: {}", results, noop);
		context.runOnClient(mc -> {
			apply(batch().remove(own));
			WaypointStore.get().deleteFolder("Kept folder");
			WaypointStore.get().remove(mine);
		});
	}

	/** A waystone as Waystones would send it again: a new waypoint with these values. */
	private static Waypoint stoneLike(UUID id, String name, int x, int y, int z) {
		Waypoint w = new Waypoint(name, x, y, z, "minecraft:overworld", 0xFF8E4EC6);
		w.id = id;
		return w;
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
			apply(batch().update(id, WaypointUpdate.builder().initials("SL").build()));
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
	 * world() is empty while Diorama doesn't know which world it is, and equal again after. A batch
	 * prepared for another world, for no world, or on one saved map and handed over after the
	 * player switched to another applies nothing, says so, and saves nothing: the removal it holds
	 * finds nothing on either map and the waypoint it adds lands on neither.
	 */
	private static void staleWorlds(ClientGameTestContext context, List<String> wrong) {
		Path file = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		// A waypoint get() finds while the world is known, so its lookup while it isn't means something.
		UUID known = addOne(context, "Known before", 2, 9);
		String unknown = context.computeOnClient(mc -> {
			World before = api().world().orElseThrow();
			long revisionBefore = waypoints().revision();
			boolean foundBefore = waypoints().get(known).isPresent();
			WorldKey.unknownForTests = true;
			try {
				EditResult result = waypoints().edit(before, revisionBefore, batch().add(NewWaypoint.builder("Ghost", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ())).build()).build());
				return api().world().isPresent() + " " + waypoints().all().size() + " " + waypoints().revision() + " " + foundBefore + ">" + waypoints().get(known).isPresent() + " "
					+ result.applied();
			} finally {
				WorldKey.unknownForTests = false;
			}
		});
		boolean again = context.computeOnClient(mc -> api().world().map(w -> w.worldId().equals(worldId)).orElse(false));
		if (!unknown.equals("false 0 0 true>false false") || !again) {
			wrong.add("While the world isn't known: world() present, all() size, revision(), get() of a known waypoint before>while, a batch applied: " + unknown
				+ " (expected false 0 0 true>false false); known again " + again);
		}
		removeIds(context, List.of(known));

		String elsewhere = context.computeOnClient(mc -> {
			EditResult result = waypoints().edit(nowhere(), waypoints().revision(), batch().add(NewWaypoint.builder("Ghost", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ())).build()).build());
			return result.applied() + " " + result.changed() + " " + result.added();
		});

		// Prepared on the main map: a removal and a replacement. Then the player switches to another saved map, and the batch arrives.
		UUID onMain = addOne(context, "On the main map", 1, 9);
		String key = context.computeOnClient(mc -> WorldKey.current());
		World prepared = context.computeOnClient(mc -> api().world().orElseThrow());
		long preparedRevision = revision(context);
		WaypointBatch batch = batch().remove(onMain).add(NewWaypoint.builder("Replacement", DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 1, at.getY(), at.getZ() + 9)).build()).build();
		String other = context.computeOnClient(mc -> Maps.of(key).create("minecraft:overworld", "Stale batch map"));
		context.waitFor(mc -> MapSession.get() != null, 2400);
		context.waitTicks(2);
		int savesSwitch = context.computeOnClient(mc -> WaypointStore.saves());
		String late = context.computeOnClient(mc -> {
			EditResult result = waypoints().edit(prepared, preparedRevision, batch);
			return "applied " + result.applied() + ", changed " + result.changed() + ", added " + result.added() + ", saved " + result.saved();
		});
		int lateSaves = context.computeOnClient(mc -> WaypointStore.saves()) - savesSwitch;
		boolean replacementOnOther = context.computeOnClient(mc -> waypoints().all().stream().anyMatch(w -> w.name().equals("Replacement")));
		context.runOnClient(mc -> Maps.of(key).use("minecraft:overworld", Maps.MAIN));
		context.waitFor(mc -> MapSession.get() != null, 2400);
		context.waitTicks(2);
		String onMainAfter = context.computeOnClient(mc -> waypoints().get(onMain).isPresent() + " " + waypoints().all().stream().anyMatch(w -> w.name().equals("Replacement")));
		context.runOnClient(mc -> Maps.of(key).delete("minecraft:overworld", other));
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		DioramaClientTest.settle(context, 40);
		boolean ghost = text(file).contains("Ghost") || text(file).contains("Replacement");
		if (!elsewhere.equals("false false []") || !late.equals("applied false, changed false, added [], saved true") || lateSaves != 0 || replacementOnOther
			|| !onMainAfter.equals("true false") || ghost) {
			wrong.add("Batches for a world not open: for another world applied, changed, added " + elsewhere + " (expected false false []); prepared on the main map and handed over"
				+ " on another: " + late + " (expected applied false, changed false, added [], saved true), " + lateSaves + " saves, the replacement on the other map "
				+ replacementOnOther + "; back on the main, its waypoint still there and no replacement: " + onMainAfter + " (expected true false); in the file " + ghost);
		}
		Diorama.LOGGER.info("addon-api waypoints: a batch for another world {}; prepared on one saved map and handed over on another: {}", elsewhere, late);
		removeIds(context, List.of(onMain));
	}

	/**
	 * A batch is applied only for the revision it was worked out from. Two stored waypoints share
	 * an ID, as some players' files have, so all() shows the first. The addon reads it and prepares
	 * its removal; the player deletes it; all() now shows the second, with the world unchanged. The
	 * batch applies nothing, and the second is still in waypoints.json, read back from disk and
	 * when the store is read again. Then: revision() moves when an automatic pin appears, moves,
	 * is hidden or shown (portal pins switched off and on, one deleted by the player), and a
	 * step naming a waypoint all() never listed, or one an earlier step removes, throws and applies
	 * nothing.
	 */
	private static void staleRevision(ClientGameTestContext context, List<String> wrong) {
		Path file = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		Waypoint[] twins = context.computeOnClient(mc -> {
			Waypoint one = new Waypoint("Stale twin A", at.getX() + 13, at.getY(), at.getZ(), "minecraft:overworld", Waypoint.PALETTE[2]);
			Waypoint two = new Waypoint("Stale twin B", at.getX() + 14, at.getY(), at.getZ(), "minecraft:overworld", Waypoint.PALETTE[2]);
			two.id = one.id;
			WaypointStore.get().add(one);
			WaypointStore.get().add(two);
			return new Waypoint[] {one, two};
		});
		UUID id = twins[0].id;
		// The addon's tick: it reads the world, the revision and the waypoint, and works out the removal.
		World read = context.computeOnClient(mc -> api().world().orElseThrow());
		long revision = revision(context);
		String seen = context.computeOnClient(mc -> waypoints().get(id).map(dev.diorama.api.Waypoint::name).orElse("none"));
		WaypointBatch removal = batch().remove(id).build();
		// Meanwhile the player deletes the one the addon saw.
		context.runOnClient(mc -> WaypointStore.get().remove(twins[0]));
		String result = context.computeOnClient(mc -> {
			boolean worldSame = api().world().orElseThrow().equals(read);
			String shownNow = waypoints().get(id).map(dev.diorama.api.Waypoint::name).orElse("none");
			EditResult edit = waypoints().edit(read, revision, removal);
			return "world still equal " + worldSame + ", all() shows " + shownNow + " now; applied " + edit.applied() + ", changed " + edit.changed();
		});
		boolean onDisk = text(file).contains("\"Stale twin B\"");
		String reread = context.computeOnClient(mc -> {
			WaypointStore.onLeave();
			return WaypointStore.get().all().stream().filter(w -> w.id.equals(id)).map(w -> w.name).toList().toString();
		});
		String expected = "world still equal true, all() shows Stale twin B now; applied false, changed false";
		if (!seen.equals("Stale twin A") || !result.equals(expected) || !onDisk || !reread.equals("[Stale twin B]")) {
			wrong.add("A removal prepared for one of two waypoints with one ID, handed over after the player deleted it: the addon saw " + seen + "; " + result + " (expected "
				+ expected + "); the other in waypoints.json " + onDisk + ", after reading it again " + reread + " (expected true, [Stale twin B])");
		}
		Diorama.LOGGER.info("addon-api waypoints: a batch for a revision gone: saw {}, {}; on disk {}, read again {}", seen, result, onDisk, reread);
		removeNamed(context, "Stale twin");

		// Automatic pins, far off so no chunk the player sees takes them away.
		List<String> results = new ArrayList<>();
		Waypoint[] pins = new Waypoint[3];
		check(context, results, "a death pin added", true, mc -> {
			pins[0] = new Waypoint("Stale death", at.getX() + 5000, at.getY(), at.getZ(), "minecraft:overworld", 0xFFFFFFFF);
			pins[0].kind = Waypoint.Kind.DEATH;
			WaypointStore.get().add(pins[0]);
		});
		check(context, results, "a death pin hidden", true, mc -> WaypointStore.get().setVisible(pins[0], false));
		check(context, results, "a death pin shown", true, mc -> WaypointStore.get().setVisible(pins[0], true));
		check(context, results, "a bed pin added", true, mc -> {
			pins[1] = new Waypoint(Waypoint.BED_NAME, at.getX() + 5001, at.getY(), at.getZ(), "minecraft:overworld", 0xFFE5484D);
			pins[1].kind = Waypoint.Kind.BED;
			WaypointStore.get().add(pins[1]);
		});
		check(context, results, "a bed pin moved", true, mc -> {
			pins[1].x++;
			WaypointStore.get().changed();
		});
		// Portal pins are off in the test settings, and a hidden pin isn't in all(): on for these.
		boolean portalPins = context.computeOnClient(mc -> dev.diorama.Settings.get().portalMarkers);
		try {
			context.runOnClient(mc -> dev.diorama.Settings.get().portalMarkers = true);
			check(context, results, "a portal pin added", true, mc -> {
				pins[2] = new Waypoint("Nether Portal", at.getX() + 5002, at.getY(), at.getZ(), "minecraft:overworld", 0xFF8E4EC6);
				pins[2].kind = Waypoint.Kind.NETHER_PORTAL;
				WaypointStore.get().add(pins[2]);
			});
			check(context, results, "portal pins switched off", true, mc -> dev.diorama.Settings.get().portalMarkers = false);
			check(context, results, "portal pins switched on again", true, mc -> dev.diorama.Settings.get().portalMarkers = true);
			check(context, results, "a portal pin deleted by the player", true, mc -> WaypointStore.get().remove(pins[2]));
		} finally {
			context.runOnClient(mc -> dev.diorama.Settings.get().portalMarkers = portalPins);
		}
		for (String each : results) {
			if (!each.endsWith(" ok")) {
				wrong.add("revision() when " + each);
			}
		}
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.remove(pins[0]);
			store.remove(pins[1]);
			// A deleted portal pin stays in the file, hidden; this one goes for good, as the waypoints section runs here next.
			pins[2].kind = Waypoint.Kind.MANUAL;
			pins[2].deleted = false;
			store.remove(pins[2]);
		});

		// Mistakes in the addon: a waypoint all() never listed, and one an earlier step removed. The add before them doesn't happen either.
		UUID mine = addOne(context, "Stale mine", 1, 12);
		int saves = context.computeOnClient(mc -> WaypointStore.saves());
		String refused = context.computeOnClient(mc -> {
			NewWaypoint first = NewWaypoint.builder("Stale never added", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ() + 12)).build();
			String never = thrown(() -> apply(batch().add(first).update(UUID.randomUUID(), WaypointUpdate.builder().name("No such").build())));
			String twice = thrown(() -> apply(batch().add(first).remove(mine).update(mine, WaypointUpdate.builder().name("After removal").build())));
			return never.split(":")[0] + " " + twice.split(":")[0];
		});
		boolean untouched = context.computeOnClient(mc -> WaypointStore.saves() == saves && waypoints().get(mine).isPresent()
			&& waypoints().all().stream().noneMatch(w -> w.name().equals("Stale never added")));
		if (!refused.equals("IllegalArgumentException IllegalArgumentException") || !untouched) {
			wrong.add("A step naming a waypoint all() never listed, and one an earlier step removed: threw " + refused
				+ " (expected IllegalArgumentException twice); nothing applied " + untouched);
		}
		removeIds(context, List.of(mine));
	}

	/**
	 * A value Diorama would refuse throws where the addon gives it, before any batch: a name too
	 * long or blank, an ARGB colour, a null. Nothing reaches Diorama, and the addon stays on.
	 */
	private static void badValues(ClientGameTestContext context, List<String> wrong) {
		String thrown = context.computeOnClient(mc -> String.join("; ", List.of(
			thrown(() -> NewWaypoint.builder("x".repeat(NewWaypoint.MAX_NAME_LENGTH + 1), DimensionId.OVERWORLD, BlockCoord.of(0, 64, 0))).split(":")[0],
			thrown(() -> NewWaypoint.builder("  ", DimensionId.OVERWORLD, BlockCoord.of(0, 64, 0))).split(":")[0],
			thrown(() -> WaypointUpdate.builder().rgb(0xFF46A758)).split(":")[0],
			thrown(() -> batch().remove(null)).split(":")[0],
			thrown(() -> waypoints().edit(api().world().orElseThrow(), waypoints().revision(), null)).split(":")[0])));
		boolean on = context.computeOnClient(mc -> running("diorama_test_addon"));
		String expected = "IllegalArgumentException; IllegalArgumentException; IllegalArgumentException; NullPointerException; NullPointerException";
		if (!thrown.equals(expected) || !on) {
			wrong.add("Bad values: threw " + thrown + ", expected " + expected + "; the addon still on " + on);
		}
	}

	/**
	 * A disk that refuses waypoints.json (its temporary file's name taken by a folder): the batch
	 * reports saved() false and changed() true, the waypoint is kept in memory as the player's own
	 * edits are, and the next save that works writes it.
	 */
	private static void diskFull(ClientGameTestContext context, List<String> wrong) {
		Path file = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		String result;
		try (Blocked blocked = Blocked.file(file)) {
			result = context.computeOnClient(mc -> {
				EditResult edit = apply(batch().add(NewWaypoint.builder("Unsaved", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ() + 7)).build()));
				return edit.changed() + " " + edit.saved() + " " + waypoints().get(edit.added().get(0)).isPresent();
			});
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

	/**
	 * A disk that refuses only folders.json while a batch makes a folder: waypoints.json is written,
	 * the batch reports saved() false, and the next ordinary save, which changes no folder, writes
	 * folders.json too, so the waypoint is still in its folder when the store is read again from
	 * disk, as on the next start.
	 */
	private static void foldersOwed(ClientGameTestContext context, List<String> wrong) {
		Path folders = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("folders.json"));
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		String result;
		UUID id;
		try (Blocked blocked = Blocked.file(folders)) {
			EditResult edit = context.computeOnClient(mc -> apply(batch().add(NewWaypoint.builder("Owed folder pin", DimensionId.OVERWORLD,
				BlockCoord.of(at.getX(), at.getY(), at.getZ() + 8)).folder("Owed folder").build())));
			id = edit.added().get(0);
			result = edit.changed() + " " + edit.saved();
		}
		boolean before = Files.exists(folders) && text(folders).contains("Owed folder");
		// An ordinary save of something else: Diorama's own, for a waypoint renamed in the list, say.
		context.runOnClient(mc -> WaypointStore.get().changed());
		boolean after = Files.exists(folders) && text(folders).contains("Owed folder");
		String reread = context.computeOnClient(mc -> {
			WaypointStore.onLeave();
			return waypoints().get(id).flatMap(dev.diorama.api.Waypoint::folder).orElse("no folder");
		});
		if (!result.equals("true false") || before || !after || !reread.equals("Owed folder")) {
			wrong.add("A batch whose folders.json the disk refused: changed, saved " + result + " (expected true false); in folders.json before " + before
				+ ", after the next ordinary save " + after + " (expected true); its folder read again from disk: " + reread + " (expected Owed folder)");
		}
		Diorama.LOGGER.info("addon-api waypoints: folders.json refused, then written by the next save {}; read again: {}", after, reread);
		context.runOnClient(mc -> {
			apply(batch().remove(id));
			WaypointStore.get().deleteFolder("Owed folder");
		});
	}

	/**
	 * A folder a waypoint names that folders.json doesn't have, as a refused folders.json write and
	 * a restart leave: an update that doesn't name a folder doesn't make it, so the other waypoint
	 * naming it doesn't move into it; and an update that takes the first out of it is a change,
	 * saved, so it's out of it on disk too.
	 */
	private static void danglingFolders(ClientGameTestContext context, List<String> wrong) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		String folder = "Dangling folder";
		Waypoint[] both = context.computeOnClient(mc -> {
			Waypoint one = new Waypoint("Dangling one", at.getX() + 1, at.getY(), at.getZ() + 14, "minecraft:overworld", Waypoint.PALETTE[2]);
			Waypoint two = new Waypoint("Dangling two", at.getX() + 2, at.getY(), at.getZ() + 14, "minecraft:overworld", Waypoint.PALETTE[2]);
			Waypoint three = new Waypoint("Dangling three", at.getX() + 3, at.getY(), at.getZ() + 14, "minecraft:overworld", Waypoint.PALETTE[2]);
			one.folder = folder;
			two.folder = folder;
			three.folder = "Another dangling folder";
			// Added as a file read at the start brings them: in folders folders.json doesn't have.
			WaypointStore.get().add(one);
			WaypointStore.get().add(two);
			WaypointStore.get().add(three);
			return new Waypoint[] {one, two, three};
		});
		UUID one = both[0].id;
		UUID two = both[1].id;
		UUID three = both[2].id;
		String renamed = context.computeOnClient(mc -> {
			EditResult result = apply(batch().update(one, WaypointUpdate.builder().name("Dangling renamed").build()));
			return "changed " + result.changed() + ", folder made " + (WaypointStore.get().folderName(folder) != null) + ", the other's folder "
				+ waypoints().get(two).flatMap(dev.diorama.api.Waypoint::folder).orElse("none");
		});
		if (!renamed.equals("changed true, folder made false, the other's folder none")) {
			wrong.add("A rename of a waypoint in a folder folders.json doesn't have: " + renamed + " (expected changed true, folder made false, the other's folder none)");
		}
		int saves = context.computeOnClient(mc -> WaypointStore.saves());
		String cleared = context.computeOnClient(mc -> {
			EditResult result = apply(batch().update(three, WaypointUpdate.builder().folder("").build()));
			return "changed " + result.changed() + ", saved " + result.saved();
		});
		int clearSaves = context.computeOnClient(mc -> WaypointStore.saves()) - saves;
		String reread = context.computeOnClient(mc -> {
			WaypointStore.onLeave();
			return WaypointStore.get().all().stream().filter(w -> w.id.equals(three)).map(w -> w.name + " in \"" + w.folder + "\"").toList().toString();
		});
		String expected = "[Dangling three in \"\"]";
		if (!cleared.equals("changed true, saved true") || clearSaves != 1 || !reread.equals(expected)) {
			wrong.add("Taking a waypoint out of a folder folders.json doesn't have: " + cleared + ", " + clearSaves + " saves (expected changed true, saved true, 1 save); read"
				+ " again from disk " + reread + " (expected " + expected + ")");
		}
		Diorama.LOGGER.info("addon-api waypoints: a folder folders.json doesn't have: renamed {}; cleared {}; read again {}", renamed, cleared, reread);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.id.equals(one) || w.id.equals(two) || w.id.equals(three)).toList().forEach(store::remove);
		});
	}

	/** A file the disk refuses to replace while this is open: its temporary file's name is taken by a folder. */
	private record Blocked(Path blocker) implements AutoCloseable {
		static Blocked file(Path file) {
			Path blocker = file.resolveSibling(file.getFileName() + ".tmp");
			try {
				Files.createDirectories(blocker);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			return new Blocked(blocker);
		}

		@Override
		public void close() {
			try {
				Files.delete(this.blocker);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	/**
	 * Automatic pins are read but not written: a batch that names a temporary pin or a waystone
	 * throws and applies nothing, not even its other steps; the addon stays on.
	 */
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
			NewWaypoint first = NewWaypoint.builder("Never added", DimensionId.OVERWORLD, BlockCoord.of(at.getX(), at.getY(), at.getZ() + 3)).build();
			out.add(thrown(() -> apply(batch().add(first).update(temporary, WaypointUpdate.builder().name("Mine now").build()))).split(":")[0]);
			out.add(thrown(() -> apply(batch().add(first).remove(temporary))).split(":")[0]);
			out.add(thrown(() -> apply(batch().add(first).update(stone, WaypointUpdate.builder().name("Mine now").build()))).split(":")[0]);
			return String.join(" ", out);
		});
		boolean unchanged = context.computeOnClient(mc -> WaypointStore.saves() == saves && WaypointStore.get().all().stream().noneMatch(w -> w.name.equals("Mine now")
			|| w.name.equals("Never added")));
		boolean on = context.computeOnClient(mc -> running("diorama_test_addon"));
		if (!refused.equals("TEMPORARY WAYSTONE IllegalArgumentException IllegalArgumentException IllegalArgumentException") || !unchanged || !on) {
			wrong.add("Automatic pins: read and refused " + refused + "; store unchanged, the batches' adds included, " + unchanged + "; the addon still on " + on);
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
			apply(batch().update(id, WaypointUpdate.builder().name("Twin renamed").build()));
			String stored = WaypointStore.get().all().stream().filter(w -> w.id.equals(id)).map(w -> w.name).toList().toString();
			boolean[] removed = new boolean[1];
			removed[0] = apply(batch().remove(id)).changed();
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
		World world = context.computeOnClient(mc -> api.world().orElseThrow());
		String[] calls = new String[5];
		Thread other = new Thread(() -> {
			calls[0] = thrown(api::world);
			calls[1] = thrown(waypoints::all);
			calls[2] = thrown(() -> waypoints.get(UUID.randomUUID()));
			calls[3] = thrown(waypoints::revision);
			calls[4] = thrown(() -> waypoints.edit(world, 1, WaypointBatch.empty()));
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
			apply(batch().remove(doomed));
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
		context.runOnClient(mc -> apply(batch().update(other, WaypointUpdate.builder().name("Other row renamed").build())));
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
	 * The list's Delete, clicked once, deletes on the second click only what it was clicked for,
	 * when the rows are made again in between and two stored waypoints share an ID. With two rows
	 * selected, one of the twins among them, an automatic pin appearing (a death) makes the rows
	 * again: the confirming click deletes the two rows, not the twin left out. With one twin
	 * selected alone, deleted elsewhere (from the map, say) before the second click: the Delete is
	 * asked again, rather than moving to the other twin.
	 */
	private static void listTwins(ClientGameTestContext context, List<String> wrong) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		Waypoint[] made = context.computeOnClient(mc -> {
			Waypoint one = new Waypoint("Twin row A", at.getX() + 2, at.getY(), at.getZ() - 6, "minecraft:overworld", Waypoint.PALETTE[2]);
			Waypoint two = new Waypoint("Twin row B", at.getX() + 3, at.getY(), at.getZ() - 6, "minecraft:overworld", Waypoint.PALETTE[2]);
			Waypoint third = new Waypoint("Twin row C", at.getX() + 4, at.getY(), at.getZ() - 6, "minecraft:overworld", Waypoint.PALETTE[2]);
			two.id = one.id;
			WaypointStore.get().add(one);
			WaypointStore.get().add(two);
			WaypointStore.get().add(third);
			return new Waypoint[] {one, two, third};
		});
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(3);
		// Twin A, then Twin C with Ctrl, then Delete once.
		select(context, made[0]);
		context.waitTicks(2);
		context.getInput().holdControl();
		context.waitTicks(1);
		select(context, made[2]);
		context.getInput().releaseControl();
		context.waitTicks(2);
		clickDelete(context);
		boolean armed = context.computeOnClient(mc -> confirmButton(mc.gui.screen()) != null);
		Waypoint[] death = new Waypoint[1];
		context.runOnClient(mc -> {
			death[0] = new Waypoint("Twin death", at.getX() + 5003, at.getY(), at.getZ(), "minecraft:overworld", 0xFFFFFFFF);
			death[0].kind = Waypoint.Kind.DEATH;
			WaypointStore.get().add(death[0]);
		});
		context.waitTicks(3);
		String selected = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).selection().stream().map(w -> w.name).sorted().toList().toString());
		var confirm = context.computeOnClient(mc -> confirmButton(mc.gui.screen()));
		if (confirm != null) {
			DioramaClientTest.clickAt(context, confirm);
			context.waitTicks(2);
		}
		String left = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.name.startsWith("Twin row")).map(w -> w.name).sorted().toList().toString());
		if (!armed || !selected.equals("[Twin row A, Twin row C]") || !left.equals("[Twin row B]")) {
			wrong.add("The list's Delete for two rows, a twin among them, as an automatic pin appears before the second click: armed " + armed + ", selected then " + selected
				+ " (expected [Twin row A, Twin row C]); left after the second click " + left + " (expected [Twin row B])");
		}

		// Two more twins: the first selected alone, Delete clicked once, then it's deleted from elsewhere.
		Waypoint[] pair = context.computeOnClient(mc -> {
			Waypoint one = new Waypoint("Twin row E", at.getX() + 5, at.getY(), at.getZ() - 6, "minecraft:overworld", Waypoint.PALETTE[2]);
			Waypoint two = new Waypoint("Twin row F", at.getX() + 6, at.getY(), at.getZ() - 6, "minecraft:overworld", Waypoint.PALETTE[2]);
			two.id = one.id;
			WaypointStore.get().add(one);
			WaypointStore.get().add(two);
			return new Waypoint[] {one, two};
		});
		context.waitTicks(3);
		select(context, pair[0]);
		context.waitTicks(2);
		clickDelete(context);
		boolean armedOne = context.computeOnClient(mc -> confirmButton(mc.gui.screen()) != null);
		// Deleted from the map's menu, say, while the list waits for the second click.
		context.runOnClient(mc -> WaypointStore.get().remove(pair[0]));
		context.waitTicks(3);
		var confirmOne = context.computeOnClient(mc -> confirmButton(mc.gui.screen()));
		String selectedOne = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).selection().stream().map(w -> w.name).toList().toString());
		context.takeScreenshot("addon-api-17-list-twin-deleted-elsewhere");
		if (confirmOne != null) {
			DioramaClientTest.clickAt(context, confirmOne);
			context.waitTicks(2);
		}
		String leftOne = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.name.startsWith("Twin row E") || w.name.startsWith("Twin row F"))
			.map(w -> w.name).toList().toString());
		if (!armedOne || confirmOne != null || !selectedOne.equals("[]") || !leftOne.equals("[Twin row F]")) {
			wrong.add("The list's Delete for one twin, deleted elsewhere before the second click: armed " + armedOne + ", still armed after " + (confirmOne != null)
				+ " (expected false), selected then " + selectedOne + " (expected []); left " + leftOne + " (expected [Twin row F])");
		}
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().filter(w -> w.name.startsWith("Twin row")).toList().forEach(store::remove);
			store.remove(death[0]);
		});
		Diorama.LOGGER.info("addon-api waypoints: the list's Delete with twins: {} > {}; one twin deleted elsewhere: {} > {}", selected, left, selectedOne, leftOne);
	}

	/** Clicks the selected rows' Delete once. */
	private static void clickDelete(ClientGameTestContext context) {
		String delete = net.minecraft.network.chat.Component.translatable("diorama.waypoint.delete").getString();
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.Button button && button.visible && button.getMessage().getString().equals(delete)) {
					return button;
				}
			}
			throw new AssertionError("No Delete button on the selected rows");
		}));
		context.waitTicks(2);
	}

	/** The red Click again to delete showing on the selected rows, for one or several, or null if Delete isn't waiting for its second click. */
	private static net.minecraft.client.gui.components.@org.jspecify.annotations.Nullable Button confirmButton(net.minecraft.client.gui.screens.Screen screen) {
		String once = net.minecraft.network.chat.Component.translatable("diorama.menu.delete_confirm").getString();
		for (var child : screen.children()) {
			if (child instanceof net.minecraft.client.gui.components.Button button && button.visible && button.getMessage().getString().startsWith(once)) {
				return button;
			}
		}
		return null;
	}

	/**
	 * The map's right-click menu refuses a waypoint an addon deleted: a pin drawn before the delete
	 * gets no menu, and a menu opened before it does nothing when Hide or Copy coordinates is
	 * clicked (copying closes it). A waystone's menu does the same once Waystones no longer has it.
	 */
	private static void mapMenu(ClientGameTestContext context, List<String> wrong) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = at.getX() + 4;
		int z = at.getZ() - 4;
		int y = context.computeOnClient(mc -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
		UUID first = context.computeOnClient(mc -> apply(batch().add(NewWaypoint.builder("Menu pin", DimensionId.OVERWORLD, BlockCoord.of(x, y, z)).build())).added().get(0));
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
			apply(batch().remove(first));
			map.openMenuForTests(pin.x(), pin.y());
			return map.menu() == null ? "no menu" : map.menu().labels().toString();
		});
		context.setScreen(() -> null);

		UUID second = context.computeOnClient(mc -> apply(batch().add(NewWaypoint.builder("Menu pin two", DimensionId.OVERWORLD, BlockCoord.of(x, y, z)).build())).added().get(0));
		context.setScreen(MapScreen::new);
		DioramaClientTest.lookAtPlayer(context, 4.0, TileKey.SURFACE);
		DioramaClientTest.settle(context, 20);
		DioramaClientTest.rightClickPin(context, new int[] {x, y, z});
		List<String> labels = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).menu() == null ? List.of() : ((MapScreen) mc.gui.screen()).menu().labels());
		String hide = net.minecraft.network.chat.Component.translatable("diorama.waypoints.hide").getString();
		String copy = net.minecraft.network.chat.Component.translatable("diorama.menu.copy_coordinates").getString();
		int saves = context.computeOnClient(mc -> WaypointStore.saves());
		String clicked = context.computeOnClient(mc -> {
			MapScreen map = (MapScreen) mc.gui.screen();
			if (map.menu() == null || map.menu().centreOf(hide) == null || map.menu().centreOf(copy) == null) {
				return "no Hide or Copy coordinates in the menu";
			}
			float[] hideAt = map.menu().centreOf(hide);
			float[] copyAt = map.menu().centreOf(copy);
			mc.keyboardHandler.setClipboard("untouched");
			apply(batch().remove(second));
			map.menu().click(hideAt[0], hideAt[1]);
			map.menu().click(copyAt[0], copyAt[1]);
			return "clipboard " + mc.keyboardHandler.getClipboard() + ", menu open " + (map.menu() != null);
		});
		boolean nothing = context.computeOnClient(mc -> WaypointStore.saves() == saves + 1 && WaypointStore.get().all().stream().noneMatch(w -> w.id.equals(second)));
		context.setScreen(() -> null);
		if (stale.equals("no pin drawn") || stale.contains("Menu pin") || !labels.contains("Menu pin two") || !clicked.equals("clipboard untouched, menu open false") || !nothing) {
			wrong.add("The map's menu and a pin an addon deleted: right-clicking the pin drawn before gave " + stale + " (expected no waypoint menu); the menu opened before "
				+ labels + ", Hide and Copy coordinates after the delete: " + clicked + " (expected clipboard untouched, menu open false), nothing saved but the delete and the"
				+ " waypoint still gone " + nothing);
		}

		// A waystone's menu, opened while Waystones has it, then Waystones forgets it (the player left the server, say).
		Waypoint stone = context.computeOnClient(mc -> {
			Waypoint w = new Waypoint("Menu stone", x, y, z, "minecraft:overworld", 0xFF8E4EC6);
			Waystones.knownForTests(w);
			return w;
		});
		context.setScreen(MapScreen::new);
		DioramaClientTest.lookAtPlayer(context, 4.0, TileKey.SURFACE);
		DioramaClientTest.settle(context, 20);
		DioramaClientTest.rightClickPin(context, new int[] {x, y, z});
		String stoneClicked = context.computeOnClient(mc -> {
			MapScreen map = (MapScreen) mc.gui.screen();
			if (map.menu() == null || !map.menu().labels().contains("Menu stone") || map.menu().centreOf(copy) == null) {
				return "no waystone menu: " + (map.menu() == null ? "none" : map.menu().labels());
			}
			float[] copyAt = map.menu().centreOf(copy);
			mc.keyboardHandler.setClipboard("untouched");
			Waystones.clear();
			map.menu().click(copyAt[0], copyAt[1]);
			return "clipboard " + mc.keyboardHandler.getClipboard() + ", menu open " + (map.menu() != null);
		});
		context.setScreen(() -> null);
		if (!stoneClicked.equals("clipboard untouched, menu open false")) {
			wrong.add("The map's menu for a waystone Waystones forgot after it opened: Copy coordinates gave " + stoneClicked + " (expected clipboard untouched, menu open false)");
		}
		Diorama.LOGGER.info("addon-api waypoints: map menus for a pin deleted after they opened: {}; for a waystone forgotten: {} ({})", clicked, stoneClicked, stone.name);
	}

	/**
	 * The editor refuses a waypoint an addon deleted while it was open: its Save closes it without
	 * saving, rather than change a waypoint that's gone or bring it back. And it saves only what the
	 * player changed in it: the player opens a waypoint and renames it while an addon moves and
	 * hides it, and Save keeps the addon's place and hiding with the player's name.
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

		UUID shared = addOne(context, "Edited by two", 4, -4);
		Waypoint sharedWaypoint = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.id.equals(shared)).findFirst().orElseThrow());
		BlockPos moved = context.computeOnClient(mc -> mc.player.blockPosition().offset(40, 0, 40));
		context.setScreen(() -> new WaypointEditScreen(null, sharedWaypoint, false));
		context.waitTicks(3);
		DioramaClientTest.typeInto(context, 0, "Renamed by the player");
		// Meanwhile the sync addon moves and hides it.
		context.runOnClient(mc -> apply(batch().update(shared, WaypointUpdate.builder().pos(BlockCoord.of(moved.getX(), moved.getY(), moved.getZ())).visible(false).build())));
		context.takeScreenshot("addon-api-16-editor-while-addon-moves");
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.waitTicks(3);
		String after = context.computeOnClient(mc -> waypoints().get(shared).map(w -> w.name() + " at " + (w.pos().x() - moved.getX()) + "," + (w.pos().z() - moved.getZ())
			+ " visible " + w.visible()).orElse("gone"));
		if (!after.equals("Renamed by the player at 0,0 visible false")) {
			wrong.add("The editor's Save after an addon moved and hid the waypoint while it was open: " + after
				+ " (expected Renamed by the player at 0,0 visible false: the player's name, the addon's place and hiding)");
		}
		Diorama.LOGGER.info("addon-api waypoints: editor saved over an addon's move: {}", after);
		context.setScreen(() -> null);
		removeIds(context, List.of(shared));

		// The player types a new Y while the addon moves the waypoint along X and Z: Save keeps both.
		UUID axes = addOne(context, "Edited on two axes", 5, -5);
		Waypoint axesWaypoint = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.id.equals(axes)).findFirst().orElseThrow());
		BlockPos start = context.computeOnClient(mc -> new BlockPos(axesWaypoint.x, axesWaypoint.y, axesWaypoint.z));
		context.setScreen(() -> new WaypointEditScreen(null, axesWaypoint, false));
		context.waitTicks(3);
		DioramaClientTest.typeInto(context, 3, Integer.toString(start.getY() + 7));
		context.runOnClient(mc -> apply(batch().update(axes, WaypointUpdate.builder().pos(BlockCoord.of(start.getX() + 30, start.getY(), start.getZ() - 20)).build())));
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.waitTicks(3);
		String axesAfter = context.computeOnClient(mc -> waypoints().get(axes).map(w -> (w.pos().x() - start.getX()) + "," + (w.pos().y() - start.getY()) + ","
			+ (w.pos().z() - start.getZ())).orElse("gone"));
		if (!axesAfter.equals("30,7,-20")) {
			wrong.add("The editor's Save after the player typed a new Y while an addon moved the waypoint along X and Z: moved by " + axesAfter
				+ " (expected 30,7,-20: the addon's X and Z, the player's Y)");
		}
		context.setScreen(() -> null);

		// The player types a new X while the addon moves the waypoint to the Nether: the player's place, Overworld and all three coordinates, wins.
		BlockPos home = context.computeOnClient(mc -> new BlockPos(axesWaypoint.x, axesWaypoint.y, axesWaypoint.z));
		context.setScreen(() -> new WaypointEditScreen(null, axesWaypoint, false));
		context.waitTicks(3);
		DioramaClientTest.typeInto(context, 2, Integer.toString(home.getX() + 3));
		context.runOnClient(mc -> apply(batch().update(axes, WaypointUpdate.builder().dimension(DimensionId.NETHER).pos(BlockCoord.of(home.getX() / 8, 70, home.getZ() / 8)).build())));
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.waitTicks(3);
		String dimensionAfter = context.computeOnClient(mc -> waypoints().get(axes).map(w -> w.dimension() + " " + (w.pos().x() - home.getX()) + "," + (w.pos().y() - home.getY())
			+ "," + (w.pos().z() - home.getZ())).orElse("gone"));
		if (!dimensionAfter.equals("minecraft:overworld 3,0,0")) {
			wrong.add("The editor's Save after the player typed a new X while an addon moved the waypoint to the Nether: " + dimensionAfter
				+ " (expected minecraft:overworld 3,0,0: where the editor showed it, with the player's X)");
		}
		context.setScreen(() -> null);

		// The player only renames it while the addon moves it to the Nether: the addon's place stays.
		context.setScreen(() -> new WaypointEditScreen(null, axesWaypoint, false));
		context.waitTicks(3);
		DioramaClientTest.typeInto(context, 0, "Renamed, not moved");
		context.runOnClient(mc -> apply(batch().update(axes, WaypointUpdate.builder().dimension(DimensionId.NETHER).pos(BlockCoord.of(11, 70, 12)).build())));
		DioramaClientTest.clickButton(context, "diorama.waypoint.save");
		context.waitTicks(3);
		String renamedOnly = context.computeOnClient(mc -> waypoints().get(axes).map(w -> w.name() + " " + w.dimension() + " " + w.pos().x() + "," + w.pos().y() + "," + w.pos().z())
			.orElse("gone"));
		if (!renamedOnly.equals("Renamed, not moved minecraft:the_nether 11,70,12")) {
			wrong.add("The editor's Save after the player renamed a waypoint an addon moved to the Nether: " + renamedOnly + " (expected Renamed, not moved minecraft:the_nether 11,70,12)");
		}
		Diorama.LOGGER.info("addon-api waypoints: editor with an addon's move: Y typed {}; X typed, moved to the Nether {}; renamed {}", axesAfter, dimensionAfter, renamedOnly);
		context.setScreen(() -> null);
		removeIds(context, List.of(axes));
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
		String exported = other.named("Synced here");
		steps.add("added in Diorama " + (exported != null));
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
		// The copy first made is the one renamed and deleted: none is left behind.
		steps.add("deleted in Diorama " + (theirs != null && theirs.equals(exported) && !other.pins().containsKey(theirs) && other.named("Synced here") == null));

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

	/**
	 * After a save that failed, the sync keeps the links to the waypoints it added: a pin from the
	 * other mod is imported while the disk refuses waypoints.json, then both sides change again,
	 * and each side still has one copy of it, not two.
	 */
	private static void syncFailedSave(ClientGameTestContext context, List<String> wrong) {
		PollingSync sync = context.computeOnClient(mc -> new PollingSync(TestAddon.api()));
		OtherMod other = sync.other();
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		Path file = context.computeOnClient(mc -> WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
		tick(context, sync, 3);
		String problem;
		try (Blocked blocked = Blocked.file(file)) {
			context.runOnClient(mc -> other.add(new OtherMod.Pin("Imported unsaved", at.getX() - 3, at.getY(), at.getZ() - 9, "minecraft:overworld", 0x3E63DD, true, "")));
			tick(context, sync, 3);
			problem = sync.lastProblem();
		}
		// The next changes on both sides, after the disk works again.
		context.runOnClient(mc -> other.add(new OtherMod.Pin("Later other", at.getX() - 4, at.getY(), at.getZ() - 9, "minecraft:overworld", 0x3E63DD, true, "")));
		context.runOnClient(mc -> WaypointStore.get().add(new Waypoint("Later Diorama", at.getX() - 5, at.getY(), at.getZ() - 9, "minecraft:overworld", Waypoint.PALETTE[2])));
		tick(context, sync, 5);
		String copies = context.computeOnClient(mc -> "Diorama " + waypoints().all().stream().filter(w -> w.name().equals("Imported unsaved")).count() + ", the other mod "
			+ other.pins().values().stream().filter(p -> p.name().equals("Imported unsaved")).count());
		if (!copies.equals("Diorama 1, the other mod 1") || !problem.startsWith("Diorama couldn't save")) {
			wrong.add("The polling sync after a save that failed: copies of the imported pin: " + copies + " (expected Diorama 1, the other mod 1); its problem then: " + problem);
		}
		Diorama.LOGGER.info("addon-api waypoints: sync through a failed save: {}; problem {}", copies, problem);
		removeNamed(context, "Imported unsaved");
		removeNamed(context, "Later ");
	}

	/**
	 * The sync and saved maps. A waypoint with a linked waypoint's ID on another saved map is
	 * another waypoint: switching to that map neither changes nor deletes the linked one's copy,
	 * and the other map's waypoint gets a copy of its own. A batch worked out on one saved map and
	 * handed over after a switch to another applies nothing, and the sync keeps its links and
	 * tries again. Deleting a saved map removes the copies of its waypoints.
	 */
	private static void syncSavedMaps(ClientGameTestContext context, List<String> wrong) {
		PollingSync sync = context.computeOnClient(mc -> new PollingSync(TestAddon.api()));
		OtherMod other = sync.other();
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		String key = context.computeOnClient(mc -> WorldKey.current());
		tick(context, sync, 3);
		Waypoint linked = context.computeOnClient(mc -> {
			Waypoint w = new Waypoint("Linked on main", at.getX() + 7, at.getY(), at.getZ() - 9, "minecraft:overworld", Waypoint.PALETTE[1]);
			WaypointStore.get().add(w);
			return w;
		});
		tick(context, sync, 3);
		String copy = other.named("Linked on main");
		String second = context.computeOnClient(mc -> Maps.of(key).create("minecraft:overworld", "Sync map"));
		context.waitFor(mc -> MapSession.get() != null, 2400);
		context.waitTicks(2);
		// On the second map, a waypoint with the same ID, as a waypoints file copied by hand gives.
		context.runOnClient(mc -> {
			Waypoint twin = new Waypoint("Same ID elsewhere", at.getX() + 8, at.getY(), at.getZ() - 9, "minecraft:overworld", Waypoint.PALETTE[3]);
			twin.id = linked.id;
			WaypointStore.get().add(twin);
		});
		tick(context, sync, 3);
		String sameId = (copy == null ? "no copy" : other.pins().containsKey(copy) ? other.pins().get(copy).name() : "copy deleted") + ", the other map's own copy "
			+ (other.named("Same ID elsewhere") != null);

		// Worked out on the second map, handed over after the player switched back to the main one. A waypoint new on Diorama's side
		// too, which the refused pass must not copy to the other mod.
		context.runOnClient(mc -> other.add(new OtherMod.Pin("Raced", at.getX() + 9, at.getY(), at.getZ() - 9, "minecraft:overworld", 0x3E63DD, true, "")));
		context.runOnClient(mc -> WaypointStore.get().add(new Waypoint("Raced here", at.getX() + 10, at.getY(), at.getZ() - 9, "minecraft:overworld", Waypoint.PALETTE[3])));
		long versionBefore = context.computeOnClient(mc -> other.version());
		int linksBefore = context.computeOnClient(mc -> sync.linkCount());
		context.runOnClient(mc -> sync.beforeEdit(() -> Maps.of(key).use("minecraft:overworld", Maps.MAIN)));
		context.runOnClient(mc -> sync.tick());
		context.runOnClient(mc -> sync.beforeEdit(() -> {
		}));
		String raced = context.computeOnClient(mc -> sync.lastProblem() + "; links " + sync.linkCount() + " (before " + linksBefore + "); the other mod changed "
			+ (other.version() != versionBefore) + ", has Raced here " + (other.named("Raced here") != null) + "; Raced in Diorama "
			+ waypoints().all().stream().filter(w -> w.name().equals("Raced")).count());
		context.waitFor(mc -> MapSession.get() != null, 2400);
		tick(context, sync, 3);
		String retried = context.computeOnClient(mc -> "Raced in Diorama " + waypoints().all().stream().filter(w -> w.name().equals("Raced")).count()
			+ ", in the other mod " + other.pins().values().stream().filter(p -> p.name().equals("Raced")).count());

		// Back on the main map, the second is deleted: its waypoint's copy goes.
		context.runOnClient(mc -> Maps.of(key).delete("minecraft:overworld", second));
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		tick(context, sync, 3);
		String deleted = "the other map's copy " + (other.named("Same ID elsewhere") != null) + ", the main map's " + (copy != null && other.pins().containsKey(copy));
		String refusedTail = "; links " + linksBefore + " (before " + linksBefore + "); the other mod changed false, has Raced here false; Raced in Diorama 0";
		if (!sameId.equals("Linked on main, the other map's own copy true") || !raced.startsWith("Diorama's waypoints or world changed before the batch was applied")
			|| !raced.endsWith(refusedTail) || !retried.equals("Raced in Diorama 1, in the other mod 1") || !deleted.equals("the other map's copy false, the main map's true")) {
			wrong.add("The polling sync and saved maps: a waypoint with a linked one's ID on another map: the linked copy " + sameId
				+ " (expected Linked on main, the other map's own copy true); a batch handed over after a switch: " + raced + " (expected the world moved, nothing applied, "
				+ "nothing changed on either side: ..." + refusedTail + ");"
				+ " on the next ticks " + retried + " (expected 1 and 1); after the second map was deleted, " + deleted + " (expected the other map's copy false, the main map's true)");
		}
		Diorama.LOGGER.info("addon-api waypoints: sync and saved maps: {}; {}; {}; {}", sameId, raced, retried, deleted);
		removeNamed(context, "Linked on main");
		removeNamed(context, "Raced");
		tick(context, sync, 3);
	}

	/**
	 * A link matches its waypoint by dimension, saved map and ID together. A linked waypoint in the
	 * Overworld has a twin with its ID in the Nether, stored after it, so all() shows only the
	 * first. The player deletes the first: its copy in the other mod goes, and the twin, which
	 * all() shows now, gets a copy of its own rather than taking over the link (after which the
	 * other mod deleting that copy would delete the twin).
	 */
	private static void syncSameIdElsewhere(ClientGameTestContext context, List<String> wrong) {
		PollingSync sync = context.computeOnClient(mc -> new PollingSync(TestAddon.api()));
		OtherMod other = sync.other();
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		tick(context, sync, 3);
		Waypoint linked = context.computeOnClient(mc -> {
			Waypoint w = new Waypoint("Linked here", at.getX() + 7, at.getY(), at.getZ() - 13, "minecraft:overworld", Waypoint.PALETTE[1]);
			WaypointStore.get().add(w);
			return w;
		});
		tick(context, sync, 3);
		String copy = other.named("Linked here");
		context.runOnClient(mc -> {
			Waypoint twin = new Waypoint("Same ID in the Nether", 30, 70, 40, "minecraft:the_nether", Waypoint.PALETTE[3]);
			twin.id = linked.id;
			WaypointStore.get().add(twin);
		});
		tick(context, sync, 3);
		context.runOnClient(mc -> WaypointStore.get().remove(linked));
		tick(context, sync, 3);
		String twinCopy = other.named("Same ID in the Nether");
		String after = "the linked copy gone " + (copy != null && !other.pins().containsKey(copy)) + ", the twin's own copy " + (twinCopy != null && !twinCopy.equals(copy))
			+ ", the twin in Diorama " + context.computeOnClient(mc -> waypoints().get(linked.id).map(dev.diorama.api.Waypoint::name).orElse("none"));
		String expected = "the linked copy gone true, the twin's own copy true, the twin in Diorama Same ID in the Nether";
		if (copy == null || !after.equals(expected)) {
			wrong.add("The polling sync and a twin with a linked waypoint's ID in another dimension, after the linked one was deleted: copied first " + (copy != null) + "; "
				+ after + " (expected " + expected + ")");
		}
		Diorama.LOGGER.info("addon-api waypoints: sync and a twin in another dimension: {}", after);
		removeNamed(context, "Same ID in the Nether");
		tick(context, sync, 3);
	}

	/**
	 * The sync while automatic pins appear. A death pin appears between the sync working out its
	 * batch and handing it over: the batch isn't applied, the next pass applies it, and then it
	 * settles. A bed pin appearing while it's idle makes one pass and no edit.
	 */
	private static void syncAutomaticPins(ClientGameTestContext context, List<String> wrong) {
		PollingSync sync = context.computeOnClient(mc -> new PollingSync(TestAddon.api()));
		OtherMod other = sync.other();
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		tick(context, sync, 3);
		Waypoint[] pins = new Waypoint[2];
		context.runOnClient(mc -> other.add(new OtherMod.Pin("Mid-sync pin", at.getX() - 7, at.getY(), at.getZ() - 13, "minecraft:overworld", 0x3E63DD, true, "")));
		context.runOnClient(mc -> sync.beforeEdit(() -> {
			if (pins[0] == null) {
				pins[0] = new Waypoint("Mid-sync death", at.getX() + 5004, at.getY(), at.getZ(), "minecraft:overworld", 0xFFFFFFFF);
				pins[0].kind = Waypoint.Kind.DEATH;
				WaypointStore.get().add(pins[0]);
			}
		}));
		int edits = context.computeOnClient(mc -> sync.edits());
		int passes = context.computeOnClient(mc -> sync.passes());
		context.runOnClient(mc -> sync.tick());
		String refused = context.computeOnClient(mc -> sync.lastProblem() + "; in Diorama " + waypoints().all().stream().filter(w -> w.name().equals("Mid-sync pin")).count());
		tick(context, sync, 3);
		String retried = context.computeOnClient(mc -> "in Diorama " + waypoints().all().stream().filter(w -> w.name().equals("Mid-sync pin")).count() + ", edits "
			+ (sync.edits() - edits) + ", passes " + (sync.passes() - passes));
		context.runOnClient(mc -> sync.beforeEdit(() -> {
		}));
		int idleEdits = context.computeOnClient(mc -> sync.edits());
		int idlePasses = context.computeOnClient(mc -> sync.passes());
		long version = context.computeOnClient(mc -> other.version());
		tick(context, sync, 20);
		context.runOnClient(mc -> {
			pins[1] = new Waypoint(Waypoint.BED_NAME, at.getX() + 5005, at.getY(), at.getZ(), "minecraft:overworld", 0xFFE5484D);
			pins[1].kind = Waypoint.Kind.BED;
			WaypointStore.get().add(pins[1]);
		});
		tick(context, sync, 5);
		String idle = context.computeOnClient(mc -> "edits " + (sync.edits() - idleEdits) + ", passes " + (sync.passes() - idlePasses) + ", the other mod changed "
			+ (other.version() != version));
		String expectedRetry = "in Diorama 1, edits 1, passes 2";
		String expectedIdle = "edits 0, passes 1, the other mod changed false";
		if (!refused.startsWith("Diorama's waypoints or world changed before the batch was applied") || !refused.endsWith("in Diorama 0") || !retried.equals(expectedRetry)
			|| !idle.equals(expectedIdle)) {
			wrong.add("The polling sync with a death pin appearing mid-pass: " + refused + " (expected refused, in Diorama 0); then " + retried + " (expected " + expectedRetry
				+ "); 20 idle ticks and a bed pin appearing: " + idle + " (expected " + expectedIdle + ")");
		}
		Diorama.LOGGER.info("addon-api waypoints: sync with automatic pins: {}; {}; idle {}", refused, retried, idle);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			for (Waypoint pin : pins) {
				if (pin != null) {
					store.remove(pin);
				}
			}
		});
		removeNamed(context, "Mid-sync pin");
	}

	/**
	 * The other mod's pins Diorama would refuse don't stop the rest: a 33-character name comes
	 * across cut to 32, a blank one is left out, and the pin beside them comes across as it is.
	 */
	private static void syncTooLong(ClientGameTestContext context, List<String> wrong) {
		PollingSync sync = context.computeOnClient(mc -> new PollingSync(TestAddon.api()));
		OtherMod other = sync.other();
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		String longName = "A name one letter longer than 32";
		String tooLong = longName + "!";
		context.runOnClient(mc -> {
			other.add(new OtherMod.Pin(tooLong, at.getX() + 2, at.getY(), at.getZ() - 11, "minecraft:overworld", 0x3E63DD, true, ""));
			other.add(new OtherMod.Pin("   ", at.getX() + 3, at.getY(), at.getZ() - 11, "minecraft:overworld", 0x3E63DD, true, ""));
			other.add(new OtherMod.Pin("Beside the long one", at.getX() + 4, at.getY(), at.getZ() - 11, "minecraft:overworld", 0x3E63DD, true, ""));
		});
		String thrown = "nothing";
		try {
			tick(context, sync, 3);
		} catch (RuntimeException e) {
			thrown = e.toString();
		}
		String came = context.computeOnClient(mc -> waypoints().all().stream().filter(w -> w.pos().z() == at.getZ() - 11).map(dev.diorama.api.Waypoint::name).sorted().toList()
			.toString());
		String expected = "[" + longName + ", Beside the long one]";
		if (!came.equals(expected) || !thrown.equals("nothing")) {
			wrong.add("The polling sync with a name too long and a blank one: came across " + came + " (expected " + expected + "); the tick threw " + thrown);
		}
		Diorama.LOGGER.info("addon-api waypoints: sync with names Diorama refuses: {}; {}", came, sync.lastProblem());
		removeNamed(context, longName);
		removeNamed(context, "Beside the long one");
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

	/** At the title screen after leaving: no world, so nothing to read, and a batch for the world left applies nothing. */
	static void left(ClientGameTestContext context) {
		String read = context.computeOnClient(mc -> {
			World gone = new World(worldId, DimensionId.OVERWORLD, Map.of(), Map.of());
			EditResult result = waypoints().edit(gone, 1, batch().add(NewWaypoint.builder("After leaving", DimensionId.OVERWORLD, BlockCoord.of(0, 64, 0)).build()).build());
			return api().world() + " " + waypoints().all().size() + " " + waypoints().revision() + " applied " + result.applied();
		});
		if (!read.equals("Optional.empty 0 0 applied false")) {
			throw new AssertionError("After leaving: world(), all() size, revision(), a batch for the world left: " + read);
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
		try {
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
		} finally {
			// Whatever happened above: the sync stops ticking and the pins go, for whatever runs after.
			context.runOnClient(mc -> {
				syncTicking = false;
				pins.forEach(WaypointStore.get()::remove);
				TestAddon.sync().tick();
			});
		}
	}
	//?}

	/**
	 * Bytes and time of reading and editing with 1,000 waypoints, logged: world(), all(), get() and
	 * revision() over 100,000 calls each with nothing changed, and one batch of 10 renames.
	 */
	static void measure(ClientGameTestContext context) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		List<UUID> ids = context.computeOnClient(mc -> {
			WaypointBatch.Builder batch = batch();
			for (int i = 0; i < 1000; i++) {
				batch.add(NewWaypoint.builder("Many " + i, DimensionId.OVERWORLD, BlockCoord.of(at.getX() + 2000 + i, 70, at.getZ() + 2000)).build());
			}
			return apply(batch).added();
		});
		Diorama.LOGGER.info("addon-api measure: {}", readCost(context, ids));
		Diorama.LOGGER.info("addon-api measure: one batch of 10 renames with 1,000 waypoints: {}", editCost(context, ids));
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
		removeIds(context, ids);
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
	private static String editCost(ClientGameTestContext context, List<UUID> ids) {
		return context.computeOnClient(mc -> {
			dev.diorama.api.Waypoints waypoints = waypoints();
			com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			long[] nanos = new long[50];
			long[] bytes = new long[50];
			for (int round = 0; round < 50; round++) {
				int r = round;
				long startBytes = threads.getCurrentThreadAllocatedBytes();
				long start = System.nanoTime();
				// What a sync does: reads the world, builds the batch, hands both over.
				WaypointBatch.Builder batch = batch();
				for (int i = 0; i < 10; i++) {
					batch.update(ids.get(r * 10 + i), WaypointUpdate.builder().name("Renamed " + r + " " + i).build());
				}
				waypoints.edit(api().world().orElseThrow(), waypoints.revision(), batch.build());
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
			return apply(batch().add(NewWaypoint.builder(name, DimensionId.OVERWORLD, BlockCoord.of(at.getX() + east, at.getY(), at.getZ() + south)).build())).added().get(0);
		});
	}

	private static void removeIds(ClientGameTestContext context, List<UUID> ids) {
		context.runOnClient(mc -> {
			WaypointBatch.Builder batch = batch();
			ids.forEach(batch::remove);
			apply(batch);
		});
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
