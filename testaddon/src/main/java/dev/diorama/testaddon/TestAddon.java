package dev.diorama.testaddon;

import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.DioramaEntrypoint;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import java.util.EnumSet;
import java.util.List;

/**
 * The game tests' addon: it does nothing on its own, and the addon-api scenario drives Diorama's
 * API through it, as an addon would, from the client thread.
 */
@DioramaEntrypoint
public final class TestAddon implements DioramaAddon {
	private static DioramaApi api;
	private static int readyCalls;
	private static String readyThread = "";

	static {
		// Read by the scenario that runs the game without Diorama: nothing should load this class there.
		System.setProperty("diorama.testaddon.loaded", "true");
	}

	@Override
	public void onReady(DioramaApi api) {
		TestAddon.api = api;
		readyCalls++;
		readyThread = Thread.currentThread().getName();
		System.out.println("Diorama test addon: ready as " + api.modId() + " on API " + api.apiVersion());
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

	/** Hides these parts through this addon's API. */
	public static Handle hide(Hud.Part... parts) {
		return api.hud().hide(parts.length == 0 ? EnumSet.noneOf(Hud.Part.class) : EnumSet.copyOf(List.of(parts)));
	}
}
