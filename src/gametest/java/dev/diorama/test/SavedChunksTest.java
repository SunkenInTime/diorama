package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.render.SavedChunkImport;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The map filling in from the world's save ({@code saved-chunks}): chunks made on the server alone,
 * as a pre-generation tool makes them, show up without the client ever hearing of them; a place
 * the player has been isn't drawn again when the game saves it; and turning the setting off stops
 * the drawing.
 */
final class SavedChunksTest {
	/** Far from anything the other sections visit. */
	private static final int X = 6000;
	private static final int Z = 6000;
	private static final int CHUNKS = 4;
	private static final int PILLAR = 30;

	private SavedChunksTest() {
	}

	/** Returns the ground's height over the far chunks, for {@link #reopened}. */
	static int run(ClientGameTestContext context, TestSingleplayerContext world) {
		// A section before this one may have turned the setting back on, or moved the player to
		// another level, and the fill only starts on the session's next tick.
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().savedChunks() != null, 200);
		int ground = farChunksShowUp(context, world);
		placesPlayedInAreNotDrawnAgain(context, world);
		settingStopsIt(context);
		return ground;
	}

	private static int farChunksShowUp(ClientGameTestContext context, TestSingleplayerContext world) {
		int before = drawn(context);
		int last = X + CHUNKS * 16 - 1;
		world.getServer().runCommand("forceload add " + X + " " + Z + " " + last + " " + last);
		for (int i = 0; !world.getServer().computeOnServer(SavedChunksTest::allForcedChunksLoaded); i++) {
			if (i > 2400) {
				throw new AssertionError("The forced chunks never loaded");
			}
			context.waitTick();
		}
		int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, X + 24, Z + 24));
		world.getServer().runCommand("forceload remove all");
		// Gone from the server, so nothing writes them after this and the save has its last word.
		for (int i = 0; world.getServer().computeOnServer(server -> server.overworld().getChunkSource().getChunkNow(X >> 4, Z >> 4) != null); i++) {
			if (i > 2400) {
				throw new AssertionError("The forced chunks never unloaded");
			}
			context.waitTick();
		}
		save(context, world);
		if (context.computeOnClient(mc -> MapSession.get().map().chunk(X >> 4, Z >> 4) != null)) {
			throw new AssertionError("The client knows the far chunks: this wouldn't show the save being read");
		}
		rescanAndWait(context);
		if (drawn(context) <= before) {
			throw new AssertionError("Nothing was drawn from the save");
		}
		TileKey tile = tileAt(X + 24, ground, Z + 24);
		int[] pixels = context.computeOnClient(mc -> MapSession.get().map().savedMap().read(tile));
		int shown = 0;
		if (pixels != null) {
			for (int pixel : pixels) {
				if ((pixel >>> 24) != 0) {
					shown++;
				}
			}
		}
		if (shown == 0) {
			throw new AssertionError("Tile " + tile + " over the far chunks shows nothing");
		}
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(X + 24, Projection.v(ground, Z + 24), 2, TileKey.SURFACE);
			}
		});
		context.waitTicks(40);
		context.takeScreenshot("saved-chunks-far");
		// The tooltip over them names their biome, read from the save: the client has none for a
		// chunk it hasn't loaded, and its stand-in for one is all Plains (#262).
		hoverMiddle(context);
		BlockPos far = new BlockPos(X + 24, ground, Z + 24);
		context.waitFor(mc -> ((MapScreen) mc.gui.screen()).biomeAt(far) != null, 200);
		context.waitTicks(5);
		context.takeScreenshot("saved-chunks-far-tooltip");
		sameBiomeAsTheServer(context, world, far, "over chunks the client hasn't loaded");
		java.util.List<String> tooltip = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).tooltipText());
		if (tooltip.size() != 2 || !tooltip.get(1).contains(" · ")) {
			throw new AssertionError("The tooltip over chunks the client hasn't loaded should have a biome and a distance on its second line: " + tooltip);
		}
		// Where the player is, it does.
		BlockPos near = context.computeOnClient(mc -> mc.player.blockPosition().offset(8, 0, 0));
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(near.getX(), Projection.v(near.getY(), near.getZ()), 2, TileKey.SURFACE));
		hoverMiddle(context);
		context.takeScreenshot("saved-chunks-near-tooltip");
		context.runOnClient(mc -> {
			if (((MapScreen) mc.gui.screen()).biomeAt(near) == null) {
				throw new AssertionError("The tooltip next to the player names no biome");
			}
		});
		// At the east edge of the loaded chunks, the last blocks of a loaded chunk can take their
		// biome from the unloaded one beyond it: that one comes from the save too, not from the
		// client's stand-in. The chunk's middle needs no save.
		BlockPos edge = context.computeOnClient(mc -> {
			int chunkX = near.getX() >> 4;
			int chunkZ = near.getZ() >> 4;
			while (mc.level.getChunkSource().hasChunk(chunkX + 1, chunkZ)) {
				chunkX++;
			}
			if (((MapScreen) mc.gui.screen()).biomeAt(new BlockPos(chunkX * 16 + 8, near.getY(), chunkZ * 16 + 8)) == null) {
				throw new AssertionError("The tooltip names no biome in the middle of the loaded chunk " + chunkX + "," + chunkZ);
			}
			return new BlockPos(chunkX * 16 + 15, near.getY(), chunkZ * 16 + 8);
		});
		// The server may not have written the chunk beyond the edge yet.
		save(context, world);
		context.waitFor(mc -> ((MapScreen) mc.gui.screen()).biomeAt(edge) != null, 400);
		sameBiomeAsTheServer(context, world, edge, "at the edge of the loaded chunks");
		context.getInput().setCursorPos(0, 0);
		context.setScreen(() -> null);
		return ground;
	}

	/** The map names the biome at {@code pos} that the server has there. */
	private static void sameBiomeAsTheServer(ClientGameTestContext context, TestSingleplayerContext world, BlockPos pos, String where) {
		String key = world.getServer().computeOnServer(server -> server.overworld().getBiome(pos).unwrapKey().orElseThrow().identifier().toLanguageKey("biome"));
		context.runOnClient(mc -> {
			Component biome = ((MapScreen) mc.gui.screen()).biomeAt(pos);
			String expected = Component.translatable(key).getString();
			if (biome == null || !biome.getString().equals(expected)) {
				throw new AssertionError("The tooltip " + where + " names " + (biome == null ? "no biome" : biome.getString()) + " at " + pos + ", where the server has " + expected);
			}
		});
	}

	/** The mouse on the middle of the map, long enough for its tooltip to show. */
	private static void hoverMiddle(ClientGameTestContext context) {
		double[] at = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			return new double[] {mc.gui.screen().width / 2.0 * scale, mc.gui.screen().height / 2.0 * scale};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(20);
	}

	private static void placesPlayedInAreNotDrawnAgain(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		// Up in the air, so nothing near it, wherever the player spawned, hides it from the map.
		int y = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, at.getX() + 3, at.getZ())) + PILLAR;
		TileKey tile = tileAt(at.getX() + 3, y + 1, at.getZ());
		int pixel = pixelAt(at.getX() + 3, y + 1, at.getZ());
		int before = savedPixel(context, tile, pixel);
		// Blocks the game hasn't saved yet, so the save still has what was there before.
		world.getServer().runOnServer(server -> {
			for (int i = 0; i <= PILLAR; i++) {
				server.overworld().setBlockAndUpdate(new BlockPos(at.getX() + 3, y - i, at.getZ()), Blocks.DIAMOND_BLOCK.defaultBlockState());
			}
		});
		context.waitFor(mc -> savedPixelNow(tile, pixel) != before, 1200);
		int diamond = savedPixel(context, tile, pixel);
		rescanAndWait(context);
		if (savedPixel(context, tile, pixel) != diamond) {
			throw new AssertionError("Filling in from the save replaced a block only the live world has yet");
		}
		save(context, world);
		rescanAndWait(context);
		if (savedPixel(context, tile, pixel) != diamond) {
			throw new AssertionError("The block went from the map once the game saved it");
		}
		// One chunk rather than a count of tiles: the chunks just past the view distance, which the
		// server made and never sent, are news to the map, and they reach into nearby tiles.
		if (context.computeOnClient(mc -> MapSession.get().savedChunks().drewChunk(at.getX() >> 4, at.getZ() >> 4))) {
			throw new AssertionError("The chunk the player stands in was drawn from the save");
		}
	}

	/** All of them: released half made, the ones still generating would save unfinished and never show. */
	private static boolean allForcedChunksLoaded(MinecraftServer server) {
		for (int dx = 0; dx < CHUNKS; dx++) {
			for (int dz = 0; dz < CHUNKS; dz++) {
				if (server.overworld().getChunkSource().getChunkNow((X >> 4) + dx, (Z >> 4) + dz) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static int savedPixel(ClientGameTestContext context, TileKey tile, int pixel) {
		return context.computeOnClient(mc -> savedPixelNow(tile, pixel));
	}

	private static int savedPixelNow(TileKey tile, int pixel) {
		int[] pixels = MapSession.get().map().savedMap().read(tile);
		return pixels == null ? 0 : pixels[pixel];
	}

	/** Where the top of a block lands in its tile's pixels. */
	private static int pixelAt(int x, int y, int z) {
		int size = Projection.TILE_PIXELS;
		int px = Math.floorMod((int) Math.floor((x + 0.5) * Projection.PIXELS_PER_UNIT), size);
		int py = Math.floorMod((int) Math.floor(Projection.v(y, z + 0.5) * Projection.PIXELS_PER_UNIT), size);
		return py * size + px;
	}

	/** Writes everything to the world's files, as the game does every few minutes. */
	private static void save(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> server.saveEverything(true, true, true));
		context.waitTicks(40);
	}

	private static void settingStopsIt(ClientGameTestContext context) {
		context.runOnClient(mc -> Settings.get().mapSavedChunks = false);
		context.waitFor(mc -> MapSession.get().savedChunks() == null, 200);
		context.runOnClient(mc -> Settings.get().mapSavedChunks = true);
		context.waitFor(mc -> MapSession.get().savedChunks() != null, 200);
	}

	private static TileKey tileAt(int x, int y, int z) {
		return new TileKey(TileKey.SURFACE, Math.floorDiv(x, Projection.TILE_UNITS), (int) Math.floor(Projection.v(y, z) / Projection.TILE_UNITS));
	}

	/** After the world is reopened: what was drawn stays drawn, so the far chunks aren't drawn again. */
	static void reopened(ClientGameTestContext context, int ground) {
		try (WorldPause paused = WorldPause.pause(context)) {
			context.waitFor(mc -> {
				SavedChunkImport job = MapSession.get() == null ? null : MapSession.get().savedChunks();
				return job != null && job.idle();
			}, 3600);
		}
		//? if >=1.21.5 {
		// Up to 1.21.4 reopening a world writes the far chunks again, each with a new save time, and a
		// chunk the save changed is drawn again, as it should be.
		TileKey tile = tileAt(X + 24, ground, Z + 24);
		if (context.computeOnClient(mc -> MapSession.get().savedChunks().drew(tile))) {
			throw new AssertionError("Reopening the world drew tile " + tile + " from the save again");
		}
		//?}
	}

	private static int drawn(ClientGameTestContext context) {
		return context.computeOnClient(mc -> MapSession.get().savedChunks().drawnCount());
	}

	/** Lists the save now, and waits until everything it holds is drawn, with the world quiet. */
	private static void rescanAndWait(ClientGameTestContext context) {
		try (WorldPause paused = WorldPause.pause(context)) {
			context.runOnClient(mc -> MapSession.get().savedChunks().rescanSoon());
			context.waitFor(mc -> MapSession.get().savedChunks().idle(), 3600);
		}
	}
}
