package dev.diorama.api;

import java.util.UUID;

/**
 * One batch of waypoint changes, handed to the {@code edits} of {@link Waypoints#edit}. Each call
 * is checked when it is made and applied when {@code edits} returns, in the order made; a later
 * call in the batch sees what the earlier ones did, so a waypoint added can be changed or removed
 * in the same batch. Until {@code edits} returns, {@link Waypoints#all} shows none of the batch.
 * Diorama implements this; don't, and don't keep it past {@code edits}.
 *
 * <p>An exception thrown here and not caught inside {@code edits} leaves {@code edits} with it,
 * which switches the addon off (see {@link Waypoints#edit}). Check {@link Waypoint#kind} first, or
 * catch it, to carry on.
 *
 * <p>Only player-made waypoints ({@link Waypoint.Kind#MANUAL}) can be changed or removed: the
 * others are Diorama's or another mod's, and kept up to date by them.
 */
public interface WaypointEditor {
	/**
	 * Adds a player-made waypoint, on its dimension's saved map in use. It records this addon's
	 * mod ID ({@link Waypoint#addedBy}). A folder named that doesn't exist yet is made.
	 *
	 * @param waypoint what to add
	 * @return the new waypoint's ID
	 * @throws IllegalStateException if called outside the {@code edits} it was handed to
	 * @throws NullPointerException if {@code waypoint} is null
	 */
	UUID add(NewWaypoint waypoint);

	/**
	 * Changes what {@code update} sets on a player-made waypoint and leaves the rest. A waypoint
	 * moved to another dimension goes on that dimension's saved map in use. A folder named that
	 * doesn't exist yet is made.
	 *
	 * @param id the waypoint's ID; of two with the same ID, the first in {@link Waypoints#all}'s
	 *     order
	 * @param update what to change
	 * @return false if no waypoint has that ID (the player may just have deleted it); true otherwise
	 * @throws IllegalArgumentException if the waypoint isn't {@link Waypoint.Kind#MANUAL}
	 * @throws IllegalStateException if called outside the {@code edits} it was handed to
	 * @throws NullPointerException if an argument is null
	 */
	boolean update(UUID id, WaypointUpdate update);

	/**
	 * Removes a player-made waypoint.
	 *
	 * @param id the waypoint's ID; of two with the same ID, the first in {@link Waypoints#all}'s
	 *     order, and the second answers to the ID after that
	 * @return false if no waypoint has that ID; true otherwise
	 * @throws IllegalArgumentException if the waypoint isn't {@link Waypoint.Kind#MANUAL}
	 * @throws IllegalStateException if called outside the {@code edits} it was handed to
	 * @throws NullPointerException if {@code id} is null
	 */
	boolean remove(UUID id);
}
