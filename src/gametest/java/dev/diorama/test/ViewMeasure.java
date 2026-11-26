package dev.diorama.test;

//? if >=26.1 && fabric {
import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.Projection;
import dev.diorama.addon.AddonView;
import dev.diorama.api.MapView;
import dev.diorama.api.ViewPlace;
import dev.diorama.bake.TileKey;
import dev.diorama.testaddon.TestAddon;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/**
 * What an addon's map view costs a frame, in bytes the render thread allocates ({@code addon-api},
 * 26.x Fabric, world paused): from just before a screen works out what to draw to just after,
 * through Fabric's screen events, less what reading the counter twice costs. The same place at the
 * same zoom: the full map, the view of the map alone, the view with every part, two views, and the
 * view screen with no view, which is what the screen itself costs.
 */
final class ViewMeasure {
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static boolean registered;
	private static boolean counting;
	private static long before;
	private static int frames;
	private static final long[] SAMPLES = new long[4096];
	private static long empty;

	private ViewMeasure() {
	}

	private static void register() {
		if (registered) {
			return;
		}
		registered = true;
		ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
			if (!(screen instanceof MapScreen) && !(screen instanceof ViewScreen)) {
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

	/** Counts this many frames of the open screen; returns the median bytes a frame, the least and the most. */
	private static long[] count(ClientGameTestContext context, int count) {
		context.runOnClient(mc -> {
			long a = THREADS.getCurrentThreadAllocatedBytes();
			long b = THREADS.getCurrentThreadAllocatedBytes();
			empty = b - a;
			before = 0;
			frames = 0;
			counting = true;
		});
		context.waitFor(mc -> frames >= count, 20 * 600);
		return context.computeOnClient(mc -> {
			counting = false;
			long[] sorted = Arrays.copyOf(SAMPLES, Math.min(frames, SAMPLES.length));
			Arrays.sort(sorted);
			return new long[] {sorted[sorted.length / 2], sorted[0], sorted[sorted.length - 1]};
		});
	}

	private static String row(ClientGameTestContext context, String what) {
		count(context, 100);
		long[] got = count(context, 300);
		return String.format(Locale.ROOT, "%-44s %10d %10d %10d", what, got[0], got[1], got[2]);
	}

	static void run(ClientGameTestContext context, double px, double py, double pz) {
		context.runOnClient(mc -> register());
		int[] gui = context.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
		List<String> table = new ArrayList<>();
		table.add(String.format(Locale.ROOT, "%-44s %10s %10s %10s", "the same place at 4 GUI px a block", "median", "least", "most"));
		List<MapView> opened = new ArrayList<>();
		try (WorldPause paused = WorldPause.pause(context)) {
			// The full map, whole screen.
			AddonMarkersTest.openMap(context);
			context.runOnClient(mc -> {
				if (mc.gui.screen() instanceof MapScreen map) {
					map.lookAt(Projection.u(px, pz), Projection.v(px, py, pz), 4, TileKey.SURFACE);
				}
			});
			context.waitTicks(20);
			table.add(row(context, "full map, whole screen"));

			ViewPlace whole = ViewPlace.builder(0, 0, gui[0], gui[1]).center(px, py, pz).zoom(4).build();
			ViewPlace half = ViewPlace.builder(gui[0] / 4, gui[1] / 4, gui[0] / 2, gui[1] / 2).center(px, py, pz).zoom(4).build();
			MapView alone = context.computeOnClient(mc -> TestAddon.api().openView(EnumSet.noneOf(MapView.Part.class)));
			opened.add(alone);
			MapView all = context.computeOnClient(mc -> TestAddon.api().openView(EnumSet.allOf(MapView.Part.class)));
			opened.add(all);

			measureScreen(context, table, "view screen, no view", List.of());
			measureScreen(context, table, "view, map alone, whole screen", List.of(new ViewScreen.Draw(alone, whole)));
			measureScreen(context, table, "view, every part, whole screen", List.of(new ViewScreen.Draw(all, whole)));
			measureScreen(context, table, "view, map alone, half the screen", List.of(new ViewScreen.Draw(alone, half)));
			measureScreen(context, table, "two views, half the screen each", List.of(new ViewScreen.Draw(alone, half), new ViewScreen.Draw(all, half)));
		} finally {
			context.runOnClient(mc -> {
				for (MapView view : opened) {
					view.close();
				}
			});
			context.setScreen(() -> null);
		}
		int open = context.computeOnClient(mc -> AddonView.openCount());
		table.add("views open after closing both: " + open);
		Diorama.LOGGER.info("addon-api view alloc: bytes a frame on the render thread (world paused):\n{}", String.join("\n", table));
	}

	private static void measureScreen(ClientGameTestContext context, List<String> table, String what, List<ViewScreen.Draw> draws) {
		ViewScreen screen = new ViewScreen(draws);
		context.setScreen(() -> screen);
		context.waitFor(mc -> screen.frames >= 5, 600);
		context.waitTicks(20);
		table.add(row(context, what));
	}
}
//?}
