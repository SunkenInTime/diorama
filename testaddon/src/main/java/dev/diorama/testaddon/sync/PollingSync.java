package dev.diorama.testaddon.sync;

import dev.diorama.api.BlockCoord;
import dev.diorama.api.DimensionId;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.EditResult;
import dev.diorama.api.MapId;
import dev.diorama.api.NewWaypoint;
import dev.diorama.api.Waypoint;
import dev.diorama.api.WaypointUpdate;
import dev.diorama.api.Waypoints;
import dev.diorama.api.World;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A two-way waypoint sync between Diorama and {@link OtherMod}, written the way the JourneyMap and
 * Xaero's sync addons work, against Diorama's API alone: it is ticked from the addon's own client
 * tick ({@link #tick}), and Diorama never calls it. Each tick it reads {@link DioramaApi#world} and
 * {@link Waypoints#revision}, and the other mod's version; while none moved, it does nothing and
 * allocates nothing. When one moved, it compares both sides with what they were after its last
 * sync, carries each change across (Diorama's side wins when both changed one waypoint), and
 * makes one Diorama edit with everything. Then it reads the revision again, so its own edit isn't
 * taken for a change on the next tick: no echo.
 *
 * <p>Only the player's own waypoints are synced. Each link notes the dimension and saved map its
 * Diorama waypoint is on: one missing from {@link Waypoints#all} is taken as deleted only while
 * that map is the one in use, as otherwise it is on another saved map. A real sync files its links
 * under {@link World#worldId}; this one is for a single world.
 */
public final class PollingSync {
	private final DioramaApi api;
	private final OtherMod other = new OtherMod();
	private World world;
	private long revision = -1;
	private long otherVersion = -1;
	/** Diorama's waypoint ID to the other mod's and where Diorama's is, for each pair kept in step. */
	private final Map<UUID, Link> links = new LinkedHashMap<>();
	/** Each side's waypoints as they were after the last sync, by their own IDs. */
	private final Map<UUID, OtherMod.Pin> lastDiorama = new HashMap<>();
	private final Map<String, OtherMod.Pin> lastOther = new HashMap<>();
	private int edits;
	private int passes;
	private String lastProblem = "";

	public PollingSync(DioramaApi api) {
		this.api = api;
	}

	/** The pretend mod this keeps in step. */
	public OtherMod other() {
		return this.other;
	}

	/** How many Diorama edits it made that changed something. */
	public int edits() {
		return this.edits;
	}

	/** How many ticks found something moved and compared the two sides. */
	public int passes() {
		return this.passes;
	}

	/** What last went wrong, or empty. */
	public String lastProblem() {
		return this.lastProblem;
	}

	/** The other mod's ID for a Diorama waypoint, or null if they aren't linked. */
	public String linkOf(UUID id) {
		Link link = this.links.get(id);
		return link == null ? null : link.other();
	}

	/** One tick: does nothing unless the world, Diorama's waypoints or the other mod's changed. */
	public void tick() {
		Optional<World> open = this.api.world();
		if (open.isEmpty()) {
			return;
		}
		World now = open.get();
		Waypoints waypoints = this.api.waypoints();
		if (now.equals(this.world) && waypoints.revision() == this.revision && this.other.version() == this.otherVersion) {
			return;
		}
		this.world = now;
		this.passes++;
		this.sync(now, waypoints);
	}

	private void sync(World now, Waypoints waypoints) {
		Map<UUID, Waypoint> diorama = new LinkedHashMap<>();
		for (Waypoint waypoint : waypoints.all()) {
			if (waypoint.kind() == Waypoint.Kind.MANUAL) {
				diorama.put(waypoint.id(), waypoint);
			}
		}
		Map<String, OtherMod.Pin> theirs = new LinkedHashMap<>(this.other.pins());
		Map<UUID, OtherMod.Pin> updates = new LinkedHashMap<>();
		List<UUID> removals = new ArrayList<>();
		List<String> adding = new ArrayList<>();
		// Links whose Diorama side is on a saved map not in use: their other side keeps its baseline until it's back.
		Set<String> skipped = new HashSet<>();

		for (Map.Entry<UUID, Link> entry : new ArrayList<>(this.links.entrySet())) {
			Link link = entry.getValue();
			Waypoint mine = diorama.remove(entry.getKey());
			OtherMod.Pin their = theirs.remove(link.other());
			if (mine == null && !now.mapId(link.dimension()).equals(link.map())) {
				// On a saved map not in use: not here, and not deleted.
				skipped.add(link.other());
				continue;
			}
			if (mine == null && their == null) {
				this.unlink(entry.getKey());
			} else if (mine == null) {
				this.other.remove(link.other());
				this.unlink(entry.getKey());
			} else if (their == null) {
				OtherMod.Pin seen = pin(mine);
				if (seen.equals(this.lastDiorama.get(entry.getKey()))) {
					removals.add(entry.getKey());
					this.unlink(entry.getKey());
				} else {
					// Changed in Diorama while deleted in the other mod: Diorama's side wins, so it goes back.
					this.links.put(entry.getKey(), new Link(this.other.add(seen), link.dimension(), link.map()));
				}
			} else {
				OtherMod.Pin seen = pin(mine);
				if (!seen.equals(this.lastDiorama.get(entry.getKey()))) {
					this.other.put(link.other(), seen);
				} else if (!their.equals(this.lastOther.get(link.other()))) {
					updates.put(entry.getKey(), their);
				}
			}
		}
		// What's left on either side is new there.
		for (Waypoint mine : diorama.values()) {
			this.links.put(mine.id(), new Link(this.other.add(pin(mine)), mine.dimension(), mine.mapId()));
		}
		// A pin whose dimension Diorama can't name is left out, so it can't stop the rest of the batch.
		Map<String, DimensionId> dimensions = new HashMap<>();
		for (Map.Entry<String, OtherMod.Pin> pin : theirs.entrySet()) {
			if (this.dimension(pin.getValue(), dimensions) != null) {
				adding.add(pin.getKey());
			}
		}
		updates.values().removeIf(pin -> this.dimension(pin, dimensions) == null);

		if (!updates.isEmpty() || !removals.isEmpty() || !adding.isEmpty()) {
			EditResult result = waypoints.edit(now.worldId(), edits -> {
				for (String id : adding) {
					OtherMod.Pin pin = theirs.get(id);
					edits.add(NewWaypoint.builder(pin.name(), dimensions.get(pin.dimension()), BlockCoord.of(pin.x(), pin.y(), pin.z()))
						.rgb(pin.rgb()).visible(pin.visible()).folder(pin.folder()).build());
				}
				for (Map.Entry<UUID, OtherMod.Pin> update : updates.entrySet()) {
					OtherMod.Pin pin = update.getValue();
					edits.update(update.getKey(), WaypointUpdate.builder().name(pin.name()).dimension(dimensions.get(pin.dimension()))
						.pos(BlockCoord.of(pin.x(), pin.y(), pin.z())).rgb(pin.rgb()).visible(pin.visible()).folder(pin.folder()).build());
				}
				removals.forEach(edits::remove);
			});
			if (result.changed()) {
				this.edits++;
			}
			if (result.saved()) {
				for (int i = 0; i < adding.size(); i++) {
					UUID id = result.added().get(i);
					Optional<Waypoint> made = waypoints.get(id);
					if (made.isPresent()) {
						this.links.put(id, new Link(adding.get(i), made.get().dimension(), made.get().mapId()));
					}
				}
			} else {
				this.lastProblem = "Diorama couldn't save the batch; its new waypoints aren't linked";
			}
		}

		// Both sides as they are now, this sync's own changes included, so the next tick doesn't take them for news.
		this.revision = waypoints.revision();
		this.otherVersion = this.other.version();
		for (Waypoint waypoint : waypoints.all()) {
			Link link = this.links.get(waypoint.id());
			if (link != null) {
				this.lastDiorama.put(waypoint.id(), pin(waypoint));
				// It may have moved to another dimension, so onto that one's map.
				this.links.put(waypoint.id(), new Link(link.other(), waypoint.dimension(), waypoint.mapId()));
			}
		}
		for (Map.Entry<String, OtherMod.Pin> pin : this.other.pins().entrySet()) {
			if (!skipped.contains(pin.getKey())) {
				this.lastOther.put(pin.getKey(), pin.getValue());
			}
		}
		this.lastOther.keySet().retainAll(this.other.pins().keySet());
	}

	/** The pin's dimension as Diorama names it, or null (noted in {@link #lastProblem}) for one it can't. */
	private DimensionId dimension(OtherMod.Pin pin, Map<String, DimensionId> known) {
		DimensionId dimension = known.get(pin.dimension());
		if (dimension == null) {
			try {
				dimension = DimensionId.of(pin.dimension());
				known.put(pin.dimension(), dimension);
			} catch (IllegalArgumentException e) {
				this.lastProblem = "Left out the other mod's waypoint " + pin.name() + ": " + e.getMessage();
			}
		}
		return dimension;
	}

	/** The other mod's ID for a linked waypoint, and the dimension and saved map Diorama's is on. */
	private record Link(String other, DimensionId dimension, MapId map) {
	}

	private void unlink(UUID id) {
		this.links.remove(id);
		this.lastDiorama.remove(id);
	}

	private static OtherMod.Pin pin(Waypoint waypoint) {
		return new OtherMod.Pin(waypoint.name(), waypoint.pos().x(), waypoint.pos().y(), waypoint.pos().z(), waypoint.dimension().toString(), waypoint.rgb(),
			waypoint.visible(), waypoint.folder().orElse(""));
	}
}
