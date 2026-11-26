package dev.diorama.testaddon.sync;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A pretend second map mod's waypoint store, kept in memory, which {@link PollingSync} keeps in
 * step with Diorama's. Its waypoints have IDs of its own; {@link #version} goes up with every
 * change, as a real mod's change counter or a file's timestamp would.
 */
public final class OtherMod {
	/** One of its waypoints: what the sync carries across. */
	public record Pin(String name, int x, int y, int z, String dimension, int rgb, boolean visible, String folder) {
		public Pin renamed(String name) {
			return new Pin(name, this.x, this.y, this.z, this.dimension, this.rgb, this.visible, this.folder);
		}

		public Pin shown(boolean visible) {
			return new Pin(this.name, this.x, this.y, this.z, this.dimension, this.rgb, visible, this.folder);
		}

		public Pin inFolder(String folder) {
			return new Pin(this.name, this.x, this.y, this.z, this.dimension, this.rgb, this.visible, folder);
		}
	}

	private final Map<String, Pin> pins = new LinkedHashMap<>();
	private final Map<String, Pin> view = Collections.unmodifiableMap(this.pins);
	private long version;
	private int made;

	/** Adds a waypoint and returns its ID. */
	public String add(Pin pin) {
		String id = "other-" + ++this.made;
		this.pins.put(id, pin);
		this.version++;
		return id;
	}

	/** Changes a waypoint; setting what it has already is no change. */
	public void put(String id, Pin pin) {
		if (!pin.equals(this.pins.put(id, pin))) {
			this.version++;
		}
	}

	public void remove(String id) {
		if (this.pins.remove(id) != null) {
			this.version++;
		}
	}

	/** Its waypoints by ID, in the order they were made; can't be changed. */
	public Map<String, Pin> pins() {
		return this.view;
	}

	/** The ID of the first waypoint with this name, or null. */
	public String named(String name) {
		for (Map.Entry<String, Pin> pin : this.pins.entrySet()) {
			if (pin.getValue().name().equals(name)) {
				return pin.getKey();
			}
		}
		return null;
	}

	/** Goes up with every change. */
	public long version() {
		return this.version;
	}
}
