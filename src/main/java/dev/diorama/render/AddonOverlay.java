package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.Projection;
import dev.diorama.addon.AddonLayers;
import dev.diorama.api.DimensionId;
import dev.diorama.api.LayerContent;
import dev.diorama.api.MapLayer;
import dev.diorama.api.MapLine;
import dev.diorama.api.Marker;
import dev.diorama.api.ResourceId;
import dev.diorama.render.gpu.MapCanvas;
import dev.diorama.render.gpu.Sampling;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * Addons' layers of markers and lines ({@link AddonLayers}) on one map view, the minimap's or the
 * full map's: one of these per view, kept with it. Markers are placed the way the radar places a
 * mob, from where they are in the world ({@link Projection}) and turned with the view, and drawn
 * from one texture ({@link MarkerAtlas}) in one draw, in their order. Lines are cut into quads the
 * way the player's drawings are ({@link dev.diorama.drawing.DrawingOverlay}): a dark rim, then the
 * colour, round at each point, and drawn in one draw too.
 *
 * <p>Where things are on screen is worked out in doubles, and every line is cut to the view (a
 * little outside it) before any corner becomes a float, so a line from one end of the world to the
 * other gives corners near the view like any other. The view handed in is the one the player
 * sees, which the scissor cuts to: a turned square minimap's, not the bigger ground under it, so
 * only what can be seen counts against a map's budget. In a rectangular view, a marker any of
 * which is in view is drawn and the scissor cuts the rest. On a round minimap nothing cuts to the
 * circle: lines are cut to it here, and a marker is drawn only if all of what it draws is inside.
 *
 * <p>What it draws a frame goes into arrays kept between frames, which the GUI holds on to until
 * the frame is drawn instead of copying ({@link MapCanvas#rectsKept}): so a crowd of markers
 * allocates nothing a frame beyond its labels, which the game's text drawing allocates for. The
 * arrays are shared by every view, {@link #RING} sets for each kind taken in turn, so up to that
 * many draws of a frame can't change what an earlier one handed the GUI. They never grow past a
 * map's budget ({@link MapLayer#MAX_DRAWN_MARKERS}, {@link MapLayer#MAX_DRAWN_LINE_PIECES}), are
 * cut down once they've been far bigger than needed for a while, and are let go when the last
 * layer closes. With no layer open, nothing happens at all.
 */
public final class AddonOverlay {
	/** A marker's dark outline, a pixel each side of its dot, as the radar's mob dots have. */
	private static final int OUTLINE = 0xE0101418;
	private static final int PLATE = 0x90000000;
	/** The grid labels are kept apart on, in GUI pixels: coarse enough to be cheap, fine enough that near labels both show. */
	private static final int LABEL_CELL = 4;
	/** The rim either side of a line's colour, in GUI pixels, as the player's drawings have. */
	private static final float RIM = 1;
	/** The corners of a line's joint: an octagon round each point, turning the way the GUI's corners do. */
	private static final float[] OCTAGON = new float[16];
	/**
	 * How many draws of each kind may still be waiting for the screen: the minimap and the full
	 * map draw once a frame each, and addons' views twice at most
	 * ({@link dev.diorama.api.MapView#MAX_DRAWS_PER_FRAME}). A set of arrays, and
	 * an icon's tile in {@link MarkerAtlas}, is used again only this many draws later.
	 */
	public static final int RING = 4;
	/** Quads a set of arrays starts with. */
	private static final int FIRST = 64;
	/** Draws in a row a set's arrays can be four times what they hold before they're cut down. */
	private static final int SHRINK_AFTER = 60;

	static {
		for (int i = 0; i < 8; i++) {
			double a = Math.PI * 2 * (i + 0.5) / 8;
			OCTAGON[i * 2] = (float) Math.cos(a);
			OCTAGON[i * 2 + 1] = (float) -Math.sin(a);
		}
	}

	/** For the game tests: told where each marker was put, frame by frame. Null outside them. */
	public static @Nullable Probe probe;

	/** What a test hears about each frame's markers and lines. */
	public interface Probe {
		/** A frame of a view starts: its lines are drawn next, then its markers. */
		void frame(boolean fullscreen);

		/** A view's markers are about to be placed. */
		void view(boolean fullscreen, MapViewport.View view);

		/** One marker drawn at (x, y) on screen, its index in its layer's content, and its icon's tile or -1 for a dot. */
		void marker(boolean fullscreen, AddonLayers.Layer layer, int index, float x, float y, int tile);

		/** One line that gave quads, its index in its layer's content. */
		void line(boolean fullscreen, AddonLayers.Layer layer, int index);

		/** What one draw handed the GUI: {@code quads} quads, four corners of x, y, z each, and a colour each. */
		void drawn(boolean fullscreen, boolean lines, float[] xyz, int[] colors, int quads);
	}

	/** The markers' and lines' sets of arrays, shared by every view, and whose turn is next. */
	private static final Quads[] MARKERS = new Quads[RING];
	private static final Quads[] LINES = new Quads[RING];
	private static int markerTurn;
	private static int lineTurn;
	/** Whether any set of arrays is held, to let go once no layer is open. */
	private static boolean holding;
	private static boolean markersOverSaid;
	private static boolean linesOverSaid;
	/** The labels to draw after the markers: each marker, and where it went and how far it reaches. */
	private static Marker[] labels = new Marker[16];
	private static float[] labelAt = new float[48];
	/** A line's points on screen, kept between lines and frames: never more than a line's {@link MapLine#MAX_POINTS}. */
	private static double[] sx = new double[64];
	private static double[] sy = new double[64];
	/** The polygon being cut into quads. */
	private static final float[] POLYGON = new float[16];
	/** The part of a segment {@link #clip} kept, from 0 to 1 along it. */
	private double t0;
	private double t1;

	/** Where this frame's labels are, in cells of {@link #LABEL_CELL} GUI pixels over the view: a cell holds the frame's stamp once taken. */
	private int[] taken = new int[0];
	private int takenX;
	private int takenY;
	private int takenColumns;
	private int takenRows;
	private int stamp;
	private @Nullable Circle circle;
	private float z;
	// The view being drawn: what screen(u, v) needs, worked out once a draw.
	private double centerU;
	private double centerV;
	private double zoom;
	private double cos;
	private double sin;
	private double middleX;
	private double middleY;
	/** The last dimension asked for by its text, and its ID. */
	private @Nullable String dimensionText;
	private @Nullable DimensionId dimension;

	/** A set of quads' arrays, kept between frames, never more quads than {@link #budget}. Lines have no texture, so no UVs. */
	private static final class Quads {
		final int budget;
		float[] xyz;
		float @Nullable [] uvs;
		int[] colors;
		int count;
		/** Draws in a row it has held under a quarter of its room. */
		int idle;

		Quads(boolean textured, int budget) {
			this.budget = budget;
			this.xyz = new float[FIRST * 12];
			this.uvs = textured ? new float[FIRST * 8] : null;
			this.colors = new int[FIRST];
		}

		/** Makes room for {@code more} quads, unless that passes the budget: then false, and nothing changes. */
		boolean room(int more) {
			int need = this.count + more;
			if (need > this.budget) {
				return false;
			}
			if (need > this.colors.length) {
				int n = Math.min(this.budget, Math.max(this.colors.length * 2, need));
				this.xyz = Arrays.copyOf(this.xyz, n * 12);
				if (this.uvs != null) {
					this.uvs = Arrays.copyOf(this.uvs, n * 8);
				}
				this.colors = Arrays.copyOf(this.colors, n);
			}
			return true;
		}

		/**
		 * After a draw: once it has held under a quarter of its room for {@link #SHRINK_AFTER}
		 * draws, the arrays are cut to twice what it holds. New arrays: the GUI may still hold the
		 * old ones this frame.
		 */
		void settle() {
			if (this.colors.length <= FIRST || this.count * 4 >= this.colors.length) {
				this.idle = 0;
				return;
			}
			if (++this.idle < SHRINK_AFTER) {
				return;
			}
			int n = Math.max(FIRST, this.count * 2);
			this.xyz = new float[n * 12];
			if (this.uvs != null) {
				this.uvs = new float[n * 8];
			}
			this.colors = new int[n];
			this.idle = 0;
		}

		long bytes() {
			return this.xyz.length * 4L + (this.uvs == null ? 0 : this.uvs.length * 4L) + this.colors.length * 4L;
		}
	}

	/** The next set of arrays in a ring, made the first time, emptied. */
	private static Quads next(Quads[] ring, int turn, boolean textured, int budget) {
		Quads quads = ring[turn];
		if (quads == null) {
			quads = new Quads(textured, budget);
			ring[turn] = quads;
			holding = true;
		}
		quads.count = 0;
		return quads;
	}

	/** Bytes of arrays held for addons' layers now, for the tests: the sets, labels and a line's points. */
	public static long heldBytes() {
		long bytes = 0;
		for (Quads quads : MARKERS) {
			bytes += quads == null ? 0 : quads.bytes();
		}
		for (Quads quads : LINES) {
			bytes += quads == null ? 0 : quads.bytes();
		}
		return bytes + labels.length * 4L + labelAt.length * 4L + sx.length * 16L;
	}

	/** The dimension with this ID, as the API names it; the last one is kept, so asking every frame costs nothing. */
	public @Nullable DimensionId dimension(String id) {
		if (!id.equals(this.dimensionText)) {
			this.dimensionText = id;
			try {
				this.dimension = DimensionId.of(id);
			} catch (IllegalArgumentException e) {
				this.dimension = null;
			}
		}
		return this.dimension;
	}

	/**
	 * Whether any layer is open: the maps ask before working out anything to draw. With none, the
	 * arrays kept for drawing them are let go.
	 */
	public static boolean any() {
		if (!AddonLayers.open().isEmpty()) {
			return true;
		}
		if (holding) {
			release();
		}
		return false;
	}

	private static void release() {
		Arrays.fill(MARKERS, null);
		Arrays.fill(LINES, null);
		labels = new Marker[16];
		labelAt = new float[48];
		sx = new double[64];
		sy = new double[64];
		holding = false;
	}

	private void place(MapViewport.View view, GuiGraphicsExtractor graphics) {
		this.centerU = view.centerU();
		this.centerV = view.centerV();
		this.zoom = view.zoom();
		this.cos = view.turn() == 0 ? 1 : Math.cos(view.turn());
		this.sin = view.turn() == 0 ? 0 : Math.sin(view.turn());
		this.middleX = view.x() + view.width() / 2.0;
		this.middleY = view.y() + view.height() / 2.0;
		this.circle = view.round() ? Circle.in(view) : null;
		//? if <1.21.6 {
		/*// The GUI depth-tests before 1.21.6: draw at the depth the rest of the view is drawn at.
		this.z = graphics.pose().last().pose().m32();
		*///?} else {
		this.z = 0;
		//?}
	}

	// Where a point of the map is on screen, turned with the view: View.screenX(u, v) and screenY(u, v).
	private double screenX(double u, double v) {
		return this.middleX + ((u - this.centerU) * this.cos - (v - this.centerV) * this.sin) * this.zoom;
	}

	private double screenY(double u, double v) {
		return this.middleY + ((u - this.centerU) * this.sin + (v - this.centerV) * this.cos) * this.zoom;
	}

	/**
	 * The layers' markers in {@code dimension}, with their labels on the full map, up to
	 * {@link MapLayer#MAX_DRAWN_MARKERS}. Call it inside no scissor of its own: it cuts to the view.
	 */
	public void drawMarkers(GuiGraphicsExtractor graphics, MapViewport.View view, @Nullable DimensionId dimension, boolean fullscreen) {
		List<AddonLayers.Layer> layers = AddonLayers.open();
		if (layers.isEmpty() || dimension == null) {
			return;
		}
		AddonLayers.sync();
		Probe probe = AddonOverlay.probe;
		if (probe != null) {
			probe.view(fullscreen, view);
		}
		this.place(view, graphics);
		Quads quads = next(MARKERS, markerTurn, true, MapLayer.MAX_DRAWN_MARKERS * 2);
		markerTurn = (markerTurn + 1) % RING;
		MarkerAtlas.startDraw();
		int drawn = 0;
		int labelCount = 0;
		double gui = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
		double left = view.x();
		double top = view.y();
		double right = view.x() + view.width();
		double bottom = view.y() + view.height();
		Circle circle = this.circle;
		all:
		for (int l = 0; l < layers.size(); l++) {
			AddonLayers.Layer layer = layers.get(l);
			if (!layer.shown()) {
				continue;
			}
			List<Marker> markers = layer.content().markers();
			for (int i = 0; i < markers.size(); i++) {
				Marker marker = markers.get(i);
				DimensionId in = marker.dimension();
				if (in != dimension && !in.equals(dimension)) {
					continue;
				}
				double u = Projection.u(marker.x(), marker.z());
				double v = Projection.v(marker.x(), marker.y(), marker.z());
				double x = this.screenX(u, v);
				double y = this.screenY(u, v);
				Optional<ResourceId> icon = marker.icon();
				// How far it reaches from its middle: a dot and its outline, or the icon's whole tile,
				// which at a small size is bigger than the size asked for.
				double dotHalf = marker.size() / 2.0 + 1;
				double iconHalf = icon.isPresent() ? WaypointIcons.TILE * Radar.texel(marker.size(), WaypointIcons.TILE) / 2.0 : dotHalf;
				boolean iconShows = icon.isPresent() && shows(x, y, iconHalf, left, top, right, bottom, circle);
				boolean dotShows = shows(x, y, dotHalf, left, top, right, bottom, circle);
				if (!iconShows && !dotShows) {
					continue;
				}
				// A marker whose icon wouldn't show here isn't drawn as a dot instead, unless its icon is
				// already known to be missing, as then it is a dot wherever it is. Only what is known is
				// read: a marker that isn't drawn never costs a lookup.
				if (icon.isPresent() && !iconShows && !MarkerAtlas.missing(icon.get())) {
					continue;
				}
				if (drawn == MapLayer.MAX_DRAWN_MARKERS) {
					if (!markersOverSaid) {
						markersOverSaid = true;
						Diorama.LOGGER.warn("Addons' layers have more than {} markers in a map's view; from {}'s layer {} on, the rest aren't drawn",
							MapLayer.MAX_DRAWN_MARKERS, layer.modId(), layer.layerName());
					}
					break all;
				}
				// Only an icon that is drawn takes a tile.
				int tile = iconShows ? MarkerAtlas.tile(icon.get()) : MarkerAtlas.NONE;
				if (tile == MarkerAtlas.NONE && !dotShows) {
					continue;
				}
				double half = tile != MarkerAtlas.NONE ? iconHalf : dotHalf;
				float fx = (float) x;
				float fy = (float) y;
				this.marker(quads, marker, tile, fx, fy, (float) half, gui);
				drawn++;
				if (probe != null) {
					probe.marker(fullscreen, layer, i, fx, fy, tile);
				}
				if (fullscreen && !marker.label().isEmpty()) {
					if (labelCount == labels.length) {
						labels = Arrays.copyOf(labels, labelCount * 2);
						labelAt = Arrays.copyOf(labelAt, labelCount * 6);
					}
					labels[labelCount] = marker;
					labelAt[labelCount * 3] = fx;
					labelAt[labelCount * 3 + 1] = fy;
					labelAt[labelCount * 3 + 2] = (float) half;
					labelCount++;
				}
			}
		}
		if (probe != null) {
			probe.drawn(fullscreen, false, quads.xyz, quads.colors, quads.count);
		}
		if (quads.count > 0) {
			graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
			MapCanvas.of(graphics).rectsKept(MarkerAtlas.texture(), Sampling.CLAMP_CRISP, quads.xyz, quads.uvs, quads.colors, quads.count);
			if (labelCount > 0) {
				Font font = Minecraft.getInstance().font;
				this.clearTaken(view);
				for (int k = 0; k < labelCount; k++) {
					this.label(graphics, font, labels[k], labelAt[k * 3], labelAt[k * 3 + 1], labelAt[k * 3 + 2]);
					labels[k] = null;
				}
			}
			graphics.disableScissor();
		}
		quads.settle();
	}

	/**
	 * Whether a square reaching {@code half} from (x, y) is drawn: in a rectangular view if any of
	 * it is in the view, as the scissor cuts the rest; on a round minimap only if all of it is
	 * inside the circle, as nothing cuts to the circle.
	 */
	private static boolean shows(double x, double y, double half, double left, double top, double right, double bottom, @Nullable Circle circle) {
		if (circle == null) {
			return x + half > left && x - half < right && y + half > top && y - half < bottom;
		}
		if (x - half < left || x + half > right || y - half < top || y + half > bottom) {
			return false;
		}
		float x0 = (float) (x - half);
		float y0 = (float) (y - half);
		float x1 = (float) (x + half);
		float y1 = (float) (y + half);
		return circle.contains(x0, y0) && circle.contains(x1, y0) && circle.contains(x0, y1) && circle.contains(x1, y1);
	}

	/** One marker's quads, reaching {@code half} from (x, y): its icon, or its dot over a dark outline. */
	private void marker(Quads quads, Marker marker, int tile, float x, float y, float half, double gui) {
		quads.room(2);
		if (tile != MarkerAtlas.NONE) {
			// Each of the icon's pixels a whole number of screen pixels, as the waypoint icons are.
			float u0 = MarkerAtlas.tileX(tile) / (float) MarkerAtlas.SIZE;
			float v0 = MarkerAtlas.tileY(tile) / (float) MarkerAtlas.SIZE;
			float side = WaypointIcons.TILE / (float) MarkerAtlas.SIZE;
			this.rect(quads, x - half, y - half, x + half, y + half, u0, v0, u0 + side, v0 + side, 0xFFFFFFFF);
			return;
		}
		float size = marker.size();
		this.dot(quads, x, y, size + 2, gui, OUTLINE);
		this.dot(quads, x, y, size, gui, 0xFF000000 | marker.rgb());
	}

	private void dot(Quads quads, float x, float y, float size, double gui, int argb) {
		float screen = (float) (size * gui);
		float u0 = MarkerAtlas.dotX(screen) / (float) MarkerAtlas.SIZE;
		float v0 = MarkerAtlas.dotY() / (float) MarkerAtlas.SIZE;
		float side = MarkerAtlas.dotSize(screen) / (float) MarkerAtlas.SIZE;
		float half = size / 2;
		this.rect(quads, x - half, y - half, x + half, y + half, u0, v0, u0 + side, v0 + side, argb);
	}

	/** An upright rectangle, its corners in the order MapCanvas.rect gives them. Room for it is already made. */
	private void rect(Quads quads, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
		int q = quads.count++;
		float[] xyz = quads.xyz;
		float[] uvs = quads.uvs;
		int p = q * 12;
		xyz[p] = x0;
		xyz[p + 1] = y0;
		xyz[p + 2] = this.z;
		xyz[p + 3] = x0;
		xyz[p + 4] = y1;
		xyz[p + 5] = this.z;
		xyz[p + 6] = x1;
		xyz[p + 7] = y1;
		xyz[p + 8] = this.z;
		xyz[p + 9] = x1;
		xyz[p + 10] = y0;
		xyz[p + 11] = this.z;
		int t = q * 8;
		uvs[t] = u0;
		uvs[t + 1] = v0;
		uvs[t + 2] = u0;
		uvs[t + 3] = v1;
		uvs[t + 4] = u1;
		uvs[t + 5] = v1;
		uvs[t + 6] = u1;
		uvs[t + 7] = v0;
		quads.colors[q] = argb;
	}

	/**
	 * A marker's label under it, white on the dark plate waypoint names have on the map, unless
	 * its plate would overlap one already drawn this frame: where markers crowd, the first label
	 * shows and the others wait for the player to zoom in. That also keeps a crowd from costing a
	 * label each, as the game's text drawing allocates for every one.
	 */
	private void label(GuiGraphicsExtractor graphics, Font font, Marker marker, float x, float y, float half) {
		String text = marker.label();
		int w = font.width(text);
		int left = Math.round(x - w / 2.0F);
		int top = Math.round(y + half + 2);
		if (!this.take(left - 2, top - 1, left + w + 2, top + 9)) {
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(left, top);
		graphics.fill(-2, -1, w + 2, 9, PLATE);
		graphics.text(font, text, 0, 0, 0xFFFFFFFF, false);
		graphics.pose().popMatrix();
	}

	/** Forgets every label's place, for a new frame: the grid is kept, and only its stamp moves on. */
	private void clearTaken(MapViewport.View view) {
		this.takenX = view.x();
		this.takenY = view.y();
		this.takenColumns = view.width() / LABEL_CELL + 2;
		int rows = view.height() / LABEL_CELL + 2;
		if (this.taken.length < this.takenColumns * rows) {
			this.taken = new int[this.takenColumns * rows];
		}
		this.takenRows = rows;
		this.stamp++;
		if (this.stamp == 0) {
			java.util.Arrays.fill(this.taken, 0);
			this.stamp = 1;
		}
	}

	/** Whether the box x0..x1, y0..y1 is clear of the labels drawn so far this frame, and if so takes it. */
	private boolean take(int x0, int y0, int x1, int y1) {
		int c0 = Math.max(0, Math.floorDiv(x0 - this.takenX, LABEL_CELL));
		int c1 = Math.min(this.takenColumns - 1, Math.floorDiv(x1 - 1 - this.takenX, LABEL_CELL));
		int r0 = Math.max(0, Math.floorDiv(y0 - this.takenY, LABEL_CELL));
		int r1 = Math.min(this.takenRows - 1, Math.floorDiv(y1 - 1 - this.takenY, LABEL_CELL));
		if (c0 > c1 || r0 > r1) {
			// Wholly outside the view: the scissor would cut it all anyway.
			return false;
		}
		for (int r = r0; r <= r1; r++) {
			for (int c = c0; c <= c1; c++) {
				if (this.taken[r * this.takenColumns + c] == this.stamp) {
					return false;
				}
			}
		}
		for (int r = r0; r <= r1; r++) {
			java.util.Arrays.fill(this.taken, r * this.takenColumns + c0, r * this.takenColumns + c1 + 1, this.stamp);
		}
		return true;
	}

	/**
	 * The layers' lines in {@code dimension}, cut to the view, a round one's circle included, up to
	 * {@link MapLayer#MAX_DRAWN_LINE_PIECES} quads. {@code view} is what the player sees, the
	 * rectangle the scissor cuts to: on a turned square minimap that's the minimap itself, not the
	 * ground view drawn under it, so lines out of sight take none of the budget.
	 */
	public void drawLines(GuiGraphicsExtractor graphics, MapViewport.View view, @Nullable DimensionId dimension, boolean fullscreen) {
		List<AddonLayers.Layer> layers = AddonLayers.open();
		if (layers.isEmpty() || dimension == null) {
			return;
		}
		AddonLayers.sync();
		Probe probe = AddonOverlay.probe;
		if (probe != null) {
			probe.frame(fullscreen);
		}
		this.place(view, graphics);
		Quads quads = next(LINES, lineTurn, false, MapLayer.MAX_DRAWN_LINE_PIECES);
		lineTurn = (lineTurn + 1) % RING;
		all:
		for (int l = 0; l < layers.size(); l++) {
			AddonLayers.Layer layer = layers.get(l);
			if (!layer.shown()) {
				continue;
			}
			LayerContent content = layer.content();
			List<MapLine> lines = content.lines();
			for (int i = 0; i < lines.size(); i++) {
				MapLine line = lines.get(i);
				DimensionId in = line.dimension();
				if (in != dimension && !in.equals(dimension)) {
					continue;
				}
				int before = quads.count;
				if (!this.line(quads, view, line)) {
					// Past the budget: none of this line, and nothing after it.
					quads.count = before;
					if (!linesOverSaid) {
						linesOverSaid = true;
						Diorama.LOGGER.warn("Addons' layers have more lines in a map's view than it draws a frame ({} pieces); from {}'s layer {} on, the rest aren't drawn",
							MapLayer.MAX_DRAWN_LINE_PIECES, layer.modId(), layer.layerName());
					}
					break all;
				}
				if (quads.count > before && probe != null) {
					probe.line(fullscreen, layer, i);
				}
			}
		}
		if (probe != null) {
			probe.drawn(fullscreen, true, quads.xyz, quads.colors, quads.count);
		}
		if (quads.count > 0) {
			graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
			MapCanvas.of(graphics).solidsKept(quads.xyz, quads.colors, quads.count);
			graphics.disableScissor();
		}
		quads.settle();
	}

	/** One line's quads, if any of it is in the view: its rim, then its colour over it. False if they don't fit in the budget. */
	private boolean line(Quads quads, MapViewport.View view, MapLine line) {
		int n = line.pointCount();
		if (sx.length < n) {
			sx = new double[Math.min(MapLine.MAX_POINTS, Math.max(n, sx.length * 2))];
			sy = new double[sx.length];
		}
		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (int k = 0; k < n; k++) {
			double x = line.x(k);
			double z = line.z(k);
			double u = Projection.u(x, z);
			double v = Projection.v(x, line.y(k), z);
			double px = this.screenX(u, v);
			double py = this.screenY(u, v);
			sx[k] = px;
			sy[k] = py;
			minX = Math.min(minX, px);
			maxX = Math.max(maxX, px);
			minY = Math.min(minY, py);
			maxY = Math.max(maxY, py);
		}
		double grow = line.width() / 2.0 + RIM + 1;
		if (maxX + grow < view.x() || minX - grow > view.x() + view.width() || maxY + grow < view.y() || minY - grow > view.y() + view.height()) {
			return true;
		}
		int rgb = line.rgb();
		return this.stroke(quads, view, n, line.width() + RIM * 2, rim(rgb)) && this.stroke(quads, view, n, line.width(), 0xFF000000 | rgb);
	}

	/** The colour darkened to three tenths, for the rim under a line, as the player's drawings have. */
	private static int rim(int rgb) {
		int r = (rgb >> 16 & 0xFF) * 3 / 10;
		int g = (rgb >> 8 & 0xFF) * 3 / 10;
		int b = (rgb & 0xFF) * 3 / 10;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/**
	 * A line through the screen points in {@link #sx} and {@link #sy}, {@code width} wide, round at
	 * every point. Each straight piece is cut to the view, a pixel past where its width could show,
	 * before it becomes floats, and on a round minimap left out where it passes wide of the circle.
	 * False if it doesn't fit in the budget.
	 */
	private boolean stroke(Quads quads, MapViewport.View view, int n, float width, int argb) {
		double half = width / 2.0;
		double pad = half + 1;
		double left = view.x() - pad;
		double top = view.y() - pad;
		double right = view.x() + view.width() + pad;
		double bottom = view.y() + view.height() + pad;
		Circle circle = this.circle;
		double[] sx = AddonOverlay.sx;
		double[] sy = AddonOverlay.sy;
		float[] p = POLYGON;
		for (int k = 0; k + 1 < n; k++) {
			double ax = sx[k];
			double ay = sy[k];
			double dx = sx[k + 1] - ax;
			double dy = sy[k + 1] - ay;
			double length = Math.sqrt(dx * dx + dy * dy);
			if (length < 1e-4) {
				continue;
			}
			this.t0 = 0;
			this.t1 = 1;
			if (!(this.clip(-dx, ax - left) && this.clip(dx, right - ax) && this.clip(-dy, ay - top) && this.clip(dy, bottom - ay))) {
				continue;
			}
			double x0 = ax + dx * this.t0;
			double y0 = ay + dy * this.t0;
			double x1 = ax + dx * this.t1;
			double y1 = ay + dy * this.t1;
			if (circle != null && distance(circle.cx, circle.cy, x0, y0, x1, y1) > circle.r + half) {
				continue;
			}
			double nx = -dy / length * half;
			double ny = dx / length * half;
			// In the GUI's turning order, whichever way the piece goes: along it on the normal's side,
			// then back on the other side.
			p[0] = (float) (x0 + nx);
			p[1] = (float) (y0 + ny);
			p[2] = (float) (x1 + nx);
			p[3] = (float) (y1 + ny);
			p[4] = (float) (x1 - nx);
			p[5] = (float) (y1 - ny);
			p[6] = (float) (x0 - nx);
			p[7] = (float) (y0 - ny);
			if (!this.convex(quads, 4, argb)) {
				return false;
			}
		}
		for (int k = 0; k < n; k++) {
			double x = sx[k];
			double y = sy[k];
			if (x < left || x > right || y < top || y > bottom || circle != null && Math.hypot(x - circle.cx, y - circle.cy) > circle.r + half) {
				continue;
			}
			for (int c = 0; c < 8; c++) {
				p[c * 2] = (float) (x + OCTAGON[c * 2] * half);
				p[c * 2 + 1] = (float) (y + OCTAGON[c * 2 + 1] * half);
			}
			if (!this.convex(quads, 8, argb)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * One edge of the Liang-Barsky clip: narrows {@link #t0} to {@link #t1} to the part of the
	 * segment on the inside of an edge, where {@code d} is how fast it heads out and {@code room}
	 * how far inside it starts. False when none of it is left.
	 */
	private boolean clip(double d, double room) {
		if (d == 0) {
			return room >= 0;
		}
		double t = room / d;
		if (d < 0) {
			if (t > this.t1) {
				return false;
			}
			this.t0 = Math.max(this.t0, t);
		} else {
			if (t < this.t0) {
				return false;
			}
			this.t1 = Math.min(this.t1, t);
		}
		return true;
	}

	/** How far (px, py) is from the segment (x0, y0) to (x1, y1). */
	private static double distance(double px, double py, double x0, double y0, double x1, double y1) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double lengthSquared = dx * dx + dy * dy;
		double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((px - x0) * dx + (py - y0) * dy) / lengthSquared));
		return Math.hypot(px - (x0 + dx * t), py - (y0 + dy * t));
	}

	/**
	 * The convex polygon in {@link #POLYGON} as quads, cut to the circle on a round minimap, with
	 * nothing allocated. Its corners come turning the way the GUI's do (top left, bottom left,
	 * bottom right, top right), as the GUI culls the other way round. They're put in that order
	 * where they're made rather than worked out from the corners: for a tiny piece far from the
	 * screen's corner, floats can't tell which way it turns. False if they don't fit in the budget.
	 */
	private boolean convex(Quads quads, int n, int argb) {
		float[] p = POLYGON;
		float[] corners = p;
		int count = n;
		Circle circle = this.circle;
		if (circle != null) {
			boolean inside = true;
			for (int k = 0; k < n && inside; k++) {
				inside = circle.contains(p[k * 2], p[k * 2 + 1]);
			}
			if (!inside) {
				count = circle.cutConvex(p, n);
				if (count < 3) {
					return true;
				}
				corners = Circle.cutCorners();
			}
		}
		// A fan from the first corner, two triangles to a quad; an odd one left over repeats its last corner.
		if (!quads.room((count - 1) / 2)) {
			return false;
		}
		for (int k = 1; k + 1 < count; k += 2) {
			int q = quads.count++;
			int at = q * 12;
			int last = Math.min(k + 2, count - 1);
			this.corner(quads.xyz, at, corners, 0);
			this.corner(quads.xyz, at + 3, corners, k);
			this.corner(quads.xyz, at + 6, corners, k + 1);
			this.corner(quads.xyz, at + 9, corners, last);
			quads.colors[q] = argb;
		}
		return true;
	}

	private void corner(float[] xyz, int at, float[] p, int k) {
		xyz[at] = p[k * 2];
		xyz[at + 1] = p[k * 2 + 1];
		xyz[at + 2] = this.z;
	}
}
