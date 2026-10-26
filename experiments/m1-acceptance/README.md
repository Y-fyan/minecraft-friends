# M1 正式产物的隔离客户端验收助手

这是测试专用模组，不安装到真实存档。先从根目录运行 `./gradlew checkAll`，再用已有 Java 21 和显示环境运行本助手。Fabric 通过 Loom 重映射正式发行 JAR；Forge 从正式 JAR 编译接口，并把同一个 JAR 放入隔离运行目录。助手不复制生产源码或自行实现伙伴规则。

```bash
experiments/player-control/gradlew -p experiments/m1-acceptance :fabric:build :forge:build
python3 experiments/m1-acceptance/run_acceptance.py fabric-integrated
python3 experiments/m1-acceptance/run_acceptance.py forge-integrated
```

IntegratedServer 场景用真实开发客户端新建平坦世界，经原版网络连接发送 `/mf companion create/dismiss/summon`；测试助手只设置临时背包和伤害条件，再观察正式模组的恢复/死亡逻辑。严格检查死亡掉落正好 4 个原木、保留物品时背包 4 个且地上 0 个。关闭并重新打开同一个新建世界后，UUID、背包和生命必须恢复。

普通 DedicatedServer 场景需先准备单独的本地测试服务器，放入对应正式 JAR、本助手构建 JAR（Fabric 另需 Fabric API），使用独立测试世界并仅绑定 loopback。只有使用者本人接受 Minecraft EULA 后才能启动；助手不代用户接受。给服务端 Java 加 `-Dmf.acceptance=true`，然后运行：

```bash
python3 experiments/m1-acceptance/run_acceptance.py fabric-dedicated --server 127.0.0.1:25575
python3 experiments/m1-acceptance/run_acceptance.py forge-dedicated --server 127.0.0.1:25576
```

客户端仅允许连接显式传入的 loopback 地址。运行器只管理自己创建的客户端进程组；超时/中断仍记失败，不把关闭客户端的退出码 0 单独作为通过。专服启动和 `stop` 退出由启动者单独记录。它不安装软件、不改系统 Java 或显卡驱动，软件渲染仅作用于本次子进程。

结果位于实验 `build/evidence/<目标>-<时间>/`：完整本地日志及包含源码提交、正式 JAR/日志哈希、实际服务器类、必需断言和客户端结束标志的 JSON。只有完整证据与退出状态同时满足才通过。未运行的目标不能标为通过；不得将自动开发客户端流程称为真人人工试玩。当前助手覆盖生命周期，聊天与记忆仍需各自证据。
