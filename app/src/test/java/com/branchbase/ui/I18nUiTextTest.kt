package com.branchbase.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「界面文案必须跟语言走」的钉子（英文模式下翻译不完全的那一类 bug）。
 *
 * 背景（2026-09 真机，界面语言 = English）：设置页里 Theme / Language / Commit mode 的名字与说明
 * 都已经是英文，**只有下面这些还是中文**：
 *
 * | 位置 | 当时的写法 | 为什么切不过去 |
 * |---|---|---|
 * | 外观 → 主题三档 | `ThemeMode.SYSTEM("system", "跟随系统")` | 枚举不是 `@Composable`，写死的中文没有解析点 |
 * | 代码与提交 → 提交模式 | `CommitMode.SINGLE_FILE("单个文件", …)` | 同上；状态位 / 卡片标题 / 说明三处共用 |
 * | 通知 → 状态与说明 | `SystemNotificationState.label = "已开启"` | 同上（在 `get()` 里拼中文） |
 * | 代码与提交 → 本地仓库的按钮 | `DisabledNavRow(fixLabel = "去设置")` | **参数默认值**里的中文字面量 |
 * | 底部导航 | `NavDestination.Home("首页")` | 同上（枚举） |
 * | 危险确认卡标题 | `DangerConfirmCard(title = "二次确认 · 不可恢复")` | 参数默认值，且 8 个调用点全都不传 |
 * | 应用图标无障碍名 | `AppIcon(contentDescription = "应用图标")` | 参数默认值，且 4 个调用点全都不传 |
 *
 * 共同点：**都不在 `@Composable` 函数体里**，所以 `tools/i18n/extract.py` 一条也抽不到
 * （它只动组合体内的字面量），只能按 `docs/specs/i18n-migration.md` §5.1 路径 A′ 手工改：
 * 模型只带 `@StringRes`，解析留给调用方。
 *
 * 这个类钉两件事：
 * 1. **改过的文件不许退回中文**（`logLabel` 那类日志专用名除外 —— 日志按约定固定中文，见规范 §7）；
 * 2. **英文资源里不许出现中文**，且这一批新键在中英两边都存在、值不同（漏译 / 复制中文后忘改）。
 */
class I18nUiTextTest {

    private fun source(path: String) = File(path).readText()

    /**
     * 去掉 Kotlin 注释后的源码。
     *
     * 要钉的是**代码里**的中文，不是注释：这些文件的注释大量用中文解释「为什么」，
     * 而那正是留给后来者的信息。扫原文会让「把坑写下来」变成假红 ——
     * 假红比没有检查更坏（同 `SettingsSpecTest.stripComments` 的理由）。
     * 字符串字面量原样保留（`"http://…"` 里的 `//` 不是注释）。
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') i++
                c == '/' && src.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < src.length && depth > 0) {
                        when {
                            src.startsWith("/*", i) -> { depth++; i += 2 }
                            src.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                }
                c == '"' -> {
                    out.append(c); i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') { out.append(src[i]); i++ }
                        if (i < src.length) { out.append(src[i]); i++ }
                    }
                    if (i < src.length) { out.append(src[i]); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private val cjk = Regex("[\u4e00-\u9fff\u3400-\u4dbf]")

    /** 去注释后仍含中文的代码行（行号 + 原文），按 [allow] 过滤掉约定保留的行。 */
    private fun chineseCodeLines(path: String, allow: (String) -> Boolean = { false }): List<Pair<Int, String>> =
        stripComments(source(path)).lines().withIndex()
            .filter { (_, line) -> cjk.containsMatchIn(line) && !allow(line) }
            .map { (idx, line) -> (idx + 1) to line.trim() }

    /**
     * 这一批**已经改完**的界面文件：代码里不许再有中文。
     *
     * `logLabel = "…"` 是日志专用名（固定中文，见 i18n 规范 §7），是本清单唯一的例外。
     * 往清单里加文件 = 承诺「这个文件的用户可见文案已经全部资源化」，
     * 所以一个一个加，不要图省事整目录扫。
     */
    private val localizedFiles = listOf(
        "src/main/java/com/branchbase/ui/theme/Theme.kt",
        "src/main/java/com/branchbase/ui/theme/AppIcon.kt",
        "src/main/java/com/branchbase/ui/navigation/NavDestination.kt",
        "src/main/java/com/branchbase/ui/navigation/NavigationBar.kt",
        "src/main/java/com/branchbase/ui/navigation/EdgeNavigationBar.kt",
        "src/main/java/com/branchbase/ui/navigation/GlassNavigationBar.kt",
        "src/main/java/com/branchbase/ui/navigation/TopLevelBack.kt",
        "src/main/java/com/branchbase/ui/notification/SystemNotificationPermission.kt",
        "src/main/java/com/branchbase/ui/decision/DecisionComponents.kt",
        "src/main/java/com/branchbase/ui/settings/SettingsRow.kt",
        "src/main/java/com/branchbase/ui/task/TaskDatabase.kt",
        "src/main/java/com/branchbase/ui/repository/RepoRelation.kt",
        // 登录链路（2026-11-24）：介绍页的步骤 / 权限说明此前写死中文，英文界面整页是中文。
        // 现在全部走 @StringRes（AuthFlowStep.titleRes / descRes + keyScopeCopyRes），
        // 加进清单 = 承诺这个文件不再出现界面中文。
        "src/main/java/com/branchbase/ui/auth/LoginScreens.kt",
    )

    @Test
    fun `已资源化的界面文件里没有中文`() {
        val logName = { line: String -> line.contains("logLabel") }
        localizedFiles.forEach { path ->
            val hits = chineseCodeLines(path, logName)
            assertTrue(
                "$path 里还有中文（界面切英文后这几行不跟着变）：" +
                    hits.joinToString { "第 ${it.first} 行 ${it.second}" } +
                    "。改法见 docs/specs/i18n-migration.md §5.1 路径 A′：模型只带 @StringRes，解析放调用方",
                hits.isEmpty(),
            )
        }
    }

    @Test
    fun `提交模式的三档名字与说明都是资源`() {
        val path = "src/main/java/com/branchbase/ui/profile/SubPageScreens.kt"
        val page = source(path)
        val at = page.indexOf("internal enum class CommitMode(")
        assertTrue("找不到 CommitMode 定义", at >= 0)
        val block = page.substring(at).substringBefore("\n}")
        listOf("labelRes: Int", "titleRes: Int", "descRes: Int", "logLabel: String").forEach {
            assertTrue("CommitMode 缺少 $it", block.contains(it))
        }
        // 三个界面字段都是资源 ID 之后，枚举里只剩 logLabel 允许是中文
        val entryLine = Regex("""^\s*(SINGLE_FILE|MULTI_FILE|LOCAL_REPO)\(""")
        val hits = chineseCodeLines(path).filter { (lineNo, _) ->
            page.lines().getOrNull(lineNo - 1)?.let { entryLine.containsMatchIn(it) } == true
        }
        assertTrue(
            "CommitMode 的三档里又出现了写死的中文（界面切英文后状态位 / 卡片仍是中文）：" +
                hits.joinToString { "第 ${it.first} 行" },
            hits.isEmpty(),
        )
    }

    @Test
    fun `英文资源里没有中文`() {
        val path = "src/main/res/values-en/strings.xml"
        // 去 XML 注释：这份文件的**头部注释与条目注释**是中文写的（给人看的说明），
        // 要钉的是「条目值」不许是中文
        val values = source(path).replace(Regex("<!--[\\s\\S]*?-->"), "")
        val hits = values.lines().withIndex().filter { (_, line) -> cjk.containsMatchIn(line) }
        assertTrue(
            "values-en/strings.xml 里出现了中文（漏译，或复制中文后忘了改）：" +
                hits.joinToString { "第 ${it.index + 1} 行" },
            hits.isEmpty(),
        )
    }

    @Test
    fun `这一批新键在中英两边都存在且值不同`() {
        val zh = source("src/main/res/values/strings.xml")
        val en = source("src/main/res/values-en/strings.xml")

        fun value(xml: String, name: String): String? =
            Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)

        listOf(
            "theme_mode_system", "theme_mode_light", "theme_mode_dark",
            "commit_mode_single_file_label", "commit_mode_single_file_title", "commit_mode_single_file_desc",
            "commit_mode_multi_file_label", "commit_mode_local_repo_label",
            "state_notifications_on", "state_notifications_off_short",
            "note_notifications_on", "note_notifications_need_grant", "note_notifications_blocked",
            "action_open_settings", "nav_home", "nav_messages",
            "account_status_ok", "account_status_token_invalid", "auth_kind_oauth",
            "release_variant_release", "confirm_default_title", "label_app_icon",
            "action_discard", "hint_discard_hidden",
            // 2026-11-24 这一批：星标 / 关注 / 复刻的写入失败提示（RepoActions 直接解析）
            "error_web_no_response", "error_write_failed", "error_fork_no_response",
            "watch_error_custom_needs_web_login", "watch_error_limit_exceeded", "watch_error_forbidden",
            // 搜索页排序与高级筛选的显示名（key 是 ASCII，显示名才是资源）
            "search_sort_best", "search_sort_stars", "search_sort_forks", "search_sort_updated",
            "advanced_filter_owner", "advanced_filter_size", "advanced_filter_followers",
            "advanced_filter_forks", "advanced_filter_stars", "advanced_filter_created",
            "advanced_filter_pushed", "advanced_filter_topic", "advanced_filter_license",
            "advanced_filter_archived", "advanced_filter_visibility", "search_repo_meta",
            // 星标者 / 关注者 / 复刻列表的四类失败
            "label_fork_list", "error_people_list_restricted",
            "error_repo_not_found_or_forbidden", "error_rate_limited",
            "error_people_list_load_failed",
            // PR 一条龙与仓库设置页的纯判据（模型只带 LocalizedText）
            "hint_no_changes", "error_branch_name_empty", "error_branch_name_has_space",
            "error_branch_name_exists", "error_commit_message_required",
            "merge_state_unchecked", "merge_state_merged", "merge_state_unknown",
            // 同步 / 回退决策页：选项被拦下的原因与失败文案
            "undo_blocked_initial", "undo_blocked_initial_and_no_ref", "undo_blocked_initial_only",
            "undo_blocked_no_remote_ref", "discard_blocked_no_remote_ref",
            "merge_blocked_merging", "merge_blocked_dirty", "merge_blocked_shallow",
            "error_action_failed", "error_action_failed_reason",
            "revert_title_status_unknown", "revert_title_no_upstream", "revert_title_pushed",
        ).forEach { key ->
            val z = value(zh, key)
            val e = value(en, key)
            assertTrue("$key 在 values/ 里不存在", z != null)
            assertTrue("$key 在 values-en/ 里不存在 —— 英文界面会回落成中文", e != null)
            assertFalse("$key 的中英值相同，八成是漏译", z == e)
        }
    }

    /**
     * 这一批新增的 `<plurals>`：中英都存在、英文必须有 `one` + `other` 两个形态。
     *
     * 中文只有 `other`，所以「英文漏了 one」在中文下完全看不出来 ——
     * 界面切英文时 `1 commits` 这种错法不会崩，只会一直错下去。
     */
    @Test
    fun `这一批新复数在中英两边都存在且有英文单数形`() {
        val zh = source("src/main/res/values/strings.xml")
        val en = source("src/main/res/values-en/strings.xml")

        fun forms(xml: String, name: String): Map<String, String> =
            Regex("""<plurals name="$name">([\s\S]*?)</plurals>""")
                .find(xml)?.groupValues?.get(1)
                ?.let { block ->
                    Regex("""<item quantity="(\w+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(block)
                        .associate { it.groupValues[1] to it.groupValues[2] }
                }.orEmpty()

        pluralsForThisBatch().forEach { name ->
            val z = forms(zh, name)
            val e = forms(en, name)
            assertTrue("$name 在 values/ 里不存在", z.isNotEmpty())
            assertTrue("$name 的中文缺少 other 形态（Android 强制要求）", z.containsKey("other"))
            assertTrue("$name 在 values-en/ 里不存在", e.isNotEmpty())
            assertTrue("$name 的英文缺少 one 形态（会写出 1 commits）", e.containsKey("one"))
            assertTrue("$name 的英文缺少 other 形态", e.containsKey("other"))
            assertFalse("$name 的中英值相同，八成是漏译", z["other"] == e["other"])
        }
    }

    private fun pluralsForThisBatch() = listOf(
        "merge_ahead_commits",
        "stat_stars", "stat_forks", "stat_commits", "stat_contributors",
    )
}
