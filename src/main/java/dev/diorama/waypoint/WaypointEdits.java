package dev.diorama.waypoint;

import dev.diorama.addon.AddonWorlds;
import dev.diorama.api.NewWaypoint;
import dev.diorama.api.WaypointEditor;
import dev.diorama.api.WaypointUpdate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One addon's edit batch ({@link dev.diorama.api.Waypoints#edit}): each call is checked as the
 * addon makes it, against the waypoints as they are and what the batch did so far, and kept; the
 * store applies them all once the addon's code returns ({@link WaypointStore#edit}), so an addon
 * that throws halfway changes nothing.
 */
final class WaypointEdits implements WaypointEditor {
	private final WaypointStore store;
	private final String by;
	private final List<Runnable> steps = new ArrayList<>();
	/** Waypoints this batch adds, by ID, not yet in the store. */
	private final Map<UUID, Waypoint> added = new HashMap<>();
	private final Set<UUID> removed = new HashSet<>();
	private boolean open = true;

	WaypointEdits(WaypointStore store, String by) {
		this.store = store;
		this.by = by;
	}

	@Override
	public UUID add(NewWaypoint waypoint) {
		this.check();
		Objects.requireNonNull(waypoint, "waypoint");
		Waypoint made = new Waypoint();
		made.name = waypoint.name();
		made.initials = waypoint.initials();
		made.icon = waypoint.icon();
		made.x = waypoint.pos().x();
		made.y = waypoint.pos().y();
		made.z = waypoint.pos().z();
		made.dimension = waypoint.dimension().toString();
		made.color = waypoint.color();
		made.visible = waypoint.visible();
		made.folder = waypoint.folder();
		made.kind = Waypoint.Kind.MANUAL;
		made.addedBy = this.by;
		this.added.put(made.id, made);
		this.steps.add(() -> {
			this.store.addEdited(made);
			this.store.useFolder(made);
		});
		return made.id;
	}

	@Override
	public boolean update(UUID id, WaypointUpdate update) {
		this.check();
		Objects.requireNonNull(update, "update");
		Waypoint target = this.target(id);
		if (target == null) {
			return false;
		}
		this.steps.add(() -> {
			update.name().ifPresent(name -> target.name = name);
			update.initials().ifPresent(initials -> target.initials = initials);
			update.icon().ifPresent(icon -> target.icon = icon);
			update.pos().ifPresent(pos -> {
				target.x = pos.x();
				target.y = pos.y();
				target.z = pos.z();
			});
			update.color().ifPresent(color -> target.color = color);
			update.visible().ifPresent(visible -> target.visible = visible);
			update.dimension().ifPresent(dimension -> {
				if (!dimension.toString().equals(target.dimension)) {
					target.dimension = dimension.toString();
					this.store.onMapInUse(target);
				}
			});
			update.folder().ifPresent(folder -> {
				target.folder = folder;
				this.store.useFolder(target);
			});
		});
		return true;
	}

	@Override
	public boolean remove(UUID id) {
		this.check();
		Waypoint target = this.target(id);
		if (target == null) {
			return false;
		}
		this.removed.add(id);
		this.steps.add(() -> this.store.removeEdited(target));
		return true;
	}

	/** The waypoint with this ID as the batch has it so far, if addons may change it; null if there's none. */
	private @Nullable Waypoint target(UUID id) {
		Objects.requireNonNull(id, "id");
		if (this.removed.contains(id)) {
			return null;
		}
		Waypoint added = this.added.get(id);
		if (added != null) {
			return added;
		}
		WaypointSnapshots.Snapshot snapshot = this.store.snapshots().snapshot(id);
		if (snapshot == null) {
			return null;
		}
		if (snapshot.source.kind != Waypoint.Kind.MANUAL) {
			throw new IllegalArgumentException("Waypoint " + id + " is a " + snapshot.kind() + " waypoint, which addons can read but not change: only MANUAL ones are theirs to change");
		}
		return snapshot.source;
	}

	private void check() {
		if (!this.open) {
			throw new IllegalStateException("This WaypointEditor's batch is over: use the one each edit() hands its edits");
		}
		AddonWorlds.checkThread();
	}

	/** The addon's code has returned: no more calls. */
	void close() {
		this.open = false;
	}

	boolean isEmpty() {
		return this.steps.isEmpty();
	}

	/** Applies the batch to the store, in the order the addon made its calls. Nothing is saved yet. */
	void apply() {
		for (Runnable step : this.steps) {
			step.run();
		}
	}
}
