// Diorama's API for other mods (dev.diorama.api), compiled once for every Minecraft version and
// mod loader. Only the JDK and JSpecify's and JetBrains' annotations are on its classpath, so naming a Minecraft,
// mod loader or Diorama class fails this build. Each version's jar carries these classes as they
// are compiled here (gradle/common.gradle.kts), and this project's jar, with its sources and
// Javadoc jars, is what an addon compiles against. It is published as dev.diorama:diorama-api to
// the Maven repository on GitHub Pages (https://sunkenintime.github.io/diorama/maven) by
// .github/workflows/publish-api.yml, when an api-v<version> tag is pushed.
plugins {
	`java-library`
	`maven-publish`
}

group = "dev.diorama"
version = property("api_version") as String
base.archivesName = "diorama-api"

repositories {
	mavenCentral()
}

dependencies {
	compileOnly("org.jspecify:jspecify:1.0.0")
	// @ApiStatus.Experimental on MapView, as Fabric API and NeoForge mark theirs. Kept only in the
	// class files: nothing needs it at run time, and the game's own libraries carry it.
	compileOnly("org.jetbrains:annotations:24.1.0")
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

// Published as dev.diorama:diorama-api: the same jar the versions' jars copy their API classes from,
// with its sources and Javadoc jars. Only to the folder -PapiRepoDir names, which the publishing
// workflow sets to the maven/ folder of its gh-pages checkout; without it nothing is published.
publishing {
	publications {
		create<MavenPublication>("api") {
			artifactId = "diorama-api"
			from(components["java"])
			pom {
				name = "Diorama API"
				description = "Alpha: the API other Minecraft mods use to work with Diorama, the 3D map mod. " +
					"It has no Minecraft or mod loader types. Compile against it and don't bundle it: " +
					"Diorama's own jar carries these classes. Not yet a compatibility promise; names and behaviour may change before 1.0.0."
				url = "https://github.com/SunkenInTime/diorama"
				licenses {
					license {
						name = "MIT License"
						url = "https://github.com/SunkenInTime/diorama/blob/master/LICENSE"
						distribution = "repo"
					}
				}
				scm {
					url = "https://github.com/SunkenInTime/diorama"
					connection = "scm:git:https://github.com/SunkenInTime/diorama.git"
					developerConnection = "scm:git:git@github.com:SunkenInTime/diorama.git"
				}
				issueManagement {
					system = "GitHub"
					url = "https://github.com/SunkenInTime/diorama/issues"
				}
			}
		}
	}
	repositories {
		providers.gradleProperty("apiRepoDir").orNull?.let { dir ->
			maven {
				name = "pages"
				url = uri(file(dir))
			}
		}
	}
}

// A published version is never written again: addons that resolved it once must get the same
// bytes every time. So publishing refuses a version the repository folder already has, and a
// version without a release number of its own (1.0.0-alpha.3, not 1.0.0-alpha or a -SNAPSHOT).
tasks.withType<PublishToMavenRepository>().configureEach {
	val version = project.version.toString()
	val repoDir = providers.gradleProperty("apiRepoDir").map { file(it) }
	doFirst {
		if (!Regex("""\d+\.\d+\.\d+(-(alpha|beta|rc)\.\d+)?""").matches(version)) {
			throw GradleException("API version $version can't be published: use major.minor.patch, or that with -alpha.N, -beta.N or -rc.N (api_version in gradle.properties)")
		}
		val published = repoDir.get().resolve("dev/diorama/diorama-api/$version")
		if (published.exists()) {
			throw GradleException("dev.diorama:diorama-api:$version is already published (${published.path}); published versions never change, so raise api_version instead")
		}
	}
}
