package dev.diorama.api;

/**
 * An addon's way into Diorama. Diorama finds one class per addon that implements this, makes one
 * instance of it with its public no-argument constructor, and calls {@link #onReady} once.
 *
 * <p>How Diorama finds the class depends on the mod loader:
 * <ul>
 *   <li>Fabric: an entrypoint named {@code diorama} in the addon's {@code fabric.mod.json},
 *       {@code "entrypoints": {"diorama": ["com.example.MyDioramaAddon"]}}.</li>
 *   <li>NeoForge and Forge: the class is annotated with {@link DioramaEntrypoint}.</li>
 * </ul>
 * A jar for several loaders can do both. Diorama never loads the class when it isn't installed, so
 * an addon that only optionally supports Diorama needs no reflection: keep every reference to
 * {@code dev.diorama.api} inside this class and the classes only it uses.
 *
 * <p>Anything thrown from the constructor or from any call Diorama makes into the addon switches
 * the addon off for the rest of the session: what it hid shows again, later calls it makes do
 * nothing, and Diorama logs the failure once with its mod ID. Other addons and Diorama itself
 * carry on. The exceptions are the JVM's own errors ({@link VirtualMachineError}, such as running
 * out of memory, and {@code ThreadDeath}), thrown as they are or wrapped by reflection or the mod
 * loader: Diorama switches the addon off and then lets them through. One the addon wraps in an
 * exception of its own counts as an ordinary failure.
 */
public interface DioramaAddon {
	/**
	 * Called once on the client thread, once the game has finished loading (the first tick without
	 * the loading screen), whether or not a world is open.
	 *
	 * @param api this addon's way into Diorama, tied to its mod ID. It stays valid for the whole
	 *     session; keep it.
	 */
	void onReady(DioramaApi api);
}
