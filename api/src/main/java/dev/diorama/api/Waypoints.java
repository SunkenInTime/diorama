package dev.diorama.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The waypoints of one {@link MapWorld}: the ones the player's waypoint list shows, in every
 * dimension, each on its dimension's saved map in use. Waypoints on the other saved maps aren't
 * here until the player switches to that map. Diorama implements this; don't.
 *
 * <p>Every method throws {@link IllegalStateException} off the client thread, or once the world
 * has closed.
 */
public interface Waypoints {
	/**
	 * {@return every waypoint, as it is now} The list can't be changed, and neither can its
	 * waypoints. It is the same list, the same instance, on every call until a waypoint changes, so
	 * calling this often costs nothing. Waystones and the portal pin setting can take until the
	 * next client tick to show here.
	 */
	List<Waypoint> all();

	/**
	 * One waypoint by its ID, from the same waypoints as {@link #all}.
	 *
	 * @param id the waypoint's {@link Waypoint#id}
	 * @return the waypoint, or empty if none here has that ID
	 */
	Optional<Waypoint> get(UUID id);

	/**
	 * Adds, changes and removes waypoints as one batch: {@code edits} says what through the
	 * {@link WaypointEditor} it is handed, then Diorama applies all of it, saves once and tells
	 * listeners once. If {@code edits} throws, nothing of the batch is applied and the exception
	 * goes on to the caller. Only player-made waypoints ({@link Waypoint.Kind#MANUAL}) can be
	 * changed or removed.
	 *
	 * <p>This can be called from inside a {@link WaypointListener}: the batch is applied and saved
	 * at once, and listeners hear about it once every listener has heard about the change being
	 * told now. Listeners that keep answering changes with changes are stopped: an edit that would
	 * start the 51st such round in a row throws {@link IllegalStateException} instead.
	 *
	 * @param edits what to change, given the batch's editor; it may not keep the editor
	 * @throws IllegalStateException if called from inside another {@code edit}'s {@code edits}, or
	 *     as the 51st round of changes answered from listeners
	 * @throws NullPointerException if {@code edits} is null
	 */
	void edit(Consumer<WaypointEditor> edits);

	/**
	 * Starts telling {@code listener} about every change to {@link #all}: waypoints added, changed
	 * and removed, the player's own in Diorama's screens and Diorama's automatic pins included.
	 * The listener hears nothing about changes made before this call.
	 *
	 * @param listener called on the client thread, once per change; see {@link WaypointListener}
	 * @return the handle to close to stop; it closes by itself when the world closes
	 * @throws NullPointerException if {@code listener} is null
	 */
	Handle listen(WaypointListener listener);
}
