package com.branchbase.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库页本地筛选的钉子（`RepoFilter.kt`）。
 *
 * 这些结论**都不需要跑 UI**：搜索框改一个字就要重算一遍列表，规则一旦写错，表现是
 * 「搜了没反应」或「清掉关键词还不回全量」—— 两者都是静默的，只有钉住纯函数才守得住。
 *
 * 覆盖的四条主规则：
 * 1. 空关键词 = 不筛（`assertSame`：连一次 `filter` 的复制都不该发生，列表引用不变）；
 * 2. 关键词大小写不敏感，且**描述也算**；
 * 3. 语言是**精确相等**（`Kotlin` 不该被 `Kotlin Script` 这种名字骗过去）；
 * 4. 关键词与语言**同时**生效。
 */
class RepoFilterTest {

    private val repos = listOf(
        RepoItem(
            name = "DSHA",
            fullName = "SunsetRNE/DSHA",
            description = "在手机上跑 DeepSeek Harness",
            language = "Kotlin",
            stars = 3741,
        ),
        RepoItem(
            name = "Branchbase",
            fullName = "SunsetRNE/Branchbase",
            description = "Android GitHub client",
            language = "Kotlin",
        ),
        RepoItem(
            name = "installer",
            fullName = "SunsetRNE/installer",
            description = "一键环境脚本",
            language = "Shell",
        ),
        RepoItem(
            name = "no-lang",
            fullName = "SunsetRNE/no-lang",
            description = null,
            language = null,
        ),
    )

    @Test
    fun `空关键词与空语言返回原列表本身`() {
        assertSame(repos, filterRepos(repos, "", null))
        // 只有空白的关键词也算「没填」—— 否则一个空格就会把列表清空
        assertSame(repos, filterRepos(repos, "   ", null))
    }

    @Test
    fun `关键词大小写不敏感并命中名称与全名`() {
        assertEquals(listOf("DSHA"), filterRepos(repos, "dsha", null).map { it.name })
        assertEquals(listOf("Branchbase"), filterRepos(repos, "BRANCHBASE", null).map { it.name })
        assertEquals(listOf("DSHA"), filterRepos(repos, "SunsetRNE/DS", null).map { it.name })
    }

    @Test
    fun `关键词也匹配描述`() {
        assertEquals(listOf("Branchbase"), filterRepos(repos, "github client", null).map { it.name })
        // 描述为 null 的条目不能被 `contains` 拆包（旧写法 `repo.description!!.contains` 会崩）
        assertTrue(filterRepos(repos, "github", null).none { it.name == "no-lang" })
    }

    @Test
    fun `关键词两端空白被吃掉`() {
        assertEquals(listOf("installer"), filterRepos(repos, "  installer  ", null).map { it.name })
    }

    @Test
    fun `语言是精确相等而不是前缀匹配`() {
        assertEquals(listOf("installer"), filterRepos(repos, "", "Shell").map { it.name })
        assertTrue(filterRepos(repos, "", "Shel").isEmpty())
        assertTrue(filterRepos(repos, "", "Kotlin Script").isEmpty())
    }

    @Test
    fun `关键词与语言同时生效`() {
        assertEquals(listOf("DSHA"), filterRepos(repos, "dsha", "Kotlin").map { it.name })
        // 关键词命中但语言不对 → 空；反过来同理
        assertTrue(filterRepos(repos, "dsha", "Shell").isEmpty())
        assertTrue(filterRepos(repos, "installer", "Kotlin").isEmpty())
    }

    @Test
    fun `语言条数统计忽略 null 语言`() {
        assertEquals(mapOf("Kotlin" to 2, "Shell" to 1), repoLanguageCounts(repos))
    }

    @Test
    fun `可筛语言按大类顺序排 未归类排末尾`() {
        val mixed = repos + RepoItem(name = "vue-app", fullName = "SunsetRNE/vue-app", language = "Vue")
        assertEquals(
            // Kotlin 在「应用」段、Shell 在「脚本」段，未归类的 Vue 排最后（字母序）
            listOf("Kotlin", "Shell", "Vue"),
            filterableLanguages(mixed),
        )
    }

    @Test
    fun `一个语言都读不到时回落到旧筛选条的四个`() {
        val withoutLanguage = repos.filter { it.language == null }
        assertEquals(listOf("Kotlin", "Shell", "Python", "C++"), filterableLanguages(withoutLanguage))
    }

    @Test
    fun `语言归大类 三种语义色各占一档`() {
        assertEquals(RepoLangClass.APP, langClassOf("Kotlin"))
        assertEquals(RepoLangClass.APP, langClassOf("Java"))
        assertEquals(RepoLangClass.SCRIPT, langClassOf("Shell"))
        assertEquals(RepoLangClass.SCRIPT, langClassOf("Python"))
        assertEquals(RepoLangClass.SYSTEM, langClassOf("C++"))
        assertEquals(RepoLangClass.SYSTEM, langClassOf("Rust"))
        assertNull(langClassOf("Vue"))
    }
}
