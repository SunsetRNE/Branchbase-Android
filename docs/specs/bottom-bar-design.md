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

### 手柄

- 32dp 圆、1dp 描边（收起 `BorderEmphasis` / 展开或高亮 `Blue500`）、填充 `Gray150`
  （展开时 `Blue500` 实心 + 白图标）；图标 `MoreHoriz` 18dp，展开旋转 90°；
- 高亮态 = 当前页落在气泡里的某一项（仓库页 PR / 提交 / 设置）—— 否则这些页会出现「一个都不高亮」；
- 按下缩放 0.94（120ms）。

### 弹层

- 白底 `Primer.BackgroundPrimary` + 1dp `Primer.Border` + 16dp 圆角 + 12dp 阴影，
  与 [`ui-design.md`](ui-design.md) §1「弹层」一致；宽 196dp；
- 定位：**右对齐手柄、底边贴在手柄上方 8dp**，并夹在屏幕左右 8dp 安全边距内（贴边机型不越界）；
- 逐条错峰入场（每条 +28ms，容器 180ms 缩放淡入），收起一次性淡出；
- 点外部 / 返回键 / 选中任一项都会关闭，并回调 `onExpandedChange(false)`。

### 按钮行（统一渲染）

- 每行约 46dp（26dp 图标底 + 上下各 8dp 内边距 + 2dp 行距）：左侧 26dp 同色 12% 圆角方块
  （7dp 圆角）+ 15dp 图标，右侧 13.5sp / Medium 文案；按下整行缩到 0.97；
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
3. **气泡是独立窗口，采不到页面 backdrop**：所以「更多」气泡不做玻璃材质（Popup 里没有
   `LocalBackdrop` 可采），保持白底 + 描边 + 阴影。要玻璃化得换非 Popup 的覆盖层，属另一件事。
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
