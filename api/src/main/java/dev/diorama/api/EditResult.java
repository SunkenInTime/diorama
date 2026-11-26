package dev.diorama.api;

import java.util.List;
import java.util.UUID;

/**
 * What one {@link Waypoints#edit} did. Diorama implements this; don't.
 */
public interface EditResult {
	/**
	 * {@return false if nothing of the batch was applied, because {@link DioramaApi#world} or
	 * {@link Waypoints#revision} moved on since the addon read them} The player went to another
	 * world or dimension, a saved map was made, switched or deleted, or a waypoint changed (the
	 * player's, an automatic pin, another addon's edit) since then; or no world is open, or Diorama
	 * switched this addon off. Then {@link #added} is empty and {@link #changed} false: change
	 * nothing on the other mod's side, read the world and the waypoints again, and make the batch
	 * again on the next poll. True otherwise, a batch that changed nothing included.
	 */
	boolean applied();

	/**
	 * {@return the IDs Diorama gave the waypoints the batch added, in the order of its
	 * {@link WaypointBatch.Add} steps} Empty if it added none, or wasn't applied. The list can't be
	 * changed.
	 */
	List<UUID> added();

	/**
	 * {@return whether the batch changed anything Diorama keeps} False for a batch that wasn't
	 * applied, or asked for nothing, or only for what was so already once all its steps were applied
	 * (a rename to the name a waypoint has, or one renamed and then back): then nothing was written,
	 * and {@link Waypoints#revision} didn't move. When true, it was written ({@link #saved}), and
	 * the revision moved if {@link Waypoints#all} reads differently now. It may not: a waypoint
	 * taken out of a folder that no longer exists was in none for {@code all()} already.
	 */
	boolean changed();

	/**
	 * {@return false if the disk refused part of the save} The change is in memory all the same
	 * and the player sees it, as with their own edits; part of it may be on disk, and Diorama
	 * tries again at its next save of this world's waypoints, whatever makes it. Until one works, a
	 * restart loses what wasn't written. True when it was saved, or there was nothing to save.
	 */
	boolean saved();
}
