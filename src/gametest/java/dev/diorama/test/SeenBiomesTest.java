package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//? if >=1.21.4 && fabric {
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
//?}
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * On a server, the map's tooltip names the biome of land the player has left ({@code seen-biomes}):
 * the client has no chunks there and no world save to read, so the biome comes from what the map
 * kept when the player was there, in the same session and after joining again.
 */
final class SeenBiomesTest {
	/** Far enough that nothing of the first place stays loaded. */
	private static final int AWAY = 3000;

	private SeenBiomesTest() {
	}

	//? if >=1.21.4 && fabric {
	static void run(ClientGameTestContext context) {
		java.util.Properties properties = DioramaClientTest.serverProperties();
		properties.setProperty("view-distance", "6");
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("diorama-seen-biomes"))
			.createServer(properties)) {
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			// The middle of a 4 × 4 cell, where the biome kept is the one the server has for the cell.
			int x = 18;
			int z = 18;
			int y = surface(server, x, z) - 1;
			BlockPos spot = new BlockPos(x, y, z);
			String key = server.computeOnServer(s -> s.overworld().getNoiseBiome(x >> 2, y >> 2, z >> 2).unwrapKey().orElseThrow().identifier().toLanguageKey("biome"));
			try (TestDedicatedServerConnection connection = server.connect()) {
				teleport(server, x, z);
				waitForMap(context);
				teleport(server, x + AWAY, z);
				context.waitFor(mc -> !mc.level.getChunkSource().hasChunk(x >> 4, z >> 4), 600);
				waitForMap(context);
				named(context, spot, key, "seen-biomes-01-left", "after walking away");
			}
			try (TestDedicatedServerConnection connection = server.connect()) {
				waitForMap(context);
				if (context.computeOnClient(mc -> mc.level.getChunkSource().hasChunk(x >> 4, z >> 4))) {
					throw new AssertionError("The client has the first place's chunk after joining again far from it: this wouldn't show the saved biome");
				}
				named(context, spot, key, "seen-biomes-02-rejoined", "after joining again");
			}
		}
	}

	/** Hovering the spot on the map names the biome the server has there. */
	private static void named(ClientGameTestContext context, BlockPos spot, String key, String picture, String when) {
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(spot.getX() + 0.5, spot.getZ() + 0.5),
			Projection.v(spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5), 2, TileKey.SURFACE));
		DioramaClientTest.settle(context, 40);
		double[] at = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			return new double[] {mc.gui.screen().width / 2.0 * scale, mc.gui.screen().height / 2.0 * scale};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(20);
		context.takeScreenshot(picture);
		String expected = context.computeOnClient(mc -> Component.translatable(key).getString());
		Component biome = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).biomeAt(spot));
		List<String> tooltip = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).tooltipText());
		context.getInput().setCursorPos(0, 0);
		context.setScreen(() -> null);
		if (biome == null || !biome.getString().equals(expected)) {
			throw new AssertionError("The map names " + (biome == null ? "no biome" : biome.getString()) + " at " + spot + " " + when + ", where the server has " + expected);
		}
		if (tooltip.size() != 2 || !tooltip.get(1).contains(" · ")) {
			throw new AssertionError("The tooltip over land the player left should have a biome and a distance on its second line " + when + ": " + tooltip);
		}
	}

	private static void teleport(TestDedicatedServerContext server, int x, int z) {
		server.runCommand("tp @a " + x + " " + surface(server, x, z) + " " + z);
	}

	/** The first open y above a column, generating its chunk if it has to. */
	private static int surface(TestDedicatedServerContext server, int x, int z) {
		return server.computeOnServer(s -> s.overworld().getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1);
	}

	private static void waitForMap(ClientGameTestContext context) {
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 60, 2400);
		context.waitTicks(100);
		DioramaClientTest.settle(context, 20);
	}
	//?}
}
