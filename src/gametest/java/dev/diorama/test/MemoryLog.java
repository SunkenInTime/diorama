package dev.diorama.test;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * One line on where the game's memory is, for a section to log between its parts: the process's
 * resident memory, the heap (in use, committed, the most it may take, and what was live after the
 * last collection of the old generation, which a leak keeps growing), and on Linux the VM's memory
 * left and the other Java processes' (Gradle's). On a boat VM with no GPU the GL driver's buffers
 * are in the game's own memory, outside the heap.
 */
final class MemoryLog {
	private static final long MB = 1024 * 1024;

	private MemoryLog() {
	}

	static String line() {
		StringBuilder text = new StringBuilder();
		long[] own = status(Path.of("/proc/self/status"));
		if (own != null) {
			text.append("rss ").append(own[0] / 1024).append(" MB (most ").append(own[1] / 1024).append(") | ");
		}
		Runtime runtime = Runtime.getRuntime();
		text.append("heap used ").append((runtime.totalMemory() - runtime.freeMemory()) / MB).append(" committed ").append(runtime.totalMemory() / MB).append(" max ").append(runtime.maxMemory() / MB);
		long live = 0;
		long nonHeap = 0;
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() == MemoryType.NON_HEAP) {
				nonHeap += pool.getUsage().getCommitted();
				continue;
			}
			MemoryUsage after = pool.getCollectionUsage();
			if (after != null && pool.getName().contains("Old")) {
				live += after.getUsed();
			}
		}
		text.append(" old gen after last GC ").append(live / MB).append(" | non-heap committed ").append(nonHeap / MB);
		for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
			if (pool.getName().equals("direct")) {
				text.append(" direct ").append(pool.getMemoryUsed() / MB);
			}
		}
		long available = meminfo("MemAvailable:");
		if (available >= 0) {
			text.append(" | VM available ").append(available / 1024).append(" of ").append(meminfo("MemTotal:") / 1024).append(" MB");
		}
		String others = otherJava();
		if (!others.isEmpty()) {
			text.append(" | other java ").append(others);
		}
		return text.toString();
	}

	/** {VmRSS, VmHWM} in KB, or null off Linux. */
	private static long @org.jspecify.annotations.Nullable [] status(Path file) {
		try {
			long rss = -1;
			long most = -1;
			for (String line : Files.readAllLines(file)) {
				if (line.startsWith("VmRSS:")) {
					rss = kb(line);
				} else if (line.startsWith("VmHWM:")) {
					most = kb(line);
				}
			}
			return rss < 0 ? null : new long[] {rss, most};
		} catch (Exception e) {
			return null;
		}
	}

	private static long meminfo(String key) {
		try {
			for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
				if (line.startsWith(key)) {
					return kb(line);
				}
			}
		} catch (Exception ignored) {
		}
		return -1;
	}

	private static long kb(String line) {
		return Long.parseLong(line.replaceAll("[^0-9]", ""));
	}

	/** The other Java processes' resident memory, named by what they run. */
	private static String otherJava() {
		StringBuilder text = new StringBuilder();
		long self = ProcessHandle.current().pid();
		try (var dirs = Files.list(Path.of("/proc"))) {
			for (Path dir : (Iterable<Path>) dirs::iterator) {
				String name = dir.getFileName().toString();
				if (!name.chars().allMatch(Character::isDigit) || Long.parseLong(name) == self) {
					continue;
				}
				try {
					String status = Files.readString(dir.resolve("status"));
					if (!status.startsWith("Name:\tjava")) {
						continue;
					}
					long[] rss = status(dir.resolve("status"));
					String cmd = new String(Files.readAllBytes(dir.resolve("cmdline"))).replace('\0', ' ');
					String what = cmd.contains("GradleDaemon") || cmd.contains("gradle") ? "gradle" : cmd.contains("server") ? "server" : "java";
					text.append(text.isEmpty() ? "" : ", ").append(String.format(Locale.ROOT, "%s %d MB", what, rss == null ? -1 : rss[0] / 1024));
				} catch (Exception ignored) {
					// Gone meanwhile, or not ours to read.
				}
			}
		} catch (Exception ignored) {
		}
		return text.toString();
	}
}
