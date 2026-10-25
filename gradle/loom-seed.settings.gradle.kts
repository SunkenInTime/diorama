// Loom keeps the Minecraft jars it builds for each Fabric version in this checkout's
// .gradle/loom-cache, not in the shared Gradle home, because Fabric API's injected interfaces
// change them. Building them takes about five minutes, so every new git worktree used to spend
// that before its first task. A new worktree now copies them from another checkout of this repo
// that has finished building them with the same Loom, and Loom builds only what's still missing.
//
// A checkout offers its jars only between builds: the marker below is removed when a build starts
// (Loom may rewrite jars while it configures) and written once every project has configured. The
// copy goes to a staging folder and is kept only if the other checkout's marker didn't change
// while it was being copied.

import java.io.File
import java.nio.file.Files

val cache = rootDir.resolve(".gradle/loom-cache")
val marker = cache.resolve("seed-ready.properties")
val staging = cache.resolve("seeding")
val folders = listOf("minecraftMaven", "remapped_mods")

marker.delete()
val seededLoom = seedLoomCache()

gradle.projectsEvaluated {
	val loom = rootProject.allprojects.asSequence()
		.flatMap { it.plugins.asSequence() }
		.firstOrNull { it.javaClass.name.startsWith("net.fabricmc.loom.") }
		?.let { it.javaClass.classLoader.loadClass("net.fabricmc.loom.LoomGradlePlugin").getField("LOOM_VERSION").get(null) as String }
		?: return@projectsEvaluated
	if (seededLoom != null && seededLoom != loom) {
		// Jar names don't change between Loom versions even when what Loom puts in them does.
		folders.forEach { cache.resolve(it).deleteRecursively() }
		throw GradleException("Copied Loom's Minecraft jars from a checkout built with Loom $seededLoom, but this one resolved Loom $loom. Removed them; run the build again and Loom will build its own.")
	}
	if (cache.resolve("minecraftMaven").isDirectory) marker.writeText("loom=$loom\nbuilt=${System.currentTimeMillis()}\n")
}

/** Returns the Loom version of the checkout it copied from, or null when it copied nothing. */
fun seedLoomCache(): String? {
	val dotGit = rootDir.resolve(".git")
	// Only a linked worktree has a .git file, and only its first build has no jars yet.
	if (!dotGit.isFile || cache.resolve("minecraftMaven").exists()) return null
	staging.deleteRecursively()
	val gitDir = rootDir.resolve(dotGit.readText().trim().removePrefix("gitdir:").trim()).canonicalFile
	val commonDir = gitDir.resolve("commondir").takeIf { it.isFile }?.let { gitDir.resolve(it.readText().trim()).canonicalFile } ?: return null
	val checkouts = listOf(commonDir.parentFile) + (commonDir.resolve("worktrees").listFiles() ?: emptyArray())
		.mapNotNull { meta -> meta.resolve("gitdir").takeIf(File::isFile)?.let { meta.resolve(it.readText().trim()).canonicalFile.parentFile } }
	val donor = checkouts
		.map { it.resolve(".gradle/loom-cache") }
		.filter { it.canonicalFile != cache.canonicalFile }
		.mapNotNull { candidate -> runCatching { candidate to readMarker(candidate) }.getOrNull()?.takeIf { it.second != null } }
		.maxByOrNull { (_, ready) -> ready!!.getProperty("built").toLong() }
		?: return null
	val (source, ready) = donor
	val started = System.nanoTime()
	try {
		folders.forEach { source.resolve(it).takeIf(File::isDirectory)?.copyRecursively(staging.resolve(it)) }
		if (readMarker(source) != ready) {
			println("Not copying Loom's Minecraft jars: ${source.parentFile.parentFile} started a build while they were being copied")
			staging.deleteRecursively()
			return null
		}
		folders.forEach { folder -> staging.resolve(folder).takeIf(File::isDirectory)?.let { Files.move(it.toPath(), cache.resolve(folder).toPath()) } }
	} catch (failure: Exception) {
		println("Not copying Loom's Minecraft jars from ${source.parentFile.parentFile}: $failure")
		staging.deleteRecursively()
		return null
	}
	staging.deleteRecursively()
	val megabytes = folders.sumOf { folder -> cache.resolve(folder).walkTopDown().filter(File::isFile).sumOf(File::length) } shr 20
	println("Copied %d MB of Loom's Minecraft jars from %s in %.1f s".format(megabytes, source.parentFile.parentFile, (System.nanoTime() - started) / 1e9))
	return ready!!.getProperty("loom")
}

fun readMarker(cache: File): java.util.Properties? {
	val file = cache.resolve("seed-ready.properties")
	if (!file.isFile) return null
	return java.util.Properties().apply { file.reader().use { load(it) } }
}
