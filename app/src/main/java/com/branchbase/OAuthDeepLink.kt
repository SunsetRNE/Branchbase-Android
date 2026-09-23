package com.branchbase

/**
 * OAuth 回调深链的**唯一判定**（不依赖 Android 类型，因此可直接单测）。
 *
 * ## 为什么单独成文件
 *
 * `branchbase://oauth/callback` 这个地址**声明在两处**：`AndroidManifest.xml` 的 `intent-filter`
 * 与这里的三条判定。两处差一个字符，症状是「浏览器授权完回到 App 什么也没发生」——
 * 而链路上没有任何东西会报错（回调根本进不来）。
 *
 * 所以判定抽成纯函数，钉子见 `app/src/test/java/com/branchbase/OAuthDeepLinkTest.kt`：
 * 它同时读[清单](src/main/AndroidManifest.xml)与本文件，把两处声明钉在一起。
 */
internal object OAuthDeepLink {

    /** 与清单 `android:scheme` 一致。 */
    const val SCHEME = "branchbase"

    /** 与清单 `android:host` 一致。 */
    const val HOST = "oauth"

    /** 与清单 `android:path` 一致。 */
    const val PATH = "/callback"

    /** 三段全中才算命中（大小写敏感，与清单的匹配语义一致）。 */
    fun matches(scheme: String?, host: String?, path: String?): Boolean =
        scheme == SCHEME && host == HOST && path == PATH
}
