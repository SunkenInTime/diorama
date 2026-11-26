package dev.diorama.testaddon.broken;

import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.DioramaEntrypoint;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import java.util.EnumSet;

/** Hides the minimap, then fails: Diorama should switch it off and show the minimap again. */
@DioramaEntrypoint
public final class BrokenAddon implements DioramaAddon {
	private static DioramaApi api;
	private static Handle held;

	@Override
	public void onReady(DioramaApi api) {
		BrokenAddon.api = api;
		held = api.hud().hide(EnumSet.of(Hud.Part.MINIMAP));
		throw new IllegalStateException("Diorama test addon: broken on purpose");
	}

	/** The API Diorama handed this addon before it failed. */
	public static DioramaApi api() {
		return api;
	}

	/** The handle it took before it failed. */
	public static Handle held() {
		return held;
	}
}
