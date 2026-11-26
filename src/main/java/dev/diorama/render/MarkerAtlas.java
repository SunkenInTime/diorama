package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.api.ResourceId;
import dev.diorama.render.gpu.MapTexture;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Every picture addons' markers are drawn with, in one texture, so a map draws all of them in one
 * go and in their order: a round dot at two sizes (the small one for dots up to {@link #SMALL_DOT}
 * screen pixels across, the big one above, so neither is shrunk or blown up far) and each icon a
 * marker has asked for, as the waypoint icons have them ({@link WaypointIcons}): cut to its opaque
 * pixels on an 11-pixel tile. An icon is added the first time a marker shows it, and stays until a
 * resource reload. Render thread only.
 */
public final class MarkerAtlas {
	static final int SIZE = 256;
	/** The icons' tiles fill the top half, a pixel of space between each. */
	private static final int CELL = WaypointIcons.TILE + 1;
	private static final int COLUMNS = SIZE / CELL;
	private static final int ROWS = SIZE / 2 / CELL;
	/** Where the dots are, and how big. */
	static final int SMALL_DOT = 32;
	static final int BIG_DOT = 128;
	private static final int SMALL_X = 0;
	private static final int DOTS_Y = SIZE / 2;
	private static final int BIG_X = SIZE - BIG_DOT;
	/** An icon no resource pack has, or one past the atlas's room: a dot is drawn instead. */
	static final int NONE = -1;

	private static @Nullable MapTexture texture;
	/** Each icon asked for, by ID, and its tile, or {@link #NONE}. */
	private static final Map<ResourceId, Integer> TILES = new HashMap<>();
	private static int used;
	private static boolean fullSaid;

	private MarkerAtlas() {
	}

	/** The texture, made with its dots the first time it's needed. */
	static MapTexture texture() {
		MapTexture made = texture;
		if (made == null) {
			made = MapTexture.create("diorama addon markers", SIZE, 1);
			made.write(0, 0, 0, SIZE, new int[SIZE * SIZE]);
			made.write(0, SMALL_X, DOTS_Y, SMALL_DOT, dot(SMALL_DOT));
			made.write(0, BIG_X, DOTS_Y, BIG_DOT, dot(BIG_DOT));
			texture = made;
		}
		return made;
	}

	/** The tile an icon is on, or {@link #NONE}: it loads the icon the first time it's asked for. */
	static int tile(ResourceId icon) {
		Integer known = TILES.get(icon);
		if (known != null) {
			return known;
		}
		int tile = NONE;
		PinArt.Image image = WaypointIcons.image(icon.toString());
		if (image != null) {
			if (used < COLUMNS * ROWS) {
				tile = used++;
				texture().write(0, tileX(tile), tileY(tile), WaypointIcons.TILE, image.abgr(WaypointIcons.TILE));
			} else if (!fullSaid) {
				fullSaid = true;
				Diorama.LOGGER.warn("Addon markers use more than {} icons; {} and any after it show as dots until resources are reloaded", COLUMNS * ROWS, icon);
			}
		}
		TILES.put(icon, tile);
		return tile;
	}

	static int tileX(int tile) {
		return tile % COLUMNS * CELL;
	}

	static int tileY(int tile) {
		return tile / COLUMNS * CELL;
	}

	/** The dot's left edge in the atlas, for a dot this many screen pixels across. */
	static int dotX(float screenPixels) {
		return screenPixels <= SMALL_DOT ? SMALL_X : BIG_X;
	}

	static int dotY() {
		return DOTS_Y;
	}

	static int dotSize(float screenPixels) {
		return screenPixels <= SMALL_DOT ? SMALL_DOT : BIG_DOT;
	}

	/** Drops the icons: a resource reload may have changed them. */
	public static void forget() {
		TILES.clear();
		used = 0;
		fullSaid = false;
		if (texture != null) {
			texture.close();
			texture = null;
		}
	}

	/** A white disc with a pixel of soft edge, as {@link MapIcons}'s dot, as ABGR. */
	private static int[] dot(int size) {
		int[] pixels = new int[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				float px = (x + 0.5F) / size * 2 - 1;
				float py = (y + 0.5F) / size * 2 - 1;
				float d = (float) Math.sqrt(px * px + py * py);
				int a = Math.round(Math.max(0, Math.min(1, (0.94F - d) * size / 2)) * 255);
				pixels[y * size + x] = a << 24 | 0xFFFFFF;
			}
		}
		return pixels;
	}
}
