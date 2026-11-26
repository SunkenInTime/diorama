package dev.diorama.api;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A resource ID as the game writes it, {@code namespace:path}, such as {@code diorama:home}:
 * checked the way the game checks one. Two are equal when their IDs are. A waypoint's icon is one
 * ({@link Waypoint#icon}).
 */
public final class ResourceId {
	private final String namespace;
	private final String path;
	private final String id;

	private ResourceId(String namespace, String path) {
		this.namespace = namespace;
		this.path = path;
		this.id = namespace + ":" + path;
	}

	/**
	 * The resource ID written this way. As in the game, an ID without a namespace is in
	 * {@code minecraft}'s.
	 *
	 * @param id {@code namespace:path}, or {@code path} alone
	 * @return the ID
	 * @throws IllegalArgumentException if the ID isn't one the game would take: the namespace may
	 *     hold {@code a-z 0-9 _ - .}, the path those and {@code /}, and neither may be empty
	 * @throws NullPointerException if {@code id} is null
	 */
	public static ResourceId of(String id) {
		Objects.requireNonNull(id, "id");
		int colon = id.indexOf(':');
		return colon < 0 ? of("minecraft", id) : of(id.substring(0, colon), id.substring(colon + 1));
	}

	/**
	 * The resource ID with this namespace and path.
	 *
	 * @param namespace the mod or pack it belongs to, such as {@code diorama}
	 * @param path its name in that namespace, such as {@code home}
	 * @return the ID
	 * @throws IllegalArgumentException if either part has characters the game doesn't allow
	 * @throws NullPointerException if either is null
	 */
	public static ResourceId of(String namespace, String path) {
		Objects.requireNonNull(namespace, "namespace");
		Objects.requireNonNull(path, "path");
		if (!Checks.allowed(namespace, false)) {
			throw new IllegalArgumentException("Not a resource namespace: \"" + namespace + "\" (a-z 0-9 _ - . only)");
		}
		if (!Checks.allowed(path, true)) {
			throw new IllegalArgumentException("Not a resource path: \"" + path + "\" (a-z 0-9 _ - . / only)");
		}
		return new ResourceId(namespace, path);
	}

	/** {@return the namespace, such as {@code diorama}} */
	public String namespace() {
		return this.namespace;
	}

	/** {@return the path, such as {@code home}} */
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
		return other instanceof ResourceId && ((ResourceId) other).id.equals(this.id);
	}

	@Override
	public int hashCode() {
		return this.id.hashCode();
	}
}
