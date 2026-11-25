package dev.diorama.testaddon.linkage;

import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.DioramaEntrypoint;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import java.util.EnumSet;

/**
 * Hides the minimap, then calls a class its jar doesn't have, as an addon built against something
 * newer than what's installed would: a NoClassDefFoundError, which is a LinkageError.
 */
@DioramaEntrypoint
public final class LinkageAddon implements DioramaAddon {
	private static Handle held;

	@Override
	public void onReady(DioramaApi api) {
		held = api.hud().hide(EnumSet.of(Hud.Part.MINIMAP));
		MissingClass.call();
	}

	/** The handle it took before it failed. */
	public static Handle held() {
		return held;
	}
}
