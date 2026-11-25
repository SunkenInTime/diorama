// Diorama's API for other mods (dev.diorama.api), compiled once for every Minecraft version and
// mod loader. Only the JDK and JSpecify's annotations are on its classpath, so naming a Minecraft,
// mod loader or Diorama class fails this build. Each version's jar carries these classes as they
// are compiled here (gradle/common.gradle.kts), and this project's jar, with its sources and
// Javadoc jars, is what an addon compiles against. It isn't published anywhere yet.
plugins {
	`java-library`
}

group = "dev.diorama"
version = property("api_version") as String
base.archivesName = "diorama-api"

repositories {
	mavenCentral()
}

dependencies {
	compileOnly("org.jspecify:jspecify:1.0.0")
}

java {
	withSourcesJar()
	withJavadocJar()
}

// Java 17, the oldest any Diorama jar runs on (Minecraft 1.20.1).
tasks.withType<JavaCompile>().configureEach {
	options.release.set(17)
	options.encoding = "UTF-8"
	options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.named<Javadoc>("javadoc") {
	(options as StandardJavadocDocletOptions).apply {
		encoding = "UTF-8"
		addStringOption("-release", "17")
		addBooleanOption("Xdoclint:all", true)
		addBooleanOption("Xwerror", true)
	}
}

// The version DioramaApi.apiVersion() reports, read from inside whichever jar carries the classes.
tasks.named<ProcessResources>("processResources") {
	val props = mapOf("version" to project.version.toString())
	inputs.properties(props)
	filesMatching("dev/diorama/api/api.properties") {
		expand(props)
	}
}

tasks.named<Jar>("jar") {
	manifest.attributes(
		"Implementation-Title" to "Diorama API",
		"Implementation-Version" to project.version.toString(),
	)
	from(rootProject.file("LICENSE")) {
		rename { "${it}_diorama-api" }
	}
}
