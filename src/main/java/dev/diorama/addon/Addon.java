package dev.diorama.addon;

import dev.diorama.Diorama;
import dev.diorama.api.DioramaApi;
import dev.diorama.api.Handle;
import dev.diorama.api.Hud;
import java.util.ArrayList;
import java.util.List;
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
	 * Calls into the addon. What it throws, an exception or a LinkageError (a class or method its
	 * jar expected and didn't find), switches it off instead of reaching Diorama or other addons.
	 * Null if it threw, or was already switched off.
	 */
	<T> @Nullable T call(String what, Callable<T> call) {
		if (this.failed) {
			return null;
		}
		try {
			return call.call();
		} catch (Exception | LinkageError e) {
			this.fail(what, e);
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
		Diorama.LOGGER.error("Diorama addon {} failed in {} and is switched off until the game restarts: {}", this.modId, what, cause.toString(), cause);
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
