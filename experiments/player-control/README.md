# 玩家控制独立探针

这是 Issue #1 的可复现验证装置，独立于 #2 的正式模组工程。代码为本轮新实现，不是 PR #15 中未提交的原探针，也不复用旧报告数字。**不得安装在已有存档或正式服务器中。**

## 环境与构建

- JDK 21；Gradle Wrapper 8.8（分发包 SHA-256 已锁定）。不会安装系统软件或修改默认 Java。
- Minecraft 1.21.1；Fabric Loader 0.16.14、API 0.116.17+1.21.1、Loom 1.7.4；Minecraft Forge 52.1.20、ForgeGradle 6.0.24。两侧均采用官方映射。
- Python 3 用于启动与结果校验。集成服验证还需可用显示环境、OpenGL 和客户端资源；可使用进程级软件渲染，不修改驱动。

在本目录执行：

```bash
./gradlew :fabric:build :forge:build
python3 run_probe.py fabric-server
python3 run_probe.py forge-server
python3 run_probe.py fabric-integrated
python3 run_probe.py forge-integrated
```

Windows 使用 `gradlew.bat`；按本机环境设置 `JAVA_HOME`。首次运行需要下载官方游戏依赖及资源，缓存完备后可添加 `--offline`。无界面 Forge 测试自动排除客户端资产下载。现有无认证本地 HTTP 代理可临时传给 Gradle，不写入仓库；带凭据的代理交由用户在外部配置。

`*-server` 运行原版 GameTestServer（Fabric/Forge 的测试启动器），**不是普通 DedicatedServer 安装验证**。`*-integrated` 会在独立开发客户端里新建随机命名的平坦世界，在真正的 IntegratedServer 上运行同一组测试，完成后自动退出。所有运行目录位于各模块 `build/run/`，不读取启动器账号或 `.minecraft`，不使用真实账号、API key、模型服务或付费调用。不自动替用户接受正式服务器 EULA。

## 检查内容

场景只使用服务器游戏线程，包含：加入玩家列表、速度冲量后的移动、直接挖掘原语与拾取、受伤、保存/移除/重进保留 7 个原木、死亡掉落 7 个、适配重生、重生后不复制物品及出站包丢弃。每项都输出带加载器、实际服务器类、tick、检查名与结果的 `MF_PROBE` JSON。

客户端/网络连接使用独立的 `ProbeConnection`，显式丢弃出站消息，不经过认证、网络握手或序列化；这不是完整客户端，更不证明网络协议、皮肤、自定义通道或资源包回执兼容。重生适配显式恢复生命值，不宣称引擎默认重生即可完成。冲量不是寻路，`destroyBlock` 原语不是正常耗时采集任务。

## 结果与失败

脚本要求进程成功、完整断言集合全部通过；集成服还要求客户端完成标志。单独退出码 0、没有执行到测试、超时或缺少检查均判失败。结果写入：

- `build/evidence/<目标>/summary.json`：断言、源码树摘要与退出状态。
- `build/evidence/<目标>/full.log`：本地完整诊断，不默认上传仓库。

提交验证证据时只选取脱敏摘要，记录对应源码提交与产物 SHA-256，不提交世界、完整日志、缓存或个人数据。失败运行保留在本地，不能覆盖成成功结论。实验资产仅包含自行生成的空结构，不分发 Mojang 游戏代码、资源或 JAR。

依赖来源： [Gradle](https://github.com/gradle/gradle)、[Fabric Loader](https://github.com/FabricMC/fabric-loader)、[Fabric API](https://github.com/FabricMC/fabric)、[Fabric Loom](https://github.com/FabricMC/fabric-loom)、[Forge](https://github.com/MinecraftForge/MinecraftForge)、[ForgeGradle](https://github.com/MinecraftForge/ForgeGradle)。使用其公开 API 和开发工具，项目自身采用 [GPL-3.0-only](../../LICENSE)，第三方许可保持不变；具体运行结果见仓库的验证记录。
