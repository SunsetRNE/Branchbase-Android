package com.branchbase.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调度器单测：串行、退避重试、两级熔断。
 *
 * 这里用**注入的 `sleep`** 把等待压成 0，所以能直接断言退避序列；
 * 用脚本化的假引擎，所以能精确控制「第几次失败、失败成什么样」。
 */
class TranslateSchedulerTest {

    /** 按调用序号返回预设结果的假引擎。 */
    private class ScriptedEngine(private val script: (Int) -> EngineResult) : TranslateEngine {
        var calls = 0
        val texts = mutableListOf<String>()

        override suspend fun translate(text: String, from: String, to: String): EngineResult {
            texts += text
            return script(calls++)
        }
    }

    private fun scheduler(
        engine: TranslateEngine,
        config: SchedulerConfig = SchedulerConfig(maxRetries = 0, baseBackoffMs = 100),
        delays: MutableList<Long> = mutableListOf(),
    ) = TranslateScheduler(engine, config) { delays += it }

    @Test
    fun `成功直接返回`() = runBlocking {
        val engine = ScriptedEngine { EngineResult.Ok("你好") }
        val r = scheduler(engine).call("hello", LANG_EN, LANG_ZH)
        assertEquals(EngineResult.Ok("你好"), r)
        assertEquals(1, engine.calls)
    }

    @Test
    fun `网络失败按指数退避重试`() = runBlocking {
        val delays = mutableListOf<Long>()
        val engine = ScriptedEngine { i ->
            if (i < 2) EngineResult.Fail(FailKind.NETWORK, "timeout") else EngineResult.Ok("好了")
        }
        val s = scheduler(engine, SchedulerConfig(maxRetries = 2, baseBackoffMs = 100), delays)

        assertEquals(EngineResult.Ok("好了"), s.call("hello", LANG_EN, LANG_ZH))
        assertEquals(3, engine.calls)
        assertEquals(listOf(100L, 200L), delays)
        assertEquals(0, s.state().consecutiveFailures)   // 成功后退避计数清零
    }

    @Test
    fun `额度用尽立刻熔断_后续请求不再碰网络`() = runBlocking {
        val engine = ScriptedEngine { EngineResult.Fail(FailKind.QUOTA, "ALL QUERIES EXHAUSTED") }
        val s = scheduler(engine)

        val first = s.call("hello", LANG_EN, LANG_ZH)
        assertTrue(first is EngineResult.Fail)
        assertEquals(FailKind.QUOTA, (first as EngineResult.Fail).kind)
        assertTrue(s.state().quotaBlocked)
        assertEquals("quota", s.state().pageStatus())

        // 第二次：熔断期内直接失败，绝不能再发请求（重试只会浪费剩余额度）
        s.call("world", LANG_EN, LANG_ZH)
        assertEquals(1, engine.calls)
    }

    @Test
    fun `连续失败达到阈值后暂停_用户重试可恢复`() = runBlocking {
        val engine = ScriptedEngine { EngineResult.Fail(FailKind.UNSUPPORTED, "langpair invalid") }
        val s = scheduler(engine)

        repeat(3) { s.call("hello $it", LANG_EN, LANG_ZH) }
        assertEquals(3, engine.calls)
        assertTrue(s.state().paused)
        assertFalse(s.state().usable)
        assertEquals("paused", s.state().pageStatus())

        s.call("hello again", LANG_EN, LANG_ZH)          // 暂停期：不打网络
        assertEquals(3, engine.calls)

        s.reset()
        assertTrue(s.state().usable)
        s.call("hello after reset", LANG_EN, LANG_ZH)
        assertEquals(4, engine.calls)
    }

    @Test
    fun `Key 无效时立刻停发_状态标成 auth 且不累计连续失败`() = runBlocking {
        val engine = ScriptedEngine { EngineResult.Fail(FailKind.AUTH, "401 Authentication Fails") }
        val s = scheduler(engine)

        val first = s.call("hello", LANG_EN, LANG_ZH)
        assertEquals(FailKind.AUTH, (first as EngineResult.Fail).kind)
        assertEquals("auth", s.state().pageStatus())
        assertFalse(s.state().usable)
        // 关键：不算「连续失败」——用户改完 Key 回来，不该还需要额外点一次重试才解除暂停
        assertEquals(0, s.state().consecutiveFailures)
        assertFalse(s.state().paused)

        s.call("world", LANG_EN, LANG_ZH)
        assertEquals("熔断期内不应再发请求", 1, engine.calls)

        s.reset()
        assertTrue(s.state().usable)
        s.call("after fix", LANG_EN, LANG_ZH)
        assertEquals(2, engine.calls)
    }

    @Test
    fun `额度熔断优先于暂停展示_但两者都能停发`() = runBlocking {
        val engine = ScriptedEngine { EngineResult.Fail(FailKind.QUOTA, "402 Insufficient Balance") }
        val s = scheduler(engine)
        s.call("hello", LANG_EN, LANG_ZH)
        assertTrue(s.state().quotaBlocked)
        assertEquals("quota", s.state().pageStatus())
        s.call("world", LANG_EN, LANG_ZH)
        assertEquals(1, engine.calls)
    }

    @Test
    fun `退避序列按倍数增长并封顶`() {
        val s = TranslateScheduler(TranslateEngine.NONE, SchedulerConfig(baseBackoffMs = 400, maxBackoffMs = 4000)) {}
        assertEquals(400L, s.backoffMs(1))
        assertEquals(800L, s.backoffMs(2))
        assertEquals(1600L, s.backoffMs(3))
        assertEquals(3200L, s.backoffMs(4))
        assertEquals(4000L, s.backoffMs(5))
        assertEquals(4000L, s.backoffMs(9))
    }
}
