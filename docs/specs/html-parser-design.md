# HTML（网页）解析与跳转导航 · 设计

> 模块：`core/src/html/`（`mod.rs` 入口 + `matcher.rs` 实现）
> 消费端：`ReadmeWebView`（WebView 链接拦截）→ `RepositoryScreen.handleLink`（应用内路由）
>
> **本文档是补写的。** 代码注释里一直引用 `docs/html-parser-design.md`（`core/src/html/mod.rs`、
> `matcher.rs`、以及 `ReadmeWebView.kt` 的「对齐 §5.1」），但 `/docs/` 当时整个在 `.gitignore` 里，
> 文件从未进过版本库。补写时以**代码实际行为**为准（`matcher.rs` 及其 16 个单测），
> 并把章节号对齐那两处既有引用：**§3 = 匹配规则**、**§5.1 = 末尾斜杠判目录**。
> 改动行为时请同步改这里，别再让它掉回「注释引用一份不存在的文档」的状态。

---

## 1. 职责与边界

**做**：把任意一个从 README（或 issue / PR 正文）里点出来的链接，归一化成**一个结构化跳转目标**
（`Destination`），交给 Compose 层决定「进哪个页面 / 交给浏览器」。

**不做**：

- **不解析 HTML、不抽 DOM**。模块名里的「HTML 解析」指的是**网页链接的分类与跳转语义**，
  真正把 HTML 渲染出来的是 WebView。历史上 `core/src/html/parser.rs` 曾自己解析 HTML +
  自己用 Compose 渲染正文，已在 `4719ab5`（issue/PR 正文切 WebView）整体删除 —— **不要再往回加**。
- **不发起网络请求**，不知道也不判断仓库/分支是否存在（那是 `RustBridge` 各接口的事）。
- **不决定视觉**。只给 `type` + 结构化字段，怎么展示是 UI 的事。

## 2. 数据结构

### `Destination`（下发 Compose 层的 JSON）

| 字段 | 序列化名 | 说明 |
|---|---|---|
| `dest_type` | `type` | `repo`/`blob`/`tree`/`raw`/`issue`/`pull`/`commit`/`user`/`anchor`/`external` |
| `owner` / `repo` | 同名 | 省略 `None` |
| `branch` / `path` / `lines` | 同名 | `lines` 形如 `L12-L34`，只在锚点里出现 |
| `number` | `number` | issue / pull 的编号 |
| `sha` | `sha` | commit |
| `login` | `login` | user |
| `url` | `url` | **最终绝对 URL**（相对链接已展开） |
| `is_own` | `is_own` | `owner == current_user`（大小写不敏感） |
| `is_external` | `is_external` | 是否站外 |

`Option` 字段为 `None` 时 `skip_serializing_if` 掉；`is_own` / `is_external` 永远下发。

### `ResolveContext`（解析所需的上下文）

`host`（`github.com` 或 GHE 域名）、`owner`、`repo`、`branch`（当前默认分支）、
`base_dir`（**当前文件所在目录**，`""` = 仓库根，不带尾部 `/`）、`current_user`。

`base_dir` 是相对链接能不能解析对的关键 —— 它由 `ReadmeWebView` 从 GitHub 返回的
`<div id="readme" data-path="docs/README.md">` 反推（`readmePathOf` → 取目录部分）。

## 3. 匹配规则（有序、声明式，命中即停）

`resolve_link(url, ctx)` 先 trim，然后**按下面的顺序**判定，**命中即返回**：

| # | 输入形态 | 判定 |
|---|---|---|
| 1 | 空串 | `anchor`（本页锚点） |
| 2 | 以 `#` 开头 | `anchor`，`url` 保留 `#xxx` |
| 3 | 带 scheme（RFC 3986 形态） | `http`/`https` → 拆 host/path/frag，host 属 GitHub 族则走 §4，否则 `external`；**其余 scheme（`mailto:`/`tel:`/`javascript:`…）一律 `external`** |
| 4 | 以 `//` 开头（协议相对） | 同 3 的 host 判定 |
| 5 | 以 `/` 开头（根相对） | 当作 `ctx.host` 上的路径，走 §4 |
| 6 | 其余（相对路径） | 基于 `ctx.base_dir` 归一化，见 §5 |

「GitHub 族」= `is_github_host(host, ctx.host)`：与 `ctx.host` 相同（大小写不敏感）、
`github.com`、`*.github.com`、或 `raw.githubusercontent.com`。

### §4 站内路径分类（`classify_github`）

先按 `?` 切掉查询串再分段（见 §5.4），`segs` 是**去掉空段**后的路径段：

| 路径形态 | `type` |
|---|---|
| `raw.githubusercontent.com/{o}/{r}/{branch}/{path...}`（≥3 段） | `raw`（owner/repo/branch/path 全部带出） |
| `/{login}` | `user` |
| `/{owner}/{repo}` | `repo` |
| `/{owner}/{repo}/{blob\|tree\|raw}/{branch}/{path...}` | 同名 `blob`/`tree`/`raw`（fragment 里的 `L12` 收进 `lines`） |
| `/{owner}/{repo}/commit/{sha}` | `commit` |
| `/{owner}/{repo}/issues/{数字}` | `issue`（`number`） |
| `/{owner}/{repo}/pull/{数字}` | `pull`（`number`） |
| `/{owner}/{repo}/{单段列表级子页}` | `repo`（折叠，见下） |
| `/{owner}/{repo}/{其余}` | `tree`（裸文件/目录路径） |
| `[]`（只有域名） | `external` |

**列表级子页**（`is_repo_subpage`）会被**折叠成 `repo`**，不做文件/目录导航：
`issues`、`pulls`、`commits`、`releases`、`wiki`、`discussions`、`actions`、`projects`、
`security`、`insights`、`settings`、`stargazers`、`watchers`、`forks`、`branches`、`tags`。

**「裸路径 → tree」是给 README 相对链接用的**：WebView 里相对链接经 `baseURL` 展开后是
`/o/r/path/to/dir`（没有 `blob`/`tree` 段），这种形态判 `tree` 而不是 `external`，
否则站内相对链接会被当成站外丢给浏览器。

## 5. 相对链接归一化（`resolve_relative`）

以 `ctx.base_dir` 为起点，逐段应用目标路径：`.`/空段跳过、`..` 弹出一段，其余入栈；
再按 §5.1 决定 `blob`/`tree`，最后**重建**成绝对 URL。

### §5.1 末尾 `/` 判目录

路径以 `/` 结尾 → `tree`；否则 → `blob`。**这个判定在两处必须一致**：

- Rust 侧 `resolve_relative`（本模块）；
- Kotlin 侧 `ReadmeWebView.toNavigationUrl`（把 raw 形态还原成网页形态时，同样按末尾 `/`
  判 `tree`/`blob`，注释里就标着「对齐 html-parser-design.md §5.1」）。

### 5.2 重建 URL 必须带 `blob`/`tree` 段

`https://{host}/{o}/{r}/{blob|tree}/{branch}/{path}` ——`…/main/docs/a.md` 不是合法网页地址
（会 404）。此前只被 `path`/`lines` 消费所以没暴露，但它与 `type` 声明的形态必须自洽。

### 5.3 查询串在最后拼回

`./a.md?plain=1` → 文件名是 `a.md`，`?plain=1` 拼在 `#frag` 之前。

### 5.4 查询串不参与分段

`…/blob/main/a.md?plain=1` 若把 `?plain=1` 留在最后一段，文件名会变成 `a.md?plain=1`
（下游打开必然 404）。所以**分段用切掉 `?` 的路径本体**，而最终 URL 仍用原始 path。

### 5.5 相对路径里的冒号不是协议

`./a:b.png` 的「scheme」是 `./docs` 含 `/`，形态非法 → 判 `blob`。
判定用 RFC 3986 的 scheme 形态：`^[A-Za-z][A-Za-z0-9+.-]*:`。注意 `foo:bar.png` 在 RFC 里
**确实是**带 `foo` 协议的 URI（浏览器也这么解释），这里保持一致。

## 6. 已知边界（改之前先看这里）

| 边界 | 现状 | 备注 |
|---|---|---|
| **GHE 的 `raw.<ghe-host>`** | Rust 侧判成 `external` | `classify_github` 里的 `starts_with("raw.")` 分支只在 host **已通过** `is_github_host` 时才有机会命中（如 `raw.github.com`），而 `raw.<ghe-host>` 过不了那道门。实际链路不受影响：Kotlin 侧 `toNavigationUrl` 会先把 raw 形态还原成 `blob`/`tree`（`ReadmeRenderUrlTest` 的「GHE 走自身 raw 路径」）。要支持 GHE raw 直链，这里是第一处要改的 |
| `raw.githubusercontent.com` 必须显式认 | 已在 `is_github_host` 里单列 | 它既不是 `github.com`、也不以 `.github.com` 结尾；漏掉它时 `classify_github` 的 raw 分支永远走不到，README 里的绝对 raw 链接会被当站外丢给浏览器 |
| `anchor` / `user` / `raw` 的消费 | **未接**（`handleLink` 里 `else -> {}`） | 分类已经做出来了，UI 层还没用；改 UI 时不要以为「解析器没给」 |
| 分类不判权限 | `is_own` 只是「owner 是不是登录用户」 | 不等于有写权限；写操作的门控看 `RepoInfo.canPush` |

## 7. 消费链路

```
WebView 点击
  └─ ReadmeWebView.shouldOverrideUrlLoading
       ├─ 同文档锚点（URL 与文档基准只差 fragment）→ 放行给 WebView 自己滚，不进路由
       ├─ toNavigationUrl：raw 形态 → 还原成 blob/tree（相对链接在 raw 基准下会被解析成 raw）
       ├─ RustBridge.resolveLink(url, host, owner, repo, branch, baseDir, login)   ← 本模块
       └─ 解析失败 → **拦截但不处理**（点了没反应）
            └─ 不能把 URL 交回 WebView：那会让 README 视图自己导航到 raw 地址，页面被替换且退不回来
  └─ RepositoryScreen.handleLink（应用内路由）
       repo → 打开仓库页   |   blob → 文件页（带 lines 高亮）   |   tree → 代码页
       issue → Issues      |   pull → PR 列表                  |   commit → 提交列表
       external → 浏览器（若目标是图片则弹应用内查看器）
```

JNI 出口：`Java_com_branchbase_core_RustBridge_nativeResolveLink`
（`url, host, owner, repo, branch, baseDir, currentUser` → `Destination` JSON），
Kotlin 侧包装是 `RustBridge.resolveLink`。

> **JNI 签名编译期查不出来**：`external fun` 与 `Java_*` 只靠名字关联，参数表对不上时两侧都能编译通过，
> 直到真机点下链接才炸 `UnsatisfiedLinkError`。加参数记得同步 `JniSignatureTest`（见
> [`BUILD-NOTES.md`](BUILD-NOTES.md)）。

## 8. 测试

| 位置 | 覆盖 |
|---|---|
| `core/src/html/matcher.rs`（`mod tests`，16 例） | 自有/第三方仓库、blob 行号、issue、相对文件的 `.`/`..`、锚点、站外、user、裸路径判 tree、列表级子页仍为 repo、绝对 raw 不是 external、`?plain=1` 不进文件名、相对链接带查询串、相对路径里的冒号、`mailto:` 仍是 external |
| `app/src/test/.../ReadmeRenderUrlTest.kt`（24 例） | `data-path` → README 路径与基准目录、raw 基准 URL、raw↔blob/tree 还原、末尾斜杠判目录、斜杠分支名、大小写、图片改判、GHE |

Rust 侧：`cd core && cargo test`。Kotlin 侧：`./gradlew :app:testDebugUnitTest`。

## 9. 维护须知

1. **改任何判定都要同步本节与 §3 的规则表**，并补 `matcher.rs` 的单测 —— 这张表就是契约。
2. **`§` 号是外部引用锚点**：`matcher.rs` 指向 §3、`ReadmeWebView.kt` 指向 §5.1。
   重排章节会悄悄让注释指错地方。
3. **不要把 HTML 解析加回来**：正文渲染一律走 WebView，本模块只管链接语义（见 §1）。
