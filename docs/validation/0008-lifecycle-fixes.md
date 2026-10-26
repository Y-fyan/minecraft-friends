# 伙伴生命周期修复验收

日期：2026-10-26。关联 Issue #4。实现与本记录在同一提交。范围为伙伴身份、存档保护、重生时机、加载器生命周期事件和 GameTest；未修改 model、config、chat、memory、bootstrap 或 README。

## 修复内容

- `DimensionDataStorage.readSavedData` 会吞掉读取异常并返回 `null`，之后 `computeIfAbsent` 可以创建空 `SavedData`。运行时现在在读取缓存前用同一数据存储读取磁盘文件并严格校验 schema、字段和记录唯一性；发现损坏或未知版本时拒绝加载，不进入空数据创建路径。GameTest 写入未知 schema 的真实 `.dat` 文件，确认调用被拒绝、文件字节未变且原文件仍可回读。
- 伙伴 UUID 与主人 UUID、其他伙伴/主人 UUID 全局去重；伙伴名称大小写不敏感去重。默认名称加入主人 UUID 派生的稳定后缀并检查存档名与在线名，自定义重名明确拒绝。
- 伙伴使用具体 `ServerPlayer` 实例标记识别。召唤、暂离、退出和关服清理在 UUID 命中未标记的真人时局部拒绝；登录恢复冲突会给主人提示并让登录继续。死亡重生后将标记转交给新实体。
- 死亡事件只登记待处理状态；Fabric END tick 和 Forge END server tick 至少跨过一次服务端 tick，再核验原实体仍在玩家列表、仍死亡、记录仍 active 后重生。Forge 监听位于 MONITOR，接收已取消事件并跳过它。
- 只有首次创建会将伙伴放到主人附近；暂离后的召回保留玩家存档中的维度与坐标。

## 环境与运行

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Java | Eclipse Temurin 21.0.13+9 (`/tmp/codex-mf-jdk21-213`) |
| Gradle | 8.8，使用仓库/实验现有 Wrapper |
| Fabric Loader / API / Loom | 0.20.1 / 0.116.17+1.21.1 / 1.7.4 |
| Forge / ForgeGradle | 52.1.20 / 6.0.24 |

测试使用独立 GameTest 世界、随机 UUID 和内存连接；没有真实账号、存档、模型服务或凭据。最终命令使用 `--offline`。ForgeGradle 的环境探针会主动进行 HTTPS 握手；只在对应 Gradle 命令上加 `-Dnet.minecraftforge.gradle.check.certs=false` 关闭该探针，没有改全局或仓库 TLS 设置，也没有把它用于 Provider 请求。

运行命令：

```bash
JAVA_HOME=/tmp/codex-mf-jdk21-213 PATH=/tmp/codex-mf-jdk21-213/bin:$PATH ./gradlew :common:test --no-daemon --console=plain --offline -Dnet.minecraftforge.gradle.check.certs=false --rerun-tasks
```

```bash
cd experiments
JAVA_HOME=/tmp/codex-mf-jdk21-213 PATH=/tmp/codex-mf-jdk21-213/bin:$PATH ./player-control/gradlew -p companion-lifecycle :fabric:compileJava :forge:compileJava --no-daemon --console=plain --offline -Dnet.minecraftforge.gradle.check.certs=false
```

```bash
cd experiments
JAVA_HOME=/tmp/codex-mf-jdk21-213 PATH=/tmp/codex-mf-jdk21-213/bin:$PATH ./player-control/gradlew -p companion-lifecycle :fabric:runGameTest :forge:runGameTestServer --no-daemon --console=plain --offline -Dnet.minecraftforge.gradle.check.certs=false -x :forge:downloadAssets -x :forge:downloadMCMeta
```

## 结果

| 检查 | 结果 |
| --- | --- |
| `:common:test` | 通过；3 项 `CompanionIdentityTest` |
| 实验 Fabric/Forge `compileJava` | 通过 |
| Fabric GameTest | 通过；日志保留真实类名 `net.minecraft.gametest.framework.GameTestServer`，`isDedicatedServer=true` |
| Forge GameTestServer | 通过；真实类名同为 `net.minecraft.gametest.framework.GameTestServer`，该运行实例报告 `isDedicatedServer=false` |
| `keepInventory=false`，伙伴背包 4 个原木 | **实际掉落实体总数恰为 4**，重生背包为 0 |
| `keepInventory=true`，伙伴背包 4 个原木 | **地面掉落实体为 0**，重生背包仍为 4 |
| Forge 已取消 `LivingDeathEvent` | 取消监听器确认已触发；原玩家列表项保持不变、仍死亡、背包为 4 且地面无掉落，运行时没有延迟重生；装置随后重新创建伙伴再继续常规掉落检查 |
| 存档往返、未知 schema、重复主人/伙伴 UUID/名称、非法 active 标志 | 通过；损坏 `.dat` 字节一致并可回读 |
| 创建/重复创建/状态/暂离/召回命令 | 通过 Brigadier dispatcher 和模拟玩家 `CommandSourceStack` 执行；不是冒充真人聊天 |
| 多主人默认名后缀、自定义重名、在线名称冲突、真人 UUID 冲突 | 通过；冲突时不踢真人，登录事件不向上传播异常 |
| 创建靠近主人、暂离后维度/坐标/背包/生命恢复、主人退出重进、关服卸载 | 通过 |

掉落检查使用原版 `ServerPlayer.die` 路径：先将伙伴生命设为 0，再调用其死亡流程；独立普通模拟玩家 `hurt` 控制在活跃测试区产生 1 个掉落实体。伙伴死亡安排在已确认 entity-ticking 的测试区块，计数只观察原版实际掉落，没有手动生成物品来补足断言。初次 GameTest 曾在未处于 entity-ticking 状态的 Nether 区块观测到 0 个掉落实体；因此这是装置区块条件问题，不能据此推断普通游戏掉落丢失，也不代表早重生已被证实为原先 0 掉落的根因。

冲突通知由模拟连接捕获并断言已发送，未通过真实玩家会话验证。

父代理此前报告的 IntegratedServer/JAR 运行使用未包含本分支修复的旧代码；本分支没有重新运行图形客户端、真实 IntegratedServer 或最终独立 JAR。父代理将对含本修复的 JAR 复测，并负责把 disabled 配置接入其工作树。本记录只确认两个加载器 GameTest、公共测试和编译结果，不替代该后续验收。
