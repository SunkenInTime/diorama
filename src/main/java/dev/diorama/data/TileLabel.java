package dev.diorama.data;

import org.jspecify.annotations.Nullable;

/**
 * How a sky view's tiles were baked, which names the folder they're saved in: saved pixels are
 * merged into fresh bakes, so tiles baked differently can't share one. A new way of baking adds a
 * field here and a word on the end of the name, left out at its default, so every folder already
 * on a disk keeps its name and its meaning.
 *
 * @param underRoof the view from under a roof, a different picture from the open sky's
 * @param pitch the camera angle in whole degrees, which moves where pixels land
 * @param fog fog of war, under a roof only: such tiles may only hold what the player has seen
 */
public record TileLabel(boolean underRoof, long pitch, boolean fog) {
	/** 60 degrees was the only angle before others existed, and its sky view tiles are in "surface". */
	private static final long FIRST_PITCH = 60;
	private static final String SURFACE = "surface";
	private static final String UNDER_ROOF = "under-roof";
	private static final String FOG = "fog";

	public String folder() {
		if (this.underRoof) {
			return UNDER_ROOF + "-" + this.pitch + (this.fog ? "-" + FOG : "");
		}
		return this.pitch == FIRST_PITCH ? SURFACE : SURFACE + "-" + this.pitch;
	}

	/** The label of a folder, or null if it isn't a folder of sky view tiles. */
	public static @Nullable TileLabel parse(String folder) {
		boolean roofed = folder.startsWith(UNDER_ROOF + "-");
		if (!roofed && !folder.startsWith(SURFACE)) {
			return null;
		}
		String rest = folder.substring((roofed ? UNDER_ROOF : SURFACE).length());
		if (rest.isEmpty()) {
			return roofed ? null : new TileLabel(false, FIRST_PITCH, false);
		}
		String[] words = rest.substring(1).split("-");
		if (rest.charAt(0) != '-' || words.length > 2 || words.length == 2 && !(roofed && words[1].equals(FOG))) {
			return null;
		}
		try {
			return new TileLabel(roofed, Long.parseLong(words[0]), words.length == 2);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Whether tiles baked as {@code other} may stand in for these until they're drawn: they show the
	 * same kind of view, and what they know a player may see here.
	 */
	public boolean canShow(TileLabel other) {
		return this.underRoof == other.underRoof && this.fog == other.fog;
	}
}
