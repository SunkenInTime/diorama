package dev.diorama.api;

/**
 * Hears every change to a world's waypoints ({@link Waypoints#listen}): one call per change,
 * whatever made it. A two-way sync tells its own changes from the player's by
 * {@link WaypointChanges#by}.
 *
 * <p>Called from Diorama's client tick, after the change was applied and saved, in the order the
 * changes were made, and after any {@link WorldListener#mapChanged} that came before it. The
 * change carries its own before and after; the world's {@link Waypoints#all} may already be newer
 * (see {@link Waypoints}). If it throws, its addon is switched off like any other failure; the
 * change stands, and other listeners still hear it.
 */
@FunctionalInterface
public interface WaypointListener {
	/**
	 * Waypoints changed.
	 *
	 * @param changes what changed, and who changed it
	 */
	void changed(WaypointChanges changes);
}
