# M1 实际事件与记忆 list/delete 验收装置

本装置只供隔离验收使用，不进入正式模组。它要求真实 Minecraft 客户端和正式 Fabric/Forge JAR；默认在同一客户端中创建 IntegratedServer 与新平坦测试世界。loopback HTTP 服务仅模拟协议，回复会明确标出“协议模拟，非真实模型”。运行器不查找或使用账号、密钥或真实模型服务。

## 加载器接线

父代理在 Fabric/Forge 验收助手 entrypoint 中按 `Boolean.getBoolean("mf.acceptance.events")` 选择本场景：调用 `EventsScenario.registerCommands(dispatcher)`、`EventsScenario.tick(server)` 和 `EventsScenario.remove(server)`；客户端事件模式调用 `EventsClient.tick(Minecraft.getInstance())` 与 `EventsClient.message(text)`。Fabric 游戏消息回调和 Forge 1.21.1 `SystemMessageReceivedEvent` 都须转发到同一个 message 方法。未设置事件属性时沿用原验收场景。

运行器以 `-PacceptanceEvents=true` 启动开发客户端，Fabric/Forge Gradle run 配置需把它映射为 JVM 属性 `mf.acceptance.events=true`。Fabric `runAcceptance` 与 Forge `runClient` 仍安装同一根目录正式 JAR；本装置不编译或复制生产实现。

## 运行

先由父代理构建并放置对应根目录正式发行 JAR，再运行：

```bash
JAVA_HOME=/tmp/codex-mf-jdk21-213 python3 experiments/m1-acceptance/run_events.py fabric-integrated --offline
JAVA_HOME=/tmp/codex-mf-jdk21-213 python3 experiments/m1-acceptance/run_events.py forge-integrated --offline
python3 experiments/m1-acceptance/events_fixture.py --self-test
```

只使用仓库现有 Wrapper 和指定便携 JDK 21；运行器不安装依赖。专用 JAR、源码、验收助手在运行期间会做哈希复核。运行超时、缺少任一服务端断言、客户端真实收到回复后的 ACK、协议分类计数错误、任何 fixture 错误或哈希变化都失败。

运行器会暂时为实验客户端目录写入无凭据 loopback 配置，结束后还原原文件。`checks.log` 只保留固定的 `MF_EVENTS_*` 断言行，不保存完整客户端日志或聊天内容；fixture 的摘要只写固定事件标签、数量、布尔式错误类别与 `request_body_stored=false`。

## 关键断言

- 新世界创建伙伴前，公开 `ChatRuntime.status(server, ownerUUID)` 必须显示 chat-completions Provider 与默认 `proactiveEnabled=true`。`/mf companion create` 由真实客户端命令触发生产入口；首次回应需经客户端收到完整标记回复后 ACK。
- 三个事件都由游戏路径触发：首次创建、伙伴真实非致死 `genericKill` 伤害，以及退出并重新打开同一世界。受伤阶段先施加一次真实伤害并收到回应，再在冷却内第二次造成真实伤害；之后关闭主动问候再造成第三次真实伤害。状态与伙伴生命会检查开关、存活和伤害结果，fixture 最终必须恰好收到一条 `INJURED` 请求。场景没有直接调用 `ChatRuntime.onLogin`、`onCompanionAvailable` 或 `onCompanionInjured`，也不会伪造任务完成。
- fixture 仅当本轮最后一条消息本身是 SYSTEM，且**最后一条 SYSTEM 消息**精确匹配当前 FIRST_MEETING、REJOIN 或 INJURED 提示时才分类事件；普通用户轮次末尾残留的历史事件、长期记忆中的事件文本都不能充数。每次 HTTP 正文最多 65,536 字节、48 条消息、每条 4,096 字符且合计 16,000 字符；拒绝 Authorization 头、无标记调用和无法识别的上下文。正文、完整对话与凭据均不落盘。
- 客户端先发 `/mf memory remember MF_KEEP_FACT` 和 `MF_DELETE_FACT`，再执行真实 `/mf memory list`。客户端逐行解析正式命令返回的 UUID、类型和固定测试事实标记，证明对应 UUID 来自本次列表；列表中还须出现首次见面、受伤和重登三条事件记忆。随后客户端用该 UUID 执行 `/mf memory delete <UUID>`，服务端核对 generation 递增且条数减少，再次经客户端 list 验证删除事实消失、保留事实 UUID 不变、三个已验证事件仍在。
- 删除后再发带唯一 nonce 的专用聊天探针。fixture 检查当前模型上下文含保留事实和三条事件记忆、完全不含已删除事实；只有客户端收到实际伙伴回复并回传 owner UUID 对应的 nonce ACK，场景才报告成功。

IntegratedServer 客户端重开覆盖世界退出/再登录路径，不表示 DedicatedServer 进程重启恢复，也不表示真实模型、双玩家隔离或人工试玩已经验收。结果由父代理运行后填报；未运行的加载器不能标为通过。

## 本次实现的离线静态检查

使用便携 JDK `21.0.13` 和仓库现有 Gradle Wrapper 8.8 执行 `:fabric:compileJava :forge:compileJava --offline --no-daemon`，未运行客户端、HTTP listener 或 Minecraft server。为编译验收 helper，从父工作树 `/home/mdfz/.codex/worktrees/m1-integration/minecraft-friends/build/artifacts` 显式复制了两份正式 JAR 到本树忽略的 `build/artifacts/`；它们只作为编译输入，不是本分支重建的产物：

| 编译输入 | SHA-256 |
| --- | --- |
| Fabric 1.21.1 JAR | `e56d8c67696975e48d2f8ca440b7ccf48b01be70ee1a7aff7b0bd50b9c4d8906` |
| Forge 1.21.1 JAR | `f61cdd666a059c34ad2fd6c5a7d551e8457e59817e944a441ffa33c7a5d855c5` |

`events_fixture.py --self-test` 的 4 项纯 fixture 断言和两侧 `compileJava` 已通过。最终正式 JAR 重建、entrypoint 接线后的编译与游戏运行由父代理执行；本次静态结果不代表事件或 list/delete 已实机验收。
