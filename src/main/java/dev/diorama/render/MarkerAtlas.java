package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.api.ResourceId;
import dev.diorama.render.gpu.MapTexture;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Every picture addons' markers are drawn with, in one texture, so a map draws all of them in one
 * go and in their order: a round dot at two sizes (the small one for dots up to {@link #SMALL_DOT}
 * screen pixels across, the big one above, so neither is shrunk or blown up far) and the icons
 * markers show, as the waypoint icons have them ({@link WaypointIcons}): cut to their opaque
 * pixels on an 11-pixel tile. Render thread only.
 *
 * <p>The icons share {@link #SLOTS} tiles. An icon gets a tile the first time a marker in view
 * shows it, and keeps it while markers go on showing it. When every tile is taken, a new icon
 * takes the tile of the icon drawn longest ago, as long as no draw still waiting to reach the
 * screen uses it: the last {@link AddonOverlay#RING} draws may be, since the GUI holds on to them
 * until the end of the frame. With none free, the marker shows as a dot that frame, and its
 * image isn't even read. Icons no resource pack has are remembered, up to {@link #MAX_MISSING}
 * of them, so they aren't looked for every frame, and a draw looks up at most
 * {@link #LOOKUPS_PER_DRAW} icons it doesn't know yet: the rest show as dots and are looked up in
 * the draws after. A resource reload drops it all.
 */
public final class MarkerAtlas {
	static final int SIZE = 256;
	/** The icons' tiles fill the top half, a pixel of space between each. */
	private static final int CELL = WaypointIcons.TILE + 1;
	private static final int COLUMNS = SIZE / CELL;
	private static final int ROWS = SIZE / 2 / CELL;
	/** How many icons the texture holds at once: 210. */
	public static final int SLOTS = COLUMNS * ROWS;
	/**
	 * How many icon IDs with no image are remembered, the ones asked for longest ago forgotten
	 * first: more than a map draws markers a frame, so a still crowd of missing icons stays known.
	 */
	static final int MAX_MISSING = 16384;
	/** How many icons a draw looks up that it doesn't know yet, found or missing. */
	static final int LOOKUPS_PER_DRAW = 64;
	/** Where the dots are, and how big. */
	static final int SMALL_DOT = 32;
	static final int BIG_DOT = 128;
	private static final int SMALL_X = 0;
	private static final int DOTS_Y = SIZE / 2;
	private static final int BIG_X = SIZE - BIG_DOT;
	/** An icon no resource pack has, or one with no tile free this frame: a dot is drawn instead. */
	static final int NONE = -1;

	private static @Nullable MapTexture texture;
	/** The icon on each tile, or null for one never used. */
	private static final ResourceId[] ICONS = new ResourceId[SLOTS];
	/** The draw that last showed each tile's icon. */
	private static final long[] DRAWN = new long[SLOTS];
	/** Each icon on a tile, and which: never more than {@link #SLOTS}. */
	private static final Map<ResourceId, Integer> TILES = new HashMap<>();
	/** Icons no resource pack has, the one asked for longest ago first. */
	private static final Map<ResourceId, Boolean> MISSING = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<ResourceId, Boolean> eldest) {
			return this.size() > MAX_MISSING;
		}
	};
	/** Tiles handed out so far, from the first: past this they're all new. */
	private static int used;
	/** The draw of markers going on now, counted from 1. */
	private static long draw;
	/** Icons this draw has looked up so far. */
	private static int lookups;
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

	/** A map starts a draw of markers: the tiles it asks for from now on are this draw's. */
	static void startDraw() {
		draw++;
		lookups = 0;
	}

	/**
	 * The tile an icon is on, or {@link #NONE}: it loads the icon the first time it's asked for,
	 * and marks its tile as used by this draw. Ask only for markers that are drawn.
	 */
	static int tile(ResourceId icon) {
		Integer known = TILES.get(icon);
		if (known != null) {
			DRAWN[known] = draw;
			return known;
		}
		if (MISSING.get(icon) != null) {
			return NONE;
		}
		int tile = free();
		if (tile == NONE) {
			if (!fullSaid) {
				fullSaid = true;
				Diorama.LOGGER.warn("Addon markers show more than {} different icons at once; {} and others show as dots while there's no room", SLOTS, icon);
			}
			return NONE;
		}
		if (lookups == LOOKUPS_PER_DRAW) {
			return NONE;
		}
		lookups++;
		PinArt.Image image = WaypointIcons.image(icon.toString());
		if (image == null) {
			MISSING.put(icon, Boolean.TRUE);
			return NONE;
		}
		if (tile == used) {
			used++;
		}
		ResourceId was = ICONS[tile];
		if (was != null) {
			TILES.remove(was);
		}
		ICONS[tile] = icon;
		DRAWN[tile] = draw;
		TILES.put(icon, tile);
		texture().write(0, tileX(tile), tileY(tile), WaypointIcons.TILE, image.abgr(WaypointIcons.TILE));
		return tile;
	}

	/**
	 * Whether no resource pack has this icon, looking it up if it isn't known yet and this draw
	 * may look up more, without giving it a tile: for a marker whose icon wouldn't fit where it is,
	 * which is drawn as a dot only if it has no icon.
	 */
	static boolean missing(ResourceId icon) {
		if (TILES.containsKey(icon)) {
			return false;
		}
		if (MISSING.get(icon) != null) {
			return true;
		}
		if (lookups == LOOKUPS_PER_DRAW) {
			return false;
		}
		lookups++;
		if (WaypointIcons.image(icon.toString()) == null) {
			MISSING.put(icon, Boolean.TRUE);
			return true;
		}
		return false;
	}

	/**
	 * A tile a new icon could take, without taking it: one never used, or else the one drawn
	 * longest ago that none of the last {@link AddonOverlay#RING} draws used, as those may not have
	 * reached the screen yet.
	 */
	private static int free() {
		if (used < SLOTS) {
			return used;
		}
		long before = draw - AddonOverlay.RING;
		int best = NONE;
		for (int tile = 0; tile < SLOTS; tile++) {
			if (DRAWN[tile] <= before && (best == NONE || DRAWN[tile] < DRAWN[best])) {
				best = tile;
			}
		}
		return best;
	}

	/** How many icons have a tile, and how many missing ones are remembered: for the tests. */
	public static int[] counts() {
		return new int[] {TILES.size(), MISSING.size()};
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
		MISSING.clear();
		java.util.Arrays.fill(ICONS, null);
		java.util.Arrays.fill(DRAWN, 0);
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
