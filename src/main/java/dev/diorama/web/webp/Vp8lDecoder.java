package dev.diorama.web.webp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads lossless WebP (VP8L, RFC 9649) in plain Java: all of the format, so it reads what
 * {@link Vp8lEncoder} writes and what libwebp writes too. Lossy and extended WebP (animation, a
 * separate alpha chunk, metadata) are refused with an {@link IOException}, as is a damaged file.
 */
public final class Vp8lDecoder {
	/** The most pixels a picture may have: more than any tile, less than runs a server out of memory. */
	private static final long MAX_PIXELS = 1L << 26;

	private Vp8lDecoder() {
	}

	/** A decoded picture, 0xAARRGGBB row by row. */
	public record Picture(int width, int height, int[] argb) {
	}

	public static Picture decode(byte[] file) throws IOException {
		if (file.length < 20 || !tag(file, 0, "RIFF") || !tag(file, 8, "WEBP")) {
			throw new IOException("not a WebP file");
		}
		long riff = le32(file, 4) & 0xFFFFFFFFL;
		if (riff + 8 > file.length || riff < 12) {
			throw new IOException("WebP file cut short");
		}
		if (!tag(file, 12, "VP8L")) {
			String kind = new String(file, 12, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
			throw new IOException(kind.equals("VP8 ") ? "lossy WebP, which isn't read here" : "WebP with a " + kind.trim() + " chunk, which isn't read here");
		}
		long chunk = le32(file, 16) & 0xFFFFFFFFL;
		if (20 + chunk > riff + 8) {
			throw new IOException("WebP file cut short");
		}
		return decodeVp8l(new Bits(file, 20, (int) (20 + chunk)));
	}

	private static boolean tag(byte[] bytes, int at, String tag) {
		for (int i = 0; i < 4; i++) {
			if (bytes[at + i] != (byte) tag.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	private static int le32(byte[] bytes, int at) {
		return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8 | (bytes[at + 2] & 0xFF) << 16 | (bytes[at + 3] & 0xFF) << 24;
	}

	/** One transform as read, with the picture's width when it was read (colour indexing narrows it for the rest). */
	private record Transform(int type, int bits, int width, int @Nullable [] data) {
	}

	private static final int PREDICTOR = 0;
	private static final int CROSS_COLOUR = 1;
	private static final int SUBTRACT_GREEN = 2;
	private static final int COLOUR_INDEXING = 3;

	private static Picture decodeVp8l(Bits in) throws IOException {
		if (in.read(8) != 0x2F) {
			throw new IOException("not a lossless WebP stream");
		}
		int width = in.read(14) + 1;
		int height = in.read(14) + 1;
		in.read(1);
		if (in.read(3) != 0) {
			throw new IOException("lossless WebP of an unknown version");
		}
		if ((long) width * height > MAX_PIXELS) {
			throw new IOException("WebP of " + width + "x" + height + " is too big");
		}
		List<Transform> transforms = new ArrayList<>();
		boolean[] seen = new boolean[4];
		int xsize = width;
		while (in.read(1) == 1) {
			int type = in.read(2);
			if (seen[type]) {
				throw new IOException("a WebP transform used twice");
			}
			seen[type] = true;
			if (type == PREDICTOR || type == CROSS_COLOUR) {
				int bits = in.read(3) + 2;
				int[] data = decodeImage(in, divide(xsize, bits), divide(height, bits), false);
				transforms.add(new Transform(type, bits, xsize, data));
			} else if (type == SUBTRACT_GREEN) {
				transforms.add(new Transform(type, 0, xsize, null));
			} else {
				int size = in.read(8) + 1;
				int[] palette = decodeImage(in, size, 1, false);
				for (int i = 1; i < size; i++) {
					palette[i] = add(palette[i], palette[i - 1]);
				}
				int bits = size <= 2 ? 3 : size <= 4 ? 2 : size <= 16 ? 1 : 0;
				transforms.add(new Transform(type, bits, xsize, palette));
				xsize = divide(xsize, bits);
			}
		}
		int[] pixels = decodeImage(in, xsize, height, true);
		for (int t = transforms.size() - 1; t >= 0; t--) {
			Transform transform = transforms.get(t);
			int w = transform.width();
			switch (transform.type()) {
				case PREDICTOR -> unpredict(pixels, w, height, transform.bits(), transform.data());
				case CROSS_COLOUR -> uncrossColour(pixels, w, height, transform.bits(), transform.data());
				case SUBTRACT_GREEN -> addGreen(pixels);
				default -> pixels = unindex(pixels, w, height, transform.bits(), transform.data());
			}
		}
		return new Picture(width, height, pixels);
	}

	private static int divide(int size, int bits) {
		return (size + (1 << bits) - 1) >> bits;
	}

	/** Each channel of a plus b, modulo 256. */
	private static int add(int a, int b) {
		int alphaGreen = (a & 0xFF00FF00) + (b & 0xFF00FF00);
		int redBlue = (a & 0x00FF00FF) + (b & 0x00FF00FF);
		return (alphaGreen & 0xFF00FF00) | (redBlue & 0x00FF00FF);
	}

	private static void unpredict(int[] px, int w, int h, int bits, int[] modes) {
		int blocksWide = divide(w, bits);
		for (int y = 0; y < h; y++) {
			int row = y * w;
			int blockRow = (y >> bits) * blocksWide;
			for (int x = 0; x < w; x++) {
				int i = row + x;
				int guess;
				if (y == 0) {
					guess = x == 0 ? 0xFF000000 : px[i - 1];
				} else if (x == 0) {
					guess = px[i - w];
				} else {
					int mode = modes[blockRow + (x >> bits)] >> 8 & 0xF;
					// 14 and 15 aren't modes; libwebp reads them as 0.
					guess = mode > 13 ? 0xFF000000 : Predictor.predict(mode, px[i - 1], px[i - w], px[i - w + 1], px[i - w - 1]);
				}
				px[i] = add(px[i], guess);
			}
		}
	}

	private static int delta(int t, int c) {
		return ((byte) t * (byte) c) >> 5;
	}

	private static void uncrossColour(int[] px, int w, int h, int bits, int[] elements) {
		int blocksWide = divide(w, bits);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int element = elements[(y >> bits) * blocksWide + (x >> bits)];
				int i = y * w + x;
				int p = px[i];
				int green = p >> 8 & 0xFF;
				int red = (p >> 16) + delta(element, green) & 0xFF;
				int blue = p + delta(element >> 8, green) + delta(element >> 16, red) & 0xFF;
				px[i] = (p & 0xFF00FF00) | red << 16 | blue;
			}
		}
	}

	private static void addGreen(int[] px) {
		for (int i = 0; i < px.length; i++) {
			int p = px[i];
			int green = p >> 8 & 0xFF;
			px[i] = (p & 0xFF00FF00) | ((p >> 16) + green & 0xFF) << 16 | (p + green & 0xFF);
		}
	}

	/** Palette indexes, several to a pixel when {@code bits} is above 0, to colours; an index past the palette is transparent black. */
	private static int[] unindex(int[] packed, int w, int h, int bits, int[] palette) {
		int[] out = new int[w * h];
		int packedWidth = divide(w, bits);
		int each = 8 >> bits;
		int mask = (1 << each) - 1;
		int perPixel = (1 << bits) - 1;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int green = packed[y * packedWidth + (x >> bits)] >> 8 & 0xFF;
				int index = green >> ((x & perPixel) * each) & mask;
				out[y * w + x] = index < palette.length ? palette[index] : 0;
			}
		}
		return out;
	}

	private static final int LENGTH_CODES = 24;
	private static final int DISTANCE_CODES = 40;

	/**
	 * One entropy-coded picture: the main one (which may use different Huffman codes per region) or
	 * a transform's data.
	 */
	private static int[] decodeImage(Bits in, int w, int h, boolean main) throws IOException {
		int cacheBits = 0;
		if (in.read(1) == 1) {
			cacheBits = in.read(4);
			if (cacheBits < 1 || cacheBits > 11) {
				throw new IOException("WebP colour cache of " + cacheBits + " bits");
			}
		}
		int metaBits = 0;
		int metaWidth = 0;
		int[] meta = null;
		int groupCount = 1;
		if (main && in.read(1) == 1) {
			metaBits = in.read(3) + 2;
			metaWidth = divide(w, metaBits);
			meta = decodeImage(in, metaWidth, divide(h, metaBits), false);
			for (int i = 0; i < meta.length; i++) {
				meta[i] = meta[i] >> 8 & 0xFFFF;
				groupCount = Math.max(groupCount, meta[i] + 1);
			}
		}
		int cacheSize = cacheBits > 0 ? 1 << cacheBits : 0;
		// Groups no region uses are read past, but not kept: there can be many.
		boolean[] used = new boolean[groupCount];
		if (meta == null) {
			used[0] = true;
		} else {
			for (int group : meta) {
				used[group] = true;
			}
		}
		Code[][] groups = new Code[groupCount][];
		for (int g = 0; g < groupCount; g++) {
			Code green = Code.read(in, 256 + LENGTH_CODES + cacheSize, used[g]);
			Code red = Code.read(in, 256, used[g]);
			Code blue = Code.read(in, 256, used[g]);
			Code alpha = Code.read(in, 256, used[g]);
			Code distance = Code.read(in, DISTANCE_CODES, used[g]);
			if (used[g]) {
				groups[g] = new Code[] {green, red, blue, alpha, distance};
			}
		}
		int n = w * h;
		int[] out = new int[n];
		int[] cache = cacheSize > 0 ? new int[cacheSize] : null;
		int shift = 32 - cacheBits;
		Code[] group = groups[0];
		int pos = 0;
		while (pos < n) {
			if (meta != null) {
				int y = pos / w;
				int x = pos - y * w;
				group = groups[meta[(y >> metaBits) * metaWidth + (x >> metaBits)]];
			}
			int symbol = group[0].read(in);
			if (symbol < 256) {
				int red = group[1].read(in);
				int blue = group[2].read(in);
				int alpha = group[3].read(in);
				int p = alpha << 24 | red << 16 | symbol << 8 | blue;
				out[pos++] = p;
				if (cache != null) {
					cache[(0x1E35A7BD * p) >>> shift] = p;
				}
			} else if (symbol < 256 + LENGTH_CODES) {
				int length = value(in, symbol - 256);
				int code = value(in, group[4].read(in));
				int distance;
				if (code > 120) {
					distance = code - 120;
				} else {
					distance = Math.max(1, Lz77.NEARBY[2 * (code - 1)] + Lz77.NEARBY[2 * (code - 1) + 1] * w);
				}
				if (distance > pos || length > n - pos) {
					throw new IOException("WebP copy from outside the picture");
				}
				for (int k = 0; k < length; k++) {
					int p = out[pos - distance];
					out[pos++] = p;
					if (cache != null) {
						cache[(0x1E35A7BD * p) >>> shift] = p;
					}
				}
			} else {
				// The alphabet's size keeps the index inside the cache.
				out[pos++] = cache[symbol - 256 - LENGTH_CODES];
			}
		}
		return out;
	}

	/** A length or distance code from its prefix and the extra bits after it. */
	private static int value(Bits in, int prefix) throws IOException {
		if (prefix < 4) {
			return prefix + 1;
		}
		int extra = (prefix - 2) >> 1;
		int offset = (2 + (prefix & 1)) << extra;
		return offset + in.read(extra) + 1;
	}

	/** A canonical Huffman code as read from the stream, with a lookup table for codes up to {@link #TABLE_BITS} long. */
	private static final class Code {
		private static final int TABLE_BITS = 10;
		/** Symbol shifted left 4 and length, for each value of the next {@link #TABLE_BITS} bits; -1 where the code is longer. */
		private final int[] table;
		/** The one symbol of a code with only one, which takes no bits; -1 otherwise. */
		private final int single;
		/** How many symbols each length has, and the symbols by length, then by value. */
		private final int[] counts;
		private final int[] sorted;

		private Code(int[] table, int single, int[] counts, int[] sorted) {
			this.table = table;
			this.single = single;
			this.counts = counts;
			this.sorted = sorted;
		}

		int read(Bits in) throws IOException {
			if (this.single >= 0) {
				return this.single;
			}
			int bits = in.peek(15);
			int entry = this.table[bits & ((1 << TABLE_BITS) - 1)];
			if (entry >= 0) {
				in.skip(entry & 15);
				return entry >>> 4;
			}
			// Longer than the table: a bit at a time, the first bit read the code's highest.
			int code = 0;
			int first = 0;
			int index = 0;
			for (int length = 1; length <= 15; length++) {
				code |= bits >>> (length - 1) & 1;
				int count = this.counts[length];
				if (code - count < first) {
					in.skip(length);
					return this.sorted[index + code - first];
				}
				index += count;
				first += count;
				first <<= 1;
				code <<= 1;
			}
			throw new IOException("bad WebP Huffman code");
		}

		/** Reads a code of {@code size} symbols; builds it only if {@code keep}. */
		static @Nullable Code read(Bits in, int size, boolean keep) throws IOException {
			int[] lengths = new int[size];
			if (in.read(1) == 1) {
				int symbols = in.read(1) + 1;
				int first = in.read(in.read(1) == 0 ? 1 : 8);
				int second = symbols == 2 ? in.read(8) : first;
				if (first >= size || second >= size) {
					throw new IOException("WebP symbol outside its alphabet");
				}
				lengths[first] = 1;
				lengths[second] = 1;
			} else {
				int[] lengthLengths = new int[19];
				int stored = in.read(4) + 4;
				for (int i = 0; i < stored; i++) {
					lengthLengths[Huffman.CODE_LENGTH_ORDER[i]] = in.read(3);
				}
				Code lengthCode = build(lengthLengths);
				int max = size;
				if (in.read(1) == 1) {
					int bits = 2 + 2 * in.read(3);
					max = 2 + in.read(bits);
					if (max > size) {
						throw new IOException("WebP code with more lengths than symbols");
					}
				}
				int previous = 8;
				int symbol = 0;
				while (symbol < size) {
					if (max-- == 0) {
						break;
					}
					int length = lengthCode.read(in);
					if (length < 16) {
						lengths[symbol++] = length;
						if (length != 0) {
							previous = length;
						}
					} else {
						int repeat = length == 16 ? 3 + in.read(2) : length == 17 ? 3 + in.read(3) : 11 + in.read(7);
						if (symbol + repeat > size) {
							throw new IOException("WebP code lengths past the alphabet");
						}
						int value = length == 16 ? previous : 0;
						for (int k = 0; k < repeat; k++) {
							lengths[symbol++] = value;
						}
					}
				}
			}
			Code code = build(lengths);
			return keep ? code : null;
		}

		/** A code from its lengths; refused unless it's complete, or has just one symbol. */
		private static Code build(int[] lengths) throws IOException {
			int[] counts = new int[16];
			int used = 0;
			int last = -1;
			long kraft = 0;
			for (int i = 0; i < lengths.length; i++) {
				if (lengths[i] > 0) {
					counts[lengths[i]]++;
					used++;
					last = i;
					kraft += 1L << (15 - lengths[i]);
				}
			}
			if (used == 0) {
				throw new IOException("WebP Huffman code with no symbols");
			}
			if (used == 1) {
				return new Code(new int[0], last, counts, new int[0]);
			}
			if (kraft != 1L << 15) {
				throw new IOException("WebP Huffman code that isn't complete");
			}
			int[] offsets = new int[16];
			for (int length = 1; length < 15; length++) {
				offsets[length + 1] = offsets[length] + counts[length];
			}
			int[] sorted = new int[used];
			for (int i = 0; i < lengths.length; i++) {
				if (lengths[i] > 0) {
					sorted[offsets[lengths[i]]++] = i;
				}
			}
			int[] table = new int[1 << TABLE_BITS];
			java.util.Arrays.fill(table, -1);
			int[] next = new int[16];
			int code = 0;
			for (int length = 1; length < 16; length++) {
				next[length] = code;
				code = (code + counts[length]) << 1;
			}
			for (int i = 0; i < lengths.length; i++) {
				int length = lengths[i];
				if (length == 0 || length > TABLE_BITS) {
					if (length > 0) {
						next[length]++;
					}
					continue;
				}
				int reversed = Integer.reverse(next[length]++) >>> (32 - length);
				for (int k = reversed; k < 1 << TABLE_BITS; k += 1 << length) {
					table[k] = i << 4 | length;
				}
			}
			return new Code(table, -1, counts, sorted);
		}
	}

	/** Reads bits lowest first from part of a byte array. */
	private static final class Bits {
		private final byte[] data;
		private final int end;
		private int pos;
		private long buffer;
		private int count;

		Bits(byte[] data, int start, int end) {
			this.data = data;
			this.pos = start;
			this.end = end;
		}

		private void fill() {
			while (this.count <= 56 && this.pos < this.end) {
				this.buffer |= (this.data[this.pos++] & 0xFFL) << this.count;
				this.count += 8;
			}
		}

		/** The next {@code n} bits, at most 24. */
		int read(int n) throws IOException {
			if (this.count < n) {
				this.fill();
				if (this.count < n) {
					throw new IOException("WebP file cut short");
				}
			}
			int value = (int) (this.buffer & ((1L << n) - 1));
			this.buffer >>>= n;
			this.count -= n;
			return value;
		}

		/** The next {@code n} bits without taking them; zeros past the end. */
		int peek(int n) {
			if (this.count < n) {
				this.fill();
			}
			return (int) (this.buffer & ((1L << n) - 1));
		}

		void skip(int n) throws IOException {
			if (this.count < n) {
				throw new IOException("WebP file cut short");
			}
			this.buffer >>>= n;
			this.count -= n;
		}
	}
}
