<!-- 来源：根 `version.properties` 的注释块（2026-09 收敛）。注释正文逐字保留，只做了三件事：
     去掉行首的 `#` 注释前缀与对齐缩进、每版提成 `### <版本号>`、按版本从新到旧重排
     （原文件是追加写，1.0.40–1.0.44 排在 1.0.22 之后；1.0.45 起按新流程直接加在顶部）。 -->
# 版本变更记录（`versionName` / `versionCode` 逐版说明）

`version.properties` 现在只留格式契约 + 写法样板（3 个经典示例）；
**每一版改了什么、为什么这么改**都在这份文档里 —— §二 `versionName` 条目（1.0.22 → **1.0.103**）
与 §三 `versionCode` 流水（129 → **205**）。

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

## 二、`versionName` 流水（1.0.103 → 1.0.22）

### 1.0.103

**Git 模式阶段 5（UI 主干）：合并决策页 / 冲突弹窗 / 冲突详情页 / 面板的合并中状态 —— 合并这条路走通了。**

① **入口是决策页，不是浮层里的一枚按钮**：合并在 §6.1 的划分里是「有后果的动作」
（会把对方的内容写进工作区、可能留下一堆冲突标记），所以它和提交 / 撤销 / 分支切换一样走决策页：
面板只多一枚「合并分支…」胶囊（`onMerge` 出口）。分支清单在页上选：**本地分支 + 只在远端的**
（后者引擎会先 fetch 一次 —— 这正是 PR 冲突「拉到本地解决」那条路）。
四条前置写成**灰掉按钮的理由**，而不是点下去才报错：没拉本地 / 已在合并中 / 工作区脏（N 处）/
浅克隆（指出去加深）。

② **冲突不是失败**：`merge_branch` 返回 `conflict` 时立刻弹窗（几个文件 · 哪个分支 · 后果），
**弹窗出现那一刻**就启动预解析（D-h 的契约），结果落**进程内缓存**（`MergePreparse`）——
详情页只读它。两条被点名不许的做法各有一条钉子：页面打开后再从头算（`startTwiceFetchesOnce`）、
把预解析做成页面状态（`stateSurvivesPageReentry`）。

③ **冲突详情页**（新全屏页，登记进 `SystemBarInsetsTest.fullScreenPages`）：逐文件
**ours ↔ theirs 的 patch**（复用 `parseUnifiedDiff` + `DiffLineRow`，与本地 diff 页同一套渲染；
冲突块就是它的 hunk，不再另算一套）+ 三个动作：用我方 / 用对方 / **手工编辑**。
「用某一侧」的内容取自**索引三方条目**（引擎侧保证），不是工作区那份带 `<<<<<<<` 的文本 ——
拿带标记的文本当「我方」等于把标记一起提交上去；手工编辑的初值反过来，正是**工作区那份带标记的**
（git 留给人的就是这个形状）。二进制文件不给 diff，如实说「请选一边」。
全部解决 → 「提交合并」；任何时候 → 「放弃合并」（二次确认，回到合并前）。

④ **提交合并先跑敏感扫描**（风险 ② 的对策）：与文件页提交同一条口径 —— **扫不了就拦下**
（引擎没就绪时「以为扫过了」是最危险的状态），命中先警告、再点一次才提交。

⑤ **「合并到一半被杀」有出路**（风险 ①）：`repo_status` 的 `merging` 让面板知道现在该给的是
「继续 / 放弃」而不是假装一切正常；工作区档最上面那条状态说清「还剩 N 个文件」——
停在合并中时工作区**必然是脏的**，先列改动清单只会让人更糊涂。
同一档的「合并分支…」在合并中置灰（同时开着「再合一次」与「解决这次冲突」会让人以为要先合完）。

⑥ **两个宿主各接一次**（代码页 / 文件页），三枚出口 + 冲突弹窗都在**外层**渲染 ——
合并从全屏决策页发起，那一屏在的时候面板并没有被组合，弹窗放进面板里就会在最需要它的那一刻不出现。
接线与插桩由 `GitWorkbenchWiringTest` 新增的三条钉子盯着（面板里零写操作 / 两宿主出口 /
预解析的触发点）。

**顺带收掉两处**：① 三方内容字段（`ours` / `theirs` / `worktree`）补进 `analyze_conflicts`
（手工编辑要有内容可编辑，单份上限 64 KB + `content_truncated`）；② 提交身份的两个 helper
（`commit.author.*`）从「设置 → 本地仓库」抽到 `LocalRepoGitState.kt` —— 合并提交用的是同一个身份，
两处各写一份键名的话，改了设置里的称呼、合并提交还用旧名字。

**边界（登记，不在本轮）**：阶段 5 还差两条**入口** —— 分叉页第三条（pull 被判 `nff` 时给「合并远端」）
与 PR 冲突的「拉到本地解决」（引擎已支持远端分支名，缺的是 PR 详情页那枚入口）。

验证：`cargo test` 105 例（+1 条内容字段断言）· `:app:testDebugUnitTest` 909 → **923** 例
（`MergeModelsTest` 6 例：四条出口 / 合并状态 / 还剩几个与能不能提交 / 预解析 / 分支清单两条；
`MergePreparseTest` 5 例：Loading→Ready / 不重复启动 / 返回再进仍是那份 / 失败可重试 / 收尾清干净；
`GitWorkbenchWiringTest` 10 → 13 例）· `assembleDebug` · `check-i18n --min-coverage 100`
（新增 66 条中英资源：合并页 / 冲突页 / 弹窗 / 冲突类型五档 / 提示与错误）。
取证：这一轮踩到一个构建环境的坑 —— **suspend lambda 会把外层函数名编进匿名类文件名**
（`MergePreparseTest$<方法名>$1.class`），而构建环境的文件名编码是 POSIX，中文方法名直接报
`Malformed input or input contains unmappable characters`；那个文件的用例名因此是英文的（已在文件头写明理由）。

### 1.0.102

**Git 模式阶段 5（引擎那半）：本地合并与冲突解决 —— 七条接口 + JNI + 重建 `.so`。**

① **D11 的拆分从这一版起是真的**：「不改写已推送历史」原来被写成「不做 merge / rebase」，
   而 merge 只**新增**提交、已有 sha 一字不变 —— 禁止它从来没有技术理由（D-g）。
   现在 `merge_branch` 允许 merge，rebase / amend 已推送 / 强推仍然禁止。

② **七条接口**（都走完 `mod.rs` → `jni.rs` 导出 → `RustBridge` wrapper → `JniSignatureTest` →
   **重建 `.so`** → `cargo test` 这条链）：

| 接口 | 干什么 | 关键约定 |
|---|---|---|
| `merge_branch(dir, branch, token, name, email)` | 三方合并 | 四条出口 `up_to_date` / `fast_forward`（**不产生提交**）/ `merged`（落**两父**提交）/ `conflict` |
| `merge_state(dir)` | 当前合并状态（只读） | `merging` / 待提交信息 / `MERGE_HEAD` / **还没解决**的冲突清单 |
| `analyze_conflicts(dir)` | 预解析（**只读、不落盘**） | 逐文件 `kind` / 三方 sha 与大小 / ours ↔ theirs 的 patch |
| `resolve_conflict(dir, path, side)` · `write_resolved(dir, path, content)` | 逐文件解决 | 内容取自**索引三方条目**，不是带标记的工作区那份 |
| `merge_continue(dir, message, name, email)` · `merge_abort(dir)` | 收尾 | 两父提交并清状态 / hard reset 回合并前 |

③ **冲突不是错误**：`merge_branch` 把「有冲突」如实报成 `outcome = "conflict"` 并返回未解决的清单，
   仓库停在合并中（MERGE_HEAD 在，由 libgit2 自己写）。压成 `Err` 的话上层只剩一句「失败」，
   而这时仓库**确实**在合并中 —— 用户要的是「哪几个文件、现在能做什么」。
   与之配套：`repo_status` 多一个**只增**字段 `merging`（面板据此给「继续 / 放弃」，
   而不是让用户对着一堆 `<<<<<<<` 猜发生了什么）。

④ **三条前置 + 一条路径收口**（每条都对应一次真机上会看到的坏结果）：
   - **浅克隆拒绝合并**：没有共同祖先，libgit2 只会回一句英文 —— 现在提前拦住并写作「先加深历史」
     （风险 ③ 的对策，`is_shallow()`）；
   - **已在合并中拒绝叠加**：MERGE_HEAD 被覆盖后「放弃合并」会回到错的地方；
   - **工作区脏拒绝合并**：`merge_abort` 是一次 hard reset，敢这么做正因为入口要求了工作区干净
     （否则那一下会连用户自己的改动一起抹掉 —— 正是 D11 不许发生的事）；
   - **`write_resolved` 的路径来自上层字符串**：绝对路径 / `..` / `.git` 一律拒绝，否则一个 `../`
     就能写到仓库外面去。

⑤ **目标分支只在远端时引擎自己拉一次**：PR 冲突「拉到本地解决」那条路上，目标分支往往本地从未有过
   （别人的分支 / PR 的 head）。让上层先手动 fetch 是把一件事拆两步，而两步之间用户会看到
   「找不到分支」这种中间态错误。

⑥ **顺带收掉三处重复**：网络回调（证书 + PAT，其中**凭证类型**那三行是踩过坑的）从四处抄一遍
   收成 `net_callbacks` 一份；fetch 的 refspec 收成 `fetch_origin`；patch 的截断规则
   （上限 200 KB、按字符边界切）收成 `truncate_patch` —— 冲突预解析也要渲染 patch，
   第二份截断逻辑就是「两处上限迟早有一处漏改，而表现都是内容少了一截、界面却说这就是全部」。

**边界（登记，UI 那半做）**：分叉页文案由两条变三条（合并 / 保留 / 放弃）；
用户改过合并信息时，提交前对 `message` 跑 `scan_sensitive`（与普通提交同一条口径，不是第二套规则）；
详情对比页是**新全屏页** → `SystemBarInsetsTest.fullScreenPages` 登记。

验证：`cargo test` 92 → **105** 例（13 条：快进不产生提交、干净合并两父且第一父是「合到哪」、
已包含是 up_to_date、冲突停在合并中并列出文件、`analyze_conflicts` 只读且给三方与 patch、
用某一侧解决后索引不再冲突、`merge_continue` 两父并清状态、空信息用 `MERGE_MSG`、
`merge_abort` 回到合并前、工作区脏拒绝且**不覆盖**、浅克隆拒绝并指出去哪加深、
目标分支只在远端时自己拉一次、手工内容与越界路径拒绝）· 重建 `.so` ·
`:app:testDebugUnitTest` **909** 例（本轮无 UI 改动；`JniSignatureTest` 自动覆盖新增的 7 对原生函数，
现共 104 对）· `assembleDebug` · `check-i18n --min-coverage 100`（无新资源）。

### 1.0.101

**Git 模式阶段 4 收尾：提交图的「未推送段」—— 本地那份历史里，哪几条还没推给上游。**

① **原先不成立的地方**：提交图从 1.0.98 起优先走本地 `log_graph`，于是**没推送的本地提交第一次出现在图上**
（REST 看不见它们）—— 但它们和已推送的提交长得一模一样。工作区档写着「待推送 3」，
切到提交图却分不出是哪三条，而这一档存在的理由之一正是「离线也看得见未推送的那一段」。

② **口径与 `repo_status` 的 `ahead` 同源**（引擎里新增 `unpushed_oids`：`revwalk` push HEAD、
hide 上游，就是 `git log @{u}..HEAD`）。两个数字必须相等：面板上同时出现「待推送 3」和一张
一个标记都没有的图，比两边都没有更坏 —— 已有一条 cargo 钉子把 `repo_status.ahead`
与图上标记数按 `assert_eq` 对起来。三种「一条都不标」都是**有意**的：没有上游（分支没有 upstream
配置 / detached HEAD）、revwalk 建不起来（显示用的提示不该让整张图变成错误页）、上游就是 HEAD。
第一种尤其重要：全都标上等于每一行都在喊同一件事，而那时工作区档写的正是「已同步」。

③ **画法只走文字**：未推送的行在行尾多一枚「未推送」小字（`Primer.WarningText`），脚注补一句
「其中 N 条还没推送到上游」。**不去改节点的画法** —— 虚线圈已经是「未提交」虚节点在用的形状语法
（§4.3），再拿空心 / 虚线表示「未推送」就是两件事抢一套画法。脚注只数**已加载的这一屏**：
分页没加载的不在手上，写「一共 N 条」就是编数（全量那个数在工作区档）。

④ **REST 来源一律不标**：那份响应里没有「本地推没推」这件事 —— 不是「都推过了」，是「这一屏答不了」。
解析器上这是**刻意的不对称**（本地解析认 `unpushed`、REST 解析不认），有单测钉着，
免得日后有人「顺手统一」成一张图两种含义。

⑤ **顺带把两处旧账对齐**：§7 的 `log_graph` 还写着「消费者待接」、`diff_*` 还写着「UI 待接」——
1.0.98 / 1.0.99 早就接上了；§9 补一行「引擎接口**输出加字段**（签名不变）」的登记处
（不用动 `JniSignatureTest`，但**仍要重建 `.so`**，否则真机跑的还是旧行为而本地测试全绿）。

边界（登记，不在本轮做）：这一档的标记只回答「相对**上游**」；仓库没有任何远端时，
未推送无从谈起，也就没有标记可画（`hasUpstream = false` 时工作区档同样只显示「未设置上游」）。

验证：`cargo test` 90 → **92** 例（新增两条：有上游时逐条标记并与 `repo_status.ahead` 对账、
没有上游 / 上游就是 HEAD 时一条都不标）· 重建 `.so` · `:app:testDebugUnitTest` 905 → **909** 例
（`CommitGraphSourceTest` +3：本地 JSON 的 `unpushed` 逐条解析与缺键退化、REST 来源一条都不标、
未推送条数只数这一屏；`GitWorkbenchWiringTest` +1：面板必须真的画出来，不是只解析）
· `assembleDebug` · `check-i18n --min-coverage 100`（新增 2 条中英资源）。

### 1.0.100

**Git 模式阶段 4 收口：「文件历史」档落地（`log_file` 的消费者）—— 四个视图档全部落地。**

① **这一档回答什么**：这个文件是谁改的、改了几次。数据两条来源（D-e）：

| 来源 | 取数 | 分页键 | 什么时候用 |
|---|---|---|---|
| 本地 `log_file` | revwalk + 逐提交比对树，**只列真的碰过这个路径的提交** | `skip` | 本地仓库存在**且不是浅克隆** |
| REST `/commits?path=` | 与提交列表同一个接口，多一个 `path` | 窗口内最老 sha | 其余情况（含浅克隆） |

浅克隆走 REST 的理由与提交图一样：本地只有 HEAD 一条提交，走本地会给出「这个文件没有历史」
这种**假话**（文件明明有历史，只是不在本地）—— 所以这一档同样带「加深历史」出口，
与提交图档共用同一个运行器（一份长任务、一只弹窗、一个 `TaskKind.DEEPEN`）。

② **REST 来源的行不可点**：`diff_commit` 读的是本地对象库，浅克隆 / 没拉本地时那些提交
本地根本没有对象，点了只会得到「读取失败」—— 不给注定失败的入口（本仓库的既有口径）。
判据抽成纯函数 `canOpenCommitDiff(source)`，有单测。

③ **代码页没有「当前文件」**：这一档要一个路径。文件页传正在看的那个（所以文件页**必须**
把 `filePath = path` 传进面板，漏了会退化成「请去文件里看」——而这一页就是那个文件页）；
代码页传 null，那时**如实说明去哪看**，而不是显示一个空列表（空列表会被读成「这个文件没有历史」）。
这一条也钉进了 `GitWorkbenchWiringTest`。

④ **顺带**：`available` 机制进入「无用户」状态 —— 四个档都落地了，标签条上那句「待接入」
目前在界面上不会出现（机制留着给下一个档，`GitPanelStageTest` 现在反过来断言「没有任何档是待接入」）。
提交图里那句硬编码的「（无提交信息）」改用资源（英文模式下它一直是中文）。

验证：`cargo test` **90** 例（本轮无引擎改动）· `:app:testDebugUnitTest` 895 → **905** 例
（新增 `FileHistoryModelsTest` 9 例、`GitWorkbenchWiringTest` +1 例「文件页必须把当前文件传进去」、
`GitPanelStageTest` 改为断言四个档全可用）· `assembleDebug` 通过 ·
`check-i18n --min-coverage 100`（新增 5 条中英资源）。versionCode 201 → 202（一次提交 +1）。
**取证**：`log_file` 的三段 fixture（首页 / `skip=2` 的下一页 / 没碰过该路径给 `[]`）由引擎真跑抄回，
钉住「按**命中数**分页」这条容易写错的语义。

### 1.0.99

**Git 模式：本地 diff 页落地 —— 工作区改动清单与提交图的提交行都能点开看「改了什么」
（`diff_worktree` / `diff_commit` 的消费者）+ 引擎修掉「未跟踪文件没有内容」**。

① **为什么是「新开一个只读页」而不是给文件查看器加「本地工作树」来源**（D-k，§11.6 的选型）：

- 1.0.96 那次「点一行 → 打开那个文件」失败的原因是**目的地错**：查看器读的是
  `GET /repos/{o}/{r}/contents/{path}`（远端），而清单列的是本地改动；
- 给查看器加本地来源当然也能修，但那意味着在一个已经背着编辑态 / 草稿 / 草稿基准 sha /
  离线冲突检测 / 三种提交模式的页面上，再回答一遍「本地来源下这些还成不成立」——
  `PageCache` 的键是 `(owner, repo, path, ref)`、策略是「直出过期数据 + 写操作后失效」，
  本地内容随时在变，混进同一套键会让「提交后必须看到新内容」失真；
- 而用户点开脏文件想问的是**「我改了什么」**：diff 一句话回答，原文得自己记得远端长什么样。
  所以走一条独立的只读路：两个数据源输出同一套结构，共用一页与同一份渲染
  （引擎侧 `render_diff` 本来就是共用的）。

② **本地 diff 页**（`LocalDiffScreen` / `LocalDiffModels`）：两个入口 —— 工作区档的改动行
（`diff_worktree`，**只留点开的那一个文件**）与提交图档的提交行（`diff_commit`）。
态齐全：加载 / 失败可重试 / 没有改动 / 截断 / 单文件没有可显示的差异（二进制）——
空白页面在这里是最坏的结果（分不清「没有改动」与「没读出来」）。渲染抽了共享的
`DiffLineRow`（`DiffLines.kt`）：它原本是 `BranchCompareScreen` 的私有函数，
同一个东西两处各画一遍的下场是样式漂。

③ **引擎侧一个真问题（靠现场取证才发现）**：`diff_worktree` 的 `files` 里**有**未跟踪文件
（状态 A），但 patch 里**没有它的那一段** —— libgit2 默认只列 delta、不给内容
（`GIT_DIFF_SHOW_UNTRACKED_CONTENT` 未置位）。后果有两层：① 「新增一个文件」是脏工作区里
最常见的一种，点开就是一片空白；② 更危险的是「按下标对齐」会把**上一个文件的差异画到它名下**
（取证时 files=[bin.dat, mod.txt, new.txt]、patch 只有 mod.txt 一段）。
现在 `show_untracked_content(true)`，并新增 cargo 测试
`diff_worktree_未跟踪文件带内容且两段按下标对齐` 钉住不变量。Kotlin 侧再加一道保险：
两段数量对不上时**不按下标配**（宁可全都不画，不把 A 的差异画到 B 名下）。

④ **两个坑记在这里**：单测的 fixture 是**由引擎真跑出来再抄回来的**（不是手写「我以为它会吐什么」）——
上面第 ③ 条就是这么发现的；二进制文件的那一段没有 hunk（只有一句 `Binary files … differ`），
直接丢给 `parseUnifiedDiff` 会画出一行**假的行号 + 英文提示**，所以判据是「有没有 hunk」而不是
「patch 是否为空」。另外 LazyColumn 的行 key 必须带**文件身份**：两个文件的 hunk 头可能逐字相同
（都是 `@@ -1,2 +1,2 @@`），撞 key 是直接崩、不是画错。

验证：`cargo test` 89 → **90** 例（新增「未跟踪文件带内容且两段按下标对齐」）·
`:app:testDebugUnitTest` 882 → **895** 例（新增 `LocalDiffModelsTest` 12 例、
`GitWorkbenchWiringTest` +1 例「diff 出口两个宿主都要接」、
`SystemBarInsetsTest` 登记新全屏页）· `assembleDebug` 通过 · 重建 `.so` ·
`check-i18n --min-coverage 100`（新增 7 条中英资源）。versionCode 200 → 201（一次提交 +1）。

### 1.0.98

**Git 模式阶段 4（前半）：`fetch_deepen` 全链路（引擎 + JNI + `.so` + 任务中心 + 进度弹窗）
+ 提交图换成本地优先的双来源**。

① **为什么先做加深**：clone 用的是 `depth(1)`（浅 clone 减体积），而 `pull` / `fetch` **不设 depth** ——
在浅仓库上不会撤销浅边界（git 自己也要 `--unshallow`）。于是本地永远只有 HEAD 一条提交，
「提交图走本地来源」「文件历史本地优先」（阶段 3' / 4）全都无从谈起：直接换来源就是把
「一屏 100 条」换成「1 条」。**加深是这两件事的前置条件**，所以它排在这一轮。

- `fetch_deepen(dir, depth, token)`（`core/src/git/mod.rs`）：`depth <= 0` = 全量，
  内部发 `i32::MAX` —— 与 `git fetch --unshallow` 同一条路，libgit2 也拿 `INT_MAX` 当
  「不要浅边界」的哨兵（`fetch.c:65`：`nego.depth != INT_MAX` 才跳过本地已有的对象）；
  `depth > 0` = 加深到该条数（增量档先留着，UI 未用）。**只动对象与 `refs/remotes/origin/*`**：
  不改工作区、不动本地提交，所以是安全动作，失败 / 取消都可以重来；
- 浅边界由 libgit2 自己收尾：浅边界归零时它会**删掉 `.git/shallow`**
  （`repository.c` 的 `git_repository__shallow_roots_write`）—— 界面正是靠这个文件判定
  「加深成功没有」（`isShallowClone`）；
- 进度与取消**复用 clone 那一条通道**（`progress::begin/transfer/complete` + `CANCEL_REQUESTED`）：
  §10 要求「不许出现第二种转圈」，所以弹窗也复用 `CloneProgressDialog`，只把标题参数化。

② **提交图双来源**（`graphSourceOf(本地存在, 本地是浅克隆)`）：

| 来源 | 取数 | 分页键 | 条件 |
|---|---|---|---|
| 本地 `log_graph` | 离线、不消耗限额、**看得见还没推送的本地提交** | `skip` = 已加载条数 | 本地仓库存在**且不是浅克隆** |
| REST `/commits` | 与列表页同一接口（响应本来就带 `parents`） | 窗口内**最老的 sha** | 其余情况（含浅克隆） |

- 分页键两种形状 → 抽成 `GraphPage`（sealed）+ `nextGraphPage`：共用一个 Int 的话，
  表现是「点了『加载更早』没反应」或「反复加载同一页」（前者像网络慢，后者看不出错）；
- 本地读不出来（引擎不可用 / 目录被删）→ **退回 REST 并留一条 warn**：
  否则事后只看到「来源=本地」，没人知道它其实失败过；
- 浅克隆时脚注**如实说明**（「本地是浅克隆，只有最近的历史 · 加深后可离线看图」）
  并给一枚「加深历史」胶囊；加深成功后宿主 `refreshTick++` → 浅克隆判定重算 → 图换回本地来源。
  顺带修掉一处旧的不实文案：REST 取数失败时原先报「本地仓库不存在：请先拉取」——
  来源换成双份之后，这句话按来源分开（本地失败 / 远端读不到）。

③ **加深是一次长任务**（`LocalRepoDeepen.kt`）：任务中心新 kind `TaskKind.DEEPEN`
（借 `PULL` 的话，事后说不清那条记录为什么是几百 MB 的下载）、进度每 200ms 从引擎快照轮询、
失败**停在弹窗里**把引擎原文摊开给「重试」、取消走同一条 `gitCloneCancel`。
运行器只有一份，**代码页与文件页各挂一次** —— 写两遍的代价不是多几行，而是两套轮询节奏 /
两套终态处理，行为会随「从哪个页面点」而不同（Git 球门控那次的教训）。
面板这一族源码里**依旧一个 git 写方法都没有**：面板只拿 `onDeepen` 回调（`GitWorkbenchWiringTest` 扫全表）。

**一个测不到的坑记在这里**：libgit2 的 local transport **不支持 depth**
（`transports/local.c` 的 `local_shallow_roots` 直接返回空、下载时也不看 depth），
所以单测里造不出「真的浅克隆」——`fetch_deepen` 那三条是**手工写下 `.git/shallow`** 再加深，
钉的是界面依赖的性质（边界消失、全史可走）；真浅克隆只能在真机 / HTTP 上验。

验证：`cargo test` 86 → **89** 例（新增 3 条：没有 origin 时如实报错 / 全量之后浅边界消失且历史完整 /
已全量时再跑一次无害）· `:app:testDebugUnitTest` 869 → **882** 例（新增 `CommitGraphSourceTest` 11 例、
`GitWorkbenchWiringTest` +2 例）· `assembleDebug` 通过 · 重建 `.so`（
`core/build-android.sh`，`nm -D` 确认 `nativeGitFetchDeepen` 已导出）·
`check-i18n --min-coverage 100`（新增 6 条中英资源）。versionCode 199 → 200（一次提交 +1）。

### 1.0.97

**Git 模式阶段 3（引擎那半）：五个本地只读接口 `log_graph` / `list_tags` / `log_file` /
`diff_worktree` / `diff_commit` 落地（含 JNI 导出与 `.so` 重建）+ 引用树档接上 tag**。

① **引擎**（`core/src/git/mod.rs`，五条全是只读：不 fetch、不写工作区、不动 ref）：

- `log_graph(dir, limit, skip)`：`TOPOLOGICAL | TIME` 排序的 revwalk（泳道布局假定「父都在子下方」，
  纯时间序在时钟回拨的仓库上不成立）+ `parents` 一定带上（缺了图就退化成一条直线）；
- `list_tags(dir)`：**D-f 全字段** —— annotated 给 `tagger{name,email,time}` 与说明，
  `sha` 是 tag 对象、`target_sha` 是被指的提交；**轻量 tag 的 `tagger` 为 null、说明为空串**
  （给它编一个作者等于在界面上撒谎）；
- `log_file(dir, path, limit, skip)`：libgit2 没有 `log -- path`，只能 revwalk +
  `diff_tree_to_tree`（带 pathspec）逐个提交比对；命中 `limit` 就停，**但没碰过该路径的提交仍要继续走**
  （历史是链式的，提前退出会漏掉更早的那次改动）；根提交用「树里有没有这个路径」判定；
- `diff_worktree(dir)` / `diff_commit(dir, sha)`：同一套输出 `{patch, files, truncated}`，
  上层因此可以共用一份渲染。patch 超 200 KB 截断并**如实置 `truncated`**（悄悄截断会让人以为
  「改动就这么点」），按**字符边界**截断（diff 里有中文时按字节切会切出半个字符）；
- 自己写了一个 `commit_time_iso`（含 Howard Hinnant 的 `civil_from_days`）：只为时间格式引一个
  日期库不值当 —— 这个仓库已经在为 vendored openssl / libgit2 付交叉编译的代价。输出与 REST 那份
  `author.date` 同形，UI 侧不用分辨数据来自哪边。

② **接口链**：`jni.rs` 五个导出（`nativeGitLogGraph` / `nativeGitListTags` / `nativeGitLogFile` /
`nativeGitDiffWorktree` / `nativeGitDiffCommit`）→ `RustBridge` 五个 `suspend` 门面 →
`JniSignatureTest` 逐参数对账 → **重建 `.so`**（`core/build-android.sh`，12 分钟；`nm -D` 已确认五个符号导出）。

③ **引用树档接上 tag**（这个接口的第一个消费者）：annotated 显示说明首行、轻量 tag 右侧标「轻量」并
**不画 tagger / 说明**；没有 tag 时写「还没有 tag」而不是留空区。tag 行**不可点** ——
点开能去哪今天并不存在，画个可点的样子就是假入口。占位串
`note_git_refs_tags_pending` 与它的中英资源、`strings.tsv` 行一并删掉（不留死资源）。

**一个坑记在这里**：`diff_tree_to_workdir_with_index(None, …)` 的 old 侧是**空树**，
于是「改过的已跟踪文件」会被报成 Untracked/新增 —— 必须显式传 HEAD 树才等价于 `git diff HEAD`。
单测直接钉了这个现场（同一个 diff 里：新文件 `A`、已跟踪改动 `M`）。

验证：`cargo test` 86 例（新增 8 例，含建临时仓库跑真 libgit2 的那几条）· `:app:testDebugUnitTest`
866 → 869 例（`GitRefsModelsTest` 新增 tag 解析 3 例）· `assembleDebug` 通过 ·
`check-i18n --min-coverage 100`（删 1 条占位、新增 2 条）。versionCode 198 → 199（一次提交 +1）。

### 1.0.96

**Git 工作台：接线收口（面板只给出口 + 分支管理接进两档）+ 日志插桩（新锚点「Git工作台」四处入口）
+ 标准回归插桩（`GitWorkbenchWiringTest` 5 例，全是源码级钉子）**。

① **接线收口**（三处都是「能力已经有了、路没接上」的口子）：

- **改动清单「点开看文件」：试过又退回**（记下来，免得下次再走一遍）—— 一度接过
  「点一行 → 打开那个文件」，写完才发现**目的地是错的**：文件查看器读的是
  `GET /repos/{o}/{r}/contents/{path}`（**远端**），而这一档列的是**本地改动**，
  点开看到的是没改过的那一份，比点不动更坏。要成立得先给查看器一个「本地工作树」来源，
  或等阶段 3 的 `diff_worktree` 落地后点开看 diff（已登记进设计稿 §11.6）；
- **分支管理出口接进两档**（工作区 / 引用树）：`onOpenBranches` 为 null 的宿主不画那枚胶囊。
  引用树是只读的（D-j），但没有出口的只读列表会让人以为「App 里根本改不了分支」。
  底部胶囊改走 `FlowRow`：英文标签长得多（"Local branch sync" / "Branch management"），
  三枚挤一行会超出 268dp 的面板宽度**被裁掉**（`Row` 不换行、也不报错）；
- **设置 → 本地仓库页头加一句统筹说明**（§5 过渡期要求的「标注真源在 Git 面板」）：
  整页一句、**不动行结构**（行重绘留阶段 6，且「不能硬加边界」是用户明确的要求）。

② **日志插桩**（新锚点 `Git工作台`，已登记进 `LOG_ANCHORS`，导出包的 `report.md` 会带上这张表；
tag 抽成 `GIT_WORKBENCH_LOG_TAG` 常量，避免手写字面量写歪导致「照表 grep 一条都搜不到」）：

| 入口 | 记什么 | 为什么 |
|---|---|---|
| 面板动作 | `action.key`（不是 label） | label 跟随界面语言（英文模式下是 "Branch"/"Sync"），key 是稳定契约，两种语言下都能 grep |
| 档位变化 | `Collapsed → Actions → View(Workspace)`（枚举名） | 标签条上那套中文名会随语言变，日志要固定可检索；「点动作后自动收起」**静默**，否则同一意图在日志里出现两次 |
| 返回退档 | 两个宿主各一条（`panelBack` 前后两个档名） | 只接一边就会出现「代码页有、文件页没有」的半边链路 |
| 深链接进入 / 设置列表进入 | 各一条 | 出「点了没反应」类反馈时，能一眼分清是哪条入口 |
| 三档取数 | 引用树「本地 N · 远端 M（只在远端 K）」· 提交图条数与「加载更多 +N」· 失败各一条 warn | 面板上只有一句「加载失败」，事后判不了是网络 / 限额 / 分支名错 |
| 改动清单不可点 | —— | 入口本身就不成立（上面第 ① 条），所以这里没有可记的动作 |

日志里只放**仓库名**、不放完整路径（路径含账号登录名，没必要进日志包）。

③ **标准回归插桩**（`GitWorkbenchWiringTest`，5 例；这些行为只有真机上才看得出来，只能把规则钉在源码上）：

- **面板里不许出现任何 git 写操作**：14 个写方法（`gitCommit` / `discardAllChanges` / `checkoutBranch`…）
  全表扫描面板这一族的 6 个源文件。§6.1 的三档分档靠它执法 —— 一旦有人图省事在浮层里直接提交或丢弃，
  失效的样子是「误触一下就丢了改动」，不是编译错误；
- **两个宿主的出口不能只接一边**（`onOpenBranches =` 必须两边都有）；
- **设置列表的「进入」必须带 `openGitPanel = true`**（漏了就是「进了仓库，面板却关着」）；
- **新锚点的四个入口都真的打了日志**：`LogAnchorsTest` 只保证「表里的 tag 被用过至少一次」，
  挡不住「只在一个入口打了」；
- **提交图的取数成功与失败都要留痕**。

`tools/i18n/check-i18n.py --min-coverage 100`（新增 1 条中英资源）· `:app:testDebugUnitTest`（861 → 866 例）·
`assembleDebug` 通过。versionCode 197 → 198（一次提交 +1）。

### 1.0.95

**Git 模式阶段 2（前半）：「引用树」档落地（本地 / 远端分支，零 Rust）+ 设置列表「进入」深链接
（`RepoDeepLink.openGitPanel`）+ 收口时修掉三处「文档说已落地、代码说没落地」**。

① **「引用树」档**（`GitPanelKind.Refs` → `available = true`）：

- `ui/repository/GitRefsModels.kt`：一档视图模型 + 两条纯函数 —— `refSyncBadge`（两边都是 0 就
  **不画**「↑0 ↓0」；「未跟踪」走 `tracked = false`，与 0/0 分开表达）与 `refsViewOf`
  （**HEAD 置顶**：面板只有 268dp、分支一多就得滚动，「我现在在哪」不能被滚出屏幕；Kotlin 排序稳定，
  置顶之外保持引擎给的顺序）。数据源是本地仓库的 `local_branches` / `remote_branches`：离线可读、
  与「工作区」档同源、不消耗 API 限额；
- `ui/repository/GitRefsPanel.kt`：本地分支（HEAD 圆点 / 上游 / 领先落后）+ 远端跟踪引用
  （`origin/` 前缀、「只在远端」标出来）+ 标签区**如实写「按阶段 3 接入」**；
  骨架 / 空 / 读不到 / 仓库不存在四态分开（`null` 不折成空列表 —— 「读失败」与「一个引用都没有」
  是两条不同的文案），底部给两个真能用的出口（刷新 / 同步）；
- **这一档刻意只读**：切分支 / 建分支 / 删分支是**有后果的动作**（脏工作区切分支要撤销改动、
  删分支会丢提交），按 §6.1 的分档走决策页，落点在既有的「分支管理」「本地分支同步」——
  把可点分支行放进 268dp 的浮层里，只会把误触变成默认路径；
- **tags 不用 REST 的 `/tags` 顶替**：那会立刻出现第二个数据源与第二套字段口径
  （D-f 要 annotated 的 tagger / 时间 / 说明，REST 那份给不了），阶段 3 落 `list_tags` 时还得拆两遍。

② **设置 → 本地仓库「进入」**（§3.4 的第二个入口）：点**仓库名**打开该仓库代码页并把 Git 面板
**直接开到视图档**（蓝色 = 全 App 一致的链接样式，不加新控件、不动行结构 —— 显式「进入」按钮留给
阶段 6 的行重绘）。三条实现约定：`openGitPanel` **隐式带上代码页**（气泡只长在代码页上，
只传面板而漏 `page` 的表现就是「点了没发生」）；落地档由 `initialGitPanelStage` 给（视图档，
不是先落在动作列表）；owner/repo 从 `origin` URL 反推、**复用列表本来就要跑的那轮 `gitStatus`**
（不额外读第二遍），解析不出时回落到「当前账号 + 目录名」—— 而「解析不出就不给进入」只会让
一个能用的入口凭空消失。

③ **收口时修掉的三处账目不符**（都不是新功能，是账没对上）：

- 阶段 1 把「提交图」渲染接上了，`GitPanelKind.Graph.available` 却留在 `false` —— 标签条标「待接入」、
  点进去却是一张能用的图；而那枚钉子（`GitPanelStageTest`）当时钉的正是这个错值。现在两个方向都有钉子：
  一条查枚举值，一条**对着宿主源码**查「渲染了却没标可用」（限定在 `GitPanelViewHost` 函数体内 ——
  同文件的 `gitPanelKindLabel` 穷尽四档，整文件扫会把「文件历史」也算成已渲染）；
- 代码与资源里指向 `git-mode-design.md` 的章节号还是收束前的旧号（动效 `§3.4` 实为 §3.3、
  阶段表 `§9` 实为 §8、`local-git-engine-design.md` 的 `§10.2` 实为 §6.4），一并改正；
- `VERSION-NOTES` §三 少了 **195** 这一版（阶段 0 那次提交），§二 1.0.94 的箭头也写成了 `194 → 195`
  —— 本轮按 git 历史补回 **1.0.93** 条目并改正（此前阶段 1 的提交把两条并成了一条，
  合并可以，但合并之后版本码就对不上了）。

`tools/i18n/check-i18n.py --min-coverage 100`（新增 9 条中英资源）· `:app:testDebugUnitTest`（861 例；
新增 `GitRefsModelsTest` 5 例、`RepoDeepLinkTest` 4 例，`GitPanelStageTest` 7 → 9 例）·
`assembleDebug` 通过。versionCode 196 → 197（一次提交 +1）。

### 1.0.94

**Git 模式阶段 1 落地（提交图 + 未提交虚节点 + 分档标签条）+ 文档收束（三份并一份、落后决策丢弃）**。

⑦ **阶段 1 落地（代码，仍零 Rust）**：

- `ui/repository/CommitGraphModels.kt`：图的独立解析 `parseGraphCommits`（**保留 `parents`** ——
  不去动提交列表那份 `parseCommits`）+ 泳道布局 `CommitGraphLayout`（第一父继承泳道、汇合时本泳道释放、
  **空位当场压实并把位移画成斜线**、父不在窗口画终止符）+ `GraphRow`（含虚节点档）；
  11 例单测（线性 / 分叉 / 汇合回收 / 悬空父 / 根提交 / octopus / 虚节点出现与消失 / 幂等 / 解析容错）；
- `ui/repository/CommitGraphPanel.kt`：「提交图」档 —— `LazyColumn` + 固定宽 gutter（Canvas 只画本行线段，
  颜色在组合期取好）、短 sha · 标题 · 作者、分页脚注「已加载 N 条 · 更早历史未加载」+「加载更多」、
  骨架 / 空 / 失败三态；
- **虚节点（方案 A）落地**：HEAD 之上虚线圆、无 sha、点它进「工作区」档，不提供任何以 sha 为键的交互；
- `GitPanelViewHost` + 分档标签条：工作区 ✅ / 提交图 ✅ / 引用树·文件历史 标「待接入」，
  点进去是如实说明（不是死按钮）；两个宿主都改用它。

⑧ **文档收束**：`git-mode-design.md` 重写为 **Git 模式唯一的开发进度与设计文档**
（产品判断 / 现状 / 形态与动效 / 可视化 / 决策台账（生效 + **已废弃并丢弃**）/ 引擎缺口 / 路线图 /
登记清单 / 未决）；`git-version-tree-design.md` 收敛成指向它的指针页（旧链接不断）；
`docs/README.md` 索引两行并一行。**丢弃的落后决策**：全屏工作台 + `RepoRoute.GitWorkspace` 那套登记、
虚节点方案 B/C、缓存三选一的对比表、D11「不做 merge/rebase」旧措辞。

`tools/i18n/check-i18n.py --min-coverage 100`（新增 7 条中英资源）· `:app:testDebugUnitTest`（839 → 850 例）·
`assembleDebug` 通过。versionCode **195 → 196**（一次提交 +1）。

### 1.0.93

**Git 模式：设计三轮落账（数据面三条决策 / 虚节点「画」/ 气泡多档面板 + 框换框动效 /
D11 拆 merge 与 rebase / 缓存不另建）+ 阶段 0 落地（面板三档 + `PanelSwitcher` + 「工作区」档）**。

**Git 模式：设计三轮落账（数据面三条决策 / 虚节点「画」/ 气泡多档面板 + 框换框动效 /
D11 拆 merge 与 rebase / 缓存不另建）+ 阶段 0 落地（面板三档 + `PanelSwitcher` + 「工作区」档）**。

产品对设计稿未决问题的三轮答复，逐条折进
[`git-version-tree-design.md`](git-version-tree-design.md) / [`git-mode-design.md`](git-mode-design.md)：

① **数据面三条**：文件历史**要**本地 `log -- path` 兜底（已加深走本地：离线、无 API 限额；REST 兜底；
未加深给「加深克隆」入口）；图谱**不设上限**（head 全取、「加载更早」不限次数）；tag **取全字段**
（`name` + `sha` + annotated 的 `tagger` / 时间 / 说明，非 annotated 留空）。两条口径写死：
**不设上限 ≠ 一次拉完**（分页照旧，尾部必须显示「已加载 N 条 · 更早历史未加载」，不许把截断画成尽头）；
体积可增长但**必须有清理出口**。

② **虚节点拍板「画」（方案 A）**：目标是工作台，多一个浅色节点值得。规格定死 —— HEAD 之上、
虚线 + 半透明、不画 sha；「工作区改动 + 索引已暂存」**合成一个**节点（stash 不进图）；
`dirty` 0 ⇄ N 走元素级淡入淡出（不播换页动画）；点它进「工作区」档；
因无 sha，**详情 / 复制 sha / 对比 / 回滚一律置灰并说明原因**。

③ **Git 模式形态拍板：不做全屏页、不跳页面** —— 现有那枚 Git 气泡的展开面板就是工作台，分三档
（折叠手柄 / 动作列表 / 视图），四档视图在**弹窗内部**换框。设置 → 本地仓库页头新增「管理」入口
（→ 本地仓库内容管理页：**按仓库**看占用并清理）；**登记一条后续问题**：本地仓库列表本身
「没按语义边界区分、太丑」，要重绘，但**不能硬加边界** —— 等动作收敛完再动。

④ **D11 拆开 merge 与 rebase**：merge **只新增提交、不改写历史**，与 D11 不冲突 —— **允许**；
仍禁 rebase / amend 已推送 / 强推。冲突的交互定为**冲突弹窗 → 详情对比页**，
且**弹窗出现的那一刻就开始预解析**（ours / theirs / base 的 diff 与冲突清单），
用户点进去内容是现成的（预解析契约写在 `git-mode-design.md` §6.4）。

⑤ **缓存拍板选 A**：不另建 Room / JSON 缓存 —— 应用本身是本地优先的实现，**本地仓库对象库就是缓存**
（配合 `fetch_deepen`：离线、无 API 限额、git 自己压缩与 gc）；「占用」= 本地仓库本身的体积，
清理走 ③ 的「管理」页。三个选项的对比留在 `git-version-tree-design.md` §4.1，免得以后重新讨论一遍。

⑥ **阶段 0 落地**（代码，零 Rust）：

- `ui/repository/GitPanelStage.kt`：三档一个 sealed 状态（`Collapsed / Actions / View(kind)`），
  不再是两个 Boolean（非法组合与「先设哪个」的时序问题；消息页显示模式两个入口各持一份 `remember`
  的教训）+ 纯函数 `panelDepth` / `panelBack` / `panelDirection`；
- `ui/navigation/PageTransitions.kt` 新增 **`PanelSwitcher`**：进档 / 退档复用
  `pageEnterTransition(±1)`（滑入容器 1/10 + 淡入 220ms、旧内容原地淡出 100ms），
  同层 fade-through（110 → 延迟 → 180，两段不重叠）；容器尺寸
  `animateContentSize(tween(220, EnterEasing))`；与另两个切换器一样下发 `LocalPageActive`
  （`PageTransitionsTest` 的钉子从「两个切换器」扩到「三个」）；
- `ui/repository/GitWorkspacePanel.kt`：「工作区」档 = 分支 / 领先落后 / 上游 / **改动文件清单**
  （`LocalRepoGitState` 现在带 `dirty`，徽标与清单同源）+「刷新」「同步」两个真能用的胶囊；
  提交 / 推送 / 合并入口按阶段接入，**如实写在面板里**，不放点了会跳到别处的假按钮；
  提交图 / 引用树 / 文件历史三档仍是 `available = false` → 渲染成「还没落地」的占位；
- 两个宿主（代码页 `CodePageGitPanel`、文件页）都用 `panelBack` 逐档退（返回键三层：视图 → 动作列表 →
  收起 → 页面），源码级钉子盯着别只改一边。

`tools/i18n/check-i18n.py --min-coverage 100`（新增 8 条中英资源 + `strings.tsv`）·
`:app:testDebugUnitTest`（839 例；新增 `GitPanelStageTest` 7 例、`PageTransitionsTest` 面板过渡 1 例）·
`assembleDebug` 通过。versionCode 194 → 195（一次提交 +1）。

### 1.0.92

**分叉决策页不再承诺「到桌面端解决」；新增《仓库内 Git 模式》设计稿（IA 重定 + 冲突解决 + 引擎缺口）**。

① **文案修正**：仓库在 1.0.91 起位于内部存储（`noBackupFilesDir/repos`），分叉决策页那句
「请复制仓库路径到桌面端解决」**彻底不可兑现**（外部存储时代在 Android 11+ 上其实也访问不到，
只是没人挑明）。四处文案一起改：

| 资源 | 原文案 | 新文案 |
|---|---|---|
| `action_keep_local_for_now`（原 `…_desktop`） | 保留本地提交，引导桌面解决 | 保留本地提交（暂不处理） |
| `note_keep_local_workspace` | …请复制仓库路径到桌面端解决。 | …远端不动，App 也不删除任何数据。App 不做 merge / rebase：这条分叉需要你自己决定怎么合（改动可逐文件查看 / 复制）。 |
| `state_kept_local_for_now`（原 `…_desktop`） | 已保留本地提交 · 引导桌面解决 | 已保留本地提交 · 远端未动 |
| `action_keep_local_only`（原 `…_and_desktop`） | 保留并引导桌面 | 保留本地 |
| `confirm_discard_unpushed_body` | …建议先到桌面端备份。 | …App 不提供导出，请确认这些改动已不再需要。 |

**注意这条修正改变了承诺的口径**：以前是「有出路（桌面端）」，现在是「如实说明没有出路」。
「把仓库整体取走」（导出 zip / 分享）**没有做**，已登记进 `git-mode-design.md` §11 未决问题 ——
要做出路得单独立项，别把它当成漏掉的文案。

② **新增 [`git-mode-design.md`](git-mode-design.md)（草稿 · 未落地）**：把「Git 工作在哪里做」重新定一次。
产品判断（2026-09）：设置里的本地仓库列表只适用于**直观统筹**（拉了哪些、什么状态），
真正的版本管理 / 树可视化 / 协作 / 提交 / 提交合并 / 冲突解决必须回到**仓库内的 Git 模式**里做。
文档给出：三处半成品宿主（代码页 Git 球 / 文件页 Git 球 / 本地仓库页行内动作）收成一个全屏工作台、
四档视图（提交图 / 引用树 / 文件历史 / 工作区）、操作面三档分层（工作台 / 决策页 / 明确不做）、
设置列表的收敛方案与**过渡期双入口**规则、冲突解决的完整路径（今天 App 里**走不到**冲突 ——
pull 只 FF、不做 merge，所以这是**能力缺失**而不是 UI 缺失：一次性列出 6 个合并接口 + 5 个冲突接口）、
引擎缺口表（8 项，含是否要重建 `.so`）、六阶段落地与验收，以及 8 条待拍板问题
（其中 D11 边界「允许 merge、仍不做 rebase」是冲突解决规模的前提）。
同时把 `git-version-tree-design.md` 重新定位为**它的可视化子设计**（图怎么画），
并在 `docs/README.md` §五 索引登记。

`tools/i18n/check-i18n.py --min-coverage 100` + `:app:testDebugUnitTest` 通过。
versionCode 193 → 194（一次提交 +1）。

### 1.0.91

**兼容处理：本地仓库搬到内部存储（绕开外部存储那棵 FUSE 子树的锁文件不兼容）+ 锁文件失败现场取证。1.0.90 留下的「未解」到此闭环**。

① **锁文件名终于露出来了**。1.0.90 的 beta 在真机上复现，失败原因不再被截断，锁文件是
`.git/HEAD.lock`：

```
failed to lock file '…/Android/data/com.branchbase/files/repos/SunsetRNE/Branchbase-Android/.git/HEAD.lock' for writing
```

一次 clone 会把 `HEAD` 写**两次** —— `git_repository_init` 建仓库时写一次（unborn HEAD），
收尾 `git_repository_set_head` 再写一次（libgit2 `clone.c` 的 `update_head_to_new_branch`）——
第二次撞上第一次留下的 `.git/HEAD.lock`。而同一台机器上，ext4（容器里 `/tmp`）与
`/sdcard/Download`（**同一个 FUSE、另一棵策略子树**）用同一份 libgit2 都克隆成功，
只有「App 私有的外部存储目录」必现。结论：**问题在这棵 FUSE 子树对 git 锁文件语义的兼容性**，
不在 libgit2 的用法上。

② **为什么不能只修 clone**：git 的每一次写都是「建 `<path>.lock` → 写完 rename」。
这条路不兼容，坏掉的就不只是 clone —— commit / pull / push 迟早会坏在 `.git/index.lock`、
`.git/refs/…lock` 上。所以把仓库根目录整体换到**内部存储** `noBackupFilesDir/repos`（`/data` 分区，ext4）：
`LocalRepos.base()` 改写 + 启动时**一次性搬迁**外部存储时代的仓库
（`migrateFromExternal`：跨文件系统时递归复制 + 删源，同名目标不覆盖，失败保留原目录、下次启动继续；
全部搬完才写标记）。结果落一行 `[本地] [Repos]` 日志（根目录在哪、这次搬了几个）——
下一份日志包能直接看出仓库落在哪。

用 `noBackupFilesDir` 而不是 `filesDir`：仓库可能几百 MB，而 `filesDir` 会进云备份 / 设备迁移
（`res/xml/backup_rules.xml`）—— 外部存储时代它不参与备份，换过来不该顺手改掉这条语义。
代价：仓库不再落在能用文件管理器翻到的目录里。但在 Android 11+ 上 `Android/data/`
本来就不对文件管理器与 MTP 开放，这条「路径可被桌面端访问」的承诺早就是空的。

③ **引擎侧加现场取证**：clone 失败时先 `clear_stale_locks`（只扫 `.git`、跳过
`objects/`/`modules/`/`lfs/`、只删普通文件），把「清掉了哪几个 `.lock`」写进错误文案。
这一条是给**下一次**用的：清单为空 ⇒ 锁是文件系统层面的假象（重试无用，得换文件系统）；
清单非空 ⇒ 真有残留文件。两种可能的处置完全不同，文案必须能区分。

新增/改动：`core/LocalRepos.kt`（内部存储 + `migrateFromExternal` + `moveTree`/`copyTree`，
5 例单测 `LocalReposMigrationTest`，含**源码级钉子：`base()` 不许改回外部存储**）、
`MainActivity.kt`（启动后台迁移 + `[Repos]` 日志）、`core/src/git/mod.rs`
（`clear_stale_locks` + `map_clone_error` 现场清单，新增 3 例单测）、
`docs/specs/local-git-engine-design.md` §4.2（事故闭环记录）。

`cargo test`（78 单测 + 4 集成）+ `:app:testDebugUnitTest` + `tools/i18n/check-i18n.py --min-coverage 100`
通过。versionCode 192 → 193（一次提交 +1）。

### 1.0.90

**「拉取仓库」从小字提示改成带真实进度的模态弹窗；clone 的目标目录与失败清场收口到引擎；失败原因不再被截断**。

起因是一份真机日志包（1.0.89 / OnePlus PJD110 / Android 16）：两次拉取都失败在同一句话上 ——

```
00:09:39 [远端] [libgit2] git clone SunsetRNE/Branchbase-Android 失败：未知错误: clone 失败:
         failed to lock file '/storage/emulated/0/Android/data/com.branchbase/files/repos/SunsetRNE/Branchbase-An
```

① **进度：从「一行小字」改成模态弹窗**。旧实现在列表上方挂一行「正在克隆…」+ 一闪而过的 Snackbar，
两个后果都很实在：浅 clone 在手机上要几十秒，**正常但慢**与**已经卡死**在界面上长得一模一样；
失败原因活不过三秒，而它恰恰是用户唯一能拿去判断「要不要重试 / 反馈什么」的东西。
现在引擎把 libgit2 的 `transfer_progress` / checkout 回调写成一份进程内快照
（新增 `core/src/git/progress.rs`，阶段契约 `idle/connect/receive/resolve/checkout/finalize/…`），
JNI 加两条只读接口（`nativeGitCloneProgress` / `nativeGitCloneCancel`），Kotlin 每 200ms 轮询一次：

| 弹窗上有什么 | 数据来源 |
|---|---|
| 确定进度条（阶段加权 5% → 70% → 85% → 99% → 100%） | `received/total` → `indexed/total` → `checkoutDone/checkoutTotal` → 收尾 |
| 阶段文案（连接远端 / 接收对象 142/380 / 解析增量 / 检出文件 / 写入引用） | `phase` + 三个计数，唯一真源 `clonePhaseText()` |
| 已接收字节数 | `bytes` |

**分母未知（远端没报总数）时给不确定进度条，不编百分比** —— 真机上「进度条冲到 100% 然后不动」
比没有进度条更让人以为卡死。运行中**不可点外部 / 不可返回键关闭**（关掉不等于停止），
要停只有「取消」：libgit2 没有取消句柄，唯一的中断点是回调返回值，所以先显示「正在取消…」，
等引擎真的停下。失败态**停在原地**把原因整段摊开，并给「重试」。

② **诊断：clone 的失败原因不再被单独截短**。上面那条日志恰好 **120 个字符** ——
`gitCloneDetailed` 当时写的是 `.take(120)`，**切掉的正是锁文件名**（唯一能定位「哪个文件锁上了」的线索）。
现在与其它写操作统一走 `engineErrorOrNull`（300 字符），并由源码级钉子
`CloneErrorDiagnosticsTest` 钉住（谁再把它单独截短，单测就红）。

③ **引擎侧的三条规约**（`core/src/git/mod.rs` 的 `clone_repo` 文档注释里是完整版）：
目录预检（**含 `.git` 的目录拒绝且一个字节都不动**；不含 `.git` 的半成品目录整体删掉重建 ——
旧 UI 用 `target.exists()` 一律回「已存在」，用户除了手动去文件管理器删没有别的出路）；
失败即清场（半个 `.git` 既不能用、又挡住下一次 clone）；锁文件报错保留完整路径并附一句可照做的处置。

**未解（已登记，不许读成已修好）**：`failed to lock file` 的**根因**。
失败后目录被 libgit2 自己删干净、两次尝试都复现，说明锁文件是**同一次 clone 内**留下的、
不是上一次的残留；而容器里按 ext4 / `/sdcard/Download` / 同 uid 三种配置都**复现不出来**
（分别成功），只有 App 自己的 `Android/data/<pkg>/files/repos/…` 会失败 ——
所以这一版给的是「更好的诊断 + 能重试 + 不留半成品」，不是根因修复。
`local-git-engine-design.md` §4.1 / §8 已按这个口径登记；下一次日志会带上完整路径，
届时可以定位到具体是哪个 `.lock`。
→ **已闭环（1.0.91）**：锁文件是 `.git/HEAD.lock`（一次 clone 写 HEAD 两次），
兼容处理是把仓库搬到内部存储 —— 见 1.0.91 条目与 `local-git-engine-design.md` §4.2。

新增/改动：`core/src/git/progress.rs`（新文件，6 例单测）、`core/src/git/mod.rs`（预检 / 清场 / 错误归一 +
8 例单测）、`core/src/bridge/jni.rs`（两条导出）、`CloneProgress.kt`（app 侧进度模型，新，11 例单测）、
`ui/repository/CloneProgressDialog.kt`（新，2 例单测）、`ui/profile/SubPageScreens.kt`（接线）、
`core/RustBridge.kt`（进度 / 取消 / 截断口径）、中英资源 11 条 + `strings.tsv` 同步、
`CloneErrorDiagnosticsTest`（新，2 例）。

`cargo test`（75 单测 + 4 集成）+ `:app:testDebugUnitTest`（826 例，含本次新增 15 例）+
`tools/i18n/check-i18n.py --min-coverage 100` + `assembleDebug` 通过；`.so` 已重建
（`nm` 能看到 `nativeGitCloneProgress` / `nativeGitCloneCancel` 两个新导出）。
versionCode 191 → 192（一次提交 +1）。

### 1.0.89

**沉浸式翻译判定引擎（一致即跳过 / 混排段落匹配性翻译）+ 混排使用规则；Git 操作管理的三处状态缺陷修复 +「版本管理树」设计草稿（未落地）**。

原先的判定只有一条口径：段落里「最长连续拉丁字母 ≥ 3 且汉字占比 ≤ 一半」才翻。它有两个后果：

① **一致的内容会被再翻一遍**。中文段落偶尔漏过阈值，服务端把原文原样还回来（没翻、
专有名词、from/to 写反），页面照样插一张**与原文逐字相同**的卡片 —— 用户看到的是
「翻译坏了」，而且每次重开页面都要把同一段再问一遍。现在译后会做一次**一致校验**
（大小写 / 全角半角 / 零宽字符 / 首尾标点都不算差异），一致的段落不插卡片，
并记进**判定缓存**：同一段在这个进程内**连请求都不发**（`Translator` 的不变式 3）。

② **汉字占比超过一半的段落整段被放弃**，于是段内真正需要翻的东西（`pnpm workspace`、
`pull request`）永远翻不出来。现在判定分三条规则（`TranslateDecision.kt`，
纯函数 + `TranslateDecisionTest` 27 例）：

| 条件 | 结论 |
|------|------|
| 段内没有需要翻的内容（无外语字母，或只有 `CI` / `a` / `3D` 这类零碎外语） | **一致 → 跳过** |
| 外语为主（目标文字占比 ≤ `hanRatioMax`，含整段外语） | **整段翻**（旧行为不变） |
| 目标文字为主、段内确有需要翻的片段 | **匹配性翻译**：只翻片段，按「片段 → 译文」配对展示 |

配套的几处：

- **片段切法**：连续外语字母吸收**夹在中间**的空格/标点/数字 —— `npm run dev` 是一个片段
  而不是三个词（逐词送翻会得到「npm 运行 开发」这种读不通的东西）；按出现顺序去重；
  片段本身与原文一致时不成对（`Docker → Docker` 没有信息量）；片段数 > `maxMatchParts`（6）
  说明这段其实以外语为主，回退整段翻。每个片段各自进缓存（同一片段全站只翻一次）；
- **判定在「占位符保护视角」下做**（与「保护代码与链接」开关无关）：URL / 行内代码 /
  `@提及` / 提交 SHA 在判定眼里是中性字符。少了这一层，`详见 https://… 的说明` 会被判成
  「有需要翻的内容」，片段是 `https`；
- **使用规则进设置页**（设置 → 沉浸式翻译 →「中英混排」，落盘键 `translate.matchPolicy`）：
  只翻外语片段（默认）/ 整段一起翻（旧行为，用于对照）/ 中英混排不翻（最省额度）。
  规则真源仍是 `PageRules`，随设置注入 `window.__bbTranslate.rules`，页面脚本的镜像
  （`01-core.js` 的 `analyze()`）与原生侧逐区间对齐（`isLatinLetter` 的区间是写死的，
  就是为了这份镜像）；
- **展示**：译文容器带 `data-bb-mode="match"`，里面是一组「原文片段 → 译文片段」
  （箭头与间隔号由 CSS 画）；仅译文模式藏掉原文片段只留译文；换目标语言 / 重扫是覆盖而非追加；
- **协议细分**：一批产物的元素变成混合类型 —— `""`（判定跳过）/ 字符串（整段译文）/
  对象（匹配性译文）/ `null`（翻译失败，`TranslatePagePayload`）。**`""` 与 `null` 必须分开**：
  页面靠「一批里一段都没插进去」判断服务不可用，而判定跳过也会「一段都没插进去」——
  混在一起时，一页全是中文的正文会被误报成「翻译失败」（旧脚本只看空串，所以这条同时改了
  `01-core.js` 的 `pump()`）；
- **可观测**：设置页多一行「本次运行判定：跳过 N 段 · 匹配翻译 N 段（N 对片段）」，
  每批的日志汇总也带上「判定跳过」与「匹配段数」（`TranslateStats` 新增 4 个计数）；
- **设置页新增一组三选一**（`ModeOptionRow`×3），中英资源成对新增 8 条（`strings.tsv` 同步）。

模块改动：`TranslateDecision.kt`（新）、`TranslateTextPolicy.kt`（收成文本原语 + 布尔入口）、
`Translator.kt`（`translateOne` / `translateBatch` 返回 `ParagraphTranslation`，
旧的 `translateParagraph` / `translateAll` 一并移除）、`PlaceholderGuard.restorePartial`、
`TranslatePagePayload`（新）、`01-core.js` / `02-dom.js` / `translate.css`；
app 侧 `ReadmeWebView`（编码改走模块）、`TranslateSettingsScreen`。
新增/改写单测：`TranslateDecisionTest` 27 例、`TranslatorTest` 21 例、
`PlaceholderGuardTest` +2、`TranslatePageProtocolTest` +3、`TranslatePageDomTest` +2。

**Git 操作管理（同版第二部分）**。三处缺陷是同一类坏法：**操作成功但界面不跟着变、
失败了却不知道原因**。单看都不致命，但它们会让用户对本地仓库的每一次操作失去信任
（「到底生效没有」），所以排在「版本管理树」面板之前修 —— 面板会把它们从「偶发」
变成「每次操作都看得见」。

① **文件页 Git 徽标提交后不刷新**：`rememberLocalRepoGitState(repo)` 的 tick 恒为 0，
本地提交成功后徽标仍是「待推送 0」（用户看到的是「点了没反应」）。现在 tick 有两个来源 ——
本页本地提交成功后 `gitTick++`，以及从子页（分支同步 / 决策页）回来时由
`rememberPageResumeTick()` 触发重读；后者顺带修掉「在同步页切完分支、回到文件页还是旧状态」。

② **本地仓库页分支胶囊陈旧**：`LaunchedEffect(repos)` 以**目录集合**为键，而 `mutableStateOf`
用结构相等 —— 切过分支再回到列表，目录一个字都没变，胶囊一直是旧分支名。
现在键里带上 `page`（回到列表即重读），并在非列表页提前返回：进子页不再白跑 N 次 `gitStatus`。

③ **Git 写操作的失败原因被吞掉**：`gitResetSoft` / `gitResetHardRemote` / `gitAmend` 返回
`Boolean`，把 native 的 `ERROR:…` 就地丢掉，决策页只能说「XXX 失败（原因见日志）」——
而**日志里没有那条原因**。现在统一成 **`null` = 成功 / 非空 = 原因**
（与 `gitPullDetailed` / `gitPushDetailed` / `gitPushSetUpstream` 同一约定），
5 处调用点把原因透进 `failureMessage(action, reason)`；归一化提成顶层 `engineErrorOrNull`，
新增钉子 **`GitErrorConventionTest`** 4 例（成功折叠成 null / 去 `ERROR:` 前缀 / 300 字截断 /
非 `ERROR:` 前缀按成功 —— 最后一条是为了不让行为相对旧实现漂移）。
`error_reset_hard_failed` 资源加位置参数 `%1$s`（中英 + `strings.tsv` 同步）。

④ **「版本管理树」设计草稿入库**：新增 `docs/specs/git-version-tree-design.md` ——
三个已拍板的决策（提交 DAG 图 / 引用树 / 文件历史树**三视图都要**；可视化 + 操作，
危险动作走决策页；接受为「离线 + 完整历史」做加深克隆）、双源分层（远端 REST / 本地 libgit2）、
三个待新增引擎接口（`log_graph` · `fetch_deepen` · `list_tags`）、分阶段落地计划，
以及那份「漏了不会红」的登记清单；同时登记进 `docs/README.md` §五 索引。
**本文是草稿：代码里没有任何实现**（全仓 grep 版本树 / 图谱零命中）。

### 1.0.88

**项目声明改为 MIT；非 MIT 的第三方内容单独声明来源**（治理，非功能改动）。

原先 README 的「许可证」一节写着「（待补充）」，仓库里既没有 `LICENSE`，也没有任何第三方声明 ——
而这期间已经引入了两处**非 MIT** 的内容，一起打进 APK：`:editor` 封装的 Sora Editor 是
**LGPL-2.1**，Rust 侧 vendored 编译的 libgit2 是 **GPL-2.0（附 LINKING EXCEPTION）**。
没有声明的后果不是「不好看」，而是**义务跟着二进制走、仓库里却查不到任何依据**。

新增两份文件，并把规矩写死：

- **`LICENSE`**：标准 MIT 全文（`Copyright (c) 2026 SunsetRNE`）。
- **`THIRD-PARTY-NOTICES.md`**：分两类列非自研内容 ——
  **§一 vendored**（本体在仓库里的文件：Feather Icons 的 `git-branch` 图标、按 Primer 裁剪重写的
  `github-markdown-light.css`、`README_DARK_CSS`、Primer 令牌、通知小图标的 `file_download` 形状），
  **§二 构建依赖**（会进 APK 的二进制：AndroidX / Compose / Material 3 / Room / Coil / coroutines 为
  Apache-2.0，JUnit 为 EPL-1.0（仅测试），org.json 为 Public Domain，Rust 侧 197 个 crate 为
  MIT 或 Apache-2.0 系）。规则是**每一条都要指到证据**（Gradle 缓存的 POM `<licenses>`、
  crates.io 的 `license` 字段、libgit2 的 `COPYING`、`openssl-src` 的 `LICENSE.txt`），
  取证口径写在该文件 §三。

两处非 MIT 的合规姿势也写进 §2.1，避免后来者「顺手改用法」把它破坏掉：
Sora Editor 以**未修改的库 + 动态链接**使用（第三方库只在 `:editor` 声明，换库/删除只动一个模块）；
libgit2 的**链接例外**明确允许链接进其它程序并分发，本项目未修改其源码。
另加一条维护规则：新增非宽松许可的依赖（GPL/AGPL/SSPL…）必须回该文件单列一行，
不允许用「见 Cargo.lock」含糊带过。

配套（用户可见）：README「许可证」一节补齐（MIT + 两处非 MIT 的表格 + 名称/图标不在授权范围的说明）；
关于页新增「开源协议 MIT」与「第三方声明」两个入口（直达仓库根那两份文件）——
用户装的是 APK 而不是仓库，声明得能在 App 里点到。新增两条资源（中英成对）。

`assembleDebug` + `testDebugUnitTest` + `tools/i18n/check-i18n.py` 通过。versionCode 189 → 190（一次提交 +1）。

---

### 1.0.87

**设置页行尾对齐 + 消息页两处设计修正 + 英文模式翻译收口**（真机截图反馈，四件事一次提交）。

**① 设置页「圈起来的 `›` 每行一个位置」—— 主轴权重盒缩到了文字宽度。**
截图里同一个 `›` 在「日志」行紧跟名称、「语言」行跟在值后，只有说明折行的「Git 代理」才碰巧贴右。
根因是 `Modifier.weight(1f, fill = false)`：`fill = false` 的权重盒只占**文字宽度**，
没花掉的份额变成**行尾空白**（`Arrangement.Start` 把空白留在最后），
于是排在它后面的值列与尾控件（`›` / `Switch` / 分段控件 / 胶囊 / 「去设置」）全部停在文字后面。
`SettingsRow.kt` 四处改 `weight(1f)`（`SettingsText` 名称列、`NavRow` / `DisabledNavRow` 内层 `Row`
与其原因列、`AccountRow` 名称列）。**名称/值的上限、折行宽度、省略阈值都没变** ——
值列右缘落到文本块末尾，尾控件落到行的 16dp 内边距上（截图里已贴右的 `Switch` 右缘就是那一列）。
钉子：`SettingsSpecTest` 新增 ⑬ 三条（扫**去注释**源码，只钉代码不钉「把坑写下来的注释」）。

**② 消息页「选了平铺，列表还是按仓库分组」—— 显示模式有两个入口，各持一份状态。**
`when (layout)` 四档本来就是对的（`FLAT` 走 `items(rows)`），不生效的是**状态**：
消息页右下角面板与「设置 → 通知 → 通知显示模式」各自 `remember { readNotifLayout(context) }`，
谁后改都不通知对方；而且只在「消息页还活着」时复现（切走再切回页面重建又对了），
属于最难查的「偶尔不生效」。新增 `NotifLayoutRuntime`（`StateFlow`，照 `ThemeRuntime` 的形状），
两个入口读写同一份，`MainActivity` 在 `setContent` **之前** `init`（否则存了分组档的用户会先闪一帧平铺）。
顺带把分类过滤收成纯函数 `notifCategoryBase`：计数与列表原先各写一遍过滤，
迟早出现「面板写 12 条、列表 11 行」——`NotificationCategoryTest` 6 例按「分类 × 本地覆盖」矩阵钉住。
（批量操作的目标集合也一并修正：它从 `items` 反查，而「已完成」的行来自本地归档、根本不在 `items` 里，
批量点下去是静默空操作。）

**③「已完成」缺「丢弃」和「向左滑丢弃不再显示」。**
新增 `NotifDiscard`（键 `notif_discarded`，本地覆盖层同 `NotifReadStore` 的口径）：
远端**不动**（GitHub 没有「永久隐藏这一条」的接口 —— `DELETE /threads/{id}` 是「完成」，
`subscription=ignore` 是「整个仓库/会话以后都别通知我」，都不是用户点「丢弃」的意思）、
本地归档**保留**（撤销只需把 id 从集合里去掉；删归档就找不回来了）、
四个分类 + 面板「过往 Issue」里全部消失、5 秒可撤销。
**不能靠删归档实现丢弃**：远端照样返回这条，删了它会掉回收件箱，看起来像「丢弃反而把它叫回来了」。
手势：已完成分类的归档行恒为已读，「左右滑 = 标记已读」在那里是空操作，
所以那个分类改成**只放开左滑 = 丢弃**（右滑没有可执行动作，放开只会滑出空承诺）；
动作面板里它是唯一的危险色动作，且只在「已完成」出现（放收件箱会与「完成」抢语义）。

**④ 英文界面下仍然漏出来的中文（枚举与参数默认值）。**
真机英文截图里 Theme / Language / Commit mode 都已是英文，只有主题三档、提交模式、通知状态与说明、
「去设置」按钮还是中文。共同点是**都不在 `@Composable` 函数体里**，所以 `extract.py` 一条也抽不到：
`ThemeMode` / `CommitMode` / `NavDestination`（底部导航，含侧边与玻璃两套变体 + 「收藏/固定/新建」气泡）/
`AccountStatus` / `AuthKind` / `ReleaseVariant` / `TaskStatus` / `TaskFilter` / `TlFilter` / `ReleaseType` /
`WatchLevel` / 复刻被拒原因，以及**参数默认值**里的 `DisabledNavRow(fixLabel)`、
`DangerConfirmCard(title)`（8 个调用点全不传）、`AppIcon(contentDescription)`（4 个调用点全不传）、
双击退出的 Toast。改法照 i18n 规范 §5.1 路径 A′：模型只带 `@StringRes`，解析留给调用方；
日志专用名另立 `logLabel`（约定固定中文）；未知值原样透出改用 `LocalizedText(raw = …)`。
顺带把设置「通知」行按 §4.3/§6.3 补成**状态胶囊 + 状态驱动的出路按钮**：
能弹授权框是「开启」，被系统关掉是「去设置」（写死「开启」时，系统里已关通知的用户点下去毫无反应）。
中英资源 1432 / 1432（覆盖率 100%）。钉子：`I18nUiTextTest` 4 例
（已资源化的界面文件不许再出现中文、`values-en` 条目值不许是中文、这批新键中英成对且值不同）。

**顺带**：`RepoViewerRelationTest` 的复刻原因断言改钉资源 ID（不再钉中文字面量 ——
后者每抽取一次就假红一次）；`SigningVerifyTest` 适配 `verifyCopy(variantLabel: String)`。
剩余待资源化的界面文案（`category=ui` 75 条 / `error` 36 条）与下一批入口记在
`docs/specs/i18n-migration.md` §十二。

`assembleDebug` 通过。versionCode 188 → 189（一次提交 +1）。

---

### 1.0.86

**修消息列表「已读」常驻透出 —— 滑动提示与标题、图标叠在一起**（真机截图反馈）。

`SwipeToReadRow` 的滑动提示（「✓ 已读」＋ 12% 蓝底）画在 `SwipeToDismissBox` 内容的
**之下**，本该只在左右滑时露出来。它常驻可见的根因是**内容层背景全透明**：
`NotificationRow` 的底色写成 `if (selected) Blue500 6% else Color.Transparent`，
而前一轮「未读不铺底」的修正（原型里给未读行铺 4% 蓝底是净负收益 —— 一屏里未读常是多数，
整片都「被高亮」反而更难分，见该处注释）把原本挡着的底色也一并去掉了。
改前有底色挡着、改后全透，滑动提示就直接透出来，看着像「已读」两个字和标题、图标重叠。

修法：在选中色**之前**先铺一层不透明的页面底色。它视觉上等于没有（与页面同色），
作用只是把滑动提示挡回内容下面；选中色照旧叠在它上面，视觉与改前一致。

**「不铺底」不等于「背景可以透明」** —— 这是这次真正要记住的一条：`SwipeToDismissBox`
的 `backgroundContent` 始终绘制在内容之下，凡是用它的行，内容层必须是**不透明**的。

顺带核过其余三个会「露出下层」的口子，都不成立，无需改：
FLAT 布局行与行之间无间隙（`Column { rowContent(n); RowDivider() }`）；
分组布局那 2dp 间距是**容器级** `Arrangement.spacedBy`、在 `SwipeToDismissBox` 之外；
按压缩放是 0.985（约 1dp，可忽略）。圆角两边都是 8dp，吻合。
`SwipeToDismissBox` 全仓只此一处（其余 `Color.Transparent` 都不在滑动容器里）。

`assembleDebug` 通过。versionCode 187 → 188（一次提交 +1）。

---

### 1.0.85

**界面语言体系落地：中英双语 + 语言切换页；硬编码中文全量资源化**（功能 + 工程治理）。

改造前 `res/values/strings.xml` 里只有 `app_name` 一条，界面文案全部以**中文字面量**写在
Composable 里。代价有两层：加一种语言等于把界面重写一遍；更要命的是**文案改不动**——
一句提示里的错别字要翻遍源码，而「提示语在某个分支下不对」这类问题无法用「换语言试试」定位。
现在支持 **2 种语言**（简体中文为默认、`zh-Hans`；英文 `values-en/`），覆盖率 **100%**。

① **语言清单不在 App 里，在资源目录里**。`app/build.gradle.kts` 开
`androidResources.generateLocaleConfig`，AGP 按 `res/values-*/` 生成 `locales_config.xml` 并挂到
`android:localeConfig`；默认那份是哪种语言由 `res/resources.properties` 的
`unqualifiedResLocale=zh-Hans` 声明（缺了它 `:app:extractDebugSupportedLocales` 直接失败 ——
它只能从目录名看出「有哪些语言」，看不出默认那份）。
于是**加一种语言 ＝ 加一个 `values-xx/` 目录**，语言页自动多一个选项，没有第二处要改的清单。

② **语言存系统里，App 不存**（`ui/settings/AppLanguage.kt`）。没有 prefs 键、没有 DataStore、
没有内存状态，每次现读 `LocaleManager`（API 33+）。收益不是省几行代码，而是**消灭一整类闪烁**：
不存在「异步读出来才知道该用哪种语言」，也就没有「首帧先渲染中文再跳英文」的窗口。
低于 33 没有这个能力，于是语言行**整行不出现**而不是置灰 —— 规范 §3.2.1 对「仓库凭据」用的是同一条
道理：禁用行必须给「怎么才能开」的出路，而这里的出路是换台新手机，不属于设置页能代办的事。
语言行的**名称用母语自称**（`English` 而不是「英语」）：用户在看不懂当前界面语言时也必须能认出
自己那一行；**说明用当前界面语言的他称**，两者相同时不给说明（否则就是用说明复述名称）。
第 2 条同时是**翻译完成度的门控**：`values-en/` 不存在时清单里只有默认语言，入口自动隐藏。

③ **切换不重建 Activity**。`AndroidManifest` 给 `MainActivity` 声明
`configChanges="locale|layoutDirection"`：框架把新 Configuration 应用到 Activity 的 Resources，
Compose 侧 `LocalConfiguration` 跟着更新、`stringResource` 自动取到新文案 ——
导航记忆、各页取数状态、WebView 滚动位置都不丢。

④ **资源化的三件基础设施**：

- `LocalizedText`（`ui/LocalizedText.kt`）—— 模型层与渲染层之间的「资源 ID + 参数」载体，
  支持**参数嵌套**（`LocalizedText(R.string.last_used_at, listOf(shortTime(iso)))`：把一个 helper 的
  结果拼进句子，语序仍由资源的 `%1$s` 决定）、**`raw` 原样透出**（后端新增的事件类型不被吞掉）、
  以及**复数**（`LocalizedText.plural(R.plurals.x, n, …)`）。中文只有 `other`，英文要分
  `one`/`other`（`1 minute ago` / `2 minutes ago`）—— 复数只能靠 `<plurals>` 表达。
  本体**不依赖 Compose**（`resolve(context)` 只认 `Context`），`@Composable` 的便利扩展单独放在
  `LocalizedTextCompose.kt`，这样模型类型才能留在纯 JVM 单测里被断言。
  ⚠️ 复数**只能经工厂入口构造**：`res` 是 `Int`，编译器和 lint 分不出它装的是 `<string>` 还是
  `<plurals>`，直接传 `quantity` 会到运行时才炸（`getQuantityString` 抛
  `Resources$NotFoundException`）。
- `Feedback(text, ok)`（`ui/repository/Feedback.kt`）—— 修掉一类**静默错色**：原先有两处用
  `it.startsWith("已")` 判断操作结果的语气，判据是**文案本身**。文案一旦抽成资源、界面切英文，
  这个判断永不成立，**成功提示会全部渲染成红色**。改用产生方给出的 `ok` 后还顺带修了一个
  **中文下就已经错**的：`state_default_branch_changed` 的文案是「默认分支**已**改为 %1$s」，
  首字是「默」不是「已」，这条成功提示一直被渲染成红色。
- **库模块资源带模块前缀**（`downloader_` / `imageviewer_`）。不是洁癖：库模块资源会与 `:app`
  **合并**，而 `action_close`、`label_image` 这类通用名在 `:app` 里已被占用 ——
  同名会被 `:app` 覆盖，值一旦不同就是「改了库模块却不见效」。
  `:imageviewer` 此前**没有 `res/` 目录**，这次从零建立。

⑤ **术语约定：中文侧 `issue` → 「讨论」**（英文侧保持 `issue`）。共 21 条资源**值**，
不动资源名（改名会连带改所有引用点）。**刻意保留两处**：
`login_key_scopes_bullets` 里的 `Issues` 是 GitHub 细粒度 token 界面上真实存在的**权限名**
（换成中文用户就找不到该勾哪一项）；`type:issue` 是 **GitHub 搜索语法**，不是给人看的文案。
另有一类永远不动：`when (state)` 匹配的是 **API 返回值**（`open`/`closed`/`merged`/`draft`），
与界面语言无关。

⑥ **校验进 CI**。`tools/i18n/check-i18n.py` 跑在两个工作流的**环境准备之前**（只解析 XML，几毫秒），
查结构与覆盖率、占位符一致性与格式串完整性（`%1%1$s` 这种只比集合查不出来）、`translatable` 一致性、
**跨模块语言子集**（库模块的 `values-xx/` 必须是 `:app` 的子集 —— `localeConfig` 按 `:app` 的 res 生成，
只在库模块加语言会「通知是德文、界面是中文、语言列表里还没有德语」）、以及**复数不变量**。

**现状与后续**（体系、范式、纪律、踩过的坑、剩余工作）全部收在
[`i18n-migration.md`](i18n-migration.md) —— 这份版本记录不重复它。

数字：`:app` 资源 **1384** 条（`values-en` 1383/1383 · **100%**，差 1 条是 `app_name` 标了
`translatable="false"`）；`:downloader` 27/27；`:imageviewer` 6/6。
生产代码剩余硬编码中文 **923** 条，其中 **206** 条是日志与设备信息，**按约定保持中文**
（翻译它会让 `tools/perf/frame-baseline.py` 的正则失配），不计入待翻译量；
其余 **717** 条需要接口改造（不在 `@Composable` 内、也拿不到 `Context`）——
**补翻译表推不动它们**，路线见 `i18n-migration.md` §5.1 A′ / §5.4 C′。

钉子：`StateLabelTest` 3 例（「未知状态返回 `null` 原样透出」这条语义，断言的是**资源 ID** 而非文案）；
`AppLanguageTest` 6 例（只钉**关系** —— 名称用母语自称、说明用他称、两者相同时不给说明，
不比对具体译文，避免跟 JDK 的 CLDR 版本一起假红）；
`NotificationModelsTest` 的相对时间断言从钉字面量（`assertEquals("5 分钟前", …)`）改成
钉 `res` + `quantity` + `args` —— 文案搬家不再假红。
app + downloader **835 例全绿**；`assembleDebug` 通过；`check-i18n.py --min-coverage 100` 通过。
versionCode 186 → 187（一次提交 +1）。

---

### 1.0.84

**「添加账号」修好了；返回落点改回你离开的那一页**（用户验证 + 反馈）。

1.0.83 的改法生效了 —— 用户确认「正常弹出相应的添加页面」，真机日志也对上
（`01:20:27 点「添加账号」` 之后新增欢迎页接管）。前四版都栽在同一个假设
（「某个状态变了，根布局会按我想的方式重渲染」）上，改成**状态机换页**之后这个假设不再需要。

用户同时提了一个体验问题，且判断是对的：**返回后落在个人页主页，而不是刚才的账号管理页**。
根因不是渲染，而是这次改动的一个必然副作用：

- 「添加账号」的登录页要整屏接管 ⇒ `state` 从 `LoggedIn` 变成 `AddAccountWelcome` ⇒
  `LoggedInGate → MainScreen` **整棵子树被销毁**；返回时重建，`selected` / `showProfile` /
  `subPage` 全部回到初始值。

修法：新增 `MainNavMemory`（进程内寄存点，与 `AddAccountFlow` 同一模式 —— 不受子树销毁影响）。
账号页在点「添加账号」前寄存「我在哪」，`MainScreen` 返回时**消费一次**并恢复：

| 寄存内容 | 为什么 |
|---|---|
| 所在 Tab | 用户可能从消息 Tab 进来的 |
| 是否停在个人页 | 它是个人页子页路由的入口，只存 Tab 不够 |
| 个人页的子页名（String） | 用名字存，`MainScreen` 不必依赖 `profile` 模块的私有枚举 `SubPage` |

**必须是「消费一次」**：读完即清空。否则用户下次正常启动还会被拽回「账号管理」——
那只是把「落点不对」换了个方向（这条有单测钉住）。

> 顺带确认 `rememberSaveable` **救不了**这种情形：它的值存在所在那层的
> `SaveableStateRegistry`，那层一销毁注册表就注销了。本仓 `PageTransitions.kt` 的
> `rememberSaveableStateHolder` 保住的是「**保活**的兄弟页之间切换」，与「整棵子树被换掉」
> 不是一回事 —— 这个区分值得记住。

新增 `MainNavMemoryTest` 8 例（含「消费一次即清空」「两个 Tab 都能记」）。
app 90 类 / 784 例全绿；`assembleDebug` 通过。versionCode 185 → 186（一次提交 +1）。

---

### 1.0.83

**「添加账号」第四次 —— 不再与渲染机制较劲，改由状态机换页**（用户反馈 + 真机日志）。

1.0.82 让根布局改走 `LoginViewModel.addingAccount` 这条流。用户装上后日志显示**通道通了**：

```
00:50:28.804  点「添加账号」→ 进入新增登录流程
00:50:28.804  新增流程标记 = true
00:50:28.817  登录根布局：新增流程=true 状态=LoggedIn 接管=false   ← 根布局读到了
```

但 `接管=false`，而 `接管 = addingAccount && state !is LoggedIn` —— 同一行里 `新增流程=true`
与 `接管=false` **同时成立**，是个自相矛盾的读数。无论它是 `LaunchedEffect` 取到了旧闭包，
还是那层条件渲染另有隐情，结论都一样：**我一直在假设「某个状态变了，根布局会按我想的方式
重渲染」**，而这个假设在这棵组合树里不成立（三版三种形式都栽在这上面）。

所以第四次换思路：**让登录状态机自己表达「正在新增」**。

- `LoginState` 新增 `AddAccountWelcome`（depth 与 `Idle` 同档）；
- `addAccount()` 直接把 state 置成它 —— **状态一变，`PageSwitcher` 必然换页**，
  这是本文件里唯一一处被反复验证过的换页机制，不再需要对组合行为做任何假设；
- 根布局里那套「条件渲染替换整屏」整段删除（连同它的诊断）。

顺带堵掉一个同类漏洞：**新增流程里从介绍页按返回会掉到「未登录欢迎页」**。
用户本来登录着，却看到一个「去登录」的界面，而 `Idle` 不拦返回键 —— 再按一下直接退出 App。
`loginBackTarget` 因此加了 `addingAccount` 入参：新增流程里的「介绍页 / 授权中 / 换 token /
2FA / 出错」一律回 `AddAccountWelcome`（填写密钥页仍先回介绍页，两级返回不跳级）。

收尾也统一：`finishAddAccount()` 一次清两条通道（`AddAccountFlow` 真源 + `addingAccount` 流），
否则登录成功后前者清了、后者还挂着 true，下次进账号页判定就不一致。

钉子：`KeyLoginTest` 补一例（新增流程里六个中间态的返回目标 + 不跳级）。
app 89 类 / 776 例全绿；`assembleDebug` 通过。versionCode 184 → 185（一次提交 +1）。

---

### 1.0.82

**「添加账号」第三次仍无响应 —— 改用已验证会重组的观察通道**（用户反馈 + 真机日志）。

1.0.81 让新增流程绕过 `PageSwitcher`、直接渲染欢迎页，并加了两句诊断。用户装上后日志给出
**决定性的半边**：

```
00:12:02.250  点「添加账号」→ 进入新增登录流程
00:12:02.250  新增流程标记 = true              ← 标记确实置上了
              （「登录根布局：…」那一行**一次都没重放**）
```

也就是说：**根布局根本没有因这个标记重组**。而根布局对 `LoginState` 的变化是确定会重组的
（冷启动 `Idle → LoggedIn` 每次都会重绘，日志里那条就在）。诊断代码本身也在部署的提交里
（已核对 `git show`），`Logger` 在 Release 下不过滤 —— 排除了「日志没打」的可能。

结论：那个进程内单例的 Compose 状态，在这棵真实组合树里**没有被订阅到**。
不再继续猜它的原因，改走**已经被证明会触发重组的那条通道**：

- `LoginViewModel` 新增 `addingAccount: StateFlow<Boolean>` 与 `addAccount()` / `endAddAccount()`；
  真源仍只有 `AddAccountFlow` 一处，ViewModel 只是把同一事实转发一次（**不是双写**）；
- 根布局由 `AddAccountFlow.activeState` 改为 `viewModel.addingAccount.collectAsState()`
  —— 与 `state` 同一条流；
- 账号页自己 `viewModel()` 取同一实例（Activity 作用域）来触发。

诊断加厚到三处，下次日志能一路定位：`登录根布局：新增流程=… 状态=… 接管=…`、
`新增流程：接管整屏，渲染欢迎页`、`新增流程标记 = …`。

钉子：`AddAccountFlowTest` 补两条**源码级**断言 —— 根布局必须收集 `viewModel.addingAccount`、
**不得**再直接读 `AddAccountFlow.activeState`（防止以后改回那条不可靠的通道）。

> 顺带修一处被自己碰红的既有测试：`SettingsSpecTest` 要求「二级页的返回目标与路由**同一行**」
> （防止返回目标与路由走散）。我把 `SubPage.Accounts` 那行拆成多行时踩了它 —— 已改回单行，
> 并在代码里留了注释说明为什么不能为了排版拆行。

app 89 类 / 775 例全绿；`assembleDebug` 通过。versionCode 183 → 184（一次提交 +1）。

---

### 1.0.81

**「添加账号」仍无页面响应 —— `AnimatedContent` 的 contentKey 没变，内容不重放**（用户反馈）。

1.0.80 修掉了「自己取消自己」，用户确认「UI 不再变形、按钮有反馈」，但**页面依然不动**。
新加的诊断日志给出了决定性证据：`点「添加账号」` 连打 5 次（23:14:59 → 23:15:03），
每次后面**什么都没有**。

根因在渲染层：新增流程中 `state` 仍是 `LoginState.LoggedIn`（用户本来就登录着），
而 [PageSwitcher] 底层是 `AnimatedContent`，它的 `contentKey` 取的是 `state::class` ——
**key 完全没变**。于是「同一格里把 `LoggedInGate` 换成 `WelcomeScreen`」这件事，
`AnimatedContent` 不会重放内容，界面纹丝不动。

改法：新增流程**不再走 `PageSwitcher`**，在它之前直接渲染欢迎页并 return：

```kotlin
if (addingInProgress) {
    WelcomeScreen(onOAuthLogin = …, onKeyLogin = …)
    return
}
```

这一步只需要「显示欢迎页（选登录方式）」这一格，不需要位移动画，直接渲染反而更贴合语义，
也少一层对 `AnimatedContent` 内部行为的依赖。`LoggedIn` 分支恢复成只剩 `LoggedInGate`。

诊断留档（都做了「只在值变化时记」防刷屏）：
`登录根布局：新增流程=… 状态=… 接管=…` 与 `新增流程标记 = …` 两行 ——
前者证明**根布局有没有读到标记**，后者证明**标记有没有被置上**。
这两句是这类「点了没反应」问题的定性工具，上一轮就因为没有它们而只能靠猜。

app 89 类 / 773 例全绿；`assembleDebug` 通过。versionCode 182 → 183（一次提交 +1）。

---

### 1.0.80

**「添加账号」没反应 —— 上一版自己取消了自己**（用户反馈：点添加账号毫无响应）。

1.0.79 把「添加账号」改成子流程（`AddAccountFlow`），但同时在账号页挂了一条
`DisposableEffect { onDispose { finish() } }`，想清「中途离开」的残留。两者**结构上互斥**：

1. `begin()` 置位 → 根 `LoginFlow` 判定「新增流程中」，于是**不再组合主界面**
   （刻意如此：否则账号页会与登录界面叠两层）；
2. 主界面一撤，`AccountsScreen` 立刻被 dispose → `onDispose` 马上调 `finish()`；
3. 标记被清 → 又渲染主界面 → **界面纹丝不动，看起来就是「点了没反应」**。

清理对象恰恰是它自己触发的。残留改用**兜底过期**：`AddAccountFlow.dropIfStale()`
在账号页**进入时**丢掉超过 2 分钟的标记（`STALE_MS`）。

顺带两处（同一轮用户反馈）：

- **账号页的状态徽标不再因「检查中」改变布局**：原来整块换成 `检查中…`（文案 / 配色 / 宽度
  全变），而这一行还有「登录方式 / N 个本地仓库 / X 分钟前检查」几个徽标，宽度一变整行重排，
  窄屏上溢出到行外、卡片高度跳动 —— 用户看到的就是「一检查 UI 就被撑高一下」。
  现在文案与配色**始终是状态本身**，只加一个固定 10dp 的转圈槽位（不检查时用等宽 Spacer 占着），
  于是「检查中」完全不改变布局；附带好处是检查过程中仍能看到**上一次的结论**。
- **徽标行改为横向可滚**：这几个徽标宽度取决于 login 长度 / 仓库数 / 时间文案，
  360dp 屏上本来就会挤出行外，溢出会让整行换行、高度跳动。
- 新增流程的开始/结束各记一行日志 —— 这个功能出过「点了没反应」，而当时日志里
  **查不到任何线索**（没有任何 tag 记录这一步）。

`AddAccountFlowTest` 7 → 12 例（新增兜底过期 5 例：刚进入不过期 / 超窗口丢弃 /
无残留时无操作 / 丢弃后重新计时 / `finish` 复位计时）。顺手把设计收敛了：
`active` 改成**纯标记**（原先写成「标记 && 未过期」，getter 用真实时钟而 `begin` 用可注入时钟，
两者混用导致一 `begin` 就被判过期 —— 测试直接红了）。app 全绿；`assembleDebug` 通过。
versionCode 181 → 182（一次提交 +1）。

---

### 1.0.79

**「添加账号」不再是登出 —— 回不去账号页的根因**（用户反馈）。

账号页那个按钮直接接的是 `onLogout`（`ProfileScreen` 的 `SubPage.Accounts` 那行）：
点一下＝**把自己登出**（`logout()` 删掉全局 `session` 键、状态回欢迎页）。两个后果：

① **回不去**：欢迎页刻意不拦截返回键（未登录时是「再按一次退出 App」），所以从账号页进来的
用户既回不到列表，按返回还可能直接退出应用；
② **凭据被误删**：只想再登一个号，当前账号的 `session` 却已经被清掉了。

改法：新增流程走 `AddAccountFlow`（进程内瞬时标记，与 `ThemeRuntime` / `NotifSnapshot` 同一模式
—— 触发点在 `AccountsScreen`，分流点在根 `LoginFlow`，层层传参会穿过三个签名，其中 `MainScreen`
只是转手）。`LoginFlow` 把 `LoggedIn` 那一格在新增流程中换成欢迎页，**主界面根本不组合**
（否则账号页会叠两层）。三条收尾路径都必须成立，否则用户会被永久留在登录页：

| 路径 | 机制 |
|---|---|
| 登录成功 | `LaunchedEffect` 观察到 `LoginState.LoggedIn` → `finish()` |
| 欢迎页按返回 | `PageBackHandler(enabled = 新增流程中 && Idle)` → 回账号列表 |
| 离开账号页 | `DisposableEffect.onDispose` → 中途切 Tab / 返回设置不会把标记留成 true |

配套的「谁成为当前账号」规则进 `AccountStore.planUpsert`（纯函数）：
**首登态**（`makeCurrent = true`）登完成为当前；**刷新态**（账号页新增）新号**不夺**当前账号；
两种情形下命中**当前那条**记录时一律不切换（否则「只是重新授权一下」会被切走）。

新增 `AddAccountFlowTest` 7 例；`AccountStoreIdentityTest` 24 → 29 例（新增/刷新的夺权规则 5 例）。
app 全绿；`assembleDebug` 通过。规则进 [`features-design.md` §1](features-design.md)。
versionCode 180 → 181（一次提交 +1）。

---

### 1.0.78

**账号身份 = host + login + 登录方式 —— 修掉「密钥登录伪覆盖 OAuth 记录」**（用户反馈）。

用户报了三件相关的事，查下来是**同一段代码**（`AccountStore.add`）：

```
account = all[exist].copy(session = session, auth = auth,
                          lastCheck = 0L, status = AccountStatus.UNKNOWN)
```

① **伪覆盖**：原来只按 `login + host` 匹配已有账号 → 「已经用 OAuth 登录过，再用密钥登录同一个
账号」会**原地覆盖**那一条的 session。用户看到的：账号列表里 OAuth 那条消失、只剩「PAT 令牌」，
像切换了登录方式；实际后果不可逆 —— OAuth 的 token 已被丢掉，下一次覆盖会把密钥那条也换掉，
**两种登录方式无法共存**（`session` 是全局单键，覆盖即永失）。
现在把 `auth` 算进身份（`indexOfSameIdentity`，纯函数 + 13 例钉子）：同方式 = 同一条（重新授权 /
token 刷新），不同方式 = **两条**，各自独立，可在账号页切换或单独删除。
本地数据不受影响 —— 仓库与任务按 **login** 隔离（`repos/{login}/…`），两条指向同一份。

②③ **「启动的检查结果与设置页不共通」+「检测时机」**：原来每次 `add` 都把
`lastCheck = 0 / status = UNKNOWN` 写死，而 `AccountChecks.isStale` 正是以 `lastCheck` 判
「结论是否陈旧」—— 于是**每次登录都让「刚查过」作废**，设置页必然重探一遍。真机日志的形状：
启动探测 20:32:33 → 93 秒后又探一次（同一枚 token，指纹一致）。现在保留原有 `lastCheck` /
`status`：token 若真失效，下一次探测自然会纠偏。

两条兜底规则（都为了「不让凭据被静默丢掉」）：

- **老记录（`auth` 缺失 → `UNKNOWN`）是通配，但精确优先**：先找精确匹配，找不到才退回 UNKNOWN
  那条并就地升级 auth。否则老记录只要排在前面，就会抢走本该命中具体方式那条的机会；
- **账号 id 生成显式避让已占用值**（原先是 `acc-<36进制时间>-<0..999 随机>`，撞了会让
  `current_account` 指不到任何记录）。

另外：删除确认框点名是**哪一种**登录方式，并说明「这次只删这一条」。

新增 `AccountStoreIdentityTest` **24 例**（13 例判据 + 11 例端到端 `planUpsert`：
「已有 OAuth 再用密钥登录 → 变两条且旧 session 不丢」「同方式再登录 → 更新同一条不新增」
「登录不再重置检查结果」「老记录就地升级 auth」「同一毫秒创建的 id 不冲突」）；
`add` 因此瘦成「读 → planUpsert → 写」，决策本体成了不碰 Context 的纯函数。
app 全绿；`assembleDebug` 通过。
规则进 [`features-design.md` §1](features-design.md)。versionCode 179 → 180（一次提交 +1）。

---

### 1.0.77

**账号探测：把「为什么失效」查出来 + 结论不再每次进设置都重报**（第三份真机日志，1.0.75）。
起因是用户反馈「设置页令牌老是报失效，实际有效」。日志给出的答案是**这次不是误判**：
`/user` 与复核端点都回真 GitHub 形状的 401（`Bad credentials`），同场会话没有任何成功请求 ——
1.0.61 的三条防线表现正确。但暴露了两个真问题，且都是「日志/时机」层面的：

① **响应头被整条链路丢掉，答不出「过期还是被吊销」**。`core/src/api/client.rs` 只保留
「状态码 + 响应体」，而 GitHub 在**每一次**认证请求（**包括 401**）上都回
`GitHub-Authentication-Token-Expiration`。新增独立轻量探针：`HEAD https://<host>/`
（HEAD 不计速率限制）读该头，产出白话结论 —— 已过期（去重新生成）/ 未到期（401 是别的原因）/
未回过期头（可能被吊销，或不是该 host 签发的）。**探针不参与判定**，坏了不影响账号页。
坑：GitHub 的时间格式是 `2026-09-30 07:12:34 UTC`（空格分隔、没有 `T`），按 ISO 解析直接抛
`DateTimeParseException`；三种形态都兜，解析失败返回 null（宁可说查不到也不编时间）。

② **「老是报」是时机问题，不是判定问题**。账号页原来对 `status == UNKNOWN` 的账号无条件重探，
而结论一旦写回，用户每次进设置都会被再报一次同样的事 —— 同一件事反复说，主观上就成了
「老是报」。加最小间隔 `AUTO_RECHECK_MS`（5 分钟，纯函数 `isStale` 有单测）：**结论要稳定，
翻案交给「全部检查」**。

③ **日志下次能自证**：补 **host**（多 host 才知探的是哪个端点）、**token 指纹**
（sha256 前 8 位，日志会被导出贴进 issue，**绝不记原文**）、**探测时刻**（设备时钟偏离会让
GitHub 拒收刚生成的 token；本次已用它排除该可能）。响应体截断 160 → 400 字符 ——
160 会把 GitHub 错误体里的 `request_id` 切掉，而那是向 GitHub 追问时唯一的凭据。

新增 `AccountChecksTest` 19 例（三种时间形态解析 / 解析失败返回 null / 三种白话结论 /
无过期头不产噪音 / 边界「正好过期」/ `isStale` 四态 / 指纹稳定且不含原文）。
app 732 例全绿。设计结论进 [`reachability-design.md`](reachability-design.md) §七。
versionCode 178 → 179（一次提交 +1）。

---

### 1.0.76

**消息列表重绘 —— 一屏 8 条一模一样的 CI 通知折成 1 行**（原型 `design/messages-redesign/list-v2.html`）。

① **根因不是卡片难看，是没有层级**。真机截图里连着 8 条 `Build workflow run failed for main
branch`：同仓库、同分支、同一天，标题**逐字相同**。任何单行画得再好，重复 8 次都是噪点 ——
先把重复去掉，再谈好不好看。这与动态页「最近 30 条里 28 条是 PushEvent」是同一个问题。

② **折叠口径照抄动态页的 `collapsePushes`**（`ui/profile/ProfileScreen.kt`，那边有
`ActivityFeedTest` 12 例），新纯函数 `collapseCiRuns`（`ui/notification/NotificationModels.kt`）：

- 折叠键 = **同一仓库 + 同一 subject.type + 同一工作流 + 同一分支 + 同一天（本地时区）**，
  且 reason 必须是 `ci_activity`；
- 只在**相邻**条目之间折叠（输入已按时间倒序），**不跨天** —— 跨天会把「今天 3 次 + 昨天 5 次」
  写成 8 次，那是编造事实；
- 工作流名与分支**从标题解析**（复用 `parseCheckSuiteTitle`，CheckSuite 常没有 `subject.url`）；
  解析不出来就**不参与折叠**，宁可多几行也不猜；
- `updatedAtMs <= 0`（时间未知）一律判为不同天，避免凭空造出「今天连续失败 N 次」；
- 组内保留**最新一次**（组内首条）为行代表，`fold.runs` 保留每一次的原始标题与时间 ——
  行内「展开其余 N 次」可原地铺开，**信息一条不丢**。

③ **折叠行代表 N 条，所有写远端的地方都要打散**（这是本次最容易漏的接线）：

| 路径 | 展开方式 |
|---|---|
| 点击已读 / 长按「标记已读」 | `Notification.allIds` |
| 标记完成 | `allIds`（远端逐条 DELETE） |
| 批量已读 / 完成 / 静音 | `expandFoldIds(selected)` |
| 面板「复制链接」「恢复未读」 | 同上 |
| 顶部「全部已读」 | 端点仍是一次性 `mark-all-read`，但**本地已读集合**要按 id 逐条写全 |

不展开的后果很具体：把一折标为已读后，服务端还剩 7 条未读，刷新回来它们重新组成一个新折叠行
（表现为「标了已读没生效」）。

④ **折叠行要么整行成功、要么整行回滚**（`rollingBackRows` + 4 例钉子）。一折 8 条只失败 1 条时
按条回滚，会让这一行显示成「已读」而其中一条在服务端仍是未读 —— 与 `bulkRollbackTargets`
要解决的「本地与远端不一致」是同一个问题。批量 Toast 与撤销文案一律**按行**计数
（界面上这一折就是一行，报「成功 8 条」而只动了一行会让人以为误伤了别的消息）。

⑤ **列表从「卡片盒」改成「行 + 1dp 分隔线」**。浅色板里 `canvas` 与 `canvasSubtle`
**都是纯白**，卡片一直只靠那圈灰边撑着，一屏十几个盒子正是「空格子」观感的来源；
深色下 `canvasSubtle #161B22` 与页面底本来就有层次差，改成行之后两套都成立。
未读去掉整片浅蓝底，只留 3dp 竖条 + 加粗标题 + 尾点 —— 竖条仍是 `matchParentSize` + `drawBehind`
的 overlay 绘制（**不占布局宽度**，未读与已读行的正文宽度逐像素相同，这条硬约束没动）。
骨架屏同步去掉卡片壳（**骨架与真实行的结构必须一起改**，否则加载完会抖一下）。

⑥ **原因标签只在「需要你动手」时出现**（`Notification.reasonHighSignal`）。保留
提到了你 / 请求你审查 / 分配给了你 / 安全警报；抑制 CI 运行结果 / 你订阅的 / 评论了 /
状态更新 / 你创建的 —— 结论已经在标题里，标签只是重复占位。**文案没丢**：长按动作面板仍读
`reasonLabel`。

⑦ **把「填充色当文字用」这个老错再修一遍**。原因标签原样照抄原型时用了 `TintRole.color()`
（填充色）压 12% 同色底，实测 **WARNING 2.64 / DANGER 3.85 / ACCENT 4.38** —— 三档全在
WCAG AA 以下。新增 `TintRole.textColor()`（`ui/theme/TintRole.kt`）把真源里本就存在的
`AccentText` / `SuccessText` / `DangerText` / `WarningTextStrong` 接进角色系统，实测
**6.25 / 5.08 / 6.44 / 6.03**。`TintRole.DONE` 是唯一直接塌回填充色的一档（真源没有 `doneText`
角色，而 `Purple500` 压 12% 自色底是 5.44，已过线，不为它新造色值）。
行首图标同样改用文字色，并去掉那层 12% 同色底（深色下几乎看不见，白占一层）。

⑧ **未读徽标按行报数**（`unreadRowCount`）：折叠行在屏幕上就是一个未读点，报 8 会让徽标
与眼睛看到的对不上。注意顺序 —— 必须**先折叠再筛未读**；反过来先把未读挑出来的话，组里已读的
那几条被抽走、折叠组不再成立，「8 条折成 1 行」会退回 3 行，徽标报 3 而屏幕上只有一个点。

⑨ **钉子全绿**：`NotificationCiFoldTest`（新，22 例：基本折叠 / 明细留底 / 不跨天 / 不跨仓库 /
不跨分支 / 不跨工作流 / 不跨 run 类型 / 中间夹别的通知即断开 / 标题解析不出不折 /
时间未知不合并 / 重绘后总条数不变 / 单条不呈现折叠 / 未读行数 5 例）、
`NotificationBulkRollbackTest` 6 → 10（折叠行整行回滚）、
`ThemeContrastTest` 5 → 8（原因标签必须用文字角色 + 浅深两套实测对比度 + 低信号原因不得挂标签）。

⑩ **真机复看后的三处修正（未读不铺底 / 展开控件去边框 / 明细从属）**

真机截图给出了原型阶段看不到的结论：**一屏四行几乎同色，「读过」与「没读过」反而更难分**。
三项修正：

| 改前 | 改后 | 为什么 |
|---|---|---|
| 未读行铺 4% 蓝底（`infoSurface`） | **完全不铺底**，未读只由「竖条 + 粗标题 + 圆点」表达 | 未读常是多数，每行都带底色等于整片都「被高亮」—— 高亮失去对比对象就不再是高亮。已读行保持干净白底，有没有未读一眼可数 |
| 未读竖条 3dp | **4dp** | 去掉底色之后它是主要的未读信号，3dp 真机上偏细 |
| 「展开其余 N 次」带 1dp 描边的胶囊 | **无边框**的一行蓝色文字 + 箭头 | 它压在标题下方、每折都出现一次，带边框时读起来像「第二个标题」，把本已收起来的重复又加回来一层。它是动作，不是内容 |
| 展开明细继承父行底色 | 父行不铺底 + 明细用三级文字色 + 左侧导轨 | 父行有底色时明细跟着继承，整块连成一片、层次被抹平；`「1 条汇总 + N 条明细」`的层次现在才真正成立 |
| 选中行 8% 蓝底（无边） | **6% 蓝底 + 1.5dp `BorderControl` 描边** | 只差 6% 的底在浅色屏 / 强光下几乎看不出哪几行被选中；边框提供第二信号 |

> **教训（值得单独记）**：原型的对比度审计**只能证明「每一对前景/背景达标」，证明不了
> 「一屏里有多少行被重复高亮」**。`theme-audit.js` 33 项全过、`ThemeContrastTest` 全绿，
> 但真机观感依然是「一片蓝」—— 审计的口径是像素对，而问题在版面节奏。
> 这类结论只能靠真机截图，原型阶段看不出来。

⑪ **钉子全绿**：`NotificationCiFoldTest`（新，22 例：基本折叠 / 明细留底 / 不跨天 / 不跨仓库 /
不跨分支 / 不跨工作流 / 不跨 run 类型 / 中间夹别的通知即断开 / 标题解析不出不折 /
时间未知不合并 / 重绘后总条数不变 / 单条不呈现折叠 / 未读行数 5 例）、
`NotificationBulkRollbackTest` 6 → 10（折叠行整行回滚）、
`ThemeContrastTest` 5 → 8（原因标签必须用文字角色 + 浅深两套实测对比度 + 低信号原因不得挂标签）。

⑫ **仍未做**：深色主题下的真机复看（本轮截图是浅色）；「未读竖条在圆角行首的裁切」
与「深色下分隔线可见度」两处也只在浅色下看过。
原型页（`design/messages-redesign/list-v2.html`）已同步本轮修正，带烟测与对比度审计两个脚本。

⑬ **顺带修：账号探测「老是报令牌失效」**（第三份真机日志 `2026-09-23 194714`，1.0.75）。
结论先说：那次**不是误判** —— `/user` 与复核端点都回真 GitHub 形状的 401
（`{"message":"Bad credentials","documentation_url":…,"status":"401"}`），
同场会话没有任何成功请求。三条防线（1.0.61 加的）表现正确。但暴露了两个真问题：

- **答不出「为什么失效」**：`core/src/api/client.rs` 只保留「状态码 + 响应体」，把响应头全丢了，
  而 GitHub 在**每一次**认证请求（含 401）上都回 `GitHub-Authentication-Token-Expiration`。
  新增独立轻量探针（`HEAD https://<host>/`，不计速率限制）读它，产出一句白话：
  已过期（去重新生成）/ 未到期（401 是别的原因）/ 未回过期头（可能被吊销）。探针**不参与判定**。
  时间格式的坑：GitHub 回的是 `2026-09-30 07:12:34 UTC`（空格分隔、**没有 `T`**），
  按 ISO 解析直接抛异常，因此三种形态都兜且解析失败返回 null。
- **「老是报」是时机问题**：账号页原来对 `UNKNOWN` 的账号无条件重探，结论写回后
  用户每次进设置都再被报一次同样的事。加最小间隔 `AUTO_RECHECK_MS`（5 分钟）：
  结论要稳定，翻案交给「全部检查」。
- 日志补齐 **host / token 指纹（sha256 前 8 位，绝不记原文）/ 探测时刻**（设备时钟偏离会让
  GitHub 拒收刚生成的 token，本次用它排除了该可能）；响应体截断 160 → 400 字符
  （160 会把 `request_id` 切掉）。新增 `AccountChecksTest` 19 例。详见
  [`reachability-design.md`](reachability-design.md) §七。

---

### 1.0.75

**产物不再打包 —— 浏览器直接下到裸 APK**（1.0.74 的收尾：那条通道原来仍是个装不了的 zip）。

① **为什么还有这一版**。1.0.74 把产物拆成了「一个 APK 一个 artifact」，但**下载下来仍是 zip** ——
我当时按「GitHub Actions 的产物下载永远打包」下的结论，**这个结论是错的**：
`actions/upload-artifact` **v7 起支持 `archive: false`**（GitHub 2026-02-26 上线，
见 [官方 changelog](https://github.blog/changelog/2026-02-26-github-actions-now-supports-uploading-and-downloading-non-zipped-artifacts/)），
浏览器直接下到裸文件。原来用的 `@v4` 属于旧行为。

② **改法**。两个工作流的 `actions/upload-artifact` 由 `@v4` 升到 **`@v7`** 并加 `archive: false`；
publish 侧的 `actions/download-artifact` 由 `@v4` 升到 **`@v8`**（v7 上传的非打包产物只有 v8 能取回）。

`archive: false` 有两条硬约束，写错会直接失败或名字对不上：

- **只能传单个文件** —— 所以仍然一个文件一个 step（这正是 1.0.74 拆产物带来的便利）；
- **产物名直接取文件名，`name:` 参数被忽略** —— 所以两处都删掉了 `name:`（留着也不生效，
  只会误导）。APK 的 `.apk` 后缀因此是必需的，由 `build.gradle.kts` 的 `outputFileName` 保证。

publish 的下载同步改：`pattern: Branchbase-*` → `pattern: '*'`（非打包产物的名字不再带统一前缀，
变成 `Branchbase-<sv>-debug.apk` / `-perfBeta.apk` / `libbranchbase_core.so`），仍配
`merge-multiple: true`。取回来后正好是 `files:` 与 `cp libbranchbase_core.so` 期望的那三个路径。

③ **App 侧跟着改**（这是本版为什么还要 +1 versionCode）：

- 下载文件名不再拼 `.zip`（改成 `fileName = artifact.name`，它已经以 `.apk` 结尾）——
  拼上会让安装器拿到一个 MIME 对不上的文件；
- `extractSingleApk` 改成**两种形态都认**：`looksLikeApk`（根目录有 `AndroidManifest.xml`）→
  原样返回；否则按「装着唯一一个 APK 的 zip」解出来。
  **APK 本身就是 zip，光看魔数分不出这两种**，所以判据取 `AndroidManifest.xml`。
  存量（旧行为上传的）产物同样能装，不必区分版本。
- 钉子 `ArtifactInstallTest` 11 → **13**。

④ **测试**。`:app` 编译通过，`com.branchbase.ui.repository.*` 全绿。

⑤ **上线后实测（决定性验证）**。产物下载回来**确实是裸文件**，三条硬证据：

```
● Branchbase-1.0.75-…-perfBeta.apk   28,626,931 B
    Content-Disposition: attachment; filename="Branchbase-1.0.75-…-perfBeta.apk"
● Branchbase-1.0.75-…-debug.apk      37,887,925 B   同样带 .apk 文件名
● libbranchbase_core.so              11,104,184 B   头 4 字节 7f454c46（ELF 魔数）
```

体积是**未压缩的 APK 原大小**（perfBeta 28.6MB，而 1.0.74 打包后是 21.9MB），
且 `.so` 的头部是 ELF 魔数 —— 裸共享库不可能出现在 zip 容器里。产物名也确认直接取自文件名
（`name:` 参数确实被忽略）。保留期 30 天，publish 的 v8 下载全绿。

**⚠️ 这次上线还撞到一件事**：`v1.0.75-20260923-1755-326e3ab-beta` 那次运行（#35845394990）
**连同它的产物一起从 GitHub 上消失了** —— 运行接口连查三次 404、`head_sha` 查到 0 个运行、
仓库级产物列表里没有任何 1.0.75 条目，而**它产生的 release 活着**（附件完好）。
最后用 `workflow_dispatch` 重跑（同一提交、不新增提交）才拿到上面的验证数据。
判定与那次 `assets` 反复横跳同源：GitHub 侧的数据一致性问题。

---

### 1.0.74

**「刚发布的版本在 App 里看不到附件」—— 补齐第二条取包通道**（发布页那条断点 + CI 产物那条断点）。

① **现场**。v1.0.73 的三个 beta release，在 App 的发布页上全都没有附件，而**附件一直是好的**。
同一个 release 三条取值路径给出两种答案：

| 路径 | 结果 |
|------|------|
| `GET /repos/{o}/{r}/releases`（列表）| `assets: []` ← **App 只读这个** |
| `GET /repos/{o}/{r}/releases/{id}`（单体）| 3 个 |
| `releases/expanded_assets/{tag}`（网页）| 3 行，带 sha256 |

> ⚠️ **「窗口」这个说法是错的，已在后续提交里更正。** 第一版按**单点采样**推出「1.5~2.6 小时」，
> 后来每 4 分钟采一次、采了一小时才看清：**同一个 release、同一个端点，值在 `0` 与 `3` 之间
> 反复横跳**（09:42 恢复 → 09:47 又变回 0 → 10:15 恢复 → 10:20 又全部变 0 → 10:24 起稳定）。
> 这是 GitHub 后端**多副本数据不一致**，不是按 release 年龄过期的缓存。保留原文以便对照。

窗口约 **1.5~2.6 小时**（发布后 1h25m 仍为空、2h38m 已恢复），之后自愈。跨仓库对照佐证不是
GitHub 全局故障：`neovim/neovim` 3.7 小时前的 release（13 个附件）两条路径一致。
网页端也露了同一个馅 —— 发布页标题旁的「Assets **N**」计数显示成 `0 + 2 个自动源码包`，
而**同一页的附件列表本身是完整的**（列表由另一个端点渲染）。这也说明源头在 GitHub 那份
`assets` 元数据，与客户端解析无关。

**代价**：`刚发完版想立刻装` 是最常见的动作，而它恰好落在这段时间里。

② **发布页那条断点**。App 读列表接口，那就在它报空时补一次单体接口：
`parseSingleRelease`（单体返回对象，套层方括号复用 `parseReleases`，同 `parseWorkflowRun` 手法）
+ `releasesNeedingAssetBackfill`（只挑 `assets` 为空的、最多 3 条、`id == 0` 跳过）。
正常仓库**零额外请求**。钉子 `ReleaseAssetBackfillTest`(6)。

③ **CI 产物那条断点**。产物是发布页之外的第二条取包通道，但它原来**走到一半**：

```
发布附件  →  下载 .apk  →  有「安装」
CI 产物   →  下载 .zip  →  结束（`app`/`downloader`/`core` 里 ZipFile 零命中，手机上解不了）
```

> ⚠️ **本条下面这句结论是错的，已在 1.0.75 更正，保留原文以便对照**：
> 「Actions 的产物下载时永远是 zip」—— 那是 `upload-artifact` **v7 之前**的行为；
> v7 起支持 `archive: false`（GitHub 2026-02-26 上线），浏览器直接下到裸文件。见 §二 1.0.75。

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

## 三、`versionCode` 流水（205 → 129）

`versionCode` 每次提交前递增：**有多少次提交变更多少次版本码**（一次发布也算一次提交）。

> 更早的版本码没有逐条留存，流水从 **129** 开始。

- **205**：Git 模式阶段 5（UI 主干）—— 合并决策页 + 冲突弹窗（出现即预解析 + 进程内缓存）
+ 冲突详情页（逐文件 ours↔theirs diff / 用我方 / 用对方 / :editor 手工 / 提交合并 / 放弃合并）
+ 面板的合并中状态条与三枚出口 + 两宿主接线 + 提交前敏感扫描
+ `analyze_conflicts` 补三方内容字段 + 提交身份 helper 收成一份
+ `MergeModelsTest` 6 例、`MergePreparseTest` 5 例、`GitWorkbenchWiringTest` +3 例
+ 新字符串 66 条（一次提交，故 +1）

- **204**：Git 模式阶段 5（引擎那半）—— 合并与冲突七条接口（`merge_branch` / `merge_state` /
`analyze_conflicts` / `resolve_conflict` / `write_resolved` / `merge_continue` / `merge_abort`）
+ JNI 导出 + 重建 `.so` + `repo_status` 只增 `merging` + 三条前置（浅克隆 / 已在合并中 / 工作区脏）
与路径收口 + 网络回调 / fetch refspec / patch 截断三处收成一份
+ cargo +13 例（一次提交，故 +1）

- **203**：提交图的「未推送段」（引擎 `log_graph` 逐条带 `unpushed`，与 `repo_status` 的 `ahead`
同口径、没有上游一条都不标 + 重建 `.so`；行尾「未推送」小字 + 只数这一屏的脚注；REST 来源不标）
+ §7/§9 两处旧账对齐 + `CommitGraphSourceTest` +3 例、cargo +2 例（一次提交，故 +1）

- **202**：「文件历史」档落地（本地 `log_file` 优先 / REST `?path=` 兜底；浅克隆时的加深入口
与提交图共用运行器；REST 来源的行不可点）+ `available` 四档全开 + 提交图标题兜底文案资源化
+ `FileHistoryModelsTest` 9 例、`GitWorkbenchWiringTest` +1 例（一次提交，故 +1）

- **201**：本地 diff 页（工作区改动行 / 提交图提交行 → `diff_worktree` / `diff_commit`）
+ 引擎 `show_untracked_content`（未跟踪文件带内容 + 两段按下标对齐的 cargo 钉子）
+ 共享 `DiffLineRow`（从 BranchCompareScreen 抽出）+ 新全屏页登记
+ `LocalDiffModelsTest` 12 例、`GitWorkbenchWiringTest` +1 例（一次提交，故 +1）

- **200**：Git 模式阶段 4（前半）—— 引擎 `fetch_deepen`（unshallow，复用 clone 的进度 / 取消通道）
+ JNI 导出 + 重建 `.so` + 提交图**双来源**（本地 `log_graph` 优先、浅克隆排除、REST 兜底 + 分页键分家）
+ 面板「加深历史」出口（`TaskKind.DEEPEN` + 复用 clone 的进度弹窗，两个宿主各接一次）
+ `CommitGraphSourceTest` 11 例、`GitWorkbenchWiringTest` +2 例（一次提交，故 +1）

- **199**：Git 模式阶段 3（引擎那半）—— 五个本地只读接口（`log_graph` / `list_tags` / `log_file` /
`diff_worktree` / `diff_commit`）+ JNI 导出 + 重建 `.so`（`nm -D` 确认符号）+ 引用树档接上 tag
（annotated 全字段、轻量留空不编值）+ 删掉「按阶段接入」占位串（一次提交，故 +1）

- **198**：Git 工作台接线收口（两档接上分支管理出口 + 胶囊行改 FlowRow、设置列表加统筹说明；
「改动清单点开看文件」试过又退回并登记 —— 查看器只有远端来源）
+ 日志插桩（新锚点「Git工作台」：动作按 key、档位按枚举名、返回退档、两条进入、三档取数）
+ 标准回归插桩（`GitWorkbenchWiringTest` 5 例：面板内零 git 写操作、两宿主出口、进入必带
`openGitPanel`、锚点四入口、图形档取数留痕）（一次提交，故 +1）

- **197**：Git 模式阶段 2（前半）——「引用树」档（`GitRefsModels` 视图模型 + `GitRefsPanel` 只读列表，
本地 `local_branches` / `remote_branches`，tags 占位）+ 设置列表「进入」深链接
（`RepoDeepLink.openGitPanel`：隐式带代码页、直接落视图档、owner/repo 从 origin 反推）
+ 修掉三处账目不符（`GitPanelKind.Graph.available` 漏翻、代码/资源里的旧章节号、
§三 缺 195 与 §二 1.0.94 的箭头）（一次提交，故 +1）

- **196**：Git 模式阶段 1 落地（`CommitGraphModels` 解析 + 泳道布局、`CommitGraphPanel` 提交图档、
未提交虚节点、分档标签条）+ 文档收束（`git-mode-design.md` 成为唯一进度与设计文档，
`git-version-tree-design.md` 收敛成指针，落后决策丢弃）（一次提交，故 +1）

- **195**：Git 模式阶段 0 落地（面板三档 `GitPanelStage` + `PanelSwitcher` 框换框 + 「工作区」档）
+ 三轮设计决策落账（数据面三条 / 虚节点方案 A / 形态拍板不做全屏页 / D11 拆 merge 与 rebase /
缓存不另建）（一次提交，故 +1）

- **194**：分叉决策页文案修正（去掉「引导桌面」四处 + 两条重写；仓库在内部存储，承诺不可兑现）
+ 新增《仓库内 Git 模式》设计稿（IA 重定 / 四档视图 / 冲突解决 / 引擎缺口 8 项 / 六阶段；
`git-version-tree-design.md` 降为可视化子设计）（一次提交，故 +1）

- **193**：本地仓库搬到内部存储（外部存储那棵 FUSE 子树对 git 锁文件语义不兼容 ——
`.git/HEAD.lock`；`LocalRepos` 内部存储 + 启动一次性搬迁 `migrateFromExternal`）
+ clone 锁文件失败现场取证（`clear_stale_locks`，清单为空 = 文件系统假象）
（一次提交，故 +1）

- **192**：拉取仓库的进度弹窗（引擎侧进度快照 + `nativeGitCloneProgress` / `nativeGitCloneCancel`、
模态弹窗、可取消、失败停在原地给原因与重试）+ clone 目标目录预检与失败清场（含 `.git` 拒绝、
半成品删除）+ 失败原因截断 120 → 300（`CloneErrorDiagnosticsTest`）（一次提交，故 +1）

- **191**：沉浸式翻译判定引擎 —— 一致即跳过（译后一致校验 + 判定缓存，不再插与原文逐字相同的卡片）
+ 混排段落匹配性翻译（只翻段内片段、按「片段 → 译文」配对展示，`TranslateDecision.kt` 新文件）
+ 混排使用规则进设置页（只翻片段 / 整段翻 / 不翻，`translate.matchPolicy`）
+ 协议元素细分（`""` 判定跳过 vs `null` 失败，页面不再把「跳过」误报成「翻译失败」）
（一次提交，故 +1）

- **190**：项目声明改为 MIT —— 新增 `LICENSE`（MIT 全文）与 `THIRD-PARTY-NOTICES.md`
（非自研内容分「vendored 文件 / 构建依赖」两类逐条声明来源；其中 Sora Editor 为 LGPL-2.1、
libgit2 为 GPL-2.0 + 链接例外，是仅有的两处非 MIT，合规姿势与取证口径一并写下）；
README「许可证」补齐；关于页新增「开源协议 / 第三方声明」两个入口（一次提交，故 +1）

- **189**：设置页行尾控件贴右（`weight(1f, fill = false)` 让主轴盒子缩到文字宽度、空白留行尾 ——
四处改 `weight(1f)`）+ 消息页显示模式单一真源（两个入口各持一份 `remember`，导致「选了平铺仍按仓库分组」；
新增 `NotifLayoutRuntime`，`MainActivity` 在建 Content 前 init；分类过滤收成纯函数 `notifCategoryBase`，
`NotificationCategoryTest` 6 例）+「已完成」新增「丢弃」与左滑丢弃（`NotifDiscard`：远端不动、归档保留、
四个分类都消失、5 秒可撤销）+ 英文模式翻译收口（枚举与参数默认值里的写死中文全量改走资源，
`I18nUiTextTest` 4 例；中英 1432/1432）（一次提交，故 +1）

- **188**：修消息列表「已读」常驻透出 —— `SwipeToDismissBox` 的滑动提示画在内容**之下**，
而 `NotificationRow` 的底色是 `Color.Transparent`（前一轮「未读不铺底」把原本挡着的底色
一并去掉），于是「✓ 已读」常驻透出来、与标题和图标叠在一起。在选中色之前先铺一层不透明的
页面底色：视觉不变，把提示挡回内容下面。顺带核过三个可能露出下层的口子 ——
FLAT 行间无间隙、分组那 2dp 间距在滑动容器之外、按压缩放 0.985（约 1dp），都不成立
（一次提交，故 +1）

- **187**：界面语言体系落地 —— 中英双语（默认 `zh-Hans` + `values-en/`，覆盖率 **100%**）
+ 语言切换页（`LocaleManager`，API 33+；切换靠 `configChanges` 不重建 Activity，导航与取数状态不丢）
+ 硬编码中文全量资源化（`:app` 1384 / `:downloader` 27 / `:imageviewer` 6 条，后者从零建 `res/`）。
`LocalizedText` 统一承载「资源 ID + 参数 + 嵌套 + 复数（`@PluralsRes` 工厂入口）」；
`Feedback` 修掉「用文案猜语气」的静默错色（顺带修好一条**中文下就已错色**的
`state_default_branch_changed`）；中文侧 `issue` → 「讨论」21 条，保留 GitHub 权限名与搜索语法。
`StateLabelTest` 3 例、`AppLanguageTest` 6 例，`NotificationModelsTest` 的相对时间断言改钉
`res` + `quantity` + `args`（一次提交，故 +1）

- **186**：修「添加账号」返回落点 —— 整屏接管会销毁 `MainScreen` 子树、导航状态全丢，
新增 `MainNavMemory` 寄存并**消费一次**恢复（所在 Tab / 是否在个人页 / 子页名）。
`MainNavMemoryTest` 8 例（一次提交，故 +1）

- **185**：「添加账号」第四次 —— 不再假设「状态变了根布局就会重渲染」，改由**状态机换页**：
`LoginState` 新增 `AddAccountWelcome`，状态一变 `PageSwitcher` 必然换页；根布局那套条件
替换整屏整段删除。顺带修「新增流程里从介绍页返回会掉到未登录欢迎页」（`loginBackTarget`
加 `addingAccount` 入参）。`KeyLoginTest` 补一例（一次提交，故 +1）

- **184**：「添加账号」第三次仍无响应 —— 诊断证明「标记置上了但根布局没重组」，
改用已验证会重组的通道（`LoginViewModel.addingAccount` 转发，根布局收集它）；
诊断加厚到三处；`AddAccountFlowTest` 补两条源码级断言钉住这条通道
（一次提交，故 +1）

- **183**：「添加账号」仍无响应 —— `PageSwitcher`（`AnimatedContent`）的 `contentKey`
取 `state::class`，而新增流程中 state 仍是 `LoggedIn`、key 没变，内容不重放。
改为在 `PageSwitcher` **之前**直接渲染欢迎页并 return；补两句只在值变化时记录的诊断日志
（根布局有没有读到标记 / 标记有没有被置上）（一次提交，故 +1）

- **182**：修「添加账号没反应」—— 1.0.79 在账号页挂的 `onDispose { finish() }` 与
「登录界面接管整屏」结构互斥（主界面一撤就 dispose，标记当场被清），改用兜底过期
`dropIfStale`（2 分钟）；状态徽标检查时不再改变布局（固定转圈槽位），徽标行可横滚；
新增流程起止各记一行日志。`AddAccountFlowTest` 7 → 12 例（一次提交，故 +1）

- **181**：「添加账号」改子流程（`AddAccountFlow`），不再接 `onLogout` —— 修「点添加账号
回不去账号页」（欢迎页不拦返回）与「当前账号凭据被误删」。三条收尾路径：登录成功 /
欢迎页返回 / 离开账号页。`planUpsert` 补「首登态夺权、刷新态不夺权」规则。
`AddAccountFlowTest` 7 例，`AccountStoreIdentityTest` 24 → 29 例（一次提交，故 +1）

- **180**：账号身份加「登录方式」维度 —— 密钥登录不再伪覆盖 OAuth 记录（两种方式可共存）；
`add` 不再重置 `lastCheck`/`status`（启动检查的结果到设置页就作废、必然重探的根因）；
老记录 `UNKNOWN` 走两轮匹配（精确优先）；账号 id 避让。`AccountStoreIdentityTest` 24 例
（一次提交，故 +1）

- **179**：账号探测补「为什么失效」—— 独立 `HEAD /` 探针读
`GitHub-Authentication-Token-Expiration`（响应头此前被整条链路丢掉），产出
「已过期 / 未到期 / 可能被吊销」三态白话；进账号页的重探加 5 分钟最小间隔
（结论不再每次重报）；日志补 host + token 指纹 + 探测时刻。`AccountChecksTest` 19 例
（一次提交，故 +1）

- **178**：消息列表重绘 —— CI 通知折叠（`collapseCiRuns`，口径照抄动态页 `collapsePushes`）
+ 卡片盒改行形态 + 原因标签只留高信号 + `TintRole.textColor()`。
折叠行代表 N 条，所有写远端路径（已读 / 完成 / 静音 / 复制链接 / 全部已读）都要打散成
`allIds`，回滚一律整行。新增 `NotificationCiFoldTest` 22 例，
`NotificationBulkRollbackTest` 6 → 10，`ThemeContrastTest` 5 → 8（一次提交，故 +1）

- **177**：产物不再打包 —— `actions/upload-artifact` `@v4` → **`@v7` + `archive: false`**
（GitHub 2026-02-26 上线；v7 之前一律 zip），publish 侧 `download-artifact` `@v4` → **`@v8`**，
下载 pattern 改 `*`。App 侧跟着改：不再给产物拼 `.zip` 后缀；`extractSingleApk` 两种形态都认
（裸 APK 靠根目录 `AndroidManifest.xml` 判定，否则按「装着唯一 APK 的 zip」解出）。
更正 1.0.74 里「产物下载永远是 zip」的错误结论。`ArtifactInstallTest` 11 → 13
（一次提交，故 +1）

- **176**：「刚发布的版本在 App 里看不到附件」—— 补两条断点。①App 读的列表接口对刚发布的
release 会返回空 `assets`（**当时误记为「窗口约 1.5~2.6 小时」，实为 GitHub 多副本数据不一致，
见 §二 1.0.74 的更正说明**；单体接口与网页始终正确），改在报空时回源单体
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
