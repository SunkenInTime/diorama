package dev.diorama.web.webp;

import java.util.Arrays;

/**
 * Greedy LZ77 over pixels with a hash chain, one step lazy. At each pixel it first tries copying
 * from one pixel back and one row back, the two that matter most for a map's blocks of colour.
 */
final class Lz77 {
	static final int MAX_LENGTH = 4096;
	private static final int HASH_BITS = 16;
	private static final int MAX_DISTANCE = (1 << 20) - 120;
	/** How many earlier places with the same hash are looked at. */
	private static final int CHAIN_DEPTH = 8;

	/** The parse: a length of 0 is one literal pixel; above 0, that many pixels copied, from the distance code. */
	static final class Result {
		int[] length;
		int[] distance;
		int count;

		Result(int capacity) {
			this.length = new int[capacity];
			this.distance = new int[capacity];
		}

		void add(int length, int distance) {
			if (this.count == this.length.length) {
				this.length = Arrays.copyOf(this.length, this.count * 2);
				this.distance = Arrays.copyOf(this.distance, this.count * 2);
			}
			this.length[this.count] = length;
			this.distance[this.count++] = distance;
		}
	}

	private Lz77() {
	}

	/**
	 * The 120 nearby places with short distance codes, as x, y pairs (RFC 9649 3.6.2.2.1): code
	 * {@code c} is the pair at {@code 2 * (c - 1)}.
	 */
	static final int[] NEARBY = {
		0, 1, 1, 0, 1, 1, -1, 1, 0, 2, 2, 0, 1, 2, -1, 2, 2, 1, -2, 1, 2, 2, -2, 2, 0, 3, 3, 0, 1, 3, -1, 3,
		3, 1, -3, 1, 2, 3, -2, 3, 3, 2, -3, 2, 0, 4, 4, 0, 1, 4, -1, 4, 4, 1, -4, 1, 3, 3, -3, 3, 2, 4, -2, 4,
		4, 2, -4, 2, 0, 5, 3, 4, -3, 4, 4, 3, -4, 3, 5, 0, 1, 5, -1, 5, 5, 1, -5, 1, 2, 5, -2, 5, 5, 2, -5, 2,
		4, 4, -4, 4, 3, 5, -3, 5, 5, 3, -5, 3, 0, 6, 6, 0, 1, 6, -1, 6, 6, 1, -6, 1, 2, 6, -2, 6, 6, 2, -6, 2,
		4, 5, -4, 5, 5, 4, -5, 4, 3, 6, -3, 6, 6, 3, -6, 3, 0, 7, 7, 0, 1, 7, -1, 7, 5, 5, -5, 5, 7, 1, -7, 1,
		4, 6, -4, 6, 6, 4, -6, 4, 2, 7, -2, 7, 7, 2, -7, 2, 3, 7, -3, 7, 7, 3, -7, 3, 5, 6, -5, 6, 6, 5, -6, 5,
		8, 0, 4, 7, -4, 7, 7, 4, -7, 4, 8, 1, 8, 2, 6, 6, -6, 6, 8, 3, 5, 7, -5, 7, 7, 5, -7, 5, 8, 4, 6, 7,
		-6, 7, 7, 6, -7, 6, 8, 5, 7, 7, -7, 7, 8, 6, 8, 7
	};

	/** For each distance in pixels up to 8 rows and a bit, its shortest nearby code, or 0. */
	private static int[] distanceCodes(int width) {
		int[] codes = new int[8 * width + 9];
		// From the last code down, so the smallest stays.
		for (int c = 120; c >= 1; c--) {
			int distance = Math.max(1, NEARBY[2 * (c - 1)] + NEARBY[2 * (c - 1) + 1] * width);
			if (distance < codes.length) {
				codes[distance] = c;
			}
		}
		return codes;
	}

	static Result parse(int[] pixels, int width, int height) {
		int n = width * height;
		Result result = new Result(Math.max(16, n / 4));
		int[] codeOf = distanceCodes(width);
		if (n < 3) {
			for (int i = 0; i < n; i++) {
				result.add(0, 0);
			}
			return result;
		}
		int[] head = new int[1 << HASH_BITS];
		Arrays.fill(head, -1);
		int[] previous = new int[n];
		int i = 0;
		int[] best = new int[2];
		int[] next = new int[2];
		boolean haveNext = false;
		while (i < n) {
			if (haveNext) {
				best[0] = next[0];
				best[1] = next[1];
				haveNext = false;
			} else {
				find(pixels, n, width, i, head, previous, best);
			}
			insert(pixels, n, i, head, previous);
			int length = best[0];
			if (length >= 2 && length < 32 && i + 1 < n) {
				// A longer copy one pixel on is worth a literal here.
				find(pixels, n, width, i + 1, head, previous, next);
				if (next[0] > length + 1) {
					result.add(0, 0);
					i++;
					haveNext = true;
					continue;
				}
			}
			if (length >= 2) {
				int distance = best[1];
				int code = distance < codeOf.length && codeOf[distance] != 0 ? codeOf[distance] : distance + 120;
				if (length == 2 && code > 120) {
					// Two pixels from far away cost more than two literals.
					result.add(0, 0);
					i++;
					continue;
				}
				result.add(length, code);
				int end = i + length;
				for (int k = i + 1; k < end; k++) {
					insert(pixels, n, k, head, previous);
				}
				i = end;
			} else {
				result.add(0, 0);
				i++;
			}
		}
		return result;
	}

	private static int hash(int[] pixels, int i) {
		return ((pixels[i] * 0x9E3779B1) ^ (pixels[i + 1] * 0x85EBCA6B)) >>> (32 - HASH_BITS);
	}

	private static void insert(int[] pixels, int n, int i, int[] head, int[] previous) {
		if (i + 1 >= n) {
			return;
		}
		int hash = hash(pixels, i);
		previous[i] = head[hash];
		head[hash] = i;
	}

	/** The longest copy for pixel {@code i}, into {@code out} as {length, distance}; length 0 for none. */
	private static void find(int[] pixels, int n, int width, int i, int[] head, int[] previous, int[] out) {
		int maxLength = Math.min(MAX_LENGTH, n - i);
		int bestLength = 0;
		int bestDistance = 0;
		if (i >= 1) {
			int length = match(pixels, i, i - 1, maxLength);
			if (length > bestLength) {
				bestLength = length;
				bestDistance = 1;
			}
		}
		if (i >= width && width > 1) {
			int length = match(pixels, i, i - width, maxLength);
			if (length > bestLength) {
				bestLength = length;
				bestDistance = width;
			}
		}
		if (i + 1 < n && bestLength < maxLength) {
			int j = head[hash(pixels, i)];
			int left = CHAIN_DEPTH;
			while (j >= 0 && left-- > 0) {
				int distance = i - j;
				if (distance > MAX_DISTANCE) {
					break;
				}
				if (bestLength == 0 || pixels[j + bestLength] == pixels[i + bestLength]) {
					int length = match(pixels, i, j, maxLength);
					if (length > bestLength) {
						bestLength = length;
						bestDistance = distance;
						if (length == maxLength) {
							break;
						}
					}
				}
				j = previous[j];
			}
		}
		out[0] = bestLength;
		out[1] = bestDistance;
	}

	private static int match(int[] pixels, int a, int b, int max) {
		int length = 0;
		while (length < max && pixels[a + length] == pixels[b + length]) {
			length++;
		}
		return length;
	}
}
