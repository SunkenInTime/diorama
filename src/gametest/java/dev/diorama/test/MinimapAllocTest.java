package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MinimapHud;
import dev.diorama.Settings;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * What the minimap's HUD element allocates on the render thread each frame ({@code minimap-alloc},
 * opt-in, a measurement rather than a check). Two probes go round the minimap's HUD element and
 * read the thread's allocation counter, with the map settled and the world paused so every frame
 * draws the same minimap. Each setting logs the median, 99th percentile, largest and mean bytes a
 * frame over {@link #FRAMES} frames, and takes a screenshot. The player stands at 0, 0 whatever the
 * world's spawn, so two builds' screenshots show the same ground.
 *
 * <p>On Fabric from 1.21.6 the probes are HUD elements either side of the minimap's; before 1.21.6
 * they're HUD callbacks in a phase before Diorama's and one added after it; on NeoForge the GUI
 * layer events round its layer. Not on Forge. The frames where the fog mask is rebuilt (when a tile's
 * coverage changes, every couple of seconds here) are the large ones, a few hundred KB.
 */
final class MinimapAllocTest {
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static final int FRAMES = 400;
	/** Each frame's bytes, kept in place so counting them allocates nothing; room for {@link FogMaskBuildTest}'s longer counts too. */
	private static final long[] SAMPLES = new long[8192];
	private static boolean registered;
	private static boolean counting;
	private static long before = -1;
	private static int frames;
	/** Bytes a test allocated inside this frame's minimap for its own checks, which aren't the minimap's: taken off. Render thread. */
	static long excluded;

	private MinimapAllocTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		if (!register(context)) {
			Diorama.LOGGER.info("minimap-alloc: no probe on this loader");
			return;
		}
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("difficulty normal");
		world.getServer().runCommand("kill @e[type=!player]");
		context.waitFor(mc -> mc.player.onGround(), 600);
		int groundY = context.computeOnClient(mc -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0));
		BlockPos at = new BlockPos(0, groundY, 0);
		world.getServer().runCommand("tp @a 0.5 " + groundY + " 0.5 30 10");
		context.waitFor(mc -> mc.player.onGround() && mc.player.blockPosition().equals(at), 600);
		// The commands' chat lines fade first, so they aren't counted.
		context.waitTicks(220);
		DioramaClientTest.settle(context, 20);

		row(context, "off", s -> s.minimap = false);
		row(context, "default", s -> { });
		row(context, "round", s -> s.minimapShape = Settings.Shape.ROUND);
		row(context, "turning", s -> s.minimapLockNorth = false);
		row(context, "round turning", s -> {
			s.minimapShape = Settings.Shape.ROUND;
			s.minimapLockNorth = false;
		});
		row(context, "radar off", s -> s.radarOnMinimap = false);
		row(context, "no compass, no lines", s -> {
			s.compass = false;
			s.coordinates = false;
			s.biome = false;
		});
		row(context, "game time", s -> s.gameTime = Settings.GameTime.DAY_24H);
		row(context, "enlarged 200", s -> s.minimapSize = 192);

		// 40 mobs of every radar kind round the player, still.
		String[] types = {"pig", "sheep", "husk", "spider", "villager", "wolf", "cow", "skeleton"};
		for (int i = 0; i < 40; i++) {
			double angle = i * Math.PI * 2 / 40;
			int radius = 6 + i % 5 * 4;
			int x = (int) Math.round(Math.cos(angle) * radius);
			int z = (int) Math.round(Math.sin(angle) * radius);
			world.getServer().runCommand("summon " + types[i % types.length] + " " + x + " " + (groundY + 1) + " " + z + " {NoAI:1b,PersistenceRequired:1b}");
		}
		context.waitTicks(40);
		row(context, "40 mobs", s -> { });
		row(context, "40 mobs, turning", s -> s.minimapLockNorth = false);
		row(context, "40 mobs, radar off", s -> s.radarOnMinimap = false);
		world.getServer().runCommand("kill @e[type=!player]");
		context.waitTicks(40);

		// Ten near enough to stand on the minimap, fifty far off on its edge.
		List<Waypoint> added = context.computeOnClient(mc -> {
			List<Waypoint> list = new ArrayList<>();
			Random random = new Random(151);
			for (int i = 0; i < 60; i++) {
				double distance = i < 10 ? 20 + random.nextDouble() * 60 : 300 + random.nextDouble() * 1500;
				double angle = random.nextDouble() * Math.PI * 2;
				int x = (int) Math.round(Math.cos(angle) * distance);
				int z = (int) Math.round(Math.sin(angle) * distance);
				int y = distance < 100 ? mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : groundY;
				list.add(new Waypoint("Point " + i, x, y, z, "minecraft:overworld", Waypoint.PALETTE[i % Waypoint.PALETTE.length]));
			}
			WaypointStore.get().addAll(list);
			return list;
		});
		row(context, "60 waypoints", s -> { });
		row(context, "60 waypoints, turning", s -> s.minimapLockNorth = false);
		context.runOnClient(mc -> added.forEach(WaypointStore.get()::remove));

		// A sealed room underground, with and without a torch in hand: the cave slice and held lights.
		int caveY = groundY - 30;
		world.getServer().runCommand("fill -3 " + (caveY - 1) + " -3 3 " + (caveY + 4) + " 3 stone");
		world.getServer().runCommand("fill -2 " + caveY + " -2 2 " + (caveY + 2) + " 2 air");
		world.getServer().runCommand("tp @a 0.5 " + caveY + " 0.5 30 10");
		context.waitTicks(100);
		DioramaClientTest.settle(context, 20);
		row(context, "cave", s -> { });
		world.getServer().runCommand("item replace entity @a weapon.mainhand with torch");
		context.waitTicks(40);
		DioramaClientTest.settle(context, 20);
		row(context, "cave, torch held", s -> { });
		world.getServer().runCommand("item replace entity @a weapon.mainhand with air");
		world.getServer().runCommand("tp @a 0.5 " + groundY + " 0.5 30 10");
		context.waitTicks(100);
		DioramaClientTest.settle(context, 20);
	}

	/** Measures one setting, changed from what's set by {@code change}, then puts it back. */
	private static void row(ClientGameTestContext context, String name, Consumer<Settings> change) {
		Settings saved = context.computeOnClient(mc -> copy(Settings.get(), new Settings()));
		context.runOnClient(mc -> change.accept(Settings.get()));
		context.waitTicks(20);
		try (WorldPause paused = WorldPause.pause(context)) {
			context.waitTicks(20);
			// The first frames after a change make what later ones reuse.
			measure(context, 100);
			long[] counted = measure(context, FRAMES);
			long[] sorted = counted.clone();
			Arrays.sort(sorted);
			long sum = 0;
			for (long bytes : counted) {
				sum += bytes;
			}
			int[] box = context.computeOnClient(mc -> MinimapHud.shown());
			Diorama.LOGGER.info("minimap-alloc | {} | {} frames | median {} | p99 {} | max {} | mean {} bytes a frame | minimap at {}",
				name, counted.length, sorted[sorted.length / 2], sorted[sorted.length * 99 / 100], sorted[sorted.length - 1],
				String.format(Locale.ROOT, "%.0f", sum / (double) counted.length), box == null ? "none" : Arrays.toString(box));
			context.takeScreenshot("minimap-alloc-" + name.replace(", ", "-").replace(' ', '-'));
		}
		context.runOnClient(mc -> copy(saved, Settings.get()));
	}

	/** Bytes each of the next {@code count} frames' minimap allocated. */
	private static long[] measure(ClientGameTestContext context, int count) {
		context.runOnClient(mc -> {
			frames = 0;
			before = -1;
			counting = true;
		});
		context.waitFor(mc -> frames >= count, 20 * 600);
		return context.computeOnClient(mc -> {
			counting = false;
			return Arrays.copyOf(SAMPLES, count);
		});
	}

	/** From the next frame on, counts what each frame's minimap allocates, for {@link #stopCounting}. Render thread. */
	static void startCounting() {
		frames = 0;
		before = -1;
		counting = true;
	}

	/** Each frame's bytes since {@link #startCounting}, at most as many as there's room for. Render thread. */
	static long[] stopCounting() {
		counting = false;
		return Arrays.copyOf(SAMPLES, Math.min(frames, SAMPLES.length));
	}

	private static void start() {
		if (counting) {
			excluded = 0;
			before = THREADS.getCurrentThreadAllocatedBytes();
		}
	}

	private static void end() {
		if (counting && before >= 0) {
			long bytes = THREADS.getCurrentThreadAllocatedBytes() - before - excluded;
			if (frames < SAMPLES.length) {
				SAMPLES[frames] = bytes;
			}
			frames++;
			before = -1;
		}
	}

	/** The settings the rows change, from one to the other; returns {@code to}. */
	private static Settings copy(Settings from, Settings to) {
		to.minimap = from.minimap;
		to.minimapShape = from.minimapShape;
		to.minimapLockNorth = from.minimapLockNorth;
		to.radarOnMinimap = from.radarOnMinimap;
		to.compass = from.compass;
		to.coordinates = from.coordinates;
		to.biome = from.biome;
		to.gameTime = from.gameTime;
		to.minimapSize = from.minimapSize;
		return to;
	}

	/** Puts the two probes round the minimap's HUD element, once. False where there's no way to. */
	static boolean register(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			if (registered) {
				return true;
			}
			//? if >=1.21.6 && fabric {
			net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementBefore(Diorama.id("minimap"), Diorama.id("test_alloc_before"), (graphics, delta) -> start());
			net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementAfter(Diorama.id("minimap"), Diorama.id("test_alloc_after"), (graphics, delta) -> end());
			registered = true;
			//?} else if fabric {
			/*// Diorama's callback is in the default phase; this phase goes before it, and a callback added now comes after it.
			var event = net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT;
			var first = Diorama.id("test_alloc_before");
			event.addPhaseOrdering(first, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
			event.register(first, (graphics, delta) -> start());
			event.register((graphics, delta) -> end());
			registered = true;
			*///?} else if neoforge {
			/*var minimap = Diorama.id("minimap");
			net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderGuiLayerEvent.Pre.class, event -> {
				if (event.getName().equals(minimap)) {
					start();
				}
			});
			net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderGuiLayerEvent.Post.class, event -> {
				if (event.getName().equals(minimap)) {
					end();
				}
			});
			registered = true;
			*///?}
			return registered;
		});
	}
}
