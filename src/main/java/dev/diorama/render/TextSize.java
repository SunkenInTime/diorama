package dev.diorama.render;

import dev.diorama.Settings;

/**
 * Text drawn bigger or smaller than the game's own. A size setting is in percent of the game's
 * text, which is one GUI pixel to a font pixel; it's drawn at the nearest whole number of screen
 * pixels to a font pixel, so the letters stay sharp.
 */
public final class TextSize {
	/** The biggest size always offered, in screen pixels to a font pixel: what a GUI scale of 4 draws. */
	private static final int ALWAYS_UP_TO = 4;

	private TextSize() {
	}

	/** What text is drawn at for a size setting in percent: a whole number of screen pixels to a font pixel. */
	public static float scale(int percent) {
		int gui = Radar.pixelsPerGuiPixel();
		int pixels = Math.max(first(gui), Math.min(last(gui), Math.round(percent * gui / 100.0F)));
		return pixels / (float) gui;
	}

	/** The sizes in percent the settings offer at this GUI scale: every step {@link #scale} can show, so each one looks different. */
	public static int[] sizes() {
		int gui = Radar.pixelsPerGuiPixel();
		int first = first(gui);
		int[] sizes = new int[last(gui) - first + 1];
		for (int i = 0; i < sizes.length; i++) {
			sizes[i] = Math.round((first + i) * 100.0F / gui);
		}
		return sizes;
	}

	/** {@link #sizes()} with 0 first: 0 means the line is hidden. */
	public static int[] sizesWithOff() {
		int[] base = sizes();
		int[] out = new int[base.length + 1];
		out[0] = 0;
		System.arraycopy(base, 0, out, 1, base.length);
		return out;
	}

	/** {@code value} GUI pixels, moved to the nearest screen pixel. */
	public static float snap(float value) {
		int gui = Radar.pixelsPerGuiPixel();
		return Math.round(value * gui) / (float) gui;
	}

	private static int first(int gui) {
		// Rounded up, so no step is under the smallest size a settings file keeps.
		return Math.max(1, (Settings.MIN_TEXT_SIZE * gui + 99) / 100);
	}

	/**
	 * Twice the game's text, or more at a low GUI scale: someone who keeps the GUI small on a big
	 * screen can still get text as big as a GUI scale of 4 draws it.
	 */
	private static int last(int gui) {
		return Math.max(2 * gui, ALWAYS_UP_TO);
	}
}
