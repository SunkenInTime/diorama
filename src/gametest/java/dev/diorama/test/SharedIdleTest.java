package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapSession;
import dev.diorama.render.SavedChunkImport;
import dev.diorama.share.ShareClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * A player standing still on a real server that shares a big map (#451): what the fill's own thread
 * costs while there's nothing for it to draw. The server's world gets {@code SHARED_IDLE_REGIONS}
 * more region files, each with no chunks in it, which is what land nobody has visited looks like to
 * a player: a table that shares nothing. Fails if the tables coming in have the fill list its
 * regions over and over, as each one did before.
 */
final class SharedIdleTest {
	private SharedIdleTest() {
	}

	static void run(ClientGameTestContext context) {
		int regions = Integer.parseInt(System.getenv().getOrDefault("SHARED_IDLE_REGIONS", "2000"));
		int seconds = Integer.parseInt(System.getenv().getOrDefault("SHARED_IDLE_SECONDS", "200"));
		List<String> results = new ArrayList<>();
		long listed;
		double elapsed;
		double perListing;
		int known;
		// Once to make the world, so the region files go where this version keeps the overworld's.
		try (RealServer server = RealServer.start(true)) {
			// The overworld's region folder is made with its first saved chunk.
			server.commandAndWait("save-all flush", "Saved the game", 600);
			plant(server, regions);
		}
		try (RealServer server = RealServer.start(true)) {
			server.join(context);
			context.waitFor(mc -> ShareClient.sharing() && MapSession.get() != null && MapSession.get().savedChunks() != null, 2400);
			Sample start = Sample.take();
			Sample last = start;
			for (int passed = 0; passed < seconds; passed += 20) {
				context.waitTicks(400);
				Sample now = Sample.take();
				results.add(String.format(Locale.ROOT, "standing %3d-%3d s: %s", passed, passed + 20, now.since(last)));
				last = now;
			}
			results.add(String.format(Locale.ROOT, "standing, whole %d s: %s", seconds, last.since(start)));
			listed = last.listings - start.listings;
			elapsed = (last.nanoTime - start.nanoTime) / 1e9;

			// Each listing on its own, as one more table from the server sets off.
			SavedChunkImport job = context.computeOnClient(mc -> MapSession.get().savedChunks());
			context.waitFor(mc -> job.idle(), 6000);
			Sample before = Sample.take();
			int listings = 20;
			for (int i = 0; i < listings; i++) {
				context.runOnClient(mc -> job.rescanSoon());
				context.waitFor(mc -> job.idle(), 6000);
			}
			Sample after = Sample.take();
			known = context.computeOnClient(mc -> job.regionsListed());
			perListing = (after.bytes - before.bytes) / 1048576.0 / Math.max(1, after.listings - before.listings);
			results.add(String.format(Locale.ROOT, "one listing of %d regions: %.1f ms of the fill's thread, %.2f MB allocated",
				known, (after.cpuNanos - before.cpuNanos) / 1e6 / Math.max(1, after.listings - before.listings), perListing));
			RealServer.leave(context);
		}
		for (String result : results) {
			Diorama.LOGGER.info("SHARED-IDLE {}", result);
		}
		try {
			Path out = Path.of(System.getProperty("user.dir"), "screenshots", "shared-idle.txt");
			Files.createDirectories(out.getParent());
			Files.writeString(out, String.join("\n", results) + "\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		if (known < regions) {
			throw new AssertionError("The fill knows " + known + " regions of the " + regions + " planted: this wouldn't show what they cost");
		}
		// One listing a second while the tables come and one a minute after, with room to spare: a
		// listing for each table was one for every region, each going through all the others.
		if (listed > elapsed / 10 + 5) {
			throw new AssertionError("Standing still for " + Math.round(elapsed) + " s on a server sharing " + known + " regions, the fill listed them " + listed + " times");
		}
		// A listing that copies every table takes 4 KB a region; one that reads them in place, a third of a KB.
		if (perListing > known / 1024.0 + 1) {
			throw new AssertionError("One listing of " + known + " regions allocated " + String.format(Locale.ROOT, "%.2f", perListing) + " MB");
		}
	}

	/** Region files with a header and nothing else, far from spawn, in the server's overworld. */
	private static void plant(RealServer server, int regions) {
		// 26.x keeps the overworld under dimensions/; before, in the world's own folder.
		Path newer = server.folder.resolve("world/dimensions/minecraft/overworld/region");
		Path folder = Files.isDirectory(newer) ? newer : server.folder.resolve("world").resolve("region");
		int side = (int) Math.ceil(Math.sqrt(regions));
		try {
			Files.createDirectories(folder);
			// An earlier run's, when this one wants fewer.
			try (var files = Files.list(folder)) {
				for (Path file : files.toList()) {
					if (Files.size(file) == 8192) {
						Files.delete(file);
					}
				}
			}
			byte[] empty = new byte[8192];
			for (int i = 0; i < regions; i++) {
				Files.write(folder.resolve("r." + (1000 + i % side) + "." + (1000 + i / side) + ".mca"), empty);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** What the fill's thread has used so far, and how many times it has listed the regions. */
	private record Sample(long cpuNanos, long bytes, long listings, long nanoTime) {
		static Sample take() {
			com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			long cpu = 0;
			long bytes = 0;
			for (Thread thread : Thread.getAllStackTraces().keySet()) {
				if (thread.getName().equals("diorama-saved-chunks")) {
					cpu += Math.max(0, threads.getThreadCpuTime(thread.getId()));
					bytes += Math.max(0, threads.getThreadAllocatedBytes(thread.getId()));
				}
			}
			MapSession session = MapSession.get();
			SavedChunkImport job = session == null ? null : session.savedChunks();
			return new Sample(cpu, bytes, job == null ? 0 : job.listings(), System.nanoTime());
		}

		String since(Sample earlier) {
			double seconds = (this.nanoTime - earlier.nanoTime) / 1e9;
			return String.format(Locale.ROOT, "%.0f s of clock, fill thread %.0f ms busy (%.1f%% of a core), %.0f MB allocated (%.1f MB/s), %d listings",
				seconds, (this.cpuNanos - earlier.cpuNanos) / 1e6, (this.cpuNanos - earlier.cpuNanos) / 1e7 / seconds,
				(this.bytes - earlier.bytes) / 1048576.0, (this.bytes - earlier.bytes) / 1048576.0 / seconds,
				this.listings - earlier.listings);
		}
	}
}
