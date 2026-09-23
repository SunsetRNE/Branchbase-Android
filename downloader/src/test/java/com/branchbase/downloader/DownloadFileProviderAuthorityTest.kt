package com.branchbase.downloader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileProvider authority 的**两处声明**必须一致（代码常量 ↔ 模块清单）。
 *
 * 这条契约此前**既没有钉子也没有文档**。它的失败方式很安静：编译通过、单测全绿，
 * 直到真机上点「安装 / 打开 / 分享」才抛 `IllegalArgumentException: Failed to find configured root`
 * —— 那时已经发版了。套路同 `:app` 的 `SettingsSpecTest`（源码级钉子）。
 */
class DownloadFileProviderAuthorityTest {

    private fun source(path: String) = File(path).readText()

    @Test
    fun 清单声明的_authority_与代码常量一致() {
        val manifest = source("src/main/AndroidManifest.xml")
        val expected = "android:authorities=\"\${applicationId}${DownloadActions.FILE_PROVIDER_SUFFIX}\""
        assertTrue("清单里必须原样声明 $expected", manifest.contains(expected))
    }

    @Test
    fun authority_后缀在模块内只有一处声明() {
        val hits = File("src/main")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains(DownloadActions.FILE_PROVIDER_SUFFIX) }
            .map { it.name }
            .sorted()
            .toList()
        assertEquals(
            "authority 后缀只应在 DownloadActions.kt 声明一次（散开就会与清单分叉）",
            listOf("DownloadActions.kt"),
            hits,
        )
    }
}
