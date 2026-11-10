package dev.diorama.web.webp;

import java.util.Arrays;

/** Writes bits least significant first, the order VP8L reads them in. */
final class BitWriter {
	private byte[] buffer;
	private int position;
	private long pending;
	private int pendingBits;

	BitWriter(int capacity) {
		this.buffer = new byte[Math.max(capacity, 64)];
	}

	/** Writes the low {@code n} bits of {@code value}; {@code n} at most 32. */
	void put(int value, int n) {
		this.pending |= (value & ((1L << n) - 1)) << this.pendingBits;
		this.pendingBits += n;
		while (this.pendingBits >= 8) {
			if (this.position == this.buffer.length) {
				this.buffer = Arrays.copyOf(this.buffer, this.buffer.length * 2);
			}
			this.buffer[this.position++] = (byte) this.pending;
			this.pending >>>= 8;
			this.pendingBits -= 8;
		}
	}

	byte[] finish() {
		if (this.pendingBits > 0) {
			this.put(0, 8 - this.pendingBits);
		}
		return Arrays.copyOf(this.buffer, this.position);
	}
}
