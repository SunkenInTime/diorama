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
| `radar-sneaking` | Sneaking players off the radar unless on the player's team, as on Xaero's Minimap (`radar-sneaking-server` does it with real players on a real server, Fabric only: `--env DIORAMA_REAL_SERVER=1 --type large`) |
| `radar-categories` | The radar categories screens, and a mob following the player's own category (`radar-categories-cobblemon` with `--mods cobblemon`) |
| `xaero-icons` | Radar icons from Xaero's Minimap icon files ([docs](radar-icons.md)) |
| `model-faces` | Faces read off mob models |
| `waypoints` | Adding, editing, sharing and showing waypoints |
| `icons` | Waypoint icons: each one ships, sleeping in a bed keeps a Bed pin there, an icon is saved with its waypoint |
| `portal-pins` | Pins on lit portals: one on the widest Nether portal, the setting, deleting one, breaking a portal, portals in the save, nothing under fog of war |
| `portal-unlit` | A stronghold's End portal before it's lit: no pin from overhead, one on reaching the room, the same one once lit, none on frames that don't face in |
| `xaero-import` | Importing waypoints from Xaero's Minimap |
| `fog-of-war` | Cave slices under a server's fair-play rule |
| `settings` | The settings screen |
| `overlays` | Chunk grid, slime chunks and spawn spots |
| `screens` | The minimap while other screens are open |
| `image-export` | Saving an area and the whole map as images through the export screen |
| `dimensions` | The Nether and the End |
| `saved-map` | The map surviving a restart |
| `redraw` | A relabelled map remembers its old tiles and is redrawn from the save |
| `facing` | The map turned every way: walls, right-click, waypoints, drawings, export, the Nether, reopening |
| `recreated-world` | A new world under a deleted world's name |
| `multiplayer` | Two worlds on one dedicated server |
| `server-id`, `tracked-players`, `realms` | Telling server worlds apart, players tracked by Xaero's server plugin, Realms |

Some scenarios only run when you name them: `styles`, `bench`, `minimap-alloc` (bytes the minimap allocates each frame on the render thread under a dozen settings, logged with a screenshot of each; Fabric and NeoForge), `gpu-bake`, `waystones` (needs the Waystones mod), `ftb-claims` (needs FTB Chunks, FTB Teams and FTB Library, plus Architectury before 26.1), `showcase` and `icon-waypoints` (the showcase castle with icon pins, for pictures; `DIORAMA_GIF=1` adds the frames of picking an icon), `waypoint-clutter` (120 waypoints in the world, on the minimap and on the map, with and without the waypoint distance limit) `minimap-turn` (the minimap with Lock North off through a full turn by the showcase castle, both shapes, three zooms; `DIORAMA_GIF=1` takes a frame every 5 degrees), `long-initials` (pins with one to four letters in the world, on both minimap shapes, on the map, in the list and in the editor, then initials typed in small letters), `waypoint-export` (Export waypoints in the list: the files read back by the Xaero's import parser field by field, then imported again through the list's button; a map ID leading out of the export folder, or a world folder name too long for a file, fails the export and leaves no folder), `waypoint-export-xaero` (with Xaero's Minimap added and three maps in the Overworld: on the next visit Xaero's own code has read the map in use's file into one world and nothing of the other maps, which are folders of their own in the export, and U opens its waypoint list), and `xaero-real` and `xaero-real-server` (need Xaero's Minimap for that version, added with `-Dfabric.addMods=<folder>`: Xaero's makes waypoints, U and B open its screens, and the chat line on the next visit leads to the import, in singleplayer and on a server from 1.21.4).

Game tests bake with the CPU tracer, which is `DIORAMA_GPU_BAKE=0`. A run can set it to `1`, which forces the GPU past the speed limit and any saved result, or to any other value, such as `auto`, which leaves the choice to the game, as for a player. Run a GPU pass on a real graphics card before a release: boat.dev has none, so there `auto` falls back to the tracer within seconds (Mesa's llvmpipe takes about 0.5 s a tile).

`DIORAMA_GIF=1` also saves frames for GIFs, and `DIORAMA_DEBUG=1` adds extra logging. `DIORAMA_TESTS=showcase` isn't part of the default run: it builds a castle beside a village and takes the screenshots for the store pages and this repo's README.

`./gradlew :1.21.1-neoforge:runClientGameTest` runs the same scenarios on NeoForge, and `./gradlew :1.20.1-forge:runClientGameTest` on Forge, all but the dedicated server ones (`multiplayer`, `server-id`, `tracked-players`, `realms`). NeoForge and Forge have no client game test API, so they run on `src/gameshim`, the test-only stand-in for Fabric's that the Fabric jars before 1.21.4 use too.

`./gradlew :1.21.11:runProductionClientGameTest` runs the same tests against the remapped release jar in a normal install, with Fabric API as published. In development 1.21.11 uses Mojang's names, but a normal install uses intermediary ones, so anything that looks the game up by name only fails there.

`addon-api` tests the addon API (below) through the test addons in `testaddon/`, which only run when installed: build them with `./gradlew :testaddon:assemble` and pass their folder to the boat runner, `boat_run.py <nodes> --tests addon-api --mods testaddon/build/libs`. Install them only for the addon sections: the main test addon hides the minimap. On Fabric up to 1.21.11 that runs the remapped release jar, as above. It checks that the addon is found; that a handle it takes at the title screen keeps the minimap hidden in the first world, through a change of dimension, after leaving and joining the world again, and until the addon closes it; that hiding the minimap and the waypoints in the world takes them off the screen and back without changing the settings or `config.json`; that the minimap still shows while it's being placed; that six addons which fail (one throws, one is missing a class, one fails an assertion, one throws an exception whose `toString()` throws, one wraps a `StackOverflowError` in its own exception, one throws an exception whose `getMessage()` recurses) are switched off with one log line each and their handles closed; and that a class marked `@DioramaEntrypoint` by mistake is turned down without running. For waypoints, with addons the test makes itself as well (`Addons.foundForTests`), it checks that the addon is told when the world opens, changes dimension, switches saved map and closes, with the same world ID after leaving and joining again; that it reads what the waypoint list shows; that a waypoint it adds is in the list, on the map, in the world and in `waypoints.json` after one save, and still there after rejoining; that nothing is told from inside `edit()`, only at the next client tick; that the player's add, rename, hide and delete in Diorama's own screens, clicked and typed, reach it with no mod ID and the saves they always had; that renaming a folder is a change to each waypoint in it and hiding one is none; that its own edits reach it with its mod ID, one save and one event a batch, echoes after, with what it read at the time; that three new folders in one batch write `folders.json` once; that changing a temporary waypoint is turned down; that a batch that throws an exception or `ThreadDeath` changes nothing, folders included, and switches its addon off; that a listener which throws is switched off while the store carries on; that a callback making 55 edits, each answered once, goes through, and one that answers its own changes for ever is stopped past 50 answers deep; that a Waystones change not yet told reaches the listeners there before another subscribes or the map switches, that a new listener hears nothing from before it subscribed, even mid-delivery, and that an edit made in one addon's `mapChanged` comes after every addon's `mapChanged`; that Diorama's list drops a row an addon deleted while it's open, and the editor's Save then saves nothing; that two waypoints with one ID behave as the Javadoc says; that a waystone placed again shows in `all()`; that `all()` is one list until something changes; that `all()`, `get()` and `edit()` throw from another thread; and that a world handle whose store was replaced, or whose world isn't known for a moment (`WorldKey.unknownForTests`), throws and writes nothing, the new store's file keeping its pin. It logs what `all()`, `get()` and an edit batch with its tick cost with 1,000 waypoints, and on Fabric from 1.21.6 how many bytes the minimap's and the world waypoints' drawing allocate a frame, with and without a listener.

`addons-settings` needs the same jars and tests the settings' Addons tab: every test addon listed by its metadata's name, the failed ones as stopped (the two waypoint test addons are made to fail first if `addon-api` didn't run before); the Minimap tab naming the addon that hides it; unticking the addon with a real click bringing the minimap back while its handle stays open, and saving its mod ID in `config.json`; ticking it hiding the minimap again; the rows following a handle taken while the screen is open, and an addon found while it is open getting a row; and two pages on a small window. After every other section of the run, it switches the test addon off. `addons-restart` then checks that the game starts with that choice: run it straight after, from the same checkout so it gets the same warm VM, with `--env DIORAMA_KEEP_CONFIG=1`, which keeps the last run's `diorama/config.json` for the next game (`sandbox.sh`; on Fabric, `KeptConfig` puts it back after Fabric API's game tests empty the game folder).

## Addon API

Other mods reach Diorama through `dev.diorama.api`, in `api/`: a few interfaces with no Minecraft or mod loader types, compiled once for Java 17 with only the JDK on the classpath. `./gradlew :api:build` makes `api/build/libs/diorama-api-<version>.jar`, with sources and Javadoc jars, which an addon compiles against and doesn't bundle. Every version's jar carries the same classes, copied in as they are; the API has its own version (`api_version` in `gradle.properties`), separate from the mod's. It is alpha: not yet a compatibility promise.

An addon implements `DioramaAddon` and is found by a `diorama` entrypoint in `fabric.mod.json` on Fabric, or by `@DioramaEntrypoint` on the class on NeoForge and Forge, read from the mod loader's scan of its jar the way JEI finds its plugins. Diorama makes it once the game has loaded and hands it a `DioramaApi` tied to its mod ID. What an addon hides stays hidden until it closes the handle, fails, or the game exits; worlds and dimensions don't end it, and nothing is saved, so the game always starts with nothing hidden. `onWorld` tells an addon when a world opens and closes and when its dimension or saved map changes, judged from Diorama's own state, never from the loaders' join and leave events: a world is open while the client has a level, the loaded `WaypointStore` is the one Diorama uses, and the world's key is known and is that store's. Every call on a world handle checks this first, so a handle can't reach a store it wasn't opened for (`AddonWorlds`). Through it an addon reads the waypoints, edits the player-made ones in batches, and hears every change: every change to the waypoints, Diorama's own screens and automatic pins included, goes through `WaypointStore.committed` after its save, which moves the store's revision on (the screens follow it) and, while an addon listens, queues the change. World events go in the same queue, and Diorama's client tick tells it all in order, never from inside an edit or another call; an edit made while being told is one answer deeper, and an addon editing past `Waypoints.MAX_EDIT_DEPTH` (50) is switched off. With no addon listening, nothing is compared or built. The player can untick an addon in the settings' Addons tab: its handles stay open and hide nothing until it's ticked again. That choice is the player's setting, saved by mod ID in `config.json` (`addonHidesOff`); the counts the HUD reads are changed when it changes, never worked out per frame. Whatever an addon throws switches that addon off for the session, closes its handles, and logs one line; the JVM's own fatal errors (`VirtualMachineError`, `ThreadDeath`), thrown as they are or inside reflection's or the loader's wrappers, go on up after that (`dev.diorama.addon`). `testaddon/` is an example.

`./gradlew :<version>:checkApiCopy`, part of `build`, checks that version's release jar carries the API byte for byte as `api/` built it, and that no API class names a Minecraft, mod loader or Diorama class. Remapping (Fabric up to 1.21.11, Forge) rewrites the API classes too, so the build puts javac's bytes back afterwards.

## Translations

The game's text is in `src/main/resources/assets/diorama/lang/`. To add a language, copy `en_us.json` to your language's code (like `de_de.json`) and translate the values.
