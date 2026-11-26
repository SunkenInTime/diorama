package dev.diorama.waypoint;

import dev.diorama.Diorama;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;
*///?}
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import org.jspecify.annotations.Nullable;

/**
 * The waystones and sharestones that the Waystones mod has told the player about, shown as
 * waypoints in a folder of their own. Waystones owns them: they aren't saved, and can't be edited
 * or deleted here. The server sends them again each time the player joins. Client thread only.
 *
 * <p>Diorama doesn't build against Waystones. When Waystones is installed, this class looks up
 * Waystones' client events by name, because they changed shape across versions: before 1.21.11
 * they register through {@code Balm.getEvents().onEvent(...)} and have {@code getX()} getters,
 * and from 1.21.11 each event is a record with its own {@code EVENT} field. If anything isn't where
 * this class expects it, it logs once and stays off.
 */
public final class Waystones {
	private static final String API = "net.blay09.mods.waystones.api.";
	/** The kind of an ordinary waystone, the only kind that Waystones' own screen can forget without telling the client. */
	private static final String WAYSTONE = "waystones:waystone";
	private static final int COLOUR = 0xFF8E4EC6;
	/** Sharestones' dye colours, by the start of their kind before 26.1, e.g. {@code waystones:lime_sharestone}. */
	private static final Map<String, Integer> DYES = Map.ofEntries(
		Map.entry("white", 0xFFF9FFFE), Map.entry("orange", 0xFFF9801D), Map.entry("magenta", 0xFFC74EBD),
		Map.entry("light_blue", 0xFF3AB3DA), Map.entry("yellow", 0xFFFED83D), Map.entry("lime", 0xFF80C71F),
		Map.entry("pink", 0xFFF38BAA), Map.entry("gray", 0xFF474F52), Map.entry("light_gray", 0xFF9D9D97),
		Map.entry("cyan", 0xFF169C9C), Map.entry("purple", 0xFF8932B8), Map.entry("blue", 0xFF3C44AA),
		Map.entry("brown", 0xFF835432), Map.entry("green", 0xFF5E7C16), Map.entry("red", 0xFFB02E26),
		Map.entry("black", 0xFF1D1D21));
	/** How often the ordinary waystones are checked against Waystones' own list, in milliseconds. */
	private static final long CHECK_MS = 1000;

	/** By kind, then by waystone id, as a full list replaces only the waystones of its own kind. */
	private static final Map<String, Map<UUID, Waypoint>> KNOWN = new LinkedHashMap<>();
	/**
	 * Waystones picked up with Silk Touch, and where they stood. The server goes on sending them in
	 * full lists, at that spot, as nothing it sends says a waystone is in someone's inventory. One
	 * placed again comes back in the same kind of list, so it shows once it's sent from somewhere
	 * else, or the client sees its block back at the spot. Kept until the game closes, as the ids
	 * are unique.
	 */
	private static final Map<UUID, String> PICKED_UP = new HashMap<>();
	/** Counts changes to what's known, so a screen showing waystones can tell when to show them again. */
	private static int changes;
	/** Looked up once each; the events may come on the network thread while the client thread checks. */
	private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();
	private static boolean started;
	private static volatile boolean failed;
	private static @Nullable Method activated;
	private static long nextCheck;

	private Waystones() {
	}

	/**
	 * Called every client tick: listens to Waystones' events from the first, checks the ordinary
	 * waystones against Waystones' own list now and then, and finds picked-up waystones placed
	 * again. Here, rather than where the waystones are read, so reading what changed allocates
	 * nothing.
	 */
	public static void tick() {
		start();
		check();
		rediscover();
	}

	/** Listens to Waystones' events if Waystones is installed. Does its work on the first call. */
	private static void start() {
		if (started) {
			return;
		}
		started = true;
		if (!installed()) {
			return;
		}
		try {
			listen("WaystonesListReceivedEvent", Waystones::listReceived);
			listen("WaystoneUpdateReceivedEvent", Waystones::updateReceived);
			listen("WaystoneRemoveReceivedEvent", Waystones::removeReceived);
			Class<?> api = Class.forName(API + "WaystonesAPI");
			for (Method method : api.getMethods()) {
				if (method.getName().equals("getActivatedWaystones") && method.getParameterCount() == 1 && Modifier.isStatic(method.getModifiers())) {
					activated = method;
				}
			}
			Diorama.LOGGER.info("Waystones is installed: showing known waystones on the map");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			fail(e);
		}
	}

	private static boolean installed() {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded("waystones");
		//?} else {
		/*return ModList.get().isLoaded("waystones");
		*///?}
	}

	/** Registers a handler for one of Waystones' client events, in whichever way this version takes. */
	private static void listen(String name, Handler handler) throws ReflectiveOperationException {
		Class<?> event = Class.forName(API + "event." + name);
		Consumer<Object> guarded = e -> {
			if (!failed) {
				try {
					handler.handle(e);
				} catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
					fail(ex);
				}
			}
		};
		Field field = null;
		try {
			field = event.getField("EVENT");
		} catch (NoSuchFieldException e) {
			// Before 1.21.11: through Balm's event bus.
		}
		if (field != null) {
			Object mapper = field.get(null);
			one(field.getType(), "register", 1).invoke(mapper, guarded);
		} else {
			Method getEvents = Class.forName("net.blay09.mods.balm.api.Balm").getMethod("getEvents");
			Object events = getEvents.invoke(null);
			getEvents.getReturnType().getMethod("onEvent", Class.class, Consumer.class).invoke(events, event, guarded);
		}
	}

	private static Method one(Class<?> type, String name, int parameters) throws NoSuchMethodException {
		for (Method method : type.getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == parameters) {
				return method;
			}
		}
		throw new NoSuchMethodException(type.getName() + "." + name);
	}

	private interface Handler {
		void handle(Object event) throws ReflectiveOperationException;
	}

	/** One kind's full list: waystones of that kind that aren't in it are gone. */
	private static void listReceived(Object event) throws ReflectiveOperationException {
		String kind = String.valueOf(get(event, "getWaystoneType", "waystoneType"));
		if (!shown(kind)) {
			return;
		}
		List<Waypoint> waypoints = new ArrayList<>();
		for (Object waystone : (Collection<?>) get(event, "getWaystones", "waystones")) {
			Waypoint waypoint = waypoint(waystone);
			if (waypoint != null) {
				waypoints.add(waypoint);
			}
		}
		onClient(() -> received(kind, waypoints));
	}

	/**
	 * One kind's full list arrived, on the client thread. A waystone sent as it was keeps the
	 * waypoint it had, and a list the same as before is no change, as the server sends the whole
	 * list again for one change and on every join.
	 */
	private static void received(String kind, List<Waypoint> waypoints) {
		Map<UUID, Waypoint> before = KNOWN.getOrDefault(kind, Map.of());
		Map<UUID, Waypoint> waystones = new LinkedHashMap<>();
		boolean changed = waypoints.size() != before.size();
		java.util.Iterator<UUID> order = before.keySet().iterator();
		for (Waypoint waypoint : waypoints) {
			Waypoint old = before.get(waypoint.id);
			Waypoint kept = old != null && same(old, waypoint) ? old : waypoint;
			changed |= kept != old || !order.hasNext() || !order.next().equals(waypoint.id);
			waystones.put(waypoint.id, kept);
		}
		KNOWN.put(kind, waystones);
		if (changed) {
			changes++;
		}
		// One sent from somewhere else was placed again there.
		rediscover();
	}

	/** Whether Waystones sent a waystone as it was: what the map shows of it is the same. */
	private static boolean same(Waypoint a, Waypoint b) {
		return a.id.equals(b.id) && a.name.equals(b.name) && a.x == b.x && a.y == b.y && a.z == b.z && a.dimension.equals(b.dimension) && a.color == b.color
			&& a.kind == b.kind;
	}

	/** One waystone changed: renamed, say. */
	private static void updateReceived(Object event) throws ReflectiveOperationException {
		Object waystone = get(event, "getWaystone", "waystone");
		UUID id = (UUID) get(waystone, "getWaystoneUid");
		String kind = kind(waystone);
		Waypoint waypoint = waypoint(waystone);
		onClient(() -> {
			Map<UUID, Waypoint> ofKind = KNOWN.get(kind);
			Waypoint old = ofKind == null ? null : ofKind.get(id);
			if (waypoint != null && old != null && same(old, waypoint)) {
				// Sent again as it was.
				return;
			}
			forget(id);
			if (waypoint != null) {
				KNOWN.computeIfAbsent(kind, k -> new LinkedHashMap<>()).put(id, waypoint);
			}
			changes++;
			rediscover();
		});
	}

	/** Destroyed, or picked up with Silk Touch: either way it's no longer there. */
	private static void removeReceived(Object event) throws ReflectiveOperationException {
		UUID id = (UUID) get(event, "getWaystoneId", "waystoneId");
		boolean destroyed = (Boolean) get(event, "wasDestroyed");
		onClient(() -> removed(id, destroyed));
	}

	private static void removed(UUID id, boolean destroyed) {
		if (!destroyed) {
			for (Map<UUID, Waypoint> waystones : KNOWN.values()) {
				Waypoint waypoint = waystones.get(id);
				if (waypoint != null) {
					PICKED_UP.put(id, spot(waypoint));
				}
			}
		}
		forget(id);
		changes++;
	}

	/** False for a waystone the server still sends from where it stood before it was picked up. */
	private static boolean placed(Waypoint waypoint) {
		return !PICKED_UP.containsKey(waypoint.id);
	}

	/**
	 * Finds the picked-up waystones placed again: sent from another spot, or with the client seeing
	 * a Waystones block back at theirs, which no event says. Each one found shows again, which
	 * counts as a change. Run every client tick while any is picked up, and when a list or an
	 * update comes, so what reads the waystones (the HUD, the map, addons) sees it the same, drawn
	 * or not.
	 */
	private static void rediscover() {
		if (PICKED_UP.isEmpty()) {
			return;
		}
		java.util.Iterator<Map.Entry<UUID, String>> each = PICKED_UP.entrySet().iterator();
		while (each.hasNext()) {
			Map.Entry<UUID, String> picked = each.next();
			Waypoint waypoint = known(picked.getKey());
			if (waypoint != null && (!picked.getValue().equals(spot(waypoint)) || blockThere(waypoint))) {
				each.remove();
				changes++;
			}
		}
	}

	private static @Nullable Waypoint known(UUID id) {
		for (Map<UUID, Waypoint> waystones : KNOWN.values()) {
			Waypoint waypoint = waystones.get(id);
			if (waypoint != null) {
				return waypoint;
			}
		}
		return null;
	}

	/** Whether Waystones has told the player of this waystone, and it isn't picked up. */
	public static boolean has(UUID id) {
		Waypoint waypoint = known(id);
		return waypoint != null && placed(waypoint);
	}

	/** Whether the client has the waystone's spot loaded with one of Waystones' blocks on it. */
	private static boolean blockThere(Waypoint waypoint) {
		if (BLOCK_BACK_FOR_TESTS.contains(waypoint.id)) {
			return true;
		}
		Minecraft mc = Minecraft.getInstance();
		BlockPos pos = new BlockPos(waypoint.x, waypoint.y, waypoint.z);
		if (mc.level == null || !mc.level.dimension().identifier().toString().equals(waypoint.dimension) || !mc.level.hasChunkAt(pos)) {
			return false;
		}
		return BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).getNamespace().equals("waystones");
	}

	private static String spot(Waypoint waypoint) {
		return waypoint.dimension + " " + waypoint.x + " " + waypoint.y + " " + waypoint.z;
	}

	/** The events may come on the network thread; the waypoints are only touched on the client thread. */
	private static void onClient(Runnable task) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) {
			task.run();
		} else {
			mc.execute(task);
		}
	}

	private static void forget(UUID id) {
		for (Map<UUID, Waypoint> waystones : KNOWN.values()) {
			waystones.remove(id);
		}
	}

	/** Waystones and sharestones, as Waystones' own JourneyMap markers have it; not warp plates and the like. */
	private static boolean shown(String kind) {
		return kind.equals(WAYSTONE) || kind.endsWith("_sharestone");
	}

	private static String kind(Object waystone) throws ReflectiveOperationException {
		return String.valueOf(get(waystone, "getWaystoneType", "getWaystoneKind"));
	}

	/** A waystone as a waypoint, or null for one that isn't placed in the world (picked up with Silk Touch). */
	private static @Nullable Waypoint waypoint(Object waystone) throws ReflectiveOperationException {
		String kind = kind(waystone);
		if (!shown(kind) || (Boolean) get(waystone, "isTransient")) {
			return null;
		}
		Component name = (Boolean) get(waystone, "hasName")
			? (Component) get(waystone, "getEffectiveName", "getName")
			: Component.translatable("diorama.waystones.untitled");
		BlockPos pos = (BlockPos) get(waystone, "getPos");
		ResourceKey<?> dimension = (ResourceKey<?>) get(waystone, "getDimension");
		String dye = kind.substring(kind.indexOf(':') + 1, kind.length() - (kind.endsWith("_sharestone") ? "_sharestone".length() : 0));
		Waypoint waypoint = new Waypoint(name.getString(), pos.getX(), pos.getY(), pos.getZ(), dimension.identifier().toString(), DYES.getOrDefault(dye, COLOUR));
		waypoint.id = (UUID) get(waystone, "getWaystoneUid");
		waypoint.kind = Waypoint.Kind.WAYSTONE;
		return waypoint;
	}

	/**
	 * Calls the first of these no-argument methods the object has, looked up on Waystones' API
	 * types rather than the object's own class, which may not be public. The names are Waystones'
	 * own, which a Fabric install leaves as they are.
	 */
	private static Object get(Object target, String... names) throws ReflectiveOperationException {
		Class<?> type = target.getClass();
		for (String name : names) {
			Optional<Method> method = METHODS.computeIfAbsent(type.getName() + "." + name, key -> Optional.ofNullable(publicMethod(type, name)));
			if (method.isPresent()) {
				return method.get().invoke(target);
			}
		}
		throw new NoSuchMethodException(type.getName() + "." + String.join("/", names));
	}

	/** A no-argument method by name from a public class or interface the type is or has. */
	private static @Nullable Method publicMethod(Class<?> type, String name) {
		Set<Class<?>> seen = new HashSet<>();
		List<Class<?>> queue = new ArrayList<>(List.of(type));
		while (!queue.isEmpty()) {
			Class<?> next = queue.remove(0);
			if (!seen.add(next)) {
				continue;
			}
			if (Modifier.isPublic(next.getModifiers())) {
				try {
					return next.getMethod(name);
				} catch (NoSuchMethodException e) {
					// Not on this one; try the ones it extends.
				}
			}
			if (next.getSuperclass() != null) {
				queue.add(next.getSuperclass());
			}
			queue.addAll(List.of(next.getInterfaces()));
		}
		return null;
	}

	private static void fail(Throwable e) {
		if (!failed) {
			failed = true;
			onClient(Waystones::clear);
			Diorama.LOGGER.warn("Couldn't read Waystones' waystones, so they won't show on the map", e);
		}
	}

	/**
	 * Every known waystone in a dimension. Waystones' own screen forgets a waystone on the client
	 * without the server sending a new list, so the ordinary ones are checked against Waystones'
	 * list now and then.
	 */
	static List<Waypoint> in(String dimension) {
		check();
		List<Waypoint> out = new ArrayList<>();
		for (Map<UUID, Waypoint> waystones : KNOWN.values()) {
			for (Waypoint waypoint : waystones.values()) {
				if (waypoint.dimension.equals(dimension) && placed(waypoint)) {
					out.add(waypoint);
				}
			}
		}
		return out;
	}

	/** Every known waystone, in any dimension. */
	static List<Waypoint> all() {
		check();
		List<Waypoint> out = new ArrayList<>();
		for (Map<UUID, Waypoint> waystones : KNOWN.values()) {
			for (Waypoint waypoint : waystones.values()) {
				if (placed(waypoint)) {
					out.add(waypoint);
				}
			}
		}
		return out;
	}

	private static void check() {
		Map<UUID, Waypoint> ordinary = KNOWN.get(WAYSTONE);
		Method method = activated;
		long now = System.currentTimeMillis();
		if (failed || method == null || ordinary == null || ordinary.isEmpty() || now < nextCheck) {
			return;
		}
		nextCheck = now + CHECK_MS;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		try {
			Set<UUID> ids = new HashSet<>();
			for (Object waystone : (Collection<?>) method.invoke(null, mc.player)) {
				ids.add((UUID) get(waystone, "getWaystoneUid"));
			}
			if (ordinary.keySet().retainAll(ids)) {
				changes++;
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			fail(e);
		}
	}

	/** Goes up each time the known waystones change. */
	static int changes() {
		return changes;
	}

	/** The player left the server or joined another; the next one sends its own. */
	public static void clear() {
		KNOWN.clear();
		changes++;
	}

	/** For the game tests, which have no Waystones mod: waystones whose block the client sees back at their spot. */
	private static final Set<UUID> BLOCK_BACK_FOR_TESTS = new HashSet<>();

	/** For the game tests: Waystones told the client of this waystone, as a full list of one kind would. */
	public static void knownForTests(Waypoint waystone) {
		waystone.kind = Waypoint.Kind.WAYSTONE;
		KNOWN.computeIfAbsent("diorama:test_waystone", k -> new LinkedHashMap<>()).put(waystone.id, waystone);
		changes++;
	}

	/** For the game tests: Waystones sent this full list of one kind, handled as one from the server is. */
	public static void listForTests(String kind, List<Waypoint> waystones) {
		for (Waypoint waystone : waystones) {
			waystone.kind = Waypoint.Kind.WAYSTONE;
		}
		received(kind, waystones);
	}

	/**
	 * For the game tests: the waystone was picked up with Silk Touch, and the server goes on
	 * sending it where it stood ({@link #PICKED_UP}); with {@code back}, the client then sees its
	 * block there again, which no event announces.
	 */
	public static void pickedUpForTests(UUID id, boolean back) {
		if (back) {
			BLOCK_BACK_FOR_TESTS.add(id);
			return;
		}
		// As a removal and then the next full list, which still has it, would leave it.
		Waypoint waypoint = known(id);
		if (waypoint != null) {
			PICKED_UP.put(id, spot(waypoint));
		}
		changes++;
	}
}
