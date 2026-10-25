# 依赖与许可清单

本文件记录 M0 构建依赖。本项目原创代码采用 [GPL-3.0-only](../LICENSE)，不改变以下第三方许可，也不表示可以重新分发 Minecraft、Mojang 映射或 Forge 的 MCP 数据。发布前需按实际分发包复核。

| 依赖 | 固定版本 | 用途 | 许可 | 来源 |
| --- | --- | --- | --- | --- |
| Java / Temurin | 21 | 工具链与运行环境 | GPL-2.0 with Classpath Exception | [Eclipse Adoptium](https://adoptium.net/) |
| Gradle | 8.8 | Wrapper 与构建 | Apache-2.0 | [Gradle](https://gradle.org/) |
| Minecraft Java | 1.21.1 | 目标游戏 | Minecraft EULA / 专有 | [Minecraft](https://www.minecraft.net/eula) |
| Mojang official mappings | 1.21.1 | Fabric/Forge 编译映射 | Microsoft/Mojang 映射使用条款，不可完整原样再分发 | [Minecraft EULA](https://account.mojang.com/documents/minecraft_eula) |
| Fabric Loader | 0.20.1 | Fabric 运行与入口 | Apache-2.0 | [Fabric Loader](https://github.com/FabricMC/fabric-loader) |
| Fabric Loom | 1.7.4 | Fabric Gradle 插件 | MIT | [Fabric Loom](https://github.com/FabricMC/fabric-loom) |
| Minecraft Forge | 52.1.20 | Forge 运行与入口 | LGPL-2.1，仓库另有数据条款 | [Minecraft Forge](https://github.com/MinecraftForge/MinecraftForge) |
| ForgeGradle | 6.0.24 | Forge Gradle 插件 | LGPL-2.1 | [ForgeGradle](https://github.com/MinecraftForge/ForgeGradle) |
| JUnit Jupiter | 5.11.4 | 公共核心测试 | EPL-2.0 | [JUnit 5](https://github.com/junit-team/junit5) |
| SLF4J API | 2.0.16 | 加载器入口编译依赖 | MIT | [SLF4J](https://github.com/qos-ch/slf4j) |
| GitHub Actions 检出/Java/Gradle/上传组件 | 主版本标签 | CI | 各项目官方许可 | [GitHub Actions](https://github.com/actions) |

CLI 依赖仅用于构建和测试。当前两个发行 JAR 不打包 Fabric Loader、Forge、JUnit 或 SLF4J 实现。
