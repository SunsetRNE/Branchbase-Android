<!-- 来源：根 `README.md`（2026-09 拆分）。正文未做删改，仅规整标题（去 emoji 前缀、加章节编号）与空行；
     正文里出现的「见上文 / 见下文」都指向本文档内的对应小节。 -->
# 功能模块设计（模块化实现与已知边界）

Branchbase 把「自成一体、可整体换掉」的能力都收进独立模块：`:translate` / `:downloader` /
`:imageviewer` / `:editor` / `:joblogs`。依赖方向一律单向（`:app → :<module>`），
模块内不引用任何 App 类型 —— App 只注入「只有它才知道的东西」（凭据、小图标、缓存实现、取数实现）。

每个模块的装配点、关键设计决策与**已知边界**见下。改某个模块之前，先读它那一节的「已知边界」。

---

## 1. 沉浸式翻译（`:translate`）

在**正文页**（自述文件 README、Issue / PR 主帖、发布说明 —— 即 WebView 渲染的那些页面）把每个段落
翻成目标语言、插在原文下方，形成「原文 + 译文」对照。

**开启入口只有一个**：设置 → 沉浸式翻译 →「自动翻译正文」（总开关），开了之后正文页会自动开始翻译，
并在屏幕右下角（底部导航条之上）出现一枚**可移动悬浮球** —— 这是「翻译模式」下的唯一控件。
**翻译关掉（面板里的「本页翻译」开关）球立刻收起**，屏幕上不留任何东西。
整页可见文本 ≤ 5000 字符时一次翻完，更长则按视口滚动逐段翻译。

设计对齐网页版[沉浸式翻译](https://github.com/immersive-translate/immersive-translate)
（可读源码的开源旧版：[old-immersive-translate](https://github.com/immersive-translate/old-immersive-translate)）：
沿用「独立译文容器 + 视口优先 + 持久缓存 + 请求限流 + 占位符保护」的思路，落成 Android 侧的分层实现。

### 悬浮球与翻译工具菜单（原生 Compose 覆盖层）

| 交互 | 行为 |
|------|------|
| 位置 | 窗口右下角、底部导航条之上；**面板展开时用满屏幕高度**（内容超出才在面板内部滚动） |
| 出现条件 | 只在**翻译模式**下出现：关掉本页翻译（面板里的开关）球立刻收起；重新开启走设置里的「自动翻译正文」 |
| 单击悬浮球 | 悬浮球让位，展开工具菜单（球与面板**不会同时出现**） |
| × / 点面板外任意位置 | 收起面板、变回悬浮球；覆盖全屏的透明遮罩同时挡住正文，不会顺手点开链接 |
| 拖动悬浮球 | 跟着手指走，钳制在窗口内；偏移记在会话里（离开正文页再回来仍是原位） |
| 菜单 → 数据 | 已译 / 候选段数 + 进度条、候选字符数、翻译服务（MyMemory / DeepSeek）、目标语言与样式、实时状态 |
| 菜单 → 快捷设置 | **本页翻译开关（悬浮球的存在与否由它决定）**、显示方式（对照 / 仅译文）、译文样式（卡片 / 下划线 / 淡灰）、目标语言（中 / 英）、自动翻译正文、本地缓存、保护代码与链接 |
| 菜单 → 操作 | 重试（失败时出现）、翻译当前视口、翻译全文、清空本页译文、重置悬浮球位置 |

**界面为什么在原生层**（`app/src/main/java/com/branchbase/ui/translate/TranslateBubble.kt`）：正文 WebView 的高度等于整篇内容高度
（滚动由外层原生列表负责），页面里的 `position: fixed` 钉的是**整篇文章**的右下角，绝对定位又会被
WebView 的边界裁掉 —— 结果是「正文比屏幕短时，面板永远长不过正文，只能一直往下滑」。
搬到原生 Compose 层后，位置锚定**窗口**、面板高度由窗口决定，正文长短与滚动都不再影响它；
代价是页面要把状态推上来（`BBTranslate.report` → `TranslatePageSnapshot`），并接受原生下发的命令
（`window.__bbIT.command` → `TranslatePageCommands`）。

**快捷设置是「真设置」**：面板本身就是原生界面，改动直接落 SharedPreferences（不再需要
「页面 → 设置」的白名单写入口），因此与「设置 → 沉浸式翻译」永远是同一份配置。
页面脚本因此**没有任何写设置的通道**，凭据只走 JNI（有单测钉住）。

**总开关只有一个**（设置里的「自动翻译正文」）：

- 进入正文页时是否自动开始翻译，**只看这一个开关**；
- 页内开关（面板里的「本页翻译」）只作用于当前页面，**不写 localStorage** ——
  一旦持久化，「设置里关掉」之后上次留下的标记会把翻译重新拉起来，
  表现为「设置里关了，仓库页的悬浮球还在」（用户反馈修掉，`TranslateBootScriptTest` 钉住）；
- 面板里的总开关改动会**立刻**下发到当前页（开 → 开始翻、关 → 本页翻译与悬浮球一起收起）。

### 译文怎么「拼」进正文（结构兼容性）

译文插入按宿主结构分两种，**不能一刀切**：

| 宿主 | 插法 | 为什么 |
|------|------|--------|
| 段落 / 标题 / 引用块 / 定义列表 | 插成原文的**兄弟节点** | 原文一个字不动，删掉容器即完全还原（幂等） |
| **列表项 `<li>`**、**表格单元格 `<td>/<th>`** | 插成元素的**子节点** | `<ul>/<ol>` 里只允许 `<li>`、`<tr>` 里只允许 `<td>/<th>`；插兄弟节点是**非法 HTML**，浏览器按匿名内容处理 → 列表缩进/编号乱、表格被撑开列错位 |

内部插入时原文会被包进 `<span class="bb-tr-src" data-bb-wrap>`：这样「仅译文」模式仍然只藏原文
（直接藏整个 `<td>` 会让表格塌一列），「清空本页译文」再把包裹层拆掉还原。表格表头 `<th>` 也在
候选里（表头是正文）。译文块的排版属性（字重 / 斜体 / 对齐 / 行高）全部显式声明、不继承宿主
——落在 `<th>`（粗体）或单元格（可能居中）里不会「换个结构就变样」。下划线样式用
`text-decoration`（逐行下划线）而不是 `border-bottom`（整块下面一条线，换行后像分隔线）。

### 模块划分（`:translate`）

依赖方向单向：`:app → :translate`，模块内不引用任何 App 类型；翻译**后端由 App 注入**
（`RustTranslateEngine` → `core/src/translate/`，默认 MyMemory 匿名接口）。

| 文件 | 职责 |
|------|------|
| `TranslateLanguages.kt` | 语言模型（中英两向）+ 页面判定参数 `PageRules`（含注入 JS 的 JSON） |
| `TranslateProvider.kt` | 可选后端：MyMemory（免费）/ DeepSeek（自带 API Key，OpenAI 兼容） |
| `TranslateDecision.kt` | **判定引擎**：一致即跳过 / 外语为主整段翻 / 目标文字为主只翻片段（纯函数，可单测） |
| `TranslateTextPolicy.kt` | 文本原语（归一化 / 字符分类）+ `needsTranslation` 入口（判定引擎的布尔形态） |
| `TextSegmenter.kt` | 长文本分片：段落 → 句末 → 空格 → 硬切（后端 500 字符硬上限） |
| `PlaceholderGuard.kt` | 占位符保护：URL / `@提及` / `#编号` / 邮箱 / 模板变量 / 提交 SHA |
| `TranslateCache.kt` | 进程内 LRU + 磁盘追加日志缓存（跨进程复用） |
| `TranslateEngine.kt` | 后端接口 + 失败分类（`QUOTA` / `NETWORK` / `UNSUPPORTED` / `UNKNOWN`） |
| `TranslateScheduler.kt` | 全局串行闸门 + 指数退避重试 + 两级熔断 |
| `Translator.kt` | 门面：判定 → 缓存 → 保护 → 分片 → 调度 → 还原 → 回写缓存（含译后一致校验与判定缓存） |
| `TranslateConfig.kt` | 用户设置与读写（自动翻译 / 目标语言 / 显示方式 / 样式 / 本地缓存 / 保护） |
| `TranslatePageProtocol.kt` | 页面 ↔ 原生的协议：状态快照 `TranslatePageSnapshot` + 命令 `TranslatePageCommands` |
| `TranslatePage.kt` | 页面资产装载：`assets/translate/*` 的 CSS 与三个脚本按序拼接 |
| `TranslateBridge.kt` | WebView JS 桥（`request` / `retry` / `state` / `report`，异步回调 + 状态上报） |
| `TranslateRuntime.kt` | 装配点：`Application.onCreate` 里 `install(this, RustTranslateEngine())` |

### 判定引擎：要不要翻 → 翻哪一部分 → 怎么展示（1.0.89）

`TranslateDecision.kt` 是**唯一**的判定实现，`TranslateTextPolicy.needsTranslation` 只是它的布尔入口。
判定按顺序走三条规则，前一条成立就不再往下：

| # | 条件 | 结论 |
|---|------|------|
| ① | 段内没有需要翻的内容（没有外语字母，或只有 `CI` / `a` / `3D` 这种零碎外语） | **一致 → 跳过**，页面上什么都不插 |
| ② | 外语为主（目标文字占比 ≤ `hanRatioMax`，含整段外语） | **整段翻**（一直以来的行为；英文段落里夹一句中文引文也读得通） |
| ③ | 目标文字为主、段内确有需要翻的片段 | **匹配性翻译**：只把片段送去翻译，按「片段 → 译文」配对展示 |

**「一致」有两层，两层都要判**：

- **事前**：段内没有外语内容 —— 它本来就是目标文字（`这是一段中文说明，里面有 CI 字样。`）；
- **事后**：译文与原文归一化后逐字相同（大小写 / 全角半角 / 零宽字符 / 首尾标点都不算差异）——
  服务端把原文原样还回来（没翻、专有名词、from/to 写反）时，插一张与原文逐字相同的卡片
  只会让用户以为「翻译坏了」。这类段落会被记进**判定缓存**，**下次连请求都不发**；
  少了这一步，每次重开页面都要再问一遍，问回来还是同一份原文（`Translator` 的不变式 3）。

**判定在「占位符保护视角」下做**，与设置里的「保护代码与链接」开关无关：
URL / 行内代码 / `@提及` / 提交 SHA 在判定眼里是中性字符。少了这一层，
`详见 https://example.com/docs 的说明` 会被判成「有需要翻的内容」，片段是 `https`。
那个开关只决定**送出去的时候**是否把标记换成占位符。

**匹配片段怎么切**：连续外语字母，吸收**夹在中间**的空格 / 标点 / 数字 ——
`npm run dev` 是一个片段而不是三个词（逐词送翻会得到三份互相不知道上下文的译文，
拼起来是「npm 运行 开发」）。按出现顺序去重；片段与原文一致时（产品名、专有名词）不成对
（`Docker → Docker` 没有信息量）；片段数超过 `maxMatchParts`（默认 6）说明这段其实以外语为主，
回退成整段翻。每个片段各自进缓存 —— 同一片段全站只翻一次。

**使用规则**（设置 → 沉浸式翻译 → 中英混排，落盘键 `translate.matchPolicy`）：
只翻外语片段（默认）/ 整段一起翻（旧行为，用于对照）/ 中英混排不翻（最省额度）。
规则真源仍是 Kotlin 的 `PageRules`，随设置注入 `window.__bbTranslate.rules`；
页面脚本（`01-core.js` 的 `analyze()` / `needsTranslation()`）用同一套阈值做**粗筛**
（省一次往返、省一次额度），权威判定在原生侧 —— 脚本漂移时最坏是多送一段。

**展示**：`data-bb-mode="match"` 的译文容器里是一组「原文片段 → 译文片段」，
箭头与间隔号由 CSS 画（DOM 里只有两个 span，清空 / 重填都不必管分隔符）；
对照模式给配对、仅译文模式由 CSS 藏掉原文片段只留译文；换目标语言 / 重扫是**覆盖**而非追加。

页面脚本按职责拆分（`translate/src/main/assets/translate/`）：
`01-core.js`（配置 / 状态机 / 批量队列 / 状态上报）、
`02-dom.js`（段落收集 / 跳过 / 插入 / 视口观察 / 候选统计与清空）、
`03-boot.js`（启动与命令入口：开关、显示方式/样式/目标语言、滚动兜底）、
`translate.css`（译文样式与显示模式）。

界面侧：设置页（设置 → 沉浸式翻译）负责完整设置与**总开关**；悬浮球与工具面板是 `:app` 的原生
Compose 覆盖层（`ui/translate/TranslateBubble.kt`，在 `MainActivity` 根部渲染），正文页通过
`LocalTranslateBubbleHost` 绑定会话、上报状态并接收命令；翻译本身的判定/缓存/调度仍全在 Kotlin。
控件只在**正文页绑着且翻译开着**时出现 —— 关掉翻译就整层收起。

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
2. **判定规则只有一个真源** —— 阈值、跳过正则与混排规则定义在 Kotlin 的 `PageRules`，
   随设置注入 `window.__bbTranslate.rules`，JS 只使用不定义；原生侧仍做权威判定
   （`TranslateDecisionEngine`），防止脚本版本漂移。页面脚本里那份镜像宁可宽松
   （多送一段由原生侧判掉），也不要漏送原生侧想翻的段落。
3. **占位符保护** —— 待译文本里的 URL / `@提及` / `#编号` / 提交 SHA 等先换成 `⟦n⟧`，
   译后还原；任一占位符丢失即判失败，并**不加保护地重翻一次**（对齐网页版「还原失败就重译该段」）。
4. **三种熔断** —— 额度用尽（余额/限流）与 API Key 无效都立刻停发请求（继续发只是浪费额度、
   或被 401 刷屏），二者状态分开，页面分别提示「稍后再试」与「去设置检查 Key」；
   连续 3 次其它失败也暂停。状态回推页面，按钮变成「可重试」，而不是「点了没反应」。
5. **缓存两层** —— 内存 LRU（512 条）+ 磁盘追加日志（2000 条，`filesDir/translate/cache.tsv`），
   **键 = (源语言, 目标语言, 变体, 归一化原文)**；磁盘坏行跳过而不是让缓存整体失效。
   **变体是「谁翻的 + 怎么翻的」**：后端 / 模型 / 接入地址 / 占位符保护开关（**不含 API Key** ——
   同一后端同一模型换 Key 不改变译文）。键少了变体维度时不会报错，只会让用户觉得
   「换成 DeepSeek 没什么变化」「关掉保护对比一下结果一样」——旧后端的译文被继续命中，
   新后端一次都不会被调用（1.0.58 修的就是这条）。各字段用**长度前缀**拼接
   （`3:en|2:zh|8:mymemory||5:hello|`），因为原文里出现分隔符时裸拼会撞键，而撞键的后果是
   **返回另一段的译文**（不是慢，是错）。命中率（`TranslateStats.hitRate`）与每批一行汇总
   （`Translator` 的 `log` 回调 → :app 接到日志）是判断「缓存有没有在干活」的唯一指标。
6. **短页一次翻完、长页视口优先** —— 候选文本 ≤ 5000 字符直接全翻；超过则按 `IntersectionObserver`
   滚动逐段，避免一次几百个请求烧光额度、卡住首屏。
7. **译文样式只改一个根属性** —— `body[data-bb-style]`（卡片 / 下划线 / 淡灰），切换不重翻、不重建 WebView。
8. **JS 只做 DOM** —— 网络、缓存、串行与重试全在 Kotlin，桥上一次只传一批（≤3 段）文本，
   避免把整页内容或配置在 JS ↔ Native 之间来回搬。[#3262](https://github.com/immersive-translate/immersive-translate/issues/3262)
   的 OOM 正是「大对象过桥」造成的。
9. **悬浮控件必须是原生 Compose** —— 试过两版页面内实现都不成立：`position: fixed` 钉的是
   **整篇文章**的右下角（长 README 里球会跑到文末），改成「原生推可见带 + 页面绝对定位」之后，
   可见带只能取「正文框 ∩ 屏幕」，于是**正文比屏幕短时面板永远长不过正文**、只能一直往下滑。
   把球与面板搬到窗口层（`MainActivity` 根部的覆盖层）是唯一能让面板用满屏幕高度的做法。
10. **状态单向流** —— 页面只上报状态快照（`report`）、只接受命令（`command`），**没有写设置的通道**；
    设置由原生的面板/设置页直接落盘。少一条信任通道，凭据更安全，也不会出现双真源。
11. **球与面板互斥** —— 展开面板时球隐藏、收起时球回来（「点球 = 变成菜单」）；
    点面板之外的那一下被全屏遮罩吃掉，避免收起面板的同时误开正文里的链接。
12. **拼接按结构分两种，不用一套规则硬套** —— 普通块插兄弟节点（幂等、可完全还原），
    列表项与表格单元格插子节点（兄弟节点在那儿是非法 HTML，会破坏列表/表格排版，见上文）；
    统计与判定用 `sourceText()` 排除已插入的译文，否则候选数会虚高、同一段还会被重复翻译。
13. **开关搬进面板、页面只在翻译模式下有控件** —— 悬浮球不再是「开关」，只是面板入口；
    本页翻译的开关在面板内部，关掉即球与面板一起收起。开启入口收进设置页的总开关，
    代价是「关掉后不能在页面上重新打开」（这是刻意的：不翻译时屏幕不留控件），
    设置页的说明里写明了这条路径。

### 已知边界

- 生效范围是 **WebView 渲染的正文页**；评论区由 Compose 原生渲染（`MarkdownBody`），暂不在范围内；
- 后端支持 MyMemory 与「任意 OpenAI 兼容服务」（DeepSeek 官方 / 中转 / 自建）两种态；
  再加一家（DeepL 等）只需在 `core/src/translate/` 加一个 provider + 设置页加一项；
- 判定引擎的字符分类**只认两类文字**：汉字与「拉丁 / 希腊 / 西里尔 / 假名 / 谚文」
  （`isLatinLetter` 的区间是写死的，为的是和页面脚本的镜像逐区间对齐，见 `TranslateTextPolicy.kt`）。
  其余文字（阿拉伯文、天城文、emoji）一律算**中性**：既不当作目标文字，也不当作外语片段；
- 匹配性翻译的片段是**逐段独立**翻译的：跨片段的一致性靠缓存（同一片段全站只翻一次），
  但拿不到整段的上下文 —— 需要上下文时把「中英混排」切成「整段一起翻」；
- 术语库 / 自定义提示词 / 悬停看原文等网页版高级能力未实现
  （`TranslateEngine` 的 options JSON 与提示词构建是预留的落点，
  `translate/build.gradle.kts` 里写了移除步骤）。

参考：[主仓库](https://github.com/immersive-translate/immersive-translate) ·
[开源旧版源码](https://github.com/immersive-translate/old-immersive-translate) ·
[官网文档](https://immersivetranslate.com/docs/usage/)

---

## 2. 内建下载（`:downloader`）

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
| `DownloadNotifications.kt` | 通知渠道 + 一条常驻进度通知 + 每条任务的完成/失败通知（进度通知上叠三层：标准进度 / Hook 载荷 / 厂商「上岛」） |
| `DownloadNotificationState.kt` | 一帧通知态快照（百分比 / 不确定态 / 字节文案 / 状态键，纯数据可单测）—— 通知栏与各厂商岛共用同一份语义 |
| `DownloadNotificationHook.kt` | 给第三方 Hook 读的稳定 extras（`com.branchbase.download.*`，键名即对外契约） |
| `DownloadIslandExtension.kt` | 「上岛」扩展点接口 + 注册表（派发全程 `runCatching`，厂商 SDK 崩了也不能影响下载） |
| `VendorIslandExtensions.kt` | 三家内置实现：小米超级岛（extras）、谷歌实时更新（反射调 androidx.core 1.17+ API）、OPPO（基线形态 + 官方 SDK 注入点） |
| `NotificationPermission.kt` | 系统通知权限与总开关状态、申请与跳设置（**通知板块也复用它**） |
| `DownloadPaths.kt` | 落盘目录、文件名净化、`.part` 原子改名、sha256 校验（纯函数可单测） |
| `DownloadActions.kt` | 安装 APK / 打开 / 分享 / 在文件管理器里显示（FileProvider + 逐级兜底） |
| `DownloaderRuntime.kt` | 装配点与门面：`install` / `enqueue` / `cancel` / `retry` / `tasks` / `registerIslandExtension` |

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
7. **进度通知本身是可扩展的** —— 同一条通知上按顺序叠三层：标准进度（`setProgress` + 文案）、
   Hook 载荷（`com.branchbase.download.*` 稳定 extras，给第三方模块读）、厂商「上岛」。
   厂商能力只作用于**这一条**通知，不另发一条（否则通知栏会出现两条重复的下载）。
   装配见 `DownloaderConfig.islandExtensions` / `DownloaderRuntime.registerIslandExtension`。

### 灵动岛 / 实时活动（`上岛`）

下载进度会顺带尝试投到厂商的「灵动岛 / 实时活动」，三家都**可能因为没被加上白名单而不显示**，
这时通知退回普通形态（不是 bug，也不影响下载）：

| 厂商 | 形态 | 本项目怎么接 | 前置条件 |
|------|------|-------------|---------|
| 谷歌 | Android 16 Live Updates（promoted ongoing） | `NotificationCompat.ProgressStyle` + `setRequestPromotedOngoing`（**反射**调用，依赖是 1.10.1 时静默跳过） | `POST_PROMOTED_NOTIFICATIONS`（已声明）+ 系统 16 |
| 小米 | 超级岛 / 焦点通知 | 通知 extras 里挂 `miui.focus.param`（JSON，`XiaomiIslandPayload`） | 焦点通知权限（`notification_focus_protocol ≥ 2`） |
| OPPO | ColorOS 实况通知（流体云） | 把通知规范成常驻 + 进度 + 不重复提醒；官方 SDK 走 `OppoLiveAlertExtension(attacher = …)` 注入 | 开放平台白名单 / 官方 SDK |

没白名单还想上岛，只能靠第三方模块 Hook 通知 —— 所以进度通知上固定带一份
`com.branchbase.download.*` 的 extras（任务 id / 标题 / 状态 / 已下载 / 总量 / 百分比 / 是否常驻），
键名一经发布不再改（见 `DownloadNotificationHook`）。

### 已知边界

- 任务表在**进程内存**里：进程被杀就重来（没有「重启后继续」的语义，也没有落盘恢复）；
- 前台服务类型是 `dataSync`：Android 15 起有累计时长上限，超时回调里落成「可重试的失败态」；
- 「打开所在文件夹」没有统一契约，只能尽力而为（DocumentsUI 根 URI → 常见文件管理器包名探测）；
  失败时由调用方降级成「分享」（`ACTION_SEND` 是人人都有实现的那条路）；
- 需要用户能在系统文件管理器里直接看到文件时另走「导出」（MediaStore / SAF），
  下载主链路不申请存储权限；
- 失败文案**只出中文**：网络栈的原始异常（`Unable to resolve host …`）不进通知栏，
  统一在 `DownloadErrors` 里归到「网络 / 超时 / 证书 / 权限 / 服务端」几类结论上。

---

## 3. 图片查看器（`:imageviewer`）

正文页（README / issue 主帖）里的图片点一下就能放大看：双指缩放 / 双击放大 / 拖动平移 /
下拉关闭，全屏 `Dialog` 弹出，不进导航栈。实现收在 `:imageviewer`：

| 文件 | 职责 |
|------|------|
| `ImageViewerDialog.kt` | 全屏 Dialog：手势、加载/失败态、「用浏览器打开」、顶栏 |
| `ImageViewerMath.kt` | 纯计算：缩放钳制（1×–8×）、双击目标、平移边界、下拉关闭判定（可 JVM 单测） |

两条入口，覆盖两种标记方式：

- **图片没被链接包住** → 注入脚本的点击监听调用 `BBImage.openImage()` 桥（< 240×120 的小图不弹，
  徽章/图标弹出来只是一张糊图）；
- **图片被 `<a>` 包住**（README 里点截图最常见）→ 不动链接语义，改由 `shouldOverrideUrlLoading`
  判断目标是不是图片：是图片（`camo.githubusercontent.com` 无扩展名 / 常见图片后缀）就弹查看器，
  否则照旧交给浏览器 —— 所以「点徽章去仓库页」仍然正常。

取图凭据沿用与 WebView 拦截同一条策略：**只给 GitHub 自有域名带 Token**；
camo 是签名地址、第三方图床（shields.io 等）一律不带。

---

## 4. 代码编辑器（`:editor`）

Sora Editor 的独立封装，对外只有 `BranchbaseCodeEditor` 一个组件（换库 / 移除只动这个模块）。

### 接在哪：文件页的**编辑态**（查看态是页面自绘的只读预览）

上一版这份组件在 App 里**一次都没被调用**（`:editor` 只在关于页留了一行痕迹），
文件页编辑态仍用 Material 的 `OutlinedTextField`：四周一圈方框、没有行号，
观感反而比同一页的只读预览还差。现在编辑态走这个组件，外观契约是
**等宽 + 行号 + 无边框 + 与调用方同一块底色**：

| 观感项 | 约定 | 落点 |
|--------|------|------|
| 行号 | 开、右对齐、左侧留白 8dp；**不画行号栏竖线**（竖线等于又加一圈边框） | `BranchbaseCodeEditor.applyEditorAppearance` |
| 字体 | 正文与行号都用等宽，13sp（比只读预览的 12sp 大一档） | 同上 |
| 底色 | 调用方把页面的代码表面色传进来（`backgroundColor`），画布与行号栏同色 | `backgroundColor = CodeSyntax.CodeBg.toArgb()` |
| 明暗 | 传 App 的生效值（三档主题），不用 `isSystemInDarkTheme()` | `darkTheme = LocalIsDarkTheme.current` |
| 当前代码块高亮 | 关：没有语法分析器时它只会把整片文本糊上一层色；当前行高亮与括号配对保留 | `applyEditorAppearance` |

> `backgroundColor` 的覆盖**不能**塞进 `BranchbaseEditorColorScheme` 的构造参数再在
> `applyDefault()` 里用 —— 那是父类构造器里的回调，那一刻子类字段还没赋值，读到的是 0 / null
> （只能构造完成后再 `applySurface()`，所以两者是分开的两步）。

`FileEditorWiringTest` 把这条接线钉在源码上（文件页必须调用组件、正文不得回退成带方框的输入框、
必须传主题与底色）—— 「模块写了却没人接」编译不报、单测不红，只能这样拦。

### 配色：显式覆盖，且**编辑态与只读预览态共用同一份**

Sora 默认色板的 `TEXT_NORMAL = #FF333333` 且**不随明暗切换** —— 亮色下是深灰不是纯黑，
深色下背景变深后几乎看不见。所以 `:editor` 不再使用库默认值：

| 约束 | 说明 |
|------|------|
| 正文 / 行号 / 选中文字 / 内联提示 / 补全文字 / 诊断提示 / 删除线 / 操作窗图标 | **浅色纯黑 `#000000`、深色纯白 `#FFFFFF`**（`EditorPalette.PureTextIds` 是机器可读清单，单测逐项断言） |
| 背景 / 当前行 / 选区 / 分隔线 / 滚动条 / 括号配对 / 行号面板 / 操作窗 | 成对给出的主题表面色（浅色白底，深色 `#0D1117`）；这些「没有语法分析器也会被画出来」的 id 由 `EditorPalette.VisibleSurfaceIds` 声明，漏一项单测就红 |
| 语法令牌（关键字 / 注释 / 字符串 / 运算符 / 函数名） | 保留主题色：注释偏暗是**语法语义**，不是渲染缺陷 |

三条容易踩的坑：

1. **只覆盖一部分 id 等于没修**：漏掉的那些会退回库默认的灰 / 透明，而它们恰恰在
   「只看不编辑」的**只读预览**态最常见（选中文字默认透明、删除线默认透明、
   深浅色分隔符前景是半透明黑、诊断提示浅色下是 `#424242`、深色下行号面板文字变深色），
   编辑态也躲不开（`SCROLL_BAR_TRACK` 默认是一条有色竖带、`LINE_NUMBER_PANEL` 默认全透明、
   操作窗默认是深灰底配白图标、与本模块「浅色黑字」的契约相反）。
   所以覆盖项只从 `EditorPalette.assignments()` 来（单一真源），漏一项单测就会红；
2. **`applyDefault()` 会在父类构造器里被回调**（Sora 的设计），覆写里只能读 `isDark()`，
   不能碰子类字段（调用方的表面色因此只能在构造完成后 `applySurface()`）；
3. **`EditorColorScheme(boolean)` 是 `protected`**：直接 `EditorColorScheme()` 得到的永远是
   「浅色」实例、`isDark()` 会撒谎，所以必须以子类形式继承它。

文字颜色一旦写错，编译不报、运行不崩、单测不测就**只有真机上肉眼能发现** ——
这就是 `EditorPaletteTest` 存在的原因（配色是纯数据 + 纯映射，可在 JVM 上直接断言）。

---

## 5. 作业日志（`:joblogs`）

GitHub Actions 的 job 日志（运行详情页按步骤看、Job 详情页整段看）以前在 `:app` 里
**写了两遍**：各自的地址、各自的失败判定、各自的分段。现在取数收进 `:joblogs`：

| 文件 | 职责 |
|------|------|
| `JobLogStore.kt` | 入口：内存 LRU → 缓存直出 → 回源；**同一 jobId 的并发调用合并成一次下载** |
| `JobLogParser.kt` | 纯逻辑：剥掉行首 ISO 时间戳、按 `##[group]` 切段（可 JVM 单测） |
| `JobLogCache.kt` | 缓存**窄接口**（先直出 / 回源两段式），由 `:app` 用 `PageCache` 实现 |
| `JobLogSource` | 日志来源，「jobId → 日志原文」，由 `:app` 用 `RustBridge` 实现 |

模块只认这两样注入，**不认识** GitHub / Token / RustBridge / Room / PageCache。
接线全在 `:app` 的 `ui/repository/JobLogWiring.kt` 一个文件里，删模块时连它一起删。

三条设计约束：

- **单飞（single-flight）而不是「多线程」**：日志的可感开销是「下载整份日志 + 逐行切段」，
  前者是网络等待、后者已经在 `Dispatchers.Default` 上，加线程数不会更快。真正省时间的是
  同一 jobId 的并发请求只发一次 —— 例如运行详情页与 Job 详情页来回切、或手快点两个步骤。
  模块不创建线程、也不持有自己的作用域，全部跑在调用方的协程里；
- **页面之间共用一份 store**：`JobLogStore` 在 `RepositoryScreen` 层建一个，两个详情页共用，
  于是已切好的分段与在飞请求表都是同一份；`rememberJobLogStore` 放在页面里会让内存缓存
  随页面销毁而白建；
- **失败就是 null**：`:app` 把 `RustBridge` 的两种失败（null / `ERROR:` 前缀）折叠成 null，
  模块不解析任何错误字符串。取不到时**不写缓存**，下次调用会重新发起（可重试）。

`WorkflowLogThemeTest` 钉住两条源码级约束：工作流这条链路上参与渲染的文件都不得出现
浅色主题的写死取值（日志块用 `CodeSyntax.CodeBg` + `Primer.TextPrimary`），
且作业日志地址**只允许出现在接线层一处**（扫整个 `ui/` 目录，不是写死几个文件名）。

---

## 6. 运行中的工作流：轮询的是**状态**，不是日志

这条约束**必须写进代码注释之外的地方**，否则很容易被「顺手改成边跑边拉日志」：

| 事实 | 依据 |
|------|------|
| 逐 job 日志是**纯文本**（不是 zip）：`GET /actions/jobs/{job_id}/logs` 返回 302，`Location:` 是签名 URL、**1 分钟过期** | REST 文档 / OpenAPI：*"a redirect URL to download a plain text file of logs for a workflow job"* |
| **job 结束前日志 blob 不存在**（404），所以运行中**拉不到**日志 | [community #154834](https://github.com/orgs/community/discussions/154834)（*API No Longer Returns Logs in Real-Time, Only After Job Completion*）、[#75518](https://github.com/orgs/community/discussions/75518) |
| GitHub **没有**长轮询 / SSE：挂住连接不会等到新内容。网页版能实时滚动，走的是**未公开**的内部 websocket（`pipelines.actions.githubusercontent.com`） | [逆向记录](https://github.com/Hacksore/github-websocket-pipeline-api) |

由此定下三条：

1. **持续获取的对象是 `GET /actions/runs/{id}/jobs`**（几 KB JSON，带每个 job / step 的状态与时间戳）；
   日志只在**某个 job 的 status 变成 `completed`** 的那一刻抓一次（`newlyCompletedJobIds` 差分决定），
   之后该 job 永不重抓；
2. **不在后台轮询**：`WorkManager` 的周期任务有 [15 分钟硬下限](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/work/work-runtime/src/main/java/androidx/work/PeriodicWorkRequest.kt)，
   要更快就得常驻前台服务（常驻通知是**用户可见的代价**）。而「跑完知道」这件事
   已经由 GitHub 的服务端 webhook 通知兜住了（`WorkflowRun` 通知可直达 Run 详情），
   所以只需要**回到前台时强制对齐一次**；
3. **「还没生成」不是「失败」**：job 没结束时取不到日志是**正常状态**，界面显示
   「任务运行中，日志将在该任务结束后自动出现」，**不给「重试」按钮**。

策略本身是纯逻辑，在 `ui/repository/RunPollPolicy.kt`（间隔阶梯 5s→15s→30s、
计费网络只降速不停止、失败指数退避封顶 60s、`shouldPoll` 要求「运行中 + 前台」），
由 `RunPollPolicyTest` 逐条钉住；取数与切片继续走 `:joblogs`（单飞 + 缓存 + 分段）。

---

> **相关文档**：[`ui-design.md`](ui-design.md)（模块里的界面规格）·
> [`screens-design.md`](screens-design.md)（运行详情页的卡片流重绘）·
> 模块的构建坑（`:translate` 落地时踩到的三个）见 [`BUILD-NOTES.md`](BUILD-NOTES.md) §三。
