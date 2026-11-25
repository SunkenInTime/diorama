package dev.diorama.testaddon;

import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.DioramaEntrypoint;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import dev.diorama.api.MapWorld;
import dev.diorama.api.Waypoint;
import dev.diorama.api.WaypointChange;
import dev.diorama.api.WaypointUpdate;
import dev.diorama.api.WorldListener;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The game tests' addon: it does nothing on its own, and the addon-api scenario drives Diorama's
 * API through it, as an addon would, from the client thread. From onReady it follows every world
 * ({@link #worldEvents}) and listens to each one's waypoints ({@link #heard}); a waypoint added
 * under the name {@value #ECHO} it renames from inside its listener, as a sync would answer a
 * change.
 */
@DioramaEntrypoint
public final class TestAddon implements DioramaAddon {
	/** A waypoint added under this name is renamed {@value #ECHOED} from inside the listener. */
	public static final String ECHO = "Echo me";
	public static final String ECHOED = "Echoed";

	private static DioramaApi api;
	private static int readyCalls;
	private static String readyThread = "";
	private static Handle fromReady;
	private static MapWorld world;
	private static Handle listening;
	private static final List<String> WORLD_EVENTS = new ArrayList<>();
	private static final List<String> HEARD = new ArrayList<>();
	/** What all() gave inside the listener, by the change it was heard in. */
	private static final List<Integer> SIZES_HEARD = new ArrayList<>();

	static {
		// Read by the scenario that runs the game without Diorama: nothing should load this class there.
		System.setProperty("diorama.testaddon.loaded", "true");
	}

	@Override
	public void onReady(DioramaApi api) {
		TestAddon.api = api;
		readyCalls++;
		readyThread = Thread.currentThread().getName();
		// Taken at the title screen and kept: it should still hold in the first world.
		fromReady = api.hud().hide(EnumSet.of(Hud.Part.MINIMAP));
		api.onWorld(new WorldListener() {
			@Override
			public void opened(MapWorld opened) {
				world = opened;
				WORLD_EVENTS.add("opened " + describe(opened));
				listening = opened.waypoints().listen(changes -> {
					for (WaypointChange change : changes.changes()) {
						HEARD.add(changes.by().orElse("-") + " " + change.type() + " " + change.before().map(Waypoint::name).orElse("") + " > " + change.after().map(w -> w.name() + (w.visible() ? "" : " (hidden)")).orElse(""));
						SIZES_HEARD.add(opened.waypoints().all().size());
						if (change.type() == WaypointChange.Type.ADDED && change.after().get().name().equals(ECHO)) {
							opened.waypoints().edit(edits -> edits.update(change.id(), WaypointUpdate.builder().name(ECHOED).build()));
						}
					}
				});
			}

			@Override
			public void mapChanged(MapWorld changed) {
				WORLD_EVENTS.add("mapChanged " + describe(changed));
			}

			@Override
			public void closed(MapWorld closed) {
				WORLD_EVENTS.add("closed " + closed.worldId() + " " + closed.isOpen());
			}
		});
		System.out.println("Diorama test addon: ready as " + api.modId() + " on API " + api.apiVersion());
	}

	private static String describe(MapWorld world) {
		return world.worldId() + " " + world.dimension() + " " + world.mapId();
	}

	/** The API Diorama handed this addon, or null before onReady. */
	public static DioramaApi api() {
		return api;
	}

	/** How many times Diorama called onReady. */
	public static int readyCalls() {
		return readyCalls;
	}

	/** The thread Diorama called onReady on. */
	public static String readyThread() {
		return readyThread;
	}

	/** The handle onReady took on the minimap, at the title screen. */
	public static Handle fromReady() {
		return fromReady;
	}

	/** Hides these parts through this addon's API. */
	public static Handle hide(Hud.Part... parts) {
		return api.hud().hide(parts.length == 0 ? EnumSet.noneOf(Hud.Part.class) : EnumSet.copyOf(List.of(parts)));
	}

	/** The world it was last handed, open or closed; null before any. */
	public static MapWorld world() {
		return world;
	}

	/** The handle of its listener on the last world's waypoints. */
	public static Handle listening() {
		return listening;
	}

	/** What its WorldListener was told, one line each: "opened <world> <dimension> <map>", "mapChanged ...", "closed <world> <open>". */
	public static List<String> worldEvents() {
		return WORLD_EVENTS;
	}

	/** What its waypoint listener heard, one line per waypoint: "<mod or -> <type> <name before> > <name after>". */
	public static List<String> heard() {
		return HEARD;
	}

	/** How many waypoints all() had when each line of {@link #heard} was heard. */
	public static List<Integer> sizesHeard() {
		return SIZES_HEARD;
	}

	/** Forgets what it heard, so a check reads only what follows. */
	public static void forgetHeard() {
		HEARD.clear();
		SIZES_HEARD.clear();
	}
}
