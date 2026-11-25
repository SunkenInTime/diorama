package dev.diorama.data;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.jspecify.annotations.Nullable;

/** Reads a section's palette type, which vanilla keeps private. */
final class Sections {
	//? if >=26.1 {
	private static final Field DATA;
	private static final Method PALETTE;

	static {
		try {
			DATA = PalettedContainer.class.getDeclaredField("data");
			DATA.setAccessible(true);
			PALETTE = DATA.getType().getDeclaredMethod("palette");
			PALETTE.setAccessible(true);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("PalettedContainer layout changed", e);
		}
	}
	//?} else if <1.21.9 {
	/*// The container's data record and its storage, found by type: outside the dev environment the
	// game runs with intermediary or SRG names. Null if the layout isn't the one expected.
	private static final java.lang.invoke.@Nullable MethodHandle STORAGE;

	static {
		java.lang.invoke.MethodHandle storage = null;
		try {
			for (Field field : PalettedContainer.class.getDeclaredFields()) {
				Class<?> type = field.getType();
				if (!type.isRecord() || type.getEnclosingClass() != PalettedContainer.class) {
					continue;
				}
				for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
					if (component.getType() == net.minecraft.util.BitStorage.class) {
						field.setAccessible(true);
						Method accessor = component.getAccessor();
						accessor.setAccessible(true);
						java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
						storage = java.lang.invoke.MethodHandles.filterReturnValue(lookup.unreflectGetter(field), lookup.unreflect(accessor))
							.asType(java.lang.invoke.MethodType.methodType(net.minecraft.util.BitStorage.class, PalettedContainer.class));
					}
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			storage = null;
		}
		STORAGE = storage;
	}
	*///?}

	// The container's data record, then its storage and palette, found by type for every version:
	// outside the dev environment older games run with intermediary or SRG names. Null if the
	// layout isn't the one expected; readers then go block by block.
	private static final java.lang.invoke.@Nullable MethodHandle PACKED_DATA;
	private static final java.lang.invoke.@Nullable MethodHandle PACKED_STORAGE;
	private static final java.lang.invoke.@Nullable MethodHandle PACKED_PALETTE;

	static {
		java.lang.invoke.MethodHandle data = null;
		java.lang.invoke.MethodHandle storage = null;
		java.lang.invoke.MethodHandle palette = null;
		try {
			java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
			for (Field field : PalettedContainer.class.getDeclaredFields()) {
				Class<?> type = field.getType();
				if (!type.isRecord() || type.getEnclosingClass() != PalettedContainer.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				java.lang.invoke.MethodHandle s = null;
				java.lang.invoke.MethodHandle p = null;
				for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
					Method accessor = component.getAccessor();
					if (component.getType() == net.minecraft.util.BitStorage.class) {
						accessor.setAccessible(true);
						s = lookup.unreflect(accessor).asType(java.lang.invoke.MethodType.methodType(net.minecraft.util.BitStorage.class, Object.class));
					} else if (component.getType() == net.minecraft.world.level.chunk.Palette.class) {
						accessor.setAccessible(true);
						p = lookup.unreflect(accessor).asType(java.lang.invoke.MethodType.methodType(net.minecraft.world.level.chunk.Palette.class, Object.class));
					}
				}
				if (s != null && p != null) {
					field.setAccessible(true);
					data = lookup.unreflectGetter(field).asType(java.lang.invoke.MethodType.methodType(Object.class, PalettedContainer.class));
					storage = s;
					palette = p;
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			data = null;
		}
		PACKED_DATA = data;
		PACKED_STORAGE = data == null ? null : storage;
		PACKED_PALETTE = data == null ? null : palette;
	}

	private Sections() {
	}

	/**
	 * The container's packed blocks: one palette index per block, indexed
	 * {@code (y << 8) | (z << 4) | x}, and the palette. Null if they can't be read, or the palette
	 * is the game's whole list of block states.
	 */
	@SuppressWarnings("unchecked")
	static ChunkSnapshot.@Nullable Packed packed(PalettedContainer<BlockState> states) {
		if (PACKED_DATA == null) {
			return null;
		}
		try {
			Object data = (Object) PACKED_DATA.invokeExact(states);
			net.minecraft.util.BitStorage storage = (net.minecraft.util.BitStorage) PACKED_STORAGE.invokeExact(data);
			net.minecraft.world.level.chunk.Palette<BlockState> palette = (net.minecraft.world.level.chunk.Palette<BlockState>) (net.minecraft.world.level.chunk.Palette<?>) PACKED_PALETTE.invokeExact(data);
			if (storage == null || palette == null || storage.getSize() != 4096) {
				return null;
			}
			// A palette with more places than the storage can pick is the game's whole list of block states.
			int bits = storage.getBits();
			int size = palette.getSize();
			if (bits > 8 || size > 1 << bits) {
				return null;
			}
			BlockState[] entries = new BlockState[size];
			for (int p = 0; p < size; p++) {
				entries[p] = palette.valueFor(p);
			}
			return new ChunkSnapshot.Packed(storage, entries);
		} catch (Throwable e) {
			return null;
		}
	}

	/** The block filling the section if it uses a single-value palette, otherwise null. */
	static @Nullable BlockState singleValue(PalettedContainer<BlockState> states) {
		//? if >=26.1 {
		try {
			Object palette = PALETTE.invoke(DATA.get(states));
			return palette instanceof SingleValuePalette<?> ? states.get(0, 0, 0) : null;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		//?} else if >=1.21.9 {
		/*// Outside the dev environment the game runs with intermediary names, so the private
		// fields can't be looked up by name. Storage of zero bits per entry only ever comes with the
		// single-value palette (Strategy.ZERO_BITS).
		return states.bitsPerEntry() == 0 ? states.get(0, 0, 0) : null;
		*///?} else {
		/*// No bitsPerEntry() yet. Storage of zero bits per entry only ever comes with the single-value
		// palette. The serialized size tells too, but works it out from the whole palette, slowly
		// with Lithium's: a single-value palette needs at most 6 bytes and has no storage, while any
		// other palette stores 4096 entries of at least one bit, 512 bytes or more (#180).
		if (STORAGE != null) {
			try {
				return ((net.minecraft.util.BitStorage) STORAGE.invokeExact(states)).getBits() == 0 ? states.get(0, 0, 0) : null;
			} catch (Throwable e) {
				// Falls through to the size.
			}
		}
		return states.getSerializedSize() < 512 ? states.get(0, 0, 0) : null;
		*///?}
	}
}
