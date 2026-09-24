# Git 版本管理树（提交 DAG / 引用树 / 文件历史树）· 设计与落地方案

> **这份文档是什么**：一个**尚未落地**的功能的设计方案 + 分阶段计划 ——「版本管理树」面板
> 长什么样、数据从哪来、操作怎么走、每一步的验收是什么。它同时登记了三个已拍板的产品决策。
>
> **谁该读**：接下来做这块的人；改 `core/src/git/`、`ui/repository/GitBubblePanel.kt`、
> 仓库页 / 文件页 / 本地仓库页入口的人。
>
> **状态：草稿（未落地）**。全仓 grep「版本树 / 图谱 / commit graph / 网络图」零命中，
> 没有为它预留的 host、菜单项或未实现入口 —— 现有最接近的形态是 `GitBubblePanel`（动作气泡）
> 与 `LocalRepoScreen` 的行内动作 + 决策页状态机。
> **落地后要做的收尾**：回填 §五 索引行的「真源」列（指向 `CommitGraphLayout.kt` /
> `CommitGraphContent.kt`）、把本文的「草稿」改成「已落地」并补实际取舍。
>
> **真源（设计依赖的事实出处）**：`core/src/git/mod.rs`（20 个 `pub fn`）·
> [`local-git-engine-design.md`](local-git-engine-design.md) §3/§7 ·
> [`decision-pages-design.md`](decision-pages-design.md) §1/§2/§6/§7 ·
> [`frame-perf-design.md`](frame-perf-design.md) §3/§6/§7。

---

## 1. 目标与范围

「版本管理树」是**同一块面板的三档视图**，共享一份提交数据：

| 视图 | 回答的问题 | 需要的数据 |
|---|---|---|
| **提交 DAG 图** | 「版本是怎么长出来的」：谁在谁前面、哪条线是分支、哪次是合并 | 提交 + `parents` + 引用标注（分支 / tag / HEAD / 上游） |
| **引用树** | 「现在有哪些版本入口」：本地分支 / 远端分支 / tag / 上游跟踪关系 | `local_branches` · `remote_branches` · tags · upstream 配置 |
| **文件历史树** | 「这个文件改过什么」：单文件的提交序列 | `/repos/{o}/{r}/commits?path=`（远端）· 本地 log（加深克隆后） |

**不做**（与既有边界一致，改之前先看这里）：

- 不 merge / rebase、不改写已推送历史（D11，[`local-git-engine-design.md`](local-git-engine-design.md) §5）；
- **不做「整仓网络图」**：GitHub 没有公开的 network graph API（网页端那张图是内部接口），
  目标只能是「最近 N 条的局部 DAG + 引用标注」；
- 不在面板里内联执行危险动作（见 §9）。

## 2. 事实基线（决定了为什么要分阶段）

| 事实 | 证据 | 对设计的影响 |
|---|---|---|
| clone 是**浅克隆 `depth(1)`** | `core/src/git/mod.rs:37`、[`local-git-engine-design.md`](local-git-engine-design.md) §7.1 | 本地**没有历史**；「本地版本树」必须先加深克隆 |
| 引擎**没有** log / graph / tag 导出；唯一 revwalk 在 `repo_status` 内 | `core/src/git/mod.rs:677-694`（`push_head`+`hide(upstream)`+`take(50)`，无 `parents`） | 「本地图」是新增能力，要重建 `.so` |
| REST `/commits` **响应里就有 `parents`**，只是解析时丢了 | `ui/repository/RepositoryModels.kt:275`；`CommitItem` / `CommitDetail` / `CompareCommit` 均无 `parents` | 第 0 阶段**零 Rust 改动**就能拿到图数据 |
| 已有 `fetch_remote`（只刷 `refs/remotes/origin/*`，不合并、不动工作区） | `app/src/main/java/com/branchbase/core/RustBridge.kt:873`（`core/src/git/mod.rs:229`） | 引用树第一版今天就能做 |
| 本地 Git 数据**完全不在缓存里**；服务端 Git 数据在 `PageCache` / `ListCache` 里 | `ui/repository/LocalRepoGitState.kt:53-67`、`LocalBranchSyncScreen.kt:110-116` | 面板要自带「一次读取 + 显式刷新」契约（§10） |
| 无 paging 库、`derivedStateOf` 0 处、`contentType` 0 处、全仓无 `stickyHeader` | 数据层扫描 | 分页手写「加载更早」footer；顶部状态区用固定 `Column` 行 |
| 唯一 Canvas 自绘先例是贡献墙；绘制 lambda **不是** composable | `ui/profile/ContributionWall.kt:120,150` | 泳道几何预计算；颜色在组合期取好再传进 draw lambda |
| `:app` 的 Kotlin 单测**不在 CI** | `.github/workflows/build-beta.yml:46`、`build-release.yml:43`（只跑 i18n + 编译）；`build-core.yml:50-71`（cargo） | §12 那批登记项漏了**不会红** |

## 3. 已拍板的决策（2026-09）

1. **三个视图都要**：提交 DAG 图 + 引用树 + 文件历史树（同一面板内切换）；
2. **可视化 + 操作**：面板内可直接执行**安全动作**；**危险动作一律走决策页**
   （reset soft/hard、revert、丢弃、删除分支）—— 对齐 [`decision-pages-design.md`](decision-pages-design.md) §1/§2
   「事实与决策分离」「选中 ≠ 执行」；
3. **接受加深克隆**：为「离线 + 完整历史」新增 `fetch_deepen`，一次性网络代价 + 重建 `.so`。

## 4. 数据源分层：双源 + 一个抽象

```
                 ┌──────────────── GraphSource（产出同一个 List<GraphCommit>）────────────────┐
RemoteSource ────┤ /commits?sha=&per_page=100（含 parents）· /branches?per_page=100 ·          │
（默认，任何仓库）│ /commits?path=（文件历史）· tags（需新增封装）                              │
                 └───────────────────────────────────────────────────────────────────────────┘
LocalSource ─────┤ repo_status · local_branches · remote_branches · fetch_remote               │
（有本地仓库时）  │ 加深克隆后：log_graph（含 parents/refs）                                     │
                 └───────────────────────────────────────────────────────────────────────────┘
```

- **合并规则**：节点按 sha 去重；**本地引用优先标注**（`HEAD` / upstream 只有本地知道）；
  「未推送提交」与「工作区改动」只在 LocalSource 存在，图上以独立样式标出；
- **缓存**：`ListCache.key(owner, repo, ListCache.PAGE_COMMITS, params = "graph|<branch>")` ——
  `params` 会拼进键，所以与平铺提交列表**互不覆盖**；TTL 随 `ListCache.TYPE`（5 分钟）。
  ⚠️ 平铺列表当前**没带 `per_page`**（GitHub 默认只给 30 条），图谱必须显式 `per_page=100`；
- **取几个 head**：默认分支 + 当前本地分支 + 最近推送分支（≤3），各自 `per_page=100` 后合并去重；
  「加载更早」用 `?sha=<窗口内最早的 sha>` 续取。

## 5. 引擎新增（第 3 阶段，需重建 `.so`）

| 新接口 | 签名（`core/src/git/mod.rs`） | 成功返回 |
|---|---|---|
| 提交图 | `pub fn log_graph(dir: &str, limit: usize) -> Result<String>` | `[{sha, parents[], message, author, time, refs[]}]` |
| 加深克隆 | `pub fn fetch_deepen(dir: &str, depth: u32, token: Option<&str>) -> Result<()>` | 空串 |
| tag 列表 | `pub fn list_tags(dir: &str) -> Result<String>` | `[{name, sha}]` |

**落地清单**（缺一步就白干）：`mod.rs` → `core/src/bridge/jni.rs` 导出 →
`RustBridge.kt` 的 `external` + suspend wrapper（约定：`null = 成功`，失败 `ERROR:` 前缀）→
`JniSignatureTest` 逐参数比对 → **重建 `.so`** → `cd core && cargo test`。

**两条硬约束**：

- `fetch_deepen` 是**长任务**（大仓库几十 MB）：必须走任务中心（`TaskStore.start/progress/finish`，
  `TaskKind` 需加值），不能阻塞在一个对话框里；
- 浅克隆加深是**增量**的：失败不破坏已有仓库，但 UI 要能区分「没加深过」与「加深失败」。

## 6. 布局算法与数据模型（第 0 阶段，纯函数 + 单测）

```kotlin
/** 图上的一条提交（已解析 parents 与引用标注）。 */
data class GraphCommit(
    val fullSha: String,
    val shortSha: String,
    val parents: List<String>,          // 完整 sha；可能指向窗口外
    val subject: String,
    val author: String,
    val date: String,
    val refs: List<CommitRef>,
)

enum class RefKind { HEAD, LOCAL_BRANCH, REMOTE_BRANCH, TAG, UPSTREAM }
data class CommitRef(val name: String, val kind: RefKind)

/** 一行在 gutter 里的几何（**预计算**，绘制时 O(1)）。 */
data class GraphRow(
    val commit: GraphCommit,
    val lane: Int,                      // 节点所在泳道
    val laneCount: Int,                 // 本行泳道总数（决定 gutter 宽度上限）
    val edges: List<GraphEdge>,         // 本行要画的线段，含「穿过」的泳道，否则线会断
    val dangling: Boolean,              // 父提交不在已加载窗口（分页截断）
)

data class GraphEdge(val fromLane: Int, val toLane: Int, val fromNode: Boolean, val toNode: Boolean)

object CommitGraphLayout {
    /** 经典 swimlane：泳道 = 「还在等谁出现」。纯函数、幂等。 */
    fun layout(commits: List<GraphCommit>): List<GraphRow>
}
```

**算法**：按拓扑序（父在子之后）遍历；每条提交认领「等待它的那条泳道」，没有就开一条；
第一父继承当前泳道，额外父各开一条（取右侧最近空位）；一条泳道等到的 sha 落空 → 该泳道在下行
「穿过」；父不在窗口内 → `dangling = true`（画成终止符，**不能画成断头线**）。

**必须钉住的用例**（`CommitGraphLayoutTest`）：线性 · 单分叉 · 合并（第二父在窗口内 / 外）·
octopus（3+ 父）· 分页截断的悬空父 · detached HEAD · 泳道回收与上限 · **同输入同输出**。

## 7. 三个视图的形态

**① 提交 DAG 图**：`LazyColumn`（`key = fullSha`）+ 固定宽 gutter（Canvas 自绘泳道）+ 行内容
（subject / 作者 / 短 sha / 时间 / refs 胶囊）。顶部**固定状态区**（不是 stickyHeader —— 全仓 0 处）：
工作区 / HEAD / 上游三行。末尾「加载更早」footer；截断处标「更早历史未加载」。
泳道配色**必须定义在 `ui/theme/` 下**（`ThemeConvergenceTest` 只放过 theme 目录），
组合期取色再传进 Canvas（`ContributionWall` 的教训）。

**② 引用树**：分组折叠列表 —— 本地分支 / 远端分支 / tag；行右侧是动作（切换 / 同步 / 对比 / 删除），
数据来自 `local_branches`（含 `is_head` / `upstream` / `ahead` / `behind`）+ `remote_branches` + `list_tags`。
**不重复实现** `BranchManageScreen` / `LocalBranchSyncScreen` 已有的列表操作：这里只做「树状汇总 + 入口」。

**③ 文件历史树**：从**文件页**进入（文件页头部目前只有 返回 / 文件名 / 编辑，没有 ⋮），
`/commits?path=<path>` 取该文件的提交序列；每行 → 该次提交里这个文件的 diff
（复用 `BranchCompareScreen` 的 diff 渲染，`BranchDiff.kt`）。

## 8. 面板与入口：一份 UI，两份壳

| 入口 | 位置 | 模式 |
|---|---|---|
| 仓库页 `RepoPage.Commits` 顶部「列表 / 图谱」切换 | `ui/repository/RepositoryListScreens.kt:568-640` | 远端只读 |
| 代码页 Git 气泡新增「版本树」 | `ui/repository/RepositoryScreen.kt:1161-1206`（门控 `921`） | 远端 + 本地标注 |
| 文件页 Git 气泡新增「文件历史」 | `ui/repository/RepositoryFileViewer.kt:617-675` | 远端只读 |
| 本地仓库页 `LocalPage.Graph` | `ui/profile/SubPageScreens.kt:960-978` + 分发 `1128-1324` | 本地（含未推送 / 工作区） |

- UI 本体写成 `CommitGraphContent(...)`（**不依赖宿主**），两边各包一层壳；
- 仓库上下文用 `RepoRoute.Graph`：必须同时改 `route`（`RepositoryScreen.kt:426-445`）与
  `leavePage()`（`:535-556`），并在 `SystemBarInsetsTest` 的 `fullScreenPages` 登记（漏登记是**静默缺口**）；
- `GitBubbleAction.keepOpen`（`ui/repository/GitBubblePanel.kt:75,142`）**至今无人使用** ——
  「打开版本树」正是它的第一个用户。

## 9. 操作与门控

**安全动作（面板内直接执行）**：打开提交详情 · 复制 sha · `fetch_remote`（先看清再决定）·
加载更早 · 切换本地分支 · 对比两条 ref · 打开文件历史。

**危险动作（只做入口，落点仍是决策页）**：撤销最近一次提交（P0-x 撤销页）· 放弃本地（分叉页 P0-1）·
revert 已推送提交 · 丢弃工作区改动 · 删除分支 / 仓库。

门控维度（缺一不可）：`exists`（有没有本地仓库）× `dirty`（工作区脏不脏）× `ahead/behind` ×
`has_parent` × `has_remote_ref`。预检复用既有的纯函数 `resetSoftBlockReason` /
`resetHardBlockReason` / `discardLocalBlockReason`（`ui/decision/SyncDecisionScreens.kt:68-92`），
失败文案走 `failureMessage(action, reason)` —— **不要再新增一套门控真源**。

## 10. 状态一致性：单一真源与三个已发现的缺陷

面板、气泡、状态条**必须读同一个 `LocalRepoGitState`**（一次 `repo_status`），刷新靠 tick 传递 ——
这也是本仓库踩过的坑（消息显示模式两个入口各持一份 `remember`，只在页面活着时复现）。

| # | 缺陷 | 位置 | 症状 |
|---|---|---|---|
| 1 | 文件页 Git 徽标不刷新 | `RepositoryFileViewer.kt:459` 的 `rememberLocalRepoGitState(repo)` 用默认 `tick = 0` | 本地提交后气泡徽标仍是旧值 |
| 2 | 本地仓库页分支列陈旧 | `SubPageScreens.kt:1036` 的 `LaunchedEffect(repos)` 以**目录集合**为键（结构相等） | 切过分支 / 同步过之后，行内分支胶囊还是旧分支名 |
| 3 | 错误归因丢失 | `RustBridge.gitResetSoft` / `gitResetHardRemote` / `gitAmend` 返回 `Boolean`，错误文本被吞 | `failureMessage` 只能说「失败（原因见日志）」，而日志里没有原因 |

> 这三个在「版本管理树」落地**之前**就该修：面板会让它们从「偶发」变成「每次操作都能看到」。

## 11. 落地阶段（每阶段可独立交付 / 回滚）

| 阶段 | 内容 | 依赖 | 验收 |
|---|---|---|---|
| **0** | 数据模型 + `CommitGraphLayout` + 单测 + **保留 parents 的提交解析**（不动 `parseCommits`）+ §10 三个缺陷 | 无（纯 Kotlin + 小改） | `CommitGraphLayoutTest` 全绿；`./gradlew :app:testDebugUnitTest` |
| **1** | 只读 DAG 图 + Commits tab 切换 + 气泡入口 + i18n / 文档 / 版本条目 | 阶段 0 | 真机：多分支仓库的图不断线、不串道 |
| **2** | 引用树 + 文件历史树 + 安全动作 + 完整门控 | 阶段 1 | 决策页跳转链路通；危险动作全部落在决策页 |
| **3** | `log_graph` + `fetch_deepen` + `list_tags`（重建 `.so`） | Rust 改动 | `cargo test` + `JniSignatureTest` + 任务中心能看到加深进度 |
| **4** | 离线图谱（LocalSource 优先）+ 未推送段 + 工作区虚节点 | 阶段 3 | 飞行模式下能看图；未推送提交以独立样式标出 |

## 12. 必须同步的登记清单（**漏了不会红**）

| 要动的东西 | 登记处 |
|---|---|
| 新增全屏页 | `SystemBarInsetsTest.kt:49-81` 的 `fullScreenPages` |
| 新增路由分支 | `RepositoryScreen.kt` 的 `route` + `leavePage()`（`BackConsumptionTest.kt:124-142` 钉穷尽 `when`） |
| 任何新页面 | 只用 `PageBackHandler`（裸 `BackHandler` 被 `BackConsumptionTest.kt:29-59` 全目录扫描） |
| 新 `ui/` 文件里的颜色 | 走 `Primer` 角色；泳道配色定义在 `ui/theme/`（`ThemeConvergenceTest.kt:62-91`） |
| 新字符串 | `tools/i18n/strings.tsv` → `extract.py --apply`（CI 硬门禁 `--min-coverage 100`） |
| 新 JNI 函数 | `JniSignatureTest.kt:81-122` + 重建 `.so` |
| 新版本 | `VERSION-NOTES.md` §二/§三 → 最后改 `version.properties` |
| 本文档 | `docs/README.md` §五 索引行（落地后补「真源」列） |

## 13. 性能与验收

- 图谱属于**绘制型**风险（[`frame-perf-design.md`](frame-perf-design.md) §6）：一屏百行的泳道几何
  **必须预计算**，Canvas 每行只画自己那几条线；`LazyColumn` 带 `key = fullSha`；gutter 宽度固定；
- 首帧：数据解析 + 布局走 `Dispatchers.IO`，先骨架后内容；骨架替换用 `PlaceholderSwap`
  （**不许用 `Crossfade`**，`ui-design.md` §3）；列表增删用 `Modifier.animateItem()`；
- 验收口径（与既有基线同一套）：出 **perfBeta** 包 → `tools/perf/frame-baseline.py compare` →
  **≥100ms 条数降、超 16ms 比例降、慢帧均值不回升**；结论回写 `frame-perf-design.md` §5 与 `VERSION-NOTES`。

## 14. 未决问题（落地前需要拍板）

1. 工作区改动要不要画成 HEAD 之上的**虚节点**（还是只在顶部状态区体现）？
2. 文件历史树在浅克隆下只能走 REST；大文件的历史要分页，是否要本地 `log -- path` 兜底？
3. 图谱默认取几个 head？上限多少条（100 / 300）？「加载更早」是否限制次数？
4. tag 只取「名字 + 指向 sha」，还是也要 annotated tag 的 tagger / 时间？
