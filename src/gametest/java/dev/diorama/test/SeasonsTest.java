package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.biome.Biome;

/**
 * Grass and leaves coloured by a mod (opt-in: {@code seasons}, with Serene Seasons installed),
 * which swaps the game's grass and foliage colours for its own, by the season. In mid autumn the
 * map of land the player comes to must have the game's colours for it, the mod's, and not the
 * biome's own (#389). The map, and the game itself looking down at the same land.
 */
final class SeasonsTest {
	private SeasonsTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		int x = 2400;
		int y = 150;
		int z = 2400;
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("season set mid_autumn");
		// The season reaches the client before the land does: the map keeps the colours a chunk came with.
		context.waitTicks(60);
		world.getServer().runCommand("gamemode spectator @a");
		world.getServer().runCommand("tp @a " + x + " " + y + " " + z + " 180 90");
		context.waitFor(mc -> mc.player.blockPosition().getX() == x, 200);
		context.waitTicks(200);
		String seasonal = context.computeOnClient(mc -> {
			Biome biome = mc.level.getBiome(mc.player.blockPosition()).value();
			int game = BiomeColors.GRASS_COLOR_RESOLVER.getColor(biome, x, z) & 0xFFFFFF;
			int own = biome.getGrassColor(x, z) & 0xFFFFFF;
			return game == own ? "" : String.format("%06X in the game, %06X the biome's own", game, own);
		});
		if (seasonal.isEmpty()) {
			throw new AssertionError("DIORAMA_TESTS=seasons needs Serene Seasons installed, and autumn grass that isn't the biome's own colour");
		}
		dev.diorama.Diorama.LOGGER.info("seasons: grass under the player is {}", seasonal);

		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(x, Projection.v(70, z), 3.0, TileKey.SURFACE);
			}
		});
		DioramaClientTest.settle(context, 80);
		context.takeScreenshot("seasons-01-map");
		context.setScreen(() -> null);

		// The game itself, straight down, facing north as the map does.
		//? if >=26.2 {
		context.runOnClient(mc -> mc.gui.hud.toggle());
		//?} else {
		/*context.runOnClient(mc -> mc.options.hideGui = true);
		*///?}
		context.waitTicks(40);
		context.takeScreenshot("seasons-02-world");
		//? if >=26.2 {
		context.runOnClient(mc -> mc.gui.hud.toggle());
		//?} else {
		/*context.runOnClient(mc -> mc.options.hideGui = false);
		*///?}

		DioramaClientTest.assertTintsMatchGame(context);
		world.getServer().runCommand("gamemode creative @a");
	}
}
