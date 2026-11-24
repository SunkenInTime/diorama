package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.waypoint.FolderMenu;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointSort;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/**
 * Picking a folder from the list of folders ({@code folder-list}, issue #244): a click on the
 * folder's name in the waypoint list opens every folder with its count, a click on one shows it, a
 * click anywhere else or Escape only closes the list, the arrow keys and Enter pick from it, the
 * arrow buttons still step, and with more folders
 * than fit on the screen the list scrolls to the last. Only the waypoints and folders made here
 * are checked and removed: other sections leave waypoints of their own.
 */
final class FolderListTest {
	private static final String[] CUSTOM = {"Farms", "Villages", "Mines", "Alex's base", "Portals", "Shops"};
	private static final int[] IN_EACH = {5, 4, 3, 4, 2, 0};

	private FolderListTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		List<Waypoint> added = context.computeOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.setSort(WaypointSort.DISTANCE, false);
			String here = mc.level.dimension().identifier().toString();
			List<Waypoint> made = new ArrayList<>();
			int n = 0;
			for (String name : new String[] {"Home", "Harbour", "Lighthouse"}) {
				made.add(add(store, at, here, "", name, n++));
			}
			made.add(add(store, at, "minecraft:the_nether", "", "Fortress", n++));
			for (int f = 0; f < CUSTOM.length; f++) {
				store.addFolder(CUSTOM[f]);
				for (int i = 0; i < IN_EACH[f]; i++) {
					// One of the Farms is in the Nether: a folder of the player's own holds any dimension's.
					made.add(add(store, at, f == 0 && i == 0 ? "minecraft:the_nether" : here, CUSTOM[f], CUSTOM[f] + " " + (i + 1), n++));
				}
			}
			store.setFolderHidden(WaypointStore.customKey("Portals"), true);
			return made;
		});
		// Checked at the end, so a failing run still takes every picture.
		List<String> wrong = new ArrayList<>();

		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("folder-list-01-closed");
		if (menu(context) != null) {
			wrong.add("the list of folders was open before a click");
		}

		// A click on the folder's name opens every folder, with how many waypoints each holds.
		clickName(context);
		List<String> labels = context.computeOnClient(mc -> {
			FolderMenu menu = ((WaypointListScreen) mc.gui.screen()).folderMenu();
			return menu == null ? List.<String>of() : menu.labels();
		});
		for (int f = 0; f < CUSTOM.length; f++) {
			if (!labels.contains(CUSTOM[f] + " " + IN_EACH[f])) {
				wrong.add("the list of folders has no line \"" + CUSTOM[f] + " " + IN_EACH[f] + "\": " + labels);
			}
		}
		if (labels.size() < 9 || !labels.get(0).startsWith("Overworld ") || !labels.get(1).startsWith("Nether ") || !labels.subList(3, 9).equals(List.of("Alex's base 4", "Farms 5", "Mines 3", "Portals 2", "Shops 0", "Villages 4"))) {
			wrong.add("the list of folders isn't the dimensions' then the player's own by name: " + labels);
		}
		hover(context, "Alex's base");
		context.takeScreenshot("folder-list-02-open");

		// A click on a folder shows it and closes the list: two clicks, where the arrow takes seven.
		pick(context, "Alex's base", wrong);
		if (!folder(context).equals("Alex's base") || menu(context) != null) {
			wrong.add("picking Alex's base showed " + folder(context) + (menu(context) != null ? " with the list still open" : ""));
		}
		if (shownNames(context, added).size() != 4) {
			wrong.add("Alex's base listed " + shownNames(context, added));
		}
		context.takeScreenshot("folder-list-03-picked");

		// Open again: the folder shown is marked, and a click outside the list only closes it.
		clickName(context);
		context.takeScreenshot("folder-list-04-open-again");
		DioramaClientTest.clickButton(context, "diorama.waypoint.new");
		if (menu(context) != null || !(context.computeOnClient(mc -> mc.gui.screen()) instanceof WaypointListScreen)) {
			wrong.add("a click on New waypoint with the list of folders open did more than close the list");
		}
		// Escape closes the list and leaves the screen open; the next Escape closes the screen.
		clickName(context);
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		if (menu(context) != null || !(context.computeOnClient(mc -> mc.gui.screen()) instanceof WaypointListScreen)) {
			wrong.add("Escape with the list of folders open didn't just close the list");
		}

		// The arrow keys go through the open list and Enter picks; other keys reach nothing under it.
		clickName(context);
		context.getInput().pressKey(InputConstants.KEY_TAB);
		context.getInput().pressKey(InputConstants.KEY_DOWN);
		context.getInput().pressKey(InputConstants.KEY_DOWN);
		context.waitTicks(2);
		context.takeScreenshot("folder-list-04b-arrow-keys");
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(2);
		if (!folder(context).equals("Mines") || menu(context) != null) {
			wrong.add("Down twice and Enter from Alex's base showed " + folder(context));
		}
		clickName(context);
		pick(context, "Alex's base", wrong);

		// The arrows step as before.
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), ">")));
		if (!folder(context).equals("Farms")) {
			wrong.add("> from Alex's base showed " + folder(context));
		}
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "<")));
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "<")));
		if (!folder(context).equals("End")) {
			wrong.add("< twice from Farms showed " + folder(context));
		}

		// More folders than fit under the name: the list scrolls, and the last one is reached with the wheel.
		List<String> many = new ArrayList<>();
		context.runOnClient(mc -> {
			for (int i = 1; i <= 40; i++) {
				many.add("Trip " + i);
				WaypointStore.get().addFolder("Trip " + i);
			}
		});
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		clickName(context);
		int[] sizes = context.computeOnClient(mc -> {
			FolderMenu menu = ((WaypointListScreen) mc.gui.screen()).folderMenu();
			return new int[] {menu.shown(), menu.labels().size()};
		});
		if (sizes[0] >= sizes[1]) {
			wrong.add("49 folders all fit in the list (" + sizes[0] + " lines), so scrolling wasn't checked");
		}
		if (context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderMenu().centreOf("Villages")) != null) {
			wrong.add("the last folder was in view before scrolling");
		}
		context.takeScreenshot("folder-list-05-many");
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
		hover(context, "Villages");
		context.takeScreenshot("folder-list-06-many-scrolled");
		pick(context, "Villages", wrong);
		if (!folder(context).equals("Villages")) {
			wrong.add("picking the last folder after scrolling showed " + folder(context));
		}
		context.takeScreenshot("folder-list-07-last-picked");

		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			added.forEach(store::remove);
			for (String name : CUSTOM) {
				store.deleteFolder(name);
			}
			many.forEach(store::deleteFolder);
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError("The list of folders went wrong: " + wrong);
		}
	}

	private static Waypoint add(WaypointStore store, BlockPos at, String dimension, String folder, String name, int n) {
		Waypoint waypoint = new Waypoint(name, at.getX() + 20 + n * 37, at.getY(), at.getZ() + n * 11, dimension, Waypoint.PALETTE[n % Waypoint.PALETTE.length]);
		waypoint.folder = folder;
		store.add(waypoint);
		return waypoint;
	}

	private static FolderMenu menu(ClientGameTestContext context) {
		return context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderMenu());
	}

	private static String folder(ClientGameTestContext context) {
		return context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderName());
	}

	private static List<String> shownNames(ClientGameTestContext context, List<Waypoint> added) {
		return context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).shown().stream().filter(added::contains).map(w -> w.name).toList());
	}

	/** Clicks the folder's name in the folder bar. */
	private static void clickName(ClientGameTestContext context) {
		int[] name = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderNameCentre());
		DioramaClientTest.clickAtGui(context, name[0], name[1]);
	}

	/** Puts the mouse on a folder's line, for a picture with the line lit. */
	private static void hover(ClientGameTestContext context, String name) {
		float[] line = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).folderMenu().centreOf(name));
		if (line != null) {
			double scale = context.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
			context.getInput().setCursorPos(line[0] * scale, line[1] * scale);
			context.waitTicks(2);
		}
	}

	/** Clicks a folder's line in the open list. */
	private static void pick(ClientGameTestContext context, String name, List<String> wrong) {
		float[] line = context.computeOnClient(mc -> {
			FolderMenu menu = ((WaypointListScreen) mc.gui.screen()).folderMenu();
			return menu == null ? null : menu.centreOf(name);
		});
		if (line == null) {
			wrong.add("no line for " + name + " in the list of folders");
			return;
		}
		DioramaClientTest.clickAtGui(context, line[0], line[1]);
	}
}
