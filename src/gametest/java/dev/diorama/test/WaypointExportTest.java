package dev.diorama.test;

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
	private record Expected(String name, String initials, int x, int y, int z, String dimension, int color, boolean shown, boolean death, String set) {
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
		for (String rule : new String[] {"show_death_messages", "showDeathMessages"}) {
			world.getServer().runCommand("gamerule " + rule + " true");
		}
	}

	/**
	 * The rule that keeps every file inside the export: a dimension's folder and a map's file are
	 * one name each, with no way out, and no name is longer than a file system allows.
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
		// A save folder named Home and 64 underscores: Xaero's escapes it to 260 characters.
		String longWorld = XaeroImport.singleplayerFolders("Home" + "_".repeat(64)).get(0);
		List<String[]> refused = List.of(
			new String[] {"export-check/waypoints_x/World", "dim%0", "cm$../../../../escape_Map.txt"},
			new String[] {"export-check/waypoints_x/World", "dim%0", "cm$a/b_Map.txt"},
			new String[] {"export-check/waypoints_x/World", "..", "waypoints.txt"},
			new String[] {"export-check/waypoints_x/World", "dim%0/../..", "waypoints.txt"},
			new String[] {"export-check/waypoints_x/World", "dim%0", Path.of("escape.txt").toAbsolutePath().toString()},
			new String[] {"export-check/waypoints_x/" + longWorld, "dim%0", "waypoints.txt"});
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
	 * A hand-edited waypoint file whose map ID leads out of the export: the export fails and says
	 * so, after the Overworld's ordinary file was already written, and leaves no folder behind.
	 */
	private static void refusesEscape(ClientGameTestContext context) {
		Path exports = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("diorama").resolve("exports"));
		Set<Path> before = folders(exports);
		Waypoint bad = new Waypoint("Escape", 1, 64, 1, OVERWORLD, Waypoint.PALETTE[0]);
		context.runOnClient(mc -> {
			WaypointStore.get().add(bad);
			// After add, which notes the map in use: as if waypoints.json said so.
			bad.map = "../../../../escape";
		});
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		DioramaClientTest.clickWidget(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "Export waypoints")));
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(5);
		context.takeScreenshot("waypoint-export-6-refused");
		context.setScreen(() -> null);
		context.runOnClient(mc -> WaypointStore.get().remove(bad));
		String notice = context.computeOnClient(mc -> lastChat(mc));
		if (!notice.startsWith("Couldn't export the waypoints: ")) {
			throw new AssertionError("A map ID leading out of the export should fail it; chat says " + notice);
		}
		Set<Path> after = folders(exports);
		if (!after.equals(before)) {
			throw new AssertionError("A failed export left " + after.stream().filter(p -> !before.contains(p)).toList());
		}
		Path diorama = exports.getParent();
		try (Stream<Path> files = Files.walk(diorama.getParent(), 3)) {
			List<Path> escaped = files.filter(p -> p.getFileName().toString().startsWith("escape")).toList();
			if (!escaped.isEmpty()) {
				throw new AssertionError("The export wrote outside its folder: " + escaped);
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/** The first visit's export, for Xaero's folder between the visits (waypoint-export-xaero). */
	private static Path forXaero;
	private static List<Expected> forXaeroExpected = List.of();

	/**
	 * With Xaero's Minimap installed (opt-in: {@code waypoint-export-xaero}): the same waypoints
	 * exported with the button. Between the visits the export goes over the files Xaero's saved for
	 * this world on leaving ({@link #intoXaero}), and on the next visit Xaero's own code has read
	 * every line of it ({@link #readByXaero}).
	 */
	static void forXaero(ClientGameTestContext context, TestSingleplayerContext world) {
		// Xaero's sets up its world a few ticks after joining; it never does without Xaero's installed.
		context.waitFor(mc -> XaeroRealTest.xaeroWorld() != null, 1200);
		List<Waypoint> made = make(context, world);
		forXaeroExpected = context.computeOnClient(mc -> made.stream().map(w -> expect(WaypointStore.get(), w)).toList());
		forXaero = only(exportWithButton(context, "waypoint-export-xaero", forXaeroExpected.size()));
		context.runOnClient(mc -> Settings.get().removeReachedDeaths = true);
	}

	/** Between the visits, as a player would: the export's world folder copied into Xaero's, over what's there. */
	static void intoXaero(ClientGameTestContext context) {
		Path xaero = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("xaero").resolve("minimap"));
		Path into = xaero.resolve(forXaero.getFileName().toString());
		dev.diorama.Diorama.LOGGER.info("waypoint-export-xaero: Xaero's saved {}", Files.isDirectory(into) ? describe(into) : "nothing for this world");
		copy(forXaero, into, true);
	}

	/** Back in the world: every waypoint Xaero's Minimap has here, as Xaero's own code read the files. */
	static void readByXaero(ClientGameTestContext context) {
		context.waitFor(mc -> XaeroRealTest.xaeroWorld() != null, 1200);
		// set|name|initials|x|y|z|colour|disabled|type for each of Xaero's waypoints in this dimension.
		List<String> got = context.computeOnClient(mc -> xaeroWaypoints());
		dev.diorama.Diorama.LOGGER.info("waypoint-export-xaero: Xaero's Minimap has {}", got);
		List<String> file = lines(forXaero.resolve("dim%0").resolve("waypoints.txt"));
		List<String> wanted = new ArrayList<>();
		for (String line : file) {
			if (line.startsWith("waypoint:")) {
				String[] f = line.split(":");
				wanted.add(f[9] + "|" + f[1].replace("§§", ":") + "|" + f[2].replace("§§", ":") + "|" + f[3] + "|" + f[4] + "|" + f[5]
					+ "|" + f[6] + "|" + f[7] + "|" + f[8]);
			}
		}
		List<String> overworld = forXaeroExpected.stream().filter(e -> e.dimension().equals(OVERWORLD)).map(Expected::name).toList();
		if (wanted.size() != overworld.size()) {
			throw new AssertionError("The export's Overworld file has " + wanted.size() + " waypoints for " + overworld);
		}
		if (!new java.util.HashSet<>(got).equals(new java.util.HashSet<>(wanted)) || got.size() != wanted.size()) {
			throw new AssertionError("Xaero's Minimap read\n" + String.join("\n", got) + "\nfrom a file saying\n" + String.join("\n", wanted));
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
		String opened = context.computeOnClient(mc -> mc.gui.screen() == null ? "nothing" : mc.gui.screen().getClass().getName());
		dev.diorama.Diorama.LOGGER.info("waypoint-export-xaero: U opened {}", opened);
		if (!opened.equals("xaero.common.gui.GuiWaypoints")) {
			throw new AssertionError("U should open Xaero's Minimap's waypoint list, and opened " + opened);
		}
		context.setScreen(() -> null);
	}

	private static List<String> xaeroWaypoints() {
		try {
			Object world = XaeroRealTest.xaeroWorld();
			List<String> out = new ArrayList<>();
			for (Object set : (Iterable<?>) world.getClass().getMethod("getIterableWaypointSets").invoke(world)) {
				String setName = (String) set.getClass().getMethod("getName").invoke(set);
				for (Object w : (Iterable<?>) set.getClass().getMethod("getWaypoints").invoke(set)) {
					Class<?> c = w.getClass();
					out.add(setName + "|" + c.getMethod("getName").invoke(w) + "|" + c.getMethod("getInitials").invoke(w)
						+ "|" + c.getMethod("getX").invoke(w) + "|" + c.getMethod("getY").invoke(w) + "|" + c.getMethod("getZ").invoke(w)
						+ "|" + ((Enum<?>) c.getMethod("getWaypointColor").invoke(w)).ordinal() + "|" + c.getMethod("isDisabled").invoke(w)
						+ "|" + c.getMethod("getWaypointType").invoke(w));
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
			folder == null ? "gui.xaero_default" : folder.replace(':', '-'));
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
