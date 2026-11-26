package dev.diorama.testaddon.sync;

import dev.diorama.api.BlockCoord;
import dev.diorama.api.DimensionId;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.EditResult;
import dev.diorama.api.MapId;
import dev.diorama.api.NewWaypoint;
import dev.diorama.api.Waypoint;
import dev.diorama.api.WaypointBatch;
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
 * sync, carries each change across (Diorama's side wins when both changed one waypoint), and hands
 * Diorama one {@link WaypointBatch} with everything, together with the world and the revision it
 * read. Then it reads the revision again, so its own edit isn't taken for a change on the next
 * tick: no echo.
 *
 * <p>It hands the batch over with the revision it read before reading {@link Waypoints#all}, and
 * changes the other mod only once Diorama applied the batch. If Diorama didn't (the player or an
 * automatic pin changed something meanwhile), neither side changed, and the next tick works it out
 * again from what's there then.
 *
 * <p>Only the player's own waypoints are synced. Each link is kept under the dimension and saved
 * map its Diorama waypoint is on, as well as its ID, and matches only a waypoint with all three: one
 * missing from {@link Waypoints#all} is taken as deleted only while that map is the one in use, and
 * a waypoint with the same ID in another dimension or on another saved map is another waypoint. So
 * a waypoint the player moves to another dimension in Diorama gets a new copy in the other mod, in
 * place of the old one. A link whose saved map is no longer in {@link World#mapIds} went with that
 * map, so its other side is removed. A real sync also files its links under
 * {@link World#worldId}; this one is for a single world.
 *
 * <p>What the other mod has that Diorama would refuse is made to fit (a name longer than Diorama
 * takes is cut) or left out (a blank name, a dimension Diorama can't name), so one bad pin can't
 * stop the rest.
 */
public final class PollingSync {
	private final DioramaApi api;
	private final OtherMod other = new OtherMod();
	private World world;
	private long revision = -1;
	private long otherVersion = -1;
	/** The other mod's waypoint ID for each Diorama waypoint kept in step, by where Diorama's is. */
	private final Map<Key, String> links = new LinkedHashMap<>();
	/** Each side's waypoints as they were after the last sync, by their own IDs. */
	private final Map<Key, OtherMod.Pin> lastDiorama = new HashMap<>();
	private final Map<String, OtherMod.Pin> lastOther = new HashMap<>();
	private int edits;
	private int passes;
	private String lastProblem = "";
	/** For the game test: runs between working out the batch and handing it to Diorama. */
	private Runnable beforeEdit = () -> {
	};

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

	/** For the game test: something to do after the sync worked out its batch and before it edits, such as the player switching saved maps. */
	public void beforeEdit(Runnable action) {
		this.beforeEdit = action;
	}

	/** The other mod's ID for a Diorama waypoint on a dimension's saved map, or null if they aren't linked. */
	public String linkOf(DimensionId dimension, MapId map, UUID id) {
		return this.links.get(new Key(dimension, map, id));
	}

	/** How many pairs it keeps in step. */
	public int linkCount() {
		return this.links.size();
	}

	/** One tick: does nothing unless the world, Diorama's waypoints or the other mod's changed. */
	public void tick() {
		Optional<World> open = this.api.world();
		if (open.isEmpty()) {
			return;
		}
		World now = open.get();
		Waypoints waypoints = this.api.waypoints();
		long revision = waypoints.revision();
		if (now.equals(this.world) && revision == this.revision && this.other.version() == this.otherVersion) {
			return;
		}
		this.passes++;
		if (this.sync(now, revision, waypoints)) {
			this.world = now;
		}
	}

	/**
	 * One comparison of the two sides, from the world and the revision read just before; false if
	 * Diorama didn't apply the batch because one of them moved on, so nothing changed on either side
	 * and the next tick tries again.
	 */
	private boolean sync(World now, long revision, Waypoints waypoints) {
		// Diorama's side by where each waypoint is, so a link only ever matches its own waypoint.
		Map<Key, Waypoint> diorama = new LinkedHashMap<>();
		for (Waypoint waypoint : waypoints.all()) {
			if (waypoint.kind() == Waypoint.Kind.MANUAL) {
				diorama.put(key(waypoint), waypoint);
			}
		}
		Map<String, OtherMod.Pin> theirs = new LinkedHashMap<>(this.other.pins());
		Map<Key, OtherMod.Pin> updates = new LinkedHashMap<>();
		List<Key> removals = new ArrayList<>();
		// What to change on the other mod's side, and the links that go with it: done only once Diorama took the batch.
		List<Runnable> toOther = new ArrayList<>();
		// Links whose Diorama side is on a saved map not in use: their other side keeps its baseline until it's back.
		Set<String> skipped = new HashSet<>();

		for (Map.Entry<Key, String> link : this.links.entrySet()) {
			Key key = link.getKey();
			String otherId = link.getValue();
			if (!now.mapIds(key.dimension()).contains(key.map())) {
				// Its saved map was deleted, and the waypoint with it.
				theirs.remove(otherId);
				toOther.add(() -> {
					this.other.remove(otherId);
					this.unlink(key);
				});
				continue;
			}
			if (!now.mapId(key.dimension()).equals(key.map())) {
				// On a saved map not in use: not here, and not deleted. A waypoint with its ID on the map in use is another one.
				theirs.remove(otherId);
				skipped.add(otherId);
				continue;
			}
			Waypoint mine = diorama.remove(key);
			OtherMod.Pin their = theirs.remove(otherId);
			if (mine == null && their == null) {
				toOther.add(() -> this.unlink(key));
			} else if (mine == null) {
				toOther.add(() -> {
					this.other.remove(otherId);
					this.unlink(key);
				});
			} else if (their == null) {
				OtherMod.Pin seen = pin(mine);
				if (seen.equals(this.lastDiorama.get(key))) {
					// Unlinked once Diorama has applied the removal.
					removals.add(key);
				} else {
					// Changed in Diorama while deleted in the other mod: Diorama's side wins, so it goes back.
					toOther.add(() -> this.links.put(key, this.other.add(seen)));
				}
			} else {
				OtherMod.Pin seen = pin(mine);
				if (!seen.equals(this.lastDiorama.get(key))) {
					toOther.add(() -> this.other.put(otherId, seen));
				} else if (!their.equals(this.lastOther.get(otherId))) {
					updates.put(key, their);
				}
			}
		}
		// What's left on either side is new there.
		for (Map.Entry<Key, Waypoint> mine : diorama.entrySet()) {
			OtherMod.Pin seen = pin(mine.getValue());
			toOther.add(() -> this.links.put(mine.getKey(), this.other.add(seen)));
		}
		WaypointBatch.Builder batch = WaypointBatch.builder();
		List<String> adding = new ArrayList<>();
		Map<String, DimensionId> dimensions = new HashMap<>();
		for (Map.Entry<String, OtherMod.Pin> pin : theirs.entrySet()) {
			NewWaypoint made = this.toAdd(pin.getValue(), dimensions);
			if (made != null) {
				batch.add(made);
				adding.add(pin.getKey());
			}
		}
		// The updates that move a waypoint to another dimension, so onto that dimension's saved map in use.
		Map<Key, DimensionId> moving = new HashMap<>();
		for (Map.Entry<Key, OtherMod.Pin> update : updates.entrySet()) {
			WaypointUpdate made = this.toUpdate(update.getValue(), dimensions);
			if (made != null) {
				batch.update(update.getKey().id(), made);
				made.dimension().filter(d -> !d.equals(update.getKey().dimension())).ifPresent(d -> moving.put(update.getKey(), d));
			}
		}
		for (Key removal : removals) {
			batch.remove(removal.id());
		}

		if (!batch.isEmpty()) {
			WaypointBatch built = batch.build();
			this.beforeEdit.run();
			EditResult result = waypoints.edit(now, revision, built);
			if (!result.applied()) {
				// Nothing of it was applied, and nothing changed on the other side: keep every link and baseline as they were, and work it out again next tick.
				this.lastProblem = "Diorama's waypoints or world changed before the batch was applied; trying again";
				return false;
			}
			if (result.changed()) {
				this.edits++;
			}
			// Linked at once, saved or not: the waypoints are in Diorama now, and unlinked they'd be copied both ways again.
			for (int i = 0; i < adding.size(); i++) {
				UUID id = result.added().get(i);
				Optional<Waypoint> made = waypoints.get(id);
				if (made.isPresent()) {
					this.links.put(key(made.get()), adding.get(i));
				}
			}
			for (Key removal : removals) {
				this.unlink(removal);
			}
			for (Map.Entry<Key, DimensionId> move : moving.entrySet()) {
				Key from = move.getKey();
				String otherId = this.links.remove(from);
				this.lastDiorama.remove(from);
				if (otherId != null) {
					this.links.put(new Key(move.getValue(), now.mapId(move.getValue()), from.id()), otherId);
				}
			}
			if (!result.saved()) {
				this.lastProblem = "Diorama couldn't save the batch yet; it's in Diorama's memory and Diorama tries again at its next save";
			}
		}
		toOther.forEach(Runnable::run);

		// Both sides as they are now, this sync's own changes included, so the next tick doesn't take them for news.
		this.revision = waypoints.revision();
		this.otherVersion = this.other.version();
		Map<Key, Waypoint> listed = new HashMap<>();
		for (Waypoint waypoint : waypoints.all()) {
			if (waypoint.kind() == Waypoint.Kind.MANUAL) {
				listed.put(key(waypoint), waypoint);
			}
		}
		for (Key key : this.links.keySet()) {
			Waypoint waypoint = listed.get(key);
			if (waypoint != null) {
				this.lastDiorama.put(key, pin(waypoint));
			}
		}
		for (Map.Entry<String, OtherMod.Pin> pin : this.other.pins().entrySet()) {
			if (!skipped.contains(pin.getKey())) {
				this.lastOther.put(pin.getKey(), pin.getValue());
			}
		}
		this.lastOther.keySet().retainAll(this.other.pins().keySet());
		return true;
	}

	/** The other mod's waypoint as one for Diorama to add, made to fit; null (noted in {@link #lastProblem}) for one it can't take. */
	private NewWaypoint toAdd(OtherMod.Pin pin, Map<String, DimensionId> dimensions) {
		DimensionId dimension = this.dimension(pin, dimensions);
		String name = fit(pin.name());
		if (dimension == null) {
			return null;
		}
		if (name.isEmpty()) {
			this.lastProblem = "Left out the other mod's waypoint at " + pin.x() + " " + pin.y() + " " + pin.z() + ": it has no name";
			return null;
		}
		return NewWaypoint.builder(name, dimension, BlockCoord.of(pin.x(), pin.y(), pin.z())).rgb(pin.rgb() & 0xFFFFFF).visible(pin.visible())
			.folder(fit(pin.folder())).build();
	}

	/** The other mod's change for Diorama, made to fit as {@link #toAdd} does. */
	private WaypointUpdate toUpdate(OtherMod.Pin pin, Map<String, DimensionId> dimensions) {
		DimensionId dimension = this.dimension(pin, dimensions);
		String name = fit(pin.name());
		if (dimension == null) {
			return null;
		}
		WaypointUpdate.Builder update = WaypointUpdate.builder().dimension(dimension).pos(BlockCoord.of(pin.x(), pin.y(), pin.z())).rgb(pin.rgb() & 0xFFFFFF)
			.visible(pin.visible()).folder(fit(pin.folder()));
		if (!name.isEmpty()) {
			update.name(name);
		}
		return update.build();
	}

	/** A name as Diorama takes it: spaces at the ends off, cut to {@link NewWaypoint#MAX_NAME_LENGTH}, not through a character. */
	static String fit(String name) {
		String fitted = name.strip();
		if (fitted.length() > NewWaypoint.MAX_NAME_LENGTH) {
			int end = NewWaypoint.MAX_NAME_LENGTH;
			if (Character.isHighSurrogate(fitted.charAt(end - 1))) {
				end--;
			}
			fitted = fitted.substring(0, end).strip();
		}
		return fitted;
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

	/** Where a linked Diorama waypoint is: its dimension, its saved map there, and its ID. */
	private record Key(DimensionId dimension, MapId map, UUID id) {
	}

	private static Key key(Waypoint waypoint) {
		return new Key(waypoint.dimension(), waypoint.mapId(), waypoint.id());
	}

	private void unlink(Key key) {
		this.links.remove(key);
		this.lastDiorama.remove(key);
	}

	private static OtherMod.Pin pin(Waypoint waypoint) {
		return new OtherMod.Pin(waypoint.name(), waypoint.pos().x(), waypoint.pos().y(), waypoint.pos().z(), waypoint.dimension().toString(), waypoint.rgb(),
			waypoint.visible(), waypoint.folder().orElse(""));
	}
}
