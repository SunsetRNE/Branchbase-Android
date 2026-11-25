# 底栏规格（原生 / 悬浮玻璃）与「更多」气泡

> 2026-11-25 的「整体比例」决策落地。结论跨版本有效，改动前先读本文 §2 与 §7 的边界。
>
> 真源：
> - `app/src/main/java/com/branchbase/ui/adaptive/AdaptiveLayoutPolicy.kt` —— 栏高、内边距、内容宽度、图标压缩；
> - `app/src/main/java/com/branchbase/ui/navigation/NavigationBar.kt` —— 主界面的 Material3 原生栏；
> - `app/src/main/java/com/branchbase/ui/navigation/GlassBar.kt` + `Backdrop.kt` —— 悬浮玻璃胶囊；
> - `app/src/main/java/com/branchbase/ui/navigation/MoreBubble.kt` —— ⋮ 手柄 + 「更多」气泡（个人页 / 仓库页共用）；
> - 钉子：`app/src/test/java/com/branchbase/ui/adaptive/AdaptiveLayoutPolicyTest.kt`、
>   `app/src/test/java/com/branchbase/ui/navigation/GlassBackdropTest.kt`。

## 一、一句话

四类底栏（主界面 Material3 原生栏 / 个人页整宽栏 / 仓库页整宽栏 / 悬浮玻璃胶囊）统一到
**48dp 高、只留图标、内容宽度由同一个布局引擎算**；两处「更多」入口收成**一份**手柄 + 气泡实现。

## 二、尺寸与比例（决策原文：原生栏 −2/5、玻璃栏 −1/4）

| 项 | 调整前 | 调整后 | 比例 | 落点 |
|---|---|---|---|---|
| 主界面原生栏高 | 80dp（M3 默认） | **48dp** | −2/5 | `AdaptiveLayoutPolicy.BottomBarHeight` |
| 个人页整宽栏高 | 60dp | **48dp** | −1/5 | 同上 |
| 仓库页整宽栏高 | 56dp | **48dp** | ≈−1/7 | 同上 |
| 玻璃容器高 | 64dp | **48dp** | −1/4 | `GlassBackdrop.ContainerHeight` |
| 玻璃内边距 | 4dp | **3dp** | −1/4 | `AdaptiveLayoutPolicy.BottomBarPadding` |
| 玻璃滑块高 | 56dp | **42dp** | −1/4 | `GlassBackdrop.PillHeight = 48 − 2×3` |
| 图标 | 20 / 22 / 24dp | 仍按窗口档（18–26dp） | — | `NavPlan.iconSize × compression` |
| ⋮ 手柄 | 40–46dp | **32dp** | — | `MoreBubble.kt` |

为什么个人页 / 仓库页也落到 48dp：它们与主界面同属「原生栏」族，原来 60 / 56 与主栏 80、
玻璃 64 一共四档并存，页面之间切换时栏高会跳。统一到 48dp 是「整体比例」的另一半，
也是 `AdaptiveLayoutPolicyTest` 里 `all bottom bars share the same height` 断言钉住的事实。

栏高**不含** `navigationBarsPadding()`：手势条那块仍由各栏自己留（`NavigationShell` 不加第二层）。

## 三、只留图标

- 48dp 高度里放不下「24dp 图标 + 11sp 文字 + 选中胶囊」——M3 官方的 80dp 正是为 `icon + label` 留的；
- 所有底栏项**不再绘制 label**；`labelRes` 保留为 `contentDescription`，读屏名与英文界面都不丢；
- 选中态：主界面走 M3 自带 indicator（64×32）；个人页整宽栏走 44×30 淡蓝胶囊；
  玻璃栏走滑块折射（选中项在滑块里透出强调色，未选中是去色磨砂）；
- 仓库页整宽栏原来 `9.5sp` 的标签与个人页 `11sp` 标签一并移除（决定：**所有**底栏选项只保留图标）。

### 按压反馈：只画在图标胶囊里

- 主界面 M3：框架把 item 的 `InteractionSource` 重映射到指示器（56×32），ripple 只在胶囊里出现，
  整格不会铺出一块方块；
- 个人页 / 仓库页：整格 48dp 仍是**点击目标**，但 `clickable(indication = null)`；
  ripple 改挂到 44×30 的图标胶囊上（个人页胶囊常驻，仓库页是透明胶囊）。
  2026-11-25 的安装态反馈：画在整格上会在 48dp 栏里铺成一块灰色「方块阴影」，与主界面不一致；
- 玻璃栏：按压走滑块透镜 + 指针高光 + 整栏拉伸，不使用 ripple。

## 四、宽度引擎：压缩原生栏图标间距

`AdaptiveLayoutPolicy.metrics(nav, itemCount)` 是唯一入口（`showLabels` 维度已随「只留图标」删除）：

| 参数 | 值 |
|---|---|
| 栏最大宽 | 手机 = 屏宽 − 2×12dp；中/大屏封顶 560dp |
| 单格上限 | 手机 96dp；中/大屏 120dp |
| 压缩基准 | `idealCellWidth = 88dp`，`compression = cell / 88` 夹在 0.60–1.25 |
| 图标 | `iconSize × compression` 夹在 18–26dp |

结果（360dp 手机）：2 槽位 → 栏内容 **198dp**、每格 96dp（居中，不再把两个图标摊到屏幕两侧）；
6 槽位 → 铺满可用宽、每格 55dp。1280dp 平板：2 槽位仍是 198dp、6 槽位封顶 560dp。

原生栏只收窄**内容**：底色仍铺满整宽，内容组在栏内居中；玻璃栏则连容器一起收窄（`Box.width`）。

## 五、悬浮玻璃栏

三层结构（容器 / 染色录制层 / 滑块）与四弹簧物理**一行没改**，见
[`liquid-glass-nav-design.md`](liquid-glass-nav-design.md)。这次只动尺寸与内容：

- 容器 48dp、内边距 3dp、滑块 42dp（缺口由 `BottomBarPadding` 唯一给出）；
- 单元格只画图标；录制层与可见层取同一份 `GlassBarCells`，错一格都会让滑块里的图标错位；
- 宽度：2 Tab 约 198dp（主界面）；个人页 4 槽位约 387dp；仓库页 6 槽位铺满可用宽（平板封顶 560dp）。

## 六、「更多」气泡：只有一份实现

**改前**：个人页是自研 `Popup`（彩色图标底 + 逐条错峰），仓库页却是 Material `DropdownMenu` ——
同一个 App 两个「更多」长得不一样，而且 `DropdownMenu` 的容器色 / 行高 / 图标底读的是 Material 基线色，
不在 `Primer` 池里。现在两处（含各自的原生栏与玻璃栏形态）都接 `MoreBubbleHandle`。

**2026-11 改版**：手柄去掉 chrome、弹层从 196dp 的锚定气泡改为贴底栏的通宽面板、进场换成带过冲的弹簧
（细节见下面三节）。两处仍然只接 `MoreBubbleHandle` —— 个人页/仓库页、整宽栏/玻璃栏四个调用点
共用同一份手柄与面板，改脸色不用改四处。

### 手柄

- 32dp 圆、**常态无描边、无底色**；图标 `MoreHoriz` 17dp、`IconPrimary`；
- 展开：`Blue500` 实心 + 白色 `✕`（同 17dp）；高亮（当前页落在面板里的某一项，仓库页 PR / 提交 / 设置）：
  图标转 `Blue500` —— 与 Tab 的选中态同一种语言；
- 按下：浮一层 `Gray150`（180ms 颜色过渡）+ 缩放 0.94（120ms）；
- **不再旋转 90°**：`MoreHoriz` 转完就是 `MoreVert`，讲的还是「更多」；展开态要说的是「再点一下关掉」，
  所以换 `✕`，由 `MorphIcons.MoreOpen`（台账声明**降级**：3 条子路径 vs 1 条）走 200ms 交叉过渡。

**为什么去掉那圈黑边**（2026-11，用户反馈「最后一位带有那个大黑边」）：描边原用 `BorderEmphasis`，
浅色板是 `#000000`、深色板是 `#30363D` —— 同一颗按钮在两种主题下的「按钮感」差 10 倍，说明它
不承载信息、只是牌子的副产物；而它坐在一行**没有容器**的图标里，就成了整条栏对比度最高的东西。
现在的规则只有一条：**常态不画 chrome，展开才实心**。

### 面板（原「弹层」）

- 白底 `Primer.BackgroundPrimary` + 1dp `Primer.Border` + 16dp 圆角 + 12dp 阴影，与
  [`ui-design.md`](ui-design.md) §1「弹层」一致；
- **通宽**：左右各 12dp（与栏内容边距同口径），底边贴手柄顶边上方 8dp，行高 44dp；
- 进场：`morePanelEnter()` = **整块从栏线以下抬起**（行程 = 内容高 + 8dp 底缝；`spring ζ=0.40` /
  stiffness 560）+ 120ms 淡入；退场：`morePanelExit()` = 160ms 下拉 + 淡出，**不过冲**；
- 弹窗窗口顶部留**内容高 30% 的空白**吃过冲（ζ=0.40 约 25% 行程）：没有它，过冲会把面板顶出窗口裁掉一截；
- 关闭有四条等价路径：**面板之外的任何一处**（含窗口内为过冲预留的透明留白、左右阴影边距）、
  返回键、选中任一项、再次点手柄 —— 都会收起并回调 `onExpandedChange(false)`；
  面板本体的空白处（行间隙、内边距）会吞掉点击，不会误关。

**为什么不是 196dp 的锚定气泡**：面板里是 5 个**去处**（星标/项目/任务/设置/登出），不是一列快捷动作
—— 通宽列表把行高从 40dp 放到 44dp，读作「目录」；而且缩放在小气泡上读作「橡皮」，
在通宽面板上不成立，位移才读作「抽屉被拉开」。锚定关系没丢：底边仍贴手柄上方 8dp。

### 按钮行（统一渲染）

- 每行 44dp（26dp 图标底 + 上下各 9dp）：左侧 26dp 同色 12% 圆角方块（7dp 圆角）+ 15dp 图标，
  右侧 13.5sp / Medium 文案；按下整行缩到 0.97；
- 颜色走 `TintRole`：个人页 星标=橙、项目=紫、任务=蓝、设置=中性、登出=红（危险项排在
  1dp 分隔线之后）；仓库页 PR=紫、提交=蓝、设置=中性、分支同步=绿；
- 危险项整行文字用 `Primer.Red500`，与「去哪」的条目保持距离（防误触）。

## 七、已知边界

1. **M3 `NavigationBar` 的 80dp 下限不能硬压**：它把 `windowInsets` 与最小高度一起算在
   Surface 上，直接 `.height(48.dp)` 会被手势条内边距挤掉内容。现在的做法是：外层 `Box`
   负责底色 + `navigationBarsPadding()`，`NavigationBar` 设 `containerColor = Transparent`、
   `tonalElevation = 0.dp`、`windowInsets = 0`，只保留它的选中胶囊与点按态。
2. **M3 的 label 必须保持 `null`**：非空 label 会把 item 切到「上下两段」布局，48dp 里会裁字；
   `alwaysShowLabel = false` 只是兜底。
3. **面板是独立窗口，采不到页面 backdrop**：所以「更多」面板不做玻璃材质（Popup 里没有
   `LocalBackdrop` 可采），保持白底 + 描边 + 阴影。要玻璃化得换非 Popup 的覆盖层，属另一件事。
   这也是没走 M3 `ModalBottomSheet` 的原因之一：它是 Dialog 窗口，同样采不到 backdrop，
   而自绘覆盖层还要自己补下拉关闭与返回键。
4. **紧凑屏 6 槽位每格 55dp**（360dp 屏）：图标降到 18dp；触摸目标 = 整格 55×48dp，
   仍高于 48dp 的最小边。再窄的屏（<320dp）栏会退到 `maxWidth` 兜底，不裁图标。
5. 栏的进出逻辑（`barVisible`）与三处 `NavigationShell` 槽位不变；`FloatingBarReservedHeight`
   的名义值随栏高从 80dp 变成 64dp，页面一律读实测值。

## 八、验证与回滚

```bash
./gradlew :app:testDebugUnitTest    # 期望 BUILD SUCCESSFUL
./gradlew :app:assembleDebug        # 期望产出 debug APK
```

- 单测：`AdaptiveLayoutPolicyTest`（2 槽 198dp / 6 槽铺满 / 平板 560dp / 四档同高 48dp）、
  `GlassBackdropTest`（三层结构、3 处 `drawBackdrop`、手势与滑块跟随三条不回归）；
- 真机：主界面 / 个人页 / 仓库页各看一次「玻璃开 / 关」两种形态，再点开个人页与仓库页的 ⋮ 气泡；
- 回滚：本次只动 6 个实现文件（`AdaptiveLayoutPolicy` / `LayoutPlan` / `NavigationBar` /
  `GlassBar` / `Backdrop` / `MoreBubble`）＋ 个人页 / 仓库页两个调用点。按文件恢复即可；
  `MoreBubble.kt` 是新增文件，回滚时删除，并把两处调用点换回原来的自研气泡 / `DropdownMenu`。
