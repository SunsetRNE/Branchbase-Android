<!-- 来源：根 `README.md`（2026-09 拆分）。正文未做删改，仅做三类机械改写：规整标题（去 emoji 前缀、
     加章节编号）、空行规整（合并多余空行），以及**路径重写** —— 正文里的仓库根相对链接
     （如 `docs/specs/prototypes/xxx.md`）改成相对本文件的链接（`prototypes/xxx.md`），链接文字与正文一字未动。 -->
# 页面重绘（运行详情 / 发布 / 消息）

三个页面从「信息平铺」重排成「卡片流」的落地规格：结构是什么、约束为什么这么定、哪些取舍是刻意的。
原型**草稿**（HTML/CSS/JS）留在 `/design/<原型名>/`（不入库）；同一件事的**说明文档**入库在
[`prototypes/`](prototypes/)。本文记录的是**落地后**的规格，与原型文档互为「草稿 → 结论」，
两边改动时要一起更新。

---

## 1. 运行详情：卡片流（重绘）

运行详情页从「7 条等权信息行 + 行内展开的任务列表 + 200 行日志小窗」重排成卡片流。
设计稿与逐条论证在 `design/workflow-redesign/`（原型草稿不入库；**文档已入库**：
[`docs/specs/prototypes/workflow-redesign.md`](prototypes/workflow-redesign.md)），落地后的结构：

| 区块 | 变化 |
|------|------|
| 顶栏 | 补「刷新」（跑成功/跑一半也能手动回源，改前只有失败态有重试）与「更多」（浏览器打开 / 重新运行 / 复制链接） |
| `RunHeaderCard` | 7 条平铺 `MetaLine` → **三段式**：状态胶囊+耗时 / 提交行（头像·分支·sha·**commit message**）/ 次要行；再加一条**进度条**（仅失败或运行中显示） |
| 任务 | `JobRow` → `JobCard`：**卡片头只做展开/收起**（改前整行可点、里面又嵌一个可点的「完整日志」）；展开是步骤时间线（含**相对时长条**）；卡片脚显示 **`runner`**（这个字段模型里解析了、单测断言了，改前整个 app 从未显示过） |
| 过滤 | 标题带计数 `任务 · 3` + 分段控件 `[全部][失败]`，**仅存在失败或运行中**时出现 |
| 注解 | 按 check-run 名字**归回对应任务卡片**（改前全部沉在页尾，与任务脱钩）；归属不了的进底部「其他注解」 |
| 产物 | 行尾加**下载**（走 `:downloader`；`archive_download_url` 是这轮才解析的，改前产物只有名字没有入口） |
| 日志 | 运行详情页**不再内嵌日志小窗**；`JobDetailContent` 升级成 **`JobLogScreen`**：步骤 chips / 搜索命中跳转 / 仅错误·含警告过滤 / 分组折叠 / 复制，用 `LazyColumn` 逐行（改前把整段日志塞进一个 `Text`，几 MB 时整块测量、滚动会卡） |

顺带把五件共享小件（`DetailTopBar` / `DetailSectionTitle` / `DetailLoading` / `DetailErrorRetry` /
`DetailEmptyText`）从页面私有抽成 `DetailScaffold.kt` —— 它们原本在别的详情页各有一份。

新增的纯逻辑都有单测：`runProgress`（进度分类）、`jobBelongsToAnnotation`（注解归属）、
`annotationCheckRuns`（带名字的 check-run）、`logLineLevel` / `logHitIndexes`（日志分级与搜索）、
`isFailedConclusion`、`elapsedSince`（运行中耗时按起点算）。

---

## 2. 发布（Releases）：三档性质与三个页面重绘

### 先对齐官方语义：什么是「正式发布」

GitHub 的 release 有三种性质，「最新发布（Latest）」只在其中一档里成立。官方原文
（[REST `POST /repos/{owner}/{repo}/releases`](https://docs.github.com/en/rest/releases/releases#create-a-release)
的 `make_latest` 字段）：

> Specifies whether this release should be set as the latest release for the repository.
> **Drafts and prereleases cannot be set as latest.** Defaults to `true` for newly published releases.
> `legacy` specifies that the latest release should be determined based on the release creation date
> and higher semantic version.

| 性质 | `draft` | `prerelease` | 能否是 latest |
|------|---------|--------------|---------------|
| **正式发布** | false | false | **可以**（新建默认 `make_latest=true`） |
| 预发布 | false | true | 不可以 |
| 草稿 | true | — | 不可以 |

两点容易踩的细节：`make_latest` 的取值是**字符串** `"true"` / `"false"` / `"legacy"` 而不是布尔；
PATCH 的默认值是 `legacy`（= 不动归属），所以编辑标题/正文不应顺手改 latest。

### 列表：入口从「一整行空框」收成标题行右侧的「+」

「新建发布」原本是列表最上面一条**占满整行的描边按钮**——它没有任何信息，却永远压在第一条发布之上，
进页面第一眼看到的是一个空框。现在它是 `发布 · N` 标题行右侧的 30dp 圆形「+」
（与 `DetailSectionTitle` 同一套版式，仅 `canPush` 时出现）。

条目本身也重排了：**tag 提到第一眼**（它是唯一稳定标识，`name` 可能为空或与 tag 重复），
做成等宽胶囊；徽章紧挨着说明性质（`最新发布` / `预发布` / `草稿`）；name 与元信息依次降一级。
改前 name 在第一行、tag 混在灰色小字里，扫过去分不出哪条是哪个版本。

### 「最新发布」怎么判定

列表接口 `GET /releases` 的**每条记录里不带 latest 标记**，不能拿列表自己算
（「最新的非草稿非预发布」只是 `make_latest` 缺省时的近似规则，一旦有人显式改过归属就是错的）。
所以走权威端点 `GET /releases/latest`（`GitHubApi::latest_release_id`），拿不到时才退回上面那条近似规则。

### 编辑页：一屏装下「表单 + 附件 + 更新内容」

- **性质从两个开关改成三段式单选**。改前是「草稿」「预发布」两个可以同时勾上、含义又重叠的开关，
  而「最新发布」这个概念在 App 里根本没有；现在一屏说清三档各自的可见性与能否占用 Latest；
- **垂直预算从 ≈694dp 压到 ≈360dp**（逐项见 `docs/specs/prototypes/release-redesign.md`）：
  标签 / 标题 / 目标分支**并成一行**（标签是等宽 chip、分支是行尾只读 chip）；
  「设为最新」从一张卡片变成类型行里的小开关，且只在该有意义（正式发布）时出现；
  分组标题去掉、统计（`附件 · 2 个 · 14.0 MB`、`更新内容 · 12 行 · 348 字`）挪进分组头；
  分组之间用 1dp 发丝线代替大留白。**没有这一步，附件区根本没有位置**；
- **更新内容去掉包裹框**，改用编辑器的**行标识槽**：行号（等宽右对齐 / `CodeSyntax.LineNo`）+
  当前行底色 + **定宽标记列**，正文无框、3 行起步自动增高（改前是固定 200dp 的描边盒子）。
  槽宽、行号字号、「不画分隔竖线」、当前行底色全部取自仓库既有的行号列与 `BranchbaseCodeEditor`
  的取舍；「槽宽恒定」这条约束连槽内部也遵守 —— `+` 标记出现/消失时数字轴不动。
  折行对齐走 `TextLayoutResult`（一条逻辑行折成多行时行号只占第一视觉行），不按 `\n` 数；
- **「生成说明」不再整段覆盖**：接官方 `POST /releases/generate-notes`，生成的行在行号槽里带 `+`、
  行底淡绿，底部给 `+ N 行来自生成说明 · [全部保留] [丢弃生成行]` —— 手写的字一行都不会被吞，
  所以那个「替换现有更新内容？」的确认弹窗也删掉了；
- **预览**放在底部弹层（正文 → HTML 仍走 Rust 的渲染器），编辑区不挂 WebView；
- **主操作在顶栏且文字随状态变**：草稿写「存为草稿」、其余写「发布」/「保存」。

### 附件：导入的文件先落盘，再上传

| 环节 | 做法 | 为什么 |
|------|------|--------|
| 选择 | `ActivityResultContracts.GetMultipleContents`（SAF） | 零存储权限，与 App 既有基调一致 |
| 落盘 | `getExternalFilesDir(null)/release-uploads/{owner}/{repo}/{tag}/`，先写 `.part` 再 rename | 选择器给的 `content://` 是**临时凭据**：进程被杀或重启就失效。**不用 cacheDir** —— 系统在低存储时会清它，「导入 → 切出去查个东西 → 回来」就发现文件没了 |
| 清单 | `filesDir/release-drafts/{owner}-{repo}.json`（表单 + 附件元数据） | 文件大、改动少；清单小、敲字就可能要落盘。分开存，「退出再回来」两边各恢复各的 |
| 回收 | 发布成功即删 / 未发布保留 7 天（进编辑页时顺手 prune）/ 点「移除」立即删 | 参照仓库里唯一带 TTL 的缓存实现（`readme_images`） |
| 引用 | `downloads/` 里已有同名文件时只记路径不复制 | 省一次 IO；这类文件不是我们的，「移除」时不删它 |

### 上传：先建 release，再逐个传资产

资产**必须挂在 release 上**，所以「发布」是两步：先 `create_release` / `update_release` 拿到 id，
再对每个待上传附件调 `upload_release_asset`。拿到 id 后记下来 —— 附件失败重试时不能重新 create
（同名 tag 会 422 `already_exists`）。有附件失败就不算完成：不清理暂存、不关页面，让用户重试或移除。

> **已知取舍（写在代码注释里）**：上传目前**不是流式**的。reqwest 只开了 `json / rustls-tls / http2`，
> 流式 body 挂在 `stream` feature 下，而它会连带 `wasm-streams`（离线环境取不到、交叉编译也不需要），
> 所以实现是 `spawn_blocking` 读进内存再发，并用 256MB 上限兜住（GitHub 本身允许 2GiB，
> 但移动端一次分配 2GB 必被 OOM 杀掉）。升级成真正的流式只需给 reqwest 开 `stream`、
> 把 body 换成 `Body::wrap_stream(...)`。另外 Rust 侧是一次阻塞调用、拿不到百分比，
> 所以附件行用的是不确定进度条。

### 详情页：去掉「一个附件一个框」

附件原本一条一个描边圆角盒，三个附件就是三个盒子叠着，把版面切得很碎。现在整组只用**分隔线**分区，
描边留给真正需要边界的输入框。头部（tag + 性质徽章 + 名字 + 署名）与附件、更新内容之间用发丝线分段。

### 落到底层

`GitHubApi` 四个方法 + 对应 JNI 导出：`create_release` / `update_release` 新增 `make_latest`
（草稿与预发布下一个字段都不发，省掉可能 422 的往返）、`latest_release_id`、`generate_release_notes`、
**`upload_release_asset`**（`POST uploads.github.com/repos/{o}/{r}/releases/{id}/assets` ——
与 API 不同源所以不复用 `base_url()`；`name` / `label` 走 URL 编码，附件名里空格、括号、中文都常见）。

> **JNI 签名是编译期查不出来的**：`external fun` 与 `Java_*` 只靠名字关联，参数表对不上时
> Kotlin 编译通过、Rust 编译通过、JVM 单测也永远不会加载那个 `.so`（它是 aarch64-android 的），
> 直到真机点下按钮才炸 `UnsatisfiedLinkError`。这次正好同时改了两侧，因此新增
> `JniSignatureTest`：把两侧的声明与实现**逐参数、逐类型**全量比对、**不写死数量**
> （`external fun` 增删后测试自动跟上；`JString<'local>` ↔ `String`、`jint` ↔ `Int`、`jboolean` ↔ `Boolean` …）。

---

## 3. 消息（通知收件箱）卡片流与多选

消息页的列表是「卡片流」：一条通知 = 一张卡。卡片布局与多选语义在 `ui/notification/`。

### 卡片布局：固定「识别槽」

```
┌ Card ──────────────────────────────────────────────┐
│▍ ┌──────┐  仓库 #号 · 原因 · 时间                     │
│▍ │ 识别 │  标题（最多 2 行）                          │
│▍ │ 槽位 │  评论预览（作者：正文）                      │
│▍ └──────┘                                          │
└────────────────────────────────────────────────────┘
 ▍ = 未读竖条（overlay 绘制，不占布局宽度）
```

| 约束 | 为什么 |
|------|--------|
| 行首**恒为 32dp 识别槽**（普通态类型图标 / 多选态 20dp 方框） | 上一版多选态把 32dp 图标换成 24dp 的 `Checkbox`，标题左边界会跳 8dp；M3 Checkbox 按「独立控件」设计（内部 `wrapContentSize` + `requiredSize` + 最小触摸目标），在 `size(...)`/`padding(...)` 组合下会按自身约束重新落位、画出槽位压到标题上 |
| 复选方框**自绘** 20dp（`SelectionCheckbox`） | 只需要「未选 / 已选 / 半选」三态；自绘后尺寸与落位完全可控，也不会和整行的选择语义重复播报 |
| 未读竖条用 `matchParentSize` + `drawBehind` **overlay 绘制** | 作为 flex 子项时未读行比已读行少 3dp 正文宽度，同一标题会换行到不同位置 |
| **信息顺序**：元信息行（仓库 #号 · 原因 · 时间）在**最上**，标题居中，评论预览在下 | 排布对齐 [DioHub - Dev](https://github.com/namanshergill/diohub)（`basic_notification_card.dart` 把仓库名 + 日期放标题上方、最新动态放卡片底部）：「哪来的、什么时候」是定位坐标，先给坐标再读标题，扫一眼就能决定要不要点进去；预览仍是「要不要点进去」的关键依据，但标题必须是第一眼看到的那一行。骨架屏的占位条顺序同步跟着换（先短后长） |

### 多选交互（按官方文档实现）

| 位置 | 做法 | 依据 |
|------|------|------|
| 整行 | 多选态用 `Modifier.selectable(selected, role = Role.Checkbox)`（普通态才是 `combinedClickable`） | 选择状态必须由语义提供，读屏才会播报「已选中 / 未选中」；只画方框等于无障碍用户看不到选择状态 —— [Compose 语义](https://developer.android.com/develop/ui/compose/accessibility/semantics) |
| 列表容器 | 多选态 `Modifier.selectableGroup()` | 让读屏把行播报成「第 x 项，共 y 项」，否则每行都是孤立控件 —— [`androidx.compose.foundation.selection`](https://developer.android.com/reference/kotlin/androidx/compose/foundation/selection/package-summary) |
| 分组头 | `Modifier.triStateToggleable(ToggleableState)` + `Role.Checkbox` | 三态（全选 / 未选 / 半选）有专门的 API，半选必须被播报，否则用户无法判断点下去是补齐全组还是清空 —— [triStateToggleable](https://developer.android.com/reference/kotlin/androidx/compose/foundation/selection/triStateToggleable.modifier) |
| 长按刷选 | 拖动开始补 `HapticFeedbackType.LongPress` | 多选态行内没有长按菜单，没有触觉就无法判断长按是否生效 |
| 触摸目标 | 行整体是目标（Material 列表选择规范），方框自身不带点击 | [Material 3 复选框](https://m3.material.io/components/checkbox/overview) · [Material 3 列表](https://m3.material.io/components/lists/overview) |
| 「取消全选」 | 图标用 `Deselect`（不用 `Close`） | `✕` 在同一屏已是「退出多选」，两个不同动作共用一个图标会点错 |

### 长按状态机：单条动作面板 → 多选

长按**不是**「进入多选」，而是弹出单条动作面板（标记已读 / 未读 / 完成 / 静音 / 复制链接 /
在浏览器打开 / 分享），多选是面板末尾的一个显式选项。理由是「只想把这一条标成已读」的场景：
先长按进多选再点「已读」多一步，而且列表结构已经变了。

| 手势 | 普通态 | 多选态 |
|------|--------|--------|
| 点击 | 打开（未读时顺带标已读并打远端） | 切换选中，不跳转 |
| 长按 | **快捷动作面板**（单条动作） | 容器接管：从锚点整段选中 / 按住划过刷选 |
| 左滑 / 右滑 | 两个方向同效 → 标记已读（仅未读行） | 一律禁用（批量选择中误触会把行标成已读） |

> 滑动两个方向都放开（对齐 DioHub - Dev：左右滑都是 Mark as read）。只放开单方向时，
> 「从哪一侧滑」纯粹是握持习惯 —— 左滑在单手 / 手小的场景下更顺手，没有理由拒绝。
> 背景提示按方向贴边：M3 1.4 的 `backgroundContent` 签名是 `@Composable RowScope.() -> Unit`，
> **不带方向参数**，方向从 `SwipeToDismissBoxState.dismissDirection` 读 —— 从左往右滑时内容右移、
> 露出的是左边缘，提示就该靠左。

> 这里曾有一处**接线缺陷**：`NotificationList(onLongClick = { enterSelection(it) })` 一直写成
> 「进多选」，而 `sheetTarget` 只有多选条上的「更多」会赋值 —— 于是面板的**非多选分支从引入起
> 就没显示过**（死代码），长按退化成「进多选」，与本节（以及
> [`docs/specs/prototypes/messages-redesign.md`](prototypes/messages-redesign.md) 原型
> 的 ② 手势状态机）描述的行为不一致。修好接线后又暴露出第二个问题：面板里的单条「标记完成」
> 只写本地、从不调 `RustBridge.markNotificationDone`（该接口此前只在批量路径里用过），
> 刷新后条目会原样回来。两者一起修好，并抽出 `markDoneRemote`（本地乐观归档 → 远端 DELETE → 失败按 id 回滚）。

### 批量失败：**只回滚失败的条目**

批量是「本地乐观更新 → 远端逐条串行写（每条间隔 120ms 防二级限流）」。中途失败的条目要回滚，
但**成功的不能一起回滚**：

| 做法 | 结果 |
|------|------|
| 整批回滚（旧） | 10 条里 3 条失败 → 本地 10 条全退回未读，而远端 7 条已读。用户看到「批量失败」，下拉刷新后其中 7 条又自己变已读 —— 本地与远端在这段窗口里并不一致，而「回滚是为了跟远端一致」恰恰是整批回滚的理由 |
| 只回滚失败项（现） | 本地状态与远端一致；失败项**保留选中**，用户直接再点一次即可重试，不用重新一条条勾 |

静音（`BulkOp.MUTE`）不改动任何本地可见状态，没有可回滚的东西 —— 回滚它反而会顺带重写
`NotifArchive`，把期间用户从别的入口产生的归档改动覆盖掉；因此它既不回滚，也不提供「撤销」
（按下去什么都不变的撤销比不给更糟）。规则抽成纯函数 `bulkRollbackTargets`，有单测
（`NotificationBulkRollbackTest`）。

另外，退出多选**不再重置** `bulkRunning`：批量是仍在跑的远端长任务，退出多选只是收起选择 UI。
旧实现把两者绑在一起，退出后能再触发一批，两批并发打远端（120ms 间隔的限流保护形同虚设），
且旧批次收尾时的 `exitSelection()` 会把用户新选的一批一起清掉。现在收尾只在「本批仍是最新一批
（`bulkSeq`）且用户没动过选择」时才收拾多选态。

### 工作流通知：点击落到「这一次」运行

工作流通知（`CheckSuite` / `CheckRun` / `WorkflowRun`）点进去曾经只落到仓库的「工作流」tab，
到不了这次 run 的详情页。根因是 **GitHub 不在通知里给 run id**：

| 来源 | 能不能拿到 run id |
|------|------------------|
| `subject.url`（CheckSuite） | 常常**直接是 `null`** —— [社区讨论 #158253](https://github.com/orgs/community/discussions/158253)「Missing subject URL field for CheckSuite Notification type」 |
| `subject.url`（有值时） | 形态是 `.../check-suites/<id>` / `.../check-runs/<id>`，是 **check 域的编号**，当 run id 用会打开一个编号巧合的无关 run（1.0.29 修过一次） |
| `subject.title` | **唯一能用的**：`"<workflow> workflow run[, Attempt #N] <status> for <branch> branch"` |

所以点击时补一次解析：从标题抠出「工作流名 + 分支（+ attempt / 结论）」，
`GET /repos/{owner}/{repo}/actions/runs?branch=…` 拿到候选后用**时间最近**收口
（run 的 `updated_at` 就是它结束、通知发出的那一刻）。

- 标题格式与 [gitify](https://github.com/gitify-app/gitify)（成熟的三方通知客户端）从真实报文
  反推出的正则一致；它的注释写明「目前没有干净的办法用 API 直接拿 CheckSuite / WorkflowRun 状态」，
  因此那边只退回带筛选的 Actions 列表页 —— 本应用多做一步配对，能真正落到 run 详情。
- **配不上就退回工作流列表**，并 Toast 说明原因：一个都匹配不上、或最好的候选与通知时间
  偏差超过 24h 时一律不猜（与上面「宁可放不对，不要放错」同一条原则）。
- 三个纯函数（`parseCheckSuiteTitle` / `parseRunCandidates` / `pickRunId`）有单测
  （`NotificationWorkflowDeepLinkTest`，覆盖各类误配：名字、分支、attempt、结论、时间偏差）。

### 选中集合必须与可见集合收敛

`selectedIds` 是「用户点过的 id」，而可见列表会因换分类 / 类型 / 时间范围、下拉刷新、
「完成」归档而变。对外一律用 `effectiveSelection(selectedIds, visibleIds)`（交集），
否则「全选」判断会失真（`size >=` 在混入不可见 id 时判反），批量已读 / 完成 / 静音 / 复制链接
会作用到屏幕上根本看不到的条目。三个纯函数（`effectiveSelection` / `isAllVisibleSelected` /
`toggledAllSelection`）都有单测（`NotificationSelectionTest`）。

---

> **相关文档**：[`prototypes/workflow-redesign.md`](prototypes/workflow-redesign.md) ·
> [`prototypes/release-redesign.md`](prototypes/release-redesign.md) ·
> [`prototypes/messages-redesign.md`](prototypes/messages-redesign.md) ·
> [`modules-design.md`](modules-design.md) §5 / §6（作业日志与运行轮询）·
> [`ui-design.md`](ui-design.md)（配色与动效规格）。
