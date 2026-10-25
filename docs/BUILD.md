# 构建与开发运行

状态：M0 基础工程。Fabric 和 Minecraft Forge 1.21.1 的测试、构建、开发客户端、普通专服与最终 JAR 独立安装运行已验证；伙伴、聊天、模型请求和世界操作尚未实现。证据见 [运行与安装验收](validation/0005-runtime-installation.md)。

## 固定工具链

| 项目 | 固定版本 | 说明 |
| --- | --- | --- |
| Java | 21 | 编译目标和运行要求 |
| Gradle | 8.8 | 通过仓库 Wrapper 使用 |
| Minecraft | 1.21.1 | Fabric 与 Forge 共用目标版本 |
| Fabric Loader | 0.20.1 | 正式基础工程版本；#1 历史独立探针使用 0.16.14，当前工程不依赖 Fabric API |
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

该命令运行 `common` 测试，收集两个可安装产物到 `build/artifacts/`，并打开 JAR 校验加载器入口、元数据与公共类是否齐全：

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

运行目录分别是 `fabric/run/` 和 `forge/run/`，均已加入忽略规则。专服首次启动会创建 `server.properties` 和 `eula.txt`。是否同意 Minecraft EULA 必须由使用者本人阅读并决定；工程不会自动修改 `eula.txt`。

本次 Forge 开发专服经 Gradle 管道启动时，`stop` 未转发到游戏进程；验收脚本终止外层进程后，游戏执行停止与世界保存，Gradle 外壳非零。独立 Forge 安装直接启动 Java 时，`stop` 与退出码 0 均正常。完整证据见 [运行验收](validation/0005-runtime-installation.md#开发专用服务器)，不要将开发管道退出码当作游戏关闭结果。

Forge 首次开发运行可能下载游戏资源。仅验证服务端模组加载时，可以跳过纯客户端资源准备：

```bash
./gradlew :forge:runGameTestServer \
  -x :forge:downloadAssets \
  -x :forge:downloadMCMeta
```

当前没有注册 GameTest 用例，因此该命令会完成模组初始化后以“没有测试函数”结束。它只用于 M0 加载冒烟检查，不是正式测试命令；正式 CI 使用 `checkAll`。

## 配置

模组启动时会在运行目录的 `config/minecraft-friends.properties` 创建默认配置。[可直接使用的配置示例](examples/minecraft-friends.properties) 与生产解析器的默认值一致。开发环境对应 `fabric/run/config/minecraft-friends.properties` 或 `forge/run/config/minecraft-friends.properties`；预置时复制到对应路径，已有配置先比较差异，不盲目覆盖。当前没有热重载命令，修改后需重新启动相应开发实例。M0 支持的字段如下：

| 键 | 默认值 | 范围与行为 |
| --- | --- | --- |
| `enabled` | `true` | 布尔值 |
| `model.provider` | `mock` | `mock` 或 `disabled` |
| `model.timeout-seconds` | `30` | 1 到 300 |
| `diagnostics.enabled` | `true` | 布尔值 |

这些字段目前用于读取、校验与报告配置；即使关闭 `enabled`，配置加载和启动日志仍会执行，尚无伙伴或业务任务可被它控制。`model.timeout-seconds` 是预留请求预算，当前 Mock 不进行网络调用，不将字段存在当作真实请求超时已实现。

M0 不读取、保存或接受 API key，也不会发起模型网络请求。无效字段会记录警告并使用安全默认值。后续真实 Provider 的路径、隐私和费用影响必须在对应实现任务中补充。

## 约束

- 不通过系统批量升级解决构建依赖，不升级内核或显卡驱动。
- 不在仓库、日志或聊天中存放模型密钥。
- 新增依赖必须固定版本并更新 [依赖清单](DEPENDENCIES.md)。
- 开发运行生成的世界、日志与配置不进入仓库。

## 版本与增量构建边界

两种产物的 Minecraft 依赖均精确使用 `gradle.properties` 中的目标版本；初期为 1.21.1，不声明其他 1.21.x 已兼容。Forge 的生产 Java 类和资源保持独立输出；开发运行通过 `prepareDevelopmentMod` 生成单独的合并副本，避免资源刷新删除已编译的入口类。

可用 `./gradlew checkAll -Pproject_version=0.1.0-resource-check` 验证只改变版本元数据的重建，再运行默认 `checkAll` 恢复产物版本。上述操作只修改生成目录，不改源码。Forge 加载冒烟必须核对实际初始化日志；退出码 0 也可能伴随模组加载失败或“没有测试函数”，不能单凭退出码判定正常运行。
