package dev.diorama.api;

import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * What one addon can do with Diorama, handed to {@link DioramaAddon#onReady}. Each addon gets its
 * own, tied to the mod ID its mod loader knows it by, so whatever it does can be traced back to
 * it and undone when it fails.
 *
 * <p>Diorama never calls an addon to tell it something changed. An addon that follows the
 * player's world or waypoints reads them from its own client tick, which costs nothing while
 * nothing changes: see {@link Waypoints}.
 *
 * <p>Every method must be called on the client thread (the render thread); calls from any other
 * thread throw {@link IllegalStateException}.
 *
 * <p>Alpha: this API is still being shaped and is not yet a compatibility promise. Names and
 * behaviour may change before 1.0.0.
 */
public interface DioramaApi {
	/**
	 * {@return the version of this API that the running Diorama provides} It reads
	 * {@code major.minor.patch} with an optional pre-release part, such as {@code 1.0.0-alpha}, and
	 * counts separately from Diorama's own version.
	 */
	String apiVersion();

	/** {@return the mod ID this addon was found under, as its mod loader reported it} */
	String modId();

	/** {@return Diorama's parts of the in-game HUD} */
	Hud hud();

	/**
	 * {@return the world the player is in now} Empty at the title screen, and while Diorama
	 * doesn't know which world it is yet: a server that names its worlds for Xaero's mods can take a
	 * moment to name the next one after a dimension change. The same instance on every call until
	 * the world, the player's dimension or a dimension's saved map in use changes, so calling it
	 * every tick costs nothing.
	 */
	Optional<World> world();

	/** {@return the waypoints of the world the player is in now, whichever that is when each method is called} */
	Waypoints waypoints();

	/**
	 * Opens a layer of markers and lines on Diorama's maps, empty until its first
	 * {@link MapLayer#replace}. See {@link MapLayer} for how long it holds, how it is drawn, and
	 * how a new world empties it: hand content over while {@link #world} is present, and again
	 * when its ID changes. Content handed over while it's empty may be emptied once the world is
	 * known.
	 *
	 * @param name what this addon calls it: 1 to 32 characters, {@code a-z 0-9 _ - .}, such as
	 *     {@code trains}; not the name of another layer this addon has open
	 * @return the layer, open until this addon closes it or fails. An addon that failed, so that
	 *     Diorama switched it off, gets a layer that is already closed: {@link MapLayer#isOpen}
	 *     is false, {@link MapLayer#replace} does nothing, and it never shows.
	 * @throws IllegalArgumentException if the name won't do, or this addon already has a layer of
	 *     that name open
	 * @throws IllegalStateException if this addon already has {@link MapLayer#MAX_OPEN} layers
	 *     open, or if called off the client thread
	 * @throws NullPointerException if {@code name} is null
	 */
	MapLayer openLayer(String name);

	/**
	 * Opens a view of Diorama's map for one of this addon's own screens to draw in, drawing these
	 * parts of Diorama's over the map. See {@link MapView} for what it shows, its limits and how
	 * long it holds. Experimental.
	 *
	 * @param parts what of Diorama's to draw over the map besides addons' markers and lines; empty
	 *     for the map alone, as a map mod that draws its own pins and arrow over it would want
	 * @return the view, open until this addon closes it or fails. An addon that failed, so that
	 *     Diorama switched it off, gets a view that is already closed: {@link MapView#isOpen} is
	 *     false and {@link MapView#render} draws nothing.
	 * @throws IllegalStateException if this addon already has {@link MapView#MAX_OPEN} views open,
	 *     or if called off the client thread
	 * @throws NullPointerException if {@code parts} is null or holds null
	 */
	@ApiStatus.Experimental
	MapView openView(Set<MapView.Part> parts);
}
