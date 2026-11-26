package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.MapCoordinates;
import dev.diorama.MapScreen;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/**
 * The map's Go to coordinates box ({@code go-to-coordinates}), used as a player would: the button,
 * coordinates typed or pasted, Enter. The view lands on the place, on mapped ground with the very
 * block in the middle of the screen though the text gave no height, and on land the map has never
 * seen. Text with no place in it says so and moves nothing, and while the box is open the map's
 * keys are letters.
 */
final class GoToCoordinatesTest {
	/** The flag for Ctrl held with a key: SDL's left Ctrl from 26.3, GLFW's before. */
	//? if >=26.3 {
	private static final int CONTROL = 64;
	/** The letter a shortcut is read by there: v, whatever key the layout has it on. */
	private static final int V_LETTER = 'v';
	//?} else {
	/*private static final int CONTROL = 2;
	private static final int V_LETTER = 0;
	*///?}

	private GoToCoordinatesTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		reads();
		world.getServer().runCommand("time set noon");
		context.waitFor(mc -> mc.player.onGround(), 600);
		// A flat patch, so the block asked for is one the map shows: behind a tree it would be hidden.
		BlockPos player = context.computeOnClient(mc -> mc.player.blockPosition());
		int px = player.getX();
		int py = player.getY();
		int pz = player.getZ();
		world.getServer().runCommand("fill " + (px - 12) + " " + py + " " + (pz - 12) + " " + (px + 12) + " " + (py + 12) + " " + (pz + 12) + " air");
		world.getServer().runCommand("fill " + (px - 12) + " " + (py - 1) + " " + (pz - 12) + " " + (px + 12) + " " + (py - 1) + " " + (pz + 12) + " grass_block");
		DioramaClientTest.settle(context, 40);
		DioramaClientTest.openMapOnPlayer(context);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("go-to-01-button");

		// Typed, without a height, on ground the map shows: the block lands in the middle of the screen.
		int x = px + 7;
		int z = pz - 5;
		clickGoButton(context);
		context.takeScreenshot("go-to-02-box");
		context.getInput().typeChars(x + ", " + z);
		context.waitTicks(3);
		context.takeScreenshot("go-to-03-typed");
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitFor(mc -> {
			int[] mark = ((MapScreen) mc.gui.screen()).goMark();
			return mark != null && mark[3] == 1;
		}, 200);
		DioramaClientTest.settle(context, 10);
		context.takeScreenshot("go-to-04-arrived");
		int[] mark = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).goMark());
		if (mark[0] != x || mark[1] != py || mark[2] != z) {
			throw new AssertionError("Typing " + x + ", " + z + " marked " + java.util.Arrays.toString(mark) + ", and the ground there is at " + py);
		}
		expectCentre(context, x, mark[1], z, "After typing " + x + ", " + z);
		if (context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).goBox().visible)) {
			throw new AssertionError("The box stayed open after Enter took the view to the place");
		}
		// What the map itself says is in the middle of the screen: that column.
		double[] middle = context.computeOnClient(mc -> new double[] {mc.gui.screen().width * mc.getWindow().getGuiScale() / 2.0, mc.gui.screen().height * mc.getWindow().getGuiScale() / 2.0});
		DioramaClientTest.rightClickAt(context, middle[0], middle[1]);
		int[] spot = DioramaClientTest.menuSpot(context);
		context.takeScreenshot("go-to-05-menu-there");
		if (spot[0] != x || spot[2] != z) {
			throw new AssertionError("After going to " + x + ", " + z + " the block in the middle of the screen is " + spot[0] + ", " + spot[1] + ", " + spot[2]);
		}
		pressEscape(context);

		// Pasted, the line F3+C copies, far out where nothing is mapped: its own height is used as it is.
		context.runOnClient(mc -> mc.keyboardHandler.setClipboard("/execute in " + ((MapScreen) mc.gui.screen()).dimension() + " run tp @s 5000.50 80.00 -7000.50 90.00 0.00"));
		clickGoButton(context);
		// Ctrl+V. From 1.21.9 the key press itself says Ctrl is down, which the test's own key presses leave out.
		context.getInput().holdControl();
		//? if >=1.21.9 {
		context.runOnClient(mc -> mc.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_V, V_LETTER, CONTROL)));
		//?} else {
		/*context.getInput().pressKey(InputConstants.KEY_V);
		*///?}
		context.getInput().releaseControl();
		context.waitTicks(3);
		String pasted = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).goBox().getValue());
		if (!pasted.contains("5000.50 80.00 -7000.50")) {
			throw new AssertionError("Ctrl+V in the box left it with: " + pasted);
		}
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(5);
		mark = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).goMark());
		if (mark == null || mark[0] != 5000 || mark[1] != 80 || mark[2] != -7001 || mark[3] != 1) {
			throw new AssertionError("The pasted F3+C line marked " + java.util.Arrays.toString(mark));
		}
		expectCentre(context, 5000, 80, -7001, "After pasting the F3+C line");
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("go-to-06-unexplored");

		// No height and nothing mapped there: the view still goes, to where most ground is.
		clickGoButton(context);
		context.getInput().typeChars("9000 -9000");
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(10);
		mark = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).goMark());
		if (mark == null || mark[0] != 9000 || mark[2] != -9000 || mark[3] != 0) {
			throw new AssertionError("9000 -9000 on unmapped land marked " + java.util.Arrays.toString(mark));
		}
		expectCentre(context, 9000, mark[1], -9000, "After typing 9000 -9000");

		// Text with no place in it: said above the box, which stays open, and the view stays put.
		double[] before = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).centre());
		clickGoButton(context);
		context.getInput().typeChars("my base");
		context.getInput().pressKey(InputConstants.KEY_RETURN);
		context.waitTicks(3);
		context.takeScreenshot("go-to-07-no-coordinates");
		String problem = context.computeOnClient(mc -> {
			MapScreen map = (MapScreen) mc.gui.screen();
			return map.goBox().visible ? map.goProblem() : "box closed";
		});
		if (problem == null || !problem.startsWith("No coordinates")) {
			throw new AssertionError("Enter on text without coordinates: " + problem);
		}
		// The map's keys are letters while the box is open: C doesn't switch to caves, Space doesn't recenter.
		context.getInput().pressKey(InputConstants.KEY_C);
		context.getInput().pressKey(InputConstants.KEY_SPACE);
		context.waitTicks(3);
		double[] after = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).centre());
		int layer = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view().layer());
		if (layer != TileKey.SURFACE || after[0] != before[0] || after[1] != before[1]) {
			throw new AssertionError("C and Space acted on the map while the coordinates box was open");
		}
		// Esc puts the box away and leaves the map open.
		pressEscape(context);
		context.runOnClient(mc -> {
			if (!(mc.gui.screen() instanceof MapScreen map) || map.goBox().visible) {
				throw new AssertionError("Esc with the coordinates box open didn't just close the box");
			}
		});

		// A place in a dimension with no map: said, and the view stays.
		boolean went = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).goTo("/execute in diorama:nowhere run tp @s 1.5 70.0 2.5 0.0 0.0"));
		String noMap = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).goProblem());
		after = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).centre());
		if (went || noMap == null || !noMap.startsWith("No map of") || after[0] != before[0] || after[1] != before[1]) {
			throw new AssertionError("Going to a dimension that has no map: went " + went + ", said " + noMap);
		}
		context.setScreen(() -> null);
	}

	private static void clickGoButton(ClientGameTestContext context) {
		double[] at = context.computeOnClient(mc -> {
			MapScreen map = (MapScreen) mc.gui.screen();
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(map.goButton().getX() + 10) * scale, (map.goButton().getY() + 10) * scale};
		});
		DioramaClientTest.moveCursor(context, at[0], at[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		context.runOnClient(mc -> {
			MapScreen map = (MapScreen) mc.gui.screen();
			if (!map.goBox().visible || map.getFocused() != map.goBox()) {
				throw new AssertionError("Clicking Go to coordinates didn't open its box ready to type in");
			}
		});
	}

	private static void pressEscape(ClientGameTestContext context) {
		//? if >=1.21.9 {
		context.runOnClient(mc -> mc.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_ESCAPE, 0, 0)));
		//?} else {
		/*context.runOnClient(mc -> mc.gui.screen().keyPressed(InputConstants.KEY_ESCAPE, 0, 0));
		*///?}
		context.waitTicks(2);
	}

	private static void expectCentre(ClientGameTestContext context, int x, int y, int z, String when) {
		double[] centre = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).centre());
		double u = Projection.u(x + 0.5, z + 0.5);
		double v = Projection.v(x + 0.5, y, z + 0.5);
		if (Math.abs(centre[0] - u) > 1e-6 || Math.abs(centre[1] - v) > 1e-6) {
			throw new AssertionError(when + " the view's middle is " + centre[0] + ", " + centre[1] + ", not " + u + ", " + v + " (" + x + ", " + y + ", " + z + ")");
		}
	}

	/** Every shape of coordinates the box reads, and text it must leave alone. */
	private static void reads() {
		read("120 -340", 120, null, -340, null);
		read("120, 64, -340", 120, 64, -340, null);
		read("120 64 -340", 120, 64, -340, null);
		read("[120, 64, -340]", 120, 64, -340, null);
		read("  -12,7  ", -12, null, 7, null);
		read("x: 120, z: -340", 120, null, -340, null);
		read("X=120 Y=64 Z=-340", 120, 64, -340, null);
		read("Z: -340 X: 120", 120, null, -340, null);
		read("[name:\"Base\", x:120, y:64, z:-340, dim:minecraft:the_nether]", 120, 64, -340, "minecraft:the_nether");
		read("/tp 120 64 -340", 120, 64, -340, null);
		read("/tp @s 120 64 -340", 120, 64, -340, null);
		read("/execute in minecraft:overworld run tp @s 120.50 64.00 -340.50 90.00 0.00", 120, 64, -341, "minecraft:overworld");
		read("<Alex> xaero-waypoint:Base:B:120:64:-340:5:false:0:Internal-the-nether-waypoints", 120, 64, -340, "minecraft:the_nether");
		read("xaero-waypoint:Base:B:120:~:-340:5:false:0:Internal-overworld-waypoints", 120, null, -340, "minecraft:overworld");
		read("120.9 -0.5", 120, null, -1, null);
		for (String none : new String[] {"", "   ", "my base", "120", "1 2 3 4", "40000000 0", "0 99999 0", "minecraft:overworld", "x: 5"}) {
			MapCoordinates.Place place = MapCoordinates.parse(none);
			if (place != null) {
				throw new AssertionError("\"" + none + "\" was read as " + place);
			}
		}
	}

	private static void read(String text, int x, Integer y, int z, String dimension) {
		MapCoordinates.Place expected = new MapCoordinates.Place(x, y, z, dimension);
		MapCoordinates.Place place = MapCoordinates.parse(text);
		if (!expected.equals(place)) {
			throw new AssertionError("\"" + text + "\" was read as " + place + ", not " + expected);
		}
	}
}
