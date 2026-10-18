# Architecture

Three layers, and one rule that keeps them honest:

1. **`modCore`** — the map. Pure Python, standard library only, **no `mod.*`
   import anywhere**. `tools/validate.py` fails the build if one appears. This is
   what makes the whole map testable on a PC: 277+ unit tests and the preview
   baker run against it with no game present.
2. **`modServer` / `modClient`** — the ModSDK surfaces. Systems own event
   subscriptions and the tick loop; components own one feature each; `engine/`
   sub-packages hold every call that touches the ModSDK, so the components read
   as plain logic.
3. **`resource_pack/ui` + `modClient/ui`** — JSON defines layout and default
   looks, Python changes state (text, sprite, visibility, markers). The one
   exception is `ui_base.resolve()`, which knows about the base-screen path
   shift so no other file has to.

```
        client tick / timer                     server tick / timer
                 │                                       │
        HudClientComponent.tick()               RadarServerComponent.tick()
                 │                                       │
   ┌─────────────┴───────────┐            ┌──────────────┴─────────────┐
   │ SettingsClientComponent │            │ EntityQuery (engine/)      │
   │ WaypointClientComponent │            │  → modCore.radar.collect   │
   │ RadarClientComponent    │            │  → NotifyToClient(EV_RADAR)│
   │ MapClientComponent      │            └────────────────────────────┘
   └─────────────┬───────────┘
                 │  NotifyToServer(EV_PICK_REQUEST / EV_TILE_REQUEST / …)
                 ▼
        MapServerComponent  ── modCore.pick ── EngineBlockSource (engine/)
                            └─ modCore.baker (off by default)
```

## Data flow

**Radar.** The server is the only side with a documented, complete entity query,
so classification (`modCore.radar` tables) and projection (`modCore.projection`)
happen there, once per `RADAR_INTERVAL` per player. What crosses the wire is
`Blip.to_dict()` — id, kind, name, `u`, `v`, `dy`, distance. The client cannot
disagree with the server about what is hostile, because there is one table.

**Waypoints.** The client owns the store (it is that player's data), persisted
through `CreateConfig`. The server relays what a client cannot: a shared waypoint
becomes a chat line plus an `EV_WAYPOINTS` update, and a player joining gets the
shared list in flight. `WaypointStore.parse_share_text` reads the chat line back,
which is also how an Xaero-style shared waypoint would be imported.

**Picks.** A tap on the map sends `EV_PICK_REQUEST(u, v, layer)`. The server
walks the same camera ray the baker would (`modCore.pick.standing_spot`) and
answers with the standing position. This is what makes a waypoint land on the
ground rather than floating at some guessed height.

**Tiles.** `EV_TILE_REQUEST` exists and the client cache (`TileCache`) can store
and export tiles as PNG, but the server **refuses by default**: a tile is a ray
march per pixel in pure Python on the game thread. The shipped full-screen map
background is an atlas baked offline by `tools/bake_atlas.py`, because the
ModSDK cannot upload a texture a script produced at run time.

## Why the baker is shaped this way

* Every probe goes through one narrow interface, `modCore.block_source.BlockSource`
  (`state`, `block_light`, `sky_light`, `biome_tint`, `is_seen`, `top_at`). Three
  implementations exist: the synthetic workbench world, an in-memory fixture, and
  the engine adapter. The baker never learns which one it is talking to.
* `Geometry` replaces the hard-coded 128 units × 4 pixels. Game code uses the
  real numbers; the workbench bakes a 32-unit tile so a preview finishes in
  seconds.
* ``None`` from ``state`` means *no data* (chunk not loaded) and is deliberately
  never confused with air. It produces transparent pixels and coverage 0, which
  is where the fog edge comes from, and the saved-pixel merge prefers what was
  saved over what a partially loaded world can see today.
* Lighting is applied in a fixed order (albedo → variation → AO → sky occlusion
  → sun/shadow → water → grade → outline) and each stage has a style knob that
  can turn it off, so "depth shading off" is a re-bake with different numbers,
  not a second code path.

## Tick budget

The ModSDK runs scripts on the game thread. Everything periodic is throttled:
the HUD readout and the server tick to `HUD_INTERVAL` (0.25 s) via both
`OnScriptTick*` and a repeated timer (whichever the build provides, the work
happens once — a timestamp guard), the radar to `RADAR_INTERVAL` (0.5 s), the
map to `TILE_INTERVAL` (2 s). The bake queue drains at most one tile per tick and
only when `BAKE_BUDGET_SECONDS` has elapsed.

## Failure policy

Every engine callback registered by a system is wrapped in `modCommon.log.safe`,
and every engine call inside `engine/` goes through a `_call` helper. The
consequence is deliberate: a missing component, an unloaded chunk, a dead entity
or a renamed API costs one log line and one widget, never a crash inside a tick —
which matters most on the China Edition, where scripts cannot be debugged with a
breakpoint.
