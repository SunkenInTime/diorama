package dev.diorama.addon;

import dev.diorama.Diorama;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * One addon Diorama found, and the API it was handed: tied to the mod ID its loader reported, so
 * what it holds can be let go when it fails. It fails once: then what it hid shows again, its later
 * calls do nothing, and nothing of it is called again.
 */
final class Addon implements DioramaApi, Hud {
	private final String modId;
	/** Its open handles, so they can be closed for it. */
	private final List<HudHides.Hold> holds = new ArrayList<>();
	private boolean failed;

	Addon(String modId) {
		this.modId = modId;
	}

	/** Throws unless this is the client thread, where everything addons can reach lives. */
	static void checkThread() {
		if (!Minecraft.getInstance().isSameThread()) {
			throw new IllegalStateException("Diorama's API is only for the client thread, not " + Thread.currentThread().getName());
		}
	}

	/**
	 * Calls into the addon, its constructor included. Whatever it throws switches it off instead of
	 * reaching Diorama or other addons, except what no addon can recover from ({@link #fatal}),
	 * which goes on up. Null if it threw, or was already switched off.
	 */
	<T> @Nullable T call(String what, Callable<T> call) {
		if (this.failed) {
			return null;
		}
		try {
			return call.call();
		} catch (Throwable thrown) {
			rethrowFatal(thrown);
			this.fail(what, thrown);
			return null;
		}
	}

	/**
	 * The JVM running out of memory or stack, or breaking ({@link VirtualMachineError}), or the
	 * thread being stopped: not the addon's failure to recover from, so not caught. Looked for
	 * through the causes too, since reflection wraps a constructor's (InvocationTargetException,
	 * ExceptionInInitializerError) and Fabric wraps those again. Null for anything else.
	 */
	static @Nullable Error fatal(Throwable thrown) {
		Map<Throwable, Boolean> seen = new IdentityHashMap<>();
		for (Throwable at = thrown; at != null && seen.put(at, Boolean.TRUE) == null && seen.size() <= 32; at = causeOf(at)) {
			if (isFatal(at)) {
				return (Error) at;
			}
		}
		return null;
	}

	@SuppressWarnings("removal")
	private static boolean isFatal(Throwable thrown) {
		return thrown instanceof VirtualMachineError || thrown instanceof ThreadDeath;
	}

	static void rethrowFatal(Throwable thrown) {
		Error fatal = fatal(thrown);
		if (fatal != null) {
			throw fatal;
		}
	}

	/** Its cause, or null if getCause() (the addon's to override) throws. */
	private static @Nullable Throwable causeOf(Throwable thrown) {
		try {
			return thrown.getCause();
		} catch (Throwable unreadable) {
			if (isFatal(unreadable)) {
				throw (Error) unreadable;
			}
			return null;
		}
	}

	/** Switches the addon off for the session: lets go of what it holds and logs it, once. */
	void fail(String what, Throwable cause) {
		if (this.failed) {
			return;
		}
		this.failed = true;
		this.releaseAll();
		log(this.modId, what, cause);
	}

	/**
	 * One ERROR line naming the addon, with the stack trace under it. The throwable is the addon's,
	 * and its toString() or getMessage() can throw, here or as the logger prints it, so it is logged
	 * as it is only if they can be read, and otherwise as a stand-in with its class and stack. Nothing
	 * thrown while logging gets out of here, apart from {@link #fatal} errors.
	 */
	private static void log(String modId, String what, Throwable cause) {
		String message = "Diorama addon {} failed in {} and is switched off until the game restarts: {}";
		try {
			String described = describe(cause);
			Throwable printed = readable(cause) ? cause : standIn(cause, described);
			Diorama.LOGGER.error(message, modId, what, described, printed);
		} catch (Throwable logging) {
			rethrowFatal(logging);
			try {
				Diorama.LOGGER.error(message, modId, what, cause.getClass().getName());
			} catch (Throwable again) {
				rethrowFatal(again);
			}
		}
	}

	/** toString(), or the class name and why that couldn't be read. */
	private static String describe(Throwable thrown) {
		try {
			return String.valueOf(thrown);
		} catch (Throwable unreadable) {
			rethrowFatal(unreadable);
			return thrown.getClass().getName() + " (its message couldn't be read: " + unreadable.getClass().getName() + ")";
		}
	}

	/** Whether the logger can print it and its causes without one of them throwing. */
	private static boolean readable(Throwable thrown) {
		Map<Throwable, Boolean> seen = new IdentityHashMap<>();
		try {
			for (Throwable at = thrown; at != null && seen.put(at, Boolean.TRUE) == null; at = at.getCause()) {
				at.toString();
				at.getLocalizedMessage();
				at.getStackTrace();
				for (Throwable suppressed : at.getSuppressed()) {
					suppressed.toString();
				}
			}
			return true;
		} catch (Throwable unreadable) {
			rethrowFatal(unreadable);
			return false;
		}
	}

	/** A plain Throwable saying what it was, with its stack where that can be read. */
	private static Throwable standIn(Throwable thrown, String described) {
		Throwable standIn = new Throwable(described);
		try {
			standIn.setStackTrace(thrown.getStackTrace());
		} catch (Throwable unreadable) {
			rethrowFatal(unreadable);
		}
		return standIn;
	}

	boolean failed() {
		return this.failed;
	}

	/** Closes every handle it holds. */
	void releaseAll() {
		for (HudHides.Hold hold : this.holds) {
			hold.release();
		}
		this.holds.clear();
	}

	void forget(HudHides.Hold hold) {
		this.holds.remove(hold);
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
	public String toString() {
		return "Diorama API for " + this.modId;
	}
}
