package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapSession;
import dev.diorama.MinimapHud;
import dev.diorama.MinimapPositionScreen;
import dev.diorama.Settings;
import dev.diorama.addon.HudHides;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import dev.diorama.testaddon.TestAddon;
import dev.diorama.testaddon.assertion.AssertionAddon;
import dev.diorama.testaddon.broken.BrokenAddon;
import dev.diorama.testaddon.linkage.LinkageAddon;
import dev.diorama.testaddon.overflow.OverflowAddon;
import dev.diorama.testaddon.recursive.RecursiveAddon;
import dev.diorama.testaddon.unprintable.UnprintableAddon;
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
import net.minecraft.world.level.Level;

/**
 * Diorama's API for other mods ({@code addon-api}), driven through the test addons in testaddon/,
 * which only know the API. Needs their jars installed: boat_run.py --mods testaddon/build/libs
 * after ./gradlew :testaddon:assemble. At the title screen ({@link #titleScreen}): the good addon
 * was found and told it's ready once, on the client thread, and took a handle on the minimap; the
 * six that fail (one throws, one is missing a class, one fails an assertion, one throws an
 * exception whose toString() throws, one wraps a StackOverflowError in its own exception, one
 * throws an exception whose getMessage() recurses) were switched off once each, with a log line
 * naming them, and their handles closed; a class marked by mistake was turned down without
 * running. A handle lasts until it is closed: the one taken at the title screen still hides the
 * minimap in the first world and in the Nether ({@link #firstWorld}), back at the title screen
 * ({@link #left}), and in the reopened world, where closing it brings the minimap back
 * ({@link #reopened}). There ({@link #run}), hiding the minimap takes it off the screen without
 * touching the settings or their file, two holders stack, and each part shows again once both let
 * go; the waypoints in the world hide the same way; the placing screen still shows the minimap;
 * calls from another thread throw.
 */
final class AddonApiTest {
	/** A colour no block uses, to find the waypoint's pin in the world by. */
	private static final int PIN = 0xFF00FF;
	private AddonApiTest() {
	}

	/** Before any world: the addons were found, and the failing ones switched off and cleaned up. */
	static void titleScreen(ClientGameTestContext context) {
		found(context);
		if (context.computeOnClient(mc -> TestAddon.fromReady() == null || !TestAddon.fromReady().isOpen() || !HudHides.minimapHidden())) {
			throw new AssertionError("The handle the test addon took in onReady isn't holding at the title screen");
		}
		failures(context);
		notAnAddon(context);
	}

	/**
	 * In the first world, with the map warmed up, and in the Nether and back: the handle onReady
	 * took at the title screen still hides the minimap. It stays open for the next world.
	 */
	static void firstWorld(ClientGameTestContext context, TestSingleplayerContext world) {
		context.waitTicks(2);
		context.takeScreenshot("addon-api-00-first-world");
		stillHidden(context, "in the first world");
		BlockPos home = context.computeOnClient(mc -> mc.player.blockPosition());
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 120 0");
		context.waitFor(mc -> mc.level.dimension() == Level.NETHER && MapSession.get() != null, 2400);
		context.waitTicks(40);
		context.takeScreenshot("addon-api-00-nether");
		stillHidden(context, "after going to the Nether");
		world.getServer().runCommand("execute in minecraft:overworld run tp @a " + home.getX() + " " + home.getY() + " " + home.getZ());
		context.waitFor(mc -> mc.level.dimension() == Level.OVERWORLD && MapSession.get() != null, 2400);
		DioramaClientTest.settle(context, 40);
		stillHidden(context, "back in the Overworld");
		Diorama.LOGGER.info("addon-api: the handle onReady took at the title screen held into the first world, the Nether and back");
	}

	/** The handle onReady took is open and the minimap isn't drawn. */
	private static void stillHidden(ClientGameTestContext context, String when) {
		boolean open = context.computeOnClient(mc -> TestAddon.fromReady().isOpen());
		boolean hidden = context.computeOnClient(mc -> HudHides.minimapHidden());
		boolean drawn = context.computeOnClient(mc -> MinimapHud.shown() != null);
		if (!open || !hidden || drawn) {
			throw new AssertionError("The handle onReady took at the title screen, " + when + ": " + (open ? "open" : "closed")
				+ ", minimap " + (hidden ? "hidden by addons" : "not hidden by addons") + " and " + (drawn ? "drawn" : "not drawn"));
		}
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
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
	}

	/** At the title screen after leaving the first world: leaving let go of nothing. */
	static void left(ClientGameTestContext context) {
		context.waitTicks(2);
		boolean open = context.computeOnClient(mc -> TestAddon.fromReady().isOpen());
		boolean hidden = context.computeOnClient(mc -> HudHides.minimapHidden());
		if (!open || !hidden) {
			throw new AssertionError("After leaving the world, the handle onReady took is " + (open ? "open" : "closed")
				+ " and the minimap " + (hidden ? "hidden by addons" : "not hidden by addons"));
		}
		Diorama.LOGGER.info("addon-api: leaving the world kept the handle onReady took");
	}

	/**
	 * In the world opened again: the handle onReady took still hides the minimap, and closing it
	 * brings the minimap back, so nothing else (the failed addons' handles included) hides it.
	 * Then the rest of the scenario ({@link #run}).
	 */
	static void reopened(ClientGameTestContext context, TestSingleplayerContext world) {
		context.setScreen(() -> null);
		context.waitFor(mc -> MapSession.get() != null, 2400);
		DioramaClientTest.settle(context, 40);
		context.takeScreenshot("addon-api-00-second-world");
		stillHidden(context, "in the world opened again");
		context.runOnClient(mc -> TestAddon.fromReady().close());
		context.waitTicks(2);
		context.takeScreenshot("addon-api-00-closed");
		if (context.computeOnClient(mc -> TestAddon.fromReady().isOpen() || HudHides.minimapHidden() || MinimapHud.shown() == null)) {
			throw new AssertionError("The minimap isn't drawn after closing the handle onReady took");
		}
		Diorama.LOGGER.info("addon-api: the handle onReady took held into the world opened again, and closing it brought the minimap back");
		run(context, world);
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

	/**
	 * The addons that fail in onReady, at the title screen: each switched off once, with one log
	 * line, and the handle it took first closed. That they all got as far as hiding, beside the good
	 * addon's onReady, says one failing didn't stop the others loading.
	 */
	private static void failures(ClientGameTestContext context) {
		if (context.computeOnClient(mc -> BrokenAddon.held() == null || LinkageAddon.held() == null || AssertionAddon.held() == null || UnprintableAddon.held() == null
			|| OverflowAddon.held() == null || RecursiveAddon.held() == null)) {
			throw new AssertionError("A failing test addon never got as far as hiding the minimap: is it installed?");
		}
		boolean brokenHeld = context.computeOnClient(mc -> BrokenAddon.held().isOpen());
		boolean linkageHeld = context.computeOnClient(mc -> LinkageAddon.held().isOpen());
		boolean assertionHeld = context.computeOnClient(mc -> AssertionAddon.held().isOpen());
		boolean unprintableHeld = context.computeOnClient(mc -> UnprintableAddon.held().isOpen());
		boolean overflowHeld = context.computeOnClient(mc -> OverflowAddon.held().isOpen());
		boolean recursiveHeld = context.computeOnClient(mc -> RecursiveAddon.held().isOpen());
		if (brokenHeld || linkageHeld || assertionHeld || unprintableHeld || overflowHeld || recursiveHeld) {
			throw new AssertionError("A failed addon's handle is still open at the title screen: broken " + brokenHeld + ", linkage " + linkageHeld
				+ ", assertion " + assertionHeld + ", unprintable " + unprintableHeld + ", overflow " + overflowHeld + ", recursive " + recursiveHeld);
		}
		// Switched off, its later calls do nothing.
		Handle late = context.computeOnClient(mc -> BrokenAddon.api().hud().hide(EnumSet.of(Hud.Part.MINIMAP)));
		if (context.computeOnClient(mc -> late.isOpen())) {
			throw new AssertionError("The broken addon hid the minimap again after it was switched off");
		}
		String log = log(context);
		long broken = lines(log, "Diorama addon diorama_test_addon_broken failed");
		long linkage = lines(log, "Diorama addon diorama_test_addon_linkage failed");
		long assertion = lines(log, "Diorama addon diorama_test_addon_assertion failed");
		long unprintable = lines(log, "Diorama addon diorama_test_addon_unprintable failed");
		long overflow = lines(log, "Diorama addon diorama_test_addon_overflow failed");
		long recursive = lines(log, "Diorama addon diorama_test_addon_recursive failed");
		boolean linkageCause = log.contains("NoClassDefFoundError: dev/diorama/testaddon/linkage/MissingClass");
		boolean assertionCause = log.contains("AssertionError: Diorama test addon: an assertion failed on purpose");
		boolean unprintableCause = log.contains("UnprintableAddon$Unreadable (its stack trace couldn't be printed: java.lang.IllegalStateException)");
		boolean overflowCause = log.contains("Caused by: java.lang.StackOverflowError");
		boolean recursiveCause = log.contains("RecursiveAddon$Endless (its stack trace couldn't be printed: java.lang.StackOverflowError)");
		if (broken != 1 || linkage != 1 || assertion != 1 || unprintable != 1 || overflow != 1 || recursive != 1
			|| !linkageCause || !assertionCause || !unprintableCause || !overflowCause || !recursiveCause) {
			throw new AssertionError("Log lines for the failed addons: broken " + broken + ", linkage " + linkage + ", assertion " + assertion
				+ ", unprintable " + unprintable + ", overflow " + overflow + ", recursive " + recursive + "; causes named: linkage " + linkageCause
				+ ", assertion " + assertionCause + ", unprintable " + unprintableCause + ", overflow " + overflowCause + ", recursive " + recursiveCause);
		}
		Diorama.LOGGER.info("addon-api: the six failing addons were each switched off with one log line, and their handles closed");
	}

	/**
	 * The class marked @DioramaEntrypoint that isn't an addon: on NeoForge and Forge turned down
	 * with a log line, without its static initialiser or constructor running. Fabric only reads
	 * entrypoints, and the test addon declares none for it.
	 */
	private static void notAnAddon(ClientGameTestContext context) {
		boolean initialised = System.getProperty("diorama.testaddon.notaddon.initialised") != null;
		boolean made = System.getProperty("diorama.testaddon.notaddon.made") != null;
		String log = log(context);
		// The ERROR line names the good addon's mod ID, which the jar shares; the trace under it says why.
		long turnedDown = lines(log, "Diorama addon diorama_test_addon failed in its entrypoint");
		boolean why = log.contains("dev.diorama.testaddon.NotAnAddon doesn't implement dev.diorama.api.DioramaAddon");
		//? if fabric {
		long expected = 0;
		//?} else {
		/*long expected = 1;
		*///?}
		if (initialised || made || turnedDown != expected || why != (expected == 1)) {
			throw new AssertionError("The class marked by mistake: static initialiser ran " + initialised + ", constructor ran " + made
				+ ", turned down in " + turnedDown + " log lines (expected " + expected + "), reason logged " + why);
		}
		Diorama.LOGGER.info("addon-api: the class marked by mistake was turned down in {} log lines without running", turnedDown);
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
		sameFile(config, before, "with a handle on the minimap");
		sameSettings(context, minimapSetting, waypointsSetting, "with a handle on the minimap");

		Handle second = context.computeOnClient(mc -> TestAddon.hide(Hud.Part.MINIMAP, Hud.Part.WORLD_WAYPOINTS));
		context.waitTicks(2);
		BufferedImage both = read(context.takeScreenshot("addon-api-03-both-hidden"));
		expect(context, false, false, "with two handles");
		if (has(world(both), PIN)) {
			throw new AssertionError("The waypoint is still drawn in the world with a handle hiding waypoints in the world");
		}
		sameFile(config, before, "with two handles");
		sameSettings(context, minimapSetting, waypointsSetting, "with two handles");

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

		sameFile(config, before, "after both handles closed");
		sameSettings(context, minimapSetting, waypointsSetting, "after both handles closed");
		Diorama.LOGGER.info("addon-api: hidden and shown again, two handles stacked, config.json byte for byte the same with handles held and after ({} bytes)", before.length);
	}

	/** config.json on disk is byte for byte what it was before any handle. */
	private static void sameFile(Path config, byte[] before, String when) {
		byte[] now = bytes(config);
		if (!Arrays.equals(before, now)) {
			throw new AssertionError("config.json changed " + when + ": " + before.length + " bytes before any handle, " + now.length + " now");
		}
	}

	/** The minimap and waypointsInWorld settings in memory are what they were before any handle. */
	private static void sameSettings(ClientGameTestContext context, boolean minimap, boolean waypoints, String when) {
		boolean minimapNow = context.computeOnClient(mc -> Settings.get().minimap);
		boolean waypointsNow = context.computeOnClient(mc -> Settings.get().waypointsInWorld);
		if (minimapNow != minimap || waypointsNow != waypoints) {
			throw new AssertionError("The settings in memory changed " + when + ": minimap " + minimap + " before any handle, " + minimapNow
				+ " now; waypointsInWorld " + waypoints + " before, " + waypointsNow + " now");
		}
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

	/**
	 * hide() on a Hud got on the client thread, called from another, as an addon that keeps the Hud
	 * would, throws; and so does hiding nothing.
	 */
	private static void threads(ClientGameTestContext context) {
		Hud hud = context.computeOnClient(mc -> TestAddon.api().hud());
		String here = Thread.currentThread().getName();
		if (here.equals(context.computeOnClient(mc -> Thread.currentThread().getName()))) {
			throw new AssertionError("The test runs on the client thread, so it can't try another");
		}
		try {
			hud.hide(EnumSet.of(Hud.Part.MINIMAP));
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
			// The player switched the addon off in the Addons tab: its handle stays open and hides nothing.
			context.runOnClient(mc -> {
				Settings.get().addonHidesOff.add("diorama_test_addon");
				Settings.get().apply();
			});
			long switchedOff = AllocProbe.measure(context, "one handle on MINIMAP, its addon switched off", 200);
			context.runOnClient(mc -> {
				Settings.get().addonHidesOff.remove("diorama_test_addon");
				Settings.get().apply();
			});
			context.runOnClient(mc -> minimap.close());
			long again = AllocProbe.measure(context, "no handle again", 200);
			if (hidden != 0) {
				throw new AssertionError("The minimap's draw allocated a median " + hidden + " bytes a frame while hidden");
			}
			Diorama.LOGGER.info("addon-api alloc: medians a frame: none {}, other part {}, hidden {}, switched off {}, none again {}", none, other, hidden, switchedOff, again);
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

	private static String log(ClientGameTestContext context) {
		return context.computeOnClient(mc -> text(mc.gameDirectory.toPath().resolve("logs/latest.log")));
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
