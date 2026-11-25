package dev.diorama.testaddon;

import dev.diorama.api.DioramaEntrypoint;

/**
 * A helper marked {@link DioramaEntrypoint} by mistake: it isn't a DioramaAddon. On NeoForge and
 * Forge, Diorama should turn it down without running any of its code; the properties it sets say
 * whether it did.
 */
@DioramaEntrypoint
public final class NotAnAddon {
	static {
		System.setProperty("diorama.testaddon.notaddon.initialised", "true");
	}

	public NotAnAddon() {
		System.setProperty("diorama.testaddon.notaddon.made", "true");
	}
}
