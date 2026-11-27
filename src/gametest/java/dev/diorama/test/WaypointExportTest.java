package dev.diorama.test;

import dev.diorama.Maps;
import dev.diorama.Settings;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointExport;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.XaeroImport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;

/**
 * Exporting waypoints to files (opt-in: {@code waypoint-export}): waypoints in the Overworld and
 * the Nether, one hidden, two in folders of the player's own (one of them hidden), a death pin from
 * a real death and a temporary waypoint. Export waypoints in the list, clicked with the mouse,
 * writes Xaero's Minimap's files: read back with Diorama's Xaero's import parser, every field the
 * format carries matches, the folders are its sets and the temporary waypoint isn't there. Then the
 * player's way back: the waypoints deleted, the export's world folder copied into Xaero's folder,
 * and the list's own import button brings every one of them back. With Xaero's Minimap installed,
 * {@code waypoint-export-xaero} checks that Xaero's itself reads the files.
 */
final class WaypointExportTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final String MINES = "Mines: deep_one";
	private static final String OLD = "Old";

	/** What a waypoint was when exported: what the files should carry. */
	/** {@code mapOnly}: shown on the map alone, which Xaero's calls the World Map Local visibility type. */
	private record Expected(String name, String initials, int x, int y, int z, String dimension, int color, boolean shown, boolean death, String set, boolean mapOnly) {
	}

	private WaypointExportTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		containment();
		List<Waypoint> made = make(context, world);
		List<Expected> expected = context.computeOnClient(mc -> made.stream().map(w -> expect(WaypointStore.get(), w)).toList());
		Path folder = exportWithButton(context, "waypoint-export", expected.size());
		Path worldFolder = only(folder);

		// Read back as Diorama's import reads Xaero's files: everything it reads.
		List<XaeroImport.Entry> read;
		try {
			XaeroImport.Found found = XaeroImport.find(folder, List.of(worldFolder.getFileName().toString()), true, "", 64);
			if (found == null || found.chosen() == null) {
				throw new AssertionError("The import found no world in " + describe(folder));
			}
			read = found.chosen().entries();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		compare(expected, read.stream().map(XaeroImport.Entry::waypoint).toList(), "read back");
		if (read.stream().anyMatch(e -> !e.heightKnown())) {
			throw new AssertionError("A height was left out");
		}
		// What the import doesn't read: the sets, and which death is the latest.
		Map<String, List<String>> lines = new HashMap<>();
		lines.put(OVERWORLD, lines(worldFolder.resolve("dim%0").resolve("waypoints.txt")));
		lines.put(NETHER, lines(worldFolder.resolve("dim%-1").resolve("waypoints.txt")));
		for (List<String> file : lines.values()) {
			if (!file.get(0).equals("sets:gui.xaero_default:Mines- deep_one:Old")) {
				throw new AssertionError("The sets line should list both folders: " + file.get(0));
			}
		}
		for (Expected e : expected) {
			String line = lines.get(e.dimension()).stream().filter(l -> l.contains(":" + e.x() + ":" + e.y() + ":" + e.z() + ":")).findFirst()
				.orElseThrow(() -> new AssertionError("No line for " + e.name()));
			String[] fields = line.split(":");
			if (!fields[9].equals(e.set())) {
				throw new AssertionError(e.name() + " should be in set " + e.set() + ": " + line);
			}
			if (!fields[8].equals(e.death() ? "1" : "0")) {
				throw new AssertionError(e.name() + " should be of type " + (e.death() ? 1 : 0) + ": " + line);
			}
			if (fields.length != 14 || !fields[12].equals(e.mapOnly() ? "2" : "0")) {
				throw new AssertionError(e.name() + " should have visibility type " + (e.mapOnly() ? 2 : 0) + ": " + line);
			}
		}

		// Back the way a player would bring them back: deleted, the export put where Xaero's keeps
		// its files, and the list's import button.
		Path xaero = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("xaero").resolve("minimap"));
		Path copied = xaero.resolve(worldFolder.getFileName().toString());
		if (Files.exists(copied)) {
			throw new AssertionError("Xaero's already has a folder for this world: " + copied);
		}
		copy(worldFolder, copied, false);
		Set<UUID> ids = made.stream().map(w -> w.id).collect(Collectors.toSet());
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			for (Waypoint w : new ArrayList<>(store.all())) {
				if (ids.contains(w.id)) {
					store.remove(w);
				}
			}
		});
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		String label = "Import from Xaero's Minimap (" + expected.size() + ")";
		context.takeScreenshot("waypoint-export-4-import-offered");
		DioramaClientTest.clickWidget(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), label)));
		context.waitTicks(5);
		context.takeScreenshot("waypoint-export-5-imported");
		context.setScreen(() -> null);
		List<Waypoint> back = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind != Waypoint.Kind.TEMPORARY).toList());
		compare(expected, back, "imported again");
		refusesEscape(context);

		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			for (Waypoint w : new ArrayList<>(store.all())) {
				store.remove(w);
			}
			store.deleteFolder(MINES);
			store.deleteFolder(OLD);
			Settings.get().removeReachedDeaths = true;
		});
		delete(copied);
		namesThatClash(context);
		for (String rule : new String[] {"show_death_messages", "showDeathMessages"}) {
			world.getServer().runCommand("gamerule " + rule + " true");
		}
	}

	/**
	 * Map names that differ only by what a file name can't hold: in singleplayer each map not in
	 * use still gets a folder of its own in {@code Other maps}, none the same as another's in any
	 * case (as Windows compares them), with its waypoint. On a server a colon in a name is Xaero's
	 * {@code §§} in the file's name, which Xaero's writes for a sub-world named so.
	 */
	private static void namesThatClash(ClientGameTestContext context) {
		String[] names = {"Camp: 1", "Camp 1.", "camp 1", "Base: east_side"};
		Map<String, String> ids = new HashMap<>();
		context.runOnClient(mc -> {
			BlockPos at = mc.player.blockPosition();
			for (int i = 0; i < names.length; i++) {
				String id = Maps.get().create(OVERWORLD, names[i]);
				if (id == null) {
					throw new AssertionError("Couldn't make the map " + names[i]);
				}
				ids.put(names[i], id);
				WaypointStore.get().add(new Waypoint("Spot " + i, at.getX() + 50 * i, 64, at.getZ() + 70, OVERWORLD, Waypoint.PALETTE[i]));
			}
			Maps.get().use(OVERWORLD, Maps.MAIN);
		});
		Path check = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("diorama").resolve("export-clash-check"));
		if (Files.exists(check)) {
			delete(check);
		}
		try {
			WaypointExport.Result single = context.computeOnClient(mc -> {
				try {
					return WaypointExport.write(WaypointStore.get(), Maps.get(), check.resolve("single").resolve("World"), true);
				} catch (IOException e) {
					throw new AssertionError("The singleplayer export failed: " + e.getMessage(), e);
				}
			});
			if (single.count() != names.length) {
				throw new AssertionError("The singleplayer export wrote " + single.count() + " waypoints for " + names.length);
			}
			Path others = check.resolve("single").resolve("Other maps");
			Set<String> spots = new java.util.HashSet<>();
			Set<String> folders = new java.util.HashSet<>();
			for (Path other : folders(others)) {
				if (!folders.add(other.getFileName().toString().toLowerCase(java.util.Locale.ROOT))) {
					throw new AssertionError("Two maps' folders differ only in case: " + describe(others));
				}
				for (String line : fileWaypoints(other.resolve("World").resolve("dim%0").resolve("waypoints.txt"))) {
					spots.add(line.split("\\|")[1]);
				}
			}
			Set<String> wanted = new java.util.HashSet<>();
			for (int i = 0; i < names.length; i++) {
				wanted.add("Spot " + i);
			}
			if (folders.size() != names.length || !spots.equals(wanted)) {
				throw new AssertionError("Each map should have its own folder in Other maps with its waypoint: " + describe(others));
			}

			WaypointExport.Result server = context.computeOnClient(mc -> {
				try {
					return WaypointExport.write(WaypointStore.get(), Maps.get(), check.resolve("server").resolve("World"), false);
				} catch (IOException e) {
					throw new AssertionError("The server export failed: " + e.getMessage(), e);
				}
			});
			Path dim = check.resolve("server").resolve("World").resolve("dim%0");
			String escaped = "cm$" + ids.get("Base: east_side") + "_Base§§ east%us%side.txt";
			if (server.count() != names.length || !files(dim).contains(escaped)) {
				throw new AssertionError("The server export should name Base: east_side's file " + escaped + ": " + describe(dim));
			}
		} finally {
			if (Files.exists(check)) {
				delete(check);
			}
			context.runOnClient(mc -> {
				WaypointStore store = WaypointStore.get();
				for (Waypoint w : new ArrayList<>(store.all())) {
					store.remove(w);
				}
				for (Maps.Info map : Maps.get().list(OVERWORLD)) {
					if (!map.active()) {
						Maps.get().delete(OVERWORLD, map.id());
					}
				}
			});
		}
	}

	/**
	 * The rule that keeps every file inside the export: a dimension's folder and a map's file are
	 * one name each, with no way out.
	 */
	private static void containment() {
		Path world = Path.of("export-check", "waypoints_x", "World");
		try {
			Path fine = WaypointExport.within(world, "dim%0", "cm$a_Map.txt");
			if (!fine.equals(world.toAbsolutePath().normalize().resolve("dim%0").resolve("cm$a_Map.txt"))) {
				throw new AssertionError("An ordinary file went to " + fine);
			}
		} catch (IOException e) {
			throw new AssertionError("An ordinary file was refused", e);
		}
		List<String[]> refused = List.of(
			new String[] {"export-check/waypoints_x/World", "dim%0", "cm$../../../../escape_Map.txt"},
			new String[] {"export-check/waypoints_x/World", "dim%0", "cm$a/b_Map.txt"},
			new String[] {"export-check/waypoints_x/World", "..", "waypoints.txt"},
			new String[] {"export-check/waypoints_x/World", "dim%0/../..", "waypoints.txt"},
			new String[] {"export-check/waypoints_x/World", "dim%0", Path.of("escape.txt").toAbsolutePath().toString()});
		for (String[] c : refused) {
			try {
				Path got = WaypointExport.within(Path.of(c[0]), c[1], c[2]);
				throw new AssertionError(c[1] + " / " + c[2] + " in " + c[0] + " should be refused, not written to " + got);
			} catch (IOException expectedRefusal) {
				// The export fails with this, and says so.
			}
		}
	}

	/**
	 * Exports that must fail, and say so, and leave nothing: a hand-edited waypoint file whose map ID
	 * leads out of the export on a server, where map IDs name the files, after the Overworld's
	 * ordinary file was already written; and a save folder named Home and 64 underscores, which
	 * Xaero's escapes to a 260-character name no file system takes. A test can't name its world or
	 * be on a server and in singleplayer at once, so both go through {@link WaypointExport#run(String, boolean)}.
	 */
	private static void refusesEscape(ClientGameTestContext context) {
		Path exports = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("diorama").resolve("exports"));
		Path diorama = exports.getParent();
		Set<Path> before = folders(exports);
		// The first makes a real cm$ folder in the dimension's; the second climbs out through it,
		// past the world's folder and the export's, into the exports folder. Without a folder to
		// pass through, the write would fail anyway, whatever the check said.
		Waypoint step = new Waypoint("Step", 1, 64, 1, OVERWORLD, Waypoint.PALETTE[0]);
		Waypoint bad = new Waypoint("Escape", 2, 64, 2, OVERWORLD, Waypoint.PALETTE[0]);
		context.runOnClient(mc -> {
			WaypointStore.get().add(step);
			WaypointStore.get().add(bad);
			// After add, which notes the map in use: as if waypoints.json said so.
			step.map = "/x";
			bad.map = "/../../../../escape";
		});
		WaypointExport.Told escape = context.computeOnClient(mc -> WaypointExport.run(null, false));
		context.waitTicks(2);
		context.takeScreenshot("waypoint-export-6-refused");
		context.runOnClient(mc -> {
			WaypointStore.get().remove(step);
			WaypointStore.get().remove(bad);
		});
		String notice = context.computeOnClient(mc -> lastChat(mc));
		if (escape.done() || !notice.startsWith("Couldn't export the waypoints: ")) {
			throw new AssertionError("A map ID leading out of the export should fail it; chat says " + notice);
		}
		assertNothingLeft(exports, before, "A map ID leading out of the export");
		try (Stream<Path> files = Files.walk(diorama)) {
			List<Path> escaped = files.filter(p -> p.getFileName().toString().startsWith("escape")).toList();
			if (!escaped.isEmpty()) {
				throw new AssertionError("The export wrote outside its folder: " + escaped);
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		String longWorld = XaeroImport.singleplayerFolders("Home" + "_".repeat(64)).get(0);
		WaypointExport.Told tooLong = context.computeOnClient(mc -> WaypointExport.run(longWorld, true));
		notice = context.computeOnClient(mc -> lastChat(mc));
		dev.diorama.Diorama.LOGGER.info("waypoint-export: a {}-character world folder: {}", longWorld.length(), notice);
		if (tooLong.done() || !notice.startsWith("Couldn't export the waypoints: ")) {
			throw new AssertionError("A world folder too long for a file should fail the export; chat says " + notice);
		}
		assertNothingLeft(exports, before, "A world folder too long for a file");
	}

	private static void assertNothingLeft(Path exports, Set<Path> before, String what) {
		Set<Path> after = folders(exports);
		if (!after.equals(before)) {
			throw new AssertionError(what + " left " + after.stream().filter(p -> !before.contains(p)).toList());
		}
	}

	/** The first visit's export, for Xaero's folder between the visits (waypoint-export-xaero). */
	private static Path forXaero;
	private static List<Expected> forXaeroExpected = List.of();

	/**
	 * With Xaero's Minimap installed (opt-in: {@code waypoint-export-xaero}): the same waypoints,
	 * and two more maps in the Overworld with one waypoint each, exported with the button. The map
	 * in use is in Xaero's layout and each other map is a world folder of its own in
	 * {@code Other maps}. Between the visits the world's folder goes over the files Xaero's saved for
	 * this world on leaving ({@link #intoXaero}), and on the next visit Xaero's own code has read
	 * every line of it into the one world it opens, and nothing else ({@link #readByXaero}).
	 */
	static void forXaero(ClientGameTestContext context, TestSingleplayerContext world) {
		// Xaero's sets up its world a few ticks after joining; it never does without Xaero's installed.
		context.waitFor(mc -> XaeroRealTest.xaeroWorld() != null, 1200);
		List<Waypoint> made = make(context, world);
		forXaeroExpected = context.computeOnClient(mc -> made.stream().map(w -> expect(WaypointStore.get(), w)).toList());
		// Two more maps in the Overworld, a waypoint on each; then back on the main map, as in use.
		context.runOnClient(mc -> {
			BlockPos at = mc.player.blockPosition();
			String[][] others = {{"Second base", "Far camp"}, {"Third base", "Lake hut"}};
			for (int i = 0; i < others.length; i++) {
				if (Maps.get().create(OVERWORLD, others[i][0]) == null) {
					throw new AssertionError("Couldn't make the map " + others[i][0]);
				}
				WaypointStore.get().add(new Waypoint(others[i][1], at.getX() + 300 + 40 * i, 66, at.getZ() - 300, OVERWORLD, Waypoint.PALETTE[4 + i]));
			}
			Maps.get().use(OVERWORLD, Maps.MAIN);
		});
		Path export = exportWithButton(context, "waypoint-export-xaero", forXaeroExpected.size() + 2);
		Set<Path> inside = folders(export);
		Path others = export.resolve("Other maps");
		if (inside.size() != 2 || !inside.remove(others)) {
			throw new AssertionError("The export should hold the world's folder and Other maps: " + describe(export));
		}
		forXaero = inside.iterator().next();
		String name = forXaero.getFileName().toString();
		Map<String, List<String>> wanted = Map.of("Overworld - Second base", List.of("Far camp"), "Overworld - Third base", List.of("Lake hut"));
		Map<String, List<String>> got = new HashMap<>();
		for (Path other : folders(others)) {
			if (!folders(other).equals(Set.of(other.resolve(name))) || !files(other.resolve(name).resolve("dim%0")).equals(List.of("waypoints.txt"))) {
				throw new AssertionError("Each other map should be a world folder of its own: " + describe(other));
			}
			got.put(other.getFileName().toString(), fileWaypoints(other.resolve(name).resolve("dim%0").resolve("waypoints.txt")).stream().map(l -> l.split("\\|")[1]).toList());
		}
		if (!got.equals(wanted)) {
			throw new AssertionError("Other maps holds " + got);
		}
		if (!files(forXaero.resolve("dim%0")).equals(List.of("waypoints.txt"))) {
			throw new AssertionError("The world's Overworld folder should hold the map in use only: " + describe(forXaero));
		}
		context.runOnClient(mc -> Settings.get().removeReachedDeaths = true);
	}

	/** Between the visits, as a player would: the export's world folder copied into Xaero's, over what's there. */
	static void intoXaero(ClientGameTestContext context) {
		Path xaero = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("xaero").resolve("minimap"));
		Path into = xaero.resolve(forXaero.getFileName().toString());
		dev.diorama.Diorama.LOGGER.info("waypoint-export-xaero: Xaero's saved {}", Files.isDirectory(into) ? describe(into) : "nothing for this world");
		copy(forXaero, into, true);
	}

	/**
	 * Back in the world: Xaero's Minimap has one world in the Overworld, the one it opened, holding
	 * the map in use's waypoints as Xaero's own code read them, and none of another map's.
	 */
	static void readByXaero(ClientGameTestContext context) {
		context.waitFor(mc -> XaeroRealTest.xaeroWorld() != null, 1200);
		// Each of Xaero's worlds here by its node: set|name|initials|x|y|z|colour|disabled|type|visibility for its waypoints.
		Map<String, List<String>> worlds = context.computeOnClient(mc -> xaeroWorlds());
		String opened = context.computeOnClient(mc -> xaeroNode(XaeroRealTest.xaeroWorld()));
		dev.diorama.Diorama.LOGGER.info("waypoint-export-xaero: Xaero's Minimap opened {} and has {}", opened, worlds);
		List<String> wanted = fileWaypoints(forXaero.resolve("dim%0").resolve("waypoints.txt"));
		List<String> overworld = forXaeroExpected.stream().filter(e -> e.dimension().equals(OVERWORLD)).map(Expected::name).toList();
		if (wanted.size() != overworld.size()) {
			throw new AssertionError("The export's Overworld file has " + wanted.size() + " waypoints for " + overworld);
		}
		if (worlds.size() != 1) {
			throw new AssertionError("Xaero's Minimap should have one world in the Overworld, and has " + worlds);
		}
		List<String> got = worlds.get(opened);
		if (!new java.util.HashSet<>(got).equals(new java.util.HashSet<>(wanted)) || got.size() != wanted.size()) {
			throw new AssertionError("Xaero's Minimap opened " + opened + " with\n" + String.join("\n", got) + "\nfrom a file saying\n" + String.join("\n", wanted));
		}
		for (String name : overworld) {
			if (got.stream().noneMatch(line -> line.split("\\|")[1].equals(name))) {
				throw new AssertionError("Xaero's Minimap has no " + name + ": " + got);
			}
		}
		// Xaero's own waypoint list, as a player opens it.
		context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_U);
		context.waitTicks(10);
		context.takeScreenshot("waypoint-export-xaero-4-u-key");
		String screen = context.computeOnClient(mc -> mc.gui.screen() == null ? "nothing" : mc.gui.screen().getClass().getName());
		dev.diorama.Diorama.LOGGER.info("waypoint-export-xaero: U opened {}", screen);
		if (!screen.equals("xaero.common.gui.GuiWaypoints")) {
			throw new AssertionError("U should open Xaero's Minimap's waypoint list, and opened " + screen);
		}
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			for (Maps.Info map : Maps.get().list(OVERWORLD)) {
				if (!map.active()) {
					Maps.get().delete(OVERWORLD, map.id());
				}
			}
		});
	}

	/** A file's waypoints as {@link #xaeroWaypoints} gives Xaero's. */
	private static List<String> fileWaypoints(Path file) {
		List<String> out = new ArrayList<>();
		for (String line : lines(file)) {
			if (line.startsWith("waypoint:")) {
				String[] f = line.split(":");
				out.add(f[9] + "|" + f[1].replace("§§", ":") + "|" + f[2].replace("§§", ":") + "|" + f[3] + "|" + f[4] + "|" + f[5]
					+ "|" + f[6] + "|" + f[7] + "|" + f[8] + "|" + f[12]);
			}
		}
		return out;
	}

	/** Every world Xaero's has in the dimension the player is in, by node, with its waypoints. */
	private static Map<String, List<String>> xaeroWorlds() {
		try {
			Object opened = XaeroRealTest.xaeroWorld();
			Object container = opened.getClass().getMethod("getContainer").invoke(opened);
			Map<String, List<String>> out = new java.util.TreeMap<>();
			for (Object world : (Iterable<?>) container.getClass().getMethod("getWorlds").invoke(container)) {
				out.put(xaeroNode(world), xaeroWaypoints(world));
			}
			return out;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Couldn't list Xaero's Minimap's worlds", e);
		}
	}

	private static String xaeroNode(Object world) {
		try {
			return (String) world.getClass().getMethod("getNode").invoke(world);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static List<String> xaeroWaypoints(Object world) {
		try {
			List<String> out = new ArrayList<>();
			for (Object set : (Iterable<?>) world.getClass().getMethod("getIterableWaypointSets").invoke(world)) {
				String setName = (String) set.getClass().getMethod("getName").invoke(set);
				for (Object w : (Iterable<?>) set.getClass().getMethod("getWaypoints").invoke(set)) {
					Class<?> c = w.getClass();
					out.add(setName + "|" + c.getMethod("getName").invoke(w) + "|" + c.getMethod("getInitials").invoke(w)
						+ "|" + c.getMethod("getX").invoke(w) + "|" + c.getMethod("getY").invoke(w) + "|" + c.getMethod("getZ").invoke(w)
						+ "|" + ((Enum<?>) c.getMethod("getWaypointColor").invoke(w)).ordinal() + "|" + c.getMethod("isDisabled").invoke(w)
						+ "|" + c.getMethod("getWaypointType").invoke(w) + "|" + c.getMethod("getVisibilityType").invoke(w));
				}
			}
			return out;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Couldn't read Xaero's Minimap's waypoints", e);
		}
	}

	/**
	 * This section's waypoints, everything else in the store gone: two in the Overworld and the
	 * Nether each, one hidden, two in a folder, one in a hidden folder, a death pin from a real death
	 * and a temporary one, which the export leaves out.
	 */
	private static List<Waypoint> make(ClientGameTestContext context, TestSingleplayerContext world) {
		for (String rule : new String[] {"show_death_messages", "showDeathMessages"}) {
			world.getServer().runCommand("gamerule " + rule + " false");
		}
		context.runOnClient(mc -> {
			Settings.get().deathMarkers = true;
			Settings.get().removeReachedDeaths = false;
		});
		// What earlier sections left would be exported too: this section's waypoints only.
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			for (Waypoint w : new ArrayList<>(store.kept())) {
				store.remove(w);
			}
			for (String name : store.customFolders()) {
				store.deleteFolder(name);
			}
		});
		BlockPos home = context.computeOnClient(mc -> mc.player.blockPosition());
		Waypoint death = die(context, world, home.offset(40, 0, 0));
		List<Waypoint> made = new ArrayList<>();
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.addFolder(MINES);
			store.addFolder(OLD);
			store.setFolderHidden(WaypointStore.customKey(OLD), true);
			made.add(new Waypoint("Home", home.getX(), home.getY(), home.getZ(), OVERWORLD, Waypoint.PALETTE[0]));
			Waypoint mine = new Waypoint("Iron: east", home.getX() + 120, 12, home.getZ() - 64, OVERWORLD, Waypoint.PALETTE[3]);
			mine.folder = MINES;
			made.add(mine);
			Waypoint secret = new Waypoint("Secret", home.getX() - 90, 70, home.getZ() + 30, OVERWORLD, Waypoint.PALETTE[6]);
			secret.visible = false;
			made.add(secret);
			// Kept to the map: Xaero's World Map visibility type, out of the world and off the minimap.
			Waypoint lookout = new Waypoint("Lookout", home.getX() - 40, 90, home.getZ() - 70, OVERWORLD, Waypoint.PALETTE[3]);
			lookout.showInWorld = false;
			lookout.showOnMinimap = false;
			made.add(lookout);
			Waypoint camp = new Waypoint("Old camp", home.getX() + 15, 64, home.getZ() + 200, OVERWORLD, Waypoint.PALETTE[14]);
			camp.folder = OLD;
			made.add(camp);
			Waypoint hub = new Waypoint("Hub", 12, 70, -8, NETHER, Waypoint.PALETTE[2]);
			hub.initials = "NH";
			made.add(hub);
			Waypoint fortress = new Waypoint("Fortress", -140, 64, 220, NETHER, Waypoint.PALETTE[12]);
			fortress.folder = MINES;
			made.add(fortress);
			made.forEach(store::add);
			dev.diorama.waypoint.Waypoints.addTemporary(home.offset(5, 0, 5), OVERWORLD);
		});
		made.add(death);
		return made;
	}

	/**
	 * Export waypoints in the list, pointed at with the mouse for its tooltip and clicked: the
	 * folder it made, once chat has said so.
	 */
	private static Path exportWithButton(ClientGameTestContext context, String shots, int count) {
		Path exports = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("diorama").resolve("exports"));
		Set<Path> before = folders(exports);
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		Button export = context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "Export waypoints"));
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		context.getInput().setCursorPos((export.getX() + export.getWidth() / 2.0) * scale, (export.getY() + export.getHeight() / 2.0) * scale);
		context.waitTicks(30);
		context.takeScreenshot(shots + "-1-button");
		DioramaClientTest.clickWidget(context, export);
		context.waitTicks(5);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
		context.takeScreenshot(shots + "-2-done");
		context.setScreen(() -> null);
		context.waitTicks(5);
		context.takeScreenshot(shots + "-3-chat");

		Set<Path> after = folders(exports);
		after.removeAll(before);
		if (after.size() != 1) {
			throw new AssertionError("Export waypoints made " + after.size() + " folders in " + exports + ": " + after);
		}
		Path folder = after.iterator().next();
		dev.diorama.Diorama.LOGGER.info("waypoint-export: wrote {}", describe(folder));
		String notice = context.computeOnClient(mc -> lastChat(mc));
		if (!notice.equals("Exported " + count + " waypoints to " + folder.getFileName())) {
			throw new AssertionError("Chat says " + notice);
		}

		return folder;
	}

	/** What the files should say of a waypoint, from what the player sees of it now. */
	private static Expected expect(WaypointStore store, Waypoint w) {
		String folder = store.folderName(w.folder);
		return new Expected(w.shownName(), w.label(), w.x, w.y, w.z, w.dimension, w.color, store.shown(w), w.kind == Waypoint.Kind.DEATH,
			folder == null ? "gui.xaero_default" : folder.replace(':', '-'), !w.showInWorld && !w.showOnMinimap && w.showOnMap);
	}

	/** Every expected waypoint is there once, as it was, and nothing else is. */
	private static void compare(List<Expected> expected, List<Waypoint> got, String how) {
		if (got.size() != expected.size()) {
			throw new AssertionError(got.size() + " waypoints " + how + ", " + expected.size() + " exported: "
				+ got.stream().map(w -> w.name).toList());
		}
		for (Expected e : expected) {
			List<Waypoint> same = got.stream().filter(w -> w.dimension.equals(e.dimension()) && w.x == e.x() && w.y == e.y() && w.z == e.z()).toList();
			if (same.size() != 1) {
				throw new AssertionError(same.size() + " waypoints " + how + " at " + e.name() + "'s spot");
			}
			Waypoint w = same.get(0);
			List<String> wrong = new ArrayList<>();
			if (!w.name.equals(e.name())) {
				wrong.add("name " + w.name + " for " + e.name());
			}
			if (!w.initials.equals(e.initials())) {
				wrong.add("initials " + w.initials + " for " + e.initials());
			}
			if (w.color != e.color()) {
				wrong.add("colour " + Integer.toHexString(w.color) + " for " + Integer.toHexString(e.color()));
			}
			if (w.visible != e.shown()) {
				wrong.add(w.visible ? "shown, though hidden" : "hidden, though shown");
			}
			if (w.showInWorld == e.mapOnly() || w.showOnMinimap == e.mapOnly() || !w.showOnMap) {
				wrong.add("shown in the world " + w.showInWorld + ", on the minimap " + w.showOnMinimap + ", on the map " + w.showOnMap
					+ (e.mapOnly() ? " for the map alone" : " for everywhere"));
			}
			if ((w.kind == Waypoint.Kind.DEATH) != e.death()) {
				wrong.add("kind " + w.kind);
			}
			if (!wrong.isEmpty()) {
				throw new AssertionError(e.name() + " " + how + ": " + wrong);
			}
		}
	}

	/** Takes the player to a spot, kills them, respawns them, and returns the new death pin. */
	private static Waypoint die(ClientGameTestContext context, TestSingleplayerContext world, BlockPos spot) {
		world.getServer().runCommand("tp @a " + spot.getX() + " " + spot.getY() + " " + spot.getZ());
		context.waitTicks(10);
		Set<UUID> before = context.computeOnClient(mc -> WaypointStore.get().all().stream().map(w -> w.id).collect(Collectors.toSet()));
		world.getServer().runCommand("kill @a");
		context.waitFor(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.kind == Waypoint.Kind.DEATH && !before.contains(w.id)), 200);
		Waypoint death = context.computeOnClient(mc -> WaypointStore.get().all().stream()
			.filter(w -> w.kind == Waypoint.Kind.DEATH && !before.contains(w.id)).findFirst().orElseThrow());
		// The death screen's buttons wake after a second.
		context.waitTicks(25);
		context.clickScreenButton("deathScreen.respawn");
		context.waitFor(mc -> mc.player != null && mc.player.isAlive() && mc.gui.screen() == null, 200);
		return death;
	}

	private static String lastChat(net.minecraft.client.Minecraft mc) {
		return DioramaClientTest.chatMessages(mc).get(0).content().getString();
	}

	private static Set<Path> folders(Path folder) {
		if (!Files.isDirectory(folder)) {
			return new java.util.HashSet<>();
		}
		try (Stream<Path> list = Files.list(folder)) {
			return list.filter(Files::isDirectory).collect(Collectors.toCollection(java.util.HashSet::new));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/** The names of the files in a folder, sorted. */
	private static List<String> files(Path folder) {
		try (Stream<Path> list = Files.list(folder)) {
			return list.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static Path only(Path folder) {
		Set<Path> inside = folders(folder);
		if (inside.size() != 1) {
			throw new AssertionError("The export should hold one world's folder: " + describe(folder));
		}
		return inside.iterator().next();
	}

	private static List<String> lines(Path file) {
		try {
			return Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError("Couldn't read " + file, e);
		}
	}

	/** Every file in the export and what it says, for the log. */
	private static String describe(Path folder) {
		StringBuilder out = new StringBuilder(folder.toString());
		try (Stream<Path> files = Files.walk(folder)) {
			for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				out.append("\n== ").append(folder.relativize(file)).append('\n').append(Files.readString(file, StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			out.append(" (").append(e).append(')');
		}
		return out.toString();
	}

	/** Copies a folder's files, over files already there only when {@code replace}. */
	private static void copy(Path from, Path to, boolean replace) {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path file : files.toList()) {
				Path target = to.resolve(from.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(target);
				} else {
					if (replace) {
						Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					} else {
						Files.copy(file, target);
					}
				}
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void delete(Path folder) {
		try (Stream<Path> files = Files.walk(folder)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
