package com.branchbase.ui.profile

import com.branchbase.R
import com.branchbase.ui.LocalizedText
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
        // 返回值是 LocalizedText：截断后的指纹走 raw，读不到时才用资源（§5.4 C′）
        assertEquals("AC:AB:BC:09…", fingerprintShort(local).raw)
        assertEquals("AC:AB", fingerprintShort("AC:AB", groups = 4).raw)
        assertEquals(R.string.verify_fingerprint_unreadable, fingerprintShort("").res)
    }

    // ── 紧凑版关于页的文案（胶囊短标签 + 一行说明）──
    // 关于页要在一屏内说清「这个包是不是官方的」：结论进胶囊、依据进说明。
    // 以前这段文案写在 UI 里，改版时最容易漏掉某一态（会出现空胶囊或只有结论没有依据），
    // 所以抽成纯函数在这里逐态钉住。

    // `verifyCopy` 现在只返回 `LocalizedText`（§5.4 C′），不再产出中文：这里传的是
    // **变体名的中文样本**，只用于检查它被原样放进 `%1$s` 参数；界面侧走 stringResource。
    // 断言一律看资源 ID + args，不看会被搬走的字面量。
    private fun copyOf(
        state: BuildVerifyState,
        variantLabel: String = "测试版",
        remote: String? = local,
    ) = verifyCopy(state, variantLabel, local, remote)

    /** 一条 LocalizedText 是否“有内容”：有资源，或 raw 非空。 */
    private fun LocalizedText.hasContent() = res != 0 || raw.isNotBlank()

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
            assertTrue("$state 的胶囊标签不能为空", copy.chip.hasContent())
            assertTrue("$state 的说明不能为空", copy.detail.hasContent())
            // 而且必须是资源（同一句文案要能跟界面语言走），不能是写死的中文
            assertTrue("$state 的胶囊标签必须是资源", copy.chip.res != 0)
            assertTrue("$state 的说明必须是资源", copy.detail.res != 0)
        }
    }

    @Test
    fun `胶囊标签是短结论且能一眼区分`() {
        assertEquals(R.string.verify_chip_matched, copyOf(BuildVerifyState.Matched).chip.res)
        assertEquals(R.string.verify_chip_mismatched, copyOf(BuildVerifyState.Mismatched).chip.res)
        assertEquals(R.string.verify_chip_checking, copyOf(BuildVerifyState.Checking, remote = null).chip.res)
        assertEquals(R.string.verify_chip_local_build, copyOf(BuildVerifyState.LocalBuild, "异常").chip.res)
        assertEquals(R.string.verify_chip_unavailable, copyOf(BuildVerifyState.RemoteUnavailable, remote = null).chip.res)
        // 五种状态两两不同：胶囊是结论，不能出现两个状态同一句话
        val chips = allStates.map { copyOf(it, remote = null).chip.res }
        assertEquals(chips.size, chips.distinct().size)
    }

    @Test
    fun `说明里带上依据而不是只重复结论`() {
        // 一致：给出与哪份远端文件比对的 + 指纹短显（参数按 %1$s / %2$s 的顺序）
        val matched = copyOf(BuildVerifyState.Matched).detail
        assertEquals(R.string.verify_detail_matched, matched.res)
        assertEquals("测试版", matched.args[0])
        assertEquals("AC:AB:BC:09…", (matched.args[1] as LocalizedText).raw)

        // 不一致：两个指纹都要给（处置建议在资源里，不在这里拼）
        val other = "B3:72:AB:52:EE:47:A0:8E:45:26:6F:1C:11:E0:75:6D:86:E3:83:A0:74:BE:EB:A3:77:FD:3E:BA:7C:F7:99:94"
        val mismatched = verifyCopy(BuildVerifyState.Mismatched, "测试版", local, other).detail
        assertEquals(R.string.verify_detail_mismatched, mismatched.res)
        assertEquals("AC:AB:BC:09…", (mismatched.args[0] as LocalizedText).raw)
        assertEquals("B3:72:AB:52…", (mismatched.args[1] as LocalizedText).raw)

        // 本地编译：要用「不参与远端校验」那一条，而不是让用户以为网络坏了
        assertEquals(
            R.string.verify_detail_local_build,
            copyOf(BuildVerifyState.LocalBuild, "异常").detail.res,
        )
    }
}
