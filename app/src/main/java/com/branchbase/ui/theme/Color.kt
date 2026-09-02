package com.branchbase.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * GitHub Primer 设计体系色板（对齐逆向得到的官方色值）。
 */
object Primer {
    // 品牌主色
    val Blue500 = Color(0xFF0969DA)
    val Blue600 = Color(0xFF005CC5)
    val Blue400 = Color(0xFF2188FF)

    // 中性灰
    val Gray000 = Color(0xFFFFFFFF)
    val Gray100 = Color(0xFFF7F7F9)
    val Gray150 = Color(0xFFEFF0F5)
    val Gray200 = Color(0xFFE3E4E8)
    val Gray300 = Color(0xFFBFC1C9)
    val Gray500 = Color(0xFF6A6D7C)
    val Gray600 = Color(0xFF525560)
    val Gray700 = Color(0xFF41434E)
    val Gray900 = Color(0xFF17181C)
    val Gray1000 = Color(0xFF050505)

    // 状态色
    val Green500 = Color(0xFF28A745)
    val Green100 = Color(0xFFDCFFE4)
    val Red500 = Color(0xFFD73A49)
    val Red100 = Color(0xFFFFDCE0)
    val Orange500 = Color(0xFFF66A0A)
    val Purple500 = Color(0xFF6F42C1)

    // 语义色
    val BackgroundPrimary = Color(0xFFFFFFFF)
    val BackgroundSecondary = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF050505)
    val TextSecondary = Color(0xFF41434E)
    val TextTertiary = Color(0xFF6A6D7C)
    val IconPrimary = Color(0xFF525560)
    val IconSecondary = Color(0xFF9194A1)
    val Border = Color(0xFFBFC1C9)
}

/**
 * 代码搜索本地渲染专用色板（对标 GitHub 网页 light theme 代码高亮）。
 *
 * 用于 `SearchScreen` 代码结果卡片的本地解析渲染。
 * 与 `Primer`（品牌/语义色）分离，避免污染全局色板。
 */
object CodeSyntax {
    /** 匹配词高亮（黄底，文字保持原色） */
    val MatchBg = Color(0xFFFFF8C5)
    /** 关键字 keyword（红） */
    val Keyword = Color(0xFFCF222E)
    /** 字符串 string（蓝） */
    val StringLit = Color(0xFF0A3069)
    /** 注释 comment（灰） */
    val Comment = Color(0xFF6A737D)
    /** 函数/标识符 fn（紫） */
    val Function = Color(0xFF8250DF)
    /** 数字 number（蓝） */
    val Number = Color(0xFF0550AE)
    /** 行号（浅灰） */
    val LineNo = Color(0xFFC0C6CC)
    /** 代码块/卡片头背景（浅灰） */
    val CodeBg = Color(0xFFF6F8FA)
    /** 代码卡片边框 */
    val CardBorder = Color(0xFFD0D7DE)
}

/**
 * Profile 个人主页贡献可视化专用色板（贡献图绿阶）。
 * 用于 Activity 页贡献图 / 进度条的本地渲染。
 */
object ProfileColors {
    /** 贡献图 5 级绿阶（无贡献 → 高贡献） */
    val ContributionL0 = Color(0xFFEBEDF0)
    val ContributionL1 = Color(0xFF9BE9A8)
    val ContributionL2 = Color(0xFF40C463)
    val ContributionL3 = Color(0xFF30A14E)
    val ContributionL4 = Color(0xFF216E39)
}

/**
 * 编程语言语义色（搜索页、Profile 页共用）。
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