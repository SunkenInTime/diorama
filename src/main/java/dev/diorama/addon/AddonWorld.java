package dev.diorama.addon;

import dev.diorama.Maps;
import dev.diorama.api.DimensionId;
import dev.diorama.api.Handle;
import dev.diorama.api.MapWorld;
import dev.diorama.api.Waypoint;
import dev.diorama.api.WaypointChanges;
import dev.diorama.api.WaypointEditor;
import dev.diorama.api.WaypointListener;
import dev.diorama.api.Waypoints;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One addon's view of the world open now ({@link AddonWorlds.Context}): what it reads and changes
 * goes to the world's store, and what it changes is put down to it. It closes with the world, or
 * when the addon closes the handle it got it through, or fails.
 */
final class AddonWorld implements MapWorld, Waypoints {
	final Addon addon;
	final AddonWorlds.Context context;
	final AddonWorlds.Registration registration;
	private final List<Listen> listeners = new ArrayList<>();
	private boolean open = true;

	AddonWorld(Addon addon, AddonWorlds.Context context, AddonWorlds.Registration registration) {
		this.addon = addon;
		this.context = context;
		this.registration = registration;
	}

	private void checkOpen() {
		Addon.checkThread();
		if (!this.open) {
			throw new IllegalStateException("This world has closed in Diorama; use the MapWorld the next WorldListener.opened hands over");
		}
	}

	@Override
	public String worldId() {
		Addon.checkThread();
		return this.context.worldId;
	}

	@Override
	public DimensionId dimension() {
		this.checkOpen();
		return this.context.dimension;
	}

	@Override
	public String mapId() {
		this.checkOpen();
		return this.mapId(this.context.dimension);
	}

	@Override
	public String mapId(DimensionId dimension) {
		this.checkOpen();
		Objects.requireNonNull(dimension, "dimension");
		String id = Maps.of(this.context.store.worldKey()).active(dimension.toString());
		return id.isEmpty() ? "main" : id;
	}

	@Override
	public Waypoints waypoints() {
		this.checkOpen();
		return this;
	}

	@Override
	public boolean isOpen() {
		Addon.checkThread();
		return this.open;
	}

	@Override
	public List<Waypoint> all() {
		this.checkOpen();
		return this.context.store.published();
	}

	@Override
	public Optional<Waypoint> get(UUID id) {
		this.checkOpen();
		Objects.requireNonNull(id, "id");
		return this.context.store.published(id);
	}

	@Override
	public void edit(Consumer<WaypointEditor> edits) {
		this.checkOpen();
		Objects.requireNonNull(edits, "edits");
		if (this.addon.failed()) {
			return;
		}
		AddonWorlds.checkRound();
		this.context.store.edit(this.addon.id(), edits);
	}

	@Override
	public Handle listen(WaypointListener listener) {
		this.checkOpen();
		Objects.requireNonNull(listener, "listener");
		Listen listen = new Listen(this, listener);
		if (this.addon.failed()) {
			listen.open = false;
			return listen;
		}
		// What it hears from here is compared with the waypoints as they are now.
		this.context.store.listenFromNow();
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
		if (this.open) {
			this.addon.call("WorldListener.mapChanged", () -> {
				this.registration.listener.mapChanged(this);
				return Boolean.TRUE;
			});
		}
	}

	void tellClosed() {
		this.addon.call("WorldListener.closed", () -> {
			this.registration.listener.closed(this);
			return Boolean.TRUE;
		});
	}

	/** Tells each of its listeners still open about a change. */
	void tell(WaypointChanges changes) {
		for (Listen listen : List.copyOf(this.listeners)) {
			if (listen.open && this.open) {
				this.addon.call("a WaypointListener", () -> {
					listen.listener.changed(changes);
					return Boolean.TRUE;
				});
			}
		}
	}

	/** Closes it and its listeners: the world closed, or the addon let go of it. */
	void release() {
		this.open = false;
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
