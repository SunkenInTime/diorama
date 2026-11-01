package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.data.TileLabel;
import dev.diorama.data.TileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Tiles labelled differently ({@code redraw}): the camera angle changes, the map remembers what it
 * had and redraws it from the save, as far as it remembers with filling in off, and a label the map
 * comes back to catches up with what the world did meanwhile.
 */
final class RedrawTest {
	/** Far from anything the other sections visit. */
	private static final int A = 7000;
	private static final int B = 7600;
	private static final int CHUNKS = 4;
	private static final int PILLAR = 30;

	private static int pillarX;
	private static int pillarY;
	private static int pillarZ;
	private static int pillarPixel;
	private static TileKey pillarTile;
	private static int pillarBefore;

	private RedrawTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		int ground = generate(context, world, A);
		rescanAndWait(context);
		mustShow(context, tileAt(A + 24, ground, A + 24), "before anything changed");

		setPitch(context, 60);
		Path folder = context.computeOnClient(mc -> MapSession.get().map().importFolder());
		if (!Files.isRegularFile(folder.resolve("redraw-from"))) {
			throw new AssertionError("The new folder doesn't note what it remembers: " + folder);
		}
		if (context.computeOnClient(mc -> MapSession.get().map().memory() == null)) {
			throw new AssertionError("The map remembers nothing after its tiles' label changed");
		}
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(A + 24, Projection.v(ground, A + 24), 0.7, TileKey.SURFACE));
		context.waitFor(mc -> MapSession.get().map().memory().shownTiles() > 0, 400);
		context.waitTicks(10);
		context.takeScreenshot("redraw-under-clouds");
		rescanAndWait(context);
		context.waitTicks(40);
		context.takeScreenshot("redraw-landed");
		context.setScreen(() -> null);
		mustShow(context, tileAt(A + 24, ground, A + 24), "after the redraw");

		// With filling in off, the redraw goes as far as the map remembers.
		context.runOnClient(mc -> Settings.get().mapSavedChunks = false);
		context.waitTicks(10);
		int groundB = generate(context, world, B);
		markPillar(context, world);
		setPitch(context, 40);
		context.waitFor(mc -> MapSession.get().savedChunks() != null, 200);
		rescanAndWait(context);
		mustShow(context, tileAt(A + 24, ground, A + 24), "with only what it remembers redrawn");
		TileKey farTile = tileAt(B + 24, groundB, B + 24);
		if (shown(context, farTile) != 0) {
			throw new AssertionError("Redrawing what's remembered filled in " + farTile + ", which the map never had");
		}
		// Filling in again, what the redraw left out is drawn.
		context.runOnClient(mc -> Settings.get().mapSavedChunks = true);
		context.waitTicks(40);
		rescanAndWait(context);
		mustShow(context, farTile, "once filling in was back on");

		// Changes the world while the map is on another label, then leaves, so nothing live is left
		// of it: only the save can tell the old label about it.
		world.getServer().runOnServer(server -> {
			for (int i = 0; i <= PILLAR; i++) {
				server.overworld().setBlockAndUpdate(new BlockPos(pillarX, pillarY - i, pillarZ), Blocks.DIAMOND_BLOCK.defaultBlockState());
			}
		});
		context.waitTicks(40);
		world.getServer().runCommand("tp @a " + B + " 200 " + B);
		context.waitTicks(100);
		world.getServer().runOnServer(server -> server.saveEverything(true, true, true));
		context.waitTicks(40);
	}

	/** After the world is reopened on the other label: going back to the first brings the change with it. */
	static void reopened(ClientGameTestContext context) {
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().savedChunks() != null, 2400);
		if (context.computeOnClient(mc -> MapSession.get().map().chunk(pillarX >> 4, pillarZ >> 4) != null)) {
			throw new AssertionError("The client still knows the changed chunk: this wouldn't show the save being read");
		}
		setPitch(context, Math.round(Settings.get().style().pitchDegrees()));
		rescanAndWait(context);
		int now = context.computeOnClient(mc -> {
			int[] pixels = MapSession.get().map().savedMap().read(pillarTile);
			return pixels == null ? 0 : pixels[pillarPixel];
		});
		if (now == pillarBefore || (now >>> 24) == 0) {
			throw new AssertionError("The map came back to its first label without what changed in the world while it was away");
		}
	}

	/** After {@link #reopened}: under a roof the same goes, under smoke. */
	static void underRoof(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 64 0");
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().roofless() && MapSession.get().map().chunkCount() > 100, 2400);
		context.waitFor(mc -> !MapSession.get().map().savedMap().saved(0).isEmpty(), 2400);
		setPitch(context, 35);
		if (context.computeOnClient(mc -> MapSession.get().map().memory() == null)) {
			throw new AssertionError("The map under the roof remembers nothing after its tiles' label changed");
		}
		context.setScreen(MapScreen::new);
		// The sky view: down here the map on its own would pick a cave slice.
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(mc.player.getX(), Projection.v(mc.player.getY(), mc.player.getZ()), 0.7, TileKey.SURFACE));
		context.waitFor(mc -> MapSession.get().map().memory().shownTiles() > 0, 400);
		context.waitTicks(10);
		context.takeScreenshot("redraw-under-smoke");
		context.setScreen(() -> null);
		setPitch(context, Math.round(Settings.get().style().pitchDegrees()));
	}

	/** Notes the map as it is on the default label, in the player's own chunk, which it saw live. */
	private static void markPillar(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		pillarX = at.getX() + 3;
		pillarZ = at.getZ();
		pillarY = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, pillarX, pillarZ)) + PILLAR;
		// The label the map goes back to: where the pillar's top lands at the default angle.
		double pitch = Math.toRadians(Settings.get().style().pitchDegrees());
		double v = (pillarZ + 0.5) * Math.sin(pitch) - (pillarY + 1) * Math.cos(pitch);
		int size = Projection.TILE_PIXELS;
		int px = Math.floorMod((int) Math.floor((pillarX + 0.5) * Projection.PIXELS_PER_UNIT), size);
		int py = Math.floorMod((int) Math.floor(v * Projection.PIXELS_PER_UNIT), size);
		pillarPixel = py * size + px;
		pillarTile = new TileKey(TileKey.SURFACE, Math.floorDiv(pillarX, Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
		// Read from the folder itself: it isn't the map's right now.
		String folder = new TileLabel(false, Math.round(Settings.get().style().pitchDegrees()), false).folder();
		TileStore first = new TileStore(context.computeOnClient(mc -> MapSession.get().map().importFolder()).resolveSibling(folder));
		int[] pixels = first.read(pillarTile);
		first.close();
		pillarBefore = pixels == null ? 0 : pixels[pillarPixel];
	}

	private static void setPitch(ClientGameTestContext context, double degrees) {
		context.runOnClient(mc -> {
			MapStyle.set(MapStyle.get().withPitch(degrees));
			MapSession.get().map().colorsChanged();
		});
	}

	/** Makes a few chunks on the server alone, and returns how high their ground is. */
	private static int generate(ClientGameTestContext context, TestSingleplayerContext world, int at) {
		int last = at + CHUNKS * 16 - 1;
		world.getServer().runCommand("forceload add " + at + " " + at + " " + last + " " + last);
		for (int i = 0; !world.getServer().computeOnServer(server -> loaded(server, at)); i++) {
			if (i > 2400) {
				throw new AssertionError("The forced chunks never loaded");
			}
			context.waitTick();
		}
		int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.WORLD_SURFACE, at + 24, at + 24));
		world.getServer().runCommand("forceload remove all");
		for (int i = 0; world.getServer().computeOnServer(server -> server.overworld().getChunkSource().getChunkNow(at >> 4, at >> 4) != null); i++) {
			if (i > 2400) {
				throw new AssertionError("The forced chunks never unloaded");
			}
			context.waitTick();
		}
		world.getServer().runOnServer(server -> server.saveEverything(true, true, true));
		context.waitTicks(40);
		return ground;
	}

	private static boolean loaded(MinecraftServer server, int at) {
		for (int dx = 0; dx < CHUNKS; dx++) {
			for (int dz = 0; dz < CHUNKS; dz++) {
				if (server.overworld().getChunkSource().getChunkNow((at >> 4) + dx, (at >> 4) + dz) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static void mustShow(ClientGameTestContext context, TileKey tile, String when) {
		if (shown(context, tile) == 0) {
			throw new AssertionError("Tile " + tile + " shows nothing " + when);
		}
	}

	private static int shown(ClientGameTestContext context, TileKey tile) {
		int[] pixels = context.computeOnClient(mc -> MapSession.get().map().savedMap().read(tile));
		int shown = 0;
		if (pixels != null) {
			for (int pixel : pixels) {
				if ((pixel >>> 24) != 0) {
					shown++;
				}
			}
		}
		return shown;
	}

	private static TileKey tileAt(int x, int y, int z) {
		return new TileKey(TileKey.SURFACE, Math.floorDiv(x, Projection.TILE_UNITS), (int) Math.floor(Projection.v(y, z) / Projection.TILE_UNITS));
	}

	/** Lists the save now, and waits until everything it holds is drawn, with the world quiet. */
	private static void rescanAndWait(ClientGameTestContext context) {
		try (WorldPause paused = WorldPause.pause(context)) {
			context.runOnClient(mc -> MapSession.get().savedChunks().rescanSoon());
			context.waitFor(mc -> MapSession.get().savedChunks().idle(), 3600);
		}
	}
}
