# 仓库内 Git 模式 · 开发进度与设计（唯一文档）

> **这份文档是什么**：Git 模式（气泡工作台 / 四档视图 / 提交与合并 / 冲突解决）的
> **唯一**开发进度与设计真源。2026-09 收束：原先分散在 `git-version-tree-design.md`（可视化子设计）
> 与若干版本条目里的进度、决策、缺口，全部并到这里；那份子设计已收敛成指向本文的指针。
>
> **谁该读**：改 `ui/repository/`（Git 气泡 / 仓库页 / 文件页）、`ui/profile/SubPageScreens.kt`
> （本地仓库页）、`ui/decision/`（决策页）、`core/src/git/`（引擎）的人。
>
> **状态：进行中**（不再是「草稿 · 未落地」）：阶段 0 与阶段 1 已落地并在 Beta 上跑（1.0.93 / 1.0.94），
> 其余阶段见 §8。本文里凡标「待落地」的，**都没写代码**。
>
> **真源**：`core/src/git/mod.rs`（引擎）· [`local-git-engine-design.md`](local-git-engine-design.md)
> §3/§4/§7 · [`decision-pages-design.md`](decision-pages-design.md) §1/§2/§3 ·
> [`features-design.md`](features-design.md) §5 · [`NAVIGATION-NOTES.md`](NAVIGATION-NOTES.md) ·
> [`frame-perf-design.md`](frame-perf-design.md) §3/§6 · [`ui-design.md`](ui-design.md) §1/§3。

---

## 1. 产品判断（这一节定方向，别绕开）

> 设置里的「本地仓库」列表**只适用于直观统筹**（拉了哪些、各在什么状态）；
> 真正的版本管理、树可视化、协作、提交、提交合并、冲突解决，**回到「Git 模式」在对应仓库里做**。

由此确立三条，其余设计都从它们推出：

1. **不新增「Git 模式页」**：Git 模式就是**现有那枚 Git 气泡**（代码页 / 文件页右下角）
   展开出来的**多档面板** —— 视图在**弹窗内部**换框，**不跳页面**（动效规格见 §3.3）；
2. **设置里的本地仓库列表收敛成统筹台**（进入 / 更新 / 删除 + 页头「管理」），
   行内那些提交 / 推送 / 分支 / 同步动作**逐步迁进面板**（过渡期双入口，见 §5）；
3. **动作分三档**：面板内直接执行（安全）/ 走决策页（有后果）/ 明确不做（§6）。

---

## 2. 现状（已落地 / 未落地）

| 能力 | 状态 | 位置 |
|---|---|---|
| 面板三档（折叠 / 动作列表 / 视图） | **已落地 1.0.93** | `ui/repository/GitPanelStage.kt` |
| 框换框动效（`PanelSwitcher`） | **已落地 1.0.93** | `ui/navigation/PageTransitions.kt` |
| 「工作区」档（分支 / 领先落后 / 改动清单 / 刷新 / 同步） | **已落地 1.0.93** | `ui/repository/GitWorkspacePanel.kt` |
| 分档标签条（工作区 ✅ / 提交图 ✅ / 引用树 ⏳ / 文件历史 ⏳） | **已落地 1.0.94** | 同上（`GitPanelViewHost`） |
| 「提交图」档（REST 数据 + 泳道布局 + 分页脚注） | **已落地 1.0.94** | `ui/repository/CommitGraphPanel.kt` · `CommitGraphModels.kt` |
| 未提交**虚节点**（方案 A） | **已落地 1.0.94** | 同上（`GraphRow.WorkingTree`） |
| 引用树 / 文件历史档 | 待落地（阶段 2 / 4） | — |
| 设置列表「管理」页 / 列表重绘 | 待落地（阶段 6 / 后续问题） | — |
| 本地合并 + 冲突解决 | 待落地（阶段 5） | — |
| 引擎 `log_graph` / `list_tags` / `log_file` / `diff_*` / `merge_*` | 待落地（阶段 3–5） | `core/src/git/mod.rs` |

**已落地的验证口径**：`:app:testDebugUnitTest`（`GitPanelStageTest` 7 例、`CommitGraphLayoutTest` 11 例、
`PageTransitionsTest` 面板过渡与「三个切换器都下发 `LocalPageActive`」）· `assembleDebug` ·
`check-i18n --min-coverage 100`。

---

## 3. 形态与交互

### 3.1 三档结构

```
Collapsed ──点球──► Actions（动作列表）──点「工作区 / 提交图」等──► View(kind)
    ▲                    ▲                                          │
    └──── 点空白 ─────────┴──────────── 返回 / 面板内返回 ────────────┘
```

- 状态是**一个** `GitPanelStage`（`Collapsed / Actions / View(kind)`），不是两个 Boolean；
- 返回键一条规则：`panelBack` 逐档退（**视图 → 动作列表 → 收起 → 页面**），
  两个宿主同一条 —— 源码级钉子盯着别只改一边；
- 面板尺寸随内容变（动作列表矮、视图高）：容器 `animateContentSize(tween(220, EnterEasing))`。

### 3.2 视图档（`GitPanelKind`）

| 档 | 内容 | 数据源 | 状态 |
|---|---|---|---|
| **工作区** | 分支 / 领先落后 / 上游 / 改动文件清单 / 刷新 / 同步 | `LocalRepoGitState`（一次 `repo_status`，与徽标同源） | 已落地 |
| **提交图** | 泳道 DAG + **未提交虚节点** + 分页脚注 | REST `/commits?sha=&per_page=100`（**保留 `parents`**）；阶段 3 起本地 `log_graph` | 已落地（REST 版） |
| **引用树** | 本地 / 远端分支、tag、上游 | `local_branches` · `remote_branches` · `list_tags`（新） | 待落地（阶段 2） |
| **文件历史** | 该文件的提交序列 | **本地优先**（`log_file`，已加深时）/ REST `/commits?path=` 兜底 | 待落地（阶段 4） |

未落地的档在标签条上标「**待接入**」，点进去是一句如实的说明 —— **不是死按钮**，
也不假装能用（`available = false` 只影响样式与文案，不影响可达性）。

### 3.3 框换框的动效规格（复用页面级常量，不许自造）

| 切换 | 规格 |
|---|---|
| 进 / 退档（L1 ⇄ L2） | `pageEnterTransition(±1)`：新内容**滑入容器宽度 1/10 + 淡入 220ms**（`EnterEasing`，起播延迟 33ms）；旧内容**原地淡出 100ms** —— **同一时刻只有一个运动体** |
| 同层换挡（工作区 ⇄ 提交图） | fade-through：`FADE_OUT_MS = 110` 淡净 → 延迟 → `FADE_IN_MS = 180` 淡入，**两段不重叠** |
| 面板尺寸变化 | `animateContentSize(tween(220, EnterEasing))`，尺寸动画落在 fade-through 的空白段 |
| 内容加载 | `PlaceholderSwap`（延迟现身 + 骨架先退 / 内容再进） |

**四条不许**（都是这个仓库付过代价的）：不重叠两档同时画 · 位移期间不叠缩放 ·
不用 `Crossfade` 换骨架/内容 · `AnimatedContent` 内容不塞多个子元素。
实现落在 `ui/navigation/PageTransitions.kt` 的 `PanelSwitcher`（动效唯一真源），
与 `PageSwitcher` / `TabSwitcher` 共用 `PageMotion` 与 `LocalPageActive` 下发。

### 3.4 入口

| 入口 | 行为 | 状态 |
|---|---|---|
| 代码页 / 文件页的 Git 气泡 | 点球 → 动作列表 → 视图档（仅「本地仓库（Git）」提交模式，`showGitBubble` 门控不变） | 已落地 |
| 设置 → 本地仓库 行 | 打开该仓库代码页 + **自动展开到视图档**（`RepoDeepLink` 加 `openGitPanel`） | 待落地（阶段 2） |

---

## 4. 可视化（提交图）

### 4.1 数据与分页

- **REST 版（当前）**：`GET /repos/{o}/{r}/commits?sha={branch}&per_page=100`，
  响应里**本来就带 `parents`** —— 图的解析独立成 `parseGraphCommits`（`CommitGraphModels.kt`），
  **不去动**提交列表那份 `parseCommits`（两者用途不同，混改会牵动列表页与它的单测）；
- **本地版（阶段 3）**：新增 `log_graph(dir, limit, skip)`，浅克隆下先 `fetch_deepen`；
- **不设上限（已拍板）**：head 全取、「加载更早」不限次数；**但分页照旧**，
  且列表尾部必须如实写「已加载 N 条 · 更早历史未加载」——**不许把截断画成历史的尽头**。

### 4.2 泳道布局（`CommitGraphLayout`，纯函数 + 11 例单测）

一条泳道 = 一个「还在等谁出现」的父提交：

- 每条提交认领正等它的泳道（取最左），没有就用最左空位 / 新开一条；
- **第一父继承当前泳道**（除非已有泳道在等它 —— 两条分支汇合时并过去，本泳道释放）；
- 额外父各占一条；空出来的泳道**当场压实**，位移画成斜线（否则线会在行边界断开）；
- 父不在已加载窗口里 → `dangling`，**画终止符而不是断头线**；
- 几何**预计算**（每行的泳道与线段），绘制期只按行画自己那几条 —— 一屏百行时这是
  「能滑动」与「滑动掉帧」的分界；泳道配色走 `ui/theme/Color.kt` 的 `Primer.GraphLanes`
  （复用语义色，不新造色板）。

### 4.3 未提交虚节点（方案 A，已拍板）

| 项 | 约定 |
|---|---|
| 位置 | HEAD **之上**一行；`dirty` 从 0 → N 出现、N → 0 消失 |
| 画法 | 虚线圆 + 不走泳道实线、**不画 sha**、标「未提交（HEAD 之上）」 |
| 数量 | **一个** —— 「工作区改动 + 索引已暂存」合成一个「未提交」节点；stash 不进图（不做隐式 stash） |
| 交互 | 点它 → 「工作区」档；**因无 sha**，提交详情 / 复制 sha / 对比 / 回滚对它一律不成立（不提供、也不置灰误导） |
| 动效 | 元素级淡入淡出 + 尺寸跟随，**不播换页动画**（它是状态，不是新页） |

---

## 5. 设置里的「本地仓库」（统筹台）

目标形态：每行 = 仓库名 + 分支 + 状态胶囊 + **进入 / 更新 / 删除**；页头加**「管理」**
（→ 本地仓库内容管理页：**按仓库**看占用、按仓库清理 —— 不是所有仓库都那么大）。

- **「占用」= 本地仓库本身的体积**（缓存选型见 §6.3）：清理就是管理本地副本，不存在第二份缓存；
- **过渡期**：行内动作（提交 / 推送 / 撤销 / 上游 / 回退 / 分支 / 同步）暂时保留，
  每处标注「真源在 Git 面板」，**每迁走一个就删掉一个**（一次一个，跟着该动作在面板里验收通过）；
- **已登记的后续问题（现阶段不做）**：本地仓库**列表行本身要重绘** ——
  现在名称 / 分支 / 状态 / 动作混在一行里，**没按语义边界区分**；用户明确「不能硬加边界」，
  所以要按 [`ui-design.md`](ui-design.md) 的行型与分组规范重新细化，等动作收敛完再动。

---

## 6. 决策台账

### 6.1 生效中

| # | 决策 | 依据 / 落点 |
|---|---|---|
| D-a | **Git 模式 = 气泡多档面板，不跳页面** | §1 / §3；全屏工作台方案已废弃（见 §6.2） |
| D-b | **设置列表只做统筹**，动作迁进面板 | §5 |
| D-c | **未提交虚节点画（方案 A）** | §4.3 |
| D-d | **提交图不设上限**（head 全取、加载更早不限次数），以「尾部如实说明」为代价 | §4.1 |
| D-e | **文件历史本地优先**（`log_file`），REST 兜底；未加深时给「加深克隆」入口 | §3.2 |
| D-f | **tag 取全字段**（`name` + `sha` + annotated 的 tagger / 时间 / 说明；非 annotated 留空、不填假值） | §7 |
| D-g | **允许 merge，不做 rebase** | §6.4；D11 措辞随之修正 |
| D-h | **冲突交互 = 冲突弹窗（出现即预解析）→ 详情对比页** | §6.4 |
| D-i | **不另建图谱缓存**（Room / JSON 都否掉）：本地仓库对象库就是缓存 | §6.3 |

### 6.2 已废弃 / 已被取代（**不要再捡回来**）

| 旧结论 | 为什么丢 | 取代它的是 |
|---|---|---|
| 「全屏工作台 + `RepoRoute.GitWorkspace` + `leavePage()` + `SystemBarInsetsTest` 登记」 | 产品改为「气泡面板内换框」，不跳页面 | D-a（§3） |
| 虚节点「不画，只在顶部状态区」（方案 B）/「可切换」（方案 C） | 已拍 A：目标是工作台，多一个浅色节点值得 | §4.3 |
| 图谱缓存三选一（本地对象库 / Room / JSON 对比表） | 已拍「不另建缓存」，比较过程不再占篇幅；**只留一句**：真要重开评估时优先 Room（仓库已有缓存库先例 `SearchCacheDatabase`），不是 JSON 文件 | §6.3 |
| D11 旧措辞「不做 merge / rebase」 | merge 只新增提交、不改写历史，与 D11 不冲突 —— 禁止它从来没有技术理由 | D-g（§6.4） |
| `git-version-tree-design.md` 作为独立设计文档 | 与本文职责重叠（同样的三视图 / 接口 / 阶段表各写一份，改一处漏一处） | 本文（那份已收敛成指针） |

### 6.3 缓存（D-i 的边界）

不另建 Room / JSON 副本：**加深克隆之后提交就在本地仓库的 `.git/objects` 里**（离线、无 API 限额、
git 自己压缩与 gc）。没有本地副本的仓库，图谱按需从 REST 取 + 内存级缓存；
想「打开就秒出」就走 `fetch_deepen` 把历史落到本地。

### 6.4 合并与冲突（D-g / D-h）

- **允许 `merge`**（产生两父合并提交，已有 sha 一字不变）；**仍禁** rebase / amend 已推送 / 强推；
  [`local-git-engine-design.md`](local-git-engine-design.md) §5 的 D11 行落地时同步改措辞，
  分叉页文案由两条变三条（合并 / 保留 / 放弃）；
- **冲突流程**：

```
merge_branch ─► outcome == "conflict"
     ├─ 立刻弹窗（N 个文件冲突 · 分支 · 后果）
     │    └─ 同时启动「预解析」：后台算 ours / theirs / base 的 diff 与冲突块（可取消 · 只读 · 不落盘）
     └─ 点「查看并解决」─► 详情对比页（复用 BranchDiff 渲染）
                          逐文件：用我方 / 用对方 / 手工（:editor）→ 标记已解决
                          全部解决 → 「提交合并」；任何时候 → 「放弃合并」（回到合并前，不丢数据）
```

- **预解析契约**：触发点是**弹窗出现那一刻**（不是点按钮时）；结果落进程内缓存；
  进页面时「有结果直接渲染 / 还在算先出骨架 + 已出的部分 / 失败说明原因可重试」；
  **两条不许**：页面打开后再从头算、把预解析做成页面状态（返回再进会重算）；
- **风险（落地前必须有对策）**：① 合并到一半被杀 → 仓库停在 merge 中，
  进面板要能「继续 / 放弃」；② 合并提交信息的敏感扫描口径要说清；③ 浅克隆没有共同祖先 →
  merge 必然晚于 `fetch_deepen`；④ 详情对比页是**新全屏页** → 登记见 §9。

---

## 7. 引擎接口（现有 + 待新增）

**现有**（[`local-git-engine-design.md`](local-git-engine-design.md) §3）：clone / pull(FF) / push / commit /
分支增删切 / 撤销丢弃 / `repo_status` / `scan_sensitive` / 证书与代理 / clone 进度与取消。

**待新增**（每条都要：`mod.rs` → `jni.rs` 导出 → `RustBridge` wrapper（`null` = 成功）→
`JniSignatureTest` → **重建 `.so`** → `cargo test`）：

| # | 接口 | 用途 | 阶段 |
|---|---|---|---|
| 1 | `log_graph(dir, limit, skip)` | 本地提交图（浅克隆先加深） | 3 |
| 2 | `list_tags(dir)` | 引用树：`{name, sha, annotated, target_sha?, tagger{name,email,time}?, message?}` | 3 |
| 3 | `log_file(dir, path, limit, skip)` | 文件历史（本地优先） | 4 |
| 4 | `fetch_deepen(dir, depth, token)` | 加深克隆（长任务走任务中心） | 4 |
| 5 | `diff_worktree(dir)` · `diff_commit(dir, sha)` | 工作区 / 提交的本地 diff | 3 |
| 6 | `merge_branch(dir, branch, token)` | 三方合并（不改写历史） | 5 |
| 7 | `analyze_conflicts(dir)` · `conflict_files(dir)` · `resolve_conflict(dir, path, side)` · `write_resolved(...)` · `merge_continue(...)` · `merge_abort(dir)` | 预解析 + 逐文件解决 + 收尾 | 5 |

---

## 8. 路线图（每阶段可独立交付 / 回滚）

| 阶段 | 内容 | 状态 |
|---|---|---|
| **0** | 面板三档 + `PanelSwitcher` + 「工作区」档 | **已落地 1.0.93** |
| **1** | 「提交图」档（REST + 泳道布局）+ 虚节点 + 分档标签条 | **已落地 1.0.94** |
| **2** | 「引用树」档（`local_branches` / `remote_branches` / tags 占位）+ 危险动作收口（全部落决策页）+ 设置列表开始下线行内动作 + `RepoDeepLink.openGitPanel` | 待做 |
| **3** | `log_graph` / `list_tags` / `log_file` / `diff_worktree` / `diff_commit`（重建 `.so`）+ 工作区档的本地 diff + 提交图的本地来源 | 待做 |
| **4** | `fetch_deepen`（任务中心 + 进度）+ 「文件历史」档（本地优先 + REST 兜底）+ 离线图谱（LocalSource 优先、未推送段） | 待做 |
| **5** | 本地合并（D-g）+ 冲突弹窗 / 预解析 / 详情对比页（D-h）+ PR 冲突的「拉到本地解决」 | 待做 |
| **6** | 设置 → 本地仓库「管理」页（按仓库看占用 / 清理）+ 列表重绘（§5 的登记项） | 待做 |

---

## 9. 登记清单（**漏了不会红**）

| 要动的东西 | 登记处 |
|---|---|
| 面板多一档 / 返回键层级变化 | 注释与本文件的 §3.1；**不新增页面、不动 `route` / `leavePage()` / `fullScreenPages`**（形态已定） |
| `PanelSwitcher` 改动 | `ui/navigation/PageTransitions.kt`（动效唯一真源）+ `PageTransitionsTest`（「三个切换器都下发 `LocalPageActive`」） |
| **新全屏页**（阶段 5 的冲突详情对比页、阶段 6 的管理页） | `SystemBarInsetsTest.kt` 的 `fullScreenPages` + 只走 `PageBackHandler`（裸 `BackHandler` 被全目录扫描） |
| 新 `ui/` 文件里的颜色 | 走 `Primer` 角色；泳道配色在 `ui/theme/Color.kt`（`ThemeConvergenceTest`） |
| 新字符串 | `tools/i18n/strings.tsv` → `extract.py --apply`（CI 硬门禁 `--min-coverage 100`） |
| 新 JNI 函数 | `JniSignatureTest.kt` + 重建 `.so` |
| 新版本 | [`VERSION-NOTES.md`](VERSION-NOTES.md) §二/§三 → 最后改 `version.properties` |
| 引擎边界变化（merge） | [`local-git-engine-design.md`](local-git-engine-design.md) §1/§5/§7 |
| 本文档 | [`../README.md`](../README.md) §五 索引行 |

---

## 10. 性能与验收

- 提交图属于**绘制型**风险：几何**必须预计算**（§4.2），绘制期只画本行线段；
  `LazyColumn` 带 `key = fullSha`；gutter 固定宽；
- 首帧：解析与布局走 `Dispatchers.IO`，先骨架后内容（`PlaceholderSwap`，不许 `Crossfade`）；
- 长任务（加深克隆 / 合并 / push）一律走 `TaskStore` + 进度弹窗（1.0.90 那套），不许出现第二种「转圈」；
- 验收：出 perfBeta → `tools/perf/frame-baseline.py compare` → **≥100ms 条数降、超 16ms 比例降、
  慢帧均值不回升**；结论回写 [`frame-perf-design.md`](frame-perf-design.md) §5 与版本条目。

---

## 11. 未决问题

1. 提交图所在的那一档面板算不算 `PageLevel.heavyFirstFrame`（判据：基线 ≥3 条慢帧且主段是
   「动画」或「绘制」）—— 用 perfBeta 基线判，别凭感觉标；
2. 「管理」页的粒度：只显示占用 + 删除，还是也给「收缩历史 / 清理孤儿对象」这类动作
   （libgit2 没有干净的 `gc` 接口，要做就得评估代价）；
3. 详情对比页里手工解决冲突用现成的 `:editor`（带冲突标记高亮）还是三栏合并器
   （后者是独立模块级工作量）；
4. 显式 stash 要不要做（隐式 stash 已明确不做）：不做的话「脏工作区切分支被拒」的出路只有「放弃改动」；
5. **导出仓库**（zip / 分享给桌面端）要不要做 —— 1.0.92 只把「复制路径到桌面端」的文案删掉了，
   **没有出路**与「有出路但要新建能力」是两件事。
