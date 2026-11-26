package dev.diorama;

import dev.diorama.waypoint.WaypointShare;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads a place out of what a player typed or pasted into the map's Go to coordinates box. Xaero's
 * World Map takes "X Z" there, split on spaces, commas, semicolons and colons. Coordinates reach a
 * player in a few more shapes, and these are read:
 *
 * <ul>
 * <li>{@code 120 -340}, {@code 120, 64, -340}, {@code [120, 64, -340]}: two numbers are X and Z,
 * three are X, Y and Z, as Copy coordinates writes them;
 * <li>{@code x: 120, z: -340}, {@code X=120 Y=64 Z=-340}, and JourneyMap's
 * {@code [name:"Base", x:120, y:64, z:-340, dim:minecraft:overworld]};
 * <li>{@code /tp 120 64 -340}, {@code /tp @s 120 64 -340}, and the line F3+C copies,
 * {@code /execute in minecraft:overworld run tp @s 120.50 64.00 -340.50 90.00 0.00};
 * <li>a waypoint shared in chat, in Xaero's format ({@link WaypointShare}), with whatever chat put
 * around it.
 * </ul>
 *
 * <p>The whole text has to be one of these and nothing more. Read wrongly, text sends the player's
 * eyes to a place nobody meant, and no rule picks the right numbers out of a sentence, so a
 * sentence is refused and the box says what it takes.
 */
public final class MapCoordinates {
	/** Further out than the world border, or outside any world's height, isn't a real place. */
	private static final int MAX_HORIZONTAL = 30_000_000;
	private static final int MAX_VERTICAL = 4096;
	/** Longer than anything above. */
	private static final int MAX_TEXT = 512;

	private static final String NUMBER = "(-?\\d{1,9}(?:\\.\\d{1,9})?)";
	private static final String DIMENSION = "((?:[a-z0-9_.-]+:)?[a-z0-9_./-]+)";
	private static final String BETWEEN = "(?:\\s*[,;]\\s*|\\s+)";
	/** Two or three numbers, in brackets or not. */
	private static final Pattern PLAIN = Pattern.compile("[\\[(]?\\s*" + NUMBER + BETWEEN + NUMBER + "(?:" + BETWEEN + NUMBER + ")?\\s*[\\])]?");
	/**
	 * A teleport of the one who runs it to three numbers, with the facing F3+C adds, in the dimension
	 * {@code execute in} names. A command with more to it than that (another target, {@code ~},
	 * {@code at}) goes somewhere this can't know.
	 */
	private static final Pattern TELEPORT = Pattern.compile("/?(?:execute\\s+in\\s+" + DIMENSION + "\\s+run\\s+)?(?:minecraft:)?(?:tp|teleport)(?:\\s+@s)?\\s+"
		+ NUMBER + "\\s+" + NUMBER + "\\s+" + NUMBER + "(?:\\s+" + NUMBER + "\\s+" + NUMBER + ")?");
	/** One labelled value: an axis, a dimension, or a name in quotes, which is skipped. */
	private static final Pattern LABELLED = Pattern.compile("(?:([xyz])\\s*[:=]?\\s*" + NUMBER + "|dim(?:ension)?\\s*[:=]\\s*" + DIMENSION + "|name\\s*[:=]\\s*\"(?:[^\"\\\\]|\\\\.)*\")(?![\\w.:])");
	private static final Pattern GAP = Pattern.compile(BETWEEN);

	/** A place read from text. {@code y} is null when the text had none, and {@code dimension} when it named none. */
	public record Place(int x, @Nullable Integer y, int z, @Nullable String dimension) {
	}

	private MapCoordinates() {
	}

	/** The place {@code text} names, or null if it isn't one of the shapes read. */
	public static @Nullable Place parse(@Nullable String text) {
		if (text == null || text.isBlank() || text.length() > MAX_TEXT) {
			return null;
		}
		String lower = text.trim().toLowerCase(Locale.ROOT);
		Matcher plain = PLAIN.matcher(lower);
		if (plain.matches()) {
			return plain.group(3) == null ? place(plain.group(1), null, plain.group(2), null) : place(plain.group(1), plain.group(2), plain.group(3), null);
		}
		Matcher teleport = TELEPORT.matcher(lower);
		if (teleport.matches()) {
			return place(teleport.group(2), teleport.group(3), teleport.group(4), teleport.group(1));
		}
		Place labelled = labelled(lower);
		if (labelled != null) {
			return labelled;
		}
		// Last, as the one shape looked for inside the text: a name in one of the others can hold anything.
		Optional<WaypointShare.Shared> shared = WaypointShare.parse(text);
		return shared.map(s -> new Place(s.x(), s.y(), s.z(), s.dimension())).orElse(null);
	}

	/** Labelled values and nothing between them but commas, semicolons and spaces, each axis once, X and Z among them. */
	private static @Nullable Place labelled(String lower) {
		int start = lower.startsWith("[") || lower.startsWith("(") ? 1 : 0;
		int end = lower.endsWith("]") || lower.endsWith(")") ? lower.length() - 1 : lower.length();
		String[] axes = new String[3];
		String dimension = null;
		Matcher value = LABELLED.matcher(lower);
		Matcher gap = GAP.matcher(lower);
		int at = start;
		while (at < end && Character.isWhitespace(lower.charAt(at))) {
			at++;
		}
		while (at < end) {
			value.region(at, end);
			if (!value.lookingAt()) {
				return null;
			}
			if (value.group(1) != null) {
				int axis = value.group(1).charAt(0) - 'x';
				if (axes[axis] != null) {
					return null;
				}
				axes[axis] = value.group(2);
			} else if (value.group(3) != null) {
				if (dimension != null) {
					return null;
				}
				dimension = value.group(3);
			}
			at = value.end();
			if (at < end) {
				gap.region(at, end);
				if (!gap.lookingAt()) {
					return null;
				}
				at = gap.end();
			}
		}
		return axes[0] == null || axes[2] == null ? null : place(axes[0], axes[1], axes[2], dimension);
	}

	/**
	 * The block each number is in: 120.5 is in block 120, and -340.5 in block -341. A dimension
	 * without a namespace is the game's own, as the game reads it.
	 */
	private static @Nullable Place place(String x, @Nullable String y, String z, @Nullable String dimension) {
		double px = Double.parseDouble(x);
		double pz = Double.parseDouble(z);
		Double py = y == null ? null : Double.valueOf(y);
		if (Math.abs(px) > MAX_HORIZONTAL || Math.abs(pz) > MAX_HORIZONTAL || (py != null && Math.abs(py) > MAX_VERTICAL)) {
			return null;
		}
		String named = dimension == null || dimension.indexOf(':') >= 0 ? dimension : "minecraft:" + dimension;
		return new Place((int) Math.floor(px), py == null ? null : (int) Math.floor(py), (int) Math.floor(pz), named);
	}
}
