package dev.diorama.api;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The world the player is in, as Diorama knows it at one moment: read with
 * {@link DioramaApi#world}. A plain value that never changes: when the player changes dimension or
 * switches a saved map, {@code world()} returns a new {@code World} that isn't {@link #equals} to
 * the old one. Keep the last one and compare to tell that something moved.
 */
public final class World {
	private final WorldId worldId;
	private final DimensionId dimension;
	private final Map<DimensionId, MapId> maps;

	/**
	 * A world as Diorama would describe it. Diorama makes these for {@link DioramaApi#world};
	 * this is public so an addon's own tests can make one.
	 *
	 * @param worldId which world
	 * @param dimension the dimension the player is in
	 * @param maps the saved map in use by dimension, copied; a dimension left out is on
	 *     {@link MapId#MAIN}, the same as one given {@code MAIN}
	 * @throws NullPointerException if an argument, or anything in {@code maps}, is null
	 */
	public World(WorldId worldId, DimensionId dimension, Map<DimensionId, MapId> maps) {
		this.worldId = Objects.requireNonNull(worldId, "worldId");
		this.dimension = Objects.requireNonNull(dimension, "dimension");
		Map<DimensionId, MapId> kept = new java.util.HashMap<>();
		for (Map.Entry<DimensionId, MapId> entry : maps.entrySet()) {
			MapId map = Objects.requireNonNull(entry.getValue(), "a map ID");
			if (!map.equals(MapId.MAIN)) {
				kept.put(Objects.requireNonNull(entry.getKey(), "a dimension"), map);
			}
		}
		this.maps = Map.copyOf(kept);
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

	/** Equal when the world, the player's dimension and every dimension's map in use are. */
	@Override
	public boolean equals(@Nullable Object other) {
		return other instanceof World && ((World) other).worldId.equals(this.worldId) && ((World) other).dimension.equals(this.dimension)
			&& ((World) other).maps.equals(this.maps);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.worldId, this.dimension, this.maps);
	}

	@Override
	public String toString() {
		return "World[" + this.worldId + " in " + this.dimension + " on " + this.mapId() + "]";
	}
}
