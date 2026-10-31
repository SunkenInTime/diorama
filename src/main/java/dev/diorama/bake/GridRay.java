package dev.diorama.bake;

import org.jspecify.annotations.Nullable;

/**
 * A ray walking a craft's block grid one cell at a time: the cells it passes through in order,
 * with where it enters and leaves each. Reused from ray to ray; not thread-safe.
 */
final class GridRay {
	private final int[] size;
	final int[] cell = new int[3];
	/** The axis the ray crossed to enter the current cell. */
	int axis;
	/** Ray distances where it enters and leaves the current cell. */
	double enter;
	private final double[] o = new double[3];
	private final double[] r = new double[3];
	private final int[] step = new int[3];
	private final double[] next = new double[3];
	private final double[] delta = new double[3];
	/** Where the ray leaves the grid. */
	private double end;

	GridRay(CraftBlocks blocks) {
		this.size = new int[] {blocks.sizeX, blocks.sizeY, blocks.sizeZ};
	}

	/**
	 * Starts at (ox, oy, oz) along (rx, ry, rz), in grid units, from distance {@code from} on:
	 * at the first cell the ray reaches inside the grid. False if it misses the grid.
	 */
	boolean start(double ox, double oy, double oz, double rx, double ry, double rz, double from) {
		double[] o = this.o;
		double[] r = this.r;
		o[0] = ox;
		o[1] = oy;
		o[2] = oz;
		r[0] = rx;
		r[1] = ry;
		r[2] = rz;
		double t0 = from;
		double t1 = Double.MAX_VALUE;
		this.axis = -1;
		for (int a = 0; a < 3; a++) {
			if (Math.abs(r[a]) < 1e-12) {
				if (o[a] < 0 || o[a] > this.size[a]) {
					return false;
				}
				continue;
			}
			double ta = -o[a] / r[a];
			double tb = (this.size[a] - o[a]) / r[a];
			if (Math.min(ta, tb) > t0) {
				t0 = Math.min(ta, tb);
				this.axis = a;
			}
			t1 = Math.min(t1, Math.max(ta, tb));
		}
		if (t0 >= t1) {
			return false;
		}
		this.end = t1;
		this.enter = t0;
		if (this.axis < 0) {
			this.axis = 1;
		}
		double inside = t0 + 1e-7;
		for (int a = 0; a < 3; a++) {
			this.cell[a] = Math.max(0, Math.min(this.size[a] - 1, (int) Math.floor(o[a] + r[a] * inside)));
			if (Math.abs(r[a]) < 1e-12) {
				this.step[a] = 0;
				this.next[a] = Double.MAX_VALUE;
				this.delta[a] = Double.MAX_VALUE;
			} else {
				this.step[a] = r[a] > 0 ? 1 : -1;
				this.next[a] = (this.cell[a] + (r[a] > 0 ? 1 : 0) - o[a]) / r[a];
				this.delta[a] = Math.abs(1 / r[a]);
			}
		}
		return true;
	}

	/** Where the ray leaves the current cell. */
	double exit() {
		return Math.min(this.end, Math.min(this.next[0], Math.min(this.next[1], this.next[2])));
	}

	/** Moves to the next cell; false once the ray has left the grid. */
	boolean next() {
		int a = this.next[0] < this.next[1] ? (this.next[0] < this.next[2] ? 0 : 2) : (this.next[1] < this.next[2] ? 1 : 2);
		if (this.next[a] >= this.end) {
			return false;
		}
		this.cell[a] += this.step[a];
		if (this.cell[a] < 0 || this.cell[a] >= this.size[a]) {
			return false;
		}
		this.enter = this.next[a];
		this.next[a] += this.delta[a];
		this.axis = a;
		return true;
	}

	/** The world-to-grid inverse of a turn whose axes are linear[0..2], [3..5], [6..8], rows first; null if it can't be undone. */
	static double @Nullable [] invert(double[] l) {
		double a = l[0], b = l[3], c = l[6];
		double d = l[1], e = l[4], f = l[7];
		double g = l[2], h = l[5], i = l[8];
		double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
		if (!(Math.abs(det) > 1e-9) || !Double.isFinite(det)) {
			return null;
		}
		double k = 1 / det;
		return new double[] {
			(e * i - f * h) * k, (c * h - b * i) * k, (b * f - c * e) * k,
			(f * g - d * i) * k, (a * i - c * g) * k, (c * d - a * f) * k,
			(d * h - e * g) * k, (b * g - a * h) * k, (a * e - b * d) * k,
		};
	}
}
