package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapTile;
import dev.diorama.render.MapViewport;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.jspecify.annotations.Nullable;

/**
 * The fog mask's builds while the player stands, walks and flies, on the minimap, the full map and
 * a cave slice ({@code fog-mask}). Every build's known share, read from the tiles, is checked
 * against the same reading done the way it was before 0.12 ({@link #readAsBefore}), pixel for
 * pixel; and what a build allocates on the render thread is kept under {@link #MOST_BYTES}. Before
 * 0.12 a build allocated about 280 KB, all of it lookup keys: each tile was looked up again on every
 * row of the mask it lay on, and for a tile not in memory every overview level above it too.
 *
 * <p>Logs, for each part: the builds, bytes and time a build, builds a second of game time, and what
 * the minimap's HUD element allocated a frame (where {@link MinimapAllocTest} has a probe).
 */
final class FogMaskBuildTest {
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	/**
	 * The most a build may allocate: on the minimap every build, on the full map the least of a
	 * part's. A build allocates a hundred bytes or so, and a key, kept, for each tile no build before
	 * it read: on the minimap a few dozen at most, but on the full map's first build at a new zoom up
	 * to a few thousand. Before 0.12 every minimap build allocated 200 KB or more, and every full
	 * map build 50 KB to 2 MB.
	 */
	private static final long MOST_BYTES = 24 * 1024;
	/** Blocks a tick: walking (4.3 m/s), and an elytra (32 m/s). */
	private static final double WALK = 0.216;
	private static final double ELYTRA = 1.6;
	private static final int FLY_Y = 120;

	private static final class Part {
		final String name;
		int builds;
		long bytes;
		long mostBytes;
		long leastBytes = Long.MAX_VALUE;
		long nanos;
		long mostNanos;
		int ticks;
		int caveBuilds;
		int mostTiles;
		long @Nullable [] frames;

		Part(String name) {
			this.name = name;
		}
	}

	private static final Map<String, Part> PARTS = new LinkedHashMap<>();
	private static @Nullable Part part;
	private static long startBytes;
	private static long startNanos;
	private static long checkBytes;
	private static long checkNanos;
	private static int checked;
	private static int mismatches;
	private static @Nullable String firstMismatch;
	private static final int[] BEFORE = new int[256 * 256];
	/** The tiles one build reads, as many as it looks up in the map. */
	private static final java.util.Set<TileKey> READ = new java.util.HashSet<>();

	private FogMaskBuildTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		PARTS.clear();
		checked = 0;
		mismatches = 0;
		firstMismatch = null;
		boolean frames = MinimapAllocTest.register(context);
		context.runOnClient(mc -> MapViewport.watchMasksForTests(new Watch()));
		try {
			parts(context, world, frames);
		} finally {
			context.runOnClient(mc -> MapViewport.watchMasksForTests(null));
			context.setScreen(() -> null);
		}
		report();
	}

	private static void parts(ClientGameTestContext context, TestSingleplayerContext world, boolean frames) {
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("kill @e[type=!player]");
		int[] home = context.computeOnClient(mc -> new int[] {mc.player.blockPosition().getX(), mc.player.blockPosition().getZ()});
		int x = home[0];
		int z = home[1];
		context.runOnClient(mc -> {
			mc.player.getAbilities().flying = true;
			Settings.get().minimapPixelsPerBlock = 2;
		});
		world.getServer().runCommand("tp @a " + x + " " + FLY_Y + " " + z + " 90 0");
		DioramaClientTest.settle(context, 60);

		measure(context, world, "minimap, standing", frames, 200, null);
		measure(context, world, "minimap, walking, explored", frames, 300, tick -> new double[] {x - 32 + tick * WALK, FLY_Y, z});
		// Out into land nobody has seen, far east.
		int far = x + 6000;
		world.getServer().runCommand("tp @a " + far + " " + FLY_Y + " " + z + " 90 0");
		DioramaClientTest.settle(context, 100);
		measure(context, world, "minimap, walking, unexplored", frames, 300, tick -> new double[] {far + tick * WALK, FLY_Y, z});
		int flyFrom = far + 80;
		measure(context, world, "minimap, flying, unexplored", frames, 600, tick -> new double[] {flyFrom + tick * ELYTRA, FLY_Y, z});
		measure(context, world, "minimap, flying, explored", frames, 600, tick -> new double[] {flyFrom + (600 - tick) * ELYTRA, FLY_Y, z});
		// Zoomed out, the minimap shows overview tiles, with detailed ones over its middle.
		context.runOnClient(mc -> Settings.get().minimapPixelsPerBlock = Settings.ZOOMS[0]);
		measure(context, world, "minimap zoomed out, flying, explored", frames, 300, tick -> new double[] {flyFrom + tick * ELYTRA, FLY_Y, z});
		int further = far + 2000;
		measure(context, world, "minimap zoomed out, flying, unexplored", frames, 300, tick -> new double[] {further + tick * ELYTRA, FLY_Y, z});
		context.runOnClient(mc -> Settings.get().minimapPixelsPerBlock = 2);

		// The full map over the spawn area, whose tiles the flight has pushed out of memory: the first
		// frames stand in coarser or finer tiles for them, and the far zooms read overviews.
		world.getServer().runCommand("tp @a " + x + " " + FLY_Y + " " + z + " 90 0");
		context.waitTicks(20);
		double u = Projection.u(x, z);
		double v = Projection.v(x, 64, z);
		context.setScreen(MapScreen::new);
		double[] zooms = {2.0, 0.4, 0.08, 0.03, 0.01, 0.003};
		for (double zoom : zooms) {
			String name = "full map, zoom " + zoom;
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(u, v, zoom, TileKey.SURFACE));
			measure(context, world, name, false, 60, null);
			context.takeScreenshot("fog-mask-" + name.replace(", ", "-").replace(' ', '-'));
		}
		// Panning at a far zoom: a build for each step.
		measure(context, world, "full map, zoom 0.03, panning", false, 100, tick -> {
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(u + tick * 40, v, 0.03, TileKey.SURFACE));
			return null;
		});
		context.setScreen(() -> null);

		// A room dug deep down off the spawn: the minimap shows a cave slice.
		int caveX = x + 300;
		int caveY = 20;
		world.getServer().runCommand("tp @a " + caveX + " " + FLY_Y + " " + z);
		DioramaClientTest.settle(context, 40);
		world.getServer().runCommand("fill " + (caveX - 12) + " " + (caveY - 1) + " " + (z - 4) + " " + (caveX + 12) + " " + (caveY + 4) + " " + (z + 4) + " stone");
		world.getServer().runCommand("fill " + (caveX - 11) + " " + caveY + " " + (z - 2) + " " + (caveX + 11) + " " + (caveY + 2) + " " + (z + 2) + " air");
		world.getServer().runCommand("tp @a " + (caveX - 10) + " " + caveY + " " + z + " -90 0");
		context.runOnClient(mc -> mc.player.getAbilities().flying = false);
		DioramaClientTest.settle(context, 60);
		measure(context, world, "cave minimap, standing", frames, 100, null);
		measure(context, world, "cave minimap, walking", frames, 100, tick -> new double[] {caveX - 10 + tick * WALK, caveY, z});
		context.setScreen(MapScreen::new);
		context.waitTicks(5);
		double cu = Projection.u(caveX, z);
		double cv = Projection.v(caveX, caveY, z);
		int layer = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view().layer());
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cu, cv, 1.0, layer));
		measure(context, world, "cave full map", false, 40, null);
		context.takeScreenshot("fog-mask-cave-full-map");
		context.setScreen(() -> null);
		world.getServer().runCommand("tp @a " + x + " " + FLY_Y + " " + z + " 90 0");
		context.runOnClient(mc -> mc.player.getAbilities().flying = true);
		context.waitTicks(40);
	}

	private interface Path {
		/** Where the player is at this tick, {x, y, z}; null to leave it. */
		double @Nullable [] at(int tick);
	}

	/** Counts the builds of {@code ticks} ticks, moving the player along {@code path} each tick. */
	private static void measure(ClientGameTestContext context, TestSingleplayerContext world, String name, boolean frames, int ticks, @Nullable Path path) {
		Part counting = new Part(name);
		counting.ticks = ticks;
		context.runOnClient(mc -> {
			part = counting;
			if (frames) {
				MinimapAllocTest.startCounting();
			}
		});
		for (int tick = 0; tick < ticks; tick++) {
			double[] at = path == null ? null : path.at(tick);
			if (at != null) {
				world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.3f %.3f %.3f", at[0], at[1], at[2]));
			}
			context.waitTick();
		}
		context.runOnClient(mc -> {
			part = null;
			if (frames) {
				counting.frames = MinimapAllocTest.stopCounting();
			}
		});
		PARTS.put(name, counting);
		Diorama.LOGGER.info("fog-mask | {}", line(counting));
	}

	private static String line(Part p) {
		StringBuilder text = new StringBuilder(p.name).append(" | ").append(p.builds).append(" builds (").append(p.caveBuilds).append(" of a cave slice) in ").append(p.ticks).append(" ticks (")
			.append(String.format(Locale.ROOT, "%.1f", p.builds * 20.0 / p.ticks)).append(" a second)");
		if (p.builds > 0) {
			text.append(" | bytes a build mean ").append(p.bytes / p.builds).append(" min ").append(p.leastBytes).append(" max ").append(p.mostBytes)
				.append(" | us a build mean ").append(p.nanos / p.builds / 1000).append(" max ").append(p.mostNanos / 1000)
				.append(" | most tiles a build reads ").append(p.mostTiles);
		}
		if (p.frames != null && p.frames.length > 0) {
			long[] sorted = p.frames.clone();
			Arrays.sort(sorted);
			long sum = 0;
			for (long bytes : sorted) {
				sum += bytes;
			}
			text.append(" | minimap frames ").append(sorted.length).append(" mean ").append(sum / sorted.length).append(" median ").append(sorted[sorted.length / 2])
				.append(" p99 ").append(sorted[sorted.length * 99 / 100]).append(" max ").append(sorted[sorted.length - 1]);
		}
		return text.toString();
	}

	private static void report() {
		Diorama.LOGGER.info("fog-mask | {} builds checked against the reading before 0.12, {} differed", checked, mismatches);
		if (mismatches > 0) {
			throw new AssertionError(mismatches + " of " + checked + " fog mask builds read the tiles differently from before; the first: " + firstMismatch);
		}
		String[] mustBuild = {"minimap, walking, unexplored", "minimap, flying, unexplored", "minimap zoomed out, flying, unexplored", "full map, zoom 0.03, panning", "cave minimap, walking", "cave full map"};
		for (String name : mustBuild) {
			Part p = PARTS.get(name);
			if (p == null || p.builds == 0) {
				throw new AssertionError("The fog mask was never built in \"" + name + "\", so nothing was checked there");
			}
			if (name.startsWith("cave") && p.caveBuilds == 0) {
				throw new AssertionError("\"" + name + "\" never built a cave slice's mask: the player wasn't underground");
			}
		}
		for (Part p : PARTS.values()) {
			boolean minimap = !p.name.contains("full map");
			long bytes = minimap ? p.mostBytes : p.builds == 0 ? 0 : p.leastBytes;
			if (bytes > MOST_BYTES) {
				throw new AssertionError((minimap ? "A" : "Every") + " fog mask build in \"" + p.name + "\" allocated " + (minimap ? "" : "at least ") + bytes
					+ " bytes on the render thread, over " + MOST_BYTES + " (mean " + p.bytes / Math.max(1, p.builds) + " over " + p.builds
					+ " builds): is a tile looked up with a new key for every texel row again?");
			}
		}
	}

	private static final class Watch implements MapViewport.MaskWatch {
		@Override
		public void started() {
			checkBytes = 0;
			checkNanos = 0;
			startNanos = System.nanoTime();
			startBytes = THREADS.getCurrentThreadAllocatedBytes();
		}

		@Override
		public void sampled(MapLevel level, int layer, int lod, double originU, double originV, double texel, boolean coarse, boolean drawsNear, int nearU, int nearV, int[] known) {
			long bytes = THREADS.getCurrentThreadAllocatedBytes();
			long nanos = System.nanoTime();
			READ.clear();
			readAsBefore(level, layer, lod, originU, originV, texel, coarse, drawsNear, nearU, nearV, BEFORE);
			checked++;
			if (part != null) {
				part.mostTiles = Math.max(part.mostTiles, READ.size());
				if (layer != TileKey.SURFACE) {
					part.caveBuilds++;
				}
			}
			if (!Arrays.equals(BEFORE, known)) {
				mismatches++;
				if (firstMismatch == null) {
					int at = Arrays.mismatch(BEFORE, known);
					firstMismatch = String.format(Locale.ROOT, "in \"%s\", texel %d,%d: %08x, before %08x (layer %d, lod %d, origin %.1f,%.1f, texel %.1f, coarse %b, near %b %d,%d)",
						part == null ? "between parts" : part.name, at % 256, at / 256, known[at], BEFORE[at], layer, lod, originU, originV, texel, coarse, drawsNear, nearU, nearV);
				}
			}
			checkNanos += System.nanoTime() - nanos;
			long spent = THREADS.getCurrentThreadAllocatedBytes() - bytes;
			checkBytes += spent;
			MinimapAllocTest.excluded += spent;
		}

		@Override
		public void finished() {
			long bytes = THREADS.getCurrentThreadAllocatedBytes() - startBytes - checkBytes;
			long nanos = System.nanoTime() - startNanos - checkNanos;
			Part p = part;
			if (p != null) {
				p.builds++;
				p.bytes += bytes;
				p.mostBytes = Math.max(p.mostBytes, bytes);
				p.leastBytes = Math.min(p.leastBytes, bytes);
				p.nanos += nanos;
				p.mostNanos = Math.max(p.mostNanos, nanos);
			}
		}
	}

	private static final int MASK = 256;
	private static final int FINER_STAND_INS = 2;
	private static final int NEAR_TILES = 2;
	private static final double[] COARSE_SPOTS = {0.25, 0.25, 0.75, 0.25, 0.25, 0.75, 0.75, 0.75};
	private static final double[] MIDDLE = {0.5, 0.5};

	private static @Nullable MapTile peek(MapLevel level, TileKey key) {
		READ.add(key);
		return level.peek(key);
	}

	/** The mask's known share read from the tiles as {@code MapViewport.buildMask} read it up to 0.11, unchanged. */
	private static void readAsBefore(MapLevel level, int layer, int lod, double originU, double originV, double texel, boolean coarse, boolean drawsNear, int nearU, int nearV, int[] out) {
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double cellUnits = units / Projection.COVERAGE_CELLS;
		int lastTu = Integer.MIN_VALUE;
		int lastTv = Integer.MIN_VALUE;
		MapTile tile = null;
		MapTile above = null;
		TileKey aboveKey = null;
		MapTile[] finer = new MapTile[FINER_STAND_INS];
		int[] lastFinerTu = new int[FINER_STAND_INS];
		int[] lastFinerTv = new int[FINER_STAND_INS];
		Arrays.fill(lastFinerTu, Integer.MIN_VALUE);
		Arrays.fill(lastFinerTv, Integer.MIN_VALUE);
		int lastNearTu = Integer.MIN_VALUE;
		int lastNearTv = Integer.MIN_VALUE;
		MapTile nearTile = null;
		double[] spots = coarse ? COARSE_SPOTS : MIDDLE;
		for (int j = 0; j < MASK; j++) {
			for (int i = 0; i < MASK; i++) {
				float most = 0;
				for (int spot = 0; spot < spots.length && most < 1; spot += 2) {
					double u = originU + (i + spots[spot]) * texel;
					double v = originV + (j + spots[spot + 1]) * texel;
					int tv = (int) Math.floor(v / units);
					int cellY = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - tv * units) / cellUnits));
					int tu = (int) Math.floor(u / units);
					if (tu != lastTu || tv != lastTv) {
						tile = peek(level, new TileKey(layer, tu, tv, lod));
						lastTu = tu;
						lastTv = tv;
						above = null;
						aboveKey = null;
						if (tile == null || !tile.hasCoverage()) {
							TileKey up = new TileKey(layer, tu, tv, lod);
							for (int l = lod + 1; l <= TileKey.MAX_LOD && above == null; l++) {
								up = up.parent();
								MapTile candidate = peek(level, up);
								if (candidate != null && candidate.hasCoverage()) {
									above = candidate;
									aboveKey = up;
								}
							}
						}
					}
					float known = 0;
					if (tile != null && tile.hasCoverage()) {
						int cellX = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - tu * units) / cellUnits));
						known = tile.coverage(cellX, cellY);
					} else if (above != null) {
						double aboveSpan = aboveKey.units();
						double aboveCell = aboveSpan / Projection.COVERAGE_CELLS;
						int cellX = Math.max(0, Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - aboveKey.tileU() * aboveSpan) / aboveCell)));
						int aboveY = Math.max(0, Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - aboveKey.tileV() * aboveSpan) / aboveCell)));
						known = above.coverage(cellX, aboveY);
					} else {
						for (int down = 1; down <= FINER_STAND_INS && lod - down >= 0; down++) {
							double span = units / (1 << down);
							int finerTu = (int) Math.floor(u / span);
							int finerTv = (int) Math.floor(v / span);
							if (finerTu != lastFinerTu[down - 1] || finerTv != lastFinerTv[down - 1]) {
								finer[down - 1] = peek(level, new TileKey(layer, finerTu, finerTv, lod - down));
								lastFinerTu[down - 1] = finerTu;
								lastFinerTv[down - 1] = finerTv;
							}
							MapTile under = finer[down - 1];
							if (under != null) {
								if (under.hasCoverage()) {
									double finerCell = span / Projection.COVERAGE_CELLS;
									int cellX = Math.max(0, Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - finerTu * span) / finerCell)));
									int finerY = Math.max(0, Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - finerTv * span) / finerCell)));
									known = under.coverage(cellX, finerY);
								}
								break;
							}
						}
					}
					if (drawsNear && known < 1) {
						int nearTu = (int) Math.floor(u / Projection.TILE_UNITS);
						int nearTv = (int) Math.floor(v / Projection.TILE_UNITS);
						if (Math.abs(nearTu - nearU) <= NEAR_TILES && Math.abs(nearTv - nearV) <= NEAR_TILES) {
							if (nearTu != lastNearTu || nearTv != lastNearTv) {
								nearTile = peek(level, new TileKey(layer, nearTu, nearTv, 0));
								lastNearTu = nearTu;
								lastNearTv = nearTv;
							}
							if (nearTile != null && nearTile.hasCoverage()) {
								double cell = (double) Projection.TILE_UNITS / Projection.COVERAGE_CELLS;
								int nearX = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - nearTu * (double) Projection.TILE_UNITS) / cell));
								int nearY = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - nearTv * (double) Projection.TILE_UNITS) / cell));
								known = Math.max(known, nearTile.coverage(nearX, nearY));
							}
						}
					}
					most = Math.max(most, known);
				}
				if (coarse && most > 0) {
					most = 1;
				}
				out[j * MASK + i] = 0xFF000000 | Math.round(most * 255);
			}
		}
	}
}
