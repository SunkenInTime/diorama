package dev.diorama;

import dev.diorama.waypoint.WaypointShare;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads a place out of text a player typed or pasted into the map's Go to coordinates box. Xaero's
 * World Map takes "X Z" there, split on spaces, commas, semicolons and colons. Coordinates reach a
 * player in more shapes than that, and each of these is read:
 *
 * <ul>
 * <li>{@code 120 -340}, {@code 120, 64, -340}, {@code [120, 64, -340]}: two numbers are X and Z,
 * three are X, Y and Z, as Copy coordinates writes them;
 * <li>{@code x: 120, z: -340}, {@code X=120 Y=64 Z=-340}, and JourneyMap's
 * {@code [x:120, y:64, z:-340, dim:minecraft:overworld]};
 * <li>{@code /tp 120 64 -340}, and the line F3+C copies,
 * {@code /execute in minecraft:overworld run tp @s 120.50 64.00 -340.50 90.00 0.00};
 * <li>a waypoint shared in chat, in Xaero's format ({@link WaypointShare}).
 * </ul>
 */
public final class MapCoordinates {
	/** Further out than the world border, or outside any world's height, isn't a real place. */
	private static final int MAX_HORIZONTAL = 30_000_000;
	private static final int MAX_VERTICAL = 4096;
	/** Longer than anything above; a pasted page isn't searched for numbers. */
	private static final int MAX_TEXT = 512;

	private static final String NUMBER = "(-?\\d{1,9}(?:\\.\\d{1,9})?)";
	/** {@code tp} or {@code teleport}, a target that isn't a number if there is one, then three numbers. */
	private static final Pattern TELEPORT = Pattern.compile("(?:^|[\\s/])(?:tp|teleport)\\s+(?:[^\\s\\d~-]\\S*\\s+)?" + NUMBER + "\\s+" + NUMBER + "\\s+" + NUMBER + "(?![\\w.])");
	private static final Pattern IN_DIMENSION = Pattern.compile("execute\\s+in\\s+([a-z0-9_.-]+:[a-z0-9_./-]+)");
	private static final Pattern NAMED_DIMENSION = Pattern.compile("dim(?:ension)?\\s*[:=]\\s*([a-z0-9_.-]+:[a-z0-9_./-]+)");
	private static final Pattern X = labelled('x');
	private static final Pattern Y = labelled('y');
	private static final Pattern Z = labelled('z');
	/** A number standing on its own: not part of a word, an ID or another number. */
	private static final Pattern PLAIN = Pattern.compile("(?<![\\w.:-])" + NUMBER + "(?![\\w.:])");

	/** A place read from text. {@code y} is null when the text had none, and {@code dimension} when it named none. */
	public record Place(int x, @Nullable Integer y, int z, @Nullable String dimension) {
	}

	private MapCoordinates() {
	}

	private static Pattern labelled(char axis) {
		return Pattern.compile("(?<![a-z0-9])" + axis + "\\s*[:=]?\\s*" + NUMBER + "(?![\\w.])");
	}

	/** The place {@code text} names, or null if it names none. */
	public static @Nullable Place parse(@Nullable String text) {
		if (text == null || text.isBlank() || text.length() > MAX_TEXT) {
			return null;
		}
		Optional<WaypointShare.Shared> shared = WaypointShare.parse(text);
		if (shared.isPresent()) {
			return new Place(shared.get().x(), shared.get().y(), shared.get().z(), shared.get().dimension());
		}
		String lower = text.toLowerCase(Locale.ROOT);
		Matcher teleport = TELEPORT.matcher(lower);
		if (teleport.find()) {
			Matcher in = IN_DIMENSION.matcher(lower);
			return place(teleport.group(1), teleport.group(2), teleport.group(3), in.find() ? in.group(1) : null);
		}
		Matcher x = X.matcher(lower);
		Matcher z = Z.matcher(lower);
		if (x.find() && z.find()) {
			Matcher y = Y.matcher(lower);
			Matcher dimension = NAMED_DIMENSION.matcher(lower);
			return place(x.group(1), y.find() ? y.group(1) : null, z.group(1), dimension.find() ? dimension.group(1) : null);
		}
		List<String> numbers = new ArrayList<>();
		Matcher plain = PLAIN.matcher(lower);
		while (plain.find() && numbers.size() < 4) {
			numbers.add(plain.group(1));
		}
		return switch (numbers.size()) {
			case 2 -> place(numbers.get(0), null, numbers.get(1), null);
			case 3 -> place(numbers.get(0), numbers.get(1), numbers.get(2), null);
			default -> null;
		};
	}

	/** The block each number is in: 120.5 is in block 120, and -340.5 in block -341. */
	private static @Nullable Place place(String x, @Nullable String y, String z, @Nullable String dimension) {
		double px = Double.parseDouble(x);
		double pz = Double.parseDouble(z);
		Double py = y == null ? null : Double.valueOf(y);
		if (Math.abs(px) > MAX_HORIZONTAL || Math.abs(pz) > MAX_HORIZONTAL || (py != null && Math.abs(py) > MAX_VERTICAL)) {
			return null;
		}
		return new Place((int) Math.floor(px), py == null ? null : (int) Math.floor(py), (int) Math.floor(pz), dimension);
	}
}
