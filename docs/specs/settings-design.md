# 设置页设计规范（Branchbase Settings Spec）

> **这份文档是什么**：Branchbase 全部「设置」界面的**规范性约定** —— 信息架构、行的解剖、
> 控件选型、文案用语、状态与边界、危险操作、令牌尺寸、持久化、以及「新增一个设置项」的流程。
>
> **谁该读**：任何要往设置里加一行的人；任何要改 `SubPageScreens.kt` / `TranslateSettingsScreen.kt` /
> `AccountsScreen.kt` / `CommitModeScreen.kt` 的人；以及 review 这些改动的人。
>
> **配套原型**：`design/settings-redesign/`（可点草图，不入库），
> 说明副本见 [`prototypes/settings-redesign.md`](prototypes/settings-redesign.md)。
>
> **状态**：v4（见 §十三）· 依据 2026-09 的现状测绘（§一）。规范里标 **必须 / 应该 / 禁止** 的是硬约束，
> 标 **可以** 的是建议。凡与现状冲突的，§十一 给了迁移路径。

---

## 一、为什么需要这份规范

设置页是「**唯一一个每个功能都要来挂一行的地方**」。它不像消息页或日志页那样有单一负责人，
于是它天然会变成各feature的**拼接现场**，而拼接现场会以三种方式腐化：

1. **控件各写各的** —— 同一个「二选一」在 A 页是 `Switch`、在 B 页是两行单选、在 C 页是点一下循环。
   用户学不会，改动也改不干净。
2. **状态各说各的** —— 同一个含义出现「已开启 / 开启 / 打开 / 正常」四种文案，用户不知道哪个是状态、哪个是动作。
3. **危险操作各确认各的** —— 有的二次确认，有的不确认，有的确认框里不写对象名，删错了才发现。

规范的目的不是统一审美，而是让**「加一行设置」变成填空题**而不是作文题：
选行型 → 填 key → 填文案 → 挂测试，四步做完。

### 现状测绘（2026-09）

全仓共 **12 个设置类界面**，分布在 8 个文件里，用了 **8 套互不相通的行组件**：

> 下表是 **2026-09 改前测绘**（历史记录，故意保留当时的组件名与形态）：其中 `SettingsItem` / `LocalRepoEntry`
> 与 `TranslateSettingsScreen` 的私有 `SwitchRow` / `SectionTitle` 已在第一轮落地中**删除**（见 §十四），
> Git 代理也已从主页对话框搬进二级页；现状以 §十四 为准。

| # | 界面 | 入口 | 实现 | 行组件 | 落盘 |
|---|---|---|---|---|---|
| 1 | **设置（主页）** | 个人页 ⋮ 气泡 | `ui/profile/SubPageScreens.kt` 的 `SettingsScreen()` | `SettingsSectionTitle` + `SettingsItem`×7 + `LocalRepoEntry` | — |
| 2 | 提交模式 | 设置 → 提交模式 | `ui/profile/CommitModeScreen.kt:33` | `ModeOptionRow`×3 | `KEY_COMMIT_MODE` |
| 3 | 本地仓库 | 设置 → 本地仓库 | `ui/profile/SubPageScreens.kt` 的 `LocalRepoScreen()` | 页面自有列表 + 决策页群 | git 工作目录 |
| 4 | 账号管理 | 设置 → 账号管理 | `ui/profile/AccountsScreen.kt:70` | 卡片 + 气泡菜单 + `AlertDialog` | `AccountStore` |
| 5 | 通知 | 设置 → 通知 | `ui/profile/SubPageScreens.kt` 的 `NotificationSettingsScreen()` | `SettingsItem`×1 + `ModeOptionRow`×4 | `KEY_NOTIF_LAYOUT` |
| 6 | 沉浸式翻译 | 设置 → 沉浸式翻译 | `ui/profile/TranslateSettingsScreen.kt:93` | **私有** `SwitchRow`×3 + `ModeOptionRow`×7 + 输入框 | `TranslateSettings` |
| 7 | 关于 | 设置 → 关于 | `ui/profile/SubPageScreens.kt` 的 `AboutScreen()` | `AboutIdentityRow` / `AboutCard` / `AboutLinkRow` / `AboutInfoRow` | — |
| 8 | 日志 | 设置 → 日志 | `ui/log/LogScreen.kt` | 独立页面（另有专项重设计） | — |
| 9 | 主题（外观） | 设置 → 外观 | `ui/theme/ThemeRuntime.kt` | **无行**：点一下就地循环三档 | `ThemeMode.storageKey` |
| 10 | Git 代理 | 设置 → 网络 | 改前：`SubPageScreens.kt` 主页内；现为 `ui/settings/GitProxyScreen.kt` 的 `GitProxyScreen()` | 主页行 + **主页内** `AlertDialog` 输入 | `KEY_GIT_PROXY` |
| 11 | 仓库设置 | 仓库页 ⋮ 气泡 | `ui/decision/CollabScreens.kt:232` | `FactCard` + 自制行 + 两个确认 | 远端 API |
| 12 | 仓库设置（列表） | 仓库列表 | `ui/repository/RepositoryListScreens.kt` 的 `RepositorySettingsContent()` | 内嵌子页 | 远端 API |

> #11 / #12 是**仓库级**设置（作用于某个仓库、走远端 API），不是 App 级设置。
> 本规范对它们只有两条约束（§4.6 的对象名、§7 的危险操作）；其余章节针对 #1–#10 这个 App 级设置树。

**测绘结论（问题清单）**：

| 症状 | 现场 | 违反 |
|---|---|---|
| 8 套行组件 | `SettingsItem` / `ModeOptionRow` / `SwitchRow`(私有) / `LocalRepoEntry` / `AboutInfoRow` / `AboutLinkRow` / `FactCard` 行 / `SectionTitle`(私有) | §4.1 |
| 开关行只有翻译页有 | `SwitchRow` 是 `TranslateSettingsScreen.kt` 的 `private fun`，别的页抄不到 | §4.1 |
| 主题是「点一下循环」 | 三档枚举被压成一个不可预期的手势，看不出还有哪两档 | §5.2 |
| 输入框在主页 | Git 代理用主页 `AlertDialog` 收 URL；代理是「填错就静默失效」的字段 | §5.5 |
| 反馈远离操作 | `proxyFeedback` 渲染在**页面最底部**，而操作发生在对话框里 | §6.4 |
| 禁用行只调 alpha | `LocalRepoEntry` 在非 LOCAL_REPO 时 `alpha(0.55f)`，不说什么、也不给去开启的路 | §6.3 |
| 危险动作三种做法 | 账号删除有 `AlertDialog`；日志清空在 ⋮ 菜单里；仓库删分支有确认但文案不含对象名 | §7 |
| 无默认值/无重置 | 每个 key 各自 `?: 默认`，`notif_layout` 脏值时静默回落 | §8.2 |
| 无任何设置页测试 | `app/src/test/.../ui/` 下有 theme / repository / notification，没有 settings | §10 |

---

## 二、适用边界

**本规范管**：设置主页及其全部二级页（上表 #1–#10）。

**本规范不管**：

- **仓库设置**（#11 / #12）的信息架构 —— 它们是「远端资源管理」，不是「本机偏好」；
- **登录 / 授权引导**（`ui/auth/`）—— 那是流程页，不是设置；
- **日志页内部**的排版 —— 已有 `docs/specs/prototypes/log-redesign.md` 专管。

**一条总原则**：设置页**只放偏好，不放操作**。
如果某个功能点进去是做一件事（而不是改一个值），它不该长在设置里 —— 例外只有 §7 的「危险动作」，
且必须按 §7 的规格收尾。

---

## 三、信息架构

### 3.1 层级：只有两级

```
L1 设置（唯一入口）
 ├─ 分组卡片（外观 / 通知 / 翻译 / 代码与提交 / 网络 / 关于与诊断）
 └─ 行 ──→ L2 子页（外观 / 通知 / 沉浸式翻译 / 提交模式 / 本地仓库 / 账号管理 / 关于 / 日志）
```

- **必须**：层级深度 ≤ 2。L2 页面**禁止**再挂 L3 子页（日志页内部的视图切换不算换页）。
  超过两级说明这件事该做成一件事而不是一组设置。
- **必须**：二级页的返回目标 = 设置主页。这条已是既成事实，由
  `profileBackTarget(page)`（`ui/profile/ProfileScreen.kt:383`）与 `subPageDepth()`
  （同文件 `:366`）唯一决定，**页面内的返回箭头与系统返回键必须走同一个函数**，
  禁止任何页面自己 `onBack = { subPage = null }` 抄近路跳回个人主页。

### 3.2 分组的顺序与命名

顺序按**「用户多久碰一次」×「改错了多痛」**排，不按模块排：

| 序 | 分组名 | 放什么 | 为什么在这个位置 |
|---|---|---|---|
| 1 | **账户** | 当前账号卡 → 账号管理 → **仓库凭据**（仅在令牌登录模式出现，见 §3.2.1） | 身份是第一信息；多账号是高频动作 |
| 2 | **外观** | 主题 | 第二高频（换环境就换），且改错无痛 |
| 3 | **通知** | 系统通知开关、通知显示模式 | 用户来设置页最常见的两个目的之一（「为什么没提醒」） |
| 4 | **翻译** | 自动翻译开关 → 沉浸式翻译 | 功能级开关，开了就长期用 |
| 5 | **代码与提交** | 提交模式、本地仓库 | 低频且高风险（改动影响提交行为），所以排在中后段 |
| 6 | **网络** | Git 代理 | 低频、易错、面向进阶用户 |
| 7 | **关于与诊断** | 日志、关于 | 兜底与排障，永远放最后 |

**必须**：

- 分组名 2–4 个字，**名词**，不用「其他」「杂项」「高级」这类无信息量的词。
  确实无法归类时，用**能说明白它是什么**的名字（如「网络」而不是「其他」）。
- 每个分组 **1–6 行**。超过 6 行拆组；只有 1 行的分组考虑并入相邻组。
- **禁止**分组名与行名重复（分组「通知」下有行「通知」）。

### 3.2.1 「仓库凭据」是**条件行**（只在令牌登录模式下出现）

「仓库凭据」= 给「当前账号打不开的私有仓库」单独配的一条令牌（账号优先、打不开才回退、回退后读写都用它；
规则见 [`features-design.md`](features-design.md) §3、可行性核实见 `review/08-BCD可行性核实.md`）。

**必须**：

- 位置固定在**账户**组内、紧挨账号卡之后；
- **显示条件**：`AccountStore.current(context)?.auth == AuthKind.PAT` —— OAuth / 未登录时**整行不出现**；
- **为什么是「不出现」而不是「置灰」**：§6.3 要求禁用行给出「怎么才能开」的出路，而这条的出路是
  「改用令牌登录」，不属于设置页能代办的动作 —— 一行点不动的死行只会变成噪音；
- 值列只报条数（`N 条` / `未设置`，§6.1），**令牌与本机 host 一律不进值列**（§4.3 / §6.5）；
- 二级页 `RepoCredentialsScreen`：说明段落 + 每条一行 `DangerRow`（只打开确认）+ 空态；
  删除是危险动作，按 §7.3 写清「只删本机凭据、不动远端令牌」与「删除后该仓库回退到当前账号」。

### 3.3 「关于与诊断」是兜底组

`日志` 与 `关于` **禁止**移出该组，也禁止因为「想让它更显眼」而上移 ——
它们是排障入口，位置稳定比位置显眼更重要（一个稳定的肌肉记忆位置，比每次都要找更快）。

---

## 四、行的解剖

### 4.1 行型是封闭集合：只有 6 种

**必须**：设置页的每一行都是以下 6 种之一。**禁止**在页面里用裸 `Row { ... }` 自制行 ——
这正是现状 8 套组件的成因。

建议的落地形态：`ui/settings/SettingsRow.kt`（新增）提供 6 个 Composable，
`SettingsItem` / `SwitchRow` / `ModeOptionRow` 全部收编进去。

| 行型 | 用途 | 左 | 中 | 右 | 整行点击 |
|---|---|---|---|---|---|
| **`NavRow`** 导航行 | 进子页 | 图标 20dp | 名称 +（可选）说明 | 值（12sp 三级文字）+ `›` | 进子页 |
| **`SwitchRow`** 开关行 | 布尔，**立即生效** | 图标 20dp | 名称 +（可选）说明 | `Switch` | 等效于点开关 |
| **`ChoiceRow`** 选择行 | 2–3 档枚举，就地可见 | 图标 20dp | 名称 +（可选）说明 | 分段控件 | 无（点档位） |
| **`ActionRow`** 动作行 | 触发一次性命令 | 图标 20dp | 名称 | 无 `›`；必要时附「不可撤销」小字 | 执行 |
| **`DangerRow`** 危险行 | 破坏性动作 | 图标（`dangerText`） | 名称（`dangerText`） | 无 `›` | 执行 → §7 确认 |
| **`InfoRow`** 只读行 | 纯展示，不可点 | 图标 20dp | 名称 | 值（**点一下复制**） | 复制（不是导航） |

**例外**：`InfoRow` 的值可复制 —— 版本号、构建指纹、代理地址这类内容用户需要抄出来打给开发者。
**必须**复制成功给 `Toast` 反馈（见 §6.4）。

### 4.2 行的尺寸（dp，全部为硬值）

| 项 | 值 | 说明 |
|---|---|---|
| 行高（无说明） | **48dp** | 与 `SettingsItem` 现状一致，符合 Material 最小命中区 |
| 行高（有说明） | **min 48dp，内容撑高** | 说明折行时整行变高，`Switch` / `›` 垂直居中 |
| 左右内边距 | **16dp** | |
| 图标 | **20dp** | `Primer.IconSecondary`；危险行用 `Primer.DangerText` |
| 图标与文字间距 | **12dp** | |
| 名称 | **14sp**，`TextPrimary` | 单行，超长省略 |
| 值与说明 | **12sp**，`TextTertiary` | 值单行省略；说明可折行，`lineHeight = 17.sp` |
| 分隔线 | `1dp`，`Primer.BorderEmphasis` | 行间；**组内**才有，组与组之间靠间距区分 |

**必须**：名称**永远**优先于值显示完整 —— 值负责省略（现状 `SettingsItem` 已用两个权重盒实现，
即 `Modifier.weight(1f, fill = false)` 给名称、`weight(1f)` 给值）。**禁止**让值去挤名称。

### 4.3 值列的三种特殊形态

| 形态 | 何时用 | 规格 |
|---|---|---|
| **纯文本值** | 绝大多数 | 12sp `TextTertiary`，右对齐，单行省略 |
| **状态胶囊（chip）** | 值是一个**状态**而非**配置**（账号校验结果、签名校验结果） | 11sp，`padding(h=6dp, v=2dp)`，圆角 999dp，前景用语义**文字**色（`SuccessTextStrong` / `DangerText` / `WarningText` / `AccentText`），底色用对应 `*Surface` |
| **脱敏值** | 凭据、令牌、含账号的 URL | 只显示 `host:port`，**禁止**把完整 URL 塞进值列（§6.5） |

### 4.4 分组卡片的视觉（可选，但一旦用就要统一）

原型采用**分组卡片**（`Primer.BackgroundSecondary` 底 + `Primer.BorderEmphasis` 描边 + 8dp 圆角，
卡片间距 12dp），取代现状的「12sp 灰色分节标题 + 平铺行」。理由：

- 卡片让「组」变成**可扫读的块**（卡片边界 + 组内分隔线），而标题只是一行灰字；
- 分组名移到卡片上方 **12sp `TextTertiary` 小标签**（或直接省掉，靠卡片边界分隔）。

**别拿省空间当理由 —— 这一步几乎不省。** 实测两边的量级：

| | 每组占用 | 7 组合计 |
|---|---|---|
| 现状：分节标题 | `top 16 + 文字≈14 + bottom 6` = **≈36dp** | **≈252dp** |
| 新设计：小标签 + 卡片 | 标签 `文字≈14 + bottom 6` = 20，卡片描边 2，组间距 12 → **≈34dp** | **≈238dp** |

差 **≈14dp（6%）**。只有**连组名一起省掉**（完全靠卡片边界分组）才会真正省下 ≈150dp，
但本规范**建议保留组名**：7 个分组语义明确，标签是低成本的导航提示。

> **设置页和日志页的约束不是同一个。** 日志页受垂直空间约束（chrome 吃掉首屏，见
> `prototypes/log-redesign.md`），设置页**不受** —— 它受**一致性**约束。
> 所以这一节的收益是「组变得可扫读」，不是「塞进更多行」。

> 若判断卡片改动过大，**可以**保留分节标题 —— 但**必须**统一：不许一半分组用标题、一半用卡片。

### 4.5 图标

**必须**用 `Icons.Filled.*`（Material），**禁止** emoji（现状已在别处清理过一轮）。
同一含义在设置树里**只能有一个图标** —— 例如「通知」在全树都是 `Icons.Filled.Notifications`。

### 4.6 对象名：危险行与仓库级设置必须点明作用对象

任何作用于**某个具体对象**的行，名称里**必须**含有该对象的名字：

- 仓库设置里 → `删除仓库 SunsetRNE/Branchbase-Android`，而不是「删除仓库」；
- 账号管理里 → `退出登录 @octocat`，而不是「退出登录」；
- 分支删除 → `删除分支 patch-1`。

理由：截图、误点、以及二次确认框里都需要看到对象名，否则用户无法在按下前核对。

---

## 五、控件选型

### 5.1 决策树（先走这棵树，再写代码）

```
这个设置有几个取值？
├─ 2 个、互补（开/关）            → SwitchRow（立即生效）
├─ 2–3 个枚举                     → ChoiceRow（就地分段控件）
├─ 4–7 个枚举                     → NavRow → L2 子页，用单选列表
├─ 连续数值（字号、缓存上限）      → NavRow → L2 子页，用步进器/滑杆
├─ 自由文本（代理、Key、模型名）   → NavRow → L2 子页，用带校验的输入框
└─ 一次性命令（清缓存、导出）      → ActionRow / DangerRow
```

**必须**：

- 取值 **≥4 个** 的枚举**禁止**在主页面内联（放不下，且会挤掉名称）；
- **禁止**把「三档」压成「点一下循环」（见 §5.2）；
- **禁止**在设置主页出现输入框（见 §5.5）。

### 5.2 禁止「点一下循环」（当前主题的写法）

现状：`ThemeRuntime.cycle(context)`（`ui/theme/ThemeRuntime.kt:36`）让主题行点一次就
`跟随系统 → 浅色 → 深色 → 跟随系统`。

**为什么必须改**：

- 用户**看不到还有哪两档**，也无法直接到达目标档（想从「跟随系统」到「深色」要连点两次）；
- 循环控件没有「当前值」的显式表达，只有图标变化；
- 误点一次就越过一档，且**无法撤销**（只能再点两圈回来）。

**改成**：`ChoiceRow`，右端内联三档分段控件 `跟随系统 | 浅色 | 深色`。
三档是分段控件的上限，正好。

> `ThemeMode.next()` **不删** —— `LoginScreens.ThemeModeSwitch` 的太阳/月亮/自动三态图标还在用它。
> 只是**设置页不再用它**。

### 5.3 Switch 的语义：立即生效，不写「确定」

- 开关行**必须**立即落盘并立即生效；
- **禁止**给开关配「保存」按钮；
- 开关的**副作用要写在说明里**（「关闭后不再读写磁盘，不清除已存内容」——
  `TranslateSettingsScreen.kt` 的缓存开关就是这么写的，这是正面例子）。

### 5.4 单选列表（L2 页的 4–7 档枚举）

用 `ModeOptionRow`：18dp 单选圆点 + 名称 14sp `SemiBold` + 说明 12sp `TextTertiary`，
选中态底 `Primer.SuccessSurface`、圆点 `Primer.SuccessTextStrong`（未选中描边走
`Primer.BorderControl`；走 `selectionColor(selected, on, off)`，`ui/theme/Motion.kt:206`）。
**必须**给每一行写说明 —— 单选列表的选择质量完全取决于说明文案。

### 5.5 输入框只在 L2，且必须带反馈

**必须**：自由文本设置在**二级页**里编辑。

**为什么**：改前 Git 代理用一个主页 `AlertDialog` 收 URL，而反馈
（`proxyFeedback`，已随第一轮落地删除）渲染在**主页最底部**（改前的 `SubPageScreens.kt`）—— 对话框关了，
用户看到的是页尾一行小字，既没和操作建立联系，也可能被滚出视野。这就是 §6.4 要消灭的形态。
现状：代理已搬进二级页 `ui/settings/GitProxyScreen.kt` 的 `GitProxyScreen()`，
校验结果紧贴输入框下方（纯逻辑在 `ui/settings/GitProxy.kt`：脱敏 `displayGitProxy()` + 校验 `validateGitProxy()`）。

**规格**：

- L2 页面内联输入框 + `保存` 动作；
- 校验结果紧贴输入框（**同一屏、输入框下方**），不用 `Toast` 兜底；
- 占位符给**真实可用的示例**（`http://127.0.0.1:7890 或 socks5://127.0.0.1:1080` 是正面例子）；
- 凭据类输入**必须**有明文/密文切换（**禁止**默认明文常驻）；
- 凭据**禁止**进日志、禁止注入网页（既有约束，见 `docs/specs/modules-design.md` §1 沉浸式翻译）。

---

## 六、文案与状态

### 6.1 用语表（**必须**逐字使用）

同一个含义全树**只能有一种写法**。下表左列是可以出现的，右列是禁止的：

| 含义 | 必须用 | 禁止用 |
|---|---|---|
| 布尔真 | `已开启` | 开启 / 打开 / 启用 / 正常 / 是 |
| 布尔假 | `未开启` | 关闭 / 停用 / 否 |
| 尚未配置 | `未设置` | 空 / 无 / —— |
| 跟随系统 | `跟随系统` | 自动 / 系统 |
| 权限已给 | `已授权` | 允许 / 已允许 |
| 权限未给 | `未授权` | 拒绝 / 禁止 |
| 检查中 | `检查中…` | 加载中 / Loading |
| 取不到 | `获取失败` | 错误 / Error |
| 未知 | `未检查` | 未知 / Unknown |

> 依据：`SystemNotificationState.label`、`AccountStatus.label`（`core/AccountStore.kt:14`）
> 已经是这套写法的雏形，规范把它固定下来并推广到全树。

### 6.2 名称与说明

- **名称**：名词或名词短语，**≤ 10 个汉字**，不加句号，**不带动词**
  （写「主题」不写「设置主题」—— 点了会发生什么由行型表达，不由文案表达）。
- **说明**：**可选**。只在「用户看名称猜不到后果」时写：
  - 有副作用（「关闭只是不再读写磁盘，不清除已存内容」）；
  - 涉及权限/隐私（「Key 只保存在 App 私有目录，不会注入网页、不写日志」）；
  - 有条件依赖（「仅在提交模式为『本地仓库』时可用」）。
  **禁止**用说明复述名称（名称「主题」+ 说明「设置应用主题」= 浪费 17dp）。

### 6.3 禁用行：必须说明原因 + 给出去开启的路

**禁止**只把整行 `alpha(0.55f)` 了事（现状 `LocalRepoEntry` 的写法）。

禁用行**必须**同时满足：

1. 视觉降级（`alpha ≈ 0.5`）；
2. **说明列写明为什么禁用**，且这条说明**在禁用时也要能读清**（不跟着降 alpha 到看不见）；
3. 如果禁用是**另一个设置**造成的，提供一个直达那个设置的动作
   （如「开启需先将提交模式设为『本地仓库』」+ `去设置` 按钮）。

样式上建议：整行可点，点了不执行原动作而是弹一条解释（`Toast` / 行内展开），
比「点了完全没反应」好。

### 6.4 反馈必须贴着操作发生

**必须**：任何写操作的反馈（成功 / 失败）出现在**触发它的那一屏**：

- 行内动作（开关、分段控件）→ 状态本身变化即反馈，**不需要**额外 toast；
- L2 页内的动作（保存、测试连接）→ 结果紧贴控件下方（现状翻译页的 `TestResultText` 是正面例子）；
- 跨页/不可见结果的动作（复制）→ `Toast`；
- 失败**必须**给出下一步（「去设置检查 Key」而不是「失败」）。

**禁止**把反馈渲染在页面尾部 —— 用户看不到等于没有反馈。

### 6.5 值列的脱敏

含账号、令牌、完整 URL 的值**禁止**原样进值列：

| 内容 | 显示 |
|---|---|
| `http://user:pass@proxy.corp:7890` | `proxy.corp:7890` |
| `https://ghe.internal/api/v3` | `ghe.internal` |
| token | **不显示**（如需展示只显示后 4 位） |

完整值可以放进 L2 页，或让该行可复制。

---

## 七、危险操作与二次确认

### 7.1 什么算危险

满足任一条即为**危险动作**：**不可撤销**；删除/覆盖**远端**或**本地文件**；**退出登录 / 移除账号**；
**清空**某类数据。

### 7.2 规格

| 要求 | 规格 |
|---|---|
| **行样式** | `DangerRow`：文字与图标 `Primer.DangerText`。**禁止**用填充色 `Primer.Red500` 当文字（对比度不足，见 `log-redesign` 的审计） |
| **位置** | 该分组的**最后一行**；**禁止**放在主页第一屏无分组处 |
| **主页行为** | 点 `DangerRow` **必须**弹二次确认，**禁止**在主页直接执行 |
| **确认框标题** | 动词 + 对象名（§4.6）：「删除仓库 SunsetRNE/Branchbase-Android」 |
| **确认框正文** | **必须**写清三条：① 具体会发生什么；② 影响范围（数量/是否远端）；③ **能不能撤销** |
| **按钮文案** | 确认按钮**写动词**（`删除` / `清空` / `退出`），**禁止**写「确定」；取消按钮写 `取消` |
| **确认按钮配色** | 危险确认按钮可用 `Primer.Red500` 填充 + `Primer.Gray000` 字（此处是**填充**场景，与 §7.2 表格上一行「危险行文字不许用填充色」不冲突） |
| **高风险加码** | 作用于远端且不可撤销（删仓库、删分支）→ 确认框内**再要求一次显式动作**（现状仓库设置已有此形态，保留） |

### 7.3 确认框正文模板

```
<会发生什么>。
<影响范围>。
<能否撤销>。
```

例（清空日志）：

> 将清空当前视图中的 1000 条日志，同时截断 `branchbase.log`。
> 已导出的文件不受影响。
> **此操作不可撤销。**

---

## 八、持久化

### 8.1 key 命名

**必须**：`snake_case`，全小写，**不加模块前缀**（都住在同一个 `branchbase` prefs 里，
前缀只会让 key 变长）—— 现状 `commit_mode` / `git_proxy` / `notif_layout` 即此风格，沿用。

**必须**：key 常量集中声明，**禁止**在页面里写字符串字面量。
现状 `KEY_COMMIT_MODE` / `KEY_GIT_PROXY` / `KEY_NOTIF_LAYOUT` 分散在三个文件里，
应逐步收进一个 `SettingsKeys`（`ui/settings/` 或 `core/`）。

> 这条的理由是**可发现性**：想知道「这个键谁在写」时，应该能一次 grep 到唯一声明处。

### 8.2 默认值与脏值

**必须**：

- 每个 key 都有**显式默认值**，且默认值写在**读取函数内部**（`?: DEFAULT`），不让调用方兜底；
- 读到**非法值**（老版本残留、手改、枚举改名）**必须**回落到默认值，**禁止**抛异常 ——
  `readNotifLayout()`（`NotificationModels.kt:470`）的 `firstOrNull { } ?: FLAT` 是正确写法；
- 枚举落盘用 `name`（如 `"LOCAL_REPO"`），**禁止**用 `ordinal`（顺序一变就串档）。
  > 例外：`ThemeMode.storageKey` 存的是小写短名（`"system"`），这是历史约定 ——
  > **不要**为了统一而迁移它（会读不到老用户的设置），新增项一律存 `name`。

### 8.3 单真源

同一个设置**只能有一个读取入口**。需要多处读时，读那个入口，**禁止**复制一份解析逻辑。

现状正面例子：`SystemNotificationPermission.rememberSystemNotificationState()`
被消息页横幅、下载通知、`设置 → 通知` 三处共用（`docs/specs/modules-design.md` §2 亦有记载）。

---

## 九、主题令牌与尺寸（禁止写死色值）

**必须**：设置树里**禁止**出现 `Color(0xFF……)` 字面量。
所有颜色取自 `PrimerPalette` / `CodeSyntax` 的**语义角色**，按语义取而不是按「看起来像」取：

| 用途 | 角色 |
|---|---|
| 页面底 | `Primer.BackgroundPrimary` |
| 卡片底 / 行底（选中） | `Primer.BackgroundSecondary` / `Primer.SuccessSurface` |
| 名称 | `Primer.TextPrimary` |
| 值 / 说明 | `Primer.TextTertiary` |
| 图标 | `Primer.IconSecondary` |
| 分隔线 / 卡片描边 | `Primer.BorderEmphasis`（**不是** `Primer.Gray200`） |
| 可交互控件边界（分段控件 / 圆点 / 次级按钮） | `Primer.BorderControl`（须过 WCAG 1.4.11 的 3:1，见 §十四末） |
| 危险文字 | `Primer.DangerText`（**不是** `Primer.Red500`） |
| 权限/状态胶囊 | `Primer.SuccessTextStrong` / `DangerText` / `WarningText` / `AccentText` + 对应 `*Surface` |
| 选中圆点 / 开关轨道 | `Primer.SuccessTextStrong` / `Primer.Blue500` |

**副作用**：这样写之后，设置树**自动跟随** `ThemeRuntime.mode`（跟随系统 / 浅色 / 深色），
不需要为深色单独做一套。现状设置树已经基本合规（这是本项目做得好的地方），
规范把它变成**可测试的硬约束**（§10）。

---

## 十、验收与源码级钉子

设置页「颜色写错编译不报、单测不红，只有真机深色下肉眼能发现」——
所以**必须**有结构性的源码测试，套路同 `ui/repository/WorkflowLogThemeTest.kt`
与 `ui/theme/ThemeContrastTest.kt`。

### 10.1 必须新增的测试：`app/src/test/java/com/branchbase/ui/profile/SettingsSpecTest.kt`

| # | 钉子 | 断言 |
|---|---|---|
| 1 | **无写死色值** | `SubPageScreens.kt` / `CommitModeScreen.kt` / `NotificationModels.kt` 的设置相关段落里，不得出现 `Color(0xFF` |
| 2 | **无裸行** | 设置页面里不得出现「自制的 `Row` + `clickable` + `ChevronRight`」组合（行必须来自 §4.1 的 6 个组件） |
| 3 | **开关无保存按钮** | 含 `Switch(` 的设置行所在文件里，不得出现文案 `"保存"` |
| 4 | **危险行有确认** | `DangerRow` 的 `onClick` 必须打开确认，不得直接调用删除/清空 |
| 5 | **枚举存 name 不存 ordinal** | 新增设置项落盘语句里不得出现 `.ordinal` |
| 6 | **返回目标唯一** | 设置树内所有页面的 `onBack` 只能来自 `profileBackTarget` / `SubPage.Settings`，不得直接 `null` |
| 7 | **key 集中** | 设置 key 常量必须在唯一文件里声明 |

> 第 1、5、6 条可以在**本轮就加**（现状已基本满足或改动很小）；
> 第 2、3、4、7 条依赖 §11 的重构，落地时一并加。

### 10.2 手工验收清单（每次改设置页都要过）

- [ ] 浅色 / 深色两套主题下，**每一行**的文字与图标都清晰可读（无「深色主题下的深色文字」）
- [ ] 200% 字号下，所有名称与值不重叠、不裁切；开关不被挤出屏幕
- [ ] **超长值**（64 字符代理地址、超长账号名、超长版本号）下，名称仍完整、值省略为 `…`、`›`/`Switch` 仍在屏内
- [ ] **空态**：新装用户（无账号、未配置提交模式、无代理）下每一行都有合理的值，不出现 `null` / 空白
- [ ] **禁用行**：说明了原因，并且给了去开启的路
- [ ] 每个危险动作：确认框里有**对象名 + 后果 + 能否撤销**，确认按钮是**动词**
- [ ] 二级页返回 → 回设置主页（**不是**个人主页）
- [ ] 键盘弹出时输入框不被遮挡（L2 输入页）
- [ ] 所有开关立即生效，且杀进程重进后保持

---

## 十一、落地路径

建议分三步，每步都可独立发布、独立回滚。**三步已于 2026-09 落地，状态见 §十四：**

### 第 1 步 · 抽组件（不改观感）

| 文件 | 动作 |
|---|---|
| `ui/settings/SettingsRow.kt`（新增） | 6 个行型：`NavRow` / `SwitchRow` / `ChoiceRow` / `ActionRow` / `DangerRow` / `InfoRow` |
| `ui/settings/SettingsKeys.kt`（新增） | 集中声明 `KEY_COMMIT_MODE` / `KEY_GIT_PROXY` / `KEY_NOTIF_LAYOUT` / … |
| `ui/profile/SubPageScreens.kt` | `SettingsItem` 改为 `NavRow` 的薄封装（先保签名，零调用点改动） |
| `ui/profile/TranslateSettingsScreen.kt` | 私有 `SwitchRow` / `SectionTitle` 删除，改用公共件 |

> 这一步结束时**页面看起来没变** —— 这正是它的价值：把「8 套组件」这个债先还掉，
> 后面的观感改动才有唯一落点。

### 第 2 步 · 改主页（§3 分组 + §5 控件）

| 动作 | 依据 |
|---|---|
| 主题行 → `ChoiceRow` 三档分段 | §5.2 |
| Git 代理 → `NavRow` + 新建 `GitProxyScreen`（输入 + 校验 + 就地反馈） | §5.5 / §6.4 |
| `LocalRepoEntry` 禁用态 → 说明原因 + `去设置` | §6.3 |
| 分组重排 + 分组卡片 | §3.2 / §4.4 |
| 账号卡上移到第 1 组 | §3.2 |

### 第 3 步 · 补测试与规范钉子

按 §10.1 加 `SettingsSpecTest.kt`；把 README 的设置章节指向本文件。

---

## 十二、反模式清单（review 时对照）

| ❌ 反模式 | ✅ 正确做法 | 依据 |
|---|---|---|
| 页面里裸 `Row { ... }.clickable { }` 自制设置行 | 用 §4.1 的 6 个行型之一 | §4.1 |
| 点一下循环三档 | 三档分段控件 | §5.2 |
| 开关配「保存」按钮 | 立即生效 | §5.3 |
| 主页弹 `AlertDialog` 收 URL / Key | 收进 L2 输入页，反馈贴输入框 | §5.5 |
| 反馈渲染在页面底部 | 贴住触发它的那一屏 | §6.4 |
| 禁用行只调 alpha | 写原因 + 给去开启的路 | §6.3 |
| `Color(0xFF8B949E)` | `Primer.TextTertiary` | §9 |
| 拿 `Primer.Red500` 当文字 | `Primer.DangerText` | §7.2 / §9 |
| 确认按钮写「确定」 | 写动词（`删除` / `清空`） | §7.2 |
| 确认框里不写对象名 | `删除仓库 owner/repo` | §4.6 |
| 值列塞完整代理 URL（含账号密码） | 只显示 `host:port` | §6.5 |
| 枚举存 `ordinal` | 存 `name` | §8.2 |
| 读到脏值抛异常 | 回落默认值 | §8.2 |
| 同一个设置两处各解析一遍 | 单真源 | §8.3 |
| 二级页 `onBack = { subPage = null }` | 走 `profileBackTarget` | §3.1 |
| 「其他」分组 | 说得出它是什么的名词 | §3.2 |
| 说明里复述名称 | 只在有副作用/权限/依赖时写说明 | §6.2 |
| 设置里放「做一件事」的入口 | 除危险动作外，设置只放偏好 | §2 |

---

## 十三、变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-09 | 首次成文：现状测绘（12 个界面 / 8 套行组件）、两级 IA、6 种行型、控件决策树、用语表、危险操作规格、令牌约束、`SettingsSpecTest` 钉子、三步落地路径 |
| v2 | 2026-09 | 落地第一轮：新增 `ui/settings/` 四个文件（行组件 / 键 / 代理纯逻辑 / 代理页）；`SettingsScreen` 与 `NotificationSettingsScreen` 重写；删掉 `SettingsItem` 等三处重复组件；新增 `Primer.BorderControl` 角色；补 `SettingsSpecTest`(18) + `GitProxyTest`(10)。偏离与待办见 §十四 |
| v4 | 2026-09 | 新增「仓库凭据」条件行 + 二级页（`ui/settings/RepoCredentialsScreen.kt`）：账户组内、仅在 `AuthKind.PAT` 时出现；删除走 `DangerRow` + 二次确认；凭据存独立 prefs（已排除云备份与设备迁移）。见 §3.2.1 |
| v3 | 2026-09 | 账户卡修复：`AccountRow` 原来是写死的灰底首字母，改成统一 `theme/Avatar`（本地缓存 → `avatar_url` → 首字母兜底，圆形裁切），新增 `avatar` 参数；账号侧补 `Account.avatarUrl`（快照缺失回落会话 `user.avatar_url`），`MainActivity` 预热同源；`SettingsSpecTest` 新增两条钉子；偏离表补第 4 条。见 §十四 |
| v5 | 2026-09 | 设置树描边改为纯黑（**仅浅色**）：卡片描边与行分隔线由 `Primer.Gray200`（`#E3E4E8`）改走**新增角色** `Primer.BorderEmphasis`（浅 `#000000` / 深 `#30363D`）；`Primer.BorderControl` 浅色由 `#8B8E99` 改为 `#000000`（深色仍 `#6E7681`）。受影响断言：无 —— `SettingsSpecTest` 本轮**未新增钉子**，因为「无写死色值」那条已覆盖（改的是角色引用，不是字面量）。见 §十四末 |

---

## 十四、落地状态（2026-09 · 第一轮）

三步都已落地，`SettingsSpecTest` 与 `GitProxyTest`（10 条）**逐项**钉住机械可判定的部分
（钉子的条数随每轮修补增长，不在这里写死）。

### 已完成的改动

| 层 | 改动 |
|---|---|
| 新增 | `ui/settings/SettingsRow.kt` —— 6 种行型 + `AccountRow`（账户卡）+ `SettingsProse` + `StatusChip` + `SettingsSegment` + `SettingsField`；`DisabledNavRow` 是 `NavRow` 的禁用态 |
| 新增 | `ui/settings/SettingsKeys.kt` —— 键的唯一声明处 + 统一的 `prefs(context)` + `gitProxy(context)` |
| 新增 | `ui/settings/GitProxy.kt`（纯逻辑：脱敏 + 校验，可单测）、`ui/settings/GitProxyScreen.kt`（二级输入页） |
| 新增 | `SettingsSpecTest` / `GitProxyTest` |
| 改动 | `SettingsScreen` 重写：7 个分组卡片、账户卡上移到第 1 组、主题改三档 `ChoiceRow`、`DisabledNavRow` 给出路、代理进二级页、危险行 + 二次确认 |
| 改动 | `NotificationSettingsScreen` 卡片化；`ModeOptionRow` 增加 `divider` 参数并**修正两处对比度** |
| 改动 | `TranslateSettingsScreen` —— 删掉私有 `SwitchRow` / `SectionTitle`，自制「清空缓存」行归位为 `ActionRow` |
| 改动 | `theme/ThemePalette.kt` + `Color.kt` —— 新增 `borderControl` 角色（可交互控件边界，WCAG 1.4.11） |
| 删除 | `SettingsItem` / 私有 `SettingsSectionTitle` / `LocalRepoEntry`（`SettingsItem` 只被设置页使用，无其他调用点） |

### 与原型的多处**刻意偏离**（原型错了，实现按实际改）

原型是可点草图，它为了演示行型而简化了数据模型。落地时发现三处不能照抄；
第 4 条是后续真机反馈补上的（账户卡写死的首字母）：

| # | 原型怎么画 | App 实际是什么 | 实现怎么改 |
|---|---|---|---|
| 1 | 「允许发送通知」是 **`SwitchRow`**（带 `checked`） | 「允许通知」是**系统权限**，不是 App 的布尔值；App 只能 `request()` 或 `openSettings()` | 改成 **`NavRow` + 状态胶囊**，点击进「通知」子页。**一个骗人的开关比没有开关更坏** —— 用户开了它但系统没授权，会以为已经生效 |
| 2 | 沉浸式翻译页**不在重画范围** | 该页 500+ 行，且原型里它的入口只是 toast 占位 | 只做**组件统一**（删私有 `SwitchRow`/`SectionTitle`、自制行归位），**未卡片化** —— 见下方待办 |
| 3 | 主页有「退出登录」`DangerRow` | 个人页 `⋮` 气泡**已经**有一个登出入口 | 照原型实现（含 §7.2 确认框），于是现在有**两个**登出入口。这是刻意的：设置页放登出是通行做法，且原入口埋在气泡里可发现性差。若要收敛成一个，属于产品决策 |
| 4 | 账户卡头像画成**写死的首字母**（`accountCard()` 里的 `<span class="av">` + `initial`） | App 有真实头像：`AvatarCache` 本地缓存（登录即预热）优先、缺失回落 `avatar_url`；首页 / 个人页 / 账号管理页都走同一个 `theme/Avatar`（圆形裁切 + 首字母兜底） | `AccountRow` 改用 `Avatar` 并新增 `avatar: String?` 参数；地址取 `Account.avatarUrl`（快照缺失时回落会话 `user.avatar_url`）。`SettingsSpecTest` 钉住「不许再写死首字母」与「设置页必须传 `account.avatarUrl`」 |

### 尚未完成（按优先级）

1. **沉浸式翻译页卡片化** —— 目前它用无卡片的 `SettingsSectionTitle`，是规范 §4.4「必须统一」的**唯一例外**；
   该函数已标注为过渡态，迁移完成后应删除。
2. **「清空译文缓存」的二次确认** —— 规范 §7.1 把「清空某类数据」列为危险动作。
   本轮只把这行从自制 `Row` 归位成 `ActionRow`，**行为未变（仍然立即清空）**。
3. **`ThemeMode.next()`** 保留着 —— 设置页不再用它（已由 `SettingsSpecTest` 钉住），
   但登录页的太阳/月亮/自动图标还在用，不能删。
4. **200% 字号验证** —— 原型与实现都没有压过系统字体缩放，只能在真机上验。
5. **仓库级设置（#11 / #12）** 未纳入 —— 本规范只对它们有两条例外约束（§4.6 / §7）。

### 新增的令牌：`borderControl` 与 `borderEmphasis`

对比度审计（`design/settings-redesign/theme-audit.js`）报了 6 组不达标，其中两组的根因是
**一个取值被两种语义共用**。落地时按审计结论拆开：

| 角色 | 浅色 | 深色 | 用在哪 | 为什么不能复用 `border` |
|---|---|---|---|---|
| `Primer.BorderControl` | `#000000` | `#6E7681` | 分段控件容器、单选圆点未选中描边、次级按钮描边 | `border`（`#BFC1C9` / `#30363D`）当可交互控件边界只有 **1.80 / 1.55:1**，低于 1.4.11 的 3:1；而它当分隔线与卡片描边是合适的（装饰性描边豁免） |
| `Primer.BorderEmphasis` | `#000000` | `#30363D` | 设置页的**卡片描边**与**行分隔线**（即原型 `--border-soft` 的位置） | 浅色下 `border` 只有 1.80:1，压在纯白卡片底上几乎看不出边界；`BorderEmphasis` 拉到 21:1 |

**浅色取值是后续调整过来的**：`BorderControl` 初版为 `#8B8E99`（3.27:1，刚好过 1.4.11），
后与 `BorderEmphasis` 一并改为 `#000000`。

**深色两档都没有跟着拉黑**，这是刻意的：纯黑压在 `#0D1117` / `#161B22` 上只有约 **1.1:1**，
边界会直接消失、等于没画。所以深色下 `BorderEmphasis` 与 `border` 同值（`#30363D`），
`BorderControl` 保持 `#6E7681`（3.77 / 4.12:1，仍满足 1.4.11）。

> 换句话说：**「拉黑」这件事只发生在浅色**。深色板在本轮改动里逐位未变。

另外两处直接用现成角色解决，**没有**新增令牌：

- 单选行的选中圆点：`Primer.Green500`（2.79:1）→ **`Primer.SuccessTextStrong`**（浅 8.21:1 / 深 8.27:1）；
- 危险行文字：`Primer.Red500` → **`Primer.DangerText`**（规范 §7.2 本来就要求如此）。

---

## 附：术语

| 术语 | 含义 |
|---|---|
| **设置树** | 设置主页及其全部二级页（上表 #1–#10） |
| **行型** | §4.1 的 6 种行组件之一 |
| **主页** | L1 设置页（`SubPage.Settings`） |
| **子页 / L2** | 从主页进入的二级页（`subPageDepth(page) == 2`） |
| **安全动作** | 可撤销、或只改本机偏好的动作 |
| **危险动作** | §7.1 定义的四类之一 |
