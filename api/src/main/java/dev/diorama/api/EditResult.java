package dev.diorama.api;

import java.util.List;
import java.util.UUID;

/**
 * What one {@link Waypoints#edit} did. Diorama implements this; don't.
 */
public interface EditResult {
	/**
	 * {@return false if nothing of the batch was applied, because {@link DioramaApi#world} wasn't
	 * the world it was prepared for} The player went to another world or dimension, or a saved map
	 * was made, switched or deleted, since that world was read (or no world is open, or Diorama
	 * switched this addon off). Then {@link #added} is empty and {@link #changed} false: read the
	 * world and the waypoints again and make the batch again, on the next poll. True otherwise, a
	 * batch that changed nothing included.
	 */
	boolean applied();

	/**
	 * {@return the IDs Diorama gave the waypoints the batch added, in the order of its
	 * {@link WaypointBatch.Add} steps} Empty if it added none, or wasn't applied. The list can't be
	 * changed.
	 */
	List<UUID> added();

	/**
	 * {@return the IDs of the batch's updates and removals that found no such waypoint, in the order
	 * of their steps} Those steps were skipped, and the rest applied: the player may just have
	 * deleted the waypoint, or an earlier step of the batch removed it. Empty if every one was
	 * found, or the batch wasn't applied. The list can't be changed.
	 */
	List<UUID> missing();

	/**
	 * {@return whether the batch changed anything} False for a batch that wasn't applied, or asked
	 * for nothing, or only for what was so already once all its steps were applied (a rename to the
	 * name a waypoint has, or one renamed and then back): then nothing was written, and
	 * {@link Waypoints#revision} didn't move. When true, the revision moved.
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
