package dev.diorama.test;

import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapViewport;
import dev.diorama.render.Radar;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.Waypoints;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//? if >=1.21.4 && fabric {
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
//?}
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.entity.Entity;

/**
 * Watching through another player or a mob (#239): the server moves the camera and leaves the
 * client's own player where it started, so the map has to follow the camera.
 */
final class SpectateTest {
	private static final int STEPS = 60;
	private static final int STRIDE = 4;

	private SpectateTest() {
	}

	/** A mob in singleplayer, the one case every loader and version can run. */
	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		String self = context.computeOnClient(mc -> mc.player.getName().getString());
		world.getServer().runCommand("execute at " + self + " run summon pig ~4 ~1 ~ {NoAI:1b,NoGravity:1b,Silent:1b,Tags:[\"followed\"]}");
		context.waitTicks(10);
		follow(context, world.getServer()::runCommand, "@e[tag=followed,limit=1]", self, "spectate-mob");
		world.getServer().runCommand("gamemode creative " + self);
	}

	/** The report: another player on a server, walking off while this client watches through them. */
	//? if >=1.21.4 && fabric {
	static void onServer(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("diorama-spectate"))
			.createServer(DioramaClientTest.serverProperties())) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				server.runCommand("time set noon");
				server.runCommand("weather clear");
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
				String self = context.computeOnClient(mc -> mc.player.getName().getString());
				server.runOnServer(SpectateTest::joinBob);
				context.waitFor(mc -> mc.level.players().size() == 2, 400);
				// A new player's spawn is for the connection handler this one skips, so bring him over.
				server.runCommand("execute at " + self + " run tp Bob ~4 ~ ~");
				context.waitTicks(10);
				follow(context, server::runCommand, "Bob", self, "spectate-player");
			}
		}
	}

	/** A second player as the server sees one, on a connection nobody reads. */
	private static void joinBob(net.minecraft.server.MinecraftServer server) {
		GameProfile profile = new GameProfile(UUID.fromString("faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b"), "Bob");
		net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
		new io.netty.channel.embedded.EmbeddedChannel(connection);
		net.minecraft.server.level.ServerPlayer bob = new net.minecraft.server.level.ServerPlayer(server, server.overworld(), profile,
			net.minecraft.server.level.ClientInformation.createDefault());
		server.getPlayerList().placeNewPlayer(connection, bob, net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
	}
	//?}

	private static void follow(ClientGameTestContext context, Consumer<String> command, String target, String self, String shots) {
		boolean coordinates = context.computeOnClient(mc -> Settings.get().coordinates);
		int radarRange = context.computeOnClient(mc -> Settings.get().radarRange);
		context.runOnClient(mc -> {
			Settings.get().coordinates = true;
			Settings.get().radarRange = 128;
		});
		List<String> wrong = new ArrayList<>();
		command.accept("gamemode spectator " + self);
		command.accept("spectate " + target + " " + self);
		context.waitFor(mc -> mc.getCameraEntity() != null && mc.getCameraEntity() != mc.player, 200);
		context.waitTicks(20);
		context.takeScreenshot(shots + "-01-started");

		// Walks east along the ground, the camera on board.
		int[] start = context.computeOnClient(mc -> new int[] {mc.getCameraEntity().getBlockX(), mc.getCameraEntity().getBlockZ()});
		boolean frames = "1".equals(System.getenv("DIORAMA_GIF"));
		for (int step = 1; step <= STEPS; step++) {
			command.accept("execute positioned " + (start[0] + step * STRIDE) + " 0 " + start[1] + " positioned over world_surface run tp " + target + " ~ ~ ~");
			context.waitTicks(2);
			if (frames) {
				context.takeScreenshot(shots + "-walk-" + String.format("%02d", step));
			}
		}
		int end = start[0] + STEPS * STRIDE;
		context.waitFor(mc -> Math.abs(mc.getCameraEntity().getX() - end) < 2, 400);
		context.waitTicks(40);
		context.takeScreenshot(shots + "-02-walked");

		context.setScreen(MapScreen::new);
		context.waitTicks(5);
		context.takeScreenshot(shots + "-03-map");
		context.runOnClient(mc -> {
			Entity camera = mc.getCameraEntity();
			MapViewport.View view = ((MapScreen) mc.gui.screen()).view();
			if (Math.abs(view.centerU() - camera.getX()) > 2 || Math.abs(view.centerV() - Projection.v(camera.getY(), camera.getZ())) > 2) {
				wrong.add("The map is centred at u " + view.centerU() + ", v " + view.centerV() + ", not on the camera at x " + camera.getX() + ", u " + camera.getX());
			}
			// Looked at directly, wherever the map stayed.
			MapScreen screen = (MapScreen) mc.gui.screen();
			screen.lookAt(camera.getX(), Projection.v(camera.getY(), camera.getZ()), view.zoom(), view.layer());
		});
		context.waitTicks(2);
		context.runOnClient(mc -> {
			Entity camera = mc.getCameraEntity();
			if (Radar.collect(((MapScreen) mc.gui.screen()).view(), 1, true).stream().anyMatch(blip -> blip.entity() == camera)) {
				wrong.add("The radar shows who the map is already centred on");
			}
		});
		context.setScreen(() -> null);

		context.runOnClient(mc -> {
			Waypoint here = Waypoints.atPlayer();
			if (here == null || Math.abs(here.x - mc.getCameraEntity().getX()) > 2) {
				wrong.add("A new waypoint lands at x " + (here == null ? "nothing" : here.x) + ", not under the camera at x " + mc.getCameraEntity().getX());
			}
		});

		// Sealed in a stone room underground: the map goes to caves from where the camera is.
		int roomX = end + 8;
		command.accept("fill " + (roomX - 3) + " 40 " + (start[1] - 3) + " " + (roomX + 3) + " 46 " + (start[1] + 3) + " stone hollow");
		command.accept("tp " + target + " " + roomX + " 41 " + start[1]);
		context.waitFor(mc -> Math.abs(mc.getCameraEntity().getX() - roomX) < 2, 400);
		context.waitTicks(40);
		context.takeScreenshot(shots + "-04-underground");
		if (!context.computeOnClient(mc -> MapSession.get().isUnderground())) {
			wrong.add("The map stayed on the surface with the camera in a sealed room underground");
		}

		// Back to their own view: the server puts them where they were watching from.
		command.accept("execute as " + self + " run spectate");
		context.waitFor(mc -> mc.getCameraEntity() == mc.player, 200);
		context.waitFor(mc -> Math.abs(mc.player.getX() - roomX) < 2, 200);
		context.waitTicks(10);
		context.setScreen(MapScreen::new);
		context.waitTicks(5);
		context.runOnClient(mc -> {
			MapViewport.View view = ((MapScreen) mc.gui.screen()).view();
			if (Math.abs(view.centerU() - mc.player.getX()) > 2) {
				wrong.add("After stopping, the map is centred at u " + view.centerU() + ", not on the player at x " + mc.player.getX());
			}
			if (view.layer() == TileKey.SURFACE) {
				wrong.add("After stopping in the room, the map opened on the surface");
			}
		});
		context.takeScreenshot(shots + "-05-stopped");
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			Settings.get().coordinates = coordinates;
			Settings.get().radarRange = radarRange;
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError(shots + ": " + String.join("; ", wrong));
		}
	}
}
