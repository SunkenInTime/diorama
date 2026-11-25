package dev.diorama.api;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A dimension, by the ID the game knows it by, such as {@code minecraft:the_nether}: a namespace
 * and a path, checked the way the game checks them. Two are equal when their IDs are.
 */
public final class DimensionId {
	/** The Overworld, {@code minecraft:overworld}. */
	public static final DimensionId OVERWORLD = new DimensionId("minecraft", "overworld");
	/** The Nether, {@code minecraft:the_nether}. */
	public static final DimensionId NETHER = new DimensionId("minecraft", "the_nether");
	/** The End, {@code minecraft:the_end}. */
	public static final DimensionId END = new DimensionId("minecraft", "the_end");

	private final String namespace;
	private final String path;
	private final String id;

	private DimensionId(String namespace, String path) {
		this.namespace = namespace;
		this.path = path;
		this.id = namespace + ":" + path;
	}

	/**
	 * The dimension with this ID. As in the game, an ID without a namespace is in
	 * {@code minecraft}'s.
	 *
	 * @param id {@code namespace:path}, or {@code path} alone
	 * @return the dimension
	 * @throws IllegalArgumentException if the ID isn't one the game would take: the namespace may
	 *     hold {@code a-z 0-9 _ - .}, the path those and {@code /}, and neither may be empty
	 * @throws NullPointerException if {@code id} is null
	 */
	public static DimensionId of(String id) {
		Objects.requireNonNull(id, "id");
		int colon = id.indexOf(':');
		return colon < 0 ? of("minecraft", id) : of(id.substring(0, colon), id.substring(colon + 1));
	}

	/**
	 * The dimension with this namespace and path.
	 *
	 * @param namespace the mod or data pack it belongs to, such as {@code minecraft}
	 * @param path its name in that namespace, such as {@code the_nether}
	 * @return the dimension
	 * @throws IllegalArgumentException if either part has characters the game doesn't allow
	 * @throws NullPointerException if either is null
	 */
	public static DimensionId of(String namespace, String path) {
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(path, "path");
		if (!Checks.allowed(namespace, false)) {
			throw new IllegalArgumentException("Not a dimension namespace: \"" + namespace + "\" (a-z 0-9 _ - . only)");
		}
		if (!Checks.allowed(path, true)) {
			throw new IllegalArgumentException("Not a dimension path: \"" + path + "\" (a-z 0-9 _ - . / only)");
		}
		if (namespace.equals("minecraft")) {
			switch (path) {
				case "overworld":
					return OVERWORLD;
				case "the_nether":
					return NETHER;
				case "the_end":
					return END;
				default:
					break;
			}
		}
		return new DimensionId(namespace, path);
	}

	/** {@return the namespace, such as {@code minecraft}} */
	public String namespace() {
		return this.namespace;
	}

	/** {@return the path, such as {@code the_nether}} */
	public String path() {
		return this.path;
	}

	/** {@return the whole ID, {@code namespace:path}} */
	@Override
	public String toString() {
		return this.id;
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return other instanceof DimensionId && ((DimensionId) other).id.equals(this.id);
	}

	@Override
	public int hashCode() {
		return this.id.hashCode();
	}
}
