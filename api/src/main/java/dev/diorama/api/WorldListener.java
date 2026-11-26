package dev.diorama.api;

/**
 * Told when the player's world opens and closes in Diorama, and when the map they're on changes:
 * see {@link DioramaApi#onWorld}. Every method is called from Diorama's client tick, in the same
 * queue as waypoint changes (see {@link Waypoints}), and does nothing unless overridden.
 *
 * <p>A world opens once Diorama has loaded its waypoints and knows which world it is, shortly
 * after the player joins it, and closes when they leave it. Every {@link #opened} is followed by
 * one {@link #closed} for the same {@link MapWorld}, and nothing else for it after that. Leaving and
 * joining the same world again closes one {@link MapWorld} and opens another with the same
 * {@link MapWorld#worldId}. A server that moves the player to another of its worlds closes one and
 * opens the next. While Diorama doesn't know which world the player is in (a server that names
 * its worlds can take a moment to name the next one after a dimension change), the world is
 * closed, and opened again once it is known.
 */
public interface WorldListener {
	/**
	 * A world opened. If a world is already open when {@link DioramaApi#onWorld} is called, this
	 * is called for it at Diorama's next client tick.
	 *
	 * @param world the world, open until {@link #closed}
	 */
	default void opened(MapWorld world) {
	}

	/**
	 * The player changed dimension, or a saved map was switched in any dimension (the Maps
	 * screen's Use, or a new map made there). {@link MapWorld#dimension} and {@link MapWorld#mapId}
	 * already say where the player is now. A switch of saved map swaps the waypoints
	 * {@link Waypoints#all} lists for those of the other map without any waypoint events, so read
	 * them again here. Comes after every waypoint change made before the switch, and before any
	 * made after it.
	 *
	 * @param world the world, still open
	 */
	default void mapChanged(MapWorld world) {
	}

	/**
	 * The world closed. By now {@code world} is closed: {@link MapWorld#worldId} still answers,
	 * and everything else on it throws. Changes made in the same tick as the world closed may not
	 * be told before this; read the waypoints again when the next world opens.
	 *
	 * @param world the world that closed
	 */
	default void closed(MapWorld world) {
	}
}
