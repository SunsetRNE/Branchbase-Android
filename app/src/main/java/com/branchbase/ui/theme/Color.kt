package com.branchbase.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * 语义色门面（**所有 UI 颜色的唯一入口**）。
 *
 * ## 它是怎么支持深色模式的
 *
 * 每个属性都是「读当前主题色板」的 `@Composable get()`（[LocalPrimerPalette]）。
 * 因此**调用点一行都不用改**：`Primer.TextPrimary` 在浅色下是 #050505、深色下是 #E6EDF3。
 * 这也是为什么颜色必须集中在 `Primer` 里 —— 散落的 `Color(0xFF…)` 没有「当前主题」这个概念，
 * 深色模式下只能逐个手改（改造时项目里有 282 处）。
 *
 * ## 三条使用约束
 *
 * 1. 只能在 `@Composable` 里读（`remember {}` 内部、普通函数、顶层属性初始化都不行）——
 *    需要那种场景时，把颜色作为参数传进去，或在 composable 里读出来再传；
 * 2. **只做 stroke 的别拿去当填充**：`Border` 是描边色，填充请用中性填充色；
 * 3. 新增颜色先看 [PrimerPalette] 的角色表 —— 大部分需求是「换一个角色」，不是加一个色值。
 *
 * 品牌调色板（Gray* / Blue* / 状态色）保留原名字是为了不动既有调用点；
 * 它们的取值同样来自主题色板，所以深色下会自动换成对应的深色阶。
 */
object Primer {

    // ── 品牌主色（填充 / 文字链接分开，见 PrimerPalette 的说明）──
    /** 主色：**填充**用（按钮底、选中胶囊）。 */
    val Blue500: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.accent

    /** 主色深一档：选中胶囊上的图标 / 文字。 */
    val Blue600: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.accentStrong

    val Blue400: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.accentSoft

    /** 链接/正文里的蓝（深色下比填充蓝更亮，才读得清）。 */
    val Link: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.link

    // ── 中性色阶 ──
    /** 纯白（深色下仍为白：用在彩色填充上的文字）。 */
    val Gray000: Color = Color(0xFFFFFFFF)

    val Gray100: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.neutralFill
    val Gray150: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.neutralFillStrong
    val Gray200: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.neutralBorder
    val Gray300: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.neutralMuted
    val Gray500: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.neutralTextLight
    val Gray600: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.neutralText
    val Gray700: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.neutralTextStrong

    /** 高强调填充（浅色=深底白字；深色=浅底深字，配套用 [OnEmphasis]）。 */
    val Gray900: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.emphasisFill
    val Gray1000: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.emphasisFill

    /** 高强调填充上的文字色（**必须与 [Gray900] 成对使用**）。 */
    val OnEmphasis: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.emphasisOnFill

    // ── 状态色 ──
    val Green500: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.success
    val Green100: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.successSubtle
    val Red500: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.danger
    val Red100: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.dangerSubtle
    val Orange500: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.warning
    val Purple500: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.done

    // ── 语义色 ──
    /** 页面底。 */
    val BackgroundPrimary: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.canvas

    /** 导航栏 / 卡片 / 弹层底（深色下与页面底有层次差）。 */
    val BackgroundSecondary: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.canvasSubtle

    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.textSecondary
    val TextTertiary: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.textTertiary
    val IconPrimary: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.iconPrimary
    val IconSecondary: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.iconSecondary

    /** 描边色（**只用于 border/stroke**，不要当填充）。 */
    val Border: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.border

    // ── 浅底彩块（chip / 横幅底；深色下自动压暗）──
    val SuccessSurface: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.successSurface
    val SuccessSurfaceSoft: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.successSurfaceSoft
    val DangerSurface: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.dangerSurface
    val DangerSurfaceSoft: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.dangerSurfaceSoft
    val InfoSurface: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.infoSurface
    val InfoSurfaceStrong: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.infoSurfaceStrong
    val InfoSurfaceSoft: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.infoSurfaceSoft
    val SelectedRow: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.selectedRow
    val WarningSurface: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.warningSurface

    // ── 品牌文字色（小字标签用；深色下自动提亮）──
    val SuccessText: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.successText
    val SuccessTextStrong: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.successTextStrong
    val AccentText: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.accentText
    val WarningText: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.warningText
    val WarningTextStrong: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.warningTextStrong
}

/**
 * 代码搜索本地渲染专用色板（对标 GitHub 网页 light/dark 代码高亮）。
 */
object CodeSyntax {
    val MatchBg: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.code.matchBg
    val Keyword: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.code.keyword
    val StringLit: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.code.string
    val Comment: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.code.comment
    val Function: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.code.function
    val Number: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.code.number
    val LineNo: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.code.lineNo
    val CodeBg: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.code.bg
    val CardBorder: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.code.border
}

/**
 * Profile 个人主页贡献可视化专用色板（贡献图绿阶）。
 */
object ProfileColors {
    val ContributionL0: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.contribution.l0
    val ContributionL1: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.contribution.l1
    val ContributionL2: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.contribution.l2
    val ContributionL3: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.contribution.l3
    val ContributionL4: Color @Composable @ReadOnlyComposable get() = LocalPrimerPalette.current.contribution.l4
}

/**
 * 编程语言语义色（搜索页、Profile 页共用）。
 *
 * 这些是**语言品牌色**，明暗下都成立（GitHub 两种主题用的是同一套），因此不随主题切换。
 */
object LanguageColors {
    fun of(lang: String?): Color = when (lang) {
        "Kotlin" -> Color(0xFFA97BFF)
        "Rust" -> Color(0xFFDEA584)
        "Java" -> Color(0xFFB07219)
        "Python" -> Color(0xFF3572A5)
        "JavaScript" -> Color(0xFFF1E05A)
        "TypeScript" -> Color(0xFF3178C6)
        "Go" -> Color(0xFF00ADD8)
        "Swift" -> Color(0xFFF05138)
        "C++" -> Color(0xFFF34B7D)
        "C" -> Color(0xFF555555)
        "PHP" -> Color(0xFF4F5D95)
        "Markdown" -> Color(0xFF083FA1)
        "Text" -> Color(0xFF8B949E)
        "HTML" -> Color(0xFFE34C26)
        "CSS" -> Color(0xFF663399)
        "Shell" -> Color(0xFF89E051)
        else -> Color(0xFF8B949E)
    }
}
