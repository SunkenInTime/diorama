pluginManagement {
	repositories {
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	id("dev.kikugie.stonecutter") version "0.9.8"
	// NeoForge's build tools run on the Java its Minecraft version needs (21 for 1.21.1), so Gradle
	// downloads that JDK if it can't find one.
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// One source tree, one jar per Minecraft version or range of versions the same jar runs on (see
// minecraft_dep in versions/<version>/gradle.properties). Up to 1.21.11 the game ships obfuscated
// and needs remapping to Mojang's names; from 26.1 it ships with those names already. Versions
// named <minecraft>-neoforge build for NeoForge instead of Fabric; NeoForge runs on Mojang's names.
stonecutter {
	create(rootProject) {
		versions("1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.9", "1.21.11").buildscript("build.obfuscated.gradle.kts")
		versions("26.1", "26.2", "26.3").buildscript("build.unobfuscated.gradle.kts")
		version("1.21-neoforge", "1.21").buildscript("build.neoforge.gradle.kts")
		version("1.21.1-neoforge", "1.21.1").buildscript("build.neoforge.gradle.kts")
		version("1.21.3-neoforge", "1.21.3").buildscript("build.neoforge.gradle.kts")
		version("1.21.4-neoforge", "1.21.4").buildscript("build.neoforge.gradle.kts")
		version("1.21.5-neoforge", "1.21.5").buildscript("build.neoforge.gradle.kts")
		version("1.21.8-neoforge", "1.21.8").buildscript("build.neoforge.gradle.kts")
		version("1.21.10-neoforge", "1.21.10").buildscript("build.neoforge.gradle.kts")
		version("1.21.11-neoforge", "1.21.11").buildscript("build.neoforge.gradle.kts")
		version("26.1.2-neoforge", "26.1.2").buildscript("build.neoforge.gradle.kts")
		version("26.2-neoforge", "26.2").buildscript("build.neoforge.gradle.kts")
		version("26.3-neoforge", "26.3").buildscript("build.neoforge.gradle.kts")
		vcsVersion = "26.3"
	}
}

rootProject.name = "diorama"

// Loom keeps the Minecraft jars it builds for each Fabric version in this checkout's
// .gradle/loom-cache, not in the shared Gradle home, because Fabric API's injected interfaces
// change them. Building them takes about five minutes, so every new git worktree used to spend
// that before its first task. Their names carry a hash of everything that went into them, so a
// jar another checkout already built is the same jar: a new worktree copies them from the
// checkout that built them most recently, and Loom builds only what's still missing.
seedLoomCache()

fun seedLoomCache() {
	val dotGit = rootDir.resolve(".git")
	val cache = rootDir.resolve(".gradle/loom-cache")
	// Only a linked worktree has a .git file, and only its first build has no cache yet.
	if (!dotGit.isFile || cache.resolve("minecraftMaven").exists()) return
	val gitDir = File(dotGit.readText().trim().removePrefix("gitdir:").trim())
	val commonDir = gitDir.resolve("commondir").takeIf { it.isFile }
		?.let { gitDir.resolve(it.readText().trim()).canonicalFile } ?: return
	val checkouts = listOf(commonDir.parentFile) + (commonDir.resolve("worktrees").listFiles() ?: emptyArray())
		.mapNotNull { it.resolve("gitdir").takeIf(File::isFile)?.readText()?.trim()?.let { path -> File(path).parentFile } }
	val source = checkouts
		.map { it.resolve(".gradle/loom-cache") }
		.filter { it.canonicalFile != cache.canonicalFile && it.resolve("minecraftMaven").isDirectory }
		.maxByOrNull { candidate -> candidate.resolve("minecraftMaven").walkTopDown().maxOfOrNull { it.lastModified() } ?: 0L }
		?: return
	val started = System.nanoTime()
	var copied = 0L
	for (folder in listOf("minecraftMaven", "remapped_mods")) {
		val from = source.resolve(folder)
		from.walkTopDown().filter { it.isFile && !it.name.endsWith(".seeding") }.forEach { file ->
			// A jar the other checkout is still writing has no central directory yet; Loom builds it here instead.
			if (file.extension == "jar" && runCatching { java.util.zip.ZipFile(file).close() }.isFailure) return@forEach
			val target = cache.resolve(folder).resolve(file.relativeTo(from))
			val partial = File(target.path + ".seeding")
			target.parentFile.mkdirs()
			file.copyTo(partial, overwrite = true)
			java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
			copied += target.length()
		}
	}
	val seconds = (System.nanoTime() - started) / 1e9
	println("Copied %d MB of Loom's Minecraft jars from %s in %.1f s".format(copied shr 20, source.parentFile.parentFile, seconds))
}
