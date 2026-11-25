package dev.diorama.waypoint;

import dev.diorama.Diorama;
import dev.diorama.Maps;
import dev.diorama.Settings;
import dev.diorama.api.BlockCoord;
import dev.diorama.api.DimensionId;
import dev.diorama.api.WaypointChange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What addons read of one world's waypoints ({@link dev.diorama.api.Waypoints#all}): the waypoints
 * the waypoint list shows, its waystones included, as snapshots that never change. The list is
 * made again only after something changed, and a waypoint that didn't change keeps its snapshot,
 * so reading it costs nothing and comparing two lists says what changed. Client thread only.
 */
final class WaypointSnapshots {
	/** Dimension IDs by the string waypoints keep, so the snapshots share them; null for one that isn't valid. */
	private static final Map<String, Optional<DimensionId>> DIMENSIONS = new HashMap<>();
	private static final Set<String> WARNED = new HashSet<>();

	private final WaypointStore store;
	private List<dev.diorama.api.Waypoint> list = List.of();
	private Map<UUID, Snapshot> byId = Map.of();
	/** A change was made without anyone listening, so the list is made again when next read. */
	private boolean stale = true;
	/** What the list was made with of what changes outside the store: Waystones' count of changes, and the portal pin setting. */
	private int waystoneChanges;
	private boolean portalPins;

	WaypointSnapshots(WaypointStore store) {
		this.store = store;
	}

	/** The list as last made; {@link #refresh} first. */
	List<dev.diorama.api.Waypoint> all() {
		return this.list;
	}

	/** One waypoint of the list as last made. */
	Optional<dev.diorama.api.Waypoint> get(UUID id) {
		Snapshot snapshot = this.byId.get(id);
		return snapshot == null ? Optional.empty() : snapshot.self;
	}

	/** The snapshot of the list as last made with this ID, and the waypoint behind it; null if none. */
	@Nullable Snapshot snapshot(UUID id) {
		return this.byId.get(id);
	}

	/** Something changed that the list will show, with no one listening for changes. */
	void stale() {
		this.stale = true;
	}

	/** Whether Waystones or the portal pin setting changed since the list was made. */
	boolean outsideChanged() {
		return this.waystoneChanges != Waystones.changes() || this.portalPins != Settings.get().portalMarkers;
	}

	/**
	 * Makes the list again if a change was made since, and with {@code outside} if Waystones or the
	 * portal pin setting changed too, telling no one.
	 */
	void refresh(boolean outside) {
		if (this.stale || outside && this.outsideChanged()) {
			this.rebuild(false);
		}
	}

	/**
	 * Makes the list again from the store. With {@code compare}, returns what changed since it was
	 * last made, in the list's order, with the removed ones last; otherwise null. A waypoint that
	 * didn't change keeps its snapshot, and if none did, the list itself stays the same instance.
	 */
	@Nullable List<WaypointChange> rebuild(boolean compare) {
		this.waystoneChanges = Waystones.changes();
		this.portalPins = Settings.get().portalMarkers;
		this.stale = false;
		List<Waypoint> listed = this.store.listed();
		List<Snapshot> next = new ArrayList<>(listed.size());
		Map<UUID, Snapshot> nextById = new HashMap<>(listed.size() * 4 / 3 + 1);
		for (Waypoint waypoint : listed) {
			DimensionId dimension = dimension(waypoint.dimension);
			if (dimension == null) {
				continue;
			}
			String folder = waypoint.kind == Waypoint.Kind.WAYSTONE ? null : this.store.folderName(waypoint.folder);
			Snapshot old = this.byId.get(waypoint.id);
			Snapshot snapshot = old != null && old.matches(waypoint, folder) ? old : new Snapshot(waypoint, dimension, folder);
			next.add(snapshot);
			nextById.putIfAbsent(waypoint.id, snapshot);
		}
		List<WaypointChange> changes = null;
		if (compare) {
			changes = new ArrayList<>();
			for (Snapshot now : next) {
				if (nextById.get(now.id) != now) {
					// A second waypoint with an ID already listed, as a hand-edited file can have.
					continue;
				}
				Snapshot before = this.byId.get(now.id);
				if (before == null) {
					changes.add(new Change(WaypointChange.Type.ADDED, now.id, Optional.empty(), now.self));
				} else if (before != now) {
					changes.add(new Change(WaypointChange.Type.CHANGED, now.id, before.self, now.self));
				}
			}
			for (dev.diorama.api.Waypoint was : this.list) {
				Snapshot before = (Snapshot) was;
				if (this.byId.get(before.id) == before && !nextById.containsKey(before.id)) {
					changes.add(new Change(WaypointChange.Type.REMOVED, before.id, before.self, Optional.empty()));
				}
			}
		}
		if (!sameAs(next)) {
			this.list = List.copyOf(next);
			this.byId = nextById;
		}
		return changes;
	}

	private boolean sameAs(List<Snapshot> next) {
		if (next.size() != this.list.size()) {
			return false;
		}
		for (int i = 0; i < next.size(); i++) {
			if (next.get(i) != this.list.get(i)) {
				return false;
			}
		}
		return true;
	}

	private static @Nullable DimensionId dimension(String id) {
		Optional<DimensionId> known = DIMENSIONS.get(id);
		if (known == null) {
			try {
				known = Optional.of(DimensionId.of(id));
			} catch (IllegalArgumentException e) {
				known = Optional.empty();
				if (WARNED.add(id)) {
					Diorama.LOGGER.warn("Addons don't see the waypoints in dimension \"{}\": that isn't a dimension ID", id);
				}
			}
			DIMENSIONS.put(id, known);
		}
		return known.orElse(null);
	}

	/** The map ID addons see: {@code main} for a dimension's first map, whose ID is empty. */
	static String mapId(String id) {
		return id.isEmpty() ? "main" : id;
	}

	private static dev.diorama.api.Waypoint.Kind kind(Waypoint.Kind kind) {
		return switch (kind) {
			case MANUAL -> dev.diorama.api.Waypoint.Kind.MANUAL;
			case DEATH -> dev.diorama.api.Waypoint.Kind.DEATH;
			case BED -> dev.diorama.api.Waypoint.Kind.BED;
			case TEMPORARY -> dev.diorama.api.Waypoint.Kind.TEMPORARY;
			case WAYSTONE -> dev.diorama.api.Waypoint.Kind.WAYSTONE;
			case NETHER_PORTAL -> dev.diorama.api.Waypoint.Kind.NETHER_PORTAL;
			case END_PORTAL -> dev.diorama.api.Waypoint.Kind.END_PORTAL;
			case END_GATEWAY -> dev.diorama.api.Waypoint.Kind.END_GATEWAY;
		};
	}

	/** One waypoint as addons read it. Its getters allocate nothing. */
	static final class Snapshot implements dev.diorama.api.Waypoint {
		/** The waypoint this was made from, which an addon's edit changes. */
		final Waypoint source;
		final UUID id;
		private final String name;
		private final String initials;
		private final String icon;
		private final BlockCoord pos;
		private final String dimensionName;
		private final DimensionId dimension;
		private final int color;
		private final boolean visible;
		private final @Nullable String folderName;
		private final Optional<String> folder;
		private final String map;
		private final Waypoint.Kind internalKind;
		private final Kind kind;
		private final @Nullable String addedByName;
		private final Optional<String> addedBy;
		final Optional<dev.diorama.api.Waypoint> self;

		Snapshot(Waypoint waypoint, DimensionId dimension, @Nullable String folder) {
			this.source = waypoint;
			this.id = waypoint.id;
			this.name = waypoint.name;
			this.initials = waypoint.initials;
			this.icon = waypoint.icon;
			this.pos = BlockCoord.of(waypoint.x, waypoint.y, waypoint.z);
			this.dimensionName = waypoint.dimension;
			this.dimension = dimension;
			this.color = waypoint.color;
			this.visible = waypoint.visible;
			this.folderName = folder;
			this.folder = Optional.ofNullable(folder);
			this.map = Maps.id(waypoint.map);
			this.internalKind = waypoint.kind;
			this.kind = WaypointSnapshots.kind(waypoint.kind);
			this.addedByName = waypoint.addedBy;
			this.addedBy = Optional.ofNullable(waypoint.addedBy);
			this.self = Optional.of(this);
		}

		/** Whether this still says what {@code waypoint} is, in the folder it's in now. */
		boolean matches(Waypoint waypoint, @Nullable String folder) {
			return this.id.equals(waypoint.id) && this.name.equals(waypoint.name) && this.initials.equals(waypoint.initials) && this.icon.equals(waypoint.icon)
				&& this.pos.x() == waypoint.x && this.pos.y() == waypoint.y && this.pos.z() == waypoint.z && this.dimensionName.equals(waypoint.dimension)
				&& this.color == waypoint.color && this.visible == waypoint.visible && java.util.Objects.equals(this.folderName, folder)
				&& this.map.equals(Maps.id(waypoint.map)) && this.internalKind == waypoint.kind && java.util.Objects.equals(this.addedByName, waypoint.addedBy);
		}

		@Override
		public UUID id() {
			return this.id;
		}

		@Override
		public String name() {
			return this.name;
		}

		@Override
		public String initials() {
			return this.initials;
		}

		@Override
		public String icon() {
			return this.icon;
		}

		@Override
		public BlockCoord pos() {
			return this.pos;
		}

		@Override
		public DimensionId dimension() {
			return this.dimension;
		}

		@Override
		public int color() {
			return this.color;
		}

		@Override
		public boolean visible() {
			return this.visible;
		}

		@Override
		public Optional<String> folder() {
			return this.folder;
		}

		@Override
		public String mapId() {
			return WaypointSnapshots.mapId(this.map);
		}

		@Override
		public Kind kind() {
			return this.kind;
		}

		@Override
		public Optional<String> addedBy() {
			return this.addedBy;
		}

		@Override
		public String toString() {
			return "Waypoint[" + this.name + " " + this.kind + " at " + this.pos.x() + " " + this.pos.y() + " " + this.pos.z() + " in " + this.dimension + ", " + this.id + "]";
		}
	}

	/** One waypoint's change, as a listener hears it. */
	record Change(WaypointChange.Type type, UUID id, Optional<dev.diorama.api.Waypoint> before, Optional<dev.diorama.api.Waypoint> after) implements WaypointChange {
	}
}
