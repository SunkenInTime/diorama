package dev.diorama.api;

import java.util.List;
import java.util.UUID;

/**
 * What one {@link Waypoints#edit} did. Diorama implements this; don't.
 */
public interface EditResult {
	/**
	 * {@return the IDs Diorama gave the waypoints the batch added, in the order
	 * {@link WaypointEditor#add} was called} Empty if it added none. The list can't be changed.
	 */
	List<UUID> added();

	/**
	 * {@return whether the batch changed anything} False for a batch that asked for nothing, or
	 * only for what was so already (a rename to the name it has): then nothing was written and
	 * {@link Waypoints#revision} didn't move.
	 */
	boolean changed();

	/**
	 * {@return false if the disk refused to save the change} Diorama keeps the change in memory
	 * all the same, as it does for the player's own edits, and logs it; the next save that works
	 * writes it. Until then a restart loses it, so a sync shouldn't record a link to a waypoint
	 * it added in a batch that wasn't saved. True when the change was saved, or there was nothing
	 * to save.
	 */
	boolean saved();
}
