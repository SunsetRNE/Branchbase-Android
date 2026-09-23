<!-- 入库副本：正文与 design/workflow-redesign/README.md 一致，未做删改，仅加本段头。 -->
> **来源**：`design/workflow-redesign/README.md`（原型本体 `index.html` / `log.html` / `app.js` / `log.js` / `style.css` 在 `/design/workflow-redesign/` 下，按仓库约定**不入库**；DSH 侧边栏「原型预览」面板可直接打开）
> **为什么只把文档抽进来**：原型 HTML 是一次性草图，跟具体实现绑死、很快过期；
> 而这份文档里的**现状测绘、取舍论证与落地清单**是跨时间有效的设计结论，代码注释会引用它。
> **维护**：原型再改时请把这份副本一并更新，别让两边分叉；文中提到的文件名相对原始目录。

# 工作流详情页重设计原型 · 说明与落实方案

> **快照说明（2026-09 补）**：本文是**设计当时的记录** —— 文中的「现状测绘」、「`文件:行号`」、
> 「依据：`xxx.kt`（N 行）」都不会随代码演进更新（只有少数几处加了「2026-09 追记」）。
> 判断当前行为请以代码与 `docs/specs/` 下的规格为准；落地清单里未落地的项只表示「当时计划过」。

> 目录：`design/workflow-redesign/`（`/design/` 已在 `.gitignore` 中：原型草图不入库，但能被 DSH 原型预览面板扫到）
> 本文档的**入库副本**：`docs/specs/prototypes/workflow-redesign.md`（改完这里请同步那份）
> 目标页面：**工作流运行详情（Run 详情）** + **作业日志页**（原「Job 详情页」的演进）
> 依据：`app/src/main/java/com/branchbase/ui/repository/WorkflowRunDetailScreen.kt`（设计当时 676 行，逐行测绘；
> 2026-09 追记：该文件现为 **495 行**，文中的行号区间已整体漂移）

## 一、交付物与查看方式

| 文件 | 说明 |
| --- | --- |
| `index.html` | **Run 详情页**原型：三段式头部 + 进度 + 任务卡片流 + 按状态过滤 + 注解归属 + 产物下载 + 运行中轮询 |
| `log.html` | **日志页**原型：步骤切换 chips + 搜索命中跳转 + 仅错误/含警告过滤 + 分组折叠 + 跟随尾部 |
| `style.css` | 两页共用样式（设计令牌对齐 `ui/theme/Color.kt` 的 `Primer`，**明暗两套都在里面**） |
| `app.js` | Run 详情页全部交互（展开/折叠、过滤、刷新、更多菜单、运行中模拟、自动演示） |
| `log.js` | 日志页全部交互（步骤切换、搜索、过滤、折叠、跟随、运行中追加） |

查看方式（任选其一）：

1. **DSH Web 侧边栏「原型预览」面板** → 选 `design/workflow-redesign/index.html`（面板按「设备预设 → 手机 390×844」最贴近真机）；
2. 本地静态服务：`python3 -m http.server 8099 --directory design/workflow-redesign`，浏览器打开 `http://127.0.0.1:8099/index.html`（两页互相有跳转链接）。

原型工具条（**非产品 UI**）：`ⓘ 设计说明` / `▶ 自动演示`（Run 详情页）/ `◑ 切到「运行中」` / `◐ 深色` / 两页互跳。

---

## 二、现状测绘（改之前长什么样）

```
DetailTopBar            标题「CI · #211」+ 返回 —— 右侧 actions 槽是空的（287-312 行）
RunHeaderCard           状态点+文案 / displayTitle / 7 条等权 MetaLine（316-355 行）
「任务 Jobs」            纯文字标题，无计数（594-603 行 DetailSectionTitle）
  JobRow ×N             ● 名字 +「状态 · 耗时」+「完整日志」+ ▾（373-414 行）
    展开 → StepRow ×M    ● n. 名字 + 状态 + 耗时（416-450 行）
         → StepLogBlock  只显示一个步骤的日志段，200 行 / 320dp 封顶（454-522 行）
「产物 Artifacts」       名字 + 大小 + 已过期（无下载入口）
「注解 Annotations」     等级点 + 位置 + 标题 + 正文（沉在页底）
```

取数：`run` / `jobs` / `artifacts` 三路并行（各走 `PageCache`），随后按 `headSha` 查 check-runs 再拉最多 3 个 run 的注解（139-198 行）。

### 逐条问题（都指到行）

| # | 问题 | 位置 |
| --- | --- | --- |
| 1 | **点击目标嵌套**：整行是 `clickable { onToggle() }`，里面又嵌可点的「完整日志」 | 382 / 402-409 |
| 2 | **日志三种看法没有主次**：卡片内小窗（200 行封顶）、「完整日志」跳另一页、数据来源其实是同一个 | 454-522 / 214-276（`RepositoryWorkflowScreens.kt`） |
| 3 | **头部信息全平权**：7 条 `MetaLine` 同样字号同样颜色，事件/分支被埋在「编号」「触发人」之间 | 347-353 |
| 4 | **详情页零操作入口**：`retryTick` 只由**失败态**的重试触发，跑成功或跑一半时没有任何刷新入口；也没有浏览器打开 / 重新运行 | 213；287-312 |
| 5 | **运行中的 run 是「死」的**：不刷新、无进度、耗时不走、日志不跟随 | 全局 |
| 6 | **已有数据白解析**：`RunJob.runnerName` 全 app 零引用（模型 726/780 行解析、单测断言过）；`run.head_commit.message` 没解析 | `RepositoryModels.kt` |
| 7 | **注解与任务脱钩**：按 `headSha` 拉来的注解和「哪个任务报的」没有视觉关联，沉在页底 | 174-189 / 552-591 |
| 8 | **没有复用边界**：`DetailTopBar` / `DetailSectionTitle` / `DetailLoading` / `DetailErrorRetry` / `DetailEmptyText` 全是本文件 private（别的详情页各造一套） | 287-312 / 594-643 |

---

## 三、① 头部：三段式，第一屏答四个问题

### 新设计

| 段 | 内容 |
| --- | --- |
| 状态行 | 状态胶囊（带点，运行中脉冲）+「第 N 次尝试」+ 右侧耗时（运行中每秒走动） |
| 标题 | `displayTitle`（大字） |
| 提交行 | 头像 + 分支 chip + 短 sha（等宽）+ **commit message**（两行截断） |
| 次要行 | 触发人 · 事件 · 创建时间（11.5sp 更弱色） |
| 进度行 | `3 个任务 · 2 成功 1 失败` + 横向分段进度条（绿/红/橙/灰），**仅运行中或有失败时出现** |

第一屏要能回答：成功没 / 跑了多久 / 哪个提交 / 哪个任务挂了。

### 数据缺口

`head_commit.message` 在 `/actions/runs/{id}` 的响应里**本来就有**，是 Kotlin 侧没解析 ——
加两个字段即可，**不碰 JNI 签名、不用重编 `.so`**：

```kotlin
// RepositoryModels.kt
data class WorkflowRun(
    // …
    val headCommitMessage: String = "",
    val headCommitAuthor: String = "",
)

// parseWorkflowRun 里
headCommitMessage = o.optJSONObject("head_commit")?.optString("message").orEmpty().lineSequence().first(),
headCommitAuthor  = o.optJSONObject("head_commit")?.optJSONObject("author")?.optString("name").orEmpty(),
```

### 交互与状态

| 状态 | 表现 |
| --- | --- |
| 运行中 | 状态点脉冲；耗时每秒 +1（**从 `runStartedAt` 起算**，不是 `updatedAt`）；进度条随时反映 jobs |
| 已完成 | 耗时定格；进度行只在有失败时保留（全成功则收起，省一行） |
| 加载中 | 头部卡骨架屏（三条灰条） |

---

## 四、② 任务：卡片化 + 修掉嵌套点击

### 新设计

- **卡片头**只做一件事：展开 / 收起。状态点 + 任务名 + 状态胶囊 + 耗时 + **步骤进度 `6/6`** + 折叠箭头；
- **展开区**：步骤时间线 —— 状态点 + `n. 步骤名` + 耗时 + **相对时长条**（让「哪一步最慢」不用读数字）+ 行尾 `›`；
- **点步骤 = 进日志页**（`log.html?job=<id>&step=<n>`），卡片里不再嵌 200 行小窗；
- **卡片脚**：`runner: GitHub Actions 42`（已有字段，此前从未显示）+「日志」「下载日志」；
- **卡片内注解**：该任务相关的注解贴进卡片（黄条 `⚠ 2 条注解` 可展开）。

### 为什么这样切

现状把「展开卡片」和「看完整日志」两个动作挤在同一行，而两者的意图完全不同：
前者是**在列表里定位**，后者是**离开列表去读**。卡片头只留前者，后者统一收敛到日志页。

### 交互与状态

| 手势 / 状态 | 表现 |
| --- | --- |
| 点卡片头 | 展开 / 收起；同时只允许一个卡片展开？**不限制** —— 对比两个任务的步骤时不该被强制折叠 |
| 点步骤行 | 进日志页并落在该步骤；该行短暂高亮反馈 |
| 失败任务 | 卡片描边用 danger 淡色；「全部」视图下**排在最前** |
| 排队中任务 | 状态点为灰、耗时为「排队中」、runner 显示「待分配」 |

---

## 五、③ 过滤：任务多了才需要「按状态看」

- 标题带计数：`任务 · 3`；
- 右侧分段控件 `[全部 3] [失败 1]`，**仅当存在失败或运行中**时出现（成功的小运行不显示，避免平白多一个控件）；
- 过滤后仍按「失败优先」排序。

> 这一条是吸收网页版的信息架构，但只吸收**它真正解决的问题**（任务多时定位失败项），
> 不搬它的左右分栏 —— 390dp 放不下。

---

## 六、④ 操作入口：补三个

| 操作 | 落点 | 现状 |
| --- | --- | --- |
| 刷新 | 顶栏图标按钮，`force = true` 真回源，带旋转与骨架反馈 | 无（只有失败态的重试） |
| 在浏览器打开 | `run.htmlUrl`（已解析，详情页此前没用） | 无 |
| 重新运行 | 复用现成的 `WorkflowDispatchScreen`（它本来就是「填参数重跑」）；面板给「全部 / 只重跑失败的 / 带参数」三档 | 无 |
| 复制运行链接 / 下载全部日志 | 剪贴板 / `:downloader` | 无 |

---

## 七、⑤ 运行中：持续获取的对象是**状态**，不是日志

> **已落地（`1.0.29`）**：`ui/repository/RunPollPolicy.kt` + 运行详情页轮询 + 三个页面的回前台对齐。

### 先把 GitHub 的能力边界核准（都有出处）

| 事实 | 出处 |
| --- | --- |
| 逐 job 日志是**纯文本**（不是 zip）：`GET /actions/jobs/{job_id}/logs` 返回 302，`Location:` 是签名 URL、**1 分钟过期** | [REST 文档](https://docs.github.com/en/rest/actions/workflow-jobs?apiVersion=2022-11-28)（OpenAPI 原文：*"a redirect URL to download a plain text file of logs for a workflow job"*） |
| **运行中拿不到**：job 没结束前日志 blob 还不存在（404）；至少 job 开始后的头几秒一定是 404 | [community #154834「GitHub Actions API No Longer Returns Logs in Real-Time (Only After Job Completion)」](https://github.com/orgs/community/discussions/154834)、[#75518（运行中返回 404）](https://github.com/orgs/community/discussions/75518) |
| 网页版能实时滚动，走的是**未公开**的内部 websocket（`pipelines.actions.githubusercontent.com`） | [Hacksore/github-websocket-pipeline-api（逆向记录）](https://github.com/Hacksore/github-websocket-pipeline-api) |

由此推出两条硬约束：

1. **「长轮询」在 GitHub API 上不成立**。长轮询的前提是服务端把请求挂住、有变更才回；
   而日志接口是「立刻返回当前快照」，你挂住多久都不会等到新内容 —— 能用的只有**短轮询 + 退避**。
2. **「持续拉日志」本身就是错的抓手**：运行中拉不到，能拉到的只有它结束那一刻的定稿。

### 正确做法：轮询便宜的 JSON，按「步骤完成」事件抓一次日志

**持续获取的对象是 `/runs/{id}/jobs`**（几 KB 的 JSON，带每个 job / 每个 step 的状态与时间戳）；
日志只在「它成为定稿」的那一刻抓一次：

```
run.status == in_progress
  └─ 轮询 GET /runs/{id}/jobs              ← 便宜、可以频繁
       ├─ 某 step: in_progress → completed
       │     ⇒ 该步骤的日志段此刻定稿 ⇒ 抓一次 GET /jobs/{id}/logs（整份文本）
       │       交给 :joblogs（已有的单飞合并 + LRU + 按 ##[group] 分段）
       └─ job: → completed
             ⇒ 再抓一次拿最终整份；此后该 job 永不重抓
run.status == completed
  └─ 停轮询 + 抓 artifacts + 写回 PageCache
```

| 维度 | 设计 |
| --- | --- |
| 轮询间隔 | **自适应**：前 2 分钟 5s → 之后 15s → 5 分钟以上 30s（上限 60s）。固定 5s 跑 10 分钟 = 120 次请求，自适应约 40 次 |
| 失败退避 | 网络类失败指数退避 5→10→20→40s（封顶 60s）；4xx 直接停 |
| 生命周期门控 | 只在 `Lifecycle.STARTED` 时轮询，退到后台即停（`DisposableEffect` + 观察者，仓库里 `LoginFlow.kt:49-63` 是现成写法）；离开页面即取消，不额外保活 |
| 计费网络 | 复用现成的 `PrefetchPolicy.prefetchEnabled` / `networkMetered`（通知预取就是这么做的）：计费网络**停轮询**，只留手动刷新 |
| 去重 | `:joblogs` 的 `JobLogStore` 已保证同一 jobId 只发一次（单飞），页面来回切也不会重复下载 |
| **「还没生成」≠「失败」** | 取不到时若 `job.status ∈ {queued, in_progress}` ⇒ 显示「**任务运行中，日志将在该任务结束后可用**」，**不给「重试」按钮**；只有 job 已结束却取不到才算真失败 |

### 代价核算

| 方案 | 一次 10 分钟 run 的流量 |
| --- | --- |
| 每 5s 重抓整份日志（**要避免的**） | 几 MB × 120 ≈ **数百 MB** |
| 本方案：轮询 jobs（自适应） | 几 KB × ~40 ≈ **不到 1 MB** |
| 本方案：抓日志 | 完成的步骤涉及的 job 数（通常 1~5）次 × 几十 KB ~ 几 MB |

### 「跑完通知我」是另一件事，而且不需要轮询日志

那时轮询对象是 **run 状态**（一次几十字节），后台用 `WorkManager`（最小间隔 15 分钟，够用）
或前台服务。不过本工程已经在收 GitHub 通知（服务端 webhook 推的），跑完本来就会收到
`WorkflowRun` 通知 —— **先确认通知够不够用，不够再加后台轮询**，别一上来就常驻进程。

> **仍然刻意不做**：WebSocket / SSE。实时流是未公开的内部接口，依赖它等于把「跑完能看日志」
> 押在一个随时会变的私有协议上；而它换来的只是「运行中也能看当前步骤的输出」——
> 一个官方 API 明确不提供的能力。

> ⚠️ **本轮修正**：本节此前写的「日志页此时『跟随尾部』默认开」是**错的** ——
> 运行中根本没有日志可跟。正确形态是：已完成的步骤可以看（定稿）；正在跑的步骤显示
> 「运行中，结束后可用」，并在该步骤完成的瞬间自动把日志补上（这就是「跟随」的真正含义：
> **跟随状态事件**，不是跟随日志尾部）。

---

## 八、⑥ 注解与产物

- **注解归属**：按 `annotation.path` 的前缀 / `job.name` 与任务名匹配，能归属的贴进任务卡片；
  归属不了的留在底部「其他注解」聚合区。现状是全部沉底，与任务脱钩；
- 归属规则做成纯函数 `jobBelongsToAnnotation(annotation, job)`，可 JVM 单测；
- **产物**：行尾加「下载」按钮，走 `:downloader`（前台服务 + 通知进度都是现成的）；
  已过期产物按钮置灰 + 保留「已过期」标签。

---

## 九、⑦ 日志页：三种看法收敛成一种

见 `log.html` 与其中的设计说明抽屉。要点：

- **删掉** `StepLogBlock`（454-522 行）那个 200 行 / 320dp 封顶的小窗；
- **`JobDetailContent` 升级成日志页**：步骤切换 chips / 搜索命中跳转 / 仅错误·含警告过滤 /
  分组折叠 / 跟随尾部 / 复制·分享；
- 渲染从「整段拼成一个 `Text`」改成 `LazyColumn` 逐行 ——
  现在几 MB 的日志整块测量，滚动会卡；
- 取数继续走上一轮做好的 `:joblogs`（单飞合并 + 缓存 + 分段），**日志页只是换个宿主**。

---

## 十、令牌映射（原型 CSS 变量 → `ui/theme/Color.kt`）

原型不引入任何新色值；明暗两套都对齐 `ThemePalette.kt` 的 `LightPrimerPalette` / `DarkPrimerPalette`。

| CSS 变量 | Primer 角色 | 浅色 | 深色 |
| --- | --- | --- | --- |
| `--canvas` | `BackgroundPrimary` | `#FFFFFF` | `#0D1117` |
| `--subtle` / `--gray-100` | `Gray100` | `#F7F7F9` | `#161B22` |
| `--gray-150` | `Gray150` | `#EFF0F5` | `#21262D` |
| `--border` | `Gray200`（**内部分隔线**） | `#E3E4E8` | `#30363D` |
| `--border-strong` | `Primer.Border`（**卡片/容器描边**） | `#BFC1C9` | `#30363D` |
| `--fg` / `--fg-muted` / `--fg-subtle` | `TextPrimary` / `TextSecondary` / `TextTertiary` | `#050505` / `#41434E` / `#6A6D7C` | `#E6EDF3` / `#C9D1D9` / `#8B949E` |
| `--accent` / `--accent-text` / `--link` | `Blue500` / `AccentText` / `Link` | `#0969DA` / `#0550AE` / `#0969DA` | `#1F6FEB` / `#79C0FF` / `#2F81F7` |
| `--success` / `--success-text` / `--success-surface` | `Green500` / `SuccessTextStrong` / `SuccessSurface` | `#28A745` / `#176F2C` / `#F0FFF4` | `#3FB950` / `#3FB950` / `#12261E` |
| `--danger` / `--danger-surface` / `--danger-subtle` | `Red500` / `DangerSurface` / `Red100` | `#D73A49` / `#FFEBEC` / `#FFDCE0` | `#F85149` / `#2D1418` / `#2D1418` |
| `--danger-text` | **色板缺此角色**（见第十四节） | `#9E1C24` | `#F85149` |
| `--warning` / `--warning-text` / `--warning-surface` | `Orange500` / `WarningTextStrong` / `WarningSurface` | `#F66A0A` / `#7D4C00` / `#FFF1E0` | `#D29922` / `#D29922` / `#2A2113` |
| `--info-surface-soft` | `InfoSurfaceSoft` | `#E6F1FF` | `#10283F` |
| `--code-bg` | `CodeSyntax.CodeBg` | `#F6F8FA` | `#161B22` |
| `--log-hit-bg` | `CodeSyntax.MatchBg` | `#FFF8C5` | `#3B2300` |
| `--log-warn-bg` | `WarningSurface` | `#FFF1E0` | `#2A2113` |
| `--log-err-bg` | `Red100`（`dangerSubtle`） | `#FFDCE0` | `#2D1418` |

> 深色取值这一列正好可以复核刚做完的主题收敛：原型里没有任何一个色值是自己拍的，
> 出现「深色下读不出来」就说明角色挑错了。

### 深色是怎么接上的（`ThemeMode` 三档）

接入主题**不需要任何新机制**，链路是现成的：

```
ThemeMode（跟随系统 / 浅色 / 深色）
   → BranchbaseTheme(mode) 把 LocalPrimerPalette 换成 LightPrimerPalette 或 DarkPrimerPalette
      → Primer.XXX 的 @Composable get() 现读现取
         → 本页所有组件（只要不写 Color(0x…)）自动跟随
```

所以对这次重绘来说，**深色是「免费」的**，前提只有一条：新组件一律走 `Primer` 角色。
原型里的 `[data-theme="dark"]` 块就是 `DarkPrimerPalette` 的等价物（第 47 个变量逐一核对通过），
它存在只是为了在浏览器里能直观看到深色，而不是为了「实现深色」。

两个坑值得单说：

1. **`LocalIsDarkTheme` 只在非 Composable 场景才需要**（`:editor` 拿不到 CompositionLocal，
   所以要把 `darkTheme` 当参数传进去）。本页组件全在 Composable 里，用不上；
2. **别用 `isSystemInDarkTheme()`**：用户可以把应用锁成浅色而系统是深色。
   三档主题的唯一真源是 `ThemeMode`。

### 对比度审计（`theme-audit.py`，不需要浏览器）

深色没法用肉眼在容器里看，所以把它变成数字：

```bash
python3 design/workflow-redesign/theme-audit.py design/workflow-redesign
```

三段输出：

- **A. 令牌对齐**：原型每个 `--var` 的浅/深取值是否逐位等于对应 `Primer` 角色（当前 **48/48 通过**）；
- **B. 对比度**：28 个真实出现的前景/背景组合，按 WCAG 算（正文 4.5、非文本 UI 3.0），浅深各一列；
- **C. 深色风险扫描**：不随主题翻转的纯白/纯黑，逐条判是不是「彩色填充上的文字」（合法）。

审计当场修掉了原型自己的 3 个问题：失败胶囊误用**填充色**当文字（4.00 → 6.94）、
错误/警告行底色用的是两个角色的**混合值**（自造色）、`--border-strong` 深色填错一档。

> 剩下 1 项未达 AA：**主按钮白字压在深色 `accent`(#1F6FEB) 上 = 4.08**。
> 这是**全站既有特性**（app 里 `background(Primer.Blue500)` + `Color.White` 到处都是，
> GitHub 网页版深色主按钮同样如此），不是新设计引入的；
> 若要严格达标，可改用色板里已有但**目前零调用**的高强调组合 `Primer.Gray900` + `Primer.OnEmphasis`
> （浅色深底白字 / 深色浅底深字，两端都在 16 以上）。

---

## 十一、现有实现代码索引（改造时按图索骥）

| 文件 | 行 | 职责 / 改法 |
| --- | --- | --- |
| `ui/repository/WorkflowRunDetailScreen.kt` | 全文件 | **重写**：按新结构拆成头部 / 任务卡片 / 操作 |
| 同上 | 287-312 `DetailTopBar` | 搬到共享 `DetailScaffold.kt`，补 `actions` 槽 |
| 同上 | 316-355 `RunHeaderCard` | 重写为三段式 |
| 同上 | 373-414 `JobRow` | 换成 `JobCard`（头 / 步骤时间线 / 脚 / 注解） |
| 同上 | 416-450 `StepRow` | 保留（补相对时长条与 `›`），点击改为跳日志页 |
| 同上 | 454-522 `StepLogBlock` | **删除** |
| 同上 | 594-643 四个小件 | 搬到共享 `DetailScaffold.kt` |
| `ui/repository/RepositoryWorkflowScreens.kt` | 214-276 `JobDetailContent` | 演进为日志页 `JobLogScreen.kt` |
| `ui/repository/RepositoryScreen.kt` | 463-474 `RepoRoute.JobDetail` | 换落点，路由补 `stepNumber` |
| `ui/repository/RepositoryModels.kt` | 699-717 `WorkflowRun` | 补 `headCommitMessage` / `headCommitAuthor` |
| `ui/repository/WorkflowModels.kt` | — | 新增 `runProgress(jobs)`、`jobBelongsToAnnotation(...)`、`logHitLines(...)` 三个纯函数（可单测） |
| `cache/PageCache.kt` | 45-49 | 键不变（`runKey` / `runJobsKey` / `runArtifactsKey` / `jobLogKey`） |
| `joblogs/` | 全模块 | 不动；日志页继续用它取数与分段 |

### 新增文件（建议）

```
ui/repository/workflow/
├── WorkflowRunDetailScreen.kt   # 页面骨架（由现有文件演进）
├── RunHeaderCard.kt             # 三段式头部 + 进度条
├── JobCard.kt                   # 任务卡片
├── JobLogScreen.kt              # 日志页
├── LogView.kt                   # 日志渲染（分组 / 高亮 / 命中 / 行号，LazyColumn）
└── WorkflowRunActions.kt        # 刷新 / 浏览器 / 重跑 / 复制 / 下载
ui/repository/DetailScaffold.kt  # 共享的顶栏与四态小组件
```

---

## 十二、不新增的东西（避免过度设计）

- **不新增网络请求数**：轮询只在 `in_progress` 时发生，且结束后停止；
  正常打开一次仍是 3 路并行 + 0~3 路注解；
- **不碰 JNI 签名**：`head_commit` 只是 Kotlin 侧多解两个字段，`core/` 一行不改、不重编 `.so`；
- **不引实时推送**（见第七节）；
- **不搬网页版的左右分栏**：390dp 放不下，只吸收「按状态过滤」与「注解聚合」。

---

## 十三、验收清单

| # | 检查项 |
| --- | --- |
| 1 | 任务卡片头只做展开/收起，点击区不再嵌套（误点「完整日志」的情况消失） |
| 2 | 点任一步骤直接进日志页，且落在该步骤的日志段 |
| 3 | 头部第一屏能读到：成功没 / 多久 / 哪个提交（含 message）/ 哪个任务挂了 |
| 4 | 存在失败任务时出现分段控件且「失败」有计数；全成功的小运行不出现 |
| 5 | 顶栏有刷新；跑成功状态也能手动刷新 |
| 6 | 运行中：状态点脉冲、耗时走动、进度条推进、日志页跟随尾部 |
| 7 | 注解出现在对应任务卡片内；无法归属的落到聚合区 |
| 8 | 产物行尾可直接下载（走 `:downloader`），过期产物按钮置灰 |
| 9 | 日志页搜索命中计数正确、上/下跳可循环；仅错误过滤后**行号保持原编号** |
| 10 | 手动上滑自动暂停跟随，点「最新」恢复 |
| 11 | 深色下每个新组件都成立（原型点「◐ 深色」逐屏看） |
| 12 | 长日志（>1MB）滚动不卡（`LazyColumn` 而非整段 `Text`） |

---

## 十四、审计连带查出的 app 侧问题（已修：`1.0.28`）

> 本节记的是**审计发现**，修法已在同一轮的 `1.0.28` 落地：色板补 `dangerText` + 5 处换角色 +
> `ThemeContrastTest`。原型里的 `--danger-text` 现在有对应角色了，令牌对齐由 47/47 变成 **48/48**。

对比度回查把「上一轮主题收敛」的每一处改动按 **改前字面量 → 改后角色** 都算了一遍，
**查出 5 处对比度回退，其中 4 处低于 WCAG AA**：

| 场景 | 改前 | 改后 | 改动 |
| --- | --- | --- | --- |
| 账户状态·异常 | 6.95 | **4.00** ✗ | 文字 `#9E1C24` → `Primer.Red500` |
| 安全警报标题 | 5.10 | **3.61** ✗ | 文字 `#B91C1C` → `Primer.Red500`（底色同时换成更浅的 `Red100`） |
| `StatusChip D` | 4.68 | **4.00** ✗ | 文字 `#CF222E` → `Primer.Red500` |
| 发布「草稿」 | 4.59 | **4.38** ✗ | `WarningText` 压在换成 `WarningSurface` 的底上 |
| `StatusChip A`（深色） | — | **4.02** ✗ | 选了 `Blue600`，深色 `#388BFD` 压 `#12304F` 偏亮 |

再加两处**历史遗留**（收敛只是把它们原本透明的底变成可见，问题本身更早就在）：

| 场景 | 改前 | 改后 | 说明 |
| --- | --- | --- | --- |
| 决策「推荐」chip | 3.13 | **2.88** ✗ | `Green500` 当文字用；底色原本 alpha=0「看不见」，现在看见了 |
| 决策「危险」chip | 4.00 | 4.00 ✗ | `Red500` 当文字用 |

**根因是同一个，而且是色板的结构性缺口**：色板里有 `successText` / `successTextStrong` /
`accentText` / `warningText` / `warningTextStrong`，**唯独没有 `dangerText`**。
于是「红色的**文字**」只能退而用 `danger` —— 那是**填充色**，天生比文字色浅。
上一轮收敛时我按「尽量不新增角色」的原则复用了 `Red500`，结果就是拿填充色当文字。

### 建议的修法（小、有据）

1. **色板补一个角色** `dangerText`：浅 `#9E1C24` / 深 `#F85149`。
   浅色取值就是 `AccountsScreen` 原来那个 —— **等于把原观感原样拿回来**；
   深色与 `danger` 同值，与 `successText` / `warningText` 在深色下「塌回填充色」的既有约定一致。
   候选是算出来的，不是拍的（要求在 `DangerSurface` / `Red100` / `Canvas` / `DangerSurfaceSoft`
   四处底色上都 ≥ 4.5）：

   | 候选 | 最差底色上的对比度 | |
   | --- | --- | --- |
   | `#D73A49`（现用 `Red500`） | 3.61 | ✗ 淘汰 |
   | `#CF222E` | 4.22 | ✗ 淘汰 |
   | **`#9E1C24`** | **6.26** | ✓ 采用 |
   | `#B62324` | 5.09 | ✓ |
   | `#82071E` | 8.29 | ✓ |

2. 4 处「`Red500` 当文字」→ `Primer.DangerText`；
3. `StatusChip A` 的 `Blue600` → `AccentText`（浅 5.45 / 深 6.60，两端都过）；
4. 决策 chip：`推荐` 文字 → `SuccessTextStrong`，`危险` → `DangerText`；
5. 草稿 / 已星标这类 `WarningText` 当文字 → `WarningTextStrong`（4.38 → 5.6）。

### 顺带给钉子补一条

现在的 `ThemeConvergenceTest` 是**黑名单**（只拦那 29 个已知的浅色取值 + 6 位十六进制），
一个全新的写死色值是拦不住的。建议再加一条语义规则：

> **文字位置不得使用填充色角色** —— `color = Primer.Red500 / Green500 / Orange500`
> 且底色是 `*Surface` 时报红。

（`Primer.Red500` 当填充、图标、状态点是**对的**；当文字才是问题。这条规则能自动拦住上面第 2、4 条。）

---

## 十五、原型自身的三个坑（已修，留档）

1. **`var` 提升**：`app.js` 里 `run()` 引用的 `RUN_RUNNING` 在其后声明 ——
   靠 `var` 提升 + 「只在赋值后调用」才成立；真机上没有这个问题（Kotlin 编译期就拦），
   但改原型时别把 `render()` 提前到声明之前。
2. **滚动跟随与重渲染互相打架**：日志页每次重渲染会重置 `scrollTop`。
   处理办法是重渲染后**主动恢复**原 `scrollTop`（`renderLog(keepScroll)`），
   只在「跟随尾部」开着时才滚到底；并用 `programmaticScroll` 标记区分
   「程序滚动」与「用户上滑」，否则程序滚动自己会把跟随关掉。
3. **命中锚点与分组头不同步**（烟测抓到的真 bug）：搜索命中集是按「过滤后的全部行」算的，
   里面**包含分组头**；但分组头一开始没有 `lr-<idx>` 锚点，于是「下一条」跳到分组头时会落空
   （计数照涨、画面不动，最容易被当成「搜索坏了」）。
   修法是给分组头也带上 `lr-<idx>` 并同样做 `<mark>` 高亮。

## 十六、原型烟测（可选，但很值）

`smoke.js` 用最小 DOM 桩在 Node 里真跑一遍两页的渲染与关键交互，不依赖浏览器：

```bash
node design/workflow-redesign/smoke.js design/workflow-redesign    # 在仓库根目录
# 或在原型目录内：node smoke.js .
```

覆盖 26 项：头部三段与提交信息渲染、失败任务排最前、卡片展开、按状态过滤、
运行中切换、日志行高亮 / 分组 / 时间戳剥离 / 搜索命中计数与上跳 / 仅错误过滤后保留原行号。

> 它拦的就是「点了没反应」这一类和「语法过了但跑不通」这一层 ——
> 上面第三个坑正是它抓出来的（`node --check` 完全看不出来）。
