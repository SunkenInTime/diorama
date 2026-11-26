package dev.diorama.api;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which of a dimension's saved maps something is on (the player makes and switches these in
 * Diorama's Maps screen). Opaque: its {@link #toString} is lowercase letters and digits, safe in a
 * file name, and not something to take apart. Two are equal when their text is.
 *
 * <p>An ID only means something together with its world and dimension: the same ID in another
 * dimension, or another world, is another map. It is fixed when the map is made and stays through
 * the player renaming the map. Every dimension's first map is {@link #MAIN}.
 */
public final class MapId {
	/** A dimension's first map, the one it has before the player makes another: {@code main}. */
	public static final MapId MAIN = new MapId("main");

	private final String id;

	private MapId(String id) {
		this.id = id;
	}

	/**
	 * The ID written this way, as {@link #toString} wrote it.
	 *
	 * @param id lowercase letters and digits, such as {@code main}
	 * @return the ID
	 * @throws IllegalArgumentException if it is empty or has any other character
	 * @throws NullPointerException if {@code id} is null
	 */
	public static MapId of(String id) {
		Objects.requireNonNull(id, "id");
		if (id.equals(MAIN.id)) {
			return MAIN;
		}
		if (id.isEmpty()) {
			throw new IllegalArgumentException("Not a map ID: \"\" (lowercase letters and digits)");
		}
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'z')) {
				throw new IllegalArgumentException("Not a map ID: \"" + id + "\" (lowercase letters and digits)");
			}
		}
		return new MapId(id);
	}

	/** {@return the ID as text, such as {@code main}} */
	@Override
	public String toString() {
		return this.id;
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return other instanceof MapId && ((MapId) other).id.equals(this.id);
	}

	@Override
	public int hashCode() {
		return this.id.hashCode();
	}
}
