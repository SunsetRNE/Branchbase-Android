<!-- 来源：根 `version.properties` 的注释块（2026-09 收敛）。注释正文逐字保留，只做了三件事：
     去掉行首的 `#` 注释前缀与对齐缩进、每版提成 `### <版本号>`、按版本从新到旧重排
     （原文件是追加写，1.0.40–1.0.44 排在 1.0.22 之后；1.0.45 起按新流程直接加在顶部）。 -->
# 版本变更记录（`versionName` / `versionCode` 逐版说明）

`version.properties` 现在只留格式契约 + 写法样板（3 个经典示例）；
**每一版改了什么、为什么这么改**都在这份文档里 —— §二 `versionName` 条目（1.0.22 → **1.0.74**）
与 §三 `versionCode` 流水（129 → **176**）。

---

## 一、新增一版时怎么做

1. **先在本文档加条目**：在 §二 顶部加 `### <新版本号>` 与正文（写法见下）；
2. **再记版本码**：在 §三 顶部加一行 —— `versionCode` **有多少次提交变更多少次 +1**，一次发布也算一次提交；
3. **最后改 `version.properties`**：只改 `versionName=` / `versionCode=` 两个值。
   那个文件**不再堆变更记录**（只留 3 个写法样板），记录一律进本文档。

### 写法约定（从 1.0.22–1.0.44 那批条目里长出来的）

- 标题一句「改了什么」；正文写**为什么** —— 原先哪里不成立、代价是什么，以及这条结论的边界；
- 一版里有多件事就分 ①②③，别混成一段；
- 能量出数字（帧耗时 / dp / 对比度 / 条数）、点到端点（`GET /actions/runs/{id}/jobs`）的，别只写「优化了」；
- 新增的源码级钉子（`XxxTest`）要写出来 —— 它是这条结论的执法者。

---

## 二、`versionName` 流水（1.0.74 → 1.0.22）

### 1.0.74

**「刚发布的版本在 App 里看不到附件」—— 补齐第二条取包通道**（发布页那条断点 + CI 产物那条断点）。

① **现场**。v1.0.73 的三个 beta release，在 App 的发布页上全都没有附件，而**附件一直是好的**。
同一个 release 三条取值路径给出两种答案：

| 路径 | 结果 |
|------|------|
| `GET /repos/{o}/{r}/releases`（列表）| `assets: []` ← **App 只读这个** |
| `GET /repos/{o}/{r}/releases/{id}`（单体）| 3 个 |
| `releases/expanded_assets/{tag}`（网页）| 3 行，带 sha256 |

窗口约 **1.5~2.6 小时**（发布后 1h25m 仍为空、2h38m 已恢复），之后自愈。跨仓库对照佐证不是
GitHub 全局故障：`neovim/neovim` 3.7 小时前的 release（13 个附件）两条路径一致。
网页端也露了同一个馅 —— 发布页标题旁的「Assets **N**」计数显示成 `0 + 2 个自动源码包`，
而**同一页的附件列表本身是完整的**（列表由另一个端点渲染）。这也说明源头在 GitHub 那份
`assets` 元数据，与客户端解析无关。

**代价**：`刚发完版想立刻装` 是最常见的动作，而它恰好落在窗口里。

② **发布页那条断点**。App 读列表接口，那就在它报空时补一次单体接口：
`parseSingleRelease`（单体返回对象，套层方括号复用 `parseReleases`，同 `parseWorkflowRun` 手法）
+ `releasesNeedingAssetBackfill`（只挑 `assets` 为空的、最多 3 条、`id == 0` 跳过）。
正常仓库**零额外请求**。钉子 `ReleaseAssetBackfillTest`(6)。

③ **CI 产物那条断点**。产物是发布页之外的第二条取包通道，但它原来**走到一半**：

```
发布附件  →  下载 .apk  →  有「安装」
CI 产物   →  下载 .zip  →  结束（`app`/`downloader`/`core` 里 ZipFile 零命中，手机上解不了）
```

Actions 的产物**下载时永远是 zip**，去不掉这层壳。既然去不掉，就把壳做成确定性的：

- **工作流**：产物由「一个 58MB 混装包」拆成**一个 APK 一个 artifact**
  （`Branchbase-<sv>-debug` / `-perfBeta` / `-core-so`，正式版为 `Branchbase-<sv>`），
  保留期 7 → **30 天**，`if-no-files-found: error`（兜底通道不能静默产出空产物）。
  publish 侧改 `pattern: Branchbase-*` + **`merge-multiple: true`** —— 不加会把文件解到
  以 artifact 命名的子目录里，发布步骤与 `cp libbranchbase_core.so` 会一起找不到文件。
- **App**：新增 `ArtifactInstall.kt` —— `pickSingleApkEntry` / `extractSingleApk` /
  `installWorkflowArtifact`；产物行接上与发布附件同一套状态机（下载 → 取消 / 重试 / **安装**），
  失败原因写进行内。`installDownloadedApk` 由 `private` 改 `internal`，
  **「安装未知应用」的授权引导只有一份**。

判断力全在「挑哪个」：**只在恰好一个 APK 时才动手**，两个以上**不猜** —— debug 与 perfBeta
是两个不同签名的变体，装错要卸载重来。zip-slip 靠「输出路径只取条目名最后一段」天然不成立
（不是过滤 `..`，是不采纳）。钉子 `ArtifactInstallTest`(11)：挑选规则 / 混装拒绝 /
带 `../../` 的条目 / 非 zip 不抛。

④ **顺带**：`GET /releases` 对刚发布的 release 返回空 `assets` 这件事**没有自动化守卫** ——
HTML 端点的对照逻辑进了本地排障脚本（`.local-gh/check-releases.py`，不入库），
发布后想核对可以跑一次。

⑤ **测试**。`:app` 编译通过，`com.branchbase.ui.repository.*` 全绿；本轮新增 17 例
（`ReleaseAssetBackfillTest` 6 + `ArtifactInstallTest` 11）。

---

### 1.0.73

**首页 / 个人页三 Tab / 设置页的描边改为纯黑（仅浅色）** —— 新增 `Primer.BorderEmphasis` 角色。

① **为什么要动**。`border` 是**刻意做弱**的装饰描边（浅 `#BFC1C9` **1.80:1** / 深 `#30363D` **1.55:1**，
见 `ui-design.md` 的「图标去灰」节），但首页、个人页三 Tab（概览 · 仓库 · 动态）与设置页的卡片框、
分隔线承担的其实是**卡片边界**这层结构语义 —— 1.80:1 压在纯白卡片底上几乎看不出边界在哪。

② **为什么不直接改 `border`**。它另外还在 **24 个文件**里被引用 **55 处**，并经由
`Theme.kt:145` 的 `outline = p.border` 注入 Material 主题 —— 改它会外溢到全项目与所有
消费 `colorScheme.outline` 的 Material 组件。所以**新增角色**而不是改旧值：
`Primer.BorderEmphasis`（浅 `#000000` / 深 `#30363D`），承接范围明确：
- 首页 `HomeScreen.kt` **4 处**：待办卡外框、进行中任务卡外框、两条行分隔线；
- 个人页 `ProfileScreen.kt` **11 处**：编辑资料按钮、仓库搜索框、语言筛选 chip、
  三张统计卡与其骨架、热力图容器、气泡导航栏（未展开态 + 弹层）、`RepoCard` / `RepoCardSkeleton`；
- 设置页 `SettingsRow.kt` **2 处**：卡片描边与行分隔线（原来是 `Primer.Gray200`）。
合计 **17 处**引用替换。

③ **设置页的可交互控件边界一并拉黑**。`Primer.BorderControl` 浅色由 `#8B8E99`（3.27:1，刚好过
1.4.11）改为 `#000000`（**21:1**）。注意它是共享令牌：`RepoRelationSheets.kt:250`
（仓库关系弹层的复选框）也跟着变了 —— 这是「改令牌」而非「改引用点」的必然外溢，
不是漏改也不是多改。

④ **深色板逐位未变，这是刻意的**。纯黑压在 `#0D1117` / `#161B22` 上只有约 **1.1:1**，
边界会直接消失、等于没画。所以深色下 `BorderEmphasis` 与 `border` 同值（`#30363D`），
`BorderControl` 保持 `#6E7681`（3.77 / 4.12:1，仍满足 WCAG 1.4.11）。
一句话：**「拉黑」只发生在浅色**。

⑤ **一处观感跳变远大于描边，真机走查优先看它**。首页那两条分隔线是
`background(…copy(alpha = 0.4f))` 的**填充**而非 stroke：`#BFC1C9`@40% 在白底上混出约
`#E5E6E9`（几乎看不见），换 `#000000`@40% 后是 `#666666` —— 从隐形变成实打实一条线。

⑥ **文档同步**（这类改动的惯例是「文档追代码」，本版把三处追平）：
`settings-design.md` §九令牌表新增 `BorderEmphasis` 与 `BorderControl` 两行、§十四末的
「新增的令牌」小节改为两个角色并写明浅色调整经过、§十三补 v5 条目；
`ui-design.md` 「图标去灰」节补一段边界说明 —— 原文「拉到纯黑会让浅色退回 wireframe」
约束的是 `border` **本身**，不是「一切描边」，别把 `BorderEmphasis` 的 21:1 套回去；
`specs/prototypes/settings-redesign.md` 加「落地后的偏离」说明（原型 CSS **不回改**：
它记录的是原型当时的设计，不是 App 现状）。

⑦ **测试**。`:app` 编译通过；`ThemeContrastTest`(5) / `ThemeConvergenceTest`(2) /
`SettingsSpecTest`(24) / `ThemeModeTest`(4) 全绿。**本版没有新增钉子**，原因写下来备查：
`SettingsSpecTest` 的「设置树里没有写死色值」已经覆盖本条（改的是**角色引用**，不是字面量）；
`ThemeContrastTest` 只钉「文字角色必须比对应填充色深」，**不校验描边取值** ——
也就是说「描边该多黑」目前**没有**自动化守卫，只有这份文档与规范 §九的令牌表。

---

### 1.0.72

**文档重组收尾 + 三个「说了没做」的功能兑现 + 私有仓库凭据** —— 这一版没有新界面，改的都是
「用户已经能点到、但点到之后不成立」的地方。

① **文档先按代码纠偏，再谈重组**。逐份对照源码复核 `docs/specs/` 全部 17 份文档，改掉 **35 处**
与代码不符的断言（`SettingsSpecTest` 18→24、`JniSignatureTest` 83→88、`frame-perf` 同一基线窗
两套数字、`ui-design` 的「12% 胶囊」实际 18%、「`Gray900` 零调用」实际多处调用…），
修掉 **11 处悬空引用**（3 份从未进过版本库的文档 + 8 个不存在的 `/design/*-prototype.html`）
与 6 处幽灵代码路径。根 `README.md` 从 **417 行瘦到 110 行**（只留门面 / 下载与安装 / 快速上手 /
一行制功能一览 / 技术栈与结构 / 构建 / 文档入口），迁出的正文进了三份新规格：
`features-design.md`（用户可见行为）、`decision-pages-design.md`（14 页决策体系）、
`local-git-engine-design.md`（libgit2 稳定接口与 `nff:` 归一）；`docs/README.md` 成为**唯一**文档索引
（历史上「根 README 与 docs/README 各列一份」已经导致 `morph-design.md` 两边同时漏掉）。

② **决策页/仓库页的「演示数据」全部换成真值**。PR 一条龙的第二步此前是空转（宿主传
`changedFiles = emptyList()`，于是「建分支 → 开 PR」产生的是**与 base 同 sha、diff 为空**的假 PR）——
现在第②步真的调 `commitFiles`（内容草稿优先、读不全整体不提交），空清单**拦住并给引导**；
`PrMergeScreen` 从「零调用点」接进 PR 详情页（`open && !merged` 才露出，`mergeable` 三态分别处理）；
「已合并」不再写死 `setOf("patch-1")`，改按需 `compareBranches`；「挽留 stats」与仓库统计取真值、
取不到就不显示那一行；`DraftInfo.remoteChanged` 接上**已有的**草稿基准 sha（此前恒为 false，
多端编辑提醒从来没亮过）；仓库设置的反馈冒泡到页面。文案层面把「点了必然失败」的两处改成**预先禁用 + 写明原因**
（`has_parent` / `has_remote_ref` 由 `repo_status` 新增下发），「仅本次推送」这个与引擎行为不符的假选择删掉，
「revert 失败（引擎不可用）」这类**编造归因**全部换成中性说法。

③ **私有仓库认证失败有了完整出路**。GitHub 对无权限的私有仓库返回 **404**（与「不存在」同码），
所以先补上唯一能区分的信号：`GET /user` 的 **`x-oauth-scopes`** 响应头（`ApiClient::oauth_scopes`，
探测失败/拿不到一律收敛成 UNKNOWN，绝不误判成「没有权限」）。失败卡据此给出解释 + 三条出路
（用访问令牌打开 / 建一个带 `repo` 的令牌 / 浏览器打开），并新增**仓库级凭据**：
账号优先、账号打不开（404/403）才回退、**回退后读写都用它** —— 因此使用中页面顶部有身份横幅
（写操作会以另一身份执行）+「改用账号」退路；凭据存在独立 prefs 文件并**排除云备份与设备迁移**，
管理入口在 设置 → 仓库凭据，**只在令牌登录模式（PAT）下出现**（规范 `settings-design.md` §3.2.1）。

④ **别人发来的日志包现在自带索引**。导出 zip 从「一份 `branchbase.log`」变成**两份文件**：
`report.md`（版本 / 条数（含 ERROR/WARN）/ 时间范围 / 类别分布 / 设备档案摘要 / **锚点词典**）
+ 原始日志；索引的数字都从同一批日志现算，不可能与原始日志矛盾。配套给关键路径钉了**锚点**
（`LOG_ANCHORS`：`PR一条龙` / `PR合并` / `敏感扫描` / `决策页` / `私有仓库` / `草稿`），
没有真机走查时，「用户说某个操作不对」直接 grep 一个词就能看到整条链路。
另外：提交前敏感扫描**不可用时改为拦下并说明**（此前把 null 折叠成「没命中」直接放行 ——
等于警告在最需要它的时候正好不存在，而用户以为扫过了）。

⑤ **钉子**：新增 `DecisionModelsTest`(8) / `SyncDecisionPrecheckTest`(15) / `CollabDecisionRulesTest`(19) /
`PullDetailModelsTest`(9) / `RepoCredentialStoreTest`(11) / `LogAnchorsTest`(2) / `OAuthDeepLinkTest`(2) /
`DownloadFileProviderAuthorityTest`(2) / `RepoAccessHintTest`(9)；两条此前**既无钉子也无文档**的契约
（OAuth 深链、下载模块 FileProvider authority）补上了源码级钉子；Rust 侧 `repo_status` 补 3 个字段
（`has_parent` / `head_sha` / `has_remote_ref`）与用例。顺带修掉一个**必然 flaky** 的断言：
`MorphPairTest` 拿两次缓存命中的耗时互比（实测失败率 **34.9%**，因为台账是进程级单例、该用例
在类里最后一个跑），改成对**新建 pair** 量真首次（1.34ms vs 0.29µs）。

---

### 1.0.71

**日志页闪退（用户反馈「加载太多日志会闪退」）** —— `LazyColumn` 的 key 撞车，实锤在设备的 crash buffer 里。

① **根因**：日志页两个档位（时间流 / 原始日志）的 item key 都是
`it.time.toString() + it.message`，而**同一毫秒落两条同文案的日志是常态** ——
缓存那几行成串地打，`L1 直出（含过期）repo-info:…` 一次进入仓库页就会打两遍、经常落在同一毫秒。
`LazyColumn` 的 key 必须唯一，撞了就直接抛：

```
java.lang.IllegalArgumentException: Key "1790084432978L1 直出（含过期）repo-info:SunsetRNE/Branchbase-Android"
  was already used. If you are using LazyColumn/Row please make sure you provide a unique key for each item.
  at androidx.compose.ui.layout.LayoutNodeSubcompositionsState.subcompose(SubcomposeLayout.kt:1591)
  at androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScopeImpl.compose(LazyLayoutMeasureScope.kt:94)
```

崩溃发生在**滚到那一项、它被测量组合的那一刻**（外层栈是 fling / overscroll），不在进页面时 ——
所以表现是「日志越攒越多，翻着翻着就闪退」。同一份 crash buffer 里 **09-22 21:45:20**
还有一次一模一样的（那时是 1.0.64），这个 key 从 1.0.42（`1e69c93`，日志页惰性化那一版）就埋下了。

② **修法**：`LogEntry` 加一个**进程内单调递增的序号** `seq`（`LogManager.log` 用 `AtomicLong` 发号），
列表 key 改用它（`items(filtered, key = ::logItemKey)`，两个档位都换）。
时间戳不再参与 key —— 它不唯一，用它就是把这个崩溃请回来。
序号只服务列表身份，不进日志文件、不影响导出格式。

③ **顺带把全仓的列表 key 过了一遍**：其余 20 处都是 id / sha / number / login / name 这类天然唯一值
（`NotificationScreen` 用 `it.id`、`RepositoryListScreens` 用 `number`/`sha`、贡献者用 `login`…），
没有第二处拿时间戳或文案拼 key 的。

**钉子**：`LogListKeyTest` 3 条。第一条是**确定性**的对抗输入 —— 按字面造出「同毫秒 + 同文案、
只有 seq 不同」的两条，钉住 key 必须不同（不依赖「两次取时间戳恰好同毫秒」，那会偶发红）；
另两条钉「序号单调递增、列表最新在前也能当 key」与「key 就是序号本身」。
**把 key 换回旧写法跑一遍，这两条确实变红**（改动前后都验过）。
app+translate 671 条全绿。versionCode 172 → 173（一次提交 +1）。

### 1.0.70

**系统返回键的消费改成「默认」** —— 不再靠每页自己记得挂 handler，改由切换器兜底 + 编译器把关。

① **原来的失败模式是静默的**：每个子页要在自己的路由分支里挂一句
`PageBackHandler { xxx = null }`，漏挂的后果是「按返回**跳掉一层**」——
不崩、不红、只有真机连按才试得出来。仓库页的**网页会话登录页**就这么漏了一版：
在那一页按系统返回会直接退出整个仓库页（上一轮做系统栏审计时才顺手发现）。

② **兜底收进 `PageSwitcher`**：新增**必填**参数 `onBack`（「这一格的默认返回」），
切换器给每一格自动注册一次 `PageBackHandler`。注册位置在 `content(target)` **之前** ⇒
页面自己（或更深一层，例如文件页的编辑态、Issue 评论编辑态）注册的处理器后注册、优先命中；
退场中的旧页照旧由 `LocalPageActive` 自动放手。
**必填**是关键：新写的切换器没法「不表态」（编译器直接报错），
把 `onBack: (() -> Unit)? = null` 加回去就等于让静默缺陷复活 —— 有钉子盯着这一点。

③ **宿主那边只需要一条穷尽 `when`**（`leavePage()`）。`RepoRoute` / `MainRoute` 都是 sealed，
**新增路由时编译器会强制先在这里表态**，而不是像旧写法那样漏一个分支就跳层。
仓库页因此从 **15 个分支各挂一句** 收成 1 个函数（顺带把漏掉的 `WebLogin` 补上）；
主界面同理，并且把「顶层 → 再按一次退出」和「子页 → 退一层」彻底分成两层各管一段。
个人页（`profileBackTarget`）与任务页本来就用的这条模式，这次一并收进切换器。

④ **登录流程是例外，而且是非显然的例外**：`LoggedIn` 的 `depth` 是 3（位移动画靠它），
但它**不是子页** —— 拿默认判据（`depth > 0`）会让登录流程吃掉主界面的返回键，
「再按一次退出」直接失灵。所以切换器还开放 `isSubPage` 谓词，登录流程显式写成
`{ it !is Idle && it !is LoggedIn }`，同时**删掉了那个裸 `BackHandler`**
（`BackConsumptionTest` 的「不许裸用 BackHandler」白名单里也少了一个文件）。

**钉子**：`BackConsumptionTest` 从 2 条加到 4 条 —— 新增「`onBack` 必须是无默认值的必填参数」
（有人加回 `= null` 就红）与「宿主的退一层必须是穷尽 `when` 且显式处理顶层」；
原「有内部层级的页面必须消费返回键」放宽为两种合格写法（自己挂 handler **或** `onBack =`）。
app+translate 668 条全绿。versionCode 171 → 172（一次提交 +1）。

### 1.0.69

**系统栏内边距（edge-to-edge 的「消费」）审计** —— 全仓 30 个全屏根逐个过了一遍，补上漏的那个，并把规则钉住。

① **应用从 Android 15 起就是 edge-to-edge 强制**：窗口铺满整屏，状态栏与系统虚拟导航栏
（手势条 / 三键）都浮在内容之上。谁不消费内边距**不会报错、不会崩、测试也不会红** ——
只是「返回箭头压在状态栏底下」「列表最后一行滚不出手势条」，只有真机看得见。
仓库树那条路由最容易漏：`NavigationShell(barVisible = route is RepoRoute.Tab)` 在进详情页时
把底部导航栏**整个收起**，于是子页必须自己兜底底部内边距。

② **审计结果：只有一个页面真的漏了** —— `JobLogScreen`（工作流作业日志，仓库树里最深的一页）。
它是唯一一个没走 `DetailScaffold` 的详情页（顶栏带搜索框与步骤选择，自己用 `DetailTopBar` 搭根），
而 `DetailTopBar` 自己不取任何内边距 ⇒ 顶栏压状态栏、日志列表压手势条。已补
`statusBarsPadding().navigationBarsPadding()`。
其余 29 个全屏根都合格：要么自己取（`SubPageScreens` / `AccountsScreen` / `LogScreen` /
`SearchScreen` / `TaskScreen` / 登录页 …），要么走已经取过的壳
（`DetailScaffold` 26 处、`FullScreen`（工作流）、`DecisionScreenShell`（决策页））；
主骨架的两个 Tab（首页 / 消息）由 `MainScreen` 取「非底部」系统栏 + M3 `NavigationBar` 取底部，
两处不叠层。顺带清掉 `RepositoryOverviewScreen` 里**两个从没用过的** insets import
（当初想加没加，留着只会误导下一个人：那一页跑在 Tab 骨架里，本来就不该自己取）。

③ **把规则钉在源码上**（`SystemBarInsetsTest` 3 条）：每个全屏页的根要么自己消费、
要么用上面那几个壳；壳本身必须**真的**取了内边距（否则「用壳」就是空头承诺）；
仓库树那条「收起底栏时子页自己兜底」的约定单独一条，连同 `RepoBottomBar` 自己取内边距一起锁住。
清单是**显式**的（不是扫全目录）—— 「哪些是全屏页」只有人知道，而漏登记的后果正是这条钉子要防的。
`docs/specs/NAVIGATION-NOTES.md` §五的自检清单同步加了一条。

**钉子**：`SystemBarInsetsTest` 3 条。app+translate 666 条全绿。versionCode 170 → 171（一次提交 +1）。

### 1.0.68

**「已经渲染过的仓库，再进去会闪」** —— 数据全命中，闪的是**首帧**。

① **仓库页首帧快照**（`RepoOverviewMemory`）。日志里那次重进的缓存全命中，可页面还是从零组合：
```
23:39:12.515 进入仓库详情页 SunsetRNE/Branchbase-Android
23:39:12.516 L1 直出 ×8 / L1 命中 ×4        ← 一个字节都不用等
23:39:12.540 WebView 首次创建 10ms（主线程）  ← 但整页是重新组合出来的
```
`readmeHtml` / `languages` / `contributors` / `repoInfo` 的初始值都是空的，要等一次挂起的缓存读
才填上 ⇒ 每次都重放「正在加载自述文件… → 内容」、骨架 → 内容、README 从 **1dp 撑到几万 dp**。
新增的进程内快照（按 `owner/repo`，LRU 3 条）存**解析后的对象**，重进时每个状态的初始值都从它来，
`ReadmeViewHolder` 的初始高度也取它（上一次测到的高度），测量结果再写回去 —— 于是首帧就是
「同一份内容、同一个高度」，**不闪也不跳**。快照只服务首帧：命中与失效仍完全由 `SearchCacheManager`
的 TTL 决定，它不改变任何取数逻辑。

② **参与者列表的头像改走统一 `Avatar`**（原来是 Coil 的 `AsyncImage`）。`Avatar` 首帧**同步**画
进程内已解码的位图（24 条 LRU），`AsyncImage` 则每次重建都要重放一遍「蓝底 → 真图」——
贡献者一屏十来个，那串 pop-in 就是「参与者列表渲染有点慢」的观感来源。同一条路 1.0.62 已经在
账户头像上走过（`core/AvatarMemory`），顺带还吃到落盘的 200 文件上限。

③ **启动标记改成进程级闸门**（`Logger.startupOnce`）。这件事连着两版都没做对：
1.0.65 无条件打 ⇒ 切回首页就重跑，+20s/+22s/+27s 各一次；1.0.67 门控在 `resumeTick == 1`，
可真机日志里 **+116s 又打了一次** —— `resumeTick` 是 `remember` 出来的，**页面一被重建就从 1 重来**。
两次后果一样：之后几帧的慢帧注脚全变成「启动 ▸ …」，`frame-baseline.py` 的 `^启动` 桶把它们
算成启动帧（报表看着正常、桶是错的）。进程级的「打过没有」是唯一不受页面生命周期影响的判据。

④ **上一版那行猜测被证实了**：`WebView 首次创建 125ms（主线程）`（该进程内第一次进仓库页），
之后的创建是 10~24ms —— 进程级的那 ~100ms 就是 Chromium 初始化。它落在
「进入仓库详情页」那一帧上（1.0.65 日志里对应 `慢帧 272.8ms / 等待 254.2*`）。
**这一版没动它**：预热要在主线程空闲时提前建一个一次性 WebView，属于新的取舍，留到下一轮
（现在至少有数字了）。同一份日志里设置页（绘制 61.3 / 48.1ms）与日志页（143.5ms，动画 63.7 +
绘制 68.1）仍是帧率最低的两处，靶子已记在 `docs/specs/frame-perf-design.md` §5.2。

**钉子**：`RepoOverviewMemoryTest` 5 条（分块落地 / 同名仓库不同 owner 不串 / 后到的块覆盖不丢已有块 /
超上限淘汰最久未用 / 容量必须是个位数）+ `StartupMarksTest` 2 条（同 key 只放行一次、不同 key 互不连坐）；
`StartupMarkerTest` 那条改成钉「必须走进程级闸门」并禁止退回 `resumeTick == 1`。app+translate 663 条全绿。
versionCode 169 → 170（一次提交 +1）。

### 1.0.67

**1.0.65 的失败留痕，上线一小时就答了一个拖了三版的问题** —— 外加修掉 1.0.65 自己引入的一处假标记。

① **`/user/events` 是 404，这个端点根本不存在**。1.0.65 把 `?: break` 的静默失败改成记原始响应之后，
第二次会话立刻打出：

```
23:21:43.053 事件源 /user/events 第 1 页失败：ERROR:未知错误: HTTP 404 Not Found: {"message":"Not Found",…}
```

GitHub 的活动端点里，所谓「List events for the authenticated user」就是
`GET /users/{username}/events`（[认证成该用户时返回里才含私有活动](https://docs.github.com/en/rest/activity/events)），
**没有 `/user/events`**。旧实现是「先试 `/user/events`，空了再回退到 `/users/{login}/events`」——
那条腿不但注定失败（每次进动态页先白等一次往返），回退方向在「看别人的主页」时还写反了
（会去取**你自己**的活动显示在别人的动态里）。现在只留 `/users/{login}/events` 一条腿，
`EventSourceMemory`（网络抖动时 5 分钟内不重复踩）保留。这也解释了 1.0.58 那条
「`/user/events:1` 连续三次未命中」的旧日志：不是权限、不是代理，是端点不存在。

② **修掉 1.0.65 自己引入的假标记**。`HomeScreen` 的「启动 ▸ 首页首帧取数」打在了
`LaunchedEffect(resumeTick)` 里，而 `resumeTick` 每次「切回首页」都会 +1（初值就是 1）——
于是它变成一条常驻标记：第二次会话 28 条慢帧里有 **7 条**挂着它（+20s / +22s / +27s 的切 Tab），
而 `frame-baseline.py` 新增的 `^启动` 桶会把这些帧算成启动帧：**报表看着正常，桶是错的**。
现在门控在 `resumeTick == 1`（首次组合）。启动阶段的标记只该属于启动。

③ **给「WebView 首次创建」加一行可归因的计时**（本地类目，不进慢帧注脚池）。
同一份日志里，进程内第一次进仓库页出现 `慢帧 272.8ms（等待 254.2*）` —— 是本次会话里
最大的非启动帧，怀疑是首次创建 WebView（把 Chromium 拉起来，必须主线程），
但日志里没有任何一行能证实。这行不是修性能，是**让下一次日志能回答它**。

**钉子**：`EventFetchFailureTest` +1（源码级：ProfileScreen 里不许再出现 `/user/events`，
唯一的源必须是 `/users/{login}/events`）、`StartupMarkerTest` +1（首页阶段标记必须门控在
`resumeTick == 1`）。app+translate 656 条全绿。versionCode 168 → 169（一次提交 +1）。

### 1.0.66

**仓库页「很容易重建和重新渲染」** —— 一次进入，整段加载跑三遍；关系态还要空等一次往返。

① **根因：加载 effect 的键里带了分支**。1.0.62 为了让 README 在真实默认分支到手后重取一次，
把 `repoInfo?.defaultBranch` 塞进了 `RepositoryOverviewContent` 那个 effect 的键 —— 于是
**整段加载**（仓库信息 / 语言 / 贡献者 / README）都跟着分支重跑。进一次仓库页，外部
`sharedInfo` 与缓存直出会先后把分支补上，实测 effect 一共跑 **3 遍**（真机日志 22:52:52.538 /
52.629 / 53.019，形状是同一批 `直出 repo-info ×2 / @main / repo-lang / repo-contrib` 连着三轮）。
每遍都做两件坏事：(a) 无条件把语言 / 贡献者重新打回加载态 ⇒ **骨架闪 3 次**；
(b) 换一个 `coroutineScope` ⇒ 上一遍的在途请求被取消，同一份数据**发 3 次、只落最后一份**。
改法是把 README 拆成独立的 effect（它是唯一真正依赖分支的一块，键跟着
`val readmeBranch = branch ?: repoInfo?.defaultBranch` 走，分支未知时直接返回、绝不猜），
与分支无关的那一段只跟 `(owner, repo, refreshTick)` 走；并且**重新打加载态前先看手上有没有数据**
（手动刷新除外）—— 这条是本轮骨架闪烁的直接来源，同一条约定 `ProfileScreen` 早就写了。

② **关系态（星标双向态 / Watch 档位）先直出再复核**。判定要走「网页会话 → GraphQL」两条腿，
冷的一次实测 ~800ms（`22:52:52.519 进入仓库页` → `22:52:53.320 判定`），这段时间按钮是空的 ——
用户看到的就是「先渲染一遍、结论到了再重画一遍」。新增 `RepoActions.cachedRelation`
（只读含过期），进页面当帧把旧值画上，紧接着回源复核覆盖；复核失败保留旧值而不是清空。
TTL 只有 5 分钟且第一版就有「过期会把已星标显示成未星标」的顾虑，所以这个取舍写在函数注释里：
旧值只在**一次往返**的时间窗内可见（1.0.65 起过期行不会被清扫删掉，这条直出才成立）。

**钉子**：新增 `RepoOverviewLoadTest` 4 条（源码级）—— 与分支无关的加载不许带分支键、
README 必须是独立 effect 且分支未知时直接返回、重新打加载态前必须判「手上有没有数据」、
关系态必须「先直出、后复核」且复核失败不清空。app+translate 654 条全绿。
versionCode 167 → 168（一次提交 +1）。

### 1.0.65

**一份真机日志（907 行 / 14 次启动 / 243 条慢帧明细）读出来的五件事**：两处「修了但没生效」，
一处机制互相抵消，一处列表回收导致的重建 + 跳顶，以及启动段第一次变得可归因。

① **事件源的失败留痕是死代码** —— 1.0.58 想修的「失败源不留痕」从来没生效过。
`fetchEventPages` 写的是 `PageCache.refresh(...) ?: break`，紧接着一个
`if (pageJson.startsWith("ERROR:")) Logger.net("事件源 … 失败")`。而 `PageCache.refresh`
按契约把错误响应**吞成 `null`**（`PageCache.kt` 的 `takeIf { !it.startsWith("ERROR:") }`），
所以第二行永远到不了：失败全程静默，调用方只看到「空」，然后照样 `markDead` 5 分钟。
真机证据是数量对不上 —— 3 次 `跳过刚失败过的事件源 /user/events`、**0 次** `事件源 … 第 N 页失败`。
于是「`/user/events` 为什么总是不走」在日志里一个字都没有：是 401（令牌类型不支持）、
404（端点没有）、还是断网？三种原因的处置完全不同。
现在把原始响应截下来，`null` 时补一行，且三种失败**必须分得开**（无响应 / 空响应 / `ERROR:` 原文）；
`markDead` 的判据也从「`isEmpty()`」改成「**一条都没拿到 + 抓取失败**」——
源是好的、只是这段时间没数据，不该被拉黑；回退源同样按这个判据记账。

② **个人主页的仓库列表永远暖不起来** —— 回源跟着页面一起被取消。
`loadRepos` 挂在 `LaunchedEffect` 上，「进主页 → 一眼就点进某个仓库」会在结果回来之前离开，
协程取消 ⇒ **既不写缓存也不打日志**（连 `GET /user/repos → …` 那行都没有）。
真机证据：6 次 `未命中 profile:…:repos` 里有 2 次**之后 30 行内没有任何结果行**，且两次都紧跟
「进入仓库详情页」。新增 `PageCache.refreshDetached`：整段跑在 `NonCancellable` 里 ——
对**渲染**而言取消是对的（结果没用了），对**缓存**而言是反的（用户离开不代表这份数据不要了）。

③ **清扫把「先直出再回源」删没了** —— 两条机制互相抵消。
`getStale`（stale-while-revalidate 的直出）服务的正是 `expireAt <= now` 那批行，
而 `sweepIfDue → deleteExpired(now)` 删的就是它们。于是「直出」只在**上一次清扫之后的 5 分钟内**
成立：隔一会儿再进页面，日志是 `无缓存可直出`（5 次）而不是 `L2 直出（含过期）`，
明明上一场会话写过。现在清扫只删「过期超过 `STALE_GRACE_MS`（24h）」的行 ——
过期不等于没用，过期太久才是；容量另有 `MAX_ENTRIES`（160）的 LRU 兜着。
`sweepIfDue` 拆出可直调的 `sweepNow`（节流是 `shouldSweep` 的事），口径因此测得动。

④ **自述文件：滚到底再往回滚会重建、并跳回整篇描述的最顶部**（用户反馈）。
自述文件是 LazyColumn 里**一项 4~6 万 dp 高的 item**（本仓库自己那份实测 45k~58k px），
滚到页面底部时这一项整体离开视口被回收，而旧的 `DisposableEffect` 会 `webView.destroy()` ——
`remember` 出来的 WebView 与**测量高度**（1dp 起步）一起没了。往回滚时重新组合：
(a) 新 WebView + 重新 `wrapHtml` + `loadDataWithBaseURL`（沉浸式翻译也跟着从头来）；
(b) 在高度测量回来之前这一项只有 **1dp**，4 万多 dp 塌成 1dp ⇒ 外层列表锚点全部错位 ⇒ 跳顶。
新增 `ReadmeViewHolder`（页面级持有者）：`AndroidView` 的 `onRelease` 只把 WebView **摘下来不销毁**，
挂回去还是同一个实例、同一份文档（`readmeDocKey` 指纹）、同一个高度；每次进入组合重新登记
JS 桥（复用时上面挂的是上一轮的桥，闭包指向已回收的组合 ⇒ 点图没反应）。
只保留一份、不做多份 LRU（一个 WebView 是几 MB 到几十 MB）；发布说明等短正文页不传持有者，
行为与原来一致。

⑤ **启动段第一次可归因** —— 顺带修掉「打点落在落盘边界之外」。
真机 14/14 次启动各 1 条启动慢帧（115~266ms，其中**等待段 82~190ms**），
而注脚 14/14 都是「git TLS 证书初始化完成」—— 那只是启动过程中打的一条日志，
后面 1.2~2.9 秒的帧全被归到它头上。两处原因：
- **`LogManager.init` 之前打的日志永远进不了文件**：`appender` 还是 null，导出走「文件优先」。
  铁证：`NetworkWatch.install` 每次启动都打一行基线，整份日志里 `[Reach]` 只出现过 1 次；
  `FileAppender` 构造函数里那句「清理历史日志」同样打在自己被赋值之前。
  现在 `init` 提到 `BranchbaseApp.onCreate`，并在赋值后**把环形缓冲补写一遍**（倒序 = 时间序）；
- 启动路径补 6 个阶段标记（`启动 ▸ 日志初始化 / 应用装配 / 首选项首载 / JNI 与证书 / 首次组合 /
  首页取数`）+ `FrameWatch` 的收尾标记 `启动 ■ 首帧已上屏`（注脚是「最近一条 UI 类日志」，
  没有收尾标记它会**一直粘到交互段**）。`frame-baseline.py` 同步加 `^启动` 桶并排在最前 ——
  此前启动那一帧落在「其它」里，而它恰恰是全场景最慢的一帧。
  标记文案刻意避开 `进入/打开/切换到「` 等既有场景前缀，否则会被算进别的桶、数字看着正常却是错的。

**这一版没做的（已定位，等出包 A/B）**：
- 设置页首帧绘制量的**结构性**解法。归因已经做完：22 条慢帧、绘制累计 893ms，其中 16 条首帧占
  734.6ms（单帧 22~82ms，均值 45.9ms/次；同期等待段只有 33ms）；`PageSwitcher` 是
  `AnimatedContent` 且**不保活**，所以每次进入都是「从零组合 + 从零录显示列表」——
  这是 v1.0.53→v1.0.64 这个数字不动的结构性原因，改过渡形式当然没用。
  本版只做了两处**行为不变**的减法：禁用行的半透明乘进颜色（去掉 `Modifier.alpha` 那一层
  RenderNode）、状态胶囊的圆角交给 `background(color, shape)`（不用 `clip`）；
  以及把 `commitMode` / `gitProxy` 两处**组合期读 prefs** 收进 `remember`。
  真正的三条靶子（`SubPage.Settings` 保活 / 首屏行数瘦身 / 图标与 R8）见
  `docs/specs/frame-perf-design.md` §5。
- 启动段「等待」的**性能**修复（本版只让它可归因）。已经在代码里定位到顺序与证据：
  主线程串行链 = 首选项首载（`branchbase.xml` 实测 32.6KB）→ `System.loadLibrary`（11MB .so）
  ＋ 190KB CA 读写（且与后台 `AccountChecks` 线程**抢同一把类初始化锁**）→
  首次组合里的 `persistAccount`（`Dispatchers.Main.immediate`，与等待段 r=0.957）。
  候选处置按性价比排在同一份文档里。

**钉子**：新增 4 个测试类共 15 条 —— `EventFetchFailureTest`（4：三种失败分得开、`ERROR:` 原文
不许吞、超长截断）、`ReadmeDocKeyTest`（7：换分支/换仓库/换正文/换基准目录/换令牌都必须
**不相等** —— 这个键漏维度不是命中率低，是把另一篇文档当成这一篇）、
`StartupMarkerTest`（4：六个阶段标记都在、收尾标记的 tag 不是「帧」、不许撞上其它场景桶、
脚本里 `^启动` 桶必须排在最前）、以及 `SettingsSpecTest` 补 3 条（禁用行不许 `Modifier.alpha`、
胶囊圆角不许 `clip`、组合期读盘必须进 `remember`）；`SearchCacheManagerTest` 补 2 条
（清扫不许删「刚过期」的行、宽限期至少小时级）、`PageCacheTest` 补 2 条成对用例
（`refreshDetached` 取消后仍落盘 / 普通 `refresh` 取消后什么都不写）。

### 1.0.64

**正在跑的工作流被判成失败**：`org.json` 的 `"null"` 伪值一路传到判定层。

① **根因**：GitHub 对进行中的 run / job / step 返回 `"conclusion": null`，而
`JSONObject.optString` 在值是 JSON `null` 时返回的是**字符串 `"null"`**（不是 null）。
旧的判定是黑名单反推 —— `conclusion != null && conclusion !in setOf("success","skipped","cancelled")`
算失败 ⇒ `"null"` 非空且不在名单里 ⇒ **正在跑的被算成失败**。同一个伪值还波及：
`isFailedConclusion`（详情页「只看失败」能筛出正在跑的）、失败优先排序、
`Text(job.conclusion ?: job.status)`（界面上直接显示 `null`）。

② **三层一起修（防御纵深）**：
- **解析层**：新增 `JSONObject.optNullableString` / `optText`，把「缺省 / 空串 / 字面量 `"null"`」
  统一归一；runs / jobs / steps 的 `conclusion`、`started_at`、`completed_at`、`runner_name`
  等全部改走它；
- **判定层**：`isFailedConclusion` 从黑名单改成**白名单** `{failure, timed_out, startup_failure}` ——
  未知结论、伪值都**不算失败**（少报一个失败，好过把「正在跑」报成失败）；
  `runProgress` 用同一个谓词，并按 `status` 分「运行中 / 排队」；
- **显示层**：`stateTone` / `runStatusLabel` 顶部先把伪值归一（`normalizedConclusion()`），
  不依赖上游是否干净。

③ **多状态显示**：job 行原来是 `conclusion ?: status` 的裸值（会显示 `null`）、
step 行只有色点没有文案 —— 现在两处都走 `runStatusLabel`（进行中 / 排队中 / 已取消 / 已跳过 /
超时 / 成功 / 失败…）+ `stateTone` 的文字色。只有色点的话，「跳过 / 取消 / 失败」在小尺寸或
色觉差异下分不出来。

④ **钉子**：新增 4 条（`WorkflowFormatTest`：字面量 `null` 与未知结论都不算失败、
运行中的 job 在进度里算运行中、状态文案覆盖多状态且伪值不泄漏）+ 1 条
（`WorkflowModelsTest`：`JSON null` 字段解析成 null 而不是字符串 `"null"`，覆盖 runs/jobs/steps 三层）。

### 1.0.63

日志页的「导出 .log」换成**导出日志包**：打包 zip → 落到 `Download/Branchbase/` → 拉起系统分享。

① **为什么换掉旧的**：旧实现是把整份日志塞进剪贴板 —— 长日志又慢又容易被别的输入框截断，
出了 App 就没法用。现在是完整链路：`LogManager.flush()`（写盘是异步的，不刷会少最后几行）
→ 内存里打 zip → 落盘 → 分享。单条复制（点日志行）与过滤面板里的「复制」都没动。

② **落盘按系统分两条**（这不是偷懒，是必需）：

| 系统 | 方式 | 权限 | 目录被删后 |
|---|---|---|---|
| API 29+ | `MediaStore.Downloads` + `RELATIVE_PATH=Download/Branchbase` | **不需要** | 下次写入自动重建 |
| API ≤ 28 | `getExternalStoragePublicDirectory(DOWNLOADS)/Branchbase` + `mkdirs()` | `WRITE_EXTERNAL_STORAGE`（运行时申请） | 下次写入重建 |

MediaStore 那条还顺带解决两件事：写入期间用 `IS_PENDING` 标记（别的应用读不到半截 zip）、
拿到的 `content://` Uri 可直接丢给分享窗口。API ≤ 28 的真文件必须过 FileProvider
（`file://` 从 API 24 起抛 `FileUriExposedException`）。

③ **失败要说清是哪一种**：`Result.Failed` 带 `needsStoragePermission` —— 权限问题弹窗引导去
本应用权限页（一键跳系统设置），其它问题（磁盘满、系统拒绝）只如实说明原因。
两者混成一句「导出失败」会让用户去改一个本来没问题的开关。

④ **两个坑记在这里**：
- Manifest 合并按**类名**判重：`:downloader` 已注册过 `androidx.core.content.FileProvider`，
  再注册同一个类（即使 authority 不同）会冲突 → 新增空子类 `ui/log/LogFileProvider.kt`；
- 日志内容「文件优先、内存环形缓冲兜底」：首次启动后立刻导出时文件可能还没落盘，
  没有兜底就会导出一个空包（而「导出为空」比「导出失败」更难排查）。

⑤ **钉子**：`LogExporterTest` 4 条 —— 压缩包内容一致（中文与换行原样往返 UTF-8）、
多条目各自独立、空内容也能打出合法包、文件名带时间戳（两次导出不互相覆盖、同一时刻同名）。

### 1.0.62

两个真机反馈的修复：**仓库页闪现性重建**、**头像反复蓝底再变真图**。

① **仓库页闪现性重建** —— 根因是「**默认分支未知时猜 `main` 去取数**」。
真机日志里同一份 README 在 1 秒内被取了两次：`@main`（猜的）与 `@master`（真实默认分支）。
机制：`RepositoryOverviewScreen` 的加载 effect 键是 `(owner, repo, branch, refreshTick)`，
`branch` 从 `null` 变 `master` 会让**整段加载重跑**，而第一遍已经用猜的分支取过 README 了；
`RepoPrefetcher` 同样会猜 `main` 预取 —— 那份缓存**永远不会被读取**（页面读的是 `@master`），
白打一次请求、还占 LRU 额度。改法两条：
- **分支未知就不回源**：Overview 的 README 在 `knownBranch == null` 时跳过并保持加载态；
  「猜」只允许用在**读缓存**上（猜错即 miss，无副作用），并抽成具名常量
  `DEFAULT_BRANCH_GUESS` 把这个边界写死；
- **把默认分支纳入 effect 的键**（`repoInfo?.defaultBranch`）：否则分支到手后不会重取 README，
  页面会一直停在使用猜的分支取回来的那一份；
- `RepoPrefetcher` 的 `warmOverview` / `warmTabs` 去掉 `?: "main"`：拿不到默认分支就**整块跳过**
  —— 预取本来就是投机行为，宁可少做一次，也不要写一份永远不会命中的缓存。

② **头像反复蓝底再变真图** —— 真图此前一律交给 Coil **异步**加载，于是**每一次重建都要重放
一遍「蓝底首字母 → 真图」**（切 Tab、进出子页、开 More 菜单……），而头像出现得极频繁，很显眼。
新增 `core/AvatarMemory.kt`：**解码后的 Bitmap 留在进程内**（24 条 LRU，键含账号/像素/version），
`Avatar` 首帧**同步**取内存，命中即同一帧画出来；未命中才走既有的「本地文件 → 下载落盘」并按
目标像素 `inSampleSize` 解码。顺带删掉随之无用的 `avatarUrlSized`（全仓已无引用）。

③ **给头像目录加上界**（我自己引入的风险，一并处理）：贡献者头像现在同样会落盘，
`filesDir/avatars/` 不设限会随浏览无限增长 → 加 `MAX_FILES = 200` 的上限，
超限按**最后修改时间**淘汰最旧的；淘汰顺序抽成纯函数 `evictionVictims`（从旧到新），
单测钉住「保留最新、删最旧」——顺序写反的后果是「头像刚存下就没了，每次重新下载」。

④ **新增 5 条单测**：`core/LruCache` 泛型实现（淘汰顺序、按前缀删除、覆盖写不增条数）
+ 目录淘汰策略（保留最新 / 未超限不删 / 时间相同也不炸）。注意这个 `LruCache` 不用
`android.util.LruCache` 的原因与 `:translate` 那份一致：后者在 JVM 单测里是空壳，测不到淘汰行为。

### 1.0.61

账号探测的**误判**：应用明明能用，界面却挂着「令牌已失效」。

① **现场**（其他用户的真机日志）：同一次会话里 `/user` 报「令牌已失效」，而
`/user/repos`、`/notifications`、GraphQL 全是 200 —— 令牌显然是好的。代价很具体：
用户去重新登录（没用），而且结论**粘住整场会话**（重探只在手动点或网络跃迁时发生，
网络一直没变就没人翻案）。这段判定逻辑此前**一条测试都没有**。

② **只信 GitHub 形状的 401/403**（`AccountStore.looksLikeGitHub`）：真 GitHub 的错误体是
JSON（含 `message`，通常带 `documentation_url`）；代理 / 门户 / 中转站的 HTML 403、空体 403、
自家 JSON 一律判「无法连接」而不是「令牌已失效」—— 后者的误导在于让用户去重新登录，
而真正该检查的是网络/代理。

③ **复核一次**：401/403 类结论（含封禁 / 限流）必须换端点（`/user/repos?per_page=1`）确认，
两个端点都失败才定性；复核成功则推翻并记一行「探测端点或链路上的中间人可疑」。

④ **交叉验证**（新增 `core/ApiEvidence.kt`）：`RustBridge.getJson` 成功时记一笔
（host + token 哈希、进程内、TTL 5 分钟 —— 不把凭据原文当 map 键）。判「失效」前先查：
最近用同一 token 成功过就不可能失效，降级为「无法连接」。

⑤ **证据入日志**：`GET /user (login) → 结论｜原始 <HTTP 码 + 响应体前 160 字符>`。
此前只记结论，误判时无从下手 —— 这条 bug 拖这么久，正是因为日志里只有「令牌已失效」四个字。

⑥ **钉子**：新增 13 条单测 —— `AccountStatusTest`（7 条：GitHub 形状 401/403 才算失效、
门户 HTML 403 / 空体 401 / 中转站自家 JSON 一律算连不上、封禁与限流文案优先于状态码、
形状判据本身）+ `ApiEvidenceTest`（6 条：TTL 过期、按 host 与 token 隔离、空 token 不算证据、
纯函数过期判定）。

### 1.0.60

**设备档案**：每次启动把「这台机器是什么状况」记进日志（隐藏项，只在日志里），让别人发来的
日志第一次具备**设备上下文**。

① **为什么**：别的用户发日志过来时，能看到的只有慢帧分段与缓存命中 —— 但**同样的 120ms 慢帧，
在旗舰机上是我们写得重，在低端机上可能已经是极限**。而下面几项会直接改变结论：
**刷新率**（慢帧阈值按 60Hz 写死，120Hz 上「没超 16ms」其实已经掉了 vsync）、
**动画缩放**（开发者选项把动画调成 0.5x / 关闭时，动效类反馈全部失真）、
**不保留活动**（打开后每次离开页面都销毁重建 —— 正是「每次重进页面都要重建」这类反馈的
头号环境原因）、**省电模式 / 低内存**（限频限刷新率，性能类反馈的头号混淆项）、
**debuggable**（debug 包不做 AOT、含 baseline profile 差异，性能不代表正式版）。

② **做法**：`ui/log/DeviceProfile.kt` 采集 + 纯函数格式化，每次 `App 启动` 记**5 行**
（机型/屏幕/性能/系统开关/App）。刻意只在日志里，设置页不加入口 —— 它不该变成用户要维护的配置。
采集全部 `runCatching` 兜底：读不到的项写 `?`，绝不因为某个 ROM 不给读就让整行消失。
非 60Hz、动画被调小、低内存、debug 包这些情况**行内自带提示**，读到的人不用自己换算。

③ **行式日志的硬约定**：档案拆成单行而不是多行块 —— 日志是「一行一条、按行解析」的
（`logs/<日期>/branchbase.log`，`tools/perf/frame-baseline.py` 也按行解析）。

④ **一处必须避开的坑**：档案走**本地类目**而不是 UI 类目 —— `FrameWatch` 给慢帧加的「页面」
注脚取的是**最近一条 UI 类日志**（`LogManager.lastUiMessage`），档案若按 UI 记，启动那几帧的
注脚会变成「机型 OnePlus PJD110…」，正是它注释里警告过的套娃。

⑤ **钉子**：`DeviceProfileFormatTest` 8 条 —— 固定 5 行且每行不含换行、关键事实都在、
高刷提示换算、动画被调小要标注、不保留活动/省电模式要醒目、debug 包要标注、缺项写 `?` 不写 `null`。

### 1.0.59

翻译模块的缓存键**少了变体维度**：换后端 / 换模型 / 关掉占位符保护之后，旧译文继续命中。

① **问题**：`Translator` 的键是 `(源语言, 目标语言, 归一化原文)`，但译文内容还取决于
**谁翻的**（MyMemory ⇄ DeepSeek、模型名、自定义接入地址）与**怎么翻的**（占位符保护开关）。
于是用户在设置页换到 DeepSeek（期望质量提升）之后，**旧后端的译文照样命中、新后端一次都不会被调用**
—— 用户看到的是「换了没效果」；同理，把「保护代码与链接」关掉做对比排查时，命中的仍是保护版译文。
这与 1.0.58 修的「键里带秒级时间戳（永远 miss）」是同一类错误的反面：**错命中**。

② **做法**：键扩成 `(源语言, 目标语言, 变体, 归一化原文)`，变体 = `provider|model|baseUrl`
＋占位符保护开关（`TranslateConfig.cacheVariant()`，由 `TranslateRuntime` 以取值函数注入，
改完设置下一页即生效、不用重启）。**刻意不含 API Key**：同一后端同一模型，换 Key 不改变译文，
算进去等于「换 Key 就把整库缓存作废」，白白重烧额度。样式 / 对照方式也不进变体 ——
它们只影响页面表现，这正是「切样式不重翻」能成立的原因。

③ **顺带修掉一处撞键风险**：键的各字段改用**长度前缀**拼接（`3:en|2:zh|8:mymemory||5:hello|`）。
裸 `|` 拼接时，原文里出现 `|` 可能让两组不同的 (变体, 原文) 拼出同一个键，
而缓存撞键的后果是**返回另一段的译文**（不是慢，是错）。

④ **可观测**：`TranslateStats` 增加 `hits` / `misses` / `hitRate`；`Translator` 每批记一行汇总
（`一批 12 段：命中 9 / 未命中 3（变体「mymemory||protect=1」）`，经 :app 注入的 `log` 回调进日志）。
此前「翻译缓存有没有在干活」完全不可见 —— 而命中率低说明**键对不上**，不是缓存没生效。

⑤ **钉子**：新增 7 条单测 —— `TranslateCacheTest` 加 3 条（变体隔离 / 跨进程按变体隔离 /
长度前缀防撞键），新增 `TranslatorCacheVariantTest` 4 条（同变体只翻一次 / **换后端必须重新翻**、
换回旧后端仍命中 / 保护开关进键 / 命中率统计）。

### 1.0.58

「缓存命中率低」不是缓存层没生效，而是**两处真 bug** —— 修掉之后动态页不再每次重拉。

① **贡献日历的键里带着秒级的 `now`**：区间 `[now-364 天, now]` **同时是缓存键的一部分**
（`profileKey(login, "calendar:$from:$to")`），于是**永远不可能命中** —— 每进一次动态页都
打一遍 GraphQL，贡献墙只能等网络回来再画（真机 77.6ms 的绘制帧就是这么来的）。
真机日志里三次进页面拿到的键分别是 `…07:04:22Z` / `…07:04:24Z` / `…07:04:29Z`。
改成**按 UTC 天取整**：结束点取「**明天** 00:00Z」（取今天 00:00Z 会把今天那一格排除在区间外，
墙上的「今天」永远是空的），起点 = 结束点 − 365 天。同一天内键稳定 → L1/L2 命中（TTL 10 分钟），
跨零点自然滚动。新增 `ContributionRangeTest`（4 条），钉的就是「**同一天内多次调用必须给出同一个区间**」。

② **失败的源不留痕**：动态页先走 `/user/events`（认证用户自己的活动，含私有仓库），
空了/失败了才回退到 `/users/{login}/events`；而 `PageCache.refresh` 只在拿到有效响应时才写缓存，
于是每进一次动态页都要**先等一次注定失败的往返**（日志：`/user/events:1` 连续三次「未命中」，
同期的 `/users/SunsetRNE/events:1` 在 L1/L2 命中）。新增 `EventSourceMemory`：进程内、
TTL 5 分钟、键带账号（`"$login|$path"`，换账号不连坐）、拿到数据立刻撤销；
同时把失败原因打进日志（原先 `startsWith("ERROR:")` 直接 break，什么都不记，导致
「这条腿为什么总是不走」只能靠猜）。新增 `EventSourceMemoryTest`（6 条）。

③ **两条不是 bug 的 miss，顺便说清**：
- 首页计数（TTL 5 分钟）与通知（TTL 2 分钟）过期后回源是**设计** —— 它们要的是新鲜度，
  而且用户看到的是 `cachedFirst` 直出的旧内容 + 后台刷新，不是空转等待；
- 消息页的预取键与页面键**是一致的**（`notifListPath(participating = false)` 就是页面默认值），
  预取没有白做，15:04 那次 miss 只是距上次预取 6 分钟 > 2 分钟。

### 1.0.57

切 Tab 不再重建页面：`TabSwitcher` 改成**保活**（访问过的目的地留在组合树里），并补上配套的
「重新可见即重新校验」。

① **为什么改**：上一版（1.0.56）把数据层做到了 L1 同帧命中，但**组合本身**仍会被销毁 ——
`AnimatedContent` 在退场动画结束后把旧内容移出组合树，于是「切走再切回」是全新一次组合：
页面 effect 重跑、列表重新构建、`remember` 全部归零。用户的原话是
「每次重进页面都要重建页面，浪费时间」。

② **做法**：访问过的目的地各占一层（顺序由纯函数 `keepAliveVisited` 维护），切换只改透明度
（沿用 fade-through 的时长与曲线），**当前页压在最上面**（zIndex）；
**完全隐藏后不再绘制** —— `drawWithContent` 里判 alpha（阈值 0.004，避开插值尾部的 1e-7 那种值），
组合与状态仍保留。这就是保活的取舍：省下的是组合与重建，付出的是内存与布局。

③ **配套（不做就是静默的数据变旧）**：保活之后页面的 `LaunchedEffect(Unit)` 一辈子只跑一次。
新增 `rememberPageResumeTick()`（首次算 1，之后每次「隐藏 → 可见」+1），页面把它加进 effect 的键，
回来时重新校验一遍（先直出缓存 → 按 TTL 决定是否回源，命中就是同帧、通常零网络）。
已经接上：首页计数、消息页首屏、个人页仓库列表与动态页两路数据、仓库内六个列表页。
两个细节：**重新校验时不再置加载态**（已有数据还显示 loading 会把列表闪一下）；
**tick 而不是直接拿 `LocalPageActive` 当键** —— 后者在「切走时」也会重启 effect，
把正在飞的回源请求取消掉。

④ **钉子**：`PageTransitionsTest` 那条「两个切换器都必须下发 `LocalPageActive`」跟着改了形状 ——
保活把下发点挪进了 `KeepAliveTab`，所以现在钉两件事：全文件有**两处** `LocalPageActive provides`
（一个都不能少），且两处都必须由 `pageIsCurrent` 判定。规则本身比原来更要紧：
`TabSwitcher` 保活后，「隐藏的 Tab」是**长期**留在树里的（不再只是动画那 220ms），
少了 `LocalPageActive=false` 它们会长期抢返回键。

⑤ **没做的**：`PageSwitcher` 不保活（路由 key 带 payload，不适合常驻）。所以「返回上一层再进去」
仍然是重建 —— 那一层的解法是状态保活（ViewModel / 进程级状态），留到下一轮。

### 1.0.56

重进页面不再重新读盘：本地缓存加**进程内一级（L1）**、读路径不再写库、命中情况可见。

① **现状（两笔实打实的浪费）**：`SearchCacheManager` 直连 Room，每次重进页面（切 Tab、
返回再进、重开详情）都要走一遍跨线程调度 + SQL + 游标；而 `get()` 的第一行是
`dao.deleteExpired(now)` —— **每读一次缓存就写一次库**（DELETE 事务）。重进一个页面往往要读
好几个 key，于是「打开页面」这件事在磁盘上平白多出几笔写。

② **做法**：新增 `MemoryCache`（LRU（访问序）+ 两条预算：条数 200 / 12MB —— 只限条数会被
大 README 把堆吃掉）。`SearchCacheManager` 变成两级：**L1 命中同帧返回**、
**L2 命中回填 L1**（下次同页重进就是同帧）、`delete` **两层一起删**（只删 L2 的话
`getStale` 会把 L1 的旧值又直出回来）。过期清理从**读路径**挪到**写入路径**：
进程内一次 + 之后每 5 分钟一次，判定提成纯函数 `shouldSweep` 便于单测。

③ **可观测**：「重进页面到底还付了什么」此前只能猜 —— 页面自己那句 `GET /xxx → 200`
**命中与否都会打**，证明不了任何事。现在四种结果各记一条 DEBUG（`L1 命中` / `L2 命中` /
`直出（含过期）` / `未命中`），跑一轮 S1–S5 就能看出每个页面的数据来自哪一层、
有没有系统性 miss（miss 说明键或 TTL 有问题，而不是缓存层没生效）。

④ **钉子**：新增 10 条单测 —— `MemoryCacheTest`（过期不算新鲜但仍可直出 / 类型不匹配不算命中 /
LRU 淘汰时刚访问过的留得住 / 字节预算 / 覆盖写不重复计字节）+ `SearchCacheManagerTest`
（L2 回填 L1 / 读路径不写库 / 清扫节流纯函数 / 两层同删 / 写入按类型 TTL）。
`SearchCacheDao.deleteExpired` 改为**返回删除条数**（日志要看得见，否则清理路径不可观测）。

⑤ **边界**：L1 只管「同一份 JSON 字符串」，**不做解析结果缓存** —— 解析记忆化留在页面自己手里
（如消息页按原文记忆化）。跨进程（冷启动）仍走 L2。

### 1.0.55

「页面内容从骨架变成真实内容那一刻」不再跳、不再糊：新增元素级原语 `PlaceholderSwap`，
把三处替换收口到一处。

① **先把锅找对**：用户反馈的是「那一刻不舒服」，而不是页面过渡。查下来问题出在替换本身 ——
`Crossfade` 有三个固有行为，全都读成「不舒服」：**容器尺寸在两态之间取大者**（内容一落地，
高度当帧变成内容高度，下方整片被顶下去；骨架 4 行 / 内容 30 行的分区最明显）、
**两态同时半透明重叠着淡**（中段灰块与文字糊在一起）、**骨架无条件立刻显示**
（缓存命中常在 1~3 帧内拿到数据，闪一块灰再立刻换掉 = 「闪了一下」）。

② **做法** `PlaceholderSwap(loading, skeleton, content)`：
骨架**延迟 120ms** 才现身（延迟期内它仍占着高度，版式不会塌一下再撑开）；
替换时**骨架先退干净 120ms、内容延迟同样时长再进 160ms**（与页面级「退场淡出早收」同一个思路）；
容器从 `Crossfade` 换成 `AnimatedContent` —— 它自带 `sizeTransform`，高度变化是**动画**而不是跳变。
微光透明度仍在 `graphicsLayer` 里读（绘制期消费），延迟窗口内不会每帧重组。

③ **收口**：动态页的 `RegionSwap`（统计卡 / 类型分布 / 热力 / 时间线四个分区）与贡献墙的
`Crossfade` 全部改走它，本地那份 `RegionSwap` 实现删除。
`CrossfadeLayoutTest` 的覆盖钉子从「≥3 处」改为「≥1 处」并写明原因：剩下那一处是活动区
「有内容 ↔ 空/失败说明」的整块换，另外两处迁走后由原语自己套 Column，调用方不必再记这条规矩。

④ **边界**：`skeleton` 与 `content` 仍应尽量同尺寸。不同尺寸不会跳了，但会看到一段高度动画 ——
那是兜底，不是许可证（写在原语注释里）。

### 1.0.54

把页面切换的**抖动**当成一条物理问题来修：抖动的定义是**速度突变（急动度）**，
所以两件事一起做 —— 换掉端点速度不连续的曲线，并拿掉第二个运动体。

① **曲线**：进场从 `LinearOutSlowInEasing`（`cubic-bezier(0,0,0.2,1)`）换成
`CubicBezierEasing(0.25, 0, 0.15, 1)`。前者**起步速度是平均速度的 5 倍**（t=0 直接弹射），
位移越短越像「抽一下」—— 用户对它的描述就是「有种过度抖动感」；新曲线两端速度都是 **0**，
且中段比标准 S 曲线（`0.4,0,0.2,1`）更早发力，所以既不弹射也不黏。退场改成 `LinearEasing`：
旧页现在只做淡化，而透明度没有速度感 —— 原先的 `FastOutLinearInEasing`（末帧还在加速）
只会让最后可见的一两帧掉得特别快，看起来像闪一下。

② **只让一页动**：旧页从「反向滑出、滑动走满 180ms」改成「**原地**淡出 100ms」，
位移 1/4 → **1/10 屏**，进场 300 → **220ms**。两页同时动、曲线还一进一退时，
相对速度一直在变，眼睛读到的不是「一页推进」而是「画面在晃」；现在屏幕上只有一个运动体。

③ **起播门控** `PageMotion.ENTER_DELAY_MS = 33`（≈2 帧，**进出两段都延迟**）：
状态一变 `AnimatedContent` 立刻起播，而目标页的首次组合正好压在同一帧（真机 100~240ms，见
`frame-perf-design.md` §5），表现是「刚动一下 → 定住 → 猛地跳过去」。延迟把这段重活挪到动画
开始之前，代价只有 33ms 起播等待。**边界**：它只盖得住「一两帧」级别的首帧开销，
仓库详情那种 200ms+ 的首帧还得靠页面首帧瘦身或预取。

④ **重页过渡台账** `PageLevel.heavyFirstFrame`：首帧重的页放弃方向位移、只做 fade-through。
台账标在路由上（三个路由都是各自文件里的 `private sealed interface`，集中清单拿不到类型、
还会随重构悄悄失效），优先级由纯函数 `transitionKindFor()` 决定 ——
**新增 3 条单测**钉住「重页优先于方向」这条顺序。当前台账两页：仓库详情（244ms，动画主导）、
设置页（一轮 7 条慢帧，绘制主导）。判据写在规格里：**≥3 条慢帧且主段是「动画」或「绘制」**，
「等待」型不标（那是主线程被占，换过渡形式没用）。

⑤ **试过又退回去的，连同理由一起入库**：中间那版给推进加了**整页缩放（94%→100%）+ 视差**
（旧页只走 60% 距离、缩到 96%）。真机观感**更晃** —— 全屏内容在位移中缩放会重采样
（文字发虚、边缘游移），两页速度不同又让相对运动更不稳定。结论「**整页位移期间不要叠加缩放**」
写进 `ui-design.md` §3 的取舍清单，避免以后重走。

⑥ **顺带落地的量尺**（这一版所有取舍的依据）：`tools/perf/frame-baseline.py`（取数 / 聚合 /
改前改后对比）+ `docs/specs/frame-perf-design.md`（口径、四类归因、验收清单）。
取数走 App 自己的 `FrameWatch` 日志 —— 设备命令侧的 `dumpsys gfxinfo` 被守护拦、
`perfetto` 不在放行名单，而 `FrameWatch` 与 `framestats` 同源且带页面注脚。

### 1.0.53

把 1.0.52 那类 bug 钉成**源码级钉子**：`Crossfade` 的 lambda 体必须是一个 `Column`。

① 为什么必须钉在源码上：`Crossfade` 的实现是 `Box { 每个状态各一层 }`，子元素是否重叠是**布局期**的事；
JVM 单测没有 Compose 运行时（渲染不了布局），当时 14 条单测 + `assembleDebug` 全绿，
只有真机截图看得出来 —— 这类规则和 `BackConsumptionTest`（返回键）、`PageTransitionsTest`（切换器）
一样，只能钉形状。

② 做法：新增 `CrossfadeLayoutTest` —— 扫 `src/main/java` 下所有 `.kt`，对每个 `Crossfade(` 调用点断言
「lambda 箭头之后的第一行代码是 `Column(`」（跳过空行与 `//` 注释行，并放行 `) { x -> Column(…) {` 同行写法）；
另有一条**覆盖性**断言（当前全项目 3 处调用点：`RegionSwap`、动态页活动区、贡献墙），
防止规则写错后恰好一条都不匹配而「假绿」。

③ 顺带统一形状：动态页活动区的 Crossfade 原先只把 `Column` 套在 `else` 分支里
（结构上安全 —— `if/else` 是单个语句，但形状与另两处不一致），现在套在最外层，
规则才能用一条覆盖所有调用点。这条也是新钉子自己抓出来的第一处。

④ 边界：若将来把 Crossfade 换成别的切换器（例如 `AnimatedContent`），钉子会报
「Crossfade 之后 12 行内没有 lambda 箭头」并提示同步更新，而不是静默失效。

### 1.0.52

修 1.0.49 引入、**真机截图才暴露**的严重版式 bug：`Crossfade` 的内容落在 **Box** 里，多子元素会互相叠加。

① 现场（真机装 1.0.51 后截图）：动态页「贡献墙」的网格与图例压在同一位置，
「活动类型分布 / 活动热力 / 最近活动」三块整段叠在一起，整页看起来像错版。
根因一句话：`Crossfade` 的实现是 `Box { 每个状态各一层 }`，交给它的多个子元素**不会纵向排列**，
而是在同一坐标上叠着画。而这一版恰好把三处「多子元素」的块塞了进去：

- 贡献墙的非加载分支（网格 Row + 图例 Row 两段）；
- 活动区的三块分区（六个 composable：三个 SectionTitle + 三个 Column）；
- `RegionSwap` 的骨架与内容本身（`repeat(4) { EventRowSkeleton() }`、`forEach { EventRow(...) }`
  本来就是多行）。

② 改法：三处各套一层 `Column(Modifier.fillMaxWidth())`，其中 `RegionSwap` 内部统一套 ——
把「Crossfade 是 Box 不是 Column」这件事在**一处**收口，调用方不必知道。
（`Box` 与 `Column` 的子元素布局差异不报错、不崩溃、单测也照过：这一轮 14 条单测全绿、
`:app:assembleDebug` 也绿，只有真机截图看得出来 —— 这就是「动画 / 版式改动必须在真机上过一眼」的实例。）

③ 复验方式：装完进「动态」页，概览三卡 / 贡献墙 / 类型分布 / 活动热力 / 最近活动 应各占各的位置，
不再互相重叠（上一版的截图正是这里叠成一团）。

### 1.0.51

修 1.0.49 顺手引入的一处**静默版式回退**：动态页「概览」三张统计卡不再等宽铺满。

① 现场（代码层复现，尚未上真机）：1.0.49 把概览卡包进 `RegionSwap` 之后，`Modifier.weight(1f)`
加在了 Crossfade 的**外层容器**上，而 Crossfade 的内容装在一层 **wrap-content** 的内层 `Box` 里 ——
卡片自己不声明撑满，就各自缩到「文字宽」（「7」/「近 7 天」那种）并靠左排，
三张卡从「三等分」退化成「三段左对齐的小盒子」。此前 weight 是直接加在 `StatCard` 上的，
所以这个回退是**改加载态时才引进来的**，不报错、不崩溃，只有看图才发现。

② 改法：`StatCardSkeleton` 与 `StatCard` 都显式传 `Modifier.fillMaxWidth()`
（weight 仍留在 Crossfade 上负责三等分，卡片负责在自己的槽位里撑满），
并把「为什么必须显式声明」写进注释 —— 下次再往 Crossfade / AnimatedContent 里塞带 weight 的卡片，
会踩同一个坑，而这类坑不会有任何编译或运行期提示。

③ 逐条核对过另外三处 `RegionSwap`（类型分布 / 活动热力 / 最近活动）：骨架与内容本来就
`fillMaxWidth`（`TypeBar` 靠内层 Row 撑满、`ActivityHeatmap` 与 `EventRow` 自身 fillMaxWidth），
所以只有概览卡这一处需要显式声明。

### 1.0.50

接上一版（1.0.49）的**真实数据复验缺陷**：events 接口返回的顺序**不是按时间倒序**的。

① 现场：真机截图里「最近活动」前三行是 77d563e / e00b6a6 / 85dc7f9，而它们的 `created_at`
分别是 06:38:39 / 06:31:37 / 06:43:34（UTC）—— 时间忽大忽小。用
`GET /users/{login}/events/public` 抓原始 JSON 复核，同样如此：接口返回的是**按事件 id 倒序**，
`created_at` 只是事件自身的一个字段，两者并不一致。后果都在显示层：

- 相对时间忽大忽小（「4 小时前」下面跟着「1 天前」，再跟回「4 小时前」）——这本身就是
  「不像真实数据」观感的一部分；
- 上一版新增的 `collapsePushes` 口径是「**相邻**条目 + 同仓库 / 同分支 / 同一天」，
  顺序一乱，同一天的推送就被切成好几段。用抓到的 30 条真实事件模拟：不排序是 13 行，
  日期在 09-15 → 09-13 → 09-14 → 09-12 之间来回跳；按 `created_at` 排序后是 9 行、日期单调递减。

② 改法：`parseEvents` 的出口按 `createdAt` 倒序排一次（稳定排序：同一时间保持接口原序），
`fetchEventPages` 在**分页拼接后**再整体排一次 —— 单页排序盖不住分页边界上的乱序。
两处都写明「调用方拿到的一定是有序列表」的契约，避免以后再各自排一遍。

③ 钉子：`ActivityFeedTest` 加 2 例（共 14 例）——「解析后按时间倒序、不沿用接口顺序」
（用真机抓到的三条乱序数据，并断言时间单调不增）、「乱序的事件流折叠后仍聚成一串」
（三条乱序推送 → 1 行、次数 3、保留 06:43 那条的 sha：顺序不同，这三条本来会各自成行）。

### 1.0.49

动态页两件事：**「最近活动」接真 + 连续推送折叠**、**加载态从「整页骨架 + 第二层加载文字」收敛为「分区骨架就地填充」**。

① 症状（真机复现）：进「动态」页看到的是「骨架 → 又一层『加载中…』文字 → 内容」。
根因是**两块数据不是一起到的**：整页的 `loading` 门只代表事件流，而贡献日历走 GraphQL、通常更慢 ——
事件到齐时整页骨架让位，`ContributionWall(loading = calLoading)` 紧接着又渲染一次
`Text("加载中…")`（`ContributionWall.kt` 的加载分支）。同一屏因此叠了两层加载态，就是那一下「闪」。
另外「动态概览」三张卡在日历未到时报的是 `${stats?.week ?: 0}` —— **0 也是内容**，
用户会先读到 0、再看着它跳到真实值，这是第三处小闪。

② 加载态收敛：**取消整页 loading 门**，每一区按**自己的**数据就绪度在原地由骨架淡入内容
（新增 `RegionSwap`：Crossfade + 元素级「出现 / 消失」的 220ms 规格；`ProvideShimmer` 仍是整页一层，
所有骨架共用一条微光）。贡献墙的加载态从一行文字换成**同尺寸骨架网格**
（`ContributionWallSkeleton`，复用活动热力那份 `SkeletonGrid`，两处不会再各改各的）；
概览卡未就绪给骨架值条、**失败给「—」而不是 0**；类型分布 / 活动热力 / 最近活动各自带骨架，不再整块溶解。
「空 / 失败」态也不再整页居中：它只占活动区那三块的位置，概览与贡献墙照常显示 ——
日历明明已经拿到、却因为事件流为空而整屏报错，这个组合是不成立的。

③ 「最近活动」接真：原先 `ActivityEvent` 只有 type / repo / detail / createdAt，`detail` 在解析层就拼好，
显示层拿不到任何**真实对象**。实测 `GET /users/{login}/events/public`：最近 30 条里 **28 条是 `PushEvent`**，
而 events 接口把 PushEvent 的 payload 裁剪到只剩 `repository_id` / `push_id` / `ref` / `head` / `before`
（没有 `size`、没有 `commits` —— 提交数与提交信息**拿不到就是拿不到**），
逐条渲染必然是一屏几乎一样的「推送到 main · \<sha\>」，观感就像占位假数据。现在：
- 事件模型补上 `actor` / `actorAvatar` / `title` / `branch` / `head` / `pushCount`（真实字段能取到的都不再丢）；
- 行渲染改为「actor 真头像 + 右下角事件类型角标（Material 图标 + `TintRole` 语义色，换掉
  `⇧ ＋ − ★ ⑂ ◉ ⇄ ◆` 这些灰 emoji 字形）+ 仓库名（粗体）+ 真实对象标题（PR / issue / 发布 / 复刻 / wiki）
  + 相对时间」，并且**整行可点 → 进对应仓库**（此前这一页只读不跳，「看得到去不了」）；
- **折叠连续推送**：同仓库 + 同分支 + 同一天（本地时区）的相邻 `PushEvent` 合并成一行
  「推送到 main · 8 次推送 · 最新 77d563e」（GitHub 自己的 feed 同样是合并的）。
  只在**相邻**条目之间折叠，不跨天、不跨其它事件 —— 跨天合并会把「今天 3 次 + 昨天 5 次」写成 8 次，
  那是在编造事实；组内保留最新一次的 sha，它是这一组里唯一有定位价值的东西。
  顺带修掉 `IssuesEvent` / `PullRequestEvent` 把 `opened` / `closed` 原样拼进中文句子的问题（走 `eventAction` 映射）。

④ 钉子：`ActivityFeedTest` 12 例 —— 折叠口径 7 条（同天折叠并保留最新 sha / 不跨天 / 不跨分支 / 不跨仓库 /
中间夹其它事件即断开 / 单条原样 / 无分支信息仍按仓库与天折叠）+ payload 映射 5 条
（推送取到 actor·分支·短 sha 且**不编造标题**、PR 取真实标题与编号、星标无标题无分支、按事件 id 去重、
`ERROR:` 与空串 → 空列表）。测试用「本地时区固定时刻」而不是 `now - 24h`，跑在午夜附近也不会随机失败。

⑤ 边界（本轮未做）：切走再切回「动态」Tab 时，`events` / `calendar` 是 `remember`（普通状态，
不进 `TabSwitcher` 的 `rememberSaveable` 槽）—— 页面重建、重新走一次 cache-first，
所以仍会闪过一帧分区骨架（现在是淡入，不再是硬切）。真要「重入零骨架」需把活动状态上提到 `ProfileScreen`
（与仓库列表同样的做法）或加内存快照，属于下一轮。
**未做真机渲染验证**：本轮改的是动画与版式，需真机复验「首次进入只剩一层加载态」与「折叠条数 = 当天推送组数」。

### 1.0.48

个人主页三处加载态从「一行灰字」换成骨架屏：**热门仓库区 / 仓库页 / 动态页**。

① 原先三处都只是 `Text("加载中…")`（热门仓库与动态页是 13sp 的居中/左对齐灰字，仓库页是整屏居中）。
代价有两条：**看起来像卡死** —— 静态文字没有任何进度感，而这恰恰是「加载态该不该动」的差别
（同一段等待，呼吸的占位块与死住的灰块主观时长差很多，这条结论早写在 `ui/theme/Motion.kt`）；
以及**内容到达时整段跳一次** —— 占位是 1 行文字，内容是 N 张带边框的卡片（热门仓库 / 仓库页）
或「概览三卡 + 贡献墙 + 活动热力图 + 事件行」（动态页），两者高度差着几百 dp。
动态页尤其吃亏：首屏要等 `/user/events` 最多 3 页（300 条硬上限）+ GraphQL 贡献日历，
是三个页面里等待最久的，而它此前给出的信息量最少。

② 做法：骨架**照真实结构 1:1 复刻**（同样的圆角 / 边框 / 内边距 / 行高），并复用既有的微光规格 ——
屏幕级 `ProvideShimmer` 只包一层（整屏骨架共用一条动画），占位块用 `skeletonBlock`
（alpha 留到绘制期读，只失效绘制、不触发重组；这两条正是 `Motion.kt` 里点名的两个坑）。
新增 `RepoCardSkeleton`（热门仓库区与仓库页**共用一份**：两处真实卡片本来就是同一个 `RepoCard`，
各写一份的话改卡片尺寸只会改到其中一处，另一处就开始在数据到达时跳）、
`ProfileActivitySkeleton`（顺序与尺寸对齐真实内容：概览三卡 → 贡献墙 → 活动热力 → 最近活动）。
两处刻意的取舍：动态页骨架**不含「活动类型分布」** —— 那一段按数据非空才渲染，
放进骨架就是先给用户一个必然消失的区块；仓库页**保留搜索框与语言筛选**（不依赖数据的静态结构
照常渲染），只把下面的列表换成占位卡。

③ 占位数量取 3（`PROFILE_REPO_SKELETON_COUNT`）而不是「按屏高铺满」：
热门仓库区最多 4 张卡（`take(4)`），占位多于内容会出现「骨架比内容还长、数据回来页面缩短」的反向跳动。

### 1.0.47

远端可达性判定的完善 —— 「先没开 VPN 打开 App、之后再接入 VPN，远端还是打不开」的修复。
① 症状与两条独立成因（各自都能单独造成这个症状）：**连接池绑在切换前的网络上** ——
Rust 侧 `shared_http()` 此前是进程级 `OnceLock<Client>`（换不掉），池里的连接是在旧网络
（没梯子时那张）上握手完成的，默认网络换成 VPN 后它们既不可用也不会自己消失，后续请求继续
复用、一路超时；
以及**启动那次的负面结论被记账** —— 账号健康检查只在 `MainActivity` 启动时跑一次，没有 VPN 时
判出 `UNREACHABLE` 写进本地账号状态后无人复检，两个预取器（通知 45s / 仓库 60s）也把失败那次
记进了去重窗口。
② 判定口径（三档 + 四种跃迁）：`NetworkKind` 取 `OFFLINE / DIRECT / VPN`，档位按固定优先级算 ——
非 VPN 网络用 `NET_CAPABILITY_VALIDATED` 判「出得了网」而不是「有没有网」（Android 的「已连接」
只说明链路在），**VPN 网络则不看 VALIDATED**：真机 `dumpsys connectivity` 取证（OnePlus / Android 16，
FlClash：`NetworkInfo ni{VPN CONNECTED}` + `Transports: CELLULAR|VPN Capabilities: …&VALIDATED` +
`InterfaceName: tun0`）显示它的 VALIDATED 继承自底层网络，刚建链那一小段可能还没写上，
据此判离线会漏掉最该抓的 `VPN_UP`。
只有**网络恢复 / VPN 接入 / VPN 断开 / 换了一张网**四种跃迁才重探，同网的带宽与计费抖动一律忽略
（否则弱网下会反复重置连接池、反复打 `/user`）。VPN 优先于「换网」：挂 VPN 时网络句柄也会变，
原因记错会把「到底走没走 VPN」这条唯一的排障线索埋掉。
③ 改法：跃迁时做三件事 —— 丢共享连接池（新增 `nativeResetHttpClient` → Rust
`reset_http_client()`，两份进程级客户端 —— 共享 + 上传专用 —— 都从 `OnceLock` 改成可丢弃的
`RwLock<Option<Client>>`；在途请求各自持有引用计数，不会被掐断）、清两个预取去重窗口
（窗口在发起时记账，失败那次同样占着它）、重探账号。
监听用 `registerDefaultNetworkCallback`（含 `onCapabilitiesChanged`），350ms 防抖躲开 VPN 建链期
「旧网已断、新网还没 VALIDATED」的中间态，`MainActivity.onResume` 再兜一次（后台回调没投到时不该
带着旧结论）。只用已声明的 `ACCESS_NETWORK_STATE`，不新增权限。
④ 钉子：`ReachabilityPolicyTest`（15 条，逐条锁死四种跃迁与三类不该触发的形状）、
`core/src/api/client.rs` 的两条 Rust 单测（重置后确实重建 / 重建出来的客户端仍能发请求，走回环
服务）、`JniSignatureTest` 自动把新增的原生函数与 Kotlin 声明钉在一起。
⑤ 边界：直连的大陆网络本身是 `VALIDATED` 的（能上国内站点），「这张网到不了 GitHub」没有任何
本地系统事实能表达 —— 所以 `DIRECT` ≠ 远端可达，真正的可达性仍由 `GET /user` 给出，本机制保证的
是**换了路径就重新判定**。设计记录见 `docs/specs/reachability-design.md`。
**未做真机安装验证**（debug 包与已装正式包签名不同），待用真机复验：没开梯子启动 → 接入梯子 →
远端应在一个探测周期内恢复。

### 1.0.46

README 长文被截断的修复 —— 正文高度上限 `20_000` → `200_000`（真机截图：正文停在一张表格中间，
紧接着就是「许可证 License」）。
① 根因：正文 WebView 的高度被钳在 20,000 dp，而那里的注释写着「超出部分由 WebView 内部滚动」——
该前提在 `LazyColumn` 的 item 里不成立（`RepositoryOverviewScreen.kt:240`）：外层列表把这一项滚过去
就直接进下一节，**被钳掉的后 2/3 在 App 里没有任何入口**。这也与架构约定相反 —— 正文 WebView 的
高度就等于整篇内容高度，滚动交给外层原生列表（`docs/specs/modules-design.md` §1）。
② 取证：GitHub 侧返回的是**完整** HTML（旧 README 187,503 字节、结尾就是「（待补充）」）；
截断点落在这份 HTML 的 34.2%（字节 64.1K / 187.5K），且是**竖直切断**（高度被钳的特征；HTML 被截断
会露出未闭合标签的乱码）；按同一套 CSS（16px / 1.5 行高、正文宽 ≈438 CSS px）模型估算该文档高
≈44,700 px，20,000 px 落在 45%，与实测吻合（表格行被挤成单字列后更高，故截断点更靠前）。
顺带排除：首列被挤成每行两三个字不是本 bug，是 GitHub 表格 CSS 在 ~470px 窄视口下的行为
（同一行里有长标识符 `EditorPalette.VisibleSurfaceIds`）。
③ 改法：上限只用于挡「脚本取到离谱的 `body.scrollHeight`」这类异常（200k px ≈ 700KB 级中文正文，
正常 README 碰不到），注释换成真机现象 + 架构约定，免得下一个人又把它当显示上限。
④ 副产物：1.0.45 的自述文件拆分已把本仓库 README 从 ≈44.7k px 降到 ≈14.9k px，落回原上限之下 ——
症状在本仓库先一步消失，但上限本身仍是地雷（别人的长 README 照样在中间被切）。

### 1.0.45

自述文件拆分 + 版本变更记录收敛（纯文档，无代码行为变化）。
① 根 `README.md` 1154 → 405 行：模块族（翻译 / 下载 / 图片查看器 / 编辑器 / 作业日志 + 运行轮询）、
界面规格（配色与弹层 / 深色主题 / 动效）、页面重绘（运行详情 / 发布 / 消息）三类深度设计记录迁入
`docs/specs/modules-design.md`、`ui-design.md`、`screens-design.md` —— 正文逐字搬运，只去 emoji、
加章节号、把仓库根相对链接改成相对本文件的链接；README 每节只留结论摘要 + 指向，并新增
「📚 文档导航」表。
② 失效引用一并改指：`ReleaseNotesEditor.kt` / `ReleaseEditParts.kt` 注释里的 `README.md:804-821`
（拆分前就已指错，它指向的是动效表而不是消息卡片的「行首恒为 32dp 识别槽」）、
`prototypes/release-redesign.md` 的三处行号引用、`settings-design.md` 的两处「见 `README.md` 某板块」。
③ `version.properties` 的逐版注释（1.0.22 ~ 1.0.44 共 23 条）与 `versionCode` 流水（129 ~ 146）
收敛成 `docs/specs/VERSION-NOTES.md`，文件 244 行 / 26.8KB → 66 行 / 6.6KB，只留格式契约 + 指向 +
3 个经典示例（分点式多项修复 / 带实测数字的功能型 / 工程治理型各一）；两个读取方
（`app/build.gradle.kts` 的 `Properties.load()`、`tools/build/assemble.sh` 的 `grep '^versionName='`）
都只看键值行，取值不变。
④ `docs/README.md` 的收纳地图与索引同步，并补登一直漏掉的 `prototypes/release-redesign.md`。

### 1.0.44

设置页账户卡的头像 —— 圈选处只有字母，没有真实图标与圆形裁切。
AccountRow 原来把「写死的灰底首字母 Box」当成了头像实现：它**根本不接头像地址**，
于是 AvatarCache 的本地缓存与账号的 avatar_url 都在，设置页也永远只显示一个字母
（首字母本是 theme/Avatar 内部「图还没到」的兜底层，而不是账户卡的实现）。
现在账户卡改走统一 Avatar：本地缓存优先 → 缺缓存回落 avatar_url 网络图 → 两者都
没有才首字母，并按 CircleShape + ContentScale.Crop 裁切（与首页 / 个人页 / 账号
管理页同一个组件）。
顺带补上「地址从哪来」：新增 Account.avatarUrl —— 快照 avatar 缺失时回落会话里的
user.avatar_url。老版本单会话迁移成的账号只有会话、没有快照，裸 avatar 会让它们
连预热都跳过（MainActivity 同源改用 avatarUrl）。账号管理页也改用同一属性。
SettingsSpecTest 补两条钉子：账户行必须用 Avatar 且不许再写死首字母；设置页必须
把 account.avatarUrl 传给账户行。新增 AccountAvatarTest(6) 钉住回落契约。

### 1.0.43

星标 / 关注 / 复刻三按钮的判定规则 + 网页会话通道 ——
① 判定：这三个按钮此前**没有任何判定**，三个都无条件跳同一个列表页（星标者 / 复刻 /
   关注者），既没有「已星标」的双向态，也不区分仓库是不是自己的。规则现在收进
   RepoRelationRules（纯函数 + 22 条单测，因为每一处误判都有真实后果：复刻判错会对
   别人的仓库弹「不能复刻自己的仓库」，星标判错则是点一下**给别人的仓库取消了收藏**）：
   星标点击 = 收藏 ↔ 取消收藏（乐观更新 + 失败回滚）、长按 = 星标者；
   关注点击 = 四档 Watch 面板（Participating and @mentions / All Activity / Ignore /
   Custom）+ Watch settings、长按 = 关注者；
   复刻按持有者分流 —— 自己的仓库进复刻列表，他人仓库走网页版流程（账户 / 命名 /
   重名校验 / 只复刻默认分支），组织禁用则置灰并说明原因。
   判定输入三档：网页 sidebarAbout（最准，一次拿全，还带回 forkabilityError 与 Custom
   的可勾选项）> GraphQL（一次查询）> 本地 owner==login（**零网络**）。本地那档放在最前，
   所以自持仓库的按钮不会「先错后改」。
② 加载效率：仓库信息原先在仓库页与项目页各取一次 —— 同一个进入动作发两个一模一样的
   GET /repos/{o}/{r}，而头部与三个计数都在等它。现在统一由仓库页取、项目页消费；
   parseRepoInfo 补回一直存在于响应里却被整段丢弃的 allow_forking / fork / parent；
   关系态按账号键缓存 5 分钟（星标/关注成功后立即回写，不留自相矛盾的缓存）。
   刻意**不**进 RepoPrefetcher 的预取包：页面进入时本就会判定，预取只会把它变成两次请求。
③ Custom 通知：仓库级的自定义通知没有公开 API（REST 的 subscription 只有 subscribed /
   ignored 两个布尔，GraphQL 的 SubscriptionState 只有三态，都表达不了「只收 Issues +
   Releases」），网页走内部端点 POST /{o}/{r}/notifications/subscribe。而 OAuth token
   到不了那里 —— 实测 github.com 的 HTML 与内部端点只认浏览器会话 Cookie，带
   token / bearer / Basic 一律 302 到 /login。所以新增嵌入式 WebView 登录一次
   （GithubWebLoginScreen）、导出 Cookie 存本机（GithubWebSession），网页请求交给 Rust 侧
   （core/src/api/web.rs），并照搬网页版的防伪头（X-Fetch-Nonce 每次页面加载都变，
   故每次写入前先取一次页面）。其余能力一律走官方 API —— 只有点 Custom 且没有会话时
   才引导登录。
④ 列表：星标者 / 关注者原先把 403 / 404 / 限流 / 真没人一律折叠成「暂无内容」。GitHub
   2026-07 起已把 /stargazers 与 /subscribers 限制为管理员与协作者可见，这个歧义从
   理论问题变成了常态 —— 现在按状态码分别给话，并补上 per_page=100（此前默认 30 条
   且没有翻页入口）。

### 1.0.42

旧版日志的兼容清理 + 沉浸式翻译的两条渲染规则 ——
① 旧版（≤1.0.40）把日志直接写在 files/ 根下、不带轮转，从旧版升上来的机器上会留一个
   **孤儿文件**：新路径是 logs/<日期>/branchbase.log，而启动清理只扫 logs/ 下的目录，
   扫不到它。现在启动时单独清一次 —— 没有就跳过，删不掉也不抛（几 KB 的残留，
   绝不该影响启动）。
② 沉浸式翻译的渲染规则两处：
   · 标题（h1–h6）的译文原来插成兄弟节点，会落在 markdown `h1 { border-bottom }` 的
     横线**下面**，看着像「引用了下一段」的卡片、跟标题脱开（真机截图就是这样）；
     改成插进标题内部 —— 标题只接受短语内容，所以用 span（`<div>` 进 `<h1>` 与
     `<div>` 进 `<ul>` 是同一类非法结构），并按标题层级缩放字号、去掉卡片底色。
   · 语言切换行（`English | 中文`）不再翻：它是导航不是正文，翻出来是「英文| 中文」
     这种四不像（截图里正有一条）。语言名写成 `[Ee]nglish` 双写而不是加 `(?i)`，
     因为同一条正则要同时在 Kotlin 与页面脚本（`new RegExp` 不带 flags）里跑。

### 1.0.41

慢帧日志的「开关 + 轮转」与两处首帧瘦身 —— 上一版把仪表装上了，这一版让它可以
长期开着、并且开始还债。
① 开关：慢帧日志的默认值改由**编译通道**决定（正式编译默认关、Beta 默认开，
   见 app/build.gradle.kts 的 FRAME_WATCH_DEFAULT），设置页
   「关于与诊断 → 慢帧日志」随时可覆盖 —— 正式版用户要抓一次卡顿也能开。
② 轮转：日志改成 `logs/<北京时间日期>/branchbase.log`，跨过北京时间 00:00:00
   换新目录，**旧目录直接丢弃**（原来磁盘上无上限，log-redesign 文档记着这条）。
③ 仪表漏洞：慢帧明细原来 400ms 限流，把「更慢的那帧」也一起吞了（真机出现
   「小结 240.9ms、明细最大 179.8ms」）—— 现在限流窗口内**只要这一帧更慢就照记**。
④ 首帧瘦身（真机实测「进入设置页」43~89ms、「进入日志页」58~99ms，其中重组≈绘制）：
   设置页 `Column + verticalScroll` → `LazyColumn`（首帧只组合可见的那几组）；
   日志页删掉一次多余的整页重组（`remember` 初始化之后又补拉一次 `LogManager.all()`），
   统计徽章改成一次遍历；「原始日志」从「1000 条 join 成一个大 Text」改成逐行惰性。

### 1.0.40

慢帧守望（帧级定位）—— 「切页那一下卡了多少毫秒、卡在哪一段」原先只能靠开发者选项 +
`adb shell dumpsys gfxinfo <pkg> framestats`，而那条路有三个绕不开的硬伤：只保留最近
~120 帧（90Hz 约 1.3 秒，人去点永远慢半拍）、dump 自身跑在被测量进程的主线程（测一次
就自己造一根柱子）、取数要另开终端/分屏且还得防着被测 App 被 cached app freezer 冻住。
现在把测量搬进 App 自己：Window.addOnFrameMetricsAvailableListener 取每帧分段
（等待/输入/动画/布局/绘制/上传/下发/交换，与 framestats 同源），≥32ms 记一条明细、
每 60s 记一条小结，页面归属取「最近一条 UI 日志」（不另造「当前页面」状态）。
首轮实测已能直接定位：进设置页 95.9ms（动画 43.7 / 绘制 45.5）、进日志页 113.8ms、
仓库详情页数据到达后一帧 198.1ms（动画 129.0）—— 而布局恒 0~2ms、输入恒 0，
说明瓶颈在「首次组合 + 首次绘制」，不在布局。
顺带修掉两处：日志写盘其实是**覆盖写**（FileAppender 用了截断模式，磁盘上永远只剩最近
一批，而「设置 → 日志 → 导出 .log」读的正是这个文件）；慢帧日志把自己当成了「页面」，
日志里套了五层。
设置页：行首图标与右端的值改按整行垂直居中（它们被内层 Row 的默认 Top 对齐顶到行顶，
说明折行后尤其明显）。

### 1.0.39

发布页重排 + 附件（导入即落盘、先建 release 再传资产）—— 编辑页原来是 694dp 的垂直预算
（类型 81 + 三个字段 176 + 固定 200dp 的描边正文盒 233 + 「设为最新」卡片 56 + 间距 112），
一屏装下就没地方放附件，而 release 的一半价值在资产。现在压到 ≈360dp：标签 / 标题 /
目标分支**并成一行**（标签是等宽 chip、分支是行尾只读 chip）、「设为最新」并进类型行、
统计挪进分组头、分组之间改用 1dp 发丝线。「更新内容」去掉包裹框，改用编辑器的**行标识槽**
（无框 + 行号 + 当前行底色 + 定宽标记列，3 行起步自动增高），折行对齐走 TextLayoutResult
（一条逻辑行折成多行时行号只占第一视觉行），「槽宽恒定」连槽内部也遵守。
「生成说明」不再整段覆盖：生成的行在行号槽里带 + 号、行底淡绿，可「全部保留 / 丢弃」，
于是那个「替换现有更新内容？」的确认弹窗也删掉了。
附件走**先落盘再上传**：系统选择器给的 content:// 是临时凭据，拿到就复制进
getExternalFilesDir/release-uploads/{owner}/{repo}/{tag}/（**不用 cacheDir**——系统低存储会清它），
表单与附件清单以 JSON 落在 filesDir/release-drafts/，改字段 900ms 防抖落盘，未发布保留 7 天。
发布是两步（资产必须挂 release 上）：先 create/update 拿 id，再逐个 upload_release_asset 到
uploads.github.com（与 API 不同源，不复用 base_url；name/label 走 URL 编码）；拿到 id 后记下来，
附件失败重试不再 create（同名 tag 会 422 already_exists）。上传目前非流式（reqwest 的 stream
feature 会连带 wasm-streams，离线取不到），改为读进内存 + 256MB 上限，升级路径写在 post_binary 注释里。

### 1.0.38

设计文档重组：草图与结论分家 —— 原先 .gitignore 里 /design/（草图）与 /docs/（文档）
是**一起忽略**的，代价是 core/src/html/mod.rs 长年引用一份 docs/html-parser-design.md，
而它从未进过版本库、丢失后无从找回（注释指向不存在的文件比没有注释更坏）。
现在按「这个结论半年后还有效吗」一刀切：/docs/ 入库（README 收纳规则 + specs/ 重点
文档），/design/ 仍只放草图、永久不入库。补回 html-parser-design.md（按 matcher.rs
实际行为与 16 个单测写，章节号对齐代码里既有的 §3 / §5.1 两处锚点）；NAVIGATION-NOTES
与 BUILD-NOTES 从根目录移入 specs/；三份原型「说明与落实方案」抽入库副本到
specs/prototypes/（草图本体仍不入库，另加互指）。README 项目结构块与文档政策随之重写。

### 1.0.37

分支同步入口从概览页整行收进底部栏 ⋮ 气泡 + 模式按预览结论禁用 —— 原先那条入口是
描边整行（border + 圆角），紧跟在星标/复刻/关注下面、压在 README 之上，信息量为零
（一行字加一个「›」）却和三个主操作同级；而且**不看权限**：别人的仓库也照显示，
点到最后一步才失败。现在收进 bubbleEntries（与 PR/提交/设置同级），只在 canPush 时
出现；它是盖在 Tab 上的全屏页而不是 RepoPage（塞进去会让 when(page) 多一条走不到的
支路），所以气泡项拆成 BubbleEntry.Page / Action 两支。同步页三种模式也不再「永远
可点」：ahead=0（目标已是最新）三种全禁 + 主按钮置灰并改写文案，behind>0（目标有独有
提交）时仅快进必被服务端拒绝（422）故禁掉；判定抽成纯函数 syncModeAvailability
（新增 BranchSyncModeTest 5 例）。主按钮文案带上动作与后果：合并/快进 A → B、
覆盖 B（丢弃 N 个提交），取代笼统的「同步 A → B」。

### 1.0.36

工作流通知点进去能落到「这一次」run 的详情页 —— 此前只落到仓库的「工作流」tab。
根因：GitHub 不在通知里给 run id。CheckSuite 的 subject.url 常常直接是 null
（社区讨论 #158253），有值时也是 .../check-suites/<id> —— check 域编号，当 run id 用
会打开编号巧合的无关 run（1.0.29 修过一次）。唯一能用的是标题：
"<workflow> workflow run[, Attempt #N] <status> for <branch> branch"（与 gitify 从真实
报文反推的正则一致）。点击时补一次 /actions/runs?branch= 配对，用 run 的 updated_at
与通知时间最近来收口；名字/分支/attempt/结论任一不符、或时间偏差超 24h 就退回列表
并 Toast 说明，绝不猜编号开一个「看起来像」的无关 run。三个纯函数有单测（18 例）。

### 1.0.35

消息页左右滑都能标记已读 —— 对齐 DioHub - Dev（basic_notification_card.dart 的
Slidable 左右两个 ActionPane 都是 Mark as read）。此前只放开 StartToEnd（右滑）：
从哪一侧滑纯粹是握持习惯，左滑在单手 / 手小的场景下更顺手，没有理由拒绝。
背景提示按方向贴边 —— M3 1.4 的 backgroundContent 签名是 @Composable RowScope.() -> Unit，
**不带方向参数**，方向从 SwipeToDismissBoxState.dismissDirection 读：从左往右滑时内容右移、
露出的是左边缘，提示就该靠左。多选态仍一律禁用滑动，未读行才可滑。
design/messages-redesign 原型的滑动手势同步支持两个方向（translateX 允许负值 + 贴边镜像）

### 1.0.34

消息页「长按 → 单条动作面板」状态机修复 + 批量失败回滚范围修正
① 接线缺陷：NotificationList 的 onLongClick 一直传 enterSelection，而 sheetTarget 只有
   多选条「更多」会赋值 —— 快捷动作面板的非多选分支从引入起就是死代码，长按退化成
   「进多选」，README 与 design/messages-redesign 原型描述的「长按出单条动作」从未生效。
   改为 onLongClick = { sheetTarget = it }，多选回到它该在的位置（面板末尾的选项）。
② 修好接线后暴露：面板里的单条「标记完成」只写本地、从不调 markNotificationDone
   （该接口此前只在批量路径里用过），刷新后条目原样回来。抽出 markDoneRemote
   （本地乐观归档 → 远端 DELETE → 失败按 id 回滚）并接到单条路径上。
③ 批量失败改为**只回滚失败的条目**：整批回滚会把远端已改成功的条目在本地撤回，
   用户看到「批量失败」、刷新后一部分又自己变已读，而这正是「回滚为了跟远端一致」的
   反面。规则抽成纯函数 bulkRollbackTargets，新增 NotificationBulkRollbackTest 6 例。
   失败项保留选中以便直接重试；静音不回滚也不给撤销（本地无可见状态可退）。
④ 退出多选不再重置 bulkRunning：批量是仍在跑的远端长任务，旧实现把两者绑定，
   退出后能再触发一批（两批并发打远端，120ms 限流间隔失效），且旧批次收尾的
   exitSelection() 会清掉用户新选的一批。现按 bulkSeq + 选择是否被改过决定收尾。
⑤ 点击已读消息不再重复发远端写请求（GitHub 同秒写请求有二级限流）。

### 1.0.33

消息页通知卡片改成「元信息行在上」的信息顺序 —— 参考 DioHub - Dev
（github.com/namanshergill/diohub，view/notifications/widgets/notification_cards/
basic_notification_card.dart）：仓库 #号 · 原因 · 时间提到标题**上方**，标题居中，
评论预览（作者：正文）下移到卡片底部。原顺序是「标题 → 预览 → 元信息」，不看标题就不知道
这条是什么，可看了标题又不知道来自哪个仓库、多久之前 —— 只能一路扫到卡片底部再折回标题；
把定位坐标（哪来的、什么时候）提前后，元信息一行为标题提供了阅读语境。预览仍是「要不要
点进去」的关键依据，只是排在标题之后，标题永远第一眼看到。骨架屏（NotificationSkeleton）
的占位条顺序同步换成「先短后长」；design/messages-redesign 原型（app.js / style.css）
一并同步。未读竖条、识别槽、多选语义一律不动

### 1.0.32

发布（Releases）三档性质与三个页面重绘 —— 对齐官方语义：正式发布 / 预发布 / 草稿
三选一（此前是两个含义重叠、还能同时勾上的开关，「最新发布」在 App 里根本没有概念）；
列表「新建发布」从占满整行的描边空框收成标题行右侧的 30dp 圆形「+」，条目改为
tag 胶囊打头 + 性质徽章 + 名字 + 元信息；「最新发布」走权威端点 /releases/latest 判定
（列表接口每条不带 latest 标记），拿不到才退回近似规则；详情页去掉「一个附件一个框」
改用分隔线分区；编辑页去掉四个 OutlinedTextField 的四边框改「标签在上 + 一条底线」，
只给多行正文保留描边，并补「生成说明」（官方 generate-notes）、补 make_latest 开关。
底层：GitHubApi 的 create/update_release 新增 make_latest，新增 latest_release_id 与
generate_release_notes（各带 JNI 导出）；新增 JniSignatureTest 把 83 对
external fun ↔ Java_* 逐参数逐类型钉住（JNI 签名对不上编译期查不出来）

### 1.0.31

常态图标去灰 —— iconPrimary 浅 #525560→#000000、深 #B1BAC4→#FFFFFF。图标是图形
不是长文本，21:1 的对比不构成阅读负担，而原来的中灰在浅色下有种「发灰、像没加载出
颜色」的观感；只换常态图标：iconSecondary（次要/禁用）留中灰以保留「未选中」语义，
正文与次级文字、border 描边、灰底填充一律不动，52 处 Primer.IconPrimary 调用点未改

### 1.0.30

运行详情页卡片流重绘 —— 三段式头部（补 head_commit.message）+ 进度条；JobRow 改
JobCard（修掉嵌套点击，补 runner 字段显示与步骤相对时长条）；按状态过滤分段控件；
注解按 check-run 名字归回任务卡片；产物行尾下载（新解析 archive_download_url）；
顶栏补刷新/浏览器打开/重新运行/复制链接；JobDetailContent 升级为日志页 JobLogScreen
（步骤 chips + 搜索 + 过滤 + 分组折叠 + LazyColumn），运行详情页删掉 200 行日志小窗；
五件共享小件抽成 DetailScaffold.kt

### 1.0.29

运行中的工作流改为「轮询状态、不轮询日志」—— 新增 RunPollPolicy（间隔阶梯 5s→15s→30s、
计费网络只降速不停止、失败退避封顶 60s、运行中+前台才轮）；运行详情页按
newlyCompletedJobIds 差分在 job 定稿时抓一次日志并自动补进界面；运行历史页与作业详情页
增加「回到前台强制对齐」；修正「任务没结束时取不到日志」被显示成「加载失败」的问题；
顺带修 CheckSuite/CheckRun 通知把 check id 当 run id 深链到无关 run 的 bug

### 1.0.28

修回上一轮收敛造成的对比度回退 —— 色板补 dangerText（浅 #9E1C24 / 深 #F85149，
此前只有 successText/accentText/warningText，红色文字无处可取、只能拿填充色 Red500 顶），
4 处危险文字改用它（账户状态·异常 4.00→6.94、安全警报标题 3.61→6.26、StatusChip D、
决策「危险」标签）；StatusChip A 的 Blue600 改 AccentText（深色 4.02→6.60）；
决策「推荐」标签的文字改 SuccessTextStrong（2.88→5.9）；与 WarningSurface 搭配的
警告文字统一 WarningTextStrong（4.38→5.6）；新增 ThemeContrastTest 钉住
「浅色下文字角色必须比对应填充色更深」等三条

### 1.0.27

主题彻底收敛（第二轮）—— 架构落地时漏掉的 20 余处零散硬编码按「深色下读不读得出来」
清完：差异行未变更正文（分支对比 / 提交差异）、预发布与草稿徽章、账户状态胶囊、
安全警报横幅、工作区提示条、「推荐 / 已星标 / 分配给我」等彩色块；
工作流状态点与搜索页 PR 徽章不再照抄浅色色板的 success/danger/warning；
色板补 doneSurface（Primer.PurpleSurface），胶囊描边统一 Primer.Border；
新增 ThemeConvergenceTest：浅色专属取值只许出现在色板里 + 禁止 6 位十六进制
（抓到决策卡片「推荐」标签 Color(0xEAF9F0) alpha=0 实际透明的写法）

### 1.0.26

作业日志获取抽成独立模块 :joblogs —— 取数（同一 jobId 的并发调用合并成一次下载）/
分组切段/内存与磁盘缓存都收进模块，日志来源与缓存由 :app 注入（JobLogWiring.kt）；
运行详情页与 Job 详情页不再各写一条取日志路径，且在仓库页共用一个 store；
工作流日志块配色改为跟随主题（CodeSyntax.CodeBg + Primer.TextPrimary，
去掉写死的 0xFF24292F / 0xFFF6F8FA）；新增 WorkflowLogThemeTest 钉住这两条

### 1.0.25

文件页只读预览正文色改跟随主题 —— 去掉硬编码的 Color(0xFF24292F)（浅色主题取值，
深色下是「深灰字压深色底」），改用 Primer.TextPrimary（与搜索页代码块同一约定）；
新增 FileViewerThemeTest 钉住本页不得再出现该硬编码

### 1.0.24

文件页编辑态接上 :editor 代码编辑器 —— 去掉 Material 输入框的一圈方框，
改为等宽 + 行号 + 无边框，底色与只读预览同一块（CodeSyntax.CodeBg）；
编辑器外观契约落到 applyEditorAppearance，表面类色板补齐（滚动条轨道 / 行号面板 /
括号配对 / 选中浮窗）；新增 FileEditorWiringTest 把这条接线钉在源码上

### 1.0.23

关于页改紧凑单屏版 —— 图标 52dp 与名称同行 + 右侧校验结论胶囊；
信息行上下留白 12→8dp 并补分隔线，拆成「版本信息 / 构建校验」两张卡片；
原独立校验横幅去重（结论进胶囊、说明并进卡片）；总高约 780→590dp

### 1.0.22

返回键消费链路补全 —— PageSwitcher 补上下发 LocalPageActive（退场旧页不再抢返回键）；
个人页下级页 / 本地仓库决策页 / 任务详情 / 文件页决策页与编辑态 / Issue 评论编辑态
各自消费返回键（与页面内返回箭头同一条路径）；Git 悬浮球绑定「本地仓库（Git）」模式

---

## 三、`versionCode` 流水（176 → 129）

`versionCode` 每次提交前递增：**有多少次提交变更多少次版本码**（一次发布也算一次提交）。

> 更早的版本码没有逐条留存，流水从 **129** 开始。

- **176**：「刚发布的版本在 App 里看不到附件」—— 补两条断点。①App 读的列表接口对刚发布的
release 会返回空 `assets`（窗口约 1.5~2.6 小时，单体接口与网页始终正确），改在报空时回源单体
接口补齐（`releasesNeedingAssetBackfill`，只挑空的最多 3 条）；②CI 产物原本下载的是 zip 而
App 无法解压（三个模块 `ZipFile` 零命中），产物拆成「一个 APK 一个 artifact」+ 保留期 30 天 +
`if-no-files-found: error`，App 侧新增解压安装（只在恰好一个 APK 时动手，两个以上不猜，
zip-slip 靠不采纳条目路径天然不成立）。新增 17 例钉子（`ReleaseAssetBackfillTest` 6 +
`ArtifactInstallTest` 11）（一次提交，故 +1）

- **175**：首页 / 个人页三 Tab / 设置页的描边改为纯黑（**仅浅色**）—— 新增 `Primer.BorderEmphasis`
角色（浅 `#000000` / 深 `#30363D`）承接 17 处引用（首页 4 / 个人页 11 / 设置页 2，设置页原为 `Gray200`）；
`Primer.BorderControl` 浅色 `#8B8E99`→`#000000`（深色仍 `#6E7681`）；**深色板逐位未变**
（纯黑压深色底约 1.1:1 会消失）；文档追平三处（settings-design §九/§十三/§十四末、ui-design 边界说明、
原型「落地偏离」）；未新增钉子（`SettingsSpecTest` 的「无写死色值」已覆盖，描边取值目前无自动化守卫）
（一次提交，故 +1）

- **174**：文档按代码纠偏（35 处断言 / 11 处悬空引用 / 6 处幽灵路径）+ 根 README 瘦身 417→110 行、
迁出三份新规格；决策页与仓库页的演示数据换真值（PR 一条龙真提交、合并入口接线、预检与文案诚实化、
敏感扫描不可用改为拦下）；私有仓库 scope 探测 + 失败卡三出路 + 仓库级凭据（独立 prefs、排除备份、
设置页仅 PAT 模式）；日志导出包补 `report.md` 索引与锚点词典；新增 9 个测试类、修掉一个 34.9% 失败率的
flaky 断言（一次提交，故 +1）

- **173**：修日志页闪退 —— LazyColumn 的 key 用了 `时间戳 + 文案`（同毫秒同文案即撞车 ⇒ `IllegalArgumentException: Key … was already used`，自 1.0.42 起），改用 `LogEntry.seq`（进程内单调递增）（一次提交，故 +1）

- **172**：系统返回键的消费改成「默认」—— `PageSwitcher(onBack = …)` 必填 + 每格自动注册兜底，宿主一条穷尽 `when`（仓库页 15 个分支收成 1 个函数，顺带补上漏登记的网页登录页）（一次提交，故 +1）

- **171**：系统栏内边距审计 —— 补上 `JobLogScreen`（唯一漏掉的全屏页）+ 清掉两处误导性的 unused import + `SystemBarInsetsTest` 把「全屏页必须消费系统栏」钉成规则（一次提交，故 +1）

- **170**：修「重进已渲染过的仓库页会闪」—— 首帧快照 `RepoOverviewMemory`（数据本来就全命中，缺的是首帧有没有内容）+ 参与者头像走统一 `Avatar`（首帧同步直出，不再「蓝底→真图」）+ 启动标记改成进程级闸门（`resumeTick == 1` 拦不住页面重建）（一次提交，故 +1）

- **169**：`/user/events` 是 404（端点不存在）⇒ 事件源只留 `/users/{login}/events` 一条腿 + 修 1.0.65 自己引入的假启动标记（首页标记门控在 `resumeTick == 1`）+ WebView 首次创建计时可归因（一次提交，故 +1）

- **168**：修仓库页「一次进入跑三遍」（加载 effect 的键带了分支 ⇒ 数据发 3 次、骨架闪 3 次）+ 关系态先直出再复核（判定冷启 ~800ms 期间按钮不再空着）（一次提交，故 +1）

- **167**：修「失败留痕是死代码」（refresh 吞 ERROR → `?: break` 静默）+ 个人主页取数随页面取消（`refreshDetached`）+ 清扫不再删「刚过期」的行（`STALE_GRACE_MS`）+ 自述文件活过 LazyColumn 回收（`ReadmeViewHolder`）+ 启动段可归因（`LogManager` 落盘边界 + 7 个阶段标记 + 脚本 `^启动` 桶）（一次提交，故 +1）

- **166**：修「正在跑的工作流被判失败」（org.json 的 "null" 伪值：解析归一 + 判定改白名单 + 多状态显示）（一次提交，故 +1）

- **165**：日志页导出改成「打包 zip → Download/Branchbase → 系统分享」（含权限与失败弹窗）（一次提交，故 +1）

- **164**：修仓库页闪现性重建（默认分支未知时不再猜 main 取数）+ 头像首帧同步直出（进程内解码缓存）（一次提交，故 +1）

- **163**：账号探测防误判（只信 GitHub 形状的 401/403 + 复核一次 + 成功证据交叉验证）+ 原始响应入日志（一次提交，故 +1）

- **162**：设备档案进日志（机型 / 刷新率 / 动画缩放 / 不保留活动 / 省电模式 / debuggable）（一次提交，故 +1）

- **161**：翻译缓存键补变体维度（后端 / 模型 / 网关 / 占位符保护）+ 长度前缀防撞键 + 命中率可观测（一次提交，故 +1）

- **160**：修缓存键带秒级时间戳（日历永远 miss）与「失败源不留痕」（每次先等一次死请求）（一次提交，故 +1）

- **159**：`TabSwitcher` 改保活（切 Tab 零重建）+ `rememberPageResumeTick` 重新校验（一次提交，故 +1）

- **158**：本地缓存加进程内 L1（`MemoryCache`）+ 读路径不再写库 + 四种命中结果可观测（一次提交，故 +1）

- **157**：骨架 → 内容的替换收口成 `PlaceholderSwap`（延迟现身 + 骨架先退 / 内容再进 + 尺寸动画）（一次提交，故 +1）

- **156**：页面切换去抖（曲线换成两端零速 S 曲线 + 只让一页动 + 起播门控）+ 重页过渡台账 + 帧率基线设施（一次提交，故 +1）

- **155**：新增 `CrossfadeLayoutTest`（Crossfade 的 lambda 体必须是 Column）+ 统一活动区形状（一次提交，故 +1）

- **154**：修 Crossfade（Box）多子元素互叠导致的动态页错版（三处各套一层 Column）（一次提交，故 +1）

- **153**：修概览三卡因包进 Crossfade 而失去 weight 撑满的静默版式回退（一次提交，故 +1）

- **152**：修 events 接口顺序不可信导致的乱序显示与折叠被切段（解析出口 + 分页拼接后按时间倒序）（一次提交，故 +1）

- **151**：动态页「最近活动」接真 + 连续推送折叠 + 加载态收敛为分区骨架就地填充（一次提交，故 +1）

- **150**：个人主页三处骨架屏（热门仓库区 / 仓库页 / 动态页）（一次提交，故 +1）

- **149**：远端可达性判定完善（共享连接池可丢弃 + VPN 接入 / 断开与换网跃迁重探）（一次提交，故 +1）

- **148**：README 高度上限 20k→200k（长文被截断的修复）（一次提交，故 +1）

- **147**：自述文件拆分（README 1154→405 行，三篇入库）+ 版本注释收敛成 VERSION-NOTES.md（一次提交，故 +1）

- **146**：设置页账户卡头像改走统一 Avatar（真实图标 + 圆形裁切）+ 账号头像地址回落会话（一次提交，故 +1）
- **145**：星标/关注/复刻三按钮的判定规则 + 网页会话通道（Custom 通知）（一次提交，故 +1）
- **144**：旧版日志兼容清理 + 译文标题插入 / 语言切换行跳过（一次发布，故 +1）
- **143**：慢帧开关（通道默认值）+ 日志按天轮转 + 设置页/日志页首帧惰性化（一次发布，故 +1）
- **142**：慢帧守望（帧级定位）+ 日志追加写修复 + 设置行图标居中（一次发布，故 +1）
- **141**：发布页重排（694→360dp）+ 附件导入落盘/上传资产（一次提交，故 +1）
- **140**：设计文档重组：/docs/ 入库、/design/ 只放草图 + 补回丢失的 html-parser-design（一次提交，故 +1）
- **139**：分支同步入口收进 ⋮ 气泡 + 模式按预览结论禁用（一次提交，故 +1）
- **138**：工作流通知深链到具体 run（一次提交，故 +1）
- **137**：左右滑已读（一次提交，故 +1）
- **136**：长按状态机接线修复 + 批量失败回滚范围修正（一次提交，故 +1）
- **135**：消息卡片信息顺序对齐 DioHub（一次提交，故 +1）
- **134**：发布（Releases）三档性质与三个页面重绘（一次提交，故 +1）
- **133**：常态图标去灰（一次提交，故 +1）
- **132**：运行详情卡片流重绘（一次提交，故 +1）
- **131**：运行中改为轮询状态 + 定稿抓日志 + 回前台对齐（一次提交，故 +1）
- **130**：补 dangerText + 修回 5 处对比度回退（一次提交，故 +1）
- **129**：主题彻底收敛（A+B+C 全量收角色 + 两道源码级钉子）（一次提交，故 +1）

---

> **相关文档**：[`BUILD-NOTES.md`](BUILD-NOTES.md) §二（版本号体系 / 标准版本号 / APK 命名）·
> 根 `README.md` 的「🔖 版本号规范」· 收敛前的原始注释：`git log -p version.properties`。
