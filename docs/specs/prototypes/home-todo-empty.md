<!-- 入库副本：正文与 design/home-todo-empty/ 同批维护；按 design/README.md 的约定，正文只写在这里。 -->
> **来源**：原型本体 [`design/home-todo-empty/index.html`](../../../design/home-todo-empty/index.html)（HTML / CSS / JS 全在一个文件里，无外部依赖，双击即开、不需要起服务）。
> **为什么正文放这儿**：原型是一次性草图，跟具体实现绑死、很快过期；而**现状测绘、问题清单、规格与落地顺序**是跨时间有效的结论。
> **维护**：改原型时把这份一并更新，别让两边分叉；落地之后代码才是真源，原型只作历史对照。
> **状态**：**已落地（1.4.31）**。代码是真源：`ui/home/TodoState.kt` / `TodoFoldStore.kt` 与 `HomeScreen.kt` 的 `TodoGroup`；原型只作历史对照。
> **迭代**：第一轮只做空态（见 §四 的 A / B）；第二轮按「去掉黑框 + 做成可折叠容器」重做，落成 **D**，A 的 52dp 状态条被 D 的折叠态吸收，不再单独实现；第三轮施工时修正了两处 API 选择，见 §5.4 / §5.5 的「施工修正」。

# 首页「待处理」：无框分组 + 可折叠 · 设计说明

> **快照说明**：§二 的 `文件:行号` 是 2026-11-25 设计当时测绘的，不随代码演进更新。
> 判断当前行为请以代码与 [`features-design.md` §9](../features-design.md) 为准。

## 一、怎么打开

1. **DSH Web 侧边栏「原型预览」** → 选 `design/home-todo-empty/index.html`；
2. 或直接用文件管理器打开这个 HTML（无外部依赖），改完手动刷新即可。

工具条五组开关：

| 开关 | 取值 | 用途 |
|---|---|---|
| 版本 | 现状（黑框卡片） / 新设计（无框折叠） | 同一状态下的新旧对照 |
| 状态 | 有内容 / 空 / 加载中 / 取数失败 | 复现「三态混一」与各自的画法 |
| 折叠 | 展开 / 折叠 | 也可以直接点卡片标题行；展开是 420ms 带过冲的回弹，收起是 260ms 减速 |
| 行型 | 单行 · 44 / 两行 · 48 | 只影响新设计的展开体；现状固定两行（原 56dp） |
| 主题 | 浅色 / 深色 | 取值全部来自 `ui/theme/ThemePalette.kt` 的 `PrimerPalette` |
| 标注 | 开 / 关 | 红圈 + 编号，对应右侧说明栏逐条 |

页面底部「实测高度」是浏览器按 390dp 宽**量**出来的（量的是整个「待处理」区，含标题行）：

| 形态 | 高度 |
|---|---|
| 现状 · 空（黑框卡片） | ≈115 px |
| 现状 · 有内容 | ≈200 px |
| **新设计 · 折叠** | **44 px** |
| 新设计 · 展开（单行，默认） | ≈179 px |
| 新设计 · 展开（两行） | ≈191 px |

> 数值以页面底部表格为准（随字体度量浮动）；要读的是三件事：折叠态只剩一个标题行、
> 展开态与现状接近、空态不再额外占一张卡片。

**脚本级验收**（不需要浏览器）：`node design/home-todo-empty/smoke.js` —— 用最小 DOM 桩跑
`index.html` 里的真实脚本，模拟点工具条，断言四状态 / 两版本 / 折叠都真的切得过去、
折叠时展开体仍在 DOM（动画的前提）、展开用过冲曲线、点折叠头能展开/收起、折叠头还是 44px、控件 id 对不上会告警。
> 加这个脚本的原因：工具栏 id 与脚本 `getElementById` 一旦对不上，按钮会**静默无反应**
> （浏览器不报错）——2026-11-25 就踩过一次（`seg-state` 被绑成 `seg-data`，「加载中 / 失败」
> 点不动），而当时的桩会给未知 id 自动造元素，正好掩盖了它。

## 二、现状测绘

| 位置 | 现状 | 代价 |
|---|---|---|
| [`HomeScreen.kt:278`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L278) | `SectionHeader(待处理, 铃铛)`：不可点、没有操作 | 标题行只占位，不承担任何交互 |
| [`HomeScreen.kt:504-513`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L504-L513) | `if (unread + reviews + assigned == 0)` → 居中两行灰字，然后 `return` | 空态把整块吞掉，且**没有加载/失败分支** |
| [`HomeScreen.kt:514-519`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L514-L519) | 非空态画三行（通知 / 待审 PR / 指派） | 三行同时是**导航入口** |
| [`HomeScreen.kt:523-557`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L523-L557) | `TodoRow`：`clickable(enabled = count > 0)`，行尾 `›` **无条件**画 | 0 的行不可点却带箭头 |
| [`HomeScreen.kt:499-503`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L499-L503) | 卡片 `border(1.dp, Primer.BorderEmphasis, …)` | 浅色下 `BorderEmphasis` = **纯黑 `#000000`**（见 §五 5.8） |
| [`HomeScreen.kt:183-194`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L183-L194) | `loadCachedCount` 失败 `?: return 0` | **失败 = 0**，与「确实没有」同帧 |
| [`HomeScreen.kt:227-242`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L227-L242) | 三路并行取数（通知预取 + 两个搜索 `total_count`） | 三路各自可能失败，UI 只有一个 `Int` |
| [`HomeScreen.kt:162-164`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L162-L164) | 三个 `Int` 状态，**没有** loading / error 位 | 三态无表达载体 |
| [`HomeScreen.kt:196-200`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L196-L200) | `resumeTick`：切回首页重算（Tab 保活） | TTL 过期那一轮会先落到空态再跳回三行 |
| [`PageCache.kt:81`](../../../app/src/main/java/com/branchbase/cache/PageCache.kt#L81) | 键 `home:unread` / `home:review` / `home:assigned` | 无 host / 账号前缀（见 §七） |
| [`SearchCacheManager.kt:123`](../../../app/src/main/java/com/branchbase/cache/SearchCacheManager.kt#L123) | `"首页" -> 5 分钟` | 失效窗口 5 分钟，切回前台时正好撞上 |
| [`NotificationPrefetcher.kt:88-93`](../../../app/src/main/java/com/branchbase/ui/notification/NotificationPrefetcher.kt#L88-L93) | `warmUp` 返回 `Int?`，**null = 被策略跳过或失败** | 调用点用 `?:` 回退，失败信息丢在这里 |
| [`strings.xml:976-992`](../../../app/src/main/res/values/strings.xml#L976-L992) | 「没有待办」「需要你处理的 PR / 讨论 / 通知会出现在这里」 | 断言式否定 + 说明书式副文案 |
| [`LoadState.kt`](../../../app/src/main/java/com/branchbase/ui/LoadState.kt) | 项目自己的三态契约：「拿 `isEmpty()` 当加载判据 = 把『还没加载』和『确实没有』写成同一句话」 | 首页这块**没有**遵守 |

## 三、问题清单

### 1. 三态被压成两态（功能）

三个计数都是 `Int`，失败落到 `0`；`unread` 那路先试 `NotificationPrefetcher.warmUp(...)`，返回 `null`（计费网络策略跳过 / 失败）时再回退到 `loadCachedCount`，同样失败即 `0`。于是：

| 真实情况 | 界面 |
|---|---|
| 缓存过期，正在回源 | 没有待办 |
| 断网 / 401 / 限流 | 没有待办 |
| 三个请求都成功且确实为 0 | 没有待办 |

后果两条：**断网时首页在断言一句假话**；**切回首页闪一下**（TTL 过期先渲染空态、再跳成三行）。`LoadState.kt` 的原话是「判据必须是显式的加载标志，不能从『列表空不空』反推」——这里正好反推。

### 2. 空态把三个入口一起吞掉（功能）

非空态时三行是入口：通知页、`review-requested:@me state:open type:pr`、`assignee:@me state:open`。空态整段消失后，**「待我审查」「分配给我」在首页再无入口**（它们只活在这张卡片里）。而「计数为 0」不等于「不需要进那个页面」。

### 3. 形态与内容量不匹配（视觉）

这张卡片的 1dp 描边是 `Primer.BorderEmphasis`，浅色板里就是**纯黑**。它是首屏除正文外最重的线，而这块恰恰是「可以没有内容」的区域——层级反了。空态还把整张卡片换成两行居中灰字，看起来像一张可点的卡片、实际是死区；「进行中」用同款黑框，两块并排像两个并列的正式容器，抢走下方真正有内容的「常用仓库」。

### 4. 文案

- 「没有待办」是**断言式否定**；英文 `Nothing to do` 更像「没事干」；
- 副文案是**说明书**（「会出现在这里」），不是下一步；列举顺序 `PR / 讨论 / 通知` 还和三行顺序不一致；
- 11.5sp + `TextTertiary`（浅色 `#6A6D7C`）压在可读性边缘。

### 5. 没有出口

空态不可点；首页没有下拉刷新；标题行没有刷新按钮（`SectionHeader` 未传 `onAction`）。用户看到「没有待办」想确认一下，**无处可去**。

### 6. 顺带：0 的行仍带箭头

`TodoRow` 的 `›` 无条件绘制。真机图里「0 个 PR」「0 个讨论」带着箭头却点不动。

## 四、方案对比

| | A 紧凑条（第一轮） | B 三行常驻（第一轮） | **D 无框折叠（本轮结论）** |
|---|---|---|---|
| 形态 | 52dp 一行状态条 | 顶部一句 + 三行 | 44dp 折叠头 + 可选展开体，整体浅底无描边 |
| 空态高度 | ≈52dp | ≈200dp | **44dp**（折叠态） |
| 有内容 | 同现状 | 同现状 | 44dp + 三行（默认展开） |
| 三个入口 | 0 时不可达 | 全留 | 折叠时不可见，展开可点（一次点击） |
| 三态区分 | 骨架 / 重试 | 同上 | 摘要承担状态，体里给骨架 / 重试 |
| 黑框 | 去掉 | 去掉 | **去掉**（改浅底 + 圆角） |
| 额外收益 | — | — | 首屏可按需收起；同一套容器可给「进行中」复用 |

**为什么 D 胜出**：D 的折叠态就是 A 想要的「一行 52dp」，于是**空态与折叠合并成一件事**——不需要为「没事了」再设计一张卡片；同时把「标题行」从死区变成交互入口，顺带解决了「没有出口」。B 保住的入口在 D 里也没丢（展开就有）。A 不再单独实现。

## 五、D 规格：无框分组 + 折叠头

### 5.1 线框

折叠（空态即此形态）：

```
┌──────────────────────────────────────┐
│ 🔔 待处理              都处理完了  › │   44dp，neutralFill，圆角 10dp，无描边
└──────────────────────────────────────┘
```

展开（有内容）：

```
┌──────────────────────────────────────┐
│ 🔔 待处理               1 项待办  ⌄ │   44dp，整行可点
├──────────────────────────────────────┤
│ ●  未读通知                       ›  │   可点行才有 ›
│    1 条                              │
├──────────────────────────────────────┤
│ ○  待我审查                          │   0 的行灰、无 ›
│    0 个 PR                           │
└──────────────────────────────────────┘
```

### 5.2 规格表

| 项 | 值 | 依据 |
|---|---|---|
| 容器 | `Primer.neutralFill` 底、`RoundedCornerShape(10dp)`、左右 16dp、**无描边** | 与「有框卡片」的层级分开（§5.8） |
| 折叠头 | `min-height 44dp`、整行可点、左右内边距 12dp（与行对齐） | 44dp 达 WCAG 2.5.5（AAA）与 iOS HIG 44pt；Material 的 48dp 只是建议值 |
| 标题 | 铃铛 18dp + 13sp / Bold / `TextPrimary` | 沿用现区块标题 |
| 摘要 | 13sp；数字 600 `TextPrimary`，量词/短语 `TextTertiary`；靠右、`maxLines = 1` | 折叠时它是唯一信息 |
| 箭头 | `chevron-down` 16dp / `IconSecondary`；展开 0°、折叠 −90° | 用与展开同一条弹簧（§5.4），不要 tween |
| 展开体 | `border-top 1px borderEmphasis@40%`；行高见下两行 | 原 56dp 一行太肥：3 行吃掉 170dp |
| 行 · 单行型（默认） | 44dp：圆点 22 / 标题 13sp·17 / 计数 12sp `TextTertiary` **靠右** | 与折叠头同高，整块是 44 的整数倍（44 → 44×4） |
| 行 · 两行型 | 48dp：上下 8dp，标题 13/17 + 计数 11/14 | 保留给「计数也要扫一眼」的场景；从 56dp 压下来 |
| 行尾 | 仅 `count > 0` 的行画 `›` | 修掉问题 6 |
| 结构 | 头 + 体是**同一个容器**；展开体不再有独立背景/圆角 | 折叠时是一根 44dp 的条 |

### 5.2 附：为什么压、压到多少

| | 原来 | 现在 |
|---|---|---|
| 行高 | 56dp（上下 11dp + 17/14 两行） | **44（单行）/ 48（两行）** |
| 圆点 | 26dp | 22dp |
| 展开体（3 行） | ≈213dp | **≈179 / ≈191** |
| 头 : 行 | 44 : 56（头比行矮，块看着「散」） | 44 : 44（单行型，1:1 节奏） |

判断依据：这一块的信息量是「三行 × 一句话 + 一个数」，56dp 的行高在首屏上与折叠头（44）不成比例；
单行型把计数移到右侧后，行高与头对齐，整块从「三张厚卡片」变成「四条等宽横带」。

**施工时补的一条**：头与展开体之间也要有分隔线（原型里是 `.fold-inner` 的 `border-top`）。少了它，
展开后「标题 + 三行」读起来像一段连续文本，而不是一个可折叠的块。同一套容器与分隔线也用在
「进行中」上（`HomeGroupCard`，头不可点、无箭头），两块之间留 10dp。

### 5.3 折叠规则

| 情形 | 默认 | 说明 |
|---|---|---|
| 全 0（Empty） | **折叠** | 「都处理完了」，不占一张卡片 |
| 有内容（Ready） | 展开 | 待办要显眼，不能被收起藏掉 |
| 加载中（Loading） | **折叠**（有记忆时沿用记忆） | 首帧还不知道结果：不折叠的话，空态用户每次启动都会先弹开一屏骨架再收拢 |
| 取数失败（Failed） | **强制展开** | 重试是唯一恢复入口，不能藏在折叠里 |

- **记忆按账号（已定）**：键 `home_todo_collapsed`，与 `home_frequent_repos` 同法**按账号分桶**——否则换账号会继承别人的收起状态；用户手动切换后写盘，之后以记忆为准；只有 Failed 会临时覆盖记忆，恢复后回到记忆值。
- **折叠不冻结取数**：`resumeTick` / 预取照常跑，摘要随计数更新；数字变化**不**自动展开（不打断阅读）。
- 摘要与底栏徽标同源（`NotifSnapshot`），不另算一份。

### 5.4 动效：折叠要有回弹（落地版）

展开/收起用 `AnimatedVisibility`：

| 对象 | 展开 | 收起 |
|---|---|---|
| 展开体高度 | `expandVertically(animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f), expandFrom = Alignment.Top)` | `shrinkVertically(animationSpec = tween(260))` |
| 内容 | `fadeIn(tween(120))` | `fadeOut(tween(150))` |
| 箭头 | `animateFloatAsState(0f, spring(dampingRatio = 0.5f, stiffness = 500f))` | 同一条弹簧到 −90° |

- 回弹来自**展开体高度**的 spring（`dampingRatio < 1` 才有过冲），不是拿缩放伪装；
- `expandVertically` 默认 `clip = true`，过冲期内容不会盖到「常用仓库」上；
- 展开只在**用户点击**时发生（失败态强制展开由状态驱动，不走点击）；自动刷新不改折叠态、不触发动画；
- 降级：系统「动画时长 0」时 Compose 直接落位，不需要额外判断。

> **施工修正（2026-11-25）**：第一版写的是容器 `animateContentSize(spring)`。落到代码后发现不成立 ——
> 折叠必须 `if (!collapsed)` 才组合展开体，而 `animateContentSize` 只动容器尺寸、内容会瞬间出现/消失，
> 收起时变成一个「空盒子在缩」。`AnimatedVisibility` 在 exit 期间保留内容，才是对的那个；
> 回弹仍来自 spring（参数同上），手感与原型一致。

原型里用 Web Animations 表达同一条曲线：展开 `cubic-bezier(.2, 1.45, .34, 1)` / 420ms，
收起 `cubic-bezier(.4, 0, .55, .2)` / 260ms；折叠体常驻 DOM（折叠时 `height: 0`），
否则重建节点会让过渡失效、页面也会被滚回顶部。裁切放在**内层** wrapper 上、外层只做高度动画，
否则标注圈和编号会被自己裁掉（Compose 侧同理：`clipToBounds` 要遮内容，不能遮装饰）。

### 5.5 无障碍

- 整个头是一个语义节点：`clickable(onClickLabel = 展开/收起)` +
  `semantics { stateDescription = 已折叠/已展开; expand(标签){…} / collapse(标签){…} }`；
- TalkBack 读「待处理，1 项待办，已折叠，双击展开」；
- 摘要里的数字不单独成节点；
- 44dp 达 WCAG 2.5.5（AAA，44px）与 iOS HIG（44pt）；未采用 Material 建议的 48dp 是刻意的
  （更紧凑，整行宽度远大于高度，误触风险低）；折叠不改变点击区域大小。

> **施工修正**：Compose 1.10.3 的 `SemanticsProperties` 里**没有** `expanded`（`SemanticsActions` 只有
> `Expand` / `Collapse`），所以上面这组 `stateDescription` + 动作就是落地版；`expanded` 那行写法作废。

### 5.6 与空态、三态的关系

- **空态 = 折叠态**：不再单独实现 A 的 52dp 状态条；
- 展开后仍能看到三行 0（入口没丢，只是一步之外）；
- 四态映射：

| 状态 | 折叠头摘要 | 展开体 |
|---|---|---|
| Loading | 读取中… | 3 行骨架（行高照实） |
| Empty | 都处理完了 | 三行 0（灰、无箭头） |
| Failed | 暂时取不到 | 失败行 + 重试（强制展开） |
| Ready | N 项待办 | 三行（0 行灰、无箭头） |

### 5.7 文案

| 资源（建议名） | 中文 | 英文 |
|---|---|---|
| `home_todo_summary` | `%1$s 项待办`（plurals） | `%1$s to do` |
| `home_todo_empty` | 都处理完了 | All caught up |
| `home_todo_loading` | 读取中… | Loading… |
| `home_todo_failed` | 暂时取不到 | Can't load |
| `home_todo_failed_hint` | 网络似乎不通，数字可能不是最新的 | The numbers may be out of date |
| `home_todo_collapse` / `home_todo_expand` | 收起 / 展开（读屏用） | Collapse / Expand |

改完跑 `python3 tools/i18n/check-i18n.py`（当前基线：`:app` 1801 条、`en` 100%），并同步 [`strings.tsv`](../../../tools/i18n/strings.tsv)。数量词必须走 `plurals`（中英量词不同形）。

### 5.8 边框：为什么这张卡片不再用 `borderEmphasis`

- 这张卡片的描边是 [`HomeScreen.kt:502`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L502) 的 `border(1.dp, Primer.BorderEmphasis, …)`；浅色板里 `borderEmphasis = #000000`（[`ThemePalette.kt:178`](../../../app/src/main/java/com/branchbase/ui/theme/ThemePalette.kt#L178) 刻意写死，注释写明「首页 / 个人页三 Tab / 设置页的卡片框与分隔线」共用）。
- 深色板里它是 `#30363D`，本来就不重 —— 也就是说「黑框」只是浅色模式的问题。
- **本设计只在这一个区块去掉描边**，改用 `neutralFill` 底。**不改全局 token**：改它等于同时改「进行中」卡片、个人页三 Tab、设置页分隔线，影响面与验收成本都不是这个改动该背的。
- 连带建议：同屏的「进行中」卡片在 [`HomeScreen.kt:566`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L566) 用的是同一条描边；若这次只改「待处理」，一屏会出现「无框分组 + 黑框卡片」两种语言 —— 落地时二选一：①把「进行中」一起换成同款无框分组（推荐，容器组件本来就该共用）；②先只改「待处理」，在 `VERSION-NOTES` 记一笔待统一。

### 5.9 深色

- `neutralFill`：深色 `#161B22` on `#0D1117`，层次清楚；浅色 `#F7F7F9` on `#FFFFFF` 很淡 —— 靠 44dp 高度、圆角与周围留白识别，**不要**加描边补救；
- 分隔线 `borderEmphasis@40%`，深色下自然变成 `#30363D@40%`；
- 摘要数字用 `TextPrimary`（浅 `#050505` / 深 `#E6EDF3`），在两种主题下都是那一行最重的字。

## 六、三态与判定

计数从 `Int` 改成 `Int?`（`null` = 不知道），折叠状态也收进同一个纯函数，便于单测：

```kotlin
enum class TodoState { Loading, Empty, Failed, Ready }

// loading 优先，与公共 loadStateOf 的写法一致（手上可能还有旧值）
fun todoStateOf(loading: Boolean, unread: Int?, reviews: Int?, assigned: Int?): TodoState = when {
    loading -> TodoState.Loading
    listOf(unread, reviews, assigned).any { (it ?: 0) > 0 } -> TodoState.Ready
    unread == 0 && reviews == 0 && assigned == 0 -> TodoState.Empty
    else -> TodoState.Failed
}

// 默认折叠：只由「状态 + 记忆」决定，不读 UI
fun todoCollapsedByDefault(state: TodoState): Boolean = when (state) {
    TodoState.Empty -> true
    TodoState.Failed -> false      // 强制展开，重试是唯一恢复入口
    TodoState.Loading, TodoState.Ready -> false
}

// 记忆优先；Failed 忽略记忆
fun todoCollapsed(state: TodoState, remembered: Boolean?): Boolean =
    if (state == TodoState.Failed) false else remembered ?: todoCollapsedByDefault(state)
```

为什么新造 `TodoState` 而不给 `LoadState` 加 `Failed`：`LoadState` 的契约是「三态 + 容器固定高」，`loadStateOf` 的调用点铺在仓库页、面板、弹窗一堆地方；而这里的「失败」还带一个动作（重试），不是纯状态枚举该装的东西。若将来别的页面也要失败态，再统一提升。

部分成功（例如 `unread = 0`、`reviews = null`）：保持 `Ready`，未知那行文案显示 `—` 且不可点（不猜数字），摘要按已知的非零值算。

## 七、边界与风险

1. **回前台那一闪**：TTL 5 分钟，`resumeTick` 重算时若沿用折叠状态并显示骨架，就不会出现「空 → 三行」的跳变；Loading 期间**不要**改折叠状态。
2. **跨账号 / 跨 host 的缓存键（既有隐患，不属本设计）**：`home:unread` 等键没有 host / 账号前缀，`search_cache.db` 也是单库。切账号后 5 分钟内可能读到**上一个账号的未读数**。若这次动 `loadCachedCount`，顺手把键改成带 `host` + `login` 前缀；否则另开一条修。
3. **折叠记忆按账号**（已定，见 §5.3）：键漏了账号维度的话，换账号会继承别人的收起状态。
4. **英文长度**：摘要 `1 item to do` 比中文长；`maxLines = 1` + Ellipsis，标题与摘要之间用 `Spacer(weight)` 撑开，不要用固定宽度。
5. **位移归因**：用户点折叠导致的位移是**预期**；取数导致的位移**不允许**（§七.1）。两者别混在一起调。
6. **两步才能进「待我审查 / 分配给我」**：折叠时要点一次头展开、再点行。可接受——0 时本来也不需要，且有需要时摘要会显示「N 项待办」；通知另有底栏入口。
7. **与「进行中」的卡片语言不一致**：见 §5.8，落地时决定是否一起改。
8. **失败时摘要不能写成「都处理完了」**：`Failed` 与 `Empty` 必须分档，这是整个改动的地基。

## 八、落地清单（1.4.31 已按此落地）

| # | 文件 | 改什么 | 备注 |
|---|---|---|---|
| 1 | [`HomeScreen.kt:162-164`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L162-L164) | 三个 `Int` → 三个 `Int?` + `loading` | 别用 `0` 当哨兵 |
| 2 | [`HomeScreen.kt:183-194`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L183-L194) | `loadCachedCount` 返回 `Int?`，失败 `null`；`warmUp` 的 `null` 继续回退 | 两处失败语义合并 |
| 3 | 新增 `ui/home/TodoState.kt` | `TodoState` + `todoStateOf` / `todoCollapsedByDefault` / `todoCollapsed` | 钉子 `TodoStateTest`（8 例：四态 + 记忆优先 + Failed 覆盖记忆 + 部分 null + loading 优先） |
| 4 | [`HomeScreen.kt:278`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L278) + `:491-521` | `SectionHeader` + `TodoCard` 合并成 `TodoGroup`（折叠头 + 体）；头 `stateDescription` + `expand`/`collapse` 动作（§5.5 施工修正） | 头 44dp，整行可点 |
| 5 | [`HomeScreen.kt:499-503`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L499-L503) | 去 `border(BorderEmphasis)`，改 `background(Primer.neutralFill)` | 见 §5.8 的 scope |
| 6 | [`HomeScreen.kt:523-557`](../../../app/src/main/java/com/branchbase/ui/home/HomeScreen.kt#L523-L557) | `›` 改成按 `enabled` 画 | 修掉 0 行箭头 |
| 7 | 新增 `home_todo_collapsed` 存储 | 按账号分桶（已定）；手动切换时写 | 参考 `FrequentRepoStore` |
| 8 | 文案 | `strings.xml` + `values-en` + `strings.tsv`，数量词用 `plurals` | 跑 i18n 检查 |
| 9 | [`features-design.md` §9](../features-design.md) | 落地后补一行：可折叠 + 四态 + 无框 | 文档跟着行为走 |

**验收口径**（真机，飞行模式可复现前两条）：

1. 飞行模式进首页 → 摘要「暂时取不到」+ **自动展开**并显示「重试」，不是「都处理完了」；
2. 关掉飞行模式点重试 → 原地变成三行或折叠态，下方「常用仓库」不跳动；
3. 全 0 首次进入 → 折叠，摘要「都处理完了」；点一下展开，仍是三行 0（灰、无 `›`）；
4. 手动收起后切 Tab 再回首页 → 保持收起，摘要随新计数更新（不自动展开）；
5. 折叠头点按有反馈、箭头旋转 90°、展开/收起 220ms，连续快速点击不闪白；
6. 深色 / 英文 / TalkBack 各一遍：读屏念「待处理，N 项待办，已折叠，双击展开」。

## 九、不做什么

- **不加下拉刷新**：刷新时机已由 `resumeTick` 定义，失败态有重试。
- **不给空态配插画 / 大图**：首屏预算优先给「常用仓库」。
- **不改全局 `borderEmphasis`**：那是另一个改动（见 §5.8）。
- **不做手势折叠 / 长按折叠**：点击足够，长按留给将来的快捷操作。
- **不让折叠阻止取数**，也不因计数变化自动展开。
- **不新增颜色角色**：全部用既有 `Primer` 令牌。

## 十、待拍板

1. ~~失败时「强制展开」还是「折叠态摘要右侧直接放重试」？~~ 已按原型落地：**强制展开**。
2. 同屏的「进行中」卡片是否一起换成无框分组？—— **已做**（1.4.31，共用 `HomeGroupCard`）。

> 已定：折叠头 **44dp**（2026-11-25，理由见 §5.2 / §5.5）；折叠记忆**按账号**（2026-11-25）；行型默认**单行 44**（2026-11-25）；
> 展开/收起**带回弹**（2026-11-25，参数见 §5.4）。
