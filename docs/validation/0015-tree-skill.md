# #31 有界树木执行器：本轮验证记录

日期：2026-10-28。关联：`Refs #31`。基于主线 `a9d55447`，分支 `feat/31-bounded-tree-skill`。本记录不是 Issue 完整验收或发布许可。

## 本轮交付与范围

- 普通直干橡树/白桦的保守候选观测、精确 4–8 原木计划、一次性预览/确认。
- 复用 M2 正常移动、生存挖掘与真实库存；匹配树种的实际拾取/交付和可选真实树苗补种。
- 分离已砍/已收/已交，拒绝不可靠掉落归属，处理部分可达、停止、重复、目标变化和生命周期失效。
- 纯 Java 核心测试，以及双加载器共用、可复现但尚未运行的 `TreeScenario` 游戏装置。

没有引入能力注册表或普通聊天任务理解；相关接口仍依赖 #28、#29。不能以命令/API 装置代替“通过普通聊天选择树木”的最终验收。具体契约和限制见 [TREES.md](../TREES.md)。

## 已实际执行

使用完整 Eclipse Temurin JDK `21.0.13+9`、仓库 Gradle Wrapper `8.8`。Gradle 用户缓存和环境代理配置位于仓库外，未修改依赖版本、生产配置或源码来规避下载限制。

| 检查 | 结果 |
| --- | --- |
| `./gradlew :common:test --configure-on-demand --rerun-tasks --no-daemon --console=plain` | **通过：137 个测试，21 个 suite，0 失败/错误/跳过**；`compileJava`、`compileTestJava`、`test` 均实际重跑，非仅缓存结果 |
| `python3 -m unittest discover -s experiments/m1-acceptance -p 'test_*.py'` | **通过：23 个测试** |
| `real_model_observer.py` 既有 `--self-test` | **通过：6 个本地自测**；未调用真实模型服务 |
| JDK 编译器 API 对 common 主源码、Fabric/Forge 入口和 acceptance shared 源码执行 `JavacTask.parse()` | **通过：88 个 Java 源文件语法解析**；没有类型分析、Minecraft API 链接或加载器编译，不等同 `checkAll` |
| `git diff --check` | **通过** |
| 独立源码审查 | 已复核列出的安全修复，未发现剩余具体阻塞缺陷；只是静态审查，不是玩法证明 |

公共 Java 基线为 88 个测试，本轮新增 49 个：`TreePlannerTest` 25、`TreePlanTest` 8、`TreeActionContractTest` 10、`TreeDropAccountingTest` 6。覆盖精确计划不扩张、普通树/相连结构/不完整树冠、未加载与坐标预算、非法请求、数量上限、重复/取消/迟到写入/主人隔离，以及单原木来源不足或合并时不记账。它们不直接执行 `common/src/main/minecraft`。

最终测试使用临时 Git index 核对测试前后预期源码树一致（真实 index 未改动）：

```text
测试源码 tree：59f06ac3e1716da3354d03cb2dc9ec6c396be44b
Gradle final-common-test.log SHA-256：
76ce6eeae4159ea49ea82321a13f2a012a419f885f9a67cfd8d41284a4d88a78
```

上述 tree 是加入本验证记录之前的源码快照；测试后仅新增本记录/文档链接和 compile-only CI 步骤，生产与测试源码未改变；不应当将该 tree 哈希称为最终提交 SHA。最终 Git 提交由发布者记录。

## 审查中修正的安全边界

- 重生为普通 `ServerPlayer` 时，拾取回调同时支持橡木/白桦，不能实际拾了白桦却永远记为零。
- 树木掉落只接受破坏调用紧前/紧后的新单原木 UUID；不靠旧堆栈增长推断合并，不将挖掘等待时旁人的掉落归入任务。
- 排队后立即停止也产生 `CANCELLED` 树结果；补种路径失败得到 `UNREACHABLE`，不保留终态 `PENDING`。
- 交付先按实际物品组件兼容槽计算容量，防止创造模式主人不可插入的余量被 `Inventory.add` 丢弃。
- 破坏前重新检查有界周边结构；允许正常树叶衰减，不把新增连接结构默认为授权。
- 新树任务使用服务器实际 tick 差，包含预览、拾取、补种及返回阶段，避免多人轮转和有符号 tick 回绕放宽时限。
- 暂离、死亡、换维度和实体替换会取消旧调用；旧预览、同 ID 重放不恢复执行。

这些修正均是源码和纯核心可覆盖部分的验证，不宣称对应 Minecraft 实机失败场景已跑过。

## 未执行与原因

**Minecraft/Fabric/Forge 游戏适配源码、双加载器完整构建、发行 JAR 与 `TreeScenario` 尚未编译或运行。** 本环境已知相关构建依赖下载受执行策略限制，本候选没有重试被拒绝的下载或改用来源绕过限制。没有启动游戏、接受 EULA、调用外部模型或进行真实存档操作。

已有 M2 的游戏通过记录不能迁移为本技能通过记录。仍需确认 Minecraft API/加载器链接，并在 Fabric 与 Forge 的普通单机/专服中验证：

1. 生成的普通树、近旁/相连木屋/装饰、保护事件拒绝。
2. 高处不可达的部分完成、缺斧头/耐久耗尽、双方背包满及不兼容物品组件。
3. 掉落被其他玩家拿走、丢失或合并，不虚增已交；无苗/位置无效/放置拒绝的真实消耗。
4. 挖掘、拾取、补种、交付前停止；断线、死亡重生（尤其白桦拾取）、同 UUID 暂离重召、快速往返维度。
5. #28/#29 接入后普通聊天目标选择、必要澄清和依据实际结果反馈。

## 可复现游戏装置（待运行）

装置源码：`experiments/m1-acceptance/shared/src/main/java/io/github/yfyan/minecraftfriends/acceptance/TreeScenario.java`。入口：

```bash
./gradlew checkAll --no-daemon
JAVA_HOME=/你的/JDK21 python3 experiments/m1-acceptance/run_actions.py fabric-integrated --scope tree
JAVA_HOME=/你的/JDK21 python3 experiments/m1-acceptance/run_actions.py forge-integrated --scope tree
```

仅在新隔离世界和固定测试身份中设置普通形状的真实树冠、物资与样本。通过客户端 area 命令、typed preview/confirm 验证实际方块、分离计数、库存守恒、耐久、补种消耗和重放/停止/变化；初始化不算被测执行。为确定性库存断言，仅测试世界关闭随机 tick，不强制叶子掉落或树苗增长。`--scope tree --natural` 明确拒绝，不能伪装已覆盖普通聊天。

完整运行入口、专服隔离约束和 `TREE_CHECKS` 见 [装置说明](../../experiments/m1-acceptance/ACTIONS.md)。没有成功运行结果前，本记录保持执行器增量交付，不关闭 #31。

## 提交后 CI 检查（待结果，不是本地通过证据）

现有构建 workflow 在两个正式 JAR 构建和资源增量检查之后，增加：

```bash
./gradlew -p experiments/m1-acceptance :fabric:compileJava :forge:compileJava --no-daemon --stacktrace
```

两个实验子工程均由其 Java 插件提供 `compileJava`，公共 `shared/src/main/java` 包含 `TreeScenario`，依赖刚构建的正式 JAR。这一步只编译类型/API，不运行客户端或服务器、不接受 EULA、不请求模型。沿用现有 30 分钟 CI 预算；需要由 PR 检查结果确认它实际通过，不能提前写成成功。
