package dev.diorama.testaddon.recursive;

import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.DioramaEntrypoint;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import java.util.EnumSet;

/**
 * Hides the minimap, then throws an exception whose getMessage() calls itself, so reading it
 * overflows the stack: Diorama should switch it off and log it without that getting out.
 */
@DioramaEntrypoint
public final class RecursiveAddon implements DioramaAddon {
	private static Handle held;

	@Override
	public void onReady(DioramaApi api) {
		held = api.hud().hide(EnumSet.of(Hud.Part.MINIMAP));
		throw new Endless();
	}

	/** The handle it took before it failed. */
	public static Handle held() {
		return held;
	}

	/** An exception whose message never finishes being built. */
	static final class Endless extends RuntimeException {
		private static final long serialVersionUID = 1L;

		@Override
		public String getMessage() {
			return "again: " + this.getMessage();
		}
	}
}
