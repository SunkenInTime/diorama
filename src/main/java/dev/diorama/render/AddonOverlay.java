package dev.diorama.render;

import dev.diorama.Projection;
import dev.diorama.addon.AddonLayers;
import dev.diorama.api.DimensionId;
import dev.diorama.api.LayerContent;
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
 * <p>What it draws a frame goes into arrays it keeps, which the GUI holds on to until the frame is
 * drawn instead of copying ({@link MapCanvas#rectsKept}): so a crowd of markers allocates nothing
 * a frame beyond its labels, which the game's text drawing allocates for. Each kind of draw has
 * two sets of arrays and takes them in turn, so drawing the same view twice in a frame can't
 * change what the first draw handed the GUI. With no layer open, nothing happens at all.
 */
public final class AddonOverlay {
	/** A marker's dark outline, a pixel each side of its dot, as the radar's mob dots have. */
	private static final int OUTLINE = 0xE0101418;
	private static final int PLATE = 0x90000000;
	/** The rim either side of a line's colour, in GUI pixels, as the player's drawings have. */
	private static final float RIM = 1;
	/** The corners of a line's joint: an octagon round each point. */
	private static final float[] OCTAGON = new float[16];

	static {
		for (int i = 0; i < 8; i++) {
			double a = Math.PI * 2 * (i + 0.5) / 8;
			OCTAGON[i * 2] = (float) Math.cos(a);
			OCTAGON[i * 2 + 1] = (float) -Math.sin(a);
		}
	}

	/** For the game tests: told where each marker was put, frame by frame. Null outside them. */
	public static @Nullable Probe probe;

	/** What a test hears about each frame's markers. */
	public interface Probe {
		/** A frame of a view starts: its lines are drawn next, then its markers. */
		void frame(boolean fullscreen);

		/** A view's markers are about to be placed. */
		void view(boolean fullscreen, MapViewport.View view);

		/** One marker drawn at (x, y) on screen, its index in its layer's content. */
		void marker(boolean fullscreen, AddonLayers.Layer layer, int index, float x, float y);

		/** One line with a point on screen, its index in its layer's content. */
		void line(boolean fullscreen, AddonLayers.Layer layer, int index);
	}

	private final Quads[] markerQuads = {new Quads(), new Quads()};
	private final Quads[] lineQuads = {new Quads(), new Quads()};
	private int markerTurn;
	private int lineTurn;
	/** The labels to draw after the markers: each marker, and where it went. */
	private Marker[] labels = new Marker[16];
	private float[] labelAt = new float[32];
	/** A line's points on screen, kept between lines and frames. */
	private float[] sx = new float[64];
	private float[] sy = new float[64];
	private final float[] polygon = new float[16];
	private @Nullable Circle circle;
	private float z;
	// The view being drawn: what screen(u, v) needs, worked out once a draw.
	private double centerU;
	private double centerV;
	private double zoom;
	private double cos;
	private double sin;
	private float middleX;
	private float middleY;
	/** The last dimension asked for by its text, and its ID. */
	private @Nullable String dimensionText;
	private @Nullable DimensionId dimension;

	/** The markers' and lines' arrays of quads, kept between frames. */
	private static final class Quads {
		float[] xyz = new float[12 * 64];
		float[] uvs = new float[8 * 64];
		int[] colors = new int[64];
		int count;

		void room(int more) {
			if (this.count + more > this.colors.length) {
				int n = Math.max(this.colors.length * 2, this.count + more);
				this.xyz = Arrays.copyOf(this.xyz, n * 12);
				this.uvs = Arrays.copyOf(this.uvs, n * 8);
				this.colors = Arrays.copyOf(this.colors, n);
			}
		}
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

	/** Whether any layer is open: the maps ask before working out anything to draw. */
	public static boolean any() {
		return !AddonLayers.open().isEmpty();
	}

	private void place(MapViewport.View view, GuiGraphicsExtractor graphics) {
		this.centerU = view.centerU();
		this.centerV = view.centerV();
		this.zoom = view.zoom();
		this.cos = view.turn() == 0 ? 1 : Math.cos(view.turn());
		this.sin = view.turn() == 0 ? 0 : Math.sin(view.turn());
		this.middleX = (float) (view.x() + view.width() / 2.0);
		this.middleY = (float) (view.y() + view.height() / 2.0);
		this.circle = view.round() ? Circle.in(view) : null;
		//? if <1.21.6 {
		/*// The GUI depth-tests before 1.21.6: draw at the depth the rest of the view is drawn at.
		this.z = graphics.pose().last().pose().m32();
		*///?} else {
		this.z = 0;
		//?}
	}

	// Where a point of the map is on screen, turned with the view: View.screenX(u, v) and screenY(u, v).
	private float screenX(double u, double v) {
		return (float) (this.middleX + ((u - this.centerU) * this.cos - (v - this.centerV) * this.sin) * this.zoom);
	}

	private float screenY(double u, double v) {
		return (float) (this.middleY + ((u - this.centerU) * this.sin + (v - this.centerV) * this.cos) * this.zoom);
	}

	/**
	 * The layers' markers in {@code dimension}, with their labels on the full map. Call it inside
	 * no scissor of its own: it cuts to the view.
	 */
	public void drawMarkers(GuiGraphicsExtractor graphics, MapViewport.View view, @Nullable DimensionId dimension, boolean fullscreen) {
		List<AddonLayers.Layer> layers = AddonLayers.open();
		if (layers.isEmpty() || dimension == null) {
			return;
		}
		Probe probe = AddonOverlay.probe;
		if (probe != null) {
			probe.view(fullscreen, view);
		}
		this.place(view, graphics);
		Quads quads = this.markerQuads[this.markerTurn];
		this.markerTurn ^= 1;
		quads.count = 0;
		int labelCount = 0;
		double gui = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
		float left = view.x();
		float top = view.y();
		float right = view.x() + view.width();
		float bottom = view.y() + view.height();
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
				float x = this.screenX(u, v);
				float y = this.screenY(u, v);
				float half = marker.size() / 2.0F + 1;
				if (x + half < left || x - half > right || y + half < top || y - half > bottom) {
					continue;
				}
				Circle circle = this.circle;
				if (circle != null && !(circle.contains(x - half, y - half) && circle.contains(x + half, y - half)
					&& circle.contains(x - half, y + half) && circle.contains(x + half, y + half))) {
					continue;
				}
				this.marker(quads, marker, x, y, gui);
				if (probe != null) {
					probe.marker(fullscreen, layer, i, x, y);
				}
				if (fullscreen && !marker.label().isEmpty()) {
					if (labelCount == this.labels.length) {
						this.labels = Arrays.copyOf(this.labels, labelCount * 2);
						this.labelAt = Arrays.copyOf(this.labelAt, labelCount * 4);
					}
					this.labels[labelCount] = marker;
					this.labelAt[labelCount * 2] = x;
					this.labelAt[labelCount * 2 + 1] = y;
					labelCount++;
				}
			}
		}
		if (quads.count == 0) {
			return;
		}
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		MapCanvas.of(graphics).rectsKept(MarkerAtlas.texture(), Sampling.CLAMP_CRISP, quads.xyz, quads.uvs, quads.colors, quads.count);
		if (labelCount > 0) {
			Font font = Minecraft.getInstance().font;
			for (int k = 0; k < labelCount; k++) {
				this.label(graphics, font, this.labels[k], this.labelAt[k * 2], this.labelAt[k * 2 + 1]);
				this.labels[k] = null;
			}
		}
		graphics.disableScissor();
	}

	/** One marker's quads: its icon, or its dot over a dark outline. */
	private void marker(Quads quads, Marker marker, float x, float y, double gui) {
		Optional<ResourceId> icon = marker.icon();
		int tile = icon.isPresent() ? MarkerAtlas.tile(icon.get()) : MarkerAtlas.NONE;
		float size = marker.size();
		if (tile != MarkerAtlas.NONE) {
			// Each of the icon's pixels a whole number of screen pixels, as the waypoint icons are.
			float texel = Radar.texel(size, WaypointIcons.TILE);
			float half = WaypointIcons.TILE * texel / 2;
			float u0 = MarkerAtlas.tileX(tile) / (float) MarkerAtlas.SIZE;
			float v0 = MarkerAtlas.tileY(tile) / (float) MarkerAtlas.SIZE;
			float side = WaypointIcons.TILE / (float) MarkerAtlas.SIZE;
			this.rect(quads, x - half, y - half, x + half, y + half, u0, v0, u0 + side, v0 + side, 0xFFFFFFFF);
			return;
		}
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

	/** An upright rectangle, its corners in the order MapCanvas.rect gives them. */
	private void rect(Quads quads, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
		quads.room(1);
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

	/** A marker's label under it, white on the dark plate waypoint names have on the map. */
	private void label(GuiGraphicsExtractor graphics, Font font, Marker marker, float x, float y) {
		String text = marker.label();
		int w = font.width(text);
		graphics.pose().pushMatrix();
		graphics.pose().translate(Math.round(x - w / 2.0F), Math.round(y + marker.size() / 2.0F + 3));
		graphics.fill(-2, -1, w + 2, 9, PLATE);
		graphics.text(font, text, 0, 0, 0xFFFFFFFF, false);
		graphics.pose().popMatrix();
	}

	/**
	 * The layers' lines in {@code dimension}, cut to the view, a round one's circle included. Call
	 * it inside no scissor of its own.
	 */
	public void drawLines(GuiGraphicsExtractor graphics, MapViewport.View view, @Nullable DimensionId dimension, boolean fullscreen) {
		List<AddonLayers.Layer> layers = AddonLayers.open();
		if (layers.isEmpty() || dimension == null) {
			return;
		}
		Probe probe = AddonOverlay.probe;
		if (probe != null) {
			probe.frame(fullscreen);
		}
		this.place(view, graphics);
		Quads quads = this.lineQuads[this.lineTurn];
		this.lineTurn ^= 1;
		quads.count = 0;
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
				if (this.line(quads, view, line) && probe != null) {
					probe.line(fullscreen, layer, i);
				}
			}
		}
		if (quads.count == 0) {
			return;
		}
		graphics.enableScissor(view.x(), view.y(), view.x() + view.width(), view.y() + view.height());
		MapCanvas.of(graphics).solidsKept(quads.xyz, quads.colors, quads.count);
		graphics.disableScissor();
	}

	/** One line's quads, if any of it is in the view: its rim, then its colour over it. */
	private boolean line(Quads quads, MapViewport.View view, MapLine line) {
		int n = line.pointCount();
		if (this.sx.length < n) {
			this.sx = new float[Math.max(n, this.sx.length * 2)];
			this.sy = new float[this.sx.length];
		}
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (int k = 0; k < n; k++) {
			double x = line.x(k);
			double z = line.z(k);
			double u = Projection.u(x, z);
			double v = Projection.v(x, line.y(k), z);
			float px = this.screenX(u, v);
			float py = this.screenY(u, v);
			this.sx[k] = px;
			this.sy[k] = py;
			minX = Math.min(minX, px);
			maxX = Math.max(maxX, px);
			minY = Math.min(minY, py);
			maxY = Math.max(maxY, py);
		}
		float grow = line.width() + RIM * 2;
		if (maxX + grow < view.x() || minX - grow > view.x() + view.width() || maxY + grow < view.y() || minY - grow > view.y() + view.height()) {
			return false;
		}
		int rgb = line.rgb();
		this.stroke(quads, view, n, line.width() + RIM * 2, rim(rgb));
		this.stroke(quads, view, n, line.width(), 0xFF000000 | rgb);
		return true;
	}

	/** The colour darkened to three tenths, for the rim under a line, as the player's drawings have. */
	private static int rim(int rgb) {
		int r = (rgb >> 16 & 0xFF) * 3 / 10;
		int g = (rgb >> 8 & 0xFF) * 3 / 10;
		int b = (rgb & 0xFF) * 3 / 10;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** A line through the screen points in {@link #sx} and {@link #sy}, {@code width} wide, round at every point. */
	private void stroke(Quads quads, MapViewport.View view, int n, float width, int argb) {
		float half = width / 2;
		float left = view.x() - half;
		float top = view.y() - half;
		float right = view.x() + view.width() + half;
		float bottom = view.y() + view.height() + half;
		float[] sx = this.sx;
		float[] sy = this.sy;
		for (int k = 0; k + 1 < n; k++) {
			float ax = sx[k];
			float ay = sy[k];
			float bx = sx[k + 1];
			float by = sy[k + 1];
			if (Math.max(ax, bx) < left || Math.min(ax, bx) > right || Math.max(ay, by) < top || Math.min(ay, by) > bottom) {
				continue;
			}
			float dx = bx - ax;
			float dy = by - ay;
			float length = (float) Math.sqrt(dx * dx + dy * dy);
			if (length < 1e-4F) {
				continue;
			}
			float nx = -dy / length * half;
			float ny = dx / length * half;
			this.polygon[0] = ax + nx;
			this.polygon[1] = ay + ny;
			this.polygon[2] = ax - nx;
			this.polygon[3] = ay - ny;
			this.polygon[4] = bx - nx;
			this.polygon[5] = by - ny;
			this.polygon[6] = bx + nx;
			this.polygon[7] = by + ny;
			this.convex(quads, 4, argb);
		}
		for (int k = 0; k < n; k++) {
			float x = sx[k];
			float y = sy[k];
			if (x < left || x > right || y < top || y > bottom) {
				continue;
			}
			for (int c = 0; c < 8; c++) {
				this.polygon[c * 2] = x + OCTAGON[c * 2] * half;
				this.polygon[c * 2 + 1] = y + OCTAGON[c * 2 + 1] * half;
			}
			this.convex(quads, 8, argb);
		}
	}

	/**
	 * The convex polygon in {@link #polygon} as quads, turned the way the GUI draws (it culls the
	 * other), and cut to the circle on a round minimap. Only a piece that crosses the circle is cut,
	 * which is the one place this allocates.
	 */
	private void convex(Quads quads, int n, int argb) {
		float[] p = this.polygon;
		float area = 0;
		for (int k = 0, j = n - 1; k < n; j = k++) {
			area += p[j * 2] * p[k * 2 + 1] - p[k * 2] * p[j * 2 + 1];
		}
		if (area == 0) {
			return;
		}
		if (area > 0) {
			// The GUI's corner order (top left, bottom left, bottom right, top right) turns the other way on screen.
			for (int k = 0; k < n / 2; k++) {
				int j = n - 1 - k;
				float x = p[k * 2];
				float y = p[k * 2 + 1];
				p[k * 2] = p[j * 2];
				p[k * 2 + 1] = p[j * 2 + 1];
				p[j * 2] = x;
				p[j * 2 + 1] = y;
			}
		}
		Circle circle = this.circle;
		if (circle != null) {
			boolean inside = true;
			boolean near = false;
			for (int k = 0; k < n; k++) {
				float x = p[k * 2];
				float y = p[k * 2 + 1];
				inside &= circle.contains(x, y);
				near |= Math.abs(x - circle.cx) <= circle.r && Math.abs(y - circle.cy) <= circle.r;
			}
			if (!inside) {
				if (!near) {
					return;
				}
				float[] corners = circle.clip(p, n);
				if (corners != null) {
					for (int i = 0; i + 8 <= corners.length; i += 8) {
						this.quad(quads, corners, i, argb);
					}
				}
				return;
			}
		}
		// A fan from the first corner, two triangles to a quad; an odd one left over repeats its last corner.
		for (int k = 1; k + 1 < n; k += 2) {
			quads.room(1);
			int q = quads.count++;
			int at = q * 12;
			int last = Math.min(k + 2, n - 1);
			this.corner(quads.xyz, at, p, 0);
			this.corner(quads.xyz, at + 3, p, k);
			this.corner(quads.xyz, at + 6, p, k + 1);
			this.corner(quads.xyz, at + 9, p, last);
			quads.colors[q] = argb;
		}
	}

	private void corner(float[] xyz, int at, float[] p, int k) {
		xyz[at] = p[k * 2];
		xyz[at + 1] = p[k * 2 + 1];
		xyz[at + 2] = this.z;
	}

	private void quad(Quads quads, float[] corners, int from, int argb) {
		quads.room(1);
		int q = quads.count++;
		int at = q * 12;
		for (int c = 0; c < 4; c++) {
			quads.xyz[at + c * 3] = corners[from + c * 2];
			quads.xyz[at + c * 3 + 1] = corners[from + c * 2 + 1];
			quads.xyz[at + c * 3 + 2] = this.z;
		}
		quads.colors[q] = argb;
	}
}
