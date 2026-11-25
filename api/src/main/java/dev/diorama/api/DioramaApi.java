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
}
