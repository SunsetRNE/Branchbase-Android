package com.branchbase.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端可达性判定的钉子。
 *
 * 这里每一条都对应一个真机上会发作的形状：漏判 VPN 接入 → 「先开 App 再连梯子，远端还是打不开」；
 * 误判同网抖动 → 弱网下反复重置连接池、反复打 `/user`。判定是纯函数，必须逐条锁死。
 */
class ReachabilityPolicyTest {

    // ── 构造快照的小工具（默认：直连、已校验、不计费） ──

    private fun direct(handle: Long = 100L, metered: Boolean = false) = NetworkSnapshot(
        hasDefaultNetwork = true,
        validated = true,
        vpn = false,
        metered = metered,
        networkHandle = handle,
    )

    private fun vpn(handle: Long = 200L, metered: Boolean = false) = NetworkSnapshot(
        hasDefaultNetwork = true,
        validated = true,
        vpn = true,
        metered = metered,
        networkHandle = handle,
    )

    private fun offline(hasNetwork: Boolean = true) = NetworkSnapshot(
        hasDefaultNetwork = hasNetwork,
        validated = false,
        vpn = false,
        metered = true,
        networkHandle = if (hasNetwork) 300L else 0L,
    )

    // ── 三档判定 ──

    @Test
    fun `未接入 VPN 的可用网络判为直连`() {
        assertEquals(NetworkKind.DIRECT, direct().kind)
        assertTrue(direct().online)
    }

    @Test
    fun `带 VPN 出口的网络判为 VPN`() {
        assertEquals(NetworkKind.VPN, vpn().kind)
        assertTrue(vpn().online)
    }

    @Test
    fun `连上但没通过校验的网络仍算离线`() {
        // 「已连接」不等于「能出网」：门户未登录 / 上游断了都长这样
        assertEquals(NetworkKind.OFFLINE, offline(hasNetwork = true).kind)
        assertFalse(offline(hasNetwork = true).online)
        // 根本没有默认网络同理
        assertEquals(NetworkKind.OFFLINE, offline(hasNetwork = false).kind)
        assertEquals(NetworkKind.OFFLINE, NetworkSnapshot.OFFLINE.kind)
    }

    @Test
    fun `VPN 刚建链还没继承到 VALIDATED 也算已接入`() {
        // 设备实测（OnePlus / Android 16，FlClash）：VPN 网络的 VALIDATED 继承自底层网络，
        // 建链那一瞬可能还没写上。若据此判成离线，最该抓的 VPN_UP 会被漏掉。
        val fresh = NetworkSnapshot(
            hasDefaultNetwork = true,
            validated = false,
            vpn = true,
            metered = false,
            networkHandle = 200L,
        )
        assertEquals(NetworkKind.VPN, fresh.kind)
        assertTrue(fresh.online)
        assertEquals(ReprobeReason.VPN_UP, decideReprobe(previous = direct(), next = fresh))
    }

    // ── 需要重探的四种跃迁 ──

    @Test
    fun `没开 VPN 时打开 App 之后再接入 VPN 必须重探`() {
        // 本机制存在的理由：启动时的连接池与账号结论都建立在「直连」那条路径上
        assertEquals(ReprobeReason.VPN_UP, decideReprobe(previous = direct(), next = vpn()))
    }

    @Test
    fun `断开 VPN 回到直连也要重探`() {
        assertEquals(ReprobeReason.VPN_DOWN, decideReprobe(previous = vpn(), next = direct()))
    }

    @Test
    fun `从出不了网恢复成有出口要重探`() {
        assertEquals(ReprobeReason.RESTORED, decideReprobe(previous = offline(), next = direct()))
        assertEquals(ReprobeReason.RESTORED, decideReprobe(previous = offline(), next = vpn()))
        assertEquals(ReprobeReason.RESTORED, decideReprobe(previous = NetworkSnapshot.OFFLINE, next = vpn()))
    }

    @Test
    fun `同为直连但换了另一张网要重探`() {
        // Wi-Fi ↔ 移动数据：池里的连接同样绑在旧链路上
        assertEquals(ReprobeReason.SWITCHED, decideReprobe(previous = direct(handle = 1L), next = direct(handle = 2L)))
    }

    @Test
    fun `VPN 接入优先记成 VPN_UP 而不是切换网络`() {
        // VPN 挂上时默认网络句柄也会变；原因记错会让日志与后续排查都指错方向
        val reason = decideReprobe(previous = direct(handle = 1L), next = vpn(handle = 2L))
        assertEquals(ReprobeReason.VPN_UP, reason)
    }

    // ── 不该重探的形状（防抖 / 防折腾） ──

    @Test
    fun `首次拿到网络事实只作基线不重探`() {
        // 进程刚起：客户端本来就是新建的，没有陈旧结论可丢；此处重探只是白打一次 /user
        assertNull(decideReprobe(previous = null, next = direct()))
        assertNull(decideReprobe(previous = null, next = vpn()))
        assertNull(decideReprobe(previous = null, next = offline()))
    }

    @Test
    fun `同一个网络的带宽与计费抖动不重探`() {
        assertNull(decideReprobe(previous = direct(metered = false), next = direct(metered = true)))
        assertNull(decideReprobe(previous = vpn(metered = false), next = vpn(metered = true)))
    }

    @Test
    fun `新态仍然出不了网时不重探`() {
        // 典型是 VPN 建链中途：旧网已断、VPN 还没拿到 VALIDATED —— 这时重探只会拿到失败
        assertNull(decideReprobe(previous = direct(), next = offline()))
        assertNull(decideReprobe(previous = offline(), next = offline()))
    }

    @Test
    fun `状态完全没变当然不重探`() {
        assertNull(decideReprobe(previous = direct(), next = direct()))
        assertNull(decideReprobe(previous = vpn(), next = vpn()))
    }

    // ── 日志用的描述 ──

    @Test
    fun `日志描述带档位与句柄`() {
        assertEquals("直连(handle=100，不计费)", direct().label)
        assertEquals("VPN(handle=200，计费)", vpn(metered = true).label)
        assertEquals("离线", offline().label)
    }

    @Test
    fun `三档与四种重探原因的文案互不重复`() {
        // 文案是日志与排障的第一入口：两档同名会让人分不清到底走没走 VPN
        assertEquals(3, NetworkKind.entries.map { it.label }.toSet().size)
        assertEquals(4, ReprobeReason.entries.map { it.label }.toSet().size)
    }
}
