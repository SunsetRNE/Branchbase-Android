package com.branchbase.ui.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志列表 key 的单测（2026-09-23 真机闪退的直接产物）。
 *
 * ## 现场（设备 crash buffer 原文）
 *
 * ```
 * java.lang.IllegalArgumentException: Key "1790084432978L1 直出（含过期）repo-info:SunsetRNE/Branchbase-Android"
 *   was already used. If you are using LazyColumn/Row please make sure you provide a unique key for each item.
 *   at androidx.compose.ui.layout.LayoutNodeSubcompositionsState.subcompose(SubcomposeLayout.kt:1591)
 *   at androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScopeImpl.compose(...)
 * ```
 *
 * 旧 key 是 `it.time.toString() + it.message`：**同一毫秒落两条同文案的日志**就撞车。
 * 而那恰恰是缓存日志的常态（`L1 直出（含过期）repo-info:…` 一次进入仓库页会打两遍、
 * 经常落在同一毫秒），所以「日志越多越容易撞」—— 用户的原话是
 * 「日志页面加载太多日志会闪退」。崩溃发生在滚动到那一项、它被测量组合的那一刻
 * （栈里是 fling → subcompose），不在进页面时。
 *
 * ## 钉住什么
 *
 * 1. key 必须在**同一毫秒 + 同一文案**下仍然两两不同（这是崩的那一种输入）；
 * 2. 序号单调递增（列表是「最新在前」的，key 必须能表达顺序，不能回绕或重复）；
 * 3. 时间戳**不许**再参与 key —— 它不唯一，用它就是把这个崩溃请回来。
 */
class LogListKeyTest {

    /** 造出一条日志（走真实的 [LogManager.log]，不是自己 new [LogEntry]）。 */
    private fun logOnce(message: String) =
        LogManager.log(LogCategory.NETWORK, LogLevel.DEBUG, "缓存", message)

    /**
     * **确定性**的对抗输入：同一条日志复制一份、只改序号。
     *
     * 不依赖「两次 `System.currentTimeMillis()` 恰好落在同一毫秒」（那会偶发红），
     * 直接把当年崩掉的那组输入按字面造出来 —— 旧 key（`time + message`）在这里必红。
     */
    @Test
    fun `同一毫秒同一文案的两条日志_key 必须不同`() {
        // 时间戳取自真机 crash log 里那个 key 的前半段
        val a = LogEntry(
            seq = 1,
            time = 1_790_084_432_978L,
            category = LogCategory.NETWORK,
            level = LogLevel.DEBUG,
            tag = "缓存",
            message = "L1 直出（含过期）repo-info:SunsetRNE/Sundown",
        )
        val b = a.copy(seq = 2)

        assertEquals("这一组输入的前提：时间戳与文案都相同", a.time, b.time)
        assertEquals(a.message, b.message)
        assertNotEquals(
            "旧 key 是 `time + message` ⇒ 这里会生成同一个 key，" +
                "LazyColumn 直接抛 IllegalArgumentException: Key \"…\" was already used（真机抓到过）",
            logItemKey(a),
            logItemKey(b),
        )
    }

    @Test
    fun `序号单调递增_列表最新在前也能当 key`() {
        LogManager.clear()
        repeat(5) { logOnce("第 $it 条") }

        // all() 是「最新在前」（缓冲 addFirst），序号应当严格递减
        val seqs = LogManager.all().map { it.seq }
        assertEquals(5, seqs.size)
        assertEquals("序号必须两两不同", 5, seqs.toSet().size)
        assertTrue(
            "最新的那条序号最大（all() 第 0 条是最新的）：$seqs",
            seqs[0] > seqs.last(),
        )
    }

    @Test
    fun `key 就是序号本身`() {
        LogManager.clear()
        logOnce("x")
        val e = LogManager.all().first()
        assertEquals("列表 key 只该用 seq —— 时间戳不唯一", e.seq, logItemKey(e))
    }
}
