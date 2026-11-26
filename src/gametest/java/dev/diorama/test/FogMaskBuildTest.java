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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.jspecify.annotations.Nullable;

/**
 * The fog mask's builds while the player stands, walks and flies, on the minimap, the full map and
 * a cave slice ({@code fog-mask}). Every build's known share, read from the tiles, is checked
 * against the same reading done the way 0.11 did it ({@link #readAsBefore}), pixel for pixel; and
 * what builds allocate on the render thread is kept under {@link #MOST_MINIMAP_BYTES} and
 * {@link #MOST_FULL_MAP_MEDIAN}, and while panning a full map in a 5120 by 1440 window at GUI scale
 * 1 under {@link #MOST_LARGE_PANNING_MEDIAN}. In 0.11 a minimap build allocated about 280 KB, all of
 * it lookup keys: each tile was looked up again on every row of the mask it lay on, and for a tile
 * not in memory every overview level above it too.
 *
 * <p>Logs, for each part: the builds, bytes and time a build, builds a second of game time, and what
 * the minimap's HUD element allocated a frame (where {@link MinimapAllocTest} has a probe).
 */
final class FogMaskBuildTest {
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	/**
	 * The most any minimap build may allocate. One allocates a hundred bytes or so, a kept key for
	 * each tile no build before it read (a few dozen at most), and now and then the lookup table
	 * made again at its size, 15 KB: under 20 KB in all. In 0.11 every one allocated 200 KB or more.
	 */
	private static final long MOST_MINIMAP_BYTES = 64 * 1024;
	/**
	 * The most the full map's median build may allocate. Its first build at a new zoom makes kept keys
	 * for thousands of tiles, 50 to 400 KB; the builds after allocate a hundred bytes or so. In 0.11
	 * the median was a megabyte.
	 */
	private static final long MOST_FULL_MAP_MEDIAN = 24 * 1024;
	/**
	 * The most the median build may allocate while panning the full map in a window this wide at GUI
	 * scale 1 over unmapped land, where a build reads about 22,000 tiles. Only the keys for tiles
	 * panned into allocate, a few hundred a build, 32 bytes each: about 10 KB. Before the lookup table
	 * could grow past 16,384 slots, a build reading more than 6,144 tiles dropped every key and made
	 * them all again, 0.5 to 1.5 MB a build.
	 */
	private static final long MOST_LARGE_PANNING_MEDIAN = 64 * 1024;
	private static final int LARGE_WIDTH = 5120;
	private static final int LARGE_HEIGHT = 1440;
	/** The tiles a build read when the lookup table dropped every key: the large view must read more, or it tests nothing. */
	private static final int TABLE_DROPPED_AT = 6144;
	/** Map units the large view pans a tick, about as fast as dragging the map. */
	private static final double LARGE_PAN = 400;
	/** Every full map build's bytes, for its median. */
	private static final List<Long> FULL_MAP_BUILDS = new ArrayList<>();
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
		/** Each build's bytes, for the part's median; made here so keeping them allocates nothing. */
		final long[] each = new long[4096];

		Part(String name) {
			this.name = name;
		}

		long median() {
			long[] sorted = Arrays.copyOf(this.each, Math.min(this.builds, this.each.length));
			Arrays.sort(sorted);
			return sorted.length == 0 ? 0 : sorted[sorted.length / 2];
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
	/** Set if the allocation counter ever gave -1, which would make every build look free. */
	private static boolean counterOff;
	private static final int[] BEFORE = new int[256 * 256];
	/** The tiles one build reads, as many as it looks up in the map. */
	private static final java.util.Set<TileKey> READ = new java.util.HashSet<>();

	private FogMaskBuildTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		PARTS.clear();
		FULL_MAP_BUILDS.clear();
		checked = 0;
		mismatches = 0;
		firstMismatch = null;
		counterOff = false;
		// With the JVM's per-thread allocation counting off, every reading is -1 and every build
		// would count as allocating nothing: the budgets would pass without measuring anything.
		String off = context.computeOnClient(mc -> {
			if (!THREADS.isThreadAllocatedMemorySupported()) {
				return "this JVM can't count a thread's allocations";
			}
			if (!THREADS.isThreadAllocatedMemoryEnabled() || THREADS.getCurrentThreadAllocatedBytes() < 0) {
				return "the JVM's per-thread allocation counting is off (ThreadMXBean.isThreadAllocatedMemoryEnabled() is false)";
			}
			return null;
		});
		if (off != null) {
			throw new AssertionError("fog-mask can't measure what fog mask builds allocate: " + off);
		}
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
			measure(context, world, name, false, 60, null, () -> context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(u, v, zoom, TileKey.SURFACE)));
			context.takeScreenshot("fog-mask-" + name.replace(", ", "-").replace(' ', '-'));
		}
		// Panning at a far zoom: a build for each step.
		measure(context, world, "full map, zoom 0.03, panning", false, 100, tick -> {
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(u + tick * 40, v, 0.03, TileKey.SURFACE));
			return null;
		});
		large(context, world, x, z);
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
		double cu = Projection.u(caveX, z);
		double cv = Projection.v(caveX, caveY, z);
		// Counted from before the screen opens: its first frame may already build the mask it shows.
		measure(context, world, "cave full map", false, 40, null, () -> {
			context.setScreen(MapScreen::new);
			context.waitTicks(5);
			int layer = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view().layer());
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cu, cv, 1.0, layer));
		});
		context.takeScreenshot("fog-mask-cave-full-map");
		context.setScreen(() -> null);
		world.getServer().runCommand("tp @a " + x + " " + FLY_Y + " " + z + " 90 0");
		context.runOnClient(mc -> mc.player.getAbilities().flying = true);
		context.waitTicks(40);
	}

	/**
	 * The full map in a 5120 by 1440 window at GUI scale 1, panning over land nobody has mapped, at a
	 * zoom where its builds read overview tiles and the finer levels under them (coarse sampling), and
	 * at one where they read the detailed levels' overviews (one spot a texel). Each build reads about
	 * 22,000 tiles, far more than the 6,144 at which the lookup table used to drop every key.
	 */
	private static void large(ClientGameTestContext context, TestSingleplayerContext world, int x, int z) {
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		DioramaClientTest.setGuiScale(context, 1);
		context.getInput().resizeWindow(LARGE_WIDTH, LARGE_HEIGHT);
		context.waitTicks(5);
		int[] size = context.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
		Diorama.LOGGER.info("fog-mask | large view: window {} by {} at GUI scale 1", size[0], size[1]);
		try {
			if (size[0] < LARGE_WIDTH || size[1] < LARGE_HEIGHT) {
				throw new AssertionError("The window couldn't be made " + LARGE_WIDTH + " by " + LARGE_HEIGHT + " for the large view: it's " + size[0] + " by " + size[1]);
			}
			// Far from anywhere the player went.
			double u = Projection.u(x - 300_000, z - 300_000);
			double v = Projection.v(x - 300_000, 64, z - 300_000);
			for (double zoom : new double[] {0.07, 0.3}) {
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(u, v, zoom, TileKey.SURFACE));
				// The first builds make the keys and grow the table; the panning after is what's measured.
				context.waitTicks(10);
				measure(context, world, "full map, " + LARGE_WIDTH + " by " + LARGE_HEIGHT + " at GUI scale 1, zoom " + zoom + ", panning", false, 120, tick -> {
					context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(u + tick * LARGE_PAN, v + tick * LARGE_PAN / 2, zoom, TileKey.SURFACE));
					return null;
				});
			}
			context.takeScreenshot("fog-mask-full-map-large-panning");
		} finally {
			context.getInput().resizeWindow(1600, 900);
			DioramaClientTest.setGuiScale(context, scale);
			context.waitTicks(5);
		}
	}

	private interface Path {
		/** Where the player is at this tick, {x, y, z}; null to leave it. */
		double @Nullable [] at(int tick);
	}

	private static void measure(ClientGameTestContext context, TestSingleplayerContext world, String name, boolean frames, int ticks, @Nullable Path path) {
		measure(context, world, name, frames, ticks, path, null);
	}

	/**
	 * Counts the builds of {@code ticks} ticks, moving the player along {@code path} each tick.
	 * {@code then}, if given, runs once counting has started: a change to the view made there is
	 * counted even if a frame draws before the first tick.
	 */
	private static void measure(ClientGameTestContext context, TestSingleplayerContext world, String name, boolean frames, int ticks, @Nullable Path path, @Nullable Runnable then) {
		Part counting = new Part(name);
		counting.ticks = ticks;
		context.runOnClient(mc -> {
			part = counting;
			if (frames) {
				MinimapAllocTest.startCounting();
			}
		});
		if (then != null) {
			then.run();
		}
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
		Diorama.LOGGER.info("fog-mask | {} builds checked against 0.11's reading, {} differed", checked, mismatches);
		if (counterOff) {
			throw new AssertionError("The JVM's per-thread allocation counter gave -1 during fog-mask: counting was turned off partway, so the bytes measured mean nothing");
		}
		if (mismatches > 0) {
			throw new AssertionError(mismatches + " of " + checked + " fog mask builds read the tiles differently from 0.11; the first: " + firstMismatch);
		}
		String largeFar = "full map, " + LARGE_WIDTH + " by " + LARGE_HEIGHT + " at GUI scale 1, zoom 0.07, panning";
		String largeNear = "full map, " + LARGE_WIDTH + " by " + LARGE_HEIGHT + " at GUI scale 1, zoom 0.3, panning";
		String[] mustBuild = {"minimap, walking, unexplored", "minimap, flying, unexplored", "minimap zoomed out, flying, unexplored", "full map, zoom 0.03, panning", largeFar, largeNear, "cave minimap, walking", "cave full map"};
		for (String name : mustBuild) {
			Part p = PARTS.get(name);
			if (p == null || p.builds == 0) {
				throw new AssertionError("The fog mask was never built in \"" + name + "\", so nothing was checked there");
			}
			if (name.startsWith("cave") && p.caveBuilds == 0) {
				throw new AssertionError("\"" + name + "\" never built a cave slice's mask: the player wasn't underground");
			}
		}
		for (String name : new String[] {largeFar, largeNear}) {
			Part p = PARTS.get(name);
			if (p.mostTiles <= TABLE_DROPPED_AT) {
				throw new AssertionError("\"" + name + "\" read at most " + p.mostTiles + " tiles a build, no more than the " + TABLE_DROPPED_AT + " this part is there to go past");
			}
		}
		// Each panning part on its own: its builds are many, and a pooled median could hide one part's.
		for (String name : new String[] {"full map, zoom 0.03, panning", largeFar, largeNear}) {
			Part p = PARTS.get(name);
			long most = name.equals("full map, zoom 0.03, panning") ? MOST_FULL_MAP_MEDIAN : MOST_LARGE_PANNING_MEDIAN;
			long median = p.median();
			if (median > most) {
				throw new AssertionError("Panning in \"" + name + "\", the median fog mask build allocated " + median + " bytes on the render thread, over " + most
					+ " (" + p.builds + " builds, most tiles a build " + p.mostTiles + "): are the tile keys made again every build?");
			}
		}
		for (Part p : PARTS.values()) {
			if (!p.name.contains("full map") && p.mostBytes > MOST_MINIMAP_BYTES) {
				throw new AssertionError("A fog mask build in \"" + p.name + "\" allocated " + p.mostBytes + " bytes on the render thread, over " + MOST_MINIMAP_BYTES
					+ " (mean " + p.bytes / Math.max(1, p.builds) + " over " + p.builds + " builds): is a tile looked up with a new key for every texel row again?");
			}
		}
		List<Long> sorted = new ArrayList<>(FULL_MAP_BUILDS);
		Collections.sort(sorted);
		long median = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
		Diorama.LOGGER.info("fog-mask | full map builds | {} builds, median {} bytes", sorted.size(), median);
		if (median > MOST_FULL_MAP_MEDIAN) {
			throw new AssertionError("The full map's fog mask builds allocated a median " + median + " bytes on the render thread, over " + MOST_FULL_MAP_MEDIAN
				+ " (" + sorted.size() + " builds): is a tile looked up with a new key for every texel row again?");
		}
	}

	private static final class Watch implements MapViewport.MaskWatch {
		@Override
		public void started() {
			checkBytes = 0;
			checkNanos = 0;
			startNanos = System.nanoTime();
			startBytes = THREADS.getCurrentThreadAllocatedBytes();
			if (startBytes < 0) {
				counterOff = true;
			}
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
				if (p.builds <= p.each.length) {
					p.each[p.builds - 1] = bytes;
				}
				if (p.name.contains("full map")) {
					FULL_MAP_BUILDS.add(bytes);
				}
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
