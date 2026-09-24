# 决策页面（协作 · 提交准备 · 同步冲突）· 设计

> 模块：`ui/decision/`（6 文件 / 14 页）· 支撑：`core/src/git/mod.rs` §6（本地支持 API）、`core/src/api/github.rs` §8.4（执行层）
> 宿主：`ui/profile/SubPageScreens.kt`（本地仓库页）、`ui/repository/RepositoryFileViewer.kt`（文件页）、`RepositoryListScreens.kt`（仓库设置入口）
>
> **这份文档是什么**：决策页这一整个域的**页面清单 + 逐页契约 + 缺口台账** —— 每页摆哪几条事实、给哪些选项、
> 哪一步真会落盘、哪一步只是 UI。谁该读：加 / 改决策页的人，动 `git/mod.rs` 决策支持段或 `github.rs` 执行层的人。
>
> **本文档是补写的。** 代码里 4 处注释长期引用一份**从未进过版本库**的 `docs/decision-pages-gap.md`
> （`git log --all -- docs/decision-pages-gap.md` = 0 条记录，与 `html-parser-design.md`、`local-git-engine-design.md`
> 同一段历史：`/docs/` 曾被整体 `.gitignore`）：`ui/decision/CollabScreens.kt:60`（§4.5）、`core/src/bridge/jni.rs:1285`（§6）、
> `core/src/git/mod.rs:582`（§6）、`core/src/api/github.rs:678`（§8.4）。补写以**代码实际行为**为准，**章节号按这 4 处锚点倒排对齐**
> —— **重排章节就是改注释**，先回头把那 4 处引用一起改掉再动（参照 `html-parser-design.md` 头部的先例）。
> **本文只登记 `ui/decision/` 的 14 个页面**：`BranchesScreen` / `SyncScreen`、`BranchManageScreen` / `BranchSyncScreen` /
> `BranchCompareScreen` 都不在这个域里。

## 1. 职责与边界

**做**：把一个**有后果且不可自动决定**的动作，摆成「**事实 + 选项**」两段供用户拍板。事实区只读、只说
客观状态（分支、ahead/behind、未推送提交、命中行号…）；决策区给 2–3 个互斥选项，默认项标「推荐」、
破坏性项标「危险」并挂二次确认；底部只有按钮区（`DecisionScreenShell` 的 `bottom` 槽位）、中间可滚动
—— 这是「事实与决策分离」的物理形态。

**分界**：决策页 = 动作不可逆 / 有数据丢失风险 / 需在多条路里选一条；普通详情页（`IssueDetailScreen` 等）
以只读为主、写操作是次要按钮；设置页改的是随时可改回的偏好；任务中心只报告「已经发生的事」，不承载选项。

**不做**：

- **不 merge / rebase**：分叉只给「保留本地（暂不处理）/ 放弃本地」（对齐 D11，
  [`local-git-engine-design.md`](local-git-engine-design.md) §5、`SyncDecisionScreens.kt:101`）；**不改写已推送历史**
  —— 已推送提交只给 revert（`SyncDecisionScreens.kt:488-505`）。
- **不隐藏危险**：删除类操作靠**先勾选确认**再放开按钮（`DangerConfirmCard`，`DecisionComponents.kt:126-170`）；
  **不判断远端是否存在** —— 决策页读本地 `.git` 状态（§7），远端事实由调用方在执行前取。

## 2. 四要素与组件契约

全部来自 `ui/decision/DecisionComponents.kt`：

| 组件 | 行 | 契约 |
|---|---|---|
| `DecisionScreenShell` | `:238` | 壳：`statusBarsPadding()` + `navigationBarsPadding()` + 头部（返回 / 标题 / 副标题）+ **可滚动 content** + 固定 `bottom` 按钮行。**系统栏内边距由壳负责**，用它的页面不许再取一遍（`SystemBarInsetsTest.kt:41`、`:118-125`） |
| `FactCard` `:174` · `FactRow` `:198` · `DecisionNote` `:221` | | 事实区：卡片（灰底标题条 + 内容区，**只读**、从不放按钮）、单行（左路径 + 右值，`right` 为空则不渲染右列）、浅灰说明（讲「为什么 / 后果」） |
| `DecisionOptionRow(title, desc, selected, tag, onSelect)` `:51` · `OptionTag` `:44` | | 选项行：圆形单选 + 标题 + 描述 + 标签；选中态绿底、`DANGER` 红底；**整行可点，点击即选中**（不执行）。`OptionTag` = `RECOMMENDED`/`DANGER`/`NONE`，标签文字必须用文字角色（`ThemeContrastTest.kt:143-150`） |
| `DangerConfirmCard(title, description, confirmLabel, confirmed, onToggle)` | `:130` | 危险操作二次确认卡（红框 + 红底 + 勾选框）。**只有勾选后外部按钮才启用** —— 这个「外部按钮自己看 `confirmed`」的约定由各页自行实现 |
| `FeedbackLine(text, error)` | `SyncDecisionScreens.kt:535` | `internal` 页内反馈行（跨文件不可见）；`OptionTagChip` 是同文件私有（`DecisionComponents.kt:111`） |

约定：**事实与决策分离**是硬约束（事实区不放可点元素，唯一例外是 `RepoSettingScreen` 的分支行，见 §4.6）；
选中项 ≠ 执行（一律「选中 → 底部按钮 → 执行」，`DANGER` 还要先勾选）；`onBack` 必须与系统返回键目标一致（§5）。

## 3. 页面总览（14 页）

编号沿用代码注释里的 P 编号（三个域文件头把它们列全：`SyncDecisionScreens.kt:41-47`、
`CommitPrepScreens.kt:47-53`、`CollabScreens.kt:48-56`）；「宿主入口」= 谁把它渲染出来。

| 编号 | 页面（composable） | 实现位置 | 宿主入口 → 触发条件 |
|---|---|---|---|---|
| P0-1 | `ForkDecisionScreen` 分叉决策 | `SyncDecisionScreens.kt:55` | `SubPageScreens.kt:1005`（`LocalPage.Fork`）→ pull 回 `nff`（`:932`）· push 被拒（`:950`）· 上游页推送返回 `nff`（`:1030`） |
| P0-2 | `StageCommitScreen` 暂存勾选 + 提交信息 | `CommitPrepScreens.kt:64`（`internal`） | `SubPageScreens.kt:1078` · `RepositoryFileViewer.kt:647` → 本地仓库「提交」且 `dirty` 非空（`SubPageScreens.kt:972`）· 文件页多文件模式提交（`RepositoryFileViewer.kt:403-405`） |
| P0-3 | `AuthorIdentityScreen` 提交身份 | `CommitPrepScreens.kt:208` | `SubPageScreens.kt:1089` · `RepositoryFileViewer.kt:661` → `commit.author.name/email` 任一为空（`SubPageScreens.kt:995`、`RepositoryFileViewer.kt:390`） |
| P0-4 | `SensitiveWarningScreen` 敏感信息警告 | `CommitPrepScreens.kt:274` | `RepositoryFileViewer.kt:630` → 提交前扫描 `hits` 非空（`RepositoryFileViewer.kt:216-217`） |
| P0-5 | `PatInputScreen` 私有仓库 PAT | `CollabScreens.kt:437` | **无调用点**（§9） → 认证失败 + 私有仓库（设计意图，未接线） |
| P1-1 | `PrOnestopScreen` PR 一条龙 | `CollabScreens.kt:63` | `RepositoryListScreens.kt:963`（`subPage=2`）→ 仓库页 ⋮ → 设置 →「开 PR 一条龙」（`RepositoryListScreens.kt:979`、`RepositoryScreen.kt:786`） |
| P1-2 | `RepoSettingScreen` 仓库设置页群 | `CollabScreens.kt:232` | `RepositoryListScreens.kt:951`（`subPage=1`）→ 仓库页 ⋮ → 设置 →「仓库设置」（`RepositoryListScreens.kt:978`） |
| P1-3 | `DeleteRepoWarningScreen` 删除本地仓库警告 | `CollabScreens.kt:378` | `SubPageScreens.kt:1052`（`LocalPage.DeleteWarn`）→ `ahead > 0` 时删除（`SubPageScreens.kt:960-961`）· Git 化回退选「删除整个仓库」（`:1044`） |
| P1-4 | `GitifyRollbackScreen` Git 化回退 | `SyncDecisionScreens.kt:197` | `SubPageScreens.kt:1037`（`LocalPage.Rollback`）→ 本地仓库行「回退 Git 化」（`SubPageScreens.kt:1409`） |
| P1-5 | `PrMergeScreen` PR 合并策略 | `CollabScreens.kt:490` | **无调用点**（§9） → PR 详情页合并按钮（设计意图，未接线） |
| P2-1 | `DraftRecoverScreen` 草稿恢复 | `CommitPrepScreens.kt:352` | `RepositoryFileViewer.kt:677` → 点「编辑」时存在草稿且与远端内容不同（`:461-463`、`:578-581`） |
| P2-2 | `UpstreamSetupScreen` 首次 push 上游 | `SyncDecisionScreens.kt:287` | `SubPageScreens.kt:1024`（`LocalPage.Upstream`）→ push 时 `!hasUpstream`（`SubPageScreens.kt:944`）· 本地仓库行「上游」（`:1408`） |
| P2-3 | `UndoCommitScreen` 撤销误提交 | `SyncDecisionScreens.kt:375` | `SubPageScreens.kt:1015`（`LocalPage.Undo`）→ 本地仓库行「撤销」（`SubPageScreens.kt:1404`） |
| P2-4 | `OfflineConflictScreen` 离线同步冲突 | `CollabScreens.kt:593` | `RepositoryFileViewer.kt:699` → 提交前草稿基准 sha ≠ 远端当前 sha（`:153-161`，两处调用 `:253`、`:306`） |

## 4. 逐页规格

> **§4.1–4.4 = PR 之前的那几页**（分叉 + 提交准备），**§4.5 = PR 一条龙（锚点，由 `CollabScreens.kt:60`
> 钉死，不许挪）**，其余按下沉顺序。所以小节的数字顺序与 P 编号不重合 —— **P 编号才是权威编号**。

### 4.1 P0-1 分叉决策（`SyncDecisionScreens.kt:55`）

事实区：分叉示意（本地绿节点带 `ahead`、远端蓝节点带 `behind`，`:109-113`）+ 未推送提交清单（空则
「（无数据）」，`:118-125`）。选项：保留本地 / 放弃本地 / 取消 —— 只有「放弃本地」会动手
`gitResetHardRemote(repoDir, branch)`（`:87`）且必须先勾选（`:155-160`）；「保留本地」只回一句
`onResolved("已保留本地提交 · 远端未动")`，**不改任何文件**（`:81`）。
> **文案修正（1.0.92）**：这一页原先写「引导桌面解决」并让人「复制仓库路径到桌面端」，
> 而仓库在 1.0.91 起位于**内部存储**（`noBackupFilesDir/repos`，见
> [`local-git-engine-design.md`](local-git-engine-design.md) §4.2），那句承诺彻底不可兑现
> —— 现在如实说明「远端未动、App 不做 merge/rebase、改动可逐文件查看 / 复制」。
> 真正的处理场所留给「仓库内 Git 模式」（设计见 [`git-mode-design.md`](git-mode-design.md)）。
**预检（2026-09 加）**：`hasRemoteRef == false` 时「放弃本地提交」渲染成**禁用行 + 原因**
（「本地还没有 `origin/{branch}` 的记录（未获取过）：先执行一次获取」），不让用户点了才失败；
`status == null` 时文案是「无法读取仓库状态（目录无效或状态读取失败）」——不再归因「引擎不可用」。

已知边界：`unpushed` 最多 50 条（§7）；ahead/behind 依赖本地已有的 remote-tracking ref，**没 fetch 过就是 0**；
失败文案一律走 `failureMessage(action)`（判不了具体原因时写「原因见日志」），不编造单一归因。

### 4.2 P0-2 暂存勾选 + 提交信息（`CommitPrepScreens.kt:64`）

模式①（单文件）`files` 传空 → 跳过勾选（`:147-149`）；模式②/③传 `StageFile` 清单。
`mode == null` 时不拦，而是弹 `CommitModePickerDialog`，确认后 `saveCommitMode` 固化再继续
（`:84-85`、`:171-180`）。校验两条：**至少勾一个文件**、**提交信息非空**（`:86-87`）。
`StatusChip` 只认 `A` / `D` / 其它（`:184-189`）。
已知边界：本页**不执行提交**，只回传 `(message, selected)`（`SubPageScreens.kt:1084` →
`commitOrIdentity`；`RepositoryFileViewer.kt:653-656` → `doBatchCommit`），所以「提交失败」不在这一页显示。

### 4.3 P0-3 提交身份（`CommitPrepScreens.kt:208`）

预填 `suggestedName/Email`（本地仓库页给 `login` + `@users.noreply.github.com`，`SubPageScreens.kt:1090-1091`；
文件页给空名 + noreply 域名，`RepositoryFileViewer.kt:662-663`）。校验：名称非空、邮箱过
`Patterns.EMAIL_ADDRESS`（`:220-221`）。「保存为全局默认」= 写 `SharedPreferences("branchbase")` 的
`commit.author.name/email`（`:255`）；「仅本次使用」= 不落盘（`:256`）。
已知边界：**两个宿主的落盘逻辑各写一份**（`SubPageScreens.kt:1094-1097`、
`RepositoryFileViewer.kt:666-669`），本页只回传 `saveGlobally`；两份实现必须同步改。

### 4.4 P0-4 敏感信息警告（`CommitPrepScreens.kt:274`）

命中明细来自本地扫描（行号 + `kind` + 打码值，`:299-310`），打码规则见 §6。选项：返回修改
（默认推荐）/ 仍要提交（危险 + 勾选，`:316-317`、`:321-329`）。
已知边界：**扫描失败即放行** —— `scanSensitive` 返回 null 时 `proceedWithScan` 把 hits 当空列表
直接执行动作（`RepositoryFileViewer.kt:216-217`、`RustBridge.kt:1011-1015`）；`.so` 未重编译时这一页不会出现。

### 4.5 P1-1 PR 一条龙（`CollabScreens.kt:63`）

三步向导（`TabSwitcher`）：**① 命名分支 → ② 确认变更文件与提交信息 → ③ 填 PR 标题/描述 + 草稿开关**；
`step == 2` 时底部按钮换成「创建 PR」/「创建草稿 PR」。**三步都真落盘**（2026-09 补上第②步）。

| 步 | 已落地 | 说明 |
|---|---|---|
| ① 分支名 + 建分支 | 校验非空 / 不含空格 / **与传入的 `branches` 重名**（`branchNameError`）；`getRefSha(baseBranch)` → `createBranch(branchName, sha)` | 建分支真落盘；宿主没给分支清单时**明说「本次不查重」**，保护分支由远端在创建/开 PR 时校验（本地不承诺） |
| ② 提交 | `resolveCommitFiles()` → `commitFiles(branch = 新分支)`；提交信息可编辑（默认值 `DEFAULT_PR_COMMIT_MESSAGE`） | 执行体 `submitChanges()`：读内容 → `getRefSha(base)` → 建分支 → `commitFiles`；撞名有独立处置，失败显示 `ERROR:` 原文 |
| ③ 开 PR | `createPullRequest(...)`，head = 新分支 | 真落盘；成功写 `PrMemoryStore` 模板 + 任务中心 `TaskKind.COMMIT` / `TaskKind.PR` |

**待提交内容从哪来**：`resolveCommitFiles()` **草稿优先** —— 文件页保存过的草稿
（`files/edit/single/{owner}/{repo}/{相对路径}`，与 `RepositoryFileViewer.draftRoot()` 同一约定）；
没有草稿的路径取它在 base 分支上的当前内容。**读不全就整体不提交并点名路径**，不做「静默少提交几个文件」。

**空清单不放行**（`commitBlockReason()` 同时驱动第②步按钮禁用与红字引导）：没有待提交改动时
第①②③步各有一道拦截，引导去「代码」页编辑并保存草稿 —— **不再产生「与 base 同 sha、diff 为空」的 PR**。

**已知边界**：宿主只看得到**保存过草稿**的改动（`pendingDraftPaths()` 扫草稿目录，随页面重新可见重扫）；
文件页内存里正在编辑、尚未保存的那份不在磁盘上，本页看不到。

### 4.6 P1-2 仓库设置页群（`CollabScreens.kt:232`）

三块事实卡：通用（默认分支单选）、分支管理（已合并/未合并 + 删除）、危险操作区（删仓库，显示**真实**仓库统计）。
真落盘三处：`updateDefaultBranch`、`deleteBranch`、`deleteRepo`；删分支走底部红底按钮 + `DangerConfirmCard`
**真勾选**（本轮从纯展示接成真确认）；删仓库要先在危险区确认。

**真实性约定**（2026-09 修）：

- 反馈**冒泡到仓库页**：`onFeedback(msg, error)` 由宿主接住，落在设置入口列表顶部的 `FeedbackLine`
  （`RepositoryListScreens.kt`），不再丢进空实现；
- 「已合并」不再写死：按需调 `compareBranches(base = 默认分支, head = 分支)`，`ahead_by == 0` 判已合并 ——
  三种状态分别是**未检查 / 已合并 / N 个独有提交 / 检查失败**（缺 `ahead_by` 键按未知处理，不按 0）；
  单次上限 20 个分支，换默认分支即作废旧结论；文案写明「squash 合并过的分支仍会显示独有提交，那不代表没合并」；
- 仓库统计取 `/repos/{o}/{r}` 真值；**取不到就显示「统计不可用…不展示估算值」**，不摆写死的假数字。

### 4.7 P1-3 删除本地仓库警告（`CollabScreens.kt:378`）

事实：未推送提交清单 + 处理方式（先推送再删 / 仍要删除）+ **可选的远端统计**（`stats: RepoStats?`）；
选「仍要删除」才出 `DangerConfirmCard`，未勾选时底部按钮 `enabled = false`。
「先推送再删」= 先回列表再 `doPush`（`SubPageScreens.kt`），文案已改成**实际行为**
（「push 结束后回到本地仓库列表，不会自动回到本页」）；「仍要删除」由宿主
`File(root, name).deleteRecursively()` —— 本页不碰文件系统，且**只删本地**（远端删除在 §4.6）。

**统计参数**：宿主从本地 git 配置的 `origin` URL 反推 owner/repo 后取 `/repos/{o}/{r}`，
**取不到就传 null → 整行不显示**（宁可少一行，也不摆假数字）。`RepoStats` / `repoStatsSummary` /
`parseRepoStats` 都在 `ui/decision/CollabScreens.kt`（缺键 = 未知，不是 0）。

### 4.8 P1-4 Git 化回退（`SyncDecisionScreens.kt:197`）

事实：领先远端 N 个提交 + 工作区改动文件数（`:240-243`）+ 未推送提交清单（`:245-248`）。三选项：
保留 `.git`（推荐，只改偏好）/ 移除 `.git`（危险 + 勾选）/ 删除整个本地仓库（转 P1-3）。「移除 .git」是
**纯文件系统操作** `File(repoDir, ".git").deleteRecursively()`（`:222-224`），不走 Rust；「删除整个仓库」
交回宿主（`:229` → `SubPageScreens.kt:1041-1046` 重读 `gitStatus` 再进 P1-3）。

### 4.9 P0-5 私有仓库 PAT 输入（`CollabScreens.kt:437`）

**零调用点**（§9 #2）。设计：令牌只进 `remember`（`:441`），密码态默认遮罩 + 「显示」切换（`:459-462`），承诺「仅内存缓存 ·
不写日志 · 不上传服务器 · 退出登录即清除」（`:466`）；两个 `DecisionOptionRow` 的 `onSelect` 都是空
lambda（`:472-473`）—— 选项区是**纯展示**，唯一路径是底部「验证并继续」→ `onConfirm(token)`（`:480-482`）。
全仓（`app/src` + `core/`）**零调用点**、连 `import` 都没有（§9）——「私有仓库认证失败」这条路上没有这个
页面，页内那句「仅内存缓存」也无从验证。

### 4.10 P1-5 PR 合并策略（`CollabScreens.kt:490`）

**零调用点**（§9 #3）。设计：三策略 `squash / merge / rebase`（`:521`、`:544-549`），默认 squash（`:561`），「合并后删除 head
分支」开关（`:566-577`）；`PrMemoryStore` 按仓库预选上次的策略与开关（`:514-519`、`:539`）。执行序：
`mergePullRequest` → 若开关打开再 `deleteBranch`，删分支失败**不回滚合并**，只把原因拼进 note（`:533-536`）。
**入口（2026-09 接上）**：PR 详情页 `PullDetailScreen` 在 `state == open && !merged` 时给出合并入口
（`mergeable == false` 置灰并说明；`mergeable == null` = GitHub 仍在计算 → 可点但提醒）；
策略名统一取 `PrMemoryStore.strategyLabels`（下标 0/1/2 与 `mergePullRequest` 的 squash/merge/rebase 一一对应），
页面不再自带一份 `names`。
已知边界：合并后的**删分支**开关与失败文案仍归本页；合并失败不回调详情页（用户停在本页看原因）。

### 4.11 P2-1 草稿恢复（`CommitPrepScreens.kt:352`）

事实：草稿清单（`:378-384`）+ 远端 sha 对比结论（`:386-392`）。三选项：恢复草稿（推荐）/ 丢弃草稿
（危险 + 勾选）/ 查看远端最新，执行全在宿主（`RepositoryFileViewer.kt:680-695`）：恢复 = `loadDraft()`；
丢弃 = `clearDraft()` + 保持当前内容；查看远端 = `clearDraft()` + 退出编辑态 —— **「丢弃」与「查看远端最新」
都会删草稿**，区别只在是否留在编辑态。已知边界：宿主**每次只传一条** `DraftInfo`
（`RepositoryFileViewer.kt:463`、`:580`），`modifiedAt` 写死「本地草稿」、`changedLines` 传的是草稿总行数
（字段名比实际语义宽，`CommitPrepScreens.kt:346`）。**`remoteChanged` 已接**（2026-09）：宿主用**已有的**
草稿基准 sha（`draftBaseFile`，P2-4 在用）与当前远端 sha 比对（`draftRemoteChanged()`），
所以「远端已变化（存在多端编辑冲突）」不再是恒假分支。

### 4.12 P2-2 首次 push 上游（`SyncDecisionScreens.kt:287`）

事实：远端 URL（默认取 `gitStatus.remoteUrl`，为空才拼 `https://github.com/{repoName}.git`，`:303-305`）
+ 上游分支名（默认当前分支）。**两个选项**：推送并设为上游分支 / 取消（2026-09 删掉了「仅本次推送」——
引擎 `push_set_upstream` 无条件 `set_upstream`，那个选项与「设置并推送」走同一个函数，是个**没有区别的假选择**）；
执行只有一条路 `gitPushSetUpstream(repoDir, remoteUrl, upstreamBranch, token)`。**三态返回是关键契约**
（`RustBridge.kt:995-1008`）：`null` = 成功（`onResolved(msg, fork = false)`）；
`"nff"` = 远端领先被拒 → `onResolved(null, fork = true)` → 宿主转 P0-1（`SubPageScreens.kt:1030`）；
其他串 = 失败原因，显示 `推送失败：…`（`:321`）。

已知边界：`token` 由宿主传入（`SubPageScreens.kt:1027`），本页不校验是否为空；
「仅本次推送」不存在 —— 引擎只有一条推送路径（`push_set_upstream` 总会 `set_upstream`），
页面因此只给「推送并设为上游分支 / 取消」，选项与行为一一对应（§9 #8）。

### 4.13 P2-3 撤销误提交（`SyncDecisionScreens.kt:375`）

**双形态**，由 `status.unpushed` 是否为空决定。**有未推送提交**（`:457-464`）给 amend / reset --soft /
reset --hard（危险），落点 `gitAmend:414` · `gitResetSoft:421` · `gitResetHardRemote:429`；**无未推送提交**
（`:487-506`）只给「创建 revert 提交 / 取消」，落点 `gitRevert(sha, message, authorName, authorEmail):440`。
amend 的输入框预填当前提交信息（`:398`）且必填（`:411`）、reset --hard 必须勾选（`:426`）；作者身份取
`SharedPreferences("branchbase")` 的 `commit.author.*`，缺省 `Branchbase` / `branchbase@users.noreply.github.com`
（`:401-405`，与 P0-3 同一对 key）；revert 的 message 形如 `Revert "<原标题>"`（`:436`）。

已知边界：「已推送 / 未推送」用的是 **`unpushed` 是否为空**，而不是用户输入的那个 sha 是否已推送 ——
`revertSha` 默认 `"HEAD"` 但可改（`:387`、`:495-502`），与「已推送」这个前提没有校验关系。revert 要求
工作区干净（引擎报「工作区有未提交改动，无法 revert」，§6）——页面文案是
「revert 失败（原因见日志 · 常见：工作区有未提交改动 / sha 无效）」（不把多种原因折成单一归因）。
**预检（2026-09 加）**：`hasParent == false`（第一个提交）时两个「撤销」动作**禁用并写明原因**，
amend 仍可用；读不到状态时显式提示，而不是静默按「已推送」形态渲染；「已推送提交」的标题改用
`revertFormTitle(hasStatus, hasUpstream)` —— `unpushed` 为空**不等于**已推送（没有上游时同样为空）。

### 4.14 P2-4 离线同步冲突（`CollabScreens.kt:593`）

事实：并排 diff（本地草稿前 6 行 vs 远端前 6 行，`:604-605`、`:614-628`）+ 三选项：保留本地（推荐）/
放弃本地（危险 + 勾选）/ 复制远端为新文件；`onChoose` 取值 `keep / remote / copy`（`:655-657`），宿主在
`RepositoryFileViewer.kt:704-740` 分派。已知边界：**diff 只截前 6 行**（`.take(6)`）；「复制远端为新文件
（`${fileName}.remote`）」里的文件名宿主是另算的（`:635`），两边不是同一个字符串来源。

## 5. 宿主接线与返回键

三个宿主、三种接线方式：

| 宿主 | 接线方式 | 决策页状态 | 决策页分发 |
|---|---|---|---|
| 本地仓库页 `LocalRepoScreen`（`SubPageScreens.kt:860`） | 行内动作回调（`onUndo` / `onUpstream` / `onRollback` / `onCommit` / `onDelete`，`:1287-1290`） | `private sealed interface LocalPage`（`:839-852`，9 条路由） | `when (val p = page)` 穷尽分发，每个分支 `return`（`:1003-1194`） |
| 文件页 `RepositoryFileViewer` | 提交入口按模式分发（`:399-408`） | `private sealed interface FilePage`（`:766-773`，5 条路由） | `when (val p = page)`（`:628-744`） |
| 仓库页设置入口 `RepositorySettingsContent`（`RepositoryListScreens.kt:940`） | 行点击改 `Int` 子页 | `var subPage ... mutableStateOf(0)` | `when (subPage)` |
| PR 详情页 `PullDetailScreen`（`RepositoryDetailScreens.kt`） | 页内 `mergeOpen` 布尔（合并子页 `PrMergeScreen`） | — | `if (mergeOpen) PrMergeScreen(...)`；`PageBackHandler(mergeOpen)` 让返回键**先关子页**（2026-09 接上） |

**返回键**：前两个宿主在**页面内部**用 `PageBackHandler` 消费自己这一层（`SubPageScreens.kt:890`、
`RepositoryFileViewer.kt:421-427`）；`RepositorySettingsContent` **没有**自己的 handler，靠仓库页路由兜底
（未核实外层是否已覆盖嵌套返回）。链路规则与 `LocalPageActive` 见 [`NAVIGATION-NOTES.md`](NAVIGATION-NOTES.md)
§二 / §四 / §五（**不在这里重复**）；`nff:` 触发信号见 [`local-git-engine-design.md`](local-git-engine-design.md) §4。

## 6. 本地支持 API

> 锚点：`core/src/git/mod.rs` 的段注释「决策页面支持 API（对齐 …§6）」· `core/src/bridge/jni.rs`
> 的段注释「决策页面支持（对齐 …§6）」。稳定接口的总体约定（20 个 `pub fn`、错误归一）在
> [`local-git-engine-design.md`](local-git-engine-design.md) §3，本文只登记这 7 个决策专用函数。**返回约定**：
> `Ok(JSON 字符串)` 或 `Ok(空串)`=成功；`Err` 经 `into_jstring` 变成 `"ERROR:{e}"` 前缀串
> （`core/src/bridge/jni.rs:37-47`），Kotlin 侧一律 `startsWith("ERROR:")` 判失败。
>
> **函数按名字找**：Rust 侧这几个函数上方的 `// ── 决策页面支持 ──` 段注释就是入口。
> 下表里的 `file:line` 只作**定位参考**（`repo_status` 加三个字段就整体下移过一次），
> 引用时以**函数名/符号**为准 —— 这也是 [`docs/README.md`](../README.md) §四建议的写法。

| Rust（`core/src/git/mod.rs`） | JNI（`core/src/bridge/jni.rs`） | Kotlin 门面（`RustBridge.kt`） | 成功返回 |
|---|---|---|---|
| `repo_status` | `nativeGitStatus:1290` | `gitStatus:950` → `String?` | JSON 对象（§7） |
| `reset_soft` | `nativeGitResetSoft:1303` | `gitResetSoft:959` → `Boolean` | 空串 |
| `reset_hard_to_remote` | `nativeGitResetHardRemote:1317` | `gitResetHardRemote:968` → `Boolean` | 空串 |
| `amend_message` | `nativeGitAmend:1333` | `gitAmend:977` → `Boolean` | 空串 |
| `revert_commit` | `nativeGitRevert:1349` | `gitRevert:986` → `String?` | 新 commit sha |
| `push_set_upstream` | `nativeGitPushSetUpstream:1371` | `gitPushSetUpstream:996` → `String?` | 空串；`ERROR:nff` → `"nff"` |
| `scan_sensitive` | `nativeScanSensitive:1392` | `scanSensitive:1011` → `String?` | `[{line,kind,mask}]` |

各函数行为要点（改签名要同时改 JNI 与 Kotlin 门面并重建 `.so`）：

- `repo_status` 只读：`branch` = HEAD shorthand（detached 为空串）；`ahead/behind` 用
  `graph_ahead_behind(local, upstream)`；`remote_url` 取名为 `origin` 的 remote；`dirty.status` 归一成
  `A`/`D`/`M`；`unpushed` 走 revwalk `HEAD..upstream` **取前 50 条**（`:654`）。
- `reset_soft` → `HEAD~1`（`:687` 的 `parent_id(0)`）+ `ResetType::Soft`，**HEAD 是初始提交时直接报错**
  （无父提交），页面显示成「撤销失败（引擎不可用）」；`reset_hard_to_remote` → 重置到
  `refs/remotes/origin/{branch}`，**该 ref 必须已存在**（`:702-705`）；`amend_message` 重建 HEAD 提交时
  **沿用原 author/committer 的姓名与时间**（`:730-743`）；`revert_commit` 先查工作区干净（untracked 不算，`:782`）。
- `push_set_upstream`：确保 `origin` 存在并指向 `remote_url`（不同就改，`:824-833`）→ push
  `refs/heads/{b}:refs/heads/{b}` → **总是** `set_upstream`（语义见 §4.12）；失败经 `map_push_error:1003`
  归一（非快进/锁 ref/rejected → `nff:`，其余 → `push 失败:`）；凭据回调按 libgit2 请求的类型作答
  （先 USERNAME 再 userpass，`:839-848`）。
- `scan_sensitive` **纯本地、不碰网络**：逐行扫描（行号从 1 开始）9 个前缀特征
  （`ghp_`/`gho_`/`ghu_`/`ghs_`/`ghr_`/`AKIA`/`xoxb-`/`xoxp-`/`sk-`）、`-----BEGIN … PRIVATE KEY` 块、
  以及 `api_key|apikey|secret|password|passwd|token` 命中且同行有 `=`、值长 ≥ 8 且不含空格；`mask` 一律是
  **前 4 字符 + `****`**（`:883`、`:910`），完整值不出函数。
- **JNI 侧**：`token` 以 `JString` 传入并在 Rust 内转 `&str`，复合入参走 JSON 字符串（`nativeCommitFiles:944`）；
  这 7 个函数与 Kotlin 的 `external fun` 参数表由 `JniSignatureTest` 逐位比对（§10）。

## 7. 事实区数据模型

`repo_status` 下发的 JSON（`core/src/git/mod.rs:665-674`）↔ Kotlin `GitStatus`
（`DecisionModels.kt:11-19`）↔ `parseGitStatus`（`:31`，失败返回 null）。JSON 是 **snake_case**、Kotlin 属性
是 camelCase，桥接靠 `optBoolean("has_upstream")` / `optString("remote_url")` 手工映射（`:51-52`）。

| 字段 | 类型 / 默认 | 来源 | 谁在读 |
|---|---|---|---|
| `branch` | String，默认 `"main"` | HEAD shorthand（detached 为空串） | 分叉页标题、上游页默认分支名、`reset_hard_to_remote` 入参 |
| `ahead` | Int，默认 0 | `graph_ahead_behind`：本地领先 upstream 的提交数 | 分叉页、Git 化回退页、删除警告的触发（`:960`） |
| `has_upstream` | Boolean，默认 false | 本地分支有 upstream 配置 | push 前的分流（`SubPageScreens.kt:944`） |
| `behind` · `remote_url` | Int 0 · String `""` | 前者同上（远端领先数）；后者 = `origin` 的 URL | 分叉页远端节点 · 上游页 URL 预填 |
| `dirty` | `[{path, status}]` | `statuses(include_untracked = true)`，status ∈ `A`/`D`/`M` | 暂存页的文件清单、Git 化回退的改动计数 |
| `unpushed` | `[{sha(7 位), message}]`，最多 50 | revwalk `HEAD..upstream` | 分叉页 / 撤销页 / 删除警告 / Git 化回退 |
| `has_parent` | Boolean，默认 true | HEAD 提交是否有父提交（`parent_count > 0`） | 撤销页的**预检**：false = 第一个提交，`reset --soft HEAD~1` 必然失败 |
| `head_sha` | String，默认 `""` | HEAD 的完整 sha | 与远端 ref 比对（「远端有没有变化」） |
| `has_remote_ref` | Boolean，默认 false | `refs/remotes/origin/{branch}` 是否存在 | 分叉页「放弃本地」的**预检**：false 时 `reset --hard origin/x` 必然失败（没 fetch 过就是这种） |

**解析契约**：`parseGitStatus` 对缺失字段一律给默认值（`:47-55`）、**不报错**；`json` 为 null / 空串 / 非法
JSON 时返回 null，调用方拿到 `null` 就退化成「无数据」（分叉页「（无数据）」，Git 化回退领先 0 个）。
新加的三个字段默认值**偏保守**：`has_parent` 默认 true（不误报「第一个提交」），
`has_remote_ref` 默认 false（状态未知时先别让用户点「放弃本地」）。钉子：`DecisionModelsTest`。

**三个已知边界**（都是「事实区会说谎」的形状）：① `dirty` 的顺序由 libgit2 的状态表决定 ——
它由两个**已排序**的 diff 归并而来，所以**稳定有序**（钉子 `repo_status_dirty_keeps_path_order`，
`core/src/git/mod.rs` 的 tests）；② 浅 clone（`depth(1)`）下 revwalk 拿不到历史，`unpushed` 可能为空或不准
（[`local-git-engine-design.md`](local-git-engine-design.md) §2、§7）；③ `ahead/behind/remote_url` 全部来自
**本地已有 ref**、不联网，刚被别人推过的远端不会被感知到（`head_sha` 与 `has_remote_ref` 同理）。

## 8. 执行层 API

> 全部经 `ApiClient`（token 注入 + 错误归一）走 REST，与本地 `git` 通道互不共用；Kotlin 侧统一约定
> **`null` = 成功、其它 = 失败原因**（`RustBridge.kt:1029-1033`，`err()` 去掉 `ERROR:` 并截断 160 字符）。

### 8.1 分支与 ref

| Rust（`core/src/api/github.rs`） | 端点 | 用途 / 调用点 |
|---|---|---|
| `get_ref_sha:681` | `GET /repos/{o}/{r}/git/ref/heads/{branch}` | 取基准 sha；PR 一条龙（`CollabScreens.kt:99`）、分支管理、分支同步 |
| `merge_branch:696` | `POST /repos/{o}/{r}/merges` | 服务端合并（base=目标，head=源）；**已是最新时 GitHub 回 204 → 空串**，Kotlin 映射成 `"uptodate"`；冲突 409 按错误透出。`BranchSyncScreen.kt:253` |
| `compare_branches:716` | `GET /repos/{o}/{r}/compare/{base}...{head}` | 同步前的预览（`ahead_by`/`behind_by`/`status`）；`BranchSyncScreen.kt:229`、`BranchCompareScreen.kt:154` |
| `update_ref:732` | `PATCH /repos/{o}/{r}/git/refs/heads/{branch}` | 移动/强制移动 ref；`force=true` = 覆盖模式，会丢目标分支独有提交，调用方必须二次确认（`BranchSyncScreen.kt:257`） |
| `create_branch:747` | `POST /repos/{o}/{r}/git/refs` | 建分支（`ref` = `refs/heads/{name}` + sha）；PR 一条龙 `CollabScreens.kt:101` |
| `delete_branch:799` | `DELETE /repos/{o}/{r}/git/refs/heads/{branch}` | 删远端分支；`CollabScreens.kt:267`（设置页）、`:534`（合并后删 head）、`BranchManageScreen.kt:193` |

> 注：`merge_branch` / `compare_branches` / `update_ref` 在**源码里落在 §8.4 那段区间内**（`:691-744`，
> 介于 `get_ref_sha` 与 `create_branch` 之间），但服务的是分支同步/比较 —— 本文按**用途**归到 §8.1。

### 8.2 PR

| Rust | 端点 | 用途 / 调用点 |
|---|---|---|
| `create_pull_request:763` | `POST /repos/{o}/{r}/pulls` | 建 PR（`title`/`body`/`head`/`base`/`draft`）；唯一调用点 `CollabScreens.kt:104`（P1-1 第③步） |
| `merge_pull_request:786` | `PUT /repos/{o}/{r}/pulls/{n}/merge` | 合并 PR，`merge_method` ∈ `merge`/`squash`/`rebase`；唯一调用点 `CollabScreens.kt:529`（P1-5，**该页零调用点**，见 §9） |

### 8.3 合并

两条路互不重叠：**PR 合并**走 §8.2 的 `merge_pull_request`（按 PR 编号，策略由用户选）；**分支同步**走
§8.1 的 `merge_branch`（base/head 直给，无 PR）。结果约定也不同（前者空串=成功，后者空串=`uptodate`）；
本域页面只用得到前者。

### 8.4 协作与仓库管理

> 锚点：`core/src/api/github.rs:678`「协作与仓库管理（对齐 …§8.4 执行层）」。源码里这一段是 `:678-848`
> （分段按注释标题划）。除去 §8.1 的 4 个分支方法与工作流域的 `dispatch_workflow:834`，**归属本节的方法共 10 个**：

| # | 方法 | 端点 | 用途 | 页面调用点 |
|---|---|---|---|---|
| 1 | `get_ref_sha:681` | `GET /git/ref/heads/{branch}` | 取基准 sha（只读，PR 一条龙第①步依赖它） | `CollabScreens.kt:99` |
| 2 | `create_branch:747` | `POST /git/refs` | 建分支 | `CollabScreens.kt:101` |
| 3 | `create_pull_request:763` | `POST /pulls` | 建 PR | `CollabScreens.kt:104` |
| 4 | `merge_pull_request:786` | `PUT /pulls/{n}/merge` | 合并 PR | `CollabScreens.kt:529` |
| 5 | `delete_branch:799` | `DELETE /git/refs/heads/{branch}` | 删远端分支 | `CollabScreens.kt:267`、`:534` |
| 6 | `update_default_branch:805` | `PATCH /repos/{o}/{r}` | 改默认分支（body `default_branch`） | `CollabScreens.kt:257` |
| 7 | `delete_repo:817` | `DELETE /repos/{o}/{r}` | **删远端仓库**（不可恢复） | `CollabScreens.kt:277` |
| 8 | `update_profile:823` | `PATCH /user` | 改当前登录用户资料（body 为 JSON 串） | `ProfileEditScreen.kt:114`（`updateProfile:1336`）；决策页不调用 |
| 9 | `commit_files:239` | `GET /git/ref` → `GET /git/commits` → `POST /git/blobs` → `POST /git/trees` → `POST /git/commits` → `PATCH /git/refs` | **Git Data 批量提交**：多文件只产生一个 commit | `RepositoryFileViewer.kt:279`（多文件模式）；**PR 一条龙还没用上它**（§4.5） |
| 10 | `put_contents`（`RustBridge.kt:907`） | `PUT /repos/{o}/{r}/contents/{path}` | 单文件提交，每文件一个 commit | `RepositoryFileViewer.kt:313`（单文件模式） |

端点前缀：`/repos/{owner}/{repo}/…` 9 条 + `/user` 1 条，其中 `/git/…` 类 6 条。

## 9. 缺口台账（2026-09 复核 + 修复状态）

> 每条都 grep 复核过。**「已修」指代码里真的成立**（不是文档改了）；「仍缺」的东西不要当已完成读。

| # | 事项 | 状态 | 依据 / 说明 |
|---|---|---|---|
| 1 | PR 一条龙的提交执行 | **已修** | `submitChanges()`：读内容 → `getRefSha(base)` → `createBranch` → `commitFiles` → 第③步 `createPullRequest`；空清单由 `commitBlockReason()` 拦住并给引导 |
| 2 | `PatInputScreen`(P0-5) 零调用点 | **仍缺** | 全仓只有声明与文件头注释，没有任何路径会打开它（私有仓库认证失败仍无落地页） |
| 3 | `PrMergeScreen`(P1-5) 零调用点 | **已修** | PR 详情页 `PullDetailScreen` 给出合并入口（`open && !merged`；`mergeable` 决定可点/置灰/提醒） |
| 4 | `RepoSettingScreen` 的 `onFeedback = {}` | **已修** | 宿主接住并按 `FeedbackLine(msg, error)` 显示在设置入口列表顶部 |
| 5 | 「挽留 stats」写死演示数字 | **已修** | 两处都改：`RepoSettingScreen` 取 `/repos/{o}/{r}` 真值（取不到就说「统计不可用…不展示估算值」）；`DeleteRepoWarningScreen` 走可空的 `stats: RepoStats?`（null → 整行不显示） |
| 6 | 「已合并 / 未合并」是演示态 | **已修** | 写死的 `setOf("patch-1")` 删除；改按需 `compareBranches(base=默认分支, head=分支)` 判定（未检查 / 已合并 / N 个独有提交 / 检查失败；缺 `ahead_by` 按未知） |
| 7 | 分支名「不冲突 / 不命中保护规则」只是文案 | **已修** | 第①步按传入 `branches` 真查重（宿主没给清单时明说「本次不查重」）；保护分支改为「由远端校验」的诚实说法 |
| 8 | 「仅本次推送（不记录 upstream）」与行为不符 | **已修** | 删掉该选项（引擎无条件 `set_upstream`，两个选项走同一函数）；页面只剩「推送并设为上游分支 / 取消」 |
| 9 | P2-1「远端状态」卡片恒为「远端未变化」 | **已修** | 宿主改用**已有的**草稿基准 sha（`draftBaseFile`）比对当前远端 sha：`draftRemoteChanged()` → `DraftInfo.remoteChanged` |
| 10 | P1-3「先推送再删」成功后不回删除确认 | **已修（改文案）** | 实际行为是回列表页，文案改成「push 结束后回到本地仓库列表（不会自动回到本页）」——不做无法兑现的承诺 |
| 11 | 策略名映射两份 | **已修** | `PrMergeScreen` 改用 `PrMemoryStore.strategyLabels`，本地 `names` 删除 |
| 12 | `DangerLevel` 枚举零引用 | **已修（删除）** | 零引用死代码已删（危险分级事实上由各页手写 `DangerConfirmCard` 承担） |
| 13 | 决策页没有专属单测 | **部分修** | 新增 3 个纯函数测试类共 **42 例**（`DecisionModelsTest` 8 / `SyncDecisionPrecheckTest` 15 / `CollabDecisionRulesTest` 19）；**仍缺**：`repo_status` 之外 6 个决策 API 的 Rust 测试、以及 14 页「主按钮何时启用」的端到端断言 |
| 14 | `repo_status.dirty` 未排序 | **误报（已钉住）** | 实测 libgit2 的状态表由两个已排序 diff 归并而来，顺序**稳定**；钉子 `repo_status_dirty_keeps_path_order`（`core/src/git/mod.rs` 的 tests） |
| 15 | 本地仓库页的提交**不做**敏感信息扫描（P0-4 只覆盖文件页） | **仍缺（新记）** | `SubPageScreens.kt` 的 `doGitCommit` 直接提交，不经过 `scanSensitive`；要不要补需产品判断 |
| 16 | 文件页内存里未保存的编辑，PR 一条龙看不到 | **仍缺（边界）** | 宿主只扫草稿目录（`pendingDraftPaths()`）；要覆盖需让 `RepositoryFileViewer` 暴露内存草稿 |

## 10. 钉子与验收

**已有的钉子**（`./gradlew :app:testDebugUnitTest` 与 `cd core && cargo test`）：

| 钉子 | 位置（`file:line`） | 钉住了什么 |
|---|---|---|
| `DecisionModelsTest` | `app/src/test/java/com/branchbase/ui/decision/DecisionModelsTest.kt` | 事实区解析：`parseGitStatus` 的空/坏 JSON、缺键默认值、混入非对象、字段类型不对、以及 `has_parent`/`head_sha`/`has_remote_ref`；`parseSensitiveHits` 同样一套（8 例） |
| `SyncDecisionPrecheckTest` | `.../ui/decision/SyncDecisionPrecheckTest.kt` | 三条预检的**判定本身**：`resetSoftBlockReason` / `resetHardBlockReason` / `discardLocalBlockReason` / `failureMessage` / `revertFormTitle`（15 例） |
| `CollabDecisionRulesTest` | `.../ui/decision/CollabDecisionRulesTest.kt` | 一条龙与仓库设置页的纯逻辑：分支名校验、空清单拦截、标题继承、内容解析是否齐全、撞名处置、合并状态（含「缺 `ahead_by` 不当 0」）、仓库统计解析（19 例） |
| `PullDetailModelsTest` | `.../ui/repository/PullDetailModelsTest.kt` | PR 详情的 `merged` / `mergeable`（**JSON null 保持 null**）与合并入口三态规则 `pullMergeEntry` / `pullMergeHint`（9 例） |
| `SystemBarInsetsTest` | `.../ui/navigation/SystemBarInsetsTest.kt` | 三个决策页源文件都在「全屏页」清单里；`DecisionScreenShell` 必须自己取状态栏 + 导航栏内边距 |
| `ThemeContrastTest` | `.../ui/theme/ThemeContrastTest.kt` | `CommitPrepScreens.kt` 不许出现 `color = Primer.Red500`；「推荐/危险」标签取色被正则钉住 |
| `JniSignatureTest` | `app/src/test/java/com/branchbase/core/JniSignatureTest.kt` | `external fun` 与 `jni.rs` 导出**逐参数逐类型**比对（§6 的 7 个 + §8.4 执行层函数全在内） |
| `repo_status_dirty_keeps_path_order` | `core/src/git/mod.rs` 的 `mod tests` | 事实区 `dirty` 的顺序稳定（附「为什么不用显式排序」的理由） |
| 其余 `cargo test` | `core/src/git/mod.rs` 的 `mod tests` | 与决策页相关的：`scan_sensitive`（5 条）、`map_push_error`（`nff:` 归一 2 条）、`repo_status` 的新字段（2 条） |

**没有钉子、只能靠 review 的**：14 个页面各自的「主按钮何时启用」（`enabled = !busy && !(option == 1 && !confirmed)`
这类表达式手写在多处）；`PrMemory` 的按仓库记忆无单测；四个宿主的返回键目标一致性；
`repo_status` 之外 6 个决策 API（`reset_soft` / `reset_hard_to_remote` / `amend_message` / `revert_commit` /
`push_set_upstream` / `scan_sensitive` 的网络路径）仍只有本地纯逻辑覆盖。

**值得补的钉子**（按性价比排序）：① 「危险选项未勾选时主按钮禁用」的判定提成纯函数（现在多处手写）再加单测；
② **页面清单钉子** —— 像 `SystemBarInsetsTest` 那样把 14 个 composable 名与「有 / 零调用点」写成断言
（现在 P0-5 的「零调用」仍是**靠 grep 发现的**，接线时不会有东西提醒你回来删掉本文这一行）；
③ 本地仓库页提交路径的敏感扫描（§9 #15）。
