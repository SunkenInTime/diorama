package dev.diorama.testaddon.overflow;

import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.DioramaEntrypoint;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import java.util.EnumSet;

/**
 * Hides the minimap, then throws a StackOverflowError wrapped in its own RuntimeException: an
 * ordinary failure of this addon, which Diorama should switch off rather than crash the game for.
 */
@DioramaEntrypoint
public final class OverflowAddon implements DioramaAddon {
	private static Handle held;

	@Override
	public void onReady(DioramaApi api) {
		held = api.hud().hide(EnumSet.of(Hud.Part.MINIMAP));
		throw new RuntimeException("Diorama test addon: a wrapped stack overflow, on purpose", new StackOverflowError());
	}

	/** The handle it took before it failed. */
	public static Handle held() {
		return held;
	}
}
