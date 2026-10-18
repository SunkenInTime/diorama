# Diorama 中国版 ModSDK 实现（开发工作台原型）

这是面向网易《我的世界》中国版 ModSDK 的第二条实现路线，不是国际版 `.mcaddon`。
目标是 PC + 开发工作台个人测试，使用 Python 客户端/服务端、JSON UI 和可选 RenderDragon 能力。

## 当前内容

- `diorama_core/`：标准库纯 Python 核心与 101 个离线测试，包含斜角投影、DDA 体素采样、LOD、MipMap、路径点与雷达几何。
- `DioramaMod/diorama_behavior_pack/dioramaScripts/modCore/`：可放入 ModSDK 行为包的核心移植，包含 baker、palette、block source、mipmaps、pick、waypoint store 等。
- `modClient/DioramaClientSystem.py`：客户端系统与 UI 适配；UiInitFinished 后创建 HUD，接收服务端 tile payload。
- `modServer/DioramaServerSystem.py`：服务端系统与采样/通知适配；具体 SDK 事件名集中在此处替换。
- `diorama_resource_pack/ui/diorama_hud.json`：16×16 collection binding 的 HUD 网格原型。

## 测试

```sh
python3 run_tests.py
# 或
PYTHONPATH=. python3 -m unittest discover -s tests -v
```

当前测试覆盖核心算法，不需要安装 Minecraft 或 ModSDK。

## 导入开发工作台

1. 在中国版开发工作台创建 ModSDK 项目并选择 Python 客户端+服务端模板。
2. 将 `DioramaMod/diorama_behavior_pack/dioramaScripts/` 合并到工作台生成的脚本目录。
3. 将 `DioramaMod/diorama_resource_pack/ui/` 和 `textures/` 合并到资源包。
4. 按本机 ModSDK 版本补齐 `RegisterSystem`、`ListenForEvent`、`RegisterUI`、`CreateUI` 和 `NotifyToClient` 的准确签名。
5. 在 `UiInitFinished` 后创建 HUD；服务端只发送经过 LOD/限流的 tile payload。

> ModSDK 的 Python API 在不同中国版客户端版本存在签名差异。本目录把 SDK 调用隔离在 `modClient/`、`modServer/`，离线核心不依赖这些易变 API。没有中国版客户端/开发工作台时，不能诚实地宣称已经完成真机导入测试。

## 与原 Diorama 的对应关系

- `Projection` / DDA / lighting inputs / mipmaps / LOD：已移植并有离线测试。
- 迷你地图 HUD：使用 JSON UI collection binding；方块采样需要 ModSDK `GetBlockPalette*` 或等价接口。
- 全屏 Diorama：下一步接入 `CreateMicroBlockResStr` 或虚拟世界模型；官方接口没有把独立 3D 场景直接 blit 到 HUD 纹理的能力。
- 雷达与路径点：由服务端同步数据，客户端通过 UI/绘制组件展示。

这是一条真实的中国版 ModSDK 工程路线，但仍需在目标中国版客户端上完成 API 签名适配和性能测试，不能把离线测试等同于游戏内验收。

---

## 完成度与工具（离线可验证部分）

以下内容已在无游戏、无 ModSDK 的普通 CPython 上完成并通过检查；它们都在 `DioramaMod/` 与 `tools/` 下。

### 目录清单

- **清单文件**：`diorama_behavior_pack/manifest.json`（data 模块）与
  `diorama_resource_pack/manifest.json`（resources 模块），UUID 全局唯一，资源包通过
  `dependencies` 引用行为包。
- **客户端系统**：`dioramaScripts/modClient/DioramaClientSystem.py` 注册 4 个界面
  （HUD / 全屏地图 / 路径点 / 设置），并拆分为 `modClient/components/` 下的
  `Hud`、`Map`、`Radar`、`Settings`、`Waypoint` 五个组件。
- **服务端系统**：`modServer/DioramaServerSystem.py` + `MapServerComponent`、
  `RadarServerComponent`、`WaypointServerComponent`，引擎调用集中在
  `modServer/engine/`。
- **UI 模板**：`diorama_resource_pack/ui/` 下的 `_ui_defs.json`、`diorama_common.json`、
  `diorama_hud.json`、`diorama_map.json`、`diorama_waypoints.json`、
  `diorama_settings.json`、`diorama_label.json`。每个界面使用
  `main@common.base_screen` + `$screen_content`，路径与 Python 侧的
  `modConfig.BASE_SCREEN_PATH` 拼接规则一致。
- **文本**：`texts/zh_CN.lang`、`texts/en_US.lang`（键集合一致）与 `texts/languages.json`。
- **贴图**：`textures/ui/` 下的面板/按钮/徽章/罗盘/标记与地图集，均由脚本生成，
  不包含任何 Mojang 素材。

> `_ui_defs.json` 是官方教程使用的文件名（复数 `defs`）；校验器同时接受单数拼写但会告警。

### 工具

```sh
python3 tools/validate.py --strict   # 静态校验：编译、清单、UUID、UI 引用、贴图、文本
python3 tools/gen_textures.py        # 生成 UI 贴图与两个 pack_icon.png
python3 tools/bake_atlas.py --grid 2 # 烘焙全屏地图底图 diorama_atlas.png
python3 tools/preview_bake.py --grid 2 --overlay waypoints --out docs/preview.png
```

`validate.py` 的 `--strict` 是发布前的门槛：0 error / 0 warning。
`preview_bake.py` 与 `bake_atlas.py` 复用 `modCore.baker`，所以 PC 上预览到的画面就是
运行时会用到的同一套投影、DDA 与光照代码。

### 测试

`python3 run_tests.py` 现在包含 `tests/test_scaffold.py`：它解析两份清单、检查 UI
文件与 `_ui_defs.json` 的对应关系、验证文本键一致与贴图存在，并以子进程运行
`tools/validate.py --strict`。离线测试不能替代真机导入，但可以把“清单/路径/贴图写错”
这类问题拦在进游戏之前。

### 仍需真机确认

1. 按本机 ModSDK 版本核对 `RegisterUI` / `CreateUI` / `NotifyToClient` 的签名，以及
   `_ui_defs.json` 是否被当前客户端读取。
2. 在开发工作台里实测 HUD 控件路径（基础屏幕路径拼接）与触摸回调。
3. 实测服务端 tile 烘焙的耗时；默认关闭运行时烘焙，地图使用离线的
   `diorama_atlas.png`。
