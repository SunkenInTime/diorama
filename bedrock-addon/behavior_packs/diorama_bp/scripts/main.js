import { world, system } from "@minecraft/server";
import { ActionFormData, ModalFormData } from "@minecraft/server-ui";
import { bearingDegrees, bearingLabel, bearingArrow, distance3, safe, subscribeSafe, log, warn } from "./util.js";

const WAYPOINT_ITEM = "diorama:waypoint_tool";
const SETTINGS_ITEM = "diorama:settings_tool";
const MARKER = "diorama:waypoint_marker";
const MARKER_TAG = "diorama_marker";
const SETTINGS_KEY = "diorama:settings";
const WAYPOINT_KEY = "diorama:waypoints";
const state = new Map();

function playerKey(player) { return player.name; }
function readJson(key, fallback) {
  const raw = safe(() => world.getDynamicProperty(key));
  if (typeof raw !== "string" || raw.length === 0) return fallback;
  try { return JSON.parse(raw) ?? fallback; } catch (_) { return fallback; }
}
function writeJson(key, value) { safe(() => world.setDynamicProperty(key, JSON.stringify(value))); }
function settings() { return readJson(SETTINGS_KEY, { hud: true, range: 64, pitch: 50 }); }
function waypoints() { return readJson(WAYPOINT_KEY, []); }
function dimensionId(dimension) { return dimension.id ?? "minecraft:overworld"; }
function posOf(e) { return { x: Math.floor(e.location.x), y: Math.floor(e.location.y), z: Math.floor(e.location.z) }; }
function displayName(wp) { return `${wp.name} (${wp.x}, ${wp.y}, ${wp.z})`; }

function spawnMarker(player, wp) {
  return safe(() => {
    const e = player.dimension.spawnEntity(MARKER, { x: wp.x + 0.5, y: wp.y, z: wp.z + 0.5 });
    e.nameTag = `§e${wp.name}\n§7${wp.x} ${wp.y} ${wp.z}`;
    e.addTag(MARKER_TAG);
    e.addTag(`diorama_id:${wp.id}`);
    return e;
  });
}
function createWaypoint(player, name = "Waypoint") {
  const p = posOf(player);
  const list = waypoints();
  const wp = { id: `${Date.now()}-${Math.random().toString(16).slice(2)}`, name: name || "Waypoint", x: p.x, y: p.y, z: p.z, dimension: dimensionId(player.dimension), created: Date.now() };
  list.push(wp); writeJson(WAYPOINT_KEY, list); spawnMarker(player, wp);
  player.sendMessage(`§a[Diorama] 已创建路径点 §f${wp.name} §7(${wp.x}, ${wp.y}, ${wp.z})`);
  return wp;
}
function removeMarkers(player, id) {
  safe(() => { for (const e of player.dimension.getEntities({ type: MARKER, tags: [`diorama_id:${id}`] })) e.remove(); });
}

async function waypointForm(player) {
  const list = waypoints().filter(w => w.dimension === dimensionId(player.dimension));
  const form = new ActionFormData().title("Diorama · 路径点").body("选择路径点进行导航，或在当前位置创建新的路径点。");
  form.button("＋ 在当前位置创建");
  for (const wp of list) form.button(displayName(wp));
  const result = await form.show(player);
  if (result.canceled) return;
  if (result.selection === 0) {
    const input = new ModalFormData().title("新建路径点").textField("名称", "例如：家、矿洞入口", "Waypoint");
    const r = await input.show(player); if (!r.canceled) createWaypoint(player, String(r.formValues?.[0] ?? "Waypoint"));
    return;
  }
  const wp = list[result.selection - 1];
  if (!wp) return;
  const bearing = bearingDegrees(posOf(player), wp);
  player.sendMessage(`§e[Diorama] ${wp.name} §7距离 ${Math.round(distance3(posOf(player), wp))} 格，方向 ${bearingArrow(bearing)} ${bearingLabel(bearing)} (${Math.round(bearing)}°)`);
}
async function settingsForm(player) {
  const s = settings();
  const form = new ModalFormData().title("Diorama · 设置").toggle("显示坐标与指南针 HUD", s.hud !== false).slider("提示范围", 16, 256, 16, s.range ?? 64).slider("地图俯角（算法参数）", 25, 70, 1, s.pitch ?? 50);
  const r = await form.show(player); if (r.canceled) return;
  const v = r.formValues ?? []; writeJson(SETTINGS_KEY, { hud: !!v[0], range: Number(v[1]) || 64, pitch: Number(v[2]) || 50 });
  player.sendMessage("§a[Diorama] 设置已保存");
}

function onItemUse(ev) {
  const player = ev.source; const id = ev.itemStack?.typeId;
  if (!player || !id) return;
  if (id === WAYPOINT_ITEM) { void waypointForm(player).catch(e => warn("waypoint form", e)); }
  else if (id === SETTINGS_ITEM) { void settingsForm(player).catch(e => warn("settings form", e)); }
}
function onEntityDie(ev) {
  const dead = ev.deadEntity;
  if (!dead || dead.typeId !== "minecraft:player") return;
  const p = posOf(dead); const wp = { id: `death-${Date.now()}`, name: "Death", x: p.x, y: p.y, z: p.z, dimension: dimensionId(dead.dimension), created: Date.now(), death: true };
  const list = waypoints().filter(w => !w.death || w.dimension !== wp.dimension || distance3(w, wp) > 1); list.push(wp);
  const deaths = list.filter(w => w.death).sort((a, b) => a.created - b.created); while (deaths.length > 3) list.splice(list.indexOf(deaths.shift()), 1);
  writeJson(WAYPOINT_KEY, list); spawnMarker(dead, wp); dead.sendMessage?.(`§c[Diorama] 你在 ${p.x}, ${p.y}, ${p.z} 死亡，已记录 Death 路径点。`);
}
function hudTick() {
  const s = settings(); if (s.hud === false) return;
  for (const player of world.getAllPlayers()) {
    const p = posOf(player); const yaw = Number(player.getRotation?.().y ?? 0); const bearing = (yaw + 180 + 360) % 360;
    player.onScreenDisplay.setActionBar(`§6◆ Diorama §7│ ${p.x}, ${p.y}, ${p.z} §7│ ${bearingArrow(bearing)} ${bearingLabel(bearing)}`);
  }
}
subscribeSafe(world.afterEvents.itemUse, onItemUse, "itemUse");
subscribeSafe(world.afterEvents.entityDie, onEntityDie, "entityDie");
subscribeSafe(world.afterEvents.playerSpawn, ev => { if (ev.initialSpawn) ev.player.sendMessage("§6[Diorama] §f已启用。使用路径点工具打开路径点，设置工具调整 HUD。"); }, "playerSpawn");
system.runInterval(hudTick, 10);
log("Diorama Bedrock scripts loaded");
