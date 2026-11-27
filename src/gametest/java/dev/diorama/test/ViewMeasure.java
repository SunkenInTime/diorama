package dev.diorama.test;

//? if >=26.1 && fabric {
import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.addon.AddonView;
import dev.diorama.api.DimensionId;
import dev.diorama.api.LayerContent;
import dev.diorama.api.MapLayer;
import dev.diorama.api.MapView;
import dev.diorama.api.Marker;
import dev.diorama.api.ViewPlace;
import dev.diorama.bake.TileKey;
import dev.diorama.render.AddonOverlay;
import dev.diorama.render.MapViewport;
import dev.diorama.render.gpu.FogLayer;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.testaddon.TestAddon;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
		memory(context, px, py, pz);
	}

	/**
	 * What a view drawn over the whole screen holds, at the VM's window size, 1080p and 4K, with
	 * Fast and Fancy fog: its viewport's arrays, its label grid, its fog mask's two textures and its
	 * fog layer's texture (Fancy only), each texture's bytes as width x height x 4. A new view each
	 * row, since a view keeps what it made for the largest screen and the last quality it drew.
	 */
	private static void memory(ClientGameTestContext context, double px, double py, double pz) {
		int[] start = context.computeOnClient(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
		Settings.Quality was = context.computeOnClient(mc -> Settings.get().quality);
		List<String> table = new ArrayList<>();
		table.add(String.format(Locale.ROOT, "%-26s %-6s %10s %10s %10s %10s %10s", "window (GUI scale)", "fog", "arrays", "labels", "mask tex", "fog layer", "total"));
		MapLayer labels = context.computeOnClient(mc -> TestAddon.api().openLayer("view-memory"));
		List<MapView> made = new ArrayList<>();
		try {
			context.runOnClient(mc -> labels.replace(LayerContent.builder()
				.add(Marker.builder(DimensionId.OVERWORLD, px + 4, py, pz + 4).label("Here").build())
				.build()));
			for (int[] size : new int[][] {start, {1920, 1080}, {3840, 2160}}) {
				context.getInput().resizeWindow(size[0], size[1]);
				context.waitTicks(5);
				for (Settings.Quality quality : new Settings.Quality[] {Settings.Quality.FAST, Settings.Quality.FANCY}) {
					context.runOnClient(mc -> {
						Settings.get().quality = quality;
						Settings.get().apply();
					});
					int[] gui = context.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), (int) mc.getWindow().getGuiScale()});
					ViewPlace whole = ViewPlace.builder(0, 0, gui[0], gui[1]).center(px, py, pz).zoom(4).build();
					MapView view = context.computeOnClient(mc -> TestAddon.api().openView(EnumSet.allOf(MapView.Part.class)));
					made.add(view);
					measureScreen(context, null, null, List.of(new ViewScreen.Draw(view, whole)));
					long[] held = context.computeOnClient(mc -> held((AddonView) view));
					context.setScreen(() -> null);
					context.runOnClient(mc -> view.close());
					table.add(String.format(Locale.ROOT, "%-26s %-6s %10d %10d %10d %10d %10d", size[0] + "x" + size[1] + " (" + gui[2] + ")", quality.name().toLowerCase(Locale.ROOT),
						held[0], held[1], held[2], held[3], held[0] + held[1] + held[2] + held[3]));
				}
			}
		} finally {
			context.setScreen(() -> null);
			context.getInput().resizeWindow(start[0], start[1]);
			context.runOnClient(mc -> {
				for (MapView view : made) {
					view.close();
				}
				labels.close();
				Settings.get().quality = was;
				Settings.get().apply();
			});
			context.waitTicks(5);
		}
		boolean after = context.computeOnClient(mc -> made.stream().anyMatch(view -> ((AddonView) view).holdsMemory()) || AddonView.retiringCount() != 0);
		table.add("after close and a frame: " + (after ? "still held" : "nothing held"));
		Diorama.LOGGER.info("addon-api view memory: bytes a view drawn over the whole screen holds, every part on and one labelled marker:\n{}", String.join("\n", table));
	}

	/** Bytes of its viewport's arrays, of its label grid, of its fog mask's textures and of its fog layer's texture. Client thread. */
	private static long[] held(AddonView view) {
		MapViewport viewport = view.viewportForTests();
		AddonOverlay overlay = view.overlayForTests();
		long arrays = 0;
		long mask = 0;
		long layer = 0;
		try {
			if (viewport != null) {
				for (Field field : MapViewport.class.getDeclaredFields()) {
					if (Modifier.isStatic(field.getModifiers())) {
						continue;
					}
					field.setAccessible(true);
					Object value = field.get(viewport);
					if (value != null && value.getClass().isArray() && value.getClass().getComponentType().isPrimitive()) {
						arrays += (long) Array.getLength(value) * bytes(value.getClass().getComponentType());
					}
					if (value instanceof MapTexture && (field.getName().equals("maskTexture") || field.getName().equals("detailTexture"))) {
						mask += 256L * 256 * 4;
					}
					if (value instanceof FogLayer fog && fog.texture() != null) {
						layer += (long) fog.size() * fog.size() * 4;
					}
				}
			}
			long labels = 0;
			if (overlay != null) {
				Field taken = AddonOverlay.class.getDeclaredField("taken");
				taken.setAccessible(true);
				labels = (long) ((int[]) taken.get(overlay)).length * 4;
			}
			return new long[] {arrays, labels, mask, layer};
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static int bytes(Class<?> type) {
		return type == long.class || type == double.class ? 8 : type == int.class || type == float.class ? 4 : type == short.class || type == char.class ? 2 : 1;
	}

	/** Shows a screen drawing these, waits, and adds its row to the table, if there is one. */
	private static void measureScreen(ClientGameTestContext context, List<String> table, String what, List<ViewScreen.Draw> draws) {
		ViewScreen screen = context.computeOnClient(mc -> new ViewScreen(draws));
		context.setScreen(() -> screen);
		context.waitFor(mc -> screen.frames >= 5, 600);
		context.waitTicks(20);
		if (table != null) {
			table.add(row(context, what));
		}
	}
}
//?}
