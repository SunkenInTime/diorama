package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.Settings;
import dev.diorama.bake.TileKey;
import dev.diorama.web.ClientWebMap;
import dev.diorama.web.WebMap;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The player's own map in a browser, in a singleplayer world (opt-in: {@code web-map-client}).
 * Off, nothing listens. On, the game serves the page, the map file, the player's place and the
 * tiles over HTTP; the tile under the player is the player's own map of it, as near as a JPEG
 * gets; and the place it gives moves when the player does. Where the machine has Chromium, the
 * page itself is photographed, before and after the move, into {@code screenshots/web-map-client/}.
 */
final class ClientWebMapTest {
	private static final Pattern NUMBER = Pattern.compile("\"(x|y|z|u|v|heading)\":(-?[0-9.]+)");

	private ClientWebMapTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		Path out = Path.of("screenshots", "web-map-client");
		try {
			Files.createDirectories(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		HttpClient http = HttpClient.newHttpClient();
		// What a singleplayer world did before: the server's web map only starts on a dedicated server.
		if (WebMap.get() != null) {
			throw new AssertionError("The server's web map runs in singleplayer");
		}
		if (context.computeOnClient(mc -> Settings.get().webMap) != Settings.WebMap.OFF || ClientWebMap.servingPort() != 0) {
			throw new AssertionError("The web map is on before the player turned it on");
		}
		int ground = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8));
		world.getServer().runCommand("tp @a 8.5 " + ground + " 8.5 -90 0");
		context.waitFor(mc -> mc.player.onGround() && mc.player.blockPosition().getX() == 8, 600);
		DioramaClientTest.settle(context, 40);

		// A taken port, on the address the web map listens on (the loopback address is ::1 on some loaders): it takes the next one.
		int port;
		try (ServerSocket taken = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
			int first = taken.getLocalPort();
			context.runOnClient(mc -> {
				Settings.get().webMapPort = first;
				Settings.get().webMap = Settings.WebMap.THIS_COMPUTER;
			});
			context.waitTicks(3);
			port = ClientWebMap.servingPort();
			// The last few ports there are have none after them to take.
			if (first < 65500 && (port <= first || port >= first + 10)) {
				throw new AssertionError("With port " + first + " taken, the web map is on " + port);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (port == 0) {
			throw new AssertionError("The web map isn't serving after it was turned on");
		}
		Diorama.LOGGER.info("web-map-client: serving on {}", port);
		// The address is in chat, to click.
		context.waitTicks(5);
		context.takeScreenshot("web-map-client-chat");

		HttpResponse<String> page = get(http, port, "/");
		if (page.statusCode() != 200 || !page.body().contains("Diorama web map") || !page.body().contains("player.json")) {
			throw new AssertionError("The page: " + page.statusCode());
		}
		// The tiles reach the disk a few seconds after they're drawn, and the overviews after that.
		String map = "";
		for (int i = 0; i < 200; i++) {
			map = get(http, port, "/map.json").body();
			if (map.contains("\"zoomLevels\":6")) {
				break;
			}
			context.waitTicks(10);
		}
		if (!map.contains("\"live\":true") || !map.contains("\"id\":\"minecraft:overworld\"") || !map.contains("\"zoomLevels\":6")) {
			throw new AssertionError("map.json: " + map.substring(0, Math.min(400, map.length())));
		}
		double[] before = player(http, port);
		double[] at = context.computeOnClient(mc -> new double[] {mc.player.getX(), mc.player.getY(), mc.player.getZ()});
		if (Math.abs(before[0] - at[0]) > 0.01 || Math.abs(before[2] - at[2]) > 0.01 || Math.abs(before[3] - at[0]) > 0.01
			|| Math.abs(before[4] - Projection.v(at[1], at[2])) > 0.01) {
			throw new AssertionError("player.json says " + java.util.Arrays.toString(before) + " for a player at " + java.util.Arrays.toString(at));
		}
		// Looking east: along the picture's x.
		if (Math.abs(before[5]) > 0.05) {
			throw new AssertionError("A player looking east has heading " + before[5]);
		}

		// The tile the player stands in, against the player's own map of it.
		int tileU = Math.floorDiv((int) Math.floor(before[3]), Projection.TILE_UNITS);
		int tileV = Math.floorDiv((int) Math.floor(before[4]), Projection.TILE_UNITS);
		if (!map.contains("[" + tileU + "," + tileV + ",") && !map.contains("," + tileU + "," + tileV + ",")) {
			throw new AssertionError("map.json doesn't list the player's tile " + tileU + "," + tileV);
		}
		int[] own = context.computeOnClient(mc -> MapSession.get().map().savedMap()).read(new TileKey(TileKey.SURFACE, tileU, tileV));
		Matcher named = Pattern.compile("\"folder\":\"(minecraft_overworld-[0-9a-f]+)\"").matcher(map);
		if (!named.find()) {
			throw new AssertionError("map.json names no folder for the overworld's map: " + map.substring(0, Math.min(400, map.length())));
		}
		String folder = "/" + named.group(1);
		HttpResponse<byte[]> tile = getBytes(http, port, folder + "/0/" + tileU + "_" + tileV + ".jpg?v=1");
		if (own == null || tile.statusCode() != 200 || !tile.headers().firstValue("Content-Type").orElse("").equals("image/jpeg")) {
			throw new AssertionError("The player's tile: " + tile.statusCode() + (own == null ? ", and the map has none" : ""));
		}
		try {
			Files.write(out.resolve("tile-" + tileU + "_" + tileV + ".jpg"), tile.body());
			BufferedImage web = ImageIO.read(new ByteArrayInputStream(tile.body()));
			int size = Projection.TILE_PIXELS;
			if (web == null || web.getWidth() != size || web.getHeight() != size) {
				throw new AssertionError("The player's tile isn't a " + size + " pixel picture");
			}
			long sum = 0;
			int known = 0;
			for (int i = 0; i < own.length; i++) {
				int c = own[i];
				if (c >>> 24 != 255) {
					continue;
				}
				int w = web.getRGB(i % size, i / size);
				sum += Math.max(Math.abs((c & 0xFF) - (w >> 16 & 0xFF)), Math.max(Math.abs((c >> 8 & 0xFF) - (w >> 8 & 0xFF)), Math.abs((c >> 16 & 0xFF) - (w & 0xFF))));
				known++;
			}
			double mean = known == 0 ? 0 : (double) sum / known;
			Diorama.LOGGER.info("web-map-client: tile {},{}: {} known pixels, {} off the player's map on average, {} bytes", tileU, tileV, known, mean, tile.body().length);
			// A JPEG of ground this busy is about 5 off; another tile, or the wrong colours, several times that.
			if (known < size * size / 10 || mean > 12) {
				throw new AssertionError("The served tile is " + mean + " off the player's map over " + known + " known pixels");
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		// Zoomed out, and nothing outside the map.
		if (getBytes(http, port, folder + "/3/" + Math.floorDiv(tileU, 8) + "_" + Math.floorDiv(tileV, 8) + ".jpg").statusCode() != 200) {
			throw new AssertionError("No zoomed-out tile over the player");
		}
		// The same tile asked for as another map's (another world, or the address from before these named the map).
		for (String path : new String[] {folder + "/0/9999_9999.jpg", "/minecraft_overworld/0/" + tileU + "_" + tileV + ".jpg", folder + "0/0/" + tileU + "_" + tileV + ".jpg",
			"/minecraft_the_nether/0/" + tileU + "_" + tileV + ".jpg", "/../config.json", folder + "/0/x.jpg", folder + "/" + (TileKey.MAX_LOD + 1) + "/0_0.jpg"}) {
			int code = getBytes(http, port, path).statusCode();
			if (code != 404) {
				throw new AssertionError(path + " answered " + code);
			}
		}
		// For this computer only, a request under another name is turned away: a page from elsewhere
		// that points its own name at 127.0.0.1.
		if (!status(port, "localhost:" + port, "/player.json").contains(" 200") || !status(port, "evil.example:" + port, "/player.json").contains(" 403")
			|| !status(port, "evil.example", "/map.json").contains(" 403")) {
			throw new AssertionError("Asked as localhost: " + status(port, "localhost:" + port, "/player.json") + "; as evil.example: " + status(port, "evil.example:" + port, "/player.json"));
		}
		int[] east = browser(port, out.resolve("page-before.png"));

		// The player moves: so does the place the page is given.
		int there = world.getServer().computeOnServer(server -> server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, 72, -40));
		world.getServer().runCommand("tp @a 72.5 " + there + " -39.5 180 0");
		context.waitFor(mc -> mc.player.blockPosition().getX() == 72, 600);
		context.waitTicks(5);
		double[] after = player(http, port);
		if (Math.abs(after[0] - 72.5) > 0.01 || Math.abs(after[2] + 39.5) > 0.01 || Math.abs(after[3] - before[3] - 64) > 0.01) {
			throw new AssertionError("After moving to 72.5, -39.5, player.json says " + java.util.Arrays.toString(after));
		}
		// Looking north: up the picture.
		if (Math.abs(after[5] + Math.PI / 2) > 0.05) {
			throw new AssertionError("A player looking north has heading " + after[5]);
		}
		DioramaClientTest.settle(context, 40);
		int[] north = browser(port, out.resolve("page-after.png"));
		// The page turned the arrow with the player: it's longer than it is wide, so lying along the
		// picture looking east and standing up looking north.
		if (east != null && north != null && (east[0] <= east[1] || north[1] <= north[0])) {
			throw new AssertionError("The page's arrow is " + east[0] + " by " + east[1] + " pixels for a player looking east and " + north[0] + " by " + north[1] + " looking north");
		}

		// For every device on the network: the same port, on every address.
		int served = port;
		context.runOnClient(mc -> {
			Settings.get().webMapPort = served;
			Settings.get().webMap = Settings.WebMap.NETWORK;
		});
		context.waitTicks(3);
		if (ClientWebMap.servingPort() != served || get(http, served, "/map.json").statusCode() != 200) {
			throw new AssertionError("The web map isn't serving on " + served + " for the network");
		}
		String network = ClientWebMap.address();
		Diorama.LOGGER.info("web-map-client: for the network at {}", network);
		if (network.contains("localhost")) {
			Diorama.LOGGER.warn("web-map-client: this machine has no network address, so the page wasn't asked for over one");
		} else {
			// At this machine's own address on its network, as another device there asks for it.
			if (send(http, network + "map.json").statusCode() != 200) {
				throw new AssertionError("For the network, " + network + " doesn't answer");
			}
			// And for this computer only, that address is closed again.
			context.runOnClient(mc -> Settings.get().webMap = Settings.WebMap.THIS_COMPUTER);
			context.waitTicks(3);
			if (ClientWebMap.servingPort() != served || get(http, served, "/map.json").statusCode() != 200) {
				throw new AssertionError("Back to this computer only, the web map isn't on " + served);
			}
			try {
				send(http, network + "map.json");
				throw new AssertionError("For this computer only, " + network + " still answers");
			} catch (UncheckedIOException e) {
				// Refused.
			}
		}
		// Off: nothing listens any more.
		context.runOnClient(mc -> Settings.get().webMap = Settings.WebMap.OFF);
		context.waitTicks(3);
		if (ClientWebMap.servingPort() != 0) {
			throw new AssertionError("The web map still serves after it was turned off");
		}
		try {
			get(http, served, "/");
			throw new AssertionError("Port " + served + " still answers after the web map was turned off");
		} catch (UncheckedIOException e) {
			// Refused.
		}
		context.runOnClient(mc -> Settings.get().webMapPort = Settings.DEFAULT_WEB_MAP_PORT);
	}

	/** x, y, z, u, v and heading from player.json. */
	private static double[] player(HttpClient http, int port) {
		String json = get(http, port, "/player.json").body();
		double[] values = new double[6];
		Matcher matcher = NUMBER.matcher(json);
		int found = 0;
		while (matcher.find()) {
			values[java.util.List.of("x", "y", "z", "u", "v", "heading").indexOf(matcher.group(1))] = Double.parseDouble(matcher.group(2));
			found++;
		}
		if (found != 6 || !json.contains("\"dimension\":\"minecraft:overworld\"")) {
			throw new AssertionError("player.json: " + json);
		}
		return values;
	}

	/** The status line a request with this Host header gets. */
	private static String status(int port, String host, String path) {
		try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
			socket.setSoTimeout(10_000);
			socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
			String line = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII)).readLine();
			return String.valueOf(line);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Photographs the page in Chromium, where the machine has one, and checks the picture: the map
	 * drawn over much of it, and the player's arrow in the middle, where Follow keeps it. Returns
	 * how wide and how tall the arrow's blue is, in pixels, or null on a machine with no Chromium.
	 */
	private static int @org.jspecify.annotations.Nullable [] browser(int port, Path file) {
		return photograph("http://127.0.0.1:" + port + "/", file) ? arrow(file) : null;
	}

	/**
	 * Photographs a page in Chromium into {@code file}; false on a machine with no Chromium. On a
	 * boat VM through the game test skill's page_photo.py, which waits until the page says its
	 * pictures are in. Elsewhere on Chromium's own made-up clock, which can run out while a picture
	 * is still being decoded and leave the map blank.
	 */
	static boolean photograph(String address, Path file) {
		Path log = file.resolveSibling(file.getFileName() + ".log");
		Path helper = Path.of(System.getProperty("user.home"), "diorama", ".claude", "skills", "boat-game-test", "page_photo.py");
		if (Files.isRegularFile(helper)) {
			try {
				Process process = new ProcessBuilder("python3", helper.toString(), address, file.toAbsolutePath().toString())
					.redirectErrorStream(true).redirectOutput(log.toFile()).start();
				if (!process.waitFor(180, TimeUnit.SECONDS)) {
					process.destroyForcibly();
					throw new AssertionError("page_photo.py didn't finish photographing " + address);
				}
				String said = Files.readString(log).strip();
				Diorama.LOGGER.info("web map: {} into {}: exit {}, {}", address, file, process.exitValue(), said);
				if (process.exitValue() == 3) {
					Diorama.LOGGER.warn("web map: no Chromium on this machine, so no picture of the page");
					return false;
				}
				if (process.exitValue() != 0) {
					// 4: the page never said its pictures were in, and the picture is of it still loading.
					throw new AssertionError("page_photo.py failed for " + address + " (" + process.exitValue() + "): " + said);
				}
				return true;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} catch (InterruptedException e) {
				throw new AssertionError(e);
			}
		}
		Path profile;
		try {
			profile = Files.createTempDirectory("diorama-web-map-browser");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		for (String name : new String[] {"chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "/snap/bin/chromium"}) {
			try {
				Process process = new ProcessBuilder(name, "--headless=new", "--no-sandbox", "--disable-gpu", "--hide-scrollbars", "--window-size=1280,800",
					"--virtual-time-budget=20000", "--user-data-dir=" + profile, "--screenshot=" + file.toAbsolutePath(), address)
					.redirectErrorStream(true).redirectOutput(log.toFile()).start();
				if (!process.waitFor(180, TimeUnit.SECONDS)) {
					process.destroyForcibly();
					throw new AssertionError(name + " didn't finish photographing the page");
				}
				Diorama.LOGGER.info("web map: {} photographed {} into {} (exit {})", name, address, file, process.exitValue());
				return true;
			} catch (IOException e) {
				// Not this one.
			} catch (InterruptedException e) {
				throw new AssertionError(e);
			}
		}
		Diorama.LOGGER.warn("web map: no Chromium on this machine, so no picture of the page");
		return false;
	}

	/** Checks a picture of the page, and returns how wide and how tall the arrow's blue is. */
	private static int[] arrow(Path file) {
		BufferedImage shot;
		try {
			shot = Files.exists(file) ? ImageIO.read(file.toFile()) : null;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (shot == null) {
			throw new AssertionError("Chromium left no picture of the page at " + file);
		}
		int drawn = 0;
		for (int y = 0; y < shot.getHeight(); y += 4) {
			for (int x = 0; x < shot.getWidth(); x += 4) {
				int rgb = shot.getRGB(x, y);
				// Not the page's background (#15171c) or a panel over it.
				if ((rgb >> 16 & 0xFF) > 0x40 || (rgb >> 8 & 0xFF) > 0x40 || (rgb & 0xFF) > 0x40) {
					drawn++;
				}
			}
		}
		int sampled = (shot.getWidth() / 4) * (shot.getHeight() / 4);
		// The arrow's own colour (#3fa9f5), which no ground has, around the middle of the page.
		int midX = shot.getWidth() / 2;
		int midY = shot.getHeight() / 2;
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		int blue = 0;
		for (int y = midY - 20; y <= midY + 20; y++) {
			for (int x = midX - 20; x <= midX + 20; x++) {
				int rgb = shot.getRGB(x, y);
				if (Math.abs((rgb >> 16 & 0xFF) - 0x3F) < 24 && Math.abs((rgb >> 8 & 0xFF) - 0xA9) < 24 && Math.abs((rgb & 0xFF) - 0xF5) < 24) {
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
					blue++;
				}
			}
		}
		Diorama.LOGGER.info("web-map-client: {}: map over {} of {} sampled pixels, {} arrow pixels in the middle", file.getFileName(), drawn, sampled, blue);
		if (drawn < sampled / 10 || blue < 40) {
			throw new AssertionError("The page in " + file + " shows the map over " + drawn + " of " + sampled + " sampled pixels and " + blue + " pixels of the player's arrow in the middle");
		}
		return new int[] {maxX - minX + 1, maxY - minY + 1};
	}

	private static HttpResponse<String> get(HttpClient http, int port, String path) {
		return send(http, "http://127.0.0.1:" + port + path);
	}

	private static HttpResponse<String> send(HttpClient http, String address) {
		try {
			return http.send(HttpRequest.newBuilder(URI.create(address)).timeout(java.time.Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private static HttpResponse<byte[]> getBytes(HttpClient http, int port, String path) {
		try {
			return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofByteArray());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}
}
