package dev.diorama.data;

import dev.diorama.Diorama;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import org.jspecify.annotations.Nullable;

/**
 * A chunk snapshot's blocks, light and tints as a few kilobytes, for chunks the game has unloaded:
 * the map keeps thousands of those to redraw from, and reads few of them ever again.
 *
 * <p>The bytes hold each section's blocks as the game packs them, a few bits per block that pick
 * from the section's palette, and the palettes as places in {@link #table}: the chunk's block
 * states themselves, held beside the bytes. So nothing in the bytes depends on how the game
 * numbers its blocks, which a modded server changes, and which some mods' blocks have no number
 * in at all. Light goes in as it is, a layer of one value as that value. All of it deflated.
 *
 * <p>A chunk with a section the game doesn't pack that way stays unpacked: one with more block
 * states than a palette of its own holds, which the game reads through its list of all of them.
 */
final class ChunkPack {
	private static final int ABSENT = 0;
	private static final int ONE_VALUE = 1;
	private static final int ARRAY = 2;
	private static final int LIGHT_BYTES = 2048;
	private static final int BLOCKS = 4096;
	/** Whether packing failed once: the game's sections aren't what this expects, so no more is packed. */
	private static volatile boolean failed;
	private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);
	/** The packing thread's. */
	private static final Deflater DEFLATER = new Deflater(Deflater.BEST_SPEED);

	static final AtomicLong PACKED = new AtomicLong();
	static final AtomicLong PACKED_BYTES = new AtomicLong();
	static final AtomicLong PACK_NANOS = new AtomicLong();
	static final AtomicLong UNPACKED = new AtomicLong();
	static final AtomicLong UNPACK_NANOS = new AtomicLong();

	private final byte[] bytes;
	/** Every block state in the chunk's sections with more than one, in the order first met. */
	private final BlockState[] table;

	private ChunkPack(byte[] bytes, BlockState[] table) {
		this.bytes = bytes;
		this.table = table;
	}

	/** About how many bytes the pack takes, the table's references included. */
	int size() {
		return this.bytes.length + 4 * this.table.length + 16;
	}

	/** A section's blocks as unpacked: the game's own packing, with the palette as an array. */
	record Unpacked(BitStorage storage, BlockState[] palette) implements ChunkSnapshot.Blocks {
		@Override
		public BlockState get(int x, int y, int z) {
			return this.palette[this.storage.get((y << 8) | (z << 4) | x)];
		}

		@Override
		public boolean maybeHas(Predicate<BlockState> predicate) {
			for (BlockState state : this.palette) {
				if (predicate.test(state)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public ChunkSnapshot.Packed packed() {
			return new ChunkSnapshot.Packed(this.storage, this.palette);
		}
	}

	/** The body packed, with these tints if there are any, or null if it can't be. The packing thread only. */
	static @Nullable ChunkPack pack(ChunkSnapshot.Body body, ChunkSnapshot.@Nullable Tints tints) {
		if (failed) {
			return null;
		}
		long start = System.nanoTime();
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(48 << 10));
		try {
			Map<BlockState, Integer> places = new IdentityHashMap<>();
			List<BlockState> table = new ArrayList<>();
			for (ChunkSnapshot.Blocks blocks : body.blocks()) {
				if (blocks != null && !writeBlocks(buf, blocks, places, table)) {
					return null;
				}
			}
			for (DataLayer layer : body.blockLight()) {
				writeLight(buf, layer);
			}
			for (DataLayer layer : body.skyLight()) {
				writeLight(buf, layer);
			}
			buf.writeBoolean(tints != null);
			if (tints != null) {
				writeTint(buf, tints.grass());
				writeTint(buf, tints.foliage());
				writeTint(buf, tints.dryFoliage());
				writeTint(buf, tints.water());
			}
			ChunkPack pack = new ChunkPack(deflate(buf), table.toArray(new BlockState[0]));
			PACKED.incrementAndGet();
			PACKED_BYTES.addAndGet(pack.bytes.length);
			PACK_NANOS.addAndGet(System.nanoTime() - start);
			return pack;
		} catch (RuntimeException e) {
			failed = true;
			Diorama.LOGGER.warn("Couldn't pack a chunk; the map keeps its chunks unpacked", e);
			return null;
		} finally {
			buf.release();
		}
	}

	/** What {@link #pack} packed, for a chunk whose sections with more than one block state are {@code mixed}. Any thread. */
	ChunkSnapshot.Body unpack(boolean[] mixed) {
		long start = System.nanoTime();
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(inflate(this.bytes)));
		int sections = mixed.length;
		ChunkSnapshot.Blocks[] blocks = new ChunkSnapshot.Blocks[sections];
		for (int i = 0; i < sections; i++) {
			if (mixed[i]) {
				blocks[i] = this.readBlocks(buf);
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
		ChunkSnapshot.Tints tints = buf.readBoolean() ? new ChunkSnapshot.Tints(readTint(buf), readTint(buf), readTint(buf), readTint(buf)) : null;
		UNPACKED.incrementAndGet();
		UNPACK_NANOS.addAndGet(System.nanoTime() - start);
		return new ChunkSnapshot.Body(blocks, blockLight, skyLight, tints);
	}

	/** Writes a section's blocks; false if they aren't packed the way this reads back. */
	private static boolean writeBlocks(FriendlyByteBuf buf, ChunkSnapshot.Blocks blocks, Map<BlockState, Integer> places, List<BlockState> table) {
		ChunkSnapshot.Packed packed = blocks.packed();
		if (packed == null || !(packed.storage() instanceof SimpleBitStorage) || packed.storage().getSize() != BLOCKS) {
			return false;
		}
		BitStorage storage = packed.storage();
		BlockState[] palette = packed.palette();
		buf.writeByte(storage.getBits());
		buf.writeVarInt(palette.length);
		for (BlockState state : palette) {
			// A palette can have places nothing picks, without a state.
			BlockState known = state == null ? Blocks.AIR.defaultBlockState() : state;
			Integer place = places.get(known);
			if (place == null) {
				place = table.size();
				places.put(known, place);
				table.add(known);
			}
			buf.writeVarInt(place);
		}
		long[] raw = storage.getRaw();
		buf.writeVarInt(raw.length);
		for (long word : raw) {
			buf.writeLong(word);
		}
		return true;
	}

	private ChunkSnapshot.Blocks readBlocks(FriendlyByteBuf buf) {
		int bits = buf.readByte();
		BlockState[] palette = new BlockState[buf.readVarInt()];
		for (int i = 0; i < palette.length; i++) {
			palette[i] = this.table[buf.readVarInt()];
		}
		long[] raw = new long[buf.readVarInt()];
		for (int i = 0; i < raw.length; i++) {
			raw[i] = buf.readLong();
		}
		return new Unpacked(new SimpleBitStorage(bits, BLOCKS, raw), palette);
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

	private static void writeTint(ByteBuf buf, int[] tint) {
		for (int colour : tint) {
			buf.writeInt(colour);
		}
	}

	private static int[] readTint(ByteBuf buf) {
		int[] tint = new int[256];
		for (int i = 0; i < 256; i++) {
			tint[i] = buf.readInt();
		}
		return tint;
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
