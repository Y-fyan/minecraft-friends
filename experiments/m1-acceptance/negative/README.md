# M1 关闭开关与坏存档负向验收

此测试模块只用于全新、隔离的开发客户端世界。两个场景都经 IntegratedServer 和真实客户端网络命令运行；服务端 helper 只通过公开 `ChatRuntime.status(MinecraftServer, UUID)` 反射读取状态，不以单元 API 调用代替客户端反馈。

`NegativeScenario` 提供父加载器 helper 需要统一接线的入口。Runner 的 `-PacceptanceNegativeRunDir` 必须同时映射为启动属性 `mf.acceptance.negative.runDir`；客户端与服务端都会核对实际 game/world 路径确实位于新建的隔离目录，路径不符时不会创建测试世界或写入样本。

- `prepare(server)`：在 `SERVER_STARTING` 写入前检查世界名以 `mf-negative-` 开头，并确认 `data/minecraft_friends_companions.dat` 不存在。`CORRUPT` 场景仅在该目标不存在时以不覆盖方式写入当前 `DataVersion`、`data.schemaVersion=999` 的 NBT 样本。
- `registerCommands(dispatcher)`、`tick(server)`：发送带 nonce 的实际客户端命令请求；只接受世界中选定离线玩家 UUID 发出的对应 ACK。损坏数据场景不调用 `CompanionRuntime.data()` 来筛选玩家。
- `remove(server)`：在世界停止后再次比较坏存档样本的 SHA-256。
- `NegativeClient.tick(Minecraft)`、`message(String)`：由加载器客户端 tick 与系统消息事件调用。客户端先建一个随机 `mf-negative-<UUID>` 平坦世界，通过连接发送正式 `/mf` 命令；只有收到预期系统消息后才发 nonce ACK。

Fabric 与 Forge helper 将上述服务端入口分别挂到 `SERVER_STARTING`、命令注册、server tick 和 `SERVER_STOPPED`；客户端入口挂到实际 client tick 和 system-message 接收事件。Forge 系统反馈使用 `SystemMessageReceivedEvent`。需要通过系统属性 `mf.acceptance.negative=true` 启用，场景为 `mf.acceptance.negative.scenario=DISABLED|CORRUPT`，离线测试名为 `MF_NegativeOwner`。

父代理接好 helper 并先构建正式 JAR 后，分别运行：

```bash
JAVA_HOME=/tmp/codex-mf-jdk21-213 python3 experiments/m1-acceptance/negative/run_negative.py fabric-disabled
JAVA_HOME=/tmp/codex-mf-jdk21-213 python3 experiments/m1-acceptance/negative/run_negative.py forge-disabled
JAVA_HOME=/tmp/codex-mf-jdk21-213 python3 experiments/m1-acceptance/negative/run_negative.py fabric-corrupt
JAVA_HOME=/tmp/codex-mf-jdk21-213 python3 experiments/m1-acceptance/negative/run_negative.py forge-corrupt
```

Runner 只使用 `experiments/player-control/gradlew` 和指定便携 JDK，Gradle 离线运行。每次新建 `negative/build/run/<target>-<时间>` 客户端目录和独立证据目录，配置写入该目录后删除；fixture 仅绑定 `127.0.0.1`，拒绝请求并只统计 TCP 连接数，不保存请求头、正文、凭据或聊天。Runner 仅管理自己启动的进程组。

证据检查启动 commit、源码 diff 哈希、helper 源码哈希、正式 JAR 哈希与运行后完整性；还要求 IntegratedServer 实际类名/角色、所有必需服务端断言、按原顺序验证的玩家 UUID/nonce ACK、真实客户端完成标记及 fixture 零连接。`CORRUPT` 还在进程退出后重新读取坏 `.dat` 并对比哈希。缺任一断言、超时、异常停止、文件变化或 fixture 被访问都判失败。fixture 只用于证明关闭路径没有发出模型 HTTP 请求，不是真实模型。

此提交仅提供 helper 源码与 runner；未启动 Minecraft、IntegratedServer 或 HTTP fixture。父代理接线、运行和独立验收之前，不构成负向游戏验收通过。
