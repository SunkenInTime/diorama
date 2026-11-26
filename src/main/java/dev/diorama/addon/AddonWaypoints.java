package dev.diorama.addon;

import dev.diorama.api.EditResult;
import dev.diorama.api.Waypoint;
import dev.diorama.api.WaypointBatch;
import dev.diorama.api.Waypoints;
import dev.diorama.api.World;
import dev.diorama.waypoint.WaypointStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One addon's way to the waypoints of the world open now ({@link CurrentWorld}), looked up at each
 * call, so it holds nothing that can go stale. What it changes is put down to its addon. Diorama
 * never calls the addon from here: a batch is a value the addon made.
 */
final class AddonWaypoints implements Waypoints {
	private final Addon addon;

	AddonWaypoints(Addon addon) {
		this.addon = addon;
	}

	@Override
	public List<Waypoint> all() {
		Addon.checkThread();
		WaypointStore store = CurrentWorld.store();
		return store == null ? List.of() : store.published();
	}

	@Override
	public Optional<Waypoint> get(UUID id) {
		Addon.checkThread();
		Objects.requireNonNull(id, "id");
		WaypointStore store = CurrentWorld.store();
		return store == null ? Optional.empty() : store.published(id);
	}

	@Override
	public long revision() {
		Addon.checkThread();
		WaypointStore store = CurrentWorld.store();
		return store == null ? 0 : store.publishedRevision();
	}

	@Override
	public EditResult edit(World preparedFor, long revision, WaypointBatch batch) {
		Addon.checkThread();
		Objects.requireNonNull(preparedFor, "preparedFor");
		Objects.requireNonNull(batch, "batch");
		WaypointStore store = CurrentWorld.storeIfStill(preparedFor);
		// The waypoints the batch was worked out from, or it would land on whatever took their place.
		if (store == null || this.addon.failed() || store.publishedRevision() != revision) {
			return WaypointStore.notApplied();
		}
		// No addon code runs from here on: the world can't move under the batch.
		return store.apply(this.addon.id(), batch);
	}

	@Override
	public String toString() {
		return "Diorama waypoints for " + this.addon.id();
	}
}
