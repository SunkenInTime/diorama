package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.ServerRules;
import dev.diorama.Settings;
import dev.diorama.SettingsScreen;
import dev.diorama.bake.TileKey;
import dev.diorama.share.ShareClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * A server's map settings locks (#332), on a real dedicated server: each lock alone, then none.
 * Locked, the setting is greyed out in Settings at the server's value, and the map does what the
 * server says; after leaving, the player's own settings are as they saved them. Then the same file
 * shipped in singleplayer's config folder, as a modpack would.
 */
final class ServerLocksTest {
	/** Settings' tabs, in order. */
	private static final int MINIMAP_TAB = 0;
	private static final int RADAR_TAB = 1;
	private static final int LOOK_TAB = 4;

	private ServerLocksTest() {
	}

	static void run(ClientGameTestContext context) {
		// The player's own settings: all on but fog of war, which the locks turn the other way.
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.autoCaves = true;
			s.radarOnMinimap = true;
			s.radarOnMap = true;
			s.alwaysFogOfWar = false;
			s.save();
		});
		String saved = context.computeOnClient(mc -> read(mc.gameDirectory.toPath().resolve(Diorama.MOD_ID).resolve("config.json")));

		onServer(context, "caves", "cave-maps-allowed=false\n", () -> {
			check(context, () -> !ServerRules.caveMapsAllowed() && ServerRules.radarAllowed() && !ServerRules.fogOfWarForced(), "only cave maps locked");
			settings(context, MINIMAP_TAB, "auto_caves", "caves");
		}, server -> {
			underground(context, server);
			context.takeScreenshot("locks-caves-minimap-underground");
			mapScreen(context, "caves");
			int mapLayer = mapLayer(context);
			if (mapLayer != TileKey.SURFACE) {
				throw new AssertionError("Cave maps are locked off, but the map is on the cave slice at " + mapLayer + " after C");
			}
			context.setScreen(() -> null);
			// The Nether's map is the view under its roof: kept, as far as the player has seen.
			// On a platform high up, where the player sees some of the Nether around them. The
			// chunk loads with the player, and only then can the platform go in.
			server.command("execute in minecraft:the_nether run tp @a 0 100 0");
			context.waitFor(mc -> mc.level != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER && MapSession.get() != null, 2400);
			context.waitTicks(40);
			server.command("execute in minecraft:the_nether run fill -4 100 -4 4 104 4 minecraft:air");
			server.command("execute in minecraft:the_nether run fill -2 99 -2 2 99 2 minecraft:glass");
			server.command("execute in minecraft:the_nether run tp @a 0 100 0");
			DioramaClientTest.settle(context, 100);
			if (!context.computeOnClient(mc -> MapSession.get().map().fogOfWar())) {
				throw new AssertionError("Cave maps are locked off, but the Nether's map shows ground the player hasn't seen");
			}
			context.takeScreenshot("locks-caves-nether-minimap");
			mapScreen(context, "caves-nether");
			context.setScreen(() -> null);
		});

		onServer(context, "fog", "cave-fog-of-war=true\n", () -> {
			check(context, () -> ServerRules.caveMapsAllowed() && ServerRules.radarAllowed() && ServerRules.fogOfWarForced(), "only fog of war locked");
			settings(context, LOOK_TAB, "fog_of_war", "fog");
		}, server -> {
			underground(context, server);
			if (!context.computeOnClient(mc -> MapSession.get().map().fogOfWar())) {
				throw new AssertionError("Fog of war is locked on, but the cave map isn't fog of war");
			}
			context.takeScreenshot("locks-fog-minimap-underground");
			mapScreen(context, "fog");
			context.setScreen(() -> null);
		});

		onServer(context, "radar", "radar-allowed=false\n", () -> {
			check(context, () -> ServerRules.caveMapsAllowed() && !ServerRules.radarAllowed() && !ServerRules.fogOfWarForced(), "only the radar locked");
			settings(context, RADAR_TAB, "radar_minimap", "radar");
		}, server -> {
			pigs(context, server);
			context.takeScreenshot("locks-radar-minimap-pigs");
			mapScreen(context, "radar");
			context.setScreen(() -> null);
		});

		onServer(context, "none", "cave-maps-allowed=true\ncave-fog-of-war=false\nradar-allowed=true\n", () -> {
			check(context, () -> ServerRules.caveMapsAllowed() && ServerRules.radarAllowed() && !ServerRules.fogOfWarForced(), "nothing locked");
			settings(context, MINIMAP_TAB, "auto_caves", "none-minimap");
			settings(context, RADAR_TAB, "radar_minimap", "none-radar");
			settings(context, LOOK_TAB, "fog_of_war", "none-look");
		}, server -> {
			pigs(context, server);
			context.takeScreenshot("locks-none-minimap-pigs");
			underground(context, server);
			context.takeScreenshot("locks-none-minimap-underground");
			if (context.computeOnClient(mc -> MapSession.get().map().fogOfWar())) {
				throw new AssertionError("Nothing is locked, but the cave map is fog of war");
			}
			mapScreen(context, "none");
			if (mapLayer(context) == TileKey.SURFACE) {
				throw new AssertionError("Nothing is locked, but the map opened on the surface underground");
			}
			context.setScreen(() -> null);
		});

		String after = context.computeOnClient(mc -> read(mc.gameDirectory.toPath().resolve(Diorama.MOD_ID).resolve("config.json")));
		if (!after.equals(saved)) {
			throw new AssertionError("The player's settings file changed over the servers' locks:\n" + saved + "\n---\n" + after);
		}
		singleplayer(context);
	}

	/**
	 * Joins a real server whose diorama-server.properties is {@code file}: {@code locked} runs as soon
	 * as the client has the server's word, then {@code inGame} with the server. After leaving, nothing
	 * is locked and the player's own settings are back as they were.
	 */
	static void onServer(ClientGameTestContext context, String name, String file, Runnable locked, java.util.function.Consumer<RealServer> inGame) {
		Path config = Path.of(System.getProperty("user.home"), "real-servers", System.getenv("DIORAMA_NODE"), "config", "diorama-server.properties");
		write(config, file);
		try (RealServer server = RealServer.start(true)) {
			server.command("time set noon");
			server.command("weather clear");
			server.join(context);
			// The server's settings come right after its word on the locks.
			context.waitFor(mc -> ShareClient.settings() != null, 1200);
			context.waitTicks(20);
			DioramaClientTest.settle(context, 100);
			locked.run();
			inGame.accept(server);
			RealServer.leave(context);
			if (!server.console().contains("Diorama on the server: sharing the map")) {
				throw new AssertionError("Diorama didn't start on the real server");
			}
			boolean locks = server.console().contains("locking map settings");
			if (locks != !name.equals("none")) {
				throw new AssertionError("The server's console " + (locks ? "says it locks settings" : "doesn't say it locks settings") + " for " + file);
			}
			String written = read(config);
			for (String key : new String[] {"cave-maps-allowed=", "cave-fog-of-war=", "radar-allowed=", "web-map="}) {
				if (!written.contains(key)) {
					throw new AssertionError("The server didn't add " + key + " to its settings file:\n" + written);
				}
			}
		}
		check(context, () -> ServerRules.caveMapsAllowed() && ServerRules.radarAllowed() && !ServerRules.fogOfWarForced(), "nothing locked after leaving the " + name + " server");
		check(context, () -> Settings.get().autoCaves && Settings.get().radarOnMinimap && Settings.get().radarOnMap && !Settings.get().alwaysFogOfWar,
			"the player's own settings back after leaving the " + name + " server");
		try {
			Files.deleteIfExists(config);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Singleplayer with diorama-server.properties in the game's config folder, as a modpack ships
	 * it: the same lock applies, and the line says where it's set. Without the file, nothing.
	 */
	private static void singleplayer(ClientGameTestContext context) {
		Path config = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("config").resolve("diorama-server.properties"));
		write(config, "radar-allowed=false\n");
		try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false).create()) {
			context.waitFor(mc -> MapSession.get() != null && !ServerRules.radarAllowed(), 1200);
			check(context, () -> ServerRules.caveMapsAllowed() && !ServerRules.fogOfWarForced(), "only the radar locked in singleplayer");
			settings(context, RADAR_TAB, "radar_minimap", "singleplayer");
		}
		check(context, () -> ServerRules.radarAllowed(), "nothing locked after leaving singleplayer");
		if (!read(config).equals("radar-allowed=false\n")) {
			throw new AssertionError("Singleplayer wrote to the modpack's settings file: " + read(config));
		}
		try {
			Files.delete(config);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false).create()) {
			context.waitFor(mc -> MapSession.get() != null, 1200);
			context.waitTicks(40);
			check(context, () -> ServerRules.radarAllowed(), "nothing locked in singleplayer without the file");
			if (Files.exists(config)) {
				throw new AssertionError("Singleplayer wrote a diorama-server.properties of its own");
			}
		}
	}

	/**
	 * A 0.9.0 server (REAL_SERVER_JAR) with locks in its file, which it doesn't know: this player
	 * joins, hears the shared map's settings as before, and nothing is locked.
	 */
	static void oldServer(ClientGameTestContext context) {
		if (System.getenv("REAL_SERVER_JAR") == null) {
			throw new AssertionError("Run server-locks-old-server with --env REAL_SERVER_JAR=<a 0.9.0 jar for the node>");
		}
		Path config = Path.of(System.getProperty("user.home"), "real-servers", System.getenv("DIORAMA_NODE"), "config", "diorama-server.properties");
		write(config, "cave-maps-allowed=false\ncave-fog-of-war=true\nradar-allowed=false\n");
		try (RealServer server = RealServer.start(true)) {
			server.join(context);
			context.waitFor(mc -> ShareClient.settings() != null, 1200);
			context.waitTicks(100);
			check(context, () -> ServerRules.caveMapsAllowed() && ServerRules.radarAllowed() && !ServerRules.fogOfWarForced(), "nothing locked on a 0.9.0 server");
			settings(context, MINIMAP_TAB, "auto_caves", "old-server");
			RealServer.leave(context);
			if (server.console().contains("locking map settings")) {
				throw new AssertionError("REAL_SERVER_JAR isn't a 0.9.0 jar: the server locked settings");
			}
		}
		try {
			Files.deleteIfExists(config);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Opens Settings on a tab, checks the setting's checkbox, and shows its tooltip. */
	private static void settings(ClientGameTestContext context, int tab, String key, String name) {
		context.setScreen(() -> new SettingsScreen(null));
		context.waitTicks(5);
		context.runOnClient(mc -> ((SettingsScreen) mc.gui.screen()).selectTab(tab));
		context.waitTicks(5);
		boolean locked = !name.startsWith("none") && !name.equals("old-server");
		String label = net.minecraft.network.chat.Component.translatable("diorama.settings." + key).getString();
		double[] at = context.computeOnClient(mc -> {
			for (GuiEventListener child : mc.gui.screen().children()) {
				if (child instanceof AbstractWidget widget && widget.visible && widget.getMessage().getString().equals(label)) {
					if (widget.active == locked) {
						throw new AssertionError(label + " is " + (widget.active ? "" : "not ") + "active with " + (locked ? "" : "no ") + "lock (" + name + ")");
					}
					DioramaClientTest.revealSetting(mc, widget);
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(widget.getX() + 8) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
				}
			}
			throw new AssertionError("No " + label + " in Settings");
		});
		context.takeScreenshot("locks-" + name + "-settings");
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(14);
		context.takeScreenshot("locks-" + name + "-settings-tooltip");
		context.getInput().setCursorPos(0, 0);
		context.setScreen(() -> null);
		context.waitTicks(5);
	}

	/** Digs a room 40 blocks down under the player and stands them in it. */
	private static void underground(ClientGameTestContext context, RealServer server) {
		int[] at = context.computeOnClient(mc -> new int[] {mc.player.getBlockX(), mc.player.getBlockZ()});
		server.command("fill " + (at[0] - 6) + " 18 " + (at[1] - 6) + " " + (at[0] + 6) + " 22 " + (at[1] + 6) + " minecraft:air");
		server.command("fill " + (at[0] - 2) + " 17 " + (at[1] - 2) + " " + (at[0] + 2) + " 17 " + (at[1] + 2) + " minecraft:gold_block");
		server.command("tp @a " + at[0] + " 18 " + at[1]);
		context.waitFor(mc -> mc.player.getY() < 20 && MapSession.get().isUnderground(), 600);
		DioramaClientTest.settle(context, 120);
	}

	/** Pigs around the player, on the surface. */
	private static void pigs(ClientGameTestContext context, RealServer server) {
		for (String offset : new String[] {"~4 ~ ~", "~-4 ~ ~2", "~ ~ ~5", "~3 ~ ~-4"}) {
			server.command("execute at @a run summon minecraft:pig " + offset + " {NoAI:1b}");
		}
		context.waitTicks(60);
	}

	/** Opens the map, presses C as a player would for caves, and shows it. */
	private static void mapScreen(ClientGameTestContext context, String name) {
		context.setScreen(MapScreen::new);
		DioramaClientTest.settle(context, 60);
		context.takeScreenshot("locks-" + name + "-map");
		if (name.equals("caves")) {
			context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_C);
			DioramaClientTest.settle(context, 40);
			context.takeScreenshot("locks-caves-map-after-c");
		}
	}

	private static int mapLayer(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			if (!(mc.gui.screen() instanceof MapScreen map)) {
				throw new AssertionError("The map isn't open");
			}
			try {
				java.lang.reflect.Field field = MapScreen.class.getDeclaredField("layer");
				field.setAccessible(true);
				return (Integer) field.get(map);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	/** Checks {@code ok} on the client's thread, where the rules and settings live. */
	private static void check(ClientGameTestContext context, java.util.function.BooleanSupplier ok, String what) {
		String state = context.computeOnClient(mc -> ok.getAsBoolean() ? null
			: "caves " + ServerRules.caveMapsAllowed() + ", radar " + ServerRules.radarAllowed() + ", fog forced " + ServerRules.fogOfWarForced());
		if (state != null) {
			throw new AssertionError("Expected " + what + ": " + state);
		}
	}

	private static void write(Path file, String text) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
