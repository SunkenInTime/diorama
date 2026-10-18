# Diorama 中国版 ModSDK 实现（开发工作台原型）

这是面向网易《我的世界》中国版 ModSDK 的第二条实现路线，不是国际版 `.mcaddon`。
目标是 PC + 开发工作台个人测试，使用 Python 客户端/服务端、JSON UI 和可选 RenderDragon 能力。

## 当前内容

- `diorama_core/`：标准库纯 Python 核心与 101 个离线测试，包含斜角投影、DDA 体素采样、LOD、MipMap、路径点与雷达几何。
- `DioramaMod/diorama_behavior_pack/dioramaScripts/modCore/`：可放入 ModSDK 行为包的核心移植，包含 baker、palette、block source、mipmaps、pick、waypoint store 等。
- `modClient/main.py`：客户端适配边界；UiInitFinished 后创建 HUD，接收服务端 tile payload。
- `modServer/main.py`：服务端采样/通知适配边界；具体 SDK 事件名集中在此处替换。
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
