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
 * <p><b>A new world empties it.</b> Content is for the world it was handed over in: hand it over
 * while {@link DioramaApi#world} is present. When the player leaves a world, for the title screen
 * or another server, Diorama empties every layer at that moment, as if each was handed
 * {@link LayerContent#EMPTY}, and it does the same when the player joins a world, or arrives in
 * one whose {@link World#worldId} is a different one. The layer stays open, and the addon hands it
 * the world's content again with {@link #replace}, the same world's too if the player comes back
 * to it. {@link DioramaApi#world} returns the same instance until the world changes, so an addon
 * can check it every tick for nothing; forgetting the world whenever there is none makes a return
 * to the same one hand the content over again:
 *
 * <pre>{@code
 * WorldId now = api.world().map(World::worldId).orElse(null);
 * if (now == null) {
 *     this.shownFor = null;
 * } else if (!now.equals(this.shownFor)) {
 *     this.shownFor = now;
 *     towns.replace(this.townsIn(now));
 * }
 * }</pre>
 *
 * Content handed over while {@link DioramaApi#world} is empty, such as at the title screen or
 * while the world is being joined, may be emptied once the world is known, so hand it over again
 * then, as the code above does. So nothing an addon showed in one world shows in another,
 * whichever server the player goes to next. The moment {@link DioramaApi#world} can be empty
 * during a dimension change, on a server that names its worlds for Xaero's mods, isn't leaving:
 * the layers keep what they hold.
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
 * each of its points in view (a straight piece and a round joint, each with its rim). Where the
 * round minimap's edge cuts a piece it takes more, up to several times as many, so a layer whose
 * lines cross that edge again and again can fill the budget on its own. Only what is in view
 * counts. Past that, the map draws nothing
 * more that frame: in drawing order, the first marker, or the first line, that doesn't fit
 * whole, and everything after it, are left out, the same ones every frame for the same content
 * and view. Diorama logs a warning the first time.
 *
 * <p>Markers show whether or not the server lets the radar show: a server's radar lock hides
 * Diorama's radar, not addons' markers. An addon that marks mobs or players should not show them
 * where the server forbids the radar; the API will get a way to read that.
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
