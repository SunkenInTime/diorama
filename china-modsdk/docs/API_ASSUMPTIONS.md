# Unverified API assumptions

The README summarises these; this file is the per-symbol list a developer can
work through when bringing the scaffold up on a real China Edition build. For
each row: what the scaffold assumes, where it is used, how it is guarded, and
what happens if the assumption is wrong.

None of these has been executed against the game. The public documentation is
the only source, and where sources disagree (for example on whether
`RegisterSystem` takes a class or a class path) both forms are noted.

## Systems and the entry point

| Symbol | Assumed behaviour | Where | Guard | If wrong |
|---|---|---|---|---|
| `mod.common.mod.Mod` | `@Mod.Binding(name, version)`, `@Mod.InitServer/DestroyServer/InitClient/DestroyClient` decorators exist | `modMain.py` | none — this is the documented entry point | Nothing loads; the ModSDK install is wrong, not the scaffold |
| `serverApi.RegisterSystem(ns, name, path)` | Third argument is a **string** `"pkg.module.Class"` | `modMain.py` | `try/except` + log | Pass the class object instead: change `modCommon/modConfig.py` constants |
| `clientApi.RegisterSystem(ns, name, path)` | Same | `modMain.py` | `try/except` + log | Same |
| `ServerSystem.__init__(namespace, systemName)` | Base class written as `ServerSystem.__init__(self, ns, name)` (Python 2 style) | both systems | — | If your build only supports `super()`, that also works; the class is fetched at import time |
| `GetEngineNamespace()` / `GetEngineSystemName()` | Return the strings engine events are subscribed on | both systems | `try/except` per event | Missing events log one warning each and the feature degrades |

## Events

| Event / call | Assumed behaviour | Where | Guard | If wrong |
|---|---|---|---|---|
| `UiInitFinished` | Client event after the UI framework is ready | `modClient/DioramaClientSystem.py` | `try/except`; `OnLocalPlayerStopLoading` is also subscribed | HUD never created; add whichever init event your build fires |
| `OnScriptTickServer` / `OnScriptTickClient` | Can be subscribed to as engine events **and/or** fired automatically | both systems | A repeated timer is also started; a timestamp guard dedupes | If neither fires, nothing periodic happens — the timer is the safer of the two |
| `ServerChatEvent` | `args` has `message`/`Message`, `playerId`/`id`, and honours `args["cancel"]` | `DioramaServerSystem._handle_chat` | Reads both spellings; `cancel` set inside `try` | Commands would be broadcast as chat, or not seen at all |
| `AddServerPlayerEvent`, `PlayerDieEvent`, `DimensionChangeFinishServerEvent` | Names and payload as documented | both systems | `try/except` per subscription | That feature (greeting, death marker, cache reset) is inert |
| Custom client → server events | Received by listening on `(MOD_NAMESPACE, CLIENT_SYSTEM_NAME, event)` | `_listen_client` | `try/except` | All client→server traffic dead; check how your build names the source system |
| Sender id on a client→server event | Present as `playerId` or `__playerId` | components | Both keys read | The reply has no address; server-initiated actions stop |
| `NotifyToClient` / `NotifyToServer` | Carry a dict payload with the custom event name | all networking | `try/except` + log | Networking dead; the UI still works locally |
| `BroadcastToAllClient` | (Not used; the scaffold loops `NotifyToClient` instead) | — | — | — |

## Blocks and entities

| Symbol | Assumed behaviour | Where | Guard | If wrong |
|---|---|---|---|---|
| `CreateBlockInfo(levelId)` | Server: `GetBlockNew(pos, dimId)` → `{"name","aux"}` or `None` | `modServer/engine/block_source.py` | `_call`; `None` ⇒ no data | If it returns a tuple instead, add a tuple branch (client adapter already has one) |
| Same, client | `GetBlock(pos)` → `(name, aux)` | `modClient/engine/client_blocks.py` | Accepts tuple/list/dict | Picks fall back to the server |
| `GetTopBlockHeight((x,z), dimId)` | Server-side, one call per column | `top_at()` | Disabled after the first `None`; returns `None` upstream | Rays start at the global `top_y`; slower, still correct |
| `GetBlockLight` / `GetBlockSkyLight` | Exist on the block component | `block_light` / `sky_light` | Probed once, then cached as unavailable | Sky occlusion falls back to 5 short rays; block light is treated as 0 |
| `CreatePos(id).GetFootPos()` | Returns `(x, y, z)` | radar, waypoints, client player | `_call`, length-checked | That entity is skipped |
| `CreateEngineType(id).GetEngineTypeStr()` | Returns `"minecraft:zombie"` style ids | radar classification | `_call`; empty ⇒ passive | Unknown mobs show as passive rather than disappearing |
| `CreateEngineType(id).IsEntityAlive(id)` | Returns a bool | radar | `_call`; `False` ⇒ skip | Dead entities could linger one scan |
| `CreateGame(levelId).GetEntitiesInSquareArea(a, start, end)` | Returns ids, or a dict of them | `entity_query.py` | Tries with and without the leading entity id, then disables | Radar inert with one log line |
| `CreateGame(levelId).GetSeed()` | Returns the world seed | `world_key` | Probes `GetSeed`, `GetLevelSeed`, `GetWorldSeed` | World key is `"local"`: one waypoint list for all local worlds |
| `CreateDimension(id).GetEntityDimensionId()` | Returns an int dimension id | `dimension_id()` | `_call`, falls back to 0 | Per-dimension waypoints/radar may mix |

## UI

| Symbol | Assumed behaviour | Where | Guard | If wrong |
|---|---|---|---|---|
| `GetScreenNodeCls()` | Returns the `ScreenNode` base class | `ui/ui_base.py` | Tries `GetScreenNodeCls`, `GetUIScreenCls`, then `object` | Imports still succeed; screens cannot be created |
| `GetMiniMapBaseScreenCls()` | Returns the experimental minimap base | `ui/ui_base.py` | Tries three names, falls back to `ScreenNode` | No engine-drawn minimap; static atlas only |
| `RegisterUI(ns, key, cls, def)` | `def` is `"namespace.file"` | `DioramaClientSystem._register_ui` | `try/except` per screen | Try `"file"` alone |
| `PushScreen(ns, key)` / `PopScreen()` | Show/hide a full-screen UI | `_push`, `close_top` | `try/except`, falls back to `CreateUI({"isHud": 0})` | Screens may need `CreateUI` only |
| `CreateUI(ns, key, {"isHud": 1})` | HUD mode does not block input | `_create_hud` | `try/except` | HUD blocks clicks; try `{"isHud": 0}` plus `HideHudGUI` |
| Base-screen path | `/variables_button_mappings_and_controls/safezone_screen_matrix/inner_matrix/safezone_screen_panel/root_screen_panel` | `modConfig.BASE_SCREEN_PATH` | One constant | Every control lookup fails; change the constant |
| `$screen_content` nesting | Content controls sit **under** the named root, so paths are `BASE_SCREEN_PATH + "/<root>" + path` | `ui_base.resolve`, each screen's `CONTENT_ROOT` | One constant per screen | Control API calls silently do nothing |
| `ScreenNode.GetBaseUIControl(path)` → `.asLabel()/asImage()/asButton()` | Standard control accessors | all screens | `_as` returns `None` | One widget stops updating |
| `control.SetText/SetVisible/SetSprite/SetPosition/SetSize/SetAlpha` | Standard control methods | all screens | `_invoke` tries aliases and returns False | That update is skipped |
| `ScreenNode.Clone(path, parent, name)` / `RemoveComponent(path, parent)` | Clone/remove a control subtree | markers, waypoint rows | `try/except` | Markers/rows do not appear; the rest of the screen works |
| `clientApi.GetScreenSize()` | Returns `(width, height)` | `ui_base.screen_size` | `try/except` → 1280×720 | Layout maths is off |
| `CreateConfig(localPlayerId).Get/SetConfigData` | Per-player persistent string storage | settings, waypoints | `try/except`; in-memory backend fallback | Nothing persists between sessions |
| `CreateMsg(id).NotifyOneMessage(playerId, line)` | Server-side chat line | `broadcast_chat` | Probes four creator/method names | Falls back to an action-bar notice |

## Packaging and resources

| Assumption | Where | Guard | If wrong |
|---|---|---|---|
| Behaviour pack needs an `entities/` directory even with no entities | `entities/README.md` | `tools/validate.py` errors if absent | The pack may not load; keep the directory |
| Python scripts live in `behavior_pack/<scriptPackage>/` with `modMain.py` at that root | whole scaffold | Fixed by the layout | Move `dioramaScripts/` and update `modCommon/modConfig.py` paths |
| UI definition manifest is `ui/_ui_def.json` | resource pack | Validator accepts `_ui_defs.json` too and warns | Rename/copy the file |
| Several UI files may share one namespace | `ui/*.json` | Validator checks cross-namespace references | Rename namespace keys and `RegisterUI` definitions together |
| `texts/*.lang` are JSON objects | resource pack | Validator enforces JSON + matching keys | Convert to `key=value` if your build is old |
| Python runtime is 2.7 | all scripts | Every module is 2.7/3-compatible | Py3-only builds work unchanged apart from `unicode`/`long` shims |

## Deliberately **not** assumed

* **Runtime texture upload.** No API for pushing script-generated pixels into the
  UI texture atlas is assumed to exist. The full-screen map therefore uses an
  atlas baked offline (`tools/bake_atlas.py`); runtime tiles stay a cached,
  exportable payload.
* **Client-side entity queries.** The radar is server-authoritative; the client
  never assumes it can enumerate entities.
* **Block edit invalidation.** There is no hook wired for "this chunk changed, re-bake";
  tiles are baked on request only.
* **A server-side filesystem.** Waypoints persist on the client and reach the
  server only through the share relay.
