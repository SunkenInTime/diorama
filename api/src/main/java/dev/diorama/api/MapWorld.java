package dev.diorama.api;

/**
 * The world the player is in, as Diorama keeps it: its waypoints and which saved map each
 * dimension is on. Handed to a {@link WorldListener}, and open until its {@link WorldListener#closed}
 * call. Each addon gets its own, so what it changes is put down to it. Diorama implements this;
 * don't.
 *
 * <p>Once closed, {@link #worldId} and {@link #isOpen} still answer, and every other method
 * throws {@link IllegalStateException}. Every method throws {@link IllegalStateException} off the
 * client thread.
 */
public interface MapWorld {
	/**
	 * {@return an ID for this world, the same each time the player comes back to it} It is
	 * opaque: letters and digits only, safe in a file name, and not something to take apart. Two
	 * worlds Diorama keeps apart have different IDs; a world Diorama can't tell from another (two
	 * servers on one network that share a seed, say) shares its ID.
	 */
	String worldId();

	/**
	 * {@return the dimension the player is in}
	 *
	 * @throws IllegalStateException once the world has closed
	 */
	DimensionId dimension();

	/**
	 * {@return the saved map the player's dimension is on} Opaque, letters and digits only, and
	 * the same each time; {@code main} for a dimension's first map.
	 *
	 * @throws IllegalStateException once the world has closed
	 */
	String mapId();

	/**
	 * The saved map a dimension is on, as for {@link #mapId()}.
	 *
	 * @param dimension any dimension, visited or not
	 * @return its saved map's ID; {@code main} for one with a single map or none yet
	 * @throws IllegalStateException once the world has closed
	 */
	String mapId(DimensionId dimension);

	/**
	 * {@return the waypoints of this world}
	 *
	 * @throws IllegalStateException once the world has closed
	 */
	Waypoints waypoints();

	/** {@return whether the world is still open} */
	boolean isOpen();
}
