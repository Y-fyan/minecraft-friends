# 玩家控制验证记录（#1）

状态：**已完成第一轮实测，结论为“提议”，待审查**。本文记录 1.21.1 上两条路线的可复现实验，以及尚未验证的部分。

- 关联任务：[#1](https://github.com/Y-fyan/minecraft-friends/issues/1)
- 对应决策：[ADR 0001](../decisions/0001-player-control-route.md)
- 日期：2026-10-25
- 环境：Linux 7.0.0-30-generic x86_64，Java 21.0.11，Minecraft Java 1.21.1，独立测试世界（每次从空世界开始）

## 1. 对照的两条路线

| 路线 | 实现方式 | 本次验证对象 |
| --- | --- | --- |
| A. 服务端玩家实体 | 在服务端直接创建 `ServerPlayer`，配一个内存连接（`EmbeddedChannel`），不经过登录与认证；每 tick 主动驱动玩家逻辑 | 自写探针模组，Fabric 与 Forge 各一份 |
| B. 真实客户端/协议机器人 | 独立进程以普通客户端身份连接，完成登录握手后由机器人驱动输入 | mineflayer 4.39.0（协议机器人），单机/专服均测 |

路线 B 使用离线模式连接本机测试服。**没有使用任何真实账号，也没有尝试绕过 online-mode。**

## 2. 环境与版本

| 组件 | 版本 |
| --- | --- |
| Minecraft | 1.21.1（vanilla 服务端、Fabric 服务端、Forge 服务端各一份） |
| Fabric Loader / API | 0.20.1 / 0.116.17+1.21.1 |
| Forge | 1.21.1-52.1.20 |
| 构建 | Fabric Loom 1.10.5 + Gradle 8.13；Forge 侧以 javac 直接编译（无 ForgeGradle） |
| 协议机器人 | Node.js 24.19.0，mineflayer 4.39.0 |
| 服务端配置 | `online-mode=false`（另有 `online-mode=true` 对照）、`view-distance=4`、`simulation-distance=4`、RCON 本地连接 |

## 3. 验证装置（复现所需的关键点）

两条路线都不是“调一个 API 就行”，以下三点是能跑通的前提：

1. **内存连接**：`ServerPlayer` 必须有非空 `connection`。用 Netty `EmbeddedChannel` 承载管线，把连接对象加入管线，服务端写入的包被丢弃而不是发往 socket。
2. **主动 tick**：真实玩家由 `ServerPlayNetworkHandler#tick` 调用 `playerTick()`/`doTick()`。没有网络处理器时玩家不会物理移动、不会拾取、不会自然回血，必须每 tick 手动调用，并定期更新区块位置。
3. **死亡后重生**：仅调用 `PlayerManager#respawnPlayer` 得到的新玩家仍可能保持 0 血，必须手动补满血量；真实客户端会自己发重生确认包，无客户端时这一步不存在。

Fabric 侧额外把出站协议切到 PLAY（`transitionOutbound(PlayStateFactories.S2C…)`），否则登录后的包全部写入失败。Forge 侧使用 `Connection#setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE…)`。

探针源码未提交（属一次性验证装置，且工程任务 #2 尚未建立构建）。复现命令与关键实现见文末。

## 4. 结果总表

| 验收项 | A. Fabric 玩家实体 | A. Forge 玩家实体 | B. 协议机器人（Fabric 服） | B. 协议机器人（Forge 服） |
| --- | --- | --- | --- | --- |
| 加入 | 成功，36–62 ms | 成功，38–41 ms | 成功 | 成功 |
| 玩家列表 `/list` | 出现 | 出现 | 出现 | 出现 |
| 真实客户端标签页可见 | 是（Bot1 的 `players` 含 Fake1） | 是 | — | — |
| 移动 | 冲量后 0.93 格/2 s | 0.94 格/2 s | 6.47 格/1.5 s | 6.47 格/1.5 s |
| 背包 | 写入、读取、重进保留 7 个原木 | 同左 | 同左 | 同左 |
| 挖掘 | 石头→空气（无镐不掉落）；草方块→空气并拾取泥土 | 草方块→空气并拾取 | 草方块→空气（服务端确认）并拾取 | 同左 |
| 受伤 | `damage()` 20→15，随后自然回复 | 同左 | `/damage` 20→16.5 | 同左 |
| 死亡 | 死亡、实体移除、掉落 | 同左 | 客户端自动重生，血量 20 | 同左 |
| 重新加入 | 移除后重建，背包保留 | 同左 | 断线重连，背包保留 | 同左 |
| 服务端发包 | 保活包与聊天包写入不抛错 | 同左 | — | — |
| 登录类事件 | Fabric `ServerPlayConnectionEvents.JOIN` 触发 | Forge `PlayerLoggedInEvent` 触发 | 触发 | 触发 |
| 皮肤 | 无（profile 无 `textures`） | 无 | 无（离线模式） | 无 |
| 地址/IP | `embedded` / `<unknown>` | 同左 | 正常回环地址 | 同左 |

## 5. 关键结论与证据

### 5.1 玩家实体 != 真人账号

- 在 `online-mode=true` 的 Fabric 服上，服务端玩家实体照样生成并出现在 `/list`；同一时间离线协议机器人被踢，原因为 `multiplayer.disconnect.unverified_username`。
  说明：服务端实体**完全不经过认证**，既不是“登录过的玩家”，也不受 online-mode 约束；而真实客户端受认证强制。
- 服务端实体的游戏档案属性为空（`profileProperties=0`），UUID 由离线算法生成；它能被 `/list` 与真实客户端标签页看到，但没有皮肤。
- 因此两条路线不能混为一谈：A 是“有玩家外观与生存规则的服务端实体”，B 才是“真人账号登录的完整客户端”。

### 5.2 玩家列表与身份

- 服务端实体在 `/list` 与客户端标签页中均可见，`gamemode` 正确显示为生存，`ping=0`。
- 因为不经过认证，使用与真实玩家相同的名字会与正版账号的 UUID 不一致，存在同名冲突与冒用风险；实现必须使用保留命名前缀与独立 UUID，不复用他人身份。

### 5.3 移动、背包、挖掘、受伤、死亡、重进

- 移动：无客户端时服务端不接收位置包，位置完全由服务端驱动；本次以速度冲量 + 每 tick 驱动验证，位置变化并受地形/摩擦影响（0.93 格/2 s）。真实客户端则按输入移动（6.47 格/1.5 s）。
- 背包：写入、重进后保留（7→7），未观察到复制；死亡会按生存规则掉落，重生后为空。
- 挖掘：走 `ServerPlayerGameMode#destroyBlock` 等真实路径，方块在服务端变为空气，掉落物被实体拾取；无合适工具时不掉落（石头无镐）。
- 受伤：直接调用伤害接口有效；协议机器人用 `/damage` 验证时，**加入后 60 tick（3 秒）内处于登录无敌**，等待后 20→16.5。
- 死亡：服务端实体死亡后实体被移除、物品掉落；重生需要实现方手动补完（见 §3）。
- 重进：移除时保存、重建时加载，背包与血量正常恢复；本轮未发现复制。

### 5.4 模组兼容性

- 两条路线都会触发登录类事件（Fabric `ServerPlayConnectionEvents.JOIN`、Forge `PlayerLoggedInEvent`），因此只在登录事件里做初始化的模组可以工作。
- 服务端实体的 `getRemoteAddress()` 返回 `embedded`、`getIpAddress()` 返回 `<unknown>`；直接对地址做字符串/类型假设的模组会得到非预期值（未观察到崩溃，但需要适配）。
- 加入时会向内存连接写入 22–46 个包，全部被丢弃。任何依赖客户端回执的流程（资源包、配置同步、GUI 交互）不会有响应；这是 A 路线最主要的功能性缺口。
- 协议机器人在 Fabric/Forge 服上都能以原版协议加入（服务端未安装要求客户端模组的模组）。**未验证**：要求客户端安装对应模组、依赖自定义通道握手的模组。
- 协议库自身限制：mineflayer 4.39.0 在 1.21.1 上反复出现 `PartialReadError`（注册表解析长度不符），未中断流程但说明协议机器人需要跟随版本维护。

### 5.5 未验证项（不得当作已通过）

1. 皮肤：需要解析真实档案属性；未使用任何真实账号，故未验证。
2. 单机内置服务器（集成服）四格中的两格；本轮只测了专用服务器。
3. 要求客户端模组/自定义通道的模组组合。
4. 真实微软账号登录流程与费用；未获取、未使用真实账号。
5. 长时间运行的连接队列增长与内存占用：内存连接若不持续丢弃包会累积，需在实现中处理（本轮未做长跑）。
6. 多玩家并发下的会话隔离（属 #6/#11 验收范围）。

## 6. 复现步骤（摘要）

1. 准备 1.21.1 的 Fabric 0.20.1 与 Forge 52.1.20 专用服务器，独立世界，`online-mode=false`，开启 RCON。
2. 安装探针模组（Fabric 版依赖 Fabric API；Forge 版为单 jar）。
3. 控制台执行：`probe spawn Fake1`、`probe list`、`probe info Fake1`、`probe give Fake1 minecraft:oak_log 7`、`probe move Fake1 0.3 -0.2`、`probe mine Fake1`、`probe damage Fake1 5`、`probe kill Fake1`、`probe respawn Fake1`、`probe remove Fake1`，再 `probe spawn Fake1` 检查持久化。
4. 协议机器人：`mineflayer.createBot({version:'1.21.1', auth:'offline'})`，依次验证加入、`/list`、移动、`/give`、挖掘、重连、`/damage`、`/kill`。
5. `online-mode=true` 对照：重复步骤 3，并用离线机器人连接确认被拒。

## 7. 结论

在受控服务器（客户端/服务端可装模组）里，**服务端玩家实体可以满足 MVP 对身份、背包、移动、挖掘、受伤、死亡与重进的要求**，且不需要账号、不需要额外进程。代价是两个加载器都要自建连接垫片与 tick 驱动，并且没有客户端回执、皮肤和 GUI。真实账号客户端机器人是唯一能进入纯净服的路线，但成本与维护面显著更高，作为后续可选适配。
