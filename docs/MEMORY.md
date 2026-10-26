# 伙伴记忆核心

## 已实现范围

`common` 中的记忆核心是纯 Java 21，不引用 Fabric、Forge、Minecraft 类型或网络客户端。游戏适配层负责在正确的服务端事件中创建 store、传入真实世界/主人/伙伴 UUID，并决定何时把快照交给模型。

每份数据按 `(world UUID, owner UUID, companion UUID)` 隔离，写入调用方提供的根目录下：

```text
<root>/<world UUID>/<owner UUID>/<companion UUID>/memory.properties
```

文件使用 UTF-8 Java properties，包含 `schema=1`、范围身份、generation、人设设置、主动事件冷却状态和长期记忆。加载时验证 schema、范围、字段、枚举、条数、重复键及未知键；格式损坏或版本不支持时抛出 `MemoryStoreFormatException`，不会初始化覆盖原文件。保存先写同目录临时文件、同步文件内容，再以文件系统原子移动替换；不支持原子移动时明确失败。持久化错误通过 `IOException` 返回，不会伪装成写入成功。

## 聊天接入 API

```java
MemoryScope scope = new MemoryScope(worldId, ownerId, companionId);
CompanionMemoryStore memory = new CompanionMemoryStore(persistenceRoot, scope);

RequestContext request = memory.beginRequest();
// 用 request.persona().systemInstructions()、request.memories()、
// request.shortTermContext() 组装有界模型上下文，并保留 request.token()。
// 模型返回后：
boolean accepted = memory.commitResponse(request.token(), userText, responseText, Instant.now());
```

`commitResponse` 只更新短期上下文；它不写长期事实。`clear()` 和 `deleteMemory(id)` 都先原子保存递增后的 generation，再清除短期上下文。删除不存在的 ID 仍会推进 generation 并使在途 token 失效，返回值为 `false`。旧 token、其他 store 实例的 token，以及范围不匹配的 token 都不能提交响应。每个 store 实例的方法同步；游戏适配层应为每个范围复用一个实例，避免不同实例并发写同一文件。

私聊提示词由纯 Java `MemoryPromptComposer` 从一次 `RequestContext` 快照组装。默认先放完整人设，再把长期记忆合并为带来源标签的行：最新的用户明确偏好优先，其后是最新的已验证游戏事件。组装使用 `chatLimits` 的上下文消息数、上下文总字符数和单条消息字符数；超长事实会在末尾标记省略号，剩余预算耗尽后跳过较低优先级事实。预算不足以容纳完整人设时使用保留 AI 身份、自主性和防操控约束的精简人设；若连精简版也放不下，则不注入私人记忆。默认预算下，短偏好与三条事件可合并在一条事实消息中。短期记忆不再重复注入，因为同一私聊请求已携带有界的会话近期历史。共享会话只走共享上下文钩子，不读取该私聊快照。

长期记忆通过两个有来源约束的入口写入：

- `rememberPreference(text, time)` 记录用户明确说出的称呼或偏好，来源固定为 `USER_EXPLICIT`。
- `recordVerifiedEvent(text, time)` 仅供游戏适配层在确认事件发生后记录，来源固定为 `VERIFIED_GAME_EVENT`。

通过 `listMemories()` 查看、`deleteMemory(id)` 单项删除或 `clear()` 清空。模型输出和推测没有长期记忆写入入口；当前核心也不提供“任务已完成”写入 API。数据在写入前限制文本长度与总条数，达到上限时明确报错，不静默丢弃旧事实。

## 上限与默认人设

| 内容 | 上限或默认值 |
| --- | --- |
| 长期记忆 | 每伙伴 64 条；每条 512 字符 |
| 单条短期上下文 | 1,200 字符；总计不超过 12 条或 6,000 字符 |
| 记忆文件 | 256 KiB |
| 人设口吻、称呼字段 | 每项 80 字符 |
| 主动事件冷却 | 默认启用；每类 10 分钟；初次见面仅允许一次，直至清空 |

`PersonaSettings.defaults()` 提供稳定中文口吻。`systemInstructions()` 明确伙伴是 AI、尊重玩家自主选择、不要求持续陪伴、不表达排他关系、不以愧疚/威胁操控玩家，并要求没有证据时不声称事件或任务已完成。可用 `setPersona` 配置口吻和主人称呼。`clear()` 会恢复默认人设。

`tryClaimProactiveEvent` 只接受 `FIRST_MEETING`、`REJOIN`、`INJURED` 三类事件。冷却默认开启，可通过 `setProactiveEnabled(false)` 关闭。适配层只能在游戏确实确认对应事件时调用；本服务不会监听全服聊天、不生成事件、不编造任务完成，也不调用网络。

短期上下文只保存在当前 store 实例内存中，重建实例后为空；长期记忆和主动事件的冷却时间会持久化。此实现尚未接入 Fabric/Forge 游戏事件或聊天入口，也没有真实游戏内验证。目录由调用方选择，文件权限遵循操作系统；POSIX 文件会将新临时文件设为仅所有者可读写。
