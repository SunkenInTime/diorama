// Shared by every build script: Java level, resource expansion and the jar's contents.
val javaVersion = (project.property("java_version") as String).toInt()
val resourceProps = mapOf(
	"version" to project.version.toString(),
	"minecraft" to project.property("minecraft_dep") as String,
	"loader" to project.property("loader_version") as String,
	"java" to javaVersion.toString(),
) + listOfNotNull(
	project.findProperty("neoforge_dep")?.let { "neoforge" to it as String },
	project.findProperty("forge_dep")?.let { "forge" to it as String },
)

tasks.named<ProcessResources>("processResources") {
	val props = resourceProps
	inputs.properties(props)
	filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml", "diorama.mixins.json")) {
		expand(props)
	}
	// The vanilla colour table made for this jar's Minecraft version (the vanilla-table game test),
	// under the one name the code reads.
	val table = "${project.property("minecraft_version")}.dat"
	exclude { it.path.startsWith("diorama/vanilla/") && it.name != table }
	filesMatching("diorama/vanilla/$table") {
		path = "diorama/vanilla_looks.dat"
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(javaVersion)
	options.encoding = "UTF-8"
	options.compilerArgs.addAll(listOf("-Xmaxerrs", "5000"))
}

configure<JavaPluginExtension> {
	withSourcesJar()
	sourceCompatibility = JavaVersion.toVersion(javaVersion)
	targetCompatibility = JavaVersion.toVersion(javaVersion)
}

// Mod Menu's API is compiled against stand-ins in src/main; the real one comes with Mod Menu.
tasks.withType<Jar>().configureEach {
	exclude("com/terraformersmc/**")
}

tasks.named<Jar>("jar") {
	from(rootProject.file("LICENSE")) {
		rename { "${it}_diorama" }
	}
}

// The addon API (api/), compiled once for every version. Its classes and resources join this
// version's main output as they are: the jar carries them (copied in, not as a jar inside the jar),
// development runs load them as part of Diorama, and the main source and the tests compile
// against them.
evaluationDependsOn(":api")
val apiMain = project(":api").extensions.getByType(SourceSetContainer::class.java).getByName("main")
extensions.getByType(SourceSetContainer::class.java).named("main") {
	for (dir in apiMain.output.classesDirs.files) {
		output.dir(mapOf("builtBy" to ":api:compileJava"), dir)
	}
	output.dir(mapOf("builtBy" to ":api:processResources"), apiMain.output.resourcesDir!!)
}
dependencies {
	"compileOnly"(apiMain.output)
}
// The game tests drive the test addons (testaddon/), which a run installs as jars of their own.
evaluationDependsOn(":testaddon")
val testAddons = project(":testaddon").extensions.getByType(SourceSetContainer::class.java)
extensions.getByType(SourceSetContainer::class.java).matching { it.name == "gametest" }.configureEach {
	for (name in listOf("main", "broken", "linkage", "assertion", "unprintable")) {
		dependencies.add(compileOnlyConfigurationName, testAddons.getByName(name).output)
	}
}

afterEvaluate {
	val releaseTask = listOf("remapJar", "reobfJar").firstOrNull { it in tasks.names } ?: "jar"
	val releaseJar = tasks.named(releaseTask).map { task -> task.outputs.files.files.single { it.name.endsWith(".jar") } }
	val apiJar = project(":api").tasks.named<Jar>("jar").flatMap { it.archiveFile }

	// Remapping the jar to the names a real install uses (Fabric up to 1.21.11, Forge) rewrites
	// every class through ASM, the API's too: nothing in them is renamed, but their constant pools
	// come out reordered. Put javac's bytes back, so every version's jar carries the same files.
	if (releaseTask != "jar") {
		tasks.named(releaseTask) {
			inputs.file(apiJar)
			doLast {
				val out = outputs.files.files.single { it.name.endsWith(".jar") }
				java.util.zip.ZipFile(apiJar.get().asFile).use { api ->
					java.nio.file.FileSystems.newFileSystem(out.toPath(), mapOf<String, Any>()).use { jar ->
						for (entry in api.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("dev/diorama/api/") }) {
							val target = jar.getPath(entry.name)
							if (!java.nio.file.Files.exists(target)) {
								// checkApiCopy says so.
								continue
							}
							// The time remapping gave it, so the jar stays the same from build to build.
							val time = java.nio.file.Files.getLastModifiedTime(target)
							java.nio.file.Files.write(target, api.getInputStream(entry).readBytes())
							java.nio.file.Files.setLastModifiedTime(target, time)
						}
					}
				}
			}
		}
	}

	// Checks the release jar carries the API exactly as api/ built it: the same files, byte for
	// byte, and none of them naming a Minecraft, mod loader or Diorama class outside the API. Part
	// of ./gradlew build, through check.
	val checkApiCopy = tasks.register("checkApiCopy") {
		group = "verification"
		description = "Checks the release jar carries the addon API classes exactly as api/ compiled them, with no Minecraft references."
		dependsOn(releaseTask)
		inputs.file(apiJar)
		inputs.files(releaseJar)
		val marker = layout.buildDirectory.file("checkApiCopy/ok")
		outputs.file(marker)
		doLast {
			fun apiEntries(jar: File): Map<String, ByteArray> = java.util.zip.ZipFile(jar).use { zip ->
				zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("dev/diorama/api/") }
					.associate { it.name to zip.getInputStream(it).readBytes() }
			}
			// Every name a class file's constant pool spells out: classes, descriptors, signatures.
			fun names(bytes: ByteArray): List<String> {
				val data = java.io.DataInputStream(bytes.inputStream())
				data.readInt()
				data.readUnsignedShort()
				data.readUnsignedShort()
				val count = data.readUnsignedShort()
				val out = mutableListOf<String>()
				var i = 1
				while (i < count) {
					when (val tag = data.readUnsignedByte()) {
						1 -> out.add(data.readUTF())
						3, 4, 9, 10, 11, 12, 17, 18 -> data.readInt()
						5, 6 -> {
							data.readLong()
							i++
						}
						7, 8, 16, 19, 20 -> data.readUnsignedShort()
						15 -> {
							data.readUnsignedByte()
							data.readUnsignedShort()
						}
						else -> throw GradleException("Unknown constant pool tag $tag")
					}
					i++
				}
				return out
			}
			val apiFile = apiJar.get().asFile
			val releaseFile = releaseJar.get()
			val expected = apiEntries(apiFile)
			val actual = apiEntries(releaseFile)
			val problems = mutableListOf<String>()
			if (expected.none { it.key.endsWith(".class") }) {
				problems.add("${apiFile.name} has no API classes")
			}
			(expected.keys - actual.keys).sorted().forEach { problems.add("missing $it") }
			(actual.keys - expected.keys).sorted().forEach { problems.add("not in the API jar: $it") }
			expected.keys.filter { it in actual && !expected.getValue(it).contentEquals(actual.getValue(it)) }.sorted()
				.forEach { problems.add("changed from the API jar's: $it") }
			val outside = Regex("net/minecraft|com/mojang|net/fabricmc|net/neoforged|net/minecraftforge|cpw/mods|dev/diorama/(?!api/)")
			for ((name, bytes) in actual.toSortedMap()) {
				if (name.endsWith(".class")) {
					names(bytes).mapNotNull { outside.find(it)?.let { _ -> it } }.distinct().forEach { problems.add("$name names $it") }
				}
			}
			if (problems.isNotEmpty()) {
				throw GradleException("The addon API in ${releaseFile.name} isn't api/'s:\n  " + problems.joinToString("\n  "))
			}
			marker.get().asFile.apply {
				parentFile.mkdirs()
				writeText("${actual.size} API files in ${releaseFile.name} match ${apiFile.name}\n")
			}
			logger.lifecycle("${actual.size} API files in ${releaseFile.name} match ${apiFile.name}, no Minecraft or loader names")
		}
	}
	tasks.named("check") {
		dependsOn(checkApiCopy)
	}
}
