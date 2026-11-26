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
 * <p>Every method throws {@link IllegalStateException} off the client thread, or once its world
 * has closed (see {@link MapWorld}).
 *
 * <p><b>When listeners hear about changes.</b> A change is made, saved and readable here at once,
 * whoever makes it. Listeners hear about it later, from Diorama's own client tick: every change,
 * and every {@link WorldListener} call, goes into one queue in the order it happened, and each
 * tick tells all of it, in that order, to every addon. Nothing is told from inside {@link #edit}
 * or from inside another listener call. So by the time a listener hears about a change, the
 * waypoints may have changed again: {@link #all} and {@link #get} always say how they are now,
 * and each {@link WaypointChange} carries the waypoint as it was before and after that one change.
 */
public interface Waypoints {
	/**
	 * How many answers deep a chain of edits made from listeners can go: 50. An edit made outside
	 * any listener starts a chain. An edit made while a listener is being told about a change is
	 * one deeper than that change. An addon that edits while being told about a change that is
	 * already this deep is switched off, with one line in the log, and its edit is refused: that is
	 * two listeners answering each other's changes for ever, or one answering its own. Any number
	 * of edits made side by side, while being told about one change, is fine.
	 */
	int MAX_EDIT_DEPTH = 50;

	/**
	 * {@return every waypoint, as it is now} In the order Diorama keeps them (roughly the order
	 * they were made, the waystones last), which is not the order the player's waypoint list
	 * sorts them in. The list can't be changed, and neither can its waypoints. It is the same
	 * list, the same instance, on every call until a waypoint changes, so calling this often costs
	 * nothing. A change to the Waystones mod's waystones, or to the portal pin setting, shows here
	 * at once while no addon listens, and by Diorama's next client tick while one does (it is told
	 * as a change then).
	 */
	List<Waypoint> all();

	/**
	 * One waypoint by its ID, from the same waypoints as {@link #all}, as it is now.
	 *
	 * @param id the waypoint's {@link Waypoint#id}
	 * @return the waypoint, or empty if none here has that ID; of two with the same ID (only a
	 *     hand-edited file has those), the first in {@code all()}'s order
	 */
	Optional<Waypoint> get(UUID id);

	/**
	 * Adds, changes and removes waypoints as one batch: {@code edits} says what through the
	 * {@link WaypointEditor} it is handed, then Diorama applies all of it, writes
	 * {@code waypoints.json} once (and {@code folders.json} once, if the batch made folders) and
	 * queues one change for listeners, which they hear at Diorama's next client tick. When this
	 * returns, {@link #all} already shows the batch. A batch that changes nothing saves nothing and
	 * tells nothing. Only player-made waypoints ({@link Waypoint.Kind#MANUAL}) can be changed or
	 * removed.
	 *
	 * <p>{@code edits} is this addon's own code, so it falls under the usual rule for addon code
	 * Diorama calls: if it throws anything, the exceptions the editor throws included, nothing of
	 * the batch is applied or saved, this addon is switched off for the rest of the session (its
	 * handles and listeners closed, one line in the log), and what it threw goes on to the caller.
	 *
	 * <p>This can be called from inside a {@link WaypointListener} or {@link WorldListener}: it
	 * is applied and saved at once, and listeners hear about it after the current call returns, in
	 * the same tick. See {@link #MAX_EDIT_DEPTH} for listeners that keep answering changes.
	 *
	 * <p>If the disk refuses the write, Diorama logs it and carries on with the change in memory,
	 * as it does for the player's own edits; the next save writes it.
	 *
	 * @param edits what to change, given the batch's editor; it may not keep the editor
	 * @throws IllegalStateException if called from inside another {@code edit}'s {@code edits}, or
	 *     past {@link #MAX_EDIT_DEPTH} (and then this addon has been switched off)
	 * @throws NullPointerException if {@code edits} is null
	 */
	void edit(Consumer<WaypointEditor> edits);

	/**
	 * Starts telling {@code listener} about every change to {@link #all}: waypoints added, changed
	 * and removed, the player's own in Diorama's screens and Diorama's automatic pins included. It
	 * hears exactly the changes made after this call, none from before, even ones not yet told to
	 * the listeners already listening. A switch of saved map is told as
	 * {@link WorldListener#mapChanged}, not as waypoints added and removed.
	 *
	 * @param listener called from Diorama's client tick, once per change; see
	 *     {@link WaypointListener}
	 * @return the handle to close to stop; it closes by itself when the world closes. Once it is
	 *     closed, the listener hears nothing more, even changes made before it was closed
	 * @throws NullPointerException if {@code listener} is null
	 */
	Handle listen(WaypointListener listener);
}
