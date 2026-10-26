# 聊天与记忆

状态：公共聊天会话、服务端聊天入口、命令和记忆适配已实现；父代理仍需将服务端生命周期、玩家聊天、登录/退出、受伤和伙伴移除事件接入 `ChatRuntime`。未完成 Fabric/Forge 客户端与专用服务器实机验收。

## 游戏命令

所有命令由服务端按执行者 UUID 校验。私聊只发送给主人；伙伴回复使用系统消息，不进入全服聊天，也不会再次作为玩家输入处理。

| 命令 | 行为 |
| --- | --- |
| `/mf chat <消息>` | 私聊自己的伙伴 |
| `/mf chat cancel` | 取消自己在私聊和已加入共享会话中的在途/排队消息 |
| `/mf chat clear` | 清空自己的聊天历史和短期上下文；保留长期记忆、人设和称呼 |
| `/mf chat status` | 查看世界 UUID、前缀、Provider、伙伴在线状态、会话/队列、记忆代次和主动问候设置 |
| `/mf chat shared create` | 为自己的伙伴创建独立共享会话，主人保留管理权 |
| `/mf chat shared send <会话UUID> <消息>` | 按当前执行者 UUID 校验共享成员资格后发言，并只向成员回显 |
| `/mf chat shared invite <在线玩家>` | 按服务器在线玩家记录的 UUID 发出邀请 |
| `/mf chat shared join <会话UUID>` | 接受发给当前玩家 UUID 的邀请 |
| `/mf chat shared leave <会话UUID>` | 离开共享会话；主人不能离开自己管理的会话 |
| `/mf memory list` | 查看自己的长期记忆及删除编号 |
| `/mf memory remember <内容>` | 保存玩家明确提供的偏好 |
| `/mf memory delete <记忆UUID>` | 删除单项并使旧上下文请求失效 |
| `/mf memory clear` | 清空长期/短期记忆、人设称呼和主动事件冷却 |
| `/mf memory address <称呼|reset>` | 设置伙伴对主人的称呼，或恢复默认 |
| `/mf memory tone <口吻|reset>` | 设置伙伴口吻，或恢复默认 |
| `/mf memory proactive <true\|false>` | 开关基于真实游戏事件的主动问候，默认开启 |

共享会话成员只有在主人邀请且自己的 UUID 成功加入后才能发言、查看回复。共享会话拥有独立短期历史，不会读取主人的私聊、人设、称呼或长期记忆。离开或断线后，成员不再收到后续回复。文本中的邀请语句、伪造名称和会话 UUID 本身都不会授予权限。

## 聊天前缀

默认格式为 `@伙伴名 消息`，例如 `@MF_Alex 今天天气怎么样？`。可在 `config/minecraft-friends.properties` 中手动增加 `chat.prefix=@`，将 `@` 换成不含空白和斜线、长度不超过 8 的前缀。主人对自己伙伴的前缀始终进入私聊；非主人只会匹配已加入的唯一共享会话。共享会话主人通过 `/mf chat shared send <会话UUID> <消息>` 明确发言。普通聊天和 `/` 命令原样交回 Minecraft。

## 状态与服务端接入 API

`ChatRuntime.start(MinecraftServer, Path configDir)` 在服务端启动时创建运行时；`stop(server)` 和 `onWorldUnloaded(server)` 取消任务并关闭运行时。加载器应按服务端线程调用：

- `onLogin(ServerPlayer)`：真实主人登录/重登事件在伙伴恢复后调用；可触发首次见面或重登问候。
- `onCompanionAvailable(ServerPlayer owner)`：伙伴创建或召唤后调用，只尝试首次见面，不生成重登事件。
- `onLogout(ServerPlayer)`：撤销其共享成员资格并使其私聊/在途请求失效。
- `onCompanionRemoved(MinecraftServer, UUID ownerId)`：dismiss 或移除伙伴前调用，使该世界中的私聊和共享会话失效；无 server 参数的兼容入口会取消所有运行时中的同 UUID 主人会话。
- `onCompanionInjured(ServerPlayer companion)`：两侧加载器仅在确认实际伤害大于 0 后调用。
- `onTick(MinecraftServer)`：回收空闲会话与频率统计。
- `onChat(ServerPlayer, String)`：普通玩家聊天事件调用；返回 `true` 表示已截获。
- `registerCommands(CommandDispatcher<CommandSourceStack>)`：命令注册事件调用。
- `isEnabled(MinecraftServer)`：只反映全局 `enabled` 配置，供伙伴创建入口门控；Provider 关闭或世界 UUID 损坏不会阻止普通伙伴创建。
- `status(ServerPlayer)` 或 `status(MinecraftServer, UUID)`：只读、无磁盘 I/O 的验收快照。

`ChatStatus` 提供全局 `enabled` 与聊天/Provider 状态、持久 `worldId`、`companionDataAvailable`、伙伴 UUID/名称/活动及在线状态、会话数、在途数、队列数、共享成员数、记忆代次/条数、称呼、口吻和主动问候状态。伙伴数据预检或后续读取失败时该字段为 `false`，聊天和记忆局部关闭，`disabledReason` 说明数据不可用；不会把失败伪装为“未创建伙伴”，也不删除或重写原伙伴文件。验收可在客户端发起命令后，从服务端读取相同的状态 API。运行时默认使用 #5 的 `ModelProviders.create(ModConfig)`；工厂构造时不得联网。`setProviderFactory` 只用于明确的集成覆盖。

消息通过 `ModelProvider.complete(ModelRequest)` 异步提交到有界后台池；成功/失败回调统一经 `server.execute` 回到服务端线程，再复核运行时、会话代次、伙伴和接收成员。默认模型配置最多 24 条消息/12,000 字符，运行时为系统提示保留预算，并限制历史及附加上下文；每会话至多 1 个在途请求、4 条排队消息、512 字符输入、1,000 字符回复；全服务最多 128 个会话，每玩家每 10 秒最多 8 条。超限有中文反馈。

模型失败仅把安全的 `ModelCallException.Category` 传到聊天提示，并使用该类别的固定中文文案；未知错误统一提示模型暂时无法回复，不显示异常信息。取消、清空或成员退出后，旧请求结果失效且不会再提示。

## 身份、记忆与线程

每个世界的 UUID 独立保存在 `data/minecraft_friends_chat_world.uuid`，首次建立后随世界存档保存；会话和记忆按 `(world UUID, owner UUID, companion UUID)` 隔离，不使用 seed、临时进程 UUID 或目录名计算身份。已有身份文件必须是规范 UUID；若损坏或不可读取，运行时关闭聊天/记忆功能，保留原文件、不创建替代 UUID，也不会踢出玩家。长期记忆位于 `data/memory/<world UUID>/<owner UUID>/<companion UUID>/memory.properties`。

每个记忆范围复用单一 `CompanionMemoryStore`。磁盘读取、初始化、列表、修改及原子保存由每范围串行队列运行；存储实例缓存最多 128 个，每个范围最多 8 个待处理操作，后台池队列最多 128 项。聊天 tick 不等待磁盘；`ChatRuntime.status` 只读最近一次后台操作更新的快照。若队列满或文件损坏，命令明确失败，不报告为已保存。

执行 `chat clear`、`memory delete` 或 `memory clear` 时，适配层先取消该主人的相关私聊请求与排队项，再推进记忆 generation。迟到的模型回复无法提交到旧 token。`chat clear` 通过删除一个确保不存在的 UUID 使用记忆核心“删除会清短期上下文并推进 generation”的语义，保留长期事实与 persona；专用的 `clearConversationContext()` API 到达后可替换此兼容实现。单次回复限制为 1,000 字符，低于记忆短期上下文单条 1,200 字符上限，避免成功的模型回复因保存长度校验失败而丢失。

共享会话不创建或访问 `CompanionMemoryStore`。私聊提示包含默认 AI 身份/自主性约束、显式用户偏好、长期事实和有界短期上下文。首次登录、后续重登、受伤问候只有在对应真实事件和记忆 API 冷却认领成功后才会进入聊天队列；每类事件默认 10 分钟冷却，首次见面仅一次（清空记忆后重置）。这些问候不会读取或伪造任务完成状态。

## 默认与限制

`enabled=true` 时普通聊天功能默认启用。默认 `mock` 是离线中文演示回复，不发送网络请求、不需要密钥；`model.provider=disabled` 或 `enabled=false` 时不创建模型执行器，也不会触发推理。加载器还必须在伙伴创建入口检查 `ChatRuntime.isEnabled(server)`；该 API 仅反映 `enabled`，因此关闭 Provider 或聊天 UUID 文件损坏不会阻止普通伙伴创建。聊天不可用时，匹配伙伴前缀和 `/mf chat` 会给出中文提示；只要世界身份有效，现有伙伴的记忆查看、清理和人设管理命令仍可用。

自动化测试覆盖会话取消/迟到回复、玩家私聊隔离、共享成员退出、伪造前缀不能入会、长度/队列/限流上限以及私聊记忆不进入共享上下文。`ChatRuntime` 需要父代理接好各加载器事件后，才能进行正式发行 JAR、客户端、IntegratedServer 和 DedicatedServer 验收；当前编译通过不等于实机聊天验证通过。
