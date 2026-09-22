package com.branchbase.ui.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「事件源不可用」记忆的单测。
 *
 * 它防的是一笔**每次都付**的浪费：动态页先走 `/user/events`，失败才回退到有缓存的公开端点；
 * 而失败不留痕（`PageCache.refresh` 只在拿到有效响应时才写缓存），于是每进一次动态页
 * 都要先等一次注定失败的往返。真机日志里 `/user/events:1` 连续三次「未命中」，
 * 同期的 `/users/{login}/events:1` 在 L1/L2 命中。
 */
class EventSourceMemoryTest {

    private fun memory(ttl: Long = 5 * 60 * 1000L) = EventSourceMemory(ttlMs = ttl, clock = { now })

    private var now = 1_000_000L

    @Test
    fun `没标记过就不算不可用`() {
        val m = memory()
        assertFalse(m.isDead("me|/user/events", now))
    }

    @Test
    fun `标记后在窗口内一直算不可用`() {
        val m = memory()
        m.markDead("me|/user/events", now)
        assertTrue(m.isDead("me|/user/events", now))
        assertTrue("窗口内（4 分钟）仍然算不可用", m.isDead("me|/user/events", now + 4 * 60 * 1000))
    }

    @Test
    fun `窗口过期后自动放行`() {
        val m = memory()
        m.markDead("me|/user/events", now)
        assertFalse("过了 TTL 要重新试一次（权限可能刚被授上）", m.isDead("me|/user/events", now + 5 * 60 * 1000))
        // 过期读取会顺手清掉标记
        assertFalse(m.isDead("me|/user/events", now + 5 * 60 * 1000))
    }

    @Test
    fun `拿到数据要立刻撤销标记`() {
        val m = memory()
        m.markDead("me|/user/events", now)
        m.clear("me|/user/events")
        assertFalse(m.isDead("me|/user/events", now))
    }

    @Test
    fun `标记按 key 隔离_换账号或换源互不影响`() {
        val m = memory()
        m.markDead("me|/user/events", now)
        assertFalse("别的账号不能被连坐", m.isDead("other|/user/events", now))
        assertFalse("另一条腿不能被连坐", m.isDead("me|/users/me/events", now))
    }

    @Test
    fun `重复标记保留最近一次时间`() {
        val m = memory()
        m.markDead("me|/user/events", now)
        m.markDead("me|/user/events", now + 4 * 60 * 1000)
        assertTrue("应按最后一次失败起算 TTL", m.isDead("me|/user/events", now + 8 * 60 * 1000))
        assertFalse(m.isDead("me|/user/events", now + 9 * 60 * 1000))
    }
}
