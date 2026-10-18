#!/usr/bin/env bash
# Diorama Bedrock 附加包打包脚本 / packager.
#
# 产物:
#   dist/Diorama-Bedrock-<version>.mcaddon   # 行为包 + 资源包，一次导入
#   dist/diorama_bp.mcpack                   # 仅行为包
#   dist/diorama_rp.mcpack                   # 仅资源包
#
# 用法:
#   ./package-addon.sh                 # 先校验再打包
#   ./package-addon.sh --skip-validate # 跳过校验（不推荐）
#   ./package-addon.sh --out <dir>     # 指定输出目录，默认 ./dist
#   ./package-addon.sh --root <dir>    # 指定 packs 所在根目录，默认脚本所在目录
#
# 退出码: 0 成功, 1 校验/打包失败, 2 环境或用法错误。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR"
OUT=""
SKIP_VALIDATE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-validate) SKIP_VALIDATE=1; shift ;;
    --out) OUT="${2:?--out 需要一个目录}"; shift 2 ;;
    --root) ROOT="$(cd "${2:?--root 需要一个目录}" && pwd)"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

OUT="${OUT:-$ROOT/dist}"
BP_DIR="$ROOT/behavior_packs/diorama_bp"
RP_DIR="$ROOT/resource_packs/diorama_rp"

log()  { printf '[package] %s\n' "$*"; }
fail() { printf '[package] ERROR: %s\n' "$*" >&2; exit 1; }

command -v zip >/dev/null 2>&1 || fail "缺少 zip 命令，请先安装 zip"

[[ -d "$BP_DIR" ]] || fail "行为包目录不存在: $BP_DIR"
[[ -f "$BP_DIR/manifest.json" ]] || fail "行为包缺少 manifest.json: $BP_DIR"
[[ -d "$RP_DIR" ]] || fail "资源包目录不存在: $RP_DIR"
[[ -f "$RP_DIR/manifest.json" ]] || fail "资源包缺少 manifest.json: $RP_DIR"

# 从 BP manifest 读取版本号用于命名；失败则回退到 0.0.0
VERSION="$(node -e '
  const fs=require("fs");
  try {
    const m=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));
    const v=m?.header?.version;
    process.stdout.write(Array.isArray(v)?v.join("."):"0.0.0");
  } catch { process.stdout.write("0.0.0"); }
' "$BP_DIR/manifest.json" 2>/dev/null || echo "0.0.0")"

if [[ "$SKIP_VALIDATE" -eq 0 ]]; then
  log "校验附加包..."
  if ! node "$ROOT/validate.mjs" --root "$ROOT" --strict; then
    fail "校验未通过；修复后再打包，或用 --skip-validate 强制继续"
  fi
else
  log "跳过校验（--skip-validate）"
fi

mkdir -p "$OUT"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

# 复制成 Minecraft 期望的目录结构，并清掉不该进包的杂物
copy_pack() {
  local src="$1" dest="$2"
  mkdir -p "$dest"
  ( cd "$src" && zip -rq -X "$STAGE/tmp.zip" . -x '*.DS_Store' -x '__MACOSX/*' -x '*.bak' -x '*/node_modules/*' -x '*.git*' -x 'dist/*' )
  unzip -qo "$STAGE/tmp.zip" -d "$dest"
  rm -f "$STAGE/tmp.zip"
}

rm -rf "$STAGE/behavior_packs" "$STAGE/resource_packs"
copy_pack "$BP_DIR" "$STAGE/behavior_packs/diorama_bp"
copy_pack "$RP_DIR" "$STAGE/resource_packs/diorama_rp"

ADDON="$OUT/Diorama-Bedrock-$VERSION.mcaddon"
BP_PACK="$OUT/diorama_bp.mcpack"
RP_PACK="$OUT/diorama_rp.mcpack"
rm -f "$ADDON" "$BP_PACK" "$RP_PACK"

( cd "$STAGE" && zip -rq -X "$ADDON" behavior_packs resource_packs )
# A .mcpack is itself one pack: manifest.json must be at archive root.
( cd "$BP_DIR" && zip -rq -X "$BP_PACK" . -x '*.DS_Store' -x '__MACOSX/*' -x '*.bak' -x '*/node_modules/*' -x '*.git*' )
( cd "$RP_DIR" && zip -rq -X "$RP_PACK" . -x '*.DS_Store' -x '__MACOSX/*' -x '*.bak' -x '*/node_modules/*' -x '*.git*' )

log "完成:"
for f in "$ADDON" "$BP_PACK" "$RP_PACK"; do
  printf '  %s  (%s)\n' "$f" "$(du -h "$f" | cut -f1)"
done
log "安装: 中国版我的世界 → 打开 .mcaddon / .mcpack 自动导入，或在「组件中心 → 我的组件」中导入。"
