# Making an addon for Diorama

Diorama has a small API for other mods: hide the minimap or the waypoints in the world while your mod shows its own view, read and edit the player's waypoints, and put markers and lines on the minimap and the full map. It has no Minecraft or mod loader types in it, so one jar works on every Minecraft version and loader Diorama runs on: Fabric, NeoForge and Forge, 1.20.1 to 26.3.

**It is alpha.** Names and behaviour can still change before 1.0.0, and an alpha can drop or rename something the one before had. Each alpha has its own number (`1.0.0-alpha.1`, `1.0.0-alpha.2`, ...) and a published one never changes, so a build that worked once keeps resolving the same jar.

## Add it to your build

The jar is in a Maven repository on GitHub Pages:

- Repository: `https://sunkenintime.github.io/diorama/maven`
- Coordinates: `dev.diorama:diorama-api:1.0.0-alpha.2`

Compile against it and don't bundle it. Diorama carries the same classes inside its own jar, so a copy in yours would clash with it. That means `compileOnly`, never `implementation`, `include` or `jarJar`. It needs Java 17 or newer.

The same lines go in `build.gradle` for Fabric (Loom), NeoForge (ModDevGradle or NeoGradle) and Forge (ForgeGradle):

```groovy
repositories {
    maven {
        name = "Diorama"
        url = "https://sunkenintime.github.io/diorama/maven"
        content { includeGroup "dev.diorama" }
    }
}

dependencies {
    compileOnly "dev.diorama:diorama-api:1.0.0-alpha.2"
}
```

Not `modCompileOnly` on Loom and no `fg.deobf(...)` on ForgeGradle: the API names nothing from Minecraft, so there is nothing to remap.

In `build.gradle.kts` it's the same with Kotlin's syntax: `maven("https://sunkenintime.github.io/diorama/maven")` and `compileOnly("dev.diorama:diorama-api:1.0.0-alpha.2")`.

The sources and Javadoc jars are published next to it, so your IDE shows the documentation. Start at `DioramaAddon`.

## Let Diorama find your addon

- **Fabric:** an entrypoint named `diorama` in `fabric.mod.json`:
  ```json
  "entrypoints": {
      "diorama": ["com.example.MyDioramaAddon"]
  },
  "suggests": {
      "diorama": "*"
  }
  ```
- **NeoForge and Forge:** put `@DioramaEntrypoint` on the class.

A jar for several loaders can do both. The class implements `DioramaAddon`; Diorama calls its `onReady` once the game has loaded. Diorama never loads that class when it isn't installed, so a mod that supports Diorama optionally needs no reflection: keep every reference to `dev.diorama.api` inside that class and the classes only it uses.

Don't give Diorama an upper version bound in your metadata. Check `DioramaApi.apiVersion()` at run time if you need a newer call.

## Which Diorama has it

Diorama 0.12.0 and later carry the API. Diorama 0.12.0 provides `1.0.0-alpha.2`.

The repository's `testaddon/` folder is a working example: an addon built against the API jar alone.
