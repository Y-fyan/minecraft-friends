# 模型 Provider

公共核心提供 OpenAI-compatible Chat Completions 适配器。默认仍使用不联网的 `mock`，固定回复“我是离线演示中的 AI 伙伴，当前未连接真实模型。”；这只是离线演示文案，不会理解或记忆聊天。不会内置服务地址或凭据。只有显式选择 `chat-completions` 并由聊天层调用 `complete` 时，才会向配置的端点发送消息。

公共入口是 `ModelProviders.create(ModConfig config) -> ModelProvider`。工厂创建时不发 HTTP 请求，现有 `ModelProvider.complete(ModelRequest)` 仍返回 `CompletionStage<ModelResponse>`。真实 Provider 使用 Java 21 `HttpClient.sendAsync`；取消返回的 future 或关闭 Provider 会取消当前 HTTP 请求。有效超时取 `ModelRequest.timeout` 与 `model.timeout-seconds` 中较短者，范围不超过 1–300 秒。

## 配置

配置文件为 `<游戏配置目录>/minecraft-friends.properties`。新建文件默认 `enabled=true`、`diagnostics.enabled=true`、`model.provider=mock`，不会联网。

| 键 | 类型与范围 | 默认值 | 用途与隐私 |
| --- | --- | --- | --- |
| `model.provider` | `mock`、`chat-completions`、`disabled` | `mock` | 选择 Provider。`mock` 不联网；不会自动切换到真实模型。 |
| `model.timeout-seconds` | 整数 1–300 秒 | `30` | 每个请求的总超时；到期会取消 HTTP。 |
| `model.endpoint` | 显式 HTTP(S) URL，最多 2048 字符；不允许 userinfo、query 或 fragment | 空 | Chat Completions 完整端点。远端必须使用 HTTPS；HTTP 仅允许 `localhost`、`127.0.0.1` 或 `::1`。URL 不会写入启动日志或配置对象的 `toString()`。 |
| `model.name` | 非空文本，最多 128 字符 | 空 | 服务端模型标识。 |
| `model.api-key-env` | 环境变量名 | 空 | 只读取此处明确指定的环境变量；最多接受 8192 个可见 ASCII 字符，密钥值不写入 properties、配置对象或异常。远端服务必须配置此项和对应环境变量。 |
| `model.allow-unauthenticated-local` | 布尔值 | `false` | 仅当端点主机是上述 loopback 地址时，显式允许不带密钥访问本机服务。不会放宽远端认证要求。 |
| `model.retries` | 整数 0–2 | `1` | 网络失败、HTTP 408、429 和 5xx 的额外尝试次数；每次请求最多 3 次。退避等待最多 1 秒，且计入总超时。 |
| `model.max-context-messages` | 整数 1–64 | `24` | 单次请求的消息数量上限；超出会拒绝请求，不静默截断。 |
| `model.max-context-characters` | 整数 1–16000 | `12000` | 单次请求消息正文的 Java 字符总量上限；超出会拒绝请求。 |
| `model.max-response-bytes` | 整数 1024–1048576 | `65536` | 接收响应正文的字节上限；超出时取消读取并返回安全错误。 |

整个进程最多同时保留 4 个在途模型 HTTP 请求。超过上限会立即以 `OVERLOADED` 失败，不建立无界等待队列。
序列化后的 HTTP 请求另有固定 128 KiB 上限；超过时在联网前拒绝。
每次调用还固定请求最多生成 512 个 token，限制单次生成用量；有界重试可能将同一聊天再次发给服务端，也可能产生额外费用。

例如，远端服务须由用户明确配置：

```properties
model.provider=chat-completions
model.endpoint=https://模型服务地址/v1/chat/completions
model.name=用户选择的模型
model.api-key-env=用户选择的环境变量名
```

将密钥放入该环境变量，由启动游戏的进程继承。不要将密钥写进配置文件、命令行参数、Issue 或日志。没有配置端点、模型或凭据时，Provider 以 `CONFIGURATION` 分类失败；不会猜测服务商、读取其他变量、访问默认远端或发起付费调用。若要使用本机无认证服务，还要显式设置 `model.allow-unauthenticated-local=true`。

## 失败与诊断

`ModelCallException.category()` 和 `userMessage()` 提供中文安全分类，包括配置、停用、并发繁忙、认证、限流、断网、超时、格式错误、请求或响应超限、服务不可用、服务拒绝和取消。异常不保存 HTTP 响应正文、密钥、端点或原始异常原因。HTTP 重试、上下文、并发和响应大小均有限制。

默认诊断不会记录完整请求、响应、聊天、Authorization 头、端点或密钥。聊天层应继续遵守此边界；模型回复仅作为返回数据，不代表游戏动作获准执行。完整请求会发送给用户配置的端点，因此使用真实服务前应确认该服务及其隐私和计费条件。

当前只解析 `choices[0].message.content` 为字符串，不支持流式响应、工具调用、多模态内容或特定厂商扩展。

## 依赖与验证范围

公共模块固定使用 [Gson 2.10.1](https://github.com/google/gson)，许可证为 Apache-2.0，用于序列化有界请求和流式解析受限响应；版本与 Minecraft 1.21.1 运行时自带版本一致，避免加载器环境出现 Gson API 冲突。Java 21 没有稳定的内置 JSON API；Gson 避免手写 JSON 编码器/解析器。

自动化协议测试仅绑定本机 `127.0.0.1` 的 JDK `HttpServer` stub，覆盖成功、畸形响应、401、429、有界重试、拒绝连接、超时、取消、上下文上限和响应字节上限。它们不访问真实服务、不使用真实凭据，也不构成真实模型或游戏内聊天验收。真实服务验证需由用户提供端点和模型配置，并自行决定是否提供环境变量凭据。
