package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapSession;
import dev.diorama.Settings;
import dev.diorama.web.ClientWebMap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The web maps under the server's map settings (opt-in: {@code web-map-locks}, on a real server:
 * {@code --env DIORAMA_REAL_SERVER=1}). A player visits the Overworld, the Nether and the End, and
 * the server's web map draws all three. Then the server is started again under each setting:
 * with the End in {@code map-off-dimensions}, its web map has no End, by the page or by a
 * picture's address, and the End comes back when the setting goes; with {@code
 * cave-maps-allowed=false} or {@code cave-fog-of-war=true}, where the map in the game shows only
 * what each player has seen under the Nether's roof, it has no Nether. The player's own map in a
 * browser shows nothing in a dimension the server or the player turned off. Photographs each page
 * into {@code screenshots/web-map-locks/}.
 */
final class WebMapLocksTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final String END = "minecraft:the_end";
	/** A pass that has land to draw, on a test VM's processor. */
	private static final int PASS_WAIT_TICKS = 20 * 60 * 10;

	private WebMapLocksTest() {
	}

	static void run(ClientGameTestContext context) {
		if (System.getenv("DIORAMA_REAL_SERVER") == null) {
			throw new AssertionError("web-map-locks needs a real server: run it with --env DIORAMA_REAL_SERVER=1");
		}
		Path config = Path.of(System.getProperty("user.home"), "real-servers", System.getenv("DIORAMA_NODE"), "config", "diorama-server.properties");
		Path out = Path.of("screenshots", "web-map-locks");
		int port = freePort();
		String base = "web-map=true\nweb-map-port=" + port + "\nweb-map-address=127.0.0.1\nshare-land=visited\n";
		String page = "http://127.0.0.1:" + port + "/";
		HttpClient http = HttpClient.newHttpClient();
		List<String> wrong = new ArrayList<>();
		try {
			Files.createDirectories(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		// The player spends a while in each dimension; the server saves it all as it stops.
		write(config, base);
		try (RealServer server = RealServer.start(true)) {
			server.command("time set noon");
			server.join(context);
			context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
			context.waitTicks(200);
			visit(context, server, NETHER, Level.NETHER, "0 70 0");
			context.waitTicks(200);
			visit(context, server, END, Level.END, "0 80 0");
			context.waitTicks(200);
			RealServer.leave(context);
		}

		// Nothing set: all three dimensions drawn.
		String overworldTile;
		String netherTile;
		String endTile;
		try (RealServer server = RealServer.start(true)) {
			String json = waitFor(context, http, port, server, PASS_WAIT_TICKS, OVERWORLD, NETHER, END);
			overworldTile = tile(json, OVERWORLD);
			netherTile = tile(json, NETHER);
			endTile = tile(json, END);
			ClientWebMapTest.photograph(page + "#dim=" + NETHER, out.resolve("server-1-nothing-set-nether.png"));
			ClientWebMapTest.photograph(page + "#dim=" + END, out.resolve("server-2-nothing-set-end.png"));
		}

		// The End off: gone from the server's web map, and from the player's own while they're in it.
		write(config, base + "map-off-dimensions=minecraft:the_end\n");
		try (RealServer server = RealServer.start(true)) {
			String json = waitFor(context, http, port, server, PASS_WAIT_TICKS, OVERWORLD, NETHER);
			expect(wrong, tile(json, END) == null, "with the End in map-off-dimensions, the server's web map lists the End");
			expect(wrong, status(http, port, endTile) == 404, "with the End in map-off-dimensions, its picture " + endTile + " is still served (" + status(http, port, endTile) + ")");
			expect(wrong, status(http, port, netherTile) == 200 && status(http, port, overworldTile) == 200, "with only the End off, the Nether's or the Overworld's pictures are gone");
			ClientWebMapTest.photograph(page + "#dim=" + END, out.resolve("server-3-end-off.png"));

			// The player comes back where they left: in the End, with no map.
			server.join(context);
			int own = freePort();
			context.runOnClient(mc -> {
				Settings.get().webMapPort = own;
				Settings.get().webMap = Settings.WebMap.THIS_COMPUTER;
			});
			context.waitFor(mc -> ClientWebMap.servingPort() != 0, 200);
			int ownPort = ClientWebMap.servingPort();
			String ownPage = "http://127.0.0.1:" + ownPort + "/";
			// In the Nether, where nothing is off: the player's own map of it.
			visit(context, server, NETHER, Level.NETHER, "0 70 0");
			DioramaClientTest.settle(context, 100);
			String ownJson = "";
			for (int i = 0; i < 200 && tile(ownJson, NETHER) == null; i++) {
				context.waitTicks(10);
				ownJson = get(http, ownPort, "/map.json");
			}
			String ownTile = tile(ownJson, NETHER);
			if (ownTile == null || status(http, ownPort, ownTile) != 200) {
				throw new AssertionError("The player's own web map has no Nether while they're in it: " + cut(ownJson));
			}
			ClientWebMapTest.photograph(ownPage, out.resolve("client-1-nether-on.png"));
			// Turned off by the player, standing there.
			context.runOnClient(mc -> Settings.get().mapOffDimensions.add(NETHER));
			context.waitTicks(20);
			ownJson = get(http, ownPort, "/map.json");
			expect(wrong, !ownJson.contains("\"id\""), "with the Nether off in the player's settings, their own web map lists " + cut(ownJson));
			expect(wrong, status(http, ownPort, ownTile) == 404, "with the Nether off in the player's settings, their own web map still serves " + ownTile);
			expect(wrong, get(http, ownPort, "/player.json").equals("{}"), "with the Nether off in the player's settings, their own web map says where they are");
			ClientWebMapTest.photograph(ownPage, out.resolve("client-2-nether-off-by-player.png"));
			context.runOnClient(mc -> Settings.get().mapOffDimensions.remove(NETHER));
			// In the End, which the server turned off.
			server.command("execute in " + END + " run tp @a 0 80 0");
			context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.END, 2400);
			context.waitTicks(100);
			ownJson = get(http, ownPort, "/map.json");
			expect(wrong, !ownJson.contains("\"id\""), "in the End, which the server turned off, the player's own web map lists " + cut(ownJson));
			expect(wrong, get(http, ownPort, "/player.json").equals("{}"), "in the End, which the server turned off, the player's own web map says where they are");
			ClientWebMapTest.photograph(ownPage, out.resolve("client-3-end-off-by-server.png"));
			context.runOnClient(mc -> {
				Settings.get().webMap = Settings.WebMap.OFF;
				Settings.get().webMapPort = Settings.DEFAULT_WEB_MAP_PORT;
			});
			RealServer.leave(context);
		}

		// Cave maps not allowed: the map in the game shows only what each player has seen under the
		// Nether's roof, and a page for everyone can't. The End is on again, and drawn again.
		write(config, base + "cave-maps-allowed=false\n");
		try (RealServer server = RealServer.start(true)) {
			String json = waitFor(context, http, port, server, PASS_WAIT_TICKS, OVERWORLD, END);
			expect(wrong, tile(json, NETHER) == null, "with cave-maps-allowed=false, the server's web map lists the Nether");
			expect(wrong, status(http, port, netherTile) == 404, "with cave-maps-allowed=false, the Nether's picture " + netherTile + " is still served (" + status(http, port, netherTile) + ")");
			ClientWebMapTest.photograph(page + "#dim=" + NETHER, out.resolve("server-4-cave-maps-not-allowed.png"));
			ClientWebMapTest.photograph(page + "#dim=" + END, out.resolve("server-5-end-on-again.png"));

			// The player's own web map of the Nether has their own map's pictures, which under this
			// setting hold only what they've seen: photographed beside the map in the game.
			server.join(context);
			int own = freePort();
			context.runOnClient(mc -> {
				Settings.get().webMapPort = own;
				Settings.get().webMap = Settings.WebMap.THIS_COMPUTER;
			});
			context.waitFor(mc -> ClientWebMap.servingPort() != 0, 200);
			int ownPort = ClientWebMap.servingPort();
			visit(context, server, NETHER, Level.NETHER, "0 70 0");
			DioramaClientTest.settle(context, 100);
			String ownJson = "";
			for (int i = 0; i < 200 && tile(ownJson, NETHER) == null; i++) {
				context.waitTicks(10);
				ownJson = get(http, ownPort, "/map.json");
			}
			ClientWebMapTest.photograph("http://127.0.0.1:" + ownPort + "/", out.resolve("client-4-nether-cave-maps-not-allowed.png"));
			DioramaClientTest.openMapOnPlayer(context);
			context.takeScreenshot("web-map-locks-nether-in-game-cave-maps-not-allowed");
			context.setScreen(() -> null);
			context.runOnClient(mc -> {
				Settings.get().webMap = Settings.WebMap.OFF;
				Settings.get().webMapPort = Settings.DEFAULT_WEB_MAP_PORT;
			});
			RealServer.leave(context);
		}

		// Cave fog of war: the same.
		write(config, base + "cave-fog-of-war=true\n");
		try (RealServer server = RealServer.start(true)) {
			String json = waitFor(context, http, port, server, PASS_WAIT_TICKS, OVERWORLD, END);
			expect(wrong, tile(json, NETHER) == null, "with cave-fog-of-war=true, the server's web map lists the Nether");
			expect(wrong, status(http, port, netherTile) == 404, "with cave-fog-of-war=true, the Nether's picture " + netherTile + " is served (" + status(http, port, netherTile) + ")");
			ClientWebMapTest.photograph(page + "#dim=" + NETHER, out.resolve("server-6-cave-fog-of-war.png"));
		}

		// Nothing set again: the Nether is drawn again.
		write(config, base);
		try (RealServer server = RealServer.start(true)) {
			waitFor(context, http, port, server, PASS_WAIT_TICKS, OVERWORLD, NETHER, END);
			ClientWebMapTest.photograph(page + "#dim=" + NETHER, out.resolve("server-7-nothing-set-nether-again.png"));
		}
		try {
			Files.deleteIfExists(config);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (!wrong.isEmpty()) {
			throw new AssertionError("The web maps show more than the map in the game would:\n" + String.join("\n", wrong));
		}
	}

	private static void expect(List<String> wrong, boolean right, String otherwise) {
		if (!right) {
			Diorama.LOGGER.error("web-map-locks: {}", otherwise);
			wrong.add(otherwise);
		}
	}

	private static void visit(ClientGameTestContext context, RealServer server, String id, ResourceKey<Level> dimension, String place) {
		server.command("execute in " + id + " run tp @a " + place);
		context.waitFor(mc -> mc.level != null && mc.level.dimension() == dimension && MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
	}

	/**
	 * Waits until the web map has finished a pass since this server started and its map file lists
	 * each of {@code dimensions} with pictures; returns the map file.
	 */
	private static String waitFor(ClientGameTestContext context, HttpClient http, int port, RealServer server, int ticks, String... dimensions) {
		String[] json = {""};
		int[] asked = {0};
		try {
			context.waitFor(mc -> {
				// Every two seconds: each look reads the server's whole console.
				if (asked[0]++ % 40 != 0 || status(http, port, "/map.json") != 200) {
					return false;
				}
				// The status command answers in the console; "up to date" once a pass has finished.
				server.command("diorama-web status");
				String console = server.console();
				int said = console.lastIndexOf("Web map: ");
				if (said < 0 || !console.startsWith("Web map: up to date", said)) {
					return false;
				}
				json[0] = get(http, port, "/map.json");
				for (String dimension : dimensions) {
					if (tile(json[0], dimension) == null) {
						return false;
					}
				}
				return true;
			}, ticks);
		} catch (AssertionError e) {
			throw new AssertionError("The server's web map never listed " + String.join(", ", dimensions) + " with pictures: " + cut(json[0]), e);
		}
		return json[0];
	}

	/** The address of a dimension's first picture in a map file, or null if it lists none for it. */
	private static @Nullable String tile(String json, String dimension) {
		Matcher matcher = Pattern.compile("\"id\":\"" + Pattern.quote(dimension) + "\",\"folder\":\"([^\"]+)\",\"zoomLevels\":\\d+,\"versions\":true,\"tiles\":\\[\\[(-?\\d+),(-?\\d+),").matcher(json);
		return matcher.find() ? "/" + matcher.group(1) + "/0/" + matcher.group(2) + "_" + matcher.group(3) + ".jpg" : null;
	}

	private static String cut(String json) {
		return json.length() > 400 ? json.substring(0, 400) + "..." : json;
	}

	private static void write(Path file, String text) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The HTTP status of a path, or -1 while nothing answers. */
	private static int status(HttpClient http, int port, String path) {
		try {
			return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (IOException e) {
			return -1;
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private static String get(HttpClient http, int port, String path) {
		try {
			return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString()).body();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private static int freePort() {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
