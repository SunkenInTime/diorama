package dev.diorama.addon;

import dev.diorama.api.Handle;
import dev.diorama.api.Hud;

/**
 * What addons hide of the HUD ({@link Hud#hide}), as one count of open handles per part. The HUD
 * reads a count each frame: one field read, nothing allocated. Only the client thread changes or
 * reads them.
 */
public final class HudHides {
	private static int minimap;
	private static int worldWaypoints;

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

	static int bit(Hud.Part part) {
		return 1 << part.ordinal();
	}

	private static void count(int parts, int by) {
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

		/** Open, counted, and kept by its owner, which closes it if it fails. */
		Hold(Addon owner, int parts) {
			this.owner = owner;
			this.parts = parts;
			this.open = true;
			count(parts, 1);
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

		/** Stops counting it. True if it was still open. */
		boolean release() {
			if (!this.open) {
				return false;
			}
			this.open = false;
			count(this.parts, -1);
			return true;
		}
	}
}
