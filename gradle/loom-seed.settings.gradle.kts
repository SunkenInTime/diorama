// Loom keeps the Minecraft jars it builds for each Fabric version in this checkout's
// .gradle/loom-cache, not in the shared Gradle home, because what Fabric API adds to Minecraft
// changes them. Building them takes about five minutes, so every new git worktree used to spend that
// before its first task. Now a checkout saves them to a snapshot in the Gradle home once they're
// built, and a checkout that has none yet copies the newest snapshot instead of building them.
//
// A snapshot is only taken right after every project has configured, before any task runs: Loom
// finishes or rebuilds its jars while configuring, and tasks like genSources rewrite them later.
// Every jar has to read back whole first, and a snapshot appears under its final name only once
// it's complete. Jar names don't change between Loom versions even when their contents might, so
// a checkout that copied a snapshot keeps a note of its Loom version until a configured build
// confirms it matches.

import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

val cache = rootDir.resolve(".gradle/loom-cache")
val folders = listOf("minecraftMaven", "remapped_mods")
val store = gradle.gradleUserHomeDir.resolve("diorama-loom-seed")
val copiedFrom = cache.resolve("seeded-from.properties")

seedLoomCache()

gradle.projectsEvaluated {
	val loom = rootProject.allprojects.asSequence()
		.flatMap { it.plugins.asSequence() }
		.firstOrNull { it.javaClass.name.startsWith("net.fabricmc.loom.") }
		?.let { it.javaClass.classLoader.loadClass("net.fabricmc.loom.LoomGradlePlugin").getField("LOOM_VERSION").get(null) as String }
		?: return@projectsEvaluated
	properties(copiedFrom)?.getProperty("loom")?.let { copied ->
		if (copied != loom) {
			folders.forEach { cache.resolve(it).deleteRecursively() }
			copiedFrom.delete()
			throw GradleException("Copied Loom's Minecraft jars from a snapshot built with Loom $copied, but this checkout resolved Loom $loom. Removed them; run the build again and Loom will build its own.")
		}
		copiedFrom.delete()
	}
	// Configure-on-demand leaves some projects' jars as an earlier build left them.
	if (!gradle.startParameter.isConfigureOnDemand) saveSnapshot(loom)
}

fun seedLoomCache() {
	if (cache.resolve("minecraftMaven").exists()) return
	val snapshot = snapshots().maxByOrNull { it.second.getProperty("created").toLong() } ?: return
	val (source, manifest) = snapshot
	val staging = cache.resolve("seeding")
	val started = System.nanoTime()
	try {
		staging.deleteRecursively()
		folders.forEach { source.resolve(it).takeIf(File::isDirectory)?.copyRecursively(staging.resolve(it)) }
		cache.mkdirs()
		copiedFrom.writeText("loom=${manifest.getProperty("loom")}\nsnapshot=${source.name}\n")
		folders.forEach { folder -> staging.resolve(folder).takeIf(File::isDirectory)?.let { if (!it.renameTo(cache.resolve(folder))) error("could not move $it into place") } }
	} catch (failure: Exception) {
		println("Not copying Loom's Minecraft jars from $source: $failure")
		folders.forEach { cache.resolve(it).deleteRecursively() }
		copiedFrom.delete()
		return
	} finally {
		staging.deleteRecursively()
	}
	println("Copied %d MB of Loom's Minecraft jars from %s in %.1f s".format(size(cache) shr 20, source, (System.nanoTime() - started) / 1e9))
}

fun saveSnapshot(loom: String) {
	val ours = files(cache)
	if (ours.isEmpty()) return
	val newest = snapshots().filter { it.second.getProperty("loom") == loom }.maxByOrNull { it.second.getProperty("created").toLong() }
	if (newest != null && files(newest.first).entries.containsAll(ours.entries)) return
	val started = System.nanoTime()
	val broken = ours.keys.filter { it.endsWith(".jar") || it.endsWith(".jar.backup") }.firstOrNull { !readsWhole(cache.resolve(it)) }
	if (broken != null) {
		println("Not saving Loom's Minecraft jars for new worktrees: $broken doesn't read back whole")
		return
	}
	val created = System.currentTimeMillis()
	val partial = store.resolve(".partial-$created-${ProcessHandle.current().pid()}")
	try {
		folders.forEach { cache.resolve(it).takeIf(File::isDirectory)?.copyRecursively(partial.resolve(it)) }
		partial.resolve("snapshot.properties").writeText("loom=$loom\ncreated=$created\nfrom=$rootDir\n")
		if (!partial.renameTo(store.resolve("$created-${ProcessHandle.current().pid()}"))) error("could not rename $partial")
	} catch (failure: Exception) {
		println("Not saving Loom's Minecraft jars for new worktrees: $failure")
		return
	} finally {
		partial.deleteRecursively()
	}
	// Keep the newest two; anything older than an hour can't still be in the middle of a copy.
	val hourAgo = created - 3_600_000
	snapshots().sortedByDescending { it.second.getProperty("created").toLong() }.drop(2)
		.filter { it.second.getProperty("created").toLong() < hourAgo }.forEach { it.first.deleteRecursively() }
	store.listFiles { file -> file.name.startsWith(".partial-") && file.lastModified() < hourAgo }?.forEach { it.deleteRecursively() }
	println("Saved %d MB of Loom's Minecraft jars for new worktrees in %.1f s".format(size(cache) shr 20, (System.nanoTime() - started) / 1e9))
}

/** Complete snapshots with their manifests; anything unreadable is skipped. */
fun snapshots(): List<Pair<File, Properties>> = (store.listFiles() ?: emptyArray())
	.filter { it.isDirectory && !it.name.startsWith(".") }
	.mapNotNull { dir -> properties(dir.resolve("snapshot.properties"))?.takeIf { it.getProperty("loom") != null && it.getProperty("created")?.toLongOrNull() != null }?.let { dir to it } }

fun properties(file: File): Properties? = runCatching { Properties().apply { file.reader().use { load(it) } } }.getOrNull()

/** Relative path to size, for the folders Loom keeps per checkout. */
fun files(root: File): Map<String, Long> = folders.flatMap { folder ->
	root.resolve(folder).walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath to it.length() }.toList()
}.toMap()

fun size(root: File): Long = files(root).values.sum()

/** Reads every entry, so a truncated or half-written jar fails its checksum here. */
fun readsWhole(jar: File): Boolean = runCatching {
	ZipFile(jar).use { zip -> zip.entries().asSequence().forEach { entry -> zip.getInputStream(entry).use { it.readAllBytes() } } }
}.isSuccess
