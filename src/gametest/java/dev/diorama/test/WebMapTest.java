package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.BlockLooks;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
import java.util.Locale;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;
//? if >=1.21.4 && fabric {
import dev.diorama.web.WebMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.loader.api.FabricLoader;
//?}

/**
 * The web map on a dedicated server, with a player exploring it (opt-in: DIORAMA_TESTS=web-map).
 * On a real server (the loader's own install with the release jar: {@code --env
 * DIORAMA_REAL_SERVER=1}, any node), or else the test API's server in the client's process
 * (Fabric from 1.21.4). The player stands at a few places; once the server has saved them by itself,
 * the web map draws that land, serves the page, the map file and the pictures over HTTP, and
 * leaves land nobody visited undrawn. Each picture is compared with the player's own map of the
 * same tile, drawn in the client's colours: they may differ only as much as a JPEG of the player's
 * own tile does. Writes both side by side to {@code screenshots/web-map/}.
 *
 * <p>{@code DIORAMA_WEB_MAP_FORMAT=webp} runs it with lossless WebP pictures: then they may differ
 * from the player's tile only as much as the colours do, and each zoomed-out tile over the land
 * must be exactly the average of the four under it.
 */
final class WebMapTest {
	/**
	 * Land the server generates but nobody visits: force-loaded, so it's finished and saved with no
	 * time spent near it. Chunk 100, 100, in region 3, 3, which nothing else reaches.
	 */
	private static final int UNVISITED = 1600;
	/** Where the player stands, x and z; spawn is near the first. */
	private static final int[][] SPOTS = {{0, 0}, {160, 40}, {-120, 200}};
	/** The web map waits up to 5 minutes between passes; the test waits for the one after the server saved what was explored. */
	private static final int PASS_WAIT_TICKS = 20 * 60 * 10;

	/** The picture format under test: {@code jpeg} unless DIORAMA_WEB_MAP_FORMAT says otherwise. */
	private static final boolean WEBP = "webp".equalsIgnoreCase(System.getenv("DIORAMA_WEB_MAP_FORMAT"));
	private static final String EXT = WEBP ? ".webp" : ".jpg";

	private WebMapTest() {
	}

	static void run(ClientGameTestContext context) {
		int port = freePort();
		String settings = "web-map=true\nweb-map-port=" + port + "\nweb-map-address=127.0.0.1\nshare-land=visited\nweb-map-format=" + (WEBP ? "webp" : "jpeg") + "\n";
		if (System.getenv("DIORAMA_REAL_SERVER") != null) {
			Path config = Path.of(System.getProperty("user.home"), "real-servers", System.getenv("DIORAMA_NODE"), "config", "diorama-server.properties");
			// DIORAMA_WEB_MAP_OFF: the control, the same exploring and saving with the web map off.
			boolean off = System.getenv("DIORAMA_WEB_MAP_OFF") != null;
			write(config, off ? "web-map=false\n" : settings);
			try (RealServer server = RealServer.start(true)) {
				server.join(context);
				try {
					if (off) {
						command(context, server::command);
					} else {
						explore(context, port, server::command, server.folder);
						// The operators' command, from the server's console.
						server.command("diorama-web status");
						context.waitTicks(40);
						if (!server.console().contains("Web map: ")) {
							throw new AssertionError("/diorama-web status didn't answer in the server's console");
						}
					}
				} finally {
					// What the server said about itself: overloaded, players dropped, the web map's passes.
					String said = server.console().lines()
						.filter(line -> line.contains("keep up") || line.contains("Web map") || line.contains("web map") || line.contains("Exception") || line.contains("lost connection")
							|| line.contains("Saved the game") || line.contains("Saving"))
						.limit(60).collect(java.util.stream.Collectors.joining(System.lineSeparator()));
					Diorama.LOGGER.info("The real server's console:{}{}", System.lineSeparator(), said);
				}
				if (context.computeOnClient(mc -> mc.level != null)) {
					RealServer.leave(context);
				} else {
					throw new AssertionError("The player was dropped from the real server");
				}
			}
			return;
		}
		//? if >=1.21.4 && fabric {
		Path config = FabricLoader.getInstance().getConfigDir().resolve("diorama-server.properties");
		write(config, settings);
		try (TestDedicatedServerContext server = context.worldBuilder()
			.setUseConsistentSettings(false)
			.adjustSettings(s -> s.setSeed("diorama"))
			.createServer(DioramaClientTest.serverProperties())) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				if (WebMap.get() == null) {
					throw new AssertionError("The web map didn't start on the dedicated server");
				}
				explore(context, port, server::runCommand, null);
			}
		} finally {
			try {
				Files.deleteIfExists(config);
			} catch (IOException ignored) {
			}
		}
		//?} else {
		/*throw new AssertionError("web-map needs a dedicated server: run it with --env DIORAMA_REAL_SERVER=1");
		*///?}
	}

	/** The control run: the player explores and the server saves, as in {@link #explore}, with no web map. */
	private static void command(ClientGameTestContext context, Consumer<String> command) {
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
		for (int[] spot : SPOTS) {
			command.accept("tp @a " + spot[0] + " 200 " + spot[1]);
			context.waitTicks(200);
			DioramaClientTest.settle(context, 60);
		}
		context.waitTicks(20 * 240);
	}

	private static void write(Path file, String text) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The player stands at each spot; once the server has saved, waits for the web map to draw that
	 * land, and checks it over HTTP against the player's own map.
	 */
	private static void explore(ClientGameTestContext context, int port, Consumer<String> command, @Nullable Path serverFolder) {
		Path out = Path.of("screenshots", "web-map");
		write(out.resolve("README.txt"), "Each picture: the player's own map of a tile, left, and the web map's, right.\n");
		context.waitFor(mc -> MapSession.get() != null && MapSession.get().map().chunkCount() > 50, 2400);
		HttpClient http = HttpClient.newHttpClient();
		command.accept("forceload add " + UNVISITED + " " + UNVISITED);
		String[] want = new String[SPOTS.length];
		for (int i = 0; i < SPOTS.length; i++) {
			int[] spot = SPOTS[i];
			command.accept("tp @a " + spot[0] + " 200 " + spot[1]);
			// Falls to the ground, the chunks around count the time, the client's map draws them.
			context.waitTicks(200);
			DioramaClientTest.settle(context, 60);
			// The ground under the spot, once the client has its chunk: before, it reads as the world's bottom.
			context.waitFor(mc -> mc.level != null && mc.level.hasChunk(spot[0] >> 4, spot[1] >> 4)
				&& mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, spot[0], spot[1]) > mc.level.getMinY(), 1200);
			int y = context.computeOnClient(mc -> mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, spot[0], spot[1]));
			want[i] = Math.floorDiv(spot[0], Projection.TILE_UNITS) + "_" + (int) Math.floor(Projection.v(y, spot[1]) / Projection.TILE_UNITS);
		}
		// The player's own map of each tile, now, while they're on the server.
		List<int[]> clients = new ArrayList<>();
		for (String name : want) {
			int sep = name.indexOf('_');
			TileKey key = new TileKey(TileKey.SURFACE, Integer.parseInt(name.substring(0, sep)), Integer.parseInt(name.substring(sep + 1)));
			clients.add(context.computeOnClient(mc -> TileBaker.reference(key, MapSession.get().map(), BlockLooks.get(), false, null, null)));
		}
		// No save command: the server saves the chunks by itself, as it does while players play, and
		// the web map finds them saved. (A save-all flush right after exploring stalls the server on a
		// test VM for close to a minute, web map or not, and the player times out.)
		long started = System.currentTimeMillis();
		for (String name : want) {
			String path = "/minecraft_overworld/0/" + name + EXT;
			try {
				context.waitFor(mc -> status(http, port, path) == 200, PASS_WAIT_TICKS);
			} catch (AssertionError e) {
				String json = status(http, port, "/map.json") == 200 ? get(http, port, "/map.json").body() : "none";
				throw new AssertionError("Tile " + name + " of the explored land wasn't drawn; tiles wanted " + String.join(" ", want) + "; map.json " + json, e);
			}
		}
		Diorama.LOGGER.info("Web map: the explored land was drawn {} s after the player left the last spot", (System.currentTimeMillis() - started) / 1000);

		String page = get(http, port, "/").body();
		if (!page.contains("<canvas")) {
			throw new AssertionError("The web map's page isn't served: " + page.substring(0, Math.min(200, page.length())));
		}
		String json = get(http, port, "/map.json").body();
		if (!json.contains("\"minecraft:overworld\"")) {
			throw new AssertionError("map.json has no overworld: " + json);
		}
		if (!json.contains("\"ext\":\"" + EXT.substring(1) + "\"")) {
			throw new AssertionError("map.json doesn't name the pictures' format " + EXT + ": " + json.substring(0, Math.min(300, json.length())));
		}
		String served = getBytes(http, port, "/minecraft_overworld/0/" + want[0] + EXT + "?v=1").headers().firstValue("Content-Type").orElse("none");
		String cache = getBytes(http, port, "/minecraft_overworld/0/" + want[0] + EXT + "?v=1").headers().firstValue("Cache-Control").orElse("none");
		if (!served.equals(WEBP ? "image/webp" : "image/jpeg") || !cache.contains("immutable")) {
			throw new AssertionError("A picture is served as " + served + ", cached " + cache);
		}
		Diorama.LOGGER.info("Web map pictures: {} served as {}, {}", EXT, served, cache);
		List<String> lines = new ArrayList<>();
		int compared = 0;
		for (int i = 0; i < want.length; i++) {
			String name = want[i];
			int sep = name.indexOf('_');
			int u = Integer.parseInt(name.substring(0, sep));
			int v = Integer.parseInt(name.substring(sep + 1));
			BufferedImage web;
			try {
				web = picture(getBytes(http, port, "/minecraft_overworld/0/" + name + EXT).body());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			int[] client = clients.get(i);
			double[] diff = compare(client, web);
			// The same tile of the player's map put through the same JPEG: what the format costs alone.
			// Lossless costs nothing: the player's tile as it is.
			double[] jpeg = compare(client, WEBP ? opaque(client) : jpeg(client), web);
			lines.add(String.format(Locale.ROOT, "tile %d,%d: %d pixels compared, mean difference %.2f/255 (95th percentile %.0f), JPEG alone %.2f/255 (%.0f)",
				u, v, (int) diff[0], diff[1], diff[2], jpeg[1], jpeg[2]));
			sideBySide(out.resolve("tile-" + name + ".png"), client, web);
			if (diff[0] > 10_000) {
				compared++;
				if (diff[1] > jpeg[1] + 2) {
					throw new AssertionError("The web map's tile " + name + " differs from the player's map more than " + (WEBP ? "the colours" : "JPEG") + " explain: " + lines.get(lines.size() - 1));
				}
			}
		}
		Diorama.LOGGER.info("Web map against the player's map: {}", lines);
		if (compared == 0) {
			throw new AssertionError("No tile had enough pixels drawn on both maps to compare: " + lines);
		}
		if (WEBP) {
			// The zoomed-out tiles are made at the end of the pass, after the tiles: tried until they are, up to 5 minutes.
			for (int attempt = 0; ; attempt++) {
				try {
					zoomedOutExact(http, port, want);
					break;
				} catch (AssertionError e) {
					if (attempt >= 30) {
						throw e;
					}
					Diorama.LOGGER.info("Web map zoomed-out check, try {}: {}", attempt + 1, e.getMessage());
					context.waitTicks(200);
				}
			}
		}
		// The land the server made but nobody visited: saved, and not drawn.
		if (serverFolder != null) {
			boolean saved = Files.exists(serverFolder.resolve("world/region/r.3.3.mca")) || Files.exists(serverFolder.resolve("world/dimensions/minecraft/overworld/region/r.3.3.mca"));
			if (!saved) {
				throw new AssertionError("The unvisited land isn't saved yet, so nothing shows it stays off the web map");
			}
		}
		int u = Math.floorDiv(UNVISITED, Projection.TILE_UNITS);
		for (int y = -64; y <= 320; y += 32) {
			String path = "/minecraft_overworld/0/" + u + "_" + (int) Math.floor(Projection.v(y, UNVISITED) / Projection.TILE_UNITS) + EXT;
			int far = status(http, port, path);
			if (far != 404) {
				throw new AssertionError("Land nobody visited is on the web map: " + path + " (" + far + ")");
			}
		}
		// Nothing outside the folder: the server's own operator list is two folders up.
		if (serverFolder != null && !Files.exists(serverFolder.resolve("ops.json"))) {
			throw new AssertionError("No ops.json to try reaching");
		}
		int outside = status(http, port, "/../../ops.json");
		if (outside != 404) {
			throw new AssertionError("The web server serves outside its folder (" + outside + ")");
		}
	}

	/** A served picture, JPEG or WebP. */
	private static BufferedImage picture(byte[] bytes) throws IOException {
		if (!WEBP) {
			return ImageIO.read(new ByteArrayInputStream(bytes));
		}
		dev.diorama.web.webp.Vp8lDecoder.Picture picture = dev.diorama.web.webp.Vp8lDecoder.decode(bytes);
		BufferedImage image = new BufferedImage(picture.width(), picture.height(), BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, picture.width(), picture.height(), picture.argb(), 0, picture.width());
		return image;
	}

	/**
	 * Lossless means the zoomed-out tiles are exact: each one over the explored land, as served,
	 * is the rounded average of the four under it, as served (the background where one is missing).
	 */
	private static void zoomedOutExact(HttpClient http, int port, String[] want) {
		int size = Projection.TILE_PIXELS;
		int checked = 0;
		java.util.Set<String> parents = new java.util.TreeSet<>();
		for (String name : want) {
			int sep = name.indexOf('_');
			parents.add(Math.floorDiv(Integer.parseInt(name.substring(0, sep)), 2) + "_" + Math.floorDiv(Integer.parseInt(name.substring(sep + 1)), 2));
		}
		try {
			for (String parent : parents) {
				String path = "/minecraft_overworld/1/" + parent + EXT;
				if (status(http, port, path) != 200) {
					continue;
				}
				BufferedImage out = picture(getBytes(http, port, path).body());
				int sep = parent.indexOf('_');
				int pu = Integer.parseInt(parent.substring(0, sep));
				int pv = Integer.parseInt(parent.substring(sep + 1));
				int wrong = 0;
				for (int i = 0; i < 4; i++) {
					String child = "/minecraft_overworld/0/" + (pu * 2 + (i & 1)) + "_" + (pv * 2 + (i >> 1)) + EXT;
					BufferedImage in = status(http, port, child) == 200 ? picture(getBytes(http, port, child).body()) : null;
					for (int y = 0; y < size / 2; y++) {
						for (int x = 0; x < size / 2; x++) {
							int expected = 0x15171C;
							if (in != null) {
								int a = in.getRGB(2 * x, 2 * y);
								int b = in.getRGB(2 * x + 1, 2 * y);
								int c = in.getRGB(2 * x, 2 * y + 1);
								int d = in.getRGB(2 * x + 1, 2 * y + 1);
								expected = 0;
								for (int shift = 0; shift <= 16; shift += 8) {
									int sum = (a >> shift & 0xFF) + (b >> shift & 0xFF) + (c >> shift & 0xFF) + (d >> shift & 0xFF);
									expected |= ((sum + 2) >> 2) << shift;
								}
							}
							if ((out.getRGB((i & 1) * size / 2 + x, (i >> 1) * size / 2 + y) & 0xFFFFFF) != expected) {
								wrong++;
							}
						}
					}
				}
				if (wrong > 0) {
					throw new AssertionError("Zoomed-out tile " + parent + " differs from the average of the four under it at " + wrong + " pixels");
				}
				checked++;
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		Diorama.LOGGER.info("Web map: {} zoomed-out tiles over the explored land are exactly the average of the tiles under them", checked);
		if (checked == 0) {
			throw new AssertionError("No zoomed-out tile over the explored land to check");
		}
	}

	/** The tracer's pixels over the background, as they are. */
	private static BufferedImage opaque(int[] client) {
		int size = Projection.TILE_PIXELS;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < client.length; i++) {
			int p = client[i];
			int a = p >>> 24;
			img.setRGB(i % size, i / size, ((p & 0xFF) * a / 255 + 0x15 * (255 - a) / 255) << 16 | ((p >> 8 & 0xFF) * a / 255 + 0x17 * (255 - a) / 255) << 8
				| (p >> 16 & 0xFF) * a / 255 + 0x1C * (255 - a) / 255);
		}
		return img;
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

	/** Pixels this close to where the web tile's land ends are left out: past it, its rays have no neighbours for shadows, light or tint blending. */
	private static final int EDGE = 8;

	/**
	 * {pixels compared, mean channel difference, 95th percentile}, over pixels fully drawn on the
	 * player's map and at least {@link #EDGE} pixels inside the land the web tile drew.
	 */
	private static double[] compare(int[] client, BufferedImage web) {
		return compare(client, web, web);
	}

	/** {@link #compare} against {@code web}, leaving out what {@code mask}'s background leaves out. */
	private static double[] compare(int[] client, BufferedImage web, BufferedImage mask) {
		int size = Projection.TILE_PIXELS;
		boolean[] background = new boolean[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int w = mask.getRGB(x, y);
				background[y * size + x] = Math.abs((w >> 16 & 0xFF) - 0x15) + Math.abs((w >> 8 & 0xFF) - 0x17) + Math.abs((w & 0xFF) - 0x1C) < 12;
			}
		}
		// Grows the background by EDGE pixels, one step at a time.
		for (int step = 0; step < EDGE; step++) {
			boolean[] grown = background.clone();
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					int i = y * size + x;
					if (!background[i] && (x > 0 && background[i - 1] || x < size - 1 && background[i + 1] || y > 0 && background[i - size] || y < size - 1 && background[i + size])) {
						grown[i] = true;
					}
				}
			}
			background = grown;
		}
		int[] histogram = new int[256];
		long sum = 0;
		int n = 0;
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int c = client[y * size + x];
				if (c >>> 24 != 255 || background[y * size + x]) {
					continue;
				}
				int w = web.getRGB(x, y);
				int d = Math.max(Math.abs((c & 0xFF) - (w >> 16 & 0xFF)), Math.max(Math.abs((c >> 8 & 0xFF) - (w >> 8 & 0xFF)), Math.abs((c >> 16 & 0xFF) - (w & 0xFF))));
				histogram[d]++;
				sum += d;
				n++;
			}
		}
		int p95 = 0;
		for (int seen = 0; p95 < 255 && seen < n * 0.95; p95++) {
			seen += histogram[p95];
		}
		return new double[] {n, n == 0 ? 0 : (double) sum / n, p95};
	}

	/** The tracer's pixels over the background, through a JPEG at the web map's quality. */
	private static BufferedImage jpeg(int[] client) {
		int size = Projection.TILE_PIXELS;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < client.length; i++) {
			int p = client[i];
			int a = p >>> 24;
			img.setRGB(i % size, i / size, ((p & 0xFF) * a / 255 + 0x15 * (255 - a) / 255) << 16 | ((p >> 8 & 0xFF) * a / 255 + 0x17 * (255 - a) / 255) << 8
				| (p >> 16 & 0xFF) * a / 255 + 0x1C * (255 - a) / 255);
		}
		try {
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
			try (javax.imageio.stream.ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
				writer.setOutput(stream);
				javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
				param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
				param.setCompressionQuality(0.85F);
				writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
			}
			writer.dispose();
			return ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void sideBySide(Path file, int[] client, BufferedImage web) {
		int size = Projection.TILE_PIXELS;
		BufferedImage img = new BufferedImage(size * 2 + 8, size, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < client.length; i++) {
			int p = client[i];
			int a = p >>> 24;
			int r = (p & 0xFF) * a / 255 + 0x15 * (255 - a) / 255;
			int g = (p >> 8 & 0xFF) * a / 255 + 0x17 * (255 - a) / 255;
			int b = (p >> 16 & 0xFF) * a / 255 + 0x1C * (255 - a) / 255;
			img.setRGB(i % size, i / size, r << 16 | g << 8 | b);
		}
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				img.setRGB(size + 8 + x, y, web.getRGB(x, y));
			}
		}
		try {
			ImageIO.write(img, "png", file.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static HttpResponse<String> get(HttpClient http, int port, String path) {
		try {
			return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
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

	private static int freePort() {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
