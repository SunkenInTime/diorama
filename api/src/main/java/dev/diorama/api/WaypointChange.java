package dev.diorama.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What happened to one waypoint in a {@link WaypointChanges}. Diorama implements this; don't.
 */
public interface WaypointChange {
	/** How a waypoint changed. */
	enum Type {
		/** It wasn't in {@link Waypoints#all} before and is now. */
		ADDED,
		/** It was and still is, and something about it changed. */
		CHANGED,
		/** It was and isn't any more. */
		REMOVED,
	}

	/** {@return how it changed} */
	Type type();

	/** {@return the waypoint's ID} */
	UUID id();

	/** {@return the waypoint before the change; empty when it was added} */
	Optional<Waypoint> before();

	/** {@return the waypoint after the change; empty when it was removed} */
	Optional<Waypoint> after();
}
