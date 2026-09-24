package com.branchbase.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「文字角色不得被填充色角色顶替」的钉子。
 *
 * ## 现场
 *
 * 主题收敛那一轮把 4 处红色文字从字面量换成了 `Primer.Red500` —— 但 `Red500` 是
 * **填充色**（`danger`），天生比文字色浅。浅色下的实测对比度：
 *
 * | 场景 | 改前 | 改后（用填充色） |
 * |------|------|------------------|
 * | 账户状态·异常 | 6.95 | **4.00** |
 * | 安全警报标题 | 5.10 | **3.61** |
 * | `StatusChip D` | 4.68 | **4.00** |
 *
 * 全部低于 WCAG AA 的 4.5。根因是色板里 `successText` / `accentText` / `warningText` 都有，
 * **唯独缺 `dangerText`** —— 于是没有角色可用，只能拿填充色顶上。本文件同时钉住「补了这个角色」
 * 和「别再退化回去」。
 *
 * 颜色写错编译不报、单测不红，只有真机上肉眼（还要在浅色下）能发现 —— 所以钉在源码层。
 */
class ThemeContrastTest {

    private val uiDir = "src/main/java/com/branchbase/ui"
    private val palettePath = "$uiDir/theme/ThemePalette.kt"
    private val colorPath = "$uiDir/theme/Color.kt"

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    // ── 一、色板必须提供 dangerText，且它在浅色下必须比填充色深 ──────────────

    @Test
    fun `色板提供 dangerText 门面`() {
        assertTrue(
            "Color.kt 必须暴露 Primer.DangerText",
            source(colorPath).contains("val DangerText: Color"),
        )
    }

    @Test
    fun `文字角色的浅色取值必须比对应填充色更深`() {
        // 不这样做，「文字角色」就退化成填充色，配 *Surface 浅底必然掉到 AA 以下。
        // 深色板不查：那里的约定是文字色**塌回**品牌填充色（successText = success = #3FB950）。
        val light = lightPalette()
        val pairs = listOf(
            "successText" to "success",
            "successTextStrong" to "success",
            "warningText" to "warning",
            "warningTextStrong" to "warning",
            "accentText" to "accent",
            "dangerText" to "danger",
        )
        pairs.forEach { (text, fill) ->
            val t = light[text]
            val f = light[fill]
            assertTrue("浅色板缺角色：$text / $fill", t != null && f != null)
            assertTrue(
                "浅色下 $text(#${t!!.toString(16)}) 必须比 $fill(#${f!!.toString(16)}) 深" +
                    "（否则文字用的是填充色，压在 *Surface 上会低于 WCAG AA 4.5）",
                luminance(t) < luminance(f),
            )
        }
    }

    /** 从 `LightPrimerPalette` 里取出 `角色名 → RGB`（只取顶层字段，不取嵌套的 code/contribution）。 */
    private fun lightPalette(): Map<String, Int> =
        paletteBetween("val LightPrimerPalette", "val DarkPrimerPalette")

    /** 同上，取深色板。深色板是文件里最后一块，`toEndMarker` 传一个不会出现的标记即可。 */
    private fun darkPalette(): Map<String, Int> =
        paletteBetween("val DarkPrimerPalette", "\u0000-never-matches")

    private fun paletteBetween(startMarker: String, endMarker: String): Map<String, Int> {
        val src = source(palettePath)
        val from = src.indexOf(startMarker)
        assertTrue("$palettePath 里找不到 $startMarker", from >= 0)
        val to = src.indexOf(endMarker, from + 1)
        val seg = src.substring(from, if (to < 0) src.length else to)
        val out = mutableMapOf<String, Int>()
        var inNested = false
        seg.lines().forEach { line ->
            if (Regex("(code|contribution) = \\w+Palette\\(").containsMatchIn(line)) { inNested = true; return@forEach }
            if (inNested) {
                if (line.trim() in setOf("),", ")")) inNested = false
                return@forEach
            }
            Regex("(\\w+) = Color\\(0xFF([0-9A-Fa-f]{6})\\)").find(line)?.let { m ->
                out[m.groupValues[1]] = m.groupValues[2].toInt(16)
            }
        }
        return out
    }

    /** WCAG 相对亮度。 */
    private fun luminance(rgb: Int): Double {
        fun ch(shift: Int): Double {
            val c = ((rgb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    /** WCAG 对比度。 */
    private fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** 把一个颜色按 alpha 叠在背景上（行内 12% 浅底就是这么来的）。 */
    private fun over(fg: Int, alpha: Double, bg: Int): Int {
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val f = (fg shr shift) and 0xFF
            val b = (bg shr shift) and 0xFF
            out = (out shl 8) or ((f * alpha + b * (1 - alpha)).toInt().coerceIn(0, 255))
        }
        return out
    }

    // ── 五、通知消息行：原因标签必须用文字角色（1.0.76 重绘引入） ────────────

    /**
     * 消息行里的原因标签用的是 `TintRole.textColor()`。
     *
     * 现场：重绘时按原型直接用了 `color()`（**填充色**）压 12% 同色底，实测
     * WARNING **2.64** / DANGER **3.85** / ACCENT **4.38** —— 三档全在 AA 以下，
     * 而真源里本来就有 `warningTextStrong` / `dangerText` / `accentText` 可用。
     * 与第二节（Red500 顶替 DangerText）是同一类错误，所以钉在同一个文件里。
     */
    @Test
    fun `通知原因标签的文字必须是文字角色`() {
        val src = source("$uiDir/notification/NotificationScreen.kt")
        assertTrue(
            "原因标签的文字必须用 textColor()（color() 是填充色，压 12% 同色底过不了 AA）",
            Regex("color = n\\.reasonColor\\.textColor\\(\\)").containsMatchIn(src),
        )
        assertTrue(
            "原因标签的底色也取同一个文字角色（保证审计口径与实现一致）",
            Regex("background\\(n\\.reasonColor\\.textColor\\(\\)\\.copy\\(alpha = 0\\.12f\\)\\)").containsMatchIn(src),
        )
        assertTrue(
            "行首图标必须用 textColor()（同上）",
            Regex("tint = n\\.tint\\.textColor\\(\\)").containsMatchIn(src),
        )
        assertTrue(
            "未读竖条用 AccentText（填充蓝压白底是 5.19，可以，但竖条与原因标签应同一套角色）",
            src.contains("val unreadBar = Primer.AccentText"),
        )
    }

    @Test
    fun `通知原因标签在浅深两套下都过 AA`() {
        // 与 TintRole.textColor() 一一对应；改那边就要改这里，否则这条会红
        val cases = listOf(
            "提到了你" to Triple("accentText", "accent", 4.5),
            "请求你审查" to Triple("done", "done", 4.5),
            "分配给了你" to Triple("warningTextStrong", "warning", 4.5),
            "安全警报" to Triple("dangerText", "danger", 4.5),
        )
        listOf("浅色" to lightPalette(), "深色" to darkPalette()).forEach { (mode, palette) ->
            cases.forEach { (label, spec) ->
                val (textRole, fillRole, min) = spec
                val text = palette[textRole]
                val fill = palette[fillRole]
                assertTrue("$mode 板缺角色：$textRole / $fillRole", text != null && fill != null)
                val bg = over(fill!!, 0.12, palette.getValue("canvas"))
                val r = ratio(text!!, bg)
                assertTrue(
                    // 「12%%」：字面量里的百分号必须转义，否则 format 会把它当占位符
                    "$mode 下「$label」标签 $textRole 压 12%% $fillRole 底只有 %.2f，低于 %.1f".format(r, min),
                    r >= min,
                )
            }
        }
    }

    @Test
    fun `低信号原因在列表行里不挂标签`() {
        // 一屏 8 个「CI 运行结果」只是重复占位，结论已经在标题里。
        // 文案本身不丢：长按动作面板仍读 reasonLabel。
        val src = source("$uiDir/notification/NotificationModels.kt")
        assertTrue(
            "Notification 必须有 reasonHighSignal 字段（行渲染据此决定挂不挂标签）",
            src.contains("val reasonHighSignal: Boolean"),
        )
        listOf("ci_activity", "subscribed", "comment", "state_change", "manual", "author").forEach { reason ->
            val entry = Regex("\"$reason\" to ReasonMeta\\([^)]*\\)").find(src)?.value
            assertTrue("NotificationModels.kt 里找不到 reason=$reason 的条目", entry != null)
            assertTrue(
                "reason=$reason 是低信号，不该带 highSignal = true（实测：$entry）",
                !entry!!.contains("highSignal = true"),
            )
        }
        listOf("mention", "team_mention", "review_requested", "assign", "security_alert").forEach { reason ->
            val entry = Regex("\"$reason\" to ReasonMeta\\([^)]*\\)").find(src)?.value
            assertTrue(
                "reason=$reason 要求用户动手，必须带 highSignal = true（实测：$entry）",
                entry != null && entry.contains("highSignal = true"),
            )
        }
    }

    // ── 二、红色文字不得再用填充色 Red500 顶替 ──────────────────────────────

    @Test
    fun `危险文字必须用 DangerText 而不是填充色 Red500`() {
        listOf(
            "$uiDir/profile/AccountsScreen.kt",
            "$uiDir/notification/SecurityAlertScreen.kt",
            "$uiDir/decision/CommitPrepScreens.kt",
        ).forEach { path ->
            val src = source(path)
            assertTrue(
                "$path 的危险文字必须用 Primer.DangerText（Red500 是填充色，白底/浅底上只有 4.0）",
                !src.contains("color = Primer.Red500"),
            )
        }
    }

    // ── 三、警告文字配 WarningSurface 时必须用 Strong 那一档 ─────────────────

    @Test
    fun `警告文字压在 WarningSurface 上必须用 WarningTextStrong`() {
        // WarningText(#9A6700) 压 WarningSurface(#FFF1E0) = 4.38，低于 AA；
        // WarningTextStrong(#7D4C00) 压同一底色 = 5.6。深色下两者同值，所以「换 Strong」没有代价。
        listOf(
            "$uiDir/repository/ReleaseScreens.kt",
            "$uiDir/repository/RepositoryListScreens.kt",
            "$uiDir/profile/SubPageScreens.kt",
            "$uiDir/home/HomeScreen.kt",
        ).forEach { path ->
            val src = source(path)
            assertTrue(
                "$path 里与 Primer.WarningSurface 搭配的警告前景必须用 WarningTextStrong",
                !src.contains("Primer.WarningText,"),
            )
        }
    }

    // ── 四、决策标签与危险确认卡的文字必须是文字角色 ────────────────────────

    @Test
    fun `决策标签的文字用文字角色`() {
        val src = source("$uiDir/decision/DecisionComponents.kt")
        // 钉的是**语义分支**（哪个 OptionTag 配哪个文字角色），不是标签字面量：
        // 标签文案已经资源化（`stringResource(R.string.label_recommended)`），
        // 继续钉 `"推荐"` 只会每抽取一次假红一次 —— 而假红会训练人忽略这套钉子。
        assertTrue(
            "「推荐」标签的文字应为 Primer.SuccessTextStrong（Green500 是填充色，压浅绿底只有 2.88）",
            Regex("OptionTag\\.RECOMMENDED ->[\\s\\S]{0,200}?Primer\\.SuccessTextStrong").containsMatchIn(src),
        )
        assertTrue(
            "「危险」标签的文字应为 Primer.DangerText",
            Regex("OptionTag\\.DANGER ->[\\s\\S]{0,200}?Primer\\.DangerText").containsMatchIn(src),
        )
        // 危险确认卡：底色 DangerSurfaceSoft，标题也必须用文字角色（Red500 压上去只有 4.16）
        assertTrue(
            "危险确认卡的标题应为 Primer.DangerText",
            Regex("background\\(Primer\\.DangerSurfaceSoft\\)[\\s\\S]{0,400}?color = Primer\\.DangerText")
                .containsMatchIn(src),
        )
    }
}
