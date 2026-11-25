package dev.diorama.api;

/**
 * Told when the player's world opens and closes in Diorama, and when the map they're on changes:
 * see {@link DioramaApi#onWorld}. Every method is called on the client thread and does nothing
 * unless overridden.
 *
 * <p>A world opens once Diorama has loaded its waypoints, shortly after the player joins it, and
 * closes when they leave it. Leaving and joining the same world again closes one {@link MapWorld}
 * and opens another with the same {@link MapWorld#worldId}. A server that moves the player to
 * another of its worlds closes one and opens the next.
 */
public interface WorldListener {
	/**
	 * A world opened. Diorama also calls this at once from {@link DioramaApi#onWorld} when a world
	 * is already open.
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
	 * them again here. Comes before any waypoint event on the new map.
	 *
	 * @param world the world, still open
	 */
	default void mapChanged(MapWorld world) {
	}

	/**
	 * The world closed. By now {@code world} is closed: {@link MapWorld#worldId} still answers,
	 * and everything else on it throws.
	 *
	 * @param world the world that closed
	 */
	default void closed(MapWorld world) {
	}
}
