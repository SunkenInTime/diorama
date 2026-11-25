package dev.diorama.test;

import dev.diorama.data.SeenBlocks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Saving fog-of-war progress on its disk thread, with that thread held or a write failed on
 * purpose: a failed save while the map closes is tried again, and saves asked for while the
 * disk is slow wait as one. On a table of its own in a folder of its own, beside the game's.
 */
final class SeenSaveTest {
	private SeenSaveTest() {
	}

	static void run() throws Exception {
		Path dir = Files.createTempDirectory("diorama-seen-save");
		try {
			List<String> failures = new ArrayList<>();
			failedSaveWhileClosing(dir.resolve("failed").resolve("seen.bin"), failures);
			savesWaitAsOne(dir.resolve("slow").resolve("seen.bin"), failures);
			if (!failures.isEmpty()) {
				throw new AssertionError(String.join("; ", failures));
			}
		} finally {
			try (Stream<Path> walk = Files.walk(dir)) {
				walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
	}

	/** A periodic save still writing when the map closes, which then fails: closing has to try again. */
	private static void failedSaveWhileClosing(Path file, List<String> failures) throws Exception {
		SeenBlocks seen = new SeenBlocks();
		int[][] blocks = {{1, -50, 2}, {40, -51, -60}, {-300, 12, 77}};
		for (int[] b : blocks) {
			seen.markSeen(b[0], b[1], b[2]);
		}
		CountDownLatch writing = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger writes = new AtomicInteger();
		seen.beforeEachWrite(() -> {
			if (writes.incrementAndGet() == 1) {
				writing.countDown();
				await(release);
				throw new IOException("failed on purpose by the seen-save test");
			}
		});
		seen.save(file);
		await(writing);
		Thread closing = new Thread(() -> seen.close(file), "seen-save-test-close");
		closing.start();
		// Waiting on the disk thread: whatever close checks before it waits, it has checked.
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (closing.getState() != Thread.State.WAITING && System.nanoTime() < until) {
			Thread.sleep(5);
		}
		release.countDown();
		closing.join(TimeUnit.SECONDS.toMillis(30));
		if (closing.isAlive()) {
			failures.add("closing the map never finished after a failed save");
			return;
		}
		SeenBlocks back = SeenBlocks.load(file);
		for (int[] b : blocks) {
			if (!back.isSeen(b[0], b[1], b[2])) {
				failures.add("a save that failed as the map closed lost fog-of-war progress: " + b[0] + " " + b[1] + " " + b[2]
					+ " isn't in " + file.getFileName() + " after closing (" + writes.get() + " writes tried)");
				return;
			}
		}
	}

	/** Saves asked for while one is being written: one more write, holding the latest table, not one each. */
	private static void savesWaitAsOne(Path file, List<String> failures) throws Exception {
		SeenBlocks seen = new SeenBlocks();
		seen.markSeen(5, -20, 5);
		CountDownLatch writing = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger writes = new AtomicInteger();
		seen.beforeEachWrite(() -> {
			if (writes.incrementAndGet() == 1) {
				writing.countDown();
				await(release);
			}
		});
		seen.save(file);
		await(writing);
		// The disk is slow and the player keeps exploring: two more periodic saves come due.
		seen.markSeen(6, -20, 5);
		seen.save(file);
		seen.markSeen(700, 30, -700);
		seen.save(file);
		release.countDown();
		seen.close(file);
		if (writes.get() != 2) {
			failures.add("three saves while the disk was slow made " + writes.get() + " writes, not 2: the one being written and one more");
		}
		SeenBlocks back = SeenBlocks.load(file);
		if (!back.isSeen(5, -20, 5) || !back.isSeen(6, -20, 5) || !back.isSeen(700, 30, -700)) {
			failures.add("after saves waited as one, the file is missing blocks seen before the last save");
		}
	}

	private static void await(CountDownLatch latch) throws IOException {
		try {
			if (!latch.await(30, TimeUnit.SECONDS)) {
				throw new IOException("the seen-save test timed out waiting on the disk thread");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		}
	}
}
