# Branchbase

> GitHub 第三方 Android 客户端，对标官方 GitHub App 的信息架构与交互体验。

Branchbase 是一个基于 **Jetpack Compose** + **Rust** 的现代化 GitHub 客户端。业务核心（OAuth 认证、GitHub API、Git 操作、HTML 解析）由 Rust 实现，通过 JNI 桥接暴露给 Android Compose 层调用。

- **仓库地址**：<https://github.com/SunsetRNE/Branchbase-Android>
- **平台范围**：**只做 Android 手机 / 平板**（`minSdk 24`），桌面端、Web 端、iOS 均不在计划内。
- **开发交流**：QQ 群「Branchbase开发交流」·群号 **790735040** ·
  [点击链接加入群聊](https://qun.qq.com/universal-share/share?ac=1&authKey=pvJE5SaHMaTeBU%2BNqt2VJBfeAGY0eLg%2BGHUTz2TDjsxe0aeS3L32m6Cg0NEnMCmg&busi_data=eyJncm91cENvZGUiOiI3OTA3MzUwNDAiLCJ0b2tlbiI6ImN4bGZ6WnBiUHVRZ0JOWFlmTVloV1R6S1o3NnIyV212RExzeTdtdUs4TVdqamVaNjR0V2xDRGJvNkI1N1RvV3ciLCJ1aW4iOiIxNTM5MDA3NDYwIn0%3D&data=U0Umjl8BD3SwVhcezyilAhDNcA1MUL3HSJAq3dI4hCfZywecQmAbK4yqvgp-3FkggT-Sq4hqJivTho3p4TrhEQ&svctype=4&tempid=h5_group_info)
  （群链接里的 `authKey` 会过期，过期后按**群号搜索**加入即可）

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
│   └── src/main/assets/translate/                #   页面脚本（01-core ~ 03-boot）与译文 CSS
├── downloader/          # 内建下载独立模块（引擎/前台服务/通知进度/通知权限/安装与打开）
│   ├── src/main/java/com/branchbase/downloader/  #   引擎 + 服务 + 通知 + 权限 + 系统动作
│   └── src/main/AndroidManifest.xml              #   权限 / 前台服务 / FileProvider 都随模块合并
├── imageviewer/         # 图片查看器独立模块（正文页点图放大：缩放/平移/下拉关闭）
├── joblogs/             # 作业日志独立模块（取数单飞合并/分组切段/缓存；来源与缓存由 :app 注入）
├── docs/                # 设计文档（入库）：README 收纳规则 + specs/ 重点文档（见 /docs/README.md）
├── tools/               # 环境与构建脚本（tools/env、tools/build）
├── .github/workflows/   # CI/CD（Beta / Release）
├── version.properties   # 工程版本号配置（手动维护；逐版变更说明见 docs/specs/VERSION-NOTES.md）
└── setup_android_env.sh # ARM64 AAPT2 替换脚本
```

> **文档分两类，寿命不一样**：
> `docs/`（**设计文档：结论类，入库**）—— 收纳规则、走哪、什么该存什么不存见
> [`docs/README.md`](docs/README.md)；重点是 `docs/specs/`。
> `design/`（**原型草图：HTML/CSS/JS，永久不入库**）在 `.gitignore` 里，本地可见、DSH 侧边栏
> 「原型预览」能扫到；但草图的『说明与落实方案』会抽一份进 `docs/specs/prototypes/`。
> `re-workspace/`（逆向资源）、`tools/aapt2/`（官方二进制）、`.kotlin/` 等同样不入库。
>
> 由此，**代码注释可以放心引用 `docs/specs/` 下的文档**（含 `§` 章节号，改文档要回头改注释）；
> 引用 `design/` 下的草图文件则要意识到「那份文件不在版本库里」。

## 📚 文档导航

结论类文档全部入库在 `docs/`（原型草稿留在 `/design/`，不入库）——**该往哪放、什么该入库、
什么永远不入库，先读 [`docs/README.md`](docs/README.md)**。本文件只留「门面 + 功能概览 + 指向」，
各专题的**完整设计记录**在下面这些文档里（正文已迁出，这里只留结论摘要）：

| 文档 | 一句话 |
|---|---|
| [`docs/specs/modules-design.md`](docs/specs/modules-design.md) | **功能模块族**：沉浸式翻译 / 内建下载 / 图片查看器 / 代码编辑器 / 作业日志与运行轮询 |
| [`docs/specs/ui-design.md`](docs/specs/ui-design.md) | **界面规格**：配色与弹层（单一真源）/ 深色主题 / 动效（页面级 + 元素级） |
| [`docs/specs/frame-perf-design.md`](docs/specs/frame-perf-design.md) | **帧率基线（页面重建）**：取数只走 `FrameWatch`（dumpsys 被守护拦）/ 口径与四类归因 / 基线与验收清单 |
| [`docs/specs/screens-design.md`](docs/specs/screens-design.md) | **页面重绘**：运行详情卡片流 / 发布（三档性质 + 三个页面）/ 消息卡片流与多选 |
| [`docs/specs/settings-design.md`](docs/specs/settings-design.md) | **设置页设计规范**：两级 IA / 6 种行型 / 控件选型 / 用语表 / 危险操作 |
| [`docs/specs/NAVIGATION-NOTES.md`](docs/specs/NAVIGATION-NOTES.md) | 返回键与导航的两条硬规则（顶层双击退出、页面内逐层消费） |
| [`docs/specs/html-parser-design.md`](docs/specs/html-parser-design.md) | 正文链接如何归一化成跳转目标：§3 规则表、§6 已知边界 |
| [`docs/specs/reachability-design.md`](docs/specs/reachability-design.md) | **远端可达性判定**：离线 / 直连 / VPN 三档、四种跃迁才重探、跃迁时三件事（丢连接池 / 清去重窗口 / 重探账号） |
| [`docs/specs/BUILD-NOTES.md`](docs/specs/BUILD-NOTES.md) | AGP 9.0 的 `VariantOutputImpl` 坑 + 版本号体系 + JNI 签名与 locale |
| [`docs/specs/VERSION-NOTES.md`](docs/specs/VERSION-NOTES.md) | **版本变更记录**：逐版改了什么、为什么这么改 + `versionCode` 流水 + 新增一版的写法约定 |
| [`docs/specs/prototypes/`](docs/specs/prototypes/) | 原型**说明文档**的入库副本（原型本体在 `/design/`，不入库） |

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
- **仓库浏览**：列表 / 详情 / README 渲染 / 语言统计 / 贡献者 / 分支同步（服务端合并，入口在 `⋮` 气泡）
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
- **提交模式**：单文件 / 多文件 / 本地仓库（对齐 GitHub 官方行为）。
  代码页 / 文件页的 **Git 悬浮球绑定「本地仓库（Git）」模式** —— 另外两种模式（单文件 / 多文件）
  直接调远端 API 提交、本地没有工作树，球不显示（判定收在 `showGitBubble`，三种模式都有单测）；
  本地仓库模式下球里给的是就地切换提交模式、分支管理 / 对比、本地分支同步与刷新，
  徽标是本地工作树的改动数 / 领先落后
- **本地仓库**：libgit2 浅 clone / pull（fast-forward）/ commit / push
- **沉浸式翻译**：正文页原文 + 译文对照（独立 `:translate` 模块，见 [`docs/specs/modules-design.md`](docs/specs/modules-design.md)）
- **附件下载（内建下载器）**：发布页附件走应用内下载 —— 前台服务保活、通知栏进度条、
  断点续传，完成后可直接安装 APK / 用其他应用打开 / 分享（独立 `:downloader` 模块，见 [`docs/specs/modules-design.md`](docs/specs/modules-design.md)）
- **图片查看器**：正文页点图放大 —— 双指缩放 / 双击 / 拖动 / 下拉关闭
  （独立 `:imageviewer` 模块，见 [`docs/specs/modules-design.md`](docs/specs/modules-design.md)）
- **返回键（顶层双击退出，页面内逐层消费）**：已登录时主界面顶层按返回**不离开主界面**，而是提示
  「再按一次返回退出应用」，2 秒内再按一次才退出（保留防误触，又不会把已登录用户丢回登录页）；
  未登录的欢迎页按返回直接退出；子页 / 详情 / 多选态 / 页面内的决策页与编辑态**逐层关闭自己**，
  且与页面左上角返回箭头**走同一条路径**（如「设置 → 关于」按返回回设置、而不是跳回个人主页；
  本地仓库的决策页回列表；任务详情回列表；文件页决策页回编辑态；Issue 评论编辑态 = 取消编辑）
  —— 完整链路、两条硬规则与踩过的坑见 [`docs/specs/NAVIGATION-NOTES.md`](docs/specs/NAVIGATION-NOTES.md)
- **关于页**：紧凑单屏版（图标 52dp 与名称同行 + 右侧校验结论胶囊 + 两张信息卡），
  版本号标准化展示（工程版本 / 标准版本 / 构建时间 / 七位哈希 / Git 配置包 / 代码编辑器），
  构建校验（发布版本 / 签名校验 / 远程校验 + 一行结论说明，五态文案由 `verifyCopy` 纯函数产出），
  末尾是项目主页与开发交流 QQ 群入口

## 🧩 功能模块（`:translate` / `:downloader` / `:imageviewer` / `:editor` / `:joblogs`）

自成一体、可整体换掉的能力都收进独立模块，依赖方向一律单向（`:app → :<module>`），
模块内不引用任何 App 类型 —— App 只注入「只有它才知道的东西」（凭据、小图标、缓存与取数实现）。
**装配点、关键设计决策与已知边界**见 [`docs/specs/modules-design.md`](docs/specs/modules-design.md)：

- **沉浸式翻译（`:translate`）**：WebView 正文页「原文 + 译文」对照。悬浮球与工具面板是 `:app` 的
  原生 Compose 覆盖层，页面脚本只做 DOM，判定 / 缓存 / 调度 / 熔断全在 Kotlin，后端由 App 注入
  （默认 MyMemory，可切自带 Key 的 DeepSeek）。**已知边界**：只覆盖 WebView 渲染的正文页，
  Compose 原生渲染的评论区不在范围内。
- **内建下载（`:downloader`）**：发布页附件走应用内下载 —— 前台服务保活、通知栏进度、断点续传，
  完成后可直接安装 APK / 交给其他应用打开 / 分享；同一条通知上叠三层（标准进度 / Hook extras / 厂商「上岛」）。
  **已知边界**：任务表在进程内存里，进程被杀即重来。
- **图片查看器（`:imageviewer`）**：正文页点图放大（缩放 / 拖动 / 下拉关闭），两条入口分别覆盖
  「图片没被链接包住」与「被 `<a>` 包住」两种标记；取图凭据只给 GitHub 自有域名带。
- **代码编辑器（`:editor`）**：Sora Editor 的独立封装，只接在文件页**编辑态**；外观契约是
  等宽 + 行号 + 无边框 + 与调用方同一块底色，配色显式覆盖（编辑态与只读预览共用一份）。
- **作业日志（`:joblogs`）**：取数单飞合并 + 分组切段 + 窄接口缓存；只认 `JobLogSource` /
  `JobLogCache` 两样注入，接线集中在 `ui/repository/JobLogWiring.kt` 一处。
- **运行中的工作流**：轮询的是「状态」不是日志 —— GitHub 在 job 结束前拿不到日志 blob，也没有长轮询，
  所以只持续拉 `GET /actions/runs/{id}/jobs`，策略收在 `RunPollPolicy.kt`（纯逻辑 + 单测）。

## 🎨 界面规格（配色 / 深色主题 / 动效）

跨页面、每个页面都要遵守的三份规格，完整条文与已知取舍见
[`docs/specs/ui-design.md`](docs/specs/ui-design.md)：

- **配色与弹层（单一真源）**：所有颜色来自 `ui/theme/Color.kt` 的 `Primer` 色板，不在调用处写死色值；
  弹层统一「白底 + 1dp `Primer.Border` 描边 + 阴影 + 16dp 圆角」，M3 的容器角色一次性对齐到设计色板
  （改前弹层读的是带紫调的 Material 基线色）。单行状态位只放短名，且**由值负责省略、不许挤压名称**。
- **深色主题**：**色板 + 角色**两层架构 —— `Primer.XXX` 的调用点一行不改就跟着主题切换，
  真正要改的只有「非 Composable 上下文」那 105 处；文字色 ≠ 填充色（WCAG 对比度由 `ThemeContrastTest`
  钉住），常态图标取纯黑 / 纯白，Compose 管不到的（状态栏、WebView CSS、翻译页面脚本）另行跟随。
  启动瞬间的一帧浅色、约 30 处「拿填充色当文字」等**已知取舍**也记在文档里。
- **动效**：三层规格各一处真源 —— 页面级 `ui/navigation/PageTransitions.kt`（层级推进 = **只让一页动**
  （新页滑入 + 淡入、旧页原地淡出）、同级与**首帧重的页** = fade-through 不重叠；重页按台账降级）、
  元素级 `ui/theme/Motion.kt`（原语表：选中态 / 出现消失 / 气泡 / 按下 / 徽标 / 微光）、
  图标形态级 `ui/theme/morph/`（**路径插值形变**：端点逐点恒等、中间帧由顶点对应关系算出，
  结构差异大的配对按台账降级成交叉过渡）。
  真正起作用的四条：**曲线两端速度连续**（起步不弹射、收尾不砸停 —— 抖动就是速度突变）、
  只让一页动、切 Tab 存住原位置、参数与形态按真机基线收敛过两轮（位移 1/4 → 1/10 屏、同级 fade-through）；
  元素级一律不用 spring（唯一的弹簧是形变进度，默认 ζ=1.00 不过冲，为的是**可打断**）。
  术语、六步管线与验收清单见 [`docs/specs/morph-design.md`](docs/specs/morph-design.md)，
  **帧率口径与基线**见 [`docs/specs/frame-perf-design.md`](docs/specs/frame-perf-design.md)。

## 🖥 页面重绘（运行详情 / 发布 / 消息）

三个页面从「信息平铺」重排成「卡片流」的落地规格见
[`docs/specs/screens-design.md`](docs/specs/screens-design.md)；原型**草稿**在 `/design/`（不入库），
同一件事的**说明文档**入库在 [`docs/specs/prototypes/`](docs/specs/prototypes/)：

- **运行详情：卡片流** —— 7 条等权信息行 + 行内展开的任务列表，重排成 `RunHeaderCard` + `JobCard`
  （步骤时间线 + 相对时长条 + `runner`），注解按 check-run 归回任务卡，产出行尾给下载入口，
  日志从「整段塞进一个 `Text`」升级成 `JobLogScreen`。逐条论证见
  [`docs/specs/prototypes/workflow-redesign.md`](docs/specs/prototypes/workflow-redesign.md)。
- **发布（Releases）** —— 先对齐官方三档性质（草稿 / 预发布 / 正式发布，只有正式发布能占 Latest），
  再重绘列表（入口收成标题行右侧的「+」）、编辑页（垂直预算 694dp → 360dp、生成说明不覆盖手写行）、
  详情页（附件组改用分隔线，不再一框一个）。逐项预算见
  [`docs/specs/prototypes/release-redesign.md`](docs/specs/prototypes/release-redesign.md)。
- **消息（通知收件箱）** —— 卡片流固定 32dp「识别槽」、长按 = 单条动作面板（多选是面板里的显式选项）、
  批量失败**只回滚失败的条目**、工作流通知点击落到「这一次」运行。原型见
  [`docs/specs/prototypes/messages-redesign.md`](docs/specs/prototypes/messages-redesign.md)。

## 🔀 分支同步：入口收进 `⋮` 气泡，模式跟着预览结论走

把「A 分支的内容同步到 B 分支」是**服务端操作**（`POST /merges`，或直接移动 ref），不需要本地 clone。

**入口**原本是概览页里一条**占满整行的描边按钮**，紧跟在星标 / 复刻 / 关注下面、压在 README 之上：
信息量为零（一行字加一个「›」），视觉重量却和三个主操作同级；而且**不分仓库**都显示 ——
看别人的仓库、没有写权限也照显示，点到最后一步才失败。现在它收进底部栏 `⋮` 气泡，
与 PR / 提交 / 设置同级，且**只在 `canPush` 时出现**，首屏不再被它占掉一行。
`BubbleEntry` 因此分成 `Page` 与 `Action` 两支 —— 分支同步是盖在 Tab 上的全屏页而不是 `RepoPage`，
硬塞进 `RepoPage` 会让 `when (page)` 多出一条永远渲染不到的支路。

**模式是否可选，由比较预览的结论决定**（`GET /compare/{目标}...{源}`）：

| 预览结论 | 合并 | 仅快进 | 覆盖 |
|---|---|---|---|
| 目标已是最新（`ahead=0`） | ✗ | ✗ | ✗ |
| 目标没有独有提交（`behind=0`） | ✓ | ✓ | ✓ |
| 目标有独有提交（`behind>0`） | ✓ | ✗（服务端必然拒绝） | ✓（红色 + 二次确认） |

「目标已是最新」时主按钮一并置灰、文案换成「目标已是最新，无需同步」——改前只是提示一行小字，
三种模式照样能选、按钮照样能点。主按钮还把动作与后果写出来：`合并 main → beta` /
`快进 main → beta` / `覆盖 beta（丢弃 2 个提交）`，取代原来笼统的「同步 main → beta」。
换了分支对之后，原先选中的模式若已被禁用会**自动退回「合并」**，不会留下「高亮的那一项是灰的、点不动」。

## ⭐ 星标 / 关注 / 复刻：判定规则收在纯函数里

这三个按钮此前**没有任何判定**：三个都无条件跳同一个列表页（星标者 / 复刻 / 关注者），
既没有「已星标」的双向态，也不区分仓库是不是自己的。现在：

| 按钮 | 点击 | 长按 |
|---|---|---|
| 星标 | 收藏 ↔ 取消收藏（`PUT`/`DELETE /user/starred/{o}/{r}`，乐观更新 + 失败回滚） | 星标者列表 |
| 关注 | Watch 控制面板：Participating and @mentions / All Activity / Ignore / Custom + Watch settings | 关注者列表 |
| 复刻 | **自己的仓库** → 复刻列表；**他人仓库** → 网页版复刻流程（账户 / 命名 / 重名校验 / 只复刻默认分支）；被组织禁用 → 置灰并说明原因 | — |

判定全部收在 `RepoRelationRules`（`RepoRelation.kt`，**纯函数 + 22 条单测**），
因为每一处误判都有真实后果：复刻判错会对别人的仓库弹「不能复刻自己的仓库」，
星标判错则是点一下**给别人的仓库取消了收藏**。

### 判定输入有三个来源，精度不同

| 来源 | 拿到什么 | 代价 |
|---|---|---|
| 网页 `react-app.embeddedData` | `viewerHasStarred` / `canFork` / `forkabilityError` / `subscriptionType` / **Custom 的可勾选项** | 一次页面抓取（需网页会话） |
| GraphQL | `viewerHasStarred` / `viewerSubscription` / `forkingAllowed` | 一次查询 |
| 本地 `owner == login` | 「是不是自己的仓库」——复刻按钮形态的关键分支 | **零网络** |

本地判定放在最前面有意为之：它不需要等任何请求，所以**自持仓库的按钮不会先错后改**。
另外仓库信息原先在 `RepositoryScreen` 与概览页各取一次（同一进入动作发两个一模一样的
`GET /repos/{o}/{r}`），按钮上的计数就卡在这轮重复往返上 —— 现在统一由前者取、后者消费。

### Custom 通知：唯一需要网页会话的能力

仓库级「自定义通知」**没有公开 API**：REST 的 `PUT /repos/{o}/{r}/subscription` 只有
`subscribed` / `ignored` 两个布尔，GraphQL 的 `SubscriptionState` 只有三态，都表达不了
「只收 Issues + Releases」。网页版走的是内部端点
`POST /{owner}/{repo}/notifications/subscribe`（`do=custom` + `thread_types[]`）。

而 OAuth token **到不了那里** —— 实测 github.com 的 HTML 与内部端点只认浏览器会话 Cookie，
带 `Authorization: token/bearer` 或 Basic 一律 302 到 `/login`。所以：内嵌 WebView 登录一次
（`GithubWebLoginScreen`），导出 Cookie 存本机（`GithubWebSession`），之后的网页请求由 Rust 侧发，
并照搬网页版的防伪头（`X-Fetch-Nonce` 每次页面加载都变，所以每次写入前先取一次页面）。

其余能力一律走官方 API，不打扰用户：**只有点 Custom 且没有会话时，才会引导登录。**

### 列表：失败不再伪装成「空」

星标者 / 关注者列表原先把 403 / 404 / 限流 / 真没人一律折叠成 `null`，界面统一显示「暂无内容」。
2026-07 起 GitHub 已把 `/stargazers`、`/subscribers` 限制为**管理员与协作者**可见
（非协作者拿 403 或空响应），这个歧义从理论问题变成了常态 —— 现在按状态码分别给话，
并补上 `per_page=100`（此前默认 30 条且没有翻页入口）。

## ⚙️ 设置页：一份规范 + 一次重绘

设置页是「**唯一一个每个功能都要来挂一行的地方**」，所以它天然会变成拼接现场。
测绘下来：全仓 **12 个设置类界面**、分布在 8 个文件、用了 **8 套互不相通的行组件**。
最直接的后果是 `SwitchRow` 是 `TranslateSettingsScreen.kt` 里的 `private fun` ——
别的页抄不到，于是**设置主页一个开关都没有**（7 行全是导航行，唯一「像开关」的主题行还是点一下循环）。

规范正本：[`docs/specs/settings-design.md`](docs/specs/settings-design.md)；
原型与逐条论证：[`docs/specs/prototypes/settings-redesign.md`](docs/specs/prototypes/settings-redesign.md)。

| 议题 | 结论 |
|---|---|
| 层级 | **只有两级**。L2 禁止再挂 L3；二级页返回一律走 `profileBackTarget()` 回设置主页，不许 `onBack = { subPage = null }` 抄近路 |
| 分组顺序 | 按**使用频率 × 改错的代价**排：账户 → 外观 → 通知 → 翻译 → 代码与提交 → 网络 → 关于与诊断（`日志` / `关于` 固定留在最后一组，位置稳定比位置显眼重要） |
| 行的解剖 | 行型是**封闭集合，只有 6 种**（`NavRow` / `SwitchRow` / `ChoiceRow` / `ActionRow` / `DangerRow` / `InfoRow`），**禁止**页面里用裸 `Row` 自制行 —— 这正是 8 套组件的成因 |
| 控件选型 | 2 个互补 → `Switch`；2–3 档枚举 → **就地分段控件**；4–7 档 → 子页单选列表；文本 → 子页输入框 |
| 主题 | **去掉「点一下循环」**：三档枚举被压成一个不可预期的手势，看不到还有哪两档、无法一步直达、误点无法撤销 → 改三档分段控件（`ThemeMode.next()` 保留给登录页的太阳/月亮/自动图标） |
| 输入框 | **不在主页**。Git 代理从主页 `AlertDialog` 搬进 L2 输入页 —— 现状 `proxyFeedback` 渲染在**主页最底部**，对话框一关反馈就失联 |
| 禁用行 | 不能只 `alpha(0.55f)`：**必须**写明原因（且原因不跟着降透明度）+ 给一个「去设置」直达 |
| 危险操作 | 标题 = **动词 + 对象名**；正文写清「会发生什么 / 影响范围 / 能否撤销」；确认按钮写**动词**，禁止「确定」 |
| 用语 | 一个含义只有一种写法：`已开启/未开启`、`未设置`、`跟随系统`、`已授权/未授权`、`检查中…`、`获取失败`、`未检查` |
| 值列 | 永远是**值**负责省略，不许值去挤名称；含账号的代理 URL 只显示 `host:port`，令牌不显示 |
| 持久化 | 枚举存 `name` **不存 `ordinal`**；脏值回落默认值不抛异常；key 常量集中声明 |

重绘后对比度审计（`design/settings-redesign/theme-audit.js`）报了 **6 组**不达标，其中两组的根因是
**一个取值被两种语义共用**；落地时改了三处（规范 §14 有完整账目）：
`#BFC1C9` 当**可交互控件边界**只有 1.80:1（WCAG 1.4.11 要求 3:1）→ 色板拆出
`Primer.BorderControl`（浅 `#8B8E99` 3.27:1 / 深 `#6E7681` 3.77:1）；装饰性描边**复用**原有的
`Border` / `neutralBorder`（没有新造 `borderSoft` 角色）；
`Green500` 当**选中圆点**压在 `SuccessSurface` 上只有 2.79:1 → 圆点换 `SuccessTextStrong`；
危险行文字从填充色 `Red500` 换成 `DangerText`（§7.2 本来就要求如此）。
另有原型令牌表里定的 `#DA3633`（深色下填充式危险按钮的白字 3.35:1 → 4.61:1）—— 那一条**只在原型里**，
App 这轮没有落地：重绘后的设置页没有填充式危险按钮（确认动作是文字按钮），
而现有三处 `Red500` 填充都不在设置树内，属于另一轮的事。

**落地分三步**（每步可独立发布）：① 抽 `ui/settings/SettingsRow.kt` + `SettingsKeys.kt`，页面**观感不变**；
② 改主页（主题分段控件 / 代理进子页 / 禁用行给出路 / 分组卡片）；
③ 加 `SettingsSpecTest.kt`（7 条源码级钉子）。完整清单见规范 §11 / §10.1。

> **诚实标注**：这次重绘的收益在**一致性、可维护性、危险动作的安全性**，**不是**「设置页变短了」。
> 分组卡片把 ≈252dp 的分节标题变成 ≈238dp，只差 6% —— 设置页本来就不挤，
> 它受的是**一致性**约束，和受垂直空间约束的日志页不是一回事。

### 落地结果（第一轮已完成）

| 新增 | 改动 | 删除 |
|---|---|---|
| `ui/settings/SettingsRow.kt`（6 种行型 + 账户卡 + 说明段落 + 状态胶囊 + 分段控件 + 输入表单） | `SettingsScreen`、`NotificationSettingsScreen` 重写 | `SettingsItem`（只被设置页使用，无其他调用点） |
| `ui/settings/SettingsKeys.kt`（键的唯一声明处 + 统一的 `prefs()`） | `TranslateSettingsScreen` 改用公共组件 | 私有的 `SwitchRow` / `SectionTitle` |
| `ui/settings/GitProxy.kt` + `GitProxyScreen.kt`（代理脱敏 + 校验 + 二级输入页） | `ModeOptionRow` 增加 `divider`，修正两处对比度 | `LocalRepoEntry`（只调 alpha 的禁用行） |
| `SettingsSpecTest`(18) + `GitProxyTest`(10) | 主题色板新增 `Primer.BorderControl` 角色 | —— |

**三处刻意偏离原型**（原型为演示行型简化了数据模型，落地时不能照抄）：

1. **「允许通知」不是开关** —— 原型画成 `SwitchRow`，但它是**系统权限**，App 只能申请或跳系统设置。
   实现改成导航行 + 状态胶囊：**一个骗人的开关比没有开关更坏**。
2. **沉浸式翻译页未卡片化** —— 原型把该页列为「不在重画范围」，所以只统一了组件，
   分节标题仍用无卡片版（规范 §4.4「必须统一」的唯一例外，已标为过渡态）。
3. **登出有两个入口** —— 设置主页新增了带二次确认的 `DangerRow`，个人页 `⋮` 气泡那个仍在。
   这是刻意的（设置页放登出是通行做法）；收敛成一个属于产品决策。

**仍未做**：翻译页卡片化、「清空译文缓存」补二次确认（规范 §7.1 把它列为危险动作，
本轮只把这行从自制 `Row` 归位成 `ActionRow`，**行为未变**）、200% 字号真机验证。
完整清单见 [`docs/specs/settings-design.md`](docs/specs/settings-design.md) §14。

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
> JVM 的文件名编码取自 locale，非 UTF-8 时中文命名的测试方法会编译失败，见 [`docs/specs/BUILD-NOTES.md`](docs/specs/BUILD-NOTES.md)）。

### Rust 核心
```bash
cd core
cargo build --release       # 生成 libbranchbase_core.so
```

## 🔖 版本号规范

采用「工程版本号 + 标准版本号」双轨制（完整命名体系见
[`docs/specs/BUILD-NOTES.md`](docs/specs/BUILD-NOTES.md) §二）：

```
标准版本号 = 工程版本号-年月日-时分-七位哈希
示例：1.0.1-20260901-1342-a1b2c3d
```

- 工程版本号：`version.properties` 手动维护（semver）
- 标准版本号：构建时注入 `BuildConfig`（时间 + `git rev-parse --short=7`）
- **逐版变更说明**（每一版改了什么、为什么这么改）：[`docs/specs/VERSION-NOTES.md`](docs/specs/VERSION-NOTES.md)
  —— `version.properties` 只留格式契约 + 3 个写法样板，新增一版先去那份文档加条目

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

## 💬 反馈与交流

- **Bug / 功能建议**：[提 Issue](https://github.com/SunsetRNE/Branchbase-Android/issues)（附上机型、系统版本、复现步骤与截图最有效）
- **开发交流群**：QQ 群「Branchbase开发交流」· 群号 **790735040**
  —— [点击链接加入群聊](https://qun.qq.com/universal-share/share?ac=1&authKey=pvJE5SaHMaTeBU%2BNqt2VJBfeAGY0eLg%2BGHUTz2TDjsxe0aeS3L32m6Cg0NEnMCmg&busi_data=eyJncm91cENvZGUiOiI3OTA3MzUwNDAiLCJ0b2tlbiI6ImN4bGZ6WnBiUHVRZ0JOWFlmTVloV1R6S1o3NnIyV212RExzeTdtdUs4TVdqamVaNjR0V2xDRGJvNkI1N1RvV3ciLCJ1aW4iOiIxNTM5MDA3NDYwIn0%3D&data=U0Umjl8BD3SwVhcezyilAhDNcA1MUL3HSJAq3dI4hCfZywecQmAbK4yqvgp-3FkggT-Sq4hqJivTho3p4TrhEQ&svctype=4&tempid=h5_group_info)
- **平台范围**：只做 Android 手机 / 平板，其他端（桌面 / Web / iOS）不做。

## 📄 许可证

（待补充）
