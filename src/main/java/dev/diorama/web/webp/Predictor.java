package dev.diorama.web.webp;

/**
 * The VP8L predictor transform (RFC 9649 4.1): each pixel stored as its difference from a guess
 * made from the pixels left and above it, with one of 14 ways of guessing per block.
 */
final class Predictor {
	private Predictor() {
	}

	/** Rough bits a residual byte costs: none for no change, more the further it is from 0 either way. */
	private static final int[] COST = new int[256];

	static {
		for (int v = 0; v < 256; v++) {
			int m = Math.min(v, 256 - v);
			COST[v] = m == 0 ? 0 : (int) Math.round(16 * (2.0 + 1.5 * (Math.log(1 + m) / Math.log(2))));
		}
	}

	static int average2(int a, int b) {
		return (((a ^ b) & 0xFEFEFEFE) >>> 1) + (a & b);
	}

	private static int clamp(int v) {
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}

	private static int select(int left, int top, int topLeft) {
		int toLeft = Math.abs((top >>> 24) - (topLeft >>> 24)) + Math.abs((top >> 16 & 0xFF) - (topLeft >> 16 & 0xFF))
			+ Math.abs((top >> 8 & 0xFF) - (topLeft >> 8 & 0xFF)) + Math.abs((top & 0xFF) - (topLeft & 0xFF));
		int toTop = Math.abs((left >>> 24) - (topLeft >>> 24)) + Math.abs((left >> 16 & 0xFF) - (topLeft >> 16 & 0xFF))
			+ Math.abs((left >> 8 & 0xFF) - (topLeft >> 8 & 0xFF)) + Math.abs((left & 0xFF) - (topLeft & 0xFF));
		return toLeft < toTop ? left : top;
	}

	private static int clampAddSubtractFull(int a, int b, int c) {
		return clamp((a >>> 24) + (b >>> 24) - (c >>> 24)) << 24
			| clamp((a >> 16 & 0xFF) + (b >> 16 & 0xFF) - (c >> 16 & 0xFF)) << 16
			| clamp((a >> 8 & 0xFF) + (b >> 8 & 0xFF) - (c >> 8 & 0xFF)) << 8
			| clamp((a & 0xFF) + (b & 0xFF) - (c & 0xFF));
	}

	private static int half(int a, int b) {
		return clamp(a + (a - b) / 2);
	}

	private static int clampAddSubtractHalf(int a, int b) {
		return half(a >>> 24, b >>> 24) << 24 | half(a >> 16 & 0xFF, b >> 16 & 0xFF) << 16
			| half(a >> 8 & 0xFF, b >> 8 & 0xFF) << 8 | half(a & 0xFF, b & 0xFF);
	}

	/** The guess of mode 0 to 13 from the pixels left, top, top right and top left. */
	static int predict(int mode, int left, int top, int topRight, int topLeft) {
		switch (mode) {
			case 0:
				return 0xFF000000;
			case 1:
				return left;
			case 2:
				return top;
			case 3:
				return topRight;
			case 4:
				return topLeft;
			case 5:
				return average2(average2(left, topRight), top);
			case 6:
				return average2(left, topLeft);
			case 7:
				return average2(left, top);
			case 8:
				return average2(topLeft, top);
			case 9:
				return average2(top, topRight);
			case 10:
				return average2(average2(left, topLeft), average2(top, topRight));
			case 11:
				return select(left, top, topLeft);
			case 12:
				return clampAddSubtractFull(left, top, topLeft);
			default:
				return clampAddSubtractHalf(average2(left, top), topLeft);
		}
	}

	/** Guesses for pixels x0 to x1 - 1 of row y (y and x0 at least 1) with one mode, into {@code out}. */
	private static void predictRow(int[] px, int w, int y, int x0, int x1, int mode, int[] out) {
		int base = y * w;
		switch (mode) {
			case 0:
				for (int x = x0; x < x1; x++) out[x] = 0xFF000000;
				break;
			case 1:
				for (int x = x0; x < x1; x++) out[x] = px[base + x - 1];
				break;
			case 2:
				for (int x = x0; x < x1; x++) out[x] = px[base + x - w];
				break;
			case 3:
				for (int x = x0; x < x1; x++) out[x] = px[base + x - w + 1];
				break;
			case 4:
				for (int x = x0; x < x1; x++) out[x] = px[base + x - w - 1];
				break;
			case 5:
				for (int x = x0; x < x1; x++) out[x] = average2(average2(px[base + x - 1], px[base + x - w + 1]), px[base + x - w]);
				break;
			case 6:
				for (int x = x0; x < x1; x++) out[x] = average2(px[base + x - 1], px[base + x - w - 1]);
				break;
			case 7:
				for (int x = x0; x < x1; x++) out[x] = average2(px[base + x - 1], px[base + x - w]);
				break;
			case 8:
				for (int x = x0; x < x1; x++) out[x] = average2(px[base + x - w - 1], px[base + x - w]);
				break;
			case 9:
				for (int x = x0; x < x1; x++) out[x] = average2(px[base + x - w], px[base + x - w + 1]);
				break;
			case 10:
				for (int x = x0; x < x1; x++) {
					out[x] = average2(average2(px[base + x - 1], px[base + x - w - 1]), average2(px[base + x - w], px[base + x - w + 1]));
				}
				break;
			case 11:
				for (int x = x0; x < x1; x++) out[x] = select(px[base + x - 1], px[base + x - w], px[base + x - w - 1]);
				break;
			case 12:
				for (int x = x0; x < x1; x++) out[x] = clampAddSubtractFull(px[base + x - 1], px[base + x - w], px[base + x - w - 1]);
				break;
			default:
				for (int x = x0; x < x1; x++) out[x] = clampAddSubtractHalf(average2(px[base + x - 1], px[base + x - w]), px[base + x - w - 1]);
				break;
		}
	}

	/** Guesses for row y, x0 to x1 - 1, with the edge rules: top left black, top row from the left, left column from above. */
	private static void predictSpan(int[] px, int w, int y, int x0, int x1, int mode, int[] out) {
		if (y == 0) {
			for (int x = x0; x < x1; x++) {
				out[x] = x == 0 ? 0xFF000000 : px[x - 1];
			}
			return;
		}
		int from = x0;
		if (x0 == 0) {
			out[0] = px[y * w - w];
			from = 1;
		}
		if (from < x1) {
			predictRow(px, w, y, from, x1, mode, out);
		}
	}

	/** Returns the residuals and fills {@code modes}, one per block as a 0xFF00mm00 pixel, with the cheapest-looking mode. */
	static int[] apply(int[] px, int w, int h, int bits, int[] modes) {
		int size = 1 << bits;
		int blocksWide = (w + size - 1) >> bits;
		int blocksHigh = (h + size - 1) >> bits;
		int[] residuals = new int[w * h];
		int[] guess = new int[w];
		for (int by = 0; by < blocksHigh; by++) {
			for (int bx = 0; bx < blocksWide; bx++) {
				int x0 = bx << bits;
				int y0 = by << bits;
				int x1 = Math.min(w, x0 + size);
				int y1 = Math.min(h, y0 + size);
				int bestMode = 0;
				int bestCost = Integer.MAX_VALUE;
				for (int mode = 0; mode < 14; mode++) {
					int cost = 0;
					for (int y = y0; y < y1 && cost < bestCost; y++) {
						predictSpan(px, w, y, x0, x1, mode, guess);
						int base = y * w;
						for (int x = x0; x < x1; x++) {
							int r = Vp8lEncoder.subtract(px[base + x], guess[x]);
							cost += COST[r >>> 24] + COST[r >> 16 & 0xFF] + COST[r >> 8 & 0xFF] + COST[r & 0xFF];
						}
					}
					if (cost < bestCost) {
						bestCost = cost;
						bestMode = mode;
					}
				}
				modes[by * blocksWide + bx] = 0xFF000000 | bestMode << 8;
				for (int y = y0; y < y1; y++) {
					predictSpan(px, w, y, x0, x1, bestMode, guess);
					int base = y * w;
					for (int x = x0; x < x1; x++) {
						residuals[base + x] = Vp8lEncoder.subtract(px[base + x], guess[x]);
					}
				}
			}
		}
		return residuals;
	}
}
