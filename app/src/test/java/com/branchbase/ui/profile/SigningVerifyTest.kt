package com.branchbase.ui.profile

import org.junit.Assert.assertEquals
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
}
