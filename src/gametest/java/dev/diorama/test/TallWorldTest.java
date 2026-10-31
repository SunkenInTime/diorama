package dev.diorama.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.MapPick;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapTile;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A world made taller by a data pack, with a station built at y 2000 (#208). The map draws the
 * station 1,600 blocks from where it stands, and the rays of its tile columns start up there, in
 * chunks the map has never seen: the ground in those columns stays under the fog unless rays pass
 * through such chunks.
 */
final class TallWorldTest {
	private static final int STATION_Y = 2000;
	/** The station covers these four by four chunks whole. */
	private static final int WEST = 976;
	private static final int EAST = 1039;
	private static final int NORTH = -32;
	private static final int SOUTH = 31;
	private static final int X = 1008;
	/** Ground in the station's tile columns, outside its chunks, seen from where the player first stands. */
	private static final int BESIDE_Z = -300;

	private TallWorldTest() {
	}

	static void run(ClientGameTestContext context) {
		TestWorldSave save;
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			save = world.getWorldSave();
		}
		writePack(save.getSaveDirectory());
		try (TestSingleplayerContext world = save.open()) {
			if (context.computeOnClient(mc -> mc.level.getMaxY()) != 2031) {
				throw new AssertionError("The data pack didn't make the world taller: top " + context.computeOnClient(mc -> mc.level.getMaxY()));
			}
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 1200);
			// Up before the client has any of its chunks, as on a server someone else built on.
			world.getServer().runCommand("forceload add " + WEST + " " + NORTH + " " + EAST + " " + SOUTH);
			int ground = groundHeight(context, world);
			world.getServer().runCommand("fill " + WEST + " " + STATION_Y + " " + NORTH + " " + EAST + " " + STATION_Y + " " + SOUTH + " minecraft:white_concrete");
			context.waitTicks(10);
			world.getServer().runCommand("forceload remove all");

			// Walking up to it from the north: the tiles are baked before the station's chunks arrive.
			arriveAt(context, world, BESIDE_Z - 40, ground, BESIDE_Z);
			arriveAt(context, world, -40, ground, 0);
			assertGroundShows(context, BESIDE_Z, ground, "north of the station, in its tile columns");
			assertGroundShows(context, 0, ground, "under the station");

			BlockPos top = context.computeOnClient(mc ->
				MapPick.standingSpot(MapSession.get().map(), BlockLooks.get(), X + 0.5, Projection.v(STATION_Y + 1, 0.5), TileKey.SURFACE, false));
			if (top == null || top.getY() != STATION_Y + 1) {
				throw new AssertionError("Expected the map to show the station's top at y " + (STATION_Y + 1) + ", found " + top);
			}
			context.setScreen(MapScreen::new);
			context.runOnClient(mc -> {
				if (mc.gui.screen() instanceof MapScreen screen) {
					screen.lookAt(X, Projection.v(STATION_Y, 0), 1.0, TileKey.SURFACE);
				}
			});
			settle(context, 40);
			context.takeScreenshot("tall-world-station");
			context.setScreen(() -> null);
		}
	}

	/** The overworld as the game defines it, 2,096 blocks tall, for the world's data packs folder. */
	private static void writePack(Path saveDirectory) {
		try (InputStream vanilla = Minecraft.class.getResourceAsStream("/data/minecraft/dimension_type/overworld.json")) {
			if (vanilla == null) {
				throw new AssertionError("The game has no overworld dimension type to copy");
			}
			JsonObject type = JsonParser.parseReader(new InputStreamReader(vanilla, StandardCharsets.UTF_8)).getAsJsonObject();
			type.addProperty("height", 2096);
			type.addProperty("logical_height", 2096);
			Path pack = saveDirectory.resolve("datapacks").resolve("tall");
			Path dimensionTypes = pack.resolve("data/minecraft/dimension_type");
			Files.createDirectories(dimensionTypes);
			Files.writeString(dimensionTypes.resolve("overworld.json"), type.toString());
			// Formats wide enough for every version this test runs on.
			Files.writeString(pack.resolve("pack.mcmeta"),
				"{\"pack\":{\"description\":\"Tall\",\"pack_format\":15,\"supported_formats\":[15,1000],\"min_format\":15,\"max_format\":1000}}");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static int groundHeight(ClientGameTestContext context, TestSingleplayerContext world) {
		for (int i = 0; i < 600; i++) {
			if (world.getServer().computeOnServer(server -> server.overworld().hasChunk(X >> 4, 0))) {
				return world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, X, 0));
			}
			context.waitTick();
		}
		throw new AssertionError("The forced chunks didn't load");
	}

	/** Moves the player to {@code z} and waits until the map has the chunk at {@code seenZ} and has drawn the ground there. */
	private static void arriveAt(ClientGameTestContext context, TestSingleplayerContext world, int z, int ground, int seenZ) {
		world.getServer().runCommand("tp @a " + X + " " + (ground + 1) + " " + z);
		context.waitFor(mc -> MapSession.get().map().chunk(X >> 4, seenZ >> 4) != null, 1200);
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(X, Projection.v(ground, seenZ), 1.0, TileKey.SURFACE);
			}
		});
		settle(context, 40);
		context.setScreen(() -> null);
	}

	private static void assertGroundShows(ClientGameTestContext context, int z, int ground, String where) {
		double v = Projection.v(ground, z + 0.5);
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(X, v, 1.0, TileKey.SURFACE);
			}
		});
		settle(context, 40);
		context.takeScreenshot("tall-world-ground-" + z);
		context.setScreen(() -> null);
		float known = context.computeOnClient(mc -> known(X + 0.5, v));
		if (known < 0.9F) {
			throw new AssertionError("The ground " + where + " is " + known + " known");
		}
		BlockPos spot = context.computeOnClient(mc -> MapPick.standingSpot(MapSession.get().map(), BlockLooks.get(), X + 0.5, v, TileKey.SURFACE, false));
		if (spot == null || spot.getY() != ground || Math.abs(spot.getZ() - z) > 2) {
			throw new AssertionError("Expected right-clicking the ground " + where + " to find " + X + " " + ground + " " + z + ", found " + spot);
		}
	}

	/** How known the map is at a point, 0..1. */
	private static float known(double u, double v) {
		int tu = (int) Math.floor(u / Projection.TILE_UNITS);
		int tv = (int) Math.floor(v / Projection.TILE_UNITS);
		MapTile tile = MapSession.get().map().peek(new TileKey(TileKey.SURFACE, tu, tv));
		if (tile == null || !tile.hasCoverage()) {
			return 0F;
		}
		double cell = (double) Projection.TILE_UNITS / Projection.COVERAGE_CELLS;
		return tile.coverage((int) ((u - tu * Projection.TILE_UNITS) / cell), (int) ((v - tv * Projection.TILE_UNITS) / cell));
	}

	private static void settle(ClientGameTestContext context, int minTicks) {
		context.waitTicks(minTicks);
		try (WorldPause paused = WorldPause.pause(context)) {
			int quiet = 0;
			for (int i = 0; i < 1200 && quiet < 30; i++) {
				context.waitTick();
				boolean baking = context.computeOnClient(mc -> MapSession.get() != null && MapSession.get().map().bakingCount() > 0);
				quiet = baking ? 0 : quiet + 1;
			}
		}
	}
}
