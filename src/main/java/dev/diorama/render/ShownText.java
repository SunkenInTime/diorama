package dev.diorama.render;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Text the minimap draws every frame: its compass letters, the lines under it and the waypoints'
 * initials. From 1.21.6 the game puts a string in display order each time it's drawn (bidirectional
 * text, through ICU), about 1.7 KB of garbage a call, though these are the same few strings frame
 * after frame. This keeps each string in display order from one frame to the next and draws that,
 * which is what drawing the string does after the reordering, so it looks the same. The order
 * depends on the language, so a new language starts afresh. Render thread.
 */
public final class ShownText {
	/** Strings kept: every letter and line on screen, and the coordinates passed through on the way, many times over. */
	private static final int KEPT = 256;
	private static final Map<String, FormattedCharSequence> ORDERED = new LinkedHashMap<>(KEPT, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, FormattedCharSequence> eldest) {
			return this.size() > KEPT;
		}
	};
	private static @Nullable Language language;

	private ShownText() {
	}

	/** Draws {@code text} as {@code graphics.text} does. */
	public static void draw(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color, boolean shadow) {
		//? if >=1.21.6 {
		graphics.text(font, ordered(text), x, y, color, shadow);
		//?} else {
		/*// Before 1.21.6 the game draws a string another way, which costs no more than drawing it in order.
		graphics.text(font, text, x, y, color, shadow);
		*///?}
	}

	/** {@code text} in display order, as {@code graphics.text} would put it. */
	static FormattedCharSequence ordered(String text) {
		Language current = Language.getInstance();
		if (current != language) {
			ORDERED.clear();
			language = current;
		}
		FormattedCharSequence ordered = ORDERED.get(text);
		if (ordered == null) {
			ordered = current.getVisualOrder(FormattedText.of(text));
			ORDERED.put(text, ordered);
		}
		return ordered;
	}
}
