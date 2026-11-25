package dev.diorama.addon;

import dev.diorama.api.DimensionId;
import dev.diorama.api.Handle;
import dev.diorama.api.WaypointChange;
import dev.diorama.api.WaypointChanges;
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
 * The world addons see ({@link dev.diorama.api.DioramaApi#onWorld}), followed from Diorama's own
 * state, never from the mod loaders' join and leave events, which differ between loaders: a world
 * is open while the client has a level and a {@link WaypointStore} is loaded for it, and its map
 * changes when the player's dimension does or a dimension switches saved map
 * ({@link WaypointStore#mapSwitches}). Each client tick compares, and tells addons what moved.
 * With no addon listening, a tick does nothing.
 *
 * <p>Waypoint changes come from the store as they're made ({@link #deliver}), each after its
 * save. A listener that edits from inside its call is told about its own change once every
 * listener has heard the current one; {@link #MAX_ROUNDS} such rounds in a row stop it.
 */
public final class AddonWorlds {
	/** How many rounds of changes answered with changes one change may set off. */
	static final int MAX_ROUNDS = 50;

	private static final List<Registration> REGISTRATIONS = new ArrayList<>();
	private static @Nullable Context context;
	private static boolean syncing;
	private static boolean delivering;
	private static int rounds;
	private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();

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
		sync();
		Context open = context;
		if (open != null && registration.open && open.viewOf(registration) == null) {
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

	/** Every client tick: opens, changes and closes the world addons see, and catches Waystones and the portal pin setting up. */
	public static void tick() {
		if (REGISTRATIONS.isEmpty()) {
			Context open = context;
			if (open != null) {
				close(open);
			}
			return;
		}
		sync();
		Context open = context;
		if (open != null && open.listeners > 0) {
			open.store.outsideChanges();
		}
	}

	/** Whether an addon listens to this store's waypoints, so changes to them are worth comparing. */
	public static boolean listening(WaypointStore store) {
		Context open = context;
		return open != null && open.store == store && open.listeners > 0;
	}

	/**
	 * Throws if an addon's edit from inside a listener would start one round too many. Edits from
	 * anywhere else start afresh.
	 */
	static void checkRound() {
		if (delivering && rounds >= MAX_ROUNDS) {
			throw new IllegalStateException("Waypoint listeners answered changes with changes " + MAX_ROUNDS + " rounds in a row; this edit would start another, so it was refused");
		}
	}

	/**
	 * A change to a store's waypoints, saved: every listener on it hears it, unless one is being told
	 * of another change, after which this one goes out.
	 */
	public static void deliver(WaypointStore store, List<WaypointChange> changes, @Nullable String by) {
		Context open = context;
		if (open == null || open.store != store || open.listeners == 0) {
			return;
		}
		QUEUE.add(new Pending(changes, by));
		if (delivering) {
			return;
		}
		delivering = true;
		try {
			// So a switch of map or dimension is told before the first waypoint event on the new one.
			sync();
			Pending pending;
			while ((pending = QUEUE.poll()) != null) {
				Context now = context;
				if (now == null || now.store != store) {
					break;
				}
				rounds++;
				Changes event = new Changes(Optional.ofNullable(pending.by()), List.copyOf(pending.changes()));
				for (AddonWorld view : List.copyOf(now.views)) {
					view.tell(event);
				}
			}
		} finally {
			delivering = false;
			rounds = 0;
			QUEUE.clear();
		}
	}

	/** Compares what Diorama has now with what addons were last told, and tells them what changed. */
	private static void sync() {
		if (syncing) {
			return;
		}
		syncing = true;
		try {
			Minecraft mc = Minecraft.getInstance();
			WaypointStore store = null;
			if (mc.level != null) {
				// Loads the world's store if nothing has yet, as the HUD would on its next frame.
				WaypointStore.get();
				store = WaypointStore.peek();
			}
			Context open = context;
			if (open != null && open.store != store) {
				close(open);
				open = null;
			}
			if (open == null) {
				if (store != null && !REGISTRATIONS.isEmpty()) {
					Context made = new Context(store, worldId(store.worldKey()), mc.level.dimension(), store.mapSwitches());
					context = made;
					for (Registration registration : List.copyOf(REGISTRATIONS)) {
						if (context != made) {
							break;
						}
						made.open(registration);
					}
				}
			} else if (open.dimensionKey != mc.level.dimension() || open.mapSwitches != store.mapSwitches()) {
				open.moved(mc.level.dimension(), store.mapSwitches());
				for (AddonWorld view : List.copyOf(open.views)) {
					if (context != open) {
						break;
					}
					view.tellMapChanged();
				}
			}
		} finally {
			syncing = false;
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
			view.release();
		}
		for (AddonWorld view : views) {
			view.tellClosed();
		}
	}

	/**
	 * The world key, hashed: it names Diorama's folders, which addons shouldn't build on, and a
	 * server's address, which their files needn't hold. 32 hex digits.
	 */
	static String worldId(String worldKey) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(worldKey.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 16);
		} catch (NoSuchAlgorithmException e) {
			// Every JVM has SHA-256.
			throw new IllegalStateException(e);
		}
	}

	/** The world open now, as addons see it: one per loaded store. */
	static final class Context {
		final WaypointStore store;
		final String worldId;
		ResourceKey<Level> dimensionKey;
		DimensionId dimension;
		int mapSwitches;
		boolean open = true;
		/** Each listening addon's own view of it. */
		final List<AddonWorld> views = new ArrayList<>();
		/** Waypoint listeners open across the views. */
		int listeners;

		Context(WaypointStore store, String worldId, ResourceKey<Level> dimensionKey, int mapSwitches) {
			this.store = store;
			this.worldId = worldId;
			this.dimensionKey = dimensionKey;
			this.dimension = DimensionId.of(dimensionKey.identifier().toString());
			this.mapSwitches = mapSwitches;
		}

		void moved(ResourceKey<Level> dimensionKey, int mapSwitches) {
			if (dimensionKey != this.dimensionKey) {
				this.dimensionKey = dimensionKey;
				this.dimension = DimensionId.of(dimensionKey.identifier().toString());
			}
			this.mapSwitches = mapSwitches;
		}

		@Nullable AddonWorld viewOf(Registration registration) {
			for (AddonWorld view : this.views) {
				if (view.registration == registration) {
					return view;
				}
			}
			return null;
		}

		/** Gives the registration's addon its view of this world, and tells it the world opened. */
		void open(Registration registration) {
			if (!registration.open || registration.addon.failed()) {
				return;
			}
			AddonWorld view = new AddonWorld(registration.addon, this, registration);
			this.views.add(view);
			view.tellOpened();
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

		/** Stops telling it anything, and closes the world it was handed. True if it was still open. */
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
					view.release();
				}
			}
			return true;
		}
	}

	private record Pending(List<WaypointChange> changes, @Nullable String by) {
	}

	/** One change as listeners hear it. */
	private record Changes(Optional<String> by, List<WaypointChange> changes) implements WaypointChanges {
	}
}
