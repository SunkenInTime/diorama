package dev.diorama.test;

import dev.diorama.Claims;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapTile;
import dev.diorama.render.SavedChunkImport;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
		chunksWrittenAgainChangeNothing(context, world);
		farTileDrawnAgainAsSaved(context, world, ground);
		newLandMovesTheFog(context, world, ground);
		sameHeightBlockKeepsTheFog(context, world);
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

	/**
	 * The game writes a chunk again whenever something in it changed, and the chunks the server keeps
	 * loaded past what the client sees get written every minute or so, mostly with nothing new to
	 * show. Drawn again, none of them has the fog built again. Not every one comes out the same to
	 * the last bit: by the edge of the client's chunks some colours can be one step off from the
	 * last time. One that does stays on the GPU as it is ({@link #farTileDrawnAgainAsSaved}).
	 */
	private static void chunksWrittenAgainChangeNothing(ClientGameTestContext context, TestSingleplayerContext world) {
		save(context, world);
		rescanAndWait(context);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int px = at.getX() >> 4;
		int pz = at.getZ() >> 4;
		List<long[]> loaded = world.getServer().computeOnServer(server -> {
			List<long[]> out = new ArrayList<>();
			for (int cx = px - 20; cx <= px + 20; cx++) {
				for (int cz = pz - 20; cz <= pz + 20; cz++) {
					if (server.overworld().getChunkSource().getChunkNow(cx, cz) != null) {
						int x = cx * 16 + 8;
						int z = cz * 16 + 8;
						out.add(new long[] {cx, cz, server.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, x, z)});
					}
				}
			}
			return out;
		});
		// Never sent, drawn from the save, and under a tile the map has.
		List<long[]> ring = context.computeOnClient(mc -> {
			List<long[]> out = new ArrayList<>();
			for (long[] chunk : loaded) {
				int cx = (int) chunk[0];
				int cz = (int) chunk[1];
				if (!mc.level.getChunkSource().hasChunk(cx, cz) && MapSession.get().savedChunks().drewChunk(cx, cz)
					&& MapSession.get().map().peek(tileAt(cx * 16 + 8, (int) chunk[2], cz * 16 + 8)) != null) {
					out.add(chunk);
				}
			}
			return out;
		});
		if (ring.isEmpty()) {
			throw new AssertionError("No chunk the server has loaded and the client doesn't is drawn under a tile the map has, of " + loaded.size() + " loaded");
		}
		int minY = context.computeOnClient(mc -> MapSession.get().map().minY());
		int drawnBefore = drawn(context);
		int coverageBefore = context.computeOnClient(mc -> MapSession.get().map().coverageChanges());
		// A second on, so the save time moves. A block changed and changed back makes the game
		// write the chunk again as it was.
		context.waitTicks(30);
		world.getServer().runOnServer(server -> {
			for (long[] chunk : ring) {
				BlockPos bottom = new BlockPos((int) chunk[0] * 16 + 8, minY, (int) chunk[1] * 16 + 8);
				net.minecraft.world.level.block.state.BlockState was = server.overworld().getBlockState(bottom);
				server.overworld().setBlockAndUpdate(bottom, was.is(Blocks.STONE) ? Blocks.BEDROCK.defaultBlockState() : Blocks.STONE.defaultBlockState());
				server.overworld().setBlockAndUpdate(bottom, was);
			}
		});
		save(context, world);
		rescanAndWait(context);
		if (drawn(context) <= drawnBefore) {
			throw new AssertionError("None of the " + ring.size() + " chunks written again was drawn again");
		}
		int coverage = context.computeOnClient(mc -> MapSession.get().map().coverageChanges()) - coverageBefore;
		if (coverage > 0) {
			throw new AssertionError("Chunks the game wrote again unchanged changed the coverage of " + coverage + " tiles, so the fog was built again");
		}
	}

	/**
	 * The far chunks written again as they were: the tile over them is drawn again, and comes out as
	 * it was saved, as nothing the client has is near. It stays on the GPU as it is. Saved without its
	 * spots, it gets them back, and with a claims mod in the game the claims over it, painted on the
	 * ground the spots say, show again.
	 */
	private static void farTileDrawnAgainAsSaved(ClientGameTestContext context, TestSingleplayerContext world, int ground) {
		TileKey tile = tileAt(X + 24, ground, Z + 24);
		lookAt(context, X + 24, Projection.v(ground, Z + 24), tile);
		MapLevel map = context.computeOnClient(mc -> MapSession.get().map());
		map.forgetSpots(tile);
		if (map.hasSavedSpots(tile)) {
			throw new AssertionError("Tile " + tile + " still has spots after forgetting them");
		}
		// A claims mod only has to be there for claims to be shown: these come from the test.
		boolean claims = context.computeOnClient(mc -> Claims.supported());
		if (claims) {
			claimFarChunks(context, TestOwner.ONE);
			context.waitFor(mc -> Claims.get().at(X >> 4, Z >> 4) != null, 200);
			context.waitTicks(60);
			if (context.computeOnClient(mc -> claimsShown(tile))) {
				throw new AssertionError("Tile " + tile + " shows claims with no spots saved: this wouldn't show them coming back with the spots");
			}
		}
		List<Boolean> taken = new ArrayList<>();
		context.runOnClient(mc -> map.watchSavedTiles((key, kept, coverageMoved) -> {
			if (key.equals(tile)) {
				taken.add(kept);
			}
		}));
		// Closed: on 1.20.1 the map pauses the whole server while the world is paused.
		context.setScreen(() -> null);
		int minY = context.computeOnClient(mc -> MapSession.get().map().minY());
		loadForAWhile(context, world, X, Z, X + CHUNKS * 16 - 1, Z + CHUNKS * 16 - 1, true, server -> {
			for (int dx = 0; dx < CHUNKS; dx++) {
				for (int dz = 0; dz < CHUNKS; dz++) {
					BlockPos bottom = new BlockPos(X + dx * 16 + 8, minY, Z + dz * 16 + 8);
					net.minecraft.world.level.block.state.BlockState was = server.overworld().getBlockState(bottom);
					server.overworld().setBlockAndUpdate(bottom, was.is(Blocks.STONE) ? Blocks.BEDROCK.defaultBlockState() : Blocks.STONE.defaultBlockState());
					server.overworld().setBlockAndUpdate(bottom, was);
				}
			}
		});
		save(context, world);
		lookAt(context, X + 24, Projection.v(ground, Z + 24), tile);
		rescanAndWait(context);
		context.runOnClient(mc -> map.watchSavedTiles(null));
		List<Boolean> seen = context.computeOnClient(mc -> List.copyOf(taken));
		if (seen.isEmpty()) {
			throw new AssertionError("Tile " + tile + " over the far chunks the game wrote again unchanged wasn't drawn again");
		}
		if (seen.contains(false)) {
			throw new AssertionError("Tile " + tile + ", drawn again from far chunks the game wrote again unchanged, went on the GPU again (" + seen
				+ " for each time it was drawn, true for kept)");
		}
		if (!map.hasSavedSpots(tile)) {
			throw new AssertionError("Tile " + tile + ", drawn again from the save, has no spots saved yet");
		}
		if (claims) {
			for (int i = 0; !context.computeOnClient(mc -> claimsShown(tile)); i++) {
				if (i > 400) {
					throw new AssertionError("Tile " + tile + " got its spots back from the save, but the claims over it never showed again");
				}
				context.waitTick();
			}
			context.takeScreenshot("saved-chunks-far-claims");
			claimFarChunks(context, null);
		}
		context.setScreen(() -> null);
	}

	/**
	 * New land in a tile the map shows, drawn from the save: the share of the tile the map knows
	 * grows, and the number the fog mask is kept by moves with it, so the fog lifts there.
	 */
	private static void newLandMovesTheFog(ClientGameTestContext context, TestSingleplayerContext world, int ground) {
		// Past the chunks made around the far ones, in the same column of tiles.
		int x = X + 136;
		int z = Z + 24;
		TileKey tile = tileAt(X + 24, ground, Z + 24);
		lookAt(context, X + 24, Projection.v(ground, Z + 24), tile);
		MapLevel map = context.computeOnClient(mc -> MapSession.get().map());
		List<Boolean> moved = new ArrayList<>();
		context.runOnClient(mc -> map.watchSavedTiles((key, kept, coverageMoved) -> {
			if (key.equals(tile)) {
				moved.add(!kept && coverageMoved);
			}
		}));
		long versionBefore = context.computeOnClient(mc -> map.coverageVersion());
		context.setScreen(() -> null);
		int[] height = new int[1];
		loadForAWhile(context, world, x, z, x, z, false, server -> height[0] = server.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
		save(context, world);
		TileKey landed = tileAt(x, height[0], z);
		if (!landed.equals(tile)) {
			throw new AssertionError("The new land at " + x + "," + z + " is in tile " + landed + ", not " + tile + " over the far chunks");
		}
		lookAt(context, X + 24, Projection.v(ground, Z + 24), tile);
		int pixel = pixelAt(x, height[0], z);
		rescanAndWait(context);
		context.runOnClient(mc -> map.watchSavedTiles(null));
		if ((savedPixel(context, tile, pixel) >>> 24) == 0) {
			throw new AssertionError("The new land at " + x + "," + z + " wasn't drawn into tile " + tile);
		}
		List<Boolean> seen = context.computeOnClient(mc -> List.copyOf(moved));
		long version = context.computeOnClient(mc -> map.coverageVersion());
		if (!seen.contains(true) || version == versionBefore) {
			throw new AssertionError("New land drawn into tile " + tile + " from the save didn't move the coverage the fog is built from (drawn " + seen.size()
				+ " times, coverage version " + versionBefore + " to " + version + "): the fog would stay over it");
		}
		context.setScreen(() -> null);
	}

	/**
	 * Forces chunks loaded on the server, does {@code meanwhile} there, and lets them go again, so the
	 * game writes them when it saves. {@code paused}: the world is paused until they're let go, so
	 * nothing in them changes but what {@code meanwhile} does; paused, the game never unloads them.
	 */
	private static void loadForAWhile(ClientGameTestContext context, TestSingleplayerContext world, int x0, int z0, int x1, int z1, boolean paused,
		java.util.function.Consumer<MinecraftServer> meanwhile) {
		try (WorldPause pause = paused ? WorldPause.pause(context) : null) {
			world.getServer().runCommand("forceload add " + x0 + " " + z0 + " " + x1 + " " + z1);
			for (int i = 0; !world.getServer().computeOnServer(server -> allLoaded(server, x0 >> 4, z0 >> 4, x1 >> 4, z1 >> 4, true)); i++) {
				if (i > 2400) {
					throw new AssertionError("The chunks from " + x0 + "," + z0 + " to " + x1 + "," + z1 + " never loaded");
				}
				context.waitTick();
			}
			world.getServer().runOnServer(meanwhile::accept);
		}
		world.getServer().runCommand("forceload remove all");
		for (int i = 0; !world.getServer().computeOnServer(server -> allLoaded(server, x0 >> 4, z0 >> 4, x1 >> 4, z1 >> 4, false)); i++) {
			if (i > 2400) {
				throw new AssertionError("The chunks from " + x0 + "," + z0 + " to " + x1 + "," + z1 + " never unloaded");
			}
			context.waitTick();
		}
	}

	/** Whether every chunk in the square is loaded on the server, or with {@code loaded} false, none is. */
	private static boolean allLoaded(MinecraftServer server, int cx0, int cz0, int cx1, int cz1, boolean loaded) {
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				if ((server.overworld().getChunkSource().getChunkNow(cx, cz) != null) != loaded) {
					return false;
				}
			}
		}
		return true;
	}

	/** Opens the map on a point, and waits for it to show the tile there. */
	private static void lookAt(ClientGameTestContext context, double u, double v, TileKey tile) {
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(u, v, 2, TileKey.SURFACE));
		context.waitFor(mc -> MapSession.get().map().peek(tile) != null, 400);
	}

	/** Hands the far chunks to {@code owner} as a claims mod would, or to no one. */
	private static void claimFarChunks(ClientGameTestContext context, TestOwner owner) {
		context.runOnClient(mc -> {
			for (int dx = 0; dx < CHUNKS; dx++) {
				for (int dz = 0; dz < CHUNKS; dz++) {
					Claims.updated(mc.level.dimension(), (X >> 4) + dx, (Z >> 4) + dz, TestOwner.class, owner, false);
				}
			}
		});
	}

	private static boolean claimsShown(TileKey key) {
		MapTile tile = MapSession.get().map().peek(key);
		return tile != null && tile.claims() != null;
	}

	/** Whoever the test hands claims to. */
	private record TestOwner(String name) implements Claims.Owner {
		static final TestOwner ONE = new TestOwner("Test claim");

		@Override
		public Optional<Claims.Claim> look() {
			return Optional.of(new Claims.Claim(this, 0xE04040, this.name, false, false));
		}
	}

	/** A block changed for another the same height is drawn, and leaves the coverage the fog is built from as it was. */
	private static void sameHeightBlockKeepsTheFog(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = at.getX() + 5;
		int z = at.getZ();
		int y = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, x, z)) - 1;
		BlockPos top = new BlockPos(x, y, z);
		TileKey tile = tileAt(x, y + 1, z);
		int pixel = pixelAt(x, y + 1, z);
		// A full block first, whatever was there, so the change after is one full block for another.
		int before = savedPixel(context, tile, pixel);
		world.getServer().runOnServer(server -> server.overworld().setBlockAndUpdate(top,
			(server.overworld().getBlockState(top).is(Blocks.STONE) ? Blocks.GOLD_BLOCK : Blocks.STONE).defaultBlockState()));
		context.waitFor(mc -> savedPixelNow(tile, pixel) != before, 1200);
		DioramaClientTest.settle(context, 40);
		int stone = savedPixel(context, tile, pixel);
		if (context.computeOnClient(mc -> MapSession.get().map().peek(tile) == null)) {
			throw new AssertionError("The map doesn't have tile " + tile + " next to the player");
		}
		int coverageBefore = context.computeOnClient(mc -> MapSession.get().map().coverageChanges());
		world.getServer().runOnServer(server -> server.overworld().setBlockAndUpdate(top, Blocks.DIAMOND_BLOCK.defaultBlockState()));
		context.waitFor(mc -> savedPixelNow(tile, pixel) != stone, 1200);
		int coverage = context.computeOnClient(mc -> MapSession.get().map().coverageChanges()) - coverageBefore;
		if (coverage > 0) {
			throw new AssertionError("Stone turned to diamond next to the player changed the coverage of " + coverage + " tiles, so the fog was built again");
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
