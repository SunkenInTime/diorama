package dev.diorama.testaddon.unprintable;

import dev.diorama.api.DioramaAddon;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.DioramaEntrypoint;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import java.util.EnumSet;

/**
 * Hides the minimap, then throws an exception whose message can't be read, so its toString()
 * throws too: Diorama should switch it off and log it without that getting out.
 */
@DioramaEntrypoint
public final class UnprintableAddon implements DioramaAddon {
	private static Handle held;

	@Override
	public void onReady(DioramaApi api) {
		held = api.hud().hide(EnumSet.of(Hud.Part.MINIMAP));
		throw new Unreadable();
	}

	/** The handle it took before it failed. */
	public static Handle held() {
		return held;
	}

	/** An exception whose getMessage() throws, as one built from a field that is still null would. */
	static final class Unreadable extends RuntimeException {
		private static final long serialVersionUID = 1L;

		@Override
		public String getMessage() {
			throw new IllegalStateException("Diorama test addon: this message can't be read, on purpose");
		}
	}
}
