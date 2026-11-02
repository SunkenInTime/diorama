package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.DioramaClient;
import dev.diorama.Settings;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * How far the minimap reaches, for reading off pictures (opt-in: {@code minimap-zoom}). A ruler in
 * the sky with a tick every 32 blocks is photographed at every zoom step, and with Xaero's Minimap
 * loaded too, the same picture has Xaero's map beside ours, at its zoom 1 and with Further Zoomout
 * on. Then the furthest step round, enlarged, in a cave and flying over new land.
 */
final class MinimapZoomTest {
	private static final int Y = 220;
	private static final int REACH = 160;
	/** Blocks a tick: three times a sprint, slow enough for the test machine to generate the world. */
	private static final double FLIGHT_SPEED = 0.75;

	private MinimapZoomTest() {
	}

	static void ruler(ClientGameTestContext context, TestSingleplayerContext world) {
		int[] at = context.computeOnClient(mc -> new int[] {mc.player.blockPosition().getX() >> 4 << 4, mc.player.blockPosition().getZ() >> 4 << 4});
		int x = at[0];
		int z = at[1];
		// Grey lines both ways, white ticks every 32 blocks, red every 64.
		world.getServer().runCommand("fill " + (x - REACH) + " " + Y + " " + (z - 1) + " " + (x + REACH) + " " + Y + " " + (z + 1) + " gray_concrete");
		world.getServer().runCommand("fill " + (x - 1) + " " + Y + " " + (z - REACH) + " " + (x + 1) + " " + Y + " " + (z + REACH) + " gray_concrete");
		for (int d = -REACH; d <= REACH; d += 32) {
			String block = d % 64 == 0 ? "red_concrete" : "white_concrete";
			world.getServer().runCommand("fill " + (x + d) + " " + Y + " " + (z - 6) + " " + (x + d + 1) + " " + Y + " " + (z + 6) + " " + block);
			world.getServer().runCommand("fill " + (x - 6) + " " + Y + " " + (z + d) + " " + (x + 6) + " " + Y + " " + (z + d + 1) + " " + block);
		}
		world.getServer().runCommand("tp @a " + x + " " + (Y + 1) + " " + z + " 0 0");
		DioramaClientTest.settle(context, 100);
		context.runOnClient(mc -> Diorama.LOGGER.info("Ruler window {}x{}, GUI scale {}", mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.getWindow().getGuiScale()));

		for (int i = 0; i < Settings.ZOOMS.length; i++) {
			int step = i;
			context.runOnClient(mc -> Settings.get().zoomStep(step));
			DioramaClientTest.settle(context, 60);
			context.takeScreenshot("minimap-zoom-" + step + "-" + Settings.ZOOMS[step]);
		}
		context.runOnClient(mc -> Settings.get().zoomStep(0));
		if (context.computeOnClient(mc -> furtherZoomOut(true))) {
			DioramaClientTest.settle(context, 100);
			context.takeScreenshot("minimap-zoom-xaero-further");
			context.runOnClient(mc -> furtherZoomOut(false));
		}

		context.runOnClient(mc -> {
			Settings.get().zoomStep(0);
			Settings.get().minimapShape = Settings.Shape.ROUND;
		});
		DioramaClientTest.settle(context, 40);
		context.takeScreenshot("minimap-zoom-round");
		context.runOnClient(mc -> {
			Settings.get().minimapShape = Settings.Shape.SQUARE;
			DioramaClient.ENLARGE_MINIMAP.setKey(InputConstants.getKey("key.keyboard.z"));
			KeyMapping.resetMapping();
		});
		context.getInput().holdKey(DioramaClient.ENLARGE_MINIMAP);
		context.waitTicks(2);
		DioramaClientTest.settle(context, 40);
		context.takeScreenshot("minimap-zoom-enlarged-200");
		context.runOnClient(mc -> Settings.get().minimapEnlarged = 400);
		DioramaClientTest.settle(context, 40);
		context.takeScreenshot("minimap-zoom-enlarged-400");
		context.getInput().releaseKey(DioramaClient.ENLARGE_MINIMAP);
		context.runOnClient(mc -> {
			Settings.get().minimapEnlarged = 200;
			DioramaClient.ENLARGE_MINIMAP.setKey(InputConstants.UNKNOWN);
			KeyMapping.resetMapping();
		});

		world.getServer().runCommand("fill " + (x - REACH) + " " + Y + " " + (z - 6) + " " + (x + REACH + 1) + " " + Y + " " + (z + 6) + " air");
		world.getServer().runCommand("fill " + (x - 6) + " " + Y + " " + (z - REACH) + " " + (x + 6) + " " + Y + " " + (z + REACH + 1) + " air");

		// A dug room far below the ground: the map shows a cave slice, which has no overviews.
		world.getServer().runCommand("fill " + (x - 8) + " 10 " + (z - 8) + " " + (x + 8) + " 16 " + (z + 8) + " stone");
		world.getServer().runCommand("fill " + (x - 7) + " 11 " + (z - 7) + " " + (x + 7) + " 14 " + (z + 7) + " air");
		world.getServer().runCommand("tp @a " + x + " 11 " + z);
		DioramaClientTest.settle(context, 60);
		context.takeScreenshot("minimap-zoom-cave");

		flight(context, world, x, z);
		context.runOnClient(mc -> {
			Settings.get().minimapPixelsPerBlock = 2;
			Settings.get().save();
		});
	}

	/** Flies east over land nobody has seen, as fast as an elytra, photographing the furthest step. */
	private static void flight(ClientGameTestContext context, TestSingleplayerContext world, int x, int z) {
		int y = 120;
		world.getServer().runCommand("gamerule send_command_feedback false");
		for (int tick = 0; tick <= 800; tick++) {
			world.getServer().runCommand("tp @a " + (x + tick * FLIGHT_SPEED) + " " + y + " " + z + " 90 0");
			context.waitTick();
			if (tick % 200 == 0) {
				context.takeScreenshot("minimap-zoom-flight-" + tick);
			}
		}
		world.getServer().runCommand("gamerule send_command_feedback true");
	}

	/** Sets Xaero's Further Zoomout, if Xaero's Minimap is here. */
	private static boolean furtherZoomOut(boolean on) {
		try {
			Class<?> options = Class.forName("xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions");
			Object option = options.getField("FURTHER_ZOOMOUT").get(null);
			Object hud = Class.forName("xaero.common.HudMod").getField("INSTANCE").get(null);
			Object configs = hud.getClass().getMethod("getHudConfigs").invoke(hud);
			Object manager = configs.getClass().getMethod("getClientConfigManager").invoke(configs);
			Object profile = manager.getClass().getMethod("getCurrentProfile").invoke(manager);
			for (java.lang.reflect.Method set : profile.getClass().getMethods()) {
				if (set.getName().equals("set") && set.getParameterCount() == 2) {
					set.invoke(profile, option, on);
					Diorama.LOGGER.info("Xaero's Further Zoomout set to {}", on);
					return true;
				}
			}
			throw new AssertionError("No set method on " + profile.getClass());
		} catch (ClassNotFoundException e) {
			return false;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}
}
