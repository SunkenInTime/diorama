<img src="assets/icon.png" width="64" height="64" alt="">

# Diorama

Diorama is a client-side Fabric mod for Minecraft 26.3 and 1.21.11 that draws your world as an angled voxel map, like a baked diorama, in a minimap and a fullscreen map you can pan and zoom.

The map is ray traced from the chunk data your client already has. The camera looks north and down at 50°, so you see block tops and their south faces. Each pixel's ray walks the real blocks, so overhangs, bridges and tree canopies come out right. A second ray toward a low sun casts long shadows, more rays measure how much sky each spot can see so the ground under trees and overhangs darkens, and higher ground is a little brighter, so hills still read as hills when you zoom far out. Water gets darker with depth. A "Depth shading" setting turns all of this down to a flatter look. Tiles bake on background threads and only re-bake the parts an edit can affect.

Places the map has no data for sit under animated fog. Underground, the map switches to a cave slice that shows the first open space below a cut a few blocks above your head, lit by the game's own block light. If a server sends Xaero's fair-play chat code (`§f§a§i§r§x§a§e§r§o`), cave slices only show blocks you've had line of sight to.

## What it does

- **Minimap** in a corner of your choice, with a compass, coordinates and biome. `=` and `-` zoom it.
- **Fullscreen map** you can pan and zoom, from close-up blocks out to about 10,000 blocks across.
- **Radar** for other players and mobs, each shown as its own face, framed red, yellow or green for hostile, neutral or passive, with markers for whether they're above or below you.
- **Waypoints** on the map and as labels in the world, plus a marker wherever you die. Share one in chat from its edit screen; shared waypoints, including Xaero's, get an [Add] link in chat. Teleport to one if you're allowed to use `/tp`.
- **Saved map.** Everything your client loads is saved per world and dimension, so the map is still there next time, and you can zoom out over everywhere you've been.
- **The Nether** from above: the sky view starts under the bedrock roof and shows everything below it, lit by block light.
- **Overlays** on the fullscreen map: chunk grid, slime chunks (singleplayer) and the spots where monsters can spawn.
- **Settings** for all of the above, from a button on the fullscreen map.

Saved maps and waypoints live in `.minecraft/diorama/`, one folder per world. On a server, each world behind its address gets its own folder, told apart by the world seed the server sends, so a network's lobby and survival worlds, or a map reset, don't mix.

## Keys

- `M` opens the fullscreen map. Drag to pan, scroll to zoom, right-click to add a waypoint, click a waypoint to edit it.
- In the fullscreen map, `C` toggles the cave slice, `PgUp`/`PgDn` move the slice by 4 blocks, `U` lists waypoints, and `Space` recenters on you.
- `B` adds a waypoint where you stand, and `U` opens the waypoint list.
- `=` and `-` zoom the minimap. Map settings and hiding the minimap have keys too, unbound by default.

## Community ports

- [Diorama NeoForge](https://github.com/gilvex/diorama-neoforge) — an unofficial Minecraft 1.21.1 / NeoForge port. Downloads, screenshots, test notes and support are available in its separate repository.

## Building

Needs JDK 25. Gradle downloads everything else.

One source tree builds a jar for each supported Minecraft version, using [Stonecutter](https://codeberg.org/stonecutter/stonecutter). The source is written for the newest version, 26.3. Names that only changed between versions are translated by the replacements in `stonecutter.gradle.kts`. Code that differs in other ways sits in `//? if >=26.1 { … //?} else { /* … *///?}` blocks, with the older version commented out. Keep the active version at 26.3: the replacements only translate in one direction.

```sh
./gradlew :26.3:build
./gradlew :1.21.11:build
```

Each jar lands in `versions/<version>/build/libs/`. 1.21.11 ships obfuscated, so its build remaps the mod to the names a normal install uses.

`./gradlew :26.3:runClientGameTest` (or `:1.21.11:runClientGameTest`) launches the game, creates a world and runs every test scenario: the map views, radar, waypoints, fog of war, settings, overlays, the Nether and the End, saving the map across a restart, and two worlds on one dedicated server. Each scenario asserts what it can measure and saves screenshots to `versions/<version>/build/run/clientGameTest/screenshots/` for the rest. Set `DIORAMA_TESTS` to a comma-separated list (`tour`, `radar`, `waypoints`, `fog-of-war`, `settings`, `overlays`, `dimensions`, `saved-map`, `multiplayer`) to run only some, `DIORAMA_GIF=1` to also save frames for GIFs, and `DIORAMA_DEBUG=1` for extra logging. `DIORAMA_TESTS=showcase` isn't part of the default run: it builds a castle beside a village and takes the screenshots for the store page.

`./gradlew :1.21.11:runProductionClientGameTest` runs the same tests against the remapped release jar in a normal install, with Fabric API as published. In development 1.21.11 uses Mojang's names, but a normal install uses intermediary ones, so anything that looks the game up by name only fails there.
