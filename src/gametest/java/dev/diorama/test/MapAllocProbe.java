package dev.diorama.test;

//? if >=26.1 && fabric {
import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import java.lang.management.ManagementFactory;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/**
 * Bytes the full map allocates a frame on the render thread ({@code addon-api}): from just before
 * the map screen works out what to draw to just after, through Fabric's screen events, less what
 * two empty probes cost. As {@link AllocProbe} does for the minimap. Run it with the map settled and
 * the world paused, so every frame draws the same.
 *
 * <p>{@link #none} is the part that runs on master as well: the minimap and the full map with no
 * addon layer, for comparing a branch's frames with master's.
 */
final class MapAllocProbe {
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static boolean registered;
	private static boolean counting;
	private static long before;
	private static int frames;
	private static final long[] SAMPLES = new long[4096];
	/** What reading the counter twice costs, taken off each frame. */
	private static long empty;

	private MapAllocProbe() {
	}

	private static void register() {
		if (registered) {
			return;
		}
		registered = true;
		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			if (!(screen instanceof MapScreen)) {
				return;
			}
			ScreenEvents.beforeExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				if (counting) {
					before = THREADS.getCurrentThreadAllocatedBytes();
				}
			});
			ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
				if (counting && before != 0) {
					long bytes = THREADS.getCurrentThreadAllocatedBytes() - before - empty;
					if (frames < SAMPLES.length) {
						SAMPLES[frames] = bytes;
					}
					frames++;
				}
			});
		});
	}

	/** Counts this many frames of the open map and logs bytes a frame; returns the median. */
	static long measure(ClientGameTestContext context, String what, int count) {
		context.runOnClient(mc -> {
			long a = THREADS.getCurrentThreadAllocatedBytes();
			long b = THREADS.getCurrentThreadAllocatedBytes();
			empty = b - a;
			before = 0;
			frames = 0;
			counting = true;
		});
		context.waitFor(mc -> frames >= count, 20 * 600);
		long[] result = context.computeOnClient(mc -> {
			counting = false;
			long[] sorted = java.util.Arrays.copyOf(SAMPLES, Math.min(frames, SAMPLES.length));
			java.util.Arrays.sort(sorted);
			long total = 0;
			for (long one : sorted) {
				total += one;
			}
			return new long[] {sorted.length, sorted[0], sorted[sorted.length - 1], total / sorted.length, sorted[sorted.length / 2]};
		});
		Diorama.LOGGER.info("addon-api alloc: full map: {}: {} frames, a median {} bytes a frame (least {}, most {}, mean {})", what, result[0], result[4], result[1], result[2], result[3]);
		return result[4];
	}

	/**
	 * The minimap and the full map with no addon layer open: bytes a frame, medians, minimap
	 * first. The map is settled and the world paused while it counts, with every mob and dropped
	 * item gone first, so that a run on master and one on a branch draw the same frames: the
	 * radar's icons are most of what changes from one run to the next.
	 */
	static long[] none(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("kill @e[type=!player]");
		context.runOnClient(mc -> {
			AllocProbe.register();
			register();
		});
		context.setScreen(() -> null);
		DioramaClientTest.settle(context, 20);
		try (WorldPause paused = WorldPause.pause(context)) {
			world.getServer().runCommand("kill @e[type=!player]");
			context.waitTicks(5);
			AllocProbe.measure(context, "warm-up, no layer", 150);
			long minimap = AllocProbe.measure(context, "no layer", 300);
			context.setScreen(MapScreen::new);
			// The cursor on the map's bottom bar, where it shows no tooltip.
			double[] bar = context.computeOnClient(mc -> new double[] {mc.getWindow().getScreenWidth() / 2.0, mc.getWindow().getScreenHeight() - 5});
			context.getInput().setCursorPos(bar[0], bar[1]);
			context.waitTicks(20);
			measure(context, "warm-up, no layer", 150);
			long map = measure(context, "no layer", 300);
			context.setScreen(() -> null);
			Diorama.LOGGER.info("addon-api alloc: no layer: minimap {} bytes a frame, full map {} (medians)", minimap, map);
			return new long[] {minimap, map};
		}
	}
}
//?}
