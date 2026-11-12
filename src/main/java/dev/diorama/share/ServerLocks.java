package dev.diorama.share;

import dev.diorama.Diorama;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Map settings a server decides for every player with Diorama, from
 * {@code config/diorama-server.properties}, beside the shared map's. The player's own settings stay
 * as they saved them; the server's apply while they're on it. Xaero's Minimap lets a server do the
 * same with its enforced config ({@code cave_mode_allowed}, {@code display_radar}), and these keep
 * its names where they mean the same.
 *
 * @param caveMapsAllowed false: no cave slices, only the view from the sky
 * @param caveFogOfWar true: cave slices show only blocks the player has seen, as "Fog of war always" does
 * @param radarAllowed false: no players or mobs on the minimap or the map
 * @param mapOffDimensions the IDs of the dimensions with no map at all, as {@code minecraft:the_nether}
 */
public record ServerLocks(boolean caveMapsAllowed, boolean caveFogOfWar, boolean radarAllowed, Set<String> mapOffDimensions) {
	public static final ServerLocks NONE = new ServerLocks(true, false, true, Set.of());

	private static final String CAVE_MAPS = "cave-maps-allowed";
	private static final String CAVE_FOG = "cave-fog-of-war";
	private static final String RADAR = "radar-allowed";
	private static final String MAP_OFF = "map-off-dimensions";

	/** Each setting: its key, what it means, its default. */
	private static final String[][] SETTINGS = {
		{CAVE_MAPS, """
			# Map settings this server decides for players with Diorama. Their own settings come back
			# when they leave. false: no cave maps underground, only the view from the sky; in the
			# Nether, only what each player has seen under the roof. The web map can't know what
			# each player has seen, so it leaves out the Nether while this is false.
			""", "true"},
		{CAVE_FOG, """
			# true: cave maps show only the blocks each player has seen (fog of war), and players
			# can't turn it off. The web map leaves out the Nether while this is true.
			""", "false"},
		{RADAR, """
			# false: no players or mobs on the minimap or the map.
			""", "true"},
		{MAP_OFF, """
			# Dimensions where Diorama is off: no minimap, no map, no waypoints in the world, and
			# nothing is mapped there; the web map leaves them out too. Dimension IDs separated by
			# commas, for example minecraft:the_nether,somemod:maze. Empty: the map works in every
			# dimension.
			""", ""},
	};

	/** Whether this locks anything at all. */
	public boolean any() {
		return !this.equals(NONE);
	}

	/**
	 * Reads the locks from {@code configFolder}. A dedicated server adds the ones the file lacks, with
	 * what each means; singleplayer only reads a file a modpack put there, and writes nothing.
	 */
	public static ServerLocks load(Path configFolder, boolean addMissing) {
		Path file = configFolder.resolve(ShareSettings.FILE);
		if (!Files.exists(file) && !addMissing) {
			return NONE;
		}
		Properties properties = new Properties();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				properties.load(reader);
			} catch (IOException | IllegalArgumentException e) {
				Diorama.LOGGER.warn("Couldn't read the map settings locks in {}; locking none", file, e);
				return NONE;
			}
		}
		if (addMissing) {
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
					Diorama.LOGGER.warn("Couldn't add the map settings locks to {}", file, e);
				}
			}
		}
		return of(properties, file.toString());
	}

	private static ServerLocks of(Properties properties, String where) {
		return new ServerLocks(
			flag(properties, CAVE_MAPS, NONE.caveMapsAllowed, where),
			flag(properties, CAVE_FOG, NONE.caveFogOfWar, where),
			flag(properties, RADAR, NONE.radarAllowed, where),
			dimensions(properties.getProperty(MAP_OFF, "")));
	}

	/**
	 * The dimension IDs in a list separated by commas, as Xaero's writes its lists of dimensions.
	 * An ID without a namespace is Minecraft's, as the game reads it.
	 */
	public static Set<String> dimensions(String list) {
		Set<String> dimensions = new LinkedHashSet<>();
		for (String part : list.split(",")) {
			String id = part.trim().toLowerCase(java.util.Locale.ROOT);
			if (!id.isEmpty()) {
				dimensions.add(id.indexOf(':') < 0 ? "minecraft:" + id : id);
			}
		}
		return java.util.Collections.unmodifiableSet(dimensions);
	}

	private static boolean flag(Properties properties, String key, boolean otherwise, String where) {
		String text = properties.getProperty(key);
		if (text == null) {
			return otherwise;
		}
		text = text.trim();
		if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
			return text.equalsIgnoreCase("true");
		}
		Diorama.LOGGER.warn("{} in {} is {}, which is neither true nor false; using {}", key, where, text, otherwise);
		return otherwise;
	}

	/**
	 * As the server sends them: {@link ShareProtocol#LOCKS}, then the locks as the file has them, in
	 * one string. Diorama 0.9.0 reads that string as the dimension of a shared map message, finds it
	 * isn't the one it's in, and drops the message; so servers can send it to every Diorama.
	 */
	byte[] encode() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(ShareProtocol.LOCKS);
			out.writeUTF(CAVE_MAPS + "=" + this.caveMapsAllowed + "\n" + CAVE_FOG + "=" + this.caveFogOfWar + "\n" + RADAR + "=" + this.radarAllowed + "\n"
				+ MAP_OFF + "=" + String.join(",", this.mapOffDimensions) + "\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}

	/** The locks in a message from the server, or null if it's cut short. Keys this version doesn't know are left alone. */
	public static @Nullable ServerLocks decode(byte[] data) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			if (in.readUnsignedByte() != ShareProtocol.LOCKS) {
				return null;
			}
			Properties properties = new Properties();
			properties.load(new StringReader(in.readUTF()));
			return of(properties, "the server's message");
		} catch (IOException | IllegalArgumentException e) {
			return null;
		}
	}
}
