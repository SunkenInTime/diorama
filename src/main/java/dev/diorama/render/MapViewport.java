package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.MapStyle;
import dev.diorama.Projection;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.render.gpu.FogLayer;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.MapTexture;
import dev.diorama.render.gpu.Sampling;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
	/**
	 * What fog.fsh would otherwise work out from the mask for every pixel, worked out once per mask
	 * texel instead: blue is the known share blurred as the fog's soft edge reads it.
	 */
	private @Nullable MapTexture detailTexture;
	private final int[] detailPixels = new int[MASK * MASK];
	private final byte[] softBytes = new byte[MASK * MASK];
	/** What the mask texture was last built from; it only needs building again when this changes. */
	private @Nullable MaskInputs maskInputs;
	/** The map the mask was built from. Weak, so a minimap left over after leaving a world doesn't keep its map. */
	private WeakReference<MapLevel> maskLevel = new WeakReference<>(null);

	private record MaskInputs(long coverageVersion, long memoryVersion, int layer, int lod, int memoryLod, double originU, double originV, double texel, boolean rounded, int scaleSteps, int fraction, boolean fast, int nearU, int nearV) {
		/**
		 * Whether a mask built from these lies over the screen as one built from {@code other} does:
		 * same place, scale and fog. Only what the map knows differs, which may show a frame late.
		 */
		boolean sameGeometry(MaskInputs other) {
			return this.layer == other.layer && this.originU == other.originU && this.originV == other.originV && this.texel == other.texel
				&& this.rounded == other.rounded && this.scaleSteps == other.scaleSteps && this.fraction == other.fraction && this.fast == other.fast;
		}
	}

	/**
	 * Finishes fog masks off the render thread. After the tiles are read into the mask, the rest of
	 * a build (rounding off, the soft edge, the reach marks) took 3 to 5 milliseconds of the frame
	 * on a fast CPU, several times a second while flying over new land. When only what the map
	 * knows changed, the view is where it was and a mask a frame late shows the same: so this
	 * thread does that part, and the render thread uploads it once it's done.
	 */
	private static final ExecutorService MASK_WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "diorama-fog-mask");
		thread.setDaemon(true);
		// Under the game's threads, over the bakers': the fog waits on it.
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	});
	/**
	 * The mask being finished on {@link #MASK_WORKER}, or null. Until it's done, everything a build
	 * works in (the mask's pixels and what they're made with) is the worker's, and the render
	 * thread leaves it alone.
	 */
	private @Nullable CompletableFuture<Void> finishing;
	/** The worker's build threw: built again in full on the render thread. */
	private volatile boolean finishFailed;
	private static boolean finishFailureLogged;

	/**
	 * Where and how big a map is drawn, and what part of the map it shows. A {@code round} view
	 * shows only the circle filling it (the round minimap); a {@code follows} view is on the player,
	 * so what's around its middle has to stay up to date (the minimap again).
	 *
	 * <p>A view with a {@code turn} shows the map turned that many radians clockwise about its
	 * middle (the minimap that turns with the player). What lies on the ground is placed as if there
	 * were none, with {@link #screenX(double)} and {@link #screenY(double)}, and drawn through
	 * {@link #canvas}, which turns it; what stays upright, like an icon, is placed with
	 * {@link #screenX(double, double)} and {@link #screenY(double, double)}.
	 */
	public record View(int x, int y, int width, int height, double centerU, double centerV, double zoom, int layer, boolean round, boolean follows, float turn) {
		public View(int x, int y, int width, int height, double centerU, double centerV, double zoom, int layer) {
			this(x, y, width, height, centerU, centerV, zoom, layer, false, false, 0);
		}

		public View(int x, int y, int width, int height, double centerU, double centerV, double zoom, int layer, boolean round, boolean follows) {
			this(x, y, width, height, centerU, centerV, zoom, layer, round, follows, 0);
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

		/** Where a point of the map is on screen, turned with the view. */
		public float screenX(double u, double v) {
			if (this.turn == 0) {
				return this.screenX(u);
			}
			return (float) (this.x + this.width / 2.0 + ((u - this.centerU) * Math.cos(this.turn) - (v - this.centerV) * Math.sin(this.turn)) * this.zoom);
		}

		public float screenY(double u, double v) {
			if (this.turn == 0) {
				return this.screenY(v);
			}
			return (float) (this.y + this.height / 2.0 + ((u - this.centerU) * Math.sin(this.turn) + (v - this.centerV) * Math.cos(this.turn)) * this.zoom);
		}

		/**
		 * The part of the map that has to be drawn to fill this view however it's turned: for a
		 * square one, the square that holds it at every angle, about the same middle. A round view
		 * shows the same circle at every angle, and a view that doesn't turn is its own.
		 */
		public View ground() {
			if (this.turn == 0 || this.round) {
				return this;
			}
			int reach = (int) Math.ceil(Math.hypot(this.width, this.height) / 2);
			int padX = reach - this.width / 2;
			int padY = reach - this.height / 2;
			return new View(this.x - padX, this.y - padY, this.width + 2 * padX, this.height + 2 * padY, this.centerU, this.centerV, this.zoom, this.layer, false, this.follows, this.turn);
		}

		/** A canvas that draws what's placed on this view's ground turned as the view is. */
		public MapCanvas canvas(GuiGraphicsExtractor graphics) {
			MapCanvas canvas = MapCanvas.of(graphics);
			return this.turn == 0 ? canvas : new TurnedCanvas(canvas, this.x + this.width / 2.0F, this.y + this.height / 2.0F, this.turn);
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
	private static final int NEAR_TILE_COUNT = (2 * NEAR_TILES + 1) * (2 * NEAR_TILES + 1);

	/**
	 * The most whole steps the fog's noise grows by as the view zooms out: enough for the full map's
	 * furthest zoom at GUI scale 8, so the noise never repeats in a visible grid.
	 */
	private static final int MAX_FOG_STEPS = 11;

	/**
	 * From this many map units a mask texel, the fog keeps off everything mapped; see
	 * {@link #keepOffMapped}. The fog's soft edge, two texels, is then a thousand blocks or more.
	 * Only the full map gets here, zoomed out past where it stopped up to 0.9.0 (0.04 GUI pixels a
	 * block), so the fog looks as it did at every zoom the map had then.
	 */
	private static final double COARSE_TEXEL = 512;
	/** How many mask texels the mapped ground grows by under a coarse mask: as far as the soft edge reaches in. */
	private static final int COARSE_GROW = 2;
	/** Where in a coarse mask texel the map is read: four spots, so ground a texel's middle misses still counts. */
	private static final double[] COARSE_SPOTS = {0.25, 0.25, 0.75, 0.25, 0.25, 0.75, 0.75, 0.75};
	private static final double[] MIDDLE = {0.5, 0.5};

	/** The most the fog shader's opacity code can say: it has five spare bits. */
	private static final int OPACITY_CODES = 31;

	/** How opaque the map draws, 0 to 1. */
	private float opacity = 1;
	/** How far into the night to draw the sky view whatever the time, or -1 to follow {@link NightLook}. */
	private float night = -1;

	/**
	 * Sets how opaque this map is drawn: the tiles and the fog, and not the backdrop behind them, so
	 * the game shows through. The fullscreen map leaves it at 1; the minimap takes it from the settings.
	 */
	/** Draws the sky view this far into the night (0..1) whatever the time, or as {@link NightLook} has it for -1. */
	public void night(float night) {
		this.night = night;
	}

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
	public void draw(GuiGraphicsExtractor graphics, MapLevel level, View frame, int overlays, boolean claims, @Nullable Runnable lit) {
		graphics.enableScissor(frame.x(), frame.y(), frame.x() + frame.width(), frame.y() + frame.height());
		// A turned square view shows ground from outside its own square.
		View view = frame.ground();
		boolean cave = view.layer() != TileKey.SURFACE;
		// Behind the tiles, a colour from the fog that covers where there's no data, so the ragged
		// edge of known ground blends into it: the fog, smoke under a roof, or the dark of caves.
		int background = cave ? 0xFF0C0E12 : level.roofless() ? 0xFF29211F : 0xFF8C99AB;
		float night = this.night < 0 || cave || level.roofless() ? NightLook.forView(level, view.layer()) : this.night;
		if (night > 0) {
			background = NightLook.darken(background, night);
		}
		// A see-through map has no backdrop: drawn under tiles that are themselves see-through, it
		// would show through them and tint the game behind.
		if (this.opacity < 1) {
			// Nothing to draw.
		} else if (view.round()) {
			MapCanvas.of(graphics).solid(Circle.in(frame).disc(), background);
		} else {
			graphics.fill(frame.x(), frame.y(), frame.x() + frame.width(), frame.y() + frame.height(), background);
		}

		int alpha = Math.round(this.opacity * 255);
		MapMemory memory = cave ? null : level.memory();
		if (memory != null) {
			memory.draw(view.canvas(graphics), view, NightLook.dim(night, alpha), lodFor(level, view), tileSampling(sampling(view)));
		}
		this.drawTiles(graphics, level, view, view.zoom() >= OVERLAY_ZOOM ? overlays : 0, claims, lit, night);
		this.drawFog(graphics, level, view, frame, night);
		graphics.disableScissor();
	}

	/**
	 * The overview level to draw: the coarsest one that still has at least one texture pixel per
	 * screen pixel, or one finer where that many tiles fit in memory, as a tile looks sharper shrunk
	 * further. Overviews only exist for the saved surface map, and are only used below
	 * {@link #DETAILED_ZOOM}: above it the live tiles' own mipmaps look the same and never lag
	 * behind recent changes. Stepping one finer, the full map can reach the live tiles a little below
	 * it too.
	 *
	 * <p>A wide view just above {@link #DETAILED_ZOOM} is the exception: a big screen at a small GUI
	 * scale shows more detailed tiles there than stay in memory, so the map dropped tiles it was
	 * drawing and baked them again for as long as the view stayed. It draws the first overview level
	 * until the detailed tiles fit, while that level still has a texture pixel per screen pixel and
	 * below {@link #OVERLAY_ZOOM}, as only detailed tiles carry the overlays.
	 */
	public static int lodFor(MapLevel level, View view) {
		if (view.layer() != TileKey.SURFACE || !level.hasOverviews()) {
			return 0;
		}
		double screenPixelsPerUnit = view.zoom() * Minecraft.getInstance().getWindow().getGuiScale();
		if (view.zoom() >= DETAILED_ZOOM) {
			boolean wide = !view.follows() && view.zoom() < OVERLAY_ZOOM && mostTilesFor(view, 0) > MapLevel.KEPT_TILES;
			return wide && Projection.PIXELS_PER_UNIT / 2.0 >= screenPixelsPerUnit ? 1 : 0;
		}
		int lod = 0;
		while (lod < TileKey.MAX_LOD && Projection.PIXELS_PER_UNIT / (double) (2 << lod) >= screenPixelsPerUnit) {
			lod++;
		}
		// A following view stays on overviews: detailed tiles all over a minimap would bake again as
		// the player moves, and the middle, which needs them, gets them from drawNear.
		boolean finer = lod > 1 || lod == 1 && !view.follows();
		return finer && mostTilesFor(view, lod - 1) <= MapLevel.KEPT_TILES - NEAR_TILE_COUNT ? lod - 1 : lod;
	}

	/**
	 * The most tiles of an overview level a view of this size asks for, wherever it pans: those it
	 * shows, up to one more each way than it spans, and a tile all round, as {@link #drawTiles} asks.
	 */
	private static double mostTilesFor(View view, int lod) {
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		return (Math.ceil(view.width() / view.zoom() / units) + 3) * (Math.ceil(view.height() / view.zoom() / units) + 3);
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
		// Turned, the tiles' pixels lie at an angle to the screen's, and crisp ones would crawl as the view turns.
		return view.turn() == 0 && (view.zoom() >= Projection.PIXELS_PER_UNIT || screenPixelsPerPixel >= CRISP_FROM) ? Sampling.TILE_CRISP : Sampling.TILE_SMOOTH;
	}

	/** How a tile's own pixels are sampled; what's drawn over them has no mip levels for the tile shader. */
	static Sampling tileSampling(Sampling sampling) {
		return sampling == Sampling.TILE_SMOOTH ? Sampling.TILE_AREA : sampling;
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

	private void drawTiles(GuiGraphicsExtractor graphics, MapLevel level, View view, int overlays, boolean claims, @Nullable Runnable lit, float night) {
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
		Sampling tiles = tileSampling(sampling);
		MapCanvas canvas = view.canvas(graphics);
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
				MapTile tile = level.want(key, Math.sqrt(du * du + dv * dv) + (visible ? 0 : MapLevel.OUTSIDE_VIEW));
				float sx = view.screenX(tu * units);
				float sy = view.screenY(tv * units);
				if (visible && (tile == null || tile.texture() == null)) {
					// Not loaded yet, as after zooming to another level: show what's loaded of the same ground.
					drawStandIn(level, key, canvas, tiles, circle, sx, sy, size, tint, night);
					continue;
				}
				if (!visible || tile == null || tile.texture() == null) {
					continue;
				}
				MapTexture overlay = overlays == 0 || lod > 0 ? null : tile.overlay(overlays);
				MapTexture claimed = claims ? level.claims(key, tile) : null;
				if (circle != null) {
					Circle.Quads inside = circle.clip(sx, sy, sx + size, sy + size, 0, 0, 1, 1);
					if (inside != null) {
						drawTile(canvas, tile, tiles, inside.corners(), inside.uvs(), tint, night);
						if (claimed != null) {
							above.add(() -> canvas.quad(claimed, sampling, inside.corners(), inside.uvs(), tint));
						}
						if (overlay != null) {
							above.add(() -> canvas.quad(overlay, sampling, inside.corners(), inside.uvs(), tint));
						}
					}
					continue;
				}
				drawTile(canvas, tile, tiles, sx, sy, size, 0, 0, 1, 1, tint, night);
				if (claimed != null) {
					above.add(() -> canvas.rect(claimed, sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, tint));
				}
				if (overlay != null) {
					above.add(() -> canvas.rect(overlay, sampling, sx, sy, sx + size, sy + size, 0, 0, 1, 1, tint));
				}
			}
		}
		if (this.drawsNear(view, lod)) {
			drawNear(level, view, canvas, tiles, circle, tint, night);
		}
		if (lit != null) {
			lit.run();
		}
		above.forEach(Runnable::run);
	}

	/**
	 * Draws a tile that hasn't loaded from one that has: the coarser tile over the same ground,
	 * magnified, else the finer tiles under it, shrunk, from up to {@link #FINER_STAND_INS} levels
	 * down. Blurry or rough, but the ground doesn't vanish while the right tile loads, which on a big
	 * map takes a while. Draws nothing if none is in memory.
	 */
	private static void drawStandIn(MapLevel level, TileKey key, MapCanvas canvas, Sampling sampling, @Nullable Circle circle, float sx, float sy, float size, int tint, float night) {
		TileKey above = key;
		for (int up = 1; key.lod() + up <= TileKey.MAX_LOD; up++) {
			above = above.parent();
			MapTile tile = level.standIn(above);
			if (tile != null && tile.texture() != null) {
				int span = 1 << up;
				float u0 = Math.floorMod(key.tileU(), span) / (float) span;
				float v0 = Math.floorMod(key.tileV(), span) / (float) span;
				drawPart(canvas, tile, sampling, circle, sx, sy, size, u0, v0, u0 + 1F / span, v0 + 1F / span, tint, night);
				return;
			}
		}
		drawFiner(level, key, canvas, sampling, circle, sx, sy, size, tint, night, FINER_STAND_INS);
	}

	/**
	 * How many levels finer a stand-in can come from. One notch of the wheel zooms 1.25 times and a
	 * level is twice the zoom, so a quick scroll crosses two levels before the one between has loaded,
	 * and the tiles last drawn are then two levels finer.
	 */
	private static final int FINER_STAND_INS = 2;

	/** Draws the four tiles one level finer than {@code key}, and in place of any not in memory, theirs. */
	private static void drawFiner(MapLevel level, TileKey key, MapCanvas canvas, Sampling sampling, @Nullable Circle circle, float sx, float sy, float size, int tint, float night, int levels) {
		if (key.lod() == 0 || levels == 0) {
			return;
		}
		float half = size / 2;
		for (int dv = 0; dv < 2; dv++) {
			for (int du = 0; du < 2; du++) {
				TileKey child = new TileKey(key.layer(), key.tileU() * 2 + du, key.tileV() * 2 + dv, key.lod() - 1);
				MapTile tile = level.standIn(child);
				if (tile != null && tile.texture() != null) {
					drawPart(canvas, tile, sampling, circle, sx + du * half, sy + dv * half, half, 0, 0, 1, 1, tint, night);
				} else if (tile == null) {
					drawFiner(level, child, canvas, sampling, circle, sx + du * half, sy + dv * half, half, tint, night, levels - 1);
				}
			}
		}
	}

	/** Draws the part of a tile's texture from (u0, v0) to (u1, v1) in a square, inside the circle if the view is round. */
	private static void drawPart(MapCanvas canvas, MapTile tile, Sampling sampling, @Nullable Circle circle, float sx, float sy, float size, float u0, float v0, float u1, float v1, int tint, float night) {
		if (circle != null) {
			Circle.Quads inside = circle.clip(sx, sy, sx + size, sy + size, u0, v0, u1, v1);
			if (inside != null) {
				drawTile(canvas, tile, sampling, inside.corners(), inside.uvs(), tint, night);
			}
			return;
		}
		drawTile(canvas, tile, sampling, sx, sy, size, u0, v0, u1, v1, tint, night);
	}

	/** A square of a tile's texture from (u0, v0) to (u1, v1); see the other {@link #drawTile}. */
	private static void drawTile(MapCanvas canvas, MapTile tile, Sampling sampling, float sx, float sy, float size, float u0, float v0, float u1, float v1, int tint, float night) {
		drawTile(
			canvas, tile, sampling,
			new float[] {sx, sy, sx, sy + size, sx + size, sy + size, sx + size, sy},
			new float[] {u0, v0, u0, v1, u1, v1, u1, v0},
			tint, night
		);
	}

	/**
	 * A tile's texture over these quads, as {@link MapCanvas#quad} draws it, {@code night} into the
	 * night ({@link NightLook}): by day as it is, at night dimmed, with its block light if it has any.
	 */
	private static void drawTile(MapCanvas canvas, MapTile tile, Sampling sampling, float[] corners, float[] uvs, int tint, float night) {
		if (night <= 0) {
			canvas.quad(tile.texture(), sampling, corners, uvs, tint);
			return;
		}
		int alpha = tint >>> 24;
		MapTexture light = tile.light();
		if (light == null) {
			canvas.quad(tile.texture(), sampling, corners, uvs, NightLook.dim(night, alpha));
			return;
		}
		canvas.nightTile(tile.texture(), sampling, light, corners, uvs, NightLook.params(night, sampling == Sampling.TILE_CRISP, alpha));
	}

	/**
	 * Whether detailed tiles go over this view's overviews. Not on a see-through map: the ground
	 * would show through twice, as a brighter square, so it waits for the overviews instead.
	 */
	private boolean drawsNear(View view, int lod) {
		return lod > 0 && view.follows() && this.opacity >= 1;
	}

	private static void drawNear(MapLevel level, View view, MapCanvas canvas, Sampling sampling, @Nullable Circle circle, int tint, float night) {
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
				drawPart(canvas, tile, sampling, circle, sx, sy, size, 0, 0, 1, 1, tint, night);
			}
		}
	}

	private static volatile boolean fogHiddenForTests;

	/** For tests that check the tiles under the fog (TileFilterTest): the map drawn without it. */
	public static void hideFogForTests(boolean hide) {
		fogHiddenForTests = hide;
	}

	private void drawFog(GuiGraphicsExtractor graphics, MapLevel level, View view, View frame, float night) {
		double viewW = view.width() / view.zoom();
		double viewH = view.height() / view.zoom();
		// Zoomed out past the detailed zoom, the fog's noise grows with the view so it keeps its
		// size on screen instead of repeating every few pixels. The scale is 2^(steps + fraction);
		// the shader blends the noise of two whole steps by the fraction.
		double scaleLog = Math.min(MAX_FOG_STEPS + 0.99, Math.log(Math.max(1, DETAILED_ZOOM / view.zoom())) / Math.log(2));
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
		this.buildMask(level, view, originU, originV, texel, steps, fraction, fast);
		if (fogHiddenForTests) {
			return;
		}

		double span = MASK * texel;
		float mu0 = (float) ((view.minU() - originU) / span);
		float mv0 = (float) ((view.minV() - originV) / span);
		float mu1 = (float) ((view.minU() + viewW - originU) / span);
		float mv1 = (float) ((view.minV() + viewH - originV) / span);
		int noiseU = Math.floorMod((long) (originU / snap), 8);
		int noiseV = Math.floorMod((long) (originV / snap), 8);
		boolean cave = view.layer() != TileKey.SURFACE;
		// The blue byte has three bits for the steps; the eight above them are a bit of the alpha byte.
		int fogCode = (cave ? 128 : 0) | (steps & 7) << 4 | fraction;
		// The mask scale code takes the alpha byte's high nibble; in the low nibble, 8 is eight more
		// fog scale steps, 4 asks for fast fog, and 1 for smoke instead of fog over the sky view
		// under a roof.
		int quality = (steps >= 8 ? 8 : 0) | (fast ? 4 : 0) | (level.roofless() ? 1 : 0);
		// The low five bits of the noise offset's red byte hold how see-through the fog is, 0 for
		// not at all, so a caller that sets nothing there gets the fog it always did.
		int veil = Math.round((1 - this.opacity) * OPACITY_CODES);
		// The green byte's low five bits: how far into the night, in 31sts, which dims the fog too.
		int dark = Math.round(night * 31);
		int params = (scaleCode * 16 | quality) << 24 | (noiseU * 32 | veil) << 16 | (noiseV * 32 | dark) << 8 | fogCode;

		MapCanvas canvas = view.canvas(graphics);
		// The surface fog on Fast draws at full resolution, as it did before 0.8.0: its two layers'
		// dither, drawn at half and scaled up, came out twice as coarse.
		boolean fullSize = fast && !cave && !level.roofless();
		if (!fullSize && this.drawFogLayer(MapCanvas.of(graphics), canvas, view, originU, originV, span, params)) {
			return;
		}
		if (view.round()) {
			Circle.Quads disc = Circle.in(view).clip(view.x(), view.y(), view.x() + view.width(), view.y() + view.height(), mu0, mv0, mu1, mv1);
			if (disc != null) {
				canvas.fog(this.maskTexture, FogTextures.noise(), this.detailTexture, disc.corners(), disc.uvs(), frame.x(), frame.y(), frame.width(), frame.height(), params);
			}
			return;
		}
		canvas.fog(
			this.maskTexture, FogTextures.noise(), this.detailTexture,
			new float[] {view.x(), view.y(), view.x(), view.y() + view.height(), view.x() + view.width(), view.y() + view.height(), view.x() + view.width(), view.y()},
			new float[] {mu0, mv0, mu0, mv1, mu1, mv1, mu1, mv0},
			frame.x(), frame.y(), frame.width(), frame.height(),
			params
		);
	}

	/**
	 * The fog at half the screen's resolution, scaled up: drawn into {@link FogLayer} when anything it
	 * shows changes (the mask, the view, the parameters) or a game tick goes by, which moves the
	 * fog's slow drift on, and otherwise shown again as it is. False if the layer couldn't be drawn.
	 *
	 * <p>The layer's texels sit on a grid fixed to the map, whole texels from map 0, and the layer is
	 * shown shifted by where the view starts within a texel. So a pan draws the same fog at the same
	 * points of the map, and what's on screen moves with the map rather than being worked out afresh
	 * at new points, which made the fog's thin edges change shape as the map moved.
	 */
	private boolean drawFogLayer(MapCanvas straight, MapCanvas canvas, View view, double originU, double originV, double span, int params) {
		double scale = Minecraft.getInstance().getWindow().getGuiScale() / 2.0;
		// The view in layer texels, and a texel in map units.
		double texelsW = view.width() * scale;
		double texelsH = view.height() * scale;
		double texel = 1 / (view.zoom() * scale);
		// From the grid line a texel before the view's start, so what's shown never blends with a
		// texel the layer didn't draw; a texel more after its end for the same reason.
		double startU = (Math.floor(view.minU() / texel) - 1) * texel;
		double startV = (Math.floor(view.minV() / texel) - 1) * texel;
		double shiftU = (view.minU() - startU) / texel;
		double shiftV = (view.minV() - startV) / texel;
		int width = (int) Math.ceil(shiftU + texelsW) + 1;
		int height = (int) Math.ceil(shiftV + texelsH) + 1;
		if (this.fogLayer == null) {
			this.fogLayer = new FogLayer();
		}
		float u0 = (float) ((startU - originU) / span);
		float v0 = (float) ((startV - originV) / span);
		float u1 = (float) ((startU + width * texel - originU) / span);
		float v1 = (float) ((startV + height * texel - originV) / span);
		var world = Minecraft.getInstance().level;
		LayerKey key = new LayerKey(this.fogSerial, params, width, height, u0, v0, u1, v1, world == null ? 0 : world.getGameTime());
		if (!key.equals(this.layerKey) || this.fogLayer.texture() == null) {
			if (!this.fogLayer.render(straight, this.maskTexture, FogTextures.noise(), this.detailTexture, width, height, u0, v0, u1, v1, params | LAYER_CODE << 24)) {
				return false;
			}
			this.layerKey = key;
		}
		MapTexture layer = this.fogLayer.texture();
		float size = this.fogLayer.size();
		float su0 = (float) (shiftU / size);
		float sv0 = (float) (shiftV / size);
		float su1 = (float) ((shiftU + texelsW) / size);
		float sv1 = (float) ((shiftV + texelsH) / size);
		float x1 = view.x() + view.width();
		float y1 = view.y() + view.height();
		if (view.round()) {
			Circle.Quads disc = Circle.in(view).clip(view.x(), view.y(), x1, y1, su0, sv0, su1, sv1);
			if (disc != null) {
				canvas.quad(layer, Sampling.CLAMP_LINEAR, disc.corners(), disc.uvs(), 0xFFFFFFFF);
			}
		} else {
			canvas.rect(layer, Sampling.CLAMP_LINEAR, view.x(), view.y(), x1, y1, su0, sv0, su1, sv1, 0xFFFFFFFF);
		}
		return true;
	}

	/** In the fog parameters' alpha byte: draw into the layer (fog.fsh writes, rather than discards, where there is none). */
	private static final int LAYER_CODE = 2;

	/** Everything the layer shows: when it differs from what was drawn, the layer is drawn again. */
	private record LayerKey(long serial, int params, int width, int height, float u0, float v0, float u1, float v1, long tick) {
	}

	private @Nullable FogLayer fogLayer;
	private @Nullable LayerKey layerKey;
	/** Goes up whenever the mask or detail textures change. */
	private long fogSerial;

	private void buildMask(MapLevel level, View view, double originU, double originV, double texel, int scaleSteps, int fraction, boolean fast) {
		if (this.maskTexture == null) {
			this.maskTexture = MapTexture.create("diorama fog mask", MASK, 1);
			this.detailTexture = MapTexture.create("diorama fog details", MASK, 1);
		}
		int lod = lodFor(level, view);
		// Caves keep the sharp mask: rounding would erode narrow explored tunnels away.
		// Once a texel spans a chunk there are no chunk steps left to smooth, and rounding would
		// only eat small explored areas that are a few texels across.
		boolean rounded = MapStyle.get().roundedFog() && view.layer() == TileKey.SURFACE && texel < 16;
		MapMemory memory = view.layer() == TileKey.SURFACE ? level.memory() : null;
		boolean drawsNear = this.drawsNear(view, lod);
		int nearU = drawsNear ? (int) Math.floor(view.centerU() / Projection.TILE_UNITS) : Integer.MIN_VALUE;
		int nearV = drawsNear ? (int) Math.floor(view.centerV() / Projection.TILE_UNITS) : Integer.MIN_VALUE;
		// The memory's level can change with the zoom while the real tiles' doesn't.
		int memoryLod = memory == null ? -1 : memory.lod(view, lod);
		MaskInputs inputs = new MaskInputs(level.coverageVersion(), memory == null ? 0 : memory.version(), view.layer(), lod, memoryLod, originU, originV, texel, rounded, scaleSteps, fraction, fast, nearU, nearV);
		boolean sameLevel = this.maskLevel.get() == level;
		this.takeFinished();
		if (sameLevel && inputs.equals(this.maskInputs)) {
			return;
		}
		boolean later = sameLevel && this.maskInputs != null && inputs.sameGeometry(this.maskInputs);
		if (this.finishing != null) {
			if (later) {
				// The next starts once this one is in.
				return;
			}
			// The view moved: what's being finished wouldn't line up, and its arrays are this build's.
			this.finishing.join();
			this.finishing = null;
		}
		this.maskInputs = inputs;
		this.maskLevel = new WeakReference<>(level);
		double units = (double) Projection.TILE_UNITS * (1 << lod);
		double cellUnits = units / Projection.COVERAGE_CELLS;
		int lastTu = Integer.MIN_VALUE;
		int lastTv = Integer.MIN_VALUE;
		MapTile tile = null;
		MapTile above = null;
		TileKey aboveKey = null;
		// The finer tiles under the last point read, one level down and the next, as drawTiles stands them in.
		MapTile[] finer = new MapTile[FINER_STAND_INS];
		int[] lastFinerTu = new int[FINER_STAND_INS];
		int[] lastFinerTv = new int[FINER_STAND_INS];
		Arrays.fill(lastFinerTu, Integer.MIN_VALUE);
		Arrays.fill(lastFinerTv, Integer.MIN_VALUE);
		int lastNearTu = Integer.MIN_VALUE;
		int lastNearTv = Integer.MIN_VALUE;
		MapTile nearTile = null;
		// Zoomed far out a texel is hundreds of blocks: anything mapped in it makes it known, so the
		// fog doesn't close over a trail or a small map that fills only part of it.
		boolean coarse = view.layer() == TileKey.SURFACE && texel >= COARSE_TEXEL;
		double[] spots = coarse ? COARSE_SPOTS : MIDDLE;
		for (int j = 0; j < MASK; j++) {
			for (int i = 0; i < MASK; i++) {
				float most = 0;
				for (int spot = 0; spot < spots.length && most < 1; spot += 2) {
					double u = originU + (i + spots[spot]) * texel;
					double v = originV + (j + spots[spot + 1]) * texel;
					int tv = (int) Math.floor(v / units);
					int cellY = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - tv * units) / cellUnits));
					int tu = (int) Math.floor(u / units);
					if (tu != lastTu || tv != lastTv) {
						tile = level.peek(new TileKey(view.layer(), tu, tv, lod));
						lastTu = tu;
						lastTv = tv;
						above = null;
						aboveKey = null;
						if (tile == null || !tile.hasCoverage()) {
							// As drawTiles stands a coarser tile in for one that hasn't loaded: it knows as much.
							TileKey up = new TileKey(view.layer(), tu, tv, lod);
							for (int l = lod + 1; l <= TileKey.MAX_LOD && above == null; l++) {
								up = up.parent();
								MapTile candidate = level.peek(up);
								if (candidate != null && candidate.hasCoverage()) {
									above = candidate;
									aboveKey = up;
								}
							}
						}
					}
					float known = 0;
					if (tile != null && tile.hasCoverage()) {
						int cellX = Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - tu * units) / cellUnits));
						known = tile.coverage(cellX, cellY);
					} else if (above != null) {
						double aboveSpan = aboveKey.units();
						double aboveCell = aboveSpan / Projection.COVERAGE_CELLS;
						int cellX = Math.max(0, Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - aboveKey.tileU() * aboveSpan) / aboveCell)));
						int aboveY = Math.max(0, Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - aboveKey.tileV() * aboveSpan) / aboveCell)));
						known = above.coverage(cellX, aboveY);
					} else {
						// Or a finer tile under this spot, as drawTiles shrinks it in: the next level down
						// if it's in memory, else the one below that.
						for (int down = 1; down <= FINER_STAND_INS && lod - down >= 0; down++) {
							double span = units / (1 << down);
							int finerTu = (int) Math.floor(u / span);
							int finerTv = (int) Math.floor(v / span);
							if (finerTu != lastFinerTu[down - 1] || finerTv != lastFinerTv[down - 1]) {
								finer[down - 1] = level.peek(new TileKey(view.layer(), finerTu, finerTv, lod - down));
								lastFinerTu[down - 1] = finerTu;
								lastFinerTv[down - 1] = finerTv;
							}
							MapTile under = finer[down - 1];
							if (under != null) {
								if (under.hasCoverage()) {
									double finerCell = span / Projection.COVERAGE_CELLS;
									int cellX = Math.max(0, Math.min(Projection.COVERAGE_CELLS - 1, (int) ((u - finerTu * span) / finerCell)));
									int finerY = Math.max(0, Math.min(Projection.COVERAGE_CELLS - 1, (int) ((v - finerTv * span) / finerCell)));
									known = under.coverage(cellX, finerY);
								}
								break;
							}
						}
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
					most = Math.max(most, known);
				}
				if (coarse && most > 0) {
					most = 1;
				}
				this.maskPixels[j * MASK + i] = 0xFF000000 | Math.round(most * 255);
			}
		}
		boolean surface = view.layer() == TileKey.SURFACE;
		if (surface) {
			if (memory != null) {
				// The whole mask, not just the view: the fog reads past the view's edge, and lies low
				// over the remembered map there too.
				memory.ask(memoryLod, originU, originV, originU + MASK * texel, originV + MASK * texel);
				for (int j = 0; j < MASK; j++) {
					double v = originV + (j + 0.5) * texel;
					for (int i = 0; i < MASK; i++) {
						float known = memory.known(memoryLod, originU + (i + 0.5) * texel, v);
						// A remembered tile not read yet is taken as all known: read a moment later, it
						// rarely has much less, while taking it for unexplored stands the fog up to full
						// height until it lands.
						this.remembered[j * MASK + i] = Float.isNaN(known) ? 1 : known;
					}
				}
			} else {
				Arrays.fill(this.remembered, 0);
			}
		}
		this.finish(() -> this.finishBuild(inputs, rounded, surface), later);
	}

	/**
	 * The rest of a mask's build, from the known share read into the mask's pixels (and the
	 * remembered map into {@link #remembered}) to everything the textures take. Doesn't touch the
	 * map or the textures, so it can run on {@link #MASK_WORKER}.
	 */
	private void finishBuild(MaskInputs inputs, boolean rounded, boolean surface) {
		double texel = inputs.texel();
		boolean fast = inputs.fast();
		if (rounded) {
			this.roundOff(Math.max(1, (int) Math.round(10 / texel)));
		}
		if (surface && texel >= COARSE_TEXEL) {
			this.keepOffMapped(COARSE_GROW);
		}
		this.writeSoft(texel, !surface, fast);
		// The fog at full height (alpha 255), but over a remembered map, whose share alpha is 255
		// less, so the fog lies low there.
		this.markReach(fogReach(texel, !surface, fast, inputs.scaleSteps()));
		if (surface) {
			for (int k = 0; k < MASK * MASK; k++) {
				this.maskPixels[k] = this.maskPixels[k] & 0xFFFFFF | 255 - Math.round(this.remembered[k] * 255) << 24;
			}
		}
		this.writeDetail();
	}

	/**
	 * Runs {@code work}, a build's part that doesn't touch the textures, then uploads
	 * what it made: on the worker if {@code later}, the upload then on a later frame (see
	 * {@link #takeFinished}), else here and now.
	 */
	private void finish(Runnable work, boolean later) {
		if (!later) {
			work.run();
			this.uploadMask();
			return;
		}
		this.finishFailed = false;
		this.finishing = CompletableFuture.runAsync(() -> {
			try {
				work.run();
			} catch (Throwable t) {
				this.finishFailed = true;
				if (!finishFailureLogged) {
					finishFailureLogged = true;
					Diorama.LOGGER.error("Building the fog mask failed", t);
				}
			}
		}, MASK_WORKER);
	}

	/** Uploads the mask the worker finished, if it has. Render thread. */
	private void takeFinished() {
		if (this.finishing == null || !this.finishing.isDone()) {
			return;
		}
		this.finishing = null;
		if (this.finishFailed) {
			// Half made: the next frame builds it all again, here.
			this.maskInputs = null;
			return;
		}
		this.uploadMask();
	}

	/** The mask's pixels and the detail texture to the graphics card. Render thread. */
	private void uploadMask() {
		if (this.maskTexture != null) {
			this.maskTexture.write(0, 0, 0, MASK, this.maskPixels);
		}
		if (this.detailTexture != null) {
			this.detailTexture.write(0, 0, 0, MASK, this.detailPixels);
		}
		this.fogSerial++;
	}

	/** How much of a remembered map is under each mask texel, 0 to 1. */
	private final float[] remembered = new float[MASK * MASK];

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
				int k = j * MASK + i;
				int known = this.maskPixels[k] & 0xFF;
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

	/** One line of the mask as {@link #keepOffMapped} grows it. */
	private final int[] grownLine = new int[MASK];

	/**
	 * Grows the known area by {@code radius} texels each way. The fog's soft edge is never narrower
	 * than a mask texel, and reaches about two into the known side: nothing close up, but zoomed far
	 * out those two texels are hundreds of blocks, the mapped ground's whole rim, or all of a small
	 * map. Grown first, the soft edge lies outside what's mapped, over ground the map has nothing
	 * for either way.
	 */
	private void keepOffMapped(int radius) {
		int[] known = this.grownLine;
		for (int pass = 0; pass < 2; pass++) {
			boolean horizontal = pass == 0;
			for (int line = 0; line < MASK; line++) {
				for (int k = 0; k < MASK; k++) {
					int most = 0;
					for (int d = Math.max(0, k - radius); d <= Math.min(MASK - 1, k + radius); d++) {
						most = Math.max(most, this.maskPixels[index(line, d, horizontal)] & 0xFF);
					}
					known[k] = most;
				}
				for (int k = 0; k < MASK; k++) {
					int at = index(line, k, horizontal);
					this.maskPixels[at] = this.maskPixels[at] & 0xFFFFFF00 | known[k];
				}
			}
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

	/** The soft known share ({@link #writeSoft}) into the detail texture's blue, a texel a mask texel. */
	private void writeDetail() {
		for (int k = 0; k < MASK * MASK; k++) {
			this.detailPixels[k] = (this.softBytes[k] & 0xFF) << 16;
		}
	}

	private final float[] knownShare = new float[MASK * MASK];
	private final int[] notFullCounts = new int[(MASK + 1) * (MASK + 1)];
	private final int[] notEmptyCounts = new int[(MASK + 1) * (MASK + 1)];

	/**
	 * The known share as fog.fsh's knownSoft reads it, at each texel's middle, for the detail
	 * texture's blue: one blur of nine taps (the middle, four at the radius, four on the diagonals at
	 * 1.9 times it, each read filtered between texels and clamped at the mask's edge) or, for the
	 * wide surface fog, half of that and half of the same at 2.4 times the radius. Filtered between
	 * texels the shader then reads the same as it would have worked out, to well under a step of the
	 * mask's bytes. Texels whose whole blur reach is all known or all unknown are just that.
	 */
	private void writeSoft(double texel, boolean cave, boolean fast) {
		double units = cave ? 2.5 : 7;
		boolean wide = units >= 5 && !fast;
		double near = Math.max(units / texel, 1);
		double far = Math.max(units * 2.4 / texel, 1);
		int reach = (int) Math.ceil(1.9 * (wide ? far : near)) + 2;
		int row = MASK + 1;
		for (int j = 0; j < MASK; j++) {
			int lineNotFull = 0;
			int lineNotEmpty = 0;
			for (int i = 0; i < MASK; i++) {
				int k = j * MASK + i;
				int known = this.maskPixels[k] & 0xFF;
				this.knownShare[k] = known / 255.0F;
				lineNotFull += known == 0xFF ? 0 : 1;
				lineNotEmpty += known == 0 ? 0 : 1;
				this.notFullCounts[(j + 1) * row + i + 1] = this.notFullCounts[j * row + i + 1] + lineNotFull;
				this.notEmptyCounts[(j + 1) * row + i + 1] = this.notEmptyCounts[j * row + i + 1] + lineNotEmpty;
			}
		}
		for (int j = 0; j < MASK; j++) {
			int t = Math.max(0, j - reach);
			int b = Math.min(MASK - 1, j + reach) + 1;
			for (int i = 0; i < MASK; i++) {
				int l = Math.max(0, i - reach);
				int r = Math.min(MASK - 1, i + reach) + 1;
				float soft;
				if (this.notFullCounts[b * row + r] - this.notFullCounts[t * row + r] - this.notFullCounts[b * row + l] + this.notFullCounts[t * row + l] == 0) {
					soft = 1;
				} else if (this.notEmptyCounts[b * row + r] - this.notEmptyCounts[t * row + r] - this.notEmptyCounts[b * row + l] + this.notEmptyCounts[t * row + l] == 0) {
					soft = 0;
				} else {
					soft = this.blurAt(i + 0.5, j + 0.5, near);
					if (wide) {
						soft = 0.5F * soft + 0.5F * this.blurAt(i + 0.5, j + 0.5, far);
					}
				}
				this.softBytes[j * MASK + i] = (byte) Math.round(soft * 255);
			}
		}
	}

	/** The known share blurred over {@code r} texels around (x, y), in texels, so chunk-shaped edges melt away. */
	private float blurAt(double x, double y, double r) {
		double d = r * 1.9;
		return this.knownAt(x, y) * 0.2F
			+ (this.knownAt(x + r, y) + this.knownAt(x - r, y) + this.knownAt(x, y + r) + this.knownAt(x, y - r)) * 0.1F
			+ (this.knownAt(x + d, y + d) + this.knownAt(x - d, y + d) + this.knownAt(x + d, y - d) + this.knownAt(x - d, y - d)) * 0.1F;
	}

	/** The known share at (x, y) in texels, filtered between texel middles and clamped at the edge, as the graphics card reads it. */
	private float knownAt(double x, double y) {
		double fx = x - 0.5;
		double fy = y - 0.5;
		int i0 = (int) Math.floor(fx);
		int j0 = (int) Math.floor(fy);
		float tx = (float) (fx - i0);
		float ty = (float) (fy - j0);
		int i1 = Math.min(MASK - 1, Math.max(0, i0 + 1));
		int j1 = Math.min(MASK - 1, Math.max(0, j0 + 1));
		i0 = Math.min(MASK - 1, Math.max(0, i0));
		j0 = Math.min(MASK - 1, Math.max(0, j0));
		float top = this.knownShare[j0 * MASK + i0] + (this.knownShare[j0 * MASK + i1] - this.knownShare[j0 * MASK + i0]) * tx;
		float bottom = this.knownShare[j1 * MASK + i0] + (this.knownShare[j1 * MASK + i1] - this.knownShare[j1 * MASK + i0]) * tx;
		return top + (bottom - top) * ty;
	}

	private static int index(int line, int k, boolean horizontal) {
		return horizontal ? line * MASK + k : k * MASK + line;
	}

	public void close() {
		if (this.finishing != null) {
			// Its arrays are the next build's.
			this.finishing.join();
			this.finishing = null;
		}
		if (this.maskTexture != null) {
			this.maskTexture.close();
			this.maskTexture = null;
		}
		if (this.detailTexture != null) {
			this.detailTexture.close();
			this.detailTexture = null;
		}
		if (this.fogLayer != null) {
			this.fogLayer.close();
			this.fogLayer = null;
		}
		this.layerKey = null;
		this.maskInputs = null;
	}
}
