# 伙伴身份与生命周期验收

日期：2026-10-25。关联 [Issue #4](https://github.com/Y-fyan/minecraft-friends/issues/4)。实现提交：[`dc1c9c0c5cedf9d91c3ab340cc2f15573915b7cf`](https://github.com/Y-fyan/minecraft-friends/commit/dc1c9c0c5cedf9d91c3ab340cc2f15573915b7cf)。本记录只覆盖服务端伙伴身份、归属与生命周期；聊天、记忆、模型调用和移动 AI 不在范围。

## 固定环境

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| Java | Eclipse Temurin 21.0.13+9-LTS (`/tmp/codex-mf-jdk21-213`) |
| Gradle | 8.8 Wrapper |
| Fabric Loader / API / Loom | 0.20.1 / 0.116.17+1.21.1 / 1.7.4 |
| Minecraft Forge / ForgeGradle | 52.1.20 / 6.0.24 |

没有真实账号、模型凭据或真实存档。两侧 GameTest 在独立实验运行目录运行；普通开发专服在 `fabric/build/run/server` 和 `forge/build/run/server` 创建独立世界。创作者已本人接受 EULA，`eula=true` 仅写入这两个临时专服目录。没有升级系统、内核或显卡驱动，也没有重启、注销或停止桌面。

## 自动化运行

从仓库根目录执行：

```bash
JAVA_HOME=/tmp/codex-mf-jdk21-213 PATH=/tmp/codex-mf-jdk21-213/bin:$PATH ./gradlew checkAll --no-daemon
```

结果：**通过**。公共测试、Fabric/Forge 编译、最终 JAR 汇集与入口结构检查均通过。发行 JAR SHA-256：

| 产物 | SHA-256 |
| --- | --- |
| Fabric 1.21.1 | `15cac3581672116bf99f70ef626d37909c59cb000144b0f11cca3e9c67699cb1` |
| Forge 1.21.1 | `9e4c9424dbf2c6515d8503a10785c1cc354e289253e42a4098fe729d102c80a9` |

从仓库 `experiments/` 目录执行：

```bash
JAVA_HOME=/tmp/codex-mf-jdk21-213 PATH=/tmp/codex-mf-jdk21-213/bin:$PATH player-control/gradlew -p companion-lifecycle :fabric:runGameTest :forge:runGameTestServer --no-daemon --console=plain -x :forge:downloadAssets -x :forge:downloadMCMeta
```

Fabric 与 Forge 各 1 个生命周期 GameTest 均通过。证据行使用脱敏 UUID，以下结果均由正式 `CompanionRuntime` 执行：

| 场景 | Fabric | Forge |
| --- | --- | --- |
| 伙伴 UUID 与主人 UUID 独立；创建及重复创建幂等 | 通过 | 通过 |
| 暂离、重新召唤后 UUID、背包和生命值保留 | 通过 | 通过 |
| 保存并恢复伙伴所在维度 | 通过 | 通过 |
| 主人断线卸载、重连恢复 | 通过 | 通过 |
| 死亡后按同一伙伴 UUID 重生；背包未复制回身上 | 通过 | 通过 |
| 两名主人各自拥有独立 UUID 与记录 | 通过 | 通过 |
| 关服卸载活动实体并保留下次恢复状态 | 通过 | 通过 |
| `keepInventory=false` 时原木实际掉落实体数量 | 未测；GameTest 报告 0，未能确认掉落实体行为 | 未测；GameTest 报告 0，未能确认掉落实体行为 |

GameTest 测试服务器不能替代 IntegratedServer 或普通 DedicatedServer。它通过 API 直接调用 `createFor`、`dismissFor` 和 `summonFor`；命令树已注册并编译，但本轮未通过真实玩家聊天手动执行命令。

## 普通专用服务器

使用 `./gradlew :fabric:runServer --no-daemon --console=plain` 与 `./gradlew :forge:runServer --no-daemon --console=plain`，运行目录分别为 `fabric/build/run/server`、`forge/build/run/server`。

| 环境 | 结果 | 脱敏证据 |
| --- | --- | --- |
| Fabric DedicatedServer | **通过** | `Minecraft 1.21.1`、`minecraft_friends 0.1.0-SNAPSHOT`、Fabric API `0.116.17+1.21.1`、离线 Mock 初始化，`Done (0.589s)`；控制台 `stop` 后三维度全部保存，Gradle `BUILD SUCCESSFUL` |
| Forge DedicatedServer | **通过** | Forge `52.1.20`、`minecraft_friends 0.1.0-SNAPSHOT`、离线 Mock 初始化，`Done (2.425s)`；停止后日志包含 `Stopping server`、三维度保存及 `All dimensions are saved` |

两侧均从新建的测试世界启动，未见客户端类加载错误。Forge 停止阶段使用进程中断触发服务端正常关闭钩子；日志记录了完整世界保存。

## 未完成验收

| 场景 | 结果 | 说明 |
| --- | --- | --- |
| Fabric 单机 IntegratedServer 游戏内流程 | **未测** | 临时开发客户端完成资源初始化后未到达主菜单/世界，未产生玩法证据；仅停止该测试客户端进程，未影响桌面 |
| Forge 单机 IntegratedServer 游戏内流程 | **未测** | 未运行 |
| 普通玩家执行 `/mf companion create/summon/dismiss/status` | **未测** | 命令由服务端注册；本轮 GameTest 直接调用正式服务 API |
| 最终 JAR 独立安装后重复上述行为 | **未测** | `checkAll` 证明构建结构，不证明独立安装行为 |
| `keepInventory=false` 的死亡掉落实体 | **未测** | 两侧测试装置均记录 0 个原木实体，不能据此确认符合 gamerule |

因此 Issue #4 保持开放。需要补做两侧 IntegratedServer 的创建/退出/重进/死亡流程，并在普通游戏场景核实掉落规则；当前结果不足以关闭任务。

## 命令与身份规则

```text
/mf companion create [name]
/mf companion summon
/mf companion dismiss
/mf companion status
```

每位主人 UUID 只能登记一个伙伴。伙伴 UUID 随机生成并持久化，名称必须以 `MF_` 开头以明确区分伙伴与真人账号；不使用真人 UUID 或认证账号。所有操作按命令执行者 UUID 服务端授权。世界归属元数据保存于 `data/minecraft_friends_companions.dat`；生命值、背包、维度与位置交由原版玩家数据存储。Fabric 运行依赖已固定为 Fabric API `0.116.17+1.21.1`，Apache-2.0，详见 [依赖清单](../DEPENDENCIES.md)。
