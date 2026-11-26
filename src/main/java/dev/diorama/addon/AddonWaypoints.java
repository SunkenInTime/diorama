package dev.diorama.addon;

import dev.diorama.api.EditResult;
import dev.diorama.api.Waypoint;
import dev.diorama.api.WaypointEditor;
import dev.diorama.api.Waypoints;
import dev.diorama.api.WorldId;
import dev.diorama.waypoint.WaypointStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One addon's way to the waypoints of the world open now ({@link CurrentWorld}), looked up at each
 * call, so it holds nothing that can go stale. What it changes is put down to its addon. Diorama
 * never calls the addon from here but for the batch it hands {@link #edit}.
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
	public EditResult edit(WorldId world, Consumer<WaypointEditor> edits) {
		Addon.checkThread();
		Objects.requireNonNull(world, "world");
		Objects.requireNonNull(edits, "edits");
		WaypointStore store = CurrentWorld.storeFor(world);
		if (this.addon.failed()) {
			return WaypointStore.nothingEdited();
		}
		return store.edit(this.addon.id(), editor -> {
			try {
				edits.accept(editor);
			} catch (Throwable thrown) {
				// The addon's own code: it's switched off, and the batch goes nowhere.
				this.addon.fail("a Waypoints.edit batch", thrown);
				throw Addon.rethrow(thrown);
			}
		}, () -> {
			// The batch ran addon code, which may have done anything: it lands only where it was meant to.
			if (CurrentWorld.storeFor(world) != store) {
				throw new IllegalStateException("World " + world + "'s waypoints were loaded again while the batch was being made (the player joined it again): nothing was changed");
			}
		});
	}

	@Override
	public String toString() {
		return "Diorama waypoints for " + this.addon.id();
	}
}
