package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import java.util.List;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * The Cursor coordinates setting (opt-in: {@code map-coordinates}): on, the tooltip of the spot
 * under the mouse on the map starts with its coordinates; switched off with its checkbox, it keeps
 * its biome and distance alone. The player's position at the top of the map stays either way.
 */
final class MapCoordinatesTest {
	private static final Pattern COORDINATES = Pattern.compile("-?[0-9]+, -?[0-9]+, -?[0-9]+");

	private MapCoordinatesTest() {
	}

	static void run(ClientGameTestContext context) {
		boolean before = context.computeOnClient(mc -> Settings.get().mapCoordinates);
		context.runOnClient(mc -> Settings.get().mapCoordinates = true);
		String position = context.computeOnClient(mc -> mc.player.getBlockX() + ", " + mc.player.getBlockY() + ", " + mc.player.getBlockZ());
		List<String> shown = hover(context, "map-coordinates-01-on");
		String status = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).statusLine());
		if (!status.contains(position)) {
			throw new AssertionError("The map's status line doesn't give the player's position " + position + ": " + status);
		}
		if (shown.size() != 2 || !COORDINATES.matcher(shown.get(0)).matches() || !shown.get(1).endsWith(" m")) {
			throw new AssertionError("The tooltip of a spot should be its coordinates over its biome and distance: " + shown);
		}

		context.setScreen(() -> new SettingsScreen(null));
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(2));
		context.waitTicks(5);
		DioramaClientTest.click(context, "Cursor coordinates");
		if (context.computeOnClient(mc -> Settings.get().mapCoordinates)) {
			throw new AssertionError("Clicking Cursor coordinates didn't switch it off");
		}
		// The mouse is still on the checkbox: the picture has its tooltip.
		context.waitTicks(20);
		context.takeScreenshot("map-coordinates-02-setting");

		List<String> hidden = hover(context, "map-coordinates-03-off");
		status = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).statusLine());
		if (!status.contains(position)) {
			throw new AssertionError("The map's status line lost the player's position " + position + " with Cursor coordinates off: " + status);
		}
		if (hidden.size() != 1 || !hidden.get(0).equals(shown.get(1))) {
			throw new AssertionError("With Cursor coordinates off the tooltip of a spot should be \"" + shown.get(1) + "\" alone: " + hidden);
		}
		context.getInput().setCursorPos(0, 0);
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			Settings.get().mapCoordinates = before;
			Settings.get().save();
		});
	}

	/** Opens the map with the mouse on a spot beside the player, and returns the tooltip it shows there. */
	private static List<String> hover(ClientGameTestContext context, String picture) {
		context.setScreen(MapScreen::new);
		context.waitTicks(20);
		double[] at = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(mc.gui.screen().width / 2.0 + 40) * scale, (mc.gui.screen().height / 2.0 + 30) * scale};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(20);
		context.takeScreenshot(picture);
		return context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).tooltipText());
	}
}
