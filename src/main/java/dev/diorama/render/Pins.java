package dev.diorama.render;

import dev.diorama.Settings;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import dev.diorama.waypoint.Waypoint;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * Draws waypoint pins from {@link PinArt}: the pin's body is a small texture,
 * its initials are the game's own text drawn on top. A waypoint with an icon ({@link WaypointIcons})
 * shows the icon instead. Bodies are made once per shape, colour and icon.
 */
public final class Pins {
	/** Head sizes: the map and the world use the large pin, the minimap the small one. */
	public static final int LARGE = 15;
	public static final int SMALL = 13;
	/** Directions a caret at the edge of a view can point, around the full turn. */
	private static final int CARET_STEPS = 32;
	/** How far a standing pin's shadow reaches below the spot it marks, before any scale. */
	public static final int SHADOW = 3;
	private static final Map<String, Sprite> SPRITES = new HashMap<>();
	/** Sprites showing an icon start with this, so a resource reload can drop them. */
	private static final String ICON = "icon ";

	private record Sprite(MapTexture texture, int edge, PinArt.Art art) {
	}

	private Pins() {
	}

	/**
	 * What a pin is drawn at for a size setting in percent. Each of its pixels stays a whole number of
	 * screen pixels, so the art, icons included, and the initials stay crisp and on one grid.
	 */
	public static float scale(int percent) {
		return Radar.texel(percent / 100.0F, 1);
	}

	/** The sizes in percent the settings offer at this GUI scale: every step {@link #scale} can show, so each one looks different. */
	public static int[] sizes() {
		int gui = Radar.pixelsPerGuiPixel();
		int first = Math.max(1, Math.round(Settings.MIN_WAYPOINT_SIZE * gui / 100.0F));
		int last = Settings.MAX_SCALE * gui / 100;
		int[] sizes = new int[last - first + 1];
		for (int i = 0; i < sizes.length; i++) {
			sizes[i] = Math.round((first + i) * 100.0F / gui);
		}
		return sizes;
	}

	/** A pin standing with its point on (x, y), the spot it marks. */
	public static void standing(GuiGraphicsExtractor graphics, Waypoint waypoint, float x, float y, int size) {
		standing(graphics, waypoint, x, y, size, 1);
	}

	/** A pin at {@link #scale}, grown or shrunk about its point, which stays on (x, y). */
	public static void standing(GuiGraphicsExtractor graphics, Waypoint waypoint, float x, float y, int size, float scale) {
		PinArt.Image icon = icon(waypoint);
		if (icon != null) {
			// Icon pins are one head size whatever the view: the head is what holds the icon.
			Sprite sprite = sprite(ICON + "pin " + waypoint.icon + " " + rgb(waypoint), () -> PinArt.iconPin(icon, rgb(waypoint)));
			draw(graphics, sprite, waypoint, x, y, scale);
			return;
		}
		int head = PinArt.standingHead(size);
		boolean death = waypoint.kind == Waypoint.Kind.DEATH;
		int stretch = stretch(waypoint, head);
		Sprite sprite = sprite("pin " + head + " " + stretch + " " + rgb(waypoint) + " " + death, () -> PinArt.standing(rgb(waypoint), death, head, stretch));
		draw(graphics, sprite, waypoint, x, y, scale);
	}

	/** A round badge centred on (x, y): the waypoint list's rows. */
	public static void badge(GuiGraphicsExtractor graphics, Waypoint waypoint, float x, float y, int head) {
		boolean death = waypoint.kind == Waypoint.Kind.DEATH;
		PinArt.Image icon = icon(waypoint);
		Sprite sprite = icon != null
			? sprite(ICON + "badge " + waypoint.icon + " " + rgb(waypoint), () -> PinArt.badge(rgb(waypoint), false, PinArt.iconHead(icon), 0, Double.NaN, 0, icon))
			: sprite("badge " + head + " " + stretch(waypoint, head) + " " + rgb(waypoint) + " " + death,
				() -> PinArt.badge(rgb(waypoint), death, head, stretch(waypoint, head), Double.NaN, 0, null));
		draw(graphics, sprite, waypoint, x, y, 1);
	}

	/**
	 * A badge centred on (x, y) with a caret beside it pointing along {@code angle} (radians, screen
	 * space, 0 is right): a waypoint pulled in to the edge of a view, showing which way it lies.
	 */
	public static void edge(GuiGraphicsExtractor graphics, Waypoint waypoint, float x, float y, int head, double angle, float scale) {
		boolean death = waypoint.kind == Waypoint.Kind.DEATH;
		int step = Math.floorMod(Math.round((float) (angle / (Math.PI * 2) * CARET_STEPS)), CARET_STEPS);
		double snapped = step * Math.PI * 2 / CARET_STEPS;
		int caret = head >= LARGE ? 6 : 5;
		PinArt.Image icon = icon(waypoint);
		Sprite sprite = icon != null
			? sprite(ICON + "edge " + waypoint.icon + " " + rgb(waypoint) + " " + step, () -> PinArt.badge(rgb(waypoint), false, PinArt.iconHead(icon), 0, snapped, 6, icon))
			: sprite("edge " + head + " " + stretch(waypoint, head) + " " + rgb(waypoint) + " " + death + " " + step,
				() -> PinArt.badge(rgb(waypoint), death, head, stretch(waypoint, head), snapped, caret, null));
		draw(graphics, sprite, waypoint, x, y, scale);
	}

	/** How wide this waypoint's badge is drawn with a head of {@code head}, without a caret. */
	public static int badgeWidth(Waypoint waypoint, int head) {
		PinArt.Image icon = icon(waypoint);
		return icon != null ? PinArt.iconHead(icon) : head + stretch(waypoint, head);
	}

	/** How far above the marked spot this waypoint's standing pin has its head centred. */
	public static float headAbove(Waypoint waypoint, int size) {
		PinArt.Image icon = icon(waypoint);
		return icon != null ? PinArt.iconHeadAbove(icon) : headAbove(size);
	}

	/**
	 * How near the centre of this waypoint's head, or badge, the cursor has to be to point at it; a
	 * head widened for its initials ({@link #stretch}) reaches as far from its middle line.
	 */
	public static float reach(Waypoint waypoint, int size) {
		PinArt.Image icon = icon(waypoint);
		return (icon != null ? PinArt.iconHead(icon) : size) / 2.0F + 2;
	}

	/** How far above the marked spot a standing pin's head is centred, in GUI pixels. */
	public static float headAbove(int size) {
		int head = PinArt.standingHead(size);
		return (head == SMALL ? 5 : 6) + head / 2.0F;
	}

	/** Whether this waypoint's standing pin marking (x, y) shows whole inside the box x0..x1, y0..y1: its head above, its shadow below. */
	public static boolean fitsStanding(Waypoint waypoint, int size, float scale, float x, float y, float x0, float y0, float x1, float y1) {
		float[] box = standingBox(waypoint, size, scale, x, y);
		return box[0] >= x0 && box[2] <= x1 && box[1] >= y0 && box[3] <= y1;
	}

	/** Whether this waypoint's standing pin with its point at (x, y) is wholly inside a circle. */
	public static boolean fitsStanding(Waypoint waypoint, int size, float scale, float x, float y, Circle circle) {
		float[] box = standingBox(waypoint, size, scale, x, y);
		return circle.contains(box[0], box[1]) && circle.contains(box[2], box[1]) && circle.contains(box[0], box[3]) && circle.contains(box[2], box[3]);
	}

	/** What a standing pin with its point at (x, y) covers: x0, y0, x1, y1. */
	private static float[] standingBox(Waypoint waypoint, int size, float scale, float x, float y) {
		PinArt.Image icon = icon(waypoint);
		int head = icon != null ? PinArt.iconHead(icon) : PinArt.standingHead(size);
		float above = head + (head == SMALL ? 5 : 6);
		float side = (head + 4 + (icon != null ? 0 : stretch(waypoint, head))) / 2.0F;
		return new float[] {x - side * scale, y - above * scale, x + side * scale, y + SHADOW * scale};
	}

	/** How far this waypoint's badge with a caret reaches from its centre, so a view can keep it inside. */
	public static float edgeReach(Waypoint waypoint, int head) {
		PinArt.Image icon = icon(waypoint);
		return (icon != null ? PinArt.iconHead(icon) : head + stretch(waypoint, head)) / 2.0F + 6;
	}

	/** How much wider than round this waypoint's head is drawn to hold its initials. */
	public static int stretch(Waypoint waypoint, int head) {
		if (waypoint.kind == Waypoint.Kind.DEATH || icon(waypoint) != null) {
			return 0;
		}
		String text = waypoint.label();
		return text.isEmpty() ? 0 : PinArt.stretchFor(head, ink(Minecraft.getInstance().font, text));
	}

	/** Whether this waypoint's pin shows an icon rather than initials. */
	public static boolean showsIcon(Waypoint waypoint) {
		return icon(waypoint) != null;
	}

	/** The icon a waypoint's pin shows, or null for initials (and for deaths, which show a headstone). */
	private static PinArt.@Nullable Image icon(Waypoint waypoint) {
		if (waypoint.icon == null || waypoint.icon.isEmpty() || waypoint.kind == Waypoint.Kind.DEATH) {
			return null;
		}
		return WaypointIcons.image(waypoint.icon);
	}

	/** Drops the pins drawn with icons, for a resource reload that may have changed them. Render thread. */
	static void forgetIcons() {
		SPRITES.entrySet().removeIf(entry -> {
			if (!entry.getKey().startsWith(ICON)) {
				return false;
			}
			entry.getValue().texture().close();
			return true;
		});
	}

	private static int rgb(Waypoint waypoint) {
		return waypoint.color & 0xFFFFFF;
	}

	private static Sprite sprite(String key, java.util.function.Supplier<PinArt.Art> art) {
		Sprite sprite = SPRITES.get(key);
		if (sprite == null) {
			PinArt.Art made = art.get();
			int edge = Math.max(made.image().width, made.image().height);
			MapTexture texture = MapTexture.create("diorama " + key, edge, 1);
			texture.write(0, 0, 0, edge, made.image().abgr(edge));
			sprite = new Sprite(texture, edge, made);
			SPRITES.put(key, sprite);
		}
		return sprite;
	}

	private static void draw(GuiGraphicsExtractor graphics, Sprite sprite, Waypoint waypoint, float x, float y, float scale) {
		PinArt.Art art = sprite.art();
		// Keep the body and initials on one local pixel grid, moving continuously with the map.
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale);
		graphics.pose().translate(-art.anchorX(), -art.anchorY());
		int w = art.image().width;
		int h = art.image().height;
		float edge = sprite.edge();
		// A colour saved with no alpha at all is meant opaque; otherwise the pin keeps the colour's alpha.
		int alpha = waypoint.color >>> 24 == 0 ? 0xFF : waypoint.color >>> 24;
		MapCanvas.of(graphics).rect(sprite.texture(), Sampling.CLAMP_CRISP, 0, 0, w, h, 0, 0, w / edge, h / edge, alpha << 24 | 0xFFFFFF);
		if (waypoint.kind != Waypoint.Kind.DEATH && art.labelX1() > art.labelX0()) {
			label(graphics, waypoint, art.labelX0(), art.labelY0(), art.labelX1(), art.labelY1());
		}
		graphics.pose().popMatrix();
	}

	/** The initials at full size, centred on their ink in the head, which is drawn wide enough for them. */
	private static void label(GuiGraphicsExtractor graphics, Waypoint waypoint, int x0, int y0, int x1, int y1) {
		Font font = Minecraft.getInstance().font;
		String text = waypoint.label();
		if (text.isEmpty()) {
			return;
		}
		int rgb = rgb(waypoint);
		boolean light = PinArt.light(rgb);
		int x = PinArt.centred(x0, x1, ink(font, text));
		// Capitals and digits are 7 pixels tall from the top of the line.
		int y = PinArt.centred(y0, y1, 7);
		graphics.text(font, text, x, y, light ? 0xFF000000 | PinArt.INK : 0xFFFFFFFF, !light);
	}

	/** Width of a string's ink: its advance less the one-pixel gap after the last glyph. */
	private static int ink(Font font, String text) {
		return font.width(text) - 1;
	}
}
