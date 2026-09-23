package com.branchbase

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OAuth 回调深链的**两处声明**必须一致（`AndroidManifest.xml` ↔ [OAuthDeepLink]）。
 *
 * 这条契约此前**既没有钉子也没有文档**：三段字面量各写一份，改错一处的症状是
 * 「浏览器里授权成功、回到 App 毫无反应」—— 不报错、不崩溃，只能靠人去比对。
 * 套路同 `ui/profile/SettingsSpecTest`（源码级钉子）。
 */
class OAuthDeepLinkTest {

    private fun source(path: String) = File(path).readText()

    @Test
    fun 清单声明的三段与判定常量逐字一致() {
        val manifest = source("src/main/AndroidManifest.xml")
        assertTrue(
            "清单必须声明 scheme=${OAuthDeepLink.SCHEME}",
            manifest.contains("android:scheme=\"${OAuthDeepLink.SCHEME}\""),
        )
        assertTrue(
            "清单必须声明 host=${OAuthDeepLink.HOST}",
            manifest.contains("android:host=\"${OAuthDeepLink.HOST}\""),
        )
        assertTrue(
            "清单必须声明 path=${OAuthDeepLink.PATH}",
            manifest.contains("android:path=\"${OAuthDeepLink.PATH}\""),
        )
    }

    @Test
    fun 只认三段全中的深链() {
        assertTrue(OAuthDeepLink.matches("branchbase", "oauth", "/callback"))
        // 少一段 / 多一段 / 换了 scheme 都不算命中
        assertFalse(OAuthDeepLink.matches("branchbase", "oauth", null))
        assertFalse(OAuthDeepLink.matches("branchbase", null, "/callback"))
        assertFalse(OAuthDeepLink.matches(null, "oauth", "/callback"))
        assertFalse(OAuthDeepLink.matches("branchbase", "oauth", "/callback/"))
        assertFalse(OAuthDeepLink.matches("https", "oauth", "/callback"))
        assertFalse(OAuthDeepLink.matches("branchbase", "OAuth", "/callback"))
    }
}
