package dev.diorama.testaddon.looping;

import dev.diorama.api.BlockCoord;
import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.DioramaEntrypoint;
import dev.diorama.api.MapWorld;
import dev.diorama.api.NewWaypoint;
import dev.diorama.api.WaypointUpdate;
import dev.diorama.api.WorldListener;
import java.util.UUID;

/**
 * Once started, answers each change it made to its waypoint with another, for ever: Diorama
 * should refuse the edit past Waypoints.MAX_EDIT_DEPTH answers, which switches it off, and leave
 * the store as the last allowed answer made it.
 */
@DioramaEntrypoint
public final class LoopingAddon implements DioramaAddon {
	private static MapWorld world;
	private static UUID id;
	private static int renames;
	private static String modId;

	@Override
	public void onReady(DioramaApi api) {
		modId = api.modId();
		api.onWorld(new WorldListener() {
			@Override
			public void opened(MapWorld opened) {
				world = opened;
				opened.waypoints().listen(changes -> {
					if (id != null && changes.by().filter(modId::equals).isPresent()) {
						int next = renames + 1;
						opened.waypoints().edit(edits -> edits.update(id, WaypointUpdate.builder().name("Loop " + next).build()));
						renames = next;
					}
				});
			}
		});
	}

	/** Adds its waypoint here, which sets the loop off. */
	public static UUID start(int x, int y, int z) {
		world.waypoints().edit(edits -> id = edits.add(NewWaypoint.builder("Loop 0", world.dimension(), BlockCoord.of(x, y, z)).build()));
		return id;
	}

	/** How many of its renames went through. */
	public static int renames() {
		return renames;
	}
}
