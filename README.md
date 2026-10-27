# Minecraft Friends

在 Minecraft 中，拥有一个能聊天、记住共同经历、陪你探索并帮忙干活的 AI 伙伴。

Minecraft Friends 计划为 **Minecraft Java 1.21.1** 提供 **Fabric** 和 **Minecraft Forge** 两个版本。目标是让 AI 操控具有玩家身份、背包和生存能力的伙伴，通过原版聊天框与玩家交流，在实际游戏规则下执行任务。

> **项目状态：M0、M1、M2 已通过技术验收，提供 Alpha 预览构建。** 伙伴能聊天、记忆、正常跟随、等待和停止，并在用户授权区域采集橡木、返回交付。Fabric / Minecraft Forge 的单机、普通专服、双玩家隔离和本机性能对照均通过；详见 [M2 验收记录](docs/validation/0014-m2-acceptance.md)。默认启用并使用明确标注的离线演示模型；真实聊天需要配置自己的服务。首次试用见 [安装与短演示](docs/M2-INSTALL.md)。Forge 不等于 NeoForge。

## 我们希望带来的体验

- **自然交流**：在聊天框中和伙伴说话，分享今天的经历，也可以提出游戏内请求。
- **持续陪伴**：伙伴有稳定的名字与性格，记住你明确告诉它的偏好，以及一起完成的事情。
- **一起行动**：跟随、等待，面对受伤、重逢和任务完成等事件作出恰当回应。
- **帮忙干活**：理解“收集 8 个橡木原木并交给我”，实际寻找、挖掘、拾取并交付，按真实结果汇报。
- **随时叫停**：用户可以取消任务，查看并删除记忆，调整主动发言频率。

初期采用服务端玩家实体，它拥有玩家身份与生存能力，但不是经过认证的真人账号。选择依据和限制见 [ADR 0001](docs/decisions/0001-player-control-route.md) 与 [独立验证记录](docs/validation/0002-player-control-reproduction.md)。

## 当前实现

| 能力 | 状态 |
| --- | --- |
| Java 21 公共核心 | 已实现；包含配置、模型契约、Mock Provider 和任务终态规则 |
| Fabric / Forge 1.21.1 工程 | 已构建；分别产出独立发行 JAR |
| 专服类隔离 | 已扫描；发行 JAR 不引用客户端或渲染类 |
| 无密钥启动 | 已验证；创建离线配置且不发起模型请求 |
| 伙伴身份、主人归属与生命周期 | 已实现；双加载器实际单机/专服验证创建、恢复与死亡规则，坏存档保护通过 |
| 私聊、共享会话、称呼与记忆 | 私聊、显式共享与双人隔离、称呼及记忆恢复/删除均通过 |
| 导航、采集与交付 | 已实现有界地表跟随、木门、等待/停止及一次性授权区域橡木采集；双加载器四格及专项验收通过 |
| 模型服务 | 已通过 DeepSeek 官方 API 真实聊天和故障恢复验收；默认仍为离线演示 |
| 客户端内运行 | Fabric / Forge 实际客户端主流程通过，模型后端为明确标注的协议模拟 |

构建、测试和开发运行步骤见 [构建指南](docs/BUILD.md)。

## 初期版本目标

第一个 Alpha 以这个场景验收：

1. 用户在测试世界中创建一个有名字的伙伴。
2. 在原版聊天框交流，伙伴记住用户指定的称呼。
3. 伙伴能够跟随用户，并听从等待和停止请求。
4. 用户指定采集区域后，请伙伴收集 8 个橡木原木并交付。
5. 退出并重新进入世界，伙伴身份与记忆按配置恢复。

语音、多伙伴群聊、复杂建筑、自动战斗和跨维度自主探索留待后续讨论。初期也不承诺兼容任意纯净服务器或所有整合包。

## 路线图

| 阶段 | 目标 | 对应任务 |
| --- | --- | --- |
| M0 技术验证与协作基础（已完成） | 已验证玩家控制基础路径，建立双加载器工程和协作约定 | [#1](https://github.com/Y-fyan/minecraft-friends/issues/1)、[#2](https://github.com/Y-fyan/minecraft-friends/issues/2)、[#3](https://github.com/Y-fyan/minecraft-friends/issues/3) |
| M1 陪伴聊天闭环（已验收） | 伙伴身份、模型接入、聊天、人设与记忆 | [#4](https://github.com/Y-fyan/minecraft-friends/issues/4)、[#5](https://github.com/Y-fyan/minecraft-friends/issues/5)、[#6](https://github.com/Y-fyan/minecraft-friends/issues/6)、[#7](https://github.com/Y-fyan/minecraft-friends/issues/7) |
| M2 干活能力与 Alpha（已验收） | 感知、动作调度、跟随、采集交付及双加载器验收 | [#8](https://github.com/Y-fyan/minecraft-friends/issues/8)、[#9](https://github.com/Y-fyan/minecraft-friends/issues/9)、[#10](https://github.com/Y-fyan/minecraft-friends/issues/10)、[#11](https://github.com/Y-fyan/minecraft-friends/issues/11) |

完整计划见 [路线图 #12](https://github.com/Y-fyan/minecraft-friends/issues/12)，进度以 [Issues](https://github.com/Y-fyan/minecraft-friends/issues) 和 [里程碑](https://github.com/Y-fyan/minecraft-friends/milestones) 为准。

## 技术与运行边界

| 项目 | 初期约定 |
| --- | --- |
| 游戏 | Minecraft Java 1.21.1 |
| 加载器 | Fabric、Minecraft Forge，分别构建与验证 |
| 开发环境 | Java 21、Gradle Wrapper 8.8；加载器、映射和插件版本已固定 |
| 模型 | 默认离线 Mock；显式配置后支持异步 Chat Completions；仅服务端读取指定环境变量凭据 |
| 部署 | M2 两个独立 JAR 与首次安装已验收，交付 Alpha 预览构建 |
| 控制 | 模型输出结构化意图，游戏执行器校验并执行，世界操作由服务端最终授权 |

真实模型调用需要用户显式配置服务地址、模型和凭据来源，可能产生服务商费用。凭据只从指定的服务端环境变量读取，不写入 properties；配置与数据流见 [模型说明](docs/MODEL.md)。运行时会创建 `config/minecraft-friends.properties`；字段和限制见 [构建指南](docs/BUILD.md#配置)。

真实 Provider 会把会话及必要上下文发送至用户配置的服务。默认不采集全服聊天，不在诊断日志中记录密钥或完整对话。私聊和记忆按世界、主人、伙伴隔离；共享会话不会导入私人历史或长期记忆。删除与清空入口见 [聊天说明](docs/CHAT.md)。

## 参与开发

先查看 [贡献指南](CONTRIBUTING.md)，在对应 Issue 中认领任务，再从最新 `main` 创建分支。每个 PR 说明改动、验证方法和未完成项，保留真实作者署名。

| 文档 | 内容 |
| --- | --- |
| [贡献指南](CONTRIBUTING.md) | 认领、分支、提交、PR、审查与贡献统计 |
| [构建指南](docs/BUILD.md) | 固定工具链、构建、开发运行、配置与 EULA 边界 |
| [M0 运行与安装验收](docs/validation/0005-runtime-installation.md) | 双端客户端、普通专服与最终 JAR 独立安装证据 |
| [M0 收尾记录](docs/validation/0006-m0-closeout.md) | 阶段交付、证据复核、已知限制与 M1 入口 |
| [伙伴生命周期验证](docs/validation/0007-companion-lifecycle.md) | 原始部分验收与历史限制 |
| [生命周期修复](docs/validation/0008-lifecycle-fixes.md) | 重生时机、严格掉落、冲突及坏存档保护 |
| [M1 协议与联机验收](docs/validation/0009-m1-acceptance.md) | 双加载器主流程、双人共享隔离、事件与记忆删除证据及剩余项 |
| [模型配置](docs/MODEL.md) | 异步协议、凭据、预算、取消和失败反馈 |
| [M1 真实模型验收](docs/validation/0010-m1-real-model.md) | DeepSeek 官方 API、双加载器三类事件、故障恢复及最终审阅 |
| [聊天入口](docs/CHAT.md) | 私聊、共享会话、命令和数据范围 |
| [记忆核心](docs/MEMORY.md) | 来源、持久化、删除、隔离和冷却 |
| [项目统筹](docs/COORDINATION.md) | 近期交付、依赖、职责、状态与推进节奏 |
| [开发规范](docs/DEVELOPMENT.md) | 编码、线程、动作、配置、日志与依赖约定 |
| [架构草案](docs/ARCHITECTURE.md) | 分层、数据流、公共接口与待决事项 |
| [测试与发布规范](docs/TESTING.md) | 四格环境矩阵、失败场景与发布门槛 |
| [依赖与许可](docs/DEPENDENCIES.md) | 直接依赖、用途、版本和许可 |
| [配置示例](docs/examples/minecraft-friends.properties) | 与生产默认值一致的离线配置 |
| [项目许可](docs/LICENSING.md) | GPL-3.0-only 与第三方材料边界 |
| [玩家控制 ADR 0001](docs/decisions/0001-player-control-route.md) | 服务端玩家实体与真实账号客户端的路线取舍 |
| [玩家控制验证](docs/validation/0002-player-control-reproduction.md) | 独立探针源码、四组结果与可追溯证据 |
| [ADR 0002](docs/decisions/0002-multiloader-layout.md) | 公共源码加双加载器发行 JAR 的取舍 |
| [架构决策模板](docs/decisions/0000-template.md) | 技术选择的背景、证据、代价与结论 |

M0 的 #1、#2、#3 已关闭；当前由创作者授权的统筹助手监督 M1 分工实现并验收。#4–#7 的关闭以实际验收证据为准，未完成项不会因编译成功而提前关闭。根目录命令 `./gradlew checkAll --no-daemon` 运行公共测试并生成两个 JAR。实验目录仅用于独立测试世界，使用方法见各实验 README。

## 原创与许可

功能研究参考了 [minecraft-numen](https://github.com/Dwinovo/minecraft-numen/)。本项目独立设计与实现，不复制其代码、提示词、Lua API、界面、文档或素材；第三方依赖必须单独核对许可并记录来源。

本项目原创代码与文档按 [GNU GPL v3（GPL-3.0-only）](LICENSE) 发布，Copyright (C) 2026 Y-fyan and contributors。保留真实作者与第三方许可声明；许可范围见 [项目许可说明](docs/LICENSING.md)。
