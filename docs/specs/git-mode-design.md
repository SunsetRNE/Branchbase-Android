# 仓库内 Git 模式（版本管理 / 协作 / 提交 / 冲突解决）· 设计与落地方案

> **这份文档是什么**：把「Git 工作到底在哪里做」这件事**重新定一次**——信息架构（IA）、
> 工作台形态、操作面分层、冲突解决的完整路径、以及为此需要的引擎缺口。它是
> [`git-version-tree-design.md`](git-version-tree-design.md)（三棵树的**可视化**子设计）的**上位文档**：
> 那篇回答「图怎么画」，本文回答「谁在什么地方用它、旁边的动作怎么摆、缺的能力从哪来」。
>
> **谁该读**：接下来做 Git 这块的人；改 `ui/repository/`（仓库页 / 文件页 / Git 气泡）、
> `ui/profile/SubPageScreens.kt`（本地仓库页）、`ui/decision/`（决策页）、`core/src/git/`（引擎）的人。
>
> **状态：草稿（未落地）**。全仓 grep「Git 模式 / GitWorkspace / 工作台」零命中 —— 没有为它预留的
> 页面、路由或菜单项。**现有最接近的形态是三处半成品**：代码页的 Git 悬浮球
> （`ui/repository/RepositoryScreen.kt:1162-1205`）、文件页的 Git 悬浮球
> （`RepositoryFileViewer.kt:636-690`）、本地仓库页的行内动作 + 决策页状态机
> （`ui/profile/SubPageScreens.kt:1003` 起）。本文就是把它们**收成一个模式**。
>
> **真源（设计依赖的事实出处）**：`core/src/git/mod.rs`（Git 引擎）·
> [`local-git-engine-design.md`](local-git-engine-design.md) §3/§4/§7 ·
> [`decision-pages-design.md`](decision-pages-design.md) §1/§2/§3 ·
> [`features-design.md`](features-design.md) §5 · [`NAVIGATION-NOTES.md`](NAVIGATION-NOTES.md) ·
> [`frame-perf-design.md`](frame-perf-design.md) §3/§6 · [`ui-design.md`](ui-design.md) §1/§3。

---

## 1. 结论先行：列表是统筹台，干活回仓库里

**产品判断（2026-09，本文的出发点）**：

> 设置里的「本地仓库」列表只适用于**直观的统筹** —— 一眼看清「拉了哪些仓库、各自在什么状态」。
> 而真正的 Git 工作（版本管理、树的可视化、协作、提交、提交合并、冲突解决）**必须回到
> 「Git 模式」下、在对应的那个仓库里做**。

这条判断与现有实现是**冲突**的：今天这些操作**散在四处**，而且**没有任何一处有仓库上下文的全貌**：

| 今天在哪 | 有什么 | 缺什么 |
|---|---|---|
| 设置 → 本地仓库（`LocalRepoScreen`） | 行内平铺：更新 / 推送 / 提交 / 撤销 / 上游 / 回退 / 分支 / 同步 / 删除；列表 + 分支胶囊 | 没有「仓库」上下文页面：看不到树、看不到工作区、看不到协作对象；仓库一多就是按钮墙 |
| 代码页 Git 悬浮球（仅 ③ 模式） | 切模式 / 分支管理 / 分支对比 / 同步 / 刷新 | 与文件页、本地仓库页**三处重复**；球里塞不下「树」这种需要大面积的东西 |
| 文件页 Git 悬浮球 | 编辑 / 暂存提交 / 分支 / 同步 / 身份 | 只在「当前文件」语境下成立，不是仓库语境 |
| 决策页 14 页 | 有后果的动作（分叉 / 撤销 / 上游 / 回退 / 敏感 / 草稿…） | **它们是终点，不是家**：用户得先知道「为什么会被送到这里」 |

所以本文的目标不是「再加一个页面」，而是**换一次 IA**：

```
今天：  设置 → 本地仓库（列表 + 行内动作 + 决策页）      ← 干活的入口在这里
       仓库页 Git 球 / 文件页 Git 球（重复的同一批动作）

目标：  设置 → 本地仓库            = 统筹台（只看状态、拉取、删除、进入）
        仓库页 → 「Git」模式       = 工作台（树 / 工作区 / 提交 / 分支 / 同步 / 合并）
        决策页                     = 有后果动作的落点（不变，仍是终点）
```

**为什么必须是「仓库内」而不是「设置里」**（三条都是硬理由，不是审美）：

1. **上下文**：Git 的每一个动作都要回答「哪个仓库、哪条分支、相对谁」；
   设置里的列表页天然只有一个仓库名，没有 owner/repo 的协作面（PR / issue / 远端分支）。
2. **面积**：提交 DAG 需要固定宽 gutter + 每行内容（[`git-version-tree-design.md`](git-version-tree-design.md) §7），
   引用树 / 文件历史同理 —— 列表页的行高（`LocalRepoRow`）给不了。
3. **承诺收口**：`CommitMode.LOCAL_REPO`（提交模式③，`SubPageScreens.kt:483-493`）承诺的是
   「改动落到本地 git」；这个承诺今天由**两枚悬浮球 + 一页行内动作**三处各自兑现。
   Git 模式是它唯一的兑现场所（`showGitBubble(mode)` 的判定不变，`GitBubblePanel.kt:60`）。

---

## 2. 事实基线（决定了分几步走）

| 事实 | 证据 | 对设计的影响 |
|---|---|---|
| 本地仓库的**唯一真源**是 `LocalRepoGitState`（一次 `repo_status` + tick 刷新） | `ui/repository/LocalRepoGitState.kt:53-67`、`rememberPageResumeTick` | 工作台、悬浮球、列表页**都必须读同一份**，不许各持一个 `remember`（消息页显示模式的教训，见 §7） |
| 仓库根目录在**内部存储** `noBackupFilesDir/repos` | `core/LocalRepos.kt`、[`local-git-engine-design.md`](local-git-engine-design.md) §4.2 | 工作台**不能**再承诺「路径可被桌面端访问」；导出/接管要另设计 |
| 引擎能力边界：浅 clone（`depth=1`）、pull 只 FF、**不 merge / rebase**、不隐式 stash | [`local-git-engine-design.md`](local-git-engine-design.md) §1/§7 | 树视图与冲突解决都要**新增引擎能力**，不是纯 UI 活 |
| 引擎已有 20 个 `pub fn`（clone / pull / push / commit / 分支 / 撤销丢弃 / 证书代理 / 决策页支持） | `local-git-engine-design.md` §3 | 引用树 / 工作区 / 同步**今天就能做**；图 / 合并 / 冲突**不能** |
| clone 进度已成体系（`progress.rs` + 轮询 + 弹窗） | [`VERSION-NOTES.md`](VERSION-NOTES.md) 1.0.90、`core/src/git/progress.rs` | 长任务（加深克隆 / 合并 / push）**沿用同一套**，不要再发明一种「转圈」 |
| 决策页有 14 页、四要素、门控纯函数（`resetSoftBlockReason` / `discardLocalBlockReason`…） | [`decision-pages-design.md`](decision-pages-design.md) §2/§3/§6 | 工作台的危险动作**只做入口**，落点复用这些页；**不新增门控真源** |
| `GitBubbleAction.keepOpen` 至今无人使用 | `ui/repository/GitBubblePanel.kt:75` 附近 | 「打开 Git 模式」正是它的第一个用户（见 `git-version-tree-design.md` §8） |
| `:app` 的 Kotlin 单测**不在 CI**（只跑 i18n + 编译） | `.github/workflows/build-beta.yml` | §9 的登记项漏了**不会红** —— 落地时逐条对着改 |

---

## 3. 形态：一个全屏工作台，四档视图

### 3.1 入口（三条，指向同一个页面）

| 入口 | 位置 | 前置条件 |
|---|---|---|
| 仓库页顶部 | `RepositoryScreen` 的 `RepoPage` 旁加「Git」入口（或复用右上 More 菜单） | 该仓库**有本地副本**（`LocalRepos` 里有同名目录） |
| 代码页 / 文件页 Git 悬浮球 | 新增一条 `GitBubbleAction("workspace", "Git 模式")`（`GitBubblePanel.kt`，`keepOpen = true`） | 仅 ③ 模式（`showGitBubble`，现状不变） |
| 本地仓库页行 | 行内动作收敛成「进入」为主入口（§4） | 有本地副本 |

**路由**：`RepoRoute.GitWorkspace`（`RepositoryScreen.kt:1046` 的 `RepoRoute` 家族）——
必须**同时**改 `route` 与 `leavePage()`（`:535`），并在 `SystemBarInsetsTest.fullScreenPages` 登记。
返回键只走 `PageBackHandler`（`NAVIGATION-NOTES.md` §二）。

### 3.2 骨架（头 / 切换 / 动作固定，中间滚动）

```
┌ 仓库头（固定）──────────────────────────────────────────┐
│ owner/repo · 分支 [main ▾] · 工作区 3 处改动 · ↑2 ↓0 · 上游 origin/main │
│ [刷新]  [切换 ③ 本地仓库模式]                                    │
├ 视图切换（固定）─────────────────────────────────────────┤
│ 提交图 │ 引用树 │ 文件历史 │ 工作区                            │
├ 视图内容（滚动）─────────────────────────────────────────┤
│ …（§3.3）                                                  │
├ 动作区（固定）───────────────────────────────────────────┤
│ [提交]  [同步（拉取 / 推送）]  [更多 ⋯]                        │
└──────────────────────────────────────────────────────────┘
```

- 仓库头的数据**全部**来自 `LocalRepoGitState`（一次 `repo_status`）；
  「更多」直接复用 `GitBubblePanel`（它是现成的动作面板，不该在第二处再写一遍）；
- 视图切换用**分段控件**（`settings-design.md` 的行型封闭集合里已有分段控件范式），不用 Tab 骨架
  —— 这一页是**全屏页**，跑在仓库页 Tab 骨架之外（见 `SystemBarInsetsTest` 的说明）。

### 3.3 四档视图

| 视图 | 回答什么 | 数据源 | 落地阶段 |
|---|---|---|---|
| **提交图**（DAG） | 版本怎么长出来的：分支、合并、引用标注 | 阶段 1 走 REST `/commits`（含 `parents`）；阶段 3 起本地 `log_graph` | 1 / 3 |
| **引用树** | 现在有哪些版本入口：本地 / 远端分支、tag、上游 | `local_branches` · `remote_branches` · `list_tags`（新） | 1 / 3 |
| **文件历史** | 这个文件改过什么 | 阶段 4 起：`fetch_deepen` + `log_file`；在此之前只从**文件页**看远端历史 | 4 |
| **工作区** | 现在有什么没提交 | `repo_status.dirty` + 本地 diff（新） | 0 / 3 |

> 三棵树的画法、泳道算法、分页与配色**不在这里重复** —— 全部见
> [`git-version-tree-design.md`](git-version-tree-design.md) §6/§7/§13。本文只加一条硬约束：
> **同一份 `GraphCommit` 数据要能被工作台与仓库页 Commits tab 共用**（那篇 §8 的「一份 UI，两份壳」）。

---

## 4. 设置里的「本地仓库」收敛成什么

**目标形态**（一屏一眼）：每行 = 仓库名 + 分支 + 状态胶囊（干净 / N 处改动 / ↑ahead ↓behind）+ 三个动作：

| 动作 | 语义 | 备注 |
|---|---|---|
| 进入 | 打开 Git 模式（§3） | **主入口**，整行可点 |
| 更新 | `pull`（fast-forward） | 保持现状；分叉仍进 P0-1 决策页 |
| 删除 | 删除本地副本 | 保持现状（含未推送警告升级） |

**迁走的行内动作**（提交 / 推送 / 撤销 / 上游 / 回退 Git 化 / 分支 / 同步）→ 工作台的动作区与视图。

**过渡期（必须写清楚，否则会出现两个真源）**：

- 阶段 0–2 **保留**行内动作，但每一处都加一句「真源在 Git 模式」（或在行的主入口给出提示），
  避免用户在两处做同一件事、状态互相看不见；
- 阶段 3 起，行内动作**逐个**下线（一次一个，跟着该动作在工作台里验收通过）；
- 页面顶部那行「N 个」与「＋拉取仓库」保持不变 —— 它是统筹台的本职。

---

## 5. 操作面分层（安全 / 决策 / 不做）

沿用 [`decision-pages-design.md`](decision-pages-design.md) §1 的分界：**不可逆、有数据丢失风险、
需在多条路里选一条**的，一律是决策页；其余可以直接在工作台里执行。

| 档 | 动作 | 落点 |
|---|---|---|
| **工作台内直接执行** | 刷新状态 · `fetch_remote`（先看清再决定）· 切换本地分支 · 对比两条 ref · 打开提交详情 · 复制 sha · 加载更早 · 打开文件历史 · 打开文件页 | 工作台（现状已有：`fetch_remote` / 分支 / 对比） |
| **走决策页** | 撤销最近一次提交（P0-3 撤销页）· 分叉（P0-1）· 首次 push 上游（P2-2）· 回退 Git 化（P1-4）· 删除本地仓库（P1-3）· 丢弃工作区改动 · 敏感信息（P0-4）· 提交身份（P0-3） | 现有 14 页**原文照用**，不新增门控真源（`resetSoftBlockReason` / `discardLocalBlockReason` 等纯函数复用） |
| **明确不做** | 隐式 stash · 改写**已推送**历史（rebase / amend 已推送）· 整仓网络图 · 子模块 · LFS · 自动合并远端分叉 | [`local-git-engine-design.md`](local-git-engine-design.md) §1/§7 的既有边界 |

**协作操作**（PR / 合并 / 讨论）**不搬进工作台**：它们的宿主是仓库页的 Pulls tab 与 PR 详情页
（[`decision-pages-design.md`](decision-pages-design.md) §4.5 的「PR 一条龙」）。工作台只负责**把它们串起来**：

```
工作台提交 → （同步）推送 → 开 PR（一条龙，P1-1） → PR 详情页合并（P1-2）
```

> 这条链**今天已经通了**（[`decision-pages-design.md`](decision-pages-design.md) §9 缺口台账 #1/#3
> 均为「已修」：一条龙的提交执行、PR 详情页的合并入口）——工作台要做的只是把入口摆在正确的位置，
> **不是**重做这条链。
>
> 但有两笔账要在工作台里一起还（同一张台账）：
>
> - **#15 本地仓库的提交不做敏感扫描**：`doGitCommit`（`SubPageScreens.kt`）直接提交，绕过 P0-4；
>   工作台的「提交」入口必须与文件页走同一条扫描路径（**要么补上、要么明确写清不扫**，
>   不能让两个提交入口两套口径）；
> - **#2 私有仓库认证失败仍无落地页**（`PatInputScreen` 零调用点）：工作台里到处是「推送 / 拉取」，
>   凭据不对时没有出路会被放大 —— 这是工作台的**前置条件**而不是它的附属功能。

---

## 6. 冲突解决（用户点名要的那一块）

**先把冲突的来路数清**——这决定了要做多少：

| 来路 | 今天的状态 | 用户能做什么 |
|---|---|---|
| ① 本地 `pull` 遇到分叉 | **不会产生冲突**：pull 只 FF，分叉直接进 P0-1 决策页 | 保留本地 / 放弃本地（都不解决分歧，见 §4.1 的文案修正） |
| ② 本地 merge / rebase | **能力不存在**（引擎刻意不做，D11） | 无 |
| ③ 远端 PR 合并冲突 | 由 GitHub 判定，App 只能显示 `mergeable == false` | 去网页解决 |

也就是说：**今天 App 里根本走不到「冲突」这一步** —— 冲突解决不是「UI 缺失」，是**能力缺失**。
设计要做的就是把它补上，并且**分两步**：

### 6.1 引擎：本地合并（新增，最大的一块）

| 新接口（草案） | 语义 | 返回 |
|---|---|---|
| `merge_branch(dir, branch, token)` | fetch 后做**三方合并**（不改写历史，产生 merge commit） | `{outcome: "up-to-date"\|"fast-forward"\|"merged"\|"conflict", conflicts: [path…], message}` |
| `conflict_files(dir)` | 冲突文件清单 + 每个文件的 ours / theirs / base 摘要 | `[{path, ours_sha, theirs_sha, base_sha, kind}]` |
| `resolve_conflict(dir, path, side)` | 逐文件采用 `ours` / `theirs` | 空串（`null` = 成功） |
| `write_resolved(dir, path, content)` | 手工解决后写回并 `add` | 空串 |
| `merge_continue(dir, message)` | 全部解决后提交合并 | sha |
| `merge_abort(dir)` | 放弃合并（回到合并前状态） | 空串 |

**边界（必须拍板，见 §10）**：`merge` **不改写已推送历史**（它产生新提交），与 D11 不冲突；
`rebase` 仍然不做。这条要在 [`local-git-engine-design.md`](local-git-engine-design.md) §5 的登记表里
把 D11 的措辞从「不 merge / rebase」改成「不 rebase；merge 仅在显式决策页发起」。

### 6.2 UI：冲突解决视图（工作台内的一个状态，不是新页面）

- 触发：`merge_branch` 返回 `conflict` → 工作台切到「**冲突解决**」态（视图切换条右侧亮红点）；
- 内容：冲突文件清单（每行：路径 + 三个动作「用我方 / 用对方 / 手工」），顶部一句
  「全部解决后才能提交合并，或放弃合并」；
- 手工：进 `:editor`（现成的编辑器模块）显示冲突标记 `<<<<<<< / ======= / >>>>>>>`，
  保存 = `write_resolved`；
- 收尾：全部解决 → 「提交合并」（`merge_continue`，message 预填 `Merge <branch> into <branch>`）；
  任何时候可「放弃合并」（`merge_abort`，**不丢数据** —— 只是回到合并前）；
- 一切有后果的动作（`merge_abort` / `merge_continue` / 逐文件采用某一侧）都要**说明后果**：
  采用一侧 = 另一侧的改动在这个文件上被丢弃。文案口径照 [`decision-pages-design.md`](decision-pages-design.md) §2。

### 6.3 远端 PR 冲突的两条出路

`mergeable == false` 时，PR 详情页给：

1. **在 GitHub 网页解决**（现状，链接 + 说明）；
2. **拉到本地解决**：`merge_branch` 拉目标分支 → 冲突解决视图 → 推送回 head 分支
   （**依赖 §6.1 的 merge 能力与写权限**，因此排在阶段 5）。

---

## 7. 状态与刷新（三个已发现的缺陷必须一起修）

工作台会把「状态不刷新」从**偶发**变成**每次操作都看得见**。下面三条在
[`git-version-tree-design.md`](git-version-tree-design.md) §10 已登记，实施顺序上**排在工作台之前**：

| # | 缺陷 | 位置 |
|---|---|---|
| 1 | 文件页 Git 徽标提交后不刷新（`tick` 恒 0） | `RepositoryFileViewer.kt` 的 `rememberLocalRepoGitState` |
| 2 | 本地仓库页分支胶囊陈旧（`LaunchedEffect` 以目录集合为键） | `SubPageScreens.kt` |
| 3 | 写操作失败原因被吞（返回 `Boolean`） | `RustBridge.gitResetSoft` / `gitResetHardRemote` / `gitAmend` |

> **三条都已在 1.0.89 修掉**（[`VERSION-NOTES.md`](VERSION-NOTES.md) 1.0.89 ②：分支胶囊的键里带上 `page`、
> 文件页 `gitTick++` + `rememberPageResumeTick`、写操作失败原因统一成 `null` = 成功 / 非空 = 原因）——
> 工作台落地时**不要重复修**，直接把这三条当成既有前提。本文保留这张表是为了说明
> 「为什么刷新契约必须一次做对」：面板会把它们从偶发变成每次都看得见。

**工作台的刷新契约**：仓库头与所有视图共用一次 `repo_status` 快照；任何写操作成功后
`tick++`；从子页（决策页 / 文件页 / 分支页）返回时 `rememberPageResumeTick()` 补一次。
本地 git 数据**不进 `PageCache`**（它不是远端数据），显式刷新是唯一口径。

---

## 8. 引擎缺口与新增接口（一次列全，按依赖排序）

| # | 能力 | 现状 | 新增（`core/src/git/mod.rs`） | 要重建 `.so` |
|---|---|---|---|---|
| 1 | 提交图 | 唯一 revwalk 在 `repo_status` 内（50 条、**无 parents**） | `log_graph(dir, limit)` | 是 |
| 2 | 加深克隆 | 无 | `fetch_deepen(dir, depth, token)`（长任务，走任务中心） | 是 |
| 3 | tag 列表 | 无 | `list_tags(dir)` | 是 |
| 4 | 本地 diff | 无（`BranchCompare` 走 REST） | `diff_worktree(dir)` · `diff_commit(dir, sha)` | 是 |
| 5 | 文件历史 | 无 | `log_file(dir, path, limit)` | 是 |
| 6 | 三方合并 | 无（D11） | `merge_branch(dir, branch, token)` | 是 |
| 7 | 冲突解决 | 无 | §6.1 的 5 个接口 | 是 |
| 8 | 显式 stash | 无（隐式不做） | **未决**（§10） | — |

**每一条的落地清单**（缺一步就白干，与 [`git-version-tree-design.md`](git-version-tree-design.md) §5 同一套）：
`mod.rs` → `core/src/bridge/jni.rs` 导出 → `RustBridge.kt` 的 `external` + suspend wrapper
（约定：`null` = 成功，失败 `ERROR:` 前缀，见 `GitErrorConventionTest`）→ `JniSignatureTest` 逐参数比对 →
**重建 `.so`** → `cd core && cargo test` → 版本条目。

---

## 9. 分阶段落地（每阶段可独立交付 / 可回滚）

| 阶段 | 交付 | 依赖 | 验收 |
|---|---|---|---|
| **0** | 工作台骨架（仓库头 + 四档切换的空壳）+ 工作区视图（脏文件列表 + 提交入口，复用现有动作）+ 三条入口 + 设置列表「进入」为主入口 | 无（零 Rust） | 真机：从仓库页 / 悬浮球 / 列表都能进；返回键链路正确（`BackConsumptionTest`） |
| **1** | 提交图（REST 数据 + `CommitGraphLayout`）+ 引用树（`fetch_remote` + tags 占位） | 阶段 0 | 多分支仓库的图不断线、不串道（`git-version-tree-design.md` §6 的用例） |
| **2** | 危险动作收口：工作台只做入口，全部落现有决策页；设置列表开始下线行内动作（一次一个） | 阶段 1 | 每个危险动作都能从工作台走到对应决策页并回到工作台 |
| **3** | `diff_worktree` / `diff_commit` + `log_graph` + `list_tags`（重建 `.so`）+ 工作区视图的本地 diff | Rust | `cargo test` + `JniSignatureTest`；浅克隆下「历史不够」有明确说明 |
| **4** | `fetch_deepen`（任务中心 + 进度）+ 文件历史树 + 离线图谱（LocalSource 优先、未推送段） | 阶段 3 | 飞行模式下能看图；加深失败不破坏已有仓库 |
| **5** | 本地合并 + 冲突解决视图 + PR 冲突的「拉到本地解决」 | 阶段 4 | 构造真实冲突仓库跑通：冲突清单 → 逐文件解决 → 提交合并 / 放弃合并 |

---

## 10. 未决问题（落地前需要拍板）

1. **Git 模式放哪**：仓库页内的全屏页（本文推荐，天然带 owner/repo 上下文）还是独立顶层页？
2. **与提交模式③的关系**：进 Git 模式是否**强制**切到 ③？还是只提示「当前不是 ③，本地改动不会落到 git」？
3. **D11 的边界**：允许 `merge`（不改写历史）吗？`rebase` 是否放宽到「仅未推送提交 + 二次确认」？
   —— 这条直接影响 §6 的规模（合并能力 = 6 个新引擎接口）。
4. **手工解决冲突**用 `:editor`（带冲突标记高亮）还是另写三栏合并器？后者是独立模块级工作量。
5. **加深克隆的默认深度**：全量 / 300 / 100？要不要在移动网络下先问一句（流量）？
6. **设置列表的过渡期**：行内动作保留到哪个版本？谁负责在每个动作验收后删掉它？
7. **显式 stash** 要不要（隐式 stash 已明确不做）？不做的话「脏工作区切分支被拒」的出路只有「放弃改动」。
8. **导出仓库**（zip / 分享给桌面端）要不要做 —— 1.0.92 只改了文案，把「复制路径到桌面端」
   这句话删掉了；**没有出路**与「有出路但要新建能力」是两件事，要明确选一个。

---

## 11. 登记清单（**漏了不会红**）

| 要动的东西 | 登记处 |
|---|---|
| 新增全屏页 | `SystemBarInsetsTest.kt` 的 `fullScreenPages` |
| 新增路由分支 | `RepositoryScreen.kt` 的 `route` + `leavePage()`（`BackConsumptionTest.kt` 钉穷尽 `when`） |
| 任何新页面 | 只用 `PageBackHandler`（裸 `BackHandler` 被 `BackConsumptionTest.kt` 全目录扫描） |
| 新 `ui/` 文件里的颜色 | 走 `Primer` 角色；泳道配色定义在 `ui/theme/`（`ThemeConvergenceTest.kt`） |
| 新字符串 | `tools/i18n/strings.tsv` → `extract.py --apply`（CI 硬门禁 `--min-coverage 100`） |
| 新 JNI 函数 | `JniSignatureTest.kt` + 重建 `.so` |
| 新版本 | [`VERSION-NOTES.md`](VERSION-NOTES.md) §二/§三 → 最后改 `version.properties` |
| 本文档 | [`../README.md`](../README.md) §五 索引行（落地后补「真源」列） |
| 引擎边界变化（merge） | [`local-git-engine-design.md`](local-git-engine-design.md) §1/§5/§7 |

---

## 12. 性能与验收

- 工作台属于**绘制型**风险（提交图的泳道）：一屏百行的几何**必须预计算**
  （[`frame-perf-design.md`](frame-perf-design.md) §6、[`git-version-tree-design.md`](git-version-tree-design.md) §13）；
- 首帧：数据解析 + 布局走 `Dispatchers.IO`，先骨架后内容；骨架替换用 `PlaceholderSwap`
  （**不许 `Crossfade`**，[`ui-design.md`](ui-design.md) §3）；
- 长任务（加深克隆 / 合并 / push）一律走 `TaskStore` + 进度弹窗（1.0.90 那套），
  不许出现第二种「转圈」；
- 验收口径与既有基线同一套：出 perfBeta → `tools/perf/frame-baseline.py compare` →
  **≥100ms 条数降、超 16ms 比例降、慢帧均值不回升**，结论回写 `frame-perf-design.md` §5 与版本条目。
