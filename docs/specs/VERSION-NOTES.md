<!-- 来源：根 `version.properties` 的注释块（2026-09 收敛）。注释正文逐字保留，只做了三件事：
     去掉行首的 `#` 注释前缀与对齐缩进、每版提成 `### <版本号>`、按版本从新到旧重排
     （原文件是追加写，1.0.40–1.0.44 排在 1.0.22 之后；1.0.45 起按新流程直接加在顶部）。 -->
# 版本变更记录（`versionName` / `versionCode` 逐版说明）

`version.properties` 现在只留格式契约 + 写法样板（3 个经典示例）；
**每一版改了什么、为什么这么改**都在这份文档里 —— 26 个 `versionName` 条目（1.0.22 → 1.0.47）
与 21 条 `versionCode` 流水（129 → 149）。

---

## 一、新增一版时怎么做

1. **先在本文档加条目**：在 §二 顶部加 `### <新版本号>` 与正文（写法见下）；
2. **再记版本码**：在 §三 顶部加一行 —— `versionCode` **有多少次提交变更多少次 +1**，一次发布也算一次提交；
3. **最后改 `version.properties`**：只改 `versionName=` / `versionCode=` 两个值。
   那个文件**不再堆变更记录**（只留 3 个写法样板），记录一律进本文档。

### 写法约定（从这 23 条里长出来的）

- 标题一句「改了什么」；正文写**为什么** —— 原先哪里不成立、代价是什么，以及这条结论的边界；
- 一版里有多件事就分 ①②③，别混成一段；
- 能量出数字（帧耗时 / dp / 对比度 / 条数）、点到端点（`GET /actions/runs/{id}/jobs`）的，别只写「优化了」；
- 新增的源码级钉子（`XxxTest`）要写出来 —— 它是这条结论的执法者。

---

## 二、`versionName` 流水（1.0.48 → 1.0.22）

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

## 三、`versionCode` 流水（150 → 129）

`versionCode` 每次提交前递增：**有多少次提交变更多少次版本码**（一次发布也算一次提交）。

> 更早的版本码没有逐条留存，流水从 **129** 开始。

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
