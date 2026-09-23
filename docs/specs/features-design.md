# 功能规格（用户可见行为与判定）

> **这份文档是什么**：根 `README.md`「🚀 功能特性」一节的**正文迁出与补全**（2026-09 拆分）——
> 那里只留一行一条的「功能一览」，**判定规则、边界、为什么这么定**都收在这里。
>
> **分工**：模块内部的装配与取舍见 [`modules-design.md`](modules-design.md)；界面的配色与动效见
> [`ui-design.md`](ui-design.md)；页面重绘见 [`screens-design.md`](screens-design.md)；设置项见
> [`settings-design.md`](settings-design.md)；本地 Git 能力见 [`local-git-engine-design.md`](local-git-engine-design.md)；
> 决策页面见 [`decision-pages-design.md`](decision-pages-design.md)；返回键见 [`NAVIGATION-NOTES.md`](NAVIGATION-NOTES.md)。
> **本文不重复它们的正文，只写「用户看到什么」这一层。**

---

## 1. 登录与账号

| 方式 | 怎么走 | 边界 |
|---|---|---|
| **OAuth 授权登录** | 授权码 + **PKCE**；`client_secret` 是**编译期注入**（`local.properties` → `BuildConfig`，`app/build.gradle.kts:85`） | 自己出包必须用自己的 GitHub OAuth App（仓库里没有也不该有 secret） |
| **访问密钥（PAT）** | `GET /user` **先校验再落库**（无效 / 权限不足挡在登录前）；支持一键跳网页端并**预填所需权限** | 登录时无需数字口令；账号管理里标记为「PAT 令牌」 |

- **新设备首次登录**要过一次验证：开了双重验证是 6 位口令（或 GitHub Mobile 确认）；
  没开则走**设备验证**（验证码发邮箱；装了 GitHub Mobile 会收到两位数口令在手机上确认）。
  两种都**只在新设备首次登录**出现。
- **权限清单是契约**：密钥页预填的权限必须与 OAuth 申请的 scopes 一致（`ui/auth/KeyLogin.kt:19`，钉子 `KeyLoginTest`）。
- **会话 JSON 的字段名也是契约**：必须与 `sessionInfo()` / `AccountStore.accessTokenOf()` 对得上（`ui/auth/KeyLogin.kt:38`）；
  换字段名 = 全员掉线。
- **OAuth 回调深链** `branchbase://oauth/callback`（`MainActivity.kt:137` + `AndroidManifest.xml:44-50`）——
  scheme / host / path 三段与解析函数必须逐字一致，**目前既无单测也无文档**（见 `review/01-契约审计.md` B5）。
- 两种登录方式在欢迎页各有一个入口，点进去各有一页**带渲染动画的流程要点介绍**
  （授权登录：数字口令格依次点亮；密钥登录：权限清单逐个打勾 + 「无需口令验证」卖点呼吸高亮）。

## 2. 个人主页

- 概览 / 仓库 / 动态三栏 + 气泡导航；星标 / 软件包 / 项目 / 设置子页。
- **动态页的 payload 映射是外部契约**：`GET /users/{login}/events/public` 的字段解释与官方文档**不完全一致**，
  实测为准（`ui/profile/ProfileScreen.kt:602`，钉子 `ActivityFeedTest`；踩坑记录见 `VERSION-NOTES.md` 的 1.0.65 条目）。
- 贡献日历的区间计算收在纯函数里（`ContributionRangeTest`）。
- `/user/events` 这个端点**不存在**（404）—— 动态页必须走 `/users/{login}/events/public`。

## 3. 仓库浏览

列表 / 详情 / README 渲染 / 语言统计 / 贡献者 / 分支同步（**服务端合并**，入口收在 `⋮` 气泡，见
`docs/specs/prototypes/` 与 `screens-design.md`）。

- **README 渲染**：WebView 高度 = 整篇内容高度，滚动交给外层原生列表；
  页面级持有者让 WebView 活过 LazyColumn 的回收（`ui/repository/ReadmeWebView.kt`）。
- **WebView 复用有身份**：`readmeDocKey(html, host, owner, repo, …)`（`ReadmeWebView.kt:410`）——
  换分支/换仓库就是另一篇，**不许复用**（钉子 `ReadmeDocKeyTest`）。
- 正文链接的归一化与跳转规则见 [`html-parser-design.md`](html-parser-design.md)。
- **仓库级凭据**（给「当前账号打不开的私有仓库」单独配一条令牌）：规则是
  **① 账号优先** —— 账号能打开就一律用账号；**② 打不开时自动回退**到该仓库的凭据；
  **③ 回退后读与写都用它**（提交 / 开 PR / 合并也走这条令牌，因此**操作身份可能与当前账号不同**，
  页面顶部有横幅明示并给「改用账号」的退路）。
  添加入口在失败卡的「用访问令牌打开」（可勾选记住），**管理入口在 设置 → 仓库凭据**，
  且**只在令牌登录模式（PAT）下显示**；凭据存在独立 prefs 文件 `repo_credentials`，
  已排除出云备份与设备迁移。
- **打不开某个仓库时不再只给一句原始错误**（404/403）：会说明「GitHub 对无权限的私有仓库也返回 404」这层歧义，
  并给出三条出路 —— **用访问令牌打开**（PAT 只在本次会话内覆盖，不落盘、不进日志）、
  **建一个带 `repo` 权限的令牌**、**在浏览器打开**；同时用 `x-oauth-scopes` 探测当前令牌**已授予**的权限，
  能确定时说清「有 repo 权限，所以多半不是权限问题」或「没有 repo 权限，私有仓库必然看不到」。
  仓库页概览此前遇到失败是**静默**的（信息卡缺字段、不报错），现在与各 Tab 一样走这张失败卡。

## 4. 搜索

**七类**：代码 / 仓库 / 议题 / 拉取请求 / 用户 / 提交 / 主题（`ui/search/SearchScreen.kt:1460`；
议题与拉取请求共用 `searchIssues` 端点、分别解析 —— `:222`）。

| 议题 | 结论 | 真源 |
|---|---|---|
| 结果可点进站内 | 仓库 → 仓库页；issue/PR → 详情；提交 → 提交详情；代码 → 文件页（解析阶段就带出 `SearchTarget`，走与通知深链接同一条路由）；**用户主页 / 主题页交给浏览器** | `ui/search/SearchTarget.kt` |
| 缓存**按账号隔离** | 缓存键里带 login：`searchCacheKey(type, query, sortKey, login)` —— 搜索结果含私有仓库/私有代码，跨账号命中既是错误结果也是权限泄漏 | `ui/search/SearchQuery.kt:50-51` |
| 只有**第一页**进缓存 | 首页结果写 `cacheManager.put(...)`（`SearchScreen.kt:235`）；翻页结果不写 | `SearchScreen.kt:207,235` |
| 翻页用**显式按钮** | 「加载更多（已显示 N / 共 M）」（`SearchQuery.kt:149`）—— 不做滚到底自动翻页：搜索类接口限额很紧（代码搜索约 10 次/分钟、其它约 30 次/分钟） | `SearchQuery.kt:67,149` |
| 翻页按 **key 去重** | key 用定位信息（`searchItemKey(target, title)`），不用标题 —— 否则不同仓库里的同名 issue 会被悄悄合并 | `SearchScreen.kt:298-299` |
| 页码只在**真拿到数据**时推进 | 空页 / 限流不推进，避免跳到拿不到的页码 | `SearchQuery.kt:143` |
| 限流**按状态码给话** | 「被 GitHub 限流了…等 1 分钟再试」；语法错误 / 未登录 / 网络问题各自的文案 | `SearchQuery.kt:56-67` |
| 到 **1000 条上限**单独判 | 「已到 GitHub 的搜索上限（只提供前 1000 条结果）…」并**收掉按钮**（不再当成语法错误） | `SearchQuery.kt:69-72`、`SearchScreen.kt:345` |
| 仓库项标注存取关系 | `RepoRelation`：账号仓库 / 协作仓库 / 非账号仓库 / 非协作仓库 | `ui/repository/RepoRelation.kt` |

## 5. 提交与本地仓库

- **三种提交模式**：单文件 / 多文件 / 本地仓库（对齐 GitHub 官方行为）。
- **Git 悬浮球只在「本地仓库（Git）」模式显示**：判定收在 `showGitBubble(mode)`（`ui/repository/GitBubblePanel.kt:58`），
  三种模式都有单测 —— 另外两种直接调远端 API、本地没有工作树，球不显示。
- 本地仓库模式下，球里给的是：就地切换提交模式、分支管理 / 对比、本地分支同步与刷新；
  徽标是本地工作树的**改动数 / 领先落后**。
- **能力边界**（libgit2 浅 clone / pull（fast-forward）/ commit / push）与「不做 merge/rebase、不隐式 stash」等
  硬边界见 [`local-git-engine-design.md`](local-git-engine-design.md) §1 / §7。
- 有后果的动作走**决策页面**（分叉 / 撤销 / 上游 / 回退 / 删除警告 / 暂存提交 / 身份 / 敏感信息 / 草稿恢复 / 离线冲突 …），
  见 [`decision-pages-design.md`](decision-pages-design.md)。
- **提交前会扫一遍敏感信息**（PAT / 私钥 / `key=value` 形式的口令），命中就先给警告页；
  **扫描能力不可用时直接拦下**并说明原因（宁可不让提交，也不让「以为扫过了」）。
- **开 PR 一条龙**：命名分支 → 确认变更文件与提交信息 → 开 PR，**三步都真执行**；
  待提交内容取文件页保存过的草稿，没有待提交改动时**不放行**（不会开出「与 base 相同、diff 为空」的 PR）。
- **PR 合并**：PR 详情页可直接合并（squash / merge / rebase，可勾选合并后删分支）——
  之前这条只能去网页；`mergeable == false` 时入口置灰并说明，GitHub 仍在计算时给提醒而不是替你下结论。

## 6. 独立模块入口

| 模块 | 用户可见的一句话 | 细节 |
|---|---|---|
| `:translate` | 正文页「原文 + 译文」对照；悬浮球 + 工具面板 | [`modules-design.md`](modules-design.md) §1 |
| `:downloader` | 发布页附件应用内下载（前台服务、通知进度、断点续传、装/开/分享） | §2 |
| `:imageviewer` | 正文页点图放大（缩放 / 拖动 / 下拉关闭） | §3 |
| `:editor` | 文件页**编辑态**的代码编辑器（等宽 + 行号 + 无边框） | §4 |
| `:joblogs` | 作业日志（分组切段 + 两段式缓存） | §5 |

## 7. 关于页

紧凑单屏：图标 52dp（`SubPageScreens.kt:1774`）与名称同行 + 右侧校验结论胶囊 + 两张信息卡。

- **版本号标准化展示**：工程版本 / 标准版本号 / 构建时间 / 七位哈希 / Git 配置包 / 代码编辑器（`:1712-1722`）；
  命名体系见 [`BUILD-NOTES.md`](BUILD-NOTES.md) §二。
- **构建校验五态**（发布版本 / 签名校验 / 远程校验 + 一行结论说明）由纯函数 `verifyCopy` 产出
  （`ui/profile/SigningVerify.kt:97-117`）：`校验中…` / `本地编译` / `无法校验` / `✓ 签名一致` / `签名不一致`
  （状态枚举 `BuildVerifyState`，钉子 `SigningVerifyTest`）。
- 末尾是项目主页与开发交流群入口。

## 8. 返回键

顶层「再按一次退出」、页面内逐层关闭、页面内返回箭头与系统返回键**同一条路径** ——
完整链路与两条硬规则见 [`NAVIGATION-NOTES.md`](NAVIGATION-NOTES.md)，本文不重复。

---

## 附：这份文档的维护

- 新增一个**用户可见**的功能或判定 → 在这里加一节或一行，并在根 `README.md` 的「功能一览」加一行；
- 只改实现、不改用户可见行为 → 改对应的专题文档，**不要**在这里复述；
- 引用代码请用 `file::symbol` 或 `file:line`（行号会漂，符号名可 grep 自证）。
