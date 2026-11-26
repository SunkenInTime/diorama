package dev.diorama.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The world the player is in, as Diorama knows it at one moment: read with
 * {@link DioramaApi#world}. A plain value that never changes: when the player changes dimension or
 * switches a saved map, {@code world()} returns a new {@code World} that isn't {@link #equals} to
 * the old one; so does making or deleting a saved map. Keep the last one and compare to tell that
 * something moved.
 */
public final class World {
	private final WorldId worldId;
	private final DimensionId dimension;
	private final Map<DimensionId, MapId> maps;
	/** Each dimension's saved maps, left out for one whose only map is the one in use. */
	private final Map<DimensionId, List<MapId>> saved;

	/**
	 * A world as Diorama would describe it. Diorama makes these for {@link DioramaApi#world};
	 * this is public so an addon's own tests can make one.
	 *
	 * @param worldId which world
	 * @param dimension the dimension the player is in
	 * @param maps the saved map in use by dimension, copied; a dimension left out is on
	 *     {@link MapId#MAIN}, the same as one given {@code MAIN}
	 * @param saved every saved map by dimension, as {@link #mapIds} lists them, copied; one whose
	 *     list leaves out its map in use gets it added at the end, and a dimension left out has
	 *     only its map in use
	 * @throws IllegalArgumentException if a list in {@code saved} names a map twice
	 * @throws NullPointerException if an argument, or any key, value or list item in {@code maps}
	 *     or {@code saved}, is null
	 */
	public World(WorldId worldId, DimensionId dimension, Map<DimensionId, MapId> maps, Map<DimensionId, List<MapId>> saved) {
		this.worldId = Objects.requireNonNull(worldId, "worldId");
		this.dimension = Objects.requireNonNull(dimension, "dimension");
		Map<DimensionId, MapId> kept = new HashMap<>();
		for (Map.Entry<DimensionId, MapId> entry : maps.entrySet()) {
			DimensionId key = Objects.requireNonNull(entry.getKey(), "a dimension in maps");
			MapId map = Objects.requireNonNull(entry.getValue(), "a map ID in maps");
			if (!map.equals(MapId.MAIN)) {
				kept.put(key, map);
			}
		}
		this.maps = Map.copyOf(kept);
		Map<DimensionId, List<MapId>> lists = new HashMap<>();
		for (Map.Entry<DimensionId, List<MapId>> entry : saved.entrySet()) {
			DimensionId key = Objects.requireNonNull(entry.getKey(), "a dimension in saved");
			List<MapId> list = new ArrayList<>(Objects.requireNonNull(entry.getValue(), "a list in saved"));
			Set<MapId> seen = new HashSet<>();
			for (MapId map : list) {
				if (!seen.add(Objects.requireNonNull(map, "a map ID in saved"))) {
					throw new IllegalArgumentException("Map " + map + " is listed twice in " + key);
				}
			}
			MapId inUse = this.mapId(key);
			if (!seen.contains(inUse)) {
				list.add(inUse);
			}
			if (!list.equals(List.of(inUse))) {
				lists.put(key, List.copyOf(list));
			}
		}
		this.saved = Map.copyOf(lists);
	}

	/**
	 * {@return which world this is} The same each time the player comes back to it; see
	 * {@link WorldId} for what changes it. Leaving a world and joining it again gives an equal
	 * {@code World}.
	 */
	public WorldId worldId() {
		return this.worldId;
	}

	/** {@return the dimension the player is in} */
	public DimensionId dimension() {
		return this.dimension;
	}

	/** {@return the saved map in use in the player's dimension} */
	public MapId mapId() {
		return this.mapId(this.dimension);
	}

	/**
	 * The saved map in use in a dimension, visited or not. A saved-map ID means something only
	 * with its world and dimension: another dimension's map with the same ID is another map.
	 *
	 * @param dimension any dimension
	 * @return its map in use; {@link MapId#MAIN} for one with a single map, or none yet
	 * @throws NullPointerException if {@code dimension} is null
	 */
	public MapId mapId(DimensionId dimension) {
		MapId map = this.maps.get(Objects.requireNonNull(dimension, "dimension"));
		return map == null ? MapId.MAIN : map;
	}

	/**
	 * Every saved map a dimension has, visited or not: in the order Diorama's Maps screen lists
	 * them, the main map first unless the player deleted it, then the others oldest first. A saved
	 * map that isn't here was deleted, and the waypoints on it with it.
	 *
	 * @param dimension any dimension
	 * @return its saved maps, its map in use among them; the list can't be changed
	 * @throws NullPointerException if {@code dimension} is null
	 */
	public List<MapId> mapIds(DimensionId dimension) {
		List<MapId> list = this.saved.get(Objects.requireNonNull(dimension, "dimension"));
		return list == null ? List.of(this.mapId(dimension)) : list;
	}

	/**
	 * Equal when the world, the player's dimension, every dimension's map in use and every
	 * dimension's saved maps are.
	 */
	@Override
	public boolean equals(@Nullable Object other) {
		return other instanceof World && ((World) other).worldId.equals(this.worldId) && ((World) other).dimension.equals(this.dimension)
			&& ((World) other).maps.equals(this.maps) && ((World) other).saved.equals(this.saved);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.worldId, this.dimension, this.maps, this.saved);
	}

	@Override
	public String toString() {
		return "World[" + this.worldId + " in " + this.dimension + " on " + this.mapId() + "]";
	}
}
