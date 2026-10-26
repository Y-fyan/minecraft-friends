# M1 正式产物的隔离客户端验收助手

这是测试专用模组，不安装到真实存档。先从根目录运行 `./gradlew checkAll`，再用已有 Java 21 和显示环境运行本助手。Fabric 通过 Loom 重映射正式发行 JAR；Forge 从正式 JAR 编译接口，并把同一个 JAR 放入隔离运行目录。助手不复制生产源码或自行实现伙伴规则。

```bash
experiments/player-control/gradlew -p experiments/m1-acceptance :fabric:build :forge:build
python3 experiments/m1-acceptance/run_acceptance.py fabric-integrated
python3 experiments/m1-acceptance/run_acceptance.py forge-integrated
```

IntegratedServer 场景用真实开发客户端新建平坦世界，经原版网络连接发送 `/mf companion create/dismiss/summon`；测试助手只设置临时背包和伤害条件，再观察正式模组的恢复/死亡逻辑。严格检查死亡掉落正好 4 个原木、保留物品时背包 4 个且地上 0 个。关闭并重新打开同一个新建世界后，UUID、背包和生命必须恢复。

普通 DedicatedServer 场景需先准备单独的本地测试服务器，放入对应正式 JAR、本助手构建 JAR（Fabric 另需 Fabric API），使用独立测试世界并仅绑定 loopback。只有使用者本人接受 Minecraft EULA 后才能启动；助手不代用户接受。给服务端 Java 加 `-Dmf.acceptance=true`，然后运行：

```bash
python3 experiments/m1-acceptance/run_acceptance.py fabric-dedicated --server 127.0.0.1:25575
python3 experiments/m1-acceptance/run_acceptance.py forge-dedicated --server 127.0.0.1:25576
```

客户端仅允许连接显式传入的 loopback 地址。运行器只管理自己创建的客户端进程组；超时/中断仍记失败，不把关闭客户端的退出码 0 单独作为通过。专服启动和 `stop` 退出由启动者单独记录。它不安装软件、不改系统 Java 或显卡驱动，软件渲染仅作用于本次子进程。

结果位于实验 `build/evidence/<目标>-<时间>/`：完整本地日志及包含源码提交、正式 JAR/日志哈希、实际服务器类、必需断言和客户端结束标志的 JSON。只有完整证据与退出状态同时满足才通过。未运行的目标不能标为通过；不得将自动开发客户端流程称为真人人工试玩。原助手已覆盖生命周期；新增聊天/记忆流程见下文，尚未实机运行。

若 ForgeGradle 构建环境的 HTTPS 预检连接卡住，可在确认原因后为本次运行显式添加 `--skip-forge-build-cert-probe`；它只传入一次性的构建预检参数，不修改全局 TLS、依赖下载校验或模型 HTTP 客户端。使用该参数时在验证记录中说明，不能隐藏失败的第一次运行。

## 聊天/记忆协议验收（新流程，尚未实机运行）

默认运行器现在在同一组严格生命周期断言之后，继续单真实客户端路径：

1. 服务端观察公开 `ChatRuntime.status(server, ownerUUID)`，确认正式 Provider 为 `chat-completions`、世界与伙伴身份有效、记忆已加载。
2. 客户端通过实际网络命令关闭主动问候、设置称呼“验收主人”、保存 `MF_PRIVATE_FACT`，随后通过 `/mf chat` 与普通聊天 `@MF_M1Bot ...` 发言。
3. loopback fixture 检查**系统消息**中的称呼/长期事实注入，返回带随机 nonce 的简短中文回复，并明确写“协议模拟，非真实模型”。只有客户端实际收到精确匹配的伙伴回复才发送 `mfacceptance ack <nonce>`；服务端只接受相同主人真实命令源 UUID 与当前 nonce，不接受控制台或其他玩家 ACK。
4. IntegratedServer 关闭网络连接、退出世界并重新打开同一新建世界；再次核对原生命周期 UUID/生命/背包，以及独立 `data/minecraft_friends_chat_world.uuid` 对应的公开世界 UUID、称呼和记忆条数。再发一次私聊，fixture 重新核对提示注入。
5. 经客户端执行 `/mf memory clear`，等待公开 generation 递增、称呼为空和长期事实为 0；再请求回复，fixture 检查整份上下文中无旧称呼、长期事实或本次私聊历史标记。回复到达客户端并 ACK 后才完成。

```bash
# 正式 JAR 需由父代理在当前源码上先构建到根 build/artifacts。
JAVA_HOME=/已有/JDK21 python3 experiments/m1-acceptance/run_acceptance.py fabric-integrated --offline
JAVA_HOME=/已有/JDK21 python3 experiments/m1-acceptance/run_acceptance.py forge-integrated --offline
# 原生命周期流程继续可用：
JAVA_HOME=/已有/JDK21 python3 experiments/m1-acceptance/run_acceptance.py fabric-integrated --lifecycle-only --offline
```

完整流程仅给实验目录 `fabric|forge/build/run/client/config/minecraft-friends.properties` 临时写入显式 loopback endpoint、`model.name=acceptance-protocol-simulation`、空 `model.api-key-env` 和 `model.allow-unauthenticated-local=true`；结束时恢复该隔离客户端原配置。生命周期模式临时指定离线 mock。不会搜索凭据、写真实配置目录或自动调用真实模型。可选 `--username` 是离线测试身份覆盖，默认保留已有开发客户端身份；尚未实机验证覆盖参数。

### DedicatedServer 独立 fixture

运行器不启动、重启、停止或配置专服。专服须使用独立新测试世界和开发离线身份（`online-mode=false`），仅绑定 loopback，不使用真实账号。先为已准备的独立 loopback 测试专服创建协议 fixture：

```bash
python3 experiments/m1-acceptance/protocol_stub.py --fixture
```

命令只在 `127.0.0.1` 随机端口监听，打印本次独立证据目录中的 `isolated-server-model.properties` 和 `protocol-summary.json` 路径。由操作者将**这份无凭据模型配置片段**用于已准备的隔离专服，并给专服 Java 加 `-Dmf.acceptance=true`。正式模组和验收助手 JAR 的安装、专服实际 JAR 哈希、EULA、启动与退出由父代理独立记录。fixture 命令仅用 Ctrl-C 关闭自己的监听器，不控制 Minecraft。

```bash
JAVA_HOME=/已有/JDK21 python3 experiments/m1-acceptance/run_acceptance.py fabric-dedicated \
  --server 127.0.0.1:25575 --fixture-summary /本次fixture目录/protocol-summary.json --offline
```

Dedicated 新流程实际断开并重新连接同一专服；**不等于专服重启后磁盘恢复**。专服 `--lifecycle-only` 还需要在其 Java 进程加 `-Dmf.acceptance.lifecycleOnly=true`，并明确配置离线 mock；客户端运行器不会改服务器目录。

### 证据、检查与缺项

- 每次使用唯一证据目录，启动前记录源码 commit、dirty diff SHA、助手源码集合 SHA 和根正式 JAR SHA；运行期间每 0.5 秒及结束时复核正式 JAR，结束时也复核源码/助手。中途变化、缺任何服务端断言、无客户端完整结束标记、fixture 的 PRIVATE/PREFIX/REJOIN/AFTER_CLEAR 调用各不恰好一次、失败计数增加或进程超时均不能通过。源码 commit 表示启动时 checkout，不能替代 JAR 构建来源证明；最终产物由父代理从最终源码重建。
- fixture 接受有界 UTF-8 JSON（128 KiB、64 条消息、16,000 字符），只保存标签计数、布尔元数据与固定错误类别，不保存完整请求、聊天正文、nonce 或凭据。任何 Authorization 头均拒绝。`SHARED` 的隐私断言仅供后续助手使用，目前没有双客户端场景，不能据此声称共享验收完成。
- 当前范围是生命周期 + 单客户端中文私聊/前缀 + 称呼/事实持久化与清空的**协议模拟**。`summary.json` 始终记录 `m1_complete=false`，列出真实模型、双玩家隔离/共享邀请退出、三类真实主动事件与冷却、memory list/delete 客户端路径、DedicatedServer 重启恢复等缺项。此助手主动关闭问候以隔离主路径，不能用 unmarked fixture 调用冒充真实事件证据。
- 新助手未启动任何 Minecraft 客户端、IntegratedServer 或 DedicatedServer，也未启动 HTTP fixture 做网络测试。旧生命周期实机通过的证据由父树保留，未复制或覆盖。

无图形的验证入口：

```bash
python3 -m py_compile experiments/m1-acceptance/run_acceptance.py experiments/m1-acceptance/protocol_stub.py experiments/m1-acceptance/test_protocol_stub.py
python3 -m unittest discover -s experiments/m1-acceptance -p test_protocol_stub.py
JAVA_HOME=/已有/JDK21 experiments/player-control/gradlew -p experiments/m1-acceptance \
  :fabric:compileJava :forge:compileJava --offline --no-daemon
```

本次编译前按父代理授权，将父树 `m1-integration/build/artifacts` 的两份正式 JAR 显式复制到本树 `build/artifacts`，仅作为编译输入；哈希记录于本树实验 `build/evidence/compile-inputs-*.json`。没有修改父树或生产源码。最终运行由父代理从最终源码重新构建正式产物。公开状态 API 使用反射桥，既让旧正式 JAR 可继续编译/运行 `--lifecycle-only`，也使新 API 缺失或不符时完整流程明确失败；编译通过不代表新聊天 API 已实机通过。

本次静态验证记录：`py_compile` 通过；4 个纯验证器 unittest 通过；Fabric/Forge `compileJava` 通过，额外带 `acceptanceLifecycleOnly=true`、`acceptanceUsername=MF_AcceptOwner` 的工程配置检查通过。编译输入仅为从父树复制的正式 JAR：Fabric SHA-256 `60708c5c3a0d20466484849cc316fa19905e90925b019f21f1b9359967eeb0db`，Forge `a2c04632d9632c2d97fae50e20250b0cd3a4048145cf73d6caf178db447d5150`。新游戏流程、HTTP socket fixture 和可选 username 的实际启动均未运行；不能把这些静态检查当成游戏验收。
