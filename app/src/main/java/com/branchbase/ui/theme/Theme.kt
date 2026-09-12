package com.branchbase.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 应用主题（GitHub Primer light）。
 *
 * ## 为什么要把 M3 的「容器色角色」全部显式写出来
 *
 * 之前这里只覆盖了 `primary / background / surface / onSurface / error` 等少数角色，
 * 其余角色沿用 Material 基线调色板。问题在于 **弹层类组件的底色并不是读 `surface`**：
 *
 * | 组件 | 实际读的角色 |
 * |------|-------------|
 * | `DropdownMenu`（搜索的类型/排序、Issue 反应选择器…） | `surfaceContainer` |
 * | `AlertDialog`（各页确认框） | `surfaceContainerHigh` |
 * | `ModalBottomSheet`（筛选手板 / 通知面板 / 工作流操作） | `surfaceContainerLow` + 拖拽把手用 `surfaceVariant` |
 * | `NavigationBarItem` 选中胶囊（底部导航） | `secondaryContainer` |
 *
 * 这些角色没被覆盖 → 弹层底色是 Material 的**带紫调基线色**（≈#F3EDF7），
 * 和「标准白底」的设计不一致：白底页面里浮出一块淡紫，且各弹层深浅还各不相同。
 * 与其在每个组件调用处逐个传 `containerColor`（十几处、必然漏），不如在这里一次对齐。
 *
 * 映射规则（对齐 Primer 的国家语言）：
 * - **所有容器角色 → 标准白底**（`Gray000`）—— 弹层与页面同为白，靠描边 + 阴影区分层次；
 * - **`surfaceContainerHighest` / `surfaceVariant` → `Gray150` / `Gray200`** ——
 *   给「需要在白底上再垫一层灰」的元素（拖拽把手、分隔线、衬底）用；
 * - **`outline` / `outlineVariant` → `Border` / `Gray150`** —— 描边色的唯一来源是 `Primer.Border`；
 * - **`secondaryContainer` → 主色 12% 蓝** —— 底部导航选中胶囊与个人页气泡 Tab 的胶囊同色。
 *
 * ⚠️ 约定：**新组件不要再用 M3 的默认容器色**，需要弹层就让它读这些角色；
 * 确有特殊需求（如深色浮层）时才在调用处显式传 `containerColor`。
 */
private val LightColors = lightColorScheme(
    // 品牌主色
    primary = Primer.Blue500,
    onPrimary = Primer.Gray000,
    inversePrimary = Primer.Blue400,
    // 底部导航选中胶囊：与个人页气泡 Tab 的选中胶囊同色系（主色 12%）
    secondaryContainer = Primer.Blue500.copy(alpha = 0.12f),
    onSecondaryContainer = Primer.Blue600,
    secondary = Primer.Green500,
    // 页面
    background = Primer.BackgroundPrimary,
    onBackground = Primer.TextPrimary,
    surface = Primer.BackgroundPrimary,
    onSurface = Primer.TextPrimary,
    // 弹层容器：统一标准白底（详见类注释的映射表）
    surfaceBright = Primer.BackgroundPrimary,
    surfaceDim = Primer.Gray100,
    surfaceContainerLowest = Primer.BackgroundPrimary,
    surfaceContainerLow = Primer.BackgroundPrimary,
    surfaceContainer = Primer.BackgroundPrimary,
    surfaceContainerHigh = Primer.BackgroundPrimary,
    surfaceContainerHighest = Primer.Gray150,
    surfaceVariant = Primer.Gray200,
    onSurfaceVariant = Primer.TextSecondary,
    surfaceTint = Primer.Blue500,
    // 描边
    outline = Primer.Border,
    outlineVariant = Primer.Gray150,
    // 遮罩 / 反色（Snackbar 等深底浮层）
    scrim = Color.Black,
    inverseSurface = Primer.Gray900,
    inverseOnSurface = Primer.Gray000,
    // 状态色
    error = Primer.Red500,
    onError = Primer.Gray000,
)

@Composable
fun BranchbaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
