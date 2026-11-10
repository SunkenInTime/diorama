package dev.diorama.web;

import dev.diorama.Diorama;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * The web map's settings, from {@code config/diorama-server.properties}, beside the shared map's.
 *
 * @param enabled whether the server draws the web map at all (off unless the owner turns it on)
 * @param port the built-in web server's port; 0 for none, when another web server serves the folder
 * @param address the address the built-in web server listens on; empty for every address
 * @param folder where the tiles go; null for {@code diorama-web} in the world folder
 * @param everyChunk whether every finished chunk is drawn, rather than only land players spent time near
 * @param format the pictures' format: JPEG, or lossless WebP (experimental)
 */
public record WebSettings(boolean enabled, int port, String address, @Nullable Path folder, boolean everyChunk, TileFormat format) {
	public static final String FILE = "diorama-server.properties";
	public static final int DEFAULT_PORT = 8100;
	public static final WebSettings DEFAULTS = new WebSettings(false, DEFAULT_PORT, "", null, false, TileFormat.JPEG);

	/** Reads the settings from {@code configFolder}, adding the web map's to the file if it lacks them. */
	public static WebSettings load(Path configFolder) {
		Path file = configFolder.resolve(FILE);
		Properties properties = new Properties();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				properties.load(reader);
			} catch (IOException | IllegalArgumentException e) {
				Diorama.LOGGER.warn("Couldn't read the web map settings in {}; the web map stays off", file, e);
				return DEFAULTS;
			}
		}
		// The web map's settings the file lacks, with what each means; ones the owner set stay.
		StringBuilder missing = new StringBuilder();
		for (String[] setting : SETTINGS) {
			if (!properties.containsKey(setting[0])) {
				missing.append('\n').append(setting[1]).append(setting[0]).append('=').append(setting[2]).append('\n');
			}
		}
		if (!missing.isEmpty()) {
			try {
				Files.createDirectories(configFolder);
				Files.writeString(file, (Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "") + missing, StandardCharsets.UTF_8);
			} catch (IOException e) {
				Diorama.LOGGER.warn("Couldn't add the web map settings to {}", file, e);
			}
		}
		if (!properties.containsKey("web-map")) {
			return DEFAULTS;
		}
		boolean enabled = properties.getProperty("web-map", "false").trim().equalsIgnoreCase("true");
		int port = DEFAULT_PORT;
		String portText = properties.getProperty("web-map-port", String.valueOf(DEFAULT_PORT)).trim();
		try {
			port = Integer.parseInt(portText);
			if (port < 0 || port > 65535) {
				throw new NumberFormatException();
			}
		} catch (NumberFormatException e) {
			Diorama.LOGGER.warn("web-map-port in {} is {}, which isn't a port; using {}", file, portText, DEFAULT_PORT);
			port = DEFAULT_PORT;
		}
		String address = properties.getProperty("web-map-address", "").trim();
		String folderText = properties.getProperty("web-map-folder", "").trim();
		Path folder = folderText.isEmpty() ? null : Path.of(folderText);
		boolean everyChunk = properties.getProperty("share-land", "visited").trim().toLowerCase(Locale.ROOT).equals("generated");
		String formatText = properties.getProperty("web-map-format", TileFormat.JPEG.setting);
		TileFormat format = TileFormat.named(formatText);
		if (format == null) {
			Diorama.LOGGER.warn("web-map-format in {} is {}, which is neither jpeg nor webp; using jpeg", file, formatText.trim());
			format = TileFormat.JPEG;
		}
		return new WebSettings(enabled, port, address, folder, everyChunk, format);
	}

	/** Each of the web map's settings: its key, what it means, its default. */
	private static final String[][] SETTINGS = {
		{"web-map", """
			# The web map: this server draws its map, in vanilla colours, for anyone to see in a browser.
			# Off unless set to true. It covers the same land as the shared map (share-land).
			""", "false"},
		{"web-map-port", """
			# The port of the web map's own web server, at http://<this server>:<port>/. Set it to 0
			# to only write the map's files to web-map-folder, for a web server of your own to serve.
			""", Integer.toString(DEFAULT_PORT)},
		{"web-map-address", """
			# The address the web map's server listens on: empty for every address, or 127.0.0.1 to
			# reach it only through a web server of your own on this machine.
			""", ""},
		{"web-map-folder", """
			# Where the web map's files go. Empty: diorama-web in the world folder.
			""", ""},
		{"web-map-format", """
			# The pictures' format: jpeg, or webp (experimental) for every pixel exactly as drawn, at
			# more than twice the disk. Changing it draws the whole map again in the new format.
			""", "jpeg"},
	};
}
