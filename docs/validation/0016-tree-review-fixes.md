# #36 审计修复与回归验证

日期：2026-10-29。原始候选：`ade2711b1de0f80e14066147d2a62de6b82bcfc9`。
关联：PR #36、#31；本记录只覆盖执行器审计修复，不关闭 #31。

## 修复

1. 死亡、换维度的动作取消只查内存中的主人/伙伴对应关系，不再调用 `CompanionRuntime.data`。无任务的普通玩家和空运行状态直接返回；取消涉及待确认预览，并且不触及其他主人的任务。`clearInputs` 使用现有运行时伙伴标记，不读取存档。
2. 掉落账本同时绑定原始 `ItemStack` 实例。原版合并会替换接收方堆栈，后来数量回到一仍不通过校验；正常拾取消耗同一实例，仍可记账。普通伙伴和重生后的普通 `ServerPlayer` 回调均传递实际来源实例。来源不匹配时丢弃该账本条目；任务结束后不保留凭据，不改变物品组件、掉落合并或消失规则。

## 本地已运行

本机只有已安装 JDK 17/25；使用 BellSoft JDK 25 编译器 `--release 21`，没有安装/升级 JDK 或系统软件。这不是本地 JDK 21 Gradle 构建。

| 检查 | 结果 |
| --- | --- |
| 公共源码/测试独立编译，JUnit Platform 执行 | 141 项通过，0 失败/跳过；含 3 项生命周期归属回归、1 项混合来源回归 |
| Python acceptance 工具测试 | 23 项通过 |
| 全部公共 Minecraft 源码类型检查 | 对缓存的 Minecraft 1.21.1 official-named API 编译通过 |
| shared/negative 游戏夹具类型检查 | 编译通过；简化本地 classpath 缺 Fabric 环境注解产生警告，不代表完整加载器构建 |
| `TreeDropRegression.main` | 对实际 Minecraft `ItemEntity.merge`、实际橡木/白桦 `ItemStack` 执行：合并后部分取走不记账，正常消耗仍可记账；通过 |
| `git diff --check` | 通过 |

`TreeDropRegression` 只初始化原版注册表，不创建服务器、世界或玩家，不接受 EULA，不调用模型。它证明固定版本合并的堆栈替换行为，不能代替多人游戏或第三方合并修改模组验收。

可复现的 Java 21 Gradle 入口（先构建正式 JAR）：

```bash
./gradlew checkAll --no-daemon
./gradlew -p experiments/m1-acceptance :fabric:verifyTreeDropAccounting :forge:verifyTreeDropAccounting --no-daemon
python3 -m unittest discover -s experiments/m1-acceptance -p 'test_*.py'
```

新增 CI 步骤运行两个加载器 classpath 下的原版合并回归；结果须查看修复提交对应的 Actions，不能沿用原始候选的 CI 结果。

## 仍待游戏验收

未运行真实客户端、专服、损坏存档下死亡/换维度、多人抢拾/合并、领地保护与完整树任务玩法；原记录 [0015](0015-tree-skill.md) 的游戏验收缺口继续适用。生命周期回归测试验证实际使用的内存归属解析，不宣称已复现并修复运行中的整服崩溃。普通聊天依赖 #28/#29，最终端到端验收依赖 #33。

本次修复与自审由 AI 辅助完成；未取得第二位成员独立验收，不把自审称为独立复审。
