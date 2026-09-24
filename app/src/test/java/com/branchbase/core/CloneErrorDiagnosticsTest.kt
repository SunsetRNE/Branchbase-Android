package com.branchbase.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「拉取失败的原始原因必须留得住」的钉子。
 *
 * ## 它钉的是哪一次事故
 *
 * 2026-09-25 的日志包里，两次拉取都失败在同一句话上：
 *
 * ```
 * git clone SunsetRNE/Branchbase-Android 失败：未知错误: clone 失败: failed to lock file
 *   '/storage/emulated/0/Android/data/com.branchbase/files/repos/SunsetRNE/Branchbase-An
 * ```
 *
 * 这一行**恰好 120 个字符** —— `gitCloneDetailed` 当时写的是 `.take(120)`，
 * 而按下去的那一刀正好落在仓库名中间：**被截掉的正是锁文件的文件名**。
 * 一句「有个文件锁上了」但不说哪个文件，用户看不懂、日志也定位不了。
 *
 * 现在口径与其它写操作一致（`engineErrorOrNull` → 300 字符），并且由这个测试钉住：
 * 谁再把 clone 的失败原因单独截短，这里就红。这类缺陷**在真机上只表现为「原因看不全」**，
 * 没有崩溃、没有异常日志，只有下一次翻日志包时才会再次付代价。
 */
class CloneErrorDiagnosticsTest {

    // 单测工作目录是 app 模块根（与 JniSignatureTest 同一约定）
    private val bridge = File("src/main/java/com/branchbase/core/RustBridge.kt")

    private fun source(): String {
        assertTrue("找不到 ${bridge.absolutePath}（单测工作目录应为 app 模块根）", bridge.exists())
        return bridge.readText()
    }

    /** `gitCloneDetailed` 的函数体（到下一个顶层 `suspend fun` 之前）。 */
    private fun cloneBody(): String {
        val src = source()
        val start = src.indexOf("suspend fun gitCloneDetailed")
        assertTrue("RustBridge 里找不到 gitCloneDetailed —— 改名了就要一起改这个钉子", start >= 0)
        val next = src.indexOf("\n    suspend fun ", start + 1)
        return if (next > start) src.substring(start, next) else src.substring(start)
    }

    @Test
    fun `clone 失败原因走统一归一（300 字符）而不是就地截短`() {
        assertTrue(
            "gitCloneDetailed 必须用 engineErrorOrNull 归一失败原因（见本测试的说明）",
            cloneBody().contains("engineErrorOrNull"),
        )
    }

    @Test
    fun `桥接层不再出现 120 字符截断`() {
        assertFalse(
            "找到 take(120)：失败原因被截短过，路径/文件名会再次被切掉",
            source().contains("take(120)"),
        )
    }
}
