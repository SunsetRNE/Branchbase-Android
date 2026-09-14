package com.branchbase.ui.profile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置树的**结构性钉子**（源码级，套路同 `ui/repository/WorkflowLogThemeTest`）。
 *
 * `docs/specs/settings-design.md` 是一份规范，但规范写在文档里不会自己生效 ——
 * 这个类把其中**机械可判定**的几条变成断言，改坏了立刻红：
 *
 * | # | 钉子 | 规范 |
 * |---|---|---|
 * | 1 | 无写死色值 | §9 |
 * | 2 | 设置页不许自制行（chevron 只许出现在公共行组件里） | §4.1 |
 * | 3 | 开关行不配「保存」 | §5.3 |
 * | 4 | 危险行只许打开确认，不许直接执行 | §7.2 |
 * | 5 | 枚举存 `name` 不存 `ordinal` | §8.2 |
 * | 6 | 二级页返回目标唯一（回设置主页，不是回个人主页） | §3.1 |
 * | 7 | 落盘键只在唯一文件里声明 | §8.1 |
 */
class SettingsSpecTest {

    private fun source(path: String) = File(path).readText()

    /** 设置树参与渲染的全部源文件。 */
    private val settingsTree = listOf(
        "src/main/java/com/branchbase/ui/settings/SettingsRow.kt",
        "src/main/java/com/branchbase/ui/settings/SettingsKeys.kt",
        "src/main/java/com/branchbase/ui/settings/GitProxy.kt",
        "src/main/java/com/branchbase/ui/settings/GitProxyScreen.kt",
        "src/main/java/com/branchbase/ui/profile/SubPageScreens.kt",
        "src/main/java/com/branchbase/ui/profile/CommitModeScreen.kt",
        "src/main/java/com/branchbase/ui/profile/CommitModeStore.kt",
        "src/main/java/com/branchbase/ui/profile/TranslateSettingsScreen.kt",
    )

    private val rowComponentsPath = "src/main/java/com/branchbase/ui/settings/SettingsRow.kt"

    /**
     * 设置主页 + 通知页那一段（不含同一文件里的关于页 / 本地仓库页）。
     *
     * 划出这一段是因为 `SubPageScreens.kt` 还住着别的页面：钉子必须只约束设置树，
     * 否则「关于页用了某个图标」会把设置页判红（假红，比没有测试更坏）。
     */
    private fun settingsRegion(): String {
        val s = source("src/main/java/com/branchbase/ui/profile/SubPageScreens.kt")
        val start = s.indexOf("fun SettingsScreen(")
        val end = s.indexOf("// ───────────────────────── 本地仓库列表页")
        assertTrue("找不到设置页区段（start=$start end=$end）", start in 0 until end)
        return s.substring(start, end)
    }

    // ── ① 无写死色值（§9） ──────────────────────────────────────────────

    @Test
    fun `设置树里没有写死色值`() {
        settingsTree.forEach { path ->
            val hits = Regex("""Color\(0x""").findAll(source(path)).toList()
            assertEquals("$path 里出现了写死的 Color(0x…)，应当取 Primer 语义角色（规范 §9）", 0, hits.size)
        }
    }

    @Test
    fun `危险文字用语义角色而不是填充色`() {
        val row = source(rowComponentsPath)
        assertTrue("DangerRow 的文字必须走 Primer.DangerText（规范 §7.2）", row.contains("Primer.DangerText"))
        assertFalse(
            "DangerRow 里出现 Primer.Red500 当文字：浅色下只有 3.13:1，低于 AA",
            Regex("""DangerRow[\s\S]{0,600}?Primer\.Red500""").containsMatchIn(row),
        )
    }

    // ── ② 行型封闭：不许自制行（§4.1） ──────────────────────────────────

    @Test
    fun `六个行型都还在公共组件文件里`() {
        val row = source(rowComponentsPath)
        // 只匹配「名字 + 左括号」，不匹配 "fun Xxx(" —— ChoiceRow 是泛型的（`fun <T> ChoiceRow(`），
        // 写成 "fun ChoiceRow(" 会永远找不到，那是测试假红（第一版就踩了）
        listOf("NavRow(", "SwitchRow(", "ChoiceRow(", "ActionRow(", "DangerRow(", "InfoRow(")
            .forEach { assertTrue("缺少行型：$it（规范 §4.1 的封闭集合）", row.contains(it)) }
    }

    @Test
    fun `设置页里没有自制行`() {
        val region = settingsRegion()
        // chevron 是「这是一条导航行」的唯一视觉承诺。页面自己画 chevron，
        // 就等于自己实现了一套导航行 —— 那正是 8 套行组件的成因（规范 §4.1 / §12）。
        assertFalse(
            "设置页里出现了 ChevronRight：行必须来自 ui/settings/SettingsRow.kt 的 6 种行型",
            region.contains("ChevronRight"),
        )
    }

    @Test
    fun `设置页的行都来自公共行组件`() {
        val region = settingsRegion()
        val used = listOf(
            "NavRow(", "SwitchRow(", "ChoiceRow(", "ActionRow(", "DangerRow(", "InfoRow(",
            "DisabledNavRow(", "AccountRow(", "SettingsProse(", "ModeOptionRow(",
        ).filter { region.contains(it) }
        assertTrue("设置页一个公共行组件都没用到，说明又退回了自制行", used.size >= 6)
    }

    // ── ③ 开关不配「保存」（§5.3） ──────────────────────────────────────

    @Test
    fun `设置页没有保存按钮`() {
        assertFalse(
            "设置页出现了「保存」：开关与选择行必须立即生效，不配保存（规范 §5.3）",
            settingsRegion().contains("\"保存\""),
        )
    }

    @Test
    fun `开关行的开关立即回调`() {
        val row = source(rowComponentsPath)
        val switchRow = row.substring(row.indexOf("SwitchRow("), row.indexOf("ChoiceRow("))
        assertTrue("SwitchRow 必须把 onCheckedChange 直接接到 Switch 上", switchRow.contains("onCheckedChange = onCheckedChange"))
        assertTrue("SwitchRow 的整行点击也要立刻生效", switchRow.contains("onClick = { onCheckedChange(!checked) }"))
    }

    // ── ④ 危险行必须二次确认（§7.2 / §7.3） ─────────────────────────────

    @Test
    fun `危险行只打开确认而不直接执行`() {
        val region = settingsRegion()
        val dangerAt = region.indexOf("DangerRow(")
        assertTrue("设置页应当有一个危险行（退出登录）", dangerAt >= 0)
        val block = region.substring(dangerAt, minOf(region.length, dangerAt + 700))

        assertTrue("危险行的 onClick 必须打开二次确认（规范 §7.2）", block.contains("confirmLogout = true"))
        assertFalse(
            "危险行里直接调用了 onLogout()：破坏性动作必须过确认框",
            block.substringBefore("},").contains("onLogout()"),
        )
    }

    @Test
    fun `确认框写清对象名_影响范围与不可撤销`() {
        val region = settingsRegion()
        assertTrue("确认框标题要含对象名（动词 + 对象名，规范 §4.6）", region.contains("退出登录 \${account.login}"))
        assertTrue("确认框正文要写「会发生什么」", region.contains("将清除本机保存的凭据"))
        assertTrue("确认框正文要写「影响范围」", region.contains("不受影响"))
        assertTrue("确认框正文要写「能不能撤销」", region.contains("此操作不可撤销"))
        assertTrue("确认按钮要写动词，不是「确定」（规范 §7.2）", region.contains("Text(\"退出登录\""))
    }

    // ── ⑤ 枚举存 name 不存 ordinal（§8.2） ─────────────────────────────

    @Test
    fun `设置落盘不存 ordinal`() {
        settingsTree.forEach { path ->
            assertFalse(
                "$path 里出现 .ordinal：枚举落盘要存 name，顺序一变就串档（规范 §8.2）",
                source(path).contains(".ordinal"),
            )
        }
    }

    // ── ⑥ 返回目标唯一（§3.1） ─────────────────────────────────────────

    @Test
    fun `设置树的二级页返回目标都是设置主页`() {
        val profile = source("src/main/java/com/branchbase/ui/profile/ProfileScreen.kt")
        val subPages = listOf(
            "LocalRepo", "About", "Log", "NotificationSettings",
            "Translate", "Accounts", "CommitMode", "GitProxy",
        )
        subPages.forEach { page ->
            val marker = "SubPage.$page -> "
            val at = profile.indexOf(marker)
            assertTrue("ProfileScreen 里找不到 $marker 的路由", at >= 0)
            val line = profile.substring(at, profile.indexOf('\n', at))
            assertTrue(
                "$page 的返回目标不是设置主页；规范 §3.1 要求二级页统一回 Settings（当前：$line）",
                line.contains("onBack = { subPage = SubPage.Settings }"),
            )
        }
    }

    @Test
    fun `设置树的返回层级由 subPageDepth 唯一决定`() {
        val profile = source("src/main/java/com/branchbase/ui/profile/ProfileScreen.kt")
        val depthBlock = profile.substring(
            profile.indexOf("internal fun subPageDepth"),
            profile.indexOf("internal fun profileBackTarget"),
        )
        // 8 个二级页都必须是 depth 2，否则返回键会跳过设置主页
        listOf("LocalRepo", "About", "Log", "NotificationSettings", "Translate", "Accounts", "CommitMode", "GitProxy")
            .forEach { assertTrue("subPageDepth 漏了 SubPage.$it", depthBlock.contains("SubPage.$it")) }
    }

    // ── ⑦ 落盘键集中（§8.1） ───────────────────────────────────────────

    @Test
    fun `设置键只在唯一文件里声明`() {
        val keysPath = "src/main/java/com/branchbase/ui/settings/SettingsKeys.kt"
        assertTrue("设置键必须集中声明（规范 §8.1）", File(keysPath).exists())

        val literals = listOf("\"commit_mode\"", "\"git_proxy\"", "\"notif_layout\"")
        literals.forEach { literal ->
            settingsTree.forEach { path ->
                if (path == keysPath) return@forEach
                assertFalse(
                    "$path 里出现了键字面量 $literal：应当引 SettingsKeys（规范 §8.1 / §8.3）",
                    source(path).contains(literal),
                )
            }
        }

        val keys = source(keysPath)
        listOf("COMMIT_MODE", "GIT_PROXY", "NOTIF_LAYOUT").forEach {
            assertTrue("SettingsKeys 缺少 $it", keys.contains("const val $it"))
        }
    }

    // ── ⑧ 行解剖：尺寸与省略（§4.2） ────────────────────────────────────

    @Test
    fun `行高与图标尺寸符合规范`() {
        val row = source(rowComponentsPath)
        assertTrue("行最小高度应为 48dp（规范 §4.2）", row.contains("RowMinHeight = 48.dp"))
        assertTrue("图标应为 20dp（规范 §4.2）", row.contains("IconSize = 20.dp"))
        assertTrue("左右内边距应为 16dp（规范 §4.2）", row.contains("RowPaddingH = 16.dp"))
    }

    @Test
    fun `值负责省略而不是挤名称`() {
        val row = source(rowComponentsPath)
        val text = row.substring(row.indexOf("private fun RowScope.SettingsText("))
        assertTrue("名称要有权重盒（fill = false）", text.contains("weight(1f, fill = false)"))
        assertTrue("值要有独立的权重盒并右对齐", text.contains("contentAlignment = Alignment.CenterEnd"))
        assertTrue("值要能省略", text.contains("TextOverflow.Ellipsis"))
    }

    // ── ⑨ 控件选型（§5.2） ─────────────────────────────────────────────

    @Test
    fun `主题用三档选择行而不是循环手势`() {
        val region = settingsRegion()
        assertTrue("主题必须用 ChoiceRow（三档就地可见，规范 §5.2）", region.contains("ChoiceRow("))
        assertTrue("主题三档要取自 ThemeMode.entries", region.contains("ThemeMode.entries"))
        assertFalse(
            "设置页又出现了 ThemeRuntime.cycle：循环手势看不到全部档位、误点无法撤销（规范 §5.2）",
            region.contains("ThemeRuntime.cycle"),
        )
    }

    // ── ⑩ 禁用行给出路（§6.3） ─────────────────────────────────────────

    @Test
    fun `本地仓库禁用时说明原因并给出去开启的路`() {
        val region = settingsRegion()
        assertTrue("禁用态要用 DisabledNavRow（规范 §6.3）", region.contains("DisabledNavRow("))
        assertTrue("禁用行必须写明原因", region.contains("开启需先将"))
        assertTrue("禁用行必须给出去开启的路", region.contains("onFix = onOpenCommitMode"))
    }

    @Test
    fun `禁用行的原因不跟着降到看不见`() {
        val row = source(rowComponentsPath)
        val block = row.substring(row.indexOf("fun DisabledNavRow("))
        // 说明用 TextSecondary（不降 alpha），只有名称与图标降级
        assertTrue("禁用行的原因要用 TextSecondary 保持可读", block.contains("color = Primer.TextSecondary"))
    }
}
