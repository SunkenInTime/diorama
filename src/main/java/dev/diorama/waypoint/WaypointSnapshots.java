package dev.diorama.waypoint;

import dev.diorama.Diorama;
import dev.diorama.Maps;
import dev.diorama.Settings;
import dev.diorama.api.BlockCoord;
import dev.diorama.api.DimensionId;
import dev.diorama.api.MapId;
import dev.diorama.api.ResourceId;
import dev.diorama.api.WaypointChange;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What addons read of one world's waypoints ({@link dev.diorama.api.Waypoints#all}): the waypoints
 * the waypoint list shows, its waystones included, as snapshots that never change. The list is
 * made again only after something changed, and a waypoint that didn't change keeps its snapshot,
 * so reading it costs nothing and comparing two lists says what changed. Client thread only.
 *
 * <p>It's out of date ({@link #pending}) when the store's {@link WaypointStore#revision} moved on
 * since it was made, or Waystones' count of changes, or the portal pin setting: every change that
 * can change what the list holds moves one of those three.
 */
final class WaypointSnapshots {
	/** Dimension IDs by the string waypoints keep, so the snapshots share them; empty for one that isn't valid. */
	private static final Map<String, Optional<DimensionId>> DIMENSIONS = new HashMap<>();
	/** Icons by the string waypoints keep; empty for none, or one that isn't a resource ID. */
	private static final Map<String, Optional<ResourceId>> ICONS = new HashMap<>();
	/** Map IDs by Diorama's own, which is empty for the main map. */
	private static final Map<String, MapId> MAPS = new HashMap<>();
	private static final Set<String> WARNED = new HashSet<>();

	private final WaypointStore store;
	private List<dev.diorama.api.Waypoint> list = List.of();
	/** The first snapshot in the list with each ID. */
	private Map<UUID, Snapshot> byId = Map.of();
	/** What the list was made from: the store's revision, Waystones' count of changes, and the portal pin setting. */
	private int revision = -1;
	private int waystoneChanges;
	private boolean portalPins;

	WaypointSnapshots(WaypointStore store) {
		this.store = store;
	}

	/** The list as last made; {@link #refresh} first. */
	List<dev.diorama.api.Waypoint> all() {
		return this.list;
	}

	/** One waypoint of the list as last made: the first with this ID. */
	Optional<dev.diorama.api.Waypoint> get(UUID id) {
		Snapshot snapshot = this.byId.get(id);
		return snapshot == null ? Optional.empty() : snapshot.self;
	}

	/** The snapshot of the list as last made with this ID, and the waypoint behind it; null if none. */
	@Nullable Snapshot snapshot(UUID id) {
		return this.byId.get(id);
	}

	/** Whether something changed since the list was made that may change it. */
	boolean pending() {
		return this.revision != this.store.revision() || this.waystoneChanges != Waystones.changes() || this.portalPins != Settings.get().portalMarkers;
	}

	/** Makes the list again if it's out of date, telling no one. */
	void refresh() {
		if (this.pending()) {
			this.rebuild(false);
		}
	}

	/**
	 * Makes the list again from the store. With {@code compare}, returns what changed since it was
	 * last made, in the list's order, with the removed ones last; otherwise null. A waypoint that
	 * didn't change keeps its snapshot, and if none did, the list itself stays the same instance.
	 * A second waypoint with an ID already listed (a hand-edited file can have one) is listed, but
	 * the ID stays the first's: comparing goes by the first with each ID.
	 */
	@Nullable List<WaypointChange> rebuild(boolean compare) {
		this.revision = this.store.revision();
		this.portalPins = Settings.get().portalMarkers;
		List<Waypoint> listed = this.store.listed();
		// After listing, which can count a change itself (a waystone placed again).
		this.waystoneChanges = Waystones.changes();
		List<Snapshot> next = new ArrayList<>(listed.size());
		Map<UUID, Snapshot> nextById = new HashMap<>(listed.size() * 4 / 3 + 1);
		Map<UUID, Snapshot> oldById = this.byId;
		for (Waypoint waypoint : listed) {
			DimensionId dimension = dimension(waypoint.dimension);
			if (dimension == null) {
				continue;
			}
			String folder = waypoint.kind == Waypoint.Kind.WAYSTONE ? null : this.store.folderName(waypoint.folder);
			Snapshot old = oldById.get(waypoint.id);
			Snapshot snapshot = old != null && old.source == waypoint && old.sameAs(waypoint, folder) ? old : new Snapshot(waypoint, dimension, folder);
			next.add(snapshot);
			nextById.putIfAbsent(waypoint.id, snapshot);
		}
		List<WaypointChange> changes = null;
		if (compare) {
			changes = new ArrayList<>();
			for (Snapshot now : next) {
				if (nextById.get(now.id) != now) {
					continue;
				}
				Snapshot before = oldById.get(now.id);
				if (before == null) {
					changes.add(new Change(WaypointChange.Type.ADDED, now.id, Optional.empty(), now.self));
				} else if (before != now && !before.sameAs(now.source, now.folderName)) {
					// The same ID may now be another waypoint (its first went, or the waystone came again): told only if what addons read changed.
					changes.add(new Change(WaypointChange.Type.CHANGED, now.id, before.self, now.self));
				}
			}
			for (dev.diorama.api.Waypoint was : this.list) {
				Snapshot before = (Snapshot) was;
				if (oldById.get(before.id) == before && !nextById.containsKey(before.id)) {
					changes.add(new Change(WaypointChange.Type.REMOVED, before.id, before.self, Optional.empty()));
				}
			}
		}
		if (!this.sameAs(next)) {
			this.list = List.copyOf(next);
		}
		// Always the new index: the same snapshots in the same order can stand for other waypoints.
		this.byId = nextById;
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

	private static Optional<ResourceId> icon(String icon) {
		Optional<ResourceId> known = ICONS.get(icon);
		if (known == null) {
			try {
				known = icon.isEmpty() ? Optional.empty() : Optional.of(ResourceId.of(icon));
			} catch (IllegalArgumentException e) {
				known = Optional.empty();
			}
			ICONS.put(icon, known);
		}
		return known;
	}

	/**
	 * The map ID addons see for one of Diorama's: {@link MapId#MAIN} for a dimension's first map,
	 * whose ID is empty. Diorama makes the others from hex digits; one a hand edit left that
	 * {@link MapId} wouldn't take is given hex digits of its own, the same each time.
	 */
	static MapId mapId(String id) {
		if (id.isEmpty()) {
			return MapId.MAIN;
		}
		MapId known = MAPS.get(id);
		if (known == null) {
			try {
				known = MapId.of(id);
			} catch (IllegalArgumentException e) {
				known = MapId.of(hash(id));
			}
			if (known.equals(MapId.MAIN)) {
				// A map Diorama keeps under the name "main" isn't the first one.
				known = MapId.of(hash(id));
			}
			MAPS.put(id, known);
		}
		return known;
	}

	private static String hash(String id) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(("map:" + id).getBytes(StandardCharsets.UTF_8));
			return "x" + HexFormat.of().formatHex(hash, 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
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
		private final String iconName;
		private final Optional<ResourceId> icon;
		private final BlockCoord pos;
		private final String dimensionName;
		private final DimensionId dimension;
		private final int color;
		private final boolean visible;
		final @Nullable String folderName;
		private final Optional<String> folder;
		private final String map;
		private final MapId mapId;
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
			this.iconName = waypoint.icon;
			this.icon = WaypointSnapshots.icon(waypoint.icon);
			this.pos = BlockCoord.of(waypoint.x, waypoint.y, waypoint.z);
			this.dimensionName = waypoint.dimension;
			this.dimension = dimension;
			this.color = waypoint.color;
			this.visible = waypoint.visible;
			this.folderName = folder;
			this.folder = Optional.ofNullable(folder);
			// A waystone keeps no map: it's on every one, and reads as the main.
			this.map = waypoint.kind == Waypoint.Kind.WAYSTONE ? Maps.MAIN : Maps.id(waypoint.map);
			this.mapId = WaypointSnapshots.mapId(this.map);
			this.internalKind = waypoint.kind;
			this.kind = WaypointSnapshots.kind(waypoint.kind);
			this.addedByName = waypoint.addedBy;
			this.addedBy = Optional.ofNullable(waypoint.addedBy);
			this.self = Optional.of(this);
		}

		/** Whether this says what {@code waypoint} says, in the folder it's in now: what addons read is the same. */
		boolean sameAs(Waypoint waypoint, @Nullable String folder) {
			return this.id.equals(waypoint.id) && this.name.equals(waypoint.name) && this.initials.equals(waypoint.initials) && this.iconName.equals(waypoint.icon)
				&& this.pos.x() == waypoint.x && this.pos.y() == waypoint.y && this.pos.z() == waypoint.z && this.dimensionName.equals(waypoint.dimension)
				&& this.color == waypoint.color && this.visible == waypoint.visible && Objects.equals(this.folderName, folder)
				&& this.map.equals(waypoint.kind == Waypoint.Kind.WAYSTONE ? Maps.MAIN : Maps.id(waypoint.map)) && this.internalKind == waypoint.kind
				&& Objects.equals(this.addedByName, waypoint.addedBy);
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
		public Optional<ResourceId> icon() {
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
		public int rgb() {
			return this.color & 0xFFFFFF;
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
		public MapId mapId() {
			return this.mapId;
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
