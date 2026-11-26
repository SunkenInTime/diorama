package dev.diorama.addon;

import dev.diorama.WorldKey;
import dev.diorama.api.DimensionId;
import dev.diorama.api.Handle;
import dev.diorama.api.WaypointChange;
import dev.diorama.api.WaypointChanges;
import dev.diorama.api.Waypoints;
import dev.diorama.api.WorldId;
import dev.diorama.api.WorldListener;
import dev.diorama.waypoint.WaypointStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The world addons see ({@link dev.diorama.api.DioramaApi#onWorld}), and the one queue everything
 * addons are told goes through.
 *
 * <p><b>Which world.</b> Followed from Diorama's own state, never from the mod loaders' join and
 * leave events, which differ between loaders. A world is open while the client has a level, the
 * {@link WaypointStore} loaded is the one {@link WaypointStore#get} would hand out, and the world's
 * key ({@link WorldKey#current}) is known and is that store's. When any of that stops being true
 * the world is closed: the store replaced (another world, or this one joined again), or its key
 * not known for a moment (a dimension change waiting for a server to name its level). Every call
 * an addon makes on a world checks this first ({@link Context#valid}), so a world can't reach a
 * store it wasn't opened for, even before the tick notices and closes it.
 *
 * <p><b>The queue.</b> Every world event (opened, mapChanged, closed) and every change to the
 * waypoints is put on one queue as it happens, numbered. Only {@link #tick}, once a client tick,
 * tells them, in order, so every addon hears one order, and nothing is told from inside an edit or
 * from inside another call: an edit a listener makes goes on the end and is told in the same tick,
 * after the call that made it returns. A listener hears the changes numbered after it was added
 * ({@link AddonWorld.Listen#since}). Each change notes how many answers deep it is
 * ({@link Event#depth}): one made while a change is being told is one deeper, and an addon editing
 * past {@link Waypoints#MAX_EDIT_DEPTH} is switched off.
 *
 * <p>With no addon listening to waypoints, nothing is compared and no change is made into an
 * event; with no addon calling onWorld, a tick does nothing.
 */
public final class AddonWorlds {
	private static final List<Registration> REGISTRATIONS = new ArrayList<>();
	private static @Nullable Context context;
	private static final ArrayDeque<Event> QUEUE = new ArrayDeque<>();
	/** The number of the last event queued. */
	private static long sequence;
	/** The depth of the event being told now; -1 while none is. */
	private static int delivering = -1;
	private static boolean draining;

	private AddonWorlds() {
	}

	/** Throws unless this is the client thread. */
	public static void checkThread() {
		Addon.checkThread();
	}

	/** An addon's {@link WorldListener}: told of every world from now on, until closed or the addon fails. */
	static Handle register(Addon addon, WorldListener listener) {
		Registration registration = new Registration(addon, listener);
		REGISTRATIONS.add(registration);
		addon.keep(registration);
		Context open = context;
		if (open != null && open.valid()) {
			// Told at the next tick, with everything else; a world that isn't valid any more is closed then, and this opens with the next.
			open.open(registration);
		}
		return registration;
	}

	/** A handle that never held anything, for an addon that was switched off. */
	static Handle closed(Addon addon) {
		Registration registration = new Registration(addon, new WorldListener() {
		});
		registration.open = false;
		return registration;
	}

	/**
	 * Every client tick: closes, opens and moves the world addons see, turns what changed outside
	 * the store (Waystones, the portal pin setting) into a change if anyone listens, then tells
	 * everything queued, in order.
	 */
	public static void tick() {
		if (REGISTRATIONS.isEmpty() && context == null && QUEUE.isEmpty()) {
			return;
		}
		sync();
		Context open = context;
		if (open != null && open.listeners > 0) {
			open.store.tellPending();
		}
		drain();
	}

	/** Whether the store is that of the world open now and Diorama is using it: see {@link AddonWorlds}. */
	static boolean current(WaypointStore store) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && WaypointStore.peek() == store && store.worldKey().equals(WorldKey.current());
	}

	/** Whether an addon listens to this store's waypoints, so changes to them are worth comparing and telling. */
	public static boolean listening(WaypointStore store) {
		Context open = context;
		return open != null && open.store == store && open.listeners > 0 && open.valid();
	}

	/** The number of the last event queued: a listener added now hears the ones after it. */
	static long sequence() {
		return sequence;
	}

	/** How deep an edit made now would be: 0 outside any call, one more than the event being told inside one. */
	static int depth() {
		return delivering < 0 ? 0 : delivering + 1;
	}

	/** A change to a store's waypoints, saved and compared: queued for its world's listeners, after any dimension change not yet queued. */
	public static void changed(WaypointStore store, List<WaypointChange> changes, @Nullable String by) {
		Context open = context;
		if (open == null || open.store != store || !open.valid()) {
			return;
		}
		moved(open);
		queue(new Changes(open, Optional.ofNullable(by), List.copyOf(changes)));
	}

	/** A dimension of this store switched saved map, its waypoints already swapped: queued as a mapChanged. */
	public static void mapSwitched(WaypointStore store) {
		Context open = context;
		if (open == null || open.store != store || !open.valid()) {
			return;
		}
		moved(open);
		queue(new MapChanged(open));
	}

	private static void queue(Event event) {
		event.seq = ++sequence;
		event.depth = depth();
		QUEUE.add(event);
	}

	/** Tells everything queued, in order, including what's queued while telling. */
	private static void drain() {
		if (draining) {
			return;
		}
		draining = true;
		try {
			Event event;
			while ((event = QUEUE.poll()) != null) {
				delivering = event.depth;
				event.deliver();
			}
		} finally {
			delivering = -1;
			draining = false;
		}
	}

	/** Compares what Diorama has now with the world addons were last given, and queues what changed. */
	private static void sync() {
		Context open = context;
		if (open != null && (REGISTRATIONS.isEmpty() || !open.valid())) {
			close(open);
			open = null;
		}
		Minecraft mc = Minecraft.getInstance();
		if (open == null) {
			if (REGISTRATIONS.isEmpty() || mc.level == null) {
				return;
			}
			// Loads the world's store if nothing has yet, as the HUD would on its next frame.
			WaypointStore store = WaypointStore.get();
			if (store == null || !current(store)) {
				return;
			}
			Context made = new Context(store, worldId(store.worldKey()), mc.level.dimension());
			context = made;
			for (Registration registration : List.copyOf(REGISTRATIONS)) {
				made.open(registration);
			}
		} else {
			moved(open);
		}
	}

	/** Queues a mapChanged if the player's dimension isn't the one addons were last told. */
	private static void moved(Context open) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.level.dimension() != open.dimensionKey) {
			open.moved(mc.level.dimension());
			queue(new MapChanged(open));
		}
	}

	private static void close(Context open) {
		open.open = false;
		if (context == open) {
			context = null;
		}
		List<AddonWorld> views = List.copyOf(open.views);
		open.views.clear();
		for (AddonWorld view : views) {
			view.worldClosed();
			queue(new Closed(view));
		}
	}

	/**
	 * The world key, hashed: it names Diorama's folders, which addons shouldn't build on, and a
	 * server's address, which their files needn't hold. 32 hex digits.
	 */
	static WorldId worldId(String worldKey) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(worldKey.getBytes(StandardCharsets.UTF_8));
			return WorldId.of(HexFormat.of().formatHex(hash, 0, 16));
		} catch (NoSuchAlgorithmException e) {
			// Every JVM has SHA-256.
			throw new IllegalStateException(e);
		}
	}

	/** For the game tests: how many events wait to be told. */
	public static int queuedForTests() {
		return QUEUE.size();
	}

	/** One opening of a world, as addons see it: one store and one world key, until it closes. */
	static final class Context {
		final WaypointStore store;
		final WorldId worldId;
		ResourceKey<Level> dimensionKey;
		DimensionId dimension;
		boolean open = true;
		/** Each listening addon's own view of it. */
		final List<AddonWorld> views = new ArrayList<>();
		/** Waypoint listeners open across the views. */
		int listeners;

		Context(WaypointStore store, WorldId worldId, ResourceKey<Level> dimensionKey) {
			this.store = store;
			this.worldId = worldId;
			this.dimensionKey = dimensionKey;
			this.dimension = DimensionId.of(dimensionKey.identifier().toString());
		}

		/** Not closed, and still the world Diorama is in: see {@link AddonWorlds}. */
		boolean valid() {
			return this.open && current(this.store);
		}

		void moved(ResourceKey<Level> dimensionKey) {
			this.dimensionKey = dimensionKey;
			this.dimension = DimensionId.of(dimensionKey.identifier().toString());
		}

		@Nullable AddonWorld viewOf(Registration registration) {
			for (AddonWorld view : this.views) {
				if (view.registration == registration) {
					return view;
				}
			}
			return null;
		}

		/** Gives the registration's addon its view of this world, and queues telling it the world opened. */
		void open(Registration registration) {
			if (!registration.open || registration.addon.failed()) {
				return;
			}
			AddonWorld view = new AddonWorld(registration.addon, this, registration);
			this.views.add(view);
			Opened opened = new Opened(view);
			queue(opened);
			view.since = opened.seq;
		}
	}

	/** One addon's {@link WorldListener}, until it closes it or fails. */
	static final class Registration implements Handle, Addon.Held {
		final Addon addon;
		final WorldListener listener;
		boolean open = true;

		Registration(Addon addon, WorldListener listener) {
			this.addon = addon;
			this.listener = listener;
		}

		@Override
		public void close() {
			Addon.checkThread();
			if (this.release()) {
				this.addon.drop(this);
			}
		}

		@Override
		public boolean isOpen() {
			Addon.checkThread();
			return this.open;
		}

		/** Stops telling it anything, and closes the world it was handed, untold. True if it was still open. */
		@Override
		public boolean release() {
			if (!this.open) {
				return false;
			}
			this.open = false;
			REGISTRATIONS.remove(this);
			Context open = context;
			if (open != null) {
				AddonWorld view = open.viewOf(this);
				if (view != null) {
					open.views.remove(view);
					view.stop();
				}
			}
			return true;
		}
	}

	/** Something queued for addons, numbered in the order it happened. */
	private abstract static class Event {
		long seq;
		/** How many answers deep: 0 for one made outside any call to an addon, else one more than the event being told when it was made. */
		int depth;

		abstract void deliver();
	}

	private static final class Opened extends Event {
		final AddonWorld view;

		Opened(AddonWorld view) {
			this.view = view;
		}

		@Override
		void deliver() {
			// A world that closed before this was told is never told at all: no closed either.
			if (this.view.open) {
				this.view.told = true;
				this.view.tellOpened();
			}
		}
	}

	private static final class Closed extends Event {
		final AddonWorld view;

		Closed(AddonWorld view) {
			this.view = view;
		}

		@Override
		void deliver() {
			if (this.view.told && !this.view.stopped) {
				this.view.tellClosed();
			}
		}
	}

	private static final class MapChanged extends Event {
		final Context world;

		MapChanged(Context world) {
			this.world = world;
		}

		@Override
		void deliver() {
			if (!this.world.open) {
				return;
			}
			for (AddonWorld view : List.copyOf(this.world.views)) {
				if (view.told && view.open && view.since < this.seq) {
					view.tellMapChanged();
				}
			}
		}
	}

	/** One change as listeners hear it. */
	private static final class Changes extends Event implements WaypointChanges {
		final Context world;
		final Optional<String> by;
		final List<WaypointChange> changes;

		Changes(Context world, Optional<String> by, List<WaypointChange> changes) {
			this.world = world;
			this.by = by;
			this.changes = changes;
		}

		@Override
		void deliver() {
			// A world closed since: its closed comes instead, and nothing on it can be read now.
			if (!this.world.open) {
				return;
			}
			for (AddonWorld view : List.copyOf(this.world.views)) {
				if (view.told && view.open) {
					view.tell(this, this.seq);
				}
			}
		}

		@Override
		public Optional<String> by() {
			return this.by;
		}

		@Override
		public List<WaypointChange> changes() {
			return this.changes;
		}

		@Override
		public String toString() {
			return "WaypointChanges[by " + this.by.orElse("Diorama or the player") + ", " + this.changes + "]";
		}
	}
}
