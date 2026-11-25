package dev.diorama.addon;

import dev.diorama.Diorama;
import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The addons other mods made with Diorama's API ({@code dev.diorama.api}). The loader's entrypoint
 * says how to find them ({@link #finder}): a Fabric entrypoint, or an annotation in the mod files'
 * scan on NeoForge and Forge. Once the game has started they are made and told they're ready. What
 * they hide stays hidden until they close it or fail; nothing of it is saved.
 */
public final class Addons {
	/** The API version these classes are, written into api.properties by api/build.gradle.kts. */
	static final String API_VERSION = readApiVersion();

	/**
	 * One addon a loader found: the mod ID the loader knows the jar by, and how to make the addon,
	 * which loads its class.
	 */
	public record Found(String modId, Callable<DioramaAddon> create) {
	}

	private static Supplier<List<Found>> finder = List::of;
	private static boolean started;

	private Addons() {
	}

	private static String readApiVersion() {
		try (InputStream in = DioramaApi.class.getResourceAsStream("api.properties")) {
			if (in != null) {
				Properties properties = new Properties();
				properties.load(in);
				return properties.getProperty("version", "unknown");
			}
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't read the addon API's version", e);
		}
		return "unknown";
	}

	/**
	 * A class marked {@code @DioramaEntrypoint} in a mod file's scan (NeoForge and Forge), tied to
	 * the mod the file declares. A file declaring several mods can't say which one the class is for,
	 * so it is skipped; null then. Loads nothing until the addon is made.
	 */
	public static @Nullable Found scanned(List<String> fileMods, String className) {
		if (fileMods.size() != 1) {
			Diorama.LOGGER.warn("Skipped Diorama addon {}: its jar declares {} mods ({}), so it can't be told which one it belongs to", className, fileMods.size(), String.join(", ", fileMods));
			return null;
		}
		return new Found(fileMods.get(0), () -> {
			// Loaded without running any of its code, so a class marked by mistake is turned down
			// before its static initialiser or constructor can do anything.
			Class<?> type = Class.forName(className, false, Addons.class.getClassLoader());
			if (!DioramaAddon.class.isAssignableFrom(type)) {
				throw new ClassCastException(className + " doesn't implement " + DioramaAddon.class.getName());
			}
			return (DioramaAddon) type.getDeclaredConstructor().newInstance();
		});
	}

	/** How this loader finds addons; set once, by its entrypoint. */
	public static void finder(Supplier<List<Found>> finder) {
		Addons.finder = finder;
	}

	/**
	 * Called every client tick. The first tick after the game has finished loading (its loading
	 * overlay gone, so every mod is set up and the title screen is up) makes the addons and calls
	 * their onReady.
	 */
	public static void tick(Minecraft mc) {
		if (!started && mc.gui.overlay() == null) {
			started = true;
			start();
		}
	}

	private static void start() {
		List<Found> found;
		try {
			found = finder.get();
		} catch (RuntimeException | LinkageError e) {
			// Diorama's own code or the loader's, not an addon's.
			Diorama.LOGGER.error("Diorama's addon discovery failed, so no addon loads this session", e);
			return;
		}
		for (Found one : found) {
			Addon addon = new Addon(one.modId());
			DioramaAddon made = addon.call("its entrypoint", one.create());
			if (made == null) {
				continue;
			}
			addon.call("onReady", () -> {
				made.onReady(addon);
				return Boolean.TRUE;
			});
			if (!addon.failed()) {
				Diorama.LOGGER.info("Diorama addon {} ready ({}, API {})", addon.id(), made.getClass().getName(), API_VERSION);
			}
		}
	}
}
