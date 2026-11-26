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
 * game exits. Nothing of it is saved. Changing dimension doesn't empty it: markers are in a
 * dimension, and show whenever a map of it does.
 *
 * <p><b>A new world empties it.</b> When the world open changes, Diorama empties every layer, as
 * if each was handed {@link LayerContent#EMPTY}: when the player leaves a world, so that
 * {@link DioramaApi#world} is empty, and when they join another, so that its
 * {@link World#worldId} is a different one. The layer stays open, and the addon hands it the new
 * world's content with {@link #replace}. {@link DioramaApi#world} returns the same instance until
 * the world changes, so an addon can check it every tick for nothing:
 *
 * <pre>{@code
 * WorldId now = api.world().map(World::worldId).orElse(null);
 * if (now != null && !now.equals(this.shownFor)) {
 *     this.shownFor = now;
 *     towns.replace(this.townsIn(now));
 * }
 * }</pre>
 *
 * Content handed over while there is no world, such as at the title screen, is emptied when one
 * opens. So nothing an addon showed in one world shows in another, whichever server the player
 * goes to next.
 *
 * <p>While the player has unticked the addon in the Addons tab of Diorama's settings, its
 * layers stay open and keep what they were given, and show nothing until the player ticks it
 * again.
 *
 * <p>Layers draw in the order they were opened, across all addons: a later layer's lines over an
 * earlier one's lines, and its markers over the earlier one's markers. Lines draw under the
 * radar, markers between the radar's mobs and Diorama's waypoints, so the player's waypoints and
 * other players show over any addon's markers.
 *
 * <p><b>How much a map draws.</b> Each map, the minimap and the full map, draws at most
 * {@value #MAX_DRAWN_MARKERS} markers a frame, and lines cut into at most
 * {@value #MAX_DRAWN_LINE_PIECES} pieces, across every addon's layers. A line takes 8 pieces at
 * each of its points (a straight piece and a round joint, each with its rim), and a few more where
 * the round minimap's edge cuts it, so one full layer's {@value LayerContent#MAX_LINE_POINTS}
 * points fit on screen at once. Only what is in view counts. Past that, the map draws nothing
 * more that frame: in drawing order, the first marker, or the first line, that doesn't fit
 * whole, and everything after it, are left out, the same ones every frame for the same content
 * and view. Diorama logs a warning the first time.
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
	/** The most markers a map draws a frame, across every addon's layers: 8,192. */
	int MAX_DRAWN_MARKERS = 8192;
	/** The most pieces a map cuts every addon's lines into a frame: 32,768, 8 at each point of a line. */
	int MAX_DRAWN_LINE_PIECES = 32768;

	/** {@return the name it was opened with} */
	String name();

	/**
	 * Shows this content in place of everything the layer showed before, from the next frame on.
	 * Diorama keeps the value as it is (it can't change) and copies nothing, so this costs the same
	 * for a thousand markers as for one. On a closed layer, this does nothing. Content handed
	 * over in one world is emptied when the world changes (see above).
	 *
	 * @param content what to show; {@link LayerContent#EMPTY} to show nothing for now
	 * @throws NullPointerException if {@code content} is null
	 * @throws IllegalStateException if called off the client thread
	 */
	void replace(LayerContent content);
}
