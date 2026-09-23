package com.branchbase.ui.repository

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「CI 产物 → 可安装 APK」的钉子。
 *
 * ## 现场
 *
 * 产物是发布页之外的第二条取包通道。列表接口对刚发布的 release 会有一段空 assets 的
 * 缓存窗口（约 1~2 小时），那段时间只能靠产物 —— 但这条路原来**走到一半就断了**：
 * 发布附件下载 `.apk` 能直接装，产物下载的是 `.zip`，而 `app` / `downloader` / `core`
 * 三个模块里 `ZipFile` / `ZipInputStream` **零命中**，手机上根本没有解它的能力。
 *
 * Actions 的产物**下载时永远是 zip**，去不掉这层壳。既然去不掉，就把壳做成确定性的：
 * 工作流侧拆成「一个 APK 一个 artifact」，App 侧只在**恰好一个 APK** 时才动手。
 *
 * ## 这个文件钉的是什么
 *
 * 挑选规则是这段代码全部的判断力所在 —— 挑错就会装上一个用户没想要、
 * 甚至不该装的包（debug 与 perfBeta 是两个不同签名的变体，装错要卸载重来）。
 */
class ArtifactInstallTest {

    // ── 一、挑哪个条目 ────────────────────────────────────────────────

    @Test
    fun `恰好一个 apk 才认`() {
        assertEquals(
            "Branchbase-1.0.73-debug.apk",
            pickSingleApkEntry(listOf("Branchbase-1.0.73-debug.apk")),
        )
    }

    @Test
    fun `一个都没有返回 null`() {
        assertNull(pickSingleApkEntry(emptyList()))
        assertNull(pickSingleApkEntry(listOf("signature.txt", "libbranchbase_core.so")))
    }

    @Test
    fun `两个以上不猜`() {
        // 混装产物（debug + perfBeta）正是改造前的形态。猜错的代价是装错变体，
        // 所以这里必须返回 null，让 UI 如实说「无法直接安装」。
        assertNull(
            pickSingleApkEntry(
                listOf("Branchbase-x-debug.apk", "Branchbase-x-perfBeta.apk"),
            ),
        )
    }

    @Test
    fun `目录条目不算 apk`() {
        assertNull(pickSingleApkEntry(listOf("apk/")))
        assertEquals("a.apk", pickSingleApkEntry(listOf("apk/", "a.apk")))
    }

    @Test
    fun `子目录里的 apk 也算`() {
        // Actions 产物可能带一层目录；只按文件名判定，不看路径
        assertEquals("dist/a.apk", pickSingleApkEntry(listOf("dist/a.apk")))
    }

    @Test
    fun `后缀要真的是 apk`() {
        // `.apks` / `.apk.bak` / `.apk.txt` 都不是能装的包
        assertNull(pickSingleApkEntry(listOf("bundle.apks")))
        assertNull(pickSingleApkEntry(listOf("a.apk.bak")))
        assertNull(pickSingleApkEntry(listOf("a.apk.txt")))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals("A.APK", pickSingleApkEntry(listOf("A.APK")))
    }

    // ── 二、真解一个 zip ──────────────────────────────────────────────

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val f = Files.createTempFile("artifact", ".zip").toFile()
        ZipOutputStream(f.outputStream()).use { zos ->
            entries.forEach { (name, body) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `解出唯一 apk 并落到目标目录`() {
        val zip = zipOf("Branchbase-1.0.73-perfBeta.apk" to "APKDATA", "signature.txt" to "x")
        val dir = Files.createTempDirectory("wf").toFile()
        val apk = extractSingleApk(zip, dir)
        assertEquals("Branchbase-1.0.73-perfBeta.apk", apk?.name)
        assertEquals("APKDATA", apk?.readText())
    }

    @Test
    fun `混装产物拒绝解压`() {
        val zip = zipOf("a-debug.apk" to "1", "a-perfBeta.apk" to "2")
        val dir = Files.createTempDirectory("wf").toFile()
        assertNull(extractSingleApk(zip, dir))
    }

    @Test
    fun `带路径的条目只取文件名`() {
        // zip-slip 在这里天然不成立：输出路径只取条目名最后一段，
        // 归档里的相对路径一律不采纳（不是靠过滤 `..`，是靠不采纳）
        val zip = zipOf("../../evil.apk" to "X")
        val dir = Files.createTempDirectory("wf").toFile()
        val apk = extractSingleApk(zip, dir)
        assertEquals("evil.apk", apk?.name)
        assertEquals("应当落在目标目录内", dir.canonicalPath, apk?.parentFile?.canonicalPath)
        assertTrue("不得写到目标目录之外", apk!!.canonicalPath.startsWith(dir.canonicalPath))
    }

    @Test
    fun `不是 zip 时返回 null 而不是抛`() {
        val f = Files.createTempFile("notzip", ".zip").toFile().apply { writeText("这不是 zip") }
        val dir = Files.createTempDirectory("wf").toFile()
        assertNull(extractSingleApk(f, dir))
    }
}
