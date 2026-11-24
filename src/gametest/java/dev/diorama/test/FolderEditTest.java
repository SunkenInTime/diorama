package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.waypoint.FolderMenu;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
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
 * Picking a waypoint's folder in its editor ({@code folder-editor}, issue #244): the Folder button
 * opens the folders to pick from where it used to step to the next one. A pick shows on the button
 * and moves the waypoint on Save and not on Cancel; a click anywhere else or Escape only closes
 * the list; and with more folders than fit, the list scrolls to the last. Only the waypoint and
 * folders made here are checked and removed.
 */
final class FolderEditTest {
	private static final String[] CUSTOM = {"Farms", "Villages", "Mines", "Alex's base", "Portals", "Shops"};

	private FolderEditTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		Waypoint cabin = context.computeOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.setSort(WaypointSort.DISTANCE, false);
			for (String name : CUSTOM) {
				store.addFolder(name);
			}
			Waypoint waypoint = new Waypoint("Cabin", at.getX() + 3, at.getY(), at.getZ(), mc.level.dimension().identifier().toString(), Waypoint.PALETTE[4]);
			store.add(waypoint);
			return waypoint;
		});
		// Checked at the end, so a failing run still takes every picture.
		List<String> wrong = new ArrayList<>();

		// Into the editor the way a player goes: the row, then Edit.
		edit(context, cabin);
		context.takeScreenshot("folder-editor-01-closed");
		DioramaClientTest.clickAt(context, folderButton(context));
		List<String> labels = context.computeOnClient(mc -> {
			FolderMenu menu = ((WaypointEditScreen) mc.gui.screen()).folderMenu();
			return menu == null ? List.<String>of() : menu.labels();
		});
		List<String> expected = new ArrayList<>(List.of("Overworld"));
		expected.addAll(List.of("Alex's base", "Farms", "Mines", "Portals", "Shops", "Villages"));
		if (!labels.equals(expected)) {
			wrong.add("the editor's list of folders was " + labels);
		}
		hover(context, "Alex's base");
		context.takeScreenshot("folder-editor-02-open");

		// One click picks: the button says so, and the waypoint moves only on Save.
		pick(context, "Alex's base", wrong);
		if (menu(context) != null || !folderButton(context).getMessage().getString().equals("Alex's base")) {
			wrong.add("picking Alex's base left the button saying " + folderButton(context).getMessage().getString());
		}
		if (!cabin.folder.isEmpty()) {
			wrong.add("the waypoint moved before Save");
		}
		context.takeScreenshot("folder-editor-03-picked");

		// A click outside the open list only closes it: Save under the mouse isn't pressed.
		DioramaClientTest.clickAt(context, folderButton(context));
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "Save")));
		if (!(context.computeOnClient(mc -> mc.gui.screen()) instanceof WaypointEditScreen) || menu(context) != null) {
			wrong.add("a click on Save with the list of folders open did more than close the list");
		}
		if (!folderButton(context).getMessage().getString().equals("Alex's base")) {
			wrong.add("a click outside the list of folders changed the folder to " + folderButton(context).getMessage().getString());
		}
		// Escape closes the list and leaves the editor open.
		DioramaClientTest.clickAt(context, folderButton(context));
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitTicks(2);
		if (!(context.computeOnClient(mc -> mc.gui.screen()) instanceof WaypointEditScreen) || menu(context) != null) {
			wrong.add("Escape with the list of folders open didn't just close the list");
		}
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "Save")));
		context.waitTicks(3);
		if (!cabin.folder.equals("Alex's base")) {
			wrong.add("Save left the waypoint in \"" + cabin.folder + "\"");
		}

		// Without the mouse: Enter on the Folder button opens the list, the arrows and Enter pick.
		edit(context, cabin);
		context.runOnClient(mc -> mc.gui.screen().setFocused(((WaypointEditScreen) mc.gui.screen()).folderButton()));
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(2);
		if (menu(context) == null) {
			wrong.add("Enter on the Folder button didn't open the list of folders");
		}
		context.getInput().pressKey(InputConstants.KEY_DOWN);
		context.getInput().pressKey(InputConstants.KEY_DOWN);
		context.waitTicks(2);
		context.takeScreenshot("folder-editor-03b-arrow-keys");
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(2);
		if (menu(context) != null || !folderButton(context).getMessage().getString().equals("Mines")) {
			wrong.add("Down twice and Enter from Alex's base left the button saying " + folderButton(context).getMessage().getString());
		}
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "Cancel")));
		context.waitTicks(3);

		// Picked and then cancelled, it stays where it was.
		edit(context, cabin);
		DioramaClientTest.clickAt(context, folderButton(context));
		pick(context, "Farms", wrong);
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "Cancel")));
		context.waitTicks(3);
		if (!cabin.folder.equals("Alex's base")) {
			wrong.add("Cancel after picking Farms left the waypoint in \"" + cabin.folder + "\"");
		}

		// Back to its dimension's own folder, the first line.
		edit(context, cabin);
		DioramaClientTest.clickAt(context, folderButton(context));
		context.takeScreenshot("folder-editor-04-open-in-own-folder");
		pick(context, "Overworld", wrong);
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "Save")));
		context.waitTicks(3);
		if (!cabin.folder.isEmpty()) {
			wrong.add("picking Overworld and Save left the waypoint in \"" + cabin.folder + "\"");
		}

		// More folders than fit beside the button: the list stays on the screen and scrolls to the last.
		List<String> many = new ArrayList<>();
		context.runOnClient(mc -> {
			for (int i = 1; i <= 40; i++) {
				many.add("Trip " + i);
				WaypointStore.get().addFolder("Trip " + i);
			}
		});
		edit(context, cabin);
		DioramaClientTest.clickAt(context, folderButton(context));
		int[] sizes = context.computeOnClient(mc -> {
			FolderMenu menu = ((WaypointEditScreen) mc.gui.screen()).folderMenu();
			return new int[] {menu.shown(), menu.labels().size()};
		});
		if (sizes[0] >= sizes[1]) {
			wrong.add("47 folders all fit in the editor's list (" + sizes[0] + " lines), so scrolling wasn't checked");
		}
		context.takeScreenshot("folder-editor-05-many");
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
		context.takeScreenshot("folder-editor-06-many-scrolled");
		pick(context, "Villages", wrong);
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "Save")));
		context.waitTicks(3);
		if (!cabin.folder.equals("Villages")) {
			wrong.add("picking the last folder after scrolling left the waypoint in \"" + cabin.folder + "\"");
		}

		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.remove(cabin);
			for (String name : CUSTOM) {
				store.deleteFolder(name);
			}
			many.forEach(store::deleteFolder);
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError("The editor's list of folders went wrong: " + wrong);
		}
	}

	/** Opens the waypoint list on the waypoint's folder, selects its row and clicks Edit. */
	private static void edit(ClientGameTestContext context, Waypoint waypoint) {
		context.setScreen(() -> {
			WaypointListScreen list = new WaypointListScreen(null);
			return list;
		});
		context.waitTicks(5);
		// Its folder: stepped to with the arrow, as many times as it takes.
		for (int i = 0; i < 60 && context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(waypoint)) == null; i++) {
			DioramaClientTest.clickAt(context, context.computeOnClient(mc -> DioramaClientTest.buttonLabelled(mc.gui.screen(), "<")));
		}
		int[] row = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(waypoint));
		DioramaClientTest.clickAtGui(context, row[0], row[1]);
		DioramaClientTest.clickAt(context, context.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof Button button && button.visible && button.getMessage().getString().equals("Edit")) {
					return button;
				}
			}
			throw new AssertionError("No Edit button on the selected row");
		}));
		context.waitTicks(3);
	}

	private static Button folderButton(ClientGameTestContext context) {
		return context.computeOnClient(mc -> ((WaypointEditScreen) mc.gui.screen()).folderButton());
	}

	private static FolderMenu menu(ClientGameTestContext context) {
		return context.computeOnClient(mc -> ((WaypointEditScreen) mc.gui.screen()).folderMenu());
	}

	/** Puts the mouse on a folder's line, for a picture with the line lit. */
	private static void hover(ClientGameTestContext context, String name) {
		float[] line = context.computeOnClient(mc -> {
			FolderMenu menu = ((WaypointEditScreen) mc.gui.screen()).folderMenu();
			return menu == null ? null : menu.centreOf(name);
		});
		if (line != null) {
			double scale = context.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
			context.getInput().setCursorPos(line[0] * scale, line[1] * scale);
			context.waitTicks(2);
		}
	}

	/** Clicks a folder's line in the open list. */
	private static void pick(ClientGameTestContext context, String name, List<String> wrong) {
		float[] line = context.computeOnClient(mc -> {
			FolderMenu menu = ((WaypointEditScreen) mc.gui.screen()).folderMenu();
			return menu == null ? null : menu.centreOf(name);
		});
		if (line == null) {
			wrong.add("no line for " + name + " in the editor's list of folders");
			return;
		}
		DioramaClientTest.clickAtGui(context, line[0], line[1]);
	}
}
