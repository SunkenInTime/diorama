package dev.diorama.addon;

import dev.diorama.Maps;
import dev.diorama.api.DimensionId;
import dev.diorama.api.MapId;
import dev.diorama.api.World;
import dev.diorama.api.WorldId;
import dev.diorama.waypoint.WaypointStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The world addons see ({@link dev.diorama.api.DioramaApi#world}), worked out from Diorama's own
 * state when asked, never from the mod loaders' join and leave events: there is one while the
 * client has a level and Diorama knows which world it is, which is when {@link WaypointStore#get}
 * has a store. The {@link World} handed out is kept, and made again only when the store, the
 * player's dimension or the list of saved maps changed; one equal to the last is not handed out
 * as a new instance. Client thread only.
 */
final class CurrentWorld {
	private static @Nullable WaypointStore store;
	private static @Nullable ResourceKey<Level> dimension;
	private static @Nullable Maps maps;
	private static int mapsVersion;
	private static Optional<World> world = Optional.empty();
	/** The world key {@link #worldId} was last worked out for, and what it gave. */
	private static @Nullable String idFor;
	private static @Nullable WorldId id;

	private CurrentWorld() {
	}

	/** The store of the world the player is in, or null with none, or while its name isn't known. */
	static @Nullable WaypointStore store() {
		return Minecraft.getInstance().level == null ? null : WaypointStore.get();
	}

	/** The world the player is in now; see {@link CurrentWorld}. */
	static Optional<World> world() {
		Minecraft mc = Minecraft.getInstance();
		WaypointStore now = store();
		if (now == null || mc.level == null) {
			return Optional.empty();
		}
		ResourceKey<Level> here = mc.level.dimension();
		Maps list = Maps.of(now.worldKey());
		if (now == store && here == dimension && list == maps && list.version() == mapsVersion) {
			return world;
		}
		Optional<World> made = make(now.worldKey(), here, list);
		store = now;
		dimension = here;
		maps = list;
		mapsVersion = list.version();
		if (!made.equals(world)) {
			world = made;
		}
		return world;
	}

	/**
	 * The store a batch for {@code worldId} may write to: the one of the world open now.
	 *
	 * @throws IllegalStateException if there's no world, or it isn't {@code worldId}
	 */
	static WaypointStore storeFor(WorldId worldId) {
		Optional<World> open = world();
		WaypointStore now = store;
		if (open.isEmpty() || now == null) {
			throw new IllegalStateException("No world is open now (or Diorama doesn't know which yet), so world " + worldId + " isn't: nothing was changed");
		}
		if (!open.get().worldId().equals(worldId)) {
			throw new IllegalStateException("World " + worldId + " isn't the world open now, " + open.get().worldId() + ": nothing was changed. Read DioramaApi.world() again");
		}
		return now;
	}

	private static Optional<World> make(String worldKey, ResourceKey<Level> here, Maps list) {
		DimensionId dimension;
		try {
			dimension = DimensionId.of(here.identifier().toString());
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
		Map<DimensionId, MapId> inUse = new HashMap<>();
		for (Map.Entry<String, String> each : list.inUse().entrySet()) {
			try {
				inUse.put(DimensionId.of(each.getKey()), WaypointStore.mapId(each.getValue()));
			} catch (IllegalArgumentException e) {
				// A dimension addons can't name: they see no waypoints in it either.
			}
		}
		return Optional.of(new World(worldId(worldKey), dimension, inUse));
	}

	/**
	 * The world key, hashed: it names Diorama's folders, which addons shouldn't build on, and a
	 * server's address, which their files needn't hold. 32 hex digits.
	 */
	static WorldId worldId(String worldKey) {
		WorldId known = id;
		if (known != null && worldKey.equals(idFor)) {
			return known;
		}
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(worldKey.getBytes(StandardCharsets.UTF_8));
			known = WorldId.of(HexFormat.of().formatHex(hash, 0, 16));
		} catch (NoSuchAlgorithmException e) {
			// Every JVM has SHA-256.
			throw new IllegalStateException(e);
		}
		idFor = worldKey;
		id = known;
		return known;
	}
}
