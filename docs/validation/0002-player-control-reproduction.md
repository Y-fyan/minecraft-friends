# 玩家控制独立复现与补充验证（#1）

日期：2026-10-25。执行：创作者账号下的统筹助手。状态：**可运行装置、版本化源码及四组基础断言已补齐**。本轮独立实现，不使用 PR #15 未提交的探针，也不声称复现了旧报告的每一个数字。

## 装置与固定环境

源码与完整入口见 [experiments/player-control](../../experiments/player-control/README.md)，与 #2 的工程分开构建。JDK 为 Temurin 21.0.13+9-LTS，Java 目标 21；Gradle Wrapper 8.8，分发包 SHA-256 已锁定；Linux x86_64。Fabric Loader 0.16.14 / API 0.116.17+1.21.1 / Loom 1.7.4；Minecraft Forge 52.1.20 / ForgeGradle 6.0.24；两侧均为 Minecraft 1.21.1 官方映射。

使用独立生成的平坦测试世界和确定性的虚构玩家档案；不访问真实存档或账号，不使用 API key 或模型服务，不安装系统包、不升级内核或显卡驱动。客户端运行使用进程级软件渲染和无音频输出。首轮下载的官方游戏资源只保存在本机内容寻址缓存中，未提交游戏 JAR、素材或世界。

## 复现命令

在 `experiments/player-control` 中配置本机 `JAVA_HOME` 为 JDK 21，然后执行：

```bash
./gradlew :fabric:build :forge:build
python3 run_probe.py fabric-server
python3 run_probe.py forge-server
python3 run_probe.py fabric-integrated
python3 run_probe.py forge-integrated
```

网络依赖已缓存时可添加 `--offline`。首次构建可能下载较大的官方资源；脚本不安装软件、不改变系统默认 Java、代理或图形配置。真正的集成服测试需要有效显示环境与 OpenGL，不满足时必须报告失败/未测，不能改用 GameTestServer 冒充通过。

测试装置的 `ProbeConnection` 无 socket，直接丢弃出站消息；不会验证序列化、认证或网络握手。这样可以独立测试服务端玩家生命周期，同时明确其无法替代完整客户端的部分。

## 四组结果

| 目标 | 实际服务器类型 | 结果 |
| --- | --- | --- |
| Fabric 无界面测试启动器 | `net.minecraft.gametest.framework.GameTestServer` | 全部断言通过 |
| Forge 无界面测试启动器 | `net.minecraft.gametest.framework.GameTestServer` | 全部断言通过 |
| Fabric 开发客户端中新建单机世界 | `net.minecraft.client.server.IntegratedServer` | 全部断言及客户端完成标志通过 |
| Forge 开发客户端中新建单机世界 | `net.minecraft.client.server.IntegratedServer` | 全部断言及客户端完成标志通过 |

各组均检查：加入玩家列表、移动大于 0.1 格、直接挖掘后方块为空气并实际拾取泥土、受伤由 20 降至 15、移除时玩家列表删除、同档案重进保留 7 个原木、死亡掉落 7 个原木、显式适配后以 20 生命重生、重生后背包无原木以及确实丢弃出站包。

每组都要求 `result=PASS`、进程退出码 0、完整断言集合、正确加载器及服务器类；集成服还要求 `MF_CLIENT_RESULT PASS`。单凭“游戏启动了”或退出码 0 不算通过。

## 证据与可追溯性

本轮装置对应源码提交：`d99168a6e9615438bdbdb9f6f4f231e5ca445fe4`。后续只补充证据索引元数据，不更改该提交的探针源码。

- 结构化脱敏断言、退出码、源码树摘要与环境检查：[player-control-results.json](evidence/player-control-results.json)。
- 实验源码文件摘要与生成 JAR 校验值：[player-control-manifest.json](evidence/player-control-manifest.json)。
- 原作者初轮报告：[0001-player-control.md](0001-player-control.md)，继续明确原装置未提供，原数字尚未独立核实。

本机完整日志位于探针 `build/evidence/<目标>/full.log`，不提交完整日志。版本化摘要仅含虚构测试上下文及断言，不含凭据、机器私人路径、玩家私人消息或真实世界内容。源码树摘要覆盖装置目录的源码与配置；生成目录排除在外。提交本身记录所有源码版本，清单另记录文件及产物 SHA-256。

## 发现并修复的问题

1. 最初仅覆写发包方法仍会在服务端刷新连接时访问空 channel；补齐实验连接的刷新、只读与 tick 边界后四组通过。生产实现仍须考虑更多网络生命周期。
2. Forge 无界面测试需要声明数据包元信息才能加载自写空结构；补充 `pack.mcmeta` 后通过。
3. 直接调用底层重生不等于完整客户端重生流程；探针明确补齐生命恢复并检查死亡掉落与重生背包，未把手动适配伪装成引擎默认行为。

## 结论与边界

接受 [ADR 0001](../decisions/0001-player-control-route.md) 的初期路线：在可安装模组的受控服务器环境中优先采用服务端玩家实体，世界执行权归服务端，初期推理与服务端同侧。

这四组结果是玩家控制的基础可行性证据，**不是正式发布的 Fabric/Forge × 单机/普通专服兼容矩阵**。GameTestServer 的实际类已单独记录，不能当普通 DedicatedServer；后者的正式模组部署由 #2/#11 验证。初期按双端安装开发，不宣称客户端可选。

真实微软账号认证、外部协议机器人的原报告、图形标签页截图、自定义皮肤、正常耗时挖掘、寻路、第三方模组回执、多人隔离和长时间资源占用不在本轮通过声明中。前两项中真实账号路线已明确不进入 MVP；正式伙伴生命周期、动作、聊天、导航及发布验收继续由 #4/#6/#8/#9/#10/#11 承接。
