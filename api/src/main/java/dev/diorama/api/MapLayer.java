package dev.diorama.api;

/**
 * A named set of markers and lines one addon shows on Diorama's maps, the minimap and the full
 * map, opened with {@link DioramaApi#openLayer}. It shows {@link LayerContent#EMPTY} until the
 * addon hands it content with {@link #replace}, and each call replaces all of it, so the map never
 * shows half of an update. Closing it takes all of it off the maps.
 *
 * <p>Diorama never calls the addon about a layer: no callbacks, nothing run while drawing. The
 * addon hands over values and Diorama draws them, every frame, from what it was last given. A
 * layer whose content hasn't changed costs nothing but its drawing, so an addon that shows the
 * same things all session replaces its content once; one that follows things that move, such as
 * trains or carts, replaces it as often as they move, every tick if need be. Diorama doesn't move
 * a marker between two replacements, so a marker replaced every tick steps twenty times a second.
 *
 * <p>A layer holds until this addon closes it, this addon is switched off after a failure, or the
 * game exits. Opening, leaving or switching worlds and changing dimension don't close it, and
 * don't empty it: markers are in a dimension, and show whenever a map of it does. Nothing of it
 * is saved. While the player has unticked the addon in the Addons tab of Diorama's settings, its
 * layers stay open and keep what they were given, and show nothing until the player ticks it
 * again.
 *
 * <p>Layers draw in the order they were opened, across all addons: a later layer's lines over an
 * earlier one's lines, and its markers over the earlier one's markers. Lines draw under the
 * radar, markers between the radar's mobs and Diorama's waypoints, so the player's waypoints and
 * other players show over any addon's markers.
 *
 * <p>Markers show whether or not the server lets the radar show: a server's radar lock hides
 * Diorama's radar, not addons' markers. This may change before 1.0.0.
 *
 * <p>Every method must be called on the client thread; calls from any other thread throw
 * {@link IllegalStateException}.
 */
public interface MapLayer extends Handle {
	/** The most layers one addon can have open at once: 16. */
	int MAX_OPEN = 16;

	/** {@return the name it was opened with} */
	String name();

	/**
	 * Shows this content in place of everything the layer showed before, from the next frame on.
	 * Diorama keeps the value as it is (it can't change) and copies nothing, so this costs the same
	 * for a thousand markers as for one. On a closed layer, this does nothing.
	 *
	 * @param content what to show; {@link LayerContent#EMPTY} to show nothing for now
	 * @throws NullPointerException if {@code content} is null
	 * @throws IllegalStateException if called off the client thread
	 */
	void replace(LayerContent content);
}
