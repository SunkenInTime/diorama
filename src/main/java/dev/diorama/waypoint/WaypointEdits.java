package dev.diorama.waypoint;

import dev.diorama.Maps;
import dev.diorama.api.EditResult;
import dev.diorama.api.NewWaypoint;
import dev.diorama.api.ResourceId;
import dev.diorama.api.WaypointBatch;
import dev.diorama.api.WaypointUpdate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Applies one addon's {@link WaypointBatch} to a store ({@link WaypointStore#apply}), once the
 * addon's world and revision were found to be the ones open now, so the waypoints addons read are
 * the ones the batch was worked out from. Only Diorama's code and the API's own final classes run
 * here, never the addon's. Every step is checked first, against those waypoints, so a batch that
 * names one addons may not change, or one they don't see, changes nothing. Whether the batch
 * changed anything is told from the waypoints as they end up, against how they were before it,
 * as they're kept: a waypoint renamed and back is no change. Folders are looked up, and made,
 * only for the waypoints a step put in one, and for where they end up.
 */
final class WaypointEdits {
	/** What a batch that changed nothing did. */
	static final EditResult UNCHANGED = new Result(true, List.of(), false, true);
	/** What a batch that wasn't applied did. */
	static final EditResult NOT_APPLIED = new Result(false, List.of(), false, true);

	private WaypointEdits() {
	}

	/** One step, its waypoint found: what the store applies. */
	private record Planned(WaypointBatch.Step step, @Nullable Waypoint target, @Nullable Waypoint made) {
	}

	/**
	 * Applies the batch: checks every step, then makes the changes, then the folders the waypoints
	 * a step put in one ended up in. Nothing is saved here.
	 *
	 * @throws IllegalArgumentException if a step names a waypoint addons don't see, one that isn't
	 *     MANUAL, or one an earlier step removes; nothing changed
	 */
	static Applied apply(WaypointStore store, String by, WaypointBatch batch) {
		WaypointSnapshots snapshots = store.snapshots();
		snapshots.refresh();
		// First every step is checked and its waypoint found, so a refusal changes nothing.
		List<Planned> plan = new ArrayList<>(batch.steps().size());
		Set<UUID> removed = new HashSet<>();
		// Every ID in use, made only for a batch that adds: a new waypoint's random ID is checked against them.
		Set<UUID> taken = null;
		for (WaypointBatch.Step step : batch.steps()) {
			if (step instanceof WaypointBatch.Add add) {
				Waypoint made = made(add.waypoint(), by);
				if (taken == null) {
					taken = store.ids();
				}
				while (!taken.add(made.id)) {
					made.id = UUID.randomUUID();
				}
				plan.add(new Planned(step, null, made));
				continue;
			}
			UUID id = step instanceof WaypointBatch.Update update ? update.id() : ((WaypointBatch.Remove) step).id();
			if (removed.contains(id)) {
				throw new IllegalArgumentException("An earlier step of the batch removes waypoint " + id + ", so a later one can't change or remove it. Nothing of the"
					+ " batch was applied");
			}
			WaypointSnapshots.Snapshot snapshot = snapshots.snapshot(id);
			if (snapshot == null) {
				// The revision matched, so the addon read the waypoints as they are: it never saw this one.
				throw new IllegalArgumentException("Waypoint " + id + " isn't one of the waypoints all() lists, so no step can change or remove it. Nothing of the"
					+ " batch was applied");
			}
			if (snapshot.source.kind != Waypoint.Kind.MANUAL) {
				throw new IllegalArgumentException("Waypoint " + id + " is a " + snapshot.kind() + " waypoint, which addons can read but not change: only MANUAL ones are"
					+ " theirs to change. Nothing of the batch was applied");
			}
			if (step instanceof WaypointBatch.Remove) {
				removed.add(id);
			}
			plan.add(new Planned(step, snapshot.source, null));
		}

		// How each waypoint an update reaches was before, to tell at the end whether it changed.
		Map<Waypoint, Before> before = new IdentityHashMap<>();
		for (Planned planned : plan) {
			if (planned.step() instanceof WaypointBatch.Update && !before.containsKey(planned.target())) {
				before.put(planned.target(), new Before(planned.target()));
			}
		}
		List<UUID> added = new ArrayList<>();
		// The waypoints a step put in a folder: only theirs is looked up, and made if there's none.
		Set<Waypoint> foldered = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<Waypoint> gone = Collections.newSetFromMap(new IdentityHashMap<>());
		boolean changed = false;
		for (Planned planned : plan) {
			if (planned.made() != null) {
				store.addEdited(planned.made());
				added.add(planned.made().id);
				foldered.add(planned.made());
				changed = true;
			} else if (planned.step() instanceof WaypointBatch.Update update) {
				Waypoint target = Objects.requireNonNull(planned.target());
				update(store, target, update.update());
				if (update.update().folder().isPresent()) {
					foldered.add(target);
				}
			} else {
				store.removeEdited(Objects.requireNonNull(planned.target()));
				gone.add(planned.target());
				changed = true;
			}
		}
		// Folders last, for where the waypoints ended up: one an update moved through isn't made.
		boolean madeFolders = false;
		for (Waypoint waypoint : foldered) {
			if (!gone.contains(waypoint)) {
				madeFolders |= store.useFolder(waypoint);
			}
		}
		for (Map.Entry<Waypoint, Before> each : before.entrySet()) {
			if (!gone.contains(each.getKey()) && !each.getValue().same(each.getKey())) {
				changed = true;
			}
		}
		// A made folder is a change too, though only a waypoint that changed folders can make one.
		changed |= madeFolders;
		return new Applied(changed, List.copyOf(added), madeFolders);
	}

	private static Waypoint made(NewWaypoint waypoint, String by) {
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
		made.addedBy = by;
		return made;
	}

	private static void update(WaypointStore store, Waypoint target, WaypointUpdate update) {
		update.name().ifPresent(name -> target.name = name);
		update.initials().ifPresent(initials -> target.initials = initials);
		if (update.removesIcon()) {
			target.icon = "";
		} else {
			update.icon().ifPresent(icon -> target.icon = icon.toString());
		}
		update.pos().ifPresent(pos -> {
			target.x = pos.x();
			target.y = pos.y();
			target.z = pos.z();
		});
		if (update.rgb().isPresent()) {
			target.color = 0xFF000000 | update.rgb().getAsInt();
		}
		update.visible().ifPresent(visible -> target.visible = visible);
		if (update.dimension().isPresent() && !update.dimension().get().toString().equals(target.dimension)) {
			target.dimension = update.dimension().get().toString();
			store.onMapInUse(target);
		}
		// Taken as given here; the folder's spelling, or a new folder, once every step is applied.
		update.folder().ifPresent(folder -> target.folder = folder);
	}

	/**
	 * A waypoint as the store keeps it, before a batch: its folder as written in the file, not as
	 * the folders resolve it, so taking a waypoint out of a folder that no longer exists is a change
	 * that's saved.
	 */
	private record Before(String name, String initials, String icon, int x, int y, int z, int color, boolean visible, String folder, String dimension, String map) {
		Before(Waypoint waypoint) {
			this(waypoint.name, waypoint.initials, waypoint.icon == null ? "" : waypoint.icon, waypoint.x, waypoint.y, waypoint.z, waypoint.color, waypoint.visible,
				waypoint.folder == null ? "" : waypoint.folder, waypoint.dimension, Maps.id(waypoint.map));
		}

		boolean same(Waypoint waypoint) {
			return this.equals(new Before(waypoint));
		}
	}

	/** A batch applied, before its save: whether it changed anything, and what it reports. */
	record Applied(boolean changed, List<UUID> added, boolean madeFolders) {
		/** What it reports once saved, or with nothing to save. */
		EditResult result(boolean saved) {
			return this.changed ? new Result(true, this.added, true, saved) : UNCHANGED;
		}
	}

	private record Result(boolean applied, List<UUID> added, boolean changed, boolean saved) implements EditResult {
	}
}
