package dev.diorama.web.webp;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Writes lossless WebP (VP8L, RFC 9649) in plain Java, so the web map needs no native library.
 *
 * <p>A picture with 256 colours or fewer is stored as a palette (with several pixels to a byte when
 * there are 16 or fewer). Otherwise: green subtracted from red and blue, then the predictor
 * transform. Then copies from earlier pixels (LZ77), a colour cache, and one set of Huffman codes for
 * the whole picture. It leaves out what the format allows but doesn't need: the cross-colour
 * transform, Huffman codes per region, and searching for the cheapest copies.
 */
public final class Vp8lEncoder {
	/** Predictor blocks of 32 by 32 pixels. */
	private static final int PREDICTOR_BITS = 5;
	/** Colour cache of the main picture: 1024 colours, as good as picking by estimate on the map's tiles. */
	private static final int CACHE_BITS = 10;
	static final int LENGTH_CODES = 24;
	static final int DISTANCE_CODES = 40;

	private Vp8lEncoder() {
	}

	/** A .webp file of the pixels, 0xAARRGGBB row by row. */
	public static byte[] encode(int[] argb, int width, int height) {
		if (width < 1 || height < 1 || width > 16384 || height > 16384 || argb.length < width * height) {
			throw new IllegalArgumentException("can't write a " + width + "x" + height + " WebP");
		}
		BitWriter out = new BitWriter(width * height / 2 + 1024);
		boolean alpha = false;
		for (int i = 0; i < width * height; i++) {
			if (argb[i] >>> 24 != 0xFF) {
				alpha = true;
				break;
			}
		}
		out.put(0x2F, 8);
		out.put(width - 1, 14);
		out.put(height - 1, 14);
		out.put(alpha ? 1 : 0, 1);
		out.put(0, 3);

		int[] image = Arrays.copyOf(argb, width * height);
		int packedWidth = width;
		int[] palette = palette(image);
		if (palette != null) {
			int n = palette.length;
			out.put(1, 1);
			out.put(3, 2);
			out.put(n - 1, 8);
			int[] delta = new int[n];
			delta[0] = palette[0];
			for (int i = 1; i < n; i++) {
				delta[i] = subtract(palette[i], palette[i - 1]);
			}
			writeImage(out, delta, n, 1, false);
			int bits = n <= 2 ? 3 : n <= 4 ? 2 : n <= 16 ? 1 : 0;
			packedWidth = (width + (1 << bits) - 1) >> bits;
			int[] packed = new int[packedWidth * height];
			int shift = 8 >> bits;
			int mask = (1 << bits) - 1;
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int index = Arrays.binarySearch(palette, image[y * width + x]);
					packed[y * packedWidth + (x >> bits)] |= index << ((x & mask) * shift);
				}
			}
			for (int i = 0; i < packed.length; i++) {
				packed[i] = 0xFF000000 | packed[i] << 8;
			}
			image = packed;
		} else {
			out.put(1, 1);
			out.put(2, 2);
			for (int i = 0; i < image.length; i++) {
				int p = image[i];
				int g = p >> 8 & 0xFF;
				image[i] = (p & 0xFF00FF00) | ((p >> 16) - g & 0xFF) << 16 | ((p & 0xFF) - g & 0xFF);
			}
			out.put(1, 1);
			out.put(0, 2);
			out.put(PREDICTOR_BITS - 2, 3);
			int blocksWide = (width + (1 << PREDICTOR_BITS) - 1) >> PREDICTOR_BITS;
			int blocksHigh = (height + (1 << PREDICTOR_BITS) - 1) >> PREDICTOR_BITS;
			int[] modes = new int[blocksWide * blocksHigh];
			image = Predictor.apply(image, width, height, PREDICTOR_BITS, modes);
			writeImage(out, modes, blocksWide, blocksHigh, false);
		}
		out.put(0, 1);
		writeImage(out, image, packedWidth, height, true);
		byte[] vp8l = out.finish();

		int chunk = vp8l.length;
		int padded = chunk + (chunk & 1);
		byte[] file = new byte[20 + padded];
		putTag(file, 0, "RIFF");
		putLe32(file, 4, 4 + 8 + padded);
		putTag(file, 8, "WEBP");
		putTag(file, 12, "VP8L");
		putLe32(file, 16, chunk);
		System.arraycopy(vp8l, 0, file, 20, chunk);
		return file;
	}

	private static void putTag(byte[] bytes, int at, String tag) {
		for (int i = 0; i < 4; i++) {
			bytes[at + i] = (byte) tag.charAt(i);
		}
	}

	private static void putLe32(byte[] bytes, int at, int value) {
		bytes[at] = (byte) value;
		bytes[at + 1] = (byte) (value >> 8);
		bytes[at + 2] = (byte) (value >> 16);
		bytes[at + 3] = (byte) (value >> 24);
	}

	/** The distinct colours, sorted, or null when there are more than 256. */
	private static int @Nullable [] palette(int[] image) {
		int[] table = new int[1024];
		boolean[] used = new boolean[1024];
		int n = 0;
		int[] colours = new int[257];
		for (int p : image) {
			int h = (p * 0x9E3779B1) >>> 22;
			while (used[h] && table[h] != p) {
				h = (h + 1) & 1023;
			}
			if (!used[h]) {
				used[h] = true;
				table[h] = p;
				colours[n++] = p;
				if (n > 256) {
					return null;
				}
			}
		}
		int[] palette = Arrays.copyOf(colours, n);
		Arrays.sort(palette);
		return palette;
	}

	/** Each channel of a minus b, modulo 256. */
	static int subtract(int a, int b) {
		int alphaGreen = 0x00FF00FF + (a & 0xFF00FF00) - (b & 0xFF00FF00);
		int redBlue = 0xFF00FF00 + (a & 0x00FF00FF) - (b & 0x00FF00FF);
		return (alphaGreen & 0xFF00FF00) | (redBlue & 0x00FF00FF);
	}

	/**
	 * Writes one entropy-coded picture: its colour cache, (for the main picture) that there's one set
	 * of codes, the five Huffman codes, then the pixels as literals, cache hits and copies.
	 */
	private static void writeImage(BitWriter out, int[] px, int w, int h, boolean main) {
		int n = w * h;
		Lz77.Result refs = Lz77.parse(px, w, h);
		int cacheBits = main ? CACHE_BITS : pickCacheBits(px, refs);
		if (cacheBits > 0) {
			out.put(1, 1);
			out.put(cacheBits, 4);
		} else {
			out.put(0, 1);
		}
		if (main) {
			out.put(0, 1);
		}
		int cacheSize = cacheBits > 0 ? 1 << cacheBits : 0;
		int[] green = new int[256 + LENGTH_CODES + cacheSize];
		int[] red = new int[256];
		int[] blue = new int[256];
		int[] alpha = new int[256];
		int[] distance = new int[DISTANCE_CODES];
		// First the counts, and which literals are cache hits.
		int tokens = refs.count;
		int[] cacheIndex = new int[tokens];
		int[] cache = cacheSize > 0 ? new int[cacheSize] : null;
		int shift = 32 - cacheBits;
		int pos = 0;
		for (int t = 0; t < tokens; t++) {
			int length = refs.length[t];
			if (length == 0) {
				int p = px[pos];
				if (cache != null) {
					int key = (0x1E35A7BD * p) >>> shift;
					if (cache[key] == p) {
						cacheIndex[t] = key + 1;
						green[256 + LENGTH_CODES + key]++;
						pos++;
						continue;
					}
					cache[key] = p;
				}
				green[p >> 8 & 0xFF]++;
				red[p >> 16 & 0xFF]++;
				blue[p & 0xFF]++;
				alpha[p >>> 24]++;
				pos++;
			} else {
				green[256 + prefix(length)]++;
				distance[prefix(refs.distance[t])]++;
				if (cache != null) {
					for (int k = 0; k < length; k++) {
						int p = px[pos + k];
						cache[(0x1E35A7BD * p) >>> shift] = p;
					}
				}
				pos += length;
			}
		}
		if (pos != n) {
			throw new IllegalStateException("the copies cover " + pos + " of " + n + " pixels");
		}
		Huffman greenCode = Huffman.build(green, 15);
		Huffman redCode = Huffman.build(red, 15);
		Huffman blueCode = Huffman.build(blue, 15);
		Huffman alphaCode = Huffman.build(alpha, 15);
		Huffman distanceCode = Huffman.build(distance, 15);
		greenCode.writeHeader(out);
		redCode.writeHeader(out);
		blueCode.writeHeader(out);
		alphaCode.writeHeader(out);
		distanceCode.writeHeader(out);
		pos = 0;
		for (int t = 0; t < tokens; t++) {
			int length = refs.length[t];
			if (length == 0) {
				if (cacheIndex[t] != 0) {
					greenCode.write(out, 256 + LENGTH_CODES + cacheIndex[t] - 1);
				} else {
					int p = px[pos];
					greenCode.write(out, p >> 8 & 0xFF);
					redCode.write(out, p >> 16 & 0xFF);
					blueCode.write(out, p & 0xFF);
					alphaCode.write(out, p >>> 24);
				}
				pos++;
			} else {
				int lengthPrefix = prefix(length);
				greenCode.write(out, 256 + lengthPrefix);
				int extra = extraBits(lengthPrefix);
				if (extra > 0) {
					out.put((length - 1) & ((1 << extra) - 1), extra);
				}
				int d = refs.distance[t];
				int distancePrefix = prefix(d);
				distanceCode.write(out, distancePrefix);
				extra = extraBits(distancePrefix);
				if (extra > 0) {
					out.put((d - 1) & ((1 << extra) - 1), extra);
				}
				pos += length;
			}
		}
	}

	/** The colour cache size, as bits (0 for none), that the coded size estimates smallest for. */
	private static int pickCacheBits(int[] px, Lz77.Result refs) {
		double best = Double.MAX_VALUE;
		int bestBits = 0;
		int[] green = new int[256 + LENGTH_CODES + (1 << 11)];
		int[] red = new int[256];
		int[] blue = new int[256];
		int[] alpha = new int[256];
		int[] cache = new int[1 << 11];
		for (int bits = 0; bits <= (px.length < 4096 ? 8 : 11); bits++) {
			Arrays.fill(green, 0);
			Arrays.fill(red, 0);
			Arrays.fill(blue, 0);
			Arrays.fill(alpha, 0);
			Arrays.fill(cache, 0);
			int shift = 32 - bits;
			int pos = 0;
			for (int t = 0; t < refs.count; t++) {
				int length = refs.length[t];
				if (length == 0) {
					int p = px[pos++];
					if (bits > 0) {
						int key = (0x1E35A7BD * p) >>> shift;
						if (cache[key] == p) {
							green[256 + LENGTH_CODES + key]++;
							continue;
						}
						cache[key] = p;
					}
					green[p >> 8 & 0xFF]++;
					red[p >> 16 & 0xFF]++;
					blue[p & 0xFF]++;
					alpha[p >>> 24]++;
				} else {
					green[256 + prefix(length)]++;
					if (bits > 0) {
						for (int k = 0; k < length; k++) {
							int p = px[pos + k];
							cache[(0x1E35A7BD * p) >>> shift] = p;
						}
					}
					pos += length;
				}
			}
			int greenSize = 256 + LENGTH_CODES + (bits > 0 ? 1 << bits : 0);
			double cost = entropy(green, greenSize) + entropy(red, 256) + entropy(blue, 256) + entropy(alpha, 256);
			if (cost < best) {
				best = cost;
				bestBits = bits;
			}
		}
		return bestBits;
	}

	/** Shannon bits for the symbols, plus about 4 bits a used symbol for storing the code. */
	private static double entropy(int[] counts, int size) {
		long total = 0;
		int used = 0;
		for (int i = 0; i < size; i++) {
			total += counts[i];
			if (counts[i] > 0) {
				used++;
			}
		}
		if (total == 0) {
			return 0;
		}
		double bits = 0;
		double logTotal = Math.log(total);
		for (int i = 0; i < size; i++) {
			if (counts[i] > 0) {
				bits += counts[i] * (logTotal - Math.log(counts[i]));
			}
		}
		return bits / Math.log(2) + used * 4;
	}

	/** The prefix code of a length or distance code of 1 or more. */
	static int prefix(int value) {
		int d = value - 1;
		if (d < 4) {
			return d;
		}
		int high = 31 - Integer.numberOfLeadingZeros(d);
		return 2 * high + ((d >> (high - 1)) & 1);
	}

	static int extraBits(int prefix) {
		return prefix < 4 ? 0 : (prefix - 2) >> 1;
	}
}
