package dev.diorama.bake;

import java.lang.invoke.VarHandle;

/**
 * Where {@link MeshColumns} and {@link SpotColumns} keep their ints: a ring of a few large arrays,
 * filled in turn. Kept as small arrays, every young collection copied what the last bakes had kept
 * from one survivor space to the next until it was old enough to promote, which made each pause
 * longer. An array this large is allocated where the collector never moves it ({@code G1} puts one
 * at least half a region long straight into the old generation), and the ring allocates each only
 * once: allocating such arrays as they filled started a collection each time.
 *
 * <p>Each pass over an array has a serial number, and only the last {@link #WINDOW} are in use. A
 * tile whose ints are in an older one is dropped ({@link #live}), and a kept run about to leave the
 * window is copied forward when a bake takes it again ({@link #renew}). An array is written over
 * only once it has left the window, but a bake that took a tile just before may still be reading
 * it: so a reader checks {@link #valid} after reading a run, as {@link java.util.concurrent.locks.StampedLock}
 * readers do, and works the column out instead if the array was written over meanwhile.
 */
final class Slabs {
	/** Arrays in use at once; together about 40 MB, what the kept tiles were allowed before. */
	static final int WINDOW;
	/** Ints per array: one heap region of the collector, at least 1M ints (4 MB). */
	static final int SLAB_INTS;

	static {
		long region = 0;
		try {
			var bean = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
			region = Long.parseLong(bean.getVMOption("G1HeapRegionSize").getValue());
		} catch (Throwable unknown) {
			// Another collector, or no such option: any large array will do.
		}
		// Just under a whole number of regions, at least 4 MB, so an array fills its regions and is
		// humongous (at least half of one).
		long regionInts = Math.max(1L << 10, region / Integer.BYTES);
		long regions = region <= 0 ? 1 : ((1L << 20) + regionInts - 1) / regionInts;
		long ints = region <= 0 ? 1L << 20 : regions * regionInts - 64;
		// Larger regions (heaps of 32 GB and more) would make the window too coarse: there an array
		// of 8 MB is promoted like any other, once.
		SLAB_INTS = (int) Math.min(ints, (1L << 21) - 64);
		WINDOW = (int) Math.max(4, (40L << 20) / ((long) SLAB_INTS * Integer.BYTES));
	}

	/** One array of the ring and the serial of the pass that writes it now. */
	static final class Slab {
		final int[] ints = new int[SLAB_INTS];
		volatile long serial = -1;
	}

	/** A run of ints kept in an array of the store, by the pass {@link #serial}. */
	static final class Run {
		final Slab slab;
		final int offset;
		final int length;
		final long serial;

		Run(Slab slab, int offset, int length, long serial) {
			this.slab = slab;
			this.offset = offset;
			this.length = length;
			this.serial = serial;
		}

		/** The array; read from {@link #offset}, then check {@link Slabs#valid}. */
		int[] ints() {
			return this.slab.ints;
		}
	}

	private static final Slab[] RING = new Slab[WINDOW];
	private static Slab current;
	private static int used;
	/** The current pass's serial: the first is 0. */
	private static long serial = -1;

	private Slabs() {
	}

	/** Keeps {@code length} ints of {@code from} from {@code at}. */
	static synchronized Run keep(int[] from, int at, int length) {
		if (length > SLAB_INTS) {
			throw new IllegalArgumentException("Run of " + length + " ints is larger than a slab");
		}
		if (current == null || used + length > SLAB_INTS) {
			serial++;
			int slot = (int) (serial % WINDOW);
			if (RING[slot] == null) {
				RING[slot] = new Slab();
			}
			current = RING[slot];
			// Readers of the pass this one writes over see a new serial once anything is written.
			current.serial = serial;
			VarHandle.fullFence();
			used = 0;
		}
		System.arraycopy(from, at, current.ints, used, length);
		Run run = new Run(current, used, length, serial);
		used += length;
		return run;
	}

	/**
	 * Whether what was just read of {@code run} is what was kept: its array hasn't been written over
	 * since. Call after reading.
	 */
	static boolean valid(Run run) {
		VarHandle.acquireFence();
		return run.slab.serial == run.serial;
	}

	/** The serial new runs get now; a tile kept now is no older than this. */
	static synchronized long serial() {
		return serial;
	}

	/** Whether ints kept in pass {@code serial} may still be used. */
	static synchronized boolean live(long serial) {
		return serial > Slabs.serial - WINDOW;
	}

	/** Whether a run is in one of the two passes that leave the window next, so a bake that takes it again should keep a copy. */
	private static synchronized boolean aging(Run run) {
		return run.serial <= serial - WINDOW + 2;
	}

	/**
	 * {@code run}, or a copy of it in the current array when it is about to leave the window; null
	 * if its array was written over before the copy was made.
	 */
	static Run renew(Run run) {
		if (!aging(run)) {
			return run;
		}
		Run copy = keep(run.slab.ints, run.offset, run.length);
		return valid(run) ? copy : null;
	}
}
