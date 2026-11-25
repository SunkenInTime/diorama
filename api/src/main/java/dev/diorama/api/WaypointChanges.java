package dev.diorama.api;

import java.util.List;
import java.util.Optional;

/**
 * One change to a world's waypoints, as a {@link WaypointListener} hears it: one edit batch of an
 * addon's, or one thing the player or Diorama did, such as saving the waypoint editor or a death
 * pin replacing the oldest. Diorama implements this; don't.
 */
public interface WaypointChanges {
	/**
	 * {@return the mod ID of the addon whose {@link Waypoints#edit} made the change, or empty for
	 * one the player made in Diorama's screens or Diorama made itself}
	 */
	Optional<String> by();

	/** {@return each waypoint that was added, changed or removed, at least one} */
	List<WaypointChange> changes();
}
