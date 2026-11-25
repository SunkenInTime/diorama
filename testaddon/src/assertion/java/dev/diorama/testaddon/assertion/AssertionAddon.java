package dev.diorama.testaddon.assertion;

import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.DioramaEntrypoint;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import java.util.EnumSet;

/**
 * Hides the minimap, then fails an assertion: an Error that isn't an exception or a LinkageError,
 * which Diorama should still switch off like any other failure.
 */
@DioramaEntrypoint
public final class AssertionAddon implements DioramaAddon {
	private static Handle held;

	@Override
	public void onReady(DioramaApi api) {
		held = api.hud().hide(EnumSet.of(Hud.Part.MINIMAP));
		throw new AssertionError("Diorama test addon: an assertion failed on purpose");
	}

	/** The handle it took before it failed. */
	public static Handle held() {
		return held;
	}
}
