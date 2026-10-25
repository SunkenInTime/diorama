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

// A new git worktree copies Loom's Minecraft jars from another checkout instead of spending about
// five minutes rebuilding them.
apply(from = "gradle/loom-seed.settings.gradle.kts")
