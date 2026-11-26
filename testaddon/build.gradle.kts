// Addons for the game tests' addon-api scenario, built against the API jar alone (api/), as an
// outside mod would be: nothing from Minecraft, a mod loader or Diorama's own code is on their
// classpath. Each jar is one mod with metadata for Fabric, NeoForge and Forge, found on Fabric
// through its diorama entrypoint and on NeoForge and Forge through @DioramaEntrypoint.
//   diorama-test-addon.jar              works, and lets the test hide and show Diorama's HUD through
//                                       it and sync waypoints with a pretend map mod by polling; it
//                                       also has a class marked @DioramaEntrypoint by mistake, and
//                                       shows markers and lines on the maps (markers/MapMarkers)
//                                       with an icon of its own: its pack.mcmeta is for Forge 1.20.1,
//                                       which loads no assets from a mod jar without one; and 300
//                                       made-up icons (many/n000 to many/n299, made at build time),
//                                       more than Diorama's icon texture holds at once
//   diorama-test-addon-broken.jar       hides the minimap and opens a layer of markers, then throws
//                                       from onReady
//   diorama-test-addon-linkage.jar      hides the minimap, then calls a class its jar doesn't have
//   diorama-test-addon-assertion.jar    hides the minimap, then throws an AssertionError
//   diorama-test-addon-unprintable.jar  hides the minimap, then throws an exception whose
//                                       toString() throws
//   diorama-test-addon-overflow.jar     hides the minimap, then throws a StackOverflowError
//                                       wrapped in its own RuntimeException
//   diorama-test-addon-recursive.jar    hides the minimap, then throws an exception whose
//                                       getMessage() calls itself
// ./gradlew :testaddon:assemble puts them in testaddon/build/libs; boat_run.py --mods takes that
// folder (docs/development.md). Never released.
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

plugins {
	java
}

repositories {
	mavenCentral()
}

val main = sourceSets.main.get()
val broken = sourceSets.create("broken")
// Compiled against, never packed: the linkage addon's jar is missing it, as a jar built against a
// newer library would be.
val missing = sourceSets.create("missing")
val linkage = sourceSets.create("linkage")
val assertion = sourceSets.create("assertion")
val unprintable = sourceSets.create("unprintable")
val overflow = sourceSets.create("overflow")
val recursive = sourceSets.create("recursive")
val addons = listOf(main, broken, linkage, assertion, unprintable, overflow, recursive)

dependencies {
	for (set in addons) {
		add(set.compileOnlyConfigurationName, project(":api"))
	}
	add(linkage.compileOnlyConfigurationName, missing.output)
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(17)
	options.encoding = "UTF-8"
}

for (set in addons) {
	tasks.named<ProcessResources>(set.processResourcesTaskName) {
		// Local to the task, which the configuration cache keeps; a script-level value it can't.
		val props = mapOf("version" to "1.0.0")
		inputs.properties(props)
		filesMatching(listOf("fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
			expand(props)
		}
	}
}

// 300 waypoint icons, each different, for the test that shows more icons over time than
// Diorama's marker texture holds at once: made here rather than kept in git.
val manyIcons = tasks.register("manyIcons") {
	val out = layout.buildDirectory.dir("generated/many-icons")
	outputs.dir(out)
	doLast {
		val folder = out.get().dir("assets/diorama_test_addon/textures/waypoint_icons/many").asFile
		folder.mkdirs()
		for (n in 0 until 300) {
			val image = BufferedImage(11, 11, BufferedImage.TYPE_INT_ARGB)
			val rgb = Color.HSBtoRGB(n / 300f, 0.8f, 0.9f)
			for (y in 0 until 11) {
				for (x in 0 until 11) {
					val edge = x == 0 || y == 0 || x == 10 || y == 10
					// The number in binary across the middle row, so no two are alike.
					val bit = y == 5 && x in 1..9 && (n shr (x - 1) and 1) == 1
					image.setRGB(x, y, if (edge) 0xFF101418.toInt() else if (bit) -1 else rgb)
				}
			}
			ImageIO.write(image, "png", File(folder, "n" + n.toString().padStart(3, '0') + ".png"))
		}
	}
}
main.resources.srcDir(manyIcons)

tasks.named<Jar>("jar") {
	archiveFileName = "diorama-test-addon.jar"
}
for ((set, name) in listOf(broken to "broken", linkage to "linkage", assertion to "assertion", unprintable to "unprintable", overflow to "overflow", recursive to "recursive")) {
	val jar = tasks.register<Jar>("${name}Jar") {
		from(set.output)
		archiveFileName = "diorama-test-addon-$name.jar"
	}
	tasks.named("assemble") {
		dependsOn(jar)
	}
}
