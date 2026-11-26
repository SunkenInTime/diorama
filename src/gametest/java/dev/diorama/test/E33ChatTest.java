package dev.diorama.test;

import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointEditScreen;
import dev.diorama.waypoint.WaypointShare;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Shared waypoints with E33Chat installed (opt-in: {@code e33chat}), which draws chat in a window
 * of its own, from its own copy of each line. A shared waypoint that arrives, from the server or
 * from a player, must have its "[Add]" link in that window, and clicking it there must open the
 * waypoint editor filled in.
 */
final class E33ChatTest {
	private E33ChatTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		List<String> wrong = new ArrayList<>();
		// As the server says it, the way a server without signed chat passes a player's line on.
		world.getServer().runCommand("tellraw @a {\"translate\":\"chat.type.text\",\"with\":[\"Alex\",\"xaero-waypoint:Old Mine:oM:120:40:-80:6:false:0:Internal-overworld-waypoints\"]}");
		context.waitTicks(10);
		check(context, "Old Mine", "120", "40", "-80", "e33chat-01-server-passes-on", wrong);

		// As a player says it in chat.
		context.runOnClient(mc -> mc.player.connection.sendChat("xaero-waypoint:Far Camp:FC:-300:71:412:2:false:0:Internal-overworld-waypoints"));
		context.waitTicks(20);
		check(context, "Far Camp", "-300", "71", "412", "e33chat-02-player-says", wrong);

		// As a command says it for someone: a line with a sender and no signature.
		world.getServer().runCommand("say xaero-waypoint:Tower:T:55:90:-12:6:false:0:Internal-overworld-waypoints");
		context.waitTicks(10);
		check(context, "Tower", "55", "90", "-12", "e33chat-03-command-says", wrong);

		// A plain line from the server, with no player in it.
		world.getServer().runCommand("tellraw @a \"xaero-waypoint:Dock:D:8:63:9:6:false:0:Internal-overworld-waypoints\"");
		context.waitTicks(10);
		check(context, "Dock", "8", "63", "9", "e33chat-04-plain-line", wrong);
		if (!wrong.isEmpty()) {
			throw new AssertionError("Shared waypoints with E33Chat: " + String.join("; ", wrong));
		}
	}

	/**
	 * On a real server (opt-in: {@code e33chat-server}): another player, with the same mods, shares
	 * a waypoint in chat, with the line Diorama's "Share in chat" sends. On Fabric, where the second
	 * player can be told what to say.
	 */
	static void server(ClientGameTestContext context) {
		//? if !fabric {
		/*if (true) {
			throw new AssertionError("e33chat-server runs on Fabric nodes: the second player only takes lines to say there");
		}
		*///?}
		if (System.getenv("DIORAMA_REAL_SERVER") == null) {
			throw new AssertionError("e33chat-server needs a real server: run it with --env DIORAMA_REAL_SERVER=1");
		}
		List<String> wrong = new ArrayList<>();
		try (RealServer server = RealServer.start(false)) {
			server.join(context);
			try (SecondPlayer alice = SecondPlayer.join("Alice", server)) {
				Waypoint shared = new Waypoint("Alice's base", 240, 68, -96, "minecraft:overworld", Waypoint.PALETTE[3]);
				shared.initials = "AB";
				alice.say(WaypointShare.format(shared).orElseThrow());
				context.waitFor(mc -> DioramaClientTest.chatMessages(mc).stream().anyMatch(line -> line.content().getString().contains("xaero-waypoint:Alice's base:")), 1200);
				context.waitTicks(10);
				check(context, "Alice's base", "240", "68", "-96", "e33chat-server-01-another-player-shares", wrong);
			}
			RealServer.leave(context);
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("A shared waypoint from another player with E33Chat: " + String.join("; ", wrong));
		}
	}

	/** Opens chat, finds the line's "[Add]" link in E33Chat's window, clicks it and reads the editor. */
	private static void check(ClientGameTestContext context, String name, String x, String y, String z, String picture, List<String> wrong) {
		//? if >=1.21.9 {
		context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen("", false));
		//?} else {
		/*context.setScreen(() -> new net.minecraft.client.gui.screens.ChatScreen(""));
		*///?}
		context.waitTicks(30);
		String screen = context.computeOnClient(mc -> mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getName());
		if (!screen.startsWith("com.niuqu.chatbubble.")) {
			throw new AssertionError("DIORAMA_TESTS=e33chat needs E33Chat installed: chat opened as " + screen);
		}
		context.takeScreenshot(picture);
		// Where E33Chat drew a link that runs Diorama's command for this waypoint, in GUI pixels: one
		// for each row the link is on, and E33Chat before 2.4.8 makes the whole line the link.
		List<double[]> links = context.computeOnClient(mc -> {
			List<double[]> found = new ArrayList<>();
			for (Object span : spans(mc.gui.screen())) {
				Style style = (Style) read(span, "style");
				String command = DioramaClientTest.linkIn(Component.literal("x").withStyle(style));
				if (command != null && command.startsWith("/diorama waypoint add " + x + " ") && command.endsWith(" " + name)) {
					found.add(new double[] {(int) read(span, "x") + (int) read(span, "w") / 2.0, (int) read(span, "y") + (int) read(span, "h") / 2.0});
				}
			}
			return found;
		});
		double[] at = links.isEmpty() ? null : links.get(0);
		if (at == null) {
			String vanilla = context.computeOnClient(mc -> {
				StringBuilder lines = new StringBuilder();
				for (net.minecraft.client.multiplayer.chat.GuiMessage message : DioramaClientTest.chatMessages(mc)) {
					if (message.content().getString().contains(":" + name + ":")) {
						lines.append(message.content().getString()).append(" (link: ").append(DioramaClientTest.linkIn(message.content())).append(") ");
					}
				}
				return lines.toString();
			});
			String clickable = context.computeOnClient(mc -> {
				List<String> commands = new ArrayList<>();
				for (Object span : spans(mc.gui.screen())) {
					commands.add(String.valueOf(DioramaClientTest.linkIn(Component.literal("x").withStyle((Style) read(span, "style")))));
				}
				return commands.toString();
			});
			wrong.add("E33Chat's window has no [Add] link for " + name + " (its links run " + clickable + "); Minecraft's own chat has: " + vanilla);
			context.setScreen(() -> null);
			return;
		}
		DioramaClientTest.clickAtGui(context, at[0], at[1]);
		try {
			context.waitFor(mc -> mc.gui.screen() instanceof WaypointEditScreen, 100);
		} catch (AssertionError e) {
			wrong.add("clicking [Add] for " + name + " in E33Chat's window didn't open the waypoint editor");
			context.setScreen(() -> null);
			return;
		}
		context.waitTicks(2);
		List<String> shown = context.computeOnClient(mc -> {
			List<String> boxes = new ArrayList<>();
			for (var child : mc.gui.screen().children()) {
				if (child instanceof net.minecraft.client.gui.components.EditBox box) {
					boxes.add(box.getValue());
				}
			}
			return boxes;
		});
		if (shown.size() < 5 || !shown.get(0).equals(name) || !shown.subList(2, 5).equals(List.of(x, y, z))) {
			wrong.add("the editor opened from " + name + "'s link shows " + shown);
		}
		context.takeScreenshot(picture + "-editor");
		context.setScreen(() -> null);
		context.waitTicks(2);
	}

	/** The links E33Chat's window drew this frame: each has a place on screen and a style. */
	private static List<?> spans(Screen screen) {
		return (List<?>) read(screen, "clickableSpans");
	}

	private static Object read(Object from, String name) {
		try {
			Field field = from.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(from);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("E33Chat has changed: no " + name + " in " + from.getClass().getName(), e);
		}
	}
}
