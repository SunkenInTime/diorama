package dev.diorama.testaddon.throwing;

import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.DioramaEntrypoint;
import dev.diorama.api.Handle;
import dev.diorama.api.MapWorld;
import dev.diorama.api.WorldListener;

/**
 * Listens to the waypoints of every world, and once armed, throws from its listener on the next
 * change: Diorama should switch it off, close its handles, and let the change and the other
 * addons' listeners carry on.
 */
@DioramaEntrypoint
public final class ThrowingAddon implements DioramaAddon {
	private static Handle world;
	private static Handle listening;
	private static boolean armed;
	private static int heard;

	@Override
	public void onReady(DioramaApi api) {
		world = api.onWorld(new WorldListener() {
			@Override
			public void opened(MapWorld opened) {
				listening = opened.waypoints().listen(changes -> {
					heard++;
					if (armed) {
						throw new IllegalStateException("Diorama test addon: a waypoint listener that throws on purpose");
					}
				});
			}
		});
	}

	/** Throws on the next change it hears. */
	public static void arm() {
		armed = true;
	}

	/** The handle onWorld returned. */
	public static Handle world() {
		return world;
	}

	/** The handle its listener in the current world returned, or null before one opened. */
	public static Handle listening() {
		return listening;
	}

	/** How many changes it heard, the one it threw on included. */
	public static int heard() {
		return heard;
	}
}
