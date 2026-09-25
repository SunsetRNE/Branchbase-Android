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
> **形态已拍板（2026-09，产品）**：Git 模式**不做全屏页、不跳页面** —— 它就是把现在那枚
> Git 气泡的**展开面板做成多档**（动作列表 ⇄ 提交图 / 引用树 / 文件历史 / 工作区），
> 视图之间在**弹窗内部**换（真正的「框换框」），动效与内容过渡要认真做（§3.4 是规格）。
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
       （三处都在做同一件事，谁也不是「家」）

目标：  设置 → 本地仓库      = 统筹台（只看状态、更新、删除、进仓库）
        仓库页 / 文件页的 Git 气泡 = **工作台**：展开即多档面板
                                   动作列表 ⇄ 提交图 / 引用树 / 文件历史 / 工作区
                                   （**在弹窗内部换框，不跳页面**）
        决策页              = 有后果动作的落点（不变，仍是终点）
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

## 3. 形态：Git 气泡的**多档面板**（框换框，不跳页面）

> **已拍板（2026-09，产品）**：不做全屏工作台、**不点击气泡跳页面** —— 现有那枚 Git 气泡的
> 展开面板本身就是工作台，视图之间在**弹窗内部**切换（真正的「框换框」），
> 动效与内容过渡要**认真做**（§3.4 是规格，不是「顺手淡入淡出」）。

### 3.1 面板的三档（同一枚气泡，不新增页面）

| 档 | 内容 | 尺寸 | 出入口 |
|---|---|---|---|
| **L0 手柄** | 52dp 圆球 + 徽标（改动数 / `!`） | 52dp | 现状，不变（`GitBubblePanel.kt` 的 `BubbleHandle`） |
| **L1 动作列表** | 现有 `actions`（模式 / 分支 / 对比 / 同步 / 刷新）+ **新增一条「提交图 / 引用树 / 文件历史 / 工作区」入口** | 现状（40dp 圆图标 + 标签胶囊，自下而上） | 现状，不变 |
| **L2 视图** | 四档视图之一（§3.3）：自带的头（当前分支 / 工作区 / ↑↓ / 上游）+ 内容 + 底部 2–3 个动作 | **比 L1 大**：宽 `fillMaxWidth - 32dp`，高 `min(内容, 屏高 60%)`，内部滚动 | 新增：从 L1 进、面板内返回回 L1 |

三档是**同一枚气泡的三个状态**，不是三个页面：`expanded` 从 `Boolean` 变成
`sealed interface BubbleStage { Collapsed; Actions; View(kind) }`（宿主仍只持有它，不需要路由）。

### 3.2 入口（两处，都不跳「新模式页」）

| 入口 | 行为 |
|---|---|
| 仓库页 / 文件页的 Git 气泡 | 现状：点球 → L1；L1 里点「提交图」等 → **L2（框内切换）**。**仅 ③ 模式**（`showGitBubble(mode)`，不变） |
| 设置 → 本地仓库 行 | 主入口 = 打开该仓库的代码页 + **自动展开到 L2**（把「我就是要在这儿干活」一步到位）。跨页意图走既有 `RepoDeepLink`（`RepositoryScreen.kt:124`）加一个 `openGitPanel: Boolean`，**不新增页面/路由** |

> **阶段 0 落地记录（1.0.93）**：三档 = `GitPanelStage`（`ui/repository/GitPanelStage.kt`），
> 切换动效 = `PanelSwitcher`（`ui/navigation/PageTransitions.kt`，与页面级共用 `PageMotion` 常量），
> 视图档第一批 = `GitWorkspacePanel`（工作区：分支 / 领先落后 / 上游 / 改动清单 + 刷新 / 同步），
> 两个宿主（代码页 `CodePageGitPanel`、文件页）都用 `panelBack` 逐档退。**提交图 / 引用树 / 文件历史**
> 仍是 `available = false`（渲染成「还没落地」的占位，不是点了没反应的入口），按阶段接上。
> 设置列表的 `RepoDeepLink.openGitPanel`（§3.2 第二条入口）**尚未接线**，留到阶段 2。
>
> 因为不新增全屏页，[`git-version-tree-design.md`](git-version-tree-design.md) §8 与
> `git-mode-design` 早期版本里那套「`RepoRoute.GitWorkspace` + `leavePage()` +
> `SystemBarInsetsTest.fullScreenPages`」的登记动作**全部作废** —— 这里只有面板，
> 登记项收敛成「返回键层级 + 面板切换器」两条（§11）。

### 3.3 四档视图

| 视图 | 回答什么 | 数据源 | 落地阶段 |
|---|---|---|---|
| **提交图**（DAG，含**未提交的工作区虚节点**） | 版本怎么长出来的：分支、合并、引用标注，以及 HEAD 之上还没提交的那一层 | 阶段 1 走 REST `/commits`（含 `parents`）；阶段 3 起本地 `log_graph` + **图谱本地缓存**（**不设上限**，见 [`git-version-tree-design.md`](git-version-tree-design.md) §4.1） | 1 / 3 |
| **引用树** | 现在有哪些版本入口：本地 / 远端分支、tag、上游 | `local_branches` · `remote_branches` · `list_tags`（新，**含 annotated 的 tagger / 时间**） | 1 / 3 |
| **文件历史** | 这个文件改过什么 | **本地优先、REST 兜底**（已拍板：本地 `log_file` 要做）；未加深时给「加深克隆」入口 | 2 / 4 |
| **工作区** | 现在有什么没提交（**虚节点点进来的就是这一档**） | `repo_status.dirty` + 本地 diff（新） | 0 / 3 |

> 三棵树的画法、泳道算法、分页与配色**不在这里重复** —— 全部见
> [`git-version-tree-design.md`](git-version-tree-design.md) §6/§7/§13。
> 虚节点已拍板**画**（方案 A），它的画法、可点/不可点边界见那篇 §14.1。

### 3.4 框换框的动效与内容过渡（**这一节是硬规格**）

现状：气泡的展开/收起已经有一套 —— `AnimatedVisibility(fadeIn + expandVertically)` +
`bubbleEnter/bubbleExit`（`ui/theme/Motion.kt:303`，180ms 缩放+淡入）、
`ElementMotion.BUBBLE_MS = 180` / `STAGGER_MS = 28`（条目逐条入场）。
**面板之间的切换要沿用同一套语义**，不许自造一套参数 —— 全项目的动效真源是
[`ui-design.md`](ui-design.md) §3 与 `ui/navigation/PageTransitions.kt` 的 `PageMotion`。

| 切换 | 语义 | 规格（**复用现有常量**） |
|---|---|---|
| L1 ⇄ L2（动作列表 ↔ 视图） | **有层级**：进 L2 = 前进、回 L1 = 返回 | `PageMotion` 的两条：新内容**滑入面板宽度的 1/10 + 淡入**（220ms，`PageMotion.EnterEasing`，起播延迟 33ms），旧内容**原地淡出**（100ms，线性）—— **同一时刻只有一个运动体** |
| L2 内切视图（提交图 ⇄ 引用树 ⇄ …） | **同级**：没有前后关系 | fade-through：旧内容淡净（`PageMotion.FADE_OUT_MS = 110`）→ 新内容延迟 110ms、`FADE_IN_MS = 180` 淡入（**两段不重叠**）—— 横向滑动会暗示不存在的层级，且重叠期是最贵的一笔 |
| L1 → 危险动作 / 决策页 | 不是框换框：**走现有决策页**（那是页级切换，用 `PageSwitcher` 自己的动效） | 不在本节管辖 |
| 面板尺寸变化（L1 紧凑 ⇄ L2 高） | 随内容变 | 容器 `animateContentSize(tween(ElementMotion.REVEAL_MS = 220, easing = PageMotion.EnterEasing))`；**尺寸动画发生在 fade-through 的空白段**（此时只有容器在动） |
| L2 内容加载（缓存未命中 / 远端分页） | 骨架 → 内容 | `PlaceholderSwap`（`ui/theme/Motion.kt:247`：延迟现身 + 骨架先退 / 内容再进 + 尺寸动画）—— **不许 `Crossfade`**（全仓已有结论，见 `ContributionWall.kt:96`） |
| 虚节点出现 / 消失（`dirty` 0 ⇄ N） | 元素级 | 淡入淡出 + 尺寸跟随（`ElementMotion.COLOR_MS = 180` 一档；它是「状态」不是「新页」，不许播换页动画） |

**落地形态**：在 `ui/navigation/PageTransitions.kt`（动效唯一真源）里加一个
**`PanelSwitcher`**，与 `PageSwitcher` / `TabSwitcher` 共用 `PageMotion` 常量与
`pageIsCurrent` 的 `LocalPageActive` 下发，只是容器不是全屏、并自带「面板内返回」。

**四条不许（都是这个仓库已经付过代价的）**：

1. **不许重叠期两档同时画**（fade-through 的空白段是有意取舍，不是 bug）；
2. **不许在位移期间叠加缩放**（`PageMotion` 的「试过又退回去的」：整页/整框缩放会重采样，文字发虚）；
3. **不许用 `Crossfade`** 换骨架/内容（`PlaceholderSwap` 是唯一口径）；
4. **不许给 `AnimatedContent` 里塞多个子元素**（内容在 Box 里会互叠 —— `CrossfadeLayoutTest` 钉过这个坑）。

**验收**：真机 perfBeta 出包 → `tools/perf/frame-baseline.py compare`，面板切换场景
**≥100ms 条数降、超 16ms 比例降、慢帧均值不回升**；台账回写
[`frame-perf-design.md`](frame-perf-design.md) §5 与版本条目。

### 3.5 返回键链路（面板把「框」当层用）

现状已经是：**展开时消费返回**（`PageBackHandler(expanded) { expanded = false }`，
`RepositoryScreen.kt:1159`、文件页同款）。加入 L2 之后变成三层，
**注册顺序 = 优先级**（后注册优先，`PageSwitcher` 的既有约定）：

```
L2 注册：返回 = 回 L1（面板内返回，新增）
  ↓ 未消费
L1 注册：返回 = 收起面板（现状：PageBackHandler(expanded)）
  ↓ 未消费
页面自己的路由（仓库页 / 文件页）
```

要求：**L2 的注册只在「当前档就是 L2」时生效**，退场中的旧档一律放手
（`LocalPageActive` 的同一套纪律，`PageTransitions.kt:210` 的 `pageIsCurrent`）。

---

## 4. 设置里的「本地仓库」收敛成什么

**目标形态**（一屏一眼）：每行 = 仓库名 + 分支 + 状态胶囊（干净 / N 处改动 / ↑ahead ↓behind）+ 三个动作：

| 动作 | 语义 | 备注 |
|---|---|---|
| 进入 | 打开该仓库的代码页**并自动展开到 L2**（§3.2 的 `RepoDeepLink.openGitPanel`） | **主入口**，整行可点；不新增页面 |
| 更新 | `pull`（fast-forward） | 保持现状；分叉仍进 P0-1 决策页 |
| 删除 | 删除本地副本 | 保持现状（含未推送警告升级） |

**迁走的行内动作**（提交 / 推送 / 撤销 / 上游 / 回退 Git 化 / 分支 / 同步）→ 工作台的动作区与视图。

**页头新增「管理」**（2026-09 拍板）：列表页顶部加一个「管理」按钮 → 进**本地仓库内容管理页**
（新页面），按仓库列出**占用**并支持**针对单个仓库清理** —— 理由：不是所有仓库都那么大，
要能只清一个（清什么见 `git-version-tree-design.md` §4.1：缓存方案 A 之下，「占用」就是本地仓库本身，
所以这里的「清理」= 管理本地副本的体积/删除，而不是清一份额外的缓存）。

> **已登记（后续问题，现阶段不做）**：本地仓库**列表行本身的视觉与信息层级要重绘** ——
> 现在没有按语义边界区分（名称 / 分支 / 状态 / 动作混在一行里，塞不下更多动作）。
> 用户明确「不能硬加边界」，所以这不是顺手改一处 padding 的事，得按
> [`ui-design.md`](ui-design.md) 的行型与分组规范重新细化设计。**先把这条记在这里**，
> 等「管理」入口与 §3 的面板都落地、真实动作收敛完之后再动它（那时才知道要分几组）。

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

### 6.2 UI：冲突弹窗 → 详情对比页（已拍板 2026-09）

**产品口径**：`merge_branch` 一旦发现冲突，**立刻弹窗**告诉用户「有冲突」；弹窗上给一个按钮，
点它进**详情对比页**（逐文件对比 + 解决）。**关键要求：弹窗出现的那一刻就开始解析**
（ours / theirs / base 的 diff、冲突文件清单），等用户点进页面时**内容是现成的** ——
不许让用户进了新页面才开始等。

```
merge_branch ──► outcome == "conflict"
                    │
                    ├─ 立即：冲突弹窗（事实：N 个文件冲突 · 分支 · 后果说明）
                    │        └─ 同步启动「预解析」（后台、可取消、带进度）
                    │              └─ 逐文件算 ours / theirs / base 的 diff + 冲突块
                    │
                    └─ 用户点「查看并解决」──► 详情对比页（复用 BranchCompareScreen 的 diff 渲染）
                                                逐文件：用我方 / 用对方 / 手工（:editor）→ 标记已解决
                                                全部解决 → 「提交合并」；任何时候 → 「放弃合并」
```

**预解析的契约**（这一条是本节的重点，别做成「页面自己再算一遍」）：

| 项 | 约定 |
|---|---|
| 触发 | 冲突弹窗**出现时**（不是用户点按钮时） |
| 位置 | 引擎侧一个新接口（`analyze_conflicts(dir)`）在后台线程跑；结果落**进程内缓存**（冲突是瞬态状态，不落盘） |
| 进度 | 走既有长任务口径（`TaskStore` + 进度；冲突文件通常几个到几十个，多数情况下不到 1s） |
| 可取消 | 用户关掉弹窗 / 离开页面 → 取消；**不进决策、不写盘**（只读分析） |
| 页面侧 | 进页面时：有结果 → 直接渲染；还在算 → `PlaceholderSwap` 的骨架 + 已出的部分；失败 → 说明原因并能重试 |
| 不许 | ① 页面打开后再从头算（用户已经等过一次）；② 把预解析做成「页面状态」，导致返回再进重算 |

**详情对比页**是**页面**（不是面板内的一档）：冲突解决要大面积看 diff，塞进气泡面板会是灾难。
它复用现有 diff 渲染（`BranchDiff.kt` + `BranchCompareScreen` 的组件），
返回键与登记按 §11 走（新增全屏页要进 `SystemBarInsetsTest.fullScreenPages`）。

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

**不经引擎、但同样要新建的一件东西**：**图谱本地缓存**（产品决策「不设上限 + 接受体积增长」
的落地形态）——按 `(owner, repo, ref)` 分片、`sha` 主键去重、增量 append、命中即先直出，
并给清理入口。形态与约定见 [`git-version-tree-design.md`](git-version-tree-design.md) §4.1；
存哪（Room / JSON 文件）仍是未决问题（§10）。

**每一条的落地清单**（缺一步就白干，与 [`git-version-tree-design.md`](git-version-tree-design.md) §5 同一套）：
`mod.rs` → `core/src/bridge/jni.rs` 导出 → `RustBridge.kt` 的 `external` + suspend wrapper
（约定：`null` = 成功，失败 `ERROR:` 前缀，见 `GitErrorConventionTest`）→ `JniSignatureTest` 逐参数比对 →
**重建 `.so`** → `cd core && cargo test` → 版本条目。

---

## 9. 分阶段落地（每阶段可独立交付 / 可回滚）

| 阶段 | 交付 | 依赖 | 验收 |
|---|---|---|---|
| **0**（已落地）| **面板三档**（`GitPanelStage`：Collapsed / Actions / View）+ **`PanelSwitcher`**（§3.4 的动效规格）+ **工作区档**（脏文件清单 + 刷新 / 同步；数据与徽标同源 `LocalRepoGitState`） | 无（零 Rust） | 真机：框换框不闪、不抖、不叠；三层返回键链路正确（L2 → L1 → 页面） |
| **1** | 提交图（REST 数据 + `CommitGraphLayout`，含 **A 方案的未提交虚节点**）+ 引用树（`fetch_remote` + tags 占位） | 阶段 0 | 多分支仓库的图不断线、不串道（`git-version-tree-design.md` §6 的用例）；虚节点的出现/消失不播换页动画 |
| **2** | 危险动作收口：工作台只做入口，全部落现有决策页；设置列表开始下线行内动作（一次一个） | 阶段 1 | 每个危险动作都能从工作台走到对应决策页并回到工作台 |
| **3** | `diff_worktree` / `diff_commit` + `log_graph(limit, skip)` + `list_tags`（全字段）+ `log_file`（重建 `.so`）+ **图谱本地缓存**（Room + 清理入口） | Rust | `cargo test` + `JniSignatureTest`；缓存命中时二次打开不重新拉全量 |
| **4** | `fetch_deepen`（任务中心 + 进度）+ 文件历史**本地兜底** + 离线图谱（LocalSource 优先、未推送段） | 阶段 3 | 飞行模式下能看图、能看文件历史；加深失败不破坏已有仓库 |
| **5** | 本地合并（D11 已放开）+ **冲突弹窗 + 预解析 + 详情对比页**（§6.2）+ PR 冲突的「拉到本地解决」 | 阶段 4 | 构造真实冲突仓库跑通：弹窗 → 点进页面**内容已就绪**（预解析生效）→ 逐文件解决 → 提交合并 / 放弃合并 |
| **6** | 设置 → 本地仓库 →「管理」页（按仓库看占用 / 清理）+ 本地仓库列表重绘（§4 的登记项） | 阶段 4（要有加深后的占用才有的管） | 占用数字与目录实际大小一致；删除仓库后不留派生数据 |

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

### 10.1 已拍板 / 已澄清（2026-09，详见 [`git-version-tree-design.md`](git-version-tree-design.md) §3 / §14）

| 问题 | 结论 |
|---|---|
| 文件历史要不要本地 `log -- path` 兜底 | **要**：已加深走本地（离线、无 API 限额），REST 兜底 |
| 图谱 head 数 / 条数上限 / 「加载更早」次数 | **都不设上限**；用**图谱本地缓存**抵住请求量与体积（**体积增长接受**，但必须有清理出口） |
| tag 字段 | **都要**：`name` + `sha` + annotated 的 `tagger` / 时间 / 说明；非 annotated 留空 |
| Git 模式的形态 | **气泡面板、框换框、不跳页面**（§3 开头）；全屏工作台与 `RepoRoute.GitWorkspace` 的方案作废 |
| 「虚节点」要不要画 | **画（方案 A）**：目标是工作台，「多一个浅色节点」值得。画法、只有哪些交互可用、
  多个状态怎么并成一个节点：见 [`git-version-tree-design.md`](git-version-tree-design.md) §14.1 |

---

### 10.2 决策说明一：D11 边界（允不允许 merge）—— 要拍的到底是什么

**D11 原文**（[`local-git-engine-design.md`](local-git-engine-design.md) §5）：
**不改写已推送历史** —— 已推送的提交只能 revert，现状写法是「不做 merge / rebase」
（`SyncDecisionScreens.kt`、`core/src/git/mod.rs` 的 `revert_commit` 都按它写注释）。

**先把两件事分开，它们被同一句话绑在一起太久了**：

| 动作 | 会不会改写已有提交 | 与 D11 的关系 |
|---|---|---|
| `merge`（三方合并） | **不会**：在现有提交之上**新增**一个两父的合并提交，所有旧 sha 不变 | 与 D11 **不冲突** —— 禁止它从来没有技术理由，只是当年顺手写进了同一条 |
| `rebase` / `amend 已推送` / 强推 | **会**：替换/丢弃已推送的提交，别人的仓库已经有那些 sha | D11 真正要禁的就是这一类 |

**今天的处境**（`core/src/git/mod.rs` 的 `pull_repo`）：pull **只做 fast-forward**，
两边都有新提交（分叉）时直接 `Err("nff: …")` → 上层弹 P0-1 决策页 → 页面只有两条路：
「保留本地（暂不处理）」或「放弃本地（`reset --hard origin/x`）」。也就是说，
**「远端有人提交 + 我本地也有未推送提交」这种最常见的分叉，用户只能丢一边** ——
工作台想做的「提交合并 / 冲突解决」在能力上根本不存在（[`git-mode-design.md`](git-mode-design.md) §6）。

**拍「允许 merge」意味着**：

- 分叉可解：`merge_branch` 产生合并提交 → 直接 push（不改写任何历史）；
- 冲突从「死路」变成可处理：冲突清单 → 逐文件「用我方 / 用对方 / 手工（`:editor`）」→
  提交合并，或随时「放弃合并」（回到合并前，不丢数据）；
- 引擎新增 6 个接口 + 冲突解决视图（§6.1/§6.2），排期落在**阶段 5**。

**两条硬依赖与三个必须在设计里交代的风险**：

1. **依赖加深克隆（阶段 4）**：合并需要**共同祖先**，而浅克隆只有 1 层历史 ——
   没有共同祖先时 libgit2 给不出 merge base。所以「允许 merge」在排期上必然晚于 `fetch_deepen`；
2. **依赖提交身份**（`commit.author.name/email`，已有）；
3. 风险：① **合并到一半被杀**（仓库停在 merge 中）——必须设计「进入工作台时发现未完成的合并 →
   继续 / 放弃」，否则用户会卡死；② 合并提交的信息与敏感扫描口径（信息是自动生成的，要不要扫、
   扫失败怎么办，要一句话说清）；③ 合并后的 push 走既有路径（`push_repo`），但**分叉页的文案与选项要重写**
   （不再是「保留 / 放弃」两条，而是「合并 / 保留 / 放弃」三条）。

**已拍板（2026-09）：选 A —— 允许 merge，仍不做 rebase**。D11 措辞随之修正为
「**不改写已推送历史**：不做 rebase / amend 已推送 / 强推；**merge 允许**（它只新增提交）」，
实现落地时同步改 [`local-git-engine-design.md`](local-git-engine-design.md) §5 的登记行与代码注释。

冲突的交互形态也一并拍板：**冲突弹窗（出现即开始预解析）→ 详情对比页**（见 §6.2），
不是「面板里塞一个解决视图」。

### 10.3 决策说明二：图谱缓存存哪、清理入口放哪

**缓存是为了解决什么**：图谱要「打开就能画」。数据有两条来路 —— REST `/commits`
（GitHub 限流、分页、每次都要重拉）与本地 libgit2（快、离线）。
产品已拍「不设上限」，一个活跃仓库几万条提交是常态，**每次打开从头拉不可行**，所以要有本地副本。
真正要选的不是「要不要缓存」，而是**这份副本由谁来当**：

| 选项 | 是什么 | 优点 | 缺点 |
|---|---|---|---|
| **A. 让本地仓库自己当缓存**（首选，配合 `fetch_deepen`） | 加深克隆后，提交就在 `.git/objects` 里；libgit2 直接读 | 不重复存事实；git 自己压缩 / gc；离线、无 API、零维护 | 只对有本地副本的仓库有效；没克隆的要先加深（几 MB~几十 MB，长任务） |
| **B. Room 表**（`graph_commits` + 水位行） | 结构化表：`sha` 主键去重、按 `(owner, repo, ref)` 索引分页、事务写入 | 分页 / 去重 / 统计占用都是白送的；**仓库里已有 3 个 Room 库（其中一个就是缓存库 `SearchCacheDatabase`）**，KSP 已配好 | 多一套 schema 与迁移；重复存了一份 git 已有的事实 |
| **C. JSON / NDJSON 文件**（`noBackupFilesDir/graph-cache/…`） | 每 `(repo, ref)` 一个追加式文件 | 清理 = 删文件；无 schema 迁移 | 读一页要解析整个文件（大仓库几 MB）；去重要在内存做；并发写要自己保证（临时文件 + rename） |

**已拍板（2026-09）：选 A —— 本地仓库自己当缓存**。理由（产品原话的意思）：
**应用本身就是本地优先的实现，相关数据也应当落在本地** —— 不另造一份 Room / JSON 副本。
由此推出三条口径：

1. **不建独立的图谱缓存**：没有本地副本的仓库，图谱数据按需从 REST 取（沿用现有内存级缓存，
   无上限分页）；想要「打开就秒出、离线可看」，就走**加深克隆**把历史落到本地仓库（阶段 4）；
2. **「占用」= 本地仓库本身的体积**：所以清理不是「清一份缓存」，而是管理本地副本
   （见 §4 的「管理」页：按仓库看占用、按仓库清理 / 删除）；
3. **删除本地仓库时顺带删掉该仓库的一切派生数据**（现有的草稿、以及未来的图谱内存缓存键）——
   这条无论选哪个方案都成立，继续有效。

> 若将来真机数据表明「远端路径的重拉」不可接受（比如没克隆的仓库图谱打开太慢），
> 再回来评估要不要加持久缓存 —— 那时也优先 Room（仓库里已有缓存库先例 `SearchCacheDatabase`），
> 而不是 JSON 文件。这条写在这里，免得以后重新讨论一遍。

## 11. 登记清单（**漏了不会红**）

| 要动的东西 | 登记处 |
|---|---|
| **面板多了一档（L2）** | 返回键层级要在注释与 `NAVIGATION-NOTES.md` 里写清（L2 → L1 → 页面）；**不新增页面、不动 `route` / `leavePage()` / `fullScreenPages`**（形态已定：气泡面板） |
| 新增 `PanelSwitcher` | `ui/navigation/PageTransitions.kt`（动效唯一真源）+ `PageTransitionsTest` 的源码级钉子（与 `PageSwitcher`/`TabSwitcher` 共用 `pageIsCurrent` 下发） |
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
