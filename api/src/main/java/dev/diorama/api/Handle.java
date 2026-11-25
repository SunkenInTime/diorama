package dev.diorama.api;

/**
 * Something an addon holds and closes when it is done with it, such as a part of the HUD it hid
 * ({@link Hud#hide}). Works with try-with-resources.
 */
public interface Handle extends AutoCloseable {
	/**
	 * Lets go. Closing a handle that is already closed, by the addon or by Diorama after the addon
	 * failed, does nothing.
	 *
	 * @throws IllegalStateException if called off the client thread
	 */
	@Override
	void close();

	/**
	 * {@return whether the handle still holds} False once the addon closed it, or Diorama did
	 * because the addon failed.
	 *
	 * @throws IllegalStateException if called off the client thread
	 */
	boolean isOpen();
}
