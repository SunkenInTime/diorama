#!/usr/bin/env node
/**
 * Diorama Bedrock 附加包校验器 / Diorama Bedrock add-on validator.
 *
 * 零依赖，Node.js >= 18。自动发现本目录下的行为包与资源包：
 *   bedrock-addon/behavior_packs/<pack>/manifest.json
 *   bedrock-addon/resource_packs/<pack>/manifest.json
 *
 * 校验内容:
 *   - manifest.json 结构、必填字段、模块白名单、脚本 entry 可达
 *   - UUID 格式 / RFC 4122 变体 与全局唯一性
 *   - 版本号三元组、dependencies 引用
 *   - 所有 *.json 可解析（含 entity / models / ui / textures）
 *   - texts/*.lang 为 key=value 格式，且多语言键集合一致（键对齐）
 *   - textures 引用路径可解析为真实 .png/.tga，PNG 文件头与 IHDR 尺寸有效
 *   - client_entity 引用的 geometry 标识符在 models 目录的 .geo.json 中存在
 *
 * 用法:
 *   node validate.mjs                 # 校验，warning 不致命
 *   node validate.mjs --strict        # warning 也算失败（发布前用）
 *   node validate.mjs --root <dir>    # 指定 bedrock-addon 根目录
 *   node validate.mjs --json          # 机器可读输出
 *
 * 退出码: 0 = 通过, 1 = 有 error（或 strict 下有 warning）, 2 = 用法/环境错误。
 */

import { readFileSync, readdirSync, existsSync, openSync, readSync, closeSync, statSync } from "node:fs";
import { join, dirname, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MODULE_TYPES = new Set([
  "data",
  "script",
  "javascript",
  "resources",
  "client_data",
  "skin_pack",
  "world_template",
]);
const SCRIPT_TYPES = new Set(["script", "javascript"]);
const IMAGE_EXT = [".png", ".tga", ".jpg", ".jpeg"];

const problems = [];
const add = (level, pack, msg) => problems.push({ level, pack, msg });
const err = (pack, msg) => add("error", pack, msg);
const warn = (pack, msg) => add("warning", pack, msg);

function parseArgs(argv) {
  const opts = { root: HERE, strict: false, json: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--strict") opts.strict = true;
    else if (a === "--json") opts.json = true;
    else if (a === "--root") opts.root = resolve(argv[++i] ?? "");
    else if (a === "-h" || a === "--help") {
      console.log("用法: node validate.mjs [--strict] [--json] [--root <dir>]");
      process.exit(0);
    } else {
      console.error(`未知参数: ${a}`);
      process.exit(2);
    }
  }
  if (!existsSync(opts.root)) {
    console.error(`根目录不存在: ${opts.root}`);
    process.exit(2);
  }
  return opts;
}

const readJson = (file) => JSON.parse(readFileSync(file, "utf8").replace(/^\uFEFF/, ""));
const isVersion = (v) =>
  Array.isArray(v) && v.length === 3 && v.every((n) => Number.isInteger(n) && n >= 0);

function checkVersion(pack, field, v) {
  if (v === undefined) return;
  if (!isVersion(v)) err(pack, `${field} 必须是 [major, minor, patch] 三个非负整数，收到 ${JSON.stringify(v)}`);
}

function checkUuid(pack, field, uuid, { rfc = false } = {}) {
  if (uuid === undefined) return;
  if (!UUID_RE.test(String(uuid))) {
    err(pack, `${field} 不是合法 UUID: ${JSON.stringify(uuid)}`);
    return;
  }
  if (!rfc) return;
  const s = String(uuid).toLowerCase().replace(/-/g, "");
  if (s[12] !== "4" || !"89ab".includes(s[16])) {
    warn(pack, `${field} 不是 RFC 4122 v4 变体（版本位=${s[12]}, 变体位=${s[16]}）: ${uuid}`);
  }
}

/** 递归列出目录下所有文件（相对 packDir 的 posix 路径） */
function walkFiles(dir, base = dir, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === "node_modules" || entry.name.startsWith(".")) continue;
    const p = join(dir, entry.name);
    if (entry.isDirectory()) walkFiles(p, base, out);
    else if (entry.isFile()) out.push(relative(base, p).split("\\").join("/"));
  }
  return out;
}

/** 校验 PNG 文件头与 IHDR 尺寸 */
function checkPng(pack, packDir, rel) {
  const file = join(packDir, rel);
  let fd;
  try {
    fd = openSync(file, "r");
    const head = Buffer.alloc(33);
    const read = readSync(fd, head, 0, 33, 0);
    if (read < 33) {
      err(pack, `${rel} 不是有效 PNG（文件过短，${read} 字节）`);
      return;
    }
    const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    if (!head.subarray(0, 8).equals(sig)) {
      err(pack, `${rel} 缺少 PNG 文件头`);
      return;
    }
    if (head.subarray(12, 16).toString("ascii") !== "IHDR") {
      err(pack, `${rel} 首个数据块不是 IHDR`);
      return;
    }
    const w = head.readUInt32BE(16);
    const h = head.readUInt32BE(20);
    if (w === 0 || h === 0) err(pack, `${rel} IHDR 尺寸非法: ${w}x${h}`);
    else if (w > 4096 || h > 4096) warn(pack, `${rel} 尺寸偏大: ${w}x${h}`);
  } catch (e) {
    err(pack, `${rel} 无法读取: ${e.message}`);
  } finally {
    if (fd !== undefined) closeSync(fd);
  }
}

/** manifest.json 存在性与基础结构 */
function validateManifest(pack, packDir, kind) {
  const manifestPath = join(packDir, "manifest.json");
  if (!existsSync(manifestPath)) {
    err(pack, `缺少 manifest.json（${relative(HERE, packDir)}）`);
    return null;
  }
  let m;
  try {
    m = readJson(manifestPath);
  } catch (e) {
    err(pack, `manifest.json 不是合法 JSON: ${e.message}`);
    return null;
  }

  if (![1, 2, 3].includes(m.format_version)) {
    warn(pack, `format_version=${JSON.stringify(m.format_version)}，建议使用 2`);
  }

  const h = m.header;
  if (!h || typeof h !== "object") {
    err(pack, "manifest.json 缺少 header 对象");
    return null;
  }
  for (const f of ["name", "description", "uuid", "version"]) {
    if (h[f] === undefined) err(pack, `header.${f} 缺失`);
  }
  checkUuid(pack, "header.uuid", h.uuid, { rfc: true });
  checkVersion(pack, "header.version", h.version);
  checkVersion(pack, "header.min_engine_version", h.min_engine_version);

  if (!Array.isArray(m.modules) || m.modules.length === 0) {
    err(pack, "modules 必须是非空数组");
    return { manifest: m, kind };
  }
  const kinds = new Set();
  m.modules.forEach((mod, i) => {
    const where = `modules[${i}]`;
    if (!mod || typeof mod !== "object") {
      err(pack, `${where} 不是对象`);
      return;
    }
    if (!mod.type) err(pack, `${where}.type 缺失`);
    else if (!MODULE_TYPES.has(mod.type)) err(pack, `${where}.type 非法: ${JSON.stringify(mod.type)}`);
    else kinds.add(mod.type);
    checkUuid(pack, `${where}.uuid`, mod.uuid, { rfc: true });
    checkVersion(pack, `${where}.version`, mod.version);
    if (SCRIPT_TYPES.has(mod.type)) {
      if (!mod.entry) err(pack, `${where} 是脚本模块但没有 entry 入口文件`);
      else if (!existsSync(join(packDir, mod.entry))) err(pack, `脚本入口不存在: ${mod.entry}`);
      if (mod.language !== undefined && mod.language !== "javascript") {
        warn(pack, `${where}.language=${JSON.stringify(mod.language)}，通常是 javascript`);
      }
    } else if (mod.entry !== undefined) {
      warn(pack, `${where} 非脚本模块却声明了 entry: ${mod.entry}`);
    }
  });

  if (kind === "behavior" && ![...kinds].some((k) => k === "data" || SCRIPT_TYPES.has(k))) {
    warn(pack, `行为包没有任何 data/script 模块（实际: ${[...kinds].join(", ") || "无"}）`);
  }
  if (kind === "resource" && !kinds.has("resources")) {
    warn(pack, `资源包没有 resources 模块（实际: ${[...kinds].join(", ") || "无"}）`);
  }

  if (Array.isArray(m.dependencies)) {
    m.dependencies.forEach((dep, i) => {
      if (!dep || typeof dep !== "object") {
        err(pack, `dependencies[${i}] 不是对象`);
        return;
      }
      if (dep.module_name !== undefined) {
        // 模块依赖: { "module_name": "@minecraft/server", "version": "1.17.0" }
        if (typeof dep.version !== "string" || !/^\d+\.\d+\.\d+/.test(dep.version)) {
          err(pack, `dependencies[${i}].version 模块依赖应为字符串版本号（如 "1.17.0"），收到 ${JSON.stringify(dep.version)}`);
        }
      } else if (dep.uuid !== undefined) {
        // 包依赖: { "uuid": "...", "version": [0, 1, 0] }
        checkUuid(pack, `dependencies[${i}].uuid`, dep.uuid, { rfc: true });
        checkVersion(pack, `dependencies[${i}].version`, dep.version);
      } else {
        err(pack, `dependencies[${i}] 必须包含 uuid 或 module_name`);
      }
    });
  }
  if (!existsSync(join(packDir, "pack_icon.png"))) {
    warn(pack, "缺少 pack_icon.png（中国版内会显示为空白图标）");
  }
  return { manifest: m, kind };
}

/** 所有 JSON 可解析（manifest 已单独校验，跳过以免重复报错） */
function validateJsonFiles(pack, packDir) {
  for (const rel of walkFiles(packDir)) {
    if (!rel.endsWith(".json") || rel === "manifest.json") continue;
    try {
      readJson(join(packDir, rel));
    } catch (e) {
      err(pack, `${rel} 不是合法 JSON: ${e.message}`);
    }
  }
}

/**
 * Bedrock .lang 是 key=value 纯文本（`#` 开头为注释），不是 JSON。
 * 同时检查键重复与多语言键对齐。
 */
function validateLangFiles(pack, packDir) {
  const dir = join(packDir, "texts");
  if (!existsSync(dir)) return;
  const langFiles = readdirSync(dir).filter((f) => f.endsWith(".lang"));
  if (langFiles.length === 0) {
    warn(pack, "texts/ 下没有 .lang 本地化文件");
    return;
  }
  const keysByFile = new Map();
  for (const f of langFiles) {
    const keys = new Map();
    const lines = readFileSync(join(dir, f), "utf8").split(/\r?\n/);
    lines.forEach((line, i) => {
      const t = line.trim();
      if (t === "" || t.startsWith("#")) return;
      const eq = t.indexOf("=");
      if (eq <= 0) {
        err(pack, `texts/${f}:${i + 1} 不是 key=value 行: ${t.slice(0, 40)}`);
        return;
      }
      const key = t.slice(0, eq).trim();
      if (keys.has(key)) warn(pack, `texts/${f}:${i + 1} 键重复: ${key}`);
      keys.set(key, t.slice(eq + 1));
    });
    keysByFile.set(f, keys);
  }
  const allKeys = new Set([...keysByFile.values()].flatMap((m) => [...m.keys()]));
  for (const [f, keys] of keysByFile) {
    const missing = [...allKeys].filter((k) => !keys.has(k));
    if (missing.length > 0) {
      warn(pack, `texts/${f} 缺少 ${missing.length} 个键（其他语言有）: ${missing.slice(0, 5).join(", ")}${missing.length > 5 ? " …" : ""}`);
    }
  }
  const langsJson = join(dir, "languages.json");
  if (existsSync(langsJson)) {
    try {
      const declared = readJson(langsJson);
      if (Array.isArray(declared)) {
        const onDisk = langFiles.map((f) => f.replace(/\.lang$/, ""));
        const undeclared = onDisk.filter((l) => !declared.includes(l));
        if (undeclared.length > 0) warn(pack, `languages.json 未声明: ${undeclared.join(", ")}`);
      } else {
        warn(pack, "languages.json 应为语言代码数组");
      }
    } catch (e) {
      err(pack, `languages.json 不是合法 JSON: ${e.message}`);
    }
  }
}

/** 解析 "textures/..." 引用为磁盘文件（自动补 .png/.tga 扩展名） */
function resolveTexture(packDir, ref) {
  const clean = ref.split("?")[0];
  const candidates = [clean];
  if (!IMAGE_EXT.some((e) => clean.toLowerCase().endsWith(e))) {
    for (const e of IMAGE_EXT) candidates.push(clean + e);
  }
  return candidates.find((c) => existsSync(join(packDir, c)));
}

/** 扫描所有 JSON 里的贴图引用 + 校验 PNG */
function validateTextures(pack, packDir) {
  const seen = new Set();
  for (const rel of walkFiles(packDir)) {
    if (!rel.endsWith(".json") || rel === "manifest.json") continue;
    let data;
    try {
      data = readJson(join(packDir, rel));
    } catch {
      continue; // 已在 validateJsonFiles 报错
    }
    const visit = (node) => {
      if (typeof node === "string") {
        if (/^textures\//i.test(node) && !seen.has(node)) {
          seen.add(node);
          if (!resolveTexture(packDir, node)) {
            err(pack, `${rel} 引用的贴图不存在: ${node}（尝试补 .png/.tga）`);
          }
        }
      } else if (node && typeof node === "object") {
        for (const v of Object.values(node)) visit(v);
      }
    };
    visit(data);
  }
  for (const rel of walkFiles(packDir)) {
    if (rel.toLowerCase().endsWith(".png")) checkPng(pack, packDir, rel);
  }
}

/** client_entity 的 geometry 标识符必须在 models 目录的 .geo.json 中定义 */
function validateGeometry(pack, packDir) {
  const modelIds = new Set();
  for (const rel of walkFiles(packDir)) {
    if (!/\.geo\.json$/i.test(rel)) continue;
    try {
      const data = readJson(join(packDir, rel));
      const geos = data["minecraft:geometry"];
      if (Array.isArray(geos)) {
        for (const g of geos) {
          const id = g?.description?.identifier;
          if (id) modelIds.add(String(id));
        }
      }
    } catch {
      /* 已报错 */
    }
  }
  const entityDir = join(packDir, "entity");
  if (!existsSync(entityDir)) return;
  for (const rel of walkFiles(entityDir)) {
    if (!rel.endsWith(".json")) continue;
    let data;
    try {
      data = readJson(join(entityDir, rel));
    } catch {
      continue;
    }
    const desc = data?.["minecraft:client_entity"]?.description;
    const geom = desc?.geometry;
    const refs = [];
    if (typeof geom === "string") refs.push(geom);
    else if (geom && typeof geom === "object") {
      for (const v of Object.values(geom)) if (typeof v === "string") refs.push(v);
    }
    for (const ref of refs) {
      if (ref.startsWith("geometry.") && !modelIds.has(ref)) {
        err(pack, `entity/${rel} 引用的 geometry 未在 models/**/*.geo.json 中定义: ${ref}`);
      }
    }
    if (refs.length === 0) warn(pack, `entity/${rel} 未声明 geometry`);
  }
}

function validatePack(pack, packDir, kind) {
  const info = validateManifest(pack, packDir, kind);
  validateJsonFiles(pack, packDir);
  validateLangFiles(pack, packDir);
  validateTextures(pack, packDir);
  if (kind === "resource") validateGeometry(pack, packDir);
  return info;
}

function discoverPacks(root, subdir, kind) {
  const base = join(root, subdir);
  if (!existsSync(base)) {
    warn(subdir, `目录不存在: ${relative(root, base)}`);
    return [];
  }
  const found = [];
  for (const entry of readdirSync(base, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    if (entry.name.startsWith(".") || entry.name === "node_modules") continue;
    found.push({ pack: `${subdir}/${entry.name}`, dir: join(base, entry.name), kind });
  }
  if (found.length === 0) warn(subdir, `${subdir}/ 下没有包目录`);
  return found;
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  const packs = [
    ...discoverPacks(opts.root, "behavior_packs", "behavior"),
    ...discoverPacks(opts.root, "resource_packs", "resource"),
  ];
  if (packs.length === 0) {
    err("addon", "没有发现任何包：需要 behavior_packs/<pack>/ 或 resource_packs/<pack>/");
  }

  const infos = packs.map((p) => {
    const info = validatePack(p.pack, p.dir, p.kind);
    if (info) info.pack = p.pack;
    return info;
  });

  // UUID 全局唯一性 + dependencies 引用可解析
  const seen = new Map();
  const known = new Set();
  for (const info of infos) {
    if (!info?.manifest) continue;
    for (const e of [info.manifest.header, ...(info.manifest.modules ?? [])]) {
      if (e?.uuid) known.add(String(e.uuid).toLowerCase());
    }
  }
  for (const info of infos) {
    if (!info?.manifest) continue;
    const pack = info.pack;
    const entries = [
      ["header", info.manifest.header?.uuid],
      ...(info.manifest.modules ?? []).map((m, i) => [`modules[${i}]`, m?.uuid]),
    ];
    for (const [where, uuid] of entries) {
      if (!uuid) continue;
      const key = String(uuid).toLowerCase();
      if (seen.has(key)) err(pack, `UUID 重复: ${uuid}（已用于 ${seen.get(key)}）`);
      else seen.set(key, `${pack} ${where}`);
    }
    for (const dep of info.manifest.dependencies ?? []) {
      if (dep?.uuid && !known.has(String(dep.uuid).toLowerCase())) {
        const hint =
          info.kind === "resource"
            ? "；资源包若要依赖行为包，其 uuid 必须等于行为包 header.uuid"
            : "";
        warn(pack, `依赖的 UUID 不在本次打包内（可能为原版/实验模块，或对应包尚未落盘）: ${dep.uuid}${hint}`);
      }
    }
  }

  // 行为包与资源包的 min_engine_version 应一致，避免一方在旧引擎被拒绝
  const bp = infos.find((i) => i?.kind === "behavior");
  const rp = infos.find((i) => i?.kind === "resource");
  if (bp?.manifest && rp?.manifest) {
    const bv = JSON.stringify(bp.manifest.header?.min_engine_version);
    const rv = JSON.stringify(rp.manifest.header?.min_engine_version);
    if (bv !== rv) {
      warn("addon", `BP 与 RP 的 min_engine_version 不一致: BP=${bv}, RP=${rv}`);
    }
  }

  const errors = problems.filter((p) => p.level === "error");
  const warnings = problems.filter((p) => p.level === "warning");

  if (opts.json) {
    console.log(JSON.stringify({ root: opts.root, packs: packs.length, errors, warnings }, null, 2));
  } else {
    console.log(`Diorama Bedrock 附加包校验 @ ${opts.root}`);
    console.log(`发现 ${packs.length} 个包\n`);
    for (const p of problems) {
      const mark = p.level === "error" ? "ERROR  " : "WARN   ";
      console.log(`${mark}[${p.pack}] ${p.msg}`);
    }
    if (problems.length === 0) console.log("全部通过，无 error / warning。");
    console.log(`\n结果: ${errors.length} error, ${warnings.length} warning`);
  }

  process.exit(errors.length > 0 || (opts.strict && warnings.length > 0) ? 1 : 0);
}

main();
