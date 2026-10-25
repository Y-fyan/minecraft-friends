# #2 基础工程落实复核

日期：2026-10-25。#3 完成后复核 #2，保留“构建交付”和“完整运行验收”的区别。

本文保留 PR #19 时的历史状态。后续 PR #20、#21 已补齐下述运行与安装缺项，#2 已关闭；最新证据见 [运行验收](0005-runtime-installation.md) 和 [M0 收尾](0006-m0-closeout.md)。

## 已落地的证据

- 基础工程 PR #17 已合并；原审查提交 `c7ca236` 的 [CI 40195940087](https://github.com/Y-fyan/minecraft-friends/actions/runs/40195940087) 完成测试、构建和上传，主分支相应构建亦通过。
- GPL 与协作收尾 PR #18 已合并，其 [CI 40197111461](https://github.com/Y-fyan/minecraft-friends/actions/runs/40197111461) 成功。本轮实际下载该次上传物，确认恰有 Fabric、Forge 两个非空 JAR，元数据为 GPL-3.0-only，内置许可正文与根 LICENSE 一致。
- Java 21、Gradle Wrapper、公共核心、加载器入口、Mock、配置初始化、状态机、构建与依赖说明均可从主分支取得。

## 发现并修复

1. 两侧 Minecraft 元数据原本允许其他未验证的 1.21.x；改为从统一目标版本生成精确依赖，当前为 1.21.1。
2. Forge 曾把生产 Java 类与资源指向同一输出目录。只更新资源/版本信息后，资源任务清理了入口类，`verifyArtifacts` 报缺少 `MinecraftFriendsForge.class`。修复为独立生产输出和由 Sync 独占的开发运行副本，并让开发启动显式依赖该副本。
3. 去掉共享目录后，仅用分离的类/资源直接开发运行会导致加载器找不到入口。补充独立 development 源集及其运行类路径后，再次观察到正式模组初始化与默认配置加载完成；没有把中间失败记录算作通过。

## 修复验证

- 默认 `checkAll` 通过，公共核心 11 项测试零失败。
- 用 `-Pproject_version=0.1.0-resource-check` 只改版本元数据重建通过，随后恢复默认版本重建也通过；无需手动 clean 才能保住入口类。
- CI 增加同类增量重建及恢复默认版本步骤，先通过入口校验再上传正常版本产物；远端运行结果以修复 PR 的实际检查为准。
- 最终两个 JAR 都包含对应入口类、精确 1.21.1 依赖与 GPL 正文。Forge 开发副本的入口类和 `mods.toml` 与最终 JAR 逐字节一致。
- Forge GameTest 启动器日志实际出现 `Initializing Minecraft Friends with Minecraft Forge`、`Model provider: MOCK`、`Startup completed without model credentials.`；不存在先前“constructed 0 mods”的入口缺失。
- 随后出现 `No test functions were given!`，因为正式工程尚无 GameTest 用例；已结束本次初始化冒烟进程。这是加载/配置证据，**不是游戏测试通过、普通服务器完成启动或长期运行成功**。

## #2 仍未验收的部分

普通开发客户端、接受使用者 EULA 后的普通 DedicatedServer，以及最终 JAR 在独立普通服务器目录中的安装/运行仍需补齐。未代用户接受 EULA；#1 独立探针的客户端/服务器测试也不能替代正式工程安装验证。

因此，基础工程代码与自动构建/上传已交付，复核中发现的版本声明与增量打包问题已处理，**#2 继续开放**，直到其原运行/安装验收获得证据。
