# 构建与开发运行

状态：M1 伙伴身份与生命周期开发中。Fabric 和 Minecraft Forge 1.21.1 的身份/归属生命周期 GameTest 与普通专服启动已通过；单机实际玩法和 gamerule 掉落实体仍未验收。详见 [伙伴生命周期记录](validation/0007-companion-lifecycle.md)。

## 固定工具链

| 项目 | 固定版本 | 说明 |
| --- | --- | --- |
| Java | 21 | 编译目标和运行要求 |
| Gradle | 8.8 | 通过仓库 Wrapper 使用 |
| Minecraft | 1.21.1 | Fabric 与 Forge 共用目标版本 |
| Fabric Loader | 0.20.1 | Fabric 加载器 |
| Fabric API | 0.116.17+1.21.1 | Fabric 命令与玩家生命周期事件；客户端和服务端都需安装 |
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

运行目录位于 `fabric/build/run/` 和 `forge/build/run/` 下，客户端、专服与 GameTest 各自隔离。专服首次启动会创建 `server.properties` 和 `eula.txt`。是否同意 Minecraft EULA 必须由使用者本人阅读并决定；工程不会自动修改 `eula.txt`。

本次 Forge 开发专服经 Gradle 管道启动时，`stop` 未转发到游戏进程；验收脚本终止外层进程后，游戏执行停止与世界保存，Gradle 外壳非零。独立 Forge 安装直接启动 Java 时，`stop` 与退出码 0 均正常。完整证据见 [运行验收](validation/0005-runtime-installation.md#开发专用服务器)，不要将开发管道退出码当作游戏关闭结果。

Forge 首次开发运行可能下载游戏资源。仅验证服务端模组加载时，可以跳过纯客户端资源准备：

```bash
./gradlew :forge:runGameTestServer \
  -x :forge:downloadAssets \
  -x :forge:downloadMCMeta
```

该命令用于 Forge GameTest 运行；没有测试用例时不能以退出码 0 作为玩法通过证据。完整的伙伴生命周期验收需要记录每项断言结果。

## 配置

模组启动时会在运行目录的 `config/minecraft-friends.properties` 创建默认配置。[可直接使用的配置示例](examples/minecraft-friends.properties) 与生产解析器的默认值一致。开发环境对应 `fabric/build/run/<client|server>/config/minecraft-friends.properties` 或 `forge/build/run/<client|server>/config/minecraft-friends.properties`；预置时复制到对应路径，已有配置先比较差异，不盲目覆盖。当前没有热重载命令，修改后需重新启动相应开发实例。M0 支持的字段如下：

| 键 | 默认值 | 范围与行为 |
| --- | --- | --- |
| `enabled` | `true` | 布尔值 |
| `model.provider` | `mock` | `mock` 或 `disabled` |
| `model.timeout-seconds` | `30` | 1 到 300 |
| `diagnostics.enabled` | `true` | 布尔值 |

## 伙伴命令

伙伴命令由服务端执行，并按执行玩家 UUID 查找归属：

```text
/mf companion create [name]
/mf companion summon
/mf companion dismiss
/mf companion status
```

每位玩家最多一个伙伴。名称必须以 `MF_` 开头，长度为 1 至 16 个 ASCII 字母、数字或下划线；默认名称为 `MF_<玩家名>`。创建是幂等的；重复创建保留原伙伴 UUID 和名称。伙伴 UUID 与主人 UUID 独立，`MF_` 标识不会使用真人档案或认证账号。

主人断线或服务端关闭时，活动伙伴会保存并暂时卸载；主人再次加入后自动恢复。`dismiss` 将活动状态持久化为暂离，需由主人再次 `summon`。背包、生命、维度和位置由原版玩家数据存储；伙伴归属、名字、稳定 UUID 和活动状态由世界 `data/minecraft_friends_companions.dat` 保存。死亡遵循世界 `keepInventory` gamerule，并通过原版玩家重生流程恢复，不额外复制背包。自动 GameTest 已验证死亡后重生且背包没有重复恢复；GameTest 对掉落实体查询不稳定，默认 `keepInventory=false` 下的实际掉落数仍待普通游戏场景核验。

状态与命令仅供主人使用；当前没有聊天寻址、移动 AI 或伙伴自主动作。服务端玩家实体没有真实客户端，无法收到客户端 UI、皮肤或资源包回执；连接适配显式丢弃发往客户端的数据包。

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
