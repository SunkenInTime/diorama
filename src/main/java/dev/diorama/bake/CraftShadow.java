package dev.diorama.bake;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import org.jspecify.annotations.Nullable;

/**
 * The shadow a craft casts on the ground under it, in the map's sun. How far it falls from the
 * craft is what shows how high the craft flies.
 *
 * <p>Made in two steps, as a compositor would. The craft is first drawn as the sun sees it: a
 * {@link Silhouette} that says, along each line toward the sun, how far toward the sun the
 * craft reaches. That only changes when the craft turns. Then every map pixel near the craft
 * looks up the ground point the tile shows there ({@link TileSurface}) and is in shadow if the
 * silhouette covers that point from the sun's side: a lookup per pixel, on the tiles' own pixel
 * grid, so the shadow is as sharp as the ground it falls on and is made again as the craft moves.
 * Ground the world's blocks already shade gets none: the tile shows that shade already.
 */
public final class CraftShadow {
	/** Silhouette texels per block, and fewer for a craft too big for that. */
	private static final int SILHOUETTE_TEXELS = 8;
	private static final int BIG_SILHOUETTE_TEXELS = 4;
	/** Past this far from the craft (blocks, across) the shadow isn't made. */
	private static final int MAX_REACH = 192;
	/** How far a corner of the craft may have turned from its silhouette, in blocks: half a map pixel. */
	private static final double STALE = 0.5 / Projection.PIXELS_PER_UNIT;
	/** Samples per pixel along each side, for a smooth edge. */
	private static final int SAMPLES = 2;

	/**
	 * A craft as the sun sees it, turned by {@code linear}: across a plane facing the sun (axes
	 * {@code e1}, {@code e2} in the world, from ({@code a0}, {@code b0})), how far along the
	 * sun's direction {@code s} the craft reaches at each texel, measured from the craft's grid
	 * origin, or NaN where the sun passes it by.
	 */
	public record Silhouette(double[] linear, double[] s, double[] e1, double[] e2, double a0, double b0, int texels, int width, int height, float[] reach) {
		/** How far toward the sun the craft reaches over the point (a, b) of the plane, or NaN. */
		float at(double a, double b) {
			int i = (int) Math.floor((a - this.a0) * this.texels);
			int j = (int) Math.floor((b - this.b0) * this.texels);
			return i < 0 || j < 0 || i >= this.width || j >= this.height ? Float.NaN : this.reach[j * this.width + i];
		}
	}

	/**
	 * A shadow: {@code width} by {@code height} map pixels of alpha in the corner of an
	 * {@code edge}-square ABGR mip chain, its top left at map pixel ({@code pu0}, {@code pv0}),
	 * made with the craft at {@code pose} from {@code silhouette} on {@code ground}, with the
	 * camera at {@code pitchSin}. {@code mips} is null when none of the shadow shows.
	 */
	public record Image(
		int edge, int width, int height, int pu0, int pv0, int @Nullable [][] mips, double[] pose, @Nullable Silhouette silhouette, SurfaceArea ground, double pitchSin
	) {
	}

	private CraftShadow() {
	}

	/** Toward the sun, as a unit vector. */
	public static double[] sun(MapStyle style) {
		double elevation = Math.toRadians(style.sunElevation());
		return new double[] {Math.cos(elevation) * TileBaker.SUN_AZIMUTH_X, Math.sin(elevation), Math.cos(elevation) * TileBaker.SUN_AZIMUTH_Z};
	}

	/** The area a craft at {@code pose} may shadow, as {x0, z0, x1, z1} inclusive, given the lowest ground there. */
	public static int[] reach(CraftBlocks blocks, double[] pose, int groundY, MapStyle style) {
		double[] box = blocks.box(pose);
		double elevation = Math.toRadians(style.sunElevation());
		double run = Math.min(MAX_REACH, Math.max(0, box[4] - groundY)) / Math.tan(elevation);
		// The shadow falls away from the sun.
		double dx = -TileBaker.SUN_AZIMUTH_X * run;
		double dz = -TileBaker.SUN_AZIMUTH_Z * run;
		return new int[] {
			(int) Math.floor(Math.min(box[0], box[0] + dx)) - 1,
			(int) Math.floor(Math.min(box[2], box[2] + dz)) - 1,
			(int) Math.ceil(Math.max(box[3], box[3] + dx)) + 1,
			(int) Math.ceil(Math.max(box[5], box[5] + dz)) + 1,
		};
	}

	/** Whether a silhouette made for one turn is too far from {@code linear} to stand in: over half a map pixel at the craft's corners. */
	public static boolean stale(Silhouette silhouette, double[] linear, CraftBlocks blocks) {
		double[] l = silhouette.linear;
		for (int c = 1; c < 8; c++) {
			double x = (c & 1) * blocks.sizeX;
			double y = (c >> 1 & 1) * blocks.sizeY;
			double z = (c >> 2 & 1) * blocks.sizeZ;
			double dx = (l[0] - linear[0]) * x + (l[3] - linear[3]) * y + (l[6] - linear[6]) * z;
			double dy = (l[1] - linear[1]) * x + (l[4] - linear[4]) * y + (l[7] - linear[7]) * z;
			double dz = (l[2] - linear[2]) * x + (l[5] - linear[5]) * y + (l[8] - linear[8]) * z;
			if (dx * dx + dy * dy + dz * dz > STALE * STALE) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The craft turned by {@code linear} (the world offsets of one step along its grid's x, y and
	 * z) as the style's sun sees it, or null if the turn can't be undone. Any thread.
	 */
	public static @Nullable Silhouette silhouette(CraftBlocks blocks, double[] linear, BlockLooks looks, MapStyle style) {
		double[] inv = GridRay.invert(linear);
		if (inv == null) {
			return null;
		}
		double[] s = sun(style);
		// Two axes across the sun's rays: one level, one up the plane facing it.
		double[] e1 = normalize(new double[] {-s[2], 0, s[0]});
		double[] e2 = {e1[1] * s[2] - e1[2] * s[1], e1[2] * s[0] - e1[0] * s[2], e1[0] * s[1] - e1[1] * s[0]};
		double a0 = Double.MAX_VALUE;
		double a1 = -Double.MAX_VALUE;
		double b0 = Double.MAX_VALUE;
		double b1 = -Double.MAX_VALUE;
		for (int c = 0; c < 8; c++) {
			double x = (c & 1) * blocks.sizeX;
			double y = (c >> 1 & 1) * blocks.sizeY;
			double z = (c >> 2 & 1) * blocks.sizeZ;
			double wx = linear[0] * x + linear[3] * y + linear[6] * z;
			double wy = linear[1] * x + linear[4] * y + linear[7] * z;
			double wz = linear[2] * x + linear[5] * y + linear[8] * z;
			double a = wx * e1[0] + wy * e1[1] + wz * e1[2];
			double b = wx * e2[0] + wy * e2[1] + wz * e2[2];
			a0 = Math.min(a0, a);
			a1 = Math.max(a1, a);
			b0 = Math.min(b0, b);
			b1 = Math.max(b1, b);
		}
		int texels = SILHOUETTE_TEXELS;
		int width = (int) Math.ceil((a1 - a0) * texels) + 1;
		int height = (int) Math.ceil((b1 - b0) * texels) + 1;
		if ((long) width * height > 1 << 22) {
			texels = BIG_SILHOUETTE_TEXELS;
			width = (int) Math.ceil((a1 - a0) * texels) + 1;
			height = (int) Math.ceil((b1 - b0) * texels) + 1;
			if ((long) width * height > 1 << 22) {
				return null;
			}
		}
		float[] reach = new float[width * height];
		Walker walker = new Walker(blocks, looks, inv, s);
		for (int j = 0; j < height; j++) {
			for (int i = 0; i < width; i++) {
				double a = a0 + (i + 0.5) / texels;
				double b = b0 + (j + 0.5) / texels;
				reach[j * width + i] = walker.reach(a * e1[0] + b * e2[0], a * e1[1] + b * e2[1], a * e1[2] + b * e2[2]);
			}
		}
		return new Silhouette(linear.clone(), s, e1, e2, a0, b0, texels, width, height, reach);
	}

	/**
	 * The shadow of a craft at {@code pose} (see {@link dev.diorama.craft.Body#pose}) with this
	 * silhouette (none for a craft that can't have one), over the map pixels {@code width} by
	 * {@code height} from ({@code pu0}, {@code pv0}). Any thread.
	 */
	public static Image cast(@Nullable Silhouette silhouette, double[] pose, int pu0, int pv0, int width, int height, SurfaceArea ground) {
		int step = 1 << (Mipmaps.LEVELS - 1);
		int edge = (Math.max(width, height) + step - 1) / step * step;
		Image none = new Image(edge, width, height, pu0, pv0, null, pose.clone(), silhouette, ground, Projection.sin());
		if (silhouette == null || edge > CraftBaker.MAX_PIXELS) {
			return none;
		}
		double sin = Projection.sin();
		double cos = Projection.cos();
		int ppu = Projection.PIXELS_PER_UNIT;
		int[] pixels = new int[edge * edge];
		boolean any = false;
		double[] s = silhouette.s;
		double[] e1 = silhouette.e1;
		double[] e2 = silhouette.e2;
		for (int py = 0; py < height; py++) {
			for (int px = 0; px < width; px++) {
				int pu = pu0 + px;
				int pv = pv0 + py;
				float y = ground.y(pu, pv);
				float sun = Float.isNaN(y) ? 0 : ground.sun(pu, pv);
				if (sun <= 0) {
					continue;
				}
				int dark = 0;
				for (int sy = 0; sy < SAMPLES; sy++) {
					for (int sx = 0; sx < SAMPLES; sx++) {
						// Across the pixel on the ground's own level: the ground point under each sample.
						double u = (pu + (sx + 0.5) / SAMPLES) / ppu;
						double v = (pv + (sy + 0.5) / SAMPLES) / ppu;
						double rx = u - pose[0];
						double ry = y - pose[1];
						double rz = (v + y * cos) / sin - pose[2];
						float reach = silhouette.at(rx * e1[0] + ry * e1[1] + rz * e1[2], rx * e2[0] + ry * e2[1] + rz * e2[2]);
						// Shadowed when the craft lies further toward the sun than the ground point.
						if (reach > rx * s[0] + ry * s[1] + rz * s[2] + 0.05) {
							dark++;
						}
					}
				}
				if (dark > 0) {
					int coverage = Math.round(255F * dark / (SAMPLES * SAMPLES));
					int shaded = ground.shaded(pu, pv);
					if (shaded != 0) {
						// The ground as the tile would draw it in shade.
						pixels[py * edge + px] = coverage << 24 | shaded & 0xFFFFFF;
					} else {
						// Water: takes the sun's share away, blended in sRGB, where a linear share
						// lands as about its 1 / 2.2 power.
						float alpha = (float) (1 - Math.pow(1 - sun, 1 / 2.2)) * dark / (SAMPLES * SAMPLES);
						pixels[py * edge + px] = Math.round(alpha * 255) << 24;
					}
					any = true;
				}
			}
		}
		return any ? new Image(edge, width, height, pu0, pv0, Mipmaps.build(pixels, edge), none.pose(), silhouette, ground, none.pitchSin()) : none;
	}

	private static double[] normalize(double[] v) {
		double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		return new double[] {v[0] / length, v[1] / length, v[2] / length};
	}

	/** Walks lines along the sun's direction through the craft's grid. */
	private static final class Walker {
		private final CraftBlocks blocks;
		private final BlockLooks looks;
		private final double[] inv;
		private final double rx;
		private final double ry;
		private final double rz;
		private final GridRay ray;

		Walker(CraftBlocks blocks, BlockLooks looks, double[] inv, double[] s) {
			this.blocks = blocks;
			this.looks = looks;
			this.inv = inv;
			this.rx = inv[0] * s[0] + inv[1] * s[1] + inv[2] * s[2];
			this.ry = inv[3] * s[0] + inv[4] * s[1] + inv[5] * s[2];
			this.rz = inv[6] * s[0] + inv[7] * s[1] + inv[8] * s[2];
			this.ray = new GridRay(blocks);
		}

		/**
		 * How far along the sun's direction, from the world point (x, y, z) relative to the grid
		 * origin, the line through it last leaves a solid block of the craft; NaN if it meets none.
		 */
		float reach(double x, double y, double z) {
			double[] m = this.inv;
			GridRay ray = this.ray;
			if (!ray.start(m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z,
				this.rx, this.ry, this.rz, -Double.MAX_VALUE)) {
				return Float.NaN;
			}
			CraftBlocks b = this.blocks;
			float reach = Float.NaN;
			do {
				int id = b.look(this.looks, CraftBlocks.index(b.sizeX, b.sizeZ, ray.cell[0], ray.cell[1], ray.cell[2]));
				// Glass and plants too, so the shadow keeps the craft's outline.
				if (id != 0 && id < this.looks.kind.length && this.looks.kind[id] != BlockLooks.AIR && ray.enter < ray.exit()) {
					reach = (float) ray.exit();
				}
			} while (ray.next());
			return reach;
		}
	}
}
