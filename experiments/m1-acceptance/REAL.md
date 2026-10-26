# M1 真实模型游戏验收入口

这个入口只接受使用者显式提供的 HTTPS 端点、模型名和唯一凭据环境变量名。观察代理不是模型：它绑定 loopback，转发生产模组发出的 OpenAI-compatible 请求，并在回复进入游戏前执行确定性语义判定。请求正文、回复正文、Authorization、真实端点和模型名都不写入证据；证据只保留判定计数、受控故障计数和哈希。

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
  --api-key-env '<使用者指定的凭据环境变量名>'
```

Forge 把目标替换为 `forge-integrated`。只可加 `--offline` 或 Forge 记录过的单次证书预检参数；不要重复全套主流程测试。运行器会从传给 Gradle/Minecraft 的进程环境中移除指定凭据变量；代理进程已在启动时读取唯一授权凭据。

## 判定

通过必须同时满足：

- 原有三类事件、记忆 list/delete 和删除后上下文探针通过；
- `FIRST_MEETING`、`INJURED`、`REJOIN` 各有一次真实模型请求和语义合格回复；
- 回复不是空文本或协议桩文案，包含足够的中文与明确 AI 身份，并响应对应事件语境；默认人设与温和口吻必须出现在请求上下文中；
- 普通聊天回复完整保留 `MF_REAL_CHAT` UUID 验证码，且明确 AI 身份；
- 受控超时和受控断连各出现一次，玩家分别收到对应中文反馈，之后游戏继续执行；
- 记忆删除后，保留偏好与三条事件仍在上下文，删除事实完全不在；记忆数量保持稳定；
- 客户端、服务端、观察代理计数一致，失败计数为零，产物/源码/助手哈希不变。

语义判定是可复核的机械规则，不宣称主观试玩或自然语气质量已完整验收。任一语义不合格回复会被网关拒绝，游戏收不到该回复，整轮验收失败。

## 证据与停止

每次运行生成独立 `experiments/m1-acceptance/build/evidence/<target>-<timestamp>/summary.json`；观察代理另写 `protocol-summary.json`。失败时保留摘要、固定断言日志和代理计数，不保存正文。提交或上报只允许脱敏摘要与哈希。真实服务费用、隐私和响应质量由使用者确认服务条件后才能运行；没有完整配置时不得猜测或发起调用。
