package dev.diorama.addon;

import dev.diorama.Maps;
import dev.diorama.api.DimensionId;
import dev.diorama.api.Handle;
import dev.diorama.api.MapId;
import dev.diorama.api.MapWorld;
import dev.diorama.api.Waypoint;
import dev.diorama.api.WaypointChanges;
import dev.diorama.api.WaypointEditor;
import dev.diorama.api.WaypointListener;
import dev.diorama.api.Waypoints;
import dev.diorama.api.WorldId;
import dev.diorama.waypoint.WaypointStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One addon's view of one opening of a world ({@link AddonWorlds.Context}): what it reads and
 * changes goes to that world's store, and what it changes is put down to it. Every call checks
 * first that the world is still open and is still the one Diorama is in, so it can never reach a
 * store it wasn't opened for. It closes with the world, or when the addon closes the handle it
 * got it through, or fails.
 */
final class AddonWorld implements MapWorld, Waypoints {
	final Addon addon;
	final AddonWorlds.Context context;
	final AddonWorlds.Registration registration;
	private final List<Listen> listeners = new ArrayList<>();
	/** Its world is open, and the addon still holds it. */
	boolean open = true;
	/** The addon let go of it, or failed: it's told nothing more, closed included. */
	boolean stopped;
	/** Its opened was told; until then nothing else is. */
	boolean told;
	/** The number of its opened event: it hears what's queued after. */
	long since;

	AddonWorld(Addon addon, AddonWorlds.Context context, AddonWorlds.Registration registration) {
		this.addon = addon;
		this.context = context;
		this.registration = registration;
	}

	private void check() {
		Addon.checkThread();
		if (!this.open || !this.context.valid()) {
			throw new IllegalStateException("This MapWorld is closed: the player left the world, joined it again, or Diorama doesn't know which world they're in yet. "
				+ "Use the MapWorld the next WorldListener.opened hands over");
		}
	}

	private WaypointStore store() {
		return this.context.store;
	}

	@Override
	public WorldId worldId() {
		Addon.checkThread();
		return this.context.worldId;
	}

	@Override
	public DimensionId dimension() {
		this.check();
		return this.context.dimension;
	}

	@Override
	public MapId mapId() {
		this.check();
		return this.mapId(this.context.dimension);
	}

	@Override
	public MapId mapId(DimensionId dimension) {
		this.check();
		Objects.requireNonNull(dimension, "dimension");
		return WaypointStore.mapId(Maps.of(this.store().worldKey()).active(dimension.toString()));
	}

	@Override
	public Waypoints waypoints() {
		this.check();
		return this;
	}

	@Override
	public boolean isOpen() {
		Addon.checkThread();
		return this.open && this.context.valid();
	}

	@Override
	public List<Waypoint> all() {
		this.check();
		return this.store().published();
	}

	@Override
	public Optional<Waypoint> get(UUID id) {
		this.check();
		Objects.requireNonNull(id, "id");
		return this.store().published(id);
	}

	@Override
	public void edit(Consumer<WaypointEditor> edits) {
		this.check();
		Objects.requireNonNull(edits, "edits");
		if (this.addon.failed()) {
			return;
		}
		int depth = AddonWorlds.depth();
		if (depth > MAX_EDIT_DEPTH) {
			IllegalStateException refused = new IllegalStateException("This edit would be " + depth + " answers deep, past Waypoints.MAX_EDIT_DEPTH (" + MAX_EDIT_DEPTH
				+ "): waypoint listeners are answering each other's changes, or their own, for ever. It was refused");
			this.addon.fail("Waypoints.edit", refused);
			throw refused;
		}
		this.store().edit(this.addon.id(), editor -> {
			try {
				edits.accept(editor);
			} catch (Throwable thrown) {
				// The addon's own code: it's switched off, and the batch goes nowhere.
				this.addon.fail("a Waypoints.edit batch", thrown);
				throw Addon.rethrow(thrown);
			}
		});
	}

	@Override
	public Handle listen(WaypointListener listener) {
		this.check();
		Objects.requireNonNull(listener, "listener");
		Listen listen = new Listen(this, listener);
		if (this.addon.failed()) {
			listen.open = false;
			return listen;
		}
		// Anything changed but not yet queued is queued first, for those listening already; this one hears what comes after.
		this.store().beforeListen(this.context.listeners > 0);
		listen.since = AddonWorlds.sequence();
		this.listeners.add(listen);
		this.context.listeners++;
		this.addon.keep(listen);
		return listen;
	}

	void tellOpened() {
		this.addon.call("WorldListener.opened", () -> {
			this.registration.listener.opened(this);
			return Boolean.TRUE;
		});
	}

	void tellMapChanged() {
		this.addon.call("WorldListener.mapChanged", () -> {
			this.registration.listener.mapChanged(this);
			return Boolean.TRUE;
		});
	}

	void tellClosed() {
		this.addon.call("WorldListener.closed", () -> {
			this.registration.listener.closed(this);
			return Boolean.TRUE;
		});
	}

	/** Tells each of its listeners that was listening before this change was made, and is still open. */
	void tell(WaypointChanges changes, long seq) {
		for (Listen listen : List.copyOf(this.listeners)) {
			if (listen.open && listen.since < seq && this.open) {
				this.addon.call("a WaypointListener", () -> {
					listen.listener.changed(changes);
					return Boolean.TRUE;
				});
			}
		}
	}

	/** The world closed: it and its listeners close, and its closed is queued. */
	void worldClosed() {
		this.open = false;
		this.releaseListeners();
	}

	/** The addon let go of the handle it got this through, or failed: closed, and told nothing more. */
	void stop() {
		this.open = false;
		this.stopped = true;
		this.releaseListeners();
	}

	private void releaseListeners() {
		for (Listen listen : List.copyOf(this.listeners)) {
			if (listen.release()) {
				this.addon.drop(listen);
			}
		}
	}

	/** One waypoint listener, open until closed, or its world closes, or its addon fails. */
	static final class Listen implements Handle, Addon.Held {
		final AddonWorld world;
		final WaypointListener listener;
		boolean open = true;
		/** The number of the last event queued when it was added: it hears the ones after. */
		long since;

		Listen(AddonWorld world, WaypointListener listener) {
			this.world = world;
			this.listener = listener;
		}

		@Override
		public void close() {
			Addon.checkThread();
			if (this.release()) {
				this.world.addon.drop(this);
			}
		}

		@Override
		public boolean isOpen() {
			Addon.checkThread();
			return this.open;
		}

		@Override
		public boolean release() {
			if (!this.open) {
				return false;
			}
			this.open = false;
			if (this.world.listeners.remove(this)) {
				this.world.context.listeners--;
			}
			return true;
		}
	}
}
