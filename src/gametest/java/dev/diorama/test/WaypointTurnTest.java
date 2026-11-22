package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.Settings;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A waypoint in the world stays on its spot while the camera turns (opt-in: {@code waypoint-turn},
 * #399). The camera turns a step and the very next frame is read: its beam and pin have to stand
 * over the pillar they mark, as they do in a frame drawn with the camera still. A waypoint laid out
 * with the camera of the frame before stands a whole step to the side of it. On 26.1 Fabric the
 * screenshot after a turn still shows the frame before it, so the test can't fail there; 26.1.2
 * NeoForge runs the same code and shows it.
 */
final class WaypointTurnTest {
	/** Degrees the camera turns before each frame. */
	private static final float STEP = 4;
	/** Screen pixels a beam or pin may be off its spot: what rounding and the beam's soft edge come to. */
	private static final double ALLOWED = 3;
	/** Columns either side of the pillar that are looked at, and rows above it the pin may reach. */
	private static final int SIDE = 150;
	private static final int PIN_ROWS = 70;
	/** A row with this many of the waypoint's pixels is the pin's and not the beam's. */
	private static final int PIN_WIDTH = 17;

	private WaypointTurnTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		BlockPos spawn = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = spawn.getX();
		int z = spawn.getZ();
		// A stage in the air: a black floor and a black wall, so the only things on screen near the
		// magenta pillar are the pillar and what the waypoint on it draws.
		int floor = context.computeOnClient(mc -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)) + 30;
		fill(world, x - 5, floor, z - 23, x + 5, floor, z + 3, "black_concrete");
		fill(world, x - 24, floor, z - 23, x + 72, floor + 38, z - 23, "black_concrete");
		fill(world, x, floor + 1, z - 20, x, floor + 4, z - 20, "magenta_concrete");
		// Facing a little right of the pillar, so it stays clear of the crosshair through every turn, and
		// level, so the beam stands straight up the screen.
		world.getServer().runCommand("tp @a " + (x + 0.5) + " " + (floor + 1) + " " + (z + 0.5) + " 188 0");

		Settings.Beams beams = context.computeOnClient(mc -> Settings.get().waypointBeams);
		Settings.WaypointLabels labels = context.computeOnClient(mc -> Settings.get().waypointLabels);
		int size = context.computeOnClient(mc -> Settings.get().waypointWorldSize);
		Waypoint waypoint = new Waypoint("Turn", x, floor + 4, z - 20, "minecraft:overworld", 0xFF46A758);
		context.runOnClient(mc -> {
			Settings.get().waypointBeams = Settings.Beams.ALL;
			// The text under a pin would cover the pillar.
			Settings.get().waypointLabels = Settings.WaypointLabels.NONE;
			// The pin is told from the beam by its width.
			Settings.get().waypointWorldSize = Settings.DEFAULT_WAYPOINT_SIZE;
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
			store.add(waypoint);
		});
		// Long enough for the commands' chat lines to fade and the beam to find its line of sight.
		context.waitTicks(220);

		List<String> wrong = new ArrayList<>();
		Frame still = shot(context, "waypoint-turn-still");
		check(wrong, "still", still.beamX - still.pillarX, still.pinX - still.pillarX, 0);
		// To the right and back, then down and back. Turning sideways the beam and the pin are measured
		// against the pillar in the same frame; turning up and down the beam says nothing, so the pin's
		// height over the pillar is measured against what it is once the camera has stopped.
		for (int i = 0; i < 12; i++) {
			boolean sideways = i < 6;
			float by = i % 6 < 3 ? STEP : -STEP;
			String name = (sideways ? by > 0 ? "right" : "left" : by > 0 ? "down" : "up") + "-" + (i % 3 + 1);
			context.runOnClient(mc -> mc.player.turn(sideways ? by / 0.15 : 0, sideways ? 0 : by / 0.15));
			Frame turning = shot(context, "waypoint-turn-" + name);
			Frame stopped = shot(context, "waypoint-turn-" + name + "-stopped");
			if (sideways) {
				check(wrong, name, turning.beamX - turning.pillarX, turning.pinX - turning.pillarX, 0);
			} else {
				// Looking up or down, the beam leans across the screen: only the pin's height is read.
				check(wrong, name, 0, 0, stopped.pinOver - turning.pinOver);
			}
		}

		context.runOnClient(mc -> {
			Settings.get().waypointBeams = beams;
			Settings.get().waypointLabels = labels;
			Settings.get().waypointWorldSize = size;
			Settings.get().save();
			WaypointStore.get().remove(waypoint);
		});
		world.getServer().runCommand("tp @a " + (spawn.getX() + 0.5) + " " + spawn.getY() + " " + (spawn.getZ() + 0.5));
		// Until the client has the move: the next section may read where the player is at once.
		context.waitFor(mc -> mc.player.blockPosition().distManhattan(spawn) <= 2, 400);
		fill(world, x - 24, floor, z - 23, x + 72, floor + 38, z - 23, "air");
		fill(world, x - 5, floor, z - 23, x + 5, floor + 4, z + 3, "air");
		if (!wrong.isEmpty()) {
			throw new AssertionError("A waypoint in the world left its spot while the camera turned " + STEP + "° a frame (pixels off, of " + ALLOWED + " allowed): " + String.join("; ", wrong));
		}
	}

	private static void check(List<String> wrong, String name, double beam, double pin, double foot) {
		String line = "%s: beam %.1f px and pin %.1f px to the side of the pillar, pin %.1f px below where it settles".formatted(name, beam, pin, foot);
		// boat runs keep the log: the table of a run, passing or not.
		Diorama.LOGGER.info("waypoint-turn {}", line);
		if (!(Math.abs(beam) <= ALLOWED && Math.abs(pin) <= ALLOWED && Math.abs(foot) <= ALLOWED)) {
			wrong.add(line);
		}
	}

	/**
	 * What a frame shows: the middle of the pillar, of the beam above the pin and of the pin, and how
	 * far the pin's middle is over the pillar's top.
	 */
	private record Frame(double pillarX, double beamX, double pinX, double pinOver) {
	}

	private static Frame shot(ClientGameTestContext context, String name) {
		BufferedImage image;
		try {
			image = ImageIO.read(context.takeScreenshot(name).toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		long sum = 0;
		int count = 0;
		int top = Integer.MAX_VALUE;
		int bottom = 0;
		// The middle of the screen: the minimap shows the pillar too.
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = image.getWidth() / 8; x < image.getWidth() * 3 / 4; x++) {
				if (pillar(image.getRGB(x, y))) {
					sum += x;
					count++;
					top = Math.min(top, y);
					bottom = Math.max(bottom, y);
				}
			}
		}
		if (count < 200) {
			throw new AssertionError(name + ": the pillar isn't on screen, " + count + " of its pixels");
		}
		double pillarX = (double) sum / count;
		int left = Math.max(0, (int) pillarX - SIDE);
		int right = Math.min(image.getWidth() - 1, (int) pillarX + SIDE);
		double beamX = middle(image, left, right, 10, top - PIN_ROWS, name + ": the beam");
		double pinX = middle(image, left, right, top - PIN_ROWS, top, name + ": the pin");
		// The pin's rows are the wide ones: the beam is half as wide. Only close to the pillar, where
		// the crosshair isn't, and above its foot, where the floor's edge can be as bright.
		long rows = 0;
		int wide = 0;
		for (int y = 0; y < bottom - 20; y++) {
			int across = 0;
			for (int x = Math.max(0, (int) pillarX - 40); x <= Math.min(image.getWidth() - 1, (int) pillarX + 40); x++) {
				if (drawn(image.getRGB(x, y))) {
					across++;
				}
			}
			if (across >= PIN_WIDTH) {
				rows += y;
				wide++;
			}
		}
		// None when the pin stands well to the side of the pillar: that fails as a height too.
		return new Frame(pillarX, beamX, pinX, wide < 5 ? Double.NaN : top - (double) rows / wide);
	}

	/** The middle column of what the waypoint drew in these rows. */
	private static double middle(BufferedImage image, int left, int right, int from, int to, String what) {
		long sum = 0;
		int count = 0;
		for (int y = Math.max(0, from); y < to; y++) {
			for (int x = left; x <= right; x++) {
				if (drawn(image.getRGB(x, y))) {
					sum += x;
					count++;
				}
			}
		}
		if (count < 50) {
			throw new AssertionError(what + " isn't on screen near the pillar, " + count + " of its pixels");
		}
		return (double) sum / count;
	}

	private static boolean pillar(int rgb) {
		int r = rgb >> 16 & 0xFF;
		int g = rgb >> 8 & 0xFF;
		int b = rgb & 0xFF;
		return r > 90 && r > g + 50 && b > g + 40;
	}

	/** Neither the black stage nor the pillar: a pixel of the beam or the pin. */
	private static boolean drawn(int rgb) {
		return !pillar(rgb) && Math.max(rgb >> 16 & 0xFF, Math.max(rgb >> 8 & 0xFF, rgb & 0xFF)) >= 90;
	}

	private static void fill(TestSingleplayerContext world, int x0, int y0, int z0, int x1, int y1, int z1, String block) {
		world.getServer().runCommand("fill " + x0 + " " + y0 + " " + z0 + " " + x1 + " " + y1 + " " + z1 + " minecraft:" + block);
	}
}
