// Addons for the game tests' addon-api scenario, built against the API jar alone (api/), as an
// outside mod would be: nothing from Minecraft, a mod loader or Diorama's own code is on their
// classpath. Each jar is one mod with metadata for Fabric, NeoForge and Forge, found on Fabric
// through its diorama entrypoint and on NeoForge and Forge through @DioramaEntrypoint.
//   diorama-test-addon.jar          works, and lets the test hide and show Diorama's HUD through it
//   diorama-test-addon-broken.jar   hides the minimap, then throws from onReady
//   diorama-test-addon-linkage.jar  hides the minimap, then calls a class its jar doesn't have
// ./gradlew :testaddon:assemble puts them in testaddon/build/libs; boat_run.py --mods takes that
// folder (docs/development.md). Never released.
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

dependencies {
	for (set in listOf(main, broken, linkage)) {
		add(set.compileOnlyConfigurationName, project(":api"))
	}
	add(linkage.compileOnlyConfigurationName, missing.output)
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(17)
	options.encoding = "UTF-8"
}

for (set in listOf(main, broken, linkage)) {
	tasks.named<ProcessResources>(set.processResourcesTaskName) {
		// Local to the task, which the configuration cache keeps; a script-level value it can't.
		val props = mapOf("version" to "1.0.0")
		inputs.properties(props)
		filesMatching(listOf("fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml")) {
			expand(props)
		}
	}
}

tasks.named<Jar>("jar") {
	archiveFileName = "diorama-test-addon.jar"
}
for ((set, name) in listOf(broken to "broken", linkage to "linkage")) {
	val jar = tasks.register<Jar>("${name}Jar") {
		from(set.output)
		archiveFileName = "diorama-test-addon-$name.jar"
	}
	tasks.named("assemble") {
		dependsOn(jar)
	}
}
