package dev.diorama.api;

import java.util.Objects;

/**
 * The checks {@link DimensionId}, {@link ResourceId}, {@link NewWaypoint}, {@link WaypointUpdate},
 * {@link Marker} and {@link MapLine} make on what they're given.
 */
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
		return rgb("A waypoint's colour", rgb);
	}

	/** {@link #rgb(int)}, for something other than a waypoint: {@code what} starts the message, such as "A line's colour". */
	static int rgb(String what, int rgb) {
		if (rgb >>> 24 != 0) {
			throw new IllegalArgumentException(what + " is 0xRRGGBB, with nothing above it: " + String.format("0x%08X", rgb) + " looks like ARGB; drop the alpha");
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

	/**
	 * An east-west or north-south coordinate of a marker or a line's point: finite, and no further
	 * out than {@link Marker#MAX_COORDINATE}, where the game stops a player.
	 */
	static double horizontal(String axis, double value) {
		if (!(Math.abs(value) <= Marker.MAX_COORDINATE)) {
			throw new IllegalArgumentException("A place on the map has x and z from -" + Marker.MAX_COORDINATE + " to " + Marker.MAX_COORDINATE + ", where the game stops a player; "
				+ axis + " is " + value);
		}
		return value;
	}

	/** A height of a marker or a line's point: finite, and no further than {@link Marker#MAX_HEIGHT} up or down. */
	static double height(double value) {
		if (!(Math.abs(value) <= Marker.MAX_HEIGHT)) {
			throw new IllegalArgumentException("A place on the map has y from -" + Marker.MAX_HEIGHT + " to " + Marker.MAX_HEIGHT + ", where the game stops a player; y is " + value);
		}
		return value;
	}

	/**
	 * A marker's label as plain text: the game's formatting codes ({@code §} and the character
	 * after it) taken out, then spaces at the ends.
	 */
	static String label(String label) {
		Objects.requireNonNull(label, "label");
		String plain = label;
		int code = label.indexOf('\u00A7');
		if (code >= 0) {
			StringBuilder kept = new StringBuilder(label.length());
			for (int i = 0; i < label.length(); i++) {
				char c = label.charAt(i);
				if (c == '\u00A7') {
					// The code's character goes with it.
					i++;
				} else {
					kept.append(c);
				}
			}
			plain = kept.toString();
		}
		String trimmed = plain.strip();
		if (trimmed.length() > Marker.MAX_LABEL_LENGTH) {
			throw new IllegalArgumentException("A marker's label can be " + Marker.MAX_LABEL_LENGTH + " UTF-16 units at most; this is " + trimmed.length() + ": \"" + trimmed + "\"");
		}
		return trimmed;
	}

	/**
	 * An icon for a marker: a path of parts split by {@code /}, none of them empty, {@code .} or
	 * {@code ..}, since it names a file under {@code textures/waypoint_icons/}.
	 */
	static ResourceId icon(ResourceId icon) {
		Objects.requireNonNull(icon, "icon");
		String path = icon.path();
		int start = 0;
		while (start <= path.length()) {
			int end = path.indexOf('/', start);
			if (end < 0) {
				end = path.length();
			}
			String part = path.substring(start, end);
			if (part.isEmpty() || part.equals(".") || part.equals("..")) {
				throw new IllegalArgumentException("A marker's icon names a file under textures/waypoint_icons/, so its path can't have an empty part, \".\" or \"..\": " + icon);
			}
			start = end + 1;
		}
		return icon;
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
