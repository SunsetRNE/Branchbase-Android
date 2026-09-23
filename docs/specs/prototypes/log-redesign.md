<!-- 入库副本：正文与 design/log-redesign/README.md 一致，未做删改，仅加本段头。 -->
> **来源**：`design/log-redesign/README.md`（原型本体 `index.html` / `style.css` / `app.js` / `smoke.js` / `theme-audit.js` 在 `/design/log-redesign/` 下，按仓库约定**不入库**；DSH 侧边栏「原型预览」面板可直接打开）
> **为什么只把文档抽进来**：原型 HTML 是一次性草图，跟具体实现绑死、很快过期；
> 而这份文档里的**现状测绘、压缩账、令牌映射与落地清单**是跨时间有效的设计结论，代码注释会引用它。
> **维护**：原型再改时请把这份副本一并更新，别让两边分叉；文中提到的文件名相对原始目录。

# 日志页重设计原型 · 说明与落实方案

> **快照说明（2026-09 补）**：本文是**设计当时的记录** —— 文中的「现状测绘」、「`文件:行号`」、
> 「依据：`xxx.kt`（N 行）」都不会随代码演进更新（只有少数几处加了「2026-09 追记」）。
> 判断当前行为请以代码与 `docs/specs/` 下的规格为准；落地清单里未落地的项只表示「当时计划过」。

> 目录：`design/log-redesign/`（`/design/` 在 `.gitignore` 里：原型草图不入库）
> 本文档的**入库副本**：`docs/specs/prototypes/log-redesign.md`（改完这里请同步那份）
> 对象页面：`app/src/main/java/com/branchbase/ui/log/LogScreen.kt`（设置 →「日志」）

---

## 一、交付物与查看方式

| 文件 | 作用 |
|---|---|
| `index.html` | 页面原型（手机外壳 390×844 + 设计说明覆盖层） |
| `style.css` | 设计令牌（明暗两套）+ 组件样式。**不引入任何新色值** |
| `app.js` | mock 日志数据 + 渲染与交互（纯 vanilla，无依赖） |
| `smoke.js` | Node 烟测：DOM 桩里真跑渲染与交互（13 组，155 项），含**令牌审计**、**尺寸钉子**、**排版鲁棒性**、**过滤行挤占**、**布局自检**、**id 交叉核对** |
| `theme-audit.js` | 对比度审计：从 CSS 令牌算 WCAG，不需要浏览器 |

**怎么打开**

1. DSH Web 侧边栏「原型预览」面板 → 选 `design/log-redesign/index.html`（设备预设取「手机 390×844」最贴近真机）；
2. 或本地静态服务：`python3 -m http.server 8099 --directory design/log-redesign` → `http://127.0.0.1:8099/index.html`。

**原型工具条**（窄视口下是页面底部的一条控制栏，宽视口下是左下角的悬浮列；不属于设计稿）：
`ⓘ 设计说明` · `◐ 主题（浅色 → 深色 → 跟随系统）` · `▤ 行距（紧凑 ↔ 宽松）` · `◑ 切到「空日志」` ·
`⌗ 布局自检` · 构建号 `v20260913b`。

> **改了 `style.css` / `app.js` 就要升 `index.html` 里的 `?v=`**（两处）以及工具条上的构建号，
> 否则预览面板会继续用缓存里的旧文件 —— 这一条已经踩过一次坑，`smoke.js` 里钉住了。

**自检**（都不需要浏览器）

```bash
node design/log-redesign/smoke.js design/log-redesign        # 渲染 + 交互 + 令牌审计 + 索引核对
node design/log-redesign/theme-audit.js design/log-redesign  # 对比度（两套令牌，全部达标才退 0）
```

**URL 参数**（便于把某个状态直接发给别人复核）

```
index.html?state=empty                 空日志态
index.html?mode=raw                    原始日志视图
index.html?q=GitHubAPI                 搜索态（命中计数 / 高亮 / n/m 跳转）
index.html?level=ERROR&cat=NETWORK     级别 + 类别叠加过滤
index.html?n=123456                    压力：把日志堆到 6 位数（看顶栏/底栏/行号）
index.html?probe=1                     把**实测尺寸**打回静态服务器日志（远程核对排版，见 v4）
index.html?theme=dark                  深色
```

---

## 二、现状测绘（改之前长什么样）

`LogScreen.kt` 是「设置 → 日志」的唯一入口（`SubPageScreens.kt:508` 挂的 `onOpenLog`）。
数据来自 `Logging.kt`：类别 4 个、级别 4 档、内存环形缓冲最近 **1000** 条 + 追加写 `branchbase.log`。

从进入页面到第一条日志，纵向堆了 5 层：

| 层 | 代码 | 高度 |
|---|---|---|
| 顶栏（返回 / 标题 / 「导出 .log」/「清空」两个 `TextButton`） | `LogScreen.kt:110-123` | ≈52dp |
| 工具栏（48dp 的 `OutlinedTextField` + 级别下拉 + 过滤按钮） | `LogScreen.kt:126-164` | 48dp |
| 统计徽章行（`padding(vertical = 12.dp)`，**不可点**） | `LogScreen.kt:167-175` | ≈46dp |
| 时间流 / 原始日志 双加载 | `LogScreen.kt:178-194` | ≈42dp |
| `Spacer(12.dp)` | `LogScreen.kt:196` | 12dp |

合计 **≈196dp**。单条日志（`LogStreamItem`，`LogScreen.kt:259-285`）是两行：
11dp 上下内边距 + 14sp 消息（lineHeight 20sp）+ 6dp 间隔 + 11sp 元信息行 ≈ **63dp**。

⇒ 844dp 的屏上，**首屏只能看到 ≈9 条日志**。

### 逐条问题（都指到行）

1. **颜色写死成 Primer 深色取值**（`LogScreen.kt:60-69, 245, 253-255, 264, 275, 278, 280, 211, 216`）：
   `0xFF161B22`（行底）、`0xFF21262D` / `0xFF30363D`（徽章）、`0xFF0A0E14`（原始日志底）、
   `0xFFE6EDF3` / `0xFFC9D1D9`（正文）、`0xFF8B949E` / `0xFF6E7681`（次要文字），
   外加 `0xFF3F8FE0`（既不是浅色板的蓝，也不是深色板的蓝）。
   ⇒ **浅色主题下这一页依然是黑底白字**，与全 App 撕裂。这是「接入主题」要解决的第一件事。
2. **统计徽章是死的**：4 个类别 + 「共」只显示数字，点了没反应；
   而同一批类别在过滤对话框（`LogScreen.kt:306-310`）里又列了一遍。
3. **过滤对话框会爆屏**：tag 列表用 `logs.map { it.tag }.distinct()` 全量竖排（`LogScreen.kt:300, 319-321`），
   没有计数、没有限高 —— tag 一多就超出屏幕，也没有搜索。
4. **原始日志不可读**：把 1000 条拼成一个字符串塞进 `Text`（`LogScreen.kt:211-220`），
   无行号、无横向滚动（长行被裁）、几 MB 时整块测量、滚动卡。
5. **空态没有出路**：只给一句「（无匹配日志）」（`LogScreen.kt:201`）。
6. **搜索不能跳**：只高亮，没有「第 n / 共 m 条」与上/下跳。
7. **重复初始化**：`LogScreen.kt:81` 与 `95` 两个 `LaunchedEffect(Unit)` 各读一次 `LogManager.all()`。
8. **「清空」名不副实**：`LogManager.clear()`（`Logging.kt:57`）只清**内存缓冲**，
   `branchbase.log` 原样保留 —— 用户以为清干净了，但「导出 .log」仍会导出全部历史。
9. **「导出 .log」在主线程读整个文件**（`LogScreen.kt:119`，`logFile()?.readText()`）：
   文件大了就是一次 UI 线程 IO。
10. **时区写死 Asia/Shanghai**（`LogScreen.kt:72-75`）：非该时区的用户看到的时间不是本地时间。

### 顺带查出的 app 侧问题（不属于本页，但建议一起处理）

- **`LazyColumn` 的 key 可能重复**：`key = { it.time.toString() + it.message }`（`LogScreen.kt:204`）。
  同一毫秒、同一条消息打两次 → 重复 key，`LazyColumn` 会直接抛 `IllegalArgumentException`。
- **日志文件无上限**：`FileAppender.appendText`（`Logging.kt:96-101`）只追加，没有轮转/截断，
  `branchbase.log` 会一直长（内存有 1000 条上限，磁盘没有）。
- **失败静默**：`FileAppender.append` 的 `catch (_: Exception) {}` 把落盘失败吞掉，
  日志页里也看不出来「文件其实没写进去」。

---

## 三、压缩账（这是本轮的主诉求）

做法不是「把字号统一调小」，而是**先删重复的行，再压单条的密度**。

| 区块 | 现状 | 新设计 | 省下 |
|---|---|---|---|
| 顶栏 | ≈52dp（两个 `TextButton` 撑高） | 44dp（导出/清空收进 ⋮） | 8dp |
| 搜索 | 48dp 常驻输入框 | 36dp，**默认收起**（点放大镜才出现） | 48dp |
| 类别统计 | ≈46dp，纯展示 | 0（并入下面那行**可点**的 chips） | 46dp |
| 视图切换 | ≈42dp 独占一行 | 0（塞进 32dp 子栏右端） | 42dp |
| 间距 | 12dp | 0 | 12dp |
| **chrome 合计** | **≈196dp** | **≈110dp** | **−44%** |

单条日志：**63dp → 22dp（紧凑）/ 31dp（宽松）**，靠的是「两行 → 单行网格」而不是缩字号
（消息仍是 12.5sp，比现状的 14sp 小一档但同比更清晰：现状把 14sp 浪费在整行留白上）。

| | 现状 | 新设计（紧凑） |
|---|---|---|
| 日志区可用高度 | 844 − 44 − 196 = **604dp** | 844 − 44 − 110 − 34(底栏) = **656dp** |
| 单条高度 | 63dp | 22dp |
| **首屏可见** | **≈9 条** | **29 条**（`smoke.js` 从 CSS 实测反推，见「尺寸钉子」） |

底栏是**新增**的 34dp，换来的是一条常驻的「现在什么情况」：总条数 / 过滤后条数 / 错误与警告计数 /
跟随尾部 / ↓ 最新。它替代的正是顶部那行不可点的徽章。

---

## 四、逐块设计

### ① 顶栏（44dp）

`←  日志 · 1000 条 ……  🔍  ⋮`。标题右侧直接用次要色带上条数（过滤时变成 `12 / 1000 条`），
不再需要单独的统计行。⋮ 菜单收：导出 .log · 复制当前视图 · 按 tag 过滤… · 行距 · 分享 · **清空**（危险项，红色文字）。

### ② 搜索行（36dp，默认收起）

点 🔍 展开；输入即过滤，右侧给 `3/161` 命中计数 + `↑`/`↓` 跳转 + `✕` 关闭。
命中行整行加底色、**当前命中**再加一条左侧标记。点 ✕ 或再点 🔍 会同时清掉关键词（不会留下「看不见的过滤」）。

### ③ 类别 chips（34dp）——统计徽章变成过滤器

`全部 1000` `● UI 278` `● 网络 356` `● 远端 166` `● 本地 200`
计数长在 chip 上，**点它就是过滤**（再点取消）。类别点用主题里的语义色：
UI→`accent`、网络→`success`、远端→`done`、本地→`warning`。计数为 0 的 chip 变淡。

> 现状那 4 个类别徽章 + 过滤对话框里的 4 个选项，本来是同一件事说了两遍；现在是一件事一个入口。

### ④ 子栏（32dp）：级别 chips + 视图切换

`全部` `DEBUG 51` `INFO 858` `WARN 74` `ERROR 17` …（横向滚动）… 右端固定 `[时间流 | 原始]` 分段控件。
级别文字色即主题的文字角色：INFO→`accentText`、WARN→`warningText`、ERROR→`dangerText`、DEBUG→`textTertiary`。

### ⑤ tag 过滤（点日志里的 tag 即过滤）

最常见的路径是「这条日志是哪个模块打的」→ 直接点那一行的 tag，顶部出现一条 `tag 过滤 [GitHubAPI ✕]`，再点取消。
标签太多、需要挑的时候走 ⋮ →「按 tag 过滤…」：底部面板，**限高 62%、带计数、可搜索**（现状是全量竖排的对话框）。

### ⑥ 单行日志行（22dp）

四列网格：`时间(HH:mm:ss.SSS) | 分级条 | 类别点 | [tag] 消息`。

- 时间列 `tabular-nums` 定宽右对齐 —— 长消息换行时也不会把时间挤走；
- **不铺整行红/黄底**，改成左侧 2px 分级条：深色下不吵，浅色下正文对比度不被底色拖低；
- 级别体现在**消息文字色**上（错误用 `dangerText` 而不是填充红 `danger`，见第六节）；
- 点任意一行 = 复制这一行的落盘格式；点 tag = 过滤。

**紧凑是默认，宽松是选项**（⋮ → 行距，或工具条 `▤`）。官方 logcat / DevTools 也是这个思路：
密度该由使用者决定，而不是一次性焊死。

### ⑦ 原始日志（等宽 + 行号 + 横向滚动 + 分块）

行号 gutter + `ui-monospace` + `white-space: pre` + `overflow-x: auto`，
内容就是 `FileAppender` 的落盘行格式：`HH:mm:ss.SSS [类别] [tag] LEVEL message`
（与 `Logging.kt:99` 逐字一致，所以「看到的」= 「文件里的」）。

长日志**分块渲染**（每块 240 行，滚到底自动续，也给了「继续加载（还有 N 行）」按钮）：
现状整段拼字符串丢给 `Text` 的写法在几 MB 时会整块测量。

### ⑧ 底栏（34dp）

`● 1000 条 · 错误 17 · 警告 74`（有错误时圆点变红、只有警告变黄）+ `跟随尾部` + `↓ 最新`。
过滤后变成 `显示 17 / 1000 条 · 级别 ERROR`。

**顺序改为升序（旧 → 新）**：与 `branchbase.log` 的追加顺序一致，于是「跟随尾部 = 滚到底部」
和「↓ 最新」都成立。现状 `LogManager.all()` 是新→旧，页面直接照渲染 —— 这个改动只发生在页面层，
`Logging.kt` 不用动。

### ⑨ 空态

- 没有日志：「还没有日志 / App 运行时的记录会出现在这里，同时写入 branchbase.log」；
- 筛没了：「没有匹配的日志 + 当前筛选条件 + **清空筛选** 按钮」。

---

## 五、颜色：9 个写死常量 → 主题角色

现状 9 个写死值全部换成角色令牌。**按语义取色，而不是按「看起来像」取**：

| 用途 | 现状（写死） | 新设计（角色） |
|---|---|---|
| 行底 / 日志底 | `0xFF161B22` | `CodeSyntax.CodeBg`（`--code-bg`） |
| 原始日志底 | `0xFF0A0E14` | 同上（**不再单独一套黑**） |
| 日志正文 | `0xFFE6EDF3` / `0xFFC9D1D9` | `Primer.TextPrimary` / `TextSecondary` |
| 时间 / tag | `0xFF6E7681` / `0xFF8B949E` | `Primer.TextTertiary` |
| 徽章底 / 描边 | `0xFF21262D` / `0xFF30363D` | `Primer.Gray150` / `Primer.Border` |
| ERROR 文字 | `0xFFF85149`（填充红当文字） | `Primer.DangerText` |
| WARN 文字 | `0xFFD29922`（警告填充色） | `Primer.WarningText` |
| INFO / 类别蓝 | `0xFF3F8FE0`（两套色板都不是） | `Primer.AccentText` / `Primer.Blue500` |
| 远端紫 / 本地黄 | `0xFFBC8CFF` / `0xFFD29922` | `Primer.Purple500` / `Primer.Orange500` |

### 令牌映射表（原型 CSS 变量 → `ui/theme/ThemePalette.kt` 角色 → Compose 写法）

| CSS 变量 | `PrimerPalette` 角色 | Compose 访问 | 浅色 | 深色 |
|---|---|---|---|---|
| `--canvas` | `canvas` | `Primer.BackgroundPrimary` | `#FFFFFF` | `#0D1117` |
| `--canvas-subtle` | `canvasSubtle` | `Primer.BackgroundSecondary` | `#FFFFFF` | `#161B22` |
| `--text-primary` | `textPrimary` | `Primer.TextPrimary` | `#050505` | `#E6EDF3` |
| `--text-secondary` | `textSecondary` | `Primer.TextSecondary` | `#41434E` | `#C9D1D9` |
| `--text-tertiary` | `textTertiary` | `Primer.TextTertiary` | `#6A6D7C` | `#8B949E` |
| `--icon-primary` | `iconPrimary` | `Primer.IconPrimary` | `#000000` | `#FFFFFF` |
| `--icon-secondary` | `iconSecondary` | `Primer.IconSecondary` | `#9194A1` | `#8B949E` |
| `--border` | `border` | `Primer.Border`（**只做 stroke**） | `#BFC1C9` | `#30363D` |
| `--border-soft` | `neutralBorder` | `Primer.Gray200` | `#E3E4E8` | `#30363D` |
| `--neutral-fill` | `neutralFill` | `Primer.Gray100` | `#F7F7F9` | `#161B22` |
| `--neutral-fill-strong` | `neutralFillStrong` | `Primer.Gray150` | `#EFF0F5` | `#21262D` |
| `--neutral-muted` | `neutralMuted` | `Primer.Gray300` | `#BFC1C9` | `#484F58` |
| `--emphasis-fill` / `--emphasis-on-fill` | `emphasisFill` / `emphasisOnFill` | `Primer.Gray900` / `Primer.OnEmphasis` | `#17181C` / `#FFF` | `#E6EDF3` / `#0D1117` |
| `--accent` | `accent` | `Primer.Blue500` | `#0969DA` | `#1F6FEB` |
| `--accent-strong` | `accentStrong` | `Primer.Blue600` | `#005CC5` | `#388BFD` |
| `--accent-text` | `accentText` | `Primer.AccentText` | `#0550AE` | `#79C0FF` |
| `--on-accent` | — | `Primer.Gray000`（彩色填充上的白字，两套都是白） | `#FFFFFF` | `#FFFFFF` |
| `--link` | `link` | `Primer.Link` | `#0969DA` | `#2F81F7` |
| `--success` | `success` | `Primer.Green500` | `#28A745` | `#3FB950` |
| `--danger` / `--danger-text` | `danger` / `dangerText` | `Primer.Red500` / `Primer.DangerText` | `#D73A49` / `#9E1C24` | `#F85149` / `#F85149` |
| `--warning` / `--warning-text` | `warning` / `warningText` | `Primer.Orange500` / `Primer.WarningText` | `#F66A0A` / `#7D4C00` | `#D29922` / `#D29922` |
| `--done` | `done` | `Primer.Purple500` | `#6F42C1` | `#A371F7` |
| `--code-bg` / `--code-border` | `code.bg` / `code.border` | `CodeSyntax.CodeBg` / `CodeSyntax.CardBorder` | `#F6F8FA` / `#D0D7DE` | `#161B22` / `#30363D` |
| `--mark-bg` | `code.matchBg` | `CodeSyntax.MatchBg` | `#FFF8C5` | `#3B2300` |
| `--code-line-no` | `code.lineNo` | `CodeSyntax.LineNo`（**本页刻意不用**，见下） | `#C0C6CC` | `#6E7681` |
| `--lv-*-bar` | `warning` / `danger` / `accent` / `neutralMuted` | 同上 | — | — |

**唯一一处刻意偏离**：原始日志的**行号**没有用 `CodeSyntax.LineNo`，而是用 `Primer.TextTertiary`。
理由是可读性：`LineNo` 的浅色取值 `#C0C6CC` 压在 `#F6F8FA` 上只有 **1.62:1**（等于看不见），
换 `TextTertiary` 后浅色 **4.82:1** / 深色 **5.62:1**。行号是功能性文字（用来定位），不是装饰。

### 对比度审计（`theme-audit.js`，不需要浏览器）

19 组真实叠放在**明暗两套下全部达标**（文本 ≥4.5、图形 ≥3.0）。摘要：

| 组合 | 浅色 | 深色 |
|---|---|---|
| 正文消息 | 19.14:1 | 14.64:1 |
| 时间列 / tag | 4.82:1 | 5.62:1 |
| INFO 消息（`accentText`） | 7.13:1 | 8.89:1 |
| WARN 消息（`warningText`） | 6.79:1 | 6.85:1 |
| ERROR 消息（`dangerText`） | 7.46:1 | 5.16:1 |
| 原始日志行号 | 4.82:1 | 5.62:1 |
| chip 选中（白字压 `accent`） | 5.19:1 | 4.63:1 |
| 命中高亮 | 18.91:1 | 12.46:1 |

脚本同时打印「现状写死取值若放到浅色页面」的对照，说明为什么必须换角色而不是搬数值：

| 现状取值 | 放在浅色页面上 |
|---|---|
| ERROR `#F85149` | `#FFFFFF` 上 **3.35:1** · `#F6F8FA` 上 **3.15:1** —— 低于 AA |
| WARN `#D29922` | `#FFFFFF` 上 **2.52:1** —— 明显不可读 |
| 次要文字 `#6E7681` | `#F6F8FA` 上 **4.32:1** —— 差一点 |
| INFO 蓝 `#3F8FE0` | 浅色底 **3.38:1** —— 低于 AA |

也就是说：现状「能用」只是因为整页被写死成了深色；一旦要跟随主题，这 9 个值里有一半当文字就不合格。

---

## 六、后续接入主题板块（本轮的接口面）

「接入主题板块」在这份原型里是**已经预留好接口、只差一次机械替换**的：

1. **页面不持有任何色值**：`app.js` 与 `style.css` 的组件区都没有十六进制色值
   （`smoke.js` 的令牌审计会检查这件事，并把 9 个历史常量列为禁止出现）；
2. **变量名与角色同名同义**：`--text-tertiary` ↔ `Primer.TextTertiary`，
   落实时在 Compose 侧把 `Color(0xFF…)` 逐个换成 `Primer.XXX` 即可，**不需要新增任何令牌**
   （`PrimerPalette` 里 `dangerText` / `accentText` / `warningText` / `emphasisFill` 等角色都已存在）；
3. **页面因此自动跟随 `ThemeRuntime.mode`**（跟随系统 / 浅色 / 深色）——
   原型工具条的 `◐ 主题` 就是在演示这三档；
4. **以后主题板块若加「强调色」档**，本页所有蓝都已经是 `accent` / `accentText` / `link` 三个角色，
   没有第二个蓝，不会漏改。

**建议加一条源码级钉子**（照 `app/src/test/java/com/branchbase/ui/repository/WorkflowLogThemeTest.kt` 的写法，
它是同一套「日志页不许写死色值」的套路）：

```kotlin
// app/src/test/java/com/branchbase/ui/log/LogThemeTest.kt
// 1) LogScreen.kt 里不得出现 0xFF161B22 / 0xFF21262D / 0xFF30363D / 0xFF0A0E14 /
//    0xFFE6EDF3 / 0xFFC9D1D9 / 0xFF8B949E / 0xFF6E7681 / 0xFF3F8FE0 这些字面量；
// 2) 日志底必须是 CodeSyntax.CodeBg、正文必须是 Primer.TextPrimary；
// 3) 级别文字必须取 Primer.DangerText / WarningText / AccentText / TextTertiary，
//    不许再拿 Primer.Red500 / Orange500 这类**填充色**当文字。
```

---

## 七、落实方案（Compose 侧，按图索骥）

改动集中在 `app/src/main/java/com/branchbase/ui/log/`，**不动 `Logging.kt` 的取数与落盘**：

| 文件 | 动作 |
|---|---|
| `ui/log/LogScreen.kt` | 重写页面骨架：顶栏 44dp + 搜索行（收起）+ 类别/级别 chips + 子栏 + 日志区 + 底栏；删掉 `StatBadge` 与 `FilterDialog` 的类别/级别部分 |
| `ui/log/LogScreenChips.kt`（新增） | 类别/级别 chips（计数 + 选中态）。计数用 `remember(logs) { logs.groupingBy { it.category }.eachCount() }` |
| `ui/log/LogRow.kt`（新增） | 单行网格行：`Row { 时间 / 分级条 / 类别点 / tag + 消息 }`，紧凑 22dp 与宽松 31dp 两档；点行复制、点 tag 过滤 |
| `ui/log/LogRawView.kt`（新增） | 原始日志：`LazyColumn` 逐行（**不要**再拼一个 `String` 丢进 `Text`），行号 + 等宽 + `horizontalScroll`，分块 240 行 |
| `ui/log/LogScreen.kt` 的搜索 | 关键词过滤 + 命中行号列表 + `n/m` + 上/下跳（复用一个 `LogSearch` 纯函数，可 JVM 单测） |
| `ui/log/LogTagSheet.kt`（新增） | tag 过滤面板：`ModalBottomSheet`，限高 `fillMaxHeight(0.62f)` + `LazyColumn` + 计数 + 搜索框 |
| `ui/log/LogText.kt`（新增，纯函数） | `formatLine(entry)`（与 `FileAppender` 同一格式）、`rawLines(entries)`、`hits(entries, kw)` —— 都能单测 |
| `ui/log/Logging.kt` | **只加不改**：`all()` 保持新→旧；页面上做 `asReversed()`（或给 `LogManager` 加一个 `allAscending()`） |

> **2026-09 追记（落地核实）**：上表是设计当时的计划。实际落地**没有拆出那 5 个新文件** ——
> `app/src/main/java/com/branchbase/ui/log/` 现在只有 `LogScreen.kt` / `Logging.kt` / `LogExporter.kt` /
> `LogFileProvider.kt` / `FrameWatch.kt` / `DeviceProfile.kt`，chips、单行网格、原始视图都收在
> `LogScreen.kt` 与 `Logging.kt` 里。设计意图（单行网格 / 底栏 / chips / 原始视图）保留在上文。

顺带一起修（第一节列出的 app 侧问题）：

1. `LazyColumn` 的 key 换成稳定唯一值（如序号或 `time.toString() + index`），消掉重复 key 崩溃；
2. `FileAppender` 加大小上限与轮转（或至少在超过 N MB 时截断），别让文件无限长；
3. 「导出 .log」挪到 IO 线程（`LaunchedEffect` + `Dispatchers.IO`），大文件不再卡 UI；
4. 「清空」要么同时清文件（并二次确认），要么改文案为「清空当前视图」，别让用户以为文件被清了；
5. `formatTime` 的 `ZoneId.of("Asia/Shanghai")` 换成 `ZoneId.systemDefault()`。

---

## 八、验收清单

- [ ] chrome 高度 ≤110dp（现状 ≈196dp）；单条 ≤23dp（紧凑）
- [ ] 首屏可见 ≥28 条（现状 ≈9 条）
- [ ] 浅色主题下不再出现深色块；`data-theme` 两套都过对比度审计（`theme-audit.js` 退 0）
- [ ] `LogScreen.kt` 里不再有 9 个历史写死色值（源码级测试钉子）
- [ ] 搜索有 `n/m` 与上/下跳；命中高亮且当前命中可定位
- [ ] 原始日志有行号、可横向滚动、长日志分块渲染
- [ ] 空态（无日志 / 筛没了）都有出路
- [ ] tag 过滤面板限高可滚、带计数，不会爆屏
- [ ] 点行复制的内容 = `branchbase.log` 的行格式（同一函数产出）
- [ ] **时间列不会被挤压/遮挡**：换字体、切宽松档、条数到 6 位都不许压住分级条与消息
- [ ] **数字变大不炸**：`?n=123456` 下顶栏条数、底栏、行号 gutter、chips 计数都正常排版
- [ ] 长数字/hash 这类无断点长串会折行，不被裁切
- [ ] `node design/log-redesign/smoke.js design/log-redesign` 全绿

---

## 九、原型自身的坑（留档）

1. **`className` 与 `classList` 必须双向同步**：烟测的 DOM 桩里这两者是同一个 `Set` 的两个视图，
   只实现一个会让「点开后 `bar.className = 'tagbar on'`、判断用 `classList.contains('on')`」这类代码在桩里失效
   （第一版就踩了：切换过滤后烟测假绿）；
2. **审计要把注释排除**：CSS 注释里写了「浅色下用 `#9E1C24`」这种说明文字，
   如果直接正则扫十六进制会被当成「写死色值」。现在审计先剥注释再扫；
3. **`getElementById` 的桩要懒创建**：`app.js` 里有 `if (!el) return` 的防御，
   桩若返回 `null` 就会让一半交互静默跳过 —— 这类「静默跳过」在烟测里等于假绿；
4. **mock 的级别分布要加权**：等概率抽模板会让 ERROR 占到 11%，
   看着像个随时在崩的 App；加权后 ERROR ≈1.7% / WARN ≈7.4%，才和真实日志的观感一致；
5. **断言助手要取「全部」同名选择器**：`.rawrow` 也出现在 `.logwrap.raw .rawrow { … }` 里，
   只取第一条匹配会断言到错的规则（假红）；
6. **测试关键词别落在被测字符串内部**：搜「长数字串的前 8 位」时，命中高亮会给它插 `<mark>`，
   于是「这个长串完整存在」的断言永远失败 —— 是测试假红，不是页面问题（改用长串之外的词搜索）。

---

## 十一、修订记录

### v2 · 复核后修订（用户反馈：时间遮挡 / 数字变大后排版错乱）

两条反馈的根因是**同一个**：用猜的像素值去装会被内容撑宽的列。

**缺陷 1 · 时间遮挡**

- 现象：`HH:mm:ss.SSS` 压到分级条与消息上。
- 根因：`.lrow` 的时间列写死 `62px`，而 `HH:mm:ss.SSS` 是 **12 个字符**：
  11px 下 DejaVu / Windows 系约 **67px**、SF / 思源约 **59px** ——
  也就是说「在某些字体下正好溢出、在另一些字体下只剩 3px 余量」，属于碰运气。
- 修：`grid-template-columns: max-content 2px 7px minmax(0, 1fr)`。
  时间格式等长 ⇒ 列宽仍然对齐，但不再依赖字体猜测。
- 连带：`.lbody` 加 `overflow-wrap: anywhere` —— 日志里常见
  `offset=1234567890123456789012` / sha256 / base64 这类没有断点的长串，
  在 `overflow-x: hidden` 的滚动容器里会被直接裁掉，**看起来也像被遮挡**。

**缺陷 2 · 数字变大后排版错乱**

- 行号 gutter 写死 `46px`：日志到 6 位（100000 行）就会顶出去 →
  改 `minmax(4ch, max-content)` + `column-gap: 0`。
  （`column-gap` 必须归零：行号是 `position: sticky` 的，它只盖住自己的单元格，
  靠 gap 留白的话横向滚动时正文会从那条缝里透出来 —— 又是一种「被遮挡」。留白改走行号自身的 `padding-right`。）
- 顶栏条数（`100000 条` / `12 / 100000 条`）、底栏状态、命中计数 `n/m`、面板里的超长 tag 名：
  都没有收缩与省略规则，数字一大就会把 🔍 / ⋮ / 按钮顶出屏幕 →
  统一加 `min-width: 0` + `overflow: hidden` + `text-overflow: ellipsis`，按钮加 `flex: 0 0 auto`。
- 压力参数：`index.html?n=123456` 直接把日志堆到 6 位数，用来看顶栏/底栏/行号。

**由此立的规矩**：**会被内容撑宽的列一律不写死 px**（时间列、行号列）；
定宽元素（32px 图标、2px 分级条、7px 类别点）才允许写死。

**新增的钉子**：`smoke.js` 的「排版鲁棒性」共 **21 项** ——
11 项是 CSS 规则钉子（时间列必须 `max-content`、行号列必须 `minmax(ch, max-content)`、
会被数字撑宽的容器必须有省略号……），10 项是在 **123456 条**与 **40 位数字长串**下的动态验证。
改回写死 px 或删掉省略规则，烟测立刻变红。

### v3 · 复核后修订（反馈：渲染出来的原型「没改好」）

v2 改的是 CSS 内部尺寸，但**你看到的仍是旧渲染结果** —— 两个原因都在「外壳」这一层：

**原因 1 · 缓存**：`index.html` 里写的是 `style.css?v=20260913a` / `app.js?v=20260913a`，
改完 CSS/JS 后**没升版本号**，预览面板 / 浏览器就继续用旧文件 —— 内层修得再对也看不见。

- 修：版本升到 `20260913b`，并且在原型工具条上**把构建号显示出来**（`v20260913b`）。
  以后凡是改 `style.css` / `app.js`，必须把 `index.html` 里的两处 `?v=` 和工具条上的构建号一起升；
  看一眼工具条就知道自己是不是在用旧文件。
- 钉子：`smoke.js` 会核对「CSS/JS 的 `?v=` 必须一致，且等于按钮上显示的构建号」。

**原因 2 · 外壳是按桌面写死的**：`.phone` 固定 `390×844`、`body` 还有 24px 内边距，
而 `.proto-tools` 是 `position: fixed`。在预览面板 / 手机浏览器里（视口宽度≈390）：
手机壳右侧被裁掉约 48px（**⋮ 按钮和每行日志的右端直接看不见**）、上下被裁，
工具条还压在日志内容上 —— 看起来就是"排版坏了、有遮挡"。

- 修：外壳改成**两套呈现方式，由视口自动选**：
  - 默认（窄视口）：`.stage` 用 `100dvh` 铺满，`.phone` 宽度 100%、无圆角无外壳，
    工具条变成底部一条 **38px 控制栏**（在文档流里，压不住内容），并避开安全区
    （`env(safe-area-inset-*)`，适配刘海与手势条）；
  - 宽视口（`≥560×900`）：才摆出手机壳 + 桌面背景 + 左下角悬浮工具条，供大屏评审。
  两种模式下 `.phone` **内部**的行列尺寸完全一致 —— 不会再出现"只在预览里坏"。

**新增 3 · 页内布局自检**（因为我没法在你的设备上截图）

与其让你逐个截图描述，不如让页面自己体检：工具条上多了一个徽章
`⌗ 布局自检：正常` / `⌗ 布局自检：N 处溢出`。它检查**内容比容器宽**的元素
（`#phone` / `.topbar` / `.subbar` / `.footbar` / `.tagbar` / 每一行 `.lrow`），
点一下会把出问题的元素用红框标出来，悬停能看到具体是哪个选择器、超了多少 px。

- 这一条检查同时覆盖了 v1 的三个缺陷形态：时间列过窄、长串不折行、顶栏/底栏被大数字顶破；
- 故意横向滚动的容器（chips 行、原始日志）不在检查范围，不会误报；
- 收起的元素（搜索行 / tag 条，`clientWidth == 0`）跳过，也不会误报；
- 每次重渲染（`renderAll`）与视口变化（`resize` / 旋转）后都会复检。

**用法**：打开原型 → 看工具条徽章。显示「正常」= 这一屏没有排版溢出；
显示「N 处溢出」= 点一下，红框就是出问题的地方（把截图或徽章文案发我即可定位）。

### v4 · 复核后修订（反馈：第一排、第二排被挤占；建议数字改气泡球）

现场是一张真机截图，问题很具体：**第一排最后一个「本地」被屏幕右边缘硬切**，
**第二排的「WARN」被右端 `时间流 | 原始` 分段控件截断**。

**先量，再改。** 这一轮加了一条回传通道 `index.html?probe=1`：
页面用一次图片请求把**真实渲染出来的尺寸**打回静态服务器的访问日志
（只回传尺寸数字 + 构建号，不含日志内容）。于是没有截图权限也能拿到真机数据。

真机实测（你的浏览器，视口 **360×656**）：

| 区块 | v3（你截图那版） | v4 |
|---|---|---|
| 类别行 | 360 / **373** → 溢出 13px（`本地` 被切） | 360 / **360** ✓ |
| 级别行 | 344 / **368** → 溢出 24px（`WARN` 被控件截断） | 344 / **344** ✓ |
| 顶栏 / 子栏 / 底栏 | 360 / 360 ✓ | 360 / 360 ✓ |
| 每行日志 | 0 / 240 溢出 ✓ | 0 / 240 ✓ |
| 工具条 | 360 / 434 | 360 / 384（滚动条，带渐隐提示） |

> 注：v3 的级别行在**去掉分段控件前**要 480px，也就是你截图里 `WARN` 被切掉一半的样子。

**根因不是「数字太大」，而是「级别行里塞了一个 76px 的控件」。** 逐条：

1. **计数改「气泡球」**（你的提议，采纳）：数字有自己的紧致底 + 更小字号（9.5px），
   标签成为可扫读的主体，计数退成次要信息；计数变长时在气泡里缩写
   （`1200 → 1.2k`、`123456 → 123k`、`2500000 → 2.5M`），精确值放 `title`。
   顺带说明：**光换气泡解决不了挤占** —— 气泡比同字号的纯文字略宽（多 8px 内边距），
   它解决的是「视觉重量」和「数字无限变长」，宽度得靠下面 2~3 条。
2. **视图切换从级别行搬到顶栏**：这是 `WARN` 被截断的直接原因（那一行少 76px）。
   顶栏本来就有富余（`日志 1000 条` + 搜索 + ⋮），塞得下。
3. **级别 chip 用中文短名**：`DEBUG/INFO/WARN/ERROR` 四个英文词占 130px，是最后缺的那一截；
   改 `调试/信息/警告/错误` 省 **43px**，而且与「时间流 / 原始 / 跟随尾部」等同为中文文案。
   英文 token **没有消失**：仍在 `title`（`ERROR（错误）：17 条`）、原始日志视图、
   复制/导出内容里 —— 数据与落盘格式一个字没改。
4. **「全部」不再重复总条数**：顶栏和底栏各有一处，chip 上再来一次是三重冗余，白占 29px × 2 行。
   精确总数移到 `title`（`不过滤类别（共 1000 条）`）。
5. **chip 更紧**：内边距 8→6px、间隔 5→3px，两行共省 ≈26px，给 320px 窄屏留余量。
6. **横向滚动行加渐隐提示**：超宽时不要「硬切一半」，而是右侧渐隐告诉用户「还能划」；
   状态由真实滚动位置驱动（`more-left` / `more-right`），**滚到端头渐隐就撤掉**，
   不会让最后一个 chip 一直发虚。工具条同样纳入。
7. **工具条文案缩短**（`ⓘ 说明`→`ⓘ`、`⌗ 自检：正常`→`⌗ 正常` 等），390px→360px 下少切一截。

**新增的钉子**：`smoke.js` 的「过滤行的挤占」共 **19 项**（气泡的底/字号/令牌、`全部` 不带计数、
`fmtCount` 三档缩写、分段控件必须在顶栏且不在级别行、滚动渐隐的四种状态、`mask-image` 存在）；
「缓存与呈现方式」再加 1 项：**`app.js` 的 `BUILD` 常量必须等于 `index.html` 的 `?v=`** ——
它随 `?probe=1` 回传，所以远端能直接确认设备加载的是哪一版（不用你念给我听）。

---

## 十、不新增的东西（避免过度设计）

- **不加新接口**：日志只有 `LogManager` 一个来源，不引入 rumble / 文件流式读取；
- **不加新状态源**：筛选、密度、跟随都是页面内状态，不进 `ThemeRuntime`、不落盘（下次进来回到默认：紧凑 + 跟随 + 无筛选）；
- **不加新颜色**：所有取值都来自 `PrimerPalette` 已有角色（`smoke.js` 会拦住新增）；
- **不改 `Logging.kt` 的数据形状**：`LogEntry` 还是那 5 个字段，不加「线程/耗时/堆栈」——真要加，是另一轮的事。
