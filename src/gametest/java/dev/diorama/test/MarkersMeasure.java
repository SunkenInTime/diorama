package dev.diorama.test;

//? if >=26.1 && fabric {
import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.api.DimensionId;
import dev.diorama.api.LayerContent;
import dev.diorama.api.MapLayer;
import dev.diorama.api.MapLine;
import dev.diorama.api.Marker;
import dev.diorama.api.ResourceId;
import dev.diorama.render.AddonOverlay;
import dev.diorama.testaddon.TestAddon;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.core.BlockPos;

/**
 * What addons' layers cost a frame, in bytes the render thread allocates ({@code addon-api}, 26.x
 * Fabric, in a world of its own, since it kills every mob first so that runs draw the same
 * frames). Only the API, the probes and reflection, so the same file runs on an older head of the
 * branch for a before-and-after table:
 * <ul>
 *   <li>no layer; one layer of 100 and of 1,000 dots replaced every client tick, 1,000 still, 1,000
 *       icons with labels, the layer open and empty: the minimap and the full map;</li>
 *   <li>the worst lines the limits allow, on the round minimap: 16 layers, each with all the line
 *       points a layer can have, every piece crossing the circle between points in the corners of
 *       the square around it, so every piece is cut by the circle; and the same on the full map;</li>
 *   <li>bytes of arrays the minimap's overlay holds with those 16 layers open, and after they are
 *       all closed;</li>
 *   <li>what one replace of 1,000 markers costs.</li>
 * </ul>
 */
final class MarkersMeasure {
	private static final String MOD = "diorama_test_addon";
	/** What a feed of moving markers does each client tick, or null. */
	private static Runnable feed;
	private static boolean feeding;
	/** The minimap's HUD element, as Fabric last drew it: Fabric applies a replacement as it draws. */
	private static Object minimapHud;

	private MarkersMeasure() {
	}

	/** Measures where the player stands, facing north, in a world it may kill every mob in. */
	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 0");
		context.waitTicks(5);
		long[] none = MapAllocProbe.none(context, world);
		context.runOnClient(mc -> {
			if (!feeding) {
				feeding = true;
				HudElementRegistry.replaceElement(Diorama.id("minimap"), element -> {
					minimapHud = element;
					return element;
				});
				net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
					if (feed != null) {
						feed.run();
					}
				});
			}
		});
		double px = at.getX() + 0.5;
		double py = at.getY();
		double pz = at.getZ() + 0.5;
		Settings.Shape shape = context.computeOnClient(mc -> Settings.get().minimapShape);
		List<String> table = new ArrayList<>();
		table.add(String.format(Locale.ROOT, "%-48s %12s %12s", "layers", "minimap", "full map"));
		table.add(String.format(Locale.ROOT, "%-48s %12d %12d", "none, no mobs", none[0], none[1]));
		List<MapLayer> layers = new ArrayList<>();
		try (WorldPause paused = WorldPause.pause(context)) {
			context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.SQUARE);
			MapLayer layer = context.computeOnClient(mc -> TestAddon.api().openLayer("alloc"));
			layers.add(layer);
			for (int count : new int[] {100, 1000}) {
				long[] step = {0};
				context.runOnClient(mc -> feed = () -> layer.replace(rings(px, py, pz, count, step[0]++, false)));
				table.add(row(context, count + " dots replaced every tick", 100, 200));
				context.runOnClient(mc -> feed = null);
			}
			context.runOnClient(mc -> layer.replace(rings(px, py, pz, 1000, 0, false)));
			table.add(row(context, "1000 dots, still", 100, 200));
			context.runOnClient(mc -> layer.replace(rings(px, py, pz, 1000, 0, true)));
			table.add(row(context, "1000 icons with labels, still", 100, 200));
			context.runOnClient(mc -> layer.replace(LayerContent.EMPTY));
			table.add(row(context, "layer open, empty", 100, 200));
			context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.ROUND);
			table.add(row(context, "layer open, empty, round minimap", 100, 200));

			// The worst lines the limits allow, on the round minimap: first, how big its circle is in blocks.
			context.runOnClient(mc -> {
				Settings.get().minimapShape = Settings.Shape.ROUND;
				layer.replace(LayerContent.builder().add(Marker.builder(DimensionId.OVERWORLD, px, py, pz).build()).build());
			});
			context.setScreen(() -> null);
			context.waitTicks(10);
			double[] circle = context.computeOnClient(mc -> circleOnMinimap(py));
			context.runOnClient(mc -> layer.close());
			layers.clear();
			context.runOnClient(mc -> {
				LayerContent worst = worstLines(px, py, pz, circle);
				for (int i = 0; i < MapLayer.MAX_OPEN; i++) {
					MapLayer each = TestAddon.api().openLayer("worst-" + i);
					layers.add(each);
					each.replace(worst);
				}
			});
			String worstName = MapLayer.MAX_OPEN + " layers of " + LayerContent.MAX_LINE_POINTS + " line points";
			table.add(row(context, worstName + ", round minimap", 10, 30));
			long held = context.computeOnClient(mc -> retained(minimapOverlay()));
			context.runOnClient(mc -> {
				for (MapLayer each : layers) {
					each.close();
				}
			});
			layers.clear();
			context.setScreen(() -> null);
			context.waitTicks(40);
			long left = context.computeOnClient(mc -> retained(minimapOverlay()));
			table.add(String.format(Locale.ROOT, "arrays the minimap's overlay holds: %d bytes with those layers open, %d bytes after they're all closed", held, left));
		} finally {
			context.runOnClient(mc -> {
				feed = null;
				for (MapLayer each : layers) {
					each.close();
				}
				Settings.get().minimapShape = shape;
			});
		}
		// One replace of 1,000 markers, 200 times: the addon building them, and Diorama taking them.
		long[] replace = context.computeOnClient(mc -> {
			com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
			MapLayer timed = TestAddon.api().openLayer("replace-cost");
			try {
				long buildBytes = 0;
				long buildNanos = 0;
				long takeBytes = 0;
				long takeNanos = 0;
				for (int i = 0; i < 250; i++) {
					long a = threads.getCurrentThreadAllocatedBytes();
					long t0 = System.nanoTime();
					LayerContent content = rings(px, py, pz, 1000, i, false);
					long t1 = System.nanoTime();
					long b = threads.getCurrentThreadAllocatedBytes();
					long t1b = System.nanoTime();
					timed.replace(content);
					long t2 = System.nanoTime();
					long c = threads.getCurrentThreadAllocatedBytes();
					// The first 50 warm the JIT up.
					if (i >= 50) {
						buildBytes += b - a;
						buildNanos += t1 - t0;
						takeBytes += c - b;
						takeNanos += t2 - t1b;
					}
				}
				return new long[] {buildBytes / 200, buildNanos / 200, takeBytes / 200, takeNanos / 200};
			} finally {
				timed.close();
			}
		});
		table.add(String.format(Locale.ROOT, "one replace of 1000 dots: the addon builds them in %d bytes, %.1f µs; replace() takes them in %d bytes, %.2f µs",
			replace[0], replace[1] / 1000.0, replace[2], replace[3] / 1000.0));
		Diorama.LOGGER.info("addon-api alloc: bytes a frame (medians, world paused):\n{}", String.join("\n", table));
	}

	/** Markers in rings round a spot: {@code count} dots, or icons with labels, turned by {@code step}. */
	private static LayerContent rings(double px, double py, double pz, int count, long step, boolean icons) {
		LayerContent.Builder content = LayerContent.builder();
		for (int i = 0; i < count; i++) {
			int ring = i % 10;
			double radius = 4 + ring * 4;
			double angle = i * 2 * Math.PI * 10 / count + step * 0.02;
			Marker.Builder marker = Marker.builder(DimensionId.OVERWORLD, px + Math.cos(angle) * radius, py, pz + Math.sin(angle) * radius);
			if (icons) {
				marker.icon(i % 2 == 0 ? ResourceId.of("diorama:home") : ResourceId.of(MOD + ":raid")).label("Place " + i);
			} else {
				marker.rgb(0xFF00FF).size(4);
			}
			content.add(marker.build());
		}
		return content.build();
	}

	/**
	 * The round minimap's circle, from its AddonOverlay as it last drew: its radius in GUI pixels,
	 * and GUI pixels a block east and a block south moves a place there. Client thread.
	 */
	private static double[] circleOnMinimap(double y) {
		Object overlay = minimapOverlay();
		try {
			Field zoomField = AddonOverlay.class.getDeclaredField("zoom");
			zoomField.setAccessible(true);
			Field circleField = AddonOverlay.class.getDeclaredField("circle");
			circleField.setAccessible(true);
			double zoom = zoomField.getDouble(overlay);
			Object circle = circleField.get(overlay);
			if (circle == null) {
				throw new AssertionError("The minimap's overlay hasn't drawn a round view");
			}
			float r = circle.getClass().getField("r").getFloat(circle);
			double east = zoom * Math.hypot(Projection.u(1, 0) - Projection.u(0, 0), Projection.v(1, y, 0) - Projection.v(0, y, 0));
			double south = zoom * Math.hypot(Projection.u(0, 1) - Projection.u(0, 0), Projection.v(0, y, 1) - Projection.v(0, y, 0));
			return new double[] {r, east, south};
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * A layer's worth of the costliest lines: as many points as a layer can have, in lines as long
	 * as a line can be, each point in a corner of the square around the round minimap (inside the
	 * square, outside the circle), going round the corners, so every piece crosses the circle and
	 * is cut by it.
	 */
	private static LayerContent worstLines(double px, double py, double pz, double[] circle) {
		double ax = circle[0] * 0.9 / circle[1];
		double az = circle[0] * 0.9 / circle[2];
		int[][] corners = {{-1, -1}, {1, 1}, {-1, 1}, {1, -1}};
		LayerContent.Builder content = LayerContent.builder();
		int perLine = MapLine.MAX_POINTS;
		int lines = LayerContent.MAX_LINE_POINTS / perLine;
		for (int l = 0; l < lines; l++) {
			MapLine.Builder line = MapLine.builder(DimensionId.OVERWORLD).rgb(0x3060FF).width(MapLine.MAX_WIDTH);
			for (int k = 0; k < perLine; k++) {
				int[] corner = corners[k % 4];
				// A little further in at each point, so no two points are the same.
				double in = 1 - 0.05 * (l * perLine + k) / (lines * perLine);
				line.point(px + corner[0] * ax * in, py, pz + corner[1] * az * in);
			}
			content.add(line.build());
		}
		return content.build();
	}

	/** The minimap's median bytes a frame and then the full map's, as one row of the table. */
	private static String row(ClientGameTestContext context, String what, int warm, int count) {
		context.setScreen(() -> null);
		context.waitTicks(10);
		AllocProbe.measure(context, "warm-up, " + what, warm);
		long minimap = AllocProbe.measure(context, what, count);
		AddonMarkersTest.openMap(context);
		context.waitTicks(10);
		MapAllocProbe.measure(context, "warm-up, " + what, warm);
		long map = MapAllocProbe.measure(context, what, count);
		context.setScreen(() -> null);
		return String.format(Locale.ROOT, "%-48s %12d %12d", what, minimap, map);
	}

	/** The minimap's AddonOverlay, read off the HUD element Diorama registered. Client thread. */
	private static Object minimapOverlay() {
		if (minimapHud == null) {
			throw new AssertionError("Fabric hasn't drawn the minimap's HUD element since its replacement was registered");
		}
		try {
			Field field = minimapHud.getClass().getDeclaredField("addons");
			field.setAccessible(true);
			return field.get(minimapHud);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Bytes of the arrays an overlay holds: its fields and AddonOverlay's static ones, and the
	 * arrays inside its own kinds of objects (its sets of quads), at most two steps in.
	 */
	private static long retained(Object overlay) {
		Map<Object, Boolean> seen = new IdentityHashMap<>();
		long bytes = 0;
		try {
			for (Field field : AddonOverlay.class.getDeclaredFields()) {
				if (field.getType().isPrimitive()) {
					continue;
				}
				field.setAccessible(true);
				bytes += size(Modifier.isStatic(field.getModifiers()) ? field.get(null) : field.get(overlay), 2, seen);
			}
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
		return bytes;
	}

	private static long size(Object value, int depth, Map<Object, Boolean> seen) throws ReflectiveOperationException {
		if (value == null || seen.put(value, Boolean.TRUE) != null) {
			return 0;
		}
		Class<?> type = value.getClass();
		if (type.isArray()) {
			Class<?> element = type.getComponentType();
			int length = Array.getLength(value);
			if (element.isPrimitive()) {
				int each = element == double.class || element == long.class ? 8 : element == float.class || element == int.class ? 4 : element == short.class || element == char.class ? 2 : 1;
				return (long) length * each;
			}
			long bytes = length * 4L;
			if (depth > 0) {
				for (int i = 0; i < length; i++) {
					bytes += size(Array.get(value, i), depth - 1, seen);
				}
			}
			return bytes;
		}
		if (type.getEnclosingClass() != AddonOverlay.class || depth == 0) {
			return 0;
		}
		long bytes = 0;
		for (Field field : type.getDeclaredFields()) {
			if (field.getType().isPrimitive() || Modifier.isStatic(field.getModifiers())) {
				continue;
			}
			field.setAccessible(true);
			bytes += size(field.get(value), depth - 1, seen);
		}
		return bytes;
	}
}
//?}
