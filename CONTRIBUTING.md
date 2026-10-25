# 贡献指南

欢迎一起开发 Minecraft Friends。当前目标是 Minecraft Java 1.21.1 的 Fabric / Minecraft Forge AI 玩家伙伴，初期范围见 [路线图 #12](https://github.com/Y-fyan/minecraft-friends/issues/12)。

## 开始之前

阅读 [README](README.md)、[开发规范](docs/DEVELOPMENT.md) 和 [测试规范](docs/TESTING.md)。涉及玩家控制、网络或模块边界时，同时阅读 [架构草案](docs/ARCHITECTURE.md)。

当前已有 Java 21 公共核心、Fabric / Forge 最小工程、Gradle Wrapper 和 CI，但伙伴与游戏玩法尚未实现。可执行命令、配置字段和产物路径以 [构建指南](docs/BUILD.md) 为准，不把规划能力写成已经可用。

## 认领与分工

1. 先搜索现有 Issue；已有任务就在原帖讨论，避免重复创建。
2. 评论说明想负责的范围、方案和依赖。维护者确认后，在同一次更新中设置 Assignee 和唯一的 `status:in-progress`，移除 `status:ready` 等旧状态，并记录交付范围；不要只口头确认而留下无人负责的任务。仓库外贡献者可以通过 Fork 提交。
3. 先对齐跨模块接口，再并行实现。一个 Issue 可拆为多个小 PR，每个 PR 保持可独立审查。
4. 进度受阻时记录复现信息、已尝试方法和需要的帮助。暂停参与时及时说明，便于其他成员接手。

新增功能提案至少写明用户场景、目标版本、范围、验收标准与前置依赖。缺陷报告包含游戏/加载器/模组版本、最小复现步骤、期望与实际结果、脱敏日志。

从 [新建 Issue](https://github.com/Y-fyan/minecraft-friends/issues/new/choose) 选择缺陷报告或功能提案表单；已有任务的范围确认、方案补充和进度更新留在原任务中。

## 分支流程

`main` 保存已审查的工作。工程建立后，应保持默认分支能够通过已配置的构建和检查。常规开发使用分支与 Pull Request；初始文档引导提交完成后按此执行。

```bash
git clone https://github.com/Y-fyan/minecraft-friends.git
cd minecraft-friends
git switch main
git pull --ff-only origin main
git switch -c docs/3-contributing
```

分支格式为 `<类型>/<Issue编号>-<简短英文描述>`，例如 `feat/6-chat-routing`、`fix/9-follow-timeout`、`docs/3-contributing`、`test/11-disconnect`。不共享开发分支，不向 `main` 强制推送。同步失败先检查分叉和本地修改，不使用重置命令丢弃他人工作。

Fork 贡献者将自己的 Fork 作为推送目标，通过 GitHub 向本仓库 `main` 发起 PR；以上直接克隆示例适用于有写入权限的协作者。

## 提交与作者署名

使用自己的名字和已关联本人 GitHub 的邮箱。可在仓库内设置，避免影响其他项目：

```bash
git config --local user.name "你的名字"
git config --local user.email "你的 GitHub 已关联邮箱"
```

需要隐藏邮箱时，从 GitHub 的 **Settings → Emails** 复制本人提供的 `noreply` 地址，不使用示例地址或别人的邮箱。提交前用 `git diff` 检查内容，并使用具体路径暂存文件，避免夹带凭据和存档：

```bash
git add CONTRIBUTING.md
git diff --cached
git commit -m "docs: 完善贡献与署名规范"
git push -u origin docs/3-contributing
```

GitHub 新式隐私邮箱通常是 `数字ID+用户名@users.noreply.github.com`，部分旧账号格式不同，以本人 Emails 页面为准，不根据昵称猜地址。本地 `git show --no-patch --format='%an <%ae>' HEAD` 只能核对写入提交的作者；推送后再查看该提交的 GitHub 作者链接是否指向本人。需要自动核对时，可用 `gh api repos/Y-fyan/minecraft-friends/commits/提交SHA --jq '.author.login'`；结果为空时检查邮箱关联与 GitHub 处理状态，不能用本地配置冒充关联已验证。

提交消息格式为 `<type>: <具体改动>`，常用 `feat`、`fix`、`docs`、`refactor`、`test`、`build`、`ci`、`chore`。标题说清结果，必要时正文说明原因和关联任务。

优先采用保留原作者提交的合并方式。确需 squash 时先核对最终作者与实际参与者，不能把多人改动全部归为合并者。实际共同完成的提交可添加 `Co-authored-by: 姓名 <关联邮箱>`，邮箱应由参与者提供；没有参与的人员不添加署名。不擅自重写已共享的历史。

仓库 [Contributors](https://github.com/Y-fyan/minecraft-friends/graphs/contributors) 统计默认分支中的作者提交，邮箱需要关联 GitHub 账号；合并提交本身、空提交不计入该图，图表最多展示前 100 位贡献者。开 Issue、审查和讨论也有价值，但不等于该图中的代码提交贡献。不能用提交次数或行数评价贡献质量。

规则依据：[仓库贡献统计](https://docs.github.com/en/repositories/viewing-activity-and-data-for-your-repository/viewing-a-projects-contributors)、[共同作者署名](https://docs.github.com/en/pull-requests/how-tos/commit-changes/creating-a-commit-with-multiple-authors)。

## Pull Request

使用仓库的 [PR 模板](.github/pull_request_template.md)，写清具体问题、修改后的行为、验证证据和限制。未完成可先开 Draft，便于讨论接口。

- 完整解决任务时写 `Closes #编号`；仅推进一部分时写 `Refs #编号`，不要提前关闭任务。
- UI/聊天效果提供截图或示例；行为变更提供复现步骤与结果。没有运行的测试明确标注，不把 Mock 结果描述为真实游戏验证。
- 普通文档改动检查链接、内容与实际状态即可，无需专门编写自动化测试。
- 修改一侧加载器也要说明另一侧影响；受影响环境未测时保留明确记录。
- 新增第三方依赖说明用途、版本、许可和必要性；不得夹带无关大规模格式化或系统修改。
- 技术验证 PR 同时提供版本化装置源码、依赖版本、完整构建/运行入口、关键断言及脱敏结果。代码放在 `experiments/` 或对应测试目录，报告放在 `docs/validation/` 并记录源码提交；一次性实验也不能只提交他人无法运行的命令。若装置不能公开，明确限制，不能标为可独立复现。

合并前原则上由另一位成员审查；维护者检查需求范围、实现、测试、署名和许可，并确认已配置的必需检查通过。若暂时只有一位维护者，保留自审及验证记录，明确缺少独立审查。分支保护和 CI 是否启用以仓库实际设置为准，本文件不代表已配置。

只有任务验收条件全部满足才关闭 Issue；部分交付在原任务中列出剩余项。合并后删除已完成的远端功能分支，下一任务从更新后的 `main` 开始。

## 原创、沟通与敏感信息

讨论聚焦事实、复现和用户体验，尊重不同经验水平的贡献者。AI 辅助开发同样需要提交者理解改动、核实来源并完成验证，不能以“模型生成”替代审查。

可在 PR 中说明 AI 辅助的范围、人工核对与实际执行的验证；这项说明是可选透明约定，不要求把工具当成人类共同作者。署名始终对应实际参与者，不能批量改写他人的作者信息。

不复制参考项目的代码、提示词、Lua API、界面、文档或素材。复用其他第三方材料先核对许可并保留必要声明。本项目原创代码与文档采用 [GPL-3.0-only](LICENSE)，贡献按该许可提交并保留作者信息；第三方工具、依赖和材料仍遵循各自许可。许可范围见 [说明](docs/LICENSING.md)，变更许可证须由创作者明确决定。

Issue、PR、截图、日志均不包含 API key、令牌、账号密码或完整私人聊天。涉及敏感凭据的材料先脱敏；误泄露时立即撤销或轮换凭据，并联系维护者处理残留。
