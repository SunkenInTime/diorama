package dev.diorama.test;

import dev.diorama.Settings;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The text under waypoints in the world (opt-in: {@code waypoint-text-size}): a near and a far
 * waypoint at every World size. Both are measured in the screenshot: the pin and the letters have
 * to be whole screen pixels, the letters the game's own size at the default and bigger and smaller
 * in step with the pin up to 100%, where they stop growing while the pin goes on to 200%.
 */
final class WaypointTextSizeTest {
	/** Colours no other pin or block uses, to find each pin's head by. */
	private static final int NEAR = 0xFF00FF;
	private static final int FAR = 0x00FFFF;
	/**
	 * A GUI scale, then pairs of a World size and the screen pixels to a font pixel its text has to
	 * be: first the default, which is the game's own text size, then every step the slider has
	 * there; past 100 the text stays as it is at 100. The step the slider shows for the default (50, 67 and 50) has to look like the default,
	 * or moving the slider and putting it back would leave the text another size.
	 */
	private static final int[][] SIZES = {
		{2, 60, 2, 50, 2, 100, 4, 150, 4, 200, 4},
		{3, 60, 3, 33, 2, 67, 3, 100, 5, 133, 5, 167, 5, 200, 5},
		{4, 60, 4, 25, 2, 50, 4, 75, 6, 100, 8, 125, 8, 150, 8, 175, 8, 200, 8}
	};
	/** How many pixels of a pin's head are its own colour, across, and how many pin pixels its text starts below them; measured on the first pin. */
	private static int headPixels = -1;
	private static int gapPixels = Integer.MIN_VALUE;

	private WaypointTextSizeTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("difficulty peaceful");
		int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, -2, -2));
		// A dark floor and wall, so the only white under a pin is its text.
		world.getServer().runCommand("fill -20 " + ground + " -40 26 " + (ground + 12) + " 2 minecraft:air");
		world.getServer().runCommand("fill -20 " + (ground - 1) + " -40 26 " + (ground - 1) + " 2 minecraft:black_concrete");
		world.getServer().runCommand("fill -20 " + ground + " -40 26 " + (ground + 12) + " -40 minecraft:black_concrete");
		world.getServer().runCommand("tp @a -1.5 " + ground + " -1.5 180 10");
		context.waitFor(mc -> mc.player.onGround() && mc.player.blockPosition().getX() == -2 && mc.player.blockPosition().getZ() == -2, 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		Settings.WaypointLabels labels = context.computeOnClient(mc -> Settings.get().waypointLabels);
		context.runOnClient(mc -> {
			Settings settings = Settings.get();
			settings.waypointLabels = Settings.WaypointLabels.ALWAYS;
			settings.minimap = false;
			WaypointStore store = WaypointStore.get();
			Waypoint near = new Waypoint("Base", at.getX() - 4, at.getY(), at.getZ() - 6, "minecraft:overworld", NEAR);
			near.initials = "B";
			store.add(near);
			Waypoint far = new Waypoint("Mine", at.getX() + 19, at.getY(), at.getZ() - 30, "minecraft:overworld", FAR);
			far.initials = "M";
			store.add(far);
		});
		// Big enough for a GUI scale of 4, which a smaller window clamps to 3. Long enough for the commands' chat lines to fade.
		context.getInput().resizeWindow(1920, 1080);
		context.waitTicks(220);
		for (int[] row : SIZES) {
			int gui = row[0];
			DioramaClientTest.setGuiScale(context, gui);
			for (int i = 1; i < row.length; i += 2) {
				int size = row[i];
				int text = row[i + 1];
				context.runOnClient(mc -> Settings.get().waypointWorldSize = size);
				context.waitTicks(4);
				BufferedImage shot = read(context.takeScreenshot("label-size-gui" + gui + "-" + size));
				int pin = Math.max(1, Math.round(size * gui / 100.0F));
				check(shot, NEAR, pin, text, "the near waypoint at " + size + "% on GUI scale " + gui);
				check(shot, FAR, pin, text, "the far waypoint at " + size + "% on GUI scale " + gui);
			}
		}
		// The minimap's and the map's pins have their own sizes: the picture shows the minimap's unchanged.
		context.runOnClient(mc -> {
			Settings.get().minimap = true;
			Settings.get().waypointWorldSize = 200;
		});
		context.waitTicks(4);
		context.takeScreenshot("label-size-with-minimap");

		DioramaClientTest.setGuiScale(context, guiScale);
		context.getInput().resizeWindow(1600, 900);
		context.waitTicks(10);
		context.runOnClient(mc -> {
			Settings settings = Settings.get();
			settings.waypointWorldSize = Settings.DEFAULT_WAYPOINT_SIZE;
			settings.waypointLabels = labels;
			settings.minimap = true;
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
		});
	}

	/**
	 * The waypoint with a head of this colour is drawn at {@code pin} screen pixels to a pixel of
	 * pin, with {@code text} to a pixel of the text under it, whose tall letters and digits are 7 high.
	 */
	private static void check(BufferedImage shot, int colour, int pin, int text, String what) {
		int[] head = extent(shot, colour, 0, 0, shot.getWidth(), shot.getHeight());
		if (head[2] < 0) {
			throw new AssertionError("No pin for " + what);
		}
		int across = head[2] - head[0] + 1;
		// However many of the head's pixels are its own colour, every pin has as many.
		if (headPixels < 0 && across % pin == 0) {
			headPixels = across / pin;
		}
		if (across != headPixels * pin) {
			throw new AssertionError("The pin of " + what + " is " + across + " screen pixels across its colour, not " + headPixels + " pixels of " + pin);
		}
		// The text is under the pin, no wider than "Mine · 36 m": white on the box's dark.
		int middle = (head[0] + head[2]) / 2;
		int[] letters = extent(shot, 0xFFFFFF, middle - 30 * text, head[3], middle + 30 * text, head[3] + 24 * pin + 12 * text);
		// The spot isn't on a whole pixel, so the letters can catch one more row of screen pixels.
		int high = letters[3] - letters[1] + 1;
		if (letters[2] < 0 || high < 7 * text || high > 7 * text + 1) {
			throw new AssertionError("The text of " + what + " covers " + Arrays.toString(letters) + ", which isn't 7 pixels of " + text + " high");
		}
		// It starts one of its own pixels into its box, which hangs a fixed number of pin pixels under
		// the head, and it's centred under the pin. The spot isn't on a whole pixel and the first pin is small to measure by, so it can be a pin pixel off.
		int gap = letters[1] - head[3] - text;
		if (gapPixels == Integer.MIN_VALUE) {
			gapPixels = Math.round(gap / (float) pin);
		}
		if (Math.abs(gap - gapPixels * pin) > 1 + pin || Math.abs((letters[0] + letters[2]) / 2 - middle) > 2 * text) {
			throw new AssertionError("The text of " + what + " covers " + Arrays.toString(letters) + " under a head at " + Arrays.toString(head) + ": not centred " + gapPixels + " pin pixels of " + pin + " and one of its own below it");
		}
	}

	/** The box around every pixel of this colour inside a part of a screenshot: x0, y0, x1, y1. */
	private static int[] extent(BufferedImage shot, int rgb, int left, int top, int right, int bottom) {
		int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};
		for (int y = Math.max(0, top); y < Math.min(shot.getHeight(), bottom); y++) {
			for (int x = Math.max(0, left); x < Math.min(shot.getWidth(), right); x++) {
				if (close(shot.getRGB(x, y), rgb)) {
					box[0] = Math.min(box[0], x);
					box[1] = Math.min(box[1], y);
					box[2] = Math.max(box[2], x);
					box[3] = Math.max(box[3], y);
				}
			}
		}
		return box;
	}

	/** Before 1.21.6 the game draws text a shade off the colour it's given. */
	private static boolean close(int argb, int rgb) {
		return Math.abs((argb >> 16 & 0xFF) - (rgb >> 16 & 0xFF)) <= 4 && Math.abs((argb >> 8 & 0xFF) - (rgb >> 8 & 0xFF)) <= 4 && Math.abs((argb & 0xFF) - (rgb & 0xFF)) <= 4;
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
