package com.branchbase.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「常用仓库」显示规则的单测。
 *
 * 这里钉的都是**用户能直接感知的边界**，任何一条错了都不会崩、只会静默做错事：
 * - 升级上来的老用户（键不存在）必须和以前一样看到接口顺序的前 5 个；
 * - 用户排的顺序就是他点选的先后，不能被接口顺序、也不能被字母序覆盖；
 * - 勾过的仓库被取消星标后不能变成死条目（点进去是 404 那种）；
 * - 选满 5 个之后「取消」仍然点得动（否则用户被锁在满员状态里）；
 * - 「取消全部置顶」= 回到默认态（看哪个数据源决定了默认态长什么样，见 `MyReposSourceTest`）。
 */
class FrequentRepoRulesTest {

    /**
     * 假的「星标仓库」：**必须声明在类里，不能声明在测试函数里**。
     *
     * 反例（已踩）：写成函数内局部类时，kotlinc 会为它生成
     * `FrequentRepoRulesTest$<中文方法名>$Repo.class` —— 文件名带中文，而编译器写类文件的路径
     * 走的是平台默认编码，于是报 `Internal compiler error: java.nio.file.InvalidPathException:
     * Malformed input or input contains unmappable characters`（不是编译错误，是编译器自己崩）。
     */
    private data class Repo(val fullName: String, val stars: Int)

    private val all = listOf("a/one", "b/two", "c/three", "d/four", "e/five", "f/six", "g/seven")

    @Test
    fun `从未自定义过时按接口顺序取前 5 个`() {
        assertEquals(
            listOf("a/one", "b/two", "c/three", "d/four", "e/five"),
            FrequentRepoRules.visibleOnHome(all, selection = null, key = { it }),
        )
    }

    @Test
    fun `自定义过时按用户的点选先后排`() {
        assertEquals(
            listOf("c/three", "a/one", "f/six"),
            FrequentRepoRules.visibleOnHome(all, listOf("c/three", "a/one", "f/six"), key = { it }),
        )
    }

    @Test
    fun `取消全部置顶后回到默认态而不是空态`() {
        // 需求原话：「若设置常用仓库，则不显示收藏仓库，只显示常用仓库，除非取消所有常用仓库的选择」。
        // 所以空选择 = 还原默认（按数据源顺序取前 5），不是「这一栏空着」。
        assertEquals(
            listOf("a/one", "b/two", "c/three", "d/four", "e/five"),
            FrequentRepoRules.visibleOnHome(all, emptyList(), key = { it }),
        )
    }

    @Test
    fun `已取消星标的仓库不再出现在首页`() {
        assertEquals(
            listOf("b/two", "d/four"),
            FrequentRepoRules.visibleOnHome(all, listOf("b/two", "gone/repo", "d/four"), key = { it }),
        )
    }

    @Test
    fun `超过上限时截断且保留靠前的（用户先选的那几个）`() {
        assertEquals(
            listOf("g/seven", "f/six", "e/five", "d/four", "c/three"),
            FrequentRepoRules.visibleOnHome(
                all,
                listOf("g/seven", "f/six", "e/five", "d/four", "c/three", "b/two"),
                key = { it },
            ),
        )
    }

    @Test
    fun `对象映射保留的是仓库元信息而不只是名字`() {
        val repos = listOf(Repo("a/one", 1), Repo("b/two", 2), Repo("c/three", 3))
        assertEquals(
            listOf(Repo("c/three", 3), Repo("a/one", 1)),
            FrequentRepoRules.visibleOnHome(repos, listOf("c/three", "a/one"), key = { it.fullName }),
        )
    }

    @Test
    fun `选满之后不能再勾新的，但已勾的仍可点掉`() {
        val full = listOf("a/one", "b/two", "c/three", "d/four", "e/five")
        assertFalse(FrequentRepoRules.canPin(full, "f/six"))
        assertTrue("已勾上的点一下是取消，必须放行", FrequentRepoRules.canPin(full, "a/one"))
        assertTrue(FrequentRepoRules.canPin(full.dropLast(1), "f/six"))
    }

    @Test
    fun `点一下追加到末尾、再点一下取消，其余相对顺序不变`() {
        var sel = emptyList<String>()
        sel = FrequentRepoRules.toggle(sel, "a/one")
        sel = FrequentRepoRules.toggle(sel, "b/two")
        sel = FrequentRepoRules.toggle(sel, "c/three")
        assertEquals(listOf("a/one", "b/two", "c/three"), sel)

        // 取消中间那个：剩下的相对顺序保持（a 仍排第一）
        sel = FrequentRepoRules.toggle(sel, "b/two")
        assertEquals(listOf("a/one", "c/three"), sel)

        // 重新勾上：排到末尾（= 首页放最后），而不是插回原位
        sel = FrequentRepoRules.toggle(sel, "b/two")
        assertEquals(listOf("a/one", "c/three", "b/two"), sel)
    }
}
