<!-- 入库副本：正文与 design/settings-redesign/README.md 一致，未做删改，仅加本段头 + 一处路径重写（见下）。 -->

# 设置页重设计原型 · 说明与落实方案

> 目录：`design/settings-redesign/`（`/design/` 在 `.gitignore` 里：原型草图不入库）
> 本文档的**入库副本**：`docs/specs/prototypes/settings-redesign.md`（改完这里请同步那份）
> **规范正本**：[`docs/specs/settings-design.md`](../settings-design.md) —— 本文只讲这份原型，
> 规范讲的是**所有**设置页必须遵守的约定。两者是一对：**规范说「必须怎样」，原型是「长这样」**。

---

## 一、交付物与查看方式

| 文件 | 作用 |
|---|---|
| `index.html` | 可点原型（设置主页 + 6 个二级页 + 危险确认面板 + 设计说明抽屉） |
| `style.css` | 令牌与组件（明暗两套；**设计稿区域 0 个写死色值**） |
| `app.js` | 渲染与交互；4 套 mock 数据；布局自检；probe 回传 |
| `smoke.js` | Node 烟测：**151 项**断言，含「内容层」与「规范层」两层 |
| `theme-audit.js` | 对比度审计：**70 组**真实叠放，明暗各 35 组 |

```bash
node design/settings-redesign/smoke.js design/settings-redesign        # 期望：全部通过 ✓（退出码 0）
node design/settings-redesign/theme-audit.js design/settings-redesign  # 期望：失败 0 组（退出码 0）
```

**工具条**（不属于设计稿）：

| 按钮 | 作用 |
|---|---|
| `ⓘ` | 打开设计说明抽屉 |
| `◐ 浅色 / 深色` | 切主题（三档的「跟随系统」在设置里演示） |
| `⌥ 默认 / 新装 / 禁用 / 长文本` | 切 4 套 mock 数据（新装 = 全空；禁用 = 提交模式为「单个文件」；长文本 = 排版压力） |
| `⌗ 正常 / N 处溢出` | 布局自检：点一下把横向溢出的元素用红框标出来 |
| `v20260914a` | 构建号 —— **它与 `index.html` 的 `?v=` 和 `app.js` 的 `BUILD` 必须一致**（烟测钉住） |

**入口**：设置主页顶栏右上角的 `☰` → **行型目录**，是规范 §4.1 的活参照。

---

## 二、这份原型演示的不是像素，是「规范能否被机械执行」

与 `log-redesign`（那份的目标是「把 196dp chrome 压到 110dp」）不同，
**设置页不缺空间，缺一致性**。所以这份原型的价值在于：把规范条款变成**页面里看得见、烟测里拦得住**的东西。

| 规范条款 | 原型里的可见证据 | 烟测断言 |
|---|---|---|
| §4.1 行型是**封闭集合**（6 种） | 每一行都带 `data-row="nav\|switch\|choice\|action\|danger\|info"`；6 个构造器 | 行数 == `data-row` 数；源码里恰好 7 处 `class="row`（6 行型 + 禁用态） |
| §5.2 主题**不再「点一下循环」** | 三档分段控件，全部在屏上、一步直达、可撤销 | 三档 `data-val` 都在；没有 `data-act="cycle"`；没有 `.next()` |
| §5.3 开关**立即生效**、不配「保存」 | 点开关马上改状态 | 主页没有「保存」按钮；切换后模型立即变 |
| §5.5 输入框**只在 L2**、反馈**贴住输入框** | 主页无 `<input>`；代理页的校验结果在输入框正下方 | 主页无 `<input>`；代理页有 |
| §6.3 禁用行**说明原因 + 给出去开启的路** | 「本地仓库」灰掉时写「开启需先将『提交模式』设为…」+「去设置」按钮 | 有原因文案、有 `data-act="go"`；CSS 里原因 `opacity: 1` |
| §6.5 值列**脱敏** | 长代理地址在列表里只显示 `host:port` | `maskProxy()` 4 条；长文本数据下 `username:password` 不出现 |
| §7 危险操作**点名对象 + 三条正文 + 动词按钮** | 确认框标题「退出登录 octocat」 | 标题 = 动词+对象名；正文含「会发生什么/影响范围/不可撤销」；按钮不是「确定」 |
| §6.1 用语表 | 全树只有「已开启 / 未授权 / 未设置 / 未检查」 | 4 套数据下都搜不到「打开 / 启用 / Loading / 未知」 |
| §9 令牌纪律 | 设计稿区域无写死色值 | `app.js` / `index.html` 0 个十六进制；组件区 0 个 |

---

## 三、现状 → 新设计

### 3.1 八套行组件 → 六种行型

现状同一个「一行设置」被实现了 8 次，分散在 8 个文件：

| 现状组件 | 位置 | 新设计 |
|---|---|---|
| `SettingsItem` | `SubPageScreens.kt:704` | `NavRow`（薄封装，签名可先不变） |
| `ModeOptionRow` | `SubPageScreens.kt:627` | 保留，作为 **L2 单选行**（规范 §5.4） |
| `SwitchRow`（**私有**） | `TranslateSettingsScreen.kt:383` | 提到公共件 `SwitchRow` |
| `LocalRepoEntry` | `SubPageScreens.kt:658` | `NavRow` + 禁用态（§6.3） |
| `AboutInfoRow` / `AboutLinkRow` | `SubPageScreens.kt:1792 / 1765` | `InfoRow` / `NavRow` |
| `FactCard` 行 | `CollabScreens.kt` | 仓库级设置，本轮范围外 |
| `SectionTitle`（**私有**） | `TranslateSettingsScreen.kt:405` | 分组标签（§4.4） |

**关键后果**：`SwitchRow` 是 `private fun`，所以**设置主页一个开关都没有** —— 主页 7 行全是导航行
（唯一「像开关」的主题行还是点一下循环）。这是「行组件不共享」直接造成的可用性问题。

### 3.2 逐项改动与收益（**不夸大战果**）

| 改动 | 收益 | 量级 |
|---|---|---|
| 8 套行组件 → 6 种行型 | 加设置从「写一个 Row」变成「选一个行型」 | 维护成本；这是最大的一项 |
| 主题：循环手势 → 三档分段 | 看得见全部档位、一步直达、可撤销 | 交互质量 |
| Git 代理：主页弹窗 → L2 输入页 | 反馈从**页尾**移到**输入框下方**；主页少一个对话框 | 反馈可达性 |
| 禁用行：只调 alpha → 原因 + 出路 | 用户知道为什么不能点、怎么才能点 | 可用性 |
| 危险操作：三种做法 → 一种规格 | 确认框点名对象、写清后果与可否撤销 | 安全 |
| 分节标题 → 分组卡片 | 组变得可扫读 | **≈252dp → ≈238dp，几乎持平（见 §5.4 的更正）** |
| 用语统一 | 一个含义一种写法 | 一致 |

> **一句话**：这次重设计的收益在**一致性、可维护性、危险动作的安全性**，
> **不是**「设置页变短了」—— 设置页本来就不挤。

---

## 四、行型对照（规范 §4.1 的可视化）

| 行型 | 左 | 中 | 右 | 语义 |
|---|---|---|---|---|
| `NavRow` | 图标 20 | 名称 +（可选）说明 | 值 + `›` | 进子页 |
| `SwitchRow` | 图标 20 | 名称 +（可选）说明 | （可选）状态胶囊 + `Switch` | 布尔，立即生效 |
| `ChoiceRow` | 图标 20 | 名称 +（可选）说明 | 分段控件（2–3 档） | 枚举，就地可见 |
| `ActionRow` | 图标 20 | 名称 +（可选）说明 | （可选）右侧小字 | 一次性命令 |
| `DangerRow` | 图标（`dangerText`） | 名称（`dangerText`） | （可选）「不可撤销」 | 破坏性 → 二次确认 |
| `InfoRow` | 图标 20 | 名称 +（可选）说明 | 值（**可复制**） | 只读展示 |

**外加两个不是「行型」的东西**（原型里有，规范里明确排除）：

- `Prose` —— 说明段落。**不许拿 `InfoRow` 冒充散文**：那样会污染行型统计（本原型第一版就踩了，见 §7.5）；
- `DisabledNavRow` —— 不是第 7 种行型，是 `NavRow` 的**禁用态**。

---

## 五、令牌与对比度

### 5.1 令牌映射（原型 CSS 变量 → `PrimerPalette` 角色）

设置树现状**本来就基本合规**（全走 `Primer` 角色），所以这一节大部分是「沿用」：

| CSS 变量 | `PrimerPalette` 角色 | Compose 访问 | 浅色 | 深色 |
|---|---|---|---|---|
| `--canvas` | `canvas` | `Primer.BackgroundPrimary` | `#FFFFFF` | `#0D1117` |
| `--canvas-subtle`（卡片底） | `canvasSubtle` | `Primer.BackgroundSecondary` | `#FFFFFF` | `#161B22` |
| `--text-primary` | `textPrimary` | `Primer.TextPrimary` | `#050505` | `#E6EDF3` |
| `--text-secondary` | `textSecondary` | `Primer.TextSecondary` | `#41434E` | `#C9D1D9` |
| `--text-tertiary`（值 / 说明） | `textTertiary` | `Primer.TextTertiary` | `#6A6D7C` | `#8B949E` |
| `--icon-secondary` | `iconSecondary` | `Primer.IconSecondary` | `#9194A1` | `#8B949E` |
| `--border-soft` | `neutralBorder` | `Primer.Gray200` | `#E3E4E8` | `#30363D` |
| `--danger-text` | `dangerText` | `Primer.DangerText` | `#9E1C24` | `#F85149` |
| `--success-surface` / `--success-text-strong` | `successSurface` / — | `Primer.SuccessSurface` / `SuccessTextStrong` | `#E6F6EA` / `#0F5323` | `#12261A` / `#56D364` |
| `--warning-surface` / `--warning-text` | `warningSurface` / `warningText` | `Primer.WarningSurface` / `WarningText` | `#FFF1E0` / `#7D4C00` | `#2A2113` / `#D29922` |
| `--danger-surface` | `dangerSurface` | `Primer.DangerSurface` | `#FFEBEC` | `#2D1418` |
| `--accent` / `--on-accent` | `accent` / — | `Primer.Blue500` / `Primer.Gray000` | `#0969DA` / `#FFF` | `#1F6FEB` / `#FFF` |
| `--neutral-fill-strong` | `neutralFillStrong` | `Primer.Gray150` | `#EFF0F5` | `#21262D` |
| `--emphasis-fill` / `--emphasis-on-fill`（Toast） | `emphasisFill` / `emphasisOnFill` | `Primer.Gray900` / `OnEmphasis` | `#17181C` / `#FFF` | `#E6EDF3` / `#0D1117` |

### 5.2 本次**新增**的三个令牌 —— 是审计逼出来的，不是随手加的

第一版原型的对比度审计报了 **6 组不达标**。逐条查下来，根因是**一个令牌被两种语义共用**：

| 新令牌 | 浅色 | 深色 | 为什么必须与旧值分开 |
|---|---|---|---|
| `--border-control` | `#8B8E99` | `#6E7681` | 原 `--border`（`#BFC1C9` / `#30363D`）当**可交互控件边界**只有 **1.80 / 1.55:1**，低于 WCAG 1.4.11 的 3:1。但它当**分隔线 / 卡片描边**是合适的 —— 装饰性描边豁免 1.4.11。**一个值不能同时满足两种语义**，所以拆成 `--border-soft`（装饰）与 `--border-control`（可交互） |
| `--select-dot` | `#176F2C` | `#3FB950` | 单选行的选中圆点用 `Green500` 压在 `SuccessSurface` 上只有 **2.79:1**。选中圆点是**唯一**表达「选了哪个」的图形，必须 ≥3:1 |
| `--danger-btn-bg` | `#D73A49` | `#DA3633` | 深色下白字压在 `#F85149` 上只有 **3.35:1**。深色主题换成更深的红，白字回到 **4.61:1**（浅色不变，仍是 `#D73A49`） |

> **对应到 Compose**：`--border-control` 需要一个新角色（或复用 `Primer.Gray300` 并按主题取值）；
> `--select-dot` 与 `--danger-btn-bg` 是**同一个角色在不同主题下的取值差异**，
> 在 Compose 里就是 `Primer` 已有的主题化取值，不需要新角色 ——
> 但原型必须把这件事显式写出来，否则「浅色照抄深色的红」这类问题在真机上才会暴露。

### 5.3 审计结果

```
node theme-audit.js design/settings-redesign
→ 浅色 35 组 + 深色 35 组 = 70 组，失败 0 组（退出码 0）
```

审计的是**真实叠放**（名称压卡片底、胶囊字压胶囊底、分段控件选中字压 accent），不是随便挑两个令牌比。

审计脚本还会打印「拿填充色当文字」的对照，说明规范 §7.2 / §9 那条禁令不是洁癖：

| 写法 | 卡片底上 | 页面底上 |
|---|---|---|
| `Red500` 当文字 | 4.57:1 | 4.57:1 |
| `Green500` 当文字 | **3.13:1** | **3.13:1** |
| `Orange500` 当文字 | **3.01:1** | **3.01:1** |

### 5.4 一处**更正**：分组卡片不省空间

第一版说明里写了「7 个分节标题白吃掉 ≈154dp，改卡片省下来」—— **这个数字是错的**，
它把「组名也一起删掉」的前提当成了默认。实际量级：

| | 每组 | 7 组 |
|---|---|---|
| 现状分节标题 | `top 16 + 文字≈14 + bottom 6` = ≈36dp | **≈252dp** |
| 新设计（保留组名） | 标签 20 + 卡片描边 2 + 组间距 12 = ≈34dp | **≈238dp** |

**差 ≈14dp（6%）**，等于没省。只有连组名一起省掉才真正省 ≈150dp，而本规范**建议保留组名**。

**教训**：报「省了多少」之前先把两边的算式写出来。这类数字一旦写进文档就会被当成事实引用
（`log-redesign` 的 196dp→110dp 是有量算的，这里当时没有）。

---

## 六、落实方案（Compose 侧）

分三步，每步可独立发布、独立回滚。完整清单见规范 §11。

### 第 1 步 · 抽组件（**不改观感**）

| 文件 | 动作 |
|---|---|
| `ui/settings/SettingsRow.kt`（新增） | 6 个行型 + `Prose` |
| `ui/settings/SettingsKeys.kt`（新增） | 集中 `KEY_COMMIT_MODE` / `KEY_GIT_PROXY` / `KEY_NOTIF_LAYOUT` |
| `SubPageScreens.kt` | `SettingsItem` 改成 `NavRow` 的薄封装（签名不变，零调用点改动） |
| `TranslateSettingsScreen.kt` | 删掉私有 `SwitchRow` / `SectionTitle`，改用公共件 |

这一步结束时**页面看起来没变** —— 这正是它的价值：把「8 套组件」先还掉，后面的观感改动才有唯一落点。

### 第 2 步 · 改主页

主题行 → `ChoiceRow`；Git 代理 → `NavRow` + 新建 `GitProxyScreen`；
`LocalRepoEntry` 禁用态 → 原因 + 去设置；分组重排 + 分组卡片；账号卡上移到第 1 组。

### 第 3 步 · 补测试

`app/src/test/java/com/branchbase/ui/profile/SettingsSpecTest.kt`，7 条钉子见规范 §10.1。

---

## 七、原型自身的坑（留档）

这一轮踩的坑**大部分在测试桩里**，而且全是「**假红**」—— 值得记，因为假红会消磨对测试的信任。

1. **`countAttr(html, 'class="row')` 永远返回 0**：辅助函数是 `new RegExp(a + '="')`，
   传进去的字符串已经带了 `="`，拼出来是 `class="row"="`。一个辅助函数用错地方，
   §4.1 那 12 条断言集体假红。→ 拆成 `countAttr`（属性名）/ `countClass`（类名）/ `countExact`（带值关键字）。

2. **CSS 规则正则漏掉「前面是注释」的规则**：原来写 `(^|[,{}])\s*选择器\s*\{`，
   而规则前一行常常是 `*/` 结尾的注释 → `.row` / `.row .val` / `.row--disabled .sub`
   这些**紧跟注释的规则全部匹配不到**，排版鲁棒性 8 条假红。
   → 改成「先把整份 CSS 扫成 `{sel, body}` 列表，再按选择器全等取块」。

3. **「app.js 里没有 cycle」把工具条函数 `cycleData` 也匹配了**：断言 `/cycle/i` 太宽。
   → 改钉语义：没有 `data-act="cycle"`，且没有调用 `.next()`。

4. **注释自己触发了断言**：`app.js` 注释里写着「不许在 render 里拼裸 `<div class="row">`」，
   而「没有裸行」的断言直接扫源码 → 注释里的那行把自己判红。
   → 扫源码前先剥块注释。

5. **散文冒充 `InfoRow`**：三处「说明」段落复用了 `row row--info` 结构（只是为了借用排版），
   它们没有名称列也没有值列，却让「行型统计」把它们算成了信息行。
   → 独立出 `Prose` 组件，并加了一条断言：「说明段落有自己的构造器」。

6. **审计口径必须区分「装饰描边」与「活动组件边界」**：一开始把分隔线也按 3:1 要求，
   结论是「要么放水、要么把分隔线做得很重」。正确做法是按 WCAG 1.4.11 的口径分开：
   可交互组件边界 ≥3:1，装饰性描边只做存在性检查。**这个区分直接催生了 `--border-control`**。

7. **别报没算过的数字**：见 §5.4。第一版把「省 154dp」写进了说明，实际只差 14dp。

---

## 八、验收清单

- [x] `node design/settings-redesign/smoke.js design/settings-redesign` 全绿（**151 项**）
- [x] `node design/settings-redesign/theme-audit.js design/settings-redesign` 退出码 0（**70 组**）
- [x] 四套数据（默认 / 新装 / 禁用 / 长文本）都能干净渲染，布局自检均为 **0 处溢出**
- [x] 设计稿区域 **0 个写死色值**
- [x] 行型是封闭集合：页面里没有裸行，6 个构造器覆盖全部行
- [x] 主题三档全在屏上，不再有循环手势
- [x] 禁用行写了原因 + 给了出路，原因的对比度不跟着降
- [x] 危险确认框：标题 = 动词 + 对象名，正文三条，按钮写动词
- [x] 值列脱敏：代理 URL 只显示 `host:port`
- [x] 超长账号名 / 超长版本号 / 百万级数字 / 40 字符代理地址都不破版

**尚未做（原型不覆盖，需要在真机上验）**：

- [ ] 200% 字号（系统字体缩放）下的排版 —— 原型的四套数据只压了**内容**长度，没压**字号**
- [ ] 键盘弹出时 L2 输入页是否被遮挡
- [ ] 真实 Compose 侧的 `Switch` 命中区与 `TalkBack` 朗读（原型只做了 `role="switch"` / `aria-*` 标注）
- [ ] 深色主题下系统栏图标明暗（`BranchbaseTheme` 的 `SideEffect`，与本页无关但同属设置）

---

## 八·补、落地结果（2026-09）

本原型已按规范 §11 的三步落地。**落地时发现原型有三处不能照抄** —— 都在下面列清楚了，
因为「原型 vs 实现」的差异如果只在实现里，下一个人看原型还会被骗一次。

| # | 原型怎么画 | App 实际是什么 | 实现怎么改 |
|---|---|---|---|
| 1 | 「允许发送通知」是 **`SwitchRow`** | 那是**系统权限**，不是 App 的布尔值；App 只能 `request()` / `openSettings()` | 改 **`NavRow` + 状态胶囊**。一个骗人的开关比没有开关更坏 |
| 2 | 沉浸式翻译页**不在重画范围**（原型里它的入口只是 toast 占位） | 该页 500+ 行 | 只统一组件（删私有 `SwitchRow`/`SectionTitle`、自制行归位为 `ActionRow`），**未卡片化** |
| 3 | 主页有「退出登录」`DangerRow` | 个人页 `⋮` 气泡已有登出入口 | 照实现（含 §7.2 确认框），于是有**两个**登出入口 —— 刻意为之，收敛成一个属于产品决策 |

**原型自身的教训**：第 1 条是「为了演示行型而简化数据模型」的代价 ——
原型里 `notify.enabled` 是一个漂亮的三行 mock，落地时才发现 App 根本没有这个布尔值。
**画原型时把「这个值到底存不存在」标出来**，比把行型画准更省事。

落地后的验收：`SettingsSpecTest` 18 条 + `GitProxyTest` 10 条 + 全量单测绿；
新增 `Primer.BorderControl` 角色（审计逼出来的，见 §5.2）。

---

## 九、修订记录

| 版本 | 构建号 | 变更 |
|---|---|---|
| v1 | `20260914a` | 首版：设置主页 + 6 个二级页 + 危险确认；6 种行型；4 套 mock 数据；布局自检；probe 回传；`smoke.js` 151 项；`theme-audit.js` 70 组（修正 3 个真实对比度缺陷：`--border-control` / `--select-dot` / `--danger-btn-bg`）；更正「分组卡片省 154dp」为「几乎持平」 |
| v1.1 | `20260914a` | 补「落地结果」一节：三处原型与实现的刻意偏离（通知开关 / 翻译页范围 / 登出入口）；原型本体未改 |
