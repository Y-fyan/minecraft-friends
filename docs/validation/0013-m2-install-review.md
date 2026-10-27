# 0013：M2 安装文档与源码独立审查（GLM）

2026-10-26，审查对象为工作树 `test/11-m2-acceptance` 提交 `beb0fb08c95b857a93d0315fa50b2c0f82ca7862`。本次执行者是 GLM（glm-5.3-flash）作为独立模型审查员，不是人类新成员试玩；没有真实服务器、单机世界或安装流程操作。

## 阻塞结论

1. **已修复文档阻塞**：原 `docs/M2-INSTALL.md` 让新成员执行 `/mf companion create Friend` 并用 `@Friend` 交互，但生产 `CompanionIdentity` 要求名称以 `MF_` 开头且长度为 1–16 个 ASCII 字母、数字或下划线。照文档操作会失败。本次已把示例改为 `MF_Friend`。
2. **不允许宣布人类新成员验收完成**：本次只完成文档、构建产物元数据和生产源码只读审查，没有实机安装、创建世界、启动真实服务器/客户端或操作真实存档。

## 安装与构建产物核对

- 工作树在审查开始时干净；本次只改 `docs/M2-INSTALL.md` 和本文件。
- `build/artifacts/` 已存在两个真实 JAR：
  - Fabric：`minecraft-friends-fabric-1.21.1-0.1.0-SNAPSHOT.jar`，SHA-256 `547b353f1b03a315c676b4f295965468c529daf2a210c1b3a5684e9723a626b4`。
  - Forge：`minecraft-friends-forge-1.21.1-0.1.0-SNAPSHOT.jar`，SHA-256 `abfd5650e0d141693c379031688b9e1f43bb4b875aa74495762a29c12992e10e`。
- JAR 内核对通过：Fabric 有 `fabric.mod.json`、主入口类和公共核心类；Forge 有 `META-INF/mods.toml`、Forge 主入口类和公共核心类；两包均带 `META-INF/LICENSE`。
- Fabric 元数据声明 Minecraft `1.21.1`、Fabric Loader `>=0.20.1`、Fabric API `0.116.17+1.21.1`、Java `>=21`；Forge 元数据声明 Forge `[52,)`、Minecraft `[1.21.1]`。与安装文档一致。
- 未把上述元数据检查当作全新构建、游戏加载或玩法验收。JAR 未嵌入可核对源码提交；因冻结要求，本轮未重跑 `checkAll`。最终实机矩阵必须用冻结提交重新构建并记录产物哈希。
- `docs/examples/minecraft-friends.properties` 与 `ConfigLoader` 默认值一致：默认启用一般功能但模型为离线 `mock`；没有端点、模型名或凭据，未认证本机服务也默认拒绝。

## 源码审查结论

重点审查动作安全、取消、调度、后台规划和生命周期边界，未发现当前应阻塞冻结实机矩阵的新生产缺陷：

- 动作在服务端主线程调度；`ActionScheduler` 限制每主人 16 个任务并保留最多 256 个结果，取消会把队列和活动任务转成 `CANCELLED`。
- `ActionRuntime` 注销、伙伴死亡、维度/距离失效、功能关闭和服务端停止会取消主人任务，并调用 `clearInputs` 中止挖掘输入；停止路径不隔空转移背包。
- 后台规划使用有界 pending、超时、过期重验和取消令牌；模型回复只进入严格 allowlist JSON 解析，服务端仍复核区域、主人/伙伴实例、维度、存活和模型访问后才启动动作。
- 采集要求一次性授权区域、生存模式、实际挖掘进度、真实掉落拾取和近距离复核后交付；失败/超时/取消按实际拾取与交付结算。
- `ActionPlan` 与命令参数把采集数量限制为 1–64；`ActionPlanParser` 使用严格 JSON、白名单动作和未知字段拒绝。

## 已知边界与后续必测

- 源码审查不能证明实机安装、启动、磁盘配置生成、双加载器游戏矩阵、性能或长期稳定性。
- 父代理应继续固定源码版本实机矩阵，并额外执行 Forge `BreakEvent` 取消场景（验收 helper 固定 `14,-60,6`，不改生产）。
- 性能代理结果应另行记录；本报告不得合并成性能结论。
- 若冻结实机矩阵重新生成 JAR，应以新哈希覆盖记录；当前哈希仅对应本轮检查到的本地产物。
