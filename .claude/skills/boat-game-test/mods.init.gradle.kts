// Other mods' release jars in a game test run: boat_run.py --mods, through sandbox.sh, which passes
// this file to Gradle with -I and the jars' folder in DIORAMA_TEST_MODS. A run without --mods never
// loads it. Release jars use the names a real install runs on, so how one loads depends on the node:
// - Fabric 26.x: the game ships with Mojang's names, release jars use them too, and Fabric Loader
//   takes the folder as it is (fabric.addMods).
// - Fabric up to 1.21.11: release jars use intermediary names and the development run Mojang's, so
//   sandbox.sh runs runProductionClientGameTest instead, which takes them in its mod list.
// - Forge 1.20.1: release jars use Forge's (SRG) names and the development run Mojang's. ModDevGradle
//   remaps each jar onto the game tests' runtime classpath, where Forge finds it as a mod.
// - NeoForge runs on Mojang's names everywhere: sandbox.sh copies the jars into the game's mods
//   folder, and this file isn't used.
val dir = System.getenv("DIORAMA_TEST_MODS")
if (dir != null) {
	allprojects {
		val jars = fileTree(dir) { include("*.jar") }
		tasks.matching { it.name == "runProductionClientGameTest" }.configureEach {
			// Loom's ClientProductionRunTask, which this script can't name: Loom isn't on its classpath.
			val mods = javaClass.getMethod("getMods").invoke(this) as ConfigurableFileCollection
			mods.from(jars)
			// The game on another Java than the VM's own, for a mod that needs it (sandbox.sh installs it).
			val java = System.getenv("DIORAMA_TEST_JAVA")
			if (!java.isNullOrEmpty()) {
				@Suppress("UNCHECKED_CAST")
				val launcher = javaClass.getMethod("getJavaLauncher").invoke(this) as Property<JavaLauncher>
				launcher.set(project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
					languageVersion.set(JavaLanguageVersion.of(java))
				})
			}
		}
		if (!name.endsWith("forge")) {
			tasks.matching { it.name == "runClientGameTest" }.configureEach {
				(this as JavaExec).jvmArgs("-Dfabric.addMods=$dir")
			}
		}
		plugins.withId("net.neoforged.moddev.legacyforge") {
			afterEvaluate {
				val obfuscation = extensions.getByName("obfuscation")
				val remapping = obfuscation.javaClass.getMethod("createRemappingConfiguration", Configuration::class.java)
					.invoke(obfuscation, configurations.getByName("gametestRuntimeOnly")) as Configuration
				// The remapping takes modules, not files: each jar is one, found by its file name.
				repositories.flatDir { dirs(dir) }
				for (jar in jars.files.sorted()) {
					dependencies.add(remapping.name, "diorama.testmods:" + jar.name.removeSuffix(".jar") + ":0")
				}
			}
		}
	}
}
