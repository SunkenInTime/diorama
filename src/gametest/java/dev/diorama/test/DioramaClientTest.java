package dev.diorama.test;

import dev.diorama.Claims;
import dev.diorama.Diorama;
import dev.diorama.DioramaClient;
import dev.diorama.MapMenu;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.WorldKey;
import dev.diorama.bake.BiomeTints;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.Overlays;
import dev.diorama.bake.TileKey;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapTile;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Radar;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointListScreen;
import dev.diorama.waypoint.WaypointOverlay;
import dev.diorama.waypoint.WaypointShare;
import dev.diorama.waypoint.WaypointStore;
import dev.diorama.waypoint.WorldProjection;
import dev.diorama.waypoint.XaeroImport;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Drives the real game through the map's views and saves screenshots, so a person can look at
 * what the map actually draws, and asserts what can be measured along the way.
 */
public class DioramaClientTest implements FabricClientGameTest {
	/**
	 * The test servers' port: free when the run starts, so test runs on one machine don't collide,
	 * and the same for every server in the run, so they all share one address.
	 */
	private static final int SERVER_PORT = freePort();

	@Override
	public void runTest(ClientGameTestContext context) {
		context.getInput().resizeWindow(1600, 900);
		context.runOnClient(mc -> mc.options.renderDistance().set(12));
		String seed = System.getProperty("diorama.seed", "diorama");
		Set<String> tests = selectedTests();
		if (tests.contains("stale-gl-error")) {
			// An OpenGL error another mod raised and nobody read, still flagged when GPU baking sets
			// up: with gpu-bake, the comparison shows the setup didn't take it for its own. OpenGL only.
			context.runOnClient(mc -> org.lwjgl.opengl.GL11C.glBindTexture(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, Integer.MAX_VALUE));
		}
		if (tests.contains("showcase") && tests.size() > 1) {
			// The showcase leaves the player in the Nether, spectating, on peaceful, with radar off.
			throw new AssertionError("Run the showcase on its own: DIORAMA_TESTS=showcase");
		}
		TestWorldSave save = null;
		int[] spawnCell = null;
		int savedChunksGround = 0;
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setSeed(seed);
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			String tp = System.getProperty("diorama.tp");
			if (tp != null) {
				world.getServer().runCommand("tp @a " + tp);
			}

			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
			settle(context, 100);
			// boat_run.py times the warm-up by this line.
			Diorama.LOGGER.info("Map warm-up done");
			context.runOnClient(mc -> VoxelReaderChecks.check(MapSession.get().map(),
				mc.player.blockPosition().getX() >> 4, mc.player.blockPosition().getZ() >> 4));
			save = world.getWorldSave();
			spawnCell = tests.contains("saved-map") ? knownSpawnCell(context) : null;
			if (tests.contains("styles")) {
				styleTour(context, world);
			}
			if (tests.contains("showcase")) {
				showcase(context, world);
			}
			if (tests.contains("icon-waypoints")) {
				IconWaypointsTest.photos(context, world);
			}
			if (tests.contains("drawings")) {
				DrawingTest.run(context, world);
			}
			if (tests.contains("waypoint-clutter")) {
				WaypointClutterTest.photos(context, world);
			}
			if (tests.contains("waypoint-size")) {
				WaypointSizeTest.photos(context, world);
			}
			if (tests.contains("long-initials")) {
				LongInitialsTest.photos(context, world);
			}
			if (tests.contains("natural-waters")) {
				NaturalWatersTest.run(context, world);
			}
			if (tests.contains("portal-crossing")) {
				portalCrossing(context, world);
			}
			if (tests.contains("dimensions")) {
				dimensions(context, world);
			}
			if (tests.contains("bench")) {
				bench(context);
			}
			if (tests.contains("gpu-bake")) {
				gpuBake(context, world);
			}
			if (tests.contains("tour")) {
				tour(context, world);
			}
			if (tests.contains("radar")) {
				radar(context, world);
			}
			if (tests.contains("xaero-icons")) {
				xaeroIcons(context, world);
			}
			if (tests.contains("model-faces")) {
				modelFaces(context, world);
			}
			if (tests.contains("waypoints")) {
				waypoints(context, world);
			}
			if (tests.contains("icons")) {
				IconWaypointsTest.checks(context, world);
			}
			if (tests.contains("xaero-import")) {
				xaeroImport(context, world);
			}
			if (tests.contains("unnumbered-states")) {
				UnnumberedStatesTest.run(context, world);
			}
			if (tests.contains("failing-model")) {
				FailingModelTest.run(context);
			}
			if (tests.contains("fog-of-war")) {
				fogOfWar(context, world);
			}
			if (tests.contains("settings")) {
				settings(context, world);
			}
			if (tests.contains("overlays")) {
				overlays(context, world);
			}
			if (tests.contains("screens")) {
				screens(context, world);
			}
			if (tests.contains("enlarge")) {
				enlarge(context);
			}
			if (tests.contains("minimap-frames")) {
				MinimapFrameTest.run(context, world);
			}
			if (tests.contains("map-mouse-keys")) {
				MapMouseKeysTest.run(context);
			}
			if (tests.contains("game-time")) {
				GameTimeTest.run(context, world);
			}
			if (tests.contains("image-export")) {
				ImageExportTest.run(context, world);
			}
			if (tests.contains("saved-chunks")) {
				savedChunksGround = SavedChunksTest.run(context, world);
			}
			if (tests.contains("waystones")) {
				waystones(context, world);
			}
			if (tests.contains("ftb-claims")) {
				ftbClaims(context, world);
			}
			if (tests.contains("cobblemon")) {
				cobblemonBattle(context, world);
			}
			if (tests.contains("pixelmon")) {
				PixelmonTest.run(context, world);
			}
			//? if <1.21.6 {
			/*if (tests.contains("broken-fog")) {
				brokenFog(context);
			}
			*///?}
			if (spawnCell != null) {
				spawnProbesBeforeLeaving(context, world, spawnCell);
				// Go far from spawn, so its chunks are long gone once the world is reopened.
				world.getServer().runCommand("tp @a 3000 200 3000");
				context.waitTicks(100);
			}
		}
		if ((spawnCell != null || tests.contains("waypoints") || tests.contains("waystones") || tests.contains("saved-chunks")) && save != null) {
			try (TestSingleplayerContext reopened = save.open()) {
				if (tests.contains("saved-chunks")) {
					SavedChunksTest.reopened(context, savedChunksGround);
				}
				if (tests.contains("waypoints")) {
					temporaryGone(context);
				}
				if (tests.contains("waystones")) {
					waystonesAfterRejoin(context, reopened);
				}
				if (spawnCell != null) {
					savedMap(context, reopened, spawnCell, tests.contains("fog-of-war"));
				}
			}
		}
		if (tests.contains("recreated-world")) {
			recreatedWorld(context);
		}
		if (tests.contains("tall-world")) {
			TallWorldTest.run(context);
		}
		if (tests.contains("sub-maps")) {
			subMaps(context);
		}
		if (tests.contains("map-dimensions")) {
			mapDimensions(context);
			moddedDimension(context);
		}
		if (tests.contains("languages")) {
			languages(context);
		}
		if (tests.contains("multiplayer")) {
			multiplayer(context);
		}
		//? if >=1.21.4 && fabric {
		if (tests.contains("game-time-server")) {
			GameTimeTest.onServer(context);
		}
		//?}
		// The server's side of Xaero's channels goes through Fabric API networking, so these run on
		// Fabric only, and from 1.21, whose networking has payload types. On NeoForge and on 1.20.1
		// the shim can't start a dedicated server anyway.
		//? if >=1.21 && fabric {
		if (tests.contains("server-id")) {
			serverLevelIds(context);
		}
		if (tests.contains("tracked-players")) {
			trackedPlayers(context);
			// BuildPrint takes these channels for itself, so Diorama isn't their handler, and nobody
			// else answers the server: it reads and answers on the way.
			answerOnAnotherModsChannel();
			dev.diorama.XaeroChannels.leaveToAnotherModForTests(true);
			try {
				trackedPlayers(context);
			} finally {
				dev.diorama.XaeroChannels.leaveToAnotherModForTests(false);
			}
		}
		//?}
		//? if >=1.21.11 && fabric {
		if (tests.contains("realms")) {
			realms(context);
		}
		//?}
	}

	/**
	 * Two worlds behind one server address, like a network's lobby and survival servers, or a
	 * server whose map was reset: each keeps its own map and waypoints, and going back to the
	 * first finds them again, even after its spawn moved.
	 */
	private static void multiplayer(ClientGameTestContext context) {
		String address = WorldKey.key("server", "localhost:" + SERVER_PORT);
		context.runOnClient(mc -> {
			// Start from nothing saved for this address.
			try (java.util.stream.Stream<java.nio.file.Path> old = java.nio.file.Files.list(WorldKey.folder("."))) {
				for (java.nio.file.Path folder : old.filter(p -> p.getFileName().toString().startsWith(address)).toList()) {
					org.apache.commons.io.FileUtils.deleteDirectory(folder.toFile());
				}
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		String first = playOnServer(context, "diorama-first", "first", null, "Camp First", List.of(), List.of(), null, null);
		String second = playOnServer(context, "diorama-second", "second", null, "Camp Second", List.of(), List.of("Camp First"), false, null);
		String again = playOnServer(context, "diorama-first", "first-again", "setworldspawn 40 90 40", null, List.of("Camp First"), List.of("Camp Second"), true, null);
		if (first.equals(second)) {
			throw new AssertionError("Two worlds on one server address share a map folder: " + first);
		}
		if (!first.equals(again)) {
			throw new AssertionError("Coming back to a world opened another folder: " + first + " then " + again);
		}
	}

	/**
	 * A world made after deleting one of the same name gets the same save folder, but not the old
	 * world's map and waypoints (#116), even with the same seed. Reopened, it finds its own again,
	 * also once the old world's folder is gone.
	 */
	private static void recreatedWorld(ClientGameTestContext context) {
		String name = "Diorama Recreated";
		String oldKey;
		java.nio.file.Path saveDirectory;
		try (TestSingleplayerContext old = context.worldBuilder()
			.adjustSettings(s -> {
				s.setName(name);
				s.setSeed("recreated");
			})
			.create()) {
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 1200);
			saveDirectory = old.getWorldSave().getSaveDirectory();
			oldKey = context.computeOnClient(mc -> {
				BlockPos at = mc.player.blockPosition();
				WaypointStore.get().add(new Waypoint("Old Camp", at.getX(), at.getY(), at.getZ(), mc.level.dimension().identifier().toString(), Waypoint.PALETTE[3]));
				return WorldKey.current();
			});
		}
		// What the world list's Delete button does.
		context.runOnClient(mc -> {
			try (net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess access = mc.getLevelSource().createAccess(saveDirectory.getFileName().toString())) {
				access.deleteLevel();
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		String newKey;
		TestWorldSave save;
		try (TestSingleplayerContext recreated = context.worldBuilder()
			.adjustSettings(s -> {
				s.setName(name);
				s.setSeed("recreated");
			})
			.create()) {
			save = recreated.getWorldSave();
			if (!save.getSaveDirectory().equals(saveDirectory)) {
				throw new AssertionError("The new world didn't get the deleted world's save folder: " + saveDirectory + " then " + save.getSaveDirectory());
			}
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 1200);
			newKey = context.computeOnClient(mc -> WorldKey.current());
			if (newKey.equals(oldKey)) {
				throw new AssertionError("The new world opened the deleted world's folder " + oldKey);
			}
			if (context.computeOnClient(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.name.equals("Old Camp")))) {
				throw new AssertionError("The deleted world's waypoint is in the new world");
			}
			context.runOnClient(mc -> {
				BlockPos at = mc.player.blockPosition();
				WaypointStore.get().add(new Waypoint("New Camp", at.getX(), at.getY(), at.getZ(), mc.level.dimension().identifier().toString(), Waypoint.PALETTE[3]));
			});
			context.setScreen(MapScreen::new);
			settle(context, 60);
			context.takeScreenshot("recreated-world-map");
			context.setScreen(() -> null);
		}
		// A player tidying up deletes the old world's map.
		context.runOnClient(mc -> {
			try {
				org.apache.commons.io.FileUtils.deleteDirectory(WorldKey.folder(oldKey).toFile());
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		try (TestSingleplayerContext reopened = save.open()) {
			String again = context.computeOnClient(mc -> WorldKey.current());
			if (!again.equals(newKey)) {
				throw new AssertionError("Reopening the new world opened another folder: " + newKey + " then " + again);
			}
			if (!context.computeOnClient(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.name.equals("New Camp")))) {
				throw new AssertionError("Reopening the new world lost its waypoint");
			}
		}
	}

	/**
	 * Sub-maps (#156), in a world of its own so the maps it leaves in use don't reach the other
	 * tests. Main maps two places; a new map made through the Maps screen starts with only what's
	 * around the player, without Main's waypoint and drawing, and leaves the Nether on its main map;
	 * going back to Main brings them all back; a deleted map's tiles and waypoints go; and the map
	 * in use is still in use when the world is opened again.
	 */
	private static void subMaps(ClientGameTestContext context) {
		String overworld = "minecraft:overworld";
		String nether = "minecraft:the_nether";
		TestWorldSave save;
		String newId;
		String[] worldKeyLeft = new String[1];
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setName("Diorama Sub-maps");
				s.setSeed("diorama");
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			save = world.getWorldSave();
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
			// Main maps a second place, 600 blocks east, then the player comes back.
			world.getServer().runCommand("tp @a 600 120 0");
			context.waitFor(mc -> mc.player.getX() > 500 && MapSession.get() != null && MapSession.get().map().chunk(600 >> 4, 0) != null, 2400);
			settle(context, 100);
			world.getServer().runCommand("tp @a 0 120 0");
			context.waitFor(mc -> mc.player.getX() < 100, 600);
			settle(context, 100);
			String worldKey = context.computeOnClient(mc -> {
				BlockPos at = mc.player.blockPosition();
				WaypointStore.get().add(new Waypoint("Old Base", at.getX(), at.getY(), at.getZ(), overworld, Waypoint.PALETTE[3]));
				WaypointStore.get().add(new Waypoint("Nether Hub", 10, 70, 10, nether, Waypoint.PALETTE[5]));
				dev.diorama.drawing.DrawingStore.get().add(new dev.diorama.drawing.Drawing(dev.diorama.drawing.Drawing.Kind.NOTE, overworld, 0xFFFFFFFF,
					new double[] {at.getX(), Projection.v(at.getY(), at.getZ())}, "Old note"));
				return WorldKey.current();
			});
			worldKeyLeft[0] = worldKey;
			java.nio.file.Path mainFolder = context.computeOnClient(mc -> dev.diorama.Maps.get().tileFolder(overworld, dev.diorama.Maps.MAIN));
			context.waitFor(mc -> tileFiles(mainFolder) > 20 && hasTileAt(mainFolder, 600), 600);
			long mainTiles = tileFiles(mainFolder);
			context.setScreen(MapScreen::new);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(300, Projection.v(70, 0), 0.45, TileKey.SURFACE));
			settle(context, 60);
			context.takeScreenshot("sub-maps-01-main");

			// The player makes a new map through the Maps screen, with the mouse (gliding, with GIF
			// frames, under DIORAMA_GIF=1), and goes back to the map with Done.
			holdFrames(context, 20);
			glideClick(context, "diorama.map.maps");
			context.waitFor(mc -> mc.gui.screen() instanceof dev.diorama.MapsScreen, 40);
			holdFrames(context, 20);
			context.takeScreenshot("sub-maps-02-maps-screen");
			glideClick(context, "diorama.maps.new");
			context.waitFor(mc -> mc.gui.screen() instanceof dev.diorama.MapNameScreen, 40);
			String suggested = context.computeOnClient(mc -> editBoxes(mc.gui.screen()).get(0).getValue());
			if (!suggested.equals("Map 2")) {
				throw new AssertionError("A second map should be suggested as Map 2, not " + suggested);
			}
			holdFrames(context, 15);
			context.runOnClient(mc -> editBoxes(mc.gui.screen()).get(0).setValue(""));
			for (char c : "After the reset".toCharArray()) {
				context.getInput().typeChars(String.valueOf(c));
				context.waitTick();
				menuFrame(context);
			}
			holdFrames(context, 10);
			context.takeScreenshot("sub-maps-03-name");
			glideClick(context, "diorama.waypoint.save");
			context.waitFor(mc -> mc.gui.screen() instanceof dev.diorama.MapsScreen, 40);
			holdFrames(context, 25);
			newId = context.computeOnClient(mc -> dev.diorama.Maps.get().active(overworld));
			if (newId.equals(dev.diorama.Maps.MAIN)) {
				throw new AssertionError("Saving a new map didn't switch the Overworld to it");
			}
			if (!context.computeOnClient(mc -> dev.diorama.Maps.get().active(nether)).equals(dev.diorama.Maps.MAIN)) {
				throw new AssertionError("A new Overworld map switched the Nether's too");
			}
			context.takeScreenshot("sub-maps-04-maps-screen-two");
			glideClick(context, "gui.done");
			context.waitFor(mc -> mc.gui.screen() instanceof MapScreen, 40);
			// The new map fills in from the chunks already loaded around the player, and only those.
			for (int i = 0; i < 80; i++) {
				context.waitTick();
				menuFrame(context);
			}
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 300, 600);
			settle(context, 100);
			holdFrames(context, 1);
			context.takeScreenshot("sub-maps-05-new-map");
			java.nio.file.Path newFolder = context.computeOnClient(mc -> dev.diorama.Maps.get().tileFolder(overworld, newId));
			context.waitFor(mc -> tileFiles(newFolder) > 0, 600);
			context.runOnClient(mc -> {
				if (WaypointStore.get().all().stream().anyMatch(w -> w.name.equals("Old Base"))) {
					throw new AssertionError("Main's waypoint shows on the new map");
				}
				if (WaypointStore.get().all().stream().noneMatch(w -> w.name.equals("Nether Hub"))) {
					throw new AssertionError("The Nether's waypoint went with the Overworld's map");
				}
				if (!dev.diorama.drawing.DrawingStore.get().in(overworld).isEmpty()) {
					throw new AssertionError("Main's drawing shows on the new map");
				}
				if (hasTileAt(newFolder, 600) || MapSession.get().map().chunk(600 >> 4, 0) != null) {
					throw new AssertionError("The new map has the place only Main mapped");
				}
				BlockPos at = mc.player.blockPosition();
				WaypointStore.get().add(new Waypoint("New Base", at.getX(), at.getY(), at.getZ(), overworld, Waypoint.PALETTE[1]));
			});

			// Back to Main: its tiles, waypoint and drawing are all there, the new map's waypoint isn't.
			clickButton(context, "diorama.map.maps");
			context.waitFor(mc -> mc.gui.screen() instanceof dev.diorama.MapsScreen, 40);
			clickMapsRow(context, 0);
			clickButton(context, "diorama.maps.use");
			context.waitFor(mc -> dev.diorama.Maps.get().active(overworld).equals(dev.diorama.Maps.MAIN) && MapSession.get() != null, 200);
			context.runOnClient(mc -> {
				if (WaypointStore.get().all().stream().noneMatch(w -> w.name.equals("Old Base"))) {
					throw new AssertionError("Main's waypoint didn't come back with Main");
				}
				if (WaypointStore.get().all().stream().anyMatch(w -> w.name.equals("New Base"))) {
					throw new AssertionError("The new map's waypoint shows on Main");
				}
				if (dev.diorama.drawing.DrawingStore.get().in(overworld).stream().noneMatch(d -> d.text.equals("Old note"))) {
					throw new AssertionError("Main's drawing didn't come back with Main");
				}
			});
			if (!hasTileAt(mainFolder, 600) || tileFiles(mainFolder) < mainTiles) {
				throw new AssertionError("Main lost tiles: " + mainTiles + " then " + tileFiles(mainFolder));
			}
			// And the map open now reads them: the place only Main mapped shows again.
			context.setScreen(MapScreen::new);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(600, Projection.v(70, 0), 2.0, TileKey.SURFACE));
			settle(context, 60);
			context.runOnClient(mc -> {
				MapTile east = MapSession.get().map().peek(new TileKey(TileKey.SURFACE, Math.floorDiv(600, Projection.TILE_UNITS),
					(int) Math.floor(Projection.v(70, 0) / Projection.TILE_UNITS)));
				if (east == null || !east.hasCoverage()) {
					throw new AssertionError("Back on Main, the map doesn't show the place Main mapped 600 blocks east");
				}
			});
			context.takeScreenshot("sub-maps-05b-main-again-east");
			context.setScreen(() -> null);

			// A third map, then deleting it from Main: its tiles and waypoint go, nothing else does.
			String scratchId = context.computeOnClient(mc -> {
				String id = dev.diorama.Maps.get().create(overworld, "Scratch");
				BlockPos at = mc.player.blockPosition();
				WaypointStore.get().add(new Waypoint("Scratch Pin", at.getX(), at.getY(), at.getZ(), overworld, Waypoint.PALETTE[2]));
				return id;
			});
			java.nio.file.Path scratchFolder = context.computeOnClient(mc -> dev.diorama.Maps.get().tileFolder(overworld, scratchId));
			context.waitFor(mc -> MapSession.get() != null && tileFiles(scratchFolder) > 0, 600);
			context.runOnClient(mc -> dev.diorama.Maps.get().use(overworld, dev.diorama.Maps.MAIN));
			context.setScreen(() -> new dev.diorama.MapsScreen(null));
			context.waitTicks(10);
			clickMapsRow(context, 2);
			clickButton(context, "diorama.waypoint.delete");
			context.takeScreenshot("sub-maps-06-delete-armed");
			clickButton(context, "diorama.menu.delete_confirm");
			context.runOnClient(mc -> {
				if (java.nio.file.Files.exists(scratchFolder)) {
					throw new AssertionError("The deleted map's tiles are still there: " + scratchFolder);
				}
				if (dev.diorama.Maps.get().list(overworld).size() != 2) {
					throw new AssertionError("Expected Main and After the reset left, not " + dev.diorama.Maps.get().list(overworld));
				}
			});
			String saved = readFile(context.computeOnClient(mc -> WorldKey.folder(worldKey).resolve("waypoints.json")));
			if (saved.contains("Scratch Pin") || !saved.contains("New Base") || !saved.contains("Old Base")) {
				throw new AssertionError("After deleting Scratch the waypoints file should keep Old Base and New Base only: " + saved);
			}

			// A list that can't be saved changes nothing: no new map, the same map in use.
			java.nio.file.Path mapsFile = context.computeOnClient(mc -> WorldKey.folder(worldKey).resolve("maps.json"));
			java.nio.file.Path blocker = mapsFile.resolveSibling("maps.json.tmp");
			try {
				java.nio.file.Files.createDirectories(blocker);
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			context.runOnClient(mc -> {
				if (dev.diorama.Maps.get().create(overworld, "Blocked") != null) {
					throw new AssertionError("A map was made though the list of maps couldn't be saved");
				}
				if (dev.diorama.Maps.get().list(overworld).size() != 2 || !dev.diorama.Maps.get().active(overworld).equals(dev.diorama.Maps.MAIN)) {
					throw new AssertionError("A failed save changed the maps: " + dev.diorama.Maps.get().list(overworld));
				}
			});
			try {
				java.nio.file.Files.delete(blocker);
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}

			// Leave on the new map.
			context.runOnClient(mc -> dev.diorama.Maps.get().use(overworld, newId));
			context.setScreen(() -> null);
			context.waitTicks(40);
		}
		// A hand edit breaks the End's entry: the Overworld's maps still open, and the file is kept aside.
		java.nio.file.Path mapsFile = context.computeOnClient(mc -> WorldKey.folder(worldKeyLeft[0]).resolve("maps.json"));
		try {
			String text = java.nio.file.Files.readString(mapsFile);
			if (!text.contains("\"dimensions\": {")) {
				throw new AssertionError("Unexpected maps file: " + text);
			}
			java.nio.file.Files.writeString(mapsFile, text.replace("\"dimensions\": {", "\"dimensions\": {\"minecraft:the_end\": {\"maps\": \"broken\"},"));
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		try (TestSingleplayerContext reopened = save.open()) {
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 1200);
			context.runOnClient(mc -> {
				if (!dev.diorama.Maps.get().active(overworld).equals(newId)) {
					throw new AssertionError("Reopening the world didn't open the map in use when it was left");
				}
				if (WaypointStore.get().all().stream().noneMatch(w -> w.name.equals("New Base"))
					|| WaypointStore.get().all().stream().anyMatch(w -> w.name.equals("Old Base"))) {
					throw new AssertionError("Reopened on the new map, the waypoints should be its own");
				}
			});
			try (java.util.stream.Stream<java.nio.file.Path> kept = java.nio.file.Files.list(mapsFile.getParent())) {
				if (kept.noneMatch(f -> f.getFileName().toString().startsWith("maps.json.unreadable-"))) {
					throw new AssertionError("The maps file with a broken entry wasn't kept aside");
				}
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			context.setScreen(MapScreen::new);
			settle(context, 60);
			context.takeScreenshot("sub-maps-07-reopened");
			context.setScreen(() -> null);
		}
	}

	/**
	 * The dimension button, in a world of its own (opt-in: {@code map-dimensions}), used with the
	 * mouse (gliding, with GIF frames, under DIORAMA_GIF=1). It stays away until a second dimension
	 * has a map. From the Overworld it shows the Nether's saved map around the player's Nether
	 * coordinates, with that dimension's waypoints; what's added there is the Nether's, and nothing
	 * in its folder changes. It skips dimensions never visited, follows the map chosen in Maps,
	 * and teleports across dimensions.
	 */
	private static void mapDimensions(ClientGameTestContext context) {
		String overworld = "minecraft:overworld";
		String nether = "minecraft:the_nether";
		String end = "minecraft:the_end";
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setName("Diorama Dimensions");
				s.setSeed("diorama");
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
			context.setScreen(MapScreen::new);
			context.waitTicks(4);
			context.runOnClient(mc -> {
				if (((MapScreen) mc.gui.screen()).dimensionButton().visible) {
					throw new AssertionError("The dimension button shows with only one dimension mapped");
				}
			});
			context.setScreen(() -> null);

			// The Nether gets a map, a waypoint, and then the player leaves.
			world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 70 0");
			context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER && MapSession.get().map().chunkCount() > 200, 2400);
			settle(context, 100);
			java.nio.file.Path netherFolder = context.computeOnClient(mc -> dev.diorama.Maps.get().tileFolder(nether, dev.diorama.Maps.MAIN));
			context.waitFor(mc -> tileFiles(netherFolder) > 5, 1200);
			context.runOnClient(mc -> WaypointStore.get().add(new Waypoint("Nether Hub", 40, 70, 10, nether, Waypoint.PALETTE[5])));
			world.getServer().runCommand("execute in minecraft:overworld run tp @a 480 120 0");
			context.waitFor(mc -> mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD && mc.gui.screen() == null
				&& MapSession.get() != null && MapSession.get().map().chunkCount() > 200, 2400);
			settle(context, 100);
			context.runOnClient(mc -> WaypointStore.get().add(new Waypoint("Overworld Home", 480, 70, 10, overworld, Waypoint.PALETTE[2])));
			java.util.Map<String, String> before = folderState(netherFolder);

			context.setScreen(MapScreen::new);
			settle(context, 40);
			holdFrames(context, 20);
			context.takeScreenshot("map-dimensions-01-overworld");

			clickDimensionButton(context);
			waitForMapOf(context, nether);
			settle(context, 40);
			holdFrames(context, 30);
			context.takeScreenshot("map-dimensions-02-nether");
			context.runOnClient(mc -> {
				MapScreen map = (MapScreen) mc.gui.screen();
				// 480 in the Overworld is 60 in the Nether.
				if (Math.abs(map.view().centerU() - mc.player.getX() / 8) > 1) {
					throw new AssertionError("The Nether's map isn't around the player's Nether coordinates: " + map.view().centerU());
				}
				if (map.pins().stream().noneMatch(p -> p.waypoint().name.equals("Nether Hub")) || map.pins().stream().anyMatch(p -> p.waypoint().name.equals("Overworld Home"))) {
					throw new AssertionError("The map of the Nether should have the Nether's waypoints and no others");
				}
				if (map.exportButton().visible || !map.blips().isEmpty()) {
					throw new AssertionError("Export and the radar belong to the player's own dimension");
				}
			});

			// The right-click menu is about the Nether's blocks, with no distance from a player who isn't there.
			double[] spotAt = context.computeOnClient(mc -> {
				double scale = mc.getWindow().getGuiScale();
				MapScreen map = (MapScreen) mc.gui.screen();
				return new double[] {(map.width / 2.0 - 30) * scale, (map.height / 2.0 + 20) * scale};
			});
			rightClickAt(context, spotAt[0], spotAt[1]);
			holdFrames(context, 12);
			context.takeScreenshot("map-dimensions-03-menu");
			int[] spot = context.computeOnClient(mc -> {
				String line = ((MapScreen) mc.gui.screen()).menu().labels().get(0);
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("(-?\\d+), (-?\\d+), (-?\\d+)").matcher(line);
				if (!m.matches()) {
					throw new AssertionError("The spot menu of another dimension starts with " + line);
				}
				return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
			});
			clickMenu(context, "Add temporary waypoint");
			context.waitFor(mc -> WaypointStore.get().in(nether).stream().anyMatch(w -> w.kind == Waypoint.Kind.TEMPORARY && w.x == spot[0] && w.z == spot[2]), 40);

			// So is a pen stroke.
			clickWidget(context, context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).drawButton()));
			int strokes = context.computeOnClient(mc -> dev.diorama.drawing.DrawingStore.get().in(nether).size());
			moveCursor(context, spotAt[0] + 80, spotAt[1] + 40);
			context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			moveCursor(context, spotAt[0] + 200, spotAt[1] + 90);
			context.waitTicks(2);
			moveCursor(context, spotAt[0] + 300, spotAt[1] + 20);
			context.waitTicks(2);
			context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> dev.diorama.drawing.DrawingStore.get().in(nether).size() == strokes + 1, 40);
			clickWidget(context, context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).drawButton()));
			context.waitTicks(4);
			context.runOnClient(mc -> {
				if (((MapScreen) mc.gui.screen()).drawMode().active()) {
					throw new AssertionError("Draw mode stayed on");
				}
			});
			context.takeScreenshot("map-dimensions-04-drawn");

			// Never visited, the End is skipped: the next press is back home, with the live map, here
			// through the key, which is unbound until the player binds it.
			context.runOnClient(mc -> {
				DioramaClient.TOGGLE_DIMENSION.setKey(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_LEFT + 3));
				net.minecraft.client.KeyMapping.resetMapping();
			});
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT + 3);
			waitForMapOf(context, overworld);
			context.runOnClient(mc -> {
				DioramaClient.TOGGLE_DIMENSION.setKey(InputConstants.UNKNOWN);
				net.minecraft.client.KeyMapping.resetMapping();
			});

			// The Maps screen shows the dimension being looked at, and the view follows what's picked there.
			clickDimensionButton(context);
			waitForMapOf(context, nether);
			String second = context.computeOnClient(mc -> dev.diorama.Maps.get().create(nether, "Second"));
			context.waitFor(mc -> mc.gui.screen() instanceof MapScreen map && map.otherMap() != null && map.pins().stream().noneMatch(p -> p.waypoint().name.equals("Nether Hub")), 100);
			// Long enough for the view's tiles to have come up empty. Still empty, the map keeps its
			// place on the button: coming back from Maps rebuilds the screen.
			settle(context, 40);
			clickButton(context, "diorama.map.maps");
			context.waitFor(mc -> mc.gui.screen() instanceof dev.diorama.MapsScreen, 40);
			clickButton(context, "gui.done");
			context.waitFor(mc -> mc.gui.screen() instanceof MapScreen, 40);
			settle(context, 20);
			context.runOnClient(mc -> {
				MapScreen map = (MapScreen) mc.gui.screen();
				if (MapViewport.showsAnything(map.otherMap(), map.view())) {
					throw new AssertionError("A new map of the Nether shows the old one's tiles");
				}
				if (!map.dimensionButton().visible) {
					throw new AssertionError("A dimension's empty new map took the dimension button away");
				}
				dev.diorama.Maps.get().use(nether, dev.diorama.Maps.MAIN);
				if (!dev.diorama.Maps.get().delete(nether, second)) {
					throw new AssertionError("Couldn't delete the Nether's new map");
				}
			});
			waitForMapOf(context, nether);
			clickButton(context, "diorama.map.maps");
			context.waitFor(mc -> mc.gui.screen() instanceof dev.diorama.MapsScreen, 40);
			context.takeScreenshot("map-dimensions-05-maps-screen");
			clickButton(context, "gui.done");
			context.waitFor(mc -> mc.gui.screen() instanceof MapScreen, 40);
			waitForMapOf(context, nether);
			clickButton(context, "diorama.map.waypoints");
			context.waitFor(mc -> mc.gui.screen() instanceof dev.diorama.waypoint.WaypointListScreen, 40);
			context.runOnClient(mc -> {
				String folder = ((dev.diorama.waypoint.WaypointListScreen) mc.gui.screen()).folderName();
				if (!folder.equals("Nether")) {
					throw new AssertionError("The waypoint list opened on " + folder + " from the Nether's map");
				}
			});
			context.setScreen(MapScreen::new);
			clickDimensionButton(context);
			waitForMapOf(context, nether);

			java.util.Map<String, String> after = folderState(netherFolder);
			if (!after.equals(before)) {
				throw new AssertionError("Looking at the Nether's map changed its folder: " + before + " became " + after);
			}

			// Teleporting works from here, and a custom command can't be run into a dimension.
			context.runOnClient(mc -> {
				Settings.get().teleportCommand = "warp {x} {y} {z}";
				if (dev.diorama.waypoint.Waypoints.canTeleport(nether)) {
					throw new AssertionError("A custom teleport command was offered across dimensions");
				}
				Settings.get().teleportCommand = Settings.DEFAULT_TELEPORT_COMMAND;
				if (!dev.diorama.waypoint.Waypoints.canTeleport(nether)) {
					throw new AssertionError("Teleporting to the Nether wasn't offered");
				}
				if (dev.diorama.waypoint.Waypoints.canTeleport("diorama_test:gone")) {
					throw new AssertionError("Teleporting to a dimension the server doesn't have was offered");
				}
			});
			rightClickAt(context, spotAt[0], spotAt[1]);
			holdFrames(context, 8);
			clickMenu(context, "Teleport here");
			// Along the ground only: the spot can be the lava sea, which the player sinks into.
			context.waitFor(mc -> mc.level.dimension() == net.minecraft.world.level.Level.NETHER && Math.hypot(mc.player.getX() - spot[0] - 0.5, mc.player.getZ() - spot[2] - 0.5) < 2 && mc.gui.screen() == null, 400);
			settle(context, 40);
			for (int i = 0; i < (MENU_GIF ? 12 : 0); i++) {
				context.waitTicks(3);
				menuFrame(context);
			}
			context.takeScreenshot("map-dimensions-06-arrived");

			// With the End visited there are three stops. Going to the dimension being looked at
			// takes the map down with the loading screen, and it opens on the live map after.
			world.getServer().runCommand("execute in minecraft:the_end run tp @a 0 70 0");
			context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == net.minecraft.world.level.Level.END && mc.gui.screen() == null && MapSession.get().map().chunkCount() > 100, 2400);
			settle(context, 100);
			context.setScreen(MapScreen::new);
			settle(context, 10);
			clickDimensionButton(context);
			// Nothing follows the End but the Overworld, whose tiles it then shows.
			waitForMapOf(context, overworld);
			clickDimensionButton(context);
			waitForMapOf(context, nether);
			world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 70 0");
			context.waitFor(mc -> mc.level.dimension() == net.minecraft.world.level.Level.NETHER && mc.gui.screen() == null
				&& MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
			context.setScreen(MapScreen::new);
			waitForMapOf(context, nether);
			settle(context, 40);
			context.takeScreenshot("map-dimensions-07-walked-in");
			context.setScreen(() -> null);
			world.getServer().runCommand("execute in minecraft:overworld run tp @a 0 120 0");
			context.waitFor(mc -> mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD && mc.gui.screen() == null, 2400);
		}
	}

	/**
	 * Every language on the screens with the newest text, for reading it in the game (opt-in:
	 * {@code languages}): the game time, the dimension button and its map, the menu of a place not
	 * yet mapped, and each settings tab with a tooltip showing.
	 */
	private static void languages(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setName("Diorama Languages");
				s.setSeed("diorama");
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			world.getServer().runCommand("time set noon");
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
			world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 70 0");
			context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER && MapSession.get().map().chunkCount() > 200, 2400);
			settle(context, 100);
			java.nio.file.Path netherFolder = context.computeOnClient(mc -> dev.diorama.Maps.get().tileFolder("minecraft:the_nether", dev.diorama.Maps.MAIN));
			context.waitFor(mc -> tileFiles(netherFolder) > 5, 2400);
			world.getServer().runCommand("execute in minecraft:overworld run tp @a 0 120 0");
			context.waitFor(mc -> mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD && mc.gui.screen() == null
				&& MapSession.get() != null && MapSession.get().map().chunkCount() > 200, 2400);
			context.runOnClient(mc -> Settings.get().gameTime = Settings.GameTime.DAY_12H);
			for (String language : List.of("ru_ru", "be_by", "ja_jp", "zh_cn", "zh_tw")) {
				switchLanguage(context, language);
				context.waitTicks(10);
				context.takeScreenshot("languages-" + language + "-1-minimap");

				context.setScreen(MapScreen::new);
				settle(context, 10);
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(9000, 0, 2, TileKey.SURFACE));
				context.waitTicks(5);
				double[] middle = context.computeOnClient(mc -> {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {mc.gui.screen().width / 2.0 * scale, mc.gui.screen().height / 2.0 * scale};
				});
				rightClickAt(context, middle[0], middle[1]);
				context.takeScreenshot("languages-" + language + "-2-menu");
				context.getInput().pressKey(InputConstants.KEY_ESCAPE);
				context.setScreen(() -> null);
				context.setScreen(MapScreen::new);
				context.waitTicks(5);
				clickDimensionButton(context);
				waitForMapOf(context, "minecraft:the_nether");
				context.waitTicks(10);
				context.takeScreenshot("languages-" + language + "-3-other-map");
				context.setScreen(() -> null);

				context.setScreen(() -> new SettingsScreen(null));
				context.waitTicks(5);
				String[][] tooltips = {{"game_time"}, {"items"}, {"map_dimension_button"}, {"waypoint_size_minimap"}, {"saved_chunks"}};
				for (int tab = 0; tab < tooltips.length; tab++) {
					int index = tab;
					context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(index));
					context.waitTicks(3);
					String label = net.minecraft.network.chat.Component.translatable("diorama.settings." + tooltips[tab][0]).getString();
					double[] at = context.computeOnClient(mc -> {
						java.util.List<net.minecraft.client.gui.components.AbstractWidget> widgets = new java.util.ArrayList<>();
						collectWidgets(mc.gui.screen(), widgets);
						double scale = mc.getWindow().getGuiScale();
						for (var widget : widgets) {
							if (widget.getMessage().getString().startsWith(label)) {
								return new double[] {(widget.getX() + 8) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
							}
						}
						throw new AssertionError("No " + label + " in " + language);
					});
					context.getInput().setCursorPos(at[0], at[1]);
					context.waitTicks(14);
					context.takeScreenshot("languages-" + language + "-4-settings-" + tooltips[tab][0]);
				}
				context.setScreen(() -> null);
			}
			switchLanguage(context, "en_us");
			context.runOnClient(mc -> Settings.get().gameTime = Settings.GameTime.OFF);
		}
	}

	private static void switchLanguage(ClientGameTestContext context, String language) {
		reloadPacks(context, mc -> {
			mc.getLanguageManager().setSelected(language);
			mc.options.languageCode = language;
		});
	}

	/**
	 * A dimension Diorama has never heard of, from a datapack: the server names it, so once visited
	 * it joins the dimension button under its own name, and teleporting there works.
	 */
	private static void moddedDimension(ClientGameTestContext context) {
		//? if >=26.3 {
		String moon = "diorama_test:moon";
		TestWorldSave save;
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setName("Diorama Moon");
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			save = world.getWorldSave();
		}
		var format = context.computeOnClient(mc -> net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.SERVER_DATA));
		java.nio.file.Path pack = save.getSaveDirectory().resolve("datapacks").resolve("moon");
		try {
			java.nio.file.Files.createDirectories(pack.resolve("data/diorama_test/dimension"));
			java.nio.file.Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"moon\",\"min_format\":[" + format.major() + "," + format.minor() + "],\"max_format\":[" + format.major() + "," + format.minor() + "]}}");
			java.nio.file.Files.writeString(pack.resolve("data/diorama_test/dimension/moon.json"), """
				{"type": "minecraft:overworld", "generator": {"type": "minecraft:flat", "settings": {
					"biome": "minecraft:plains", "lakes": false, "features": false, "structure_overrides": [],
					"layers": [{"block": "minecraft:bedrock", "height": 1}, {"block": "minecraft:white_concrete", "height": 3}]}}}
				""");
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		try (TestSingleplayerContext world = save.open()) {
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 1200);
			world.getServer().runCommand("execute in " + moon + " run tp @a 0 40 0");
			context.waitFor(mc -> mc.level.dimension().identifier().toString().equals(moon) && mc.gui.screen() == null
				&& MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
			settle(context, 100);
			java.nio.file.Path folder = context.computeOnClient(mc -> dev.diorama.Maps.get().tileFolder(moon, dev.diorama.Maps.MAIN));
			context.waitFor(mc -> tileFiles(folder) > 5, 1200);
			world.getServer().runCommand("execute in minecraft:overworld run tp @a 0 120 0");
			context.waitFor(mc -> mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD && mc.gui.screen() == null
				&& MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
			settle(context, 60);
			context.setScreen(MapScreen::new);
			settle(context, 20);
			clickDimensionButton(context);
			waitForMapOf(context, moon);
			settle(context, 20);
			context.takeScreenshot("map-dimensions-08-modded");
			context.runOnClient(mc -> {
				String name = dev.diorama.waypoint.Waypoints.dimensionName(moon).getString();
				if (!name.equals("Moon")) {
					throw new AssertionError("The modded dimension is called " + name);
				}
			});
			// It has no waypoints, and still has its folder from here.
			clickButton(context, "diorama.map.waypoints");
			context.waitFor(mc -> mc.gui.screen() instanceof dev.diorama.waypoint.WaypointListScreen, 40);
			context.runOnClient(mc -> {
				String listed = ((dev.diorama.waypoint.WaypointListScreen) mc.gui.screen()).folderName();
				if (!listed.equals("Moon")) {
					throw new AssertionError("The waypoint list opened on " + listed + " from the Moon's map");
				}
			});
			clickButton(context, "gui.done");
			waitForMapOf(context, moon);
			double[] middle = context.computeOnClient(mc -> {
				double scale = mc.getWindow().getGuiScale();
				MapScreen map = (MapScreen) mc.gui.screen();
				return new double[] {map.width / 2.0 * scale, map.height / 2.0 * scale};
			});
			rightClickAt(context, middle[0], middle[1]);
			clickMenu(context, "Teleport here");
			context.waitFor(mc -> mc.level.dimension().identifier().toString().equals(moon) && mc.gui.screen() == null, 400);
		}
		//?}
	}

	/** Waits for the map on screen to show a dimension and something of it. */
	private static void waitForMapOf(ClientGameTestContext context, String dimension) {
		context.waitFor(mc -> {
			if (!(mc.gui.screen() instanceof MapScreen map) || !map.dimension().equals(dimension) || map.view() == null) {
				return false;
			}
			boolean own = dimension.equals(mc.level.dimension().identifier().toString());
			if (own != (map.otherMap() == null)) {
				return false;
			}
			return MapViewport.showsAnything(own ? MapSession.get().map() : map.otherMap(), map.view());
		}, 300);
	}

	private static void clickDimensionButton(ClientGameTestContext context) {
		clickWidget(context, context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).dimensionButton()));
		holdFrames(context, 2);
	}

	/** Moves to a widget and clicks it with the mouse. */
	private static void clickWidget(ClientGameTestContext context, net.minecraft.client.gui.components.AbstractWidget widget) {
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		moveCursor(context, (widget.getX() + widget.getWidth() / 2.0) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale);
		holdFrames(context, 6);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	/** Each file under a folder and its size and time, to see that nothing touched them. */
	private static java.util.Map<String, String> folderState(java.nio.file.Path folder) {
		java.util.Map<String, String> state = new java.util.TreeMap<>();
		try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(folder)) {
			for (java.nio.file.Path file : files.filter(java.nio.file.Files::isRegularFile).toList()) {
				state.put(folder.relativize(file).toString(), java.nio.file.Files.size(file) + "@" + java.nio.file.Files.getLastModifiedTime(file));
			}
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		return state;
	}

	/** Whether a map folder has a full-detail surface tile in the column of map units that x is in. */
	private static boolean hasTileAt(java.nio.file.Path folder, int x) {
		String column = Math.floorDiv(x, Projection.TILE_UNITS) + "_";
		try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(folder)) {
			return files.filter(java.nio.file.Files::isDirectory).filter(d -> d.getFileName().toString().startsWith("surface")).anyMatch(d -> {
				try (java.util.stream.Stream<java.nio.file.Path> tiles = java.nio.file.Files.list(d)) {
					return tiles.anyMatch(t -> t.getFileName().toString().startsWith(column) && t.toString().endsWith(".omt"));
				} catch (java.io.IOException e) {
					return false;
				}
			});
		} catch (java.io.IOException e) {
			return false;
		}
	}

	/** How many tile files a map folder holds, at every level. */
	private static long tileFiles(java.nio.file.Path folder) {
		if (!java.nio.file.Files.isDirectory(folder)) {
			return 0;
		}
		try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(folder)) {
			return files.filter(f -> f.toString().endsWith(".omt")).count();
		} catch (java.io.IOException | java.io.UncheckedIOException e) {
			return 0;
		}
	}

	private static String readFile(java.nio.file.Path file) {
		try {
			return java.nio.file.Files.readString(file);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Clicks a button by its label's key with the mouse. Under DIORAMA_GIF=1 the cursor glides there
	 * and each tick is a GIF frame (see moveCursor).
	 */
	private static void glideClick(ClientGameTestContext context, String translationKey) {
		String label = net.minecraft.network.chat.Component.translatable(translationKey).getString();
		double[] at = context.computeOnClient(mc -> {
			var button = buttonLabelled(mc.gui.screen(), label);
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(button.getX() + button.getWidth() / 2.0) * scale, (button.getY() + button.getHeight() / 2.0) * scale};
		});
		moveCursor(context, at[0], at[1]);
		holdFrames(context, 6);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
		holdFrames(context, 2);
	}

	/** Under DIORAMA_GIF=1, this many ticks of GIF frames with the cursor still. */
	private static void holdFrames(ClientGameTestContext context, int frames) {
		for (int i = 0; i < (MENU_GIF ? frames : 0); i++) {
			context.waitTick();
			menuFrame(context);
		}
	}

	/** Clicks a row of the Maps screen's list by its place, with the mouse. */
	private static void clickMapsRow(ClientGameTestContext context, int row) {
		double[] at = context.computeOnClient(mc -> new double[] {mc.gui.screen().width / 2.0 - 100, 48 + 2 + row * 26 + 12});
		clickAtGui(context, at[0], at[1]);
		context.waitTicks(6);
	}

	private static final long REALM_ID = 4242;

	/** What a visit to a realm does after joining. */
	private enum RealmVisit {
		/** Sets a waypoint and walks 600 blocks east. */
		FIRST,
		/** Finds that waypoint and the map near spawn. */
		RETURN,
		/** Another realm: finds neither. */
		OTHER
	}

	/**
	 * A realm that went to sleep wakes up at another address. Joined through Realms' own connection
	 * code at one address, then at another, the realm keeps its map and waypoints. The map an older
	 * Diorama kept under the realm's address becomes the realm's, and another realm on the same
	 * server and seed gets a folder of its own.
	 */
	//? if >=1.21.11 && fabric {
	private static void realms(ClientGameTestContext context) {
		String realm = WorldKey.key("realm", Long.toString(REALM_ID));
		String otherRealm = WorldKey.key("realm", Long.toString(REALM_ID + 1));
		String oldAddress = WorldKey.key("realms", "127.0.0.1");
		context.runOnClient(mc -> {
			try (java.util.stream.Stream<java.nio.file.Path> old = java.nio.file.Files.list(WorldKey.folder("."))) {
				for (java.nio.file.Path folder : old.filter(p -> p.getFileName().toString().startsWith(realm)
					|| p.getFileName().toString().startsWith(otherRealm) || p.getFileName().toString().startsWith(oldAddress)).toList()) {
					org.apache.commons.io.FileUtils.deleteDirectory(folder.toFile());
				}
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("diorama-realm"))
			.createServer(serverProperties())) {
			String first = playOnRealm(context, server, "localhost", REALM_ID, RealmVisit.FIRST);
			if (!first.startsWith(realm + "-")) {
				throw new AssertionError("The realm's folder isn't named by its ID: " + first);
			}
			// Where an older Diorama would have kept it: under the address the realm runs at next.
			String legacy = oldAddress + first.substring(realm.length());
			context.runOnClient(mc -> {
				try {
					java.nio.file.Files.move(WorldKey.folder(first), WorldKey.folder(legacy));
				} catch (java.io.IOException e) {
					throw new AssertionError(e);
				}
			});
			String woken = playOnRealm(context, server, "127.0.0.1", REALM_ID, RealmVisit.RETURN);
			if (!first.equals(woken)) {
				throw new AssertionError("The realm opened another folder at its new address: " + first + " then " + woken);
			}
			boolean legacyLeft = context.computeOnClient(mc -> java.nio.file.Files.exists(WorldKey.folder(legacy)));
			if (legacyLeft) {
				throw new AssertionError("The realm's map under its address wasn't moved to the realm's folder: " + legacy);
			}
			String other = playOnRealm(context, server, "127.0.0.1", REALM_ID + 1, RealmVisit.OTHER);
			if (!other.startsWith(otherRealm + "-")) {
				throw new AssertionError("Another realm at the same address got " + other);
			}
		}
	}

	/** Joins the server as realm {@code id} at {@code host}, does the visit's part, leaves, and returns the realm's folder. */
	private static String playOnRealm(ClientGameTestContext context, TestDedicatedServerContext server, String host, long id, RealmVisit visit) {
		net.minecraft.realms.RealmsConnect connect = context.computeOnClient(mc -> new net.minecraft.realms.RealmsConnect(new net.minecraft.client.gui.screens.TitleScreen()));
		context.setScreen(() -> new RealmsJoinScreen(connect));
		context.runOnClient(mc -> {
			com.mojang.realmsclient.dto.RealmsServer realm = new com.mojang.realmsclient.dto.RealmsServer();
			realm.id = id;
			realm.name = "Diorama Realm";
			connect.connect(realm, new net.minecraft.client.multiplayer.resolver.ServerAddress(host, SERVER_PORT));
		});
		context.waitFor(mc -> MapSession.get() != null && mc.player != null && MapSession.get().map().chunkCount() > 150, 2400);
		server.runCommand("time set noon");
		server.runCommand("weather clear");
		settle(context, 60);
		String joined = context.computeOnClient(mc -> mc.getCurrentServer() == null ? null : mc.getCurrentServer().isRealm() + " " + mc.getCurrentServer().ip);
		if (!("true " + host).equals(joined)) {
			throw new AssertionError("Not joined as a realm at " + host + ": " + joined);
		}
		String key = context.computeOnClient(mc -> WorldKey.current());
		dev.diorama.Diorama.LOGGER.info("Realm {} at {}: folder {}", id, host, key);
		List<String> names = context.computeOnClient(mc -> WaypointStore.get().all().stream().map(w -> w.name).toList());
		switch (visit) {
			case FIRST -> {
				context.runOnClient(mc -> {
					BlockPos at = mc.player.blockPosition();
					WaypointStore.get().add(new Waypoint("Realm Camp", at.getX(), at.getY(), at.getZ(), mc.level.dimension().identifier().toString(), Waypoint.PALETTE[3]));
				});
				for (int x = 200; x <= 600; x += 200) {
					server.runCommand("tp @a " + x + " 200 0");
					context.waitTicks(20);
					settle(context, 60);
				}
			}
			case RETURN -> {
				// Zoomed out over what the first visit walked. The player comes back where they left, at
				// x 600, so the map near spawn is from the first visit alone.
				context.setScreen(MapScreen::new);
				lookAtPlayer(context, 0.25, TileKey.SURFACE);
				settle(context, 40);
				context.takeScreenshot("realms-woken");
				context.setScreen(() -> null);
				float known = context.computeOnClient(mc -> overviewKnown(100, Projection.v(70, 0)));
				if (!names.contains("Realm Camp")) {
					throw new AssertionError("The realm lost its waypoint at its new address: " + names);
				}
				if (known < 0.5F) {
					throw new AssertionError("The realm lost its map at its new address: the strip east of spawn is " + known + " known");
				}
			}
			case OTHER -> {
				float known = context.computeOnClient(mc -> overviewKnown(100, Projection.v(70, 0)));
				if (names.contains("Realm Camp")) {
					throw new AssertionError("Another realm shows the first realm's waypoint: " + names);
				}
				if (known > 0.1F) {
					throw new AssertionError("Another realm shows the first realm's map: the strip east of spawn is " + known + " known");
				}
			}
		}
		context.runOnClient(mc -> {
			mc.level.disconnect(net.minecraft.network.chat.Component.literal("The realm went to sleep"));
			mc.disconnect(new net.minecraft.client.gui.screens.TitleScreen(), false);
		});
		context.waitFor(mc -> mc.level == null && MapSession.get() == null, 600);
		return key;
	}

	/** Ticks a Realms connection while it logs in, as Realms' connecting screen does. */
	private static final class RealmsJoinScreen extends net.minecraft.client.gui.screens.Screen {
		private final net.minecraft.realms.RealmsConnect connect;

		RealmsJoinScreen(net.minecraft.realms.RealmsConnect connect) {
			super(net.minecraft.network.chat.Component.literal("Joining a realm"));
			this.connect = connect;
		}

		@Override
		public void tick() {
			this.connect.tick();
		}
	}
	//?}

	/** Level ID messages as MapModCompanion sent them through Velocity, for two same-seed servers. */
	private static final byte[] LOBBY_ID = {0, 124, 86, -44, -107};
	private static final byte[] SURVIVAL_ID = {0, 4, -83, -79, -28};
	/** What the dedicated server sends a player a second after they join: a level ID message, or nothing. */
	private static byte @Nullable [] levelIdOnJoin;
	private static boolean joinHooked;
	//? if >=1.21 {
	private static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<dev.diorama.XaeroChannels.Message> WORLD_INFO =
		new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("worldinfo", "world_id"));
	//?}

	/**
	 * A network's two servers whose worlds share a seed, each naming its world with a level ID a
	 * second after the player joins, as MapModCompanion does: they keep separate maps and
	 * waypoints. A server that names its world without declaring a channel gets the seed's folder
	 * on the first visit, and keeps it for that visit when the ID comes. From the next visit on,
	 * the map waits for the ID, since the address named a world before.
	 */
	//? if >=1.21 && fabric {
	private static void serverLevelIds(ClientGameTestContext context) {
		String address = WorldKey.key("server", "localhost:" + SERVER_PORT);
		context.runOnClient(mc -> {
			try (java.util.stream.Stream<java.nio.file.Path> old = java.nio.file.Files.list(WorldKey.folder("."))) {
				for (java.nio.file.Path folder : old.filter(p -> p.getFileName().toString().startsWith(address)).toList()) {
					org.apache.commons.io.FileUtils.deleteDirectory(folder.toFile());
				}
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		if (!joinHooked) {
			joinHooked = true;
			net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(WORLD_INFO, dev.diorama.XaeroChannels.codec(WORLD_INFO));
			net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
				byte[] message = levelIdOnJoin;
				if (message != null) {
					Thread later = new Thread(() -> {
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
							return;
						}
						server.execute(() -> sender.sendPacket(new dev.diorama.XaeroChannels.Message(dev.diorama.XaeroChannels.MINIMAP, message[0], java.util.Arrays.copyOfRange(message, 1, message.length))));
					});
					later.setDaemon(true);
					later.start();
				}
			});
		}
		String seeded = playOnServer(context, "diorama-shared", "id-late", null, "Old Camp", List.of(), List.of(), null, LOBBY_ID);
		if (!seeded.startsWith(address + "-world-")) {
			throw new AssertionError("A level ID after the map opened moved it out of the seed's folder: " + seeded);
		}
		// The ID came, so this time the map waits for it, still with no channel declared.
		String lobby = playOnServer(context, "diorama-shared", "id-lobby", null, "Lobby Camp", List.of(), List.of("Old Camp"), false, LOBBY_ID);
		// Listen where MapModCompanion does, so the server declares a channel that comes with
		// level IDs, as servers that send them do.
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(WORLD_INFO, (message, ctx) -> {
		});
		String survival = playOnServer(context, "diorama-shared", "id-survival", null, "Survival Camp", List.of(), List.of("Old Camp", "Lobby Camp"), false, SURVIVAL_ID);
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.unregisterGlobalReceiver(WORLD_INFO.id());
		String lobbyAgain = playOnServer(context, "diorama-shared", "id-lobby-again", null, null, List.of("Lobby Camp"), List.of("Old Camp", "Survival Camp"), true, LOBBY_ID);
		// With Xaero's mods installed, the channels are theirs and Diorama has no handler there,
		// but it still reads the level ID on the way: survival opens its own folder again.
		dev.diorama.XaeroChannels.handOverForTests(true);
		String survivalWithXaero;
		try {
			survivalWithXaero = playOnServer(context, "diorama-shared", "id-survival-xaero", null, null, List.of("Survival Camp"), List.of("Old Camp", "Lobby Camp"), true, SURVIVAL_ID);
			levelIdBeforeHop(context, address, false);
			levelIdBeforeHop(context, address, true);
		} finally {
			dev.diorama.XaeroChannels.handOverForTests(false);
		}
		// Another mod holding the channels reads them on the way too.
		dev.diorama.XaeroChannels.leaveToAnotherModForTests(true);
		String survivalWithAnotherMod;
		try {
			survivalWithAnotherMod = playOnServer(context, "diorama-shared", "id-survival-other-mod", null, null, List.of("Survival Camp"), List.of("Old Camp", "Lobby Camp"), true, SURVIVAL_ID);
		} finally {
			dev.diorama.XaeroChannels.leaveToAnotherModForTests(false);
		}
		levelIdOnJoin = null;
		if (!survivalWithXaero.equals(survival)) {
			throw new AssertionError("With Xaero's mods holding the channels, survival opened " + survivalWithXaero + ", not " + survival);
		}
		if (!survivalWithAnotherMod.equals(survival)) {
			throw new AssertionError("With another mod holding the channels, survival opened " + survivalWithAnotherMod + ", not " + survival);
		}
		if (!lobby.equals(address + "-id-7c56d495") || !survival.equals(address + "-id-04adb1e4")) {
			throw new AssertionError("The level IDs didn't name the folders: " + lobby + ", " + survival);
		}
		if (!lobby.equals(lobbyAgain)) {
			throw new AssertionError("Coming back to the lobby opened another folder: " + lobby + " then " + lobbyAgain);
		}
		List<String> old = context.computeOnClient(mc -> WaypointStore.open(seeded).all().stream().map(w -> w.name).toList());
		if (!old.equals(List.of("Old Camp"))) {
			throw new AssertionError("The seed's folder lost its waypoint or took another world's: " + old);
		}
	}
	//?}

	/**
	 * A level ID the server sends just before moving the player to the Nether names the level it
	 * came in, not the Nether, which waits for its own: the ID keeps its place among the packets,
	 * also in a bundle ({@code bundled}), whose packets the client handles on its own thread. Run
	 * while Xaero's mods hold the channels, where Diorama reads IDs on the way to them.
	 */
	//? if >=1.21 && fabric {
	private static void levelIdBeforeHop(ClientGameTestContext context, String address, boolean bundled) {
		levelIdOnJoin = LOBBY_ID;
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("diorama-hop"))
			.createServer(serverProperties())) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
				String overworld = context.computeOnClient(mc -> WorldKey.current());
				// The overworld's ID again, then the move to the Nether. The client only runs when the
				// test lets it, so both are waiting when it next handles packets, as when they reach a
				// client mid-frame. It handles them and opens the Nether's map, or waits for its ID,
				// which comes after. However long that takes: on a slow machine the test's own wait
				// below can outlast the second the map gives a server to name a new level.
				WorldKey.waitForIdsForever = true;
				String nether;
				try {
					server.runOnServer(s -> {
						net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket id = new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
							new dev.diorama.XaeroChannels.Message(dev.diorama.XaeroChannels.MINIMAP, LOBBY_ID[0], java.util.Arrays.copyOfRange(LOBBY_ID, 1, LOBBY_ID.length)));
						s.getPlayerList().getPlayers().get(0).connection.send(bundled
							? new net.minecraft.network.protocol.game.ClientboundBundlePacket(List.<net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>>of(id))
							: id);
						s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), "execute in minecraft:the_nether run tp @a 0 100 0");
					});
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						throw new AssertionError(e);
					}
					context.waitTicks(5);
					sendXaero(server, SURVIVAL_ID);
					context.waitFor(mc -> mc.level != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER && WorldKey.current() != null, 600);
					nether = context.computeOnClient(mc -> WorldKey.current());
				} finally {
					WorldKey.waitForIdsForever = false;
				}
				levelIdOnJoin = null;
				if (!overworld.equals(address + "-id-7c56d495") || !nether.equals(address + "-id-04adb1e4")) {
					throw new AssertionError("The ID sent " + (bundled ? "in a bundle " : "") + "before the move to the Nether named the wrong level: overworld " + overworld + ", Nether " + nether);
				}
			}
		}
	}
	//?}

	/** Bob's offline-mode UUID, and his position as XaeroTracker sent it: x 640.5, y 120, z 30.5 in the overworld. */
	private static final UUID BOB = UUID.fromString("faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b");
	private static final byte[] BOB_TRACKED = {2, 10, 1, 0, 1, 114, 0, 11, 0, 1, 105, 0, 0, 0, 4, -6, -91, -36, -93, -61, -44, 53, 75, -82, 27, -35, -23, -27, -95, 75, 59,
		6, 0, 1, 120, 64, -124, 4, 0, 0, 0, 0, 0, 6, 0, 1, 121, 64, 94, 0, 0, 0, 0, 0, 0, 6, 0, 1, 122, 64, 62, -128, 0, 0, 0, 0, 0,
		8, 0, 1, 100, 0, 19, 109, 105, 110, 101, 99, 114, 97, 102, 116, 58, 111, 118, 101, 114, 119, 111, 114, 108, 100, 0};

	/**
	 * A player far beyond the loaded chunks whom the server tracks, as Xaero's player tracker
	 * reports them: once the client has answered the server's handshake, they show on the map
	 * with their face and name, move when the server says they moved, hide under fair play, and
	 * go when the server stops tracking them.
	 */
	//? if >=1.21 && fabric {
	private static void trackedPlayers(ClientGameTestContext context) {
		java.util.Properties properties = serverProperties();
		java.util.concurrent.atomic.AtomicInteger answers = new java.util.concurrent.atomic.AtomicInteger();
		// Bob is higher up than the radar's default height range reaches, which hides players as it does mobs.
		int range = context.computeOnClient(mc -> Settings.get().radarRange);
		context.runOnClient(mc -> Settings.get().radarRange = 128);
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(dev.diorama.XaeroChannels.MINIMAP, (message, ctx) -> {
			if (message.kind() == 1 && java.util.Arrays.equals(message.body(), new byte[] {0, 0, 0, 3})) {
				answers.incrementAndGet();
			}
		});
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("diorama-tracked"))
			.createServer(properties)) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				server.runCommand("time set noon");
				server.runCommand("weather clear");
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 150, 2400);
				server.runOnServer(s -> {
					// Bob is on the tab list, as players elsewhere on the server are, but not in the
					// client's world: he's far away.
					net.minecraft.server.level.ServerPlayer alice = s.getPlayerList().getPlayers().get(0);
					net.minecraft.server.level.ServerPlayer bob = new net.minecraft.server.level.ServerPlayer(s, s.overworld(), new GameProfile(BOB, "Bob"),
						net.minecraft.server.level.ClientInformation.createDefault());
					// The tab list entry reads the player's latency from their connection.
					bob.connection = alice.connection;
					alice.connection.send(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bob)));
					sendXaero(alice, new byte[] {1, 0, 0, 0, 3});
				});
				context.waitFor(mc -> answers.get() > 0, 200);
				sendXaero(server, BOB_TRACKED);
				context.setScreen(MapScreen::new);
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(600, Projection.v(120, 30.5), 1.0, TileKey.SURFACE));
				settle(context, 20);
				context.takeScreenshot("tracked-players-01-far");
				waitForBob(context, 640.5, "where XaeroTracker put him");

				byte[] moved = BOB_TRACKED.clone();
				java.nio.ByteBuffer.wrap(moved, indexAfter(moved, new byte[] {6, 0, 1, 'x'}), 8).putDouble(580.5);
				sendXaero(server, moved);
				waitForBob(context, 580.5, "after he moved");
				context.takeScreenshot("tracked-players-02-moved");

				server.runCommand("tellraw @a \"§f§a§i§r§x§a§e§r§o\"");
				waitForBob(context, null, "under fair play");
				context.takeScreenshot("tracked-players-03-fair-play");
				server.runCommand("tellraw @a \"§r§e§s§e§t§x§a§e§r§o\"");
				waitForBob(context, 580.5, "after fair play ended");

				// A remove names only the player, as servers send it.
				byte[] uuid = java.util.Arrays.copyOfRange(BOB_TRACKED, indexAfter(BOB_TRACKED, new byte[] {11, 0, 1, 'i'}), indexAfter(BOB_TRACKED, new byte[] {11, 0, 1, 'i'}) + 20);
				java.io.ByteArrayOutputStream removed = new java.io.ByteArrayOutputStream();
				removed.writeBytes(new byte[] {2, 10, 1, 0, 1, 'r', 1, 11, 0, 1, 'i'});
				removed.writeBytes(uuid);
				removed.write(0);
				sendXaero(server, removed.toByteArray());
				waitForBob(context, null, "after the server stopped tracking him");
				// Tracked again, then the tracker reset that comes before the server sends everyone again.
				sendXaero(server, BOB_TRACKED);
				waitForBob(context, 640.5, "when tracked again");
				sendXaero(server, new byte[] {3});
				waitForBob(context, null, "after the tracker reset");
				context.setScreen(() -> null);
			}
		} finally {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.unregisterGlobalReceiver(dev.diorama.XaeroChannels.MINIMAP.id());
			context.runOnClient(mc -> Settings.get().radarRange = range);
		}
	}

	/** Diorama's answer goes out on a channel it has no codec for, as when another mod has the channel. */
	private static void answerOnAnotherModsChannel() {
		net.minecraft.resources.Identifier channel = net.minecraft.resources.Identifier.fromNamespaceAndPath("diorama-test", "no-codec");
		dev.diorama.XaeroChannels.Message answer = new dev.diorama.XaeroChannels.Message(
			new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(channel), 1, new byte[] {0, 0, 0, 3});
		net.minecraft.network.FriendlyByteBuf written = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket.STREAM_CODEC.encode(written, new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(answer));
		if (!written.readIdentifier().equals(channel) || written.readableBytes() != 5 || written.readByte() != 1 || written.readInt() != 3) {
			throw new AssertionError("The answer wasn't written as it is: " + answer);
		}
	}

	private static void sendXaero(TestDedicatedServerContext server, byte[] message) {
		server.runOnServer(s -> sendXaero(s.getPlayerList().getPlayers().get(0), message));
	}

	private static void sendXaero(net.minecraft.server.level.ServerPlayer player, byte[] message) {
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
			new dev.diorama.XaeroChannels.Message(dev.diorama.XaeroChannels.MINIMAP, message[0], java.util.Arrays.copyOfRange(message, 1, message.length)));
	}
	//?}

	private static int indexAfter(byte[] bytes, byte[] pattern) {
		for (int i = 0; i + pattern.length <= bytes.length; i++) {
			if (java.util.Arrays.equals(bytes, i, i + pattern.length, pattern, 0, pattern.length)) {
				return i + pattern.length;
			}
		}
		throw new AssertionError("Not in the fixture: " + java.util.Arrays.toString(pattern));
	}

	/** Waits until Bob is on the fullscreen map where block x {@code x}, y 120, z 30.5 draws, or not on it at all if null. */
	private static void waitForBob(ClientGameTestContext context, @Nullable Double x, String when) {
		for (int tick = 0; tick < 400; tick++) {
			String wrong = context.computeOnClient(mc -> {
				MapScreen screen = (MapScreen) mc.gui.screen();
				Radar.Blip bob = screen.blips().stream().filter(b -> b.entity().getUUID().equals(BOB)).findFirst().orElse(null);
				if (x == null) {
					return bob == null ? null : "Bob is on the map at " + bob.x() + ", " + bob.y();
				}
				if (bob == null) {
					return "Bob isn't on the map";
				}
				float wantX = screen.view().screenX(x);
				float wantY = screen.view().screenY(Projection.v(120, 30.5));
				return Math.abs(bob.x() - wantX) <= 1 && Math.abs(bob.y() - wantY) <= 1 ? null
					: "Bob is drawn at " + bob.x() + ", " + bob.y() + ", not " + wantX + ", " + wantY;
			});
			if (wrong == null) {
				return;
			}
			if (tick == 399) {
				throw new AssertionError(wrong + " " + when);
			}
			context.waitTicks(1);
		}
	}

	/**
	 * Joins a fresh dedicated server for this seed, always on the same address, and checks its
	 * waypoints. The first visit to a world adds a waypoint and maps a strip east of spawn that
	 * the other world won't load. {@code stripKnown}, if not null, is whether that strip should
	 * show on the zoomed-out map from what's saved. {@code levelId}, if not null, is a level ID
	 * message the server sends a second after the player joins. Returns the world's folder key.
	 */
	private static String playOnServer(ClientGameTestContext context, String seed, String name, @Nullable String beforeJoin,
		@Nullable String addWaypoint, List<String> expected, List<String> unexpected, @Nullable Boolean stripKnown, byte @Nullable [] levelId) {
		levelIdOnJoin = levelId;
		java.util.Properties properties = serverProperties();
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed(seed))
			.createServer(properties)) {
			if (beforeJoin != null) {
				server.runCommand(beforeJoin);
			}
			try (TestDedicatedServerConnection connection = server.connect()) {
				server.runCommand("time set noon");
				server.runCommand("weather clear");
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 150, 2400);
				settle(context, 60);
				// The fullscreen map zoomed out, before this visit has spread far: anything
				// beyond the loaded chunks would be another world's saved map.
				context.setScreen(MapScreen::new);
				lookAtPlayer(context, 0.25, TileKey.SURFACE);
				settle(context, 40);
				context.takeScreenshot("multiplayer-" + name);
				if (stripKnown != null) {
					float known = context.computeOnClient(mc -> overviewKnown(500, Projection.v(70, 0)));
					if ((known > 0.5F) != stripKnown) {
						throw new AssertionError("The first world's strip east of spawn is " + known + " known on the " + name + " world");
					}
				}
				context.setScreen(() -> null);
				List<String> names = context.computeOnClient(mc -> WaypointStore.get().all().stream().map(w -> w.name).toList());
				dev.diorama.Diorama.LOGGER.info("Server world {}: waypoints {}", name, names);
				for (String want : expected) {
					if (!names.contains(want)) {
						throw new AssertionError("The " + name + " world is missing waypoint " + want + ": " + names);
					}
				}
				for (String other : unexpected) {
					if (names.contains(other)) {
						throw new AssertionError("Another world's waypoint shows on the " + name + " world: " + names);
					}
				}
				if (addWaypoint != null) {
					context.runOnClient(mc -> {
						BlockPos at = mc.player.blockPosition();
						WaypointStore.get().add(new Waypoint(addWaypoint, at.getX(), at.getY(), at.getZ(), mc.level.dimension().identifier().toString(), Waypoint.PALETTE[3]));
					});
					for (int x = 200; x <= 600; x += 200) {
						server.runCommand("tp @a " + x + " 200 0");
						context.waitTicks(20);
						settle(context, 60);
					}
				}
				return context.computeOnClient(mc -> WorldKey.current());
			}
		}
	}

	/**
	 * A spawn tile with a fully known coverage cell: {tileU, tileV, cellX, cellY}. The map was
	 * baked there in this session, so it should come back from disk after a restart.
	 */
	private static int[] knownSpawnCell(ClientGameTestContext context) {
		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 2.0, TileKey.SURFACE);
		settle(context, 60);
		int[] cell = context.computeOnClient(mc -> {
			double u = mc.player.getX();
			double v = Projection.v(mc.player.getY(), mc.player.getZ());
			int tu = (int) Math.floor(u / Projection.TILE_UNITS);
			int tv = (int) Math.floor(v / Projection.TILE_UNITS);
			MapTile tile = MapSession.get().map().peek(new TileKey(TileKey.SURFACE, tu, tv));
			if (tile == null || !tile.hasCoverage()) {
				throw new AssertionError("The spawn tile wasn't baked");
			}
			double cellUnits = (double) Projection.TILE_UNITS / Projection.COVERAGE_CELLS;
			int cx = (int) ((u - tu * Projection.TILE_UNITS) / cellUnits);
			int cy = (int) ((v - tv * Projection.TILE_UNITS) / cellUnits);
			if (tile.coverage(cx, cy) < 0.9F) {
				throw new AssertionError("The spawn tile isn't known under the player");
			}
			return new int[] {tu, tv, cx, cy};
		});
		spawnPosition = context.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		spawnProbes = spawnProbes(context, cell);
		context.setScreen(() -> null);
		return cell;
	}

	/** Where the player stood when the spawn cell was found. */
	private static double[] spawnPosition = new double[3];

	/**
	 * The scenarios before the restart build and dig around spawn, so where the menu puts the player
	 * there is taken again just before leaving, from spawn's chunks as they are then. Taken at the
	 * start, it compared the saved map with ground that had since changed, and failed whenever a
	 * whole suite ran.
	 */
	private static void spawnProbesBeforeLeaving(ClientGameTestContext context, TestSingleplayerContext world, int[] cell) {
		world.getServer().runCommand("execute in minecraft:overworld run tp @a " + spawnPosition[0] + " " + spawnPosition[1] + " " + spawnPosition[2]);
		int chunkX = (int) Math.floor(spawnPosition[0]) >> 4, chunkZ = (int) Math.floor(spawnPosition[2]) >> 4;
		// The player there, not just the chunk: the map may still hold spawn's chunk from earlier. Not
		// the height, as the ground they land on may have changed.
		context.waitFor(mc -> mc.player != null && mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD
			&& Math.abs(mc.player.getX() - spawnPosition[0]) < 0.5 && Math.abs(mc.player.getZ() - spawnPosition[2]) < 0.5
			&& MapSession.get() != null && MapSession.get().map().chunk(chunkX, chunkZ) != null, 2400);
		settle(context, 100);
		spawnProbes = spawnProbes(context, cell);
	}

	/**
	 * Where the right-click menu puts the player around spawn while its chunks are loaded, to
	 * compare with after the restart. Several places, as a waypoint may cover one by then.
	 */
	private static List<double[]> spawnProbes(ClientGameTestContext context, int[] cell) {
		double cellUnits = (double) Projection.TILE_UNITS / Projection.COVERAGE_CELLS;
		// The middle of a block column and of a pixel row.
		double u0 = cell[0] * Projection.TILE_UNITS + cell[2] * cellUnits + 0.5;
		double v0 = cell[1] * Projection.TILE_UNITS + cell[3] * cellUnits + 2.5 / Projection.PIXELS_PER_UNIT;
		return context.computeOnClient(mc -> {
			List<double[]> probes = new java.util.ArrayList<>();
			for (int[] offset : new int[][] {{0, 0}, {12, 0}, {-12, 0}, {0, 12}, {0, -12}, {12, 12}, {-12, -12}}) {
				double u = u0 + offset[0];
				double v = v0 + offset[1];
				BlockPos spot = dev.diorama.bake.MapPick.standingSpot(MapSession.get().map(), dev.diorama.bake.BlockLooks.get(),
					u, v, TileKey.SURFACE, false);
				if (spot != null) {
					probes.add(new double[] {u, v, spot.getX(), spot.getY(), spot.getZ()});
				}
			}
			if (probes.isEmpty()) {
				throw new AssertionError("Nothing to stand on around spawn while its chunks are loaded");
			}
			return probes;
		});
	}

	/** Map points around spawn and where the right-click menu put the player there before the restart: {u, v, x, y, z}. */
	private static List<double[]> spawnProbes = List.of();

	/**
	 * Looks at spawn and returns the first of {@link #spawnProbes} with no waypoint pin over it,
	 * and where it is in the window: {u, v, x, y, z, windowX, windowY}.
	 */
	private static double[] freeSpawnProbe(ClientGameTestContext context) {
		double[] first = spawnProbes.get(0);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(first[0], first[1], 4.0, TileKey.SURFACE));
		settle(context, 20);
		return context.computeOnClient(mc -> {
			MapScreen map = (MapScreen) mc.gui.screen();
			MapViewport.View view = map.view();
			double scale = mc.getWindow().getGuiScale();
			for (double[] probe : spawnProbes) {
				double x = view.screenX(probe[0]);
				double y = view.screenY(probe[1]);
				if (WaypointOverlay.under(map.pins(), x, y) == null) {
					return new double[] {probe[0], probe[1], probe[2], probe[3], probe[4], x * scale, y * scale};
				}
			}
			throw new AssertionError("Waypoint pins cover every place the test looked at around spawn");
		});
	}

	/**
	 * After leaving and reopening the world 3000 blocks from spawn: the spawn tile shows from disk
	 * alone, and the fog-of-war corridor is still seen.
	 */
	private static void savedMap(ClientGameTestContext context, TestSingleplayerContext world, int[] spawnCell, boolean checkFogOfWar) {
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
		settle(context, 60);
		// Map u is block x, so a tile this far from the player has no loaded chunks under it.
		double distance = context.computeOnClient(mc -> Math.abs(mc.player.getX() - (spawnCell[0] + 0.5) * Projection.TILE_UNITS));
		if (distance < 1000) {
			throw new AssertionError("The spawn tile is " + distance + " blocks away; the test can't tell disk from memory");
		}
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			double u = (spawnCell[0] + (spawnCell[2] + 0.5) / Projection.COVERAGE_CELLS) * Projection.TILE_UNITS;
			double v = (spawnCell[1] + (spawnCell[3] + 0.5) / Projection.COVERAGE_CELLS) * Projection.TILE_UNITS;
			((MapScreen) mc.gui.screen()).lookAt(u, v, 1.0, TileKey.SURFACE);
		});
		settle(context, 60);
		context.takeScreenshot("saved-map-01-spawn-after-restart");
		float known = context.computeOnClient(mc -> {
			MapTile tile = MapSession.get().map().peek(new TileKey(TileKey.SURFACE, spawnCell[0], spawnCell[1]));
			return tile == null || !tile.hasCoverage() ? 0 : tile.coverage(spawnCell[2], spawnCell[3]);
		});
		if (known < 0.9F) {
			throw new AssertionError("The spawn tile didn't come back from disk (known share " + known + ")");
		}

		// The right-click menu still knows where to stand there, from disk alone.
		double[] probe = freeSpawnProbe(context);
		int[] spot = {(int) probe[2], (int) probe[3], (int) probe[4]};
		rightClickAt(context, probe[5], probe[6]);
		context.takeScreenshot("saved-map-01b-menu-after-restart");
		List<String> labels = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).menu().labels());
		if (labels.contains("Nothing mapped here")) {
			throw new AssertionError("After the restart, the menu says nothing is mapped at spawn, which the map shows");
		}
		int[] savedSpot = menuSpot(context);
		if (!java.util.Arrays.equals(savedSpot, spot)) {
			throw new AssertionError("After the restart, the menu offers " + java.util.Arrays.toString(savedSpot)
				+ " at spawn; before it, " + java.util.Arrays.toString(spot));
		}

		// A tile saved by Diorama 0.3.0 or older has no spots: in singleplayer the menu finds the
		// ground in the world's save instead (#145).
		context.setScreen(MapScreen::new);
		double[] oldProbe = freeSpawnProbe(context);
		TileKey oldTile = new TileKey(TileKey.SURFACE, (int) Math.floor(oldProbe[0] / Projection.TILE_UNITS), (int) Math.floor(oldProbe[1] / Projection.TILE_UNITS));
		MapLevel level = context.computeOnClient(mc -> MapSession.get().map());
		level.forgetSpots(oldTile);
		if (level.hasSavedSpots(oldTile)) {
			throw new AssertionError("Tile " + oldTile + " still has spots after forgetting them");
		}
		rightClickAt(context, oldProbe[5], oldProbe[6]);
		context.takeScreenshot("saved-map-01c-menu-on-old-tile");
		List<String> oldLabels = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).menu().labels());
		if (oldLabels.contains("Nothing mapped here")) {
			throw new AssertionError("On a tile saved without spots, the menu says nothing is mapped at spawn, which the map shows");
		}
		int[] worldSpot = menuSpot(context);
		int[] oldSpot = {(int) oldProbe[2], (int) oldProbe[3], (int) oldProbe[4]};
		if (!java.util.Arrays.equals(worldSpot, oldSpot)) {
			throw new AssertionError("On a tile saved without spots, the menu offers " + java.util.Arrays.toString(worldSpot)
				+ " at spawn; with its chunks loaded, " + java.util.Arrays.toString(oldSpot));
		}
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			double u = (spawnCell[0] + (spawnCell[2] + 0.5) / Projection.COVERAGE_CELLS) * Projection.TILE_UNITS;
			double v = (spawnCell[1] + (spawnCell[3] + 0.5) / Projection.COVERAGE_CELLS) * Projection.TILE_UNITS;
			((MapScreen) mc.gui.screen()).lookAt(u, v, 1.0, TileKey.SURFACE);
		});

		// Zoom far out: overview tiles shrunk from the saved ones show both spawn and here.
		// Travel back toward spawn, so there's a trail of explored land to zoom out over. The
		// minimap is off, so no map draws the trail: only the background bakes can save it.
		double[] far = context.computeOnClient(mc -> new double[] {mc.player.getX(), Projection.v(mc.player.getY(), mc.player.getZ())});
		double[] away = new double[2];
		context.setScreen(() -> null);
		boolean minimapWasOn = context.computeOnClient(mc -> Settings.get().minimap);
		context.runOnClient(mc -> Settings.get().minimap = false);
		try {
			for (int stop = 2750; stop >= 750; stop -= 250) {
				world.getServer().runCommand("tp @a " + stop + " 200 " + stop);
				// New terrain generates around the player a few chunks at a time.
				context.waitTicks(200);
				if (stop == 1750) {
					context.runOnClient(mc -> {
						away[0] = mc.player.getX();
						away[1] = Projection.v(mc.player.getY(), mc.player.getZ());
					});
				}
			}
		} finally {
			context.runOnClient(mc -> Settings.get().minimap = minimapWasOn);
		}
		double spawnU = (spawnCell[0] + (spawnCell[2] + 0.5) / Projection.COVERAGE_CELLS) * Projection.TILE_UNITS;
		double spawnV = (spawnCell[1] + (spawnCell[3] + 0.5) / Projection.COVERAGE_CELLS) * Projection.TILE_UNITS;
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt((spawnU + far[0]) / 2, (spawnV + far[1]) / 2, 0.06, TileKey.SURFACE));
		context.waitFor(mc -> overviewKnown(spawnU, spawnV) >= 0.9F && overviewKnown(away[0], away[1]) >= 0.9F, 1200);
		settle(context, 20);
		context.takeScreenshot("saved-map-02-zoomed-out");
		if ("1".equals(System.getenv("DIORAMA_GIF"))) {
			// Frames for a GIF: a steady zoom out from spawn until both spawn and the player show.
			for (int frame = 0; frame < 70; frame++) {
				double t = frame / 69.0;
				context.runOnClient(mc -> {
					double su = (spawnCell[0] + 0.5) * Projection.TILE_UNITS;
					double sv = (spawnCell[1] + 0.5) * Projection.TILE_UNITS;
					double pu = (spawnU + far[0]) / 2;
					double pv = (spawnV + far[1]) / 2;
					double ease = t * t * (3 - 2 * t);
					((MapScreen) mc.gui.screen()).lookAt(su + (pu - su) * ease, sv + (pv - sv) * ease, Math.pow(0.06, t), TileKey.SURFACE);
				});
				context.waitTicks(2);
				context.takeScreenshot("saved-map-zoom-" + String.format("%02d", frame));
			}
		}
		context.setScreen(() -> null);
		if (checkFogOfWar) {
			boolean seen = context.computeOnClient(mc -> MapSession.get().map().isSeen(115, -39, 116));
			if (!seen) {
				throw new AssertionError("Fog-of-war progress was lost across the restart");
			}
		}

		// Teleport here, from far away, lands on the ground at spawn.
		context.setScreen(MapScreen::new);
		double[] teleportAt = freeSpawnProbe(context);
		rightClickAt(context, teleportAt[5], teleportAt[6]);
		clickMenu(context, "Teleport here");
		int[] landing = {(int) teleportAt[2], (int) teleportAt[3], (int) teleportAt[4]};
		context.waitFor(mc -> mc.player.distanceToSqr(landing[0] + 0.5, landing[1], landing[2] + 0.5) < 0.5, 200);
		// The chunks there arrive after the player does.
		BlockPos below = new BlockPos(landing[0], landing[1] - 1, landing[2]);
		for (int tick = 0; tick < 400 && context.computeOnClient(mc -> mc.level.getBlockState(below).isAir()); tick += 10) {
			context.waitTicks(10);
		}
		context.takeScreenshot("saved-map-03-teleported-to-spawn");
		context.runOnClient(mc -> {
			if (mc.level.getBlockState(below).isAir()) {
				throw new AssertionError("Teleport here from the saved map put the player over air at " + java.util.Arrays.toString(landing)
					+ " (chunk loaded: " + mc.level.hasChunk(landing[0] >> 4, landing[2] >> 4) + ", player at " + mc.player.position() + ")");
			}
		});
	}

	static java.util.Properties serverProperties() {
		java.util.Properties properties = new java.util.Properties();
		properties.setProperty("server-port", String.valueOf(SERVER_PORT));
		properties.setProperty("gamemode", "creative");
		// No watchdog: on a slow machine (software rendering on a boat.dev VM) the server can wait
		// over its 60 s limit for the client's turn, and the watchdog takes that for a hang.
		properties.setProperty("max-tick-time", "-1");
		return properties;
	}

	private static int freePort() {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	/** Known share of the overview tile drawn at a map point, or 0 if none is loaded. */
	private static float overviewKnown(double u, double v) {
		for (int lod = 1; lod <= TileKey.MAX_LOD; lod++) {
			double units = (double) Projection.TILE_UNITS * (1 << lod);
			int tu = (int) Math.floor(u / units);
			int tv = (int) Math.floor(v / units);
			MapTile tile = MapSession.get().map().peek(new TileKey(TileKey.SURFACE, tu, tv, lod));
			if (tile != null && tile.hasCoverage()) {
				double cell = units / Projection.COVERAGE_CELLS;
				return tile.coverage((int) ((u - tu * units) / cell), (int) ((v - tv * units) / cell));
			}
		}
		return 0F;
	}

	/**
	 * Crossing between dimensions keeps the block color table: building it takes seconds with a
	 * big modpack's states, and it used to be rebuilt on every crossing (#150). Walks through a
	 * nether portal and comes back with /execute in, three times, and checks the table is never
	 * rebuilt and the map only ever moves with the player. Then reloads resources, which must
	 * rebuild it once. Works with Immersive Portals installed too, whose portals keep the other
	 * side's level loaded. Opt-in: DIORAMA_TESTS=portal-crossing.
	 */
	private static void portalCrossing(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos p = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = p.getX(), y = p.getY(), z = p.getZ();
		world.getServer().runCommand("fill " + (x - 4) + " " + (y - 2) + " " + (z - 4) + " " + (x + 8) + " " + (y - 1) + " " + (z + 12) + " stone");
		world.getServer().runCommand("fill " + (x - 4) + " " + y + " " + (z - 4) + " " + (x + 8) + " " + (y + 8) + " " + (z + 12) + " air");
		int fz = z + 4;
		world.getServer().runCommand("fill " + x + " " + (y - 1) + " " + fz + " " + (x + 3) + " " + (y + 3) + " " + fz + " obsidian");
		world.getServer().runCommand("fill " + (x + 1) + " " + y + " " + fz + " " + (x + 2) + " " + (y + 2) + " " + fz + " air");
		String start = (x + 2) + " " + y + " " + z + ".5 0 0";
		int builds = context.computeOnClient(mc -> BlockLooks.builds());
		int sessions = context.computeOnClient(mc -> MapSession.started());
		world.getServer().runCommand("setblock " + (x + 1) + " " + y + " " + fz + " fire");
		// With Immersive Portals, chunks of the Nether stream in through the open portal. The map
		// stays on the overworld.
		crossing(context, "portal lit", 100);
		context.takeScreenshot("portal-crossing-01-lit");
		if (context.computeOnClient(mc -> MapSession.started()) != sessions) {
			throw new AssertionError("The map left the overworld while the player stood by the portal");
		}
		for (int round = 0; round < 3; round++) {
			context.runOnClient(mc -> {
				mc.player.snapTo(x + 2.0, y, z + 0.5, 0F, 0F);
				mc.options.keyUp.setDown(true);
			});
			crossing(context, "walk through " + round, 120);
			context.runOnClient(mc -> mc.options.keyUp.setDown(false));
			if (round == 0) {
				context.takeScreenshot("portal-crossing-02-nether");
			}
			if (context.computeOnClient(mc -> mc.level.dimension() != net.minecraft.world.level.Level.NETHER)) {
				throw new AssertionError("Walking through the portal didn't reach the Nether");
			}
			teleport(context, world, net.minecraft.world.level.Level.OVERWORLD, start);
			crossing(context, "back with /execute in " + round, 100);
		}
		teleport(context, world, net.minecraft.world.level.Level.NETHER, "0 70 0");
		crossing(context, "/execute in the Nether", 100);
		teleport(context, world, net.minecraft.world.level.Level.OVERWORLD, start);
		crossing(context, "/execute in the overworld", 100);
		int rebuilt = context.computeOnClient(mc -> BlockLooks.builds()) - builds;
		if (rebuilt != 0) {
			throw new AssertionError("Crossing between dimensions rebuilt the block color table " + rebuilt + " times");
		}
		int started = context.computeOnClient(mc -> MapSession.started()) - sessions;
		if (started != 8) {
			throw new AssertionError("8 crossings started " + started + " map sessions");
		}
		context.runOnClient(mc -> mc.reloadResourcePacks());
		context.waitFor(mc -> BlockLooks.builds() > builds, 2400);
		crossing(context, "after a resource reload", 100);
		if (context.computeOnClient(mc -> BlockLooks.builds()) != builds + 1) {
			throw new AssertionError("A resource reload rebuilt the block color table more than once");
		}
	}

	/** Teleports the player into another dimension and waits until they are there. */
	private static void teleport(ClientGameTestContext context, TestSingleplayerContext world, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, String at) {
		world.getServer().runCommand("execute in " + dimension.identifier() + " run tp @a " + at);
		context.waitFor(mc -> mc.level.dimension() == dimension && MapSession.get() != null, 2400);
	}

	/** Waits some ticks and logs the slowest one, and the block color builds and map sessions so far. */
	private static void crossing(ClientGameTestContext context, String label, int ticks) {
		long last = System.nanoTime();
		long slowest = 0;
		for (int i = 0; i < ticks; i++) {
			context.waitTick();
			long now = System.nanoTime();
			slowest = Math.max(slowest, now - last);
			last = now;
		}
		Diorama.LOGGER.info("[portal-crossing] {}: slowest tick {} ms; {} block color builds and {} map sessions so far", label, slowest / 1_000_000,
			(Object) context.<Integer, RuntimeException>computeOnClient(mc -> BlockLooks.builds()), (Object) context.<Integer, RuntimeException>computeOnClient(mc -> MapSession.started()));
	}

	/** The Nether and the End: the minimap, and the fullscreen map near and far. */
	private static void dimensions(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 70 0");
		context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER && MapSession.get().map().chunkCount() > 200, 2400);
		settle(context, 100);
		context.takeScreenshot("dimensions-01-nether-minimap");
		context.setScreen(MapScreen::new);
		settle(context, 60);
		context.takeScreenshot("dimensions-02-nether-map");
		// The sky view under the roof: the whole Nether from above, roof left out.
		lookAtPlayer(context, 3.0, TileKey.SURFACE);
		settle(context, 80);
		context.takeScreenshot("dimensions-03-nether-under-roof");
		lookAtPlayer(context, 0.8, TileKey.SURFACE);
		settle(context, 80);
		context.takeScreenshot("dimensions-03b-nether-under-roof-far");
		// Zoomed out past 0.5 the map draws overview tiles, built from the saved under-roof tiles.
		context.waitTicks(200);
		lookAtPlayer(context, 0.2, TileKey.SURFACE);
		settle(context, 80);
		context.takeScreenshot("dimensions-03c-nether-under-roof-overview");
		context.setScreen(() -> null);

		world.getServer().runCommand("execute in minecraft:the_end run tp @a 0 70 0");
		context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == net.minecraft.world.level.Level.END && MapSession.get().map().chunkCount() > 100, 2400);
		settle(context, 100);
		context.takeScreenshot("dimensions-04-end-minimap");
		context.setScreen(MapScreen::new);
		settle(context, 60);
		context.takeScreenshot("dimensions-05-end-map");
		lookAtPlayer(context, 0.8, TileKey.SURFACE);
		settle(context, 80);
		context.takeScreenshot("dimensions-06-end-far");
		context.setScreen(() -> null);
		world.getServer().runCommand("execute in minecraft:overworld run tp @a 0 100 0");
		// Back home the level switches before the loading screen closes; the next scenario starts
		// with a minimap screenshot, so wait for the screen to go and the map to fill in again.
		context.waitFor(mc -> mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD && mc.gui.screen() == null
			&& MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		settle(context, 100);
	}

	/**
	 * Times baking the same nine tiles in each look, in rotation, keeping each one's best: the
	 * first passes include warming up, and the map's own bakes run alongside.
	 */
	private static void bench(ClientGameTestContext context) {
		String[] modes = {"default", "flat at 50 degrees", "flat at 60 degrees"};
		java.util.Map<String, Long> best = new java.util.LinkedHashMap<>();
		context.waitTicks(200);
		for (int round = 0; round < 4; round++) {
			for (String mode : modes) {
				MapStyle style = mode.equals("default") ? MapStyle.DEFAULT : MapStyle.FLAT.withPitch(mode.contains("50") ? 50 : 60);
				context.runOnClient(mc -> {
					MapStyle.set(style);
					double u = mc.player.getX();
					double v = Projection.v(mc.player.getY(), mc.player.getZ());
					int tu = (int) Math.floor(u / Projection.TILE_UNITS);
					int tv = (int) Math.floor(v / Projection.TILE_UNITS);
					long start = System.nanoTime();
					for (int du = -1; du <= 1; du++) {
						for (int dv = -1; dv <= 1; dv++) {
							dev.diorama.bake.TileBaker.bake(new TileKey(TileKey.SURFACE, tu + du, tv + dv), MapSession.get().map(), dev.diorama.bake.BlockLooks.get(), null, false);
						}
					}
					best.merge(mode, System.nanoTime() - start, Math::min);
				});
			}
		}
		best.forEach((mode, nanos) -> dev.diorama.Diorama.LOGGER.info("BENCH {}: {} ms per tile", mode, nanos / 9 / 1_000_000));
		context.runOnClient(mc -> MapStyle.set(Settings.get().style()));
	}

	/**
	 * The GPU bake experiment: bakes the nine tiles around a few places with the tracer and on the
	 * GPU, in the sky view, a cave slice with and without fog of war, and under the Nether's roof;
	 * saves both and their difference as pictures in {@code gpu-bake/}, and logs what each cost. Opt-in with {@code DIORAMA_TESTS=gpu-bake}; {@code DIORAMA_GPU_SPOTS="x y z;x y z"}
	 * picks the places.
	 */
	private static void gpuBake(ClientGameTestContext context, TestSingleplayerContext world) {
		String env = System.getenv("DIORAMA_GPU_SPOTS");
		String[] spots = env != null && !env.isBlank() ? env.split(";") : new String[] {"", "600 140 -300", "-800 140 900"};
		java.nio.file.Path out = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("gpu-bake"));
		StringBuilder summary = new StringBuilder();
		for (int spot = 0; spot < spots.length; spot++) {
			if (!spots[spot].isBlank()) {
				world.getServer().runCommand("tp @a " + spots[spot]);
				context.waitTicks(40);
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
			}
			settle(context, 200);
			// The sky view, then a cave slice a little below the ground, with and without fog of war.
			int cut = context.computeOnClient(mc -> mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, mc.player.getBlockX(), mc.player.getBlockZ()) - 24);
			summary.append(compareTiles(context, out, "spot" + spot, TileKey.SURFACE, false, null)).append('\n');
			if (spot == 0) {
				// The chunk grid, slime chunks and spawn spots, which the bakes record per pixel.
				dev.diorama.bake.Overlays overlays = context.computeOnClient(mc -> new dev.diorama.bake.Overlays(MapSession.get().slimeSeed(), true));
				summary.append(compareTiles(context, out, "spot0-overlays", TileKey.SURFACE, false, overlays)).append('\n');
			}
			summary.append(compareTiles(context, out, "spot" + spot + "-cave", cut, false, null)).append('\n');
			// Fog of war shows only what the player has seen: mark every other 16x16 square of
			// columns as seen through the slice, so both seen and unseen ground get compared.
			context.runOnClient(mc -> {
				dev.diorama.data.SeenBlocks seen = MapSession.get().map().seen();
				int px = mc.player.getBlockX();
				int pz = mc.player.getBlockZ();
				for (int x = px - 200; x < px + 200; x++) {
					for (int z = pz - 300; z < pz + 300; z++) {
						if ((((x >> 4) + (z >> 4)) & 1) == 0) {
							for (int y = cut - dev.diorama.bake.TileBaker.CAVE_DEPTH - 1; y <= cut + 1; y++) {
								seen.markSeen(x, y, z);
							}
						}
					}
				}
			});
			summary.append(compareTiles(context, out, "spot" + spot + "-cave-fog", cut, true, null)).append('\n');
		}
		// Blocks smaller than a cube, which both bakers draw by their shapes.
		shapesScene(context, world);
		summary.append(compareTiles(context, out, "shapes", TileKey.SURFACE, false, null)).append('\n');
		// The Nether: its views start under the roof, lit as if the rock over the first open space
		// were lifted off and fading into the biome's fog, its cave slices too.
		String[] nether = {"0 70 0", "300 70 -200"};
		for (int n = 0; n < nether.length; n++) {
			world.getServer().runCommand("execute in minecraft:the_nether run tp @a " + nether[n]);
			context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER && MapSession.get().map().chunkCount() > 300, 2400);
			settle(context, 200);
			summary.append(compareTiles(context, out, "nether" + n, TileKey.SURFACE, false, null)).append('\n');
			if (n == 0) {
				dev.diorama.bake.Overlays overlays = context.computeOnClient(mc -> new dev.diorama.bake.Overlays(MapSession.get().slimeSeed(), true));
				summary.append(compareTiles(context, out, "nether0-overlays", TileKey.SURFACE, false, overlays)).append('\n');
			}
			int cut = context.computeOnClient(mc -> mc.player.getBlockY() - 8);
			summary.append(compareTiles(context, out, "nether" + n + "-cave", cut, false, null)).append('\n');
		}
		world.getServer().runCommand("execute in minecraft:overworld run tp @a 0 100 0");
		context.waitFor(mc -> mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD
			&& MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		try {
			java.nio.file.Files.createDirectories(out);
			java.nio.file.Files.writeString(out.resolve("summary.txt"), summary.toString());
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		// The map baked its own tiles on the GPU all along, after checking its first ones against
		// the tracer. These tiles matched, so that check must have too.
		if (dev.diorama.render.gpu.GpuBakeCheck.failed()) {
			throw new AssertionError("The map's GPU baking check failed while these tiles matched: see the log");
		}
	}

	/**
	 * A flat field of blocks smaller than a cube, a 3x3 patch each: slabs, stairs every way, fences,
	 * walls, panes, snow of every height, small things that stand on the ground, and some of them
	 * waterlogged in a pool. The player stands in the middle of it.
	 */
	private static void shapesScene(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("tp @a 1600 140 1600");
		context.waitTicks(40);
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		int y = context.computeOnClient(mc -> mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, 1600, 1600));
		int x0 = 1560;
		int z0 = 1560;
		// Cleared in strips: a fill may change only so many blocks at once.
		for (int x = x0; x < x0 + 80; x += 16) {
			world.getServer().runCommand("fill " + x + " " + y + " " + z0 + " " + (x + 15) + " " + (y + 24) + " " + (z0 + 79) + " air");
		}
		world.getServer().runCommand("fill " + x0 + " " + (y - 1) + " " + z0 + " " + (x0 + 79) + " " + (y - 1) + " " + (z0 + 79) + " grass_block");
		String[] blocks = {
			"oak_slab", "oak_slab[type=top]", "stone_slab", "smooth_stone_slab[type=top]",
			"stone_brick_stairs[facing=north]", "stone_brick_stairs[facing=south]", "stone_brick_stairs[facing=east]", "stone_brick_stairs[facing=west]",
			"oak_stairs[half=top,facing=north]", "oak_stairs[half=top,facing=south]", "brick_stairs[facing=north,shape=outer_left]", "brick_stairs[facing=south,shape=inner_right]",
			"oak_fence", "cobblestone_wall", "glass_pane", "iron_bars",
			"snow[layers=1]", "snow[layers=2]", "snow[layers=3]", "snow[layers=4]",
			"snow[layers=5]", "snow[layers=6]", "snow[layers=7]", "dirt_path",
			"farmland", "soul_sand", "chest", "lantern",
			"oak_trapdoor", "oak_trapdoor[half=top,open=false]", "cauldron", "hopper",
			"anvil", "stonecutter", "enchanting_table", "daylight_detector",
			"lectern", "campfire[lit=false]", "composter", "end_rod",
			// Panes joined to their neighbours: a strip and a cross, whose boxes overlap.
			"glass_pane[east=true,west=true]", "glass_pane[north=true,south=true,east=true,west=true]",
			// Off the sixteenths grid (the chain is iron_chain from 1.21.9; the other name fails).
			"chain", "iron_chain",
		};
		for (int b = 0; b < blocks.length; b++) {
			int bx = x0 + 2 + (b % 8) * 5;
			int bz = z0 + 2 + (b / 8) * 5;
			world.getServer().runCommand("fill " + bx + " " + y + " " + bz + " " + (bx + 2) + " " + y + " " + (bz + 2) + " " + blocks[b]);
		}
		// Slabs on slabs, a fence on a wall: shapes stacked.
		world.getServer().runCommand("fill " + (x0 + 2) + " " + (y + 1) + " " + (z0 + 2) + " " + (x0 + 4) + " " + (y + 1) + " " + (z0 + 4) + " oak_slab");
		world.getServer().runCommand("fill " + (x0 + 17) + " " + (y + 1) + " " + (z0 + 17) + " " + (x0 + 19) + " " + (y + 1) + " " + (z0 + 19) + " oak_fence");
		// A pool to the east, with waterlogged blocks in it, some level with the water's top.
		int px = x0 + 45;
		world.getServer().runCommand("fill " + px + " " + (y - 3) + " " + z0 + " " + (px + 30) + " " + (y - 1) + " " + (z0 + 30) + " water");
		String[] wet = {
			"oak_slab[waterlogged=true]", "oak_slab[type=top,waterlogged=true]", "stone_brick_stairs[facing=north,waterlogged=true]", "stone_brick_stairs[facing=south,waterlogged=true]",
			"oak_fence[waterlogged=true]", "glass_pane[waterlogged=true]", "lantern[waterlogged=true]", "chest[waterlogged=true]",
			"glass_pane[north=true,south=true,waterlogged=true]", "glass_pane[north=true,south=true,east=true,west=true,waterlogged=true]",
		};
		for (int b = 0; b < wet.length; b++) {
			int bx = px + 2 + (b % 4) * 7;
			int bz = z0 + 2 + (b / 4) * 7;
			world.getServer().runCommand("fill " + bx + " " + (y - 1) + " " + bz + " " + (bx + 2) + " " + (y - 1) + " " + (bz + 2) + " " + wet[b]);
			world.getServer().runCommand("fill " + bx + " " + (y - 3) + " " + (bz + 4) + " " + (bx + 2) + " " + (y - 3) + " " + (bz + 4) + " " + wet[b]);
		}
		world.getServer().runCommand("tp @a 1600 " + (y + 1) + " 1600");
		settle(context, 200);
	}

	/**
	 * Bakes the nine tiles of one layer around the player with the tracer and on the GPU, three
	 * rounds each keeping the best (the first GPU round also compiles the shaders), and writes the
	 * comparison. {@code layer} is {@link TileKey#SURFACE} or a cave slice's cut.
	 */
	private static String compareTiles(
		ClientGameTestContext context, java.nio.file.Path out, String name, int layer, boolean fog, dev.diorama.bake.@org.jspecify.annotations.Nullable Overlays overlays
	) {
		int[] centre = context.computeOnClient(mc -> new int[] {
			(int) Math.floor(mc.player.getX() / Projection.TILE_UNITS),
			(int) Math.floor(Projection.v(layer == TileKey.SURFACE ? mc.player.getY() : layer, mc.player.getZ()) / Projection.TILE_UNITS)
		});
		java.util.List<TileKey> keys = new java.util.ArrayList<>();
		for (int dv = -1; dv <= 1; dv++) {
			for (int du = -1; du <= 1; du++) {
				keys.add(new TileKey(layer, centre[0] + du, centre[1] + dv));
			}
		}
		int[][] cpu = new int[keys.size()][];
		byte[][] cpuInfo = new byte[keys.size()][];
		long[] cpuNanos = new long[keys.size()];
		java.util.Arrays.fill(cpuNanos, Long.MAX_VALUE);
		java.util.Map<Integer, dev.diorama.render.gpu.GpuTileBaker.Result> gpu = new java.util.concurrent.ConcurrentHashMap<>();
		for (int round = 0; round < 3; round++) {
			context.runOnClient(mc -> {
				for (int i = 0; i < keys.size(); i++) {
					long start = System.nanoTime();
					dev.diorama.bake.TileImage image = dev.diorama.bake.TileBaker.bake(keys.get(i), MapSession.get().map(), dev.diorama.bake.BlockLooks.get(), null, fog, null, overlays);
					cpuNanos[i] = Math.min(cpuNanos[i], System.nanoTime() - start);
					cpu[i] = image.mips()[0];
					cpuInfo[i] = image.info();
				}
			});
			java.util.Map<Integer, dev.diorama.render.gpu.GpuTileBaker.Result> results = new java.util.concurrent.ConcurrentHashMap<>();
			context.runOnClient(mc -> {
				for (int i = 0; i < keys.size(); i++) {
					int index = i;
					dev.diorama.bake.GpuMesher.Mesh mesh = dev.diorama.bake.GpuMesher.build(keys.get(i), MapSession.get().map(), dev.diorama.bake.BlockLooks.get(), fog, overlays);
					dev.diorama.render.gpu.GpuTileBaker.submit(mesh, r -> results.put(index, r));
				}
			});
			context.waitFor(mc -> results.size() == keys.size(), 600);
			// Every round has to match, not only the one kept for its timing: a bake that went
			// wrong once mustn't hide behind a quicker right one.
			writeGpuComparison(out, name + " round " + round, keys, cpu, cpuNanos, results, false);
			if (round == 0) {
				gpu.putAll(results);
				continue;
			}
			for (var e : results.entrySet()) {
				var old = gpu.get(e.getKey());
				if (old == null || old.pixels() == null || e.getValue().timing().meshNanos() + e.getValue().timing().recordNanos()
					< old.timing().meshNanos() + old.timing().recordNanos()) {
					gpu.put(e.getKey(), e.getValue());
				}
			}
		}
		String report = writeGpuComparison(out, name, keys, cpu, cpuNanos, gpu, true);
		if (overlays != null) {
			// Overlay bits are compared where both bakes know the pixel.
			long both = 0;
			long differ = 0;
			for (int i = 0; i < keys.size(); i++) {
				var r = gpu.get(i);
				if (r != null && r.declined()) {
					// Handed to the tracer on purpose: no GPU bits to compare.
					continue;
				}
				if (r == null || r.info() == null || cpuInfo[i] == null || r.pixels() == null) {
					throw new AssertionError(name + ": tile " + keys.get(i) + " came back without overlay bits");
				}
				for (int p = 0; p < cpuInfo[i].length; p++) {
					if ((cpu[i][p] >>> 24) > 0 && (r.pixels()[p] >>> 24) > 0) {
						both++;
						if (cpuInfo[i][p] != r.info()[p]) {
							differ++;
						}
					}
				}
			}
			if (both == 0) {
				throw new AssertionError(name + ": overlays were asked for but no bits came back to compare");
			}
			double overlayOff = differ * 100.0 / both;
			if (overlayOff > 1.5) {
				throw new AssertionError(String.format(java.util.Locale.ROOT, "%s: overlay bits differ on %.3f%% of known pixels", name, overlayOff));
			}
			report += String.format(java.util.Locale.ROOT, " | overlay bits differ on %.3f%% of known pixels", overlayOff);
		}
		dev.diorama.Diorama.LOGGER.info("GPUBAKE {}", report);
		return report;
	}

	/** Saves the traced and GPU tiles as 3x3 pictures with their difference, and sums up both. */
	private static String writeGpuComparison(
		java.nio.file.Path out, String name, java.util.List<TileKey> keys, int[][] cpu, long[] cpuNanos,
		java.util.Map<Integer, dev.diorama.render.gpu.GpuTileBaker.Result> gpu, boolean save
	) {
		int size = Projection.TILE_PIXELS;
		java.awt.image.BufferedImage cpuImage = new java.awt.image.BufferedImage(size * 3, size * 3, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.image.BufferedImage gpuImage = new java.awt.image.BufferedImage(size * 3, size * 3, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.image.BufferedImage diffImage = new java.awt.image.BufferedImage(size * 3, size * 3, java.awt.image.BufferedImage.TYPE_INT_RGB);
		long both = 0;
		long differ = 0;
		long knownMismatch = 0;
		// Pixels both bakes know, but to a different share of their samples: that decides which
		// pixel a merge with the saved tile keeps, and the fog's coverage.
		long alphaDiffer = 0;
		double sumDiff = 0;
		double cpuMs = 0;
		double mesh = 0;
		double pack = 0;
		double record = 0;
		double gpuMs = 0;
		double latency = 0;
		long faces = 0;
		long casters = 0;
		long bytes = 0;
		int failed = 0;
		int declined = 0;
		for (int i = 0; i < keys.size(); i++) {
			cpuMs += cpuNanos[i] / 1e6;
			var result = gpu.get(i);
			int[] g = result == null ? null : result.pixels();
			if (result != null && result.declined()) {
				// Handed to the tracer on purpose (something the GPU doesn't draw as it does): nothing to compare.
				declined++;
				continue;
			}
			if (g == null) {
				failed++;
			} else {
				var t = result.timing();
				mesh += t.meshNanos() / 1e6;
				pack += t.packNanos() / 1e6;
				record += t.recordNanos() / 1e6;
				gpuMs += t.gpuNanos() / 1e6;
				latency += t.latencyNanos() / 1e6;
				faces += t.faces();
				casters += t.casters();
				bytes += t.bytes();
			}
			int ox = (i % 3) * size;
			int oy = (i / 3) * size;
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					int c = cpu[i][y * size + x];
					int p = g == null ? 0 : g[y * size + x];
					cpuImage.setRGB(ox + x, oy + y, argb(c));
					gpuImage.setRGB(ox + x, oy + y, argb(p));
					boolean ck = (c >>> 24) > 0;
					boolean gk = (p >>> 24) > 0;
					if (ck != gk) {
						knownMismatch++;
						diffImage.setRGB(ox + x, oy + y, 0xFF00FF);
						continue;
					}
					if (!ck) {
						continue;
					}
					if ((c >>> 24) != (p >>> 24)) {
						alphaDiffer++;
					}
					int dr = Math.abs((c & 0xFF) - (p & 0xFF));
					int dg = Math.abs(((c >> 8) & 0xFF) - ((p >> 8) & 0xFF));
					int db = Math.abs(((c >> 16) & 0xFF) - ((p >> 16) & 0xFF));
					both++;
					sumDiff += (dr + dg + db) / 3.0;
					if (Math.max(dr, Math.max(dg, db)) > 24) {
						differ++;
					}
					diffImage.setRGB(ox + x, oy + y, (Math.min(255, dr * 4) << 16) | (Math.min(255, dg * 4) << 8) | Math.min(255, db * 4));
				}
			}
		}
		int n = keys.size();
		int compared = n - declined;
		int ok = Math.max(1, compared - failed);
		double mean = both == 0 ? 0 : sumDiff / both;
		double off = both == 0 ? 0 : differ * 100.0 / both;
		double mismatch = knownMismatch * 100.0 / ((long) Math.max(1, compared) * size * size);
		double alphaOff = both == 0 ? 0 : alphaDiffer * 100.0 / both;
		// Fail on a GPU bake that didn't come back, on a comparison of nothing, and on differences
		// well past what the sample patterns explain (about twice what the bakes measured).
		boolean differs = mean > 4 || off > 6 || mismatch > 1.5 || alphaOff > 8;
		// A failing round keeps its pictures too: they're what shows what went wrong.
		if (save || failed > 0 || both == 0 || differs) {
			try {
				java.nio.file.Files.createDirectories(out);
				javax.imageio.ImageIO.write(cpuImage, "png", out.resolve(name + "-cpu.png").toFile());
				javax.imageio.ImageIO.write(gpuImage, "png", out.resolve(name + "-gpu.png").toFile());
				javax.imageio.ImageIO.write(diffImage, "png", out.resolve(name + "-diff.png").toFile());
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}
		if (failed > 0) {
			throw new AssertionError(name + ": " + failed + " GPU bakes failed");
		}
		if (declined == n) {
			throw new AssertionError(name + ": the GPU declined every tile, nothing was compared");
		}
		if (both == 0) {
			throw new AssertionError(name + ": no pixel known to both bakes, nothing was compared");
		}
		if (differs) {
			throw new AssertionError(String.format(java.util.Locale.ROOT,
				"%s: GPU tiles differ from traced ones: mean %.2f/255, %.2f%% off by >24, %.3f%% known/unknown, %.3f%% alpha", name, mean, off, mismatch, alphaOff));
		}
		return String.format(java.util.Locale.ROOT,
			"%s tiles=%d failed=%d declined=%d | cpu trace %.1f ms/tile | gpu: mesh %.1f, pack %.1f, render thread %.2f, gpu %.2f, submit-to-pixels %.1f ms/tile; %d faces, %d casters, %.2f MB/tile"
				+ " | diff: mean %.2f/255, %.2f%% pixels off by >24, %.3f%% known/unknown mismatch, %.3f%% known with a different alpha",
			name, n, failed, declined, cpuMs / n, mesh / ok, pack / ok, record / ok, gpuMs / ok, latency / ok, faces / ok, casters / ok, bytes / (double) ok / 1e6,
			mean, off, mismatch, alphaOff
		);
	}

	/** ABGR as the tiles store it to ARGB for a picture. */
	private static int argb(int abgr) {
		return (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
	}

	/**
	 * Builds {@link ShowcaseScene}'s castle beside the nearest village and photographs it for the
	 * store page: in game with the minimap, the fullscreen map from close up to far out, the caves
	 * under the castle, waypoints on the map and in the world, and the Nether over a fortress.
	 * Opt-in with {@code DIORAMA_TESTS=showcase}; {@code DIORAMA_GIF=1} adds the demo video's frames.
	 */
	private static void showcase(ClientGameTestContext context, TestSingleplayerContext world) {
		context.getInput().resizeWindow(1920, 1080);
		world.getServer().runCommand("difficulty peaceful");
		BlockPos village = world.getServer().computeOnServer(server ->
			server.overworld().findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, 100, false));
		if (village == null) {
			throw new AssertionError("No village near spawn");
		}
		int cx = village.getX() + Integer.getInteger("diorama.castleDx", 0);
		int cz = village.getZ() + Integer.getInteger("diorama.castleDz", -110);
		world.getServer().runCommand("tp @a " + cx + " 200 " + (cz + 40));
		context.waitTicks(100);
		ShowcaseScene scene = world.getServer().computeOnServer(server -> ShowcaseScene.site(server.overworld(), cx, cz));
		int base = scene.base();
		world.getServer().runCommand("time set noon");
		dev.diorama.Diorama.LOGGER.info("Showcase castle at {} {} {}, village at {}", cx, base, cz, village);
		context.runOnClient(mc -> {
			Settings.get().radarNeutral = false;
			Settings.get().radarPassive = false;
		});
		context.getInput().setCursorPos(0, 0);

		// Copper roofs by default; DIORAMA_ROOFS=1 photographs the other roofs that were tried.
		String[][] roofs = "1".equals(System.getenv("DIORAMA_ROOFS"))
			? new String[][] {
				{"deepslate_tiles", "deepslate_tile_stairs"},
				{"dark_prismarine", "dark_prismarine_stairs"},
				{"red_nether_bricks", "red_nether_brick_stairs"},
				{"bricks", "brick_stairs"},
				{"waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper_stairs"}}
			: new String[][] {{"waxed_oxidized_cut_copper", "waxed_oxidized_cut_copper_stairs"}};
		double v = Projection.v(base + 12, cz);
		for (String[] roof : roofs) {
			world.getServer().runOnServer(server -> scene.castle(roof[0], roof[1]));
			if (roofs.length > 1) {
				context.setScreen(MapScreen::new);
				context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, 3.2, TileKey.SURFACE));
				settle(context, 100);
				context.takeScreenshot("showcase-roof-" + roof[0]);
				context.setScreen(() -> null);
			}
		}

		// In game: past the wheat fields, looking up the road to the gate.
		world.getServer().runCommand("gamemode spectator @a");
		world.getServer().runCommand("tp @a " + (cx + 0.5) + " " + (base + 4) + " " + (cz + 92.5) + " 180 -4");
		context.waitTicks(60);
		settle(context, 60);
		context.takeScreenshot("showcase-01-in-game");

		context.setScreen(MapScreen::new);
		for (double zoom : new double[] {6, 3.2, 2.2, 1.2, 0.8}) {
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, v, zoom, TileKey.SURFACE));
			settle(context, 60);
			context.takeScreenshot("showcase-map-zoom-" + zoom);
		}
		if ("1".equals(System.getenv("DIORAMA_GIF"))) {
			context.setScreen(() -> null);
			demo(context, world, cx, base, cz);
		}

		// The cave view under the castle.
		int caveY = Integer.getInteger("diorama.showcaseCaveY", 30);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, Projection.v(caveY, cz), 2.4, caveY));
		settle(context, 100);
		context.takeScreenshot("showcase-cave");
		context.setScreen(() -> null);

		// Waypoints: on the map, then as labels in the world, seen from the village.
		int villageY = world.getServer().computeOnServer(server ->
			server.overworld().getChunk(village).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, village.getX() & 15, village.getZ() & 15));
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			String overworld = "minecraft:overworld";
			store.add(new Waypoint("Castle", cx, base + 1, cz - 10, overworld, Waypoint.PALETTE[3]));
			store.add(new Waypoint("Village", village.getX(), villageY, village.getZ(), overworld, Waypoint.PALETTE[1]));
			store.add(new Waypoint("Wheat", cx + 12, base - 4, cz + 66, overworld, Waypoint.PALETTE[2]));
			store.add(new Waypoint("Iron mine", cx + 150, base - 30, cz - 40, overworld, Waypoint.PALETTE[6]));
		});
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(cx + 0.5, Projection.v(base, cz + 50), 1.3, TileKey.SURFACE));
		settle(context, 60);
		context.takeScreenshot("showcase-waypoints-map");
		context.setScreen(() -> null);
		world.getServer().runCommand("tp @a " + (cx + 14.5) + " " + (base + 3) + " " + (cz + 120.5) + " 172 -6");
		context.waitTicks(60);
		settle(context, 40);
		context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
		context.takeScreenshot("showcase-waypoints-in-world");

		// The Nether from above, over the nearest fortress.
		BlockPos fortress = world.getServer().computeOnServer(server -> {
			ServerLevel nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
			HolderSet<Structure> wanted = HolderSet.direct(server.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.FORTRESS));
			var found = nether.getChunkSource().getGenerator().findNearestMapStructure(nether, wanted, BlockPos.ZERO, 100, false);
			return found == null ? new BlockPos(48, 70, 48) : found.getFirst();
		});
		world.getServer().runCommand("execute in minecraft:the_nether run tp @a " + fortress.getX() + " 75 " + fortress.getZ());
		context.waitFor(mc -> MapSession.get() != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER
			&& MapSession.get().map().chunkCount() > 200, 2400);
		context.runOnClient(mc -> Settings.get().radarHostile = false);
		settle(context, 100);
		context.setScreen(MapScreen::new);
		for (double zoom : new double[] {2.6, 1.0}) {
			lookAtPlayer(context, zoom, TileKey.SURFACE);
			settle(context, 80);
			context.takeScreenshot("showcase-nether-" + zoom);
		}
		context.setScreen(() -> null);
	}

	/**
	 * Frames for the store page's demo video, one screenshot per frame. It looks around the village
	 * and settles on the castle, then cranes up and back to the map's camera angle while the field
	 * of view narrows to near-flat, then opens the fullscreen map at the same framing and zooms out.
	 * The last in-game frame and the first map frame line up, so the video can cross-fade between
	 * them. Leaves the map open.
	 */
	private static void demo(ClientGameTestContext context, TestSingleplayerContext world, int cx, int base, int cz) {
		double[] keep = {cx + 0.5, base + 20, cz + 0.5};
		double[] centre = {cx + 0.5, base + 12, cz + 0.5};
		// A narrow field of view seen from far away looks nearly like the map's flat projection.
		double narrowFov = 17;
		double frameBlocks = 64;
		double distance = frameBlocks / (2 * Math.tan(Math.toRadians(narrowFov) / 2));
		double[] above = {centre[0], centre[1] + distance * Projection.sin(), centre[2] + distance * Projection.cos()};
		double[] start = {cx + 14.5, base + 4.6, cz + 120.5};
		double[] end = {cx + 12.5, base + 7.6, cz + 110.5};

		// The game's field of view stops at 30 degrees. Its speed effect narrows it further: with no
		// movement speed a flying player sees 0.55 of it, and the effect scale blends that in smoothly.
		world.getServer().runCommand("attribute @p minecraft:movement_speed base set 0");
		CloudStatus clouds = context.computeOnClient(mc -> mc.options.cloudStatus().get());
		context.runOnClient(mc -> {
			mc.options.cloudStatus().set(CloudStatus.OFF);
			mc.options.renderDistance().set(16);
			mc.gui.hud.getChat().clearMessages(false);
		});
		// Load and build everything both ends of the shot can see before recording.
		for (double[] at : new double[][] {above, start}) {
			camera(context, at, 180, 45, 70, 1);
			context.waitTicks(400);
		}

		int frame = 0;
		// From the village houses, turn to the castle while drifting towards it.
		for (int i = 0; i < 110; i++) {
			double s = ease(i / 109.0);
			double[] at = lerp(start, end, s);
			float[] facing = facing(at, keep);
			camera(context, at, (float) Mth.lerp(s, 115, facing[0]), (float) Mth.lerp(s, 10, facing[1]), 70, 1);
			context.takeScreenshot(String.format("showcase-demo-world-%03d", frame++));
		}
		// Crane up and back to where the map's own camera would be, looking at the castle throughout.
		for (int i = 0; i < 140; i++) {
			double s = ease(i / 139.0);
			double[] at = lerp(end, above, s);
			float[] facing = facing(at, lerp(keep, centre, s));
			camera(context, at, facing[0], facing[1], 70 * Math.pow(narrowFov / 70, s), 3);
			context.takeScreenshot(String.format("showcase-demo-world-%03d", frame++));
		}
		//? if >=26.1 {
		double fov = context.computeOnClient(mc -> (double) mc.gameRenderer.mainCamera().getFov());
		//?} else {
		/*// The camera keeps its field of view private here; the demo is recorded on the newest version anyway.
		double fov = narrowFov;
		*///?}
		dev.diorama.Diorama.LOGGER.info("Demo ends {} blocks from the castle at a {} degree field of view", Math.round(distance), fov);

		// The map, framed like the last frame: the same blocks per screen height at the castle.
		context.setScreen(MapScreen::new);
		double screenBlocks = 2 * distance * Math.tan(Math.toRadians(fov) / 2);
		double matched = context.computeOnClient(mc -> mc.getWindow().getHeight() / (mc.getWindow().getGuiScale() * screenBlocks));
		double v = Projection.v(centre[1], centre[2]);
		for (int i = 0; i < 150; i++) {
			double zoom = matched * Math.pow(0.8 / matched, ease(Math.clamp((i - 12) / 117.0, 0, 1)));
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(centre[0], v, zoom, TileKey.SURFACE));
			if (i == 0) {
				settle(context, 80);
			} else {
				context.waitTicks(2);
			}
			context.takeScreenshot(String.format("showcase-demo-map-%03d", i));
		}

		world.getServer().runCommand("attribute @p minecraft:movement_speed base reset");
		context.runOnClient(mc -> {
			mc.options.fov().set(70);
			mc.options.fovEffectScale().set(1.0);
			mc.options.cloudStatus().set(clouds);
			mc.options.renderDistance().set(12);
		});
	}

	/**
	 * Puts the camera (the player's eyes) at a point, facing a way, with a field of view in
	 * degrees, then waits for the field of view to ease in.
	 */
	private static void camera(ClientGameTestContext context, double[] eye, float yaw, float pitch, double fov, int ticks) {
		context.runOnClient(mc -> {
			int option = Math.clamp((int) Math.ceil(fov), 30, 110);
			//? if >=1.21.2 {
			double narrowest = mc.player.getFieldOfViewModifier(true, 1.0F);
			//?} else {
			/*// The modifier took the effect scale from the options.
			mc.options.fovEffectScale().set(1.0);
			double narrowest = mc.player.getFieldOfViewModifier();
			*///?}
			mc.options.fov().set(option);
			mc.options.fovEffectScale().set(Math.clamp((1 - fov / option) / (1 - narrowest), 0.0, 1.0));
			mc.player.setDeltaMovement(Vec3.ZERO);
			mc.player.snapTo(eye[0], eye[1] - mc.player.getEyeHeight(), eye[2], yaw, pitch);
		});
		context.waitTicks(ticks);
	}

	/** The yaw and pitch that look from one point at another. */
	private static float[] facing(double[] from, double[] to) {
		double dx = to[0] - from[0];
		double dy = to[1] - from[1];
		double dz = to[2] - from[2];
		float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
		return new float[] {yaw, pitch};
	}

	private static double[] lerp(double[] a, double[] b, double s) {
		return new double[] {Mth.lerp(s, a[0], b[0]), Mth.lerp(s, a[1], b[1]), Mth.lerp(s, a[2], b[2])};
	}

	/** Smoothstep: starts and stops gently. */
	private static double ease(double s) {
		return s * s * (3 - 2 * s);
	}

	/**
	 * The map blends biome tints on bake threads from its own copies of the chunks. They must come
	 * out the same as the game's blend, for every column whose neighbours the map also has.
	 */
	private static void assertTintsMatchGame(ClientGameTestContext context) {
		String result = context.computeOnClient(mc -> {
			MapLevel map = MapSession.get().map();
			int pcx = mc.player.blockPosition().getX() >> 4;
			int pcz = mc.player.blockPosition().getZ() >> 4;
			int columns = 0;
			int wrong = 0;
			String example = null;
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int cx = pcx - 8; cx <= pcx + 8; cx++) {
				for (int cz = pcz - 8; cz <= pcz + 8; cz++) {
					boolean surrounded = true;
					for (int dx = -1; dx <= 1; dx++) {
						for (int dz = -1; dz <= 1; dz++) {
							surrounded &= map.chunk(cx + dx, cz + dz) != null;
						}
					}
					if (!surrounded) {
						continue;
					}
					ChunkSnapshot snapshot = map.chunk(cx, cz);
					// What the last bake used, as it stands.
					ChunkSnapshot.Tints tints = snapshot.tints() != null ? snapshot.tints() : BiomeTints.blend(snapshot);
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							pos.set((cx << 4) + x, snapshot.surface(x, z), (cz << 4) + z);
							int i = (z << 4) | x;
							int[] ours = {tints.grass()[i], tints.foliage()[i], tints.dryFoliage()[i], tints.water()[i]};
							int[] game = {
								BiomeColors.getAverageGrassColor(mc.level, pos), BiomeColors.getAverageFoliageColor(mc.level, pos),
								//? if >=1.21.5 {
								BiomeColors.getAverageDryFoliageColor(mc.level, pos), BiomeColors.getAverageWaterColor(mc.level, pos)
								//?} else {
								/*// No dry foliage before 1.21.5; the mod fills that slot with foliage.
								BiomeColors.getAverageFoliageColor(mc.level, pos), BiomeColors.getAverageWaterColor(mc.level, pos)
								*///?}
							};
							columns++;
							for (int k = 0; k < 4; k++) {
								if ((ours[k] & 0xFFFFFF) != (game[k] & 0xFFFFFF)) {
									wrong++;
									if (example == null) {
										example = String.format("%s tint %d: ours %06X, game %06X", pos.toShortString(), k, ours[k] & 0xFFFFFF, game[k] & 0xFFFFFF);
									}
								}
							}
						}
					}
				}
			}
			return columns + " " + wrong + " " + example;
		});
		String[] parts = result.split(" ", 3);
		dev.diorama.Diorama.LOGGER.info("Biome tints checked against the game's for {} columns: {} differ", parts[0], parts[1]);
		if (Integer.parseInt(parts[0]) < 1000) {
			throw new AssertionError("Too few columns to check biome tints: " + parts[0]);
		}
		if (!parts[1].equals("0")) {
			throw new AssertionError(parts[1] + " biome tints differ from the game's, for example " + parts[2]);
		}
	}

	/** The minimap, the fullscreen map at three zooms, a resource reload and a cave slice. */
	private static void tour(ClientGameTestContext context, TestSingleplayerContext world) {
		// Fancy for the shots the Fast ones further down are compared with.
		setQuality(context, Settings.Quality.FANCY);
		context.takeScreenshot("diorama-01-minimap");

		context.setScreen(MapScreen::new);
		settle(context, 60);
		context.takeScreenshot("diorama-02-map");
		assertTintsMatchGame(context);

		lookAtPlayer(context, 5.0, TileKey.SURFACE);
		settle(context, 40);
		context.takeScreenshot("diorama-03-map-close");

		lookAtPlayer(context, 0.75, TileKey.SURFACE);
		settle(context, 80);
		context.takeScreenshot("diorama-04-map-far");

		// Quality Fast: one sample per pixel and the cheap fog. Switching re-bakes what's on screen.
		setQuality(context, Settings.Quality.FAST);
		settle(context, 80);
		context.takeScreenshot("diorama-04c-map-far-fast");
		lookAtPlayer(context, 5.0, TileKey.SURFACE);
		settle(context, 60);
		context.takeScreenshot("diorama-04d-map-close-fast");
		setQuality(context, Settings.Quality.FANCY);
		lookAtPlayer(context, 0.75, TileKey.SURFACE);
		settle(context, 80);

		if ("1".equals(System.getenv("DIORAMA_GIF"))) {
			// Frames for a GIF: a slow pan east across the edge of the loaded area, so the fog's
			// drift and the zoomed-out map show in motion.
			for (int frame = 0; frame < 60; frame++) {
				double offset = 90 + frame * 1.5;
				context.runOnClient(mc -> {
					if (mc.gui.screen() instanceof MapScreen screen && mc.player != null) {
						screen.lookAt(mc.player.getX() + offset, Projection.v(mc.player.getY(), mc.player.getZ()), 1.4, TileKey.SURFACE);
					}
				});
				context.waitTicks(3);
				context.takeScreenshot(String.format("diorama-gif-%03d", frame));
			}
		}

		// A resource reload (F3+T) must rebuild block colours and re-bake what is on screen.
		context.runOnClient(Minecraft::reloadResourcePacks);
		context.waitTicks(60);
		settle(context, 40);
		context.takeScreenshot("diorama-04b-after-reload");

		int caveY = Integer.getInteger("diorama.caveY", 24);
		lookAtPlayer(context, 2.0, caveY);
		settle(context, 80);
		context.takeScreenshot("diorama-05-cave");

		lookAtPlayer(context, 5.0, caveY);
		settle(context, 40);
		context.takeScreenshot("diorama-06-cave-close");
		context.setScreen(() -> null);

		// Open map rebound to C, which the map also uses for caves: the player's binding wins, so
		// pressing it again closes the map.
		// Before 1.21.9 a key reaches only one binding, and vanilla's Save Toolbar Activator is on C,
		// so a player moving Open map to C clears that one too; the test does the same.
		context.runOnClient(mc -> {
			mc.options.keySaveHotbarActivator.setKey(InputConstants.UNKNOWN);
			DioramaClient.OPEN_MAP.setKey(InputConstants.getKey("key.keyboard.c"));
			net.minecraft.client.KeyMapping.resetMapping();
		});
		context.getInput().pressKey(DioramaClient.OPEN_MAP);
		context.waitTicks(5);
		boolean opened = context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen);
		context.getInput().pressKey(DioramaClient.OPEN_MAP);
		context.waitTicks(5);
		boolean closed = context.computeOnClient(mc -> mc.gui.screen() == null);
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			mc.options.keySaveHotbarActivator.setKey(mc.options.keySaveHotbarActivator.getDefaultKey());
			DioramaClient.OPEN_MAP.setKey(DioramaClient.OPEN_MAP.getDefaultKey());
			net.minecraft.client.KeyMapping.resetMapping();
		});
		if (!opened || !closed) {
			throw new AssertionError("With Open map on C, the key " + (opened ? "didn't close the map" : "didn't open the map"));
		}
	}

	/** Fog of war after the server's fair-play code, in a scene built for it. */
	private static void fogOfWar(ClientGameTestContext context, TestSingleplayerContext world) {
		// Fog of war. Build a known scene deep underground: a straight corridor through solid
		// stone that turns north at its far end into a hidden room. From the corridor's start,
		// the corridor must be revealed and the room must not, because it is around a corner.
		// First over the scene: an earlier section can leave the player thousands of blocks away,
		// where its chunks aren't loaded and every fill fails.
		int surface = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 115, 110));
		world.getServer().runCommand("tp @a 115.5 " + surface + " 110.5");
		for (int i = 0; i < 600 && !world.getServer().computeOnServer(server -> server.overworld().isLoaded(new BlockPos(100, 0, 98))
			&& server.overworld().isLoaded(new BlockPos(131, 0, 98)) && server.overworld().isLoaded(new BlockPos(100, 0, 120))
			&& server.overworld().isLoaded(new BlockPos(131, 0, 120))); i++) {
			context.waitTick();
		}
		world.getServer().runCommand("fill 100 -42 98 131 -34 120 minecraft:stone");
		world.getServer().runCommand("fill 102 -40 115 128 -38 117 minecraft:air");
		world.getServer().runCommand("fill 126 -40 110 128 -38 114 minecraft:air");
		world.getServer().runCommand("fill 119 -40 100 128 -38 109 minecraft:air");
		world.getServer().runCommand("tellraw @a \"\u00a7f\u00a7a\u00a7i\u00a7r\u00a7x\u00a7a\u00a7e\u00a7r\u00a7o\"");
		if (!world.getServer().computeOnServer(server -> server.overworld().getBlockState(new BlockPos(115, -39, 116)).isAir()
			&& server.overworld().getBlockState(new BlockPos(115, -39, 118)).is(net.minecraft.world.level.block.Blocks.STONE))) {
			throw new AssertionError("The fog-of-war scene wasn't built");
		}
		world.getServer().runCommand("tp @a 103.5 -40 116.5 -90 0");
		context.waitFor(mc -> mc.level.getBlockState(new BlockPos(115, -39, 116)).isAir()
			&& mc.level.getBlockState(new BlockPos(115, -39, 118)).is(net.minecraft.world.level.block.Blocks.STONE), 600);
		settle(context, 100);
		context.takeScreenshot("diorama-07-fog-of-war-minimap");
		// Down here the map opens on the cave, unless Caves underground is off.
		for (boolean caves : new boolean[] {false, true}) {
			context.runOnClient(mc -> Settings.get().autoCaves = caves);
			context.setScreen(MapScreen::new);
			context.waitTicks(5);
			int layer = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view().layer());
			int expected = caves ? context.computeOnClient(mc -> MapSession.caveLayerFor(mc.player.getY())) : TileKey.SURFACE;
			context.takeScreenshot("fog-of-war-map-opens-" + (caves ? "caves" : "surface"));
			if (layer != expected) {
				throw new AssertionError("Underground with Caves underground " + (caves ? "on" : "off") + ", the map opened on layer " + layer + ", not " + expected);
			}
			context.setScreen(() -> null);
		}
		assertSeen(context, 115, -39, 116, true, "corridor in front of the player");
		assertSeen(context, 123, -39, 104, false, "room around the corner");
		showScene(context);
		context.takeScreenshot("diorama-08-fog-of-war-corridor");
		context.setScreen(() -> null);

		// Walk to the bend: now the room is in sight.
		world.getServer().runCommand("tp @a 127.5 -40 112.5 0 0");
		settle(context, 100);
		assertSeen(context, 123, -39, 104, true, "room once the player reaches the bend");
		showScene(context);
		context.takeScreenshot("diorama-09-fog-of-war-room");
		context.setScreen(() -> null);
			world.getServer().runCommand("tellraw @a \"\u00a7r\u00a7e\u00a7s\u00a7e\u00a7t\u00a7x\u00a7a\u00a7e\u00a7r\u00a7o\"");
	}

	/**
	 * Radar: mobs of each kind and another player around the viewer, one mob high above and one
	 * underground. Then the server's fair-play code must hide the radar, and its no-minimap code
	 * must hide the minimap.
	 */
	/**
	 * Every mob in the game on a platform, each with the face {@link dev.diorama.render.ModelFaces}
	 * reads off its model, logged as MODEL-FACE lines beside the face it's drawn with, and on the
	 * fullscreen map with the hand-made faces and then with the model's for every mob. Mobs from
	 * other mods come first: load some (on 26.x, -Dfabric.addMods=<folder>) to see theirs. Faces read off
	 * these vanilla models must come out exactly as the hand-made ones, on every version.
	 */
	private static void modelFaces(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		int x = 3000;
		int y = 200;
		int z = 3000;
		// Go there first: a fill in chunks that aren't loaded does nothing.
		world.getServer().runCommand("tp @a " + x + " " + (y + 5) + " " + (z + 15));
		context.waitTicks(100);
		world.getServer().runCommand("fill " + (x - 20) + " " + (y - 1) + " " + (z - 20) + " " + (x + 20) + " " + (y - 1) + " " + (z + 20) + " smooth_stone");
		world.getServer().runCommand("tp @a " + x + " " + y + " " + (z + 20));
		context.waitTicks(40);
		java.util.List<String> names = world.getServer().computeOnServer(server -> {
			java.util.List<String> found = new java.util.ArrayList<>();
			java.util.Set<String> skipped = java.util.Set.of("ender_dragon", "wither", "giant", "player");
			java.util.Set<String> misc = java.util.Set.of("villager", "iron_golem", "snow_golem", "wandering_trader");
			for (net.minecraft.world.entity.EntityType<?> type : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE) {
				net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type);
				String name = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
				if (!skipped.contains(name) && (type.getCategory() != net.minecraft.world.entity.MobCategory.MISC || misc.contains(name))) {
					found.add(name);
				}
			}
			// Other mods' mobs first, so the first rows show them.
			found.sort(java.util.Comparator.comparing((String name) -> !name.contains(":")).thenComparing(name -> name));
			return found;
		});
		String still = "{NoAI:1b,PersistenceRequired:1b,Silent:1b,Invulnerable:1b,IsImmuneToZombification:1b}";
		int columns = 13;
		for (int i = 0; i < names.size(); i++) {
			int mx = x - 18 + (i % columns) * 3;
			int mz = z - 18 + (i / columns) * 3;
			world.getServer().runCommand("summon " + names.get(i) + " " + mx + " " + y + " " + mz + " " + still);
		}
		// A baby camel beside the adult: on versions where babies have a model of their own, each gets its own face.
		world.getServer().runCommand("summon camel " + (x + 18) + " " + y + " " + (z + 18) + " {NoAI:1b,PersistenceRequired:1b,Silent:1b,Invulnerable:1b,Age:-24000}");
		context.waitTicks(40);
		//? if >=1.21.2 {
		context.runOnClient(mc -> {
			// Reading a face runs the model's animation and moves its parts; it must leave the model as it found it.
			net.minecraft.world.entity.Entity creeper = null;
			for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
				if (entity.getType() == EntityTypes.CREEPER) {
					creeper = entity;
				}
			}
			if (creeper == null) {
				throw new AssertionError("No creeper to read a face off");
			}
			@SuppressWarnings({"unchecked", "rawtypes"})
			net.minecraft.client.renderer.entity.LivingEntityRenderer renderer =
				(net.minecraft.client.renderer.entity.LivingEntityRenderer) mc.getEntityRenderDispatcher().getRenderer(creeper);
			net.minecraft.client.model.Model model = renderer.getModel();
			net.minecraft.client.model.geom.ModelPart head = model.root().getChild("head");
			net.minecraft.client.model.geom.PartPose pose = head.storePose();
			float scale = head.xScale;
			boolean skip = head.skipDraw;
			head.yRot = 0.7F;
			head.xScale = 1.5F;
			head.skipDraw = true;
			@SuppressWarnings("unchecked")
			Object state = renderer.createRenderState((net.minecraft.world.entity.LivingEntity) creeper, 1.0F);
			dev.diorama.render.ModelFaces.of(model, state);
			boolean kept = head.yRot == 0.7F && head.xScale == 1.5F && head.skipDraw;
			head.loadPose(pose);
			head.xScale = scale;
			head.skipDraw = skip;
			if (!kept) {
				throw new AssertionError("Reading a face changed the creeper model's head");
			}
		});
		//?}
		context.runOnClient(mc -> {
			int listed = 0;
			int same = 0;
			int fromModel = 0;
			for (String name : names) {
				for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
					net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
					if (!(id.getNamespace().equals("minecraft") ? id.getPath() : id.toString()).equals(name)) {
						continue;
					}
					String listedFace = dev.diorama.render.MobFaces.modelOnly ? null : dev.diorama.render.MobFaces.describe(entity);
					String modelFace = dev.diorama.render.MobFaces.describeFromModel(entity);
					dev.diorama.Diorama.LOGGER.info("MODEL-FACE {} | listed: {} | model: {}", name, listedFace, modelFace);
					if (listedFace != null) {
						listed++;
						same += listedFace.equals(modelFace) ? 1 : 0;
					} else if (modelFace != null) {
						fromModel++;
					}
					break;
				}
			}
			dev.diorama.Diorama.LOGGER.info("MODEL-FACE summary: {} mobs, {} drawn with a face ({} the same as the one read off the model), {} with only a model face",
				names.size(), listed, same, fromModel);
			StringBuilder wrong = new StringBuilder();
			// Mobs with hand-made faces whose models give the same face on every version.
			for (String name : new String[] {"creeper", "spider", "cave_spider", "blaze", "allay", "pig", "sheep"}) {
				boolean found = false;
				for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
					if (entity.getType().toShortString().equals(name)) {
						found = true;
						String listedFace = dev.diorama.render.MobFaces.describe(entity);
						String modelFace = dev.diorama.render.MobFaces.describeFromModel(entity);
						if (listedFace == null || !listedFace.equals(modelFace)) {
							wrong.append("\n  ").append(name).append(": listed ").append(listedFace).append(", model ").append(modelFace);
						}
						break;
					}
				}
				if (!found) {
					wrong.append("\n  ").append(name).append(": not in the world");
				}
			}
			// A mob with no hand-made face is drawn with the one read off its model.
			for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
				if (entity.getType() == EntityTypes.CAMEL && entity instanceof net.minecraft.world.entity.LivingEntity living && !living.isBaby()) {
					String drawn = dev.diorama.render.MobFaces.describe(entity);
					if (drawn == null || !drawn.equals(dev.diorama.render.MobFaces.describeFromModel(entity))) {
						wrong.append("\n  camel: drawn with ").append(drawn).append(", read off its model ")
							.append(dev.diorama.render.MobFaces.describeFromModel(entity));
					}
				}
			}
			//? if >=26.1 {
			String adultCamel = null;
			String babyCamel = null;
			for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
				if (entity.getType() == EntityTypes.CAMEL && entity instanceof net.minecraft.world.entity.LivingEntity living) {
					if (living.isBaby()) {
						babyCamel = dev.diorama.render.MobFaces.describeFromModel(entity);
					} else {
						adultCamel = dev.diorama.render.MobFaces.describeFromModel(entity);
					}
				}
			}
			// The adult's model is laid out on a 128 x 128 texture, the baby's on 64 x 64.
			if (adultCamel == null || babyCamel == null || !adultCamel.startsWith("128x128 ") || !babyCamel.startsWith("64x64 ")) {
				wrong.append("\n  camel: adult ").append(adultCamel).append(", baby ").append(babyCamel).append(" (each should have its own model's face)");
			}
			//?}
			if (!wrong.isEmpty()) {
				throw new AssertionError("Faces read off the model differ from the hand-made ones:" + wrong);
			}
		});
		context.setScreen(MapScreen::new);
		for (int half = 0; half < 3; half++) {
			int row = half;
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(x + 0.5, Projection.v(y, z - 12 + row * 13), 12.0, TileKey.SURFACE));
			settle(context, 40);
			if (half == 0) {
				context.runOnClient(mc -> dev.diorama.render.MobFaces.fromModels = false);
				context.waitTicks(5);
				context.takeScreenshot("model-faces-01-before");
				context.runOnClient(mc -> dev.diorama.render.MobFaces.fromModels = true);
				context.waitTicks(5);
			}
			context.takeScreenshot("model-faces-0" + (half + 1) + "-listed");
			context.runOnClient(mc -> dev.diorama.render.MobFaces.modelOnly = true);
			context.waitTicks(5);
			context.takeScreenshot("model-faces-0" + (half + 1) + "-model");
			context.runOnClient(mc -> dev.diorama.render.MobFaces.modelOnly = false);
		}
		context.setScreen(() -> null);
		world.getServer().runCommand("kill @e[type=!player]");
	}

	private static void radar(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set midnight");
		// Summon relative to where the player actually stands, not mid-fall after spawning, on a
		// cleared flat patch so no mob spawns inside a block and suffocates.
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = at.getX();
		int y = at.getY();
		int z = at.getZ();
		world.getServer().runCommand("fill " + (x - 12) + " " + y + " " + (z - 12) + " " + (x + 12) + " " + (y + 4) + " " + (z + 12) + " air");
		world.getServer().runCommand("fill " + (x - 12) + " " + (y - 1) + " " + (z - 12) + " " + (x + 12) + " " + (y - 1) + " " + (z + 12) + " grass_block");
		String still = "{NoAI:1b,PersistenceRequired:1b,Silent:1b}";
		world.getServer().runCommand("summon husk " + (x + 6) + " " + y + " " + (z - 4) + " " + still);
		world.getServer().runCommand("summon creeper " + (x - 7) + " " + y + " " + (z + 3) + " " + still);
		world.getServer().runCommand("summon wolf " + (x + 4) + " " + y + " " + (z + 6) + " " + still);
		world.getServer().runCommand("summon cow " + (x - 5) + " " + y + " " + (z - 7) + " " + still);
		world.getServer().runCommand("summon villager " + (x + 9) + " " + y + " " + (z + 1) + " " + still);
		world.getServer().runCommand("summon bat " + (x - 2) + " " + (y + 18) + " " + (z + 9) + " " + still);
		world.getServer().runCommand("fill " + (x + 2) + " " + (y - 14) + " " + (z - 9) + " " + (x + 4) + " " + (y - 12) + " " + (z - 7) + " air");
		world.getServer().runCommand("summon zombie " + (x + 3) + " " + (y - 14) + " " + (z - 8) + " " + still);
		// Tests run one real client, so the other player is a stand-in added on the client.
		context.runOnClient(mc -> {
			RemotePlayer other = new RemotePlayer(mc.level, new GameProfile(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch"));
			other.setId(-424242);
			other.snapTo(x - 3.5, y, z + 8.5);
			//? if >=1.21 {
			mc.level.addEntity(other);
			//?} else {
			/*mc.level.addPlayer(other.getId(), other);
			*///?}
		});
		settle(context, 60);
		context.takeScreenshot("radar-01-minimap");
		// A pig in the square's corner, outside the circle: the round minimap leaves it off.
		world.getServer().runCommand("fill " + (x + 19) + " " + y + " " + (z - 25) + " " + (x + 21) + " " + (y + 3) + " " + (z - 23) + " air");
		world.getServer().runCommand("fill " + (x + 19) + " " + (y - 1) + " " + (z - 25) + " " + (x + 21) + " " + (y - 1) + " " + (z - 23) + " grass_block");
		world.getServer().runCommand("summon pig " + (x + 20) + " " + y + " " + (z - 24) + " " + still);
		settle(context, 10);
		context.takeScreenshot("radar-01b-corner-pig");
		context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.ROUND);
		context.waitTicks(5);
		context.takeScreenshot("radar-01c-round-minimap");
		// Two pigs on each diagonal near the rim, at GUI scale 2, where a pig's head snaps to 8 pixels,
		// more than its size asks for. The inner one's head fits inside the ring and shows; the
		// outer one's would cross it, so it's left off. They're off the cleared patch, so they hang in
		// the air at the player's height.
		int radarScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		setGuiScale(context, 2);
		Vec3 feet = context.computeOnClient(mc -> mc.player.position());
		double zoom = context.computeOnClient(mc -> Settings.get().zoom());
		String pig = "{NoAI:1b,NoGravity:1b,PersistenceRequired:1b,Silent:1b,Invulnerable:1b}";
		for (double radius : new double[] {37, 41.5}) {
			for (int corner = 0; corner < 4; corner++) {
				double dx = (corner % 2 == 0 ? 1 : -1) * radius / Math.sqrt(2) / zoom;
				double dz = (corner < 2 ? 1 : -1) * radius / Math.sqrt(2) / zoom / Projection.sin();
				int bx = (int) Math.floor(feet.x + dx);
				int bz = (int) Math.floor(feet.z + dz);
				world.getServer().runCommand("fill " + bx + " " + y + " " + bz + " " + bx + " " + (y + 1) + " " + bz + " air");
				world.getServer().runCommand("summon pig " + (feet.x + dx) + " " + feet.y + " " + (feet.z + dz) + " " + pig);
			}
		}
		settle(context, 10);
		context.takeScreenshot("radar-01d-round-rim");
		setGuiScale(context, radarScale);
		context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.SQUARE);
		world.getServer().runCommand("kill @e[type=pig]");

		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 6.0, TileKey.SURFACE);
		settle(context, 40);
		// Hover the husk so its tooltip shows.
		float[] husk = context.computeOnClient(mc -> {
			float scale = (float) mc.getWindow().getGuiScale();
			for (Radar.Blip blip : ((MapScreen) mc.gui.screen()).blips()) {
				if (blip.entity().getType() == EntityTypes.HUSK) {
					return new float[] {blip.x() * scale, blip.y() * scale};
				}
			}
			StringBuilder found = new StringBuilder();
			for (Radar.Blip blip : ((MapScreen) mc.gui.screen()).blips()) {
				found.append(' ').append(blip.entity().getType().toShortString()).append('@').append(blip.entity().blockPosition().toShortString());
			}
			StringBuilder husks = new StringBuilder();
			for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
				if (entity.getType() == EntityTypes.HUSK) {
					husks.append(' ').append(entity.blockPosition().toShortString()).append(" alive=").append(entity.isAlive())
						.append(" dy=").append(Math.round(entity.getY() - mc.player.getY()));
				}
			}
			throw new AssertionError("The husk is not on the radar. Player at " + mc.player.blockPosition().toShortString()
				+ ", husks in the level:" + husks + ", radar has:" + found);
		});
		context.getInput().setCursorPos(husk[0], husk[1]);
		context.waitTicks(10);
		context.takeScreenshot("radar-02-map");
		// A dropped item is a small dot on the map, unless the setting's off.
		world.getServer().runCommand("summon item " + (x + 3) + " " + y + " " + (z - 2) + " {Item:{id:\"minecraft:diamond\",count:1,Count:1b},Age:-32768s,PickupDelay:32767s}");
		context.waitTicks(10);
		context.takeScreenshot("radar-02b-dropped-item");
		context.runOnClient(mc -> {
			if (!showsItem((MapScreen) mc.gui.screen())) {
				throw new AssertionError("The dropped item is not on the radar");
			}
			Settings.get().radarItems = false;
		});
		context.waitTicks(2);
		context.runOnClient(mc -> {
			if (showsItem((MapScreen) mc.gui.screen())) {
				throw new AssertionError("The dropped item is on the radar with Dropped items off");
			}
			Settings.get().radarItems = true;
		});
		context.setScreen(() -> null);

		// A lineup of mobs, hostile in one row and the rest in another, babies at the ends: each
		// shows its own face on the radar.
		String[] hostile = {"zombie", "skeleton", "creeper", "spider", "enderman", "witch", "pillager", "blaze", "drowned", "stray", "zombie_villager"};
		String[] others = {"cow", "pig", "sheep", "chicken", "cat", "fox", "rabbit", "villager", "iron_golem", "panda", "bee"};
		String immune = "{NoAI:1b,PersistenceRequired:1b,Silent:1b,IsImmuneToZombification:1b}";
		for (int i = 0; i < hostile.length; i++) {
			world.getServer().runCommand("summon " + hostile[i] + " " + (x - 11 + 2 * i) + " " + y + " " + (z - 11) + " " + still);
		}
		world.getServer().runCommand("summon zombie " + (x + 11) + " " + y + " " + (z - 11) + " {NoAI:1b,PersistenceRequired:1b,Silent:1b,IsBaby:1b}");
		world.getServer().runCommand("summon piglin " + (x + 11) + " " + y + " " + (z - 9) + " " + immune);
		for (int i = 0; i < others.length; i++) {
			world.getServer().runCommand("summon " + others[i] + " " + (x - 11 + 2 * i) + " " + y + " " + (z + 11) + " " + still);
		}
		world.getServer().runCommand("summon cow " + (x + 11) + " " + y + " " + (z + 11) + " {NoAI:1b,PersistenceRequired:1b,Silent:1b,Age:-24000}");
		context.waitTicks(20);
		context.runOnClient(mc -> {
			// Every summoned kind is there with a face that draws, a baby zombie and a calf included.
			java.util.List<String> expected = new java.util.ArrayList<>(java.util.List.of(hostile));
			expected.addAll(java.util.List.of(others));
			expected.add("piglin");
			StringBuilder wrong = new StringBuilder();
			for (String name : expected) {
				boolean found = false;
				for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
					if (entity.getType().toShortString().equals(name) && entity.distanceToSqr(mc.player) < 30 * 30) {
						found |= dev.diorama.render.MobFaces.canDraw(entity);
					}
				}
				if (!found) {
					wrong.append(' ').append(name);
				}
			}
			for (net.minecraft.world.entity.EntityType<?> type : new net.minecraft.world.entity.EntityType<?>[] {EntityTypes.ZOMBIE, EntityTypes.COW}) {
				boolean baby = false;
				for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
					if (entity.getType() == type && entity instanceof net.minecraft.world.entity.LivingEntity living && living.isBaby()
						&& entity.distanceToSqr(mc.player) < 30 * 30) {
						baby |= dev.diorama.render.MobFaces.canDraw(entity);
					}
				}
				if (!baby) {
					wrong.append(" baby ").append(type.toShortString());
				}
			}
			if (!wrong.isEmpty()) {
				throw new AssertionError("No drawable face for:" + wrong);
			}
		});
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(x + 0.5, Projection.v(y, z - 11), 9.0, TileKey.SURFACE));
		settle(context, 40);
		context.takeScreenshot("radar-04-faces-hostile");
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(x + 0.5, Projection.v(y, z + 11), 9.0, TileKey.SURFACE));
		settle(context, 20);
		context.takeScreenshot("radar-05-faces-others");
		context.setScreen(() -> null);

		world.getServer().runCommand("tellraw @a \"§f§a§i§r§x§a§e§r§o\"");
		context.waitTicks(20);
		context.takeScreenshot("radar-03-fair-play-hides-radar");
		context.setScreen(MapScreen::new);
		context.waitTicks(2);
		context.runOnClient(mc -> {
			if (showsItem((MapScreen) mc.gui.screen())) {
				throw new AssertionError("The dropped item is on the map under fair play");
			}
		});
		context.setScreen(() -> null);

		world.getServer().runCommand("tellraw @a \"§n§o§m§i§n§i§m§a§p\"");
		context.waitTicks(20);
		context.takeScreenshot("radar-04-no-minimap");
		//? if fabric {
		// A proxy can join another backend without disconnecting; its rules start afresh.
		context.runOnClient(mc -> {
			dev.diorama.ServerRules.onServerMessage(net.minecraft.network.chat.Component.literal("§x§a§e§r§o§m§m§n§e§t§h§e§r§i§s§f§a§i§r"));
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.invoker().onPlayReady(
				mc.getConnection(), net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.getSender(), mc);
			if (!dev.diorama.ServerRules.minimapAllowed() || !dev.diorama.ServerRules.radarAllowed()
				|| dev.diorama.ServerRules.fogOfWar(false) != dev.diorama.ServerRules.alwaysFogOfWar()) {
				throw new AssertionError("The new server inherited the previous server's map restrictions");
			}
			dev.diorama.ServerRules.onServerMessage(net.minecraft.network.chat.Component.literal("§f§a§i§r§x§a§e§r§o"));
			if (!dev.diorama.ServerRules.fogOfWar(true)) {
				throw new AssertionError("The new server inherited the previous server's Nether exemption");
			}
		});
		//?}
		world.getServer().runCommand("tellraw @a \"§r§e§s§e§t§x§a§e§r§o\"");
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("kill @e[type=!player]");
		context.waitTicks(10);
	}

	private static boolean showsItem(MapScreen screen) {
		return screen.blips().stream().anyMatch(blip -> blip.kind() == Radar.Kind.ITEM);
	}

	/**
	 * Radar icons from Xaero's Minimap's icon files. The test mod's own assets give the ocelot an
	 * image, the way a mod ships icons for its mobs, and have pig and villager files shaped like the
	 * ones Xaero's ships for vanilla mobs, which keep Diorama's faces. Then a resource pack, switched
	 * on and off with the reload F3+T runs, gives mobs images (one in Xaero's old upside-down form,
 * one of thin lines), items and dots, one by texture, and
	 * has files Diorama ignores: Xaero's model renderer, a form Xaero's doesn't know, a missing
	 * image and broken JSON. Screenshots of the minimap and the map in each radar style.
	 */
	private static void xaeroIcons(ClientGameTestContext context, TestSingleplayerContext world) {
		// Night, so the zombie and skeleton don't burn.
		world.getServer().runCommand("time set midnight");
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = at.getX();
		int y = at.getY();
		int z = at.getZ();
		world.getServer().runCommand("fill " + (x - 10) + " " + y + " " + (z - 10) + " " + (x + 10) + " " + (y + 4) + " " + (z + 10) + " air");
		world.getServer().runCommand("fill " + (x - 10) + " " + (y - 1) + " " + (z - 10) + " " + (x + 10) + " " + (y - 1) + " " + (z + 10) + " grass_block");
		String sprites = "xaerominimap:entity/icon/sprite/diorama_test/";
		String heart = "outlined_sprite " + sprites + "heart.png 13x12";
		String sun = "outlined_sprite " + sprites + "sun.png 36x36";
		// Each mob: its name, where it stands, its extra NBT, and its icon without and with the pack
		// ("own" for Diorama's own face or dot).
		String[][] mobs = {
			{"creeper", "-6", "-5", "", "own", heart},
			{"slime", "-3", "-5", ",Size:0", "own", heart},
			{"sheep", "0", "-5", "", "own", "normal_sprite " + sprites + "star.png 11x11"},
			{"cow", "3", "-5", "", "own", "dot"},
			{"fox", "6", "-5", ",Type:\"red\"", "own", "item minecraft:sweet_berries"},
			{"ocelot", "-6", "0", "", sun, sun},
			{"pig", "-3", "0", "", "own", "own"},
			{"villager", "3", "0", "", "own", "own"},
			{"spider", "6", "0", "", "own", "own"},
			{"fox", "-6", "5", ",Type:\"snow\"", "own", "dot"},
			{"rabbit", "-3", "5", "", "own", "item minecraft:carrot"},
			{"zombie", "0", "5", "", "own", "own"},
			{"skeleton", "3", "5", "", "own", "own"},
			{"witch", "6", "5", "", "own", "own"},
			{"chicken", "0", "8", "", "own", "own"},
			// Xaero's old "sprite" form, drawn upside down as Xaero's draws it, and a ring of thin
			// lines, which mustn't vanish when shrunk.
			{"goat", "-3", "8", "", "own", "sprite " + sprites + "arrow.png 9x11"},
			{"cat", "3", "8", "", "own", "normal_sprite " + sprites + "ring.png 40x40"},
			// Icons on top of each other: a block item, a flat item and a head, drawn in that order.
			{"mooshroom", "8", "-9", "", "own", "item minecraft:red_mushroom_block"},
			{"rabbit", "9", "-9", "", "own", "item minecraft:carrot"},
			{"zombie", "9", "-8", "", "own", "own"},
		};
		// Invulnerable, as the radar's mobs are: it's night, and a zombie that spawns nearby kills the
		// villager while the packs reload.
		for (String[] mob : mobs) {
			world.getServer().runCommand("summon " + mob[0] + " " + (x + Integer.parseInt(mob[1])) + " " + y + " " + (z + Integer.parseInt(mob[2]))
				+ " {NoAI:1b,PersistenceRequired:1b,Silent:1b,Invulnerable:1b" + mob[3] + "}");
		}
		// An item well above: its caret sits on its slot.
		world.getServer().runCommand("fill " + (x - 8) + " " + y + " " + (z - 8) + " " + (x - 8) + " " + (y + 4) + " " + (z - 8) + " stone");
		world.getServer().runCommand("summon rabbit " + (x - 8) + " " + (y + 5) + " " + (z - 8) + " {NoAI:1b,PersistenceRequired:1b,Silent:1b,Invulnerable:1b}");
		context.waitTicks(20);
		assertIcons(context, x, z, mobs, 4, "without the pack");
		settle(context, 40);
		context.takeScreenshot("xaero-icons-01-minimap-no-pack");
		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 9.0, TileKey.SURFACE);
		settle(context, 40);
		context.takeScreenshot("xaero-icons-02-map-no-pack");
		context.setScreen(() -> null);

		String id = "file/diorama-test-xaero-icons";
		java.nio.file.Path pack = context.computeOnClient(mc -> mc.getResourcePackDirectory().resolve("diorama-test-xaero-icons"));
		context.runOnClient(mc -> {
			try {
				java.nio.file.Files.createDirectories(pack);
				//? if >=1.21.9 {
				int format = net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES).major();
				String formats = "\"pack_format\": " + format + ", \"min_format\": " + format + ", \"max_format\": " + format;
				//?} else if >=1.21.6 {
				/*int format = net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
				String formats = "\"pack_format\": " + format;
				*///?} else {
				/*int format = net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
				String formats = "\"pack_format\": " + format;
				*///?}
				java.nio.file.Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\": {" + formats + ", \"description\": \"Diorama test: Xaero icon files\"}}");
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
			String icon = "assets/xaerominimap/entity/icon/";
			for (String file : new String[] {
				"definition/minecraft/creeper.json", "definition/minecraft/slime.json", "definition/minecraft/sheep.json",
				"definition/minecraft/cow.json", "definition/minecraft/fox.json", "definition/minecraft/rabbit.json",
				"definition/minecraft/zombie.json", "definition/minecraft/skeleton.json", "definition/minecraft/witch.json",
				"definition/minecraft/spider.json", "definition/minecraft/chicken.json", "definition/example_mod/example_entity.json",
				"definition/minecraft/goat.json", "definition/minecraft/cat.json", "definition/minecraft/mooshroom.json",
				"sprite/diorama_test/heart.png", "sprite/diorama_test/star.png", "sprite/diorama_test/arrow.png", "sprite/diorama_test/ring.png"}) {
				packFixture("/xaero-icons-pack/" + icon + file, pack.resolve(icon + file));
			}
		});
		reloadPacks(context, mc -> {
			mc.getResourcePackRepository().reload();
			if (!mc.getResourcePackRepository().addPack(id)) {
				throw new AssertionError("The Xaero icons test pack didn't load");
			}
		});
		assertIcons(context, x, z, mobs, 5, "with the pack");
		Settings.RadarStyle[] styles = {Settings.RadarStyle.HEADS, Settings.RadarStyle.FRAMED, Settings.RadarStyle.DOTS};
		for (int i = 0; i < styles.length; i++) {
			Settings.RadarStyle style = styles[i];
			String name = style.name().toLowerCase(java.util.Locale.ROOT);
			context.runOnClient(mc -> Settings.get().radarStyle = style);
			settle(context, 20);
			context.takeScreenshot("xaero-icons-" + String.format("%02d", 3 + 2 * i) + "-minimap-" + name);
			context.setScreen(MapScreen::new);
			lookAtPlayer(context, 9.0, TileKey.SURFACE);
			settle(context, 40);
			context.takeScreenshot("xaero-icons-" + String.format("%02d", 4 + 2 * i) + "-map-" + name);
			context.setScreen(() -> null);
		}
		context.runOnClient(mc -> Settings.get().radarStyle = Settings.RadarStyle.HEADS);
		// The round minimap leaves off icons that would cross its rim, images and items too.
		context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.ROUND);
		settle(context, 20);
		context.takeScreenshot("xaero-icons-08b-round-minimap");
		context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.SQUARE);

		reloadPacks(context, mc -> mc.getResourcePackRepository().removePack(id));
		assertIcons(context, x, z, mobs, 4, "with the pack off again");
		settle(context, 20);
		context.takeScreenshot("xaero-icons-09-pack-off");
		context.runOnClient(mc -> {
			try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(pack)) {
				files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("kill @e[type=!player]");
		context.waitTicks(10);
	}

	/** Each mob in {@code mobs} (see xaeroIcons) has the icon in column {@code column}, as XaeroRadarIcons.describe gives it. */
	private static void assertIcons(ClientGameTestContext context, int x, int z, String[][] mobs, int column, String when) {
		String wrong = context.computeOnClient(mc -> {
			StringBuilder out = new StringBuilder();
			for (String[] mob : mobs) {
				String got = "missing";
				for (net.minecraft.world.entity.Entity entity : mc.level.entitiesForRendering()) {
					if (entity.getType().toShortString().equals(mob[0]) && entity.blockPosition().getX() == x + Integer.parseInt(mob[1])
						&& entity.blockPosition().getZ() == z + Integer.parseInt(mob[2])) {
						String icon = dev.diorama.render.XaeroRadarIcons.describe(entity);
						got = icon == null ? "own" : icon;
					}
				}
				if (!got.equals(mob[column])) {
					out.append("\n  ").append(mob[0]).append(mob[3]).append(": expected ").append(mob[column]).append(", got ").append(got);
				}
			}
			return out.toString();
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError("Radar icons " + when + ":" + wrong);
		}
	}

	/** Changes the resource packs and reloads them, as F3+T does, and waits until the game is back. */
	static void reloadPacks(ClientGameTestContext context, java.util.function.Consumer<Minecraft> change) {
		java.util.concurrent.CompletableFuture<Void> done = context.computeOnClient(mc -> {
			change.accept(mc);
			return mc.reloadResourcePacks();
		});
		context.waitFor(mc -> done.isDone() && mc.gui.overlay() == null, 2400);
		// A reload that failed fails the test, rather than leaving whatever loaded before it failed.
		done.join();
		context.waitTicks(10);
	}

	static void packFixture(String resource, java.nio.file.Path to) {
		try (java.io.InputStream in = DioramaClientTest.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new AssertionError("Missing fixture " + resource);
			}
			java.nio.file.Files.createDirectories(to.getParent());
			java.nio.file.Files.copy(in, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Waypoints: one nearby and one far off, the in-world label, the map tooltip, adding one by
	 * right-clicking the map, the list, a death marker, and the file on disk.
	 */
	private static void waypoints(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		context.waitFor(mc -> mc.player.onGround(), 600);
		// The empty list says to press the add-waypoint key, so pressing it there opens the editor.
		context.getInput().pressKey(DioramaClient.WAYPOINT_LIST);
		context.waitTicks(3);
		context.takeScreenshot("waypoints-00-empty-list");
		context.getInput().pressKey(DioramaClient.NEW_WAYPOINT);
		context.waitTicks(3);
		boolean keyOpenedEditor = context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointEditScreen);
		context.setScreen(() -> null);
		if (!keyOpenedEditor) {
			throw new AssertionError("The add-waypoint key did nothing on the waypoint list");
		}
		// Bound to a mouse button, the hint names that button, so it has to work too.
		InputConstants.Key boundKey = context.computeOnClient(mc -> InputConstants.getKey(DioramaClient.NEW_WAYPOINT.saveString()));
		context.runOnClient(mc -> {
			DioramaClient.NEW_WAYPOINT.setKey(InputConstants.Type.MOUSE.getOrCreate(InputConstants.MOUSE_BUTTON_LEFT + 3));
			net.minecraft.client.KeyMapping.resetMapping();
		});
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(3);
		// Over a button, as a player's cursor may well be: the click still adds a waypoint.
		double[] overButton = context.computeOnClient(mc -> {
			net.minecraft.client.gui.components.Button button = mc.gui.screen().children().stream()
				.filter(child -> child instanceof net.minecraft.client.gui.components.Button)
				.map(child -> (net.minecraft.client.gui.components.Button) child).findFirst().orElseThrow();
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(button.getX() + button.getWidth() / 2.0) * scale, (button.getY() + button.getHeight() / 2.0) * scale};
		});
		context.getInput().setCursorPos(overButton[0], overButton[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT + 3);
		context.waitTicks(3);
		boolean mouseOpenedEditor = context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointEditScreen);
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			DioramaClient.NEW_WAYPOINT.setKey(boundKey);
			net.minecraft.client.KeyMapping.resetMapping();
		});
		if (!mouseOpenedEditor) {
			throw new AssertionError("A mouse button bound to add-waypoint did nothing on the waypoint list");
		}
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			BlockPos at = mc.player.blockPosition();
			String dimension = mc.level.dimension().identifier().toString();
			int hx = at.getX() + 24;
			int hz = at.getZ() - 14;
			int hy = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, hx, hz);
			store.add(new Waypoint("Home", hx, hy, hz, dimension, Waypoint.PALETTE[5]));
			store.add(new Waypoint("Iron Mine", at.getX() + 400, at.getY(), at.getZ() + 120, dimension, Waypoint.PALETTE[1]));
		});
		context.waitTicks(10);
		context.takeScreenshot("waypoints-01-minimap");
		context.runOnClient(mc -> Settings.get().minimapShape = Settings.Shape.ROUND);
		context.waitTicks(5);
		context.takeScreenshot("waypoints-01b-round-minimap");
		context.runOnClient(mc -> Settings.get().minimapFrame = Settings.Frame.MINECRAFT);
		context.waitTicks(5);
		context.takeScreenshot("waypoints-01c-round-minecraft-frame");
		context.runOnClient(mc -> {
			Settings.get().minimapShape = Settings.Shape.SQUARE;
			Settings.get().minimapFrame = Settings.Frame.CLASSIC;
		});

		// Face Home: its label shows in the world with its name and distance.
		BlockPos home = context.computeOnClient(mc -> {
			for (Waypoint w : WaypointStore.get().all()) {
				if (w.name.equals("Home")) {
					return new BlockPos(w.x, w.y, w.z);
				}
			}
			throw new AssertionError("Home is missing");
		});
		//? if >=26.2 {
		context.getInput().lookAt(home);
		//?} else {
		/*context.runOnClient(mc -> mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, net.minecraft.world.phys.Vec3.atCenterOf(home)));
		*///?}
		context.waitTicks(10);
		context.takeScreenshot("waypoints-02-in-world");
		waypointsUnderTheGui(context);

		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 4.0, TileKey.SURFACE);
		settle(context, 30);
		float[] pin = context.computeOnClient(mc -> {
			float scale = (float) mc.getWindow().getGuiScale();
			for (WaypointOverlay.Pin p : ((MapScreen) mc.gui.screen()).pins()) {
				if (p.waypoint().name.equals("Home")) {
					return new float[] {p.x() * scale, p.y() * scale};
				}
			}
			throw new AssertionError("Home is not on the map");
		});
		context.getInput().setCursorPos(pin[0], pin[1]);
		context.waitTicks(5);
		context.takeScreenshot("waypoints-03-map");

		// A quick drag that starts on Home must pan, not open its editor, even when it's released
		// before the game dispatches any drag event.
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.getInput().moveCursor(80, 40);
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		boolean dragOpenedEditor = context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointEditScreen);
		if (dragOpenedEditor) {
			throw new AssertionError("Dragging from a waypoint opened its editor");
		}
		lookAtPlayer(context, 4.0, TileKey.SURFACE);
		context.waitTicks(3);

		// Right-click a spot left of the player and pick Add waypoint here: the editor opens on the
		// block shown there, which is the first solid block on the camera ray through that point
		// (a canopy can hide the ground behind it).
		double[] click = context.computeOnClient(mc -> {
			MapScreen screen = (MapScreen) mc.gui.screen();
			MapViewport.View view = screen.view();
			double u = mc.player.getBlockX() - 15 + 0.5;
			double v = Projection.v(mc.player.getY(), mc.player.getZ());
			float scale = (float) mc.getWindow().getGuiScale();
			return new double[] {u, v, view.screenX(u) * scale, view.screenY(v) * scale};
		});
		context.getInput().setCursorPos(click[2], click[3]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(3);
		clickMenu(context, "Add waypoint here");
		boolean editorOpen = context.computeOnClient(mc -> mc.gui.screen() instanceof WaypointEditScreen);
		if (!editorOpen) {
			throw new AssertionError("Add waypoint here in the map's menu didn't open the waypoint editor");
		}
		context.waitTicks(5);
		context.takeScreenshot("waypoints-04-editor");

		// A coordinate that isn't a number turns red and Save stays off, instead of saving the old
		// coordinate. What's typed survives a GUI scale change, which rebuilds the screen.
		String pickedX = context.computeOnClient(mc -> editBoxes(mc.gui.screen()).get(2).getValue());
		typeInto(context, 2, "abc");
		clickButton(context, "diorama.waypoint.save");
		boolean savedBadX = context.computeOnClient(mc -> !(mc.gui.screen() instanceof WaypointEditScreen) || WaypointStore.get().all().size() != 2);
		if (savedBadX) {
			throw new AssertionError("Save went through with X = abc");
		}
		int guiScale = context.computeOnClient(mc -> mc.options.guiScale().get());
		setGuiScale(context, 4);
		context.takeScreenshot("waypoints-04b-editor-bad-x");
		String keptX = context.computeOnClient(mc -> editBoxes(mc.gui.screen()).get(2).getValue());
		setGuiScale(context, guiScale);
		if (!keptX.equals("abc")) {
			throw new AssertionError("Changing GUI scale replaced the typed X with " + keptX);
		}
		typeInto(context, 2, pickedX);
		typeInto(context, 1, "wY");
		clickButton(context, "diorama.waypoint.save");
		context.waitTicks(5);
		int count = context.computeOnClient(mc -> WaypointStore.get().all().size());
		if (count != 3) {
			throw new AssertionError("Expected 3 waypoints after saving the picked one, found " + count);
		}
		Waypoint picked = context.computeOnClient(mc -> WaypointStore.get().all().get(2));
		if (!picked.initials.equals("wY")) {
			throw new AssertionError("Typed initials wY were saved as " + picked.initials);
		}
		// The hit block sits under the waypoint; the ray through the middle of the pixel row clicked
		// crosses its top or south face.
		int hitY = picked.y - 1;
		double vTop = Projection.v(hitY + 1, picked.z);
		double vBottom = Projection.v(hitY, picked.z + 1);
		double pickedRow = dev.diorama.bake.TileSpots.rowMiddle(click[1]);
		boolean onRay = picked.x == (int) Math.floor(click[0]) && pickedRow >= vTop - 1e-6 && pickedRow <= vBottom + 1e-6;
		boolean solid = context.computeOnClient(mc -> !mc.level.getBlockState(new BlockPos(picked.x, hitY, picked.z)).isAir());
		if (!onRay || !solid) {
			throw new AssertionError("Picked " + picked.x + "," + picked.y + "," + picked.z + " is not the block shown at the click");
		}
		// Unticking Show on maps and in the world and saving hides it; ticking it again shows it.
		for (boolean shown : new boolean[] {false, true}) {
			context.setScreen(() -> new WaypointEditScreen(null, picked, false));
			context.waitTicks(3);
			clickAt(context, context.computeOnClient(mc -> {
				for (var child : mc.gui.screen().children()) {
					if (child instanceof net.minecraft.client.gui.components.Checkbox box) {
						return box;
					}
				}
				throw new AssertionError("The editor has no visibility checkbox");
			}));
			clickButton(context, "diorama.waypoint.save");
			if (context.computeOnClient(mc -> picked.visible) != shown) {
				throw new AssertionError("Saving with the box " + (shown ? "ticked" : "unticked") + " left the waypoint " + (shown ? "hidden" : "shown"));
			}
		}
		context.setScreen(() -> null);

		// A name as long as the editor allows, and coordinates near the world border, stop before
		// the row's buttons.
		context.runOnClient(mc -> WaypointStore.get().add(new Waypoint("Long waypoint name 1234567890123",
			-29999000, -64, 29999000, mc.level.dimension().identifier().toString(), Waypoint.PALETTE[3])));
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("waypoints-05-list");

		// Typing in the search keeps the rows whose name has the text in it, in any case.
		typeInto(context, 0, "LONG");
		List<Waypoint> found = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).shown());
		if (found.size() != 1 || !found.get(0).name.startsWith("Long waypoint name")) {
			throw new AssertionError("Searching LONG shows " + found.stream().map(w -> w.name).toList());
		}
		// Delete asks again in place: the first click turns the row's buttons into Click again to
		// delete, a click anywhere else puts them back, and only the second click deletes.
		Waypoint longName = found.get(0);
		int[] row = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(longName));
		clickAtGui(context, row[0], row[1]);
		if (context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).selected()) != longName) {
			throw new AssertionError("Clicking a row didn't select it");
		}
		clickButton(context, "diorama.waypoint.delete");
		context.takeScreenshot("waypoints-05b-list-deleting");
		// Off the row, on the list's empty space. Enter then mustn't reach a button that's gone.
		clickAtGui(context, row[0], row[1] + 60);
		context.runOnClient(mc -> {
			//? if >=1.21.9 {
			mc.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_RETURN, 0, 0));
			//?} else {
			/*mc.gui.screen().keyPressed(InputConstants.KEY_RETURN, 0, 0);
			*///?}
		});
		context.waitTicks(2);
		boolean disarmed = context.computeOnClient(mc ->
			!buttonLabelled(mc.gui.screen(), "Click again to delete").visible && buttonLabelled(mc.gui.screen(), "Delete").visible);
		if (!disarmed || !context.computeOnClient(mc -> WaypointStore.get().all().contains(longName))) {
			throw new AssertionError("Clicking elsewhere didn't put Delete back, or something else deleted the waypoint");
		}
		clickButton(context, "diorama.waypoint.delete");
		clickButton(context, "diorama.menu.delete_confirm");
		if (context.computeOnClient(mc -> WaypointStore.get().all().contains(longName))) {
			throw new AssertionError("Click again to delete didn't delete");
		}
		context.setScreen(() -> null);

		// Dying leaves a death marker.
		world.getServer().runCommand("gamerule show_death_messages false");
		world.getServer().runCommand("kill @a");
		context.waitTicks(20);
		boolean death = context.computeOnClient(mc -> WaypointStore.get().all().stream().anyMatch(w -> w.kind == Waypoint.Kind.DEATH));
		if (!death) {
			throw new AssertionError("No death marker after dying");
		}
		context.clickScreenButton("deathScreen.respawn");
		context.waitFor(mc -> mc.player != null && mc.player.isAlive(), 200);
		context.waitTicks(40);
		context.takeScreenshot("waypoints-06-after-death");

		// With instant respawn there is no death screen, and the death marker must still appear.
		world.getServer().runCommand("gamerule immediate_respawn true");
		long deathsBefore = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.DEATH).count());
		world.getServer().runCommand("kill @a");
		context.waitTicks(40);
		long deathsAfter = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> w.kind == Waypoint.Kind.DEATH).count());
		world.getServer().runCommand("gamerule immediate_respawn false");
		if (deathsAfter != deathsBefore + 1) {
			throw new AssertionError("Instant respawn left " + (deathsAfter - deathsBefore) + " new death markers instead of 1");
		}

		// Worlds whose names clean up the same must not share a folder.
		if (WorldKey.key("singleplayer", "My World").equals(WorldKey.key("singleplayer", "My_World"))) {
			throw new AssertionError("Two different worlds got the same key");
		}

		// A damaged file: one bad entry is skipped, a null field is filled in, a copy of the
		// original is kept, and saving doesn't drop the good entries.
		context.runOnClient(mc -> {
			String key = "test-damaged-file";
			java.nio.file.Path folder = WorldKey.folder(key);
			try {
				java.nio.file.Files.createDirectories(folder);
				java.nio.file.Files.writeString(folder.resolve("waypoints.json"), """
					[
					  {"id": "00000000-0000-0000-0000-000000000001", "name": "Home", "initials": null, "x": 1, "y": 2, "z": 3, "dimension": "minecraft:overworld"},
					  {"id": "not-a-uuid", "name": "Broken", "x": 4, "y": 5, "z": 6, "dimension": "minecraft:overworld"}
					]
					""");
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			WaypointStore damaged = WaypointStore.open(key);
			if (damaged.all().size() != 1 || !damaged.all().get(0).label().equals("H")) {
				throw new AssertionError("Expected only Home, with initials H, from the damaged file; got " + damaged.all().size());
			}
			damaged.add(new Waypoint("New", 0, 0, 0, "minecraft:overworld", Waypoint.PALETTE[0]));
			WaypointStore reopened = WaypointStore.open(key);
			if (reopened.all().size() != 2) {
				throw new AssertionError("Saving after a damaged load kept " + reopened.all().size() + " waypoints instead of 2");
			}
			try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(folder)) {
				if (files.noneMatch(f -> f.getFileName().toString().startsWith("waypoints.json.unreadable-"))) {
					throw new AssertionError("No copy of the damaged file was kept");
				}
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});

		String saved = context.computeOnClient(mc -> {
			try {
				return java.nio.file.Files.readString(WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
			} catch (java.io.IOException e) {
				throw new AssertionError("waypoints.json is missing", e);
			}
		});
		if (!saved.contains("\"Home\"") || !saved.contains("\"Iron Mine\"") || !saved.contains("\"DEATH\"")) {
			throw new AssertionError("waypoints.json doesn't have every waypoint:\n" + saved);
		}
		shareAndTeleport(context, world);
	}

	/** Sharing a waypoint through chat, picking one up from chat, and teleporting to one. */
	private static void shareAndTeleport(ClientGameTestContext context, TestSingleplayerContext world) {
		// The chat line is one a client may send, with names escaped the way Xaero's Minimap does,
		// and it survives a round trip.
		context.runOnClient(mc -> {
			Waypoint sent = new Waypoint("Mine: east-2_b", 120, 40, -80, "minecraft:the_nether", Waypoint.PALETTE[3]);
			sent.initials = "mE";
			String text = WaypointShare.format(sent).orElseThrow(() -> new AssertionError("Couldn't format a short waypoint"));
			//? if >=1.21.9 {
			if (text.length() > 256 || !text.codePoints().allMatch(net.minecraft.util.StringUtil::isAllowedChatCharacter)) {
			//?} else {
			/*if (text.length() > 256 || !text.chars().allMatch(c -> net.minecraft.util.StringUtil.isAllowedChatCharacter((char) c))) {
			*///?}
				throw new AssertionError("Chat would refuse " + text);
			}
			WaypointShare.Shared back = WaypointShare.parse("<Alex> " + text).orElseThrow(() -> new AssertionError("Couldn't read back " + text));
			if (!text.startsWith("xaero-waypoint:Mine^col^ east^min^2-b:")) {
				throw new AssertionError("Not escaped like Xaero's Minimap: " + text);
			}
			if (!back.name().equals("Mine: east-2_b") || !back.initials().equals("mE") || back.x() != 120 || back.y() != 40 || back.z() != -80 || !back.dimension().equals("minecraft:the_nether")) {
				throw new AssertionError("Round trip of " + text + " gave " + back);
			}
			// A name cut to the editor's 32 characters never ends in half an emoji.
			String emoji = "a".repeat(31) + "\uD83D\uDE00";
			WaypointShare.Shared cut = WaypointShare.parse("xaero-waypoint:" + emoji + ":E:1:64:2:6:false:0:Internal-overworld-waypoints").orElseThrow();
			if (Character.isHighSurrogate(cut.name().charAt(cut.name().length() - 1))) {
				throw new AssertionError("Cutting the name split a character: " + cut.name());
			}
			for (String bad : new String[] {
				"xaero-waypoint:N:N:2147483648:64:0:6:false:0:Internal-overworld-waypoints",
				"xaero-waypoint:N:N:1:64:0:99:false:0:Internal-overworld-waypoints",
				"xaero-waypoint:N:N:1:64:0:6:false:0:Internal-dim%bad-waypoints",
				"xaero-waypoint: :N:1:64:0:6:false:0:Internal-overworld-waypoints"
			}) {
				if (WaypointShare.parse(bad).isPresent()) {
					throw new AssertionError("Accepted " + bad);
				}
			}
		});

		// A share with a number out of range arrives: nothing breaks, the player stays connected.
		world.getServer().runCommand("tellraw @a \"xaero-waypoint:N:N:2147483648:64:0:6:false:0:Internal-overworld-waypoints\"");
		context.waitTicks(10);
		assertConnected(context, "a share with an out-of-range number");

		// Someone else's share arrives: its line gets an [Add] link.
		world.getServer().runCommand("tellraw @a \"xaero-waypoint:Old Mine:oM:120:40:-80:6:false:0:Internal-overworld-waypoints\"");
		context.waitTicks(10);
		context.takeScreenshot("waypoints-07-shared-in-chat");
		// Take the command from the [Add] link on the line chat actually received, and run it the
		// way a chat click does: from the open chat screen, which Minecraft puts back after
		// running the command. The malformed share's line has no link.
		//? if >=1.21.9 {
		context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("", false));
		//?} else {
		/*context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen(""));
		*///?}
		context.waitTicks(2);
		context.runOnClient(mc -> {
			String command = null;
			for (net.minecraft.client.multiplayer.chat.GuiMessage message : chatMessages(mc)) {
				String text = message.content().getString();
				String link = linkIn(message.content());
				if (text.contains("2147483648") && link != null) {
					throw new AssertionError("The malformed share got a link: " + text);
				}
				if (text.contains("xaero-waypoint:Old Mine:")) {
					command = link;
				}
			}
			if (command == null) {
				throw new AssertionError("The shared line in chat has no [Add] link");
			}
			//? if >=1.21.6 {
			mc.player.connection.sendUnattendedCommand(net.minecraft.commands.Commands.trimOptionalPrefix(command), mc.gui.screen());
			//?} else {
			/*// What a chat click ran before 1.21.6.
			mc.player.connection.sendUnsignedCommand(command.startsWith("/") ? command.substring(1) : command);
			*///?}
		});
		context.waitFor(mc -> mc.gui.screen() instanceof WaypointEditScreen, 100);
		context.waitTicks(2);
		context.runOnClient(mc -> {
			// What the editor shows: name, initials, x, y, z, and the colour as hex.
			java.util.List<String> shown = new java.util.ArrayList<>();
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.EditBox box) {
					shown.add(box.getValue());
				}
			}
			String hex = dev.diorama.waypoint.WaypointColours.hex(((WaypointEditScreen) mc.gui.screen()).waypoint().color);
			if (!shown.equals(java.util.List.of("Old Mine", "oM", "120", "40", "-80", hex))) {
				throw new AssertionError("The editor shows " + shown);
			}
		});
		context.takeScreenshot("waypoints-08-add-shared");
		context.setScreen(() -> null);

		// Teleport from an existing waypoint's editor, after changing its x in the editor: the
		// player goes where the editor says.
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		Waypoint target = new Waypoint("Lookout: north", at.getX() + 40, at.getY() + 30, at.getZ(), "minecraft:overworld", Waypoint.PALETTE[5]);
		int editedX = target.x + 5;
		context.setScreen(() -> new WaypointEditScreen(null, target, false));
		context.waitTicks(2);
		context.runOnClient(mc -> {
			java.util.List<net.minecraft.client.gui.components.EditBox> boxes = new java.util.ArrayList<>();
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.EditBox box) {
					boxes.add(box);
				}
			}
			// Name, initials, x, y, z.
			boxes.get(2).setValue(Integer.toString(editedX));
			net.minecraft.client.gui.components.Button teleport = buttonLabelled(mc.gui.screen(), "Teleport");
			if (!teleport.active) {
				throw new AssertionError("Teleport is off in a world where the player may use /tp");
			}
			//? if >=1.21.9 {
			teleport.onPress(null);
			//?} else {
			/*teleport.onPress();
			*///?}
		});
		context.waitFor(mc -> mc.player.distanceToSqr(editedX + 0.5, target.y, target.z + 0.5) < 4, 100);

		// Share it: a name with a colon goes out without upsetting the server.
		context.setScreen(() -> new WaypointEditScreen(null, target, false));
		context.waitTicks(2);
		//? if >=1.21.9 {
		context.runOnClient(mc -> buttonLabelled(mc.gui.screen(), "Share in chat").onPress(null));
		//?} else {
		/*context.runOnClient(mc -> buttonLabelled(mc.gui.screen(), "Share in chat").onPress());
		*///?}
		context.waitTicks(20);
		assertConnected(context, "sharing a name with a colon");
		context.takeScreenshot("waypoints-09-shared-own");
		mapMenu(context);
		mapControls(context);
	}

	/**
	 * The map's controls, used with the mouse: the tabs switch what C switches, a zoom button
	 * zooms, and dragging the depth gauge's band moves the slice only when it's let go.
	 */
	private static void mapControls(ClientGameTestContext context) {
		openMapOnPlayer(context);
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		int cavesTab = context.computeOnClient(mc -> 2 + mc.font.width(net.minecraft.network.chat.Component.translatable("diorama.map.surface").getString()) + 13
			+ (mc.font.width(net.minecraft.network.chat.Component.translatable("diorama.map.caves").getString()) + 13) / 2);
		moveCursor(context, cavesTab * scale, 8 * scale);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		int expected = context.computeOnClient(mc -> MapSession.caveLayerFor(mc.player.getY()));
		int slice = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view().layer());
		if (slice != expected) {
			throw new AssertionError("The Caves tab showed layer " + slice + ", not the slice at the player's feet, " + expected);
		}
		context.takeScreenshot("map-controls-01-caves");

		// Dragging the band down shows the height it's at; the map keeps its slice until let go.
		float[] band = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).sliceBand());
		moveCursor(context, band[0] * scale, band[1] * scale);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		for (int i = 1; i <= 4; i++) {
			context.getInput().setCursorPos(band[0] * scale, (band[1] + i * 10) * scale);
			context.waitTick();
		}
		context.waitTicks(2);
		context.takeScreenshot("map-controls-02-gauge-drag");
		int during = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view().layer());
		if (during != slice) {
			throw new AssertionError("Dragging the gauge moved the slice before it was let go: " + slice + " to " + during);
		}
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		int after = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view().layer());
		if (after >= slice || after % 4 != 0) {
			throw new AssertionError("Letting go of the gauge's band 40 px lower left the slice at " + after + " (from " + slice + ")");
		}
		context.takeScreenshot("map-controls-03-gauge-dropped");

		// The zoom in button zooms.
		double zoom = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view().zoom());
		int[] plus = context.computeOnClient(mc -> {
			net.minecraft.client.gui.components.Button button = buttonLabelled(mc.gui.screen(), "+");
			return new int[] {button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2};
		});
		moveCursor(context, plus[0] * scale, plus[1] * scale);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		double zoomed = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view().zoom());
		if (zoomed <= zoom) {
			throw new AssertionError("The zoom in button left the zoom at " + zoomed + " (from " + zoom + ")");
		}

		// The Surface tab goes back.
		moveCursor(context, (2 + 10) * scale, 8 * scale);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		int surface = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).view().layer());
		if (surface != TileKey.SURFACE) {
			throw new AssertionError("The Surface tab left the map on layer " + surface);
		}
		context.setScreen(() -> null);
	}

	/** The temporary waypoint kept from the menu, and one left temporary, for after rejoining. */
	private static @Nullable UUID keptTemporary;
	private static @Nullable UUID leftTemporary;

	/**
	 * The map's right-click menu, used with the mouse: teleporting to a spot, temporary waypoints,
	 * a waypoint's own menu, sharing a location, and what closes the menu.
	 */
	private static void mapMenu(ClientGameTestContext context) {
		openMapOnPlayer(context);

		// Teleport here: the player lands standing on the block the map shows at the click, which
		// is the first solid block on the camera ray through the middle of the pixel row clicked.
		double[] click = rightClickMap(context, 24, 0);
		context.takeScreenshot("map-menu-01-spot");
		int[] spot = menuSpot(context);
		int hitY = spot[1] - 1;
		double row = dev.diorama.bake.TileSpots.rowMiddle(click[1]);
		boolean onRay = spot[0] == (int) Math.floor(click[0])
			&& row >= Projection.v(hitY + 1, spot[2]) - 1e-6 && row <= Projection.v(hitY, spot[2] + 1) + 1e-6;
		if (!onRay) {
			throw new AssertionError("The menu offers " + spot[0] + "," + spot[1] + "," + spot[2] + ", which isn't the block shown at the click");
		}
		clickMenu(context, "Teleport here");
		context.waitFor(mc -> mc.player.distanceToSqr(spot[0] + 0.5, spot[1], spot[2] + 0.5) < 0.5, 100);
		context.runOnClient(mc -> {
			if (mc.gui.screen() != null) {
				throw new AssertionError("The map stayed open after Teleport here");
			}
			if (mc.level.getBlockState(new BlockPos(spot[0], hitY, spot[2])).isAir()) {
				throw new AssertionError("Teleport here put the player over air at " + spot[0] + "," + spot[1] + "," + spot[2]);
			}
		});
		for (int i = 0; i < (MENU_GIF ? 12 : 0); i++) {
			context.waitTicks(3);
			menuFrame(context);
		}
		openMapOnPlayer(context);
		menuFrame(context);
		context.takeScreenshot("map-menu-02-arrived");

		// Esc closes the menu, not the map.
		rightClickMap(context, -12, 0);
		//? if >=1.21.9 {
		context.runOnClient(mc -> mc.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_ESCAPE, 0, 0)));
		//?} else {
		/*context.runOnClient(mc -> mc.gui.screen().keyPressed(InputConstants.KEY_ESCAPE, 0, 0));
		*///?}
		context.runOnClient(mc -> {
			if (!(mc.gui.screen() instanceof MapScreen map) || map.menu() != null) {
				throw new AssertionError("Esc with the menu open didn't just close the menu");
			}
		});

		// A button held since before the menu opened doesn't close it when let go.
		double[] held = mapPoint(context, -12, 0);
		moveCursor(context, held[2], held[3]);
		context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
		rightClickAt(context, held[2], held[3]);
		context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		boolean keptOpen = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).menu() != null);
		if (!keptOpen) {
			throw new AssertionError("Letting go of a button held from before closed the menu");
		}

		// C changes the view under the menu, so it closes the menu.
		//? if >=1.21.9 {
		context.runOnClient(mc -> mc.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_C, 0, 0)));
		//?} else {
		/*context.runOnClient(mc -> mc.gui.screen().keyPressed(InputConstants.KEY_C, 0, 0));
		*///?}
		boolean closedByC = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).menu() == null);
		if (!closedByC) {
			throw new AssertionError("Switching to caves left the menu open");
		}
		openMapOnPlayer(context);

		// So does a click on the Settings button: it doesn't open the settings.
		rightClickMap(context, -12, 0);
		double[] settings = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(mc.gui.screen().width - 32) * scale, 7 * scale};
		});
		moveCursor(context, settings[0], settings[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		context.runOnClient(mc -> {
			if (!(mc.gui.screen() instanceof MapScreen map) || map.menu() != null) {
				throw new AssertionError("Clicking Settings with the menu open didn't just close the menu");
			}
		});

		// Where the map has nothing, the menu says so and can only copy where it is, without a height.
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(mc.player.getX() + 100_000, Projection.v(mc.player.getY(), mc.player.getZ()), 4.0, TileKey.SURFACE));
		context.waitTicks(3);
		double[] middle = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			return new double[] {mc.gui.screen().width / 2.0 * scale, mc.gui.screen().height / 2.0 * scale};
		});
		rightClickAt(context, middle[0], middle[1]);
		List<String> nothing = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).menu().labels());
		if (nothing.size() != 3 || !nothing.get(0).equals("Nothing mapped here") || !nothing.get(1).startsWith("About ") || !nothing.get(2).equals("Copy coordinates")) {
			throw new AssertionError("Off the map, the menu shows " + nothing);
		}
		context.takeScreenshot("map-menu-02b-nothing-mapped");
		clickMenu(context, "Copy coordinates");
		context.takeScreenshot("map-menu-02c-copied");
		context.runOnClient(mc -> {
			String[] copied = mc.keyboardHandler.getClipboard().split(" ");
			// A height of 64, seen through the tilted camera from where the player's height puts the row.
			double z = mc.player.getZ() + (64 - mc.player.getY()) * Projection.cos() / Projection.sin();
			if (copied.length != 2 || Math.abs(Integer.parseInt(copied[0]) - (mc.player.getX() + 100_000)) > 1 || Math.abs(Integer.parseInt(copied[1]) - z) > 1) {
				throw new AssertionError("Off the map, Copy coordinates copied '" + mc.keyboardHandler.getClipboard() + "', looking near x "
					+ (mc.player.getX() + 100_000) + ", z " + z);
			}
		});
		if (!context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).menu().labels().contains("Copied"))) {
			throw new AssertionError("Copy coordinates doesn't say it's done");
		}
		openMapOnPlayer(context);

		// Two temporary waypoints: they're on the map but not in the file. One gets kept.
		int[] first = addTemporary(context, -12, 0);
		int[] second = addTemporary(context, 0, -10);
		context.waitTicks(5);
		context.takeScreenshot("map-menu-03-temporary");
		String file = context.computeOnClient(mc -> {
			try {
				return java.nio.file.Files.readString(WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		if (file.contains("TEMPORARY")) {
			throw new AssertionError("A temporary waypoint was saved:\n" + file);
		}

		// The first one's own menu: Delete asks for a second click.
		rightClickPin(context, first);
		context.takeScreenshot("map-menu-04-waypoint");
		List<String> labels = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).menu().labels());
		if (!labels.get(0).equals("Temporary") || !labels.contains("Keep waypoint") || !labels.contains("Teleport")) {
			throw new AssertionError("A temporary waypoint's menu shows " + labels);
		}
		clickMenu(context, "Copy coordinates");
		assertClipboard(context, first[0] + " " + first[1] + " " + first[2]);
		clickMenu(context, "Delete");
		boolean deletedEarly = context.computeOnClient(mc -> temporaryAt(first) == null);
		if (deletedEarly) {
			throw new AssertionError("Delete went through without a second click");
		}
		context.takeScreenshot("map-menu-05-delete-confirm");
		clickMenu(context, "Click again to delete");
		boolean deleted = context.computeOnClient(mc -> temporaryAt(first) == null);
		if (!deleted) {
			throw new AssertionError("Deleting from the waypoint's menu left it in place");
		}

		// The second is kept: it's in the file now.
		UUID keptId = context.computeOnClient(mc -> temporaryAt(second).id);
		rightClickPin(context, second);
		clickMenu(context, "Keep waypoint");
		String kept = context.computeOnClient(mc -> {
			try {
				return java.nio.file.Files.readString(WorldKey.folder(WorldKey.current()).resolve("waypoints.json"));
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
		if (!kept.contains(keptId.toString())) {
			throw new AssertionError("Keep waypoint didn't save it:\n" + kept);
		}
		keptTemporary = keptId;
		// One more, left temporary, for leaving the world to clear.
		int[] third = addTemporary(context, 10, 6);
		leftTemporary = context.computeOnClient(mc -> temporaryAt(third).id);

		// Share location: nothing goes out until the second click, then one line, with an [Add] link.
		rightClickMap(context, 8, -8);
		int[] shared = menuSpot(context);
		String line = "xaero-waypoint:Shared location:SL:" + shared[0] + ":" + shared[1] + ":" + shared[2] + ":";
		clickMenu(context, "Copy coordinates");
		assertClipboard(context, shared[0] + " " + shared[1] + " " + shared[2]);
		clickMenu(context, "Share location in chat");
		context.waitTicks(20);
		long early = context.computeOnClient(mc -> chatMessages(mc).stream().filter(m -> m.content().getString().contains(line)).count());
		boolean stillOpen = context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen map && map.menu() != null);
		if (early != 0 || !stillOpen) {
			throw new AssertionError("Share location went through without a second click");
		}
		clickMenu(context, "Click again to post in chat");
		context.waitTicks(20);
		assertConnected(context, "sharing a location");
		context.runOnClient(mc -> {
			List<net.minecraft.client.multiplayer.chat.GuiMessage> lines = chatMessages(mc).stream().filter(m -> m.content().getString().contains(line)).toList();
			if (lines.size() != 1 || linkIn(lines.get(0).content()) == null) {
				throw new AssertionError("Expected one shared location line with an [Add] link in chat, found " + lines.size());
			}
		});
		context.takeScreenshot("map-menu-06-shared-location");
	}

	/** Back in the world after leaving it: the temporary waypoint is gone, the kept one is still there. */
	/** The waystone the Waystones scenario hid, which the server sends again on rejoining. */
	private static @Nullable UUID hiddenWaystone;
	/** The one it picked up with Silk Touch, which the server sends again too, from where it stood. */
	private static @Nullable UUID pickedUpWaystone;
	private static @Nullable BlockPos pickedUpAt;

	/**
	 * Waystones, with the Waystones mod installed (opt-in: DIORAMA_TESTS=waystones). Two waystones
	 * the player places show on the minimap, the map and in the list's Waystones folder, whose rows
	 * and map menu have no Edit or Delete. Renaming one renames its pin; hiding one takes it off the
	 * map; picking the other up with Silk Touch takes it away; forgetting one in Waystones' own
	 * screen takes it away too.
	 */
	private static void waystones(ClientGameTestContext context, TestSingleplayerContext world) {
		if (!context.computeOnClient(mc -> waystonesClass("api.WaystonesAPI") != null)) {
			throw new AssertionError("DIORAMA_TESTS=waystones needs the Waystones mod installed");
		}
		// A flat, open patch far from anything placed before.
		int cx = 2400;
		int cz = 2400;
		// Go there first: fill does nothing where chunks aren't loaded.
		world.getServer().runCommand("tp @a " + cx + " 200 " + cz);
		context.waitFor(mc -> mc.player.blockPosition().getX() == cx, 200);
		context.waitTicks(60);
		int y = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz));
		world.getServer().runCommand("fill " + (cx - 16) + " " + y + " " + (cz - 16) + " " + (cx + 16) + " " + (y + 16) + " " + (cz + 16) + " air");
		world.getServer().runCommand("fill " + (cx - 16) + " " + (y - 1) + " " + (cz - 16) + " " + (cx + 16) + " " + (y - 1) + " " + (cz + 16) + " grass_block");
		world.getServer().runCommand("tp @a " + cx + " " + y + " " + cz);
		world.getServer().runCommand("time set noon");
		context.waitFor(mc -> mc.player.blockPosition().getX() == cx && mc.player.onGround(), 600);
		settle(context, 60);
		BlockPos eastSpot = new BlockPos(cx + 10, y, cz - 6);
		BlockPos westSpot = new BlockPos(cx - 9, y, cz + 7);
		placeWaystone(world, eastSpot);
		placeWaystone(world, westSpot);
		// Placing one may open Waystones' settings screen for it.
		waitForWaystones(context, world, 2, eastSpot, westSpot);
		context.setScreen(() -> null);
		// Where they stand, which on uneven ground can be a block off the spot aimed at.
		BlockPos east = context.computeOnClient(mc -> standing(waystoneAt(eastSpot)));
		BlockPos west = context.computeOnClient(mc -> standing(waystoneAt(westSpot)));
		UUID eastId = context.computeOnClient(mc -> waystoneAt(east).id);
		UUID westId = context.computeOnClient(mc -> waystoneAt(west).id);
		// Naming one in Waystones' screen renames it here too. Waystones only takes an edit from a
		// player standing by the waystone.
		world.getServer().runCommand("tp @a " + (east.getX() - 2) + " " + east.getY() + " " + east.getZ());
		context.waitTicks(5);
		renameWaystone(world, eastId, "Iron Farm");
		context.waitFor(mc -> waystoneAt(east).name.equals("Iron Farm"), 200);
		world.getServer().runCommand("tp @a " + cx + " " + y + " " + cz);
		context.waitTicks(10);
		context.takeScreenshot("waystones-01-minimap");

		openMapOnPlayer(context);
		float[] pin = waystonePin(context, eastId);
		if (pin == null || waystonePin(context, westId) == null) {
			throw new AssertionError("The waystones aren't both on the map");
		}
		context.getInput().setCursorPos(pin[0], pin[1]);
		context.waitTicks(5);
		context.takeScreenshot("waystones-02-map");
		// A waystone's menu: no Edit or Delete, as Waystones owns it.
		rightClickAt(context, pin[0], pin[1]);
		List<String> labels = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).menu().labels());
		context.takeScreenshot("waystones-03-menu");
		if (labels.contains("Edit") || labels.contains("Delete") || !labels.contains("Hide")) {
			throw new AssertionError("A waystone's menu should have Hide and no Edit or Delete: " + labels);
		}
		clickMenu(context, "Hide");
		context.waitTicks(3);
		if (waystonePin(context, eastId) != null) {
			throw new AssertionError("The hidden waystone is still on the map");
		}
		hiddenWaystone = eastId;
		context.setScreen(() -> null);

		// The list's Waystones folder; a waystone's row has Show and Teleport, no Edit or Delete.
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(3);
		for (int i = 0; i < 8 && !context.computeOnClient(mc -> folderTitle(mc).startsWith("Waystones")); i++) {
			clickButton(context, ">");
		}
		int[] row = context.computeOnClient(mc -> ((WaypointListScreen) mc.gui.screen()).rowCentre(waystoneAt(east)));
		if (row == null) {
			throw new AssertionError("The list has no Waystones folder with the hidden waystone in it: " + context.computeOnClient(DioramaClientTest::folderTitle));
		}
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		context.getInput().setCursorPos(row[0] * scale, row[1] * scale);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		List<String> buttons = context.computeOnClient(mc -> visibleButtons(mc));
		context.takeScreenshot("waystones-04-list");
		if (buttons.contains("Edit") || buttons.contains("Delete") || !buttons.contains("Show")) {
			throw new AssertionError("A hidden waystone's row should have Show and no Edit or Delete: " + buttons);
		}
		// Renamed while the list is open, the row follows.
		world.getServer().runCommand("tp @a " + (west.getX() + 2) + " " + west.getY() + " " + west.getZ());
		context.waitTicks(5);
		renameWaystone(world, westId, "Mine");
		context.waitFor(mc -> ((WaypointListScreen) mc.gui.screen()).shown().stream().anyMatch(w -> w.name.equals("Mine")), 200);
		world.getServer().runCommand("tp @a " + cx + " " + y + " " + cz);
		context.setScreen(() -> null);

		// Picking one up with Silk Touch removes it. Waystones keeps it activated and sends no new
		// list for this, so only its removal message can take it off. In survival, so it drops.
		world.getServer().runCommand("gamemode survival @a");
		world.getServer().runCommand("item replace entity @a weapon.mainhand with diamond_pickaxe");
		world.getServer().runCommand("enchant @a minecraft:silk_touch");
		world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().get(0).gameMode.destroyBlock(west));
		context.waitFor(mc -> WaypointStore.get().waystones().stream().noneMatch(w -> w.id.equals(westId)), 200);
		world.getServer().runCommand("tp @a " + west.getX() + " " + west.getY() + " " + west.getZ());
		for (int tick = 0; tick < 200 && world.getServer().computeOnServer(server -> waystoneSlot(server) < 0); tick++) {
			context.waitTick();
		}
		if (world.getServer().computeOnServer(server -> waystoneSlot(server) < 0)) {
			throw new AssertionError("The waystone picked up with Silk Touch didn't drop into the inventory");
		}
		world.getServer().runCommand("gamemode creative @a");
		world.getServer().runCommand("tp @a " + cx + " " + y + " " + cz);
		pickedUpWaystone = westId;
		pickedUpAt = west;
		// Waystones' own screen forgets one on the client before telling the server, which sends no
		// new list back: do what that screen does on the client.
		context.runOnClient(mc -> {
			try {
				Class<?> api = waystonesClass("api.WaystonesAPI");
				// A copy, as forgetting one changes the list.
				for (Object waystone : List.copyOf((java.util.Collection<?>) api.getMethod("getActivatedWaystones", net.minecraft.world.entity.player.Player.class).invoke(null, mc.player))) {
					Class<?> manager = waystonesClass("core.PlayerWaystoneManager");
					Class<?> type = waystonesClass("api.Waystone");
					manager.getMethod("deactivateWaystone", net.minecraft.world.entity.player.Player.class, type).invoke(null, mc.player, waystone);
				}
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		context.waitFor(mc -> WaypointStore.get().waystones().isEmpty(), 200);
		openMapOnPlayer(context);
		context.takeScreenshot("waystones-05-map-after");
		context.setScreen(() -> null);
	}

	/**
	 * A Cobblemon battle, with Cobblemon installed (opt-in: DIORAMA_TESTS=cobblemon). The player's
	 * Pikachu takes on the nearest wild Pokémon, the way a thrown Poké Ball starts a battle: the
	 * Rattata spawned beside them, or one that spawned on its own closer. Cobblemon's battle
	 * screen and the battlers' tiles fill every corner, so the minimap stays out of the way, in
	 * whichever corner it's set to, also once the battle screen is closed with the tiles still up,
	 * and comes back when the battle ends (#147).
	 */
	private static void cobblemonBattle(ClientGameTestContext context, TestSingleplayerContext world) {
		if (!context.computeOnClient(mc -> cobblemonClass("client.CobblemonClient") != null)) {
			throw new AssertionError("DIORAMA_TESTS=cobblemon needs Cobblemon installed");
		}
		world.getServer().runCommand("gamemode survival @a");
		world.getServer().runCommand("time set noon");
		world.getServer().runCommand("execute as @a run pokegive pikachu level=50");
		world.getServer().runCommand("execute as @a at @s run pokespawnat ~4 ~ ~ rattata level=2");
		context.setScreen(() -> null);
		settle(context, 40);
		context.takeScreenshot("cobblemon-00-before");
		world.getServer().runOnServer(server -> {
			try {
				net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				Class<?> pokemonEntity = cobblemonClass("entity.pokemon.PokemonEntity");
				net.minecraft.world.entity.Entity wild = player.level().getEntities((net.minecraft.world.entity.Entity) null,
					player.getBoundingBox().inflate(16), pokemonEntity::isInstance).get(0);
				Class<?> builder = cobblemonClass("battles.BattleBuilder");
				for (java.lang.reflect.Method m : builder.getMethods()) {
					if (m.getName().equals("pve") && m.getParameterCount() == 2) {
						m.invoke(builder.getField("INSTANCE").get(null), player, wild);
						return;
					}
				}
				throw new AssertionError("No BattleBuilder.pve(player, pokemon)");
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		context.waitFor(mc -> inCobblemonBattle() && mc.gui.screen() != null && mc.gui.screen().getClass().getSimpleName().equals("BattleGUI"), 400);
		// Past the battle screen's opening animation.
		context.waitTicks(80);
		Settings.Corner saved = context.computeOnClient(mc -> Settings.get().minimapCorner);
		for (Settings.Corner corner : Settings.Corner.values()) {
			context.runOnClient(mc -> Settings.get().minimapCorner = corner);
			context.waitTicks(5);
			context.takeScreenshot("cobblemon-01-battle-" + corner.name().toLowerCase(java.util.Locale.ROOT));
		}
		context.runOnClient(mc -> Settings.get().minimapCorner = saved);
		// Closed with Esc, the battle screen leaves the battlers' tiles in the top corners.
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitTicks(20);
		if (!context.computeOnClient(mc -> inCobblemonBattle())) {
			throw new AssertionError("Closing the battle screen ended the battle");
		}
		context.takeScreenshot("cobblemon-02-battle-screen-closed");
		// Cobblemon's command takes one player.
		world.getServer().runCommand("stopbattle @p");
		context.waitFor(mc -> !inCobblemonBattle(), 200);
		context.setScreen(() -> null);
		settle(context, 40);
		context.takeScreenshot("cobblemon-03-after");
		world.getServer().runCommand("gamemode creative @a");
	}

	private static boolean inCobblemonBattle() {
		try {
			Class<?> client = cobblemonClass("client.CobblemonClient");
			return client.getMethod("getBattle").invoke(client.getField("INSTANCE").get(null)) != null;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static @Nullable Class<?> cobblemonClass(String name) {
		try {
			return Class.forName("com.cobblemon.mod.common." + name);
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	/**
	 * Land claims from FTB Chunks, with FTB Chunks installed (opt-in: DIORAMA_TESTS=ftb-claims). The
	 * player's team claims the chunks round the player, force loads the middle one, and a server
	 * team claims chunks to the east. The map tints each claim in its team's colour on the ground it
	 * covers: the top of a tower in the claim is tinted though it's drawn north of the claim's
	 * ground, and the ground north of the claim isn't. Zoomed far out, and on the minimap, the claims
	 * show too. Unclaiming takes the tint away.
	 */
	private static void ftbClaims(ClientGameTestContext context, TestSingleplayerContext world) {
		if (!context.computeOnClient(mc -> dev.diorama.compat.FtbChunks.available())) {
			throw new AssertionError("DIORAMA_TESTS=ftb-claims needs FTB Chunks installed");
		}
		// Two server teams claim the hills and village round spawn, side by side.
		int spawnX = context.computeOnClient(mc -> mc.player.getBlockX());
		int spawnY = context.computeOnClient(mc -> mc.player.getBlockY());
		int spawnZ = context.computeOnClient(mc -> mc.player.getBlockZ());
		world.getServer().runCommand("ftbteams server create Spawn");
		world.getServer().runCommand("ftbteams server create Market");
		// Commands name a team by its short name, its name and a piece of its id.
		String spawn = ftbTeamShortName(world, "Spawn");
		String market = ftbTeamShortName(world, "Market");
		world.getServer().runCommand("ftbteams server settings " + spawn + " ftbteams:color #E04040");
		world.getServer().runCommand("ftbteams server settings " + market + " ftbteams:color #3CB4E6");
		world.getServer().runCommand("ftbchunks admin claim_as " + spawn + " 48 " + spawnX + " " + spawnZ);
		world.getServer().runCommand("ftbchunks admin claim_as " + market + " 16 " + (spawnX + 80) + " " + spawnZ);
		context.waitFor(mc -> Claims.get().at((spawnX + 80) >> 4, spawnZ >> 4) != null && Claims.get().at(spawnX >> 4, spawnZ >> 4) != null, 600);
		openMapOnPlayer(context);
		context.waitFor(mc -> claimsShown(new TileKey(TileKey.SURFACE, Math.floorDiv(spawnX, Projection.TILE_UNITS),
			Math.floorDiv((int) Math.floor(Projection.v(spawnY, spawnZ)), Projection.TILE_UNITS))), 200);
		settle(context, 20);
		context.takeScreenshot("ftb-claims-00-spawn");
		lookAtPlayer(context, 2.0, TileKey.SURFACE);
		settle(context, 20);
		context.takeScreenshot("ftb-claims-00-spawn-zoom-2");
		// Turned off, the claims go, and the map is as it was without them.
		context.runOnClient(mc -> Settings.get().claims = Settings.ClaimsShown.OFF);
		context.waitTicks(5);
		if (context.computeOnClient(mc -> !Claims.get().isEmpty())) {
			throw new AssertionError("Claims still known with the setting off");
		}
		context.takeScreenshot("ftb-claims-00-spawn-zoom-2-off");
		context.runOnClient(mc -> Settings.get().claims = Settings.ClaimsShown.ALWAYS);
		context.waitFor(mc -> Claims.get().at(spawnX >> 4, spawnZ >> 4) != null, 600);
		context.setScreen(() -> null);

		// The middle of a chunk, on an open patch far from anything placed before.
		int cx = -1792 + 8;
		int cz = 1808 + 8;
		int x0 = cx - 56;
		int z0 = cz - 40;
		int x1 = cx + 88;
		int z1 = cz + 40;
		world.getServer().runCommand("forceload add " + x0 + " " + z0 + " " + x1 + " " + z1);
		world.getServer().runCommand("tp @a " + cx + " 200 " + cz);
		context.waitFor(mc -> mc.player.blockPosition().getX() == cx, 200);
		context.waitTicks(60);
		// A grass platform over whatever is there, water included, with clear air above it.
		int y = Math.max(72, world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz)));
		// Fill takes at most 32768 blocks at once: a chunk's column at a time.
		for (int x = x0; x <= x1; x += 16) {
			for (int z = z0; z <= z1; z += 16) {
				int xe = Math.min(x1, x + 15);
				int ze = Math.min(z1, z + 15);
				world.getServer().runCommand("fill " + x + " " + y + " " + z + " " + xe + " " + (y + 60) + " " + ze + " air");
				world.getServer().runCommand("fill " + x + " " + (y - 1) + " " + z + " " + xe + " " + (y - 1) + " " + ze + " grass_block");
			}
		}
		// A tower in the claim's north-west chunk, whose top the map draws well north of its foot.
		int towerX = cx - 16;
		int towerZ = cz - 16;
		int top = y + 23;
		world.getServer().runCommand("fill " + (towerX - 3) + " " + y + " " + (towerZ - 3) + " " + (towerX + 2) + " " + top + " " + (towerZ + 2) + " stone_bricks");
		world.getServer().runCommand("tp @a " + cx + " " + y + " " + cz);
		world.getServer().runCommand("time set noon");
		context.waitFor(mc -> mc.player.blockPosition().getX() == cx && mc.player.onGround(), 600);
		settle(context, 60);

		world.getServer().runCommand("execute as @a at @s run ftbchunks claim 16");
		world.getServer().runCommand("execute as @a at @s run ftbchunks load");
		world.getServer().runCommand("ftbchunks admin claim_as " + spawn + " 16 " + (cx + 64) + " " + cz);
		int playerChunkX = cx >> 4;
		int playerChunkZ = cz >> 4;
		long claimed = System.nanoTime();
		for (int tick = 0; ; tick++) {
			boolean all = context.computeOnClient(mc -> {
				Claims.Snapshot claims = Claims.get();
				return claims.at(playerChunkX - 1, playerChunkZ - 1) != null && claims.at(playerChunkX + 4, playerChunkZ) != null
					&& claims.at(playerChunkX, playerChunkZ) != null && claims.at(playerChunkX, playerChunkZ).forceLoaded();
			});
			if (all) {
				break;
			}
			if (tick > 600) {
				String seen = context.computeOnClient(mc -> "own " + Claims.get().at(playerChunkX - 1, playerChunkZ - 1)
					+ ", middle " + Claims.get().at(playerChunkX, playerChunkZ) + ", Spawn's " + Claims.get().at(playerChunkX + 4, playerChunkZ));
				throw new AssertionError("The claims didn't all reach the map: " + seen);
			}
			context.waitTick();
		}
		Diorama.LOGGER.info("FTB claims reached the map's claims {} ms after the commands", (System.nanoTime() - claimed) / 1_000_000);
		Claims.Claim own = context.computeOnClient(mc -> Claims.get().at(playerChunkX + 1, playerChunkZ + 1));
		Claims.Claim server = context.computeOnClient(mc -> Claims.get().at(playerChunkX + 4, playerChunkZ));
		if (own == null || server == null || own.team().equals(server.team()) || server.rgb() != 0xE04040 || !"Spawn".equals(server.name())) {
			throw new AssertionError("Expected the player's claim and Spawn's red claim: " + own + ", " + server);
		}
		if (context.computeOnClient(mc -> Claims.get().at(playerChunkX - 2, playerChunkZ)) != null) {
			throw new AssertionError("A chunk outside both claims shows as claimed");
		}
		settle(context, 40);

		// The tower's top is in the claim; the ground just north of the claim isn't, though a ray
		// at its row crosses the claim lower down.
		int claimNorth = (playerChunkZ - 1) << 4;
		assertClaimTint(context, towerX + 0.5, top + 1, towerZ + 0.5, true, "the tower's top");
		assertClaimTint(context, cx + 0.5, y, claimNorth - 3 + 0.5, false, "the ground north of the claim");
		assertClaimTint(context, cx + 0.5, y, cz + 0.5, true, "the ground under the player");
		assertClaimTint(context, cx + 64 + 0.5, y, cz + 0.5, true, "Spawn's ground");
		assertClaimTint(context, cx + 30 + 0.5, y, cz + 0.5, false, "the ground between the claims");

		openMapOnPlayer(context);
		context.waitFor(mc -> claimsShown(new TileKey(TileKey.SURFACE, Math.floorDiv(cx, Projection.TILE_UNITS),
			Math.floorDiv((int) Math.floor(Projection.v(y, cz)), Projection.TILE_UNITS))), 200);
		context.takeScreenshot("ftb-claims-01-map");
		lookAtPlayer(context, 1.0, TileKey.SURFACE);
		settle(context, 20);
		context.takeScreenshot("ftb-claims-02-map-zoom-1");
		// Hovering a claimed spot names its owner.
		lookAtPlayer(context, 4.0, TileKey.SURFACE);
		settle(context, 10);
		double[] spot = mapPoint(context, 60, 2);
		moveCursor(context, spot[2], spot[3]);
		context.waitTicks(10);
		context.takeScreenshot("ftb-claims-03-hover");

		// Far out the map shows overview tiles, which carry their own chunks once rebuilt. Back
		// over spawn, which has more mapped round it.
		context.waitTicks(200);
		double[] far = {0.35, 0.12};
		for (int i = 0; i < far.length; i++) {
			double zoom = far[i];
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(spawnX, Projection.v(spawnY, spawnZ), zoom, TileKey.SURFACE));
			// The view the screen reports is the one it last drew.
			context.waitTicks(3);
			int lod = context.computeOnClient(mc -> MapViewport.lodFor(MapSession.get().map(), ((MapScreen) mc.gui.screen()).view()));
			if (lod == 0) {
				throw new AssertionError("Zoomed out to " + zoom + ", the map should show overview tiles");
			}
			TileKey overview = new TileKey(TileKey.SURFACE, Math.floorDiv(spawnX, Projection.TILE_UNITS << lod),
				Math.floorDiv((int) Math.floor(Projection.v(spawnY, spawnZ)), Projection.TILE_UNITS << lod), lod);
			context.waitFor(mc -> claimsShown(overview), 600);
			settle(context, 20);
			context.takeScreenshot("ftb-claims-04-map-far-lod" + lod);
			if (i == far.length - 1) {
				// A map saved before overview tiles kept their chunks: the claims go, and come back
				// once the tiles under it are rebuilt.
				MapLevel level = context.computeOnClient(mc -> MapSession.get().map());
				level.forgetChunks(overview);
				context.waitFor(mc -> !claimsShown(overview), 200);
				context.waitFor(mc -> claimsShown(overview), 1200);
				context.takeScreenshot("ftb-claims-04-map-far-migrated");
			}
		}
		context.setScreen(() -> null);
		context.waitTicks(20);
		context.takeScreenshot("ftb-claims-05-minimap");

		// Unclaiming takes the tint away.
		world.getServer().runCommand("execute as @a at @s run ftbchunks unload");
		world.getServer().runCommand("execute as @a at @s run ftbchunks unclaim 16");
		long unclaimed = System.nanoTime();
		context.waitFor(mc -> Claims.get().at(playerChunkX, playerChunkZ) == null, 600);
		Diorama.LOGGER.info("FTB unclaim reached the map's claims {} ms after the command", (System.nanoTime() - unclaimed) / 1_000_000);
		assertClaimTint(context, cx + 0.5, y, cz + 0.5, false, "the ground under the player after unclaiming");
		assertClaimTint(context, cx + 64 + 0.5, y, cz + 0.5, true, "Spawn's ground after the player unclaimed");
		openMapOnPlayer(context);
		context.waitTicks(40);
		context.takeScreenshot("ftb-claims-06-unclaimed");
		context.setScreen(() -> null);
		world.getServer().runCommand("ftbchunks admin unclaim_as " + spawn + " 16 " + (cx + 64) + " " + cz);
		world.getServer().runCommand("forceload remove " + x0 + " " + z0 + " " + x1 + " " + z1);

		// What a big server costs: 65 by 65 claimed chunks next to the player, arriving at once.
		// Claims are taken in on the client tick; painting a tile runs on a baker thread.
		context.runOnClient(mc -> Claims.resetSlowestTick());
		world.getServer().runCommand("ftbchunks admin claim_as " + market + " 512 " + (cx - 600) + " " + cz);
		context.waitFor(mc -> Claims.get().at((cx - 600) >> 4, cz >> 4) != null && Claims.get().at((cx - 1100) >> 4, (cz + 500) >> 4) != null, 1200);
		context.waitTicks(200);
		String parts = context.computeOnClient(mc -> Claims.slowestTickParts());
		long slowest = context.computeOnClient(mc -> Claims.resetSlowestTick());
		int nearby = context.computeOnClient(mc -> {
			int n = 0;
			for (int x = ((cx - 600) >> 4) - 40; x <= ((cx - 600) >> 4) + 40; x++) {
				for (int z = (cz >> 4) - 40; z <= (cz >> 4) + 40; z++) {
					n += Claims.get().at(x, z) != null ? 1 : 0;
				}
			}
			return n;
		});
		TileKey here = new TileKey(TileKey.SURFACE, Math.floorDiv(cx, Projection.TILE_UNITS), Math.floorDiv((int) Math.floor(Projection.v(y, cz)), Projection.TILE_UNITS));
		long paint = context.computeOnClient(mc -> {
			MapSession.get().map().claimPixels(here);
			long t = System.nanoTime();
			MapSession.get().map().claimPixels(here);
			return System.nanoTime() - t;
		});
		Diorama.LOGGER.info("FTB claims cost: {} chunks claimed nearby, slowest tick spent on claims {} us while they arrived ({}), painting one tile {} ms",
			nearby, slowest / 1000, parts, paint / 1_000_000);
		world.getServer().runCommand("ftbchunks admin unclaim_as " + market + " 512 " + (cx - 600) + " " + cz);

		// Deleting a server team sends no update for its chunks; its claims go all the same.
		world.getServer().runCommand("ftbteams server delete " + spawn);
		context.waitFor(mc -> Claims.get().at(spawnX >> 4, spawnZ >> 4) == null, 400);
		// Let the server finish with the deleted team before the world closes.
		settle(context, 20);
	}

	/** The short name FTB Teams' commands know a team by, from its display name. */
	private static String ftbTeamShortName(TestSingleplayerContext world, String name) {
		return world.getServer().computeOnServer(server -> {
			try {
				Object api = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI").getMethod("api").invoke(null);
				Object manager = api.getClass().getMethod("getManager").invoke(api);
				for (Object team : (java.util.Collection<?>) manager.getClass().getMethod("getTeams").invoke(manager)) {
					String shortName = (String) team.getClass().getMethod("getShortName").invoke(team);
					if (shortName.equals(name) || shortName.startsWith(name + "#")) {
						return shortName;
					}
				}
			} catch (ReflectiveOperationException e) {
				throw new AssertionError("Couldn't look up FTB Teams' teams", e);
			}
			throw new AssertionError("No FTB team named " + name);
		});
	}

	/** Whether the map has painted claims over a tile it shows. */
	private static boolean claimsShown(TileKey key) {
		MapTile tile = MapSession.get().map().peek(key);
		return tile != null && tile.claims() != null;
	}

	/** Checks whether the map tints the top of the block at a spot ({@code y} is the height of its top face) as claimed. */
	private static void assertClaimTint(ClientGameTestContext context, double x, double y, double z, boolean claimed, String what) {
		double v = Projection.v(y, z);
		TileKey key = new TileKey(TileKey.SURFACE, Math.floorDiv((int) Math.floor(x), Projection.TILE_UNITS), Math.floorDiv((int) Math.floor(v), Projection.TILE_UNITS));
		int px = (int) Math.floor((x - key.minU()) * Projection.PIXELS_PER_UNIT);
		int py = (int) Math.floor((v - key.minV()) * Projection.PIXELS_PER_UNIT);
		int pixel = context.computeOnClient(mc -> {
			int[] pixels = MapSession.get().map().claimPixels(key);
			return pixels == null ? 0 : pixels[py * Projection.TILE_PIXELS + px];
		});
		if ((pixel >>> 24 != 0) != claimed) {
			throw new AssertionError("Expected " + what + " " + (claimed ? "tinted as claimed" : "untinted") + ", pixel " + Integer.toHexString(pixel));
		}
	}

	/**
	 * After rejoining, the server sends the hidden waystone again, and it is still hidden. It sends
	 * the picked-up one too, from where it stood, and that one stays off until it's placed again
	 * on the same spot.
	 */
	private static void waystonesAfterRejoin(ClientGameTestContext context, TestSingleplayerContext world) {
		UUID id = hiddenWaystone;
		context.waitFor(mc -> WaypointStore.get() != null && WaypointStore.get().waystones().stream().anyMatch(w -> w.id.equals(id)), 600);
		context.waitTicks(20);
		context.runOnClient(mc -> {
			for (Waypoint w : WaypointStore.get().waystones()) {
				if (w.id.equals(id) && w.visible) {
					throw new AssertionError("The hidden waystone shows again after rejoining");
				}
				if (w.id.equals(pickedUpWaystone)) {
					throw new AssertionError("The waystone picked up with Silk Touch is back on the map after rejoining");
				}
			}
		});
		BlockPos spot = pickedUpAt;
		world.getServer().runCommand("tp @a " + (spot.getX() + 2) + " " + spot.getY() + " " + spot.getZ());
		context.waitTicks(20);
		world.getServer().runOnServer(server -> {
			net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			int slot = waystoneSlot(server);
			net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(slot).copy();
			player.getInventory().setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
			player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
		});
		placeHeld(world, spot);
		context.waitFor(mc -> WaypointStore.get().waystones().stream().anyMatch(w -> w.id.equals(pickedUpWaystone) && w.x == spot.getX() && w.z == spot.getZ()), 200);
	}

	/** The player's inventory slot holding a waystone item, or -1. */
	private static int waystoneSlot(net.minecraft.server.MinecraftServer server) {
		var inventory = server.getPlayerList().getPlayers().get(0).getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			var item = inventory.getItem(i);
			// waystones:waystone, which 26.1 renamed waystones:andesite_waystone.
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
			if (!item.isEmpty() && id.startsWith("waystones:") && id.endsWith("waystone")) {
				return i;
			}
		}
		return -1;
	}

	/** Waits until the client knows {@code count} waystones, and says what the server and Waystones have if it doesn't. */
	private static void waitForWaystones(ClientGameTestContext context, TestSingleplayerContext world, int count, BlockPos... at) {
		for (int tick = 0; tick < 200; tick++) {
			if (context.computeOnClient(mc -> WaypointStore.get() != null && WaypointStore.get().waystones().size() == count)) {
				return;
			}
			context.waitTick();
		}
		String blocks = world.getServer().computeOnServer(server -> {
			StringBuilder out = new StringBuilder();
			for (BlockPos pos : at) {
				out.append(pos.toShortString()).append(' ').append(server.overworld().getBlockState(pos)).append("; ");
			}
			return out.toString();
		});
		String known = context.computeOnClient(mc -> {
			try {
				Object list = waystonesClass("api.WaystonesAPI").getMethod("getActivatedWaystones", net.minecraft.world.entity.player.Player.class).invoke(null, mc.player);
				return "Waystones knows " + ((java.util.Collection<?>) list).size() + ", Diorama " + (WaypointStore.get() == null ? "no store" : WaypointStore.get().waystones().size()) + ", screen " + mc.gui.screen();
			} catch (ReflectiveOperationException e) {
				return e.toString();
			}
		});
		throw new AssertionError("Expected " + count + " waystones: " + blocks + known);
	}

	private static @Nullable Class<?> waystonesClass(String name) {
		try {
			return Class.forName("net.blay09.mods.waystones." + name);
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	/** Renames a waystone as Waystones' edit screen does, through the handler of the message it sends. */
	private static void renameWaystone(TestSingleplayerContext world, UUID id, String name) {
		world.getServer().runOnServer(server -> {
			try {
				Class<?> message = waystonesClass("network.message.EditWaystoneMessage");
				if (message == null) {
					message = waystonesClass("network.message.ServerboundEditWaystonePacket");
				}
				Class<?> visibility = waystonesClass("api.WaystoneVisibility");
				Object activation = visibility.getField("ACTIVATION").get(null);
				Object edit = message.getConstructor(UUID.class, String.class, visibility).newInstance(id, name, activation);
				message.getMethod("handle", net.minecraft.server.level.ServerPlayer.class, message).invoke(null, server.getPlayerList().getPlayers().get(0), edit);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	/** Places a waystone standing on the block under {@code pos}, as the player would. */
	private static void placeWaystone(TestSingleplayerContext world, BlockPos pos) {
		world.getServer().runCommand("item replace entity @a weapon.mainhand with waystones:waystone");
		placeHeld(world, pos);
	}

	/** Places what the player holds on the block under {@code pos}. */
	private static void placeHeld(TestSingleplayerContext world, BlockPos pos) {
		world.getServer().runOnServer(server -> {
			net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			BlockPos ground = pos.below();
			player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND,
				new net.minecraft.world.phys.BlockHitResult(new Vec3(ground.getX() + 0.5, ground.getY() + 1, ground.getZ() + 0.5), net.minecraft.core.Direction.UP, ground, false));
		});
	}

	private static BlockPos standing(Waypoint waypoint) {
		return new BlockPos(waypoint.x, waypoint.y, waypoint.z);
	}

	/** The waystone standing at a column. */
	private static Waypoint waystoneAt(BlockPos pos) {
		for (Waypoint w : WaypointStore.get().waystones()) {
			if (w.x == pos.getX() && w.z == pos.getZ() && w.kind == Waypoint.Kind.WAYSTONE) {
				return w;
			}
		}
		throw new AssertionError("No waystone at " + pos + ": " + WaypointStore.get().waystones().stream().map(w -> w.name + " " + w.x + "," + w.y + "," + w.z).toList());
	}

	/** A waystone's pin on the open map, in window pixels; null if it isn't drawn. */
	private static float @Nullable [] waystonePin(ClientGameTestContext context, UUID id) {
		return context.computeOnClient(mc -> {
			float scale = (float) mc.getWindow().getGuiScale();
			for (WaypointOverlay.Pin p : ((MapScreen) mc.gui.screen()).pins()) {
				if (p.waypoint().id.equals(id) && !p.clamped()) {
					return new float[] {p.x() * scale, p.y() * scale};
				}
			}
			return null;
		});
	}

	/** The name of the folder the waypoint list shows. */
	private static String folderTitle(Minecraft mc) {
		return ((WaypointListScreen) mc.gui.screen()).folderName();
	}

	private static List<String> visibleButtons(Minecraft mc) {
		List<String> out = new java.util.ArrayList<>();
		for (var child : mc.gui.screen().children()) {
			if (child instanceof net.minecraft.client.gui.components.Button button && button.visible) {
				out.add(button.getMessage().getString());
			}
		}
		return out;
	}

	private static void temporaryGone(ClientGameTestContext context) {
		context.waitFor(mc -> mc.player != null && WorldKey.current() != null, 600);
		context.runOnClient(mc -> {
			List<Waypoint> all = WaypointStore.get().all();
			if (all.stream().anyMatch(w -> w.id.equals(leftTemporary) || w.kind == Waypoint.Kind.TEMPORARY)) {
				throw new AssertionError("A temporary waypoint was still there after leaving the world");
			}
			if (all.stream().noneMatch(w -> w.id.equals(keptTemporary) && w.kind == Waypoint.Kind.MANUAL)) {
				throw new AssertionError("The temporary waypoint kept from the menu is gone after leaving the world");
			}
		});
	}

	private static void openMapOnPlayer(ClientGameTestContext context) {
		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 4.0, TileKey.SURFACE);
		settle(context, 20);
		menuFrame(context);
	}

	/**
	 * Right-clicks the map {@code east} blocks east and {@code down} map rows below the player,
	 * and returns the map point clicked, {u, v}.
	 */
	private static double[] rightClickMap(ClientGameTestContext context, int east, int down) {
		double[] at = mapPoint(context, east, down);
		rightClickAt(context, at[2], at[3]);
		return new double[] {at[0], at[1]};
	}

	/** The map point {@code east} blocks east and {@code down} rows below the player, and its window position: {u, v, x, y}. */
	private static double[] mapPoint(ClientGameTestContext context, int east, int down) {
		return context.computeOnClient(mc -> {
			MapViewport.View view = ((MapScreen) mc.gui.screen()).view();
			double u = mc.player.getBlockX() + east + 0.5;
			double v = Projection.v(mc.player.getY(), mc.player.getZ()) + down;
			double scale = mc.getWindow().getGuiScale();
			return new double[] {u, v, view.screenX(u) * scale, view.screenY(v) * scale};
		});
	}

	/** Right-clicks the map at a window position, which must open its menu. */
	private static void rightClickAt(ClientGameTestContext context, double x, double y) {
		moveCursor(context, x, y);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(3);
		menuFrame(context);
		boolean open = context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen map && map.menu() != null);
		if (!open) {
			throw new AssertionError("Right-clicking the map didn't open its menu");
		}
		// Where the spot comes from a saved tile on a slow disk, a placeholder shows first.
		context.waitFor(mc -> !(mc.gui.screen() instanceof MapScreen map && map.menu() != null
			&& map.menu().labels().equals(List.of("Finding the ground…"))), 200);
	}

	/** Right-clicks the pin of the waypoint at a position. */
	private static void rightClickPin(ClientGameTestContext context, int[] pos) {
		double[] at = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			for (WaypointOverlay.Pin pin : ((MapScreen) mc.gui.screen()).pins()) {
				if (pin.waypoint().x == pos[0] && pin.waypoint().y == pos[1] && pin.waypoint().z == pos[2]) {
					return new double[] {pin.x() * scale, pin.y() * scale};
				}
			}
			throw new AssertionError("No pin at " + pos[0] + "," + pos[1] + "," + pos[2]);
		});
		moveCursor(context, at[0], at[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		context.waitTicks(3);
		menuFrame(context);
	}

	/** Adds a temporary waypoint from the menu, and returns where the menu said it is. */
	private static int[] addTemporary(ClientGameTestContext context, int east, int down) {
		rightClickMap(context, east, down);
		int[] spot = menuSpot(context);
		clickMenu(context, "Add temporary waypoint");
		boolean added = context.computeOnClient(mc -> temporaryAt(spot) != null);
		if (!added) {
			throw new AssertionError("Add temporary waypoint didn't add one at " + spot[0] + "," + spot[1] + "," + spot[2]);
		}
		return spot;
	}

	private static @Nullable Waypoint temporaryAt(int[] pos) {
		for (Waypoint waypoint : WaypointStore.get().all()) {
			if (waypoint.kind == Waypoint.Kind.TEMPORARY && waypoint.x == pos[0] && waypoint.y == pos[1] && waypoint.z == pos[2]) {
				return waypoint;
			}
		}
		return null;
	}

	private static void assertClipboard(ClientGameTestContext context, String expected) {
		String copied = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
		if (!copied.equals(expected)) {
			throw new AssertionError("Copy coordinates copied '" + copied + "', not '" + expected + "'");
		}
	}

	/** The block position the open spot menu's first line names: "x, y, z · N m". */
	private static int[] menuSpot(ClientGameTestContext context) {
		String line = context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).menu().labels().get(0));
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(-?\\d+), (-?\\d+), (-?\\d+) · \\d+ m").matcher(line);
		if (!m.matches()) {
			throw new AssertionError("The spot menu starts with " + line);
		}
		return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
	}

	/** Clicks a line of the map's right-click menu with the mouse, the way a player would. */
	private static void clickMenu(ClientGameTestContext context, String label) {
		double[] at = context.computeOnClient(mc -> {
			MapMenu menu = mc.gui.screen() instanceof MapScreen map ? map.menu() : null;
			if (menu == null) {
				throw new AssertionError("The map's menu isn't open, looking for " + label);
			}
			float[] centre = menu.centreOf(label);
			if (centre == null) {
				throw new AssertionError("The map's menu has no " + label + ": " + menu.labels());
			}
			double scale = mc.getWindow().getGuiScale();
			return new double[] {centre[0] * scale, centre[1] * scale};
		});
		moveCursor(context, at[0], at[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
		menuFrame(context);
	}

	/** {@code DIORAMA_GIF=1}: frames of the menu tour for a GIF. */
	private static final boolean MENU_GIF = "1".equals(System.getenv("DIORAMA_GIF"));
	/** Where the cursor is, in window pixels; screenshots don't show it, so frames are named with it. */
	private static double[] cursor = {800, 450};
	private static int menuFrames;

	/** Moves the cursor, gliding there a frame at a time when GIF frames are wanted. */
	private static void moveCursor(ClientGameTestContext context, double x, double y) {
		int steps = MENU_GIF ? 10 : 1;
		double[] from = cursor;
		for (int i = 1; i <= steps; i++) {
			double t = i / (double) steps;
			double ease = t * t * (3 - 2 * t);
			cursor = new double[] {from[0] + (x - from[0]) * ease, from[1] + (y - from[1]) * ease};
			context.getInput().setCursorPos(cursor[0], cursor[1]);
			context.waitTick();
			menuFrame(context);
		}
		context.waitTicks(1);
	}

	private static void menuFrame(ClientGameTestContext context) {
		if (MENU_GIF) {
			context.takeScreenshot(String.format("map-menu-gif-%03d-%d-%d", menuFrames++, Math.round(cursor[0]), Math.round(cursor[1])));
		}
	}

	/**
	 * Every line in chat, newest first. Read through reflection: chat keeps them private. Found by
	 * type, not name, so it works where the game's names are obfuscated (1.21.11 in production).
	 */
	@SuppressWarnings("unchecked")
	private static java.util.List<net.minecraft.client.multiplayer.chat.GuiMessage> chatMessages(Minecraft mc) {
		try {
			for (java.lang.reflect.Field field : net.minecraft.client.gui.components.ChatComponent.class.getDeclaredFields()) {
				if (field.getGenericType() instanceof java.lang.reflect.ParameterizedType type && type.getRawType() == java.util.List.class
					&& type.getActualTypeArguments()[0] == net.minecraft.client.multiplayer.chat.GuiMessage.class) {
					field.setAccessible(true);
					return (java.util.List<net.minecraft.client.multiplayer.chat.GuiMessage>) field.get(mc.gui.hud.getChat());
				}
			}
			throw new AssertionError("Chat has no List<GuiMessage> field");
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** The command a line's click-to-run link carries, or null. */
	private static @Nullable String linkIn(net.minecraft.network.chat.Component line) {
		String[] command = new String[1];
		line.visit((style, text) -> {
			//? if >=1.21.5 {
			if (style.getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.RunCommand run) {
				command[0] = run.command();
			}
			//?} else {
			/*net.minecraft.network.chat.ClickEvent click = style.getClickEvent();
			if (click != null && click.getAction() == net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND) {
				command[0] = click.getValue();
			}
			*///?}
			return java.util.Optional.empty();
		}, net.minecraft.network.chat.Style.EMPTY);
		return command[0];
	}

	private static void assertConnected(ClientGameTestContext context, String after) {
		boolean connected = context.computeOnClient(mc -> mc.getConnection() != null && mc.level != null && mc.player != null);
		if (!connected) {
			throw new AssertionError("Disconnected after " + after);
		}
	}

	/**
	 * Importing from Xaero's Minimap, with files Xaero's Minimap 26.6.0 wrote in a real game
	 * (src/gametest/resources/xaero/): put where Xaero's keeps them for this world, the waypoint
	 * list offers them, and importing brings their names (a colon, a death point Xaero's names
	 * with a translation key), colours, hidden state, death marker and the Nether one across.
	 * A second look offers nothing, even after the player moved, so the one Xaero's stored
	 * without a height isn't taken for a new one. With several of Xaero's worlds and no telling
	 * which, the player picks. Then how a server's worlds are offered.
	 */
	private static void xaeroImport(ClientGameTestContext context, TestSingleplayerContext world) {
		// The folder names Xaero's gave a save called "Xaero_Test [1]" and a server at localhost:25580.
		if (!XaeroImport.singleplayerFolders("Xaero_Test [1]").get(0).equals("Xaero%us%Test %lb%1%rb%")
			|| !XaeroImport.serverFolders("localhost:25580", true).get(0).equals("Multiplayer_localhost")) {
			throw new AssertionError("Xaero's folder names come out as " + XaeroImport.singleplayerFolders("Xaero_Test [1]") + " and "
				+ XaeroImport.serverFolders("localhost:25580", true));
		}
		java.nio.file.Path root = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("xaero").resolve("minimap").resolve(XaeroImport.singleplayerFolders(
			mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString()).get(0)));
		xaeroFixture("singleplayer/overworld.txt", root.resolve("dim%0").resolve("waypoints.txt"));
		xaeroFixture("singleplayer/nether.txt", root.resolve("dim%-1").resolve("waypoints.txt"));
		// Next to them, an End file that isn't UTF-8 (a name in Latin-1): it's skipped, and the
		// rest is still offered.
		try {
			java.nio.file.Files.createDirectories(root.resolve("dim%1"));
			java.nio.file.Files.write(root.resolve("dim%1").resolve("waypoints.txt"),
				"waypoint:Caf\u00e9:C:10:64:10:0:false:0:gui.xaero_default:false:0:0:false\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
		Set<UUID> before = context.computeOnClient(mc -> new java.util.HashSet<>(WaypointStore.get().all().stream().map(w -> w.id).toList()));

		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("xaero-01-offered");
		String offer = context.computeOnClient(mc -> importButtonLabel(mc.gui.screen()));
		if (!"Import from Xaero's Minimap (8)".equals(offer)) {
			throw new AssertionError("The waypoint list offers " + offer);
		}
		context.runOnClient(mc -> press(buttonLabelled(mc.gui.screen(), "Import from Xaero's Minimap (8)")));
		context.waitTicks(5);
		context.takeScreenshot("xaero-02-imported");

		List<Waypoint> imported = context.computeOnClient(mc -> WaypointStore.get().all().stream().filter(w -> !before.contains(w.id)).toList());
		java.util.Map<String, Waypoint> byName = new java.util.HashMap<>();
		imported.forEach(w -> byName.put(w.name, w));
		if (imported.size() != 8 || !byName.keySet().equals(Set.of("Latest Death", "No Height", "Pink Tree", "Hidden Cave", "Base_2 - East", "Iron: Farm", "Home", "Nether Hub"))) {
			throw new AssertionError("Imported " + byName.keySet());
		}
		Waypoint home = byName.get("Home");
		if (home.x != -232 || home.y != 78 || home.z != -118 || !home.dimension.equals("minecraft:overworld") || home.color != Waypoint.PALETTE[0]) {
			throw new AssertionError("Home came in at " + home.x + "," + home.y + "," + home.z + " in " + home.dimension + ", colour " + Integer.toHexString(home.color));
		}
		if (byName.get("Latest Death").kind != Waypoint.Kind.DEATH || byName.get("Home").kind != Waypoint.Kind.MANUAL) {
			throw new AssertionError("Xaero's death point didn't come in as a death marker, or another waypoint did");
		}
		if (byName.get("Hidden Cave").visible || !byName.get("Home").visible) {
			throw new AssertionError("The waypoint hidden in Xaero's isn't hidden, or a shown one is");
		}
		if (!byName.get("Nether Hub").dimension.equals("minecraft:the_nether") || byName.get("Nether Hub").y != 128) {
			throw new AssertionError("Nether Hub came in as " + byName.get("Nether Hub").dimension + " at y " + byName.get("Nether Hub").y);
		}
		// Xaero's colour 16 is its magenta; dark blue is index 1.
		if (byName.get("Pink Tree").color != Waypoint.PALETTE[8] || byName.get("No Height").color != Waypoint.PALETTE[6]) {
			throw new AssertionError("Pink Tree is " + Integer.toHexString(byName.get("Pink Tree").color) + ", No Height " + Integer.toHexString(byName.get("No Height").color));
		}

		// Higher up, and with the list opened afresh, there's nothing left to import.
		context.setScreen(() -> null);
		world.getServer().runCommand("tp @a ~ ~20 ~");
		context.waitTicks(10);
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		String again = context.computeOnClient(mc -> importButtonLabel(mc.gui.screen()));
		if (again != null) {
			throw new AssertionError("After importing, the list still offers " + again);
		}
		context.setScreen(() -> null);
		// The death point's name came from the game's language. Under another language, or with
		// Xaero's (which has its own translation) removed, it reads differently, and it's still
		// the same death point.
		context.runOnClient(mc -> byName.get("Latest Death").name = "Derni\u00e8re mort");
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		String otherLanguage = context.computeOnClient(mc -> importButtonLabel(mc.gui.screen()));
		if (otherLanguage != null) {
			throw new AssertionError("With the death point named in another language, the list offers " + otherLanguage);
		}
		context.setScreen(() -> null);

		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			imported.forEach(store::remove);
			try {
				org.apache.commons.io.FileUtils.deleteDirectory(root.toFile());
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});

		// Two of Xaero's other worlds and no "waypoints" one: which is this world can't be told,
		// so the list asks, and the player takes one.
		xaeroFixture("singleplayer/overworld.txt", root.resolve("dim%0").resolve("mw$1_Survival.txt"));
		xaeroFixture("singleplayer/nether.txt", root.resolve("dim%0").resolve("mw$2_Old Base.txt"));
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(5);
		context.runOnClient(mc -> press(buttonLabelled(mc.gui.screen(), "Import from Xaero's Minimap...")));
		context.waitTicks(5);
		context.takeScreenshot("xaero-03-pick");
		context.runOnClient(mc -> press(buttonLabelled(mc.gui.screen(), "Survival, Overworld: 7 new")));
		context.waitTicks(5);
		context.takeScreenshot("xaero-04-picked");
		List<Waypoint> picked = context.computeOnClient(mc -> {
			buttonLabelled(mc.gui.screen(), "Survival, Overworld: imported");
			buttonLabelled(mc.gui.screen(), "Old Base, Overworld: 1 new");
			return WaypointStore.get().all().stream().filter(w -> !before.contains(w.id)).toList();
		});
		if (picked.size() != 7 || picked.stream().noneMatch(w -> w.name.equals("Iron: Farm"))) {
			throw new AssertionError("Picking Survival imported " + picked.stream().map(w -> w.name).toList());
		}
		// Back in the list, Old Base is still on offer.
		context.runOnClient(mc -> press(buttonLabelled(mc.gui.screen(), "Done")));
		context.waitTicks(5);
		String still = context.computeOnClient(mc -> importButtonLabel(mc.gui.screen()));
		if (!"Import from Xaero's Minimap...".equals(still)) {
			throw new AssertionError("Back from the picker, the list offers " + still);
		}
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			WaypointStore store = WaypointStore.get();
			picked.forEach(store::remove);
			try {
				org.apache.commons.io.FileUtils.deleteDirectory(root.toFile());
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});

		// A server's folder as Xaero's wrote it on a vanilla server, plus a copy under another
		// spawn's name, as a map reset leaves behind. On a server the player always confirms, with
		// the world Xaero's would open listed first: the one its config pins with multiworld
		// detection off, the current spawn's with it on. With Xaero's World Map's world in the
		// mix too, each dimension's file is offered on its own and none is marked.
		context.runOnClient(mc -> {
			java.nio.file.Path base = mc.gameDirectory.toPath().resolve("xaero-import-test");
			java.nio.file.Path server = base.resolve("Multiplayer_localhost");
			java.nio.file.Path config = server.resolve("config.txt");
			xaeroFixture("server/config.txt", config);
			xaeroFixture("server/overworld.txt", server.resolve("dim%0").resolve("mw-1,1,0_1.txt"));
			xaeroFixture("server/nether.txt", server.resolve("dim%-1").resolve("mw-1,1,0_1.txt"));
			xaeroFixture("server/overworld.txt", server.resolve("dim%0").resolve("mw7,1,3_2.txt"));
			List<String> folders = XaeroImport.serverFolders("localhost:25580", true);
			try {
				XaeroImport.Found pinned = XaeroImport.find(base, folders, false, "mw7,1,3", 64);
				if (pinned == null || pinned.chosen() != null || pinned.worlds().size() != 2
					|| !pinned.worlds().get(0).id().equals("mw-1,1,0") || !pinned.worlds().get(0).likely() || pinned.worlds().get(1).likely()) {
					throw new AssertionError("With detection off, the server's worlds aren't offered with the pinned one first and marked");
				}
				Set<String> names = Set.copyOf(pinned.worlds().get(0).entries().stream().map(e -> e.waypoint().name).toList());
				if (!names.equals(Set.of("Latest Death", "Mine: Deep", "Spawn Shop", "Fortress"))) {
					throw new AssertionError("The pinned server world brought " + names);
				}
				java.nio.file.Files.writeString(config, java.nio.file.Files.readString(config).replace("usingMultiworldDetection:false", "usingMultiworldDetection:true"));
				XaeroImport.Found bySpawn = XaeroImport.find(base, folders, false, "mw7,1,3", 64);
				if (!bySpawn.worlds().get(0).id().equals("mw7,1,3") || !bySpawn.worlds().get(0).likely()) {
					throw new AssertionError("With detection on, the current spawn's world isn't offered first");
				}
				xaeroFixture("server/overworld.txt", server.resolve("dim%0").resolve("mw$default_Default.txt"));
				XaeroImport.Found perDimension = XaeroImport.find(base, folders, false, "mw7,1,3", 64);
				// Two worlds' Overworld files, one's Nether file, World Map's.
				if (perDimension.worlds().size() != 4 || perDimension.worlds().stream().anyMatch(w -> w.likely() || w.dimension() == null)) {
					throw new AssertionError("With World Map's world in the mix, the server import offers " + perDimension.worlds());
				}
				// With Xaero's told not to tell servers apart, one folder holds every server's files
				// under the same ids: each dimension stays its own row, and none is marked.
				java.nio.file.Path shared = base.resolve("Multiplayer_Any Address");
				xaeroFixture("server/config.txt", shared.resolve("config.txt"));
				xaeroFixture("server/overworld.txt", shared.resolve("dim%0").resolve("mw-1,1,0_1.txt"));
				xaeroFixture("server/nether.txt", shared.resolve("dim%-1").resolve("mw-1,1,0_1.txt"));
				XaeroImport.Found anyServer = XaeroImport.find(base, XaeroImport.serverFolders("localhost:25580", false), false, "mw7,1,3", 64);
				if (anyServer.worlds().size() != 2 || anyServer.worlds().stream().anyMatch(w -> w.likely() || w.dimension() == null)) {
					throw new AssertionError("The shared server folder offers " + anyServer.worlds());
				}
				org.apache.commons.io.FileUtils.deleteDirectory(base.toFile());
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
	}

	private static void xaeroFixture(String name, java.nio.file.Path to) {
		try (java.io.InputStream in = DioramaClientTest.class.getResourceAsStream("/xaero/" + name)) {
			if (in == null) {
				throw new AssertionError("Missing fixture " + name);
			}
			java.nio.file.Files.createDirectories(to.getParent());
			java.nio.file.Files.copy(in, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	/** Presses a button the way a click does. */
	private static void press(net.minecraft.client.gui.components.Button button) {
		//? if >=1.21.9 {
		button.onPress(null);
		//?} else {
		/*button.onPress();
		*///?}
	}

	/** The label of the waypoint list's import button, or null if it has none. */
	private static @Nullable String importButtonLabel(net.minecraft.client.gui.screens.Screen screen) {
		for (var child : screen.children()) {
			if (child instanceof net.minecraft.client.gui.components.Button button && button.getMessage().getString().startsWith("Import from Xaero's")) {
				return button.getMessage().getString();
			}
		}
		return null;
	}

	/** The screen's text fields, top to bottom and left to right. */
	private static List<net.minecraft.client.gui.components.EditBox> editBoxes(net.minecraft.client.gui.screens.Screen screen) {
		java.util.List<net.minecraft.client.gui.components.EditBox> boxes = new java.util.ArrayList<>();
		for (var child : screen.children()) {
			if (child instanceof net.minecraft.client.gui.components.EditBox box) {
				boxes.add(box);
			}
		}
		return boxes;
	}

	/** Clicks the middle of a widget with the mouse, the way a player does. */
	private static void clickAt(ClientGameTestContext context, net.minecraft.client.gui.components.AbstractWidget widget) {
		double[] at = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(widget.getX() + widget.getWidth() / 2.0) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	/** Clicks the left mouse button at a point given in GUI pixels. */
	private static void clickAtGui(ClientGameTestContext context, double x, double y) {
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		context.getInput().setCursorPos(x * scale, y * scale);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(2);
	}

	/** Clicks a button with the mouse, which does nothing while it's off (unlike clickScreenButton). */
	static void clickButton(ClientGameTestContext context, String translationKey) {
		String label = net.minecraft.network.chat.Component.translatable(translationKey).getString();
		clickAt(context, context.computeOnClient(mc -> buttonLabelled(mc.gui.screen(), label)));
	}

	/** Clicks a text field, empties it and types into it. */
	static void typeInto(ClientGameTestContext context, int field, String text) {
		clickAt(context, context.computeOnClient(mc -> editBoxes(mc.gui.screen()).get(field)));
		// The test input's Ctrl+A doesn't select, so the field is emptied directly; the typing is real.
		context.runOnClient(mc -> editBoxes(mc.gui.screen()).get(field).setValue(""));
		context.getInput().typeChars(text);
		context.waitTicks(2);
	}

	static void setGuiScale(ClientGameTestContext context, int scale) {
		context.runOnClient(mc -> {
			mc.options.guiScale().set(scale);
			//? if >=26.1 {
			mc.resizeGui();
			//?} else {
			/*mc.resizeDisplay();
			*///?}
		});
		context.waitTicks(3);
	}

	private static net.minecraft.client.gui.components.Button buttonLabelled(net.minecraft.client.gui.screens.Screen screen, String label) {
		for (var child : screen.children()) {
			if (child instanceof net.minecraft.client.gui.components.Button button && button.getMessage().getString().equals(label)) {
				return button;
			}
		}
		throw new AssertionError("No " + label + " button on " + screen.getClass().getSimpleName());
	}

	/**
	 * Settings: the default minimap with its compass and info lines, the settings screen, the
	 * minimap moved to another corner at another size, and the effect icons pushing it down.
	 */
	/**
	 * Chunk grid, slime chunks and spawn spots on a built scene: a stone platform high in open sky
	 * across a chunk border, lit by one torch, with a closed stone box on one corner.
	 */
	//? if <1.21.6 {
	/*// Opt-in with DIORAMA_TESTS=broken-fog. A resource pack whose fog shader doesn't compile, the way a
	// strict graphics driver might reject ours. Before 1.21.6 the game's own shader path clears the
	// player's resource packs (or crashes) when a shader fails, so the map has to draw without fog and
	// leave the packs alone, then bring the fog back once a reload fixes the shader.
	private static void brokenFog(ClientGameTestContext context) {
		String id = "file/diorama-broken-fog";
		java.nio.file.Path pack = context.computeOnClient(mc -> mc.getResourcePackDirectory().resolve("diorama-broken-fog"));
		context.runOnClient(mc -> {
			try {
				java.nio.file.Files.createDirectories(pack.resolve("assets/diorama/shaders/core"));
				int format = net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
				java.nio.file.Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\": {\"pack_format\": " + format + ", \"description\": \"Diorama test: a fog shader that doesn't compile\"}}");
				java.nio.file.Files.writeString(pack.resolve("assets/diorama/shaders/core/fog.fsh"), "#version 150\nvoid main() { this is not glsl }\n");
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
			mc.getResourcePackRepository().reload();
			if (!mc.getResourcePackRepository().addPack(id)) {
				throw new AssertionError("The broken fog test pack didn't load");
			}
			mc.reloadResourcePacks();
		});
		context.waitTicks(60);
		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 0.75, TileKey.SURFACE);
		settle(context, 60);
		context.takeScreenshot("broken-fog-01-no-fog");
		boolean kept = context.computeOnClient(mc -> mc.getResourcePackRepository().getSelectedIds().contains(id));
		context.setScreen(() -> null);

		context.runOnClient(mc -> {
			mc.getResourcePackRepository().removePack(id);
			mc.reloadResourcePacks();
		});
		context.waitTicks(60);
		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 0.75, TileKey.SURFACE);
		settle(context, 60);
		context.takeScreenshot("broken-fog-02-fog-back");
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(pack)) {
				files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
		if (!kept) {
			throw new AssertionError("A fog shader that doesn't compile took the player's resource packs away");
		}
	}
	*///?}

	/**
	 * The minimap with a screen open. An inventory hides it, so item lists from other mods (JEI) beside
	 * the inventory don't run into it. The pause menu keeps it, behind the menu.
	 */
	private static void screens(ClientGameTestContext context, TestSingleplayerContext world) {
		// Survival, so the inventory is the plain one other mods (JEI) draw beside.
		world.getServer().runCommand("gamemode survival @a");
		context.setScreen(() -> null);
		settle(context, 40);
		context.takeScreenshot("screens-01-none");
		context.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(Minecraft.getInstance().player));
		settle(context, 20);
		context.takeScreenshot("screens-02-inventory");
		// Under any screen, the minimap steps aside, the pause menu too (#147)...
		context.setScreen(() -> new net.minecraft.client.gui.screens.PauseScreen(true));
		settle(context, 20);
		context.takeScreenshot("screens-03-pause");
		// ...but not chat, which leaves the corners alone.
		//? if >=1.21.9 {
		context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("", false));
		//?} else {
		/*context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen(""));
		*///?}
		settle(context, 20);
		context.takeScreenshot("screens-03-chat");
		context.setScreen(() -> null);
		// F3's debug screen fills both top corners; the minimap steps out of its way, and comes back.
		context.runOnClient(DioramaClientTest::toggleDebugScreen);
		settle(context, 20);
		context.takeScreenshot("screens-04-debug");
		context.runOnClient(DioramaClientTest::toggleDebugScreen);
		settle(context, 20);
		context.takeScreenshot("screens-05-debug-closed");
		world.getServer().runCommand("gamemode creative @a");
	}

	/**
	 * The enlarge key (#153), bound by the player since it starts unbound: held, the minimap grows
	 * to the Enlarged size in its own corner, and stops at what fits the screen; let go, it's back
	 * to its size.
	 */
	private static void enlarge(ClientGameTestContext context) {
		context.setScreen(() -> null);
		int[] saved = context.computeOnClient(mc -> new int[] {Settings.get().minimapEnlarged, Settings.get().minimapCorner.ordinal()});
		context.runOnClient(mc -> {
			DioramaClient.ENLARGE_MINIMAP.setKey(InputConstants.getKey("key.keyboard.z"));
			net.minecraft.client.KeyMapping.resetMapping();
		});
		settle(context, 20);
		context.takeScreenshot("enlarge-01-normal");
		context.getInput().holdKey(DioramaClient.ENLARGE_MINIMAP);
		context.waitTicks(2);
		if (!context.computeOnClient(mc -> DioramaClient.ENLARGE_MINIMAP.isDown())) {
			throw new AssertionError("Holding the enlarge key didn't reach its key mapping");
		}
		settle(context, 40);
		context.takeScreenshot("enlarge-02-200");
		for (int percent : new int[] {150, 325, 400}) {
			context.runOnClient(mc -> Settings.get().minimapEnlarged = percent);
			settle(context, 20);
			context.takeScreenshot("enlarge-03-" + percent);
		}
		// On a tall, narrow window, 4x is wider than the screen: it stops at the screen's width.
		context.getInput().resizeWindow(360, 900);
		settle(context, 20);
		context.takeScreenshot("enlarge-03-400-narrow");
		context.getInput().resizeWindow(1600, 900);
		context.runOnClient(mc -> Settings.get().minimapEnlarged = 200);
		for (Settings.Corner corner : Settings.Corner.values()) {
			context.runOnClient(mc -> Settings.get().minimapCorner = corner);
			settle(context, 20);
			context.takeScreenshot("enlarge-04-" + corner.name().toLowerCase(java.util.Locale.ROOT));
		}
		context.runOnClient(mc -> Settings.get().minimapCorner = Settings.Corner.TOP_RIGHT);
		context.getInput().releaseKey(DioramaClient.ENLARGE_MINIMAP);
		context.waitTicks(5);
		if (context.computeOnClient(mc -> DioramaClient.ENLARGE_MINIMAP.isDown())) {
			throw new AssertionError("Letting go of the enlarge key didn't reach its key mapping");
		}
		context.takeScreenshot("enlarge-05-released");
		// The Enlarged slider sits beside Size, so the Minimap tab is no taller.
		context.setScreen(() -> new SettingsScreen(null));
		settle(context, 10);
		context.takeScreenshot("enlarge-06-settings");
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			Settings.get().minimapEnlarged = saved[0];
			Settings.get().minimapCorner = Settings.Corner.values()[saved[1]];
			DioramaClient.ENLARGE_MINIMAP.setKey(InputConstants.UNKNOWN);
			net.minecraft.client.KeyMapping.resetMapping();
		});
	}

	private static void toggleDebugScreen(Minecraft mc) {
		//? if >=1.21.9 {
		mc.debugEntries.toggleDebugOverlay();
		//?} else if >=1.21 {
		/*mc.getDebugOverlay().toggleOverlay();
		*///?} else {
		/*mc.options.renderDebug = !mc.options.renderDebug;
		*///?}
	}

	private static void overlays(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		// No leaf decay or growth below the scene: a chunk changing for its own reasons would
		// refresh its light and hide whether light changes alone reach the map.
		world.getServer().runCommand("gamerule random_tick_speed 0");
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		// The platform's west part starts 4 blocks before a chunk border.
		int x0 = Math.floorDiv(at.getX() + 40, 16) * 16 - 4;
		int z0 = at.getZ() - 8;
		int y = 150;
		world.getServer().runCommand("fill " + x0 + " " + y + " " + z0 + " " + (x0 + 23) + " " + y + " " + (z0 + 15) + " stone");
		world.getServer().runCommand("setblock " + (x0 + 12) + " " + (y + 1) + " " + (z0 + 8) + " torch");
		// A sealed room south of the platform, across the same chunk border, split by a wall in the
		// east chunk with a torch behind it. Opening the wall lights the west half, in the other
		// chunk, without any block there changing.
		int rz = z0 + 24;
		world.getServer().runCommand("fill " + x0 + " " + y + " " + rz + " " + (x0 + 10) + " " + (y + 3) + " " + (rz + 6) + " stone hollow");
		world.getServer().runCommand("fill " + (x0 + 6) + " " + (y + 1) + " " + (rz + 1) + " " + (x0 + 6) + " " + (y + 2) + " " + (rz + 5) + " stone");
		world.getServer().runCommand("setblock " + (x0 + 8) + " " + (y + 1) + " " + (rz + 3) + " torch");
		// A bottom slab at head height over a dark floor block: a zombie doesn't fit.
		world.getServer().runCommand("setblock " + (x0 + 1) + " " + (y + 2) + " " + (z0 + 4) + " stone_slab");
		// Leaves on the dark west edge: monsters can't spawn on them or under them.
		world.getServer().runCommand("fill " + (x0 + 1) + " " + (y + 1) + " " + (z0 + 11) + " " + (x0 + 3) + " " + (y + 1) + " " + (z0 + 13) + " oak_leaves[persistent=true]");
		// A sealed room (5x2x5 inside) on the platform's south-east corner: its floor gets no sky light.
		int bx = x0 + 17;
		int bz = z0 + 9;
		world.getServer().runCommand("fill " + bx + " " + (y + 1) + " " + bz + " " + (bx + 6) + " " + (y + 4) + " " + (bz + 6) + " stone hollow");
		Settings saved = context.computeOnClient(mc -> {
			Settings s = Settings.get();
			Settings before = new Settings();
			before.chunkGrid = s.chunkGrid;
			before.slimeChunks = s.slimeChunks;
			before.spawnSpots = s.spawnSpots;
			s.chunkGrid = true;
			s.slimeChunks = true;
			s.spawnSpots = Settings.SpawnSpots.AT_NIGHT_TOO;
			return before;
		});
		try {
			context.setScreen(MapScreen::new);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(x0 + 12, Projection.v(y + 1, z0 + 8), 4.0, TileKey.SURFACE));
			settle(context, 60);
			context.takeScreenshot("overlays-01-platform");

			int farX = x0 + 1;
			int farZ = z0 + 1;
			assertOverlay(context, TileKey.SURFACE, farX + 0.5, y + 1, farZ + 0.6, Overlays.SPAWN_AT_NIGHT, true, "spawn spot far from the torch");
			assertOverlay(context, TileKey.SURFACE, x0 + 13.5, y + 1, z0 + 8.6, Overlays.SPAWN_AT_NIGHT | Overlays.SPAWN_ANY_TIME, false, "spawn spot next to the torch");
			assertOverlay(context, TileKey.SURFACE, x0 + 2.5, y + 2, z0 + 12.6, Overlays.SPAWN_AT_NIGHT | Overlays.SPAWN_ANY_TIME, false, "spawn spot on leaves");
			assertOverlay(context, TileKey.SURFACE, x0 + 1.5, y + 1, z0 + 4.6, Overlays.SPAWN_AT_NIGHT | Overlays.SPAWN_ANY_TIME, false, "spawn spot under a slab at head height");
			// The chunk border is at x0 + 4: a line at its west edge, none mid-block.
			assertOverlay(context, TileKey.SURFACE, x0 + 4.1, y + 1, z0 + 2.5, Overlays.CHUNK_WEST, true, "chunk line on the border");
			assertOverlay(context, TileKey.SURFACE, x0 + 4.6, y + 1, z0 + 2.5, Overlays.CHUNK_WEST, false, "chunk line mid-block");
			long seed = context.computeOnClient(mc -> MapSession.get().slimeSeed());
			// The well-known slime chunk formula, written out independently of the mod's.
			int slimes = 0;
			for (int cx = -10; cx < 10; cx++) {
				for (int cz = -10; cz < 10; cz++) {
					boolean expected = legacySlimeChunk(seed, cx, cz);
					if (Overlays.slimeChunk(seed, cx, cz) != expected) {
						throw new AssertionError("Slime chunk " + cx + "," + cz + " doesn't match the known formula");
					}
					slimes += expected ? 1 : 0;
				}
			}
			if (slimes < 10 || slimes > 80) {
				throw new AssertionError("Slime chunks are about a tenth of all chunks, found " + slimes + " of 400");
			}
			// Where the formula's int arithmetic overflows, out to the world border.
			for (int c : new int[] {21, -21, 46341, -46341, 1874999, -1874999}) {
				for (int d : new int[] {0, 7, -46341, 1874999}) {
					if (Overlays.slimeChunk(seed, c, d) != legacySlimeChunk(seed, c, d) || Overlays.slimeChunk(seed, d, c) != legacySlimeChunk(seed, d, c)) {
						throw new AssertionError("Slime chunk " + c + "," + d + " doesn't match the known formula");
					}
				}
			}
			for (int x : new int[] {x0 + 1, x0 + 8}) {
				boolean slime = legacySlimeChunk(seed, x >> 4, (z0 + 2) >> 4);
				assertOverlay(context, TileKey.SURFACE, x + 0.5, y + 1, z0 + 2.5, Overlays.SLIME, slime, "slime chunk at x=" + x);
			}

			// A torch placed now clears the dots as far as its light reaches. It goes 12 blocks
			// east: the client lights it itself, and the spot is past the cells a block change
			// re-bakes for how it looks, so only the light refresh can clear it.
			world.getServer().runCommand("setblock " + (farX + 12) + " " + (y + 1) + " " + farZ + " torch");
			settle(context, 60);
			assertOverlay(context, TileKey.SURFACE, farX + 0.5, y + 1, farZ + 0.6, Overlays.SPAWN_AT_NIGHT | Overlays.SPAWN_ANY_TIME, false, "spawn spot after placing a torch");

			// A cave slice just under the room's roof shows its floor (the box's bottom layer, so
			// its top face is at y + 2): dark any time.
			int layer = y + 4;
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(bx + 3, Projection.v(y + 1, bz + 3), 4.0, layer));
			settle(context, 60);
			context.takeScreenshot("overlays-02-closed-room");
			assertOverlay(context, layer, bx + 3.5, y + 2, bz + 3.6, Overlays.SPAWN_ANY_TIME, true, "spawn spot in the closed room");

			// The split room: dark west of the wall until the wall opens.
			int splitLayer = y + 3;
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(x0 + 5, Projection.v(y + 1, rz + 3), 4.0, splitLayer));
			settle(context, 60);
			assertOverlay(context, splitLayer, x0 + 2.5, y + 1, rz + 3.6, Overlays.SPAWN_ANY_TIME, true, "spawn spot behind the wall");
			world.getServer().runCommand("setblock " + (x0 + 6) + " " + (y + 1) + " " + (rz + 3) + " air");
			settle(context, 60);
			context.takeScreenshot("overlays-02b-wall-opened");
			assertOverlay(context, splitLayer, x0 + 2.5, y + 1, rz + 3.6, Overlays.SPAWN_ANY_TIME | Overlays.SPAWN_AT_NIGHT, false, "spawn spot once the torch shines through");

			// Spawn spots off: the tiles bake again without them, and only the grid and slime chunks show.
			context.runOnClient(mc -> Settings.get().spawnSpots = Settings.SpawnSpots.OFF);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(x0 + 12, Projection.v(y + 1, z0 + 8), 4.0, TileKey.SURFACE));
			settle(context, 20);
			context.takeScreenshot("overlays-03-grid-and-slime-only");

			// What spawn spots are for: a natural cave, with only the spots that are dark by day.
			context.runOnClient(mc -> Settings.get().spawnSpots = Settings.SpawnSpots.ALWAYS_DARK);
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(at.getX(), Projection.v(30, at.getZ()), 2.5, 32));
			settle(context, 80);
			context.takeScreenshot("overlays-04-cave-always-dark");
			context.setScreen(() -> null);
		} finally {
			world.getServer().runCommand("gamerule random_tick_speed 3");
			context.runOnClient(mc -> {
				Settings s = Settings.get();
				s.chunkGrid = saved.chunkGrid;
				s.slimeChunks = saved.slimeChunks;
				s.spawnSpots = saved.spawnSpots;
			});
		}
	}

	private static boolean legacySlimeChunk(long seed, int x, int z) {
		java.util.Random random = new java.util.Random(seed + (long) (x * x * 0x4c1906) + (long) (x * 0x5ac0db) + (long) (z * z) * 0x4307a7L + (long) (z * 0x5f24f) ^ 0x3ad8025fL);
		return random.nextInt(10) == 0;
	}

	/** Checks the overlay bits at a world point on the map (its top face, for a block). */
	private static void assertOverlay(ClientGameTestContext context, int layer, double x, double y, double z, int bits, boolean expected, String what) {
		int found = context.computeOnClient(mc -> {
			double u = x;
			double v = Projection.v(y, z);
			int tu = (int) Math.floor(u / Projection.TILE_UNITS);
			int tv = (int) Math.floor(v / Projection.TILE_UNITS);
			MapTile tile = MapSession.get().map().peek(new TileKey(layer, tu, tv));
			if (tile == null) {
				return -2;
			}
			int px = (int) ((u - tu * Projection.TILE_UNITS) * Projection.PIXELS_PER_UNIT);
			int py = (int) ((v - tv * Projection.TILE_UNITS) * Projection.PIXELS_PER_UNIT);
			return tile.overlayBits(px, py);
		});
		if (found < 0) {
			throw new AssertionError(what + ": " + (found == -2 ? "no tile there" : "the tile has no overlay bits"));
		}
		if (((found & bits) != 0) != expected) {
			throw new AssertionError(what + ": expected " + (expected ? "" : "no ") + "bits " + bits + ", found " + found);
		}
	}

	/**
	 * A settings file from before GPU baking was on for everyone says off, chosen or not, so it
	 * turns on once. Off after that is the player's choice and stays.
	 */
	private static void gpuBakingMovesOnce(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			java.nio.file.Path file = mc.gameDirectory.toPath().resolve("diorama/config.json");
			Settings settings = Settings.get();
			try {
				settings.save();
				String kept = java.nio.file.Files.readString(file);
				String old = kept.replaceAll("\\s*\"defaultsMoved\": \\d+,", "").replaceAll("\"gpuBaking\": \\w+", "\"gpuBaking\": false");
				if (old.contains("defaultsMoved")) {
					throw new AssertionError("The test's old settings file still says defaultsMoved");
				}
				java.nio.file.Files.writeString(file, old);
				Settings moved = Settings.reload();
				if (!moved.gpuBaking) {
					throw new AssertionError("A settings file from before the default changed kept GPU baking off");
				}
				if (!java.nio.file.Files.readString(file).contains("\"defaultsMoved\": 1")) {
					throw new AssertionError("Moving a settings file didn't save it");
				}
				moved.gpuBaking = false;
				moved.save();
				if (Settings.reload().gpuBaking) {
					throw new AssertionError("GPU baking turned off by the player came back on");
				}
				java.nio.file.Files.writeString(file, kept);
				Settings.reload();
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
	}

	private static void settings(ClientGameTestContext context, TestSingleplayerContext world) {
		gpuBakingMovesOnce(context);
		world.getServer().runCommand("time set noon");
		context.waitTicks(20);
		context.takeScreenshot("settings-01-default-minimap");

		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("settings-02-screen");
		// Real clicks: a checkbox changes its setting and the preview with it, a tab shows its
		// settings, and the Size slider sets the size from where it's clicked.
		click(context, "Compass");
		if (context.computeOnClient(mc -> Settings.get().compass)) {
			throw new AssertionError("Clicking Compass didn't switch it off");
		}
		context.takeScreenshot("settings-02-compass-off");
		click(context, "Compass");
		if (!context.computeOnClient(mc -> Settings.get().compass)) {
			throw new AssertionError("Clicking Compass again didn't switch it on");
		}
		// Shape switches the preview to a circle; switched off, only the circle and its lines dim.
		click(context, "Shape: ");
		if (context.computeOnClient(mc -> Settings.get().minimapShape) != Settings.Shape.ROUND) {
			throw new AssertionError("Clicking Shape didn't make the minimap round");
		}
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("settings-02-round");
		click(context, "Minimap", 0.05);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("settings-02-round-off");
		click(context, "Minimap", 0.05);
		click(context, "Shape: ");
		if (context.computeOnClient(mc -> Settings.get().minimapShape) != Settings.Shape.SQUARE) {
			throw new AssertionError("Clicking Shape again didn't make the minimap square");
		}
		clickAt(context, "Size: ", 1.0);
		int clickedSize = context.computeOnClient(mc -> Settings.get().minimapSize);
		if (clickedSize != Settings.MAX_SIZE) {
			throw new AssertionError("Clicking the right end of the Size slider set " + clickedSize + ", not " + Settings.MAX_SIZE);
		}
		// The faintest minimap: the preview's map and fog fade, and the frame is a border around the game behind it.
		clickAt(context, "Opacity: ", 0.0);
		int clickedOpacity = context.computeOnClient(mc -> Settings.get().minimapOpacity);
		if (clickedOpacity != Settings.MIN_OPACITY) {
			throw new AssertionError("Clicking the left end of the Opacity slider set " + clickedOpacity + ", not " + Settings.MIN_OPACITY);
		}
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("settings-02-opacity-" + clickedOpacity);
		// Round, the frame is a ring around the faded map, not a disc behind it.
		click(context, "Shape: ");
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("settings-02-opacity-" + clickedOpacity + "-round");
		click(context, "Shape: ");
		context.runOnClient(mc -> {
			Settings.get().minimapOpacity = Settings.MAX_OPACITY;
			Settings.get().save();
		});
		// The biggest minimap, switched off: the dimmed preview stops at the settings' edge.
		click(context, "Minimap", 0.05);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(3);
		context.takeScreenshot("settings-02-size-224-off");
		click(context, "Minimap", 0.05);
		clickAt(context, "Size: ", 0.3);
		context.takeScreenshot("settings-02-size-" + context.computeOnClient(mc -> Settings.get().minimapSize));
		context.runOnClient(mc -> {
			Settings.get().minimapSize = 96;
			Settings.get().save();
		});
		String[] tabs = {"Radar", "Overlays", "Waypoints", "Look"};
		String[] shows = {"Height range: 32 m", "Spawn spots: Off", "Death markers", "Quality: Fancy"};
		for (int i = 0; i < tabs.length; i++) {
			click(context, tabs[i]);
			String wanted = shows[i];
			if (!context.computeOnClient(mc -> widget(mc, wanted) != null)) {
				throw new AssertionError("The " + tabs[i] + " tab doesn't show " + wanted);
			}
			context.takeScreenshot("settings-02-tab-" + tabs[i].toLowerCase(java.util.Locale.ROOT));
		}
		context.setScreen(() -> null);

		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCorner = Settings.Corner.BOTTOM_LEFT;
			s.minimapSize = 176;
			s.minimapZoom = 1;
			s.save();
		});
		settle(context, 20);
		context.takeScreenshot("settings-03-bottom-left-large");
		String saved = context.computeOnClient(mc -> {
			try {
				return java.nio.file.Files.readString(mc.gameDirectory.toPath().resolve("diorama").resolve("config.json"));
			} catch (java.io.IOException e) {
				throw new AssertionError("config.json is missing", e);
			}
		});
		if (!saved.contains("\"BOTTOM_LEFT\"") || !saved.contains("\"minimapSize\": 176")) {
			throw new AssertionError("config.json doesn't have the new settings:\n" + saved);
		}

		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCorner = Settings.Corner.TOP_RIGHT;
			s.minimapSize = 96;
			s.minimapZoom = 3;
			s.save();
		});
		world.getServer().runCommand("effect give @a speed 60 0");
		world.getServer().runCommand("effect give @a weakness 60 0");
		context.waitTicks(20);
		context.takeScreenshot("settings-04-below-effects");
		world.getServer().runCommand("effect clear @a");

		// Hand-edited numbers out of range snap to valid ones, and the screen still opens.
		context.runOnClient(mc -> {
			java.nio.file.Path file = mc.gameDirectory.toPath().resolve("diorama").resolve("config.json");
			try {
				java.nio.file.Files.writeString(file, "{\"minimapZoom\": -1, \"radarRange\": -1, \"minimapOpacity\": 3}");
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			Settings s = Settings.reload();
			if (s.minimapZoom != 0 || s.radarRange != Settings.RANGES[0] || s.minimapOpacity != Settings.MIN_OPACITY) {
				throw new AssertionError("Bad numbers weren't fixed on load: zoom " + s.minimapZoom + ", range " + s.radarRange + ", opacity " + s.minimapOpacity);
			}
			s.minimapOpacity = Settings.MAX_OPACITY;
			s.minimapZoom = 3;
			s.radarRange = 32;
			s.save();
		});
		// Settings saved before Size was a slider keep their size, and odd sizes snap to the slider's steps.
		String[][] sizes = {{"\"SMALL\"", "96"}, {"\"MEDIUM\"", "128"}, {"\"LARGE\"", "176"}, {"100", "104"}, {"500", "224"}, {"null", "96"}};
		for (String[] size : sizes) {
			context.runOnClient(mc -> {
				java.nio.file.Path file = mc.gameDirectory.toPath().resolve("diorama").resolve("config.json");
				try {
					java.nio.file.Files.writeString(file, "{\"minimapSize\": " + size[0] + ", \"minimapCorner\": \"BOTTOM_LEFT\", \"compass\": false}");
				} catch (java.io.IOException e) {
					throw new AssertionError(e);
				}
				Settings s = Settings.reload();
				if (s.minimapSize != Integer.parseInt(size[1]) || s.minimapCorner != Settings.Corner.BOTTOM_LEFT || s.compass) {
					throw new AssertionError("A saved size of " + size[0] + " loaded as " + s.minimapSize + " (corner " + s.minimapCorner + ", compass " + s.compass + ")");
				}
				// Files from before the Opacity setting have none, and keep the solid map they had.
				if (s.minimapOpacity != Settings.MAX_OPACITY) {
					throw new AssertionError("A saved file with no opacity loaded as " + s.minimapOpacity + ", not " + Settings.MAX_OPACITY);
				}
			});
		}
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapSize = 96;
			s.minimapCorner = Settings.Corner.TOP_RIGHT;
			s.compass = true;
			s.save();
		});
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(3);
		context.setScreen(() -> null);

		// A small window: 1280x720 is a 427x240 GUI. No settings button may overlap another, and a
		// bottom-corner minimap must stay clear of the hotbar.
		context.getInput().resizeWindow(1280, 720);
		context.waitTicks(10);
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.takeScreenshot("settings-05-small-screen");
		// Every tab: no setting may overlap another or reach under the minimap preview.
		for (int tab = 0; tab < 5; tab++) {
			int index = tab;
			context.runOnClient(mc -> {
				((SettingsScreen) mc.gui.screen()).selectTab(index);
				java.util.List<net.minecraft.client.gui.components.AbstractWidget> widgets = new java.util.ArrayList<>();
				collectWidgets(mc.gui.screen(), widgets);
				int width = mc.gui.screen().width;
				int height = mc.gui.screen().height;
				// The preview's frame, at the default size in the top right.
				int preview = width - 6 - 128 - 2;
				for (var a : widgets) {
					if (a.getX() < 0 || a.getY() < 0 || a.getRight() > width || a.getBottom() > height) {
						throw new AssertionError(a.getMessage().getString() + " runs off the screen");
					}
					if (a.getRight() > preview && a.getY() < 6 + 128 + 2) {
						throw new AssertionError(a.getMessage().getString() + " reaches under the minimap preview");
					}
					for (var b : widgets) {
						if (a != b && a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()) {
							throw new AssertionError(a.getMessage().getString() + " overlaps " + b.getMessage().getString());
						}
					}
				}
			});
		}
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(0));
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCorner = Settings.Corner.BOTTOM_LEFT;
			s.minimapSize = 176;
			s.save();
		});
		context.waitTicks(10);
		context.takeScreenshot("settings-06-small-bottom-left");
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCorner = Settings.Corner.TOP_RIGHT;
			s.minimapSize = 96;
			s.save();
		});
		context.getInput().resizeWindow(1600, 900);
		context.waitTicks(10);

		// Rounded fog only reshapes the fog edge, so switching it keeps the baked map instead of
		// blanking it and baking every tile again.
		context.setScreen(MapScreen::new);
		lookAtPlayer(context, 1.0, TileKey.SURFACE);
		settle(context, 60);
		MapTile before = context.computeOnClient(mc -> playerTile());
		if (before == null) {
			throw new AssertionError("No baked tile under the player to check against");
		}
		context.runOnClient(mc -> {
			Settings.get().roundedFog = false;
			Settings.get().save();
		});
		context.waitTicks(5);
		context.takeScreenshot("settings-07-sharp-fog");
		boolean kept = context.computeOnClient(mc -> playerTile() == before);
		boolean applied = context.computeOnClient(mc -> !MapStyle.get().roundedFog());
		context.runOnClient(mc -> {
			Settings.get().roundedFog = true;
			Settings.get().save();
		});
		context.setScreen(() -> null);
		if (!kept) {
			throw new AssertionError("Switching rounded fog threw away the baked map");
		}
		if (!applied) {
			throw new AssertionError("Switching rounded fog didn't reach the map's fog");
		}
	}

	private static @Nullable MapTile playerTile() {
		LocalPlayer player = Minecraft.getInstance().player;
		int tu = (int) Math.floor(player.getX() / Projection.TILE_UNITS);
		int tv = (int) Math.floor(Projection.v(player.getY(), player.getZ()) / Projection.TILE_UNITS);
		return MapSession.get().map().peek(new TileKey(TileKey.SURFACE, tu, tv));
	}

	/**
	 * Waypoints in the world are part of the picture of the world: the inventory, opened with Home
	 * in view, the hotbar, and the chat box all cover them.
	 */
	private static void waypointsUnderTheGui(ClientGameTestContext context) {
		context.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(Minecraft.getInstance().player));
		context.waitTicks(5);
		context.takeScreenshot("waypoints-02b-under-inventory");
		context.setScreen(() -> null);

		// One pin standing on the hotbar, and one on chat lines, with the chat closed.
		Waypoint low = waypointOnScreen(context, "Low", Waypoint.PALETTE[3], 0.5, -8);
		context.runOnClient(mc -> {
			for (int i = 1; i <= 3; i++) {
				//? if >=26.1 {
				mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Chat line " + i + " over a waypoint"));
				//?} else {
				/*mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("Chat line " + i + " over a waypoint"), false);
				*///?}
			}
		});
		Waypoint chat = waypointOnScreen(context, "Chat", Waypoint.PALETTE[7], 60, -48);
		context.takeScreenshot("waypoints-02c-under-hotbar");
		//? if >=1.21.9 {
		context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("", false));
		//?} else {
		/*context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen(""));
		*///?}
		context.waitTicks(5);
		context.takeScreenshot("waypoints-02d-under-chat");
		context.setScreen(() -> null);
		// The totem animation, which the frame draws before the HUD before 26.3.
		Waypoint totem = waypointOnScreen(context, "Totem", Waypoint.PALETTE[1], 0.5, 0.55);
		//? if >=26.3 {
		context.runOnClient(mc -> mc.player.displayItemActivation(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)));
		//?} else {
		/*context.runOnClient(mc -> mc.gameRenderer.displayItemActivation(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)));
		*///?}
		context.waitTicks(8);
		context.takeScreenshot("waypoints-02e-under-totem");
		context.waitTicks(40);
		// F1 hides them with the rest of the HUD.
		//? if >=26.2 {
		context.runOnClient(mc -> mc.gui.hud.toggle());
		//?} else {
		/*context.runOnClient(mc -> mc.options.hideGui = true);
		*///?}
		context.waitTicks(2);
		context.takeScreenshot("waypoints-02f-hidden-hud");
		//? if >=26.2 {
		context.runOnClient(mc -> mc.gui.hud.toggle());
		//?} else {
		/*context.runOnClient(mc -> mc.options.hideGui = false);
		*///?}
		context.runOnClient(mc -> {
			WaypointStore.get().remove(low);
			WaypointStore.get().remove(chat);
			WaypointStore.get().remove(totem);
		});
	}

	/**
	 * Adds a waypoint 24 blocks ahead whose pin stands on the given GUI point with the player looking
	 * level. Below 1, x and y are fractions of the width and height; a negative y counts up from the
	 * bottom edge.
	 */
	private static Waypoint waypointOnScreen(ClientGameTestContext context, String name, int color, double x, double y) {
		Waypoint waypoint = context.computeOnClient(mc -> {
			mc.player.setXRot(0);
			mc.player.xRotO = 0;
			double width = mc.getWindow().getGuiScaledWidth();
			double height = mc.getWindow().getGuiScaledHeight();
			double guiX = x < 1 ? x * width : x;
			double guiY = y < 0 ? height + y : y < 1 ? y * height : y;
			net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition();
			net.minecraft.world.phys.Vec3 look = mc.player.getLookAngle();
			double distance = 24;
			// Blocks per GUI pixel at that distance; the field of view is the vertical one.
			double scale = distance * Math.tan(Math.toRadians(mc.options.fov().get() / 2.0)) / (height / 2);
			double right = (guiX - width / 2) * scale;
			double up = (height / 2 - guiY) * scale;
			// Screen right is look cross up; the pin stands on the middle of the block's top.
			double wx = eye.x + look.x * distance - look.z * right;
			double wz = eye.z + look.z * distance + look.x * right;
			Waypoint placed = new Waypoint(name, (int) Math.floor(wx), (int) Math.round(eye.y + up - 1.0), (int) Math.floor(wz),
				mc.level.dimension().identifier().toString(), color);
			WaypointStore.get().add(placed);
			return placed;
		});
		context.waitTicks(5);
		String missed = context.computeOnClient(mc -> {
			float width = mc.getWindow().getGuiScaledWidth();
			float height = mc.getWindow().getGuiScaledHeight();
			double guiX = x < 1 ? x * width : x;
			double guiY = y < 0 ? height + y : y < 1 ? y * height : y;
			float[] at = WorldProjection.project(waypoint.x + 0.5, waypoint.y + 1.0, waypoint.z + 0.5, width, height);
			if (at == null || Math.abs(at[0] - guiX) > 12 || Math.abs(at[1] - guiY) > 12) {
				return at == null ? "behind the camera" : at[0] + "," + at[1] + " instead of " + guiX + "," + guiY;
			}
			return null;
		});
		if (missed != null) {
			throw new AssertionError("The " + name + " waypoint isn't where the test put it: " + missed);
		}
		return waypoint;
	}

	/** Which scenarios to run: DIORAMA_TESTS=tour,radar,... (default: all but styles). */
	private static Set<String> selectedTests() {
		String env = System.getenv("DIORAMA_TESTS");
		if ("1".equals(System.getenv("DIORAMA_STYLES"))) {
			return Set.of("styles");
		}
		if (env == null || env.isBlank()) {
			//? if >=1.21.4 && fabric {
			return Set.of("tour", "radar", "xaero-icons", "model-faces", "waypoints", "icons", "xaero-import", "unnumbered-states", "failing-model", "fog-of-war", "settings", "overlays", "screens", "enlarge", "minimap-frames", "game-time", "map-mouse-keys", "image-export", "saved-chunks", "dimensions", "saved-map", "recreated-world", "tall-world", "sub-maps", "map-dimensions", "multiplayer", "game-time-server", "server-id", "tracked-players", "realms");
			//?} else {
			/*// On NeoForge and before 1.21.4 the tests run on src/gameshim, which can't start a dedicated server.
			return Set.of("tour", "radar", "xaero-icons", "model-faces", "waypoints", "icons", "xaero-import", "unnumbered-states", "failing-model", "fog-of-war", "settings", "overlays", "screens", "enlarge", "minimap-frames", "game-time", "map-mouse-keys", "image-export", "saved-chunks", "dimensions", "saved-map", "recreated-world", "tall-world", "sub-maps", "map-dimensions");
			*///?}
		}
		return Set.of(env.split(","));
	}

	private static void assertSeen(ClientGameTestContext context, int x, int y, int z, boolean expected, String what) {
		boolean seen = context.computeOnClient(mc -> MapSession.get().map().isSeen(x, y, z));
		if (seen != expected) {
			throw new AssertionError("Expected the " + what + " at " + x + "," + y + "," + z + (expected ? " to be seen" : " to stay hidden"));
		}
	}

	/** Opens the map on the test scene's cave slice. */
	private static void showScene(ClientGameTestContext context) {
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(115, Projection.v(-40, 110), 3.5, MapSession.caveLayerFor(-40));
			}
		});
		settle(context, 60);
	}

	/**
	 * Renders the same village under every look in {@link MapStyle#tour()}, close up and zoomed
	 * out, so they can be compared side by side.
	 */
	private static void styleTour(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos village = world.getServer().computeOnServer(server ->
			server.overworld().findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, 100, false));
		if (village == null) {
			throw new AssertionError("No village near spawn");
		}
		int surface = world.getServer().computeOnServer(server ->
			server.overworld().getChunk(village).getHeight(Heightmap.Types.MOTION_BLOCKING, village.getX() & 15, village.getZ() & 15));
		world.getServer().runCommand("tp @a " + village.getX() + " " + (surface + 1) + " " + village.getZ());
		context.waitTicks(200);
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 400, 2400);
		settle(context, 100);
		for (MapStyle style : MapStyle.tour()) {
			context.runOnClient(mc -> MapStyle.set(style));
			String name = style.name().toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
			context.setScreen(MapScreen::new);
			lookAtPlayer(context, 3.0, TileKey.SURFACE);
			settle(context, 60);
			context.takeScreenshot("style-" + name + "-near");
			lookAtPlayer(context, 0.8, TileKey.SURFACE);
			settle(context, 80);
			context.takeScreenshot("style-" + name + "-far");
			context.setScreen(() -> null);
		}
		context.runOnClient(mc -> MapStyle.set(Settings.get().style()));
	}

	private static void setQuality(ClientGameTestContext context, Settings.Quality quality) {
		context.runOnClient(mc -> {
			Settings.get().quality = quality;
			Settings.get().apply();
		});
	}

	private static void lookAtPlayer(ClientGameTestContext context, double zoom, int layer) {
		context.runOnClient(mc -> {
			LocalPlayer player = mc.player;
			if (mc.gui.screen() instanceof MapScreen screen && player != null) {
				screen.lookAt(player.getX(), Projection.v(player.getY(), player.getZ()), zoom, layer);
			}
		});
	}

	/** Every widget on a screen, inside containers like the tab bar too. */
	private static void collectWidgets(net.minecraft.client.gui.components.events.ContainerEventHandler parent, java.util.List<net.minecraft.client.gui.components.AbstractWidget> out) {
		for (var child : parent.children()) {
			if (child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container) {
				collectWidgets(container, out);
			} else if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget) {
				out.add(widget);
			}
		}
	}

	/** The open screen's widget whose label starts with this text. */
	private static net.minecraft.client.gui.components.@Nullable AbstractWidget widget(Minecraft mc, String label) {
		java.util.List<net.minecraft.client.gui.components.AbstractWidget> widgets = new java.util.ArrayList<>();
		collectWidgets(mc.gui.screen(), widgets);
		for (var widget : widgets) {
			if (widget.getMessage().getString().startsWith(label)) {
				return widget;
			}
		}
		return null;
	}

	/** Clicks the middle of the widget with this label, as a player would. */
	static void click(ClientGameTestContext context, String label) {
		clickAt(context, label, 0.5);
	}

	/** Clicks the checkbox with this label this far along it (a tab may have the same label). */
	private static void click(ClientGameTestContext context, String label, double along) {
		double[] at = context.computeOnClient(mc -> {
			java.util.List<net.minecraft.client.gui.components.AbstractWidget> widgets = new java.util.ArrayList<>();
			collectWidgets(mc.gui.screen(), widgets);
			for (var widget : widgets) {
				if (widget instanceof net.minecraft.client.gui.components.Checkbox && widget.getMessage().getString().equals(label)) {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(widget.getX() + along * widget.getWidth()) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
				}
			}
			throw new AssertionError("No checkbox labelled " + label);
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(2);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(5);
	}

	/** Clicks a widget this far along it, from 0 at its left edge to 1 at its right. */
	private static void clickAt(ClientGameTestContext context, String label, double along) {
		double[] at = context.computeOnClient(mc -> {
			var widget = widget(mc, label);
			if (widget == null) {
				throw new AssertionError("No widget labelled " + label);
			}
			double scale = mc.getWindow().getGuiScale();
			double x = widget.getX() + 1 + along * (widget.getWidth() - 2);
			return new double[] {x * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(2);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(5);
	}

	/** Waits until no tile has been baking for a while. */
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
