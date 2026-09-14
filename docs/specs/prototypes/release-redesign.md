<!-- 入库副本：正文与 design/release-redesign/README.md 一致，未做删改，仅加本段头 + 三处失效的 `README.md:804-821` / `:795-798` 引用改指新文档（见下）。 -->
> **来源**：`design/release-redesign/README.md`（原型本体 `index.html` / `style.css` / `app.js` / `smoke.js` 在 `/design/release-redesign/` 下，按仓库约定**不入库**；DSH 侧边栏「原型预览」面板可直接打开）
> **为什么只把文档抽进来**：原型 HTML 是一次性草图，跟具体实现绑死、很快过期；
> 而这份文档里的**现状测绘、取舍论证与落地清单**是跨时间有效的设计结论，代码注释会引用它。
> **维护**：原型再改时请把这份副本一并更新，别让两边分叉；文中提到的文件名相对原始目录。
> **路径重写（2026-09）**：根 `README.md` 拆分后，正文里三处按行号钉住 README 的引用已失效
> （`README.md:804-821` → [`docs/specs/screens-design.md` §3](../screens-design.md)；
> `README.md:795-798` → [`docs/specs/ui-design.md` §1](../ui-design.md)），仅此三处与原始草稿不同。

# 发布页重设计原型 · 说明与落实方案（第二轮）

> 本轮目标：**继续压（≈488 → ≈360dp）+ 把精细度提上来**（图标、滑动胶囊、槽内对齐、状态条、防抖草稿）。
> 目录：`design/release-redesign/`（`/design/` 已在 `.gitignore` 中：原型草图不入库，但能被 DSH 原型预览面板扫到）
> 目标页面：**新建 / 编辑发布**（`ReleaseEditScreen`）
> 依据：`app/src/main/java/com/branchbase/ui/repository/ReleaseScreens.kt`（846 行，逐行测绘）

## 一、交付物与查看方式

| 文件 | 说明 |
| --- | --- |
| `index.html` | 发布编辑页原型：压缩后的表单骨架 + 附件区（导入/引用/上传/移除）+ 行标识槽的更新内容 + 生成说明的行标记 + 预览态 |
| `style.css` | 样式（令牌对齐 `ui/theme/ThemePalette.kt` 的 Primer；行标识槽色值取自 `EditorPalette` / `CodeSyntax`；明暗两套都在里面） |
| `app.js` | 全部交互（附件状态机、行号槽与换行对齐、生成标记、草稿/重进页面、密度切换、自动演示） |
| `smoke.js` | 原型烟测：Node + 最小 DOM 桩，43 项断言（渲染 + 关键交互 + 状态流转），`node smoke.js` |

查看方式（任选其一）：

1. **DSH Web 侧边栏「原型预览」面板** → 选 `design/release-redesign/index.html`（面板按「设备预设 → 手机 390×844」最接近真机）；
2. 本地静态服务：`python3 -m http.server 8099 --directory design/release-redesign` → `http://127.0.0.1:8099/index.html`。

原型工具条（**非产品 UI**）：`ⓘ 设计说明` / `▶ 自动演示` / `⇕ 紧凑⇄标准`（对照现状规格）/ `↺ 重进页面`（模拟进程重启）/ `◐ 深色`。

---

## 二、现状测绘

**「发布页面」在这个仓库里是三个入口 + 一块路由**：

| 位置 | 内容 |
| --- | --- |
| `RepositoryScreen.kt:282-283` | 路由 `RepoRoute.ReleaseEdit` / `RepoRoute.ReleaseDetail`（前者优先） |
| `RepositoryScreen.kt:353-362` | 渲染 `ReleaseEditScreen`，`onSaved` 回列表并 `refreshTick++` |
| `RepositoryScreen.kt:632-635` | `RepoPage.Releases` → `ReleaseListContent`，`onCreate` 就是「新建发布」入口 |
| `RepositoryListScreens.kt:568 / :691` | `ReleaseListContent` / `ReleaseRow`（列表条目 + tag 胶囊） |
| `ReleaseScreens.kt:98` | `ReleaseDetailScreen`（详情页：头部 / 附件 / changelog） |
| `ReleaseScreens.kt:307` | **`ReleaseEditScreen`（本次目标页）**，纯 composable + `remember`，无 ViewModel |

### 现状的问题（都指到行）

| # | 问题 | 位置 |
| --- | --- | --- |
| 1 | **一屏装不下**：新建发布约 694dp（可视区约 790dp），附件区没有任何位置 | 全局 |
| 2 | 更新内容是**固定 200dp 的描边盒子**，和同屏「只有一条底线」的单行字段是两套语言 | `:628-657` |
| 3 | 「生成说明」是**整段替换**，所以必须配一个确认弹窗兜破坏性操作 | `:408-427` / `:510-525` |
| 4 | 每个字段各自「标签 + 底线」，标签行平白吃掉 3×17dp | `:577-618` |
| 5 | 「设为最新发布」是整块灰底卡片（56dp），只承载一个开关 | `:665-685` |
| 6 | **不能带附件**：GitHub release 的一半价值在资产，App 里只能建一条空 release | 全局 |
| 7 | 附件行 `AssetRow` 只存在于详情页（下载侧），**没有删除 / 没有可复用的行组件** | `:710-793` |
| 8 | 文件大小格式化有 3 份实现 | `ReleaseScreens.kt:841` / `DownloadPaths.kt:64` / Workflow 模型侧 |

---

## 三、设计

### ① 两轮压缩预算：≈694 → ≈488 → **≈360dp**

第一轮解决「一屏装不下 + 没有附件位」，第二轮继续压（标签/标题/分支并成一行、「设为最新」并进类型行、
段头承载统计、发丝线代替留白）：

| 区块 | 现状 | 第一轮 | 第二轮 | 第二轮的做法 |
| --- | --- | --- | --- | --- |
| 顶栏 | 50 | 50 | 50 | — |
| 类型 + 标签 / 标题 / 分支 | 257 | 124 | **87** | 去掉两个分组标题；标签 chip + 标题 + 分支 chip **并成一行**；「设为最新」并进类型行 |
| 附件 | — | 126 | **107** | 行 46→40；暂存说明从常驻一行改为段头 ⓘ + 空态提示 |
| 更新内容 | 233 | 96 | **97** | 段头承载「行数 · 字数」；生成条只在生成后出现 |
| 设为最新发布 | 56 | 38 | **0** | 已并入类型行（且只在「正式发布」下出现） |
| 分组节奏 + 间距 + 底 | 98 | 54 | **20** | 分组之间改用 **1dp 发丝线**（`Gray150`）代替大留白 |
| **合计（含 2 个附件）** | **≈694** | **≈488** | **≈360** | 第二轮再省 **≈128dp** |

结论：紧凑档一屏（可视约 790dp）放下表单 + 2 个附件 + 更新内容后仍富余约 430dp，可再放 4~5 个附件；
工具条的 `⇕ 标准（现状）` 档可对照现状规格。

### ② 一条排版骨架：左槽 + 右内容

| 语义 | 左槽 | 右内容 |
| --- | --- | --- |
| 表单行 | 46dp（内联 micro-label / 值） | 值；聚焦时该行底部的发丝线转蓝 |
| 附件行 | 24dp 类型图标（APK / zip / txt 三种底，圆角 6） | 文件名（等宽 12sp）+ `大小 · 状态点 · 状态` + 一个主动作 + ✕ |
| 更新内容 | **40dp 行号槽**（标记列 12 + 数字列） | 正文（无框，自动增高） |

「槽宽恒定 → 内容左边界永不跳」在仓库里有成文规范：`docs/specs/screens-design.md` §3（消息卡片「行首恒为 32dp 识别槽」+ 三条硬约束）。
本页把这条约束用在三种槽上，**并且行号槽内部也遵守**：标记列定宽，`+` 出现/消失时数字轴不动。

第二轮补的十二处细节（「精细」的那一半）：

1. 图标全部换成**内联 SVG**（文件 / 包 / 压缩包 / ＋ / ⓘ / ✓ / ✕ / ✦ / 预览 / 分支），不用 emoji；
2. 分段控件的选中态改成**滑动胶囊**（220ms 缓动），而不是切换背景色；
3. 行号槽拆「标记列 + 数字列」两列定宽；
4. 当前行底色**不覆盖行号槽**（编辑器里槽底色是后绘的，`EditorRenderer.java:595`）；
5. 附件行有状态点（上传中呼吸）+ 2px 进度条；
6. 主次动作分离：主动作是文字按钮，移除是 ✕ 图标（任何时刻只有一个主动作）；
7. 段头承载信息：`附件 · 2 个 · 14.0 MB`、`更新内容 · 12 行 · 348 字`，不再单开统计行；
8. 状态条（校验失败 / 草稿已自动保存）默认 `display:none`，**不占常驻高度**；
9. 改任何字段 → 900ms 防抖落草稿（附件在 `release-uploads/`，清单在 `filesDir`）；
10. 分组之间用 1dp 发丝线代替大留白；
11. 所有数字 `tabular-nums`（大小、行数、字数、百分比）；
12. 行与按钮补按压缩放 / 背景反馈。

### ③ 更新内容：去掉包裹框，借编辑器的行标识槽

规格来源（**不是新发明的**：槽宽、行号字号与颜色、当前行底色、无竖线，全部取自仓库里已经落地的三处行号列与编辑器外观契约）：

| 项 | 值 | 来源 |
| --- | --- | --- |
| 槽宽 | 仓库既有三处都是 **34dp**：只读预览 `RepositoryFileViewer.kt:531-536`（`width(34.dp).padding(end=10.dp)`）、搜索代码块 `SearchScreen.kt:769-800`、日志行号 `JobLogScreen.kt:471-499` | 本页拆成「左留白 8 + **标记列 12** + 数字列 20 = 40dp」：多出的 6dp 买的是「`+` 标记出现时数字轴不动」 |
| 行号字号 / 对齐 | 11sp、`TextAlign.End`、等宽 | 只读预览 `:532-535`、日志行号 `:471-499`（搜索代码块是 12sp） |
| 行号颜色 | 浅 `#C0C6CC` / 深 `#6E7681` | `ThemePalette.kt:199` / `:279`（`CodeSyntax.LineNo`） |
| 行号左侧留白 8dp | `GUTTER_MARGIN_DP = 8f` | `editor/.../BranchbaseCodeEditor.kt:151`（→ `setLineNumberMarginLeft`，`:139`） |
| **不画分隔竖线** | — | `BranchbaseCodeEditor.kt:140` `setDividerWidth(0f)`，注释写明「再画一条竖线就等于又加了一圈边框」 |
| 当前行整行底色 | 浅 `0x0D000000` / 深 `0x14FFFFFF`，且**不覆盖行号槽** | `EditorPalette.kt:225` / `:262`（`currentLine`）；编辑器里槽底色后绘（`EditorRenderer.java:595`） |
| 底色 | **透明**（取消 `Gray100` + 1dp 描边） | 现状见 `ReleaseScreens.kt:631-636`；仓库里另三处行号列也都是「无框 + 整页底 `CodeSyntax.CodeBg`」 |

同一套「窄槽 + 无框内容」的用法在本页有三处：表单 46dp / 附件图标 24dp / 行号 40dp，**槽宽恒定 → 内容左边界永不跳**（成文规范：`docs/specs/screens-design.md` §3）。

两处**有意偏离**编辑器，落地时可自行取舍：

| 偏离 | 说明 |
| --- | --- |
| **正文不用等宽** | 编辑器里是源码，这里是中文 Markdown 说明文，等宽会把中英混排拉得很散；只借「行标识」不借「等宽」 |
| **行高 22px、当前行号再加粗提亮** | 编辑器行高来自字体 metrics（13sp ≈ 15–16dp，`CodeEditor.java:3030-3035`），且 `lineNumber` 与 `lineNumberCurrent` **同色**（都是纯黑/纯白，`EditorPalette.kt:211-212` / `:247-248`），当前行只靠整行底色区分（只读态还没有这个高亮，`EditorRenderer` 带 `isEditable()` 条件）。中文说明文按 22px 放宽更耐读；浅底 + 5~8% 底色下再把行号提亮一档，光标行才一眼可辨。想严格对齐编辑器，把 `.ln.cur` 的加色去掉即可 |

另外两点实现约定：

- **高度自动增高**（3 行起、330dp 封顶后内部滚动）：空内容时 66dp，比现在固定 200dp 省 134dp，是压缩预算里的主要一项；
- 仓库里**没有**现成的无框多行输入（多行 `BasicTextField` 目前三处全带容器框：`ReleaseScreens.kt:631-636`、`IssueDetailScreen.kt:1244-1263`、`ProfileEditScreen.kt` 的 `OutlinedTextField`），
  可参考的无框写法只有单行的 `FormField`（`:576-618`：只有一条底线，聚焦转蓝）—— 本组件是第一个无框多行输入。

真机实现要点（原型里已用镜像元素近似）：一条逻辑行折行时，行号**只占第一视觉行**的高度，
后面的视觉行留白。Compose 里等价于 `TextLayoutResult.getLineTop(line)` / `getLineForOffset(caret)`，
不要自己按 `\n` 数 —— 这是这个组件唯一容易做错的地方。

### ④ 「生成说明」不再整段覆盖

- 生成的行在行号槽里带绿色 `+`、行底淡绿（复用日志视图的 `--log-add-bg`）；
- 底部条带：`+ N 行来自生成说明 · [全部保留] [丢弃生成行]`，**只在存在生成行时出现**（稳态不多占一行）；
- 手写的字一行都不会被吞，所以 `ReleaseScreens.kt:510-525` 那个确认弹窗**可以删掉**。

### ⑤ 附件：导入的文件放哪、什么时候清

目录沿用 `:downloader` 已经验证过的约定（`DownloadPaths.kt:10-28`：`getExternalFilesDir(null)/…`、零权限、卸载即清、`.part` → rename）：

```
getExternalFilesDir(null)/release-uploads/{owner}/{repo}/{tag}/{name}      ← 附件本体
filesDir/release-drafts/{owner}-{repo}.json                                 ← 草稿清单（表单 + 附件元数据）
```

| 决策 | 理由 |
| --- | --- |
| **不用 `cacheDir`** | 系统在低存储时会直接清 cache；用户「导入 → 切出去查东西 → 回来」就可能发现文件没了 |
| **导入即落盘**（拿到 `content://` 就复制，`.part` 完成再改名） | 只留 Uri 的话，进程被杀 / 重启后 Uri 失效 —— 这正是「避免丢失」的落点 |
| 文件名 `DownloadPaths.sanitize` + 长度上限 | 远端/系统文件名不可信，`../../foo` 会写到目录外（该函数已有单测） |
| 草稿与文件分开存 | 清单是 JSON（小、频繁改），本体是大文件（少动）；重启后两者都能恢复 |
| 清理：发布成功即删 / 草稿保留 7 天 / 手动移除即删 | 参照仓库里唯一带 TTL 的缓存实现 `ReadmeWebView.kt:720-752`（TTL + 上限淘汰） |
| 「引用已下载的文件」 | `downloads/` 里已有同名 APK 时只记路径不复制，省一次 IO |

附件行状态机（原型里可点着看）：`待上传 → 上传中(带进度条) → 已上传`，失败给「重试」；
任何时刻**只有一个主动作 + 至多一个次要动作**（与详情页 `AssetRow:744-755` 的既有约定一致）。

这套「存在哪、什么时候清」的说明在界面上是**按需展开**的：附件段头一个 ⓘ（点开是完整说明：目录、为什么不用
cacheDir、7 天 TTL、移除即删），导入成功时 toast 里也带路径 —— 既省掉一行常驻高度，又不牺牲可发现性
（第二轮把原来的常驻说明行收进了这里）。

---

## 四、落实方案（代码改动清单）

### 1) 新增：系统文件选择器（现在全仓一个都没有）

```kotlin
val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> ... }
// 或 OpenDocument（可多选）：ActivityResultContracts.OpenMultipleDocuments()
picker.launch("*/*")   // 或 "application/vnd.android.package-archive" + "application/zip"
```

证据：全仓 `GetContent` / `OpenDocument` / `ACTION_GET_CONTENT` 零匹配；清单只有 `INTERNET` + `ACCESS_NETWORK_STATE`（`AndroidManifest.xml:4-6`）——
走 SAF 不需要任何存储权限，这条与现有「零权限」基调一致。

### 2) 新增：`ReleaseAttachmentStore`（app 侧，建议放 `ui/repository/` 或 `core/`）

- `fun stage(context, owner, repo, tag, uri): StagedFile`：`contentResolver.openInputStream(uri)` → `<name>.part` → `renameTo`；
- `fun dir(context, owner, repo, tag): File`、`fun clear(...)`、`fun pruneExpired(context, ttlMs = 7.d)`；
- 复用 `DownloadPaths.sanitize/sha256Of/formatBytes`（顺带解决「3 份 formatBytes」里的 1 份）。

### 3) 新增：Rust 侧上传资产（现在完全没有）

证据：`uploads.github.com` 全仓零匹配；`core/src/api/client.rs` 只有 JSON 方法，`post_json` 写死 `Content-Type: application/json`（`:98-113`）。

```
core/src/api/client.rs   + post_binary(url, token, file, contentType)   // 流式 body + Content-Length
core/src/api/github.rs   + upload_release_asset(host, token, owner, repo, release_id, name, file, label?)
                            → POST https://uploads.github.com/repos/{o}/{r}/releases/{id}/assets?name=…
core/src/bridge/jni.rs   + nativeUploadReleaseAsset(...)
app/.../core/RustBridge.kt + suspend fun uploadReleaseAsset(...): String?
```

顺序约束：**资产必须挂在 release 上** → 「发布」按钮的语义是「先 create/update release，拿到 id，再逐个传附件」；
草稿态同样可以传（GitHub 允许 draft 附件，实现时确认一次）。单文件上限 2 GiB（GitHub 限制），同名资产会 422（要用 `label` 或改名策略）。

### 4) 改造：`ReleaseEditScreen`

| 改动 | 位置 |
| --- | --- |
| 表单改成「左槽 + 右内容」行结构；分支 chip 并入标签行 | `ReleaseScreens.kt:447-468` |
| `MarkdownBodyField` → 新的 `ReleaseNotesEditor`（行标识槽，无框，自动增高） | `:628-657` 替换 |
| 「生成说明」改成带行标记的插入 + 统计条；删掉确认弹窗 | `:408-427`、`:510-525` |
| `SwitchCard` → 一行开关 | `:665-685`、`:491-500` |
| 新增附件区（导入 / 引用 / 上传 / 移除 + 常驻暂存说明） | 新增，插在更新内容之前 |
| 保存流程：先 create/update → 再传附件 → 更新进度 | `save()` `:383-406` |
| 附件行抽成可复用 composable（详情页 `AssetRow` 与编辑页新增行同源） | `:710-793` |

### 5) 不动的地方

`:downloader` 模块**不动**（只借它的目录约定与 `sanitize`）；`DownloadPaths` 的 FileProvider 只暴露 `downloads/`，
而上传是 App 自己读文件、不经过 FileProvider，所以 `downloader_file_paths.xml` 也不需要改
（除非以后要「导出到文件管理器 / 分享待发附件」，那时再加）。

---

## 五、已知取舍与待确认

1. **行标记的判定**：原型用「行文本匹配」近似，真机上应记录插入行的集合（`AnnotatedString` 区间或逐行 diff），
   否则用户把生成的行改成同名文本会被误标；
2. **草稿 TTL 7 天**是拍的，与 `readme_images` 的 24h 不同量级 —— 待发布资产比网页缓存重要，但也不能无限留；
3. **上传中断**：编辑页退出后上传是否要继续？（本设计里上传只在「发布」这一下发生，不做后台续传；如果要，
   那就得走 `:downloader` 的 Service 模型 —— 但那是**下载**语义，需要新服务或扩展现有服务）；
4. **标签不存在时**：GitHub 会自动按 `target_commitish` 建 tag，生成说明要求 tag 已存在（现状 `generate()` 的报错文案保持）；
5. **合并行的标签宽度**：标签 chip 里的输入框定宽 68px（放 `v1.0.39` 这类短 tag 刚好）。真机上建议按
   `TextMeasurer` 量出内容宽度自适应，超长 tag 时把标题输入挤窄而不是裁 tag（对齐 `docs/specs/ui-design.md` §1
   「由值负责省略、不许挤压名称」的同一条原则）；
6. **自动保存**：改字段 900ms 防抖落草稿，与「返回/发布」时的显式保存共用同一份 JSON；
   频繁写盘要放在 IO 线程，且只在内容真的变了才写（原型里没做 diff，真机加一个 `equals` 比较）。

## 六、落地情况（2026-09 已实现）

| 项 | 状态 | 落在哪 |
| --- | --- | --- |
| 类型行（滑动胶囊 + 「设为最新」小开关） | ✅ | `ui/repository/ReleaseEditParts.kt` `ReleaseTypeRow` |
| 标签 + 标题 + 分支合并行 | ✅ | 同上 `ReleaseTagTitleRow` |
| 附件区（导入 / 引用 / 移除 / 状态 / 不确定进度条） | ✅ | `ReleaseAttachmentRow` / `ReleaseAttachmentEmpty` |
| 行标识槽编辑器（无框、行号、当前行、定宽标记列、自动增高） | ✅ | `ui/repository/ReleaseNotesEditor.kt` |
| 生成说明改为行标记（不再整段覆盖） | ✅ | 同文件的纯函数 `insertGeneratedNotes` / `generatedLineIndices` / `dropGeneratedNotes` |
| 状态条（校验失败 / 草稿已自动保存） | ✅ | `ReleaseStatusNote`（默认不占高度） |
| 暂存区 + 草稿清单 + TTL 回收 | ✅ | `core/ReleaseAttachmentStore.kt`（`pruneExpired` 在进编辑页时调用） |
| 系统文件选择器 | ✅ | `ReleaseEditScreen` 里的 `GetMultipleContents` |
| Rust 上传资产（`uploads.github.com`） | ✅ | `client.rs post_binary` / `github.rs upload_release_asset` / `jni.rs nativeUploadReleaseAsset` / `RustBridge.uploadReleaseAsset` |
| 单测 | ✅ | `ReleaseAttachmentStoreTest` / `ReleaseNotesMarkingTest`（含源码级钉子） |

### 落地时与原型的三处偏差（都是实现约束，不是走样）

1. **上传不是流式的**：reqwest 只开了 `json / rustls-tls / http2`，`Body::wrap_stream` 在 `stream`
   feature 下，而它会连带 `wasm-streams`（离线取不到、交叉编译也不需要）。实现改成
   `spawn_blocking` 读进内存 + **256MB 上限**（GitHub 允许 2GiB，但移动端一次分配 2GB 必被 OOM）。
   升级路径写在 `client.rs post_binary` 的注释里；
2. **每行没有「上传」按钮**：资产必须先挂到 release 上，所以上传只有「发布 / 保存」一个入口
   （失败的行给「重试」，重试复用同一个 release id —— 重新 create 同名 tag 会 422）；
3. **预览改成底部弹层**：原型是原地替换正文，但编辑区挂 WebView 会打断「正在写」的状态，
   改成 `ModalBottomSheet` 里用 `ReadmeWebView` 渲染（与详情页同一条渲染路径）。

### 仍然待确认

- **改 tag 后旧的暂存目录**要等 TTL（7 天）回收：附件是按导入时的 tag 落盘的，
  之后改 tag 不会搬文件（清单里存的是绝对路径，功能不受影响，只是目录会留到 TTL）；
- **上传中断**不续传：Rust 侧一次阻塞调用，取消不了；要续传得引入分片/可取消的上传通道；
- **行标记的判定**用「行文本集合」近似（`generatedLineIndices`）：用户把生成的行改成同名文本会被误标。
  真要做严格，得记录插入行的区间并在编辑时做逐行 diff。

## 七、自检

```bash
node design/release-redesign/smoke.js     # 43 项：渲染 / 附件状态机 / 行标识 / 草稿恢复 / 密度切换
```

改完原型请同步入库副本 `docs/specs/prototypes/release-redesign.md`。
