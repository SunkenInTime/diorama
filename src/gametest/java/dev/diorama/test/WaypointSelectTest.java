package dev.diorama.test;

import dev.diorama.waypoint.FolderMenu;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointSort;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;

/**
 * Selecting several rows of the waypoint list ({@code waypoint-select}, issue #244): a Shift click
 * selects the rows up to it and a Ctrl click adds one or takes it out; several rows have Move, Hide
 * or Show and Delete where one has Edit, Hide, Teleport and Delete; Move sends them all to the
 * folder picked, or back to their own dimensions' folders; Delete says how many and asks for a
 * second click. Only the waypoints and folders made here are checked and removed.
 */
final class WaypointSelectTest {
	private static final String CAMP = "Camp";
	private static final String TRIP = "Trip north";

	private WaypointSelectTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		// Spot 1 to 12 by distance, then one in the Nether, which sorts after them.
		List<Waypoint> spots = context.computeOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.setSort(WaypointSort.DISTANCE, false);
			String here = mc.level.dimension().identifier().toString();
			store.addFolder(CAMP);
			store.addFolder(TRIP);
			List<Waypoint> made = new ArrayList<>();
			for (int i = 1; i <= 12; i++) {
				Waypoint waypoint = new Waypoint("Spot " + i, at.getX() + i * 20, at.getY(), at.getZ(), here, Waypoint.PALETTE[i % Waypoint.PALETTE.length]);
				waypoint.folder = CAMP;
				store.add(waypoint);
				made.add(waypoint);
			}
			Waypoint nether = new Waypoint("Nether spot", at.getX(), at.getY(), at.getZ(), "minecraft:the_nether", Waypoint.PALETTE[2]);
			nether.folder = CAMP;
			store.add(nether);
			made.add(nether);
			return made;
		});
		// Checked at the end, so a failing run still takes every picture.
		List<String> wrong = new ArrayList<>();

		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		for (int i = 0; i < 30 && !context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderName()).equals(CAMP); i++) {
			DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), ">")));
		}

		// One row, as before: Edit, Hide, Teleport, Delete, and no Move.
		click(context, spots.get(0), false, false);
		expect(context, wrong, "a click on Spot 1", List.of("Spot 1"));
		if (!rowButtons(context).equals(List.of("Edit", "Hide", "Teleport", "Delete")) && !rowButtons(context).equals(List.of("Edit", "Hide", "Delete"))) {
			wrong.add("one selected row had the buttons " + rowButtons(context));
		}
		context.takeScreenshot("select-01-one");
		// The same screen in the smallest window, at 1280 x 720 and on a full 1080p screen at GUI
		// scales 3 and 4: the hint line clear of the list and Done. At scale 4 the rows fill the list
		// to its end, so the hint has the least room it ever gets. Then in Belarusian, the longest.
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		for (String language : new String[] {"en_us", "be_by"}) {
			if (!language.equals("en_us")) {
				DioramaClientTest.switchLanguage(context, language);
			}
			for (int[] size : new int[][] {{640, 480, 2}, {1280, 720, 3}, {1920, 1080, 3}, {1920, 1080, 4}}) {
				context.getInput().resizeWindow(size[0], size[1]);
				DioramaClientTest.setGuiScale(context, size[2]);
				context.waitTicks(5);
				String name = "select-01-hint-" + size[0] + "x" + size[1] + (size[1] == 1080 ? "-scale-" + size[2] : "") + (language.equals("en_us") ? "" : "-" + language);
				context.takeScreenshot(name);
			}
		}
		DioramaClientTest.switchLanguage(context, "en_us");
		context.getInput().resizeWindow(1600, 900);
		DioramaClientTest.setGuiScale(context, guiScale);
		context.waitTicks(5);

		// Shift click: the rows from the one clicked before to this one.
		click(context, spots.get(3), true, false);
		expect(context, wrong, "Shift click on Spot 4", List.of("Spot 1", "Spot 2", "Spot 3", "Spot 4"));
		if (!rowButtons(context).equals(List.of("Move", "Hide", "Delete"))) {
			wrong.add("four selected rows had the buttons " + rowButtons(context));
		}
		context.takeScreenshot("select-02-shift");

		// Ctrl click adds a row, and takes out one that's selected.
		click(context, spots.get(5), false, true);
		click(context, spots.get(1), false, true);
		expect(context, wrong, "Ctrl click on Spot 6 then Spot 2", List.of("Spot 1", "Spot 3", "Spot 4", "Spot 6"));
		context.takeScreenshot("select-03-ctrl");

		// Hide hides them all, and then reads Show and shows them all.
		clickRowButton(context, "Hide");
		if (spots.get(0).visible || spots.get(2).visible || spots.get(3).visible || spots.get(5).visible || !spots.get(1).visible) {
			wrong.add("Hide on four rows didn't hide those four and only them");
		}
		expect(context, wrong, "after Hide", List.of("Spot 1", "Spot 3", "Spot 4", "Spot 6"));
		context.takeScreenshot("select-04-hidden");
		clickRowButton(context, "Show");
		if (!spots.get(0).visible || !spots.get(2).visible || !spots.get(3).visible || !spots.get(5).visible) {
			wrong.add("Show on four hidden rows didn't show them");
		}

		// Move opens the folders; a pick sends all four there.
		clickRowButton(context, "Move");
		List<String> labels = context.computeOnClient(mc -> {
			FolderMenu menu = ((WaypointListScreen) mc.gui.screen()).folderMenu();
			return menu == null ? List.<String>of() : menu.labels();
		});
		if (labels.size() < 3 || !labels.get(0).startsWith("Overworld ") || !labels.contains(CAMP + " 13") || !labels.contains(TRIP + " 0")) {
			wrong.add("Move's list of folders was " + labels);
		}
		hover(context, TRIP);
		context.takeScreenshot("select-05-move");
		pick(context, TRIP, wrong);
		for (int i : new int[] {0, 2, 3, 5}) {
			if (!spots.get(i).folder.equals(TRIP)) {
				wrong.add(spots.get(i).name + " is in \"" + spots.get(i).folder + "\" after Move to " + TRIP);
			}
		}
		if (!spots.get(1).folder.equals(CAMP)) {
			wrong.add("Spot 2, taken out of the selection, moved too");
		}
		expect(context, wrong, "after Move", List.of());
		if (!context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderName()).equals(CAMP)) {
			wrong.add("the list left Camp after Move");
		}
		context.takeScreenshot("select-06-moved");

		// Two dimensions' rows go back to their own dimensions' folders together.
		click(context, spots.get(4), false, false);
		click(context, spots.get(12), false, true);
		expect(context, wrong, "Spot 5 and Nether spot", List.of("Spot 5", "Nether spot"));
		clickRowButton(context, "Move");
		String first = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderMenu().labels().get(0));
		if (!first.equals("Their own dimensions")) {
			wrong.add("Move's first line for rows of two dimensions was " + first);
		}
		context.takeScreenshot("select-07-two-dimensions");
		pick(context, "Their own dimensions", wrong);
		if (!spots.get(4).folder.isEmpty() || !spots.get(12).folder.isEmpty()) {
			wrong.add("Spot 5 and Nether spot didn't go back to their dimensions' folders");
		}

		// Delete says how many, and takes them on the second click.
		click(context, spots.get(6), false, false);
		click(context, spots.get(8), true, false);
		expect(context, wrong, "Spot 7 to 9", List.of("Spot 7", "Spot 8", "Spot 9"));
		clickRowButton(context, "Delete");
		if (!rowButtons(context).equals(List.of("Click again to delete 3 waypoints"))) {
			wrong.add("Delete on three rows asked " + rowButtons(context));
		}
		context.takeScreenshot("select-08-delete-asks");
		clickRowButton(context, "Click again to delete 3 waypoints");
		List<String> left = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(spots::contains).map(w -> w.name).toList());
		if (left.contains("Spot 7") || left.contains("Spot 8") || left.contains("Spot 9") || left.size() != 10) {
			wrong.add("after deleting Spot 7 to 9 the store still had " + left);
		}

		// A plain click goes back to one row.
		click(context, spots.get(9), false, false);
		click(context, spots.get(11), true, false);
		click(context, spots.get(10), false, false);
		expect(context, wrong, "a plain click after a Shift click", List.of("Spot 11"));
		// Also on the row that has the buttons: the others must not stay selected behind it.
		click(context, spots.get(9), false, true);
		expect(context, wrong, "Ctrl click on Spot 10", List.of("Spot 11", "Spot 10"));
		click(context, spots.get(9), false, false);
		expect(context, wrong, "a plain click on the row with the buttons", List.of("Spot 10"));
		if (!rowButtons(context).contains("Edit") || rowButtons(context).contains("Move")) {
			wrong.add("one row after a plain click had the buttons " + rowButtons(context));
		}

		// Delete clicked for three rows, then a search that leaves one of them: it asks again.
		click(context, spots.get(9), false, false);
		click(context, spots.get(11), true, false);
		clickRowButton(context, "Delete");
		// Typed without a click on the search box, which would put Delete back by itself.
		context.runOnClient(mc -> mc.gui.screen().setFocused(DioramaClientTest.editBoxes(mc.gui.screen()).get(0)));
		context.getInput().typeChars("Spot 12");
		context.waitTicks(2);
		expect(context, wrong, "a search that leaves one selected row", List.of("Spot 12"));
		if (rowButtons(context).stream().anyMatch(label -> label.startsWith("Click again"))) {
			wrong.add("Delete stayed armed after the search changed the selection: " + rowButtons(context));
		}
		DioramaClientTest.typeInto(context, 0, "");
		context.takeScreenshot("select-09-one-again");

		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			spots.forEach(store::remove);
			store.deleteFolder(CAMP);
			store.deleteFolder(TRIP);
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError("Selecting several waypoints went wrong: " + wrong);
		}
	}

	/** Clicks a waypoint's row with Shift or Ctrl held, as the keyboard holds them. */
	private static void click(ClientGameTestContext context, Waypoint waypoint, boolean shift, boolean control) {
		// The wheel brings its row into view: to the top, then down a line at a time.
		for (int i = 0; i < 12; i++) {
			scroll(context, 1);
		}
		for (int i = 0; i < 12 && context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(waypoint)) == null; i++) {
			scroll(context, -1);
		}
		context.waitTicks(2);
		int[] row = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(waypoint));
		if (row == null) {
			throw new AssertionError(waypoint.name + "'s row isn't in view");
		}
		if (shift) {
			context.getInput().holdShift();
		}
		if (control) {
			context.getInput().holdControl();
		}
		context.waitTicks(1);
		DioramaClientTest.clickAtGui(context, row[0], row[1]);
		if (shift) {
			context.getInput().releaseShift();
		}
		if (control) {
			context.getInput().releaseControl();
		}
		// Past the double-click window, so the next click on a row isn't the second of two.
		context.waitTicks(8);
	}

	private static void scroll(ClientGameTestContext context, int direction) {
		context.runOnClient(mc -> {
			//? if >=1.21 {
			mc.gui.screen().mouseScrolled(mc.gui.screen().width / 2.0, 120, 0, direction);
			//?} else {
			/*mc.gui.screen().mouseScrolled(mc.gui.screen().width / 2.0, 120, direction);
			*///?}
		});
	}

	private static void expect(ClientGameTestContext context, List<String> wrong, String what, List<String> names) {
		List<String> selected = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).selection().stream().map(w -> w.name).toList());
		if (!new java.util.HashSet<>(selected).equals(new java.util.HashSet<>(names)) || selected.size() != names.size()) {
			wrong.add(what + " selected " + selected);
		}
	}

	/** The labels of the buttons on the list's rows, left to right: the ones showing below the search row. */
	private static List<String> rowButtons(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			List<Button> buttons = new ArrayList<>();
			for (var child : mc.gui.screen().children()) {
				if (child instanceof Button button && button.visible && button.getY() > 70 && button.getY() < mc.gui.screen().height - 40) {
					buttons.add(button);
				}
			}
			buttons.sort(java.util.Comparator.comparingInt(Button::getX));
			return buttons.stream().map(b -> b.getMessage().getString()).toList();
		});
	}

	private static void clickRowButton(ClientGameTestContext context, String label) {
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof Button button && button.visible && button.getY() > 70 && button.getMessage().getString().equals(label)) {
					return button;
				}
			}
			throw new AssertionError("No " + label + " button on the selected rows");
		}));
		context.waitTicks(2);
	}

	private static void hover(ClientGameTestContext context, String name) {
		float[] line = context.computeOnClient(mc -> {
			FolderMenu menu = ((WaypointListScreen) mc.gui.screen()).folderMenu();
			return menu == null ? null : menu.centreOf(name);
		});
		if (line != null) {
			double scale = context.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
			context.getInput().setCursorPos(line[0] * scale, line[1] * scale);
			context.waitTicks(2);
		}
	}

	private static void pick(ClientGameTestContext context, String name, List<String> wrong) {
		float[] line = context.computeOnClient(mc -> {
			FolderMenu menu = ((WaypointListScreen) mc.gui.screen()).folderMenu();
			return menu == null ? null : menu.centreOf(name);
		});
		if (line == null) {
			wrong.add("no line for " + name + " in Move's list of folders");
			return;
		}
		DioramaClientTest.clickAtGui(context, line[0], line[1]);
		context.waitTicks(3);
	}
}
