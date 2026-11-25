package dev.diorama.test;

//? if >=1.21.6 && fabric {
import dev.diorama.Diorama;
import java.lang.management.ManagementFactory;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;

/**
 * Bytes one of Diorama's HUD elements allocates a frame on the render thread ({@code addon-api}):
 * the minimap ({@code diorama:minimap}) or the waypoints in the world ({@code diorama:waypoints}).
 * Three probe elements go around it, A and B before it and C after. A to B is what going from
 * one element to the next costs; B to C is the element and two of those, which are taken off. The
 * probes only read the thread's allocation counter.
 */
final class AllocProbe {
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	static final AllocProbe MINIMAP = new AllocProbe("minimap");
	static final AllocProbe WAYPOINTS = new AllocProbe("waypoints");

	private final String element;
	private boolean registered;
	private boolean counting;
	private long a;
	private long b;
	private int frames;
	private long total;
	private long between;
	private long most;
	private long least;
	/** Each frame's bytes, kept in place so counting them allocates nothing. */
	private final long[] samples = new long[4096];

	private AllocProbe(String element) {
		this.element = element;
	}

	/** Puts the minimap's probes around it; once, on the client thread. */
	static void register() {
		MINIMAP.attach();
	}

	/** Counts this many frames of the minimap and logs bytes a frame; returns the median. */
	static long measure(ClientGameTestContext context, String what, int count) {
		return MINIMAP.count(context, what, count);
	}

	/** Puts the probes around the element; once, on the client thread. */
	void attach() {
		if (this.registered) {
			return;
		}
		this.registered = true;
		Identifier target = Diorama.id(this.element);
		HudElementRegistry.attachElementBefore(target, Identifier.fromNamespaceAndPath("diorama-test", "alloc_a_" + this.element), (graphics, delta) -> {
			if (this.counting) {
				this.a = THREADS.getCurrentThreadAllocatedBytes();
			}
		});
		HudElementRegistry.attachElementBefore(target, Identifier.fromNamespaceAndPath("diorama-test", "alloc_b_" + this.element), (graphics, delta) -> {
			if (this.counting) {
				this.b = THREADS.getCurrentThreadAllocatedBytes();
			}
		});
		HudElementRegistry.attachElementAfter(target, Identifier.fromNamespaceAndPath("diorama-test", "alloc_c_" + this.element), (graphics, delta) -> {
			if (this.counting && this.a != 0 && this.b != 0) {
				long c = THREADS.getCurrentThreadAllocatedBytes();
				long step = this.b - this.a;
				long net = (c - this.b) - 2 * step;
				this.between += step;
				this.total += net;
				this.most = Math.max(this.most, net);
				this.least = Math.min(this.least, net);
				if (this.frames < this.samples.length) {
					this.samples[this.frames] = net;
				}
				this.frames++;
			}
		});
	}

	/**
	 * Counts this many frames and logs bytes a frame; returns the median. Run it with the map
	 * settled and the world paused, so every frame draws the same.
	 */
	long count(ClientGameTestContext context, String what, int count) {
		context.runOnClient(mc -> {
			this.a = 0;
			this.b = 0;
			this.frames = 0;
			this.total = 0;
			this.between = 0;
			this.most = Long.MIN_VALUE;
			this.least = Long.MAX_VALUE;
			this.counting = true;
		});
		context.waitFor(mc -> this.frames >= count, 20 * 600);
		long[] result = context.computeOnClient(mc -> {
			this.counting = false;
			long[] sorted = java.util.Arrays.copyOf(this.samples, Math.min(this.frames, this.samples.length));
			java.util.Arrays.sort(sorted);
			return new long[] {this.frames, this.total, this.least, this.most, this.between, sorted[sorted.length / 2]};
		});
		Diorama.LOGGER.info("addon-api alloc: {}: {} frames, the {} draw allocated a median {} bytes a frame (least {}, most {}, mean {}); going between elements {} bytes in all",
			what, result[0], this.element, result[5], result[2], result[3], String.format(java.util.Locale.ROOT, "%.1f", result[1] / (double) result[0]), result[4]);
		return result[5];
	}
}
//?}
