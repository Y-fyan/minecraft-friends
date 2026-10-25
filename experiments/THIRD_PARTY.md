# 独立实验的第三方工具与材料

本清单针对 `player-control`，不是正式模组所有依赖的完整清单，也不选择 Minecraft Friends 自身的许可证。实验不复制 minecraft-numen 或 Carpet 的实现；探针通过公开 Minecraft/加载器 API 自行编写。

| 工具 / 材料 | 固定版本 | 来源与许可记录 | 仓库是否分发 |
| --- | --- | --- | --- |
| Gradle / Wrapper | 8.8 | [Gradle](https://github.com/gradle/gradle/tree/v8.8.0)，Apache-2.0；Wrapper 自带 `META-INF/LICENSE`，另保留 [原许可](GRADLE_WRAPPER_LICENSE.txt) | 仅分发官方生成的启动脚本与 Wrapper JAR；分发包由官方 URL 下载并核对锁定摘要 |
| Fabric Loader | 0.16.14 | [上游](https://github.com/FabricMC/fabric-loader)，Apache-2.0 | 不分发依赖 JAR |
| Fabric API | 0.116.17+1.21.1 | [上游](https://github.com/FabricMC/fabric)，Apache-2.0 | 不分发依赖 JAR |
| Fabric Loom | 1.7.4 | [上游](https://github.com/FabricMC/fabric-loom)，MIT | 由构建解析，不分发插件 |
| Minecraft Forge | 1.21.1-52.1.20 | [上游](https://github.com/MinecraftForge/MinecraftForge)；本轮读取的源码文件标明 LGPL-2.1-only，各组件仍按自己的许可 | 不分发游戏或 Forge JAR |
| ForgeGradle | 6.0.24 | [上游](https://github.com/MinecraftForge/ForgeGradle)，LGPL 2.1；以该版本上游声明为准 | 由构建解析，不分发插件 |
| Minecraft 游戏代码、映射与资源 | Java 1.21.1 | 由官方开发依赖下载；遵守 Mojang 对应条款，不作为项目自有许可材料 | 不提交游戏 JAR、反编译代码、游戏素材或世界 |
| 空 GameTest 结构 | 本实验 1.0.0 | 本轮自行生成的最小空结构，不是从真实世界导出 | 只包含空结构 NBT |

运行器只把本机现有无凭据代理临时传给 Gradle，不写入仓库。新增或替换工具需更新版本、来源与许可；发布正式模组时仍须重新核对最终打包内容及所有传递依赖，不能把此表当作所有组件的统一许可。
