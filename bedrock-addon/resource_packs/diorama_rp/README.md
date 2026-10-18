# diorama_rp — Bedrock resource pack

Textures, localization and UI for the Diorama Bedrock port. This pack declares a
dependency on the behavior pack `diorama_bp` (header UUID
`376794a4-2303-44bb-b8a9-86f617c551b8`); if the behavior pack uses a different
UUID, update `dependencies[0].uuid` in `manifest.json` to match.

## Layout

| Path | Purpose |
| --- | --- |
| `manifest.json` | RP header/module + BP dependency |
| `pack_icon.png` | 256×256 pack icon |
| `texts/` | `en_US.lang`, `zh_CN.lang`, `languages.json` |
| `textures/item_texture.json` | item atlas keys for custom items |
| `textures/terrain_texture.json` | terrain atlas keys for `diorama:map_table` |
| `textures/items/` | 16×16 custom item icons |
| `textures/blocks/` | 16×16 map table faces |
| `textures/entity/diorama/` | waypoint marker entity texture (not atlassed) |
| `textures/ui/` | HUD badge image |
| `entity/diorama_waypoint.entity.json` | client entity for `diorama:waypoint_marker` |
| `models/entity/diorama_waypoint.geo.json` | geometry `geometry.diorama_waypoint` |
| `ui/hud_screen.json` | additive `hud` namespace controls (bottom-right badge) |

## Behavior-pack contract

Reference these exactly from the BP or the assets will not resolve:

- Items — `minecraft:icon` must use the atlas shortname:
  - `diorama:waypoint_tool` → `textures/items/waypoint_tool`
  - `diorama:settings_tool` → `textures/items/settings_tool`
- Block — texture keys in `terrain_texture.json`:
  - `diorama:map_table` / `diorama:map_table_top`
  - `diorama:map_table_side`
  - `diorama:map_table_bottom`
- Entity — `diorama:waypoint_marker` needs the BP server entity with the same
  identifier; the RP client entity, geometry and texture above are already wired
  to `controller.render.default`.

## Localization keys

`pack.name`, `pack.description`, `item.<id>.name`, `tile.diorama:map_table.name`,
`entity.diorama:waypoint_marker.name`, the `diorama.form.*` set for
`@minecraft/server-ui` forms and the `diorama.msg.*` / `diorama.hud.*` readouts.
Both `.name` and bare keys are defined for items/blocks/entities so either lookup
style resolves.

## Notes and limits

- `ui/hud_screen.json` uses the same path as the vanilla file so JSON UI merges it
  into the `hud` namespace. It only *adds* two controls and one `root_panel`
  modification; it never redefines vanilla controls. Delete the file to remove the
  badge.
- Bedrock's stable Script API cannot upload runtime textures. This legacy Add-On
  does **not** implement the angled voxel map; it supplies static chrome and
  waypoint visuals only. Dynamic readouts (coordinates, waypoint distance) arrive
  through the actionbar/title from the BP scripts. The China ModSDK implementation
  lives under `china-modsdk/`.
- Custom languages are not registered; `languages.json` lists only `en_US` and
  `zh_CN`, both of which ship.
