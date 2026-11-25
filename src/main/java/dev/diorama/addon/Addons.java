package dev.diorama.addon;

import dev.diorama.Diorama;
import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.Hud;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The addons other mods made with Diorama's API ({@code dev.diorama.api}). The loader's entrypoint
 * says how to find them ({@link #finder}): a Fabric entrypoint, or an annotation in the mod files'
 * scan on NeoForge and Forge. Once the game has started they are made and told they're ready. What
 * they hide stays hidden until they close it or fail; nothing of it is saved. The player can stop
 * an addon hiding anything, by mod ID ({@link #hidesOff}); that choice is the player's setting.
 */
public final class Addons {
	/** The API version these classes are, written into api.properties by api/build.gradle.kts. */
	static final String API_VERSION = readApiVersion();

	/**
	 * One addon a loader found: the mod ID the loader knows the jar by, the mod's name from its
	 * metadata, and how to make the addon, which loads its class.
	 */
	public record Found(String modId, String name, Callable<DioramaAddon> create) {
	}

	/**
	 * One mod using the API, for the settings screen: its name (the mod ID if its metadata has
	 * none), whether any of its addons still runs, whether the player lets it hide parts of
	 * Diorama, and what its open handles ask to hide.
	 */
	public record Listed(String modId, String name, boolean running, boolean allowed, boolean minimap, boolean worldWaypoints) {
	}

	private static Supplier<List<Found>> finder = List::of;
	private static boolean started;
	/** Every addon made, failed ones too, in the order they were found. */
	private static final List<Addon> ALL = new ArrayList<>();
	/** The mod IDs the player stopped from hiding parts of Diorama. */
	private static Set<String> hidesOff = Set.of();

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
	 * the mod the file declares, with that mod's display name ({@code fileNames}, in the same
	 * order). A file declaring several mods can't say which one the class is for, so it is skipped;
	 * null then. Loads nothing until the addon is made.
	 */
	public static @Nullable Found scanned(List<String> fileMods, List<String> fileNames, String className) {
		if (fileMods.size() != 1) {
			Diorama.LOGGER.warn("Skipped Diorama addon {}: its jar declares {} mods ({}), so it can't be told which one it belongs to", className, fileMods.size(), String.join(", ", fileMods));
			return null;
		}
		return new Found(fileMods.get(0), fileNames.get(0), () -> {
			// Loaded without running any of its code, so a class marked by mistake is turned down
			// before its static initialiser or constructor can do anything.
			Class<?> type = Class.forName(className, false, Addons.class.getClassLoader());
			if (!DioramaAddon.class.isAssignableFrom(type)) {
				throw new ClassCastException(className + " doesn't implement " + DioramaAddon.class.getName());
			}
			return (DioramaAddon) type.getDeclaredConstructor().newInstance();
		});
	}

	/**
	 * The mod IDs the player has stopped from hiding parts of Diorama, from the settings: what
	 * their open handles hide shows again at once, and hides again at once when the player ticks
	 * them again.
	 */
	public static void hidesOff(Set<String> modIds) {
		hidesOff = Set.copyOf(modIds);
		for (Addon addon : ALL) {
			addon.allow(!hidesOff.contains(addon.id()));
		}
	}

	/** The mods using the API, one each, by name. Empty until the game has loaded, or with none installed. */
	public static List<Listed> listed() {
		Map<String, Listed> mods = new LinkedHashMap<>();
		for (Addon addon : ALL) {
			int asked = addon.asked();
			Listed one = new Listed(addon.id(), addon.name(), !addon.failed(), addon.allowed(),
				(asked & HudHides.bit(Hud.Part.MINIMAP)) != 0, (asked & HudHides.bit(Hud.Part.WORLD_WAYPOINTS)) != 0);
			// A mod with more than one addon: running while one of them is, asking for what any asks.
			mods.merge(addon.id(), one, (a, b) -> new Listed(a.modId(), a.name(), a.running() || b.running(), a.allowed(),
				a.minimap() || b.minimap(), a.worldWaypoints() || b.worldWaypoints()));
		}
		List<Listed> listed = new ArrayList<>(mods.values());
		listed.sort(Comparator.comparing((Listed one) -> one.name().toLowerCase(Locale.ROOT)).thenComparing(Listed::modId));
		return listed;
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
			String name = one.name() == null || one.name().isBlank() ? one.modId() : one.name().strip();
			Addon addon = new Addon(one.modId(), name, !hidesOff.contains(one.modId()));
			ALL.add(addon);
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
