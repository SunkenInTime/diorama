# Diorama 基岩版附加包（Bedrock Add-on）

把 [Diorama](https://github.com/SunkenInTime/diorama)（Fabric 客户端地图模组）的核心地图能力，
以**基岩版附加包**的形式带到《我的世界》中国版。附加包由**行为包**（逻辑、脚本、物品与实体）和
**资源包**（贴图、文案、界面）组成，可一次导入、随存档启用。

> 版本与兼容性以 `behavior_packs/diorama_bp/manifest.json` 中的 `min_engine_version` 为准。

---

## 1. 目录结构

```
bedrock-addon/
├── behavior_packs/
│   └── diorama_bp/            # 行为包：manifest、脚本、自定义物品、路径点标记实体
├── resource_packs/
│   └── diorama_rp/            # 资源包：manifest、物品贴图、UI、本地化文本
├── validate.mjs               # 校验脚本（零依赖 Node.js）
├── package-addon.sh           # 打包脚本（.mcaddon / .mcpack）
└── README.md
```

行为包与资源包通过清单（manifest）中的 UUID 互相引用；两者的 `header.uuid` 与模块 UUID
必须全局唯一且稳定，**不要**在版本迭代中随意更换，否则已导入的存档会失去关联。

---

## 2. 安装（《我的世界》中国版）

### 2.1 使用打包好的 `.mcaddon`（推荐）

1. 在本目录执行打包（见第 4 节），得到 `dist/Diorama-Bedrock-<版本>.mcaddon`。
2. 启动中国版启动器，进入「组件中心 → 我的组件 → 导入」。
3. 选择 `.mcaddon` 文件，等待提示导入成功（行为包与资源包会一起导入）。
4. 新建或编辑世界 →「组件」→ 分别启用 **Diorama 行为包** 与 **Diorama 资源包**。
5. 进入世界后即可使用（见第 3 节功能映射）。

### 2.2 仅导入单个包

- 只需要逻辑、贴图用原版：导入 `dist/diorama_bp.mcpack`。
- 只需要贴图与文案：导入 `dist/diorama_rp.mcpack`。

行为包与资源包**需成对启用**才能得到完整效果；只启用其一可能导致物品显示为紫黑方块或脚本无效。

### 2.3 手动安装（中国版开发/调试）

1. 打开中国版存档目录（启动器「设置 → 游戏目录」，通常在
   `%APPDATA%\MinecraftPE_Netease\` 或启动器自定义的 `minecraftWorlds` 同级目录）。
2. 将 `behavior_packs/diorama_bp`、`resource_packs/diorama_rp` 整体复制到
   `development_behavior_packs/` 与 `development_resource_packs/`。
3. 重启启动器，在世界设置中启用两个包。

> 中国版与基岩版国际版的包格式一致；国际版可把 `.mcaddon` 直接双击导入。

---

## 3. 功能映射（Fabric 模组 → 基岩版附加包）

Diorama 模组是**客户端渲染**模组，能在屏幕上实时绘制体素地图；基岩版附加包**不能接管客户端渲染**，
因此映射遵循"能力尽量对齐、渲染类功能用物品/实体与脚本近似"的原则：

| Diorama（Fabric）功能 | 基岩版附加包实现 | 状态 |
|---|---|---|
| 路径点（`B` 添加、编辑、列表） | 自定义**路径点标记物品**放置/移除路径点标记实体；脚本记录坐标 | ✅ |
| 死亡点标记 | 脚本监听玩家死亡，在死亡位置生成标记实体 | ✅ |
| 坐标 / 方位提示 | 脚本通过动作栏（actionbar）显示当前坐标与朝向 | ✅ |
| 与其他玩家分享路径点 | 脚本支持在聊天中分享坐标（受中国版聊天 API 限制） | ⚠️ 部分 |
| 指南针指向 | 脚本计算目标方位并通过 UI/动作栏提示 | ✅ |
| 迷你地图 / 全屏地图 | 附加包无客户端渲染权限，无法绘制 | ❌ 不支持 |
| 雷达（玩家/生物标记） | 可用实体检测 + 标记实体近似，但无实时小地图 | ⚠️ 部分 |
| 洞穴切片 / 天空可见度光影 | 依赖客户端光影管线，附加包无法实现 | ❌ 不支持 |
| 存档地图（世界/维度分目录） | 由行为包脚本用动态属性（dynamic properties）保存路径点数据 | ✅ |
| 地图设置界面 | 资源包定义自定义 UI + 脚本读写设置 | ✅ |

> 上表中"脚本/物品/实体"的**确切标识符**以 `behavior_packs/diorama_bp/manifest.json`、
> `items/`、`entities/` 与 `scripts/` 下的实际定义为准；资源包贴图与文本键见
> `resource_packs/diorama_rp/textures/` 与 `texts/`。

无法实现的地图渲染类功能**不会**用近似实现冒充，以免误导；如需完整地图体验，请使用 Java 版模组。

---

## 4. 打包与校验

### 4.1 校验

需要 Node.js ≥ 18（无第三方依赖）：

```sh
node validate.mjs            # 日常校验：error 失败，warning 提示
node validate.mjs --strict   # 发布前校验：warning 也视为失败
node validate.mjs --json     # CI 用机器可读输出
```

校验内容：

- `manifest.json` 合法性：必填字段、`format_version`、`header`/`modules` 结构；
- UUID 格式与**全局唯一性**（header 与各 module 之间不允许重复）；
- 版本号必须是 `[major, minor, patch]` 三个非负整数，`min_engine_version` 同理；
- 模块类型白名单，脚本模块必须有存在性可达的 `entry` 入口文件；
- 行为包至少含 `data`/脚本模块，资源包必须含 `resources` 模块；
- `texts/*.lang`（基岩版为 JSON 映射）与 `textures/**/*.json` 解析正确，
  被引用的贴图路径在磁盘上存在；
- `pack_icon.png` 存在性（缺失仅警告）。

退出码：`0` 通过，`1` 存在 error（或 `--strict` 下存在 warning），`2` 用法/环境错误。

### 4.2 打包

```sh
./package-addon.sh                 # 先 --strict 校验，再打包
./package-addon.sh --skip-validate # 跳过校验（不推荐）
./package-addon.sh --out build     # 指定输出目录
./package-addon.sh --root <dir>    # 指定 packs 所在根目录（默认脚本所在目录）
```

产物位于 `dist/`：

| 文件 | 内容 |
|---|---|
| `Diorama-Bedrock-<version>.mcaddon` | 行为包 + 资源包，一次导入 |
| `diorama_bp.mcpack` | 仅行为包 |
| `diorama_rp.mcpack` | 仅资源包 |

版本号取自行为包 `manifest.json` 的 `header.version`。打包时自动排除
`.DS_Store`、`__MACOSX`、`*.bak`、`node_modules`、`.git` 等无关文件。

---

## 5. 版本与发布检查清单

发布新版本前：

1. 同步提升**行为包与资源包**的 `header.version`（`[major, minor, patch]`）。
2. 若最低引擎版本变化，更新 `header.min_engine_version` 并在本文件注明。
3. 首次发布或新增模块时，生成新的、唯一的 UUID；仅内容更新时保持 UUID 不变。
4. `node validate.mjs --strict` 必须零 error、零 warning。
5. `./package-addon.sh` 打包，并在中国版中导入 `.mcaddon` 实测：物品可获取、标记可放置、
   脚本无报错、文案与贴图正常显示。
6. 不要提交 `dist/` 与本地调试产物；如需忽略，可在仓库根 `.gitignore` 追加 `bedrock-addon/dist/`。

---

## 6. 开发说明

- **行为包**：`design/` 与 `scripts/` 使用 `@minecraft/server` 等 Script API 模块；
  改动脚本后重新导入或在开发世界中重载，注意 `manifest.json` 中脚本模块的 `entry` 路径。
- **资源包**：物品图标放在 `textures/items/`，在 `textures/item_texture.json` 中登记；
  文本键值放在 `texts/zh_CN.lang`（JSON）。新增文本时同步 `en_US.lang`。
- **中国版注意**：部分国际版 Script API 在中国版当前引擎版本上可能不可用；以
  `min_engine_version` 与实际客户端为准，遇到 API 缺失时降级为动作栏提示等通用能力。
- 修改 BP/RP 内部结构后，务必重跑 `validate.mjs`，它会在打包前拦住绝大多数低级错误。
