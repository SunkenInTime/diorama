# Building and testing Diorama

Needs JDK 25. Gradle downloads everything else.

## One source tree, many versions

One source tree builds a jar for each supported Minecraft version, using [Stonecutter](https://codeberg.org/stonecutter/stonecutter). The source is written for the newest version, 26.3. Names that only changed between versions are translated by the replacements in `stonecutter.gradle.kts`. Code that differs in other ways sits in `//? if >=26.1 { … //?} else { /* … *///?}` blocks, with the older version commented out. Keep the active version at 26.3: the replacements only translate in one direction.

```sh
./gradlew :26.3:build
./gradlew :26.2:build
./gradlew :26.1:build   # also runs on 26.1.1 and 26.1.2
./gradlew :1.21.11:build
./gradlew :1.21.1-neoforge:build   # NeoForge 21.1.178 or later; every NeoForge version is <minecraft>-neoforge
./gradlew :1.20.1-forge:build      # Forge 47.1 or later
```

Each jar lands in `versions/<version>/build/libs/`. 1.21.11 ships obfuscated, so its build remaps the mod to the names a normal install uses. `./gradlew --continue compileJava` compiles every version; run it before merging anything that touches code shared across versions.

## Fabric, NeoForge and Forge

Versions named `<minecraft>-neoforge` build for NeoForge, `1.20.1-forge` builds for Forge, and the rest build for Fabric. NeoForge gets a jar for each Minecraft version it has a stable release for: 1.21, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11, 26.1.2, 26.2 and 26.3.

From 1.21.6 the map draws through parts of the game that are private. Fabric API's access wideners open them for the Fabric jars, and a per-version access transformer, written by `build.neoforge.gradle.kts`, opens them for the NeoForge jars.

Everything but the entrypoints is shared: `dev.diorama.fabric` hooks the mod up to Fabric API, `dev.diorama.neoforge` to NeoForge's events and `dev.diorama.forge` to Forge's. Each jar contains only its own package and metadata (`fabric.mod.json`, `META-INF/neoforge.mods.toml` or `META-INF/mods.toml`). Other differences between the loaders sit in `//? if fabric`, `//? if neoforge` and `//? if forge` blocks.

Forge 1.20.1 is where NeoForge forked from, so on Forge the NeoForge code in a block's `else` branch usually works under Forge's package names, which `stonecutter.gradle.kts` swaps in. Forge runs a normal install on its own (SRG) names: `build.forge.gradle.kts` remaps the release jar and the mixins' reference maps to them, so look game members up by name only through `ObfuscationReflectionHelper`.

## Game tests

`./gradlew :26.3:runClientGameTest` (or `:1.21.11:runClientGameTest`) launches the game, creates a world and runs the default test scenarios. Each scenario asserts what it can measure and saves screenshots to `versions/<version>/build/run/clientGameTest/screenshots/` for the rest.

Set `DIORAMA_TESTS` to a comma-separated list to run only some of them:

| Scenario | What it covers |
| --- | --- |
| `tour` | The minimap, the fullscreen map at three zooms, a resource reload and a cave slice |
| `radar` | Players and mobs on the radar |
| `xaero-icons` | Radar icons from Xaero's Minimap icon files ([docs](radar-icons.md)) |
| `model-faces` | Faces read off mob models |
| `waypoints` | Adding, editing, sharing and showing waypoints |
| `icons` | Waypoint icons: each one ships, sleeping in a bed keeps a Bed pin there, an icon is saved with its waypoint |
| `xaero-import` | Importing waypoints from Xaero's Minimap |
| `fog-of-war` | Cave slices under a server's fair-play rule |
| `settings` | The settings screen |
| `overlays` | Chunk grid, slime chunks and spawn spots |
| `screens` | The minimap while other screens are open |
| `image-export` | Saving an area and the whole map as images through the export screen |
| `dimensions` | The Nether and the End |
| `saved-map` | The map surviving a restart |
| `recreated-world` | A new world under a deleted world's name |
| `multiplayer` | Two worlds on one dedicated server |
| `server-id`, `tracked-players`, `realms` | Telling server worlds apart, players tracked by Xaero's server plugin, Realms |

Some scenarios only run when you name them: `styles`, `bench`, `gpu-bake`, `waystones` (needs the Waystones mod), `ftb-claims` (needs FTB Chunks, FTB Teams and FTB Library, plus Architectury before 26.1), `showcase` and `icon-waypoints` (the showcase castle with icon pins, for pictures; `DIORAMA_GIF=1` adds the frames of picking an icon), `waypoint-clutter` (120 waypoints in the world, on the minimap and on the map, with and without the waypoint distance limit) and `long-initials` (pins with one to four letters in the world, on both minimap shapes, on the map, in the list and in the editor, then initials typed in small letters).

Game tests bake with the CPU tracer unless a run sets `DIORAMA_GPU_BAKE`: `1` forces the GPU, past the speed limit and any saved result, and any other value, such as `auto`, leaves the choice to the game, as for a player. Run a GPU pass on a real graphics card before a release: boat.dev has none, so there `auto` falls back to the tracer within seconds (Mesa's llvmpipe takes about 0.5 s a tile).

`DIORAMA_GIF=1` also saves frames for GIFs, and `DIORAMA_DEBUG=1` adds extra logging. `DIORAMA_TESTS=showcase` isn't part of the default run: it builds a castle beside a village and takes the screenshots for the store pages and this repo's README.

`./gradlew :1.21.1-neoforge:runClientGameTest` runs the same scenarios on NeoForge, and `./gradlew :1.20.1-forge:runClientGameTest` on Forge, all but the dedicated server ones (`multiplayer`, `server-id`, `tracked-players`, `realms`). NeoForge and Forge have no client game test API, so they run on `src/gameshim`, the test-only stand-in for Fabric's that the Fabric jars before 1.21.4 use too.

`./gradlew :1.21.11:runProductionClientGameTest` runs the same tests against the remapped release jar in a normal install, with Fabric API as published. In development 1.21.11 uses Mojang's names, but a normal install uses intermediary ones, so anything that looks the game up by name only fails there.

## Translations

The game's text is in `src/main/resources/assets/diorama/lang/`. To add a language, copy `en_us.json` to your language's code (like `de_de.json`) and translate the values.
