package dev.diorama.structure;

import dev.diorama.MapSession;
import dev.diorama.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.jspecify.annotations.Nullable;

/**
 * The seed structures are found from: the one the player entered for this world, kept in the
 * settings by world, else the world's own in singleplayer, else none.
 */
public final class StructureSeed {
	private StructureSeed() {
	}

	/**
	 * Reads a seed as the game's world creation screen does: a number is the seed, any other text
	 * is a seed made from it, and nothing at all is no seed.
	 */
	public static @Nullable Long parse(String text) {
		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		try {
			return Long.parseLong(trimmed);
		} catch (NumberFormatException e) {
			return (long) trimmed.hashCode();
		}
	}

	private static @Nullable String worldKey() {
		MapSession session = MapSession.get();
		return session == null ? null : session.worldKey();
	}

	/** The seed text the player entered for this world, or empty. */
	public static String entered() {
		String key = worldKey();
		return key == null ? "" : Settings.get().worldSeeds.getOrDefault(key, "");
	}

	/** Keeps the seed text for this world, or forgets it if empty. Does nothing outside a world. */
	public static void remember(String text) {
		String key = worldKey();
		if (key == null) {
			return;
		}
		Settings settings = Settings.get();
		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			settings.worldSeeds.remove(key);
		} else {
			settings.worldSeeds.put(key, trimmed);
		}
		settings.save();
	}

	/** The seed to use, or null if there is none. */
	public static @Nullable Long current() {
		Long typed = parse(entered());
		if (typed != null) {
			return typed;
		}
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		return server == null ? null : server.overworld().getSeed();
	}

	/** Whether the seed in use is the world's own rather than one the player entered. */
	public static boolean isWorlds() {
		return parse(entered()) == null && current() != null;
	}
}
