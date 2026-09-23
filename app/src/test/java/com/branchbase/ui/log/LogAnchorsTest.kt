package com.branchbase.ui.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **锚点表不会腐烂**：`LOG_ANCHORS` 里登记的每个 tag，都必须真的在源码里被用过。
 *
 * 为什么值得一条测试：锚点表会被塞进导出的 `report.md`，是收到日志的人「该 grep 什么」的唯一线索。
 * 如果某个 tag 只活在表里（代码那边被改名/删掉了），别人照表去搜会**一条都搜不到** ——
 * 而这种腐烂没有任何编译错误、也不会影响功能，只能靠这条断言拦住。
 *
 * 套路同 `ui/profile/SettingsSpecTest`（源码级钉子）。
 */
class LogAnchorsTest {

    private fun sourceFiles(): List<File> =
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `锚点表里的每个_tag_都真的被用过`() {
        // 表本身所在的文件当然含有这些字面量，必须排除，否则测试永远通过
        val anchorsFile = File("src/main/java/com/branchbase/ui/log/Logging.kt")
        assertTrue("锚点表应该在 Logging.kt 里", anchorsFile.isFile)
        val sources = sourceFiles().filterNot { it.canonicalPath == anchorsFile.canonicalPath }
            .associateWith { it.readText() }

        LOG_ANCHORS.forEach { (tag, _) ->
            val hit = sources.entries.firstOrNull { (_, text) -> text.contains("\"$tag\"") }
            assertTrue(
                "锚点「$tag」只在表里、代码里没人用 —— 要么去对应路径补日志，要么从表里删掉",
                hit != null,
            )
        }
    }

    @Test
    fun `锚点表本身不重复且都有解释`() {
        val tags = LOG_ANCHORS.map { it.first }
        assertEquals("tag 不能重复", tags.size, tags.distinct().size)
        LOG_ANCHORS.forEach { (tag, what) ->
            assertTrue("tag 不能为空", tag.isNotBlank())
            assertTrue("「$tag」要有解释（否则读者不知道该搜什么）", what.length >= 6)
        }
    }
}
