package dev.diorama.compat;

import dev.diorama.Claims;
import dev.diorama.Diorama;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;
*///?}
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Reads the land claims FTB Chunks tells the client about. The server sends every claim the player
 * may see when they join, and each change after, whether or not FTB Chunks' minimap is on. FTB
 * Chunks applies each to its own client map in a task, on the client tick;
 * {@code FtbChunksUpdateMixin} calls {@link #updated} after each, and this hands the task's chunk,
 * team and force loading to {@link Claims}, which looks the team up as FTB Chunks does. All on the
 * client thread, where FTB Chunks keeps its map, so nothing here races it.
 *
 * <p>It also asks the server to claim, unclaim and force load a chunk, with the same request
 * FTB Chunks' own map sends ({@link #request}).
 *
 * <p>Diorama doesn't build against FTB Chunks. The names below have stayed the same from 1.20.1 to
 * 26.1. If one isn't where this class expects it, it logs once and stays off.
 */
public final class FtbChunks {
	private static final String MAP = "dev.ftb.mods.ftbchunks.client.map.";

	private static boolean started;
	private static boolean failed;
	private static Field taskDimension;
	/** The update's chunk: {@code chunk} before 1.21, {@code info} after, each with x, z and forceLoaded fields. */
	private static Field taskChunk;
	private static Field taskTeam;
	private static Field chunkX;
	private static Field chunkZ;
	private static Field chunkForceLoaded;
	private static Field dimensionKey;
	private static Field dimensionManager;
	private static Method managerInvalid;
	private static Method teamId;
	private static Method getProperty;
	private static Object colorProperty;
	private static Object nameProperty;
	private static Method rgb;
	private static Method teamsApi;
	private static Method clientManagerLoaded;
	private static Method clientManager;
	private static Method teamById;

	/** What a chunk can be asked to become: FTB Chunks' own four requests, by the names of its operations. */
	public enum Action {
		CLAIM, UNCLAIM, LOAD, UNLOAD
	}

	private static boolean requestsStarted;
	private static boolean requestsFailed;
	/** FTB Chunks' operation enum: {@code ChunkChangeOp} before the newest versions, {@code Operation} after. */
	private static Class<?> operationType;
	/** The request's public constructor: (operation, chunks) on 1.20.1, plus (admin, team) from 1.21. */
	private static Constructor<?> requestConstructor;
	private static Method xzOf;
	/** On 1.20.1 the message sends itself; after, a static method of FTB Library or Architectury does. */
	private static Method sendOwn;
	private static Method sendStatic;
	private static Method selfTeam;

	/** No one's: the team the server sends for an unclaimed chunk, or one the player may not see. */
	private static final UUID NO_TEAM = new UUID(0, 0);

	private FtbChunks() {
	}

	/** An FTB team, as the owner of the chunks it claims. */
	public record Team(UUID id) implements Claims.Owner {
		@Override
		public @Nullable Optional<Claims.Claim> look() {
			return team(this.id);
		}
	}

	public static boolean installed() {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded("ftbchunks");
		//?} else {
		/*return ModList.get().isLoaded("ftbchunks");
		*///?}
	}

	/** Whether claims can be read: FTB Chunks is installed and everything was found. */
	public static boolean available() {
		if (!started) {
			started = true;
			if (installed()) {
				try {
					lookUp();
					Diorama.LOGGER.info("FTB Chunks is installed: showing its claims on the map");
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					fail(e);
				}
			} else {
				failed = true;
			}
		}
		return !failed;
	}

	private static void lookUp() throws ReflectiveOperationException {
		Class<?> task = Class.forName(MAP + "UpdateChunkFromServerTask");
		taskDimension = task.getDeclaredField("dimension");
		taskDimension.setAccessible(true);
		Class<?> dimension = Class.forName(MAP + "MapDimension");
		dimensionKey = dimension.getField("dimension");
		dimensionManager = dimension.getDeclaredField("manager");
		dimensionManager.setAccessible(true);
		managerInvalid = Class.forName(MAP + "MapManager").getMethod("isInvalid");
		taskTeam = task.getDeclaredField("teamId");
		taskTeam.setAccessible(true);
		try {
			taskChunk = task.getDeclaredField("info");
		} catch (NoSuchFieldException e) {
			// 1.20.1: the packet's own chunk record.
			taskChunk = task.getDeclaredField("chunk");
		}
		taskChunk.setAccessible(true);
		chunkX = field(taskChunk.getType(), "x");
		chunkZ = field(taskChunk.getType(), "z");
		chunkForceLoaded = field(taskChunk.getType(), "forceLoaded");
		Class<?> team = Class.forName("dev.ftb.mods.ftbteams.api.Team");
		teamId = team.getMethod("getId");
		Class<?> property = Class.forName("dev.ftb.mods.ftbteams.api.property.TeamProperty");
		getProperty = team.getMethod("getProperty", property);
		Class<?> properties = Class.forName("dev.ftb.mods.ftbteams.api.property.TeamProperties");
		colorProperty = properties.getField("COLOR").get(null);
		nameProperty = properties.getField("DISPLAY_NAME").get(null);
		rgb = Class.forName("dev.ftb.mods.ftblibrary.icon.Color4I").getMethod("rgb");
		teamsApi = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI").getMethod("api");
		Class<?> api = teamsApi.getReturnType();
		clientManagerLoaded = api.getMethod("isClientManagerLoaded");
		clientManager = api.getMethod("getClientManager");
		teamById = clientManager.getReturnType().getMethod("getTeamByID", UUID.class);
	}

	/** Whether claims, unclaims and force loading can be asked for: FTB Chunks is installed and its request was found. */
	public static boolean canRequest() {
		if (!available()) {
			return false;
		}
		if (!requestsStarted) {
			requestsStarted = true;
			try {
				lookUpRequest();
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				requestsFailed = true;
				Diorama.LOGGER.warn("Couldn't find FTB Chunks' chunk request; claiming from the map is off", e);
			}
		}
		return !requestsFailed;
	}

	private static void lookUpRequest() throws ReflectiveOperationException {
		Class<?> packet = Class.forName("dev.ftb.mods.ftbchunks.net.RequestChunkChangePacket");
		for (Constructor<?> constructor : packet.getConstructors()) {
			Class<?>[] types = constructor.getParameterTypes();
			if ((types.length == 2 || types.length == 4) && types[0].isEnum() && types[1] == Set.class) {
				requestConstructor = constructor;
				operationType = types[0];
				break;
			}
		}
		if (requestConstructor == null) {
			throw new NoSuchMethodException("RequestChunkChangePacket(operation, chunks)");
		}
		xzOf = Class.forName("dev.ftb.mods.ftblibrary.math.XZ").getMethod("of", int.class, int.class);
		try {
			sendOwn = packet.getMethod("sendToServer");
		} catch (NoSuchMethodException e) {
			sendStatic = payloadSender(packet, "dev.ftb.mods.ftblibrary.platform.network.Play2ServerNetworking", "send");
			if (sendStatic == null) {
				sendStatic = payloadSender(packet, "dev.architectury.networking.NetworkManager", "sendToServer");
			}
			if (sendStatic == null) {
				throw new NoSuchMethodException("a way to send the request to the server");
			}
		}
		for (Action action : Action.values()) {
			boolean found = false;
			for (Object constant : operationType.getEnumConstants()) {
				found |= ((Enum<?>) constant).name().equals(action.name());
			}
			if (!found) {
				throw new NoSuchFieldException(operationType.getName() + "." + action.name());
			}
		}
	}

	/** A static method that takes the packet itself, or null if the class or the method isn't there. */
	private static @Nullable Method payloadSender(Class<?> packet, String owner, String name) {
		try {
			for (Method method : Class.forName(owner).getMethods()) {
				if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers())
					&& method.getParameterCount() == 1 && method.getParameterTypes()[0].isAssignableFrom(packet)) {
					return method;
				}
			}
		} catch (ClassNotFoundException | LinkageError e) {
			// Not this version's way.
		}
		return null;
	}

	/**
	 * Asks the server to do {@code action} to a chunk, as the player or their team, as FTB Chunks'
	 * own map does; the server decides, and tells FTB Chunks, which tells {@link #updated}. Client thread.
	 */
	public static void request(Action action, int chunkX, int chunkZ) {
		request(action, java.util.List.of(new int[] {chunkX, chunkZ}));
	}

	/**
	 * Asks the server to do {@code action} to every chunk in {@code chunkXZ} (each {@code {x, z}}).
	 * Client thread.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void request(Action action, java.util.Collection<int[]> chunkXZ) {
		if (!canRequest() || chunkXZ == null || chunkXZ.isEmpty()
			|| !net.minecraft.client.Minecraft.getInstance().isSameThread()) {
			return;
		}
		try {
			Object operation = Enum.valueOf((Class<? extends Enum>) operationType, action.name());
			Set<Object> chunks = new HashSet<>();
			for (int[] xz : chunkXZ) {
				if (xz != null && xz.length >= 2) {
					chunks.add(xzOf.invoke(null, xz[0], xz[1]));
				}
			}
			if (chunks.isEmpty()) {
				return;
			}
			// Not as an admin, and as the player's own team.
			Object packet = requestConstructor.getParameterCount() == 2
				? requestConstructor.newInstance(operation, chunks)
				: requestConstructor.newInstance(operation, chunks, false, Optional.empty());
			if (sendOwn != null) {
				sendOwn.invoke(packet);
			} else {
				sendStatic.invoke(null, packet);
			}
		} catch (InvocationTargetException e) {
			Diorama.LOGGER.warn("FTB Chunks couldn't send a chunk request", e);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			requestsFailed = true;
			Diorama.LOGGER.warn("Couldn't send a chunk request to FTB Chunks; claiming from the map is off", e);
		}
	}

	/**
	 * Whether a team is the player's own, the one their claims are in: their party if they're in one,
	 * else their own team. False if FTB Teams can't say yet. Client thread.
	 */
	public static boolean isOwn(Team team) {
		if (!available()) {
			return false;
		}
		try {
			Object api = teamsApi.invoke(null);
			if (!(boolean) clientManagerLoaded.invoke(api)) {
				return false;
			}
			Object manager = clientManager.invoke(api);
			if (selfTeam == null) {
				selfTeam = clientManager.getReturnType().getMethod("selfTeam");
			}
			Object own = selfTeam.invoke(manager);
			return own != null && team.id().equals(teamId.invoke(own));
		} catch (InvocationTargetException | NoSuchMethodException e) {
			Diorama.LOGGER.debug("FTB Teams couldn't give the player's team", e);
			return false;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			fail(e);
			return false;
		}
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	/**
	 * After FTB Chunks applied a claim update from the server to one chunk of its map: tells
	 * {@link Claims} the chunk's team and whether it's force loaded. Client thread, from
	 * {@code FtbChunksUpdateMixin}.
	 */
	public static void updated(Object task) {
		if (!available() || !net.minecraft.client.Minecraft.getInstance().isSameThread()) {
			return;
		}
		long start = System.nanoTime();
		try {
			Object dimension = taskDimension.get(task);
			// A map being shut down (leaving a server, or a proxy moving the player to another)
			// still runs its queued updates: they're for the old server.
			if ((boolean) managerInvalid.invoke(dimensionManager.get(dimension))) {
				return;
			}
			Object chunk = taskChunk.get(task);
			@SuppressWarnings("unchecked")
			ResourceKey<Level> key = (ResourceKey<Level>) dimensionKey.get(dimension);
			UUID team = (UUID) taskTeam.get(task);
			Team owner = team == null || team.equals(NO_TEAM) ? null : new Team(team);
			Claims.updated(key, chunkX.getInt(chunk), chunkZ.getInt(chunk), Team.class, owner, chunkForceLoaded.getBoolean(chunk));
		} catch (InvocationTargetException e) {
			// FTB Chunks itself threw: skip this update, as it would have.
			Diorama.LOGGER.debug("FTB Chunks couldn't give a chunk's claim", e);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			fail(e);
		} finally {
			Claims.spent(System.nanoTime() - start);
		}
	}

	/**
	 * A team's claim as it stands now, from FTB Teams on the client: empty if FTB Teams knows the
	 * team no longer exists (a deleted server team, whose chunks get no update), null if it can't
	 * tell now. Client thread.
	 */
	public static @Nullable Optional<Claims.Claim> team(UUID id) {
		if (!available()) {
			return null;
		}
		try {
			Object api = teamsApi.invoke(null);
			if (!(boolean) clientManagerLoaded.invoke(api)) {
				return null;
			}
			Optional<?> team = (Optional<?>) teamById.invoke(clientManager.invoke(api), id);
			return team.isEmpty() ? Optional.empty() : Optional.of(owner(team.get()));
		} catch (InvocationTargetException e) {
			Diorama.LOGGER.debug("FTB Teams couldn't give a team", e);
			return null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			fail(e);
			return null;
		}
	}

	private static Claims.Claim owner(Object team) throws ReflectiveOperationException {
		UUID id = (UUID) teamId.invoke(team);
		Object colour = getProperty.invoke(team, colorProperty);
		Object name = getProperty.invoke(team, nameProperty);
		return new Claims.Claim(new Team(id), colour == null ? 0xFFFFFF : (int) rgb.invoke(colour) & 0xFFFFFF, name instanceof String s ? s : "", false, false);
	}

	private static void fail(Throwable e) {
		if (!failed) {
			failed = true;
			Diorama.LOGGER.warn("Couldn't read FTB Chunks' claims; they won't show on the map", e);
		}
	}
}
