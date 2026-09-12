package com.branchbase.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * 语义色**角色**（给「数据表里要带颜色」的场景用）。
 *
 * ## 为什么需要它
 *
 * 项目里有不少「常量表」要把颜色一起存下来：通知类型 → 图标 + 颜色、提示等级 → 颜色、
 * 登录流程步骤 → 颜色……这些表是 **Composable 无关的顶层常量**（`Map` / `listOf`），
 * 而主题色只能在 `@Composable` 里读。
 *
 * 如果表里存 `Color`，就只有两条路：把表变成 composable 函数（每次重组重建整张表），
 * 或者到处写 `if (dark) …`。存**角色**才是正解：表保持常量，颜色在使用点解析。
 *
 * ```
 * private val META = mapOf("Issue" to Meta(TintRole.SUCCESS, "Issue"))
 * // 使用点（composable 内）：
 * Icon(meta.icon, tint = meta.tint.color())
 * ```
 */
enum class TintRole {
    /** 主色（填充用蓝）。 */
    ACCENT,

    /** 链接/正文里的蓝。 */
    LINK,

    SUCCESS,
    DANGER,
    WARNING,
    DONE,

    /** 中性次要内容（图标/说明文字）。 */
    NEUTRAL,

    /** 中性更弱一档（灰色说明）。 */
    NEUTRAL_SUBTLE,

    /** 高强调（浅色=深、深色=浅）。 */
    EMPHASIS,
}

/** 把角色解析成当前主题下的颜色（**只能在 `@Composable` 里调用**）。 */
@Composable
@ReadOnlyComposable
fun TintRole.color(): Color = when (this) {
    TintRole.ACCENT -> Primer.Blue500
    TintRole.LINK -> Primer.Link
    TintRole.SUCCESS -> Primer.Green500
    TintRole.DANGER -> Primer.Red500
    TintRole.WARNING -> Primer.Orange500
    TintRole.DONE -> Primer.Purple500
    TintRole.NEUTRAL -> Primer.IconPrimary
    TintRole.NEUTRAL_SUBTLE -> Primer.Gray500
    TintRole.EMPHASIS -> Primer.Gray900
}
