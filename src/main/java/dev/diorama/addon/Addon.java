package dev.diorama.addon;

import dev.diorama.Diorama;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import dev.diorama.api.WorldListener;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * One addon Diorama found, and the API it was handed: tied to the mod ID its loader reported, so
 * what it holds can be let go when it fails. It fails once: then what it hid shows again, its
 * listeners are closed, its later calls do nothing, and nothing of it is called again. The player
 * can stop it hiding anything ({@link #allow}): its handles stay open for it, and just aren't
 * counted.
 */
final class Addon implements DioramaApi, Hud {
	/**
	 * What reflection and the loaders wrap a constructor's error in, by exact class name, so a
	 * subclass of one can't stand in for it: {@link #fatal} looks inside these and nothing else.
	 * Fabric wraps a constructor's error as EntrypointException, around LanguageAdapterException,
	 * around InvocationTargetException (Fabric Loader 0.19.5, EntrypointContainerImpl and
	 * DefaultLanguageAdapter).
	 */
	private static final Set<String> WRAPPERS = Set.of(
		"java.lang.reflect.InvocationTargetException",
		"java.lang.ExceptionInInitializerError",
		"java.lang.reflect.UndeclaredThrowableException",
		"net.fabricmc.loader.api.EntrypointException",
		"net.fabricmc.loader.api.LanguageAdapterException");

	private final String modId;
	/** The mod's name, as its loader's metadata gives it. */
	private final String name;
	/** Its open handles, so they can be closed for it, and counted or not as the player picks. */
	private final List<HudHides.Hold> holds = new ArrayList<>();
	/** Its other open handles: world listeners and waypoint listeners. */
	private final List<Held> held = new ArrayList<>();
	private boolean failed;
	private boolean allowed;

	/** Something an addon holds besides a part of the HUD, let go of when it fails. */
	interface Held {
		/** Lets go; true if it still held. */
		boolean release();
	}

	Addon(String modId, String name, boolean allowed) {
		this.modId = modId;
		this.name = name;
		this.allowed = allowed;
	}

	/** Throws unless this is the client thread, where everything addons can reach lives. */
	static void checkThread() {
		if (!Minecraft.getInstance().isSameThread()) {
			throw new IllegalStateException("Diorama's API is only for the client thread, not " + Thread.currentThread().getName());
		}
	}

	/**
	 * Calls into the addon, its constructor included. If it throws, the addon is switched off
	 * ({@link #fail}); then a {@link #fatal} error goes on up, and anything else goes no further.
	 * Null if it threw, or was already switched off.
	 */
	<T> @Nullable T call(String what, Callable<T> call) {
		if (this.failed) {
			return null;
		}
		try {
			return call.call();
		} catch (Throwable thrown) {
			this.fail(what, thrown);
			Error fatal = fatal(thrown);
			if (fatal != null) {
				throw fatal;
			}
			return null;
		}
	}

	/**
	 * Switches the addon off: its handles are closed, what it hid shows again, and unless it was a
	 * {@link #fatal} error, one line is logged. Once only: an addon switched off from inside one of
	 * its own calls (a refused edit, a batch that threw) isn't logged again when that call throws.
	 */
	void fail(String what, Throwable thrown) {
		if (this.failed) {
			return;
		}
		this.failed = true;
		for (HudHides.Hold hold : this.holds) {
			hold.release();
		}
		this.holds.clear();
		for (Held each : List.copyOf(this.held)) {
			each.release();
		}
		this.held.clear();
		HudHides.changed();
		if (fatal(thrown) == null) {
			log(this.modId, what, thrown);
		}
	}

	/** Throws {@code thrown} as it is, checked or not, for code that has to pass on what an addon threw. */
	@SuppressWarnings("unchecked")
	static <E extends Throwable> RuntimeException rethrow(Throwable thrown) throws E {
		throw (E) thrown;
	}

	/**
	 * The JVM running out of memory or stack, or breaking ({@link VirtualMachineError}), or the
	 * thread being stopped, thrown as it is or inside one of the {@link #WRAPPERS}. Null for
	 * anything else, including one of those errors an addon wrapped in an exception of its own.
	 */
	@SuppressWarnings("removal")
	private static @Nullable Error fatal(Throwable thrown) {
		Throwable at = thrown;
		for (int depth = 0; at != null && depth < 8; depth++) {
			if (at instanceof VirtualMachineError || at instanceof ThreadDeath) {
				return (Error) at;
			}
			if (!WRAPPERS.contains(at.getClass().getName())) {
				return null;
			}
			at = at.getCause();
		}
		return null;
	}

	/**
	 * One ERROR line naming the addon and what it threw, by class name, with the stack trace under
	 * it. The throwable is the addon's, and printing it runs the addon's own code (getMessage(),
	 * toString()), which may throw anything, a StackOverflowError included: then the line goes out
	 * without the trace. Nothing gets out of here.
	 */
	private static void log(String modId, String what, Throwable thrown) {
		String line = "Diorama addon " + modId + " failed in " + what + " and is switched off until the game restarts: " + thrown.getClass().getName();
		try {
			StringWriter trace = new StringWriter();
			thrown.printStackTrace(new PrintWriter(trace));
			Diorama.LOGGER.error("{}{}{}", line, System.lineSeparator(), trace);
		} catch (Throwable printing) {
			try {
				Diorama.LOGGER.error("{} (its stack trace couldn't be printed: {})", line, printing.getClass().getName());
			} catch (Throwable ignored) {
				// Nowhere left to say it.
			}
		}
	}

	boolean failed() {
		return this.failed;
	}

	boolean allowed() {
		return this.allowed;
	}

	/**
	 * Whether the player lets it hide parts of Diorama. Its open handles are counted in or out at
	 * once, and stay open for it either way.
	 */
	void allow(boolean allowed) {
		if (allowed == this.allowed) {
			return;
		}
		this.allowed = allowed;
		for (HudHides.Hold hold : this.holds) {
			HudHides.count(hold.parts(), allowed ? 1 : -1);
		}
		HudHides.changed();
	}

	/** The parts its open handles ask to hide, as {@link HudHides#bit} bits, counted or not. */
	int asked() {
		int parts = 0;
		for (HudHides.Hold hold : this.holds) {
			parts |= hold.parts();
		}
		return parts;
	}

	String name() {
		return this.name;
	}

	void forget(HudHides.Hold hold) {
		this.holds.remove(hold);
	}

	void keep(Held each) {
		this.held.add(each);
	}

	void drop(Held each) {
		this.held.remove(each);
	}

	String id() {
		return this.modId;
	}

	@Override
	public String apiVersion() {
		checkThread();
		return Addons.API_VERSION;
	}

	@Override
	public String modId() {
		checkThread();
		return this.modId;
	}

	@Override
	public Hud hud() {
		checkThread();
		return this;
	}

	@Override
	public Handle hide(Set<Part> parts) {
		checkThread();
		Objects.requireNonNull(parts, "parts");
		if (parts.isEmpty()) {
			throw new IllegalArgumentException("Nothing to hide: give hide() at least one part");
		}
		int bits = 0;
		for (Part part : parts) {
			bits |= HudHides.bit(Objects.requireNonNull(part, "a part"));
		}
		if (this.failed) {
			return new HudHides.Hold(this);
		}
		HudHides.Hold hold = new HudHides.Hold(this, bits);
		this.holds.add(hold);
		return hold;
	}

	@Override
	public Handle onWorld(WorldListener listener) {
		checkThread();
		Objects.requireNonNull(listener, "listener");
		if (this.failed) {
			return AddonWorlds.closed(this);
		}
		return AddonWorlds.register(this, listener);
	}

	@Override
	public String toString() {
		return "Diorama API for " + this.modId;
	}
}
