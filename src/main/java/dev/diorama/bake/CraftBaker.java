package dev.diorama.bake;

import dev.diorama.MapStyle;
import dev.diorama.Projection;
import org.jspecify.annotations.Nullable;

/**
 * Ray traces a moving craft (a train, a windmill) into a sprite drawn over the map.
 *
 * <p>A craft can sit at any angle, so unlike {@link TileBaker} the rays don't follow the block
 * grid: each one is carried into the craft's own frame and walks its grid there. The sprite
 * depends only on how the craft is turned, not where it is (the camera is orthographic). It's
 * drawn on the map's own pixel grid, like the tiles, and bakes again once the craft has turned by
 * about a pixel ({@link #warp} measures that).
 * Lit like the surface map: sun, shadows the craft casts on itself, corner occlusion and the
 * style's grade, with the block colours the tiles use.
 */
public final class CraftBaker {
	/** Past this many pixels across, a craft isn't drawn: the bake would take too long. */
	public static final int MAX_PIXELS = 2048;

	private static final int GRASS = 0x91BD59;
	private static final int FOLIAGE = 0x77AB2F;
	private static final int DRY_FOLIAGE = 0xA5864D;
	private static final int WATER = 0x3F76E4;

	private final CraftBlocks blocks;
	private final BlockLooks looks;
	private final MapStyle style;
	/** Craft to world: the world offsets of one step along the grid's x, y and z, in that order. */
	private final double[] l;
	/** World to craft, rows first. */
	private final double[] inv;
	private final double sin;
	private final double cos;
	private final Lighting lighting;
	private final float[] light = new float[4];
	/** The world y of the grid origin, for the style's shading with height. */
	private final double originY;

	// The last trace: premultiplied colour, coverage and depth of the first opaque hit (NaN if none).
	private float outR;
	private float outG;
	private float outB;
	private float outA;
	private float outDepth;
	private float albedoR;
	private float albedoG;
	private float albedoB;
	// Scratch for one trace at a time.
	private final GridRay ray;
	private final GridRay sunRay;
	private final double[] o = new double[3];
	private final double[] r = new double[3];
	private final float[] corners = new float[4];
	private float shadedR;
	private float shadedG;
	private float shadedB;
	private float sunlessR;
	private float sunlessG;
	private float sunlessB;
	private float outSunlessR;
	private float outSunlessG;
	private float outSunlessB;

	/**
	 * A baked craft: {@code width} by {@code height} pixels in the corner of an {@code edge}-square
	 * mip chain, whose top left lies ({@code offsetU}, {@code offsetV}) map units from where the
	 * middle of the craft's grid projects. The middle, as crafts mostly turn about it: a spinning
	 * windmill drawn a frame late then still sits where it is. {@code depth} is each pixel's
	 * camera depth (blocks, larger is farther) relative to that middle's, NaN where nothing solid
	 * is hit, for working out which of two overlapping crafts is in front. {@code sunless} is each
	 * pixel's colour with the sun's light taken out, for where the world shades it.
	 */
	public record Image(int edge, int width, int height, double offsetU, double offsetV, int[][] mips, float[] depth, double[] linear, double pitchSin, int[] sunless) {
	}

	private CraftBaker(CraftBlocks blocks, double[] linear, double[] inv, double originY, BlockLooks looks, MapStyle style) {
		this.blocks = blocks;
		this.looks = looks;
		this.style = style;
		this.l = linear;
		this.inv = inv;
		this.ray = new GridRay(blocks);
		this.sunRay = new GridRay(blocks);
		this.sin = Projection.sin();
		this.cos = Projection.cos();
		this.lighting = new Lighting(style);
		this.originY = originY;
	}

	/**
	 * The craft drawn turned by {@code linear} (see {@link #l}) with its grid origin at height
	 * {@code originY}, or null if it can't be. Any thread.
	 */
	public static @Nullable Image bake(CraftBlocks blocks, double[] linear, double originY, BlockLooks looks, MapStyle style, boolean fast) {
		double[] inv = GridRay.invert(linear);
		return inv == null ? null : new CraftBaker(blocks, linear.clone(), inv, originY, looks, style).image(fast);
	}

	/**
	 * What takes a picture of the craft turned by {@code from} to one turned by {@code to}, about
	 * the middle of its grid: {a, b, c, d}, with u' = a u + b v and v' = c u + d v, the best fit
	 * over the corners of its box. Exact for a flat thing like a rotor. {@code out} gets it, and
	 * the result is how far, in map units, a corner still misses.
	 */
	public static double warp(double[] from, double[] to, int sizeX, int sizeY, int sizeZ, double[] out) {
		double sin = Projection.sin();
		double cos = Projection.cos();
		double uu = 0, uv = 0, vv = 0, xu = 0, xv = 0, yu = 0, yv = 0;
		double[][] corners = new double[8][4];
		for (int c = 0; c < 8; c++) {
			double x = ((c & 1) - 0.5) * sizeX;
			double y = ((c >> 1 & 1) - 0.5) * sizeY;
			double z = ((c >> 2 & 1) - 0.5) * sizeZ;
			double u = from[0] * x + from[3] * y + from[6] * z;
			double v = (from[2] * x + from[5] * y + from[8] * z) * sin - (from[1] * x + from[4] * y + from[7] * z) * cos;
			double tu = to[0] * x + to[3] * y + to[6] * z;
			double tv = (to[2] * x + to[5] * y + to[8] * z) * sin - (to[1] * x + to[4] * y + to[7] * z) * cos;
			corners[c] = new double[] {u, v, tu, tv};
			uu += u * u;
			uv += u * v;
			vv += v * v;
			xu += tu * u;
			xv += tu * v;
			yu += tv * u;
			yv += tv * v;
		}
		double det = uu * vv - uv * uv;
		if (Math.abs(det) < 1e-6) {
			out[0] = 1;
			out[1] = 0;
			out[2] = 0;
			out[3] = 1;
			return Double.MAX_VALUE;
		}
		out[0] = (xu * vv - xv * uv) / det;
		out[1] = (xv * uu - xu * uv) / det;
		out[2] = (yu * vv - yv * uv) / det;
		out[3] = (yv * uu - yu * uv) / det;
		double most = 0;
		for (double[] c : corners) {
			most = Math.max(most, Math.max(Math.abs(out[0] * c[0] + out[1] * c[1] - c[2]), Math.abs(out[2] * c[0] + out[3] * c[1] - c[3])));
		}
		return most;
	}

	private @Nullable Image image(boolean fast) {
		CraftBlocks b = this.blocks;
		double minU = Double.MAX_VALUE;
		double maxU = -Double.MAX_VALUE;
		double minV = Double.MAX_VALUE;
		double maxV = -Double.MAX_VALUE;
		for (int c = 0; c < 8; c++) {
			double x = (c & 1) * b.sizeX;
			double y = (c >> 1 & 1) * b.sizeY;
			double z = (c >> 2 & 1) * b.sizeZ;
			double wx = this.l[0] * x + this.l[3] * y + this.l[6] * z;
			double wy = this.l[1] * x + this.l[4] * y + this.l[7] * z;
			double wz = this.l[2] * x + this.l[5] * y + this.l[8] * z;
			double v = wz * this.sin - wy * this.cos;
			minU = Math.min(minU, wx);
			maxU = Math.max(maxU, wx);
			minV = Math.min(minV, v);
			maxV = Math.max(maxV, v);
		}
		double hx = b.sizeX / 2.0;
		double hy = b.sizeY / 2.0;
		double hz = b.sizeZ / 2.0;
		double middleU = this.l[0] * hx + this.l[3] * hy + this.l[6] * hz;
		double middleY = this.l[1] * hx + this.l[4] * hy + this.l[7] * hz;
		double middleZ = this.l[2] * hx + this.l[5] * hy + this.l[8] * hz;
		double middleV = middleZ * this.sin - middleY * this.cos;
		int ppu = Projection.PIXELS_PER_UNIT;
		double offsetU = middleU + (Math.floor((minU - middleU) * ppu) - 1) / ppu;
		double offsetV = middleV + (Math.floor((minV - middleV) * ppu) - 1) / ppu;
		int width = (int) Math.ceil((maxU - offsetU) * ppu) + 1;
		int height = (int) Math.ceil((maxV - offsetV) * ppu) + 1;
		if (width > MAX_PIXELS || height > MAX_PIXELS) {
			return null;
		}
		// Mip levels halve the edge LEVELS - 1 times.
		int step = 1 << (Mipmaps.LEVELS - 1);
		int edge = (Math.max(width, height) + step - 1) / step * step;
		int[] pixels = new int[edge * edge];
		float[] depth = new float[width * height];
		int[] sunless = new int[width * height];
		double[][] samples = fast ? TileBaker.FAST_SAMPLES : TileBaker.SAMPLES;
		double unitsPerPixel = 1.0 / ppu;
		for (int py = 0; py < height; py++) {
			for (int px = 0; px < width; px++) {
				float r = 0;
				float g = 0;
				float bl = 0;
				float a = 0;
				float d = 0;
				float nr = 0;
				float ng = 0;
				float nb = 0;
				int opaque = 0;
				for (int s = 0; s < samples.length; s++) {
					double u = offsetU + (px + samples[s][0]) * unitsPerPixel;
					double v = offsetV + (py + samples[s][1]) * unitsPerPixel;
					this.trace(u, v, s);
					r += this.outR;
					g += this.outG;
					bl += this.outB;
					a += this.outA;
					nr += this.outSunlessR;
					ng += this.outSunlessG;
					nb += this.outSunlessB;
					if (!Float.isNaN(this.outDepth)) {
						d += this.outDepth;
						opaque++;
					}
				}
				depth[py * width + px] = opaque > 0 ? d / opaque : Float.NaN;
				if (a > 0) {
					int alpha = Math.min(255, Math.round(a / samples.length * 255));
					pixels[py * edge + px] = TileBaker.abgr(this.style, r / a, g / a, bl / a, alpha);
					sunless[py * width + px] = TileBaker.abgr(this.style, nr / a, ng / a, nb / a, alpha);
				}
			}
		}
		this.outline(pixels, sunless, depth, width, height, edge);
		float middleDepth = (float) -(this.sin * middleY + this.cos * middleZ);
		for (int i = 0; i < depth.length; i++) {
			depth[i] -= middleDepth;
		}
		return new Image(edge, width, height, offsetU - middleU, offsetV - middleV, Mipmaps.build(pixels, edge), depth, this.l, this.sin, sunless);
	}

	/** Darkens pixels just behind a nearer edge of the craft, as the tiles do. */
	private void outline(int[] pixels, int[] sunless, float[] depth, int width, int height, int edge) {
		float outline = this.style.outline();
		if (outline <= 0) {
			return;
		}
		float keep = 1 - outline;
		boolean[] behind = new boolean[width * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = y * width + x;
				float here = depth[i];
				behind[i] = !Float.isNaN(here)
					&& (x > 0 && TileBaker.nearer(depth[i - 1], here)
					|| x + 1 < width && TileBaker.nearer(depth[i + 1], here)
					|| y > 0 && TileBaker.nearer(depth[i - width], here)
					|| y + 1 < height && TileBaker.nearer(depth[i + width], here));
			}
		}
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if (behind[y * width + x]) {
					pixels[y * edge + x] = TileBaker.darken(pixels[y * edge + x], keep);
					sunless[y * width + x] = TileBaker.darken(sunless[y * width + x], keep);
				}
			}
		}
	}

	/** Traces the camera ray through map point (u, v), relative to the craft's origin. */
	private void trace(double u, double v, int sample) {
		this.outR = 0;
		this.outG = 0;
		this.outB = 0;
		this.outA = 0;
		this.outDepth = Float.NaN;
		this.outSunlessR = 0;
		this.outSunlessG = 0;
		this.outSunlessB = 0;
		// A point on the ray, and the camera's direction: north and down at the pitch.
		double px = u;
		double py = -v * this.cos;
		double pz = v * this.sin;
		double dx = 0;
		double dy = -this.sin;
		double dz = -this.cos;
		double[] m = this.inv;
		double ox = m[0] * px + m[1] * py + m[2] * pz;
		double oy = m[3] * px + m[4] * py + m[5] * pz;
		double oz = m[6] * px + m[7] * py + m[8] * pz;
		double rx = m[0] * dx + m[1] * dy + m[2] * dz;
		double ry = m[3] * dx + m[4] * dy + m[5] * dz;
		double rz = m[6] * dx + m[7] * dy + m[8] * dz;
		CraftBlocks b = this.blocks;
		GridRay ray = this.ray;
		if (!ray.start(ox, oy, oz, rx, ry, rz, -Double.MAX_VALUE)) {
			return;
		}
		double[] o = this.o;
		double[] r = this.r;
		o[0] = ox;
		o[1] = oy;
		o[2] = oz;
		r[0] = rx;
		r[1] = ry;
		r[2] = rz;
		int[] cell = ray.cell;
		// What's still let through, after glass and plants in front.
		float transR = 1;
		float transG = 1;
		float transB = 1;
		float left = 1;
		do {
			int id = b.look(this.looks, CraftBlocks.index(b.sizeX, b.sizeZ, cell[0], cell[1], cell[2]));
			double tEnter = ray.enter;
			double tExit = ray.exit();
			int enteredAxis = ray.axis;
			if (id != 0 && id < this.looks.kind.length && this.looks.kind[id] != BlockLooks.AIR) {
				byte kind = this.looks.kind[id];
				double tHit = tEnter;
				int hitAxis = enteredAxis;
				int shape = kind == BlockLooks.SOLID ? this.looks.shape[id] : 0;
				boolean hit = true;
				if (shape != 0) {
					hit = false;
					float[] boxes = this.looks.shapes[shape];
					double best = Double.MAX_VALUE;
					for (int i = 0; i < boxes.length; i += 6) {
						double enter = tEnter;
						double exit = tExit;
						int enterAxis = enteredAxis;
						for (int a = 0; a < 3 && enter < exit; a++) {
							double lo = cell[a] + boxes[i + a];
							double hi = cell[a] + boxes[i + 3 + a];
							if (Math.abs(r[a]) < 1e-12) {
								if (o[a] < lo || o[a] > hi) {
									enter = exit;
								}
								continue;
							}
							double ta = (lo - o[a]) / r[a];
							double tb = (hi - o[a]) / r[a];
							double near = Math.min(ta, tb);
							if (near > enter) {
								enter = near;
								enterAxis = a;
							}
							exit = Math.min(exit, Math.max(ta, tb));
						}
						if (enter < exit && enter < best) {
							best = enter;
							hitAxis = enterAxis;
						}
					}
					if (best != Double.MAX_VALUE) {
						hit = true;
						tHit = best;
					}
				}
				if (hit) {
					double hx = o[0] + r[0] * tHit;
					double hy = o[1] + r[1] * tHit;
					double hz = o[2] + r[2] * tHit;
					int sign = r[hitAxis] > 0 ? -1 : 1;
					this.shade(id, cell, hitAxis, sign, hx, hy, hz, sample);
					if (kind == BlockLooks.GLASS) {
						// Glass tints what's behind it and lets the ray on.
						transR *= 0.5F + 0.5F * this.albedoR;
						transG *= 0.5F + 0.5F * this.albedoG;
						transB *= 0.5F + 0.5F * this.albedoB;
					} else if (kind == BlockLooks.PLANT) {
						float w = 0.5F * left;
						this.outR += this.shadedR * transR * w;
						this.outG += this.shadedG * transG * w;
						this.outB += this.shadedB * transB * w;
						this.outSunlessR += this.sunlessR * transR * w;
						this.outSunlessG += this.sunlessG * transG * w;
						this.outSunlessB += this.sunlessB * transB * w;
						this.outA += w;
						left -= w;
						// A plant shows, so it's where this pixel's depth is, for what may cover it.
						if (Float.isNaN(this.outDepth)) {
							this.outDepth = (float) tHit;
						}
					} else {
						this.outR += this.shadedR * transR * left;
						this.outG += this.shadedG * transG * left;
						this.outB += this.shadedB * transB * left;
						this.outSunlessR += this.sunlessR * transR * left;
						this.outSunlessG += this.sunlessG * transG * left;
						this.outSunlessB += this.sunlessB * transB * left;
						this.outA += left;
						if (Float.isNaN(this.outDepth)) {
							this.outDepth = (float) tHit;
						}
						return;
					}
				}
			}
		} while (ray.next());
	}

	private boolean solid(int x, int y, int z) {
		CraftBlocks b = this.blocks;
		if (x < 0 || y < 0 || z < 0 || x >= b.sizeX || y >= b.sizeY || z >= b.sizeZ) {
			return false;
		}
		int id = b.look(this.looks, CraftBlocks.index(b.sizeX, b.sizeZ, x, y, z));
		return id != 0 && id < this.looks.kind.length && this.looks.kind[id] == BlockLooks.SOLID;
	}

	/** Lights the face of {@code cell} facing {@code sign} along {@code axis}, hit at (hx, hy, hz) in the craft's frame. */
	private void shade(int id, int[] cell, int axis, int sign, double hx, double hy, double hz, int sample) {
		boolean top = axis == 1;
		this.albedo(id, top);
		float variation = this.style.blockVariation();
		if (variation > 0) {
			float nudge = 1 + variation * TileBaker.blockNoise(cell[0], cell[1], cell[2]);
			this.albedoR *= nudge;
			this.albedoG *= nudge;
			this.albedoB *= nudge;
		}
		// The face's normal in the world: row `axis` of the inverse, as normals turn by its transpose.
		double nx = this.inv[axis * 3] * sign;
		double ny = this.inv[axis * 3 + 1] * sign;
		double nz = this.inv[axis * 3 + 2] * sign;
		double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
		nx /= length;
		ny /= length;
		nz /= length;

		float ao = this.ambientOcclusion(cell, axis, sign, hx, hy, hz);
		double[] j = TileBaker.SUN_JITTER[sample];
		double sx = this.lighting.sunX + j[0];
		double sy = this.lighting.sunY + j[1];
		double sz = this.lighting.sunZ + j[2];
		// Relative to a level top face, as on the tiles, so tops stay as bright whatever the sun's height.
		double lambert = Math.min(1.5, Math.max(0, nx * sx + ny * sy + nz * sz) / sy);
		double e = 1e-4 * sign;
		boolean shadowed = lambert > 0 && this.inShadow(hx + (axis == 0 ? e : 0), hy + (axis == 1 ? e : 0), hz + (axis == 2 ? e : 0), sx, sy, sz);
		// Open sky over a face: all of it on a top, half on a wall, less in a crease.
		float sky = (float) Math.max(0, Math.min(1, 0.5 + 0.5 * ny)) * (ao - 0.6F) / 0.4F;
		// Faces that don't look up read darker, as on the tiles; a tilted top somewhere between.
		float facing = (float) (TileBaker.SIDE + (1 - TileBaker.SIDE) * Math.max(0, ny));
		double y = this.originY + this.l[1] * hx + this.l[4] * hy + this.l[7] * hz;
		this.lighting.surface(lambert, shadowed, ao, sky, this.looks.emission[id] / 15.0F, facing, y, this.light);
		this.shadedR = this.albedoR * this.light[0];
		this.shadedG = this.albedoG * this.light[1];
		this.shadedB = this.albedoB * this.light[2];
		float[] tint = this.style.sunTint();
		float direct = this.light[3] * Lighting.luminance(this.light[0], this.light[1], this.light[2]) / Math.max(1e-6F, Lighting.luminance(tint[0], tint[1], tint[2]));
		this.sunlessR = this.albedoR * Math.max(0, this.light[0] - direct * tint[0]);
		this.sunlessG = this.albedoG * Math.max(0, this.light[1] - direct * tint[1]);
		this.sunlessB = this.albedoB * Math.max(0, this.light[2] - direct * tint[2]);
	}

	private void albedo(int id, boolean top) {
		BlockLooks l = this.looks;
		int base = top ? l.top[id] : l.side[id];
		int tinted = top ? l.topTinted[id] : l.sideTinted[id];
		float w = top ? l.topTintWeight[id] : l.sideTintWeight[id];
		int tint = switch (l.tint[id]) {
			case BlockLooks.TINT_GRASS -> GRASS;
			case BlockLooks.TINT_FOLIAGE -> FOLIAGE;
			case BlockLooks.TINT_DRY_FOLIAGE -> DRY_FOLIAGE;
			case BlockLooks.TINT_WATER -> WATER;
			default -> l.constantTint[id] < 0 ? 0xFFFFFF : l.constantTint[id];
		};
		float[] lin = TileBaker.SRGB_TO_LINEAR;
		float bw = 1 - w;
		this.albedoR = lin[(base >> 16) & 0xFF] * bw + lin[(tinted >> 16) & 0xFF] * lin[(tint >> 16) & 0xFF] * w;
		this.albedoG = lin[(base >> 8) & 0xFF] * bw + lin[(tinted >> 8) & 0xFF] * lin[(tint >> 8) & 0xFF] * w;
		this.albedoB = lin[base & 0xFF] * bw + lin[tinted & 0xFF] * lin[tint & 0xFF] * w;
	}

	/** Smooth corner occlusion on a face, from the blocks around the cell in front of it. */
	private float ambientOcclusion(int[] cell, int axis, int sign, double hx, double hy, double hz) {
		int a1 = (axis + 1) % 3;
		int a2 = (axis + 2) % 3;
		double h1 = a1 == 0 ? hx : a1 == 1 ? hy : hz;
		double h2 = a2 == 0 ? hx : a2 == 1 ? hy : hz;
		double f1 = Math.max(0, Math.min(1, h1 - cell[a1]));
		double f2 = Math.max(0, Math.min(1, h2 - cell[a2]));
		int fx = cell[0] + (axis == 0 ? sign : 0);
		int fy = cell[1] + (axis == 1 ? sign : 0);
		int fz = cell[2] + (axis == 2 ? sign : 0);
		float[] corners = this.corners;
		for (int c = 0; c < 4; c++) {
			int s1 = (c & 1) == 0 ? -1 : 1;
			int s2 = (c & 2) == 0 ? -1 : 1;
			int x1 = fx + (a1 == 0 ? s1 : 0);
			int y1 = fy + (a1 == 1 ? s1 : 0);
			int z1 = fz + (a1 == 2 ? s1 : 0);
			int x2 = fx + (a2 == 0 ? s2 : 0);
			int y2 = fy + (a2 == 1 ? s2 : 0);
			int z2 = fz + (a2 == 2 ? s2 : 0);
			corners[c] = TileBaker.corner(this.solid(x1, y1, z1), this.solid(x2, y2, z2), this.solid(x1 + x2 - fx, y1 + y2 - fy, z1 + z2 - fz));
		}
		float near = (float) (corners[0] + (corners[1] - corners[0]) * f1);
		float far = (float) (corners[2] + (corners[3] - corners[2]) * f1);
		return (float) (near + (far - near) * f2);
	}

	/** Whether the line from (x, y, z) along (rx, ry, rz) crosses the solid block in {@code cell}: its boxes, for one smaller than a cube. */
	private boolean meetsShape(int[] cell, double x, double y, double z, double rx, double ry, double rz) {
		CraftBlocks b = this.blocks;
		int id = b.look(this.looks, CraftBlocks.index(b.sizeX, b.sizeZ, cell[0], cell[1], cell[2]));
		int shape = this.looks.shape[id];
		if (shape == 0) {
			return true;
		}
		float[] boxes = this.looks.shapes[shape];
		double[] o = {x, y, z};
		double[] r = {rx, ry, rz};
		for (int i = 0; i < boxes.length; i += 6) {
			double enter = 0;
			double exit = Double.MAX_VALUE;
			for (int a = 0; a < 3 && enter < exit; a++) {
				double lo = cell[a] + boxes[i + a];
				double hi = cell[a] + boxes[i + 3 + a];
				if (Math.abs(r[a]) < 1e-12) {
					if (o[a] < lo || o[a] > hi) {
						exit = 0;
					}
					continue;
				}
				double ta = (lo - o[a]) / r[a];
				double tb = (hi - o[a]) / r[a];
				enter = Math.max(enter, Math.min(ta, tb));
				exit = Math.min(exit, Math.max(ta, tb));
			}
			if (enter < exit) {
				return true;
			}
		}
		return false;
	}

	/** Whether the craft's own blocks stand between (x, y, z), in its frame, and the sun along the world direction (sx, sy, sz). */
	private boolean inShadow(double x, double y, double z, double sx, double sy, double sz) {
		double[] m = this.inv;
		GridRay ray = this.sunRay;
		double rx = m[0] * sx + m[1] * sy + m[2] * sz;
		double ry = m[3] * sx + m[4] * sy + m[5] * sz;
		double rz = m[6] * sx + m[7] * sy + m[8] * sz;
		if (!ray.start(x, y, z, rx, ry, rz, 0)) {
			return false;
		}
		// The cell the ray starts in holds the face it starts on (a slab's top inside its own cell): past it.
		while (ray.next()) {
			if (this.solid(ray.cell[0], ray.cell[1], ray.cell[2]) && this.meetsShape(ray.cell, x, y, z, rx, ry, rz)) {
				return true;
			}
		}
		return false;
	}
}
