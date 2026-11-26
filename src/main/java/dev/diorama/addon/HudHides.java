package dev.diorama.addon;

import dev.diorama.api.Handle;
import dev.diorama.api.Hud;

/**
 * What addons hide of the HUD ({@link Hud#hide}), as one count of open handles per part, of the
 * addons the player lets hide parts of Diorama. The HUD reads a count each frame: one field read,
 * nothing allocated. The player's choice is folded in when it changes ({@link Addon#allow}), never
 * per frame. Only the client thread changes or reads them.
 */
public final class HudHides {
	private static int minimap;
	private static int worldWaypoints;
	/**
	 * Goes up whenever an addon is found, a handle opens or closes, the player switches an addon, or
	 * one fails, so the settings screen knows to redraw its list.
	 */
	private static int changes;

	private HudHides() {
	}

	/** Whether an addon is hiding the minimap. */
	public static boolean minimapHidden() {
		return minimap > 0;
	}

	/** Whether an addon is hiding the waypoints in the world. */
	public static boolean worldWaypointsHidden() {
		return worldWaypoints > 0;
	}

	/** A number that changes whenever the addons listed, what they hide or whether one runs does. */
	public static int changes() {
		return changes;
	}

	static void changed() {
		changes++;
	}

	static int bit(Hud.Part part) {
		return 1 << part.ordinal();
	}

	static void count(int parts, int by) {
		changes++;
		if ((parts & bit(Hud.Part.MINIMAP)) != 0) {
			minimap += by;
		}
		if ((parts & bit(Hud.Part.WORLD_WAYPOINTS)) != 0) {
			worldWaypoints += by;
		}
	}

	/** One addon's hold on some parts of the HUD, open until the addon closes it or fails. */
	static final class Hold implements Handle {
		private final Addon owner;
		private final int parts;
		private boolean open;

		/**
		 * Open and kept by its owner, which closes it if it fails; counted while the player lets the
		 * owner hide parts of Diorama.
		 */
		Hold(Addon owner, int parts) {
			this.owner = owner;
			this.parts = parts;
			this.open = true;
			if (owner.allowed()) {
				count(parts, 1);
			} else {
				// Hides nothing, but the settings screen says what it asks for.
				changed();
			}
		}

		/** One that never held anything, for an addon that was switched off. */
		Hold(Addon owner) {
			this.owner = owner;
			this.parts = 0;
			this.open = false;
		}

		@Override
		public void close() {
			Addon.checkThread();
			if (this.release()) {
				this.owner.forget(this);
			}
		}

		@Override
		public boolean isOpen() {
			Addon.checkThread();
			return this.open;
		}

		int parts() {
			return this.parts;
		}

		/** Stops counting it. True if it was still open. */
		boolean release() {
			if (!this.open) {
				return false;
			}
			this.open = false;
			if (this.owner.allowed()) {
				count(this.parts, -1);
			} else {
				changed();
			}
			return true;
		}
	}
}
