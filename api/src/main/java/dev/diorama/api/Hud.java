package dev.diorama.api;

import java.util.Set;

/**
 * Diorama's parts of the in-game HUD, which an addon can hide for a while, such as when it shows
 * a map of its own in the same place.
 */
public interface Hud {
	/** A part of the HUD that {@link #hide} can hide. More may be added. */
	enum Part {
		/** The minimap, with the lines of text under it. */
		MINIMAP,
		/** Waypoints drawn in the world: their pins, labels and beams. */
		WORLD_WAYPOINTS,
	}

	/**
	 * Hides these parts until the returned handle is closed.
	 *
	 * <p>The handle holds until this addon closes it, this addon is switched off after a failure,
	 * or the game exits. Opening, leaving or switching worlds and changing dimension don't close
	 * it, so an addon that wants a part hidden for as long as it is installed calls this once, from
	 * {@link DioramaAddon#onReady}.
	 *
	 * <p>Hiding lives only in memory. It never changes the player's settings, and nothing is
	 * written to Diorama's settings file, so the game always starts again with nothing hidden,
	 * after a crash too. A part shows again once every handle hiding it, from any addon, is
	 * closed, and only if Diorama would show it anyway: the player's settings, F1 and the server's
	 * rules still apply. Hiding only takes away; it never shows anything those would hide.
	 *
	 * <p>While the player places the minimap in Diorama's settings, the minimap shows regardless.
	 *
	 * <p>The player can untick this addon in the Addons tab of Diorama's settings, and that choice
	 * is saved. Its handles then stay open, and {@link Handle#isOpen} stays true, but they hide
	 * nothing until the player ticks it again.
	 *
	 * @param parts the parts to hide; at least one
	 * @return the handle that keeps them hidden while it is open
	 * @throws IllegalArgumentException if {@code parts} is empty
	 * @throws NullPointerException if {@code parts} is null or holds null
	 * @throws IllegalStateException if called off the client thread
	 */
	Handle hide(Set<Part> parts);
}
