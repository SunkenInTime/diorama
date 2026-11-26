package dev.diorama.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The waypoints of the world the player is in: the ones Diorama's waypoint list shows, in every
 * dimension, each on its dimension's saved map in use. Waypoints on the other saved maps aren't
 * here until the player switches to that map. Every method reads the world the player is in when
 * it is called ({@link DioramaApi#world}); with none, there are no waypoints. Diorama implements
 * this; don't.
 *
 * <p><b>Keeping another mod's waypoints in step.</b> Diorama doesn't tell addons when waypoints
 * change. Read from your own client tick instead:
 * <ol>
 * <li>Read {@link DioramaApi#world}. If it isn't equal to the one you last read, the player went
 *     to another world or dimension, or a saved map was switched: start again from {@link #all},
 *     as a switch of saved map swaps the waypoints {@code all()} lists for the other map's, which
 *     isn't the player deleting the old map's.</li>
 * <li>Read {@link #revision}. If it is the number you last read, nothing here changed: stop.</li>
 * <li>Otherwise read {@link #all}, compare it with what you had, and send what changed to the
 *     other mod.</li>
 * <li>To change Diorama's side, make one {@link #edit} with everything, then read
 *     {@code revision()} again and keep that number, so your next tick doesn't take your own
 *     change for the player's.</li>
 * </ol>
 * Reading {@code world()}, {@code revision()} and {@code all()} when nothing changed costs no
 * allocation, so doing it every tick is fine. Keep links between the two mods' waypoints by
 * {@link Waypoint#id} and file them under {@link World#worldId} (stable across restarts; see
 * {@link WorldId}) and the dimension: a {@link MapId} means something only within one world and
 * dimension. A waypoint your addon added has your mod ID in {@link Waypoint#addedBy}.
 *
 * <p>Every method throws {@link IllegalStateException} off the client thread.
 */
public interface Waypoints {
	/**
	 * {@return every waypoint, as it is now} In the order Diorama keeps them (roughly the order
	 * they were made, the waystones last), which is not the order the player's waypoint list sorts
	 * them in. The list can't be changed, and neither can its waypoints. It is the same list, the
	 * same instance, on every call until something in it changes, so calling this often costs
	 * nothing. Empty while there's no world ({@link DioramaApi#world}).
	 *
	 * <p>A hand-edited {@code waypoints.json} can give two waypoints the same ID. Only the first
	 * of them, in this order, is here; the others are left out, and no edit reaches them.
	 */
	List<Waypoint> all();

	/**
	 * One waypoint by its ID, from the same waypoints as {@link #all}, as it is now.
	 *
	 * @param id the waypoint's {@link Waypoint#id}
	 * @return the waypoint, or empty if none here has that ID, or there's no world
	 * @throws NullPointerException if {@code id} is null
	 */
	Optional<Waypoint> get(UUID id);

	/**
	 * {@return a number that moves whenever {@link #all} would return something different} Any
	 * change moves it: the player's in Diorama's screens, Diorama's automatic pins, the Waystones
	 * mod's waystones, a switch of saved map, another world, and any addon's {@link #edit},
	 * yours included. It never comes back to a number it had for other waypoints, even in another
	 * world, so comparing it with the last number read is enough; it isn't saved, and starts over
	 * when the game starts. 0 while there's no world.
	 */
	long revision();

	/**
	 * Adds, changes and removes waypoints as one batch: {@code edits} says what through the
	 * {@link WaypointEditor} it is handed, then Diorama applies all of it at once and writes
	 * {@code waypoints.json} once (and {@code folders.json} once, if the batch made folders). When
	 * this returns, {@link #all} and {@link #revision} already show the batch. A batch that changes
	 * nothing saves nothing and doesn't move the revision. Only player-made waypoints
	 * ({@link Waypoint.Kind#MANUAL}) can be changed or removed.
	 *
	 * <p>{@code world} is the world the batch was worked out for, from {@link DioramaApi#world}.
	 * If it isn't the world open now, this throws and changes nothing, both before {@code edits}
	 * runs and again after, so a batch never lands in another world's waypoints.
	 *
	 * <p>{@code edits} is this addon's own code, so it falls under the usual rule for addon code
	 * Diorama calls: if it throws anything, the exceptions the editor throws included, nothing of
	 * the batch is applied or saved, this addon is switched off for the rest of the session (its
	 * handles closed, one line in the log), and what it threw goes on to the caller. An addon
	 * already switched off can still call this; nothing is changed.
	 *
	 * <p>The player unticking the addon in Diorama's Addons settings stops only what it hides;
	 * its edits still go through.
	 *
	 * @param world the world the batch is for
	 * @param edits what to change, given the batch's editor; it may not keep the editor
	 * @return what the batch did: the IDs of the waypoints it added, whether it changed anything,
	 *     and whether that was saved
	 * @throws IllegalStateException if {@code world} isn't the world open now (or there's none),
	 *     before or after {@code edits}; or if this is called from inside another {@code edit}'s
	 *     {@code edits}
	 * @throws NullPointerException if an argument is null
	 */
	EditResult edit(WorldId world, Consumer<WaypointEditor> edits);
}
