# M1 真实模型游戏验收入口

这个入口只接受使用者显式提供的 HTTPS 端点、模型名和唯一凭据环境变量名。观察代理不是模型：它绑定 loopback，转发生产模组发出的 OpenAI-compatible 请求，并在回复进入游戏前检查格式、中文、AI 身份等机械条件。结构化证据不写请求/回复正文、Authorization、真实端点或模型名，只保留判定计数、受控故障计数和哈希；回复文本只有在下述显式 0600 人工审阅文件中保存。

尚未完成真实模型验收。以下命令是配置到达后的复现入口，不是已完成结果。

## 前置条件

1. `JAVA_HOME=/tmp/codex-mf-jdk21-213` 指向便携 JDK 21。
2. 两份与主验收同字节的正式 JAR 已放在本树 `build/artifacts/`：
   - Fabric `e3278b45c1f77755ce35b1ba73e6a9a1aaa3376778153bc59fe3a2dd4633a336`
   - Forge `bfad1766bcfb44f8077fc625908ca1dd152c2d76ae4efb7241ab9efa6024cfe6`
3. 使用者已明确提供真实服务 URL、模型名和环境变量名，并把凭据只放入该环境变量。不要把端点、模型名或密钥写入 shell 历史、properties、Issue 或提交。
4. 若目标端点不是 loopback HTTP，则必须是 HTTPS。本入口默认 `model.retries=0`，游戏模型超时默认 6 秒。

## 运行

在安全 shell 中先设置使用者指定名称的凭据，再执行：

```bash
JAVA_HOME=/tmp/codex-mf-jdk21-213 \
python3 experiments/m1-acceptance/run_events.py fabric-integrated --real \
  --endpoint '<使用者提供的完整端点>' \
  --model '<使用者提供的模型名>' \
  --api-key-env '<使用者指定的凭据环境变量名>' \
  --human-review '/绝对路径/m1-real-human-review.jsonl'
```

Forge 把目标替换为 `forge-integrated`。只可加 `--offline` 或 Forge 记录过的单次证书预检参数；不要重复全套主流程测试。运行器会从传给 Gradle/Minecraft 的进程环境中移除指定凭据变量；代理进程已在启动时读取唯一授权凭据。

## 判定

通过必须同时满足：

- 原有三类事件、记忆 list/delete 和删除后上下文探针通过；
- `FIRST_MEETING`、`INJURED`、`REJOIN` 各有一次真实模型请求和语义合格回复；
- 回复不是空文本或协议桩文案，包含足够的中文与明确 AI 身份，并响应对应事件语境；默认人设与温和口吻必须出现在请求上下文中；
- 普通聊天回复完整保留 `MF_REAL_CHAT` UUID 验证码，且明确 AI 身份；
- 受控超时和受控断连各出现一次，玩家分别收到对应中文反馈，之后游戏继续执行；
- 父代理已用受限 0600 回复输出人工确认三条事件回应、语气、人设和明确 AI 身份；
- 记忆删除后，保留偏好与三条事件仍在上下文，删除事实完全不在；记忆数量保持稳定；
- 客户端、服务端、观察代理计数一致，失败计数为零，产物/源码/助手哈希不变。

事件词表仅作提示：合法同义表达没有命中时记录 event_context_advisories，不拦截或改写真实回复；必须逐条由父代理确认事件对应关系。semantic_passes 是历史字段名，表示机械门接收计数，不代表语义质量已通过。机械门不能证明主观语气、人设和事件回应质量。真实回复必须由父代理人工审阅。给真实运行加 `--human-review /绝对路径/replies.jsonl` 时，观察代理才会把模型回复文本写入该 opt-in 本地文件；文件和必要时新建的父目录分别用 0600/0700，内容只含事件标签、机械判定、哈希和助手回复，不含请求正文、端点或凭据。该文件不提交。没有 opt-in 时不保存回复文本。

`DELETE_PROBE` 的生产提示仍是“请用中文简短回复”；观察门只检查中文、非协议桩和记忆上下文，不要求 nonce 或再次声明 AI 身份。只有 `REAL_CHAT` 因提示本身明确要求保留验证码并确认 AI 身份，才检查这两项。三类事件提示明确要求自然问候并说明 AI 身份，因此检查对应语境和 AI 身份。

机械摘要即使全部通过，也只置 `m1_real_model_automated_checks_complete=true`；真实分支的运行级 `passed`、`m1_events_complete` 和 `m1_real_model_integrated_complete` 均为 `false`，`human_review_completed=false`。因此真实分支命令会返回非零，这是“等待父代理人工验收”的明确状态。父代理必须读取受限回复输出，确认三条事件回应、语气、人设和明确 AI 身份后才能做最终验收。

## 证据与停止

每次运行生成独立 `experiments/m1-acceptance/build/evidence/<target>-<timestamp>/summary.json`；观察代理另写 `protocol-summary.json`。失败时保留结构化摘要、固定断言日志和代理计数；显式 0600 人工审阅文件可能保留失败前的模型回复，供父代理判定，不提交。提交或上报只允许脱敏摘要与哈希。真实服务费用、隐私和响应质量由使用者确认服务条件后才能运行；没有完整配置时不得猜测或发起调用。
