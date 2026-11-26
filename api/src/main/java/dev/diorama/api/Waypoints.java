package dev.diorama.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
 *     to another world or dimension, or a saved map was made, switched or deleted: start again
 *     from {@link #all}. A switch of saved map swaps the waypoints {@code all()} lists for the
 *     other map's, which isn't the player deleting the old map's; a saved map that is no longer
 *     in {@link World#mapIds} was deleted, and its waypoints with it.</li>
 * <li>Read {@link #revision}. If it is the number you last read, nothing here changed, and if the
 *     other mod's side hasn't changed either, stop.</li>
 * <li>Otherwise read {@link #all} and the other mod's waypoints, compare each with what you had,
 *     and work out what to carry across.</li>
 * <li>To change Diorama's side, build one {@link WaypointBatch} with everything and hand it to
 *     {@link #edit} with the world you read in step 1. If it wasn't applied, the world moved on:
 *     keep what you had and start again on the next tick. Otherwise link your waypoints to the IDs
 *     in {@link EditResult#added} at once, then read {@code revision()} again and keep that
 *     number, so your next tick doesn't take your own change for the player's.</li>
 * </ol>
 * Reading {@code world()}, {@code revision()} and {@code all()} when nothing changed costs no
 * allocation, so doing it every tick is fine. Keep links between the two mods' waypoints by
 * {@link Waypoint#id}, and file them under {@link World#worldId} (stable across restarts; see
 * {@link WorldId}), the dimension and the {@link Waypoint#mapId}: a {@link MapId} means something
 * only within one world and dimension, and a waypoint file copied by hand can give two saved maps
 * waypoints with the same ID. A waypoint your addon added has your mod ID in
 * {@link Waypoint#addedBy}.
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
	 * mod's waystones, a switch of saved map, another world, and any addon's {@link #edit} that
	 * changed something,
	 * yours included. It never comes back to a number it had for other waypoints, even in another
	 * world, so comparing it with the last number read is enough; it isn't saved, and starts over
	 * when the game starts. 0 while there's no world.
	 */
	long revision();

	/**
	 * Applies a batch of waypoint changes all at once, if the world is still the one it was
	 * prepared for, and writes {@code waypoints.json} once (and {@code folders.json} once, if the
	 * batch made folders). When this returns, {@link #all} and {@link #revision} already show it.
	 * None of the addon's code runs meanwhile.
	 *
	 * <p>{@code preparedFor} is the {@link DioramaApi#world} the batch was worked out from. Unless
	 * {@code world()} still {@linkplain World#equals equals} it (the same world, dimension and saved
	 * maps), nothing is applied and the result says so ({@link EditResult#applied}): read again and
	 * retry on the next poll. So a batch never lands in waypoints it wasn't worked out from, or on
	 * another saved map.
	 *
	 * <p>Only player-made waypoints ({@link Waypoint.Kind#MANUAL}) can be changed or removed: the
	 * others are Diorama's or another mod's, and kept up to date by them. A step that names one of
	 * them throws, and nothing of the batch is applied. A step whose waypoint isn't there (the
	 * player may just have deleted it) is skipped, and {@link EditResult#missing} names it. A batch
	 * that, once all its steps are applied, changes nothing (a waypoint renamed and back, say)
	 * writes nothing and doesn't move the revision.
	 *
	 * <p>The player unticking the addon in Diorama's Addons settings stops only what it hides; its
	 * edits still go through. An addon Diorama switched off gets a result that applied nothing.
	 *
	 * @param preparedFor the world the batch was worked out for
	 * @param batch what to change
	 * @return what the batch did: whether it was applied, the IDs of the waypoints it added, the
	 *     steps that found no waypoint, whether it changed anything, and whether that was saved
	 * @throws IllegalArgumentException if a step names a waypoint that isn't
	 *     {@link Waypoint.Kind#MANUAL}; nothing was applied
	 * @throws NullPointerException if an argument is null
	 */
	EditResult edit(World preparedFor, WaypointBatch batch);
}
