package dev.diorama.waypoint;

import dev.diorama.addon.Addons;
import dev.diorama.api.EditResult;
import dev.diorama.api.NewWaypoint;
import dev.diorama.api.ResourceId;
import dev.diorama.api.WaypointEditor;
import dev.diorama.api.WaypointUpdate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * One addon's edit batch ({@link dev.diorama.api.Waypoints#edit}): each call is checked as the
 * addon makes it, against the waypoints addons read, and kept; the store applies them all once the
 * addon's code returns ({@link WaypointStore#edit}), so an addon that throws halfway changes
 * nothing: no waypoint, folder or order, and no save. The steps run only Diorama's code and the
 * API's own final classes, never the addon's. Each step says whether it changed anything, so a
 * batch that only sets what's there already is no change at all.
 */
final class WaypointEdits implements WaypointEditor {
	/** What a batch that changed nothing did. */
	static final EditResult NOTHING = new Result(List.of(), false, true);

	private final WaypointStore store;
	private final String by;
	private final List<BooleanSupplier> steps = new ArrayList<>();
	private final List<UUID> added = new ArrayList<>();
	private final Set<UUID> removed = new HashSet<>();
	private boolean open = true;
	/** Applying it made one of the player's folders, so folders.json is written once after. */
	private boolean madeFolders;

	WaypointEdits(WaypointStore store, String by) {
		this.store = store;
		this.by = by;
	}

	@Override
	public void add(NewWaypoint waypoint) {
		this.check();
		Objects.requireNonNull(waypoint, "waypoint");
		Waypoint made = new Waypoint();
		made.name = waypoint.name();
		made.initials = waypoint.initials();
		made.icon = waypoint.icon().map(ResourceId::toString).orElse("");
		made.x = waypoint.pos().x();
		made.y = waypoint.pos().y();
		made.z = waypoint.pos().z();
		made.dimension = waypoint.dimension().toString();
		made.color = 0xFF000000 | waypoint.rgb();
		made.visible = waypoint.visible();
		made.folder = waypoint.folder();
		made.kind = Waypoint.Kind.MANUAL;
		made.addedBy = this.by;
		this.added.add(made.id);
		this.steps.add(() -> {
			this.store.addEdited(made);
			this.madeFolders |= this.store.useFolder(made);
			return true;
		});
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
			boolean changed = false;
			if (update.name().isPresent() && !update.name().get().equals(target.name)) {
				target.name = update.name().get();
				changed = true;
			}
			if (update.initials().isPresent() && !update.initials().get().equals(target.initials)) {
				target.initials = update.initials().get();
				changed = true;
			}
			String icon = update.removesIcon() ? "" : update.icon().map(ResourceId::toString).orElse(target.icon);
			if (!icon.equals(target.icon)) {
				target.icon = icon;
				changed = true;
			}
			if (update.pos().isPresent() && (update.pos().get().x() != target.x || update.pos().get().y() != target.y || update.pos().get().z() != target.z)) {
				target.x = update.pos().get().x();
				target.y = update.pos().get().y();
				target.z = update.pos().get().z();
				changed = true;
			}
			if (update.rgb().isPresent() && (0xFF000000 | update.rgb().getAsInt()) != target.color) {
				target.color = 0xFF000000 | update.rgb().getAsInt();
				changed = true;
			}
			if (update.visible().isPresent() && update.visible().get() != target.visible) {
				target.visible = update.visible().get();
				changed = true;
			}
			if (update.dimension().isPresent() && !update.dimension().get().toString().equals(target.dimension)) {
				target.dimension = update.dimension().get().toString();
				this.store.onMapInUse(target);
				changed = true;
			}
			if (update.folder().isPresent()) {
				String before = target.folder;
				target.folder = update.folder().get();
				boolean made = this.store.useFolder(target);
				this.madeFolders |= made;
				// Folders match whatever their capitals: "mine" for a waypoint in "Mine" is no move.
				if (!made && Objects.equals(this.store.folderName(before), this.store.folderName(target.folder))) {
					target.folder = before;
				} else {
					changed = true;
				}
			}
			return changed;
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
		this.steps.add(() -> {
			this.store.removeEdited(target);
			return true;
		});
		return true;
	}

	/** The waypoint with this ID that addons read, if they may change it; null if there's none, or the batch removed it. */
	private @Nullable Waypoint target(UUID id) {
		Objects.requireNonNull(id, "id");
		if (this.removed.contains(id)) {
			return null;
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
		Addons.checkThread();
	}

	/** The addon's code has returned: no more calls. */
	void close() {
		this.open = false;
	}

	/** Whether applying it made a folder. */
	boolean madeFolders() {
		return this.madeFolders;
	}

	/** Applies the batch to the store, in the order the addon made its calls. Nothing is saved yet. True if anything changed. */
	boolean apply() {
		boolean changed = false;
		for (BooleanSupplier step : this.steps) {
			changed |= step.getAsBoolean();
		}
		return changed;
	}

	/** What the batch did, once applied and saved. */
	EditResult result(boolean saved) {
		return new Result(List.copyOf(this.added), true, saved);
	}

	private record Result(List<UUID> added, boolean changed, boolean saved) implements EditResult {
	}
}
