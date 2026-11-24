package dev.diorama.test;

import dev.diorama.waypoint.FolderMenu;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointSort;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;

/**
 * Thirty folders of the player's own ({@code folder-many}, issue #453), with long and short names,
 * made in no order: everywhere folders are listed (the waypoint list's folder name, Move for
 * several rows, the Folder button in a waypoint's editor) they're in name order, capitals or not,
 * after the dimensions' own, the arrows beside the folder's name step in that order too, and the
 * last one is reached with the mouse wheel. Pictured at 1280 x 720 with GUI scale 3 and in the
 * smallest window. Only the waypoints and folders made here are checked and removed.
 */
final class FolderManyTest {
	private static final String[] NAMES = {
		"Villager trading hall", "Base", "Zombie farm", "iron farm", "Ancient cities", "Mob grinder (old, do not use!!)",
		"Home", "Elytra launch pads", "Shops", "North mesa outpost by the river", "Ocean monuments", "Bastions",
		"End gateways", "creeper farm", "Woodland mansions", "Friends", "Strongholds", "Ice highway stops", "Mines",
		"Trial chambers", "Desert temples", "Jungle", "Xp", "Portals", "Shipwrecks and buried treasures", "Kelp",
		"Railway stations", "Quarry", "Outposts", "Lush caves"};

	private FolderManyTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		List<Waypoint> added = context.computeOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.setSort(WaypointSort.DISTANCE, false);
			String here = mc.level.dimension().identifier().toString();
			List<Waypoint> made = new ArrayList<>();
			for (int f = 0; f < NAMES.length; f++) {
				store.addFolder(NAMES[f]);
				for (int i = 0; i < f % 4; i++) {
					Waypoint waypoint = new Waypoint(NAMES[f] + " " + (i + 1), at.getX() + 20 + made.size() * 9, at.getY(), at.getZ() + i * 7, here,
						Waypoint.PALETTE[made.size() % Waypoint.PALETTE.length]);
					waypoint.folder = NAMES[f];
					store.add(waypoint);
					made.add(waypoint);
				}
			}
			for (int i = 0; i < 3; i++) {
				Waypoint waypoint = new Waypoint("Camp " + (i + 1), at.getX() + 5 + i * 6, at.getY(), at.getZ() - 8, here, Waypoint.PALETTE[i]);
				store.add(waypoint);
				made.add(waypoint);
			}
			return made;
		});
		List<String> sorted = new ArrayList<>(List.of(NAMES));
		sorted.sort(java.util.Comparator.comparing(name -> name.toLowerCase(Locale.ROOT)));
		String last = sorted.get(sorted.size() - 1);
		// Checked at the end, so a failing run still takes every picture.
		List<String> wrong = new ArrayList<>();
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());

		for (int[] size : new int[][] {{1280, 720, 3}, {640, 480, 2}}) {
			context.getInput().resizeWindow(size[0], size[1]);
			DioramaClientTest.setGuiScale(context, size[2]);
			String at_ = size[0] + "x" + size[1];

			// The waypoint list: the folders under the folder's name.
			context.setScreen(() -> new WaypointListScreen(null));
			context.waitTicks(5);
			int[] name = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderNameCentre());
			DioramaClientTest.clickAtGui(context, name[0], name[1]);
			context.waitTicks(2);
			List<String> labels = names(context, FolderManyTest::listMenu);
			if (labels.size() < 3 + NAMES.length || !labels.subList(0, 3).equals(List.of("Overworld", "Nether", "End"))
				|| !labels.subList(labels.size() - NAMES.length, labels.size()).equals(sorted)) {
				wrong.add(at_ + ": the waypoint list's folders weren't the dimensions' then the player's by name: " + labels);
			}
			context.takeScreenshot("folder-many-" + at_ + "-01-list");
			wheelToEnd(context);
			hover(context, FolderManyTest::listMenu, last);
			context.takeScreenshot("folder-many-" + at_ + "-02-list-end");
			pick(context, FolderManyTest::listMenu, last, at_ + ": the waypoint list's folders", wrong);
			context.waitTicks(3);
			if (!folder(context).equals(last)) {
				wrong.add(at_ + ": picking the last folder showed " + folder(context));
			}
			// The arrows step in the same order: back from the last folder to the one before it.
			DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "<")));
			context.waitTicks(2);
			if (!folder(context).equals(sorted.get(sorted.size() - 2))) {
				wrong.add(at_ + ": < from " + last + " showed " + folder(context));
			}
			context.takeScreenshot("folder-many-" + at_ + "-03-list-stepped");

			// Move, for several rows of the Overworld's folder.
			context.setScreen(() -> new WaypointListScreen(null));
			context.waitTicks(5);
			List<Waypoint> camps = added.subList(added.size() - 3, added.size());
			clickRow(context, camps.get(0), false);
			clickRow(context, camps.get(2), true);
			DioramaClientTest.clickAt(context, context.computeOnClient(mc -> rowButton(mc.gui.screen(), "Move")));
			context.waitTicks(2);
			labels = names(context, FolderManyTest::listMenu);
			if (labels.isEmpty() || !labels.get(0).equals("Overworld") || !labels.subList(1, labels.size()).equals(sorted)) {
				wrong.add(at_ + ": Move's folders weren't the dimension's then the player's by name: " + labels);
			}
			context.takeScreenshot("folder-many-" + at_ + "-04-move");
			wheelToEnd(context);
			hover(context, FolderManyTest::listMenu, last);
			context.takeScreenshot("folder-many-" + at_ + "-05-move-end");
			if (context.computeOnClient(mc -> listMenu(mc.gui.screen()).centreOf(last)) == null) {
				wrong.add(at_ + ": the wheel didn't reach the last folder in Move's list");
			}

			// A waypoint's editor: the Folder button.
			context.setScreen(() -> new WaypointListScreen(null));
			context.waitTicks(5);
			clickRow(context, camps.get(1), false);
			DioramaClientTest.clickAt(context, context.computeOnClient(mc -> rowButton(mc.gui.screen(), "Edit")));
			context.waitTicks(3);
			DioramaClientTest.clickAt(context, context.computeOnClient(mc -> ((WaypointEditScreen) mc.gui.screen()).folderButton()));
			context.waitTicks(2);
			labels = names(context, FolderManyTest::editMenu);
			if (labels.isEmpty() || !labels.get(0).equals("Overworld") || !labels.subList(1, labels.size()).equals(sorted)) {
				wrong.add(at_ + ": the editor's folders weren't the dimension's then the player's by name: " + labels);
			}
			context.takeScreenshot("folder-many-" + at_ + "-06-editor");
			wheelToEnd(context);
			hover(context, FolderManyTest::editMenu, last);
			context.takeScreenshot("folder-many-" + at_ + "-07-editor-end");
			pick(context, FolderManyTest::editMenu, last, at_ + ": the editor's folders", wrong);
			context.waitTicks(2);
			String button = context.computeOnClient(mc -> ((WaypointEditScreen) mc.gui.screen()).folderButton().getMessage().getString());
			if (!button.equals(last)) {
				wrong.add(at_ + ": picking the last folder in the editor left the button saying " + button);
			}
			DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "Cancel")));
			context.waitTicks(3);
		}

		context.setScreen(() -> null);
		context.getInput().resizeWindow(1600, 900);
		DioramaClientTest.setGuiScale(context, guiScale);
		context.waitTicks(5);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			added.forEach(store::remove);
			for (String name : NAMES) {
				store.deleteFolder(name);
			}
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError("Many folders went wrong: " + wrong);
		}
	}

	private static FolderMenu listMenu(Screen screen) {
		return ((WaypointListScreen) screen).folderMenu();
	}

	private static FolderMenu editMenu(Screen screen) {
		return ((WaypointEditScreen) screen).folderMenu();
	}

	/** The open list's folders in order, without their counts. */
	private static List<String> names(ClientGameTestContext context, Function<Screen, FolderMenu> menu) {
		return context.computeOnClient(mc -> {
			FolderMenu open = menu.apply(mc.gui.screen());
			return open == null ? List.<String>of() : open.names();
		});
	}

	private static String folder(ClientGameTestContext context) {
		return context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderName());
	}

	private static void wheelToEnd(ClientGameTestContext context) {
		for (int i = 0; i < 60; i++) {
			context.runOnClient(mc -> {
				//? if >=1.21 {
				mc.gui.screen().mouseScrolled(mc.gui.screen().width / 2.0, 120, 0, -1);
				//?} else {
				/*mc.gui.screen().mouseScrolled(mc.gui.screen().width / 2.0, 120, -1);
				*///?}
			});
		}
		context.waitTicks(2);
	}

	private static void hover(ClientGameTestContext context, Function<Screen, FolderMenu> menu, String name) {
		float[] line = context.computeOnClient(mc -> {
			FolderMenu open = menu.apply(mc.gui.screen());
			return open == null ? null : open.centreOf(name);
		});
		if (line != null) {
			double scale = context.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
			context.getInput().setCursorPos(line[0] * scale, line[1] * scale);
			context.waitTicks(2);
		}
	}

	private static void pick(ClientGameTestContext context, Function<Screen, FolderMenu> menu, String name, String what, List<String> wrong) {
		float[] line = context.computeOnClient(mc -> {
			FolderMenu open = menu.apply(mc.gui.screen());
			return open == null ? null : open.centreOf(name);
		});
		if (line == null) {
			wrong.add(what + " had no line in view for " + name + " after the wheel");
			return;
		}
		DioramaClientTest.clickAtGui(context, line[0], line[1]);
	}

	/** Clicks a waypoint's row in the folder shown, with Shift held or not. */
	private static void clickRow(ClientGameTestContext context, Waypoint waypoint, boolean shift) {
		int[] row = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(waypoint));
		if (row == null) {
			throw new AssertionError(waypoint.name + "'s row isn't in view");
		}
		if (shift) {
			context.getInput().holdShift();
		}
		context.waitTicks(1);
		DioramaClientTest.clickAtGui(context, row[0], row[1]);
		if (shift) {
			context.getInput().releaseShift();
		}
		// Past the double-click window, so the next click on a row isn't the second of two.
		context.waitTicks(8);
	}

	private static Button rowButton(Screen screen, String label) {
		for (var child : screen.children()) {
			if (child instanceof Button button && button.visible && button.getY() > 70 && button.getMessage().getString().equals(label)) {
				return button;
			}
		}
		throw new AssertionError("No " + label + " button on the selected rows");
	}
}
