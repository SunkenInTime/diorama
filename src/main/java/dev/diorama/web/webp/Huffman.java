package dev.diorama.web.webp;

import java.util.Arrays;

/** A canonical Huffman code for one VP8L alphabet, built from symbol counts: its lengths, codes, and how it's stored. */
final class Huffman {
	/** The order code length code lengths are stored in (RFC 9649 3.7.2.1.2). */
	static final int[] CODE_LENGTH_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

	final int[] lengths;
	/** Codes with their bits reversed, for a writer that writes the lowest bit first. */
	private final int[] codes;
	private final int used;
	private final int first;
	private final int second;

	private Huffman(int[] lengths) {
		this.lengths = lengths;
		int n = lengths.length;
		int used = 0;
		int first = -1;
		int second = -1;
		for (int i = 0; i < n; i++) {
			if (lengths[i] > 0) {
				if (used == 0) {
					first = i;
				} else if (used == 1) {
					second = i;
				}
				used++;
			}
		}
		this.used = used;
		this.first = first;
		this.second = second;
		this.codes = new int[n];
		if (used <= 1) {
			// One symbol takes no bits to read.
			return;
		}
		int[] count = new int[16];
		for (int length : lengths) {
			count[length]++;
		}
		count[0] = 0;
		int[] next = new int[16];
		int code = 0;
		for (int length = 1; length < 16; length++) {
			code = (code + count[length - 1]) << 1;
			next[length] = code;
		}
		for (int i = 0; i < n; i++) {
			int length = lengths[i];
			if (length > 0) {
				this.codes[i] = Integer.reverse(next[length]++) >>> (32 - length);
			}
		}
	}

	static Huffman build(int[] counts, int maxLength) {
		return new Huffman(lengths(counts, maxLength));
	}

	void write(BitWriter out, int symbol) {
		if (this.used > 1) {
			out.put(this.codes[symbol], this.lengths[symbol]);
		}
	}

	/** Code lengths of at most {@code maxLength}; a lone symbol gets length 1, which costs nothing to read. */
	static int[] lengths(int[] counts, int maxLength) {
		int n = counts.length;
		int[] lengths = new int[n];
		int nonzero = 0;
		int last = -1;
		for (int i = 0; i < n; i++) {
			if (counts[i] > 0) {
				nonzero++;
				last = i;
			}
		}
		if (nonzero == 0) {
			return lengths;
		}
		if (nonzero == 1) {
			lengths[last] = 1;
			return lengths;
		}
		int[] symbols = new int[nonzero];
		long[] weights = new long[nonzero];
		int k = 0;
		for (int i = 0; i < n; i++) {
			if (counts[i] > 0) {
				symbols[k++] = i;
			}
		}
		// Too deep: rare symbols count as more common, until it fits.
		long least = 1;
		while (true) {
			for (int i = 0; i < nonzero; i++) {
				weights[i] = Math.max(counts[symbols[i]], least);
			}
			int[] depths = depths(weights);
			int max = 0;
			for (int depth : depths) {
				max = Math.max(max, depth);
			}
			if (max <= maxLength) {
				for (int i = 0; i < nonzero; i++) {
					lengths[symbols[i]] = depths[i];
				}
				return lengths;
			}
			least *= 2;
		}
	}

	/** Huffman depths for the weights: the two-queue method over leaves sorted by weight. */
	private static int[] depths(long[] weights) {
		int n = weights.length;
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> weights[a] != weights[b] ? Long.compare(weights[a], weights[b]) : Integer.compare(a, b));
		int total = 2 * n - 1;
		long[] weight = new long[total];
		int[] parent = new int[total];
		for (int i = 0; i < n; i++) {
			weight[i] = weights[order[i]];
		}
		int leaf = 0;
		int inner = n;
		int next = n;
		while (next < total) {
			int a;
			int b;
			if (leaf < n && (inner >= next || weight[leaf] <= weight[inner])) {
				a = leaf++;
			} else {
				a = inner++;
			}
			if (leaf < n && (inner >= next || weight[leaf] <= weight[inner])) {
				b = leaf++;
			} else {
				b = inner++;
			}
			weight[next] = weight[a] + weight[b];
			parent[a] = next;
			parent[b] = next;
			next++;
		}
		int[] depth = new int[total];
		for (int i = total - 2; i >= 0; i--) {
			depth[i] = depth[parent[i]] + 1;
		}
		int[] out = new int[n];
		for (int i = 0; i < n; i++) {
			out[order[i]] = depth[i];
		}
		return out;
	}

	/** Stores the code: the short form for one or two symbols under 256, else the code lengths, themselves coded. */
	void writeHeader(BitWriter out) {
		if (this.used == 0) {
			// Nothing uses it: one symbol, 0.
			out.put(1, 1);
			out.put(0, 1);
			out.put(0, 1);
			out.put(0, 1);
			return;
		}
		if (this.used <= 2 && this.first < 256 && (this.used == 1 || this.second < 256)) {
			out.put(1, 1);
			out.put(this.used - 1, 1);
			if (this.first < 2) {
				out.put(0, 1);
				out.put(this.first, 1);
			} else {
				out.put(1, 1);
				out.put(this.first, 8);
			}
			if (this.used == 2) {
				out.put(this.second, 8);
			}
			return;
		}
		out.put(0, 1);
		// Runs of lengths as 16 (the last length again), 17 and 18 (zeros).
		int n = this.lengths.length;
		int[] tokens = new int[n];
		int[] extra = new int[n];
		int count = 0;
		int previous = 8;
		int i = 0;
		while (i < n) {
			int length = this.lengths[i];
			int run = 1;
			while (i + run < n && this.lengths[i + run] == length) {
				run++;
			}
			i += run;
			if (length == 0) {
				while (run > 0) {
					if (run >= 11) {
						int r = Math.min(run, 138);
						tokens[count] = 18;
						extra[count++] = r - 11;
						run -= r;
					} else if (run >= 3) {
						int r = Math.min(run, 10);
						tokens[count] = 17;
						extra[count++] = r - 3;
						run -= r;
					} else {
						tokens[count++] = 0;
						run--;
					}
				}
			} else {
				if (length != previous) {
					tokens[count++] = length;
					run--;
					previous = length;
				}
				while (run >= 3) {
					int r = Math.min(run, 6);
					tokens[count] = 16;
					extra[count++] = r - 3;
					run -= r;
				}
				while (run > 0) {
					tokens[count++] = length;
					run--;
				}
			}
		}
		int[] histogram = new int[19];
		for (int t = 0; t < count; t++) {
			histogram[tokens[t]]++;
		}
		Huffman lengthCode = new Huffman(lengths(histogram, 7));
		int stored = 19;
		while (stored > 4 && lengthCode.lengths[CODE_LENGTH_ORDER[stored - 1]] == 0) {
			stored--;
		}
		out.put(stored - 4, 4);
		for (int k = 0; k < stored; k++) {
			out.put(lengthCode.lengths[CODE_LENGTH_ORDER[k]], 3);
		}
		// Every symbol's length follows, no shorter count.
		out.put(0, 1);
		for (int t = 0; t < count; t++) {
			int token = tokens[t];
			lengthCode.write(out, token);
			if (token == 16) {
				out.put(extra[t], 2);
			} else if (token == 17) {
				out.put(extra[t], 3);
			} else if (token == 18) {
				out.put(extra[t], 7);
			}
		}
	}
}
