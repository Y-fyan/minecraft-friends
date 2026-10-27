# 动作 API 与命令契约

此文件冻结 M2 的生产命令名称，供 Fabric、Forge 和服务器验收工具共同使用。命令由服务器端验证；模型文本本身不构成执行授权。规划或编译成功均不代表动作已经执行。

## 命令

| 命令 | 用途 |
| --- | --- |
| `/mf task follow` | 让当前主人的伙伴以正常实体移动跟随主人。 |
| `/mf task wait` | 停止当前动作并等待；清除该任务留下的移动和交互输入。 |
| `/mf task stop` | 优先取消当前任务并清除输入。 |
| `/mf task status` | 返回实际动作和独立规划状态。 |
| `/mf task plan <自然语言>` | 显式请求现有 Provider 生成白名单动作；复验通过后才提交执行。 |
| `/mf task clear` | 等同 stop：取消动作、规划和一次性区域授权。 |
| `/mf task area x1 y1 z1 x2 y2 z2` | 主人明确选定一次采集区域；设置本身不改变方块。 |
| `/mf task collect oak_log 8` | 在已选区域内合法采集并实际拾取/交付 8 个橡木原木。 |

上述生产命令名和参数顺序在 M2 内保持稳定。仅在线主人可执行动作命令；采集会逐次检查主人、伙伴与区域维度、距离、区块加载和目标方块。采集遵守生存模式挖掘进度与正常掉落/拾取，不生成物品、不直接清空方块。区域必须显式选定且有边界；矿物、箱子及其他方块不作为默认破坏目标。

## 只读状态 API

公共 Minecraft 运行时入口为：

```java
ActionRuntime.status(MinecraftServer server, UUID ownerId)
```

返回不可变 `ActionStatus` 快照，不创建任务、不推进游戏、不修改世界。状态包括 task ID、类型、生命周期状态、已完成数、请求数、已交付数和结果说明；无当前任务时返回 `IDLE` 快照。调用方只使用服务器线程读取快照。

生命周期入口统一由 `CompanionRuntime` 转发到 `ActionRuntime`：服务器启动、每 tick、停止、主人离线和伙伴死亡。任务 ID 去重；`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`TIMED_OUT` 与 `CANCELLED` 为终态/运行状态，取消或生命周期失效后清空任务输入。停止在同一 tick 内优先于普通动作。

感知只向任务提供有界、服务端可见的世界快照。Follow 每个服务器动作 tick 调用一次 `ServerPlayer.travel(Vec3)`，走 vanilla `Player/LivingEntity.travel` 的移动、碰撞和重力；台阶跳跃调用 vanilla 跳跃冲量。它不把 `setPlayerInput` 当作普通 fake player 控制接口，也不改坐标。A* 每次最多扩展 512 个节点，每 tick 最多处理 8 个主人、4 次路径搜索和 2 次区域扫描，轮转处理避免多人持续饥饿；路径只进入已加载地表/台阶。全局 `enabled=false` 时命令拒绝动作，tick 也取消任务并清空输入。该 API 不承诺模型规划：如果没有真实、白名单结构化规划结果，解析到的动作只能来自显式命令或确定性规则。

本契约描述 M2 目标接口；实现、编译、单测和 Fabric/Forge 运行验收分别记录，未执行的项目不能标记为已验证。

主人明确寻址伙伴的私聊中，精确短语“跟着我”“在这里等我”“停下来”分别走确定性跟随、等待、停止；“帮我收集8个橡木原木并交给我”要求先用 `/mf task area` 授权一次性区域。其他对话保持原聊天流程。停止不等待模型。

## 显式结构化规划

只有 `/mf task plan <自然语言>` 启动模型规划。普通聊天的模型回答绝不被解释为动作；上文明确寻址的固定中文短语仍是确定性解析，不宣称经过模型。示例：先执行 `/mf task area 0 64 0 4 68 4`，再执行 `/mf task plan 帮我收集8个橡木原木并交给我`。模型返回的 JSON 不能授予区域权限，不能写入任务成功状态。

功能默认可用，复用现有配置的 Provider 与有界后台工作池，没有新增默认关闭开关。全局 `enabled=false`、聊天/Provider 被关闭、世界身份数据失效时均拒绝规划。沿用 `requestTimeoutSeconds`（规划最多 30 秒）与 `modelMaxContextCharacters`（完整规划提示最多 16000 字符）；上下文预算过小则明确拒绝。每位主人每 20 tick 最多发起一次，服务器最多同时 16 个规划；每个请求最多 512 字符。规划取代该主人正在执行的动作并清除输入；合法的一次性区域授权保留至实际采集提交时消费。

服务端在请求时捕获不可变 `ActionPerception`：世界运行会话 UUID、主人/伙伴 UUID、维度、tick、双方位置/生命、伙伴最多 41 个库存槽的物品 ID/数量，以及已选区域。围绕伙伴只检查 x/z ±3、y ±2 的 245 个位置，每次读取前检查区块已加载及高度；最多保留 64 个非空气方块 ID/坐标以限制提示长度。不加载区块、不扫描箱子内容、不发送 NBT、其他玩家背包或聊天历史。未观测位置不能推断为可通行或可破坏。

后台通过现有 `ModelProvider.complete` 获取响应，使用 `ActionPlanParser` 严格解析一个最多 1024 字符的对象；仅允许 `follow`、`wait`、`stop`、`collect_oak_logs`（count 为 1–64 整数）。额外字段、代码、成功声明、数组/null、非整数、溢出数字等均失败。通过 `server.execute` 回到服务器线程后重新检查请求代际、服务器世界会话、同一在线主人/伙伴实例、伙伴归属、生命、维度、48 格距离、配置和最多 600 tick 快照年龄；采集额外检查当前授权区域完全一致、有效期、边界、已加载和双方 32 格距离。执行器继续逐次复核采集权限。规划 request ID 直接作为提交的任务 ID，复用任务去重。

`stop`、`clear`、直接 follow/wait/collect、新规划、主人离线、伙伴死亡、tick 检出的死亡/维度变化或距离失效，以及聊天 clear、Provider/服务器关闭均使旧规划失效；修改区域也使旧规划失效。取消先移除有效令牌，再尽力取消 Provider future；即使 Provider 忽略取消或回包已排入服务器队列，也不能恢复旧动作。停止命令直接在服务器线程处理，不等待模型。超时由服务器 tick 检查，不创建额外计时线程。

只读 API（均在服务器线程调用）：

```java
ActionRuntime.planningStatus(server, ownerId); // ActionPlanner.Status
ActionRuntime.perception(server, ownerId);     // Optional<ActionPerception>；不可用时为空
```

规划状态仅有 `IDLE / PLANNING / DISPATCHED / FAILED / CANCELLED / TIMED_OUT`。`DISPATCHED` 表示已提交动作，实际成功/数量仍只由 `ActionRuntime.status` 返回。规划失败返回固定原因（例如 `BAD_JSON / UNAUTHORIZED / STALE_CONTEXT / PROVIDER_FAILED`），不会把原始模型输出当作消息或指令执行。默认离线 `MockModelProvider` 返回普通演示文本，因此显式规划会安全反馈 `BAD_JSON`；它不伪造规划或采集成功。单测使用专门的受控 Provider 返回 JSON，未调用真实远端服务，也不需要凭据。

## 验证边界

类型化调度、正常移动、一次性区域授权、采集执行器与上述规划链已接入。规划核心单测覆盖工作池/主线程分离、严格坏 JSON、真实离线 mock、安全拒绝无授权采集、服务器上下文失效、无视取消的迟到 Provider、回包已排队后取消、请求代际、超时、关闭和队列/提示预算。common 单测与 Fabric/Forge Java 编译通过；这些不能替代真实客户端、世界方块和实际库存验收。

父验收树已独立获得 Fabric/Forge navigation smoke 通过和 Fabric 不可达有界失败证据。本树未重做游戏验收，也未同步父树的拾取修复。采集成功、完整 #9/#11 矩阵、游戏内规划/生命周期回归仍由父继续验收；死亡重生后伙伴是否仍具有实际拾取子类能力尚未覆盖，不能宣称 M2 已全部完成。
