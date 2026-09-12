package com.branchbase.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 构建校验状态机单测。
 *
 * 这一层必须测：横幅是**结论**，判错方向比不显示更糟 ——
 * 例如把「本地自编译包」判成「校验成功」，用户会以为手里这个 APK 来自官方发布。
 * 五态之间还有优先级（请求中 > 本地编译 > 远端不可用 > 一致/不一致），
 * 顺序写错同样会给出误导性结论。
 */
class SigningVerifyTest {

    private val local = "AC:AB:BC:09:9F:91:81:8B:58:C5:45:DD:7F:D6:4D:E5:E2:8D:13:31"

    @Test
    fun `请求中优先于其它一切判定`() {
        // 即使本地是 UNKNOWN、远端为空，只要还在请求中就必须显示「校验中」，
        // 否则会先闪一个「本地编译版本」再跳成别的结论
        assertEquals(
            BuildVerifyState.Checking,
            buildVerifyState(ReleaseVariant.UNKNOWN, "", null, checking = true),
        )
        assertEquals(
            BuildVerifyState.Checking,
            buildVerifyState(ReleaseVariant.BETA, local, local, checking = true),
        )
    }

    @Test
    fun `本地编译版本不参与远端校验`() {
        // debug / 自签包：即便远端有指纹也不该拿它做比对
        assertEquals(
            BuildVerifyState.LocalBuild,
            buildVerifyState(ReleaseVariant.UNKNOWN, local, local, checking = false),
        )
        // 指纹读取失败同样按本地编译处理，而不是报「不一致」
        assertEquals(
            BuildVerifyState.LocalBuild,
            buildVerifyState(ReleaseVariant.BETA, "", null, checking = false),
        )
    }

    @Test
    fun `远端文件取不到时给出独立的不可用态`() {
        assertEquals(
            BuildVerifyState.RemoteUnavailable,
            buildVerifyState(ReleaseVariant.BETA, local, null, checking = false),
        )
        assertEquals(
            BuildVerifyState.RemoteUnavailable,
            buildVerifyState(ReleaseVariant.RELEASE, local, "   ", checking = false),
        )
    }

    @Test
    fun `指纹一致判成功_大小写不敏感`() {
        assertEquals(
            BuildVerifyState.Matched,
            buildVerifyState(ReleaseVariant.BETA, local, local, checking = false),
        )
        assertEquals(
            BuildVerifyState.Matched,
            buildVerifyState(ReleaseVariant.RELEASE, local.lowercase(), local.uppercase(), checking = false),
        )
    }

    @Test
    fun `指纹不一致判失败`() {
        val other = "B3:72:AB:52:EE:47:A0:8E:45:26:6F:1C:11:E0:75:6D:86:E3:83:A0:74:BE:EB:A3:77:FD:3E:BA:7C:F7:99:94"
        assertEquals(
            BuildVerifyState.Mismatched,
            buildVerifyState(ReleaseVariant.BETA, local, other, checking = false),
        )
    }

    @Test
    fun `指纹短显只取前四组`() {
        assertEquals("AC:AB:BC:09…", fingerprintShort(local))
        assertEquals("AC:AB", fingerprintShort("AC:AB", groups = 4))
        assertEquals("（读取失败）", fingerprintShort(""))
    }

    // ── 紧凑版关于页的文案（胶囊短标签 + 一行说明）──
    // 关于页要在一屏内说清「这个包是不是官方的」：结论进胶囊、依据进说明。
    // 以前这段文案写在 UI 里，改版时最容易漏掉某一态（会出现空胶囊或只有结论没有依据），
    // 所以抽成纯函数在这里逐态钉住。

    private fun copyOf(
        state: BuildVerifyState,
        variant: ReleaseVariant = ReleaseVariant.BETA,
        remote: String? = local,
    ) = verifyCopy(state, variant, local, remote)

    /** 五态清单（`BuildVerifyState` 是 sealed interface，没有 `entries`）。 */
    private val allStates = listOf(
        BuildVerifyState.Checking,
        BuildVerifyState.LocalBuild,
        BuildVerifyState.RemoteUnavailable,
        BuildVerifyState.Matched,
        BuildVerifyState.Mismatched,
    )

    @Test
    fun `五态都有非空的胶囊与说明`() {
        allStates.forEach { state ->
            val copy = copyOf(state)
            assertTrue("$state 的胶囊标签不能为空", copy.chip.isNotBlank())
            assertTrue("$state 的说明不能为空", copy.detail.isNotBlank())
        }
    }

    @Test
    fun `胶囊标签是短结论且能一眼区分`() {
        assertEquals("✓ 签名一致", copyOf(BuildVerifyState.Matched).chip)
        assertEquals("✗ 签名不一致", copyOf(BuildVerifyState.Mismatched).chip)
        assertEquals("校验中…", copyOf(BuildVerifyState.Checking, remote = null).chip)
        assertEquals("本地编译", copyOf(BuildVerifyState.LocalBuild, ReleaseVariant.UNKNOWN).chip)
        assertEquals("无法校验", copyOf(BuildVerifyState.RemoteUnavailable, remote = null).chip)
        // 五种状态两两不同：胶囊是结论，不能出现两个状态同一句话
        val chips = allStates.map { copyOf(it, remote = null).chip }
        assertEquals(chips.size, chips.distinct().size)
    }

    @Test
    fun `说明里带上依据而不是只重复结论`() {
        // 一致：给出与哪份远端文件比对的 + 指纹短显
        val matched = copyOf(BuildVerifyState.Matched).detail
        assertTrue("一致态要说明比对对象：$matched", matched.contains("测试版"))
        assertTrue("一致态要给指纹短显：$matched", matched.contains("AC:AB:BC:09…"))

        // 不一致：两个指纹都要给，并且明确提示风险
        val other = "B3:72:AB:52:EE:47:A0:8E:45:26:6F:1C:11:E0:75:6D:86:E3:83:A0:74:BE:EB:A3:77:FD:3E:BA:7C:F7:99:94"
        val mismatched = verifyCopy(BuildVerifyState.Mismatched, ReleaseVariant.BETA, local, other).detail
        assertTrue("不一致态要给出本地指纹：$mismatched", mismatched.contains("AC:AB:BC:09…"))
        assertTrue("不一致态要给出远端指纹：$mismatched", mismatched.contains("B3:72:AB:52…"))
        assertTrue("不一致态要给出处置建议：$mismatched", mismatched.contains("建议立即卸载"))

        // 本地编译：要说明「为什么不校验」，而不是让用户以为网络坏了
        assertTrue(copyOf(BuildVerifyState.LocalBuild, ReleaseVariant.UNKNOWN).detail.contains("不参与远端校验"))
    }
}
