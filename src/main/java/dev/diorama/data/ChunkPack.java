package dev.diorama.data;

import dev.diorama.Diorama;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jspecify.annotations.Nullable;

/**
 * A chunk snapshot's blocks and light as a few kilobytes, for chunks the game has unloaded: the
 * map keeps thousands of those to redraw from, and reads few of them ever again.
 *
 * <p>Block states go in as the game sends them in a chunk packet, by number, so the bytes only
 * mean anything while the game's block numbers stay as they are: within one stay in a world, which
 * is as long as a snapshot lives. Light goes in as it is; a layer of one value as that value. All
 * of it deflated.
 */
final class ChunkPack {
	private static final int ABSENT = 0;
	private static final int ONE_VALUE = 1;
	private static final int ARRAY = 2;
	private static final int LIGHT_BYTES = 2048;
	/** An empty container to read block states into, made from the first one packed. */
	private static volatile @Nullable PalettedContainer<BlockState> empty;
	/** Whether packing failed once: the game's containers aren't what this expects, so no more is packed. */
	private static volatile boolean failed;
	private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);
	/** The packing thread's. */
	private static final Deflater DEFLATER = new Deflater(Deflater.BEST_SPEED);

	static final AtomicLong PACKED = new AtomicLong();
	static final AtomicLong PACKED_BYTES = new AtomicLong();
	static final AtomicLong PACK_NANOS = new AtomicLong();
	static final AtomicLong UNPACKED = new AtomicLong();
	static final AtomicLong UNPACK_NANOS = new AtomicLong();

	private ChunkPack() {
	}

	/** The body packed, or null if it can't be. The packing thread only. */
	static byte @Nullable [] pack(ChunkSnapshot.Body body) {
		if (failed) {
			return null;
		}
		long start = System.nanoTime();
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(48 << 10));
		try {
			for (PalettedContainer<BlockState> states : body.states()) {
				if (states != null) {
					if (empty == null) {
						empty = states.recreate();
					}
					states.write(buf);
				}
			}
			for (DataLayer layer : body.blockLight()) {
				writeLight(buf, layer);
			}
			for (DataLayer layer : body.skyLight()) {
				writeLight(buf, layer);
			}
			byte[] bytes = deflate(buf);
			PACKED.incrementAndGet();
			PACKED_BYTES.addAndGet(bytes.length);
			PACK_NANOS.addAndGet(System.nanoTime() - start);
			return bytes;
		} catch (RuntimeException e) {
			failed = true;
			Diorama.LOGGER.warn("Couldn't pack a chunk; the map keeps its chunks unpacked", e);
			return null;
		} finally {
			buf.release();
		}
	}

	/** What {@link #pack} packed, for a chunk whose sections with block states of their own are {@code mixed}. Any thread. */
	@SuppressWarnings("unchecked")
	static ChunkSnapshot.Body unpack(byte[] bytes, boolean[] mixed) {
		long start = System.nanoTime();
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(inflate(bytes)));
		int sections = mixed.length;
		PalettedContainer<BlockState>[] states = new PalettedContainer[sections];
		for (int i = 0; i < sections; i++) {
			if (mixed[i]) {
				states[i] = empty.recreate();
				states[i].read(buf);
			}
		}
		DataLayer[] blockLight = new DataLayer[sections];
		for (int i = 0; i < sections; i++) {
			blockLight[i] = readLight(buf);
		}
		DataLayer[] skyLight = new DataLayer[sections];
		for (int i = 0; i < sections; i++) {
			skyLight[i] = readLight(buf);
		}
		UNPACKED.incrementAndGet();
		UNPACK_NANOS.addAndGet(System.nanoTime() - start);
		return new ChunkSnapshot.Body(states, blockLight, skyLight);
	}

	private static void writeLight(ByteBuf buf, @Nullable DataLayer layer) {
		if (layer == null) {
			buf.writeByte(ABSENT);
		} else if (layer.isDefinitelyHomogenous()) {
			// It has no array, and asking for one would make it.
			buf.writeByte(ONE_VALUE);
			buf.writeByte(layer.get(0, 0, 0));
		} else {
			buf.writeByte(ARRAY);
			buf.writeBytes(layer.getData());
		}
	}

	private static @Nullable DataLayer readLight(ByteBuf buf) {
		int kind = buf.readByte();
		if (kind == ABSENT) {
			return null;
		}
		if (kind == ONE_VALUE) {
			return new DataLayer(buf.readByte());
		}
		byte[] data = new byte[LIGHT_BYTES];
		buf.readBytes(data);
		return new DataLayer(data);
	}

	/** The buffer's bytes deflated, after their length. */
	private static byte[] deflate(ByteBuf buf) {
		int length = buf.readableBytes();
		Deflater deflater = DEFLATER;
		deflater.reset();
		deflater.setInput(buf.array(), buf.arrayOffset() + buf.readerIndex(), length);
		deflater.finish();
		// Deflate never grows its input by more than a few bytes per block.
		byte[] out = new byte[length + (length >> 8) + 64];
		out[0] = (byte) (length >> 24);
		out[1] = (byte) (length >> 16);
		out[2] = (byte) (length >> 8);
		out[3] = (byte) length;
		int size = 4;
		while (!deflater.finished()) {
			if (size == out.length) {
				out = java.util.Arrays.copyOf(out, out.length * 2);
			}
			size += deflater.deflate(out, size, out.length - size);
		}
		return java.util.Arrays.copyOf(out, size);
	}

	private static byte[] inflate(byte[] bytes) {
		int length = (bytes[0] & 255) << 24 | (bytes[1] & 255) << 16 | (bytes[2] & 255) << 8 | bytes[3] & 255;
		byte[] out = new byte[length];
		Inflater inflater = INFLATER.get();
		inflater.reset();
		inflater.setInput(bytes, 4, bytes.length - 4);
		try {
			int size = 0;
			while (size < length) {
				int read = inflater.inflate(out, size, length - size);
				if (read == 0 && (inflater.finished() || inflater.needsInput())) {
					break;
				}
				size += read;
			}
			if (size != length) {
				throw new IllegalStateException("A packed chunk unpacks to " + size + " bytes, not " + length);
			}
		} catch (DataFormatException e) {
			throw new IllegalStateException("A packed chunk doesn't unpack", e);
		}
		return out;
	}
}
