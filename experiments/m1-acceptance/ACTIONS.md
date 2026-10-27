# M2 独立动作验收装置（开发中）

本装置不代表 M2 已完成，也不代表 Alpha 已可发布。当前仅为烟雾测试：真正客户端发出固定命令，服务端用实际坐标、方块及库存核对移动/等待/停止和区域内采集交付。不会复制动作执行实现。

生产入口约定为 `/mf task follow|wait|stop|status`、`/mf task area x1 y1 z1 x2 y2 z2`、`/mf task collect oak_log 8`。在一个独立世界中先检查未授权区域不能采集，再授权恰好八块原木所在区域，最后核对主人收到八块、目标八块消失、伙伴未剩余原木、区域外原木不变，重复请求不会生成物品。跟随过程中逐 tick 检查位置跃变，不能用生产传送蒙混正常移动。测试地图初始化时的显式放置/定位不属于被测导航或采集。

## 构建和运行

先在已包含 M2 游戏执行器的分支运行根 `./gradlew checkAll --no-daemon` 生成正式 JAR。若产物中没有 ActionRuntime，运行器会明确拒绝，不能用 M1 产物冒充 M2。

```bash
JAVA_HOME=/tmp/codex-mf-jdk21-213 python3 experiments/m1-acceptance/run_actions.py fabric-integrated
JAVA_HOME=/tmp/codex-mf-jdk21-213 python3 experiments/m1-acceptance/run_actions.py forge-integrated
```

使用便携 JDK21，不安装系统软件。每次随机 `mf-m2-acceptance-` 世界，只允许开发身份 `MF_M2Owner`，测试配置为离线 mock，不读取或调用真实模型。成功和失败均保留源码/JAR 哈希、固定断言、退出码和时间。

普通专服必须自行在新隔离目录加载同字节正式 JAR、对应 helper、依赖和绑定 loopback 的服。世界名也必须是新建的 `mf-m2-acceptance-` 前缀；不能拿真实存档改名测试。以下参数缺一即拒绝，测试后向自建专服发送 stop 并确认世界保存和 Java 退出：

```bash
python3 experiments/m1-acceptance/run_actions.py fabric-dedicated \
  --server 127.0.0.1:25685 --server-log /绝对路径/本轮server.log \
  --server-mod /绝对路径/已安装minecraft-friends-fabric.jar \
  --server-helper /绝对路径/已安装mf-acceptance.jar
```

Forge 对应 `forge-dedicated`。专服启动设置 `-Dmf.acceptance=true -Dmf.acceptance.actions=true`，安装 helper 后才有测试场景。真实普通 DedicatedServer 与 IntegratedServer 类型分别断言，GameTestServer 不替代普通专服。

## 尚需额外验收

这份 smoke 不能独自关闭 #8–#11。任务 schema/非法参数/迟到/归属需公共与游戏层测试；两真实客户端隔离、不可达与卡住自动退出、满背包/死亡/工具/资源不足、领地取消、模型结构化规划、冷重进生命周期、新成员按文档实际试走及完整四格运行均须补证据。

装置中的 baseline/active 是服务器自身滑动平均 tick 耗时的简单采样，只供排查，不能当作正式基线 A/B 或分位数预算验收。正式性能报告还需同硬件/同地图关闭与开启对照、样本区间、p50/p95/p99、寻路/感知单 tick 预算和网络调用频率。没有这些证据不宣布 Alpha 性能达标。默认 summary 始终保留 `m2_complete=false`，最终范围由统筹助手逐项核验。

补充目标：`--scope door` 检查原版木门交互；`--scope planning` 用明确标注的 loopback 协议模拟检查严格计划、非法动作拒绝和 stop 后迟到回复。`--natural` 使用真实原版聊天包 `@MF_M2Bot 跟着我` 等指令；专服同时设置 `-Dmf.acceptance.actions.natural=true`。模型模拟不等同真实模型服务质量验收。
