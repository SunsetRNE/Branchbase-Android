package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「文件历史」档的择源 / 分页 / 两个解析器（[FileHistoryModels]）。
 *
 * ## 本地 fixture 是**真跑出来的**
 *
 * 下面 `localFirstPage` / `localSecondPage` / `localNoHistory` 三段，由 `core/src/git/mod.rs`
 * 的 `log_file` 在临时仓库（三次改 `a.txt`、中间插一次只动 `b.txt` 的提交）上真跑出来再抄回来的
 * （1.0.100 取证）。这份 fixture 直接把这条接口的语义钉住了：
 *
 * - **只列真的碰过该路径的提交**（「只动 b」那一次不在里面 —— 这正是 `log_file` 的价值）；
 * - 分页按**命中数**而不是提交数（`skip = 2` 给的是第三条命中，不是「跳过两个提交」）；
 * - 没碰过该路径 / 没有历史的文件给 `[]`（**不是错误**）。
 *
 * ## 为什么这几点必须钉
 *
 * 它们读错的后果都只有真机上看得见：漏列 / 多列会让「这个文件是谁改的」查错人；
 * 把 `[]` 当失败会对着一个正常的空历史显示「读取失败」；择源把浅克隆算进本地，
 * 会话成一个**假话**（文件明明有历史，只是不在本地）。而 REST 那一侧的行**不可点**，
 * 也是从来源推出来的 —— 点开只会得到「读取失败」（本地没有那些对象）。
 */
class FileHistoryModelsTest {

    /** 现场取证①：`log_file(dir, "a.txt", limit=2, skip=0)` —— 最新的两条命中。 */
    private val localFirstPage = """
        [{"author":"Dave","date":"2026-09-25T09:02:48+00:00","sha":"a43c732312832a0a24bc9608cae38c85d709d61f","subject":"第三次改 a"},{"author":"Carol","date":"2026-09-25T09:02:48+00:00","sha":"05baa706fad3c61b1afa9657943cdca75f1d3adc","subject":"第二次改 a"}]
    """.trimIndent()

    /** 现场取证②：`skip=2` 的下一页 —— 只剩一条（命中数一共三条）。 */
    private val localSecondPage = """
        [{"author":"Alice","date":"2026-09-25T09:02:48+00:00","sha":"6c8269aa2ac2f874433ff3695a1b5be925d01de5","subject":"第一次改 a"}]
    """.trimIndent()

    /** 现场取证③：没碰过该路径的文件给空数组（不是错误）。 */
    private val localNoHistory = "[]"

    /** REST `/commits?path=` 的形状（嵌套，与提交列表同一个接口）。 */
    private val restPage = """
        [{"sha":"a43c732312832a0a24bc9608cae38c85d709d61f","commit":{"message":"第三次改 a\n\n正文不该出现在标题里","author":{"name":"Dave","date":"2026-09-25T09:02:48Z"}}},{"sha":"05baa706fad3c61b1afa9657943cdca75f1d3adc","commit":{"message":"第二次改 a","author":{"name":"Carol","date":"2026-09-25T09:02:48Z"}}}]
    """.trimIndent()

    // ── 择源 ──

    @Test
    fun `本地存在且不是浅克隆才走本地 log_file`() {
        assertEquals(FileHistorySource.LOCAL, fileHistorySourceOf(localRepoExists = true, localShallow = false))
    }

    @Test
    fun `浅克隆走 REST —— 本地只有 HEAD 一条，走本地会说「没有历史」这种假话`() {
        assertEquals(FileHistorySource.REST, fileHistorySourceOf(localRepoExists = true, localShallow = true))
        assertEquals(FileHistorySource.REST, fileHistorySourceOf(localRepoExists = false, localShallow = false))
        assertEquals(FileHistorySource.REST, fileHistorySourceOf(localRepoExists = false, localShallow = true))
    }

    @Test
    fun `只有本地来源的行能点开看 diff`() {
        // diff_commit 读本地对象库：REST 来源的提交（浅克隆 / 没拉本地）本地根本没有对象，
        // 点了只会得到一句「读取失败」—— 不给注定失败的入口
        assertTrue(canOpenCommitDiff(FileHistorySource.LOCAL))
        assertFalse(canOpenCommitDiff(FileHistorySource.REST))
    }

    // ── 分页键 ──

    private fun commit(sha: String) = FileHistoryCommit(sha, "s", "a", "")

    @Test
    fun `本地按 skip 续取、REST 按最老 sha 续取`() {
        assertEquals(
            FileHistoryPage.Local(skip = 0),
            nextFileHistoryPage(FileHistorySource.LOCAL, emptyList()),
        )
        assertEquals(
            FileHistoryPage.Local(skip = 2),
            nextFileHistoryPage(FileHistorySource.LOCAL, listOf(commit("a"), commit("b"))),
        )
        assertEquals(
            FileHistoryPage.Rest(sha = null),
            nextFileHistoryPage(FileHistorySource.REST, emptyList()),
        )
        assertEquals(
            FileHistoryPage.Rest(sha = "b"),
            nextFileHistoryPage(FileHistorySource.REST, listOf(commit("a"), commit("b"))),
        )
    }

    // ── 解析 ──

    @Test
    fun `本地扁平 JSON：新的在前，字段逐个对得上`() {
        val page = parseLocalFileHistory(localFirstPage)
        assertEquals(2, page.size)
        assertEquals("第三次改 a", page[0].subject)
        assertEquals("Dave", page[0].author)
        assertEquals("a43c732", page[0].shortSha)
        assertEquals("第二次改 a", page[1].subject)
        assertEquals("Carol", page[1].author)

        // 下一页（skip=2）只剩一条：分页语义是「命中数的第 N 条起」
        val next = parseLocalFileHistory(localSecondPage)
        assertEquals(1, next.size)
        assertEquals("第一次改 a", next[0].subject)
        assertEquals("Alice", next[0].author)
    }

    @Test
    fun `没碰过该路径给空列表 —— 与「读不到」是两件事`() {
        assertEquals(emptyList<FileHistoryCommit>(), parseLocalFileHistory(localNoHistory))
        // null / 空串 = 读不到：调用方按失败态渲染（RustBridge 那层把 ERROR 折成 null）
        assertEquals(emptyList<FileHistoryCommit>(), parseLocalFileHistory(null))
        assertEquals(emptyList<FileHistoryCommit>(), parseLocalFileHistory(""))
    }

    @Test
    fun `REST 嵌套 JSON：标题只取首行，作者取 commit 里的那个`() {
        val page = parseRestFileHistory(restPage)
        assertEquals(2, page.size)
        assertEquals("第三次改 a", page[0].subject)
        assertEquals("Dave", page[0].author)
        assertEquals("2026-09-25T09:02:48Z", page[0].date)
    }

    @Test
    fun `两个解析器各认各的形状，不互相兼容`() {
        // 本地那份只认扁平字段：喂 REST 的嵌套结构能拿到 sha，但其余字段都是空
        val fromLocal = parseLocalFileHistory(restPage)
        assertEquals(2, fromLocal.size)
        assertEquals("", fromLocal[0].subject)
        assertEquals("", fromLocal[0].author)
        // 反过来：REST 那份只认嵌套，喂扁平 JSON 同样只剩 sha
        val fromRest = parseRestFileHistory(localFirstPage)
        assertEquals(2, fromRest.size)
        assertEquals("", fromRest[0].subject)
        assertEquals("", fromRest[0].author)
        // 对照组：各自的形状能拿到完整字段（否则上面四条会因为「谁都解析不出来」而全绿）
        assertEquals("第三次改 a", parseRestFileHistory(restPage)[0].subject)
        assertEquals("第三次改 a", parseLocalFileHistory(localFirstPage)[0].subject)
    }

    @Test
    fun `坏 JSON 给空列表而不是抛`() {
        assertEquals(emptyList<FileHistoryCommit>(), parseLocalFileHistory("不是 JSON"))
        assertEquals(emptyList<FileHistoryCommit>(), parseRestFileHistory("不是 JSON"))
        // 没有 sha 的条目直接丢（列表以 sha 为 key）
        assertEquals(emptyList<FileHistoryCommit>(), parseLocalFileHistory("""[{"subject":"没有 sha"}]"""))
        assertEquals(emptyList<FileHistoryCommit>(), parseRestFileHistory("""[{"commit":{}}]"""))
    }
}
