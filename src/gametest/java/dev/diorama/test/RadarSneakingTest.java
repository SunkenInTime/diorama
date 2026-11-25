package dev.diorama.test;

import com.mojang.authlib.GameProfile;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Settings;
import dev.diorama.TrackedPlayers;
import dev.diorama.bake.TileKey;
import dev.diorama.render.Radar;
import dev.diorama.share.ShareProtocol;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Sneaking players on the radar. Two other players stand near the player, one upright and one
 * sneaking. As on Xaero's Minimap and JourneyMap, the sneaking one is left off the minimap and the
 * map while the upright one shows. On the player's scoreboard team the sneaking one shows, as on
 * Xaero's, and on another team it doesn't.
 *
 * <ul>
 * <li>{@code radar-sneaking}: in singleplayer, with stand-ins added on the client as in {@code
 *     radar}, since a test runs one real client. Also a sneaking player the server reports as a
 *     teammate (far players) shows, as Xaero's party tracker shows party members.
 * <li>{@code radar-sneaking-server}: real players on a real server with Diorama ({@code --env
 *     DIORAMA_REAL_SERVER=1 --type large}, Fabric): a vanilla client stands, and a second game like
 *     this one ({@link SecondPlayer}) holds its sneak key.
 * </ul>
 *
 * Screenshots {@code radar-sneaking-*}: the minimap, then the map, in each state.
 */
final class RadarSneakingTest {
	private static final String UPRIGHT = "Steve";
	private static final String SNEAKING = "Alex";

	private RadarSneakingTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		List<String> wrong = new ArrayList<>();
		context.waitFor(mc -> mc.player.onGround(), 600);
		BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
		int x = at.getX();
		int y = at.getY();
		int z = at.getZ();
		clear(command -> world.getServer().runCommand(command), x, y, z);
		RemotePlayer sneaking = context.computeOnClient(mc -> {
			standIn(mc, UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7"), UPRIGHT, -424300, x + 6.5, y, z - 4.5);
			return standIn(mc, UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce"), SNEAKING, -424301, x - 5.5, y, z + 5.5);
		});
		DioramaClientTest.settle(context, 20);
		Settings.NameTags names = showNames(context);
		photograph(context, "radar-sneaking-1-both-standing");
		expect(context, wrong, UPRIGHT, true, "standing");
		expect(context, wrong, SNEAKING, true, "standing");

		context.runOnClient(mc -> sneaking.setShiftKeyDown(true));
		context.waitTicks(5);
		photograph(context, "radar-sneaking-2-one-sneaking");
		expect(context, wrong, UPRIGHT, true, "standing beside a sneaking player");
		expect(context, wrong, SNEAKING, false, "sneaking");

		teams(context, command -> world.getServer().runCommand(command), wrong, "radar-sneaking-3-sneaking-teammate");

		// A teammate the server reports wherever they are (far players): shown sneaking.
		context.runOnClient(mc -> TrackedPlayers.fromDiorama(farPlayers(sneaking.getUUID(), x - 5, y, z + 5)));
		context.waitTicks(2);
		expect(context, wrong, SNEAKING, true, "sneaking and reported by the server as a teammate");
		context.runOnClient(mc -> TrackedPlayers.fromDiorama(farPlayers(null, 0, 0, 0)));
		context.waitTicks(2);
		expect(context, wrong, SNEAKING, false, "sneaking, no longer reported by the server");

		context.runOnClient(mc -> sneaking.setShiftKeyDown(false));
		context.waitTicks(2);
		expect(context, wrong, SNEAKING, true, "standing up again");

		context.runOnClient(mc -> {
			for (int id : new int[] {-424300, -424301}) {
				mc.level.removeEntity(id, Entity.RemovalReason.DISCARDED);
			}
			Settings.get().playerNames = names;
		});
		if (!wrong.isEmpty()) {
			throw new AssertionError("Sneaking players on the radar:\n" + String.join("\n", wrong));
		}
	}

	static void server(ClientGameTestContext context) {
		if (System.getenv("DIORAMA_REAL_SERVER") == null) {
			throw new AssertionError("radar-sneaking-server needs a real server: run it with --env DIORAMA_REAL_SERVER=1");
		}
		List<String> wrong = new ArrayList<>();
		Settings.NameTags names = showNames(context);
		try (RealServer server = RealServer.start(true)) {
			server.command("time set noon");
			server.join(context);
			String me = context.computeOnClient(mc -> mc.player.getScoreboardName());
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
			server.command("gamemode creative " + me);
			context.waitFor(mc -> mc.player.onGround(), 600);
			BlockPos at = context.computeOnClient(mc -> mc.player.blockPosition());
			int x = at.getX();
			int y = at.getY();
			int z = at.getZ();
			clear(server::command, x, y, z);
			server.vanillaPlayerJoins(UPRIGHT);
			try (SecondPlayer alex = SecondPlayer.join(SNEAKING, server)) {
				// Creative, so nothing hurts them while they stand about.
				server.command("gamemode creative " + UPRIGHT);
				server.command("gamemode creative " + SNEAKING);
				server.commandAndWait("tp " + UPRIGHT + " " + (x + 6.5) + " " + y + " " + (z - 4.5), "Teleported " + UPRIGHT, 120);
				server.commandAndWait("tp " + SNEAKING + " " + (x - 5.5) + " " + y + " " + (z + 5.5), "Teleported " + SNEAKING, 120);
				context.waitFor(mc -> near(player(mc, UPRIGHT), x + 6.5, z - 4.5) && near(player(mc, SNEAKING), x - 5.5, z + 5.5), 1200);
				DioramaClientTest.settle(context, 40);
				photograph(context, "radar-sneaking-server-1-both-standing");
				expect(context, wrong, UPRIGHT, true, "standing");
				expect(context, wrong, SNEAKING, true, "standing");

				alex.sneak(true);
				context.waitFor(mc -> player(mc, SNEAKING) != null && player(mc, SNEAKING).isShiftKeyDown(), 1200);
				context.waitTicks(10);
				photograph(context, "radar-sneaking-server-2-one-sneaking");
				expect(context, wrong, UPRIGHT, true, "standing beside a sneaking player");
				expect(context, wrong, SNEAKING, false, "sneaking");

				teams(context, server::command, wrong, "radar-sneaking-server-3-sneaking-teammate");

				alex.sneak(false);
				context.waitFor(mc -> player(mc, SNEAKING) != null && !player(mc, SNEAKING).isShiftKeyDown(), 1200);
				context.waitTicks(2);
				expect(context, wrong, SNEAKING, true, "standing up again");
			} finally {
				server.vanillaPlayerLeaves(UPRIGHT);
			}
			RealServer.leave(context);
		}
		context.runOnClient(mc -> Settings.get().playerNames = names);
		if (!wrong.isEmpty()) {
			throw new AssertionError("Sneaking players on the radar of a real server:\n" + String.join("\n", wrong));
		}
	}

	/** The sneaking player on the player's scoreboard team shows; on another team they don't. */
	private static void teams(ClientGameTestContext context, java.util.function.Consumer<String> command, List<String> wrong, String screenshot) {
		String me = context.computeOnClient(mc -> mc.player.getScoreboardName());
		command.accept("team add diorama_mates");
		command.accept("team join diorama_mates " + me);
		command.accept("team join diorama_mates " + SNEAKING);
		context.waitFor(mc -> mc.player.getTeam() != null && player(mc, SNEAKING) != null && player(mc, SNEAKING).getTeam() == mc.player.getTeam(), 400);
		context.waitTicks(5);
		photograph(context, screenshot);
		expect(context, wrong, SNEAKING, true, "sneaking on the player's team");
		command.accept("team add diorama_others");
		command.accept("team join diorama_others " + SNEAKING);
		context.waitFor(mc -> player(mc, SNEAKING) != null && player(mc, SNEAKING).getTeam() != null && player(mc, SNEAKING).getTeam() != mc.player.getTeam(), 400);
		context.waitTicks(2);
		expect(context, wrong, SNEAKING, false, "sneaking on another team");
		command.accept("team remove diorama_mates");
		command.accept("team remove diorama_others");
		context.waitFor(mc -> mc.player.getTeam() == null && player(mc, SNEAKING) != null && player(mc, SNEAKING).getTeam() == null, 400);
	}

	/** An empty patch of grass around the player, so nothing hides the two. */
	private static void clear(java.util.function.Consumer<String> command, int x, int y, int z) {
		command.accept("fill " + (x - 10) + " " + y + " " + (z - 10) + " " + (x + 10) + " " + (y + 4) + " " + (z + 10) + " air");
		command.accept("fill " + (x - 10) + " " + (y - 1) + " " + (z - 10) + " " + (x + 10) + " " + (y - 1) + " " + (z + 10) + " grass_block");
	}

	/** Names on the radar's player heads, for the screenshots; returns what it was. */
	private static Settings.NameTags showNames(ClientGameTestContext context) {
		Settings.NameTags names = context.computeOnClient(mc -> Settings.get().playerNames);
		context.runOnClient(mc -> Settings.get().playerNames = Settings.NameTags.ALWAYS);
		return names;
	}

	private static RemotePlayer standIn(Minecraft mc, UUID id, String name, int entityId, double x, double y, double z) {
		RemotePlayer player = new RemotePlayer(mc.level, new GameProfile(id, name));
		player.setId(entityId);
		player.snapTo(x, y, z);
		//? if >=1.21 {
		mc.level.addEntity(player);
		//?} else {
		/*mc.level.addPlayer(player.getId(), player);
		*///?}
		return player;
	}

	private static @Nullable Player player(Minecraft mc, String name) {
		for (Player player : mc.level.players()) {
			if (player.getScoreboardName().equals(name)) {
				return player;
			}
		}
		return null;
	}

	private static boolean near(@Nullable Player player, double x, double z) {
		return player != null && Math.abs(player.getX() - x) < 1 && Math.abs(player.getZ() - z) < 1;
	}

	/** The minimap, then the map over the player. */
	private static void photograph(ClientGameTestContext context, String name) {
		context.takeScreenshot(name + "-minimap");
		context.setScreen(MapScreen::new);
		DioramaClientTest.lookAtPlayer(context, 6.0, TileKey.SURFACE);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(5);
		context.takeScreenshot(name + "-map");
		context.setScreen(() -> null);
	}

	/** Whether the map's radar shows the player called {@code name}; says so in {@code wrong} if not as expected. */
	private static void expect(ClientGameTestContext context, List<String> wrong, String name, boolean shown, String state) {
		context.setScreen(MapScreen::new);
		DioramaClientTest.lookAtPlayer(context, 6.0, TileKey.SURFACE);
		context.waitTicks(2);
		boolean onMap = context.computeOnClient(mc -> {
			Player player = player(mc, name);
			return player != null && Radar.collect(((MapScreen) mc.gui.screen()).view(), 1, true).stream().anyMatch(blip -> blip.entity() == player);
		});
		context.setScreen(() -> null);
		if (onMap != shown) {
			wrong.add(name + " is " + state + " and " + (shown ? "not on the radar" : "still on the radar"));
		}
	}

	/** A far-players message from Diorama on the server naming {@code id}, or no one, in the Overworld. */
	private static byte[] farPlayers(@Nullable UUID id, int x, int y, int z) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(ShareProtocol.FAR_PLAYERS);
			out.writeUTF("");
			out.writeShort(id == null ? 0 : 1);
			if (id != null) {
				out.writeLong(id.getMostSignificantBits());
				out.writeLong(id.getLeastSignificantBits());
				out.writeUTF("minecraft:overworld");
				out.writeInt(x);
				out.writeInt(y);
				out.writeInt(z);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}
}
