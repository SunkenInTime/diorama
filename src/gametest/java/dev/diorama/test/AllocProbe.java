package dev.diorama.test;

//? if >=1.21.6 && fabric {
import dev.diorama.Diorama;
import java.lang.management.ManagementFactory;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

/**
 * Bytes the minimap's HUD element allocates a frame on the render thread ({@code addon-api}).
 * Three probe elements go around it, A and B before it and C after. A to B is what going from
 * one element to the next costs; B to C is the minimap and two of those, which are taken off. The
 * probes only read the thread's allocation counter.
 */
final class AllocProbe {
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static boolean registered;
	private static boolean counting;
	private static long a;
	private static long b;
	private static int frames;
	private static long total;
	private static long between;
	private static long most;
	private static long least;
	/** Each frame's bytes, kept in place so counting them allocates nothing. */
	private static final long[] SAMPLES = new long[4096];

	private AllocProbe() {
	}

	/** Puts the probes around the minimap; once, on the client thread. */
	static void register() {
		if (registered) {
			return;
		}
		registered = true;
		Identifier minimap = Diorama.id("minimap");
		HudElementRegistry.attachElementBefore(minimap, Identifier.fromNamespaceAndPath("diorama-test", "alloc_a"), (graphics, delta) -> {
			if (counting) {
				a = THREADS.getCurrentThreadAllocatedBytes();
			}
		});
		HudElementRegistry.attachElementBefore(minimap, Identifier.fromNamespaceAndPath("diorama-test", "alloc_b"), (graphics, delta) -> {
			if (counting) {
				b = THREADS.getCurrentThreadAllocatedBytes();
			}
		});
		HudElementRegistry.attachElementAfter(minimap, Identifier.fromNamespaceAndPath("diorama-test", "alloc_c"), (graphics, delta) -> {
			if (counting && a != 0 && b != 0) {
				long c = THREADS.getCurrentThreadAllocatedBytes();
				long step = b - a;
				long net = (c - b) - 2 * step;
				between += step;
				total += net;
				most = Math.max(most, net);
				least = Math.min(least, net);
				if (frames < SAMPLES.length) {
					SAMPLES[frames] = net;
				}
				frames++;
			}
		});
	}

	/**
	 * Counts this many frames and logs bytes a frame; returns the median. Run it with the map
	 * settled and the world paused, so every frame draws the same minimap.
	 */
	static long measure(ClientGameTestContext context, String what, int count) {
		context.runOnClient(mc -> {
			a = 0;
			b = 0;
			frames = 0;
			total = 0;
			between = 0;
			most = Long.MIN_VALUE;
			least = Long.MAX_VALUE;
			counting = true;
		});
		context.waitFor(mc -> frames >= count, 20 * 600);
		long[] result = context.computeOnClient(mc -> {
			counting = false;
			long[] sorted = java.util.Arrays.copyOf(SAMPLES, Math.min(frames, SAMPLES.length));
			java.util.Arrays.sort(sorted);
			return new long[] {frames, total, least, most, between, sorted[sorted.length / 2]};
		});
		Diorama.LOGGER.info("addon-api alloc: {}: {} frames, the minimap's draw allocated a median {} bytes a frame (least {}, most {}, mean {}); going between elements {} bytes in all",
			what, result[0], result[5], result[2], result[3], String.format(java.util.Locale.ROOT, "%.1f", result[1] / (double) result[0]), result[4]);
		return result[5];
	}
}
//?}
