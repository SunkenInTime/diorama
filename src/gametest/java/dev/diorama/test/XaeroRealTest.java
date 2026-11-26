package dev.diorama.test;

import dev.diorama.MapSession;
import dev.diorama.waypoint.WaypointListScreen;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
//? if >=1.21.4 && fabric {
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
//?}
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * With the real Xaero's Minimap installed (opt-in: {@code xaero-real}, {@code xaero-real-server}):
 * Xaero's own code makes and saves waypoints, then the player looks for Diorama's import the way
 * a player would, with the U and B keys both mods bind, and in Diorama's waypoint list. Says what
 * each step opened in the log, so a run shows where a player ends up.
 */
final class XaeroRealTest {
	private XaeroRealTest() {
	}

	/** In the world: Xaero's had nothing here at join, so no line yet. Xaero's then makes waypoints. */
	static void singleplayer(ClientGameTestContext context, TestSingleplayerContext world) {
		requireXaero(context);
		String early = context.computeOnClient(XaeroRealTest::noticeLine);
		if (early != null) {
			throw new AssertionError("A Xaero's import line before Xaero's had any waypoints: " + early);
		}
		makeXaeroWaypoints(context, "sp");
		look(context, "sp");
	}

	/** Back in the world after Xaero's made waypoints there: the chat line comes, and its link leads to the import. */
	static void reopened(ClientGameTestContext context) {
		requireXaero(context);
		followNotice(context, "sp", "Xaero's Minimap has 3 waypoints for this world that aren't in Diorama yet. [Import]");
		String label = context.computeOnClient(mc -> importLabel(mc.gui.screen()));
		if (!"Import from Xaero's Minimap (3)".equals(label)) {
			throw new AssertionError("The link's waypoint list offers " + label);
		}
		context.runOnClient(mc -> press(button(mc.gui.screen(), label)));
		context.waitTicks(5);
		context.takeScreenshot("xaero-real-sp-06-imported");
		assertImported(context);
		context.setScreen(() -> null);
	}

	//? if >=1.21.4 && fabric {
	/**
	 * A server: Xaero's makes waypoints on the first visit, the line comes on the next and its link
	 * opens the list, and on the visit after that the line doesn't come again, though nothing was
	 * imported; the list's picker still imports them.
	 */
	static void server(ClientGameTestContext context) {
		requireXaero(context);
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("diorama-xaero-real"))
			.createServer(DioramaClientTest.serverProperties())) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
				String early = context.computeOnClient(XaeroRealTest::noticeLine);
				if (early != null) {
					throw new AssertionError("A Xaero's import line before Xaero's had any waypoints: " + early);
				}
				makeXaeroWaypoints(context, "server");
				look(context, "server");
			}
			context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			try (TestDedicatedServerConnection connection = server.connect()) {
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
				followNotice(context, "server", "Xaero's Minimap has waypoints for this server that aren't in Diorama yet. [Import]");
				String label = context.computeOnClient(mc -> importLabel(mc.gui.screen()));
				if (!"Import from Xaero's Minimap...".equals(label)) {
					throw new AssertionError("The link's waypoint list offers " + label);
				}
				// The player leaves without importing.
				context.setScreen(() -> null);
			}
			// Shown once per world: not on the next visit, though Xaero's waypoints still aren't
			// in Diorama. The list still offers them.
			context.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
			try (TestDedicatedServerConnection connection = server.connect()) {
				context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
				context.waitTicks(200);
				String again = context.computeOnClient(XaeroRealTest::noticeLine);
				if (again != null) {
					throw new AssertionError("The Xaero's import line came again on the next visit: " + again);
				}
				String label = context.computeOnClient(mc -> {
					mc.gui.setScreen(new WaypointListScreen(null));
					return importLabel(mc.gui.screen());
				});
				if (!"Import from Xaero's Minimap...".equals(label)) {
					throw new AssertionError("On the next visit the waypoint list offers " + label);
				}
				context.waitTicks(5);
				context.runOnClient(mc -> press(button(mc.gui.screen(), label)));
				context.waitTicks(5);
				context.takeScreenshot("xaero-real-server-06-picker");
				context.runOnClient(mc -> {
					for (var child : mc.gui.screen().children()) {
						if (child instanceof Button button && button.getMessage().getString().endsWith(": 3 new")) {
							press(button);
							return;
						}
					}
					throw new AssertionError("The picker has no world with 3 new waypoints: " + describe(mc.gui.screen()));
				});
				context.waitTicks(5);
				context.takeScreenshot("xaero-real-server-07-imported");
				assertImported(context);
				context.setScreen(() -> null);
			}
		}
	}
	//?}

	/** Waits for the chat line, checks its text, and clicks its link the way a player does. */
	private static void followNotice(ClientGameTestContext context, String where, String expected) {
		context.waitFor(mc -> noticeLine(mc) != null, 1200);
		String line = context.computeOnClient(XaeroRealTest::noticeLine);
		if (!expected.equals(line)) {
			throw new AssertionError("The Xaero's import line reads " + line);
		}
		context.takeScreenshot("xaero-real-" + where + "-04-chat-line");
		//? if >=26.3 {
		// Open chat with T and click [Import] with the mouse.
		context.getInput().pressKey(InputConstants.KEY_T);
		context.waitFor(mc -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen, 100);
		context.waitTicks(5);
		double[] at = context.computeOnClient(XaeroRealTest::linkPosition);
		if (at == null) {
			throw new AssertionError("Can't find the [Import] link on the chat screen");
		}
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(2);
		context.takeScreenshot("xaero-real-" + where + "-05-chat-open");
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		//?} else if >=1.21.6 {
		/*context.runOnClient(mc -> mc.player.connection.sendUnattendedCommand(dev.diorama.waypoint.XaeroNotice.COMMAND.substring(1), null));
		*///?} else {
		/*context.runOnClient(mc -> mc.player.connection.sendUnsignedCommand(dev.diorama.waypoint.XaeroNotice.COMMAND.substring(1)));
		*///?}
		context.waitFor(mc -> mc.gui.screen() instanceof WaypointListScreen, 200);
		context.waitTicks(5);
		context.takeScreenshot("xaero-real-" + where + "-05-link-opened-list");
	}

	//? if >=26.3 {
	/** Where on the window, in its pixels, the chat screen has the [Import] link, or null. */
	private static double[] linkPosition(Minecraft mc) {
		int height = mc.getWindow().getGuiScaledHeight();
		int width = mc.getWindow().getGuiScaledWidth();
		double scale = mc.getWindow().getGuiScale();
		for (int y = height - 1; y > height / 2; y--) {
			for (int x = 0; x < width; x += 2) {
				net.minecraft.client.gui.ActiveTextCollector.ClickableStyleFinder finder = new net.minecraft.client.gui.ActiveTextCollector.ClickableStyleFinder(mc.font, x, y);
				mc.gui.hud.getChat().captureClickableText(finder, height, mc.gui.hud.getGuiTicks(), net.minecraft.client.gui.components.ChatComponent.DisplayMode.FOREGROUND);
				net.minecraft.network.chat.Style style = finder.result();
				if (style != null && style.getClickEvent() instanceof net.minecraft.network.chat.ClickEvent.RunCommand run
					&& run.command().equals(dev.diorama.waypoint.XaeroNotice.COMMAND)) {
					return new double[] {(x + 1) * scale, (y + 0.5) * scale};
				}
			}
		}
		return null;
	}
	//?}

	/** The text of the Xaero's import line in chat, or null if there's none. */
	private static String noticeLine(Minecraft mc) {
		for (var message : DioramaClientTest.chatMessages(mc)) {
			if (dev.diorama.waypoint.XaeroNotice.COMMAND.equals(DioramaClientTest.linkIn(message.content()))) {
				return message.content().getString();
			}
		}
		return null;
	}

	/** Xaero's three waypoints are in Diorama now, where Xaero's put them. */
	private static void assertImported(ClientGameTestContext context) {
		List<String> names = context.computeOnClient(mc -> dev.diorama.waypoint.WaypointStore.get().all().stream()
			.map(w -> w.name).filter(n -> n.startsWith("Xaero ")).sorted().toList());
		if (!names.equals(List.of("Xaero Farm", "Xaero Home", "Xaero Mine"))) {
			throw new AssertionError("After the import Diorama has " + names);
		}
	}

	/** Xaero's waypoints left by an earlier run on the same machine go, before any world is joined. */
	static void clearXaero(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			try {
				org.apache.commons.io.FileUtils.deleteDirectory(mc.gameDirectory.toPath().resolve("xaero").resolve("minimap").toFile());
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
		});
	}

	/** Where a player looks: the U key, the B key, then Diorama's waypoint list itself. */
	private static void look(ClientGameTestContext context, String where) {
		context.setScreen(() -> null);
		context.waitTicks(5);
		context.getInput().pressKey(InputConstants.KEY_U);
		context.waitTicks(10);
		context.takeScreenshot("xaero-real-" + where + "-01-u-key");
		log(context, where + " U key", mc -> describe(mc.gui.screen()));
		context.setScreen(() -> null);
		context.waitTicks(5);
		context.getInput().pressKey(InputConstants.KEY_B);
		context.waitTicks(10);
		context.takeScreenshot("xaero-real-" + where + "-02-b-key");
		log(context, where + " B key", mc -> describe(mc.gui.screen()));
		context.setScreen(() -> null);
		context.waitTicks(5);
		log(context, where + " Xaero's files", mc -> files(mc.gameDirectory.toPath().resolve("xaero").resolve("minimap")));
		context.setScreen(() -> new WaypointListScreen(null));
		context.waitTicks(10);
		context.takeScreenshot("xaero-real-" + where + "-03-diorama-list");
		log(context, where + " Diorama's list", mc -> describe(mc.gui.screen()) + ", import button: " + importLabel(mc.gui.screen()));
		context.setScreen(() -> null);
		context.waitTicks(5);
	}

	/** Three waypoints in Xaero's current world, made and saved by Xaero's own code. */
	private static void makeXaeroWaypoints(ClientGameTestContext context, String where) {
		// Xaero's sets up its world for the level a few ticks after joining.
		context.waitFor(mc -> xaeroWorld() != null, 1200);
		context.runOnClient(mc -> {
			try {
				Object xaeroWorld = xaeroWorld();
				Object set = xaeroWorld.getClass().getMethod("getCurrentWaypointSet").invoke(xaeroWorld);
				Class<?> waypoint = Class.forName("xaero.common.minimap.waypoints.Waypoint");
				Constructor<?> make = waypoint.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);
				int x = mc.player.getBlockX();
				int y = mc.player.getBlockY();
				int z = mc.player.getBlockZ();
				Method add = set.getClass().getMethod("add", waypoint);
				add.invoke(set, make.newInstance(x + 20, y, z, "Xaero Home", "H", 10));
				add.invoke(set, make.newInstance(x - 30, y, z + 15, "Xaero Mine", "M", 14));
				add.invoke(set, make.newInstance(x, y, z - 40, "Xaero Farm", "F", 2));
				Object session = session();
				Object io = session.getClass().getMethod("getWorldManagerIO").invoke(session);
				Method save = null;
				for (Method m : io.getClass().getMethods()) {
					if (m.getName().equals("saveWorld") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(xaeroWorld)) {
						save = m;
					}
				}
				if (save == null) {
					throw new NoSuchMethodException("saveWorld for " + xaeroWorld.getClass());
				}
				save.invoke(io, xaeroWorld);
				dev.diorama.Diorama.LOGGER.info("xaero-real {}: Xaero's world {} saved", where, xaeroWorld.getClass().getMethod("getFullPath").invoke(xaeroWorld));
			} catch (ReflectiveOperationException e) {
				throw new AssertionError("Couldn't make waypoints through Xaero's Minimap", e);
			}
		});
	}

	private static Object session() throws ReflectiveOperationException {
		Object module = Class.forName("xaero.hud.minimap.BuiltInHudModules").getField("MINIMAP").get(null);
		return module.getClass().getMethod("getCurrentSession").invoke(module);
	}

	/** Xaero's Minimap's world for where the player is, or null before it has one. */
	static Object xaeroWorld() {
		try {
			Object session = session();
			if (session == null) {
				return null;
			}
			Object manager = session.getClass().getMethod("getWorldManager").invoke(session);
			return manager.getClass().getMethod("getCurrentWorld").invoke(manager);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static void requireXaero(ClientGameTestContext context) {
		if (xaeroWorldClass() == null) {
			throw new AssertionError("xaero-real needs Xaero's Minimap installed");
		}
	}

	private static Class<?> xaeroWorldClass() {
		try {
			return Class.forName("xaero.hud.minimap.BuiltInHudModules");
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	private static String describe(Screen screen) {
		if (screen == null) {
			return "no screen";
		}
		return screen.getClass().getName() + " \"" + screen.getTitle().getString() + "\"";
	}

	private static String importLabel(Screen screen) {
		if (screen == null) {
			return null;
		}
		for (var child : screen.children()) {
			if (child instanceof Button button && button.getMessage().getString().startsWith("Import from Xaero's")) {
				return button.getMessage().getString();
			}
		}
		return null;
	}

	private static Button button(Screen screen, String label) {
		for (var child : screen.children()) {
			if (child instanceof Button button && button.getMessage().getString().equals(label)) {
				return button;
			}
		}
		throw new AssertionError("No " + label + " button on " + describe(screen));
	}

	private static void press(Button button) {
		//? if >=1.21.9 {
		button.onPress(null);
		//?} else {
		/*button.onPress();
		*///?}
	}

	private static String files(Path base) {
		if (!Files.isDirectory(base)) {
			return base + " missing";
		}
		List<String> out = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(base)) {
			walk.filter(Files::isRegularFile).forEach(file -> {
				String line = base.relativize(file).toString();
				try {
					if (file.getFileName().toString().endsWith(".txt")) {
						line += " [" + Files.readAllLines(file).stream().filter(l -> l.startsWith("waypoint:")).count() + " waypoints]";
					}
				} catch (java.io.IOException e) {
					line += " [unreadable]";
				}
				out.add(line);
			});
		} catch (java.io.IOException e) {
			return "can't list " + base + ": " + e;
		}
		return String.join("; ", out);
	}

	private static void log(ClientGameTestContext context, String what, java.util.function.Function<Minecraft, String> describe) {
		String text = context.computeOnClient(describe::apply);
		dev.diorama.Diorama.LOGGER.info("xaero-real {}: {}", what, text);
	}
}
