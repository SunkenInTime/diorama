package dev.diorama.craft;

import dev.diorama.Facing;
import dev.diorama.Projection;
import dev.diorama.bake.ChunkSource;
import dev.diorama.bake.TileBaker;
import dev.diorama.bake.TileKey;
import dev.diorama.compat.CreateTrains;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import dev.diorama.render.gpu.MapCanvas;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Create's trains outside the client's view on the full map, from the places Create sends
 * ({@link CreateTrains}): each carriage a plain box in its train's map colour, from its front
 * wheels to its back ones and a block and a half past each end, moving as Create's own maps move
 * it. A carriage the client has, or one parked on the map where it stands, is drawn block for
 * block by {@link Crafts} instead.
 */
public final class FarTrains {
	/** Blocks a carriage reaches past each of its two outer wheels. */
	private static final double OVERHANG = 1.5;
	/** A carriage's width and height in blocks. */
	private static final double WIDTH = 2.5;
	private static final double HEIGHT = 2;
	/** Fewest GUI pixels a carriage is across and long, so a train still shows on a map zoomed far out. */
	private static final double MIN_PIXELS = 3;
	/** The outline's width in GUI pixels. */
	private static final double OUTLINE = 1;
	private static final int OUTLINE_COLOUR = 0xE0101418;

	/** A carriage drawn on the map in the last frame, with the middle of its top on screen. For tests. */
	public record Drawn(CreateTrains.Carriage carriage, float x, float y) {
	}

	private static List<Drawn> drawn = List.of();

	private FarTrains() {
	}

	/** The carriages drawn on the map in the last frame. For tests. */
	public static List<Drawn> drawn() {
		return drawn;
	}

	/**
	 * Draws the far carriages in the map's dimension, {@code crafts} being the crafts drawn on it
	 * (null for another dimension's map), and returns the name of the train under the mouse.
	 * Render thread.
	 */
	public static @Nullable Component draw(
		GuiGraphicsExtractor graphics, MapViewport.View view, MapLevel map, String dimension, @Nullable Crafts crafts, int mouseX, int mouseY
	) {
		drawn = List.of();
		boolean surface = view.layer() == TileKey.SURFACE;
		// Under fog of war a train mustn't give away tunnels not seen yet, as for the crafts.
		if ((!surface || map.roof() != ChunkSource.NO_ROOF) && map.fogOfWar()) {
			return null;
		}
		List<CreateTrains.Carriage> carriages = new ArrayList<>();
		for (CreateTrains.Carriage carriage : CreateTrains.carriages(dimension)) {
			if (carriage.loaded() || crafts != null && crafts.parkedAt(carriage)) {
				continue;
			}
			double y = Math.min(carriage.first().y, carriage.second().y);
			if (!surface && !(y <= view.layer() && y + HEIGHT >= view.layer() - TileBaker.CAVE_DEPTH)) {
				continue;
			}
			carriages.add(carriage);
		}
		if (carriages.isEmpty()) {
			return null;
		}
		Facing facing = Projection.facing();
		double sin = Projection.sin();
		double cos = Projection.cos();
		// Farther first, so a nearer carriage covers it.
		carriages.sort(Comparator.comparingDouble(c -> facing.z(c.middle().x, c.middle().z) * cos + c.middle().y * sin));
		MapCanvas canvas = MapCanvas.of(graphics);
		List<Drawn> shown = new ArrayList<>();
		Component hovered = null;
		double zoom = view.zoom();
		for (CreateTrains.Carriage c : carriages) {
			// In the map's turned space (see Facing): x across, z down the map.
			double ax = facing.x(c.first().x, c.first().z);
			double az = facing.z(c.first().x, c.first().z);
			double bx = facing.x(c.second().x, c.second().z);
			double bz = facing.z(c.second().x, c.second().z);
			double dx = bx - ax;
			double dz = bz - az;
			double between = Math.sqrt(dx * dx + dz * dz);
			if (between < 1e-3) {
				dx = 1;
				dz = 0;
			} else {
				dx /= between;
				dz /= between;
			}
			double length = Math.max(between + 2 * OVERHANG, MIN_PIXELS / zoom);
			double width = Math.max(WIDTH, MIN_PIXELS / zoom);
			double height = HEIGHT * width / WIDTH;
			double cx = (ax + bx) / 2;
			double cz = (az + bz) / 2;
			double y = (c.first().y + c.second().y) / 2;
			double grow = OUTLINE / zoom;
			box(canvas, view, cx, cz, dx, dz, y - grow, length + 2 * grow, width + 2 * grow, height + 2 * grow, OUTLINE_COLOUR, OUTLINE_COLOUR);
			float[][] faces = box(canvas, view, cx, cz, dx, dz, y, length, width, height, 0xFF000000 | c.rgb(), 0xFF000000 | shade(c.rgb()));
			for (float[] face : faces) {
				if (face != null && inside(face, mouseX, mouseY)) {
					hovered = c.name();
				}
			}
			shown.add(new Drawn(c, view.screenX(cx), view.screenY(Projection.v(y + height, cz))));
		}
		drawn = shown;
		return hovered;
	}

	/**
	 * A box standing at height {@code y}, its middle at ({@code cx}, {@code cz}) and its length
	 * along ({@code dx}, {@code dz}): the sides facing the camera in {@code side}, then the top in
	 * {@code top}. Returns the faces drawn on screen, null for a side facing away.
	 */
	private static float[][] box(
		MapCanvas canvas, MapViewport.View view, double cx, double cz, double dx, double dz, double y, double length, double width, double height, int top, int side
	) {
		double lx = dx * length / 2;
		double lz = dz * length / 2;
		double wx = -dz * width / 2;
		double wz = dx * width / 2;
		double[] xs = {cx + lx + wx, cx + lx - wx, cx - lx - wx, cx - lx + wx};
		double[] zs = {cz + lz + wz, cz + lz - wz, cz - lz - wz, cz - lz + wz};
		float[][] faces = new float[5][];
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) % 4;
			// The camera looks north: a side whose outward direction points south faces it.
			double nz = (zs[i] + zs[j]) / 2 - cz;
			if (nz <= 1e-6) {
				continue;
			}
			faces[i] = quad(canvas, view, new double[] {xs[i], y, zs[i], xs[j], y, zs[j], xs[j], y + height, zs[j], xs[i], y + height, zs[i]}, side);
		}
		double t = y + height;
		faces[4] = quad(canvas, view, new double[] {xs[0], t, zs[0], xs[1], t, zs[1], xs[2], t, zs[2], xs[3], t, zs[3]}, top);
		return faces;
	}

	/** Four corners in turned space (x y z each), drawn in one colour; returns them on screen. */
	private static float[] quad(MapCanvas canvas, MapViewport.View view, double[] corners, int argb) {
		float[] screen = new float[8];
		for (int i = 0; i < 4; i++) {
			screen[i * 2] = view.screenX(corners[i * 3]);
			screen[i * 2 + 1] = view.screenY(Projection.v(corners[i * 3 + 1], corners[i * 3 + 2]));
		}
		// The GUI culls the other winding: counter-clockwise on screen, y down.
		if (area(screen) > 0) {
			float[] reversed = new float[8];
			for (int i = 0; i < 4; i++) {
				reversed[i * 2] = screen[(3 - i) * 2];
				reversed[i * 2 + 1] = screen[(3 - i) * 2 + 1];
			}
			screen = reversed;
		}
		canvas.solid(screen, argb);
		return screen;
	}

	private static float area(float[] q) {
		float sum = 0;
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) % 4;
			sum += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1];
		}
		return sum;
	}

	/** Whether a point is in a convex quad. */
	private static boolean inside(float[] q, double x, double y) {
		int sign = 0;
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) % 4;
			double cross = (q[j * 2] - q[i * 2]) * (y - q[i * 2 + 1]) - (q[j * 2 + 1] - q[i * 2 + 1]) * (x - q[i * 2]);
			int s = cross > 0 ? 1 : cross < 0 ? -1 : 0;
			if (s != 0) {
				if (sign != 0 && s != sign) {
					return false;
				}
				sign = s;
			}
		}
		return true;
	}

	/** A side's colour: the top's, darker. */
	private static int shade(int rgb) {
		int r = (rgb >> 16 & 0xFF) * 7 / 10;
		int g = (rgb >> 8 & 0xFF) * 7 / 10;
		int b = (rgb & 0xFF) * 7 / 10;
		return r << 16 | g << 8 | b;
	}
}
