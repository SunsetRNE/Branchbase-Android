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

/**
 * 把角色解析成**文字色**（小字标签 / 细图形用；同样只能在 `@Composable` 里调用）。
 *
 * ## 为什么 `color()` 不能直接拿来当文字色
 *
 * `color()` 给的是**填充色**。填充色压在自己的 12% 浅底上，对比度普遍过不了 WCAG AA 4.5:
 *
 * | 角色 | `color()` 压 12% 自色底 | 结论 |
 * |---|---|---|
 * | WARNING | `#F66A0A` → **2.6** | 差得最多 |
 * | DANGER | `#D73A49` → **3.9** | 不达标 |
 * | ACCENT | `#0969DA` → **4.4** | 差一点点 |
 *
 * 真源里本来就有对应的「文字色」角色（`Primer.XXXText`，见 `docs/specs/ui-design.md` 的
 * 「别拿 Red500 当文字用」），这里只是把它们接进 `TintRole`。
 * 实测（`TintRoleContrastTest`，阈值 4.5 / 图形 3.0，浅深两套都跑）：
 * ACCENT 6.25 · SUCCESS 5.08 · DANGER 6.44 · WARNING 6.03 · NEUTRAL_SUBTLE 4.80。
 *
 * [TintRole.DONE] 是**唯一**直接塌回填充色的一档：真源里没有 `doneText` 角色，
 * 而 `Purple500 #6F42C1` 压在 12% 自色底上是 **5.44**（深色 4.89），已过线 —— 就不为它新造色值了。
 */
@Composable
@ReadOnlyComposable
fun TintRole.textColor(): Color = when (this) {
    TintRole.ACCENT -> Primer.AccentText
    TintRole.LINK -> Primer.Link
    TintRole.SUCCESS -> Primer.SuccessText
    TintRole.DANGER -> Primer.DangerText
    // WARNING 用的是**强**警告文字色：warningText（#9A6700）压在 12% 自色底上只有 4.17，差一点点
    TintRole.WARNING -> Primer.WarningTextStrong
    TintRole.DONE -> Primer.Purple500
    TintRole.NEUTRAL -> Primer.TextPrimary
    TintRole.NEUTRAL_SUBTLE -> Primer.TextTertiary
    TintRole.EMPHASIS -> Primer.Gray900
}
