# 构建与开发运行

状态：M0 基础工程。Fabric 和 Minecraft Forge 1.21.1 的测试、构建与专服模组初始化已在本地验证；伙伴、聊天、模型请求和世界操作尚未实现。

## 固定工具链

| 项目 | 固定版本 | 说明 |
| --- | --- | --- |
| Java | 21 | 编译目标和运行要求 |
| Gradle | 8.8 | 通过仓库 Wrapper 使用 |
| Minecraft | 1.21.1 | Fabric 与 Forge 共用目标版本 |
| Fabric Loader | 0.20.1 | 与 #1 的玩家控制验证宿主一致；当前不依赖 Fabric API |
| Fabric Loom | 1.7.4 | 仅作为构建插件 |
| Minecraft Forge | 52.1.20 | 不使用 NeoForge 替代 |
| ForgeGradle | 6.0.24 | 锁定插件版本 |

版本统一记录在根目录 [gradle.properties](../gradle.properties)。除非关联任务明确需要，不使用动态版本。

## 构建

只需要预先安装 JDK 21，然后从仓库根目录使用 Wrapper：

```bash
./gradlew checkAll --no-daemon
```

Windows 使用：

```powershell
.\gradlew.bat checkAll --no-daemon
```

该命令运行 `common` 测试，并收集两个可安装产物到 `build/artifacts/`：

- `minecraft-friends-fabric-1.21.1-<版本>.jar`
- `minecraft-friends-forge-1.21.1-<版本>.jar`

首次构建需要下载加载器、映射和开发依赖，可能耗时较长。CI 配置见 [.github/workflows/build.yml](../.github/workflows/build.yml)。

## 开发运行

| 命令 | 作用 |
| --- | --- |
| `./gradlew :fabric:runClient` | Fabric 开发客户端 |
| `./gradlew :fabric:runServer` | Fabric 开发专用服务器 |
| `./gradlew :forge:runClient` | Forge 开发客户端 |
| `./gradlew :forge:runServer` | Forge 开发专用服务器 |

运行目录分别是 `fabric/run/` 和 `forge/run/`，均已加入忽略规则。Fabric 专服首次启动会创建 `server.properties` 和 `eula.txt`。是否同意 Minecraft EULA 必须由使用者本人阅读并决定；工程不会自动修改 `eula.txt`。

Forge 首次开发运行可能下载游戏资源。仅验证服务端模组加载时，可以跳过纯客户端资源准备：

```bash
./gradlew :forge:runGameTestServer \
  -x :forge:downloadAssets \
  -x :forge:downloadMCMeta
```

当前没有注册 GameTest 用例，因此该命令会完成模组初始化后以“没有测试函数”结束。它只用于 M0 加载冒烟检查，不是正式测试命令；正式 CI 使用 `checkAll`。

## 配置

模组启动时会在运行目录的 `config/minecraft-friends.properties` 创建默认配置。M0 支持的字段如下：

| 键 | 默认值 | 范围与行为 |
| --- | --- | --- |
| `enabled` | `true` | 布尔值 |
| `model.provider` | `mock` | `mock` 或 `disabled` |
| `model.timeout-seconds` | `30` | 1 到 300 |
| `diagnostics.enabled` | `true` | 布尔值 |

M0 不读取、保存或接受 API key，也不会发起模型网络请求。无效字段会记录警告并使用安全默认值。后续真实 Provider 的路径、隐私和费用影响必须在对应实现任务中补充。

## 约束

- 不通过系统批量升级解决构建依赖，不升级内核或显卡驱动。
- 不在仓库、日志或聊天中存放模型密钥。
- 新增依赖必须固定版本并更新 [依赖清单](DEPENDENCIES.md)。
- 开发运行生成的世界、日志与配置不进入仓库。
