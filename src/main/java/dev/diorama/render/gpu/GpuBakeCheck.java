package dev.diorama.render.gpu;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.Settings;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else if neoforge {
/*import net.neoforged.fml.ModList;
*///?} else {
/*import net.minecraftforge.fml.ModList;
*///?}

/**
 * Whether a bake goes to the GPU. The player turns GPU baking on in the settings
 * ({@code DIORAMA_GPU_BAKE=1} or {@code 0} forces it for tests). Before the map shows any GPU tile,
 * the first few are also traced and the two compared: a graphics driver that draws them differently
 * turns GPU baking off, so it can't spoil a map. So does a card too slow to bake without the
 * player feeling it, and any failure of the GPU code, down to a driver crash taking the game with
 * it. Each is saved per card ({@link GpuBakeRecord}), so the next session goes straight to the
 * tracer; turning the setting off and on again asks for a new try.
 *
 * <p>Both bakes of a check tile read the same chunks (a {@code PinnedSource}), so any difference
 * past what the two sample patterns explain is the GPU's. Six kinds of bake are checked apart:
 * sky views, cave slices and views under a roof (the Nether's, cave slices there too), each with
 * overlays and without. Each kind lights its own way (the sun, block light, light from above with
 * the lid off and the biome's fog), and only overlay bakes write the overlay bits, so a pass on
 * one kind says nothing about another. Fog-of-war bakes are never check tiles, since what the
 * player has seen keeps changing under them.
 *
 * <p>A kind that passed is trusted: its own tiles showed this GPU draws it right. A later
 * failure of another kind stops GPU baking from then on, but doesn't reach back into tiles the
 * passed kinds already drew.
 */
public final class GpuBakeCheck {
	/** How a bake runs. */
	public enum Mode {
		/** On the CPU, as without GPU baking. */
		TRACE,
		/** On the GPU, after the check passed. */
		GPU,
		/** On the GPU and the CPU both; the map gets the traced one. */
		CHECK
	}

	/** Why GPU baking turned itself off. */
	public enum Off {
		/** The tiles came out differently from the tracer's, or the GPU couldn't draw them. */
		NO_MATCH,
		/** Tiles took long enough on the GPU to stall frames. */
		TOO_SLOW,
		/** The game went down during the first bakes. */
		CRASHED
	}

	private static final String FORCED = System.getenv("DIORAMA_GPU_BAKE");
	/** Tiles compared, each with enough known pixels to say something. */
	private static final int TILES = 4;
	/** Bakes the GPU couldn't draw at all before giving up on it. */
	private static final int FAILURES = 3;
	/**
	 * Limits for each tile, over pixels both bakes know: the mean difference and how much brighter
	 * or darker the GPU's are on average. Tile by tile, matching drivers measured biases up to 1.6
	 * and means up to 2.9 in forest but 4.4 in bright snow, where every shadow edge the two sample
	 * patterns place differently costs more. A driver that lost the sky occlusion (an integer
	 * texture read as zeros) measured biases from 2.8, all of it brighter: the bias tells them
	 * apart, and the mean only catches what's far off.
	 */
	private static final double MAX_MEAN = 6;
	private static final double MAX_BIAS = 2.2;
	/**
	 * How far any one colour channel may run brighter or darker on average: colours that are
	 * wrong but cancel out in brightness (red and blue swapped, say) show here. Matching drivers
	 * measured up to 1.8 per tile; the sky-occlusion bug from 4.5.
	 */
	private static final double MAX_CHANNEL_BIAS = 3;
	/**
	 * Share of the tile's pixels known to one bake and not the other, and of pixels both know
	 * with a different alpha (how many of the four samples hit something). Along the edge of the
	 * explored area the two sample patterns split pixels differently: matching drivers measured
	 * up to 0.12% and 3.1% there. Alpha decides which pixels a merge keeps from the saved tile, so
	 * it can't be loose: 8%, as the comparison test allows.
	 */
	private static final double MAX_MISMATCH = 0.02;
	private static final double MAX_ALPHA = 0.08;
	/** Share of pixels both know whose overlay bits differ: 0.35% measured, from the sample positions. */
	private static final double MAX_OVERLAY = 0.02;
	/**
	 * Pixels both bakes must know for a tile to count towards a pass, and, fewer, for its
	 * differences to fail the check: a tile mostly unexplored still shows a GPU that's wrong.
	 */
	private static final int PASS_PIXELS = Projection.TILE_PIXELS * Projection.TILE_PIXELS / 4;
	private static final int JUDGE_PIXELS = 2048;
	/**
	 * The longest the middle check tile of a view may take, render thread or GPU. A bake can't be
	 * cut short, so it holds up the frame it lands on by about this long, however thin the budget
	 * spreads bakes out. Tests that force GPU baking on don't stop for it.
	 */
	private static final long MAX_COST = (long) (Double.parseDouble(System.getProperty("diorama.gpuBakeMaxMs", "50")) * 1e6);
	/** Finished bakes in a row the GPU couldn't draw before giving up on it, once it passed. */
	private static final int FAILURES_IN_A_ROW = 3;

	private static final Object LOCK = new Object();
	private static volatile @Nullable Off off;
	/** Whether this card's saved result has been read: {@link #open}. */
	private static volatile boolean opened;
	/** This session's first bakes are unproven, and marked so in the record. */
	private static boolean unproven;
	private static boolean closingHooked;
	private static int failuresInARow;
	/** The views, as {@link #mode} and the rest take them. */
	public static final int SKY = 0;
	public static final int CAVE = 1;
	public static final int UNDER_ROOF = 2;
	/** By kind: each view without overlays, then with. */
	private static final Check[] CHECKS = {
		new Check("sky view"), new Check("cave view"), new Check("view under a roof"),
		new Check("sky view with overlays"), new Check("cave view with overlays"), new Check("view under a roof with overlays")
	};

	private GpuBakeCheck() {
	}

	/** One kind of view's check. Guarded by {@link #LOCK}. */
	private static final class Check {
		final String name;
		volatile boolean passed;
		int out;
		int compared;
		int failures;
		double worstMean;
		double worstBias;
		double worstMismatch;
		double worstAlpha;
		double worstOverlay;
		double worstChannel;
		final long[] costs = new long[TILES];

		Check(String name) {
			this.name = name;
		}

		void reset() {
			this.passed = false;
			this.compared = 0;
			this.failures = 0;
			this.worstMean = 0;
			this.worstBias = 0;
			this.worstMismatch = 0;
			this.worstAlpha = 0;
			this.worstOverlay = 0;
			this.worstChannel = 0;
		}
	}

	/** Whether GPU baking is asked for, by the setting or a test. */
	public static boolean wanted() {
		if (FORCED != null && !FORCED.isEmpty()) {
			return "1".equals(FORCED);
		}
		return Settings.get().gpuBaking;
	}

	/** Whether GPU baking turned itself off, this session or from the saved result. */
	public static boolean failed() {
		return off != null;
	}

	/** Why GPU baking turned itself off, if it did. */
	public static @Nullable Off off() {
		return off;
	}

	/** Whether a test forces GPU baking on, past what's saved and the speed limit. */
	private static boolean forcedOn() {
		return "1".equals(FORCED);
	}

	static boolean opened() {
		return opened;
	}

	/**
	 * The first frame with GPU baking wanted, before the GPU is asked for anything: goes by what's
	 * saved for this card, or marks its first bakes unproven. Render thread.
	 */
	static void open(@Nullable String device) {
		synchronized (LOCK) {
			opened = true;
			if (device == null) {
				return;
			}
			GpuBakeRecord record = GpuBakeRecord.load();
			String crashed = record.starting;
			String mod = modVersion();
			// Another game in this folder, still on its first bakes, hasn't crashed: it's running.
			boolean elsewhere = crashed != null && ProcessHandle.of(record.startingProcess).filter(ProcessHandle::isAlive).isPresent();
			if (crashed != null && !elsewhere) {
				record.starting = null;
				if (!forcedOn()) {
					Diorama.LOGGER.warn("The game went down during the first GPU bakes on {}. Tiles are traced on the CPU on that card from now on.", crashed);
					record.off.put(crashed, new GpuBakeRecord.Entry(Off.CRASHED, record.startingMod));
				}
			}
			Off saved = forcedOn() ? null : record.result(device, mod);
			if (saved != null) {
				off = saved;
				Diorama.LOGGER.info("GPU baking stays off on {}: {} here before. Tiles are traced on the CPU.", device, saved);
				record.save();
				return;
			}
			if (elsewhere) {
				return;
			}
			record.starting = device;
			record.startingProcess = ProcessHandle.current().pid();
			record.startingMod = mod;
			record.save();
			unproven = true;
			if (!closingHooked) {
				closingHooked = true;
				// A game that closes, or crashes with a report, runs this; a dead driver doesn't.
				Runtime.getRuntime().addShutdownHook(new Thread(GpuBakeCheck::proven, "diorama-gpu-closing"));
			}
		}
	}

	/** The first bakes are through, or the game is closing in good order. */
	private static void proven() {
		synchronized (LOCK) {
			if (unproven) {
				unproven = false;
				GpuBakeRecord record = GpuBakeRecord.load();
				record.starting = null;
				record.save();
			}
		}
	}

	/** The player turned the setting back on: forgets this card's result and checks it again. */
	public static void retry() {
		synchronized (LOCK) {
			if (off == null) {
				return;
			}
			GpuBakeRecord record = GpuBakeRecord.load();
			if (record.off.remove(GpuTileBaker.device()) != null) {
				record.save();
			}
			off = null;
			opened = false;
			failuresInARow = 0;
			for (Check check : CHECKS) {
				check.reset();
			}
			GpuTileBaker.retry();
		}
	}

	private static String modVersion() {
		//? if fabric {
		return FabricLoader.getInstance().getModContainer(Diorama.MOD_ID).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
		//?} else {
		/*return ModList.get().getModContainerById(Diorama.MOD_ID).map(mod -> mod.getModInfo().getVersion().toString()).orElse("");
		*///?}
	}

	private static Check check(int view, boolean overlays) {
		return CHECKS[view + (overlays ? 3 : 0)];
	}

	/**
	 * How the next bake of a {@code view} ({@link #SKY}, {@link #CAVE} or {@link #UNDER_ROOF}) runs,
	 * with fog of war and overlays or not. A {@link Mode#CHECK} must be answered with {@link #compare} or {@link #undrawn},
	 * exactly once, with the same kind, and its two bakes must read the same chunks.
	 */
	public static Mode mode(int view, boolean fogOfWar, boolean overlays) {
		if (!wanted() || off != null) {
			return Mode.TRACE;
		}
		if (!opened) {
			// Traced while the next frame reads what's saved for this card.
			GpuTileBaker.use();
			return Mode.TRACE;
		}
		Check check = check(view, overlays);
		if (check.passed) {
			return Mode.GPU;
		}
		if (fogOfWar) {
			return Mode.TRACE;
		}
		synchronized (LOCK) {
			// A few at a time, so a tile without much in it doesn't hold the check up.
			if (off == null && !check.passed && check.compared + check.out < TILES) {
				check.out++;
				return Mode.CHECK;
			}
		}
		return Mode.TRACE;
	}

	/**
	 * A check tile came back from both bakers: the GPU's pixels and the tracer's, before any merge,
	 * with their overlay bits when the bake asked for them, and what the GPU's bake cost.
	 */
	public static void compare(int view, int[] gpu, int[] traced, byte @Nullable [] gpuInfo, byte @Nullable [] tracedInfo, long costNanos) {
		boolean overlays = tracedInfo != null;
		long both = 0;
		long mismatched = 0;
		long alphaDiffers = 0;
		long overlayDiffers = 0;
		double sum = 0;
		double bias = 0;
		double redBias = 0;
		double greenBias = 0;
		double blueBias = 0;
		for (int i = 0; i < traced.length; i++) {
			int c = traced[i];
			int g = gpu[i];
			boolean ck = (c >>> 24) != 0;
			if (ck != ((g >>> 24) != 0)) {
				mismatched++;
			} else if (ck) {
				both++;
				if ((c >>> 24) != (g >>> 24)) {
					alphaDiffers++;
				}
				if (overlays && (gpuInfo == null || gpuInfo[i] != tracedInfo[i])) {
					overlayDiffers++;
				}
				int dr = (g & 0xFF) - (c & 0xFF);
				int dg = ((g >> 8) & 0xFF) - ((c >> 8) & 0xFF);
				int db = ((g >> 16) & 0xFF) - ((c >> 16) & 0xFF);
				sum += (Math.abs(dr) + Math.abs(dg) + Math.abs(db)) / 3.0;
				bias += (dr + dg + db) / 3.0;
				redBias += dr;
				greenBias += dg;
				blueBias += db;
			}
		}
		Check check = check(view, overlays);
		synchronized (LOCK) {
			check.out--;
			if (off != null || check.passed) {
				return;
			}
			double mismatch = mismatched / (double) traced.length;
			// Too few pixels known to both to judge their colours: only the mismatch tells.
			boolean judged = both >= JUDGE_PIXELS;
			double mean = judged ? sum / both : 0;
			double brighter = judged ? bias / both : 0;
			double channel = judged ? Math.max(Math.abs(redBias), Math.max(Math.abs(greenBias), Math.abs(blueBias))) / both : 0;
			double alpha = judged ? alphaDiffers / (double) both : 0;
			double overlay = judged && overlays ? overlayDiffers / (double) both : 0;
			// Every tile must match on its own: the two read the same chunks, so nothing but the
			// GPU makes them differ, and matching tiles mustn't hide one that doesn't.
			if (mean > MAX_MEAN || Math.abs(brighter) > MAX_BIAS || channel > MAX_CHANNEL_BIAS || mismatch > MAX_MISMATCH || alpha > MAX_ALPHA || overlay > MAX_OVERLAY) {
				fail(Off.NO_MATCH, String.format(java.util.Locale.ROOT, "a %s tile doesn't match the tracer's (mean difference %.2f/255, %+.2f brighter, one channel off by %.2f, %.2f%% known/unknown mismatch, %.2f%% different alpha, %.2f%% different overlay bits)",
					check.name, mean, brighter, channel, mismatch * 100, alpha * 100, overlay * 100));
				return;
			}
			if (both < PASS_PIXELS) {
				// Mostly unexplored or empty: it matched, but another tile will tell more.
				return;
			}
			check.costs[check.compared++] = costNanos;
			check.worstMean = Math.max(check.worstMean, mean);
			check.worstBias = Math.abs(brighter) > Math.abs(check.worstBias) ? brighter : check.worstBias;
			check.worstMismatch = Math.max(check.worstMismatch, mismatch);
			check.worstAlpha = Math.max(check.worstAlpha, alpha);
			check.worstOverlay = Math.max(check.worstOverlay, overlay);
			check.worstChannel = Math.max(check.worstChannel, channel);
			if (check.compared >= TILES) {
				// The middle one: the first also pays for compiling the shaders.
				long[] costs = check.costs.clone();
				Arrays.sort(costs);
				long cost = (costs[TILES / 2 - 1] + costs[TILES / 2]) / 2;
				if (cost > MAX_COST && !forcedOn()) {
					fail(Off.TOO_SLOW, String.format(java.util.Locale.ROOT, "a %s tile takes %.1f ms, past the %.0f ms that stalls a frame", check.name, cost / 1e6, MAX_COST / 1e6));
					return;
				}
				check.passed = true;
				Diorama.LOGGER.info("GPU baking matches the tracer on {} ({}): on", GpuTileBaker.device(), String.format(java.util.Locale.ROOT,
					"%s, %d tiles, %.1f ms a tile, worst: mean difference %.2f/255, %+.2f brighter, one channel off by %.2f, %.2f%% known/unknown mismatch, %.2f%% different alpha, %.2f%% different overlay bits",
					check.name, check.compared, cost / 1e6, check.worstMean, check.worstBias, check.worstChannel, check.worstMismatch * 100, check.worstAlpha * 100, check.worstOverlay * 100));
				proven();
			}
		}
	}

	/**
	 * A check tile the GPU didn't draw, or whose comparison no longer counts; it was traced instead.
	 * Only one the GPU couldn't draw counts against it, not one handed back because the world
	 * closed or the map's look changed meanwhile.
	 */
	public static void undrawn(int view, boolean overlays, boolean cannotDraw) {
		Check check = check(view, overlays);
		synchronized (LOCK) {
			check.out--;
			if (cannotDraw && off == null && !check.passed && ++check.failures >= FAILURES) {
				fail(Off.NO_MATCH, "couldn't draw any of " + check.failures + " " + check.name + " tiles");
			}
		}
	}

	/** A map tile came back from the GPU, drawn or not. A card that stops drawing, after passing, stops being asked. */
	public static void drawn(boolean drew) {
		synchronized (LOCK) {
			failuresInARow = drew ? 0 : failuresInARow + 1;
			if (failuresInARow >= FAILURES_IN_A_ROW && off == null) {
				fail(Off.NO_MATCH, "couldn't draw " + failuresInARow + " tiles in a row");
			}
		}
	}

	/** The GPU code failed where a single tile can't take the blame. */
	static void broken(String why) {
		synchronized (LOCK) {
			if (off == null) {
				fail(Off.NO_MATCH, why);
			}
		}
	}

	/** Guarded by {@link #LOCK}. */
	private static void fail(Off why, String details) {
		off = why;
		String device = GpuTileBaker.device();
		Diorama.LOGGER.warn("GPU baking turned off on {}: {}. Tiles are traced on the CPU instead.", device, details);
		GpuBakeRecord record = GpuBakeRecord.load();
		record.off.put(device, new GpuBakeRecord.Entry(why, modVersion()));
		if (unproven) {
			record.starting = null;
			unproven = false;
		}
		record.save();
		if (why == Off.TOO_SLOW) {
			// Nothing's wrong and nothing changes on the map: the setting says so if anyone looks.
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			Component message = Component.translatable("diorama.gpu_baking.failed");
			//? if >=26.1 {
			mc.gui.hud.getChat().addClientSystemMessage(message);
			//?} else {
			/*mc.gui.getChat().addMessage(message);
			*///?}
		});
	}
}
