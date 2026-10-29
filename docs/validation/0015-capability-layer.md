# 能力接口与安全执行层验证（#28）

状态：**公共层单测与双加载器编译通过；真实客户端验收未执行**。本记录只覆盖本轮实际运行的项目，未运行的部分单独列出。

- 关联任务：[#28](https://github.com/Y-fyan/minecraft-friends/issues/28)
- 接口说明：[能力接口](../CAPABILITIES.md)
- 日期：2026-10-28
- 实现提交：本轮分支 `feat/28-capability-registry`（提交见 PR）

## 1. 改动摘要

- 新增 `capability` 包：`CapabilityRegistry`、`CapabilitySpec`、`CapabilityParameter`、`CapabilityCall`、`CapabilityCallParser`、`CapabilityException`、`CapabilityResult`。
- 注册并只注册现有已验收能力：`observe`、`follow`、`wait`、`stop`、`collect_oak_logs`。
- `ActionPlanner` 的提示由注册表生成，模型响应按注册表严格解析；`UNAUTHORIZED` 改为按能力的区域授权标记判断。
- `ActionRuntime` 负责能力分派、服务器线程复核、call id/task id 关联、只读结果查询，并新增 `/mf task capabilities`。
- 文档：新增 `docs/CAPABILITIES.md`，更新 `docs/ACTIONS.md` 与 README 索引。

## 2. 实际运行的检查

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 公共层单测 | `./gradlew :common:test --configure-on-demand --rerun-tasks` | 97 项通过，0 失败 |
| Fabric 编译 | `./gradlew :fabric:compileJava --configure-on-demand` | 通过 |
| Forge 编译 | `./gradlew :forge:compileJava --configure-on-demand` | 通过（首次因缺少缓存依赖失败，联网补齐后通过） |

新增单测覆盖：

- 注册表条目、提示长度上限与必需能力名；重复注册、只读能力要求区域授权、非法命名被拒绝。
- 合法调用（无参数、空对象、`count` 1/8/64）；未知能力、额外顶层字段、额外参数、重复字段、重复参数、数组/null/布尔/字符串数字、小数、溢出、0/65/-1、超过 1024 字符。
- 规划层：能力 JSON 成功分派、普通文本与旧 `{"action":...}` 失败、未知能力/坏参数/越界分别返回明确原因、无区域采集被拒、上下文失效、取消、超时与队列上限。

## 3. 未验证项（不得视为通过）

1. 真实客户端通过 `/mf task plan` 触发能力后，世界/库存的实际变化与 `callId` 关联结果。
2. Forge 运行时加载与执行；本轮只做编译。
3. 事件取消（保护插件/领地）在能力路径上的真实表现。
4. 停止、断线、死亡、换维度、世界卸载后的旧调用在真实多人环境中的行为。
5. 原生 `tool_calls`：本版本未实现，也不声称支持。
6. 四格矩阵、多人隔离与性能预算。

以上项目需要在 #33 的端到端验收或专门的真实客户端场景中补齐；本记录不把编译和单测描述为游戏内验证。
