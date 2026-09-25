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

### 同一账号的两种登录方式**可以共存**（1.0.78 修）

账号身份 = **host + login + 登录方式**（`AccountStore.indexOfSameIdentity`，纯函数有单测）。
所以「已经用 OAuth 登录过，再用密钥登录同一个账号」会**新增一条记录**，而不是覆盖旧的那条：

| | 旧行为（≤1.0.76） | 现在 |
|---|---|---|
| 匹配键 | `login + host` | `login + host + **auth**` |
| 用户看到 | 账号列表里 OAuth 那条消失，只剩「PAT 令牌」 | 两条独立记录，各自带方式徽标（`OAuth 授权` / `PAT 令牌`） |
| 旧凭据 | 被静默丢弃，**且不可逆** —— 下次覆盖会把新的那条也换掉 | 保留，可在账号页切换或单独删除 |
| 本地数据 | —— | 不受影响：仓库与任务按 **login** 隔离（`repos/{login}/…`），两条指向同一份 |

两条兜底规则（都是为「不让用户的凭据被静默丢掉」服务的）：

- **老记录（`auth` 缺失 → `UNKNOWN`）是通配，但精确优先**：先找同 host + 同 login + 同 auth 的
  精确匹配，找不到才退回 UNKNOWN 那条并就地升级 auth。否则老记录只要排在前面，就会抢走本该
  命中具体方式那条的机会；
- **登录不再重置检查结果**：`AccountStore.add` 原来每次都把 `lastCheck = 0 / status = UNKNOWN`
  写死，于是启动时那次账号检查（`MainActivity` 后台线程写同一个 store）**到设置页就被当成
  「没查过」**，必然重探一遍 —— 用户看到的就是「老是报失效」+「设置页还要再查一次」。
  现在保留原有 `lastCheck` / `status`，重探时机只由 `AccountChecks.isStale`（5 分钟窗口）决定。

> 删除确认框会点名是**哪一种**登录方式，并在同一账号还有其它记录时说明「这次只删这一条」。

### 「添加账号」是**子流程**，不是登出（1.0.79 修）

账号页的「添加账号」原先直接接 `onLogout` —— 点一下等于把自己登出：
`LoginViewModel.logout()` 删掉全局 `session` 键、状态回欢迎页。两个后果用户都撞上了：

| 后果 | 为什么 |
|---|---|
| **回不去账号页** | 欢迎页刻意不拦截返回键（未登录时是「再按一次退出 App」），所以从账号页进来的人既回不到列表，按返回还可能直接退出应用 |
| **凭据被误删** | 只想再登一个号，当前账号的 `session` 却已被清掉 |

现在走 `AddAccountFlow`（进程内瞬时标记，与 `ThemeRuntime` / `NotifSnapshot` 同一模式）：
账号页 `begin()` → 登录界面接管整屏（`LoginFlow` 把 `LoggedIn` 那一格换成欢迎页，
因此主界面根本不组合）→ 登录成功或用户返回时 `finish()`。

两条收尾路径（外加一条兜底过期）：

1. 登录成功（`LaunchedEffect` 观察到 `LoginState.LoggedIn`）；
2. 在欢迎页按返回（`PageBackHandler`，仅新增流程中启用）→ 回账号列表；
3. **兜底过期**：进了流程又中途离开（切 Tab / 返回设置）会留下标记，账号页**进入时**
   调 `dropIfStale()` 丢掉超过 2 分钟的残留。

> ⚠️ **整屏接管会销毁主界面子树，返回落点要寄存。** 新增登录页接管时 `MainScreen` 被销毁，
> `selected` / `showProfile` / `subPage` 全部回初始值 —— 用户从「设置 → 账号管理」进去、
> 返回却落在个人页主页。落点寄存在 `MainNavMemory`（进程内，不受销毁影响），
> `MainScreen` 返回时**消费一次**恢复（读走就清空，否则下次启动会被拽回旧页面）。
> 注意 `rememberSaveable` **救不了**这种情形：值存在所在那层的 `SaveableStateRegistry`，
> 那层销毁注册表就注销了 —— 它保的是「保活的兄弟页之间切换」，不是「整棵子树被换掉」。

> ⚠️ **页面切换一律交给登录状态机（`LoginState.AddAccountWelcome`），不要在根布局里做条件替换整屏。**
> 1.0.79–1.0.82 连栽四次，形式各不相同但根因同一个：**都在假设「某个状态变了，根布局就会按我
> 想的方式重渲染」**。三种形式分别败于 `onDispose` 自取消、`AnimatedContent` 的 contentKey
> 不变、旁路状态没被订阅。现在状态一变 `PageSwitcher` 必然换页 —— 那是本文件里唯一一处被
> 反复验证过的换页机制，不需要对组合行为做任何假设。
>
> ⚠️ **（历史）根布局曾必须收集 `LoginViewModel.addingAccount`**，而不要直接读 `AddAccountFlow` 的 Compose 状态。
> 1.0.81 的真机日志证明了这一点：标记确实置上了（`新增流程标记 = true`），但根布局
> **一次都没重组**（它那条诊断从未重放）。而根布局对 `LoginState` 的变化是确定会重组的，
> 所以把同一事实经 `LoginViewModel` 的 StateFlow 转发一次 —— 真源仍只有 `AddAccountFlow`
> 一处，是换通道而不是双写。`AddAccountFlowTest` 有两条**源码级**断言钉住它。

> ⚠️ **不要在账号页的 `onDispose` 里清这个标记**。第一版正是这么写的，结果
> 「添加账号」**完全没反应**：`begin()` 置位后根布局不再组合主界面（这是刻意的，
> 否则账号页与登录界面叠两层），主界面一撤账号页立刻 dispose → `onDispose` 马上
> `finish()` → 标记被清 → 又渲染主界面。**那个清理的对象恰恰是它自己触发的**。
> 这个坑写在 `AddAccountFlow.begin` 的注释里，不要在重构时“顺手加回一个 DisposableEffect”。

配套的「谁该成为当前账号」规则收在 `AccountStore.planUpsert`（纯函数有单测）：

- **首登态**（`makeCurrent = true`）→ 登完成为当前账号，进主界面；
- **刷新态**（账号页新增，`makeCurrent = false`）→ 新号**不夺**当前账号，用户还在原来那个号上；
- 两种情形下，若命中的是**当前那条记录**，一律不切换 —— 否则「只是重新授权一下」会被切走。

**登录方式由用户主动选，不做自动择优**（产品决定）：账号页每条记录一行、各带方式徽标，
点「切换」即把那条记录的凭据同步到全局 `session` 键并重建 Activity —— 全 App 随即改用该凭据。
不实现「两种方式自动挑一个」（例如优先 OAuth、失败回落 PAT）：那会让「当前用的是什么身份」
变得不可预期，而提交 / 开 PR 这类写操作的身份必须一眼可辨。

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
- **能力边界**（libgit2 浅 clone / pull（fast-forward）/ commit / push / **三方 merge（1.0.102 起允许，
  只新增提交、不改写已推送历史）**）与「不 rebase / 不强推、不隐式 stash」等
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
