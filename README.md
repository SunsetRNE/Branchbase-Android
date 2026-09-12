# Branchbase

> GitHub 第三方 Android 客户端，对标官方 GitHub App 的信息架构与交互体验。

Branchbase 是一个基于 **Jetpack Compose** + **Rust** 的现代化 GitHub 客户端。业务核心（OAuth 认证、GitHub API、Git 操作、HTML 解析）由 Rust 实现，通过 JNI 桥接暴露给 Android Compose 层调用。

## ✨ 技术栈

| 层 | 技术 |
|----|------|
| UI | Kotlin + Jetpack Compose + Material 3 |
| 业务核心 | Rust（`branchbase-core`，JNI 桥接） |
| Git | libgit2（clone / pull / commit / push） |
| 网络 | reqwest（rustls-tls） |
| 异步 | tokio |
| 序列化 | serde / serde_json |

## 📁 项目结构

```
Branchbase/
├── app/                 # Android 应用层（Compose UI）
│   └── src/main/java/com/branchbase/
│       ├── core/        #   RustBridge（JNI 桥接）
│       └── ui/          #   auth / home / profile / repository / navigation / theme
├── core/                # Rust 业务核心（cdylib → libbranchbase_core.so）
│   └── src/
│       ├── models/      #   数据模型（User / Repository / Token ...）
│       ├── auth/        #   OAuth（PKCE）+ token 管理 + 2FA
│       ├── api/         #   GitHub API 客户端（REST / GraphQL）
│       ├── bridge/      #   JNI 导出函数
│       ├── git/         #   libgit2 封装（clone/pull/commit/push）
│       ├── translate/   #   翻译后端（MyMemory 匿名 / DeepSeek 或任意 OpenAI 兼容服务，自带 Key）
│       └── html/        #   README 渲染 HTML 解析 + 链接跳转
├── editor/              # 代码编辑器独立模块（封装 Sora Editor，换库/移除只动这里）
├── translate/           # 沉浸式翻译独立模块（设置/分片/占位符保护/缓存/调度/页面脚本）
│   ├── src/main/java/com/branchbase/translate/   #   纯逻辑 + Android 适配 + WebView 桥
│   └── src/main/assets/translate/                #   页面脚本（01-core ~ 04-boot）与译文 CSS
├── downloader/          # 内建下载独立模块（引擎/前台服务/通知进度/通知权限/安装与打开）
│   ├── src/main/java/com/branchbase/downloader/  #   引擎 + 服务 + 通知 + 权限 + 系统动作
│   └── src/main/AndroidManifest.xml              #   权限 / 前台服务 / FileProvider 都随模块合并
├── tools/               # 环境与构建脚本（tools/env、tools/build）
├── .github/workflows/   # CI/CD（Beta / Release）
├── version.properties   # 工程版本号配置（手动维护）
└── setup_android_env.sh # ARM64 AAPT2 替换脚本
```

> `docs/`（设计文档 wireframe / 规范）、`design/`（原型草图）、`re-workspace/`（逆向资源）、
> `tools/aapt2/`（官方二进制）、`.kotlin/` 等目录已加入 `.gitignore`，**不入库**。
> 因此代码注释里不再引用这些文件路径 —— 规格与结论直接写在注释与本文档中；
> 迁移过程中丢失的文档请以代码与本文档为准。

## 🚀 功能特性

- **两种登录方式**：
  - **OAuth 授权登录**：授权码 + PKCE，`client_secret` 编译期注入（`local.properties` → `BuildConfig`）；
    首次在新设备登录时需要过一次「口令 / 设备验证」——开了双重验证是 6 位口令（或 GitHub Mobile 确认），
    没开双重验证则走**设备验证**（验证码发邮箱；装了 GitHub Mobile 会收到一个两位数口令在手机上确认）。
    两种都只在新设备首次登录时出现，启用双重验证后设备验证不再触发
    （依据 [GitHub 文档：登录时验证新设备](https://docs.github.com/zh/authentication/keeping-your-account-and-data-secure/verifying-new-devices-when-signing-in)）
  - **访问密钥登录**（PAT）：`GET /user` 先校验再落库（无效/权限不足挡在登录前），
    支持一键跳转网页端并**预填所需权限**建密钥；登录时无需数字口令，认证方式在账号管理里标记为「PAT 令牌」
  - 两种模式在欢迎页各有一个入口，点进去各有**一页带渲染动画的流程要点介绍**
    （授权登录：数字口令格依次点亮；密钥登录：权限清单逐个打勾 + 「无需口令验证」卖点呼吸高亮）
- **个人主页**：概览 / 仓库 / 动态 + 气泡导航，星标 / 软件包 / 项目 / 设置子页面
- **仓库浏览**：列表 / 详情 / README 渲染 / 语言统计 / 贡献者
- **代码搜索**：仓库 / 用户 / issue / 代码 / 提交 / 主题多类型搜索。
  **结果可点进站内**：仓库 → 仓库页、issue/PR → 详情、提交 → 提交详情、代码 → 文件页
  （解析阶段就带出 `SearchTarget`，走与通知深链接同一条路由；用户主页/主题页则交给浏览器）；
  结果缓存**按账号隔离**（搜索结果含私有仓库/私有代码，跨账号命中既是错误结果也是权限泄漏）；
  被 GitHub 限流时按状态码给出可行动的提示（等多久 / 是否该改筛选）；
  **结果翻页用显式按钮**（「加载更多（已显示 N / 共 M）」）而**不做滚到底自动翻页** ——
  搜索类接口限额很紧（代码搜索约 10 次/分钟、其它约 30 次/分钟），自动翻页会让「随手滑两下」就撞上限流；
  翻页按 key 去重（GitHub 分页在排序变动时会跨页重复同一项；key 用 `searchItemKey` 的定位信息
  而不是标题，否则不同仓库里的同名 issue 会被悄悄合并掉），页码只在**本页真的拿到数据**时才推进
  （空页 / 限流不推进，避免跳到拿不到的页码），只有第一页进缓存；
  翻到 GitHub 的 1000 条上限时单独报「已到搜索上限，请缩小范围」并收掉按钮（不再当成语法错误）；
  仓库项标注存取关系（`RepoRelation`：账号仓库 / 协作仓库 / 非账号仓库 / 非协作仓库）
- **提交模式**：单文件 / 多文件 / 本地仓库（对齐 GitHub 官方行为）
- **本地仓库**：libgit2 浅 clone / pull（fast-forward）/ commit / push
- **沉浸式翻译**：正文页原文 + 译文对照（独立 `:translate` 模块，见下文）
- **附件下载（内建下载器）**：发布页附件走应用内下载 —— 前台服务保活、通知栏进度条、
  断点续传，完成后可直接安装 APK / 用其他应用打开 / 分享（独立 `:downloader` 模块，见下文）
- **返回键（两段式）**：主界面顶层按返回**不退出 App**，而是回登录首页（会话保留）；
  在登录首页再按一次才彻底退出。子页 / 详情优先逐层关闭自己
  —— 完整链路、两条硬规则与踩过的坑见 [`NAVIGATION-NOTES.md`](NAVIGATION-NOTES.md)
- **关于页**：版本号标准化展示（工程版本 / 标准版本 / 构建时间 / 七位哈希）

## 🌐 沉浸式翻译（模块化实现）

在**正文页**（自述文件 README、Issue / PR 主帖、发布说明 —— 即 WebView 渲染的那些页面）把每个段落
翻成目标语言、插在原文下方，形成「原文 + 译文」对照；页内右下角的「译」按钮可随时开关，
长按在「对照 / 仅译文」之间切换，整页可见文本 ≤ 5000 字符时一次翻完，更长则按视口滚动逐段翻译。

设计对齐网页版[沉浸式翻译](https://github.com/immersive-translate/immersive-translate)
（可读源码的开源旧版：[old-immersive-translate](https://github.com/immersive-translate/old-immersive-translate)）：
沿用「独立译文容器 + 视口优先 + 持久缓存 + 请求限流 + 占位符保护」的思路，落成 Android 侧的分层实现。

### 模块划分（`:translate`）

依赖方向单向：`:app → :translate`，模块内不引用任何 App 类型；翻译**后端由 App 注入**
（`RustTranslateEngine` → `core/src/translate.rs`，默认 MyMemory 匿名接口）。

| 文件 | 职责 |
|------|------|
| `TranslateLanguages.kt` | 语言模型（中英两向）+ 页面判定参数 `PageRules`（含注入 JS 的 JSON） |
| `TranslateProvider.kt` | 可选后端：MyMemory（免费）/ DeepSeek（自带 API Key，OpenAI 兼容） |
| `TranslateTextPolicy.kt` | 「这一段值不值得翻」的权威判定（纯函数，可单测） |
| `TextSegmenter.kt` | 长文本分片：段落 → 句末 → 空格 → 硬切（后端 500 字符硬上限） |
| `PlaceholderGuard.kt` | 占位符保护：URL / `@提及` / `#编号` / 邮箱 / 模板变量 / 提交 SHA |
| `TranslateCache.kt` | 进程内 LRU + 磁盘追加日志缓存（跨进程复用） |
| `TranslateEngine.kt` | 后端接口 + 失败分类（`QUOTA` / `NETWORK` / `UNSUPPORTED` / `UNKNOWN`） |
| `TranslateScheduler.kt` | 全局串行闸门 + 指数退避重试 + 两级熔断 |
| `Translator.kt` | 门面：判定 → 缓存 → 保护 → 分片 → 调度 → 还原 → 回写缓存 |
| `TranslateConfig.kt` | 用户设置与读写（自动翻译 / 目标语言 / 显示方式 / 样式 / 本地缓存 / 保护） |
| `TranslatePage.kt` | 页面资产装载：`assets/translate/*` 的 CSS 与四个脚本按序拼接 |
| `TranslateBridge.kt` | WebView JS 桥（`request` / `retry` / `state`，异步回调 + 状态回推） |
| `TranslateRuntime.kt` | 装配点：`Application.onCreate` 里 `install(this, RustTranslateEngine())` |

页面脚本同样按职责拆分（`translate/src/main/assets/translate/`）：
`01-core.js`（配置 / 状态机 / 批量队列）、`02-dom.js`（段落收集 / 跳过 / 插入 / 视口）、
`03-ui.js`（浮动按钮与状态）、`04-boot.js`（启动与策略）、`translate.css`（译文样式）。

界面侧：设置页（设置 → 沉浸式翻译）负责所有开关；正文页由 `ReadmeWebView` 拼装页面资产，
不参与任何翻译逻辑。

### 翻译服务：可以自带 DeepSeek API Key

默认用 **MyMemory**（公开匿名接口，零配置，额度约 5000 词/天，本质是句子库匹配）。
想要更好的长句 / 术语一致性时，在**设置 → 沉浸式翻译 → 翻译服务**里切到 **DeepSeek** 并填入自己的 Key：

- 走 OpenAI 兼容的 `POST /chat/completions`（`Authorization: Bearer <key>`），
  **模型名与接入地址都可改**——官方模型名会随版本调整，接口兼容又意味着可以接中转 / 自建网关；
- 请求体只带 `model` / `messages` / `stream:false`：**不传 temperature**，
  官方对「翻译推荐温度」的说法变过，与其钉死一个可能被弃用的参数，不如用服务端默认；
- 系统提示词里唯一承担职责的一条是「`⟦n⟧` 占位符必须原样保留」（配合下面的占位符保护），
  另外要求「只输出译文、不加引号/前缀」，Rust 侧还会兜底清理模型爱加的外壳（`译文：`、包裹引号）；
- Key 只存在 App 私有 SharedPreferences、**绝不注入网页**（有单测钉住这条边界），设置页里可以
  「保存并测试连接」，失败原因会区分「Key 无效 / 余额不足 / 网络不可达」；
- 实现落在 `core/src/translate/`（`mod.rs` 选后端 + 2 个 provider），Kotlin 侧只是换一个后端选项，
  分片、缓存、串行、熔断全部复用同一套。

### 关键设计决策

1. **译文是原文的兄弟节点，原文一个字都不改** —— 网页版旧实现把译文写回原文本节点、再靠隐藏副本
   实现双语，导致「切回原文 / 切换对照」都依赖额外状态并互相打架；独立容器天然幂等（有容器=已翻译）。
2. **判定规则只有一个真源** —— 阈值与跳过正则定义在 Kotlin 的 `PageRules`，随设置注入
   `window.__bbTranslate.rules`，JS 只使用不定义；原生侧仍做权威判定，防止脚本版本漂移。
3. **占位符保护** —— 待译文本里的 URL / `@提及` / `#编号` / 提交 SHA 等先换成 `⟦n⟧`，
   译后还原；任一占位符丢失即判失败，并**不加保护地重翻一次**（对齐网页版「还原失败就重译该段」）。
4. **三种熔断** —— 额度用尽（余额/限流）与 API Key 无效都立刻停发请求（继续发只是浪费额度、
   或被 401 刷屏），二者状态分开，页面分别提示「稍后再试」与「去设置检查 Key」；
   连续 3 次其它失败也暂停。状态回推页面，按钮变成「可重试」，而不是「点了没反应」。
5. **缓存两层** —— 内存 LRU（512 条）+ 磁盘追加日志（2000 条，`filesDir/translate/cache.tsv`），
   键含源/目标语言；磁盘坏行跳过而不是让缓存整体失效。
6. **短页一次翻完、长页视口优先** —— 候选文本 ≤ 5000 字符直接全翻；超过则按 `IntersectionObserver`
   滚动逐段，避免一次几百个请求烧光额度、卡住首屏。
7. **译文样式只改一个根属性** —— `body[data-bb-style]`（卡片 / 下划线 / 淡灰），切换不重翻、不重建 WebView。
8. **JS 只做 DOM** —— 网络、缓存、串行与重试全在 Kotlin，桥上一次只传一批（≤3 段）文本，
   避免把整页内容或配置在 JS ↔ Native 之间来回搬。[#3262](https://github.com/immersive-translate/immersive-translate/issues/3262)
   的 OOM 正是「大对象过桥」造成的。

### 已知边界

- 生效范围是 **WebView 渲染的正文页**；评论区由 Compose 原生渲染（`MarkdownBody`），暂不在范围内；
- 后端支持 MyMemory 与「任意 OpenAI 兼容服务」（DeepSeek 官方 / 中转 / 自建）两种态；
  再加一家（DeepL 等）只需在 `core/src/translate/` 加一个 provider + 设置页加一项；
- 术语库 / 自定义提示词 / 悬停看原文等网页版高级能力未实现
  （`TranslateEngine` 的 options JSON 与提示词构建是预留的落点，
  `translate/build.gradle.kts` 里写了移除步骤）。

参考：[主仓库](https://github.com/immersive-translate/immersive-translate) ·
[开源旧版源码](https://github.com/immersive-translate/old-immersive-translate) ·
[官网文档](https://immersivetranslate.com/docs/usage/)

## 📥 内建下载（模块化实现）

发布页附件（APK / 压缩包 / 任意产物）走**应用内下载器**：前台服务保活、通知栏进度条、
断点续传、完成后直接拉起系统安装器或交给其他应用打开。实现全在 `:downloader` 模块里，
`:app` 只注入两样只有它才知道的东西（凭据与小图标）：

```kotlin
DownloaderRuntime.install(
    this,
    DownloaderConfig(smallIconRes = R.drawable.ic_stat_download, auth = AuthProvider { url -> ... }),
)
```

### 模块划分（`:downloader`）

依赖方向单向：`:app → :downloader`，模块内不引用任何 App 类型。

| 文件 | 职责 |
|------|------|
| `DownloadModels.kt` | `DownloadRequest` / `DownloadStatus` / `DownloadTask`（含进度派生，纯数据） |
| `DownloadStore.kt` | 进程内任务表（`StateFlow<List<DownloadTask>>`）+ 取消信号表 |
| `DownloadEngine.kt` | `AuthProvider` 接口 + `HttpURLConnection` 引擎（手动跟随重定向 / Range 续传 / 进度节流） |
| `DownloadService.kt` | `dataSync` 前台服务：串行执行队列、刷新进度通知、收尾（含 Android 15 超时兜底） |
| `DownloadNotifications.kt` | 通知渠道 + 一条常驻进度通知 + 每条任务的完成/失败通知 |
| `NotificationPermission.kt` | 系统通知权限与总开关状态、申请与跳设置（**通知板块也复用它**） |
| `DownloadPaths.kt` | 落盘目录、文件名净化、`.part` 原子改名、sha256 校验（纯函数可单测） |
| `DownloadActions.kt` | 安装 APK / 打开 / 分享 / 在文件管理器里显示（FileProvider + 逐级兜底） |
| `DownloaderRuntime.kt` | 装配点与门面：`install` / `enqueue` / `cancel` / `retry` / `tasks` |

### 关键设计决策

1. **通知与下载解耦** —— `POST_NOTIFICATIONS` 被拒时前台服务照常运行、下载照常完成
   （只是没有通知）。因此权限申请不进下载主链路：它只在**通知板块**里提示
   （消息页顶部横幅 + 设置 → 通知里的状态行），被拒也不会让下载失败。
2. **凭据按「每一跳」重新决策** —— `AuthProvider` 拿到的是**当前这一跳**的 URL，
   于是「GitHub 附件 302 到对象存储」时 token 不会跟着过去；策略放在 provider 而不是
   引擎里，就不会漏掉任何一条重定向链路。
3. **`Accept-Encoding: identity`** —— 默认的透明 gzip 会让 `Content-Length`（压缩后长度）
   与实际落盘字节数对不上：进度条冲到 100% 再回退，Range 偏移也全错。
4. **先 `.part` 再改名** —— 失败/取消留下的是可续传的临时文件，最终文件名要么完整要么不存在；
   文件名来自网络，落盘前净化（去路径分隔符 / 控制字符 / `..`）。
5. **串行下载** —— 同一条链路上并发多个大文件只会互相抢带宽，进度条也失去意义。
6. **状态只有一个真源** —— 应用内 UI 与系统通知都读 `DownloaderRuntime.tasks`，
   不存在两套进度；退出页面再回来、应用退到后台，进度都还在。

### 已知边界

- 任务表在**进程内存**里：进程被杀就重来（没有「重启后继续」的语义，也没有落盘恢复）；
- 前台服务类型是 `dataSync`：Android 15 起有累计时长上限，超时回调里落成「可重试的失败态」；
- 「打开所在文件夹」没有统一契约，只能尽力而为（DocumentsUI 根 URI → 常见文件管理器包名探测）；
  失败时由调用方降级成「分享」（`ACTION_SEND` 是人人都有实现的那条路）；
- 需要用户能在系统文件管理器里直接看到文件时另走「导出」（MediaStore / SAF），
  下载主链路不申请存储权限。

## 🎞 动效（两层规格：页面 / 元素）

此前全项目的动效基本是**零**：页面切换、Tab 切换、选中态、列表增删、加载骨架全是硬切。
现在动效分成两层，每层只有一处真源，调用方只挑语义、不定参数：

| 层级 | 真源 | 管什么 |
|------|------|--------|
| 页面 | `ui/navigation/PageTransitions.kt` | 整页之间的进出（层级推进 / 同级切换） |
| 元素 | `ui/theme/Motion.kt` | **同一页面内**元素的状态变化（选中态 / 出现消失 / 图标切换 / 骨架微光） |

### 页面级

页面只声明「我在第几层」（`PageLevel.depth`），方向由层级差决定：

| 场景 | 动效 | 为什么 |
|------|------|--------|
| 有层级的推进 / 返回（`PageSwitcher`） | 水平滑动 1/4 屏 + 淡入淡出 | 子页有前后关系：前进从右进、返回向右出。登录流程内部也按层级（欢迎 0 → 模式介绍 1 → 密钥填写 / 授权中 2 → 主界面 3） |
| 同级切换（`TabSwitcher`，含仓库页切 Tab） | 淡入淡出 + 轻微上浮 | 同级没有前后关系，横向滑动会暗示错误的层级 |

约定：
- **页面状态要收敛成一条路由**（枚举 / data class），交给 `PageSwitcher` 渲染 ——
  它同时承担「渲染哪个页面」和「动画期间把旧状态原样交给退场内容」两件事。
  用 `if (x != null) { 页面(); return }` 的老写法做不到后者：状态一清空，
  退场中的页面会先变成空白、再淡出（看起来像闪烁）；
- **路由要把页面数据带上**（如 `RepoRoute.Issue(number)`），不要在页面里现读可变状态；
- 返回键**按当前路由分派一个**（不要每个页面各挂一个 `BackHandler`）：
  动画期间新旧两个页面会同时存在，两个 BackHandler 会抢同一个返回事件。

### 元素级

| 场景 | 原语 | 时长 | 已接入 |
|------|------|------|--------|
| 选中态颜色 | `selectionColor(selected, on, off)` | 180ms | 底部导航（普通 / 玻璃球 / 侧边气泡 / 仓库底栏）、个人页气泡 Tab、筛选 chip、分段控件、日志过滤按钮、设置单选行、Issue 筛选胶囊、反应 chip |
| 出现 / 消失 | `revealEnter()` / `revealExit()` | 220ms 展开 + 淡入 | 多选工具栏、撤销条、分组展开、阶段预取横幅 |
| 气泡弹层 | `bubbleEnter()` / `bubbleExit(origin)` | 180ms 锚点缩放 + 淡入 | 个人页 More 气泡、侧边气泡导航 |
| 按下反馈 | `rememberPressFeedback()` | 120ms 缩到 0.94 | 个人页气泡手柄与主项、气泡条目、侧边气泡手柄、Git 气泡手柄 |
| 图标形态切换 | `AnimatedStateIcon(icon, …)` | 200ms 交叉淡入 + 缩放 | 筛选 ↔ 关闭、全选 ↔ 取消、Git 手柄 ↔ 关闭 |
| 数量徽标 | `CountBadge(count, …)` | 220ms 缩放淡入 | 底部导航未读数（含清零时的淡出） |
| 骨架屏微光 | `shimmerAlpha()` | 700ms 呼吸 | 通知骨架、搜索骨架（此前通知页是死灰块） |
| 列表增删 | `Modifier.animateItem()` | 默认 | 通知列表、任务列表、Issue 时间线、分支对比提交列表 |
| 文本长度变化 | `Modifier.animateContentSize()` | 默认 | Issue 长评论展开 / 收起（不再让整条时间线弹跳） |

气泡类弹层（个人页 More 菜单、侧边气泡导航）按同一套规格实现：
`Popup` + 锚点定位（气泡底边贴着手柄顶边）+ `bubbleEnter/bubbleExit`（**从锚点角落缩放**，
而不是 Material 菜单那种「从上边缘往下长」），容器用 `Primer.BackgroundPrimary` 白底 +
`Primer.Border` 描边 + 16dp 圆角 + 阴影；条目按用途取 `Primer` 池内语义色
（星标 `Orange500` / 项目 `Purple500` / 任务 `Blue500` / 设置 `IconPrimary` / 登出 `Red500`）
并逐条错峰入场。

取舍：元素级**都不用 spring（回弹）** —— 这类元素在列表里反复出现，
回弹第一次看是「活泼」、第十次就是「拖沓」；tween 的稳定节奏更适合高频交互。
动画值尽量在 `graphicsLayer {}` 里读（只在绘制阶段消费），避免每帧重组。

## 🌗 深色主题

### 架构：色板 + 角色，而不是「一堆写死的颜色」

| 层 | 文件 | 职责 |
|----|------|------|
| 色板 | `ui/theme/ThemePalette.kt` | `PrimerPalette`（语义**角色**）+ `LightPrimerPalette` / `DarkPrimerPalette` |
| 门面 | `ui/theme/Color.kt` | `Primer.XXX` = 读当前色板的 `@Composable get()`，**调用点一行不用改** |
| 主题 | `ui/theme/Theme.kt` | `ThemeMode`（跟随系统/浅色/深色）+ `BranchbaseTheme(mode)` + M3 角色映射 + 状态栏/窗口底色 |
| 运行时 | `ui/theme/ThemeRuntime.kt` | 进程内 StateFlow：任何页面都能切主题，不必层层传参 |
| 开关 | `LoginScreens.ThemeModeSwitch` / 设置 → 外观 | 太阳 / 月亮 / 自动 三态图标 |

关键点：**`Primer.XXX` 的调用点完全没动**（1254 处）就跟着主题切换；
真正需要改的只有 105 处「非 Composable 上下文」（顶层颜色表、`remember` 里取色、
Canvas 绘制 lambda），它们改用 `TintRole` 角色表 / 在 composable 里pre-取色 / 参数传入。

改造前项目里有 **282 处硬编码颜色**（`Color(0xFF…)` / `Color.White`），是「未声明的第二套色板」：
在浅色页面上很自然，放进深色页面就是刺眼亮斑。现按三条规则收敛：
彩色底 → `Primer.SuccessSurface` 等角色；白色面 → `Primer.BackgroundPrimary`；
深色品牌小字 → `Primer.SuccessText` 等。

### 深色下必须一起换的部分（Compose 管不到的）

- **状态栏 / 导航栏图标明暗 + 窗口底色**：`BranchbaseTheme` 的 `SideEffect` 里跟着主题设置，
  否则深色页面顶部会压一条白条；
- **正文页的 WebView**：`github-markdown-light.css` 是浅色主题，深色时额外注入 `README_DARK_CSS`；
- **沉浸式翻译的页面脚本**：`translate.css` 增加 `body.bb-dark` 段（译文卡片 / 浮动按钮），
  `dark` 标记随设置注入给 `window.__bbTranslate`；
- **代码高亮 / 贡献图**：`CodeSyntax` 与 `ProfileColors` 也是角色化的（深色用 GitHub dark 的语法色）。

### 已知取舍

- 启动瞬间（Compose 首帧前）窗口底色仍是系统主题，深色用户可能看到一帧浅色 —— 要彻底消除需要
  在 `values-night` 里再放一份主题，代价是「用户手动锁浅色而系统是深色」时会反过来闪一下；
- 少量一次性装饰色（如个别页面的临时徽标）仍是浅色硬编码，遇到时按上面的三条规则收角色。

## 🎨 配色与弹层（单一真源）

所有颜色来自 `ui/theme/Color.kt` 的 `Primer` 色板（对齐 GitHub Primer），**不在调用处写死色值**。

早期只覆盖了 M3 的少数颜色角色，导致弹层类组件读到的仍是 **Material 基线色**（带紫调）：

| 组件 | 读的角色 |
|------|---------|
| `DropdownMenu`（搜索的类型/排序、反应选择器…） | `surfaceContainer` |
| `AlertDialog`（各页确认框） | `surfaceContainerHigh` |
| `ModalBottomSheet`（筛选手板 / 通知面板 / 工作流操作） | `surfaceContainerLow`，拖拽把手用 `surfaceVariant` |
| `NavigationBarItem` 选中胶囊 | `secondaryContainer` |

现在 `Theme.kt` 把这些角色一次性对齐到设计色板：**容器一律标准白底**，
`surfaceContainerHighest`/`surfaceVariant` 用 `Gray150`/`Gray200` 作为「白底上再垫一层」的灰，
描边统一 `Primer.Border`，底部导航选中胶囊 = 主色 12% 蓝。约定：

- 弹层统一 **白底（`Primer.BackgroundPrimary`）+ 1dp `Primer.Border` 描边 + 阴影 + 16dp 圆角**
  （气泡弹层的做法见 `ui/navigation/PageTransitions.kt` 的 `bubbleEnter` 与个人页 More 气泡）；
- **白底容器里不要再放白底元素** —— 需要垫一层时用 `Gray150`（如筛选手板里的输入框、+/− 圆点），
  否则容器改白之后它们会直接「消失」；
- 新组件不要依赖 M3 默认容器色；确实需要特殊底色时才在调用处显式传 `containerColor`。

单行状态位（设置项右侧的值 / 页面副标题 / 编辑器底栏 / toast）**只放短名**：
`CommitMode` 这类有多档状态的枚举要区分 `label`（短名，给状态位）与 `title`（完整说明，给整行卡片）。
行高固定的行（如 `SettingsItem` 的 48dp）里换行会被直接裁掉，所以名称与值都必须单行省略，
且**由值负责省略、不许挤压名称**。

## 🔧 构建

### 环境要求
- JDK 17+
- Android SDK（compileSdk 35）
- Rust 工具链（编译 `core/`）

### ARM64 环境一键准备（判定 → 准备 → 持久化，脚本解耦）
```bash
chmod +x ./setup_android_env.sh
./setup_android_env.sh   # 委托 tools/env/ 下的三脚本，无镜像测速，预存资产 + 固定镜像
```

环境脚本（`tools/env/`）：
- `env-detect.sh`：纯判定（java/sdk/gradle/rust/aapt2），输出 JSON，零副作用
- `env-prepare.sh`：缺失才下载（预存资产优先 + 固定镜像）
- `env-prepare-arm64-patches.sh`：ARM64(proot) 补充准备（lld / cargo-ndk / 自包含 aarch64 aapt2 并绑定到全局 `~/.gradle/gradle.properties` / env.rc 的 cargo PATH）；**非 aarch64 自动跳过**
- `env-persist.sh`：仅在 `~/.bashrc` 追加一行 `source tools/env/env.rc`

编译脚本（`tools/build/`）：
- `build-core.sh`：编译 Rust `.so`（本地 clang / CI cargo-ndk 自动切换）
- `assemble.sh`：`gradlew assemble`，注入关键版本参数
- `warmup-aapt2.sh`：ARM64 AAPT2 替换后预热（可选，首次编译前）

### Android APK
```bash
./gradlew assembleDebug     # Debug（版本号尾部附加 -Beta）
./gradlew assembleRelease   # Release（需配置签名密钥）
```

### 单元测试
```bash
./gradlew :app:testDebugUnitTest :translate:testDebugUnitTest   # 业务层 + 沉浸式翻译模块
cd core && cargo test                                            # Rust 核心
```
> 容器/proot 环境先 `source tools/env/env.rc`（其中导出了 `LANG/LC_ALL=C.UTF-8`：
> JVM 的文件名编码取自 locale，非 UTF-8 时中文命名的测试方法会编译失败，见 `BUILD-NOTES.md`）。

### Rust 核心
```bash
cd core
cargo build --release       # 生成 libbranchbase_core.so
```

## 🔖 版本号规范

采用「工程版本号 + 标准版本号」双轨制（原设计文档未入库，规则以本节为准）：

```
标准版本号 = 工程版本号-年月日-时分-七位哈希
示例：1.0.1-20260901-1342-a1b2c3d
```

- 工程版本号：`version.properties` 手动维护（semver）
- 标准版本号：构建时注入 `BuildConfig`（时间 + `git rev-parse --short=7`）

## 🤖 CI/CD（三步骤流水线）

发行版编译（beta/release）统一为「环境准备 → 执行编译 → 发布」三步骤：

| 步骤 | job | 职责 |
|------|-----|------|
| ① 环境准备/检索资产/判定命中 | `prepare` | 复用 `.github/actions/setup-branchbase-env`（Java/Rust/NDK/cargo-ndk@4.1.2/Gradle 缓存） |
| ② 执行编译/注入版本参数 | `build` | `build-core.sh` 编 `.so` + `assemble.sh` 打包，注入版本参数并上传产物 |
| ③ 收集/类型判定发布/代码整合 | `publish` | 判定 beta/release 类型，发布 Release + 整合代码变更 |

- `build-beta.yml`：push 到 `main` 触发，三步骤构建 Debug APK 发布为 pre-release，`.so` 提交到 `beta` 分支
- `build-release.yml`：`workflow_dispatch` 手动触发，三步骤构建 Release APK 发布为正式版
- `build-core.yml`：Rust core 编译检查 + 单元测试（push/PR 触发，非发行编译）

## 📄 许可证

（待补充）