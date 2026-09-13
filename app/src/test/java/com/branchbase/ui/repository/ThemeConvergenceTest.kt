package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「主题收敛」的两条结构性钉子（源码级，套路同 [FileViewerThemeTest] / [WorkflowLogThemeTest]）。
 *
 * 收敛前的现场：各页面散着一套**未声明的第二套浅色色板** —— 浅色页面上很自然，
 * 深色页面上要么「读不出」（写死的深色字配主题深底）、要么是一块刺眼的亮斑。
 * 色板里其实早就有对应角色（`*Surface` / `*Text`），只是漏改。
 *
 * 颜色写错编译不报、单测不红，只有真机深色下肉眼能发现 —— 所以在这里钉两件事：
 *
 * 1. **浅色专属取值只允许出现在色板定义里**（`ui/theme/` 之外一律不许再写）；
 * 2. **不许再用 6 位十六进制** —— `Color(0xEAF9F0)` 这种写法 Compose 按 ARGB 解释，
 *    alpha = 0，等于「写了颜色但完全透明」，比写错颜色更隐蔽。
 *
 * 品牌色（语言色板）与 `RepositoryModels` 的亮度自适应算法是**有意的例外**，见下面 [allowed]。
 */
class ThemeConvergenceTest {

    private val uiRoot = File("src/main/java/com/branchbase/ui")

    /**
     * 收敛掉的浅色专属取值。它们现在只应出现在 `ui/theme/` 的色板定义里
     * （`success` = `#28A745`、`accent` = `#0969DA`、`warningSurface` = `#FFF1E0` … 正是同一批值）。
     */
    private val convergedLightOnlyValues = listOf(
        // 文字 / 前景
        "0xFF24292F", "0xFF0A4E9B", "0xFF005CC5", "0xFF7A5B00", "0xFFB91C1C",
        "0xFF9E1C24", "0xFF0B6B2E", "0xFF0969DA", "0xFF57606A", "0xFFCF222E",
        "0xFF8250DF", "0xFF28A745", "0xFFD73A49", "0xFFF66A0A", "0xFF6A6D7C",
        // 浅底彩块
        "0xFFFFF8E5", "0xFFFFF8C5", "0xFFE6FFEC", "0xFFFFEBE9", "0xFFF3EEFF",
        "0xFFFFDCE0", "0xFFEAF9F0", "0xFFE7F8ED", "0xFFFDECEC",
        "0xFFA9E3BC", "0xFFF5B5B5", "0xFFE3B341", "0xFFD4A72C", "0xFFA9CDF5",
    )

    /**
     * 例外：`RepositoryModels` 的「语言品牌色上该配深字还是白字」是**按颜色亮度算**的，
     * 算法本身就需要具体色值（`#24292F` 作前景、`#6A6D7C` 作解析失败的兜底），不是主题配色。
     */
    private val allowed = mapOf(
        "0xFF24292F" to setOf("RepositoryModels.kt"),
        "0xFF6A6D7C" to setOf("RepositoryModels.kt"),
    )

    /**
     * 品牌色（语言色板）：主题无关 —— GitHub 浅色/深色用的是同一套语言色，
     * 所以它们写死是对的（且本来就是 8 位写法）。它们散在三个文件里
     * （`LanguageColors` 与两处副本）属于「单一真源」问题，不是配色问题，不在本测试射程内。
     */

    private fun uiSources(): List<File> = uiRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        // 色板定义（ThemePalette / Color.kt）就是「写死色值」的地方，本就不该拦
        .filter { it.parentFile?.name != "theme" }
        .toList()

    @Test
    fun `浅色专属取值只允许出现在色板定义里`() {
        val offenders = mutableListOf<String>()
        uiSources().forEach { file ->
            val text = file.readText()
            convergedLightOnlyValues.forEach { hex ->
                if (text.contains(hex) && file.name !in allowed.getOrDefault(hex, emptySet())) {
                    offenders += "${file.name} 还留着 $hex（应改用 Primer 的角色）"
                }
            }
        }
        assertTrue(
            "主题收敛后，这些浅色专属取值不应再出现在 ui/theme 之外：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `不许使用 6 位十六进制色值`() {
        // `Color(0xEAF9F0)` = alpha 0 = 完全透明：颜色「写了」，但屏幕上没有。
        // 全项目只有 8 位（0xAARRGGBB）才是合法写法。
        val sixDigit = Regex("Color\\(0x[0-9A-Fa-f]{6}\\)")
        val offenders = uiSources()
            .filter { sixDigit.containsMatchIn(it.readText()) }
            .map { it.name }
        assertTrue(
            "6 位十六进制在 Compose 里 alpha = 0（完全透明），必须写成 8 位 0xAARRGGBB：$offenders",
            offenders.isEmpty(),
        )
    }
}
