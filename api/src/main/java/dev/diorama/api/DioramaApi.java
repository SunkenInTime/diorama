package dev.diorama.api;

/**
 * What one addon can do with Diorama, handed to {@link DioramaAddon#onReady}. Each addon gets its
 * own, tied to the mod ID its mod loader knows it by, so whatever it does can be traced back to
 * it and undone when it fails.
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
	 * Starts telling {@code listener} when the player's world opens and closes and when their map
	 * changes; the {@link MapWorld} it is handed is the way to the world's waypoints. If a world is
	 * open already, {@link WorldListener#opened} is called before this returns. Typically called
	 * once, from {@link DioramaAddon#onReady}.
	 *
	 * @param listener called on the client thread
	 * @return the handle to close to stop; worlds closing don't close it. Closing it closes the
	 *     {@link MapWorld} it handed over, and that world's waypoint listeners, without a
	 *     {@link WorldListener#closed} call
	 * @throws NullPointerException if {@code listener} is null
	 * @throws IllegalStateException if called off the client thread
	 */
	Handle onWorld(WorldListener listener);
}
