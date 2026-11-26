package dev.diorama.api;

/**
 * One world the player is in, as Diorama keeps it: its waypoints and which saved map each
 * dimension is on. Handed to {@link WorldListener#opened}, and good for that one opening of the
 * world only, until {@link WorldListener#closed}. Each addon gets its own, so what it changes is
 * put down to it. Diorama implements this; don't.
 *
 * <p>A {@code MapWorld} only ever reads and writes the waypoints it was opened for. As soon as
 * the player is in another world, or rejoined this one, or Diorama no longer knows which world
 * they are in (a server that names its worlds can take a moment to name the next one after a
 * dimension change), every method but {@link #worldId} and {@link #isOpen} throws
 * {@link IllegalStateException}, even before {@link WorldListener#closed} has been called. When the
 * world is known again, {@link WorldListener#opened} hands over a new {@code MapWorld}, which may
 * have the same {@link #worldId}. The same holds for the {@link Waypoints} it hands out. Every
 * method throws {@link IllegalStateException} off the client thread.
 */
public interface MapWorld {
	/**
	 * {@return which world this is} The same each time the player comes back to the world; see
	 * {@link WorldId} for what changes it.
	 */
	WorldId worldId();

	/**
	 * {@return the dimension the player is in, as {@link WorldListener#opened} or the last
	 * {@link WorldListener#mapChanged} told it}
	 *
	 * @throws IllegalStateException once this world has closed
	 */
	DimensionId dimension();

	/**
	 * {@return the saved map the player's dimension is on now}
	 *
	 * @throws IllegalStateException once this world has closed
	 */
	MapId mapId();

	/**
	 * The saved map a dimension is on now, as for {@link #mapId()}.
	 *
	 * @param dimension any dimension, visited or not
	 * @return its saved map's ID; {@link MapId#MAIN} for one with a single map or none yet
	 * @throws IllegalStateException once this world has closed
	 */
	MapId mapId(DimensionId dimension);

	/**
	 * {@return the waypoints of this world}
	 *
	 * @throws IllegalStateException once this world has closed
	 */
	Waypoints waypoints();

	/** {@return whether this opening of the world is still the one Diorama is using} */
	boolean isOpen();
}
