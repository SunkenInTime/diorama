package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MinimapHud;
import dev.diorama.MinimapPositionScreen;
import dev.diorama.Settings;
import dev.diorama.addon.HudHides;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import dev.diorama.testaddon.TestAddon;
import dev.diorama.testaddon.broken.BrokenAddon;
import dev.diorama.testaddon.linkage.LinkageAddon;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/**
 * Diorama's API for other mods ({@code addon-api}), driven through the test addons in testaddon/,
 * which only know the API. Needs their jars installed: boat_run.py --mods testaddon/build/libs
 * after ./gradlew :testaddon:assemble. The good addon was found and told it's ready once, on the
 * client thread; hiding the minimap through it takes the minimap off the screen without touching
 * the settings or their file, two holders stack, and each part shows again once both let go; the
 * waypoints in the world hide the same way; the placing screen still shows the minimap; calls from
 * another thread throw; the addon that throws and the one missing a class were switched off once
 * each, with a log line naming them, and what they hid shows again; and a handle held when the
 * player leaves is let go ({@link #reopened}).
 */
final class AddonApiTest {
	/** A colour no block uses, to find the waypoint's pin in the world by. */
	private static final int PIN = 0xFF00FF;
	/** Held as the world closes, to check after rejoining that leaving let go of it. */
	private static Handle heldWhileLeaving;

	private AddonApiTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		found(context);
		failures(context);

		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		context.setScreen(() -> null);
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		world.getServer().runCommand("tp @a " + (at.getX() + 0.5) + " " + at.getY() + " " + (at.getZ() + 0.5) + " 180 0");
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			store.all().stream().toList().forEach(store::remove);
			Waypoint pin = new Waypoint("Addon", at.getX() - 6, at.getY(), at.getZ() - 14, "minecraft:overworld", PIN);
			pin.initials = "A";
			store.add(pin);
		});
		// Long enough for the commands' chat lines to fade.
		context.waitTicks(220);
		try {
			hiding(context);
			placing(context);
			threads(context);
		} finally {
			context.runOnClient(mc -> {
				WaypointStore store = WaypointStore.get();
				store.all().stream().toList().forEach(store::remove);
			});
		}
		//? if >=1.21.6 && fabric {
		allocations(context);
		//?}
		// Taken last and left open: leaving the world has to let go of it.
		heldWhileLeaving = context.computeOnClient(mc -> TestAddon.hide(Hud.Part.MINIMAP));
		context.waitTicks(2);
		if (context.computeOnClient(mc -> MinimapHud.shown() != null)) {
			throw new AssertionError("The minimap shows with a handle hiding it as the world closes");
		}
	}

	/** After leaving with a handle held and opening the world again: the minimap is back. */
	static void reopened(ClientGameTestContext context) {
		if (heldWhileLeaving == null) {
			throw new AssertionError("addon-api didn't run in the first world");
		}
		context.setScreen(() -> null);
		context.waitTicks(40);
		context.takeScreenshot("addon-api-07-rejoined");
		boolean open = context.computeOnClient(mc -> heldWhileLeaving.isOpen());
		boolean shown = context.computeOnClient(mc -> MinimapHud.shown() != null);
		if (open || context.computeOnClient(mc -> HudHides.minimapHidden()) || !shown) {
			throw new AssertionError("After leaving with a handle held and rejoining: handle " + (open ? "still open" : "closed")
				+ ", minimap " + (shown ? "shown" : "not shown"));
		}
		// Closing it now does nothing, as for any closed handle.
		context.runOnClient(mc -> heldWhileLeaving.close());
		Diorama.LOGGER.info("addon-api: leaving the world let go of the handle, and the minimap is back after rejoining");
	}

	private static void found(ClientGameTestContext context) {
		context.waitFor(mc -> TestAddon.api() != null, 200);
		String clientThread = context.computeOnClient(mc -> Thread.currentThread().getName());
		int calls = context.computeOnClient(mc -> TestAddon.readyCalls());
		String thread = context.computeOnClient(mc -> TestAddon.readyThread());
		String modId = context.computeOnClient(mc -> TestAddon.api().modId());
		String version = context.computeOnClient(mc -> TestAddon.api().apiVersion());
		if (calls != 1 || !thread.equals(clientThread) || !modId.equals("diorama_test_addon") || !version.matches("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?")) {
			throw new AssertionError("The test addon: onReady called " + calls + " times on " + thread + " (the client's is " + clientThread
				+ "), mod ID " + modId + ", API " + version);
		}
		Diorama.LOGGER.info("addon-api: found {} through {}, onReady once on {}, API {}", modId, loader(), thread, version);
	}

	/** The broken and linkage addons: switched off, once each, and what they hid shows again. */
	private static void failures(ClientGameTestContext context) {
		boolean brokenHeld = context.computeOnClient(mc -> BrokenAddon.held() != null && BrokenAddon.held().isOpen());
		boolean linkageHeld = context.computeOnClient(mc -> LinkageAddon.held() != null && LinkageAddon.held().isOpen());
		if (context.computeOnClient(mc -> BrokenAddon.held() == null || LinkageAddon.held() == null)) {
			throw new AssertionError("The broken or linkage test addon never got as far as hiding the minimap: is it installed?");
		}
		if (brokenHeld || linkageHeld || context.computeOnClient(mc -> HudHides.minimapHidden())) {
			throw new AssertionError("A failed addon still hides the minimap: broken " + brokenHeld + ", linkage " + linkageHeld);
		}
		// Switched off, its later calls do nothing.
		Handle late = context.computeOnClient(mc -> BrokenAddon.api().hud().hide(EnumSet.of(Hud.Part.MINIMAP)));
		if (context.computeOnClient(mc -> late.isOpen() || HudHides.minimapHidden())) {
			throw new AssertionError("The broken addon hid the minimap again after it was switched off");
		}
		String log = context.computeOnClient(mc -> text(mc.gameDirectory.toPath().resolve("logs/latest.log")));
		long broken = lines(log, "Diorama addon diorama_test_addon_broken failed");
		long linkage = lines(log, "Diorama addon diorama_test_addon_linkage failed");
		boolean linkageCause = log.contains("NoClassDefFoundError: dev/diorama/testaddon/linkage/MissingClass");
		if (broken != 1 || linkage != 1 || !linkageCause) {
			throw new AssertionError("Log lines for the failed addons: broken " + broken + ", linkage " + linkage + ", linkage's cause named " + linkageCause);
		}
		Diorama.LOGGER.info("addon-api: the broken and linkage addons were each switched off with one log line, and their handles closed");
	}

	private static void hiding(ClientGameTestContext context) {
		Path config = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve(Diorama.MOD_ID).resolve("config.json"));
		// Saved first, so the file is there to compare, as it is for a player who has opened the settings once.
		context.runOnClient(mc -> Settings.get().save());
		byte[] before = bytes(config);
		boolean minimapSetting = context.computeOnClient(mc -> Settings.get().minimap);
		boolean waypointsSetting = context.computeOnClient(mc -> Settings.get().waypointsInWorld);

		BufferedImage shown = read(context.takeScreenshot("addon-api-01-shown"));
		expect(context, true, true, "before any handle");
		if (!has(world(shown), PIN)) {
			throw new AssertionError("The waypoint isn't drawn in the world before any handle");
		}

		Handle first = context.computeOnClient(mc -> TestAddon.hide(Hud.Part.MINIMAP));
		context.waitTicks(2);
		BufferedImage hidden = read(context.takeScreenshot("addon-api-02-minimap-hidden"));
		expect(context, false, true, "with a handle on the minimap");
		if (!has(world(hidden), PIN)) {
			throw new AssertionError("Hiding the minimap took the waypoint in the world with it");
		}

		Handle second = context.computeOnClient(mc -> TestAddon.hide(Hud.Part.MINIMAP, Hud.Part.WORLD_WAYPOINTS));
		context.waitTicks(2);
		BufferedImage both = read(context.takeScreenshot("addon-api-03-both-hidden"));
		expect(context, false, false, "with two handles");
		if (has(world(both), PIN)) {
			throw new AssertionError("The waypoint is still drawn in the world with a handle hiding waypoints in the world");
		}

		// The first lets go, twice: closing a closed handle does nothing. The second still holds both parts.
		context.runOnClient(mc -> {
			first.close();
			first.close();
		});
		context.waitTicks(2);
		context.takeScreenshot("addon-api-04-one-released");
		expect(context, false, false, "after the first of two handles closed (twice)");
		if (context.computeOnClient(mc -> first.isOpen() || !second.isOpen())) {
			throw new AssertionError("Closing the first handle didn't close it alone");
		}

		context.runOnClient(mc -> second.close());
		context.waitTicks(2);
		BufferedImage back = read(context.takeScreenshot("addon-api-05-back"));
		expect(context, true, true, "after both handles closed");
		if (!has(world(back), PIN)) {
			throw new AssertionError("The waypoint isn't drawn in the world again after both handles closed");
		}

		byte[] after = bytes(config);
		boolean settingsKept = context.computeOnClient(mc -> Settings.get().minimap == minimapSetting && Settings.get().waypointsInWorld == waypointsSetting);
		if (!Arrays.equals(before, after) || !settingsKept) {
			throw new AssertionError("Hiding changed the settings: config.json " + (Arrays.equals(before, after) ? "the same" : "changed") + ", settings " + (settingsKept ? "kept" : "changed"));
		}
		Diorama.LOGGER.info("addon-api: hidden and shown again, two handles stacked, config.json byte for byte the same ({} bytes)", after.length);
	}

	/** While the player places the minimap, it shows even with a handle hiding it. */
	private static void placing(ClientGameTestContext context) {
		Handle held = context.computeOnClient(mc -> TestAddon.hide(Hud.Part.MINIMAP));
		try {
			context.setScreen(() -> new MinimapPositionScreen(null));
			context.waitTicks(5);
			context.takeScreenshot("addon-api-06-placing");
			if (context.computeOnClient(mc -> MinimapHud.shown() == null)) {
				throw new AssertionError("The minimap position screen doesn't show the minimap while an addon hides it");
			}
			context.setScreen(() -> null);
			context.waitTicks(2);
			if (context.computeOnClient(mc -> MinimapHud.shown() != null)) {
				throw new AssertionError("The minimap came back after closing the position screen with the handle still open");
			}
		} finally {
			context.runOnClient(mc -> held.close());
		}
	}

	/** Calls from another thread throw, and so does hiding nothing. */
	private static void threads(ClientGameTestContext context) {
		DioramaApi api = context.computeOnClient(mc -> TestAddon.api());
		String here = Thread.currentThread().getName();
		if (here.equals(context.computeOnClient(mc -> Thread.currentThread().getName()))) {
			throw new AssertionError("The test runs on the client thread, so it can't try another");
		}
		try {
			api.hud().hide(EnumSet.of(Hud.Part.MINIMAP));
			throw new AssertionError("hide() from " + here + " didn't throw");
		} catch (IllegalStateException expected) {
			// Diorama's API is for the client thread only.
		}
		boolean emptyThrows = context.computeOnClient(mc -> {
			try {
				TestAddon.api().hud().hide(Set.of());
				return false;
			} catch (IllegalArgumentException expected) {
				return true;
			}
		});
		if (!emptyThrows || context.computeOnClient(mc -> HudHides.minimapHidden() || HudHides.worldWaypointsHidden())) {
			throw new AssertionError("hide() with no parts didn't throw, or a refused call left something hidden");
		}
	}

	//? if >=1.21.6 && fabric {
	/**
	 * Bytes the minimap's drawing allocates a frame, with no handle, with one handle on another
	 * part, and with one hiding the minimap ({@link AllocProbe}), the map settled and the world
	 * paused so each frame draws the same minimap.
	 */
	private static void allocations(ClientGameTestContext context) {
		context.runOnClient(mc -> AllocProbe.register());
		DioramaClientTest.settle(context, 20);
		try (WorldPause paused = WorldPause.pause(context)) {
			AllocProbe.measure(context, "warm-up", 150);
			long none = AllocProbe.measure(context, "no handle", 200);
			Handle waypoints = context.computeOnClient(mc -> TestAddon.hide(Hud.Part.WORLD_WAYPOINTS));
			long other = AllocProbe.measure(context, "one handle on WORLD_WAYPOINTS", 200);
			context.runOnClient(mc -> waypoints.close());
			Handle minimap = context.computeOnClient(mc -> TestAddon.hide(Hud.Part.MINIMAP));
			long hidden = AllocProbe.measure(context, "one handle on MINIMAP", 200);
			context.runOnClient(mc -> minimap.close());
			long again = AllocProbe.measure(context, "no handle again", 200);
			if (hidden != 0) {
				throw new AssertionError("The minimap's draw allocated a median " + hidden + " bytes a frame while hidden");
			}
			Diorama.LOGGER.info("addon-api alloc: medians a frame: none {}, other part {}, hidden {}, none again {}", none, other, hidden, again);
		}
	}
	//?}

	/** Checks what this frame drew: the minimap, and the waypoints in the world. */
	private static void expect(ClientGameTestContext context, boolean minimap, boolean waypoints, String when) {
		boolean minimapShown = context.computeOnClient(mc -> MinimapHud.shown() != null);
		boolean minimapHidden = context.computeOnClient(mc -> HudHides.minimapHidden());
		boolean waypointsHidden = context.computeOnClient(mc -> HudHides.worldWaypointsHidden());
		if (minimapShown != minimap || minimapHidden == minimap || waypointsHidden == waypoints) {
			throw new AssertionError("Expected the minimap " + (minimap ? "shown" : "hidden") + " and waypoints in the world " + (waypoints ? "shown" : "hidden")
				+ " " + when + ": minimap drawn " + minimapShown + ", hidden by addons " + minimapHidden + ", waypoints hidden by addons " + waypointsHidden);
		}
	}

	private static String loader() {
		//? if fabric {
		return "a Fabric entrypoint";
		//?} else {
		/*return "@DioramaEntrypoint in the mod file scan";
		*///?}
	}

	private static long lines(String log, String text) {
		return log.lines().filter(line -> line.contains(text)).count();
	}

	private static String text(Path file) {
		return new String(bytes(file), StandardCharsets.UTF_8);
	}

	private static byte[] bytes(Path file) {
		try {
			return Files.readAllBytes(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The screenshot without the quarter the minimap is in. */
	private static BufferedImage world(BufferedImage shot) {
		return shot.getSubimage(0, 0, shot.getWidth() * 3 / 4, shot.getHeight());
	}

	/** Whether enough of the picture is this colour to be a pin's head, and not a stray pixel. */
	private static boolean has(BufferedImage shot, int rgb) {
		int count = 0;
		for (int y = 0; y < shot.getHeight(); y++) {
			for (int x = 0; x < shot.getWidth(); x++) {
				int argb = shot.getRGB(x, y);
				if (Math.abs((argb >> 16 & 0xFF) - (rgb >> 16 & 0xFF)) <= 4 && Math.abs((argb >> 8 & 0xFF) - (rgb >> 8 & 0xFF)) <= 4 && Math.abs((argb & 0xFF) - (rgb & 0xFF)) <= 4) {
					count++;
				}
			}
		}
		return count >= 12;
	}

	private static BufferedImage read(Path file) {
		try {
			return ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
