package dev.diorama.api;

import java.util.Objects;

/** The checks {@link DimensionId}, {@link ResourceId}, {@link NewWaypoint} and {@link WaypointUpdate} make on what they're given. */
final class Checks {
	static final int MAX_NAME = NewWaypoint.MAX_NAME_LENGTH;
	static final int MAX_INITIALS = NewWaypoint.MAX_INITIALS;
	/** A waypoint's colour when none is given: the first of Diorama's palette, as 0xRRGGBB. */
	static final int DEFAULT_RGB = 0xE5484D;

	private Checks() {
	}

	static String name(String name) {
		Objects.requireNonNull(name, "name");
		String trimmed = name.strip();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("A waypoint's name can't be blank");
		}
		if (trimmed.length() > MAX_NAME) {
			throw new IllegalArgumentException("A waypoint's name can be " + MAX_NAME + " UTF-16 units at most, as in Diorama's editor; this is " + trimmed.length() + ": \"" + trimmed + "\"");
		}
		return trimmed;
	}

	static String initials(String initials) {
		Objects.requireNonNull(initials, "initials");
		String trimmed = initials.strip();
		if (trimmed.codePointCount(0, trimmed.length()) > MAX_INITIALS) {
			throw new IllegalArgumentException("A waypoint's initials can be " + MAX_INITIALS + " letters at most: \"" + trimmed + "\"");
		}
		return trimmed;
	}

	/** 0xRRGGBB: anything in the top byte is a mistake, such as passing ARGB. */
	static int rgb(int rgb) {
		if (rgb >>> 24 != 0) {
			throw new IllegalArgumentException("A waypoint's colour is 0xRRGGBB, with nothing above it: " + String.format("0x%08X", rgb) + " looks like ARGB; drop the alpha");
		}
		return rgb;
	}

	static String folder(String folder) {
		Objects.requireNonNull(folder, "folder");
		String trimmed = folder.strip();
		if (trimmed.length() > MAX_NAME) {
			throw new IllegalArgumentException("A folder's name can be " + MAX_NAME + " UTF-16 units at most, as in Diorama's folder screen; this is " + trimmed.length() + ": \"" + trimmed + "\"");
		}
		return trimmed;
	}

	/** Characters the game allows in a resource ID's namespace, or with {@code slash} its path. */
	static boolean allowed(String part, boolean slash) {
		if (part.isEmpty()) {
			return false;
		}
		for (int i = 0; i < part.length(); i++) {
			char c = part.charAt(i);
			boolean ok = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || slash && c == '/';
			if (!ok) {
				return false;
			}
		}
		return true;
	}
}
