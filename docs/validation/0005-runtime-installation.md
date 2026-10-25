# #2 开发运行与独立安装验收

日期：2026-10-25。验收对象是 M0 基础工程，不包含伙伴、聊天、记忆、导航、采集或真实模型服务。

## 结论

三项运行阻塞均已通过：

1. Fabric 与 Minecraft Forge 的正式工程开发客户端完成图形初始化。
2. 两侧普通 DedicatedServer 完整启动并创建离线配置；游戏停止与世界保存有记录，Forge 开发管道外壳非零的限制见下文。
3. `build/artifacts/` 的最终两个 JAR 在独立 1.21.1 安装目录中由加载器实际发现、加载和运行。

Minecraft EULA 由创作者本人阅读并接受。本文只记录该事实，不代表项目、加载器或任何依赖因此改变许可；工程没有自动修改 `eula.txt`。

## 环境

| 项目 | 值 |
| --- | --- |
| 源码提交 | `6e5f66c50587733e3eee9375eb07c3c1d70a58ee` |
| Minecraft | 1.21.1 |
| Java | Eclipse Temurin 21.0.13+9-LTS，便携目录 `/tmp/codex-mf-jdk21-213` |
| Gradle | Wrapper 8.8 |
| Fabric | Loader 0.20.1；Loom 1.7.4；Server Installer 1.1.2 |
| Minecraft Forge | 52.1.20；ForgeGradle 6.0.24 |
| 模组 | `minecraft_friends 0.1.0-SNAPSHOT` |
| 操作系统 | Linux 7.0.0-34-generic，x86_64 |
| 硬件 | Intel Core Ultra 9 275HX，24 线程 |
| 模型 | 离线 Mock，无 API key，无网络模型请求 |
| 隔离方式 | 仅使用临时运行目录和新建测试世界，不使用真实存档或账号 |

## 开发客户端

命令：

```bash
JAVA_HOME=/tmp/codex-mf-jdk21-213 \
PATH=/tmp/codex-mf-jdk21-213/bin:$PATH \
./gradlew :fabric:runClient --no-daemon --console=plain

JAVA_HOME=/tmp/codex-mf-jdk21-213 \
PATH=/tmp/codex-mf-jdk21-213/bin:$PATH \
./gradlew :forge:runClient --no-daemon --console=plain
```

结果：**通过**。两个客户端均实际创建 LWJGL 渲染后端、重载资源、创建 GUI 纹理图集并初始化音频设备，随后保持渲染循环，由验收者在窗口关闭前结束进程。

Fabric 脱敏证据：

```text
Loading Minecraft 1.21.1 with Fabric Loader 0.20.1
Loading 5 mods:
  - minecraft_friends 0.1.0-SNAPSHOT
Initializing Minecraft Friends with Fabric
Created default configuration at .../fabric/run/config/minecraft-friends.properties
No model credentials were loaded; Mock mode remains offline.
Model provider: MOCK (no network request or paid call is made during startup)
Startup completed without model credentials.
Backend library: LWJGL version 3.3.3-snapshot
Reloading ResourceManager: vanilla
Created: 1024x512x0 minecraft:textures/atlas/gui.png-atlas
OpenAL initialized on device No Output
```

Forge 脱敏证据：

```text
ModLauncher 10.2.4 starting: java version 21.0.13
Setting user: Dev
Backend library: LWJGL version 3.3.3+5
Initializing Minecraft Friends with Minecraft Forge
Created default configuration at .../forge/run/config/minecraft-friends.properties
No model credentials were loaded; Mock mode remains offline.
Model provider: MOCK (no network request or paid call is made during startup)
Startup completed without model credentials.
MinecraftForge v52.1.20 Initialized
Reloading ResourceManager: vanilla, mod_resources
Created: 1024x512x0 minecraft:textures/atlas/gui.png-atlas
OpenAL initialized on device No Output
```

两端仅出现原版资源、音频和着色器告警，没有模组异常、类加载失败或客户端崩溃。开发客户端使用临时开发账号 `Dev`。

本地脱敏日志与 SHA-256：

| 日志 | SHA-256 |
| --- | --- |
| `/tmp/mf-runtime-evidence-6e5f66c/fabric-dev-client/full.log` | `02c03e7562812ae017853f4370cc8313f3685bac19d6887ef69be0b0ccfd8d70` |
| `/tmp/mf-runtime-evidence-6e5f66c/forge-dev-client/full.log` | `76f4c70877be49ca103d9b9feff0058c2a7fa494983e631f6405930f51f99a88` |

## 开发专用服务器

命令：

```bash
JAVA_HOME=/tmp/codex-mf-jdk21-213 \
PATH=/tmp/codex-mf-jdk21-213/bin:$PATH \
./gradlew :fabric:runServer --no-daemon --console=plain

JAVA_HOME=/tmp/codex-mf-jdk21-213 \
PATH=/tmp/codex-mf-jdk21-213/bin:$PATH \
./gradlew :forge:runServer --no-daemon --console=plain
```

结果：**通过**。两侧都启动普通 `DedicatedServer` 并完成启动区域生成，不只是 GameTestServer 初始化或进程退出码 0。

| 加载器 | 完成启动证据 | 配置 | 停止 |
| --- | --- | --- | --- |
| Fabric | `Done (0.939s)! For help, type "help"` | 创建并读取默认离线 Mock 配置 | 控制台 `stop`，Gradle 退出码 0 |
| Forge | `Done (1.124s)! For help, type "help"` | 创建并读取默认离线 Mock 配置 | 保存世界后由验收脚本终止，Gradle 外壳非零 |

脱敏证据：

```text
Fabric: Initializing Minecraft Friends with Fabric
Fabric: Model provider: MOCK (no network request or paid call is made during startup)
Fabric: Done (0.939s)! For help, type "help"
Fabric: Stopping server
Fabric: BUILD SUCCESSFUL

Forge: Initializing Minecraft Friends with Minecraft Forge
Forge: Model provider: MOCK (no network request or paid call is made during startup)
Forge: Done (1.124s)! For help, type "help"
Forge: Stopping server
Forge: All dimensions are saved
```

`forge:runServer` 虽然启用了 GameTest 命名空间属性，但实际启动的是普通专用服务器，以上判定只依据 `DedicatedServer` 的 `Done` 和完整停止序列，不使用“没有测试函数”的 GameTestServer 结果。

Forge 开发专服通过 Gradle 的管道启动时，控制台 `stop` 没有转发到游戏进程。验收脚本在确认 `Done` 后终止外层进程，游戏自身执行了停止和世界保存，但 Gradle 外壳最终非零。因此 Forge 开发专服的“通过”依据是启动完成和游戏内关闭路径，明确不以该外壳退出码作为成功证据。最终 JAR 的独立 Forge 安装直接启动 Java 进程，控制台 `stop` 和退出码 0 均正常。

开发专服复验日志与 SHA-256：

| 日志 | SHA-256 |
| --- | --- |
| `/tmp/mf-runtime-evidence-6e5f66c/clean-stop/fabric-dev-server.log` | `a67b6af3985855a16bd9c3ff3e36df2c20895735a57fc1a2bb6862c93a0d4f05` |
| `/tmp/mf-runtime-evidence-6e5f66c/clean-stop/forge-dev-server-clean-stop.log` | `824363226ab5d58ce288ce4a52a276c74535040a78a32e616e37dd5b20e43f77` |

## 最终 JAR 独立安装

运行根目录 `./gradlew checkAll --no-daemon --console=plain`，结果：**通过**。公共核心测试、两侧检查和产物结构校验成功，产物位于 `build/artifacts/`。

在两个全新临时目录安装加载器，只把对应最终 JAR 放入 `mods/`：

```text
/tmp/minecraft-friends-independent/fabric-server
/tmp/minecraft-friends-independent/forge-server
```

哈希逐字节一致：

| 产物 | SHA-256 |
| --- | --- |
| `minecraft-friends-fabric-1.21.1-0.1.0-SNAPSHOT.jar` | `8bdc191611627be9a909b77c9324a639438e0e2f7b33b2389dd4e5c811973209` |
| `minecraft-friends-forge-1.21.1-0.1.0-SNAPSHOT.jar` | `4803635a59315f95eb43e47aa29ed6ca03ac501cddf7ed1873c603217a54f63e` |

Fabric 独立安装实际加载：

```text
Loading Minecraft 1.21.1 with Fabric Loader 0.20.1
Loading 5 mods:
  - minecraft_friends 0.1.0-SNAPSHOT
Initializing Minecraft Friends with Fabric
Created default configuration at ./config/minecraft-friends.properties
Model provider: MOCK (no network request or paid call is made during startup)
Startup completed without model credentials.
Done (1.047s)! For help, type "help"
Stopping server
All dimensions are saved
```

Forge 独立安装实际加载：

```text
ModLauncher 10.2.4 starting: java version 21.0.13
Initializing Minecraft Friends with Minecraft Forge
Created default configuration at .../forge-server/config/minecraft-friends.properties
Model provider: MOCK (no network request or paid call is made during startup)
Startup completed without model credentials.
Done (1.173s)! For help, type "help"
Stopping server
All dimensions are saved
```

结果：**通过**。两个目录都完成普通服务端启动、新建世界、默认配置创建、无密钥 Mock 初始化和正常停止。未出现客户端类、渲染类或 OpenGL 类加载错误。

Forge 首次启动记录了 `server.properties` 不存在后自动创建的非致命信息，以及一次 Forge 更新检查网络超时；这两项都不影响模组加载或服务器完成启动。日志保留这些实际告警，不将其当作通过证据。

独立安装脱敏日志与 SHA-256：

| 日志 | SHA-256 |
| --- | --- |
| `/tmp/mf-runtime-evidence-6e5f66c/clean-stop/fabric-install.log` | `ca609ae96b1afc9df756598a9904031461b080e6a983422aff41d7da6eea62a3` |
| `/tmp/mf-runtime-evidence-6e5f66c/clean-stop/forge-install.log` | `413ce44dcbbae7e27bc45dec3d39369a7c398368e9c83058222fce71ce0d5de1` |

## 验收异常与处理

- 本次网络环境下，ForgeGradle 启动前的 HTTPS 环境探测出现连接超时。复验 Forge 开发专服时只在验收命令增加 `-Dnet.minecraftforge.gradle.check.certs=false` 跳过该构建环境探针；该参数不关闭游戏运行时网络能力，也不改变模组代码。
- Forge 开发专服的外层 Gradle 进程因前一项控制台转发问题收到验收脚本终止信号，返回非零。游戏日志仍明确记录 `DedicatedServer` 完成启动、执行 `Stopping server` 和保存全部维度。没有把非零外壳退出码隐藏或改写成正常退出。
- Forge 独立安装的首次启动出现 `server.properties` 缺失后自动创建，以及一次 Forge 更新检查网络超时。两者均发生在服务器正常工作期间，后续复验正常完成启动与控制台停止。
- 没有观察到客户端类、渲染类或 OpenGL 类加载错误。退出码 0、JAR 内存在类文件或 GameTestServer 初始化都没有被单独作为通过证据。

## 状态与限制

| 验收项 | 状态 |
| --- | --- |
| Fabric 开发客户端 | 通过 |
| Forge 开发客户端 | 通过 |
| Fabric 普通开发 DedicatedServer | 通过 |
| Forge 普通开发 DedicatedServer | 通过 |
| `checkAll` 与产物哈希 | 通过 |
| Fabric 最终 JAR 独立安装运行 | 通过 |
| Forge 最终 JAR 独立安装运行 | 通过；控制台 `stop`，Java 退出码 0 |
| Minecraft EULA | 创作者本人已接受 |
| 真实存档、真实账号、付费模型 | 未使用 |
| 伙伴、聊天、记忆、导航、采集 | 未实现，未测试 |
| 双人隔离、断线和 Alpha 游戏内场景 | 未测试，属于后续任务 |

本记录关闭 #2 的运行与安装阻塞，不把基础加载验收扩展成玩法或正式 Alpha 发布验收。
