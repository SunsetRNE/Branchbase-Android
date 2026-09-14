<!-- 来源：根 `README.md`（2026-09 拆分）。正文未做删改，仅规整标题（去 emoji 前缀、加章节编号）与空行。
     章节顺序按依赖排（配色 → 深色主题 → 动效），与 README 原顺序不同。 -->
# 界面规格（配色与弹层 / 深色主题 / 动效）

跨页面、每个页面都要遵守的三份规格：**颜色只有一个真源**（`ui/theme/Color.kt` 的 `Primer` 色板）、
**主题是「色板 + 角色」而不是一堆写死的颜色**、**动效分页面级与元素级两层**。三份规格都带源码级的
测试钉子（`ThemeConvergenceTest` / `ThemeContrastTest` / `EditorPaletteTest` / `WorkflowLogThemeTest` …），
改之前请连同测试一起看。

顺序按依赖排：**1 配色**（底层真源）→ **2 深色主题**（在色板之上）→ **3 动效**（消费上面的语义角色）。

---

## 1. 配色与弹层（单一真源）

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

---

## 2. 深色主题

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

### 第二轮收敛：架构落地时漏掉的那些

架构那一轮只搬走了「成体系的」硬编码，页面上还留着 20 余处零散的。这一轮的判据是
**深色下到底读不读得出来**，而不是「有没有写 Color(0x…)」：

| 类 | 现场 | 例子 |
|----|------|------|
| 读不出来 | 写死的深色字压在**主题**深底上 | 差异行未变更正文 `#24292F`（底是 `Transparent`）；`预发布` 徽章 `#0A4E9B` 压 `InfoSurfaceSoft`；`StatusChip` 的 A / D 两个分支；安全警报横幅里的 `TextPrimary` 压在死粉底上 |
| 读不出来 | 反过来：**主题**亮字压在死浅底上 | `WarningText` 压 `#FFF8E5` / `#FFF8C5`；`Green500` 压 `#EAF9F0` |
| 浅色补丁 | 前景与底色**都**写死浅色系 | 账户状态胶囊、工作区提示条；深色下仍可读，只是页面上多一块亮斑 |
| 状态色照抄浅色色板 | 写的就是浅色色板的 `success` / `danger` / `warning` | 工作流的 `runStatusColor`、搜索页的 PR 徽章 |

顺带统一了一件事：**胶囊描边一律走 `Primer.Border`**（与「配色与弹层」一节的约定一致），
不再按状态各配一条浅色边。

收敛后 `ui/theme/` 之外只剩三类写死色，且都是**有意为之**（见「已知取舍」）。
`ThemeConvergenceTest` 钉住两条源码级约束：

1. **浅色专属取值只允许出现在色板定义里**（`ui/theme/` 之外一律不许再写）；
2. **不许 6 位十六进制** —— `Color(0xEAF9F0)` 这种写法 Compose 按 ARGB 解释，少一位 alpha 就是
   alpha 0 = **完全透明**：「颜色写了，但屏幕上没有」，比写错颜色更隐蔽。本轮就抓到一处
   （决策卡片的「推荐」标签，它的透明底一直被旁边的「危险」标签衬得很奇怪）。

### 文字色 ≠ 填充色（WCAG 对比度）

色板里有一组**成对**的角色：品牌填充色（`Green500` / `Red500` / `Orange500` / `Blue500`）
与对应的**文字**色（`SuccessText` / `SuccessTextStrong` / `DangerText` / `WarningTextStrong` /
`AccentText`）。两者不可互换：

| 场景 | 用哪个 | 反例 |
|------|--------|------|
| 填充（按钮底、圆点、进度条、图标 `tint`） | `Green500` / `Red500` / … | — |
| **文字**（标签、状态文案、链接） | `SuccessText*` / `DangerText` / `WarningText*` / `AccentText` | 拿 `Red500` 当文字：压 `DangerSurface` 只有 **4.00**（AA 要求 4.5） |

「文字色」在浅色下必须比对应填充色**更深**（深色下则按约定塌回品牌填充色，两者同值）。
色板此前漏了 `dangerText`，导致红色文字无处可取、只能退化成 `Red500` ——
浅色下账户状态胶囊 6.95→4.00、安全警报标题 5.10→3.61，都掉到 AA 以下。
现在补齐为浅 `#9E1C24` / 深 `#F85149`（浅色取值就是收敛前那个值，等于把原观感拿回来）。

`ThemeContrastTest` 钉住三条：色板必须提供 `DangerText`；**浅色下文字角色必须比对应填充色更深**；
以及具体几个页面的文字不得回退成填充色。

### 图标去灰：`iconPrimary` 取纯黑 / 纯白

常态图标（`Primer.IconPrimary`）浅色下是 `#000000`、深色下是 `#FFFFFF`，比正文
（`#050505` / `#E6EDF3`）更极端。图标是**图形**不是长文本，21:1 的对比不构成阅读负担，
而原来的中灰 `#525560` / `#B1BAC4` 在浅色下有种「发灰、像没加载出颜色」的观感。

**只有常态图标换**，边界是刻意的：

| 不动的 | 为什么 |
|--------|--------|
| `iconSecondary`（次要 / 禁用图标） | 变黑会与常态图标合并，丢掉「未选中 / 禁用」这层语义 |
| 正文与次级文字 | `textSecondary` / `textTertiary` 的「灰」就是层次本身，全塌成纯黑会让长列表发糊 |
| `border` 描边 | 浅 `#BFC1C9` 1.80:1 / 深 `#30363D` 1.55:1 是刻意做弱的；拉到纯黑/纯白会让浅色退回 wireframe、深色出现一屏「发光矩形」 |
| 灰底填充 | 高对比硬边是成套风格，单独拉黑会导致硬线与软面互相打架 |

改动只落在两份色板的 `iconPrimary` 各一行，52 处 `Primer.IconPrimary` 调用点一行未改
（这是色板 + 角色架构的前提）。

### 深色下必须一起换的部分（Compose 管不到的）

- **状态栏 / 导航栏图标明暗 + 窗口底色**：`BranchbaseTheme` 的 `SideEffect` 里跟着主题设置，
  否则深色页面顶部会压一条白条；
- **正文页的 WebView**：`github-markdown-light.css` 是浅色主题，深色时额外注入 `README_DARK_CSS`；
- **沉浸式翻译的页面脚本**：`translate.css` 增加 `body.bb-dark` 段（译文卡片 / 悬浮球 / 工具面板 / 提示），
  `dark` 标记随设置注入给 `window.__bbTranslate`；
- **代码高亮 / 贡献图**：`CodeSyntax` 与 `ProfileColors` 也是角色化的（深色用 GitHub dark 的语法色）；
- **代码视图的正文色**：文件页只读预览此前硬编码 `Color(0xFF24292F)`（浅色主题的取值），
  深色下就是「深灰字压深色底」—— 与同一页的编辑态（`:editor` 的代码编辑器，跟随主题）完全不一致。
  现在这个视图改用 `Primer.TextPrimary`（与搜索页代码块同一约定），`FileViewerThemeTest` 钉住本页不得再出现该硬编码。

### 已知取舍

- 启动瞬间（Compose 首帧前）窗口底色仍是系统主题，深色用户可能看到一帧浅色 —— 要彻底消除需要
  在 `values-night` 里再放一份主题，代价是「用户手动锁浅色而系统是深色」时会反过来闪一下；
- `LogScreen`（应用内日志查看器）的「原始日志」卡片是**固定深色**的：浅色主题下它是一块黑卡片，
  这是有意的终端观感，未纳入角色化；
- 语言品牌色（`LanguageColors`）主题无关（GitHub 两种主题用的是同一套），所以写死是对的；
  但**真源有三份** —— `Color.kt` 的 `LanguageColors.of()` 与 `SearchScreen` / `HomeScreen`
  里的两份副本。这属于「单一真源」问题，尚未收敛；
- **主按钮白字压在深色 `accent`（`#1F6FEB`）上 = 4.08**，低于 AA 的 4.5。这是**全站既有特性**
  （`background(Primer.Blue500)` + `Color.White` 到处都是，GitHub 网页版深色主按钮同样如此），
  不是某一页引入的。要严格达标可改用色板里已有、但**目前零调用**的高强调组合
  `Primer.Gray900` + `Primer.OnEmphasis`（浅色深底白字 / 深色浅底深字，两端都在 16 以上）；
- 全项目仍有约 30 处 `color = Primer.Red500`（拿填充色当文字）。其中绝大多数压在 canvas 上
  （浅色 4.57 / 深色 5.65，**达标**），属于「语义不够准但不影响可读」；收敛只处理了
  压在 `*Surface` 浅底上、对比度低于 AA 的那几处。下一轮可用「文字不得用填充色角色」这条
  规则统一扫一遍；
- 另外两条测试是**黑名单**（`ThemeConvergenceTest` 只拦 29 个已知浅色取值 + 6 位十六进制），
  **全新的写死色值拦不住**；`ThemeContrastTest` 也只钉住具体几处。真正的白名单化
  （任何 `Color(0x…)` 都必须落在显式许可清单里）成本更高，暂未做。

---

## 3. 动效（两层规格：页面 / 元素）

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
| 有层级的推进 / 返回（`PageSwitcher`） | 水平滑动 1/4 屏 + 淡入淡出；进场 300ms 减速、退场滑动 220ms 加速、退场淡出 130ms | 子页有前后关系：前进从右进、返回向右出。登录流程内部也按层级（欢迎 0 → 模式介绍 1 → 密钥填写 / 授权中 2 → 主界面 3） |
| 同级切换（`TabSwitcher`，含仓库页切 Tab） | 淡入淡出 + 轻微上浮 2%；220ms，退场淡出 140ms 早收 | 同级没有前后关系，横向滑动会暗示错误的层级 |

「丝滑」这件事上真正起作用的是三条，都在 `PageMotion` 里：

1. **两条曲线不一样**：进场 `LinearOutSlowInEasing`（减速，起步快收尾缓）、
   退场 `FastOutLinearInEasing`（加速，越走越快）。原先进出都用 `tween` 的默认对称曲线
   `FastOutSlowInEasing`，**起步那一拍是慢的** —— 手指已经离开屏幕、画面却黏着不动，
   这是「不够跟手」的主因。取向与设计稿的 `cubic-bezier(0.2, 0.8, 0.2, 1)` 一致；
2. **退场淡出比滑动早收**：滑动走满 220ms，透明度 130ms 就到 0。两张整页同时在屏上、
   又各是半透明时，中段会叠成「两张都看得见、又都看不清」的糊图（长列表页尤其明显）；
   旧页先退干净，重叠期就只剩新页在动；
3. **切 Tab 会存住原来的位置**：`TabSwitcher` 用 `rememberSaveableStateHolder()` 按目的地
   保存 `rememberSaveable` 状态（列表滚动位置、筛选、展开态）。不加的话，
   退场动画一结束旧内容就移出组合树，切走再切回来列表回到顶部 —— 动画本身没问题，
   但整体仍然「不丝滑」。`PageSwitcher` **没有**加：它的路由 key 是带 payload 的 data class
   （如 `MainRoute.Repo(RepoDeepLink)`），不是所有都能进 Bundle，强行加会在存盘时崩。

`PageSwitcher` / `TabSwitcher` 还有一个 `contentKey`（默认「状态本身」= 状态值一变就算换页）。
带 payload 的路由如果 payload 会在同一页内变，要传稳定身份（如登录流程传 `{ it::class }`），
否则同页刷新会白播一次换页动画。

约定：
- **页面状态要收敛成一条路由**（枚举 / data class），交给 `PageSwitcher` 渲染 ——
  它同时承担「渲染哪个页面」和「动画期间把旧状态原样交给退场内容」两件事。
  用 `if (x != null) { 页面(); return }` 的老写法做不到后者：状态一清空，
  退场中的页面会先变成空白、再淡出（看起来像闪烁）；
- **路由要把页面数据带上**（如 `RepoRoute.Issue(number)`），不要在页面里现读可变状态；
- 返回键**按当前路由分派一个**（不要每个页面各挂一个 `BackHandler`）：
  动画期间新旧两个页面会同时存在，两个 BackHandler 会抢同一个返回事件；
- **导航栏不许长在切换器里**：带底部导航的骨架一律走 `NavigationShell`
  （主界面 / 仓库页 / 个人页三处），栏在 `PageSwitcher` **外面**。
  放在里面的话，同级切换那 2% 的垂直位移会连着整条栏一起播 —— 旧栏上移、新栏上浮、
  两栏错位叠着，就是「切页面时导航栏上下跳」；同理，**Tab 维度也不进外层路由**
  （`MainRoute.Tabs` / `RepoRoute.Tab` / `ProfileRoute.Main` 都是 `data object`），
  切 Tab 由内容区自己的 `TabSwitcher` 负责淡入淡出。
  这条也是后续换悬浮 / 玻璃形态的前置条件（栏要能不占位、内容从它下面穿过）。

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

---

> **相关文档**：[`screens-design.md`](screens-design.md)（页面重绘里引用了本规格）·
> [`prototypes/theme-neutral-preview.md`](prototypes/theme-neutral-preview.md)（中性色「去灰」的取值与结论）·
> [`settings-design.md`](settings-design.md) §14（对比度审计的完整账目）。
