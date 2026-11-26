package dev.diorama.api;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which world a {@link World} is, as Diorama tells worlds apart: the same each time the player
 * comes back to it, and opaque. Its {@link #toString} is 32 lowercase hex digits, safe in a file
 * name, and not something to take apart. Two are equal when their text is. Diorama makes these;
 * an addon that kept one as text gets it back with {@link #of}.
 *
 * <p>It follows Diorama's own folder for the world, so it changes when that does:
 * <ul>
 * <li>Singleplayer: named by the save's folder, so renaming the save's folder gives a new ID. A
 *     new save made under a name an old one had gets an ID of its own.</li>
 * <li>A server: named by its address as the server list has it, so a changed address gives a new
 *     ID. Within it, a server that names its worlds for Xaero's mods (a level ID) gets one ID per
 *     named world; any other server is named by its seed, so its worlds that share a seed share an
 *     ID, as a network's servers often do.</li>
 * <li>A realm: named by the realm, so the same across the addresses Realms moves it to.</li>
 * </ul>
 */
public final class WorldId {
	private final String id;

	private WorldId(String id) {
		this.id = id;
	}

	/**
	 * The ID written this way, as {@link #toString} wrote it.
	 *
	 * @param id 32 lowercase hex digits
	 * @return the ID
	 * @throws IllegalArgumentException if it isn't 32 lowercase hex digits
	 * @throws NullPointerException if {@code id} is null
	 */
	public static WorldId of(String id) {
		Objects.requireNonNull(id, "id");
		if (id.length() != 32) {
			throw new IllegalArgumentException("Not a world ID: \"" + id + "\" (32 lowercase hex digits)");
		}
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
				throw new IllegalArgumentException("Not a world ID: \"" + id + "\" (32 lowercase hex digits)");
			}
		}
		return new WorldId(id);
	}

	/** {@return the ID as text: 32 lowercase hex digits} */
	@Override
	public String toString() {
		return this.id;
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return other instanceof WorldId && ((WorldId) other).id.equals(this.id);
	}

	@Override
	public int hashCode() {
		return this.id.hashCode();
	}
}
