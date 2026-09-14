package com.branchbase.ui.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 日志文件必须是**追加**写（语义钉子）。
 *
 * ## 为什么要单独钉这一条
 *
 * `Logging.kt` 的异步写盘（`FileAppender.drainLoop`）一度用 `file.bufferedWriter()` 打开文件 ——
 * 那是 `File.outputStream()`，**截断模式**：每批积压落盘都会把之前的行整片冲掉，磁盘上永远只剩
 * 最近一批（真机实测常常只有一行）。而「设置 → 日志 → 导出 .log」读的正是这个文件
 * （`LogScreen.kt` 的 `logFile()?.readText()`），于是导出出来的日志几乎是空的。
 *
 * 这类「异步重构顺手改掉的语义」既不报编译错、也不在页面上报错：原来每条 `appendText`
 * 一次开写关，改成「后台线程批量写」时顺手换成了 `bufferedWriter()`，追加就变成了覆盖。
 * 只能靠钉子盯住，所以这里不测线程、不碰 Android，只测打开方式本身。
 */
class LogFileAppendTest {

    @Test
    fun `两批日志落盘后都能读到，第二批不冲掉第一批`() {
        val file = File.createTempFile("branchbase-log", ".log")
        try {
            openLogFileForAppend(file).use {
                it.append("15:21:12.000 [UI] [Compose] INFO 进入个人主页\n")
            }
            openLogFileForAppend(file).use {
                it.append("15:21:27.043 [网络] [GitHubAPI] INFO GET /user/repos → 200（56 个仓库）\n")
            }

            assertEquals(
                "第二批把第一批冲掉了：写盘用了截断模式（file.bufferedWriter()）",
                "15:21:12.000 [UI] [Compose] INFO 进入个人主页\n" +
                    "15:21:27.043 [网络] [GitHubAPI] INFO GET /user/repos → 200（56 个仓库）\n",
                file.readText(),
            )
        } finally {
            file.delete()
        }
    }
}
