package dev.diorama.test;

import com.mojang.authlib.GameProfile;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.TrackedPlayers;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapViewport;
import dev.diorama.share.ShareProtocol;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 *     teammate (far players) shows, as Xaero's party tracker shows party members, and a sneaking
 *     player's torch doesn't light the map in a dark room, while a standing player's does.
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

		torch(context, world, sneaking, wrong, x, y, z);

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

	/**
	 * Whether the map draws the player called {@code name} on its radar, read off the blips it drew
	 * last frame; says so in {@code wrong} if not as expected. A player missing from the world is
	 * wrong either way: it would be off the radar for no reason this test checks.
	 */
	private static void expect(ClientGameTestContext context, List<String> wrong, String name, boolean shown, String state) {
		context.setScreen(MapScreen::new);
		DioramaClientTest.lookAtPlayer(context, 6.0, TileKey.SURFACE);
		context.waitTicks(2);
		Boolean onMap = context.computeOnClient(mc -> {
			Player player = player(mc, name);
			return player == null ? null : ((MapScreen) mc.gui.screen()).blips().stream().anyMatch(blip -> blip.entity() == player);
		});
		context.setScreen(() -> null);
		if (onMap == null) {
			wrong.add(name + " is " + state + " but isn't in the world");
		} else if (onMap != shown) {
			wrong.add(name + " is " + state + " and " + (shown ? "not on the radar" : "still on the radar"));
		}
	}

	/**
	 * A torch held by a sneaking player on another team leaves the map dark around them, as their
	 * face is left off the radar; standing, the torch lights it. In a closed stone room under the
	 * player, seen on the map's cave view, read off the screen's own pixels a few blocks north of
	 * {@code alex}, clear of their face and name.
	 */
	private static void torch(ClientGameTestContext context, TestSingleplayerContext world, RemotePlayer alex, List<String> wrong, int x, int y, int z) {
		java.util.function.Consumer<String> command = c -> world.getServer().runCommand(c);
		command.accept("fill " + (x - 12) + " -42 " + (z - 12) + " " + (x + 12) + " -34 " + (z + 12) + " stone");
		command.accept("fill " + (x - 10) + " -40 " + (z - 10) + " " + (x + 10) + " -37 " + (z + 10) + " air");
		command.accept("tp @a " + (x + 6.5) + " -40 " + (z + 0.5));
		context.waitFor(mc -> mc.player.blockPosition().getY() == -40, 400);
		context.runOnClient(mc -> alex.snapTo(x - 4.5, -40, z + 0.5));
		DioramaClientTest.settle(context, 120);
		int layer = context.computeOnClient(mc -> MapSession.caveLayerFor(mc.player.getY()));
		context.setScreen(MapScreen::new);
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(Projection.u(x - 4.5, z + 0.5), Projection.v(x - 4.5, -40, z + 0.5), 6.0, layer));
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(40);
		boolean present = context.computeOnClient(mc -> player(mc, SNEAKING) == alex);
		double dark = brightness(context, "radar-sneaking-4-dark-room", x - 4.5, -40, z - 3.5);

		context.runOnClient(mc -> {
			alex.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
			alex.setShiftKeyDown(true);
		});
		context.waitTicks(40);
		double sneakingLit = brightness(context, "radar-sneaking-5-sneaking-torch", x - 4.5, -40, z - 3.5);

		context.runOnClient(mc -> alex.setShiftKeyDown(false));
		context.waitTicks(40);
		double standingLit = brightness(context, "radar-sneaking-6-standing-torch", x - 4.5, -40, z - 3.5);
		context.setScreen(() -> null);
		System.out.printf("radar-sneaking torch: dark room %.1f, sneaking with a torch %.1f, standing with a torch %.1f%n", dark, sneakingLit, standingLit);

		context.runOnClient(mc -> alex.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
		command.accept("tp @a " + (x + 0.5) + " " + y + " " + (z + 0.5));
		context.waitFor(mc -> mc.player.blockPosition().getY() == y, 400);
		if (!present) {
			wrong.add(SNEAKING + " is holding a torch in a dark room but isn't in the world");
		} else if (standingLit < dark + 12) {
			wrong.add(SNEAKING + " is standing with a torch in a dark room and the map isn't lit around them (" + fmt(dark) + " dark, " + fmt(standingLit) + " lit)");
		} else if (sneakingLit > dark + 6) {
			wrong.add(SNEAKING + " is sneaking with a torch in a dark room and the map is lit around them (" + fmt(dark) + " dark, " + fmt(sneakingLit) + " sneaking)");
		}
	}

	/** How bright the screen is around where the world's (wx, wy, wz) is on the open map: 0..255, over a 7 by 7 pixel square. */
	private static double brightness(ClientGameTestContext context, String screenshot, double wx, double wy, double wz) {
		Path file = context.takeScreenshot(screenshot);
		int[] at = context.computeOnClient(mc -> {
			MapViewport.View view = ((MapScreen) mc.gui.screen()).view();
			double u = Projection.u(wx, wz);
			double v = Projection.v(wx, wy, wz);
			double scale = mc.getWindow().getGuiScale();
			return new int[] {(int) (view.screenX(u, v) * scale), (int) (view.screenY(u, v) * scale)};
		});
		BufferedImage shot;
		try {
			shot = ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		double sum = 0;
		int n = 0;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				int rgb = shot.getRGB(at[0] + dx, at[1] + dy);
				sum += ((rgb >> 16 & 0xFF) * 299 + (rgb >> 8 & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000.0;
				n++;
			}
		}
		return sum / n;
	}

	private static String fmt(double brightness) {
		return String.format(java.util.Locale.ROOT, "%.1f", brightness);
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
