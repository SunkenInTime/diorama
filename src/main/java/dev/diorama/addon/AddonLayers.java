package dev.diorama.addon;

import dev.diorama.api.LayerContent;
import dev.diorama.api.MapLayer;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The layers of markers and lines addons have open ({@link MapLayer}), in the order they were
 * opened, which is the order the maps draw them in. Each holds the last content its addon handed
 * over, as the addon built it: replacing it swaps one reference, and nothing is copied or worked
 * out until a map draws it. The maps read {@link #open} every frame; with no layer open that is
 * one size check. Only the client thread changes or reads them.
 *
 * <p>When the world open changes, every layer is emptied ({@link #sync}), so what an addon showed
 * in one world never shows in another.
 */
public final class AddonLayers {
	/** Every open layer, of every addon, oldest first. */
	private static final List<Layer> OPEN = new ArrayList<>();
	/** Moves whenever a layer opens, closes or is given new content, or an addon is switched on or off. */
	private static long changes;
	/** The world the layers' content is for: its store, and its key (null with no world). */
	private static @Nullable WaypointStore seenStore;
	private static @Nullable String seenKey;

	private AddonLayers() {
	}

	/**
	 * Empties every layer if the world open isn't the one their content was handed over in: the
	 * player left it, or joined another (a different world key, so a different
	 * {@link dev.diorama.api.World#worldId}). Asked each client tick, before a map draws the
	 * layers, and before a layer takes new content, so content handed over in the new world is
	 * never emptied for the old one's sake. With no layer open, nothing to do.
	 */
	public static void sync() {
		if (OPEN.isEmpty()) {
			return;
		}
		WaypointStore store = CurrentWorld.store();
		if (store == seenStore) {
			return;
		}
		seenStore = store;
		String key = store == null ? null : store.worldKey();
		if (Objects.equals(key, seenKey)) {
			return;
		}
		seenKey = key;
		for (Layer layer : OPEN) {
			layer.content = LayerContent.EMPTY;
		}
		changed();
	}

	/** The first layer to open takes the world open now as the one its content is for. */
	private static void seeWorldNow() {
		WaypointStore store = CurrentWorld.store();
		seenStore = store;
		seenKey = store == null ? null : store.worldKey();
	}

	/** The open layers, oldest first, those of addons the player switched off included: check {@link Layer#shown}. Don't change it. */
	public static List<Layer> open() {
		return OPEN;
	}

	/** A number that moves whenever what the layers would draw does: for tests and for caches. */
	public static long changes() {
		return changes;
	}

	static void changed() {
		changes++;
	}

	/** One addon's layer. */
	public static final class Layer implements MapLayer {
		private final Addon owner;
		private final String name;
		private LayerContent content = LayerContent.EMPTY;
		private boolean open;

		private Layer(Addon owner, String name, boolean open) {
			this.owner = owner;
			this.name = name;
			this.open = open;
			if (open) {
				if (OPEN.isEmpty()) {
					seeWorldNow();
				}
				OPEN.add(this);
				changed();
			}
		}

		/** Open, and drawn from the next frame on; its owner keeps it, to close it if it fails. */
		static Layer opened(Addon owner, String name) {
			return new Layer(owner, name, true);
		}

		/** One that never held anything, for an addon that was switched off. */
		static Layer closed(Addon owner, String name) {
			return new Layer(owner, name, false);
		}

		@Override
		public String name() {
			Addon.checkThread();
			return this.name;
		}

		@Override
		public void replace(LayerContent content) {
			Addon.checkThread();
			Objects.requireNonNull(content, "content");
			sync();
			if (this.open && content != this.content) {
				this.content = content;
				changed();
			}
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

		/** What it shows, as its addon last handed it over. */
		public LayerContent content() {
			return this.content;
		}

		/** Whether the maps draw it: its addon runs and the player hasn't switched it off. */
		public boolean shown() {
			return this.open && this.owner.allowed();
		}

		/** The mod ID of the addon that opened it. */
		public String modId() {
			return this.owner.id();
		}

		/** The name it was opened with, without the thread check, for Diorama's own code and logs. */
		public String layerName() {
			return this.name;
		}

		/** Takes it off the maps. True if it was still open. */
		boolean release() {
			if (!this.open) {
				return false;
			}
			this.open = false;
			this.content = LayerContent.EMPTY;
			OPEN.remove(this);
			changed();
			return true;
		}

		@Override
		public String toString() {
			return "Layer " + this.name + " of " + this.owner.id() + (this.open ? "" : " (closed)");
		}
	}
}
