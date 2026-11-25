package dev.diorama.api;

import java.util.Objects;

/** The checks {@link DimensionId}, {@link NewWaypoint} and {@link WaypointUpdate} make on what they're given. */
final class Checks {
	/** The longest name or folder name, as Diorama's editor allows. */
	static final int MAX_NAME = 32;
	/** The most letters a pin's initials can have. */
	static final int MAX_INITIALS = 4;
	/** A waypoint's colour when none is given: the first of Diorama's palette. */
	static final int DEFAULT_COLOR = 0xFFE5484D;

	private Checks() {
	}

	static String name(String name) {
		Objects.requireNonNull(name, "name");
		String trimmed = name.strip();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("A waypoint's name can't be blank");
		}
		if (trimmed.length() > MAX_NAME) {
			throw new IllegalArgumentException("A waypoint's name can be " + MAX_NAME + " characters at most, as in Diorama's editor: \"" + trimmed + "\"");
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

	static String icon(String icon) {
		Objects.requireNonNull(icon, "icon");
		if (icon.isEmpty()) {
			return icon;
		}
		int colon = icon.indexOf(':');
		if (colon <= 0 || colon == icon.length() - 1 || !allowed(icon.substring(0, colon), false) || !allowed(icon.substring(colon + 1), true)) {
			throw new IllegalArgumentException("Not an icon ID: \"" + icon + "\" (namespace:path, such as diorama:home, or empty for none)");
		}
		return icon;
	}

	/** ARGB; an alpha of 0, as a plain 0xRRGGBB has, is taken as opaque. */
	static int color(int color) {
		return color >>> 24 == 0 ? color | 0xFF000000 : color;
	}

	static String folder(String folder) {
		Objects.requireNonNull(folder, "folder");
		String trimmed = folder.strip();
		if (trimmed.length() > MAX_NAME) {
			throw new IllegalArgumentException("A folder's name can be " + MAX_NAME + " characters at most, as in Diorama's folder screen: \"" + trimmed + "\"");
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
