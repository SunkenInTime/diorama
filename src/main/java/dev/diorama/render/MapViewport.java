package dev.diorama.render;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * Draws a rectangle of the map: baked tiles, then fog over everything the map has no data for.
 * Each on-screen map (minimap, fullscreen) owns one, because each keeps its own fog mask.
 */
public final class MapViewport {
	private static final int MASK = 256;
	private static final double MASK_MARGIN = 24;
	/** Zooming out past this (GUI pixels per block) switches to overview tiles. */
	public static final double DETAILED_ZOOM = 0.5;

	private @Nullable MapTexture maskTexture;
	private final int[] maskPixels = new int[MASK * MASK];
	/** What the mask texture was last built from; it only needs building again when this changes. */
	private @Nullable MaskInputs maskInputs;
	/** The map the mask was built from. Weak, so a minimap left over after leaving a world doesn't keep its map. */
	private WeakReference<MapLevel> maskLevel = new WeakReference<>(null);

	private record MaskInputs(long coverageVersion, int layer, int lod, double originU, double originV, double texel, boolean rounded, int scaleSteps, boolean fast, int nearU, int nearV) {
	}

	/**
	 * Where and how big a map is drawn, and what part of the map it shows. A {@code round} view
	 * shows only the circle filling it (the round minimap); a {@code follows} view is on the player,
	 * so what's around its middle has to stay up to date (the minimap again).
	 */
	public record View(int x, int y, int width, int height, double centerU, double centerV, double zoom, int layer, boolean round, boolean follows) {
		public View(int x, int y, int width, int height, double centerU, double centerV, double zoom, int layer) {
			this(x, y, width, height, centerU, centerV, zoom, layer, false, false);
		}

		public double minU() {
			return this.centerU - this.width / 2.0 / this.zoom;
		}

		public double minV() {
			return this.centerV - this.height / 2.0 / this.zoom;
		}

		public float screenX(double u) {
			return (float) (this.x + this.width / 2.0 + (u - this.centerU) * this.zoom);
		}

		public float screenY(double v) {
			return (float) (this.y + this.height / 2.0 + (v - this.centerV) * this.zoom);
		}
	}

	/** Screen pixels per tile pixel from which the tiles are drawn crisp; see {@link #sampling}. */
	private static final double CRISP_FROM = 1.5;

	/** Below this zoom (GUI pixels per block) overlay lines and dots would blur into noise. */
	private static final double OVERLAY_ZOOM = 1.0;

	/**
	 * How many tiles each way from the middle a {@link View#follows following} view draws detailed
	 * tiles over its overviews. Overviews are made from saved tiles, seconds behind, so the ground
	 * under a moving player would still be fog. This is as far as the game usually has chunks.
	 */
	private static final int NEAR_TILES = 2;

	/** The most the fog shader's opacity code can say: it has five spare bits. */
	private static final int OPACITY_CODES = 31;

	/** How opaque the map draws, 0 to 1. */
	private float opacity = 1;

	/**
	 * Sets how opaque this map is drawn: the tiles and the fog, and not the backdrop behind them, so
	 * the game shows through. The fullscreen map leaves it at 1; the minimap takes it from the settings.
	 */
	public void opacity(float opacity) {
		this.opacity = Math.max(0, Math.min(1, opacity));
	}

	public void draw(GuiGraphicsExtractor graphics, MapLevel level, View view) {
		this.draw(graphics, level, view, 0, false, null);
	}

	/**
	 * Same, with these {@link dev.diorama.bake.Overlays} bits drawn over the tiles, land claims if
	 * {@code claims}, and {@code lit} drawn over the tiles but under those and the fog.
	 */
	public void draw(GuiGraphicsExtractor graphics, MapLevel level, View view, int overlays, boolean claims, @Nullable Runnable lit) {
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		boolean cave = view.layer() != TileKey.SURFACE;
		// Behind the tiles, a colour from the fog that covers where there's no data, so the ragged
		// edge of known ground blends into it: clouds, smoke under a roof, or the dark of caves.
		int background = cave ? 0xFF0C0E12 : level.roofless() ? 0xFF29211F : 0xFF8C99AB;
		// A see-through map has no backdrop: drawn under tiles that are themselves see-through, it
		// would show through them and tint the game behind.
		if (this.opacity < 1) {
			// Nothing to draw.
		} else if (view.round()) {
			MapCanvas.of(graphics).solid(Circle.in(view).disc(), background);
		} else {
			graphics.fill(view.x(), view.y(), view.x() + view.width(), view.y() + view.height(), background);
		}

		this.drawTiles(graphics, level, view, view.zoom() >= OVERLAY_ZOOM ? overlays : 0, claims, lit);
		this.drawFog(graphics, level, view);
		graphics.disableScissor();
	}

	/**
	 * The overview level to draw: the coarsest one that still has at least one texture pixel per
	 * screen pixel. Overviews only exist for the saved surface map, and are only used below
	 * {@link #DETAILED_ZOOM}: above it the live tiles' own mipmaps look the same and never lag
	 * behind recent changes.
	 */
	public static int lodFor(MapLevel level, View view) {
		if (view.layer() != TileKey.SURFACE || !level.hasOverviews() || view.zoom() >= DETAILED_ZOOM) {
			return 0;
		}
		double screenPixelsPerUnit = view.zoom() * Minecraft.getInstance().getWindow().getGuiScale();
		int lod = 0;
		while (lod < TileKey.MAX_LOD && Projection.PIXELS_PER_UNIT / (double) (2 << lod) >= screenPixelsPerUnit) {
			lod++;
		}
		return lod;
	}

	/**
	 * How this view samples the tiles and anything drawn on their pixel grid. Shown larger than
	 * their pixels, they stay crisp so blocks keep their edges; magnified by too little for that,
	 * crisp pixels would come out uneven, one screen pixel wide here and two there, so they blend.
	 * Measured in screen pixels, as a GUI pixel is several on a high-density display; a whole GUI
	 * pixel per tile pixel or more is always crisp.
	 */
	public static Sampling sampling(View view) {
		double screenPixelsPerPixel = view.zoom() * Minecraft.getInstance().getWindow().getGuiScale() / Projection.PIXELS_PER_UNIT;
		return view.zoom() >= Projection.PIXELS_PER_UNIT || screenPixelsPerPixel >= CRISP_FROM ? Sampling.TILE_CRISP : Sampling.TILE_SMOOTH;
	}

	/** Whether every tile this view shows has baked at least once. Asks for the ones that haven't. */
	public static boolean ready(MapLevel level, View view) {
		boolean ready = true;
		for (TileKey key : tilesIn(level, view)) {
			ready &= level.want(key, distance(key, view)) != null;
		}
		return ready;
	}

	/** Whether any tile this view shows is baked and has something on it. Asks for nothing. */
	public static boolean showsAnything(MapLevel level, View view) {
		for (TileKey key : tilesIn(level, view)) {
			MapTile tile = level.peek(key);
			if (tile != null && !tile.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static List<TileKey> tilesIn(MapLevel level, View view) {
		int lod = lodFor(level, view);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double u0 = view.minU();
		double v0 = view.minV();
		int tu1 = (int) Math.floor((u0 + view.width() / view.zoom()) / units);
		int tv1 = (int) Math.floor((v0 + view.height() / view.zoom()) / units);
		List<TileKey> keys = new ArrayList<>();
		for (int tu = (int) Math.floor(u0 / units); tu <= tu1; tu++) {
			for (int tv = (int) Math.floor(v0 / units); tv <= tv1; tv++) {
				keys.add(new TileKey(view.layer(), tu, tv, lod));
			}
		}
		return keys;
	}

	private static double distance(TileKey key, View view) {
		double units = (double) Projection.TILE_UNITS * (1 << key.lod());
		double du = (key.tileU() + 0.5) * units - view.centerU();
		double dv = (key.tileV() + 0.5) * units - view.centerV();
		return Math.sqrt(du * du + dv * dv);
	}

	private void drawTiles(GuiGraphicsExtractor graphics, MapLevel level, View view, int overlays, boolean claims, @Nullable Runnable lit) {
		int lod = lodFor(level, view);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double u0 = view.minU();
		double v0 = view.minV();
		double u1 = u0 + view.width() / view.zoom();
		double v1 = v0 + view.height() / view.zoom();
		int tu0 = (int) Math.floor(u0 / units);
		int tv0 = (int) Math.floor(v0 / units);
		int tu1 = (int) Math.floor(u1 / units);
		int tv1 = (int) Math.floor(v1 / units);
		Sampling sampling = sampling(view);
		MapCanvas canvas = MapCanvas.of(graphics);
		float size = (float) (units * view.zoom());
		Circle circle = view.round() ? Circle.in(view) : null;
		// White, as see-through as the map is: the tiles are multiplied by it.
		int tint = Math.round(this.opacity * 255) << 24 | 0xFFFFFF;
		// Claims and overlays go over anything lit, so a light doesn't hide them.
		List<Runnable> above = new ArrayList<>();
		for (int tu = tu0 - 1; tu <= tu1 + 1; tu++) {
			for (int tv = tv0 - 1; tv <= tv1 + 1; tv++) {
				TileKey key = new TileKey(view.layer(), tu, tv, lod);
				double du = (tu + 0.5) * units - view.centerU();
				double dv = (tv + 0.5) * units - view.centerV();
				boolean visible = tu >= tu0 && tu <= tu1 && tv >= tv0 && tv <= tv1;
				// Tiles just outside the view bake too, so panning finds them ready.
				MapTile tile = level.want(key, Math.sqrt(du * du + dv * dv) + (visible ? 0 : 100_000));
				if (!visible || tile == null || tile.texture() == null) {
					continue;
				}
				float sx = view.screenX(tu * units);
				float sy = view.screenY(tv * units);
				MapTexture overlay = overlays == 0 || lod > 0 ? null : tile.overlay(overlays);
				MapTexture claimed = claims ? level.claims(key, tile) : null;
				if (circle != null) {
					Circle.Quads inside = circle.clip(sx, sy, sx + size, sy + size, 0, 0, 1, 1);
					if (inside != null) {
						canvas.quad(tile.texture(), sampling, inside.corners(), inside.uvs(), tint);
						if (claimed != null) {
							above.add(() -> canvas.quad(claimed, sampling, inside.corners(), inside.uvs(), tint));
						}
						if (overlay != null) {
							above.add(() -> canvas.quad(overlay, sampling, inside.corners(), inside.uvs(), tint));
						}
					}
					continue;
				}
				canvas.rect(tile.texture(), sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, tint);
				if (claimed != null) {
					above.add(() -> canvas.rect(claimed, sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, tint));
				}
				if (overlay != null) {
					above.add(() -> canvas.rect(overlay, sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, tint));
				}
			}
		}
		if (this.drawsNear(view, lod)) {
			drawNear(level, view, canvas, sampling, circle, tint);
		}
		if (lit != null) {
			lit.run();
		}
		above.forEach(Runnable::run);
	}

	/**
	 * Whether detailed tiles go over this view's overviews. Not on a see-through map: the ground
	 * would show through twice, as a brighter square, so it waits for the overviews instead.
	 */
	private boolean drawsNear(View view, int lod) {
		return lod > 0 && view.follows() && this.opacity >= 1;
	}

	private static void drawNear(MapLevel level, View view, MapCanvas canvas, Sampling sampling, @Nullable Circle circle, int tint) {
		int units = Projection.TILE_UNITS;
		float size = (float) (units * view.zoom());
		int nearU = (int) Math.floor(view.centerU() / units);
		int nearV = (int) Math.floor(view.centerV() / units);
		for (int tu = nearU - NEAR_TILES; tu <= nearU + NEAR_TILES; tu++) {
			for (int tv = nearV - NEAR_TILES; tv <= nearV + NEAR_TILES; tv++) {
				TileKey key = new TileKey(view.layer(), tu, tv, 0);
				float sx = view.screenX((double) tu * units);
				float sy = view.screenY((double) tv * units);
				boolean visible = sx < view.x() + view.width() && sx + size > view.x() && sy < view.y() + view.height() && sy + size > view.y();
				// As in drawTiles: those just outside the view bake too, after everything in it.
				MapTile tile = level.want(key, distance(key, view) + (visible ? 0 : 100_000));
				if (!visible || tile == null || tile.texture() == null) {
					continue;
				}
				if (circle == null) {
					canvas.rect(tile.texture(), sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, tint);
					continue;
				}
				Circle.Quads inside = circle.clip(sx, sy, sx + size, sy + size, 0, 0, 1, 1);
				if (inside != null) {
					canvas.quad(tile.texture(), sampling, inside.corners(), inside.uvs(), tint);
				}
			}
		}
	}

	private void drawFog(GuiGraphicsExtractor graphics, MapLevel level, View view) {
		double viewW = view.width() / view.zoom();
		double viewH = view.height() / view.zoom();
		// Zoomed out past the detailed zoom, the fog's noise grows with the view so it keeps its
		// size on screen instead of repeating every few pixels. The scale is 2^(steps + fraction);
		// the shader blends the noise of two whole steps by the fraction.
		double scaleLog = Math.min(7.99, Math.log(Math.max(1, DETAILED_ZOOM / view.zoom())) / Math.log(2));
		int steps = (int) Math.floor(scaleLog);
		int fraction = (int) Math.floor((scaleLog - steps) * 16);
		// The noise offset is in eighths of the coarser noise's period, so the origin snaps to that.
		double snap = 64.0 * (1 << (steps + 1));
		double need = Math.max(viewW, viewH) + MASK_MARGIN * 2 + snap;
		int scaleCode = 0;
		double texel = 2;
		while (MASK * texel < need) {
			texel *= 2;
			scaleCode++;
		}
		double originU = Math.floor((view.minU() - MASK_MARGIN) / snap) * snap;
		double originV = Math.floor((view.minV() - MASK_MARGIN) / snap) * snap;
		boolean fast = TileBaker.fast();
		this.buildMask(level, view, originU, originV, texel, steps, fast);

		double span = MASK * texel;
		float mu0 = (float) ((view.minU() - originU) / span);
		float mv0 = (float) ((view.minV() - originV) / span);
		float mu1 = (float) ((view.minU() + viewW - originU) / span);
		float mv1 = (float) ((view.minV() + viewH - originV) / span);
		int noiseU = Math.floorMod((long) (originU / snap), 8);
		int noiseV = Math.floorMod((long) (originV / snap), 8);
		boolean cave = view.layer() != TileKey.SURFACE;
		int fogCode = (cave ? 128 : 0) | steps << 4 | fraction;
		// The mask scale code takes the alpha byte's high nibble; 4 in the low nibble asks for fast
		// fog, and 1 for smoke instead of clouds over the sky view under a roof.
		int quality = (fast ? 4 : 0) | (level.roofless() ? 1 : 0);
		// The low five bits of the noise offset's red byte hold how see-through the fog is, 0 for
		// not at all, so a caller that sets nothing there gets the fog it always did.
		int veil = Math.round((1 - this.opacity) * OPACITY_CODES);
		int params = (scaleCode * 16 | quality) << 24 | (noiseU * 32 | veil) << 16 | (noiseV * 32) << 8 | fogCode;

		MapCanvas canvas = MapCanvas.of(graphics);
		if (view.round()) {
			Circle.Quads disc = Circle.in(view).clip(view.x(), view.y(), view.x() + view.width(), view.y() + view.height(), mu0, mv0, mu1, mv1);
			if (disc != null) {
				canvas.fog(this.maskTexture, FogTextures.noise(), disc.corners(), disc.uvs(), view.x(), view.y(), view.width(), view.height(), params);
			}
			return;
		}
		canvas.fog(
			this.maskTexture, FogTextures.noise(),
			view.x(), view.y(), view.width(), view.height(),
			mu0, mv0, mu1, mv1,
			params
		);
	}

	private void buildMask(MapLevel level, View view, double originU, double originV, double texel, int scaleSteps, boolean fast) {
		if (this.maskTexture == null) {
			this.maskTexture = MapTexture.create("diorama fog mask", MASK, 1);
		}
		int lod = lodFor(level, view);
		// Caves keep the sharp mask: rounding would erode narrow explored tunnels away.
		// Once a texel spans a chunk there are no chunk steps left to smooth, and rounding would
		// only eat small explored areas that are a few texels across.
		boolean rounded = MapStyle.get().roundedFog() && view.layer() == TileKey.SURFACE && texel < 16;
		boolean drawsNear = this.drawsNear(view, lod);
		int nearU = drawsNear ? (int) Math.floor(view.centerU() / Projection.TILE_UNITS) : Integer.MIN_VALUE;
		int nearV = drawsNear ? (int) Math.floor(view.centerV() / Projection.TILE_UNITS) : Integer.MIN_VALUE;
		MaskInputs inputs = new MaskInputs(level.coverageVersion(), view.layer(), lod, originU, originV, texel, rounded, scaleSteps, fast, nearU, nearV);
		if (this.maskLevel.get() == level && inputs.equals(this.maskInputs)) {
			return;
		}
		this.maskInputs = inputs;
		this.maskLevel = new WeakReference<>(level);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double cellUnits = units / Projection.COVERAGE_CELLS;
		int lastTu = Integer.MIN_VALUE;
		int lastTv = Integer.MIN_VALUE;
		MapTile tile = null;
		int lastNearTu = Integer.MIN_VALUE;
		int lastNearTv = Integer.MIN_VALUE;
		MapTile nearTile = null;
		for (int j = 0; j < MASK; j++) {
			double v = originV + (j + 0.5) * texel;
			int tv = (int) Math.floor(v / units);
			int cellY = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - tv * units) / cellUnits));
			for (int i = 0; i < MASK; i++) {
				double u = originU + (i + 0.5) * texel;
				int tu = (int) Math.floor(u / units);
				if (tu != lastTu || tv != lastTv) {
					tile = level.peek(new TileKey(view.layer(), tu, tv, lod));
					lastTu = tu;
					lastTv = tv;
				}
				float known = 0;
				if (tile != null && tile.hasCoverage()) {
					int cellX = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - tu * units) / cellUnits));
					known = tile.coverage(cellX, cellY);
				}
				// Ground the overviews don't have yet can be on a detailed tile drawn over them.
				if (drawsNear && known < 1) {
					int nearTu = (int) Math.floor(u / Projection.TILE_UNITS);
					int nearTv = (int) Math.floor(v / Projection.TILE_UNITS);
					if (Math.abs(nearTu - nearU) <= NEAR_TILES && Math.abs(nearTv - nearV) <= NEAR_TILES) {
						if (nearTu != lastNearTu || nearTv != lastNearTv) {
							nearTile = level.peek(new TileKey(view.layer(), nearTu, nearTv, 0));
							lastNearTu = nearTu;
							lastNearTv = nearTv;
						}
						if (nearTile != null && nearTile.hasCoverage()) {
							double cell = (double) Projection.TILE_UNITS / Projection.COVERAGE_CELLS;
							int nearX = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - nearTu * (double) Projection.TILE_UNITS) / cell));
							int nearY = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - nearTv * (double) Projection.TILE_UNITS) / cell));
							known = Math.max(known, nearTile.coverage(nearX, nearY));
						}
					}
				}
				this.maskPixels[j * MASK + i] = 0xFF000000 | Math.round(known * 255);
			}
		}
		if (rounded) {
			this.roundOff(Math.max(1, (int) Math.round(10 / texel)));
		}
		this.markReach(fogReach(texel, view.layer() != TileKey.SURFACE, fast, scaleSteps));
		this.maskTexture.write(0, 0, 0, MASK, this.maskPixels);
	}

	/**
	 * How far, in mask texels, the fog shader can read from a pixel: {west, east, north, south}.
	 * Mirrors fog.fsh: the blur taps (the wide surface blur, 1.9x on the diagonals, never under a
	 * texel), the height layers sitting up to fogHeight * COS_PITCH further south, and the sun probe
	 * 9 west and 3.6 north times the noise scale. On top of that one texel for linear filtering,
	 * and half a texel for where in its own texel the pixel sits.
	 */
	private static int[] fogReach(double texel, boolean cave, boolean fast, int scaleSteps) {
		double fogHeight = cave ? 4 : 10;
		double blurUnits = cave ? 2.5 : 7;
		if (blurUnits >= 5 && !fast) {
			blurUnits *= 2.4;
		}
		double blur = 1.9 * Math.max(blurUnits / texel, 1);
		double layerShift = fogHeight * 0.643 / texel;
		// The noise scale is below 2^(steps + 1).
		double sun = fast ? 0 : 9.0 * (1 << (scaleSteps + 1)) / texel;
		double slack = 1.5;
		return new int[] {
			(int) Math.ceil(Math.max(blur, sun) + slack),
			(int) Math.ceil(blur + slack),
			(int) Math.ceil(Math.max(blur, sun * 0.4) + slack),
			(int) Math.ceil(blur + layerShift + slack)
		};
	}

	private final int[] notKnownCounts = new int[(MASK + 1) * (MASK + 1)];
	private final int[] notUnknownCounts = new int[(MASK + 1) * (MASK + 1)];

	/**
	 * Sets the green channel where every texel within reach is fully known, and the blue channel
	 * where every one is fully unknown. The shader skips its mask reads there, because it knows
	 * what they would return: over clear ground there is no fog thickness anywhere it looks, so it
	 * skips the noise too; deep in the fog only the noise is left to read. Texels past the mask's
	 * edge read as the edge texel, so the reach just stops at the edge.
	 */
	private void markReach(int[] reach) {
		int row = MASK + 1;
		// counts[(j + 1) * row + i + 1]: texels short of fully known (or with any known share) in the
		// rectangle from (0, 0) to (i, j).
		int[] notKnown = this.notKnownCounts;
		int[] notUnknown = this.notUnknownCounts;
		for (int j = 0; j < MASK; j++) {
			int lineNotKnown = 0;
			int lineNotUnknown = 0;
			for (int i = 0; i < MASK; i++) {
				int known = this.maskPixels[j * MASK + i] & 0xFF;
				lineNotKnown += known == 0xFF ? 0 : 1;
				lineNotUnknown += known == 0 ? 0 : 1;
				notKnown[(j + 1) * row + i + 1] = notKnown[j * row + i + 1] + lineNotKnown;
				notUnknown[(j + 1) * row + i + 1] = notUnknown[j * row + i + 1] + lineNotUnknown;
			}
		}
		for (int j = 0; j < MASK; j++) {
			int top = Math.max(0, j - reach[2]);
			int bottom = Math.min(MASK - 1, j + reach[3]) + 1;
			for (int i = 0; i < MASK; i++) {
				int left = Math.max(0, i - reach[0]);
				int right = Math.min(MASK - 1, i + reach[1]) + 1;
				int a = bottom * row + right;
				int b = top * row + right;
				int c = bottom * row + left;
				int d = top * row + left;
				if (notKnown[a] - notKnown[b] - notKnown[c] + notKnown[d] == 0) {
					this.maskPixels[j * MASK + i] |= 0xFF00;
				} else if (notUnknown[a] - notUnknown[b] - notUnknown[c] + notUnknown[d] == 0) {
					this.maskPixels[j * MASK + i] |= 0xFF0000;
				}
			}
		}
	}

	private final float[] blurA = new float[MASK * MASK];
	private final float[] blurB = new float[MASK * MASK];

	/**
	 * Rounds the known area off so the fog edge follows a smooth line instead of chunk steps.
	 * Blurs the mask, keeps only what stays solidly known, and takes the smaller of that and the
	 * original, so the fog only ever grows over the edge and never uncovers ground with no data.
	 */
	private void roundOff(int radius) {
		for (int i = 0; i < this.maskPixels.length; i++) {
			this.blurA[i] = (this.maskPixels[i] & 0xFF) / 255.0F;
		}
		boxBlur(this.blurA, this.blurB, radius, true);
		boxBlur(this.blurB, this.blurA, radius, false);
		boxBlur(this.blurA, this.blurB, radius, true);
		boxBlur(this.blurB, this.blurA, radius, false);
		for (int i = 0; i < this.maskPixels.length; i++) {
			float t = Math.max(0, Math.min(1, (this.blurA[i] - 0.5F) / 0.35F));
			float rounded = t * t * (3 - 2 * t);
			int known = Math.min(this.maskPixels[i] & 0xFF, Math.round(rounded * 255));
			this.maskPixels[i] = 0xFF000000 | known;
		}
	}

	private static void boxBlur(float[] from, float[] to, int radius, boolean horizontal) {
		float scale = 1.0F / (2 * radius + 1);
		for (int line = 0; line < MASK; line++) {
			float sum = 0;
			for (int k = -radius; k <= radius; k++) {
				sum += from[index(line, Math.max(0, Math.min(MASK - 1, k)), horizontal)];
			}
			for (int k = 0; k < MASK; k++) {
				to[index(line, k, horizontal)] = sum * scale;
				int add = Math.min(MASK - 1, k + radius + 1);
				int drop = Math.max(0, k - radius);
				sum += from[index(line, add, horizontal)] - from[index(line, drop, horizontal)];
			}
		}
	}

	private static int index(int line, int k, boolean horizontal) {
		return horizontal ? line * MASK + k : k * MASK + line;
	}

	public void close() {
		if (this.maskTexture != null) {
			this.maskTexture.close();
			this.maskTexture = null;
		}
		this.maskInputs = null;
	}
}
