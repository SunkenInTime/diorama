package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.data.TileStore;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapTile;
import dev.diorama.render.MapViewport;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The full map zoomed out as far as it goes over a big map (opt-in: {@code far-zoom}), for issue
 * 245. The big map is the land around spawn, explored for real, with its saved tiles repeated over
 * a stretch about 11,000 blocks across and on islands up to 300,000 blocks out: thousands of tiles
 * without the hours a real one takes to explore.
 *
 * <p>Checks that the overview levels reach {@link TileKey#MAX_LOD}, that a map saved without the
 * levels above 5 gets them when it's opened, that the map stops at a 512-block region a screen
 * pixel, and that at every step out, and on a wide view where the detailed tiles start, the view
 * asks for no more tiles than stay loaded and ends up with all of them. Pictures of each step, first frame and settled, are for looking at.
 */
final class FarZoomTest {
	private static final int SPREAD = 320;
	/** The explored land, repeated this many times each way, side by side. */
	private static final int GRID = 10;
	private static final int ISLANDS = 40;
	/** As many tiles as a view can count on staying loaded: MapLevel.KEPT_TILES. */
	private static final int KEPT_TILES = 192;

	private FarZoomTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		int[] home = context.computeOnClient(mc -> new int[] {mc.player.blockPosition().getX(), mc.player.blockPosition().getZ()});
		int[][] stops = {{SPREAD, SPREAD}, {-SPREAD, SPREAD}, {-SPREAD, -SPREAD}, {SPREAD, -SPREAD}, {0, 0}};
		for (int[] stop : stops) {
			world.getServer().runCommand("tp @a " + (home[0] + stop[0]) + " 200 " + (home[1] + stop[1]));
			context.waitTicks(200);
		}
		DioramaClientTest.settle(context, 100);
		TileStore store = context.computeOnClient(mc -> MapSession.get().map().savedMap());
		context.waitFor(mc -> store.unwrittenTiles() == 0, 2400);
		catchUp(store);

		// The explored tiles, and the box they fill.
		List<TileKey> explored = new ArrayList<>(store.saved(0));
		int minU = explored.stream().mapToInt(TileKey::tileU).min().orElseThrow();
		int minV = explored.stream().mapToInt(TileKey::tileV).min().orElseThrow();
		int width = explored.stream().mapToInt(TileKey::tileU).max().orElseThrow() - minU + 1;
		int height = explored.stream().mapToInt(TileKey::tileV).max().orElseThrow() - minV + 1;
		Path folder = store.folder();
		List<int[]> islands = new ArrayList<>();
		Random random = new Random(245);
		try {
			for (int gu = 0; gu < GRID; gu++) {
				for (int gv = 0; gv < GRID; gv++) {
					if (gu != 0 || gv != 0) {
						repeat(folder, explored, gu * width, gv * height, Integer.MAX_VALUE);
					}
				}
			}
			for (int i = 0; i < ISLANDS; i++) {
				// Up to 2,400 tiles east or west and 1,300 north or south: about 300,000 and 170,000 blocks.
				int du = (random.nextInt(4800) - 2400) / width * width;
				int dv = (random.nextInt(2600) - 1300) / height * height;
				if (du >= 0 && du < GRID * width && dv >= 0 && dv < GRID * height) {
					continue;
				}
				islands.add(new int[] {du, dv});
				repeat(folder, explored, du, dv, 9);
			}
		} catch (IOException e) {
			throw new AssertionError("Couldn't make the big map", e);
		}
		long start = System.nanoTime();
		catchUp(store);
		int tiles = store.saved(0).size();
		StringBuilder levels = new StringBuilder();
		int overviews = 0;
		for (int lod = 1; lod <= TileKey.MAX_LOD; lod++) {
			int count = store.saved(lod).size();
			overviews += count;
			levels.append(' ').append(count);
		}
		Diorama.LOGGER.info("far-zoom: {} tiles ({} explored, {}x{}), {} overview tiles built in {} s, by level:{}",
			tiles, explored.size(), width, height, overviews, (System.nanoTime() - start) / 1_000_000_000.0, levels);
		if (tiles < 3000) {
			throw new AssertionError("Expected a map of thousands of tiles, got " + tiles);
		}
		if (store.saved(TileKey.MAX_LOD).isEmpty()) {
			throw new AssertionError("No overview tile at level " + TileKey.MAX_LOD);
		}

		// A map from a version that stopped at level 5: opening it builds the rest.
		int[] before = new int[TileKey.MAX_LOD + 1];
		for (int lod = 6; lod <= TileKey.MAX_LOD; lod++) {
			before[lod] = store.saved(lod).size();
		}
		// The oldest kind: its overview tiles don't say which chunks they show either.
		try {
			store.forgetHighOverviews(false);
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
		start = System.nanoTime();
		TileStore reopened = new TileStore(folder);
		try {
			context.waitFor(mc -> {
				for (int lod = 6; lod <= TileKey.MAX_LOD; lod++) {
					if (reopened.saved(lod).size() != before[lod]) {
						return false;
					}
				}
				return true;
			// Minutes, not the second or two it takes alone: the map that's open shares the writer
			// thread, and has its own chunks to repair now.
			}, 6000);
		} catch (RuntimeException | AssertionError e) {
			StringBuilder got = new StringBuilder();
			for (int lod = 6; lod <= TileKey.MAX_LOD; lod++) {
				got.append(' ').append(reopened.saved(lod).size()).append('/').append(before[lod]);
			}
			throw new AssertionError("Opening an old map didn't build its levels 6 to " + TileKey.MAX_LOD + " in five minutes; tiles by level, got/expected:" + got, e);
		}
		Diorama.LOGGER.info("far-zoom: levels 6 to {} of an old map built in {} s after opening it; on disk, levels 1 to 5 take {} KB and levels 6 to {} take {} KB",
			TileKey.MAX_LOD, (System.nanoTime() - start) / 1_000_000_000.0, kilobytes(folder, 1, 5), TileKey.MAX_LOD, kilobytes(folder, 6, TileKey.MAX_LOD));
		// Land claims drawn on the coarsest tile ask for its chunks, which that map never kept: every
		// overview tile is then made again from the detailed ones, on closing at the latest.
		TileKey top = reopened.saved(TileKey.MAX_LOD).iterator().next();
		reopened.chunks(top);
		start = System.nanoTime();
		reopened.close();
		TileStore repaired = new TileStore(folder);
		boolean chunks = repaired.chunks(top) != null;
		repaired.close();
		Diorama.LOGGER.info("far-zoom: the old map's chunks repaired in {} s", (System.nanoTime() - start) / 1_000_000_000.0);
		if (!chunks) {
			throw new AssertionError("The old map's coarsest tile still has no chunks after the repair");
		}

		// Things to read on it: waypoints all over the big stretch and one on each island, and a line round the stretch.
		double units = Projection.TILE_UNITS;
		double u0 = minU * units;
		double v0 = minV * units;
		double u1 = u0 + GRID * width * units;
		double v1 = v0 + GRID * height * units;
		context.runOnClient(mc -> {
			WaypointStore waypoints = WaypointStore.get();
			String overworld = "minecraft:overworld";
			Random r = new Random(7);
			for (int i = 0; i < 30; i++) {
				double u = u0 + r.nextDouble() * (u1 - u0);
				double v = v0 + r.nextDouble() * (v1 - v0);
				waypoints.add(new Waypoint("Base " + (i + 1), (int) u, 64, (int) Projection.zAt(v, 64), overworld, Waypoint.PALETTE[r.nextInt(Waypoint.PALETTE.length)]));
			}
			for (int i = 0; i < islands.size(); i++) {
				double u = u0 + (islands.get(i)[0] + 1.5) * units;
				double v = v0 + (islands.get(i)[1] + 1.5) * units;
				waypoints.add(new Waypoint("Island " + (i + 1), (int) u, 64, (int) Projection.zAt(v, 64), overworld, Waypoint.PALETTE[r.nextInt(Waypoint.PALETTE.length)]));
			}
			Drawing line = new Drawing();
			line.kind = Drawing.Kind.STROKE;
			line.dimension = overworld;
			line.color = 0xFFE03C3C;
			line.turns = Projection.facing().turns;
			line.points = new double[] {u0, v0, u1, v0, u1, v1, u0, v1, u0, v0};
			DrawingStore.get().add(line);
		});

		double cu = (u0 + u1) / 2;
		double cv = (v0 + v1) / 2;
		double guiScale = context.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
		double furthest = 1.0 / 512 / guiScale;
		context.setScreen(MapScreen::new);
		// Out from where the map stopped before (0.04), a level at a time, to as far as it goes; then
		// in on an island from all the way out, and in close on the big stretch.
		int[] island = islands.get(0);
		double[][] steps = {
			{cu, cv, 0.5},
			{cu, cv, 0.04},
			{cu, cv, 0.02},
			{cu, cv, 0.01},
			{cu, cv, 0.005},
			{cu, cv, 0.0025},
			{cu, cv, 0.00125},
			{cu, cv, 0.00001},
			{u0 + (island[0] + 1.5) * units, v0 + (island[1] + 1.5) * units, 0.04},
			{cu, cv, 0.00001},
			{u0 + width * units, v0 + height * units, 0.3},
		};
		for (int i = 0; i < steps.length; i++) {
			double[] step = steps[i];
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(step[0], step[1], step[2], TileKey.SURFACE));
			context.waitTick();
			double zoom = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).zoom());
			if (step[2] < furthest && Math.abs(zoom / furthest - 1) > 1e-9) {
				throw new AssertionError("Expected the map to stop at " + furthest + " GUI pixels a block, a 512-block region a screen pixel; it's at " + zoom);
			}
			step[2] = zoom;
			String name = String.format("far-zoom-%02d-%.5f", i, zoom);
			context.takeScreenshot(name + "-a-first");
			check(context, store, step, name + " first frame", false);
			DioramaClientTest.settle(context, 20);
			// A few seconds at rest, for the frame rate the game reports to be this view's.
			context.waitTicks(60);
			context.takeScreenshot(name + "-b-settled");
			check(context, store, step, name + " settled", true);
		}
		// A wide view where the detailed tiles start: a big screen at GUI scale 1 shows more of them
		// than stay loaded, so until they fit the map draws the first overview level and comes to rest.
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		DioramaClientTest.setGuiScale(context, 1);
		for (double zoom : new double[] {0.5, 0.6}) {
			double[] step = {u0 + width * units, v0 + height * units, zoom};
			// Only a window this wide shows more detailed tiles than stay loaded: boat.dev's is 1600 by 900.
			int detailed = context.computeOnClient(mc -> (int) (Math.ceil(mc.getWindow().getGuiScaledWidth() / zoom / units) * Math.ceil(mc.getWindow().getGuiScaledHeight() / zoom / units)));
			if (detailed <= KEPT_TILES) {
				throw new AssertionError("The window is too small for the wide view: at zoom " + zoom + " it shows " + detailed + " detailed tiles, no more than the " + KEPT_TILES + " that stay loaded");
			}
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(step[0], step[1], step[2], TileKey.SURFACE));
			context.waitTick();
			String name = String.format("far-zoom-wide-%.2f", zoom);
			check(context, store, step, name + " first frame", false);
			DioramaClientTest.settle(context, 20);
			context.takeScreenshot(name + "-settled");
			check(context, store, step, name + " settled", true);
		}
		DioramaClientTest.setGuiScale(context, scale);
		context.setScreen(() -> null);
	}

	/** Links every explored tile, or the first {@code most}, to the same place {@code du, dv} tiles on: the same ground, mapped again there. */
	private static void repeat(Path folder, List<TileKey> explored, int du, int dv, int most) throws IOException {
		int made = 0;
		for (TileKey key : explored) {
			if (made++ >= most) {
				return;
			}
			Path from = folder.resolve(key.tileU() + "_" + key.tileV() + ".omt");
			Path to = folder.resolve((key.tileU() + du) + "_" + (key.tileV() + dv) + ".omt");
			if (!Files.isRegularFile(from) || Files.exists(to)) {
				continue;
			}
			try {
				Files.createLink(to, from);
			} catch (IOException | UnsupportedOperationException e) {
				Files.copy(from, to);
			}
		}
	}

	/** How much disk the overview tiles of levels {@code from} to {@code to} take. */
	private static long kilobytes(Path folder, int from, int to) {
		long bytes = 0;
		for (int lod = from; lod <= to; lod++) {
			Path dir = folder.resolve("lod" + lod);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (java.util.stream.Stream<Path> files = Files.list(dir)) {
				for (Path file : files.toList()) {
					// The map is open and still writing: a tile's .tmp file is renamed away as it's finished.
					if (!file.getFileName().toString().endsWith(".tmp")) {
						bytes += Files.size(file);
					}
				}
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		}
		return bytes / 1024;
	}

	private static void catchUp(TileStore store) {
		try {
			store.catchUp();
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Logs how many tiles the view takes at its level, how many of the saved ones among them aren't
	 * loaded, and the frame rate. Fails if the view takes more tiles than stay loaded, or, once
	 * {@code settled}, if a saved one still isn't there.
	 */
	private static void check(ClientGameTestContext context, TileStore store, double[] step, String what, boolean settled) {
		int[] lodOf = new int[1];
		context.runOnClient(mc -> {
			MapLevel level = MapSession.get().map();
			lodOf[0] = MapViewport.lodFor(level, new MapViewport.View(0, 0, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), step[0], step[1], step[2], TileKey.SURFACE));
		});
		Set<TileKey> saved = store.saved(lodOf[0]);
		String problem = context.computeOnClient(mc -> {
			MapLevel level = MapSession.get().map();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			MapViewport.View view = new MapViewport.View(0, 0, w, h, step[0], step[1], step[2], TileKey.SURFACE);
			int lod = lodOf[0];
			double units = (double) Projection.TILE_UNITS * (1 << lod);
			int shown = 0;
			int mapped = 0;
			int missing = 0;
			for (int tu = (int) Math.floor(view.minU() / units); tu <= (int) Math.floor((view.minU() + w / view.zoom()) / units); tu++) {
				for (int tv = (int) Math.floor(view.minV() / units); tv <= (int) Math.floor((view.minV() + h / view.zoom()) / units); tv++) {
					shown++;
					TileKey key = new TileKey(TileKey.SURFACE, tu, tv, lod);
					if (!saved.contains(key)) {
						continue;
					}
					mapped++;
					MapTile tile = level.peek(key);
					if (tile == null || tile.texture() == null) {
						missing++;
					}
				}
			}
			Diorama.LOGGER.info("far-zoom {}: zoom {} ({} blocks across), level {}, {} tiles in view, {} of them mapped, {} not loaded, {} fps",
				what, view.zoom(), Math.round(w / view.zoom()), lod, shown, mapped, missing, mc.getFps());
			if (shown > KEPT_TILES) {
				return what + ": the view takes " + shown + " tiles, more than the " + KEPT_TILES + " that stay loaded";
			}
			if (settled && missing > 0) {
				return what + ": " + missing + " of " + mapped + " mapped tiles in view never loaded";
			}
			if (settled && step[2] < 0.04 && mapped == 0) {
				return what + ": nothing mapped in view";
			}
			return null;
		});
		if (problem != null) {
			throw new AssertionError(problem);
		}
	}
}
