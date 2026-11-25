package dev.diorama.addon;

import dev.diorama.Diorama;
import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The addons other mods made with Diorama's API ({@code dev.diorama.api}). The loader's entrypoint
 * says how to find them ({@link #finder}): a Fabric entrypoint, or an annotation in the mod files'
 * scan on NeoForge and Forge. Once the game has started they are made and told they're ready, and
 * from then on everything they hold is let go when the player leaves a world, when one fails and
 * when the game stops.
 */
public final class Addons {
	/** The API version these classes are, written into api.properties by api/build.gradle.kts. */
	static final String API_VERSION = readApiVersion();

	/**
	 * One addon a loader found: the mod ID the loader knows the jar by, and how to make the addon,
	 * which loads its class.
	 */
	public record Found(String modId, Callable<?> create) {
	}

	private static Supplier<List<Found>> finder = List::of;
	private static boolean started;
	/** Whether the player is in a world, so a join that comes without a leave (a server network moving them) counts as one. */
	private static boolean inWorld;
	private static final List<Addon> ADDONS = new ArrayList<>();

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
			return type.getDeclaredConstructor().newInstance();
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
			Diorama.LOGGER.error("Diorama couldn't look for addons", e);
			return;
		}
		for (Found one : found) {
			Addon addon = new Addon(one.modId());
			ADDONS.add(addon);
			Object made = addon.call("its entrypoint", one.create());
			if (made == null) {
				continue;
			}
			if (!(made instanceof DioramaAddon ready)) {
				addon.fail("its entrypoint", new ClassCastException(made.getClass().getName() + " doesn't implement " + DioramaAddon.class.getName()));
				continue;
			}
			addon.call("onReady", () -> {
				ready.onReady(addon);
				return Boolean.TRUE;
			});
			if (!addon.failed()) {
				Diorama.LOGGER.info("Diorama addon {} ready ({}, API {})", addon.id(), made.getClass().getName(), API_VERSION);
			}
		}
	}

	/** The player joined a world. Joining another without leaving first closes the last one's handles. */
	public static void joined() {
		onClientThread(() -> {
			if (inWorld) {
				releaseAll();
			}
			inWorld = true;
		});
	}

	/**
	 * The player left the world: every addon lets go of what it held. Forge and NeoForge also send
	 * their leave event while a singleplayer world is being made, before anyone has joined it; with
	 * no world to leave that changes nothing, so a handle taken at the title screen lasts into the
	 * first world.
	 */
	public static void left() {
		onClientThread(() -> {
			if (!inWorld) {
				return;
			}
			inWorld = false;
			releaseAll();
		});
	}

	/** The game is stopping. */
	public static void stop() {
		onClientThread(Addons::releaseAll);
	}

	/** Runs it now on the client thread, or next there: hides are counted there, which reads them as it draws. */
	private static void onClientThread(Runnable task) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) {
			task.run();
		} else {
			mc.execute(task);
		}
	}

	private static void releaseAll() {
		for (Addon addon : ADDONS) {
			addon.releaseAll();
		}
	}
}
