package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.Facing;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.compat.CreateTrains;
import dev.diorama.craft.FarTrains;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A Create train far from the player on the full map (opt-in: {@code create-far-train}, with
 * Create 6 installed). A train is put together at a station by Create's own station assembly on a
 * long straight track and left standing beside the player, who then goes far enough away that
 * the client drops its carriage. The map shows the parked picture and no marker; the train drives
 * off, the picture goes, and a marker follows it along the track, where the server says the train
 * is; it stops at the far end and shows its name under the mouse, on a map facing north and
 * south. With Create's train map overlay setting off there is no marker. Back beside the train, it
 * is drawn block for block again, with no marker on it. Everything Create is reached by
 * reflection, so the section builds without Create.
 */
final class CreateFarTrainTest {
	private static final String PREFIX = "create-far-train-";
	/** Half the track's length. */
	private static final int HALF = 120;
	/** How far east the player goes, well past the client's view of the train. */
	private static final int AWAY = 1200;
	/** The throttle the train drives at: about 7 blocks a second. */
	private static final double THROTTLE = 0.25;

	private CreateFarTrainTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		try {
			Class.forName("com.simibubi.create.compat.trainmap.TrainMapSyncClient");
		} catch (ClassNotFoundException e) {
			throw new AssertionError("DIORAMA_TESTS=create-far-train needs Create 6 installed");
		}
		Scene scene = build(context, world);
		int ox = scene.ox();
		int oz = scene.oz();
		int ground = scene.ground();
		int bed = scene.bed();
		int body = scene.body();
		List<BlockPos> east = scene.east();
		Object train = scene.train();
		UUID trainId = scene.trainId();
		String name = scene.name();

		// Beside the standing train, until the map has it block for block and it has stood long enough to stay.
		int floor = bed + 1;
		world.getServer().runCommand("tp @a " + ox + " " + floor + " " + (oz + 8) + " facing " + ox + " " + body + " " + oz);
		UUID craft = waitForCarriage(context, true);
		context.waitTicks(160);
		try {
			// Near: the map draws the train block for block, and Create's places for it are left alone.
			Vec3 standing = serverMiddle(world, train);
			openAt(context, standing, 3);
			waitFor(context, "Create's places for the train", () -> CreateTrains.known() && !CreateTrains.carriages(overworld()).isEmpty());
			context.waitTicks(20);
			check(context, () -> {
				List<CreateTrains.Carriage> carriages = CreateTrains.carriages(overworld());
				if (carriages.size() != 1 || !carriages.get(0).loaded() || !carriages.get(0).train().equals(trainId)) {
					return "Near the train, Create's carriages are " + carriages + ", not one loaded carriage of " + trainId;
				}
				return FarTrains.drawn().isEmpty() ? null : "A marker is drawn on the train the map draws block for block: " + FarTrains.drawn();
			});
			context.takeScreenshot(PREFIX + "1-near");
			context.setScreen(() -> null);

			// Far: the client drops the carriage, and the map keeps its picture where it stands.
			world.getServer().runCommand("tp @a " + (ox + AWAY) + " " + (ground + 40) + " " + oz);
			waitForCarriage(context, false);
			if (!context.computeOnClient(mc -> MapSession.get().crafts().has(craft))) {
				throw new AssertionError("The map didn't keep the parked train");
			}
			openAt(context, standing, 3);
			waitForTile(context, standing);
			waitFor(context, "Create's places for the far train", () -> CreateTrains.known() && !CreateTrains.carriages(overworld()).isEmpty());
			context.waitTicks(20);
			check(context, () -> {
				List<CreateTrains.Carriage> carriages = CreateTrains.carriages(overworld());
				if (carriages.size() != 1 || carriages.get(0).loaded()) {
					return "Far from the train, Create's carriages are " + carriages + ", not one far carriage";
				}
				if (!MapSession.get().crafts().has(craft)) {
					return "The parked train went while it still stands there";
				}
				return FarTrains.drawn().isEmpty() ? null : "A marker is drawn on the parked train: " + FarTrains.drawn();
			});
			context.takeScreenshot(PREFIX + "2-far-parked");

			// It drives off east: the parked picture goes, and a marker follows the train.
			drive(world, train, east);
			Vec3 middle = new Vec3(ox + HALF / 2.0, standing.y, standing.z);
			look(context, middle, 2);
			waitFor(context, "the parked train to go", () -> !MapSession.get().crafts().has(craft) && !FarTrains.drawn().isEmpty());
			context.waitTicks(10);
			Vec3 first = tracked(context, world, train, "3-far-moving");
			context.waitTicks(40);
			Vec3 second = tracked(context, world, train, "4-far-moving");
			if (second.x - first.x < 5) {
				throw new AssertionError("The marker didn't move east: " + first + " then " + second);
			}

			// Stopped at the east station: its name under the mouse, on the map facing north and then south.
			waitFor(context, "the train to stop at the east station", () -> true, () -> world.getServer().computeOnServer(server ->
				Math.abs(((Number) field(train, "speed")).doubleValue()) < 0.01 && field(field(train, "navigation"), "destination") == null));
			context.waitTicks(20);
			Vec3 stopped = serverMiddle(world, train);
			for (Facing facing : new Facing[] {Facing.NORTH, Facing.SOUTH}) {
				context.setScreen(() -> null);
				FacingTest.turnTo(context, facing);
				openAt(context, stopped, 4);
				waitForTile(context, stopped);
				waitFor(context, "the marker facing " + facing.id(), () -> FarTrains.drawn().size() == 1);
				Vec3 shown = tracked(context, world, train, "5-far-stopped-" + facing.id(), true);
				hover(context, name, facing);
				context.takeScreenshot(PREFIX + "6-far-stopped-hover-" + facing.id());
				context.getInput().setCursorPos(800, 895);
				Diorama.LOGGER.info("Create far train test: facing {}, marker at {}", facing.id(), shown);
			}
			context.setScreen(() -> null);
			FacingTest.turnTo(context, Facing.NORTH);
			openAt(context, stopped, 4);
			waitForTile(context, stopped);
			waitFor(context, "the marker", () -> FarTrains.drawn().size() == 1);
			// Zoomed far out the marker keeps a few pixels, so the train still shows.
			look(context, stopped, 0.25);
			context.waitTicks(40);
			check(context, () -> FarTrains.drawn().size() == 1 ? null : "Zoomed far out, the markers drawn are " + FarTrains.drawn());
			context.takeScreenshot(PREFIX + "6-far-stopped-zoomed-out");
			look(context, stopped, 4);
			context.waitTicks(10);

			// Create's own setting for its train map overlay hides the markers too.
			overlay(false);
			waitFor(context, "the marker to go with Create's overlay off", () -> FarTrains.drawn().isEmpty());
			context.waitTicks(10);
			check(context, () -> FarTrains.drawn().isEmpty() ? null : "A marker is drawn with Create's overlay off");
			context.takeScreenshot(PREFIX + "7-overlay-off");
			overlay(true);
			waitFor(context, "the marker back with Create's overlay on", () -> FarTrains.drawn().size() == 1);
			context.setScreen(() -> null);

			// Back beside the train: block for block again, with no marker on it.
			world.getServer().runCommand("tp @a " + Math.round(stopped.x) + " " + floor + " " + (oz + 8) + " facing " + stopped.x + " " + body + " " + oz);
			waitForCarriage(context, true);
			context.waitTicks(40);
			openAt(context, serverMiddle(world, train), 3);
			waitForTile(context, stopped);
			waitFor(context, "Create to see the loaded carriage", () -> CreateTrains.carriages(overworld()).size() == 1 && CreateTrains.carriages(overworld()).get(0).loaded());
			context.waitTicks(20);
			check(context, () -> {
				if (!MapSession.get().crafts().has(craft)) {
					return "The map doesn't draw the train block for block back beside it";
				}
				return FarTrains.drawn().isEmpty() ? null : "A marker is drawn on the train back beside it: " + FarTrains.drawn();
			});
			context.takeScreenshot(PREFIX + "8-back-near");
			context.setScreen(() -> null);
		} finally {
			overlay(true);
			context.setScreen(() -> null);
			FacingTest.turnTo(context, Facing.NORTH);
		}
	}

	/** The track, its stations and the train, as the checks find them. */
	private record Scene(int ox, int oz, int ground, int bed, int body, List<BlockPos> east, Object train, UUID trainId, String name) {
	}

	/** Lays the track and its stations and puts the train together at the middle one, beside the player. */
	private static Scene build(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("time set noon");
		context.waitFor(mc -> mc.player.onGround(), 600);
		FacingTest.turnTo(context, Facing.NORTH);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition()).offset(0, 0, 30);
		int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()));
		int ox = at.getX();
		int oz = at.getZ();
		int bed = ground + 2;
		// A raised stone bed under a straight east-west track, clear air above it.
		clear(world, ox - HALF - 4, bed, oz - 8, ox + HALF + 4, bed + 14, oz + 10);
		world.getServer().runCommand("fill " + (ox - HALF - 4) + " " + bed + " " + (oz - 8) + " " + (ox + HALF + 4) + " " + bed + " " + (oz + 10) + " smooth_stone");
		world.getServer().runCommand("fill " + (ox - HALF - 4) + " " + (bed + 1) + " " + (oz - 1) + " " + (ox + HALF + 4) + " " + (bed + 1) + " " + (oz + 1) + " gravel");
		int ty = bed + 2;
		for (int x = ox - HALF; x <= ox + HALF; x++) {
			world.getServer().runCommand("setblock " + x + " " + ty + " " + oz + " create:track[shape=xo]");
		}
		context.waitTicks(40);
		// The station the train is put together at, in the middle, and near the east end one facing
		// each way: the train drives to whichever it can stop at.
		BlockPos home = new BlockPos(ox, ty, oz + 2);
		station(world, home, Direction.AxisDirection.POSITIVE);
		List<BlockPos> east = List.of(new BlockPos(ox + HALF - 10, ty, oz + 2), new BlockPos(ox + HALF - 20, ty, oz + 2));
		station(world, east.get(0), Direction.AxisDirection.POSITIVE);
		station(world, east.get(1), Direction.AxisDirection.NEGATIVE);
		context.waitTicks(40);
		// The carriage: a small bogey just east of the station, a wool body glued on top, controls facing east.
		int bogeyX = ox + 1;
		world.getServer().runCommand("setblock " + bogeyX + " " + (ty + 1) + " " + oz + " create:small_bogey[axis=x]");
		int body = ty + 2;
		world.getServer().runCommand("fill " + (bogeyX - 2) + " " + body + " " + (oz - 1) + " " + (bogeyX + 3) + " " + body + " " + (oz + 1) + " white_wool");
		world.getServer().runCommand("fill " + (bogeyX + 3) + " " + body + " " + (oz - 1) + " " + (bogeyX + 3) + " " + body + " " + (oz + 1) + " red_wool");
		world.getServer().runCommand("fill " + (bogeyX - 2) + " " + body + " " + (oz - 1) + " " + (bogeyX - 2) + " " + body + " " + (oz + 1) + " blue_wool");
		world.getServer().runCommand("fill " + (bogeyX - 2) + " " + (body + 1) + " " + (oz - 1) + " " + (bogeyX + 1) + " " + (body + 2) + " " + (oz - 1) + " orange_wool");
		world.getServer().runCommand("fill " + (bogeyX - 2) + " " + (body + 1) + " " + (oz + 1) + " " + (bogeyX + 1) + " " + (body + 2) + " " + (oz + 1) + " orange_wool");
		world.getServer().runCommand("fill " + (bogeyX - 2) + " " + (body + 3) + " " + (oz - 1) + " " + (bogeyX + 1) + " " + (body + 3) + " " + (oz + 1) + " black_wool");
		world.getServer().runCommand("setblock " + (bogeyX + 2) + " " + (body + 1) + " " + oz + " create:controls[facing=east]");
		world.getServer().runOnServer(server -> glue(server.overworld(), new AABB(bogeyX - 2, ty + 1, oz - 1, bogeyX + 4, body + 4, oz + 2)));
		context.waitTicks(20);
		UUID owner = context.computeOnClient(mc -> mc.player.getUUID());
		world.getServer().runOnServer(server -> handle(server.overworld().getBlockEntity(home), "enterAssemblyMode",
			MethodType.methodType(boolean.class, net.minecraft.server.level.ServerPlayer.class), (Object) null));
		context.waitTicks(40);
		String failed = world.getServer().computeOnServer(server -> {
			Object be = server.overworld().getBlockEntity(home);
			handle(be, "assemble", MethodType.methodType(void.class, UUID.class), owner);
			Object exception = field(be, "lastException");
			return exception == null ? null : String.valueOf(((Throwable) exception).getMessage());
		});
		if (failed != null) {
			throw new AssertionError("Create's station didn't assemble the train: " + failed);
		}
		Object train = world.getServer().computeOnServer(server -> {
			Map<?, ?> trains = (Map<?, ?>) field(field(null, type("com.simibubi.create.Create"), "RAILWAYS"), "trains");
			return trains.isEmpty() ? null : trains.values().iterator().next();
		});
		if (train == null) {
			throw new AssertionError("No train after assembly");
		}
		// Out of assembly mode, as the station's screen does once a train is built: a station still assembling blocks the way.
		world.getServer().runOnServer(server -> handle(server.overworld().getBlockEntity(home), "exitAssemblyMode", MethodType.methodType(boolean.class)));
		world.getServer().runCommand("kill @e[type=item]");
		UUID trainId = world.getServer().computeOnServer(server -> (UUID) field(train, "id"));
		String name = world.getServer().computeOnServer(server -> ((net.minecraft.network.chat.Component) field(train, "name")).getString());
		Diorama.LOGGER.info("Create far train test: train {} \"{}\" assembled at {}", trainId, name, home);
		return new Scene(ox, oz, ground, bed, body, east, train, trainId, name);
	}

	/**
	 * The same train on a real dedicated server (opt-in: {@code create-far-train-server}, with
	 * Create on both sides: {@code --mods create --env DIORAMA_REAL_SERVER=1}). Nothing but a player
	 * at a station's screen can put a train together there, so the train is built in a singleplayer
	 * world as above and the server is started on a copy of that world, whose spawn is set far from
	 * the train. The player joins where the server never sends them the train: the marker has to
	 * stand where the train was left, from what Create's server sends alone. Beside the train it is
	 * drawn block for block; far away again, and after leaving and joining again, the map keeps
	 * that picture, made of the same blocks (run it with {@code --client-mods} naming a mod with
	 * blocks of its own to have NeoForge or Forge number blocks another way once the server is left, #420).
	 */
	static void server(ClientGameTestContext context) {
		try {
			Class.forName("com.simibubi.create.compat.trainmap.TrainMapSyncClient");
		} catch (ClassNotFoundException e) {
			throw new AssertionError("DIORAMA_TESTS=create-far-train-server needs Create 6 installed");
		}
		String node = System.getenv("DIORAMA_NODE");
		if (System.getenv("DIORAMA_REAL_SERVER") == null || node == null) {
			throw new AssertionError("create-far-train-server needs a real server: run it with --mods create --env DIORAMA_REAL_SERVER=1");
		}
		int[] place = new int[5];
		UUID[] trainId = {null};
		String[] name = {null};
		Vec3[] standing = {null};
		TestWorldSave save;
		try (TestSingleplayerContext world = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> {
				s.setName("Diorama Far Train");
				s.setSeed("diorama");
				s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			})
			.create()) {
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 100, 2400);
			Scene scene = build(context, world);
			standing[0] = serverMiddle(world, scene.train());
			trainId[0] = scene.trainId();
			name[0] = scene.name();
			place[0] = scene.ox();
			place[1] = scene.oz();
			place[2] = scene.ground();
			place[3] = scene.bed();
			place[4] = scene.body();
			// Where a player new to the server lands: far from the train.
			world.getServer().runCommand("setworldspawn " + (scene.ox() + AWAY) + " " + (scene.ground() + 40) + " " + scene.oz());
			world.getServer().runCommand("save-all flush");
			context.waitTicks(40);
			save = world.getWorldSave();
		}
		int ox = place[0];
		int oz = place[1];
		int ground = place[2];
		int floor = place[3] + 1;
		int body = place[4];
		Path serverWorld = Path.of(System.getProperty("user.home"), "real-servers", node, "world");
		copyWorld(save.getSaveDirectory(), serverWorld);
		Facing savedFacing = context.computeOnClient(mc -> Projection.facing());
		try (RealServer server = RealServer.start(false)) {
			server.command("time set noon");
			server.join(context);
			String me = context.computeOnClient(mc -> mc.player.getScoreboardName());
			context.waitFor(mc -> MapSession.get() != null, 2400);
			int joinedAt = context.computeOnClient(mc -> mc.player.blockPosition().getX());
			if (Math.abs(joinedAt - ox) < AWAY / 2) {
				throw new AssertionError("The player joined at x " + joinedAt + ", only " + Math.abs(joinedAt - ox) + " blocks from the train at x " + ox);
			}
			FacingTest.turnTo(context, Facing.NORTH);

			// Never near it on this server: the marker stands where the train was left.
			openAt(context, standing[0], 4);
			waitFor(context, "Create's server to say where the train is", () -> CreateTrains.known() && CreateTrains.carriages(overworld()).size() == 1 && FarTrains.drawn().size() == 1);
			context.waitTicks(20);
			check(context, () -> {
				CreateTrains.Carriage carriage = CreateTrains.carriages(overworld()).get(0);
				if (carriage.loaded() || !carriage.train().equals(trainId[0])) {
					return "Far from the train, Create's carriage is " + carriage + ", not a far carriage of " + trainId[0];
				}
				Vec3 marker = FarTrains.drawn().get(0).carriage().middle();
				return marker.distanceTo(standing[0]) <= 1 ? null : "The marker is at " + marker + ", the train was left at " + standing[0];
			});
			context.takeScreenshot(PREFIX + "server-1-far-never-seen");
			hover(context, name[0], Facing.NORTH);
			context.takeScreenshot(PREFIX + "server-2-far-hover");
			context.getInput().setCursorPos(800, 895);
			context.setScreen(() -> null);

			// Beside it: block for block, no marker.
			server.commandAndWait("tp " + me + " " + ox + " " + floor + " " + (oz + 8), "Teleported " + me, 120);
			UUID craft = waitForCarriage(context, true);
			context.waitTicks(160);
			openAt(context, standing[0], 3);
			waitForTile(context, standing[0]);
			waitFor(context, "Create to see the loaded carriage", () -> CreateTrains.carriages(overworld()).size() == 1 && CreateTrains.carriages(overworld()).get(0).loaded());
			context.waitTicks(20);
			check(context, () -> FarTrains.drawn().isEmpty() ? null : "A marker is drawn on the train the map draws block for block: " + FarTrains.drawn());
			context.takeScreenshot(PREFIX + "server-3-near");
			String whole = AeronauticsServerTest.made(context, craft, PREFIX + "server-3-near");
			if (whole == null) {
				throw new AssertionError("Beside the train, the map draws no blocks for it");
			}
			// One of Create's own blocks in it, and its number while this server is joined.
			net.minecraft.world.level.block.state.BlockState part = context.computeOnClient(mc -> {
				dev.diorama.bake.CraftBlocks blocks = MapSession.get().crafts().shown(craft).blocks();
				for (int cell = 0; cell < blocks.volume(); cell++) {
					net.minecraft.world.level.block.state.BlockState state = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(blocks.state(cell));
					if (blocks.state(cell) != 0 && net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("create")) {
						return state;
					}
				}
				throw new AssertionError("The map's train has none of Create's blocks: " + whole);
			});
			int joined = context.computeOnClient(mc -> net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(part));
			context.setScreen(() -> null);

			// Far again: the picture stays where the train stands, and no marker goes on it.
			server.commandAndWait("tp " + me + " " + (ox + AWAY) + " " + (ground + 40) + " " + oz, "Teleported " + me, 120);
			waitForCarriage(context, false);
			List<String> wrong = new ArrayList<>();
			parked(context, craft, whole, standing[0], "server-4-far-parked", "far from the train again", wrong);

			RealServer.leave(context);
			int left = context.computeOnClient(mc -> net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(part));
			Diorama.LOGGER.info("Create far train test: a Create block's number was {} on the server and is {} after leaving{}", joined, left,
				joined == left ? " (the same: this run doesn't test #420's renumbering)" : "");
			server.join(context);
			context.waitFor(mc -> MapSession.get() != null, 2400);
			context.waitTicks(200);
			parked(context, craft, whole, standing[0], "server-5-rejoined-far", "after joining again far from the train", wrong);
			RealServer.leave(context);
			if (!wrong.isEmpty()) {
				throw new AssertionError("The train on a dedicated server is wrong:\n" + String.join("\n", wrong));
			}
		} finally {
			context.setScreen(() -> null);
			FacingTest.turnTo(context, savedFacing);
			// The next real-server test makes its own world.
			deleteTree(serverWorld);
		}
	}

	/** Far from a train the map has a picture of: the picture is there, made of {@code whole}, with no marker on it. */
	private static void parked(ClientGameTestContext context, UUID craft, String whole, Vec3 standing, String shot, String when, List<String> wrong) {
		openAt(context, standing, 3);
		waitForTile(context, standing);
		waitFor(context, "Create's places for the far train", () -> CreateTrains.known() && !CreateTrains.carriages(overworld()).isEmpty());
		context.waitTicks(40);
		context.takeScreenshot(PREFIX + shot);
		String now = AeronauticsServerTest.made(context, craft, PREFIX + shot);
		if (!whole.equals(now)) {
			wrong.add(when + " the map draws the train from " + now + "; beside it, it was " + whole);
		}
		List<FarTrains.Drawn> markers = context.computeOnClient(mc -> FarTrains.drawn());
		if (!markers.isEmpty()) {
			wrong.add(when + " a marker is drawn on the parked train: " + markers);
		}
		context.setScreen(() -> null);
	}

	private static void copyWorld(Path from, Path to) {
		deleteTree(to);
		try (java.util.stream.Stream<Path> files = Files.walk(from)) {
			for (Path file : files.toList()) {
				Path copy = to.resolve(from.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(copy);
				// Not the player's own file: the server would put them back beside the train.
				} else if (!file.getFileName().toString().equals("session.lock") && !from.relativize(file).toString().startsWith("playerdata")) {
					Files.copy(file, copy);
				}
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void deleteTree(Path folder) {
		if (!Files.exists(folder)) {
			return;
		}
		try (java.util.stream.Stream<Path> files = Files.walk(folder)) {
			for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static String overworld() {
		return "minecraft:overworld";
	}

	/**
	 * Where the server has the train's carriage, against where the marker is: the places Create
	 * sends lag by up to about 10 ticks, at about a third of a block a tick here. The screenshot
	 * shows what the map draws there.
	 */
	private static Vec3 tracked(ClientGameTestContext context, TestSingleplayerContext world, Object train, String shot) {
		return tracked(context, world, train, shot, false);
	}

	private static Vec3 tracked(ClientGameTestContext context, TestSingleplayerContext world, Object train, String shot, boolean still) {
		Vec3 server = serverMiddle(world, train);
		double[] seen = context.computeOnClient(mc -> {
			List<FarTrains.Drawn> drawn = FarTrains.drawn();
			if (drawn.size() != 1) {
				throw new AssertionError("Expected one marker, not " + drawn);
			}
			Vec3 m = drawn.get(0).carriage().middle();
			return new double[] {m.x, m.y, m.z};
		});
		Vec3 marker = new Vec3(seen[0], seen[1], seen[2]);
		double off = marker.distanceTo(server);
		Diorama.LOGGER.info("Create far train test: {}: server {}, marker {}, {} apart", shot, server, marker, off);
		if (off > (still ? 1 : 6)) {
			throw new AssertionError(shot + ": the marker is at " + marker + ", the server has the train at " + server);
		}
		context.takeScreenshot(PREFIX + shot);
		return marker;
	}

	/** Puts the mouse on the marker and reads the map's tooltip. */
	private static void hover(ClientGameTestContext context, String name, Facing facing) {
		double scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		float[] spot = context.computeOnClient(mc -> {
			FarTrains.Drawn d = FarTrains.drawn().get(0);
			return new float[] {d.x(), d.y()};
		});
		context.getInput().setCursorPos(spot[0] * scale, (spot[1] + 1) * scale);
		context.waitTicks(5);
		List<String> tooltip = context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen screen ? screen.tooltipText() : List.of());
		if (!tooltip.equals(List.of(name))) {
			throw new AssertionError("Facing " + facing.id() + ", the tooltip over the marker is " + tooltip + ", not the train's name " + name);
		}
	}

	private static void overlay(boolean shown) {
		try {
			Object config = Class.forName("com.simibubi.create.infrastructure.config.AllConfigs").getMethod("client").invoke(null);
			call(field(config, "showTrainMapOverlay"), "set", shown);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** Waits until the client has the train's carriage entity and the map has it, or until it has neither; returns its id. */
	private static UUID waitForCarriage(ClientGameTestContext context, boolean present) {
		UUID[] id = {null};
		waitFor(context, present ? "the carriage on the client" : "the client to drop the carriage", () -> {
			id[0] = null;
			for (Entity entity : net.minecraft.client.Minecraft.getInstance().level.entitiesForRendering()) {
				if (entity.getClass().getName().equals("com.simibubi.create.content.trains.entity.CarriageContraptionEntity") && !entity.isRemoved()) {
					id[0] = CreateTrains.craftId(entity);
				}
			}
			return present ? id[0] != null && MapSession.get().crafts().has(id[0]) : id[0] == null;
		});
		return id[0];
	}

	private interface Check {
		boolean ok() throws Exception;
	}

	private interface Problem {
		String find() throws Exception;
	}

	private static void waitFor(ClientGameTestContext context, String what, Check client) {
		waitFor(context, what, client, () -> true);
	}

	/** Up to a minute for {@code client} (on the client thread) and {@code server} (on the test thread) to hold. */
	private static void waitFor(ClientGameTestContext context, String what, Check client, Check server) {
		for (int i = 0; i < 1200; i++) {
			boolean ok = context.computeOnClient(mc -> {
				try {
					return client.ok();
				} catch (Exception e) {
					throw new AssertionError(e);
				}
			});
			try {
				if (ok && server.ok()) {
					return;
				}
			} catch (Exception e) {
				throw new AssertionError(e);
			}
			context.waitTick();
		}
		throw new AssertionError("Timed out waiting for " + what);
	}

	private static void check(ClientGameTestContext context, Problem problem) {
		String found = context.computeOnClient(mc -> {
			try {
				return problem.find();
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		if (found != null) {
			throw new AssertionError(found);
		}
	}

	private static void openAt(ClientGameTestContext context, Vec3 at, double zoom) {
		context.setScreen(MapScreen::new);
		// The cursor on the bottom bar, where the map shows no tooltip.
		context.getInput().setCursorPos(800, 895);
		look(context, at, zoom);
		context.waitTicks(10);
	}

	private static void look(ClientGameTestContext context, Vec3 at, double zoom) {
		context.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MapScreen screen) {
				screen.lookAt(Projection.u(at.x, at.z), Projection.v(at.x, at.y, at.z), zoom, TileKey.SURFACE);
			}
		});
	}

	private static void waitForTile(ClientGameTestContext context, Vec3 at) {
		double u = context.computeOnClient(mc -> Projection.u(at.x, at.z));
		double v = context.computeOnClient(mc -> Projection.v(at.x, at.y, at.z));
		TileKey tile = new TileKey(TileKey.SURFACE, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
		for (int i = 0; i < 45; i++) {
			boolean ready = context.computeOnClient(mc -> MapSession.get().map().savedMap().read(tile) != null && MapSession.get().map().bakingCount() == 0);
			if (ready) {
				return;
			}
			context.waitTicks(20);
		}
		Diorama.LOGGER.warn("Create far train test: tile {} not ready", tile);
	}

	/** The middle of the train's carriage's outer wheels, where the server has them. */
	private static Vec3 serverMiddle(TestSingleplayerContext world, Object train) {
		return world.getServer().computeOnServer(server -> {
			Object graph = field(train, "graph");
			Object carriage = ((List<?>) field(train, "carriages")).get(0);
			Vec3 a = (Vec3) call(call(carriage, "getLeadingPoint"), "getPosition", graph);
			Vec3 b = (Vec3) call(call(carriage, "getTrailingPoint"), "getPosition", graph);
			return a.add(b).scale(0.5);
		});
	}

	/** Sends the train to the first of the stations it has a path to, driven as by hand: no conductor sits in it, and a paused schedule skips that check. */
	private static void drive(TestSingleplayerContext world, Object train, List<BlockPos> to) {
		String result = world.getServer().computeOnServer(server -> {
			Object navigation = field(train, "navigation");
			set(field(train, "runtime"), "paused", true);
			set(train, "throttle", THROTTLE);
			for (BlockPos pos : to) {
				Object station = call(field(server.overworld().getBlockEntity(pos), "edgePoint"), "getEdgePoint");
				Object path = station == null ? null : call(navigation, "findPathTo", station, Double.MAX_VALUE);
				if (path != null) {
					call(navigation, "startNavigation", path);
					Diorama.LOGGER.info("Create far train test: driving to the station at {}", pos);
					return null;
				}
			}
			return "no path to " + to;
		});
		if (result != null) {
			throw new AssertionError("Couldn't send the train: " + result);
		}
	}

	private static void station(TestSingleplayerContext world, BlockPos pos, Direction.AxisDirection direction) {
		world.getServer().runCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " create:track_station");
		world.getServer().runOnServer(server -> {
			Object target = field(server.overworld().getBlockEntity(pos), "edgePoint");
			set(target, "targetTrack", new BlockPos(0, 0, -2));
			set(target, "targetDirection", direction);
		});
	}

	private static void glue(ServerLevel level, AABB box) {
		try {
			Constructor<?> make = Class.forName("com.simibubi.create.content.contraptions.glue.SuperGlueEntity").getConstructor(Level.class, AABB.class);
			level.addFreshEntity((Entity) make.newInstance(level, box));
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static void clear(TestSingleplayerContext world, int x0, int y0, int z0, int x1, int y1, int z1) {
		for (int x = x0; x <= x1; x += 16) {
			world.getServer().runCommand("fill " + x + " " + y0 + " " + z0 + " " + Math.min(x + 15, x1) + " " + y1 + " " + z1 + " air");
		}
	}

	// ---- Create by reflection ------------------------------------------------------------------

	private static Object field(Object owner, String name) {
		return field(owner, owner.getClass(), name);
	}

	private static Object field(Object owner, Class<?> type, String name) {
		try {
			return find(type, name).get(owner);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static void set(Object owner, String name, Object value) {
		try {
			find(owner.getClass(), name).set(owner, value);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static Class<?> type(String name) {
		try {
			return Class.forName(name);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	private static Field find(Class<?> type, String name) throws NoSuchFieldException {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			try {
				Field f = c.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException e) {
				// Up a class.
			}
		}
		throw new NoSuchFieldException(type.getName() + "." + name);
	}

	/**
	 * A public method by its exact type, for classes whose method list can't be read: the station's
	 * names ComputerCraft's classes, which aren't installed.
	 */
	private static Object handle(Object owner, String name, MethodType type, Object... args) {
		try {
			Object[] all = new Object[args.length + 1];
			all[0] = owner;
			System.arraycopy(args, 0, all, 1, args.length);
			return MethodHandles.publicLookup().findVirtual(owner.getClass(), name, type).invokeWithArguments(all);
		} catch (Throwable e) {
			throw new RuntimeException(owner.getClass().getName() + "." + name, e);
		}
	}

	private static Object call(Object owner, String name, Object... args) {
		for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
			for (Method m : c.getDeclaredMethods()) {
				if (m.getName().equals(name) && m.getParameterCount() == args.length && fits(m.getParameterTypes(), args)) {
					m.setAccessible(true);
					try {
						return m.invoke(owner, args);
					} catch (ReflectiveOperationException e) {
						throw new RuntimeException(owner.getClass().getName() + "." + name, e);
					}
				}
			}
		}
		throw new RuntimeException(new NoSuchMethodException(owner.getClass().getName() + "." + name + "/" + args.length));
	}

	private static boolean fits(Class<?>[] types, Object[] args) {
		for (int i = 0; i < types.length; i++) {
			if (args[i] == null) {
				if (types[i].isPrimitive()) {
					return false;
				}
				continue;
			}
			Class<?> t = types[i];
			if (t == double.class) {
				t = Double.class;
			} else if (t == boolean.class) {
				t = Boolean.class;
			} else if (t == int.class) {
				t = Integer.class;
			} else if (t == float.class) {
				t = Float.class;
			}
			if (!t.isInstance(args[i])) {
				return false;
			}
		}
		return true;
	}
}
