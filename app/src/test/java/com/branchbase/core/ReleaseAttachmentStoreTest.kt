package com.branchbase.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件暂存区与草稿清单：把「路径怎么拼」「什么时候该删」「草稿怎么落盘」这几件
 * 只有真机跑一遍才发现的问题钉在纯函数上（`stage()` 要 Context，不在这里测）。
 */
class ReleaseAttachmentStoreTest {

    // ── 目录布局 ──

    @Test
    fun `三级目录名都要净化_不许出现路径穿越`() {
        // 真实场景：tag 完全可能写成 release/1.2，owner/repo 来自 URL（也不可信）
        val dir = ReleaseAttachmentStore.relativeDir("SunsetRNE", "Branchbase-Android", "release/1.2")
        assertEquals(3, dir.split("/").size)
        assertTrue("tag 里的斜杠不能变成子目录：$dir", dir.endsWith("/1.2"))

        val evil = ReleaseAttachmentStore.relativeDir("..", "..", "..")
        assertFalse("不能出现 ..：$evil", evil.contains(".."))
        assertEquals("download/download/download", evil)
    }

    @Test
    fun `空 tag 落到 _untagged`() {
        assertEquals("o/r/_untagged", ReleaseAttachmentStore.relativeDir("o", "r", ""))
    }

    // ── 过期判定 ──

    @Test
    fun `超过 TTL 才算过期_时间未知的永不过期`() {
        val ttl = ReleaseAttachmentStore.TTL_MS
        val now = 10 * ttl
        assertFalse(ReleaseAttachmentStore.isExpired(now - ttl + 1, now, ttl))
        assertTrue(ReleaseAttachmentStore.isExpired(now - ttl - 1, now, ttl))
        // lastModified 为 0 表示拿不到时间：宁可留着，也不要把用户刚导入的文件删掉
        assertFalse(ReleaseAttachmentStore.isExpired(0L, now, ttl))
    }

    @Test
    fun `pruneExpired 删过期文件与随之空掉的目录_保留新鲜的`() {
        val root = Files.createTempDirectory("release-uploads").toFile()
        val stale = File(root, "o/r/v1/app-release.apk").apply { parentFile.mkdirs(); writeText("old") }
        val fresh = File(root, "o/r/v2/mapping.txt").apply { parentFile.mkdirs(); writeText("new") }
        val part = File(root, "o/r/v2/gh-pages.zip.part").apply { writeText("half") }
        val now = 100L * ReleaseAttachmentStore.TTL_MS
        stale.setLastModified(now - ReleaseAttachmentStore.TTL_MS - 1000)
        fresh.setLastModified(now)
        part.setLastModified(now - ReleaseAttachmentStore.TTL_MS - 1000)

        val removed = ReleaseAttachmentStore.prune(root, now, ReleaseAttachmentStore.TTL_MS)

        assertEquals(2, removed)
        assertFalse("过期附件要删", stale.exists())
        assertFalse("半截文件也要回收", part.exists())
        assertTrue("新鲜附件必须留着", fresh.exists())
        assertFalse("空掉的 tag 目录一并清掉", File(root, "o/r/v1").exists())
        assertTrue("还有文件的目录不能删", File(root, "o/r/v2").isDirectory)
    }

    // ── Content-Type ──

    @Test
    fun `上传时按扩展名给 Content-Type`() {
        assertEquals("application/vnd.android.package-archive", ReleaseAttachmentStore.mimeOf("app-release.apk"))
        assertEquals("application/zip", ReleaseAttachmentStore.mimeOf("gh-pages.ZIP"))
        assertEquals("text/plain", ReleaseAttachmentStore.mimeOf("mapping.txt"))
        assertEquals("application/octet-stream", ReleaseAttachmentStore.mimeOf("noext"))
        assertEquals("application/octet-stream", ReleaseAttachmentStore.mimeOf("weird.bin"))
    }

    // ── 草稿清单 ──

    private fun sampleDraft() = ReleaseDraft(
        releaseId = 42L,
        tag = "v1.0.39",
        title = "发布页重做",
        body = "## 变更\n- 附件",
        target = "main",
        type = "stable",
        latest = true,
        attachments = listOf(
            DraftAttachment(
                name = "app-release.apk",
                size = 13_400_000,
                path = "/data/user/0/com.branchbase/files/../release-uploads/o/r/v1.0.39/app-release.apk",
                mime = "application/vnd.android.package-archive",
                status = AttachmentStatus.FAILED,
                error = "上传失败（可重试）",
            ),
            DraftAttachment(
                name = "mapping.txt",
                size = 1024,
                path = "/sdcard/Download/mapping.txt",
                mime = "text/plain",
                reference = true,
            ),
        ),
        savedAt = 1_700_000_000_000L,
    )

    @Test
    fun `草稿清单可以原样读回来`() {
        val back = ReleaseDraftStore.parse(ReleaseDraftStore.toJson(sampleDraft()))
        assertEquals(sampleDraft(), back)
    }

    @Test
    fun `上传中的状态不落盘_重启后回到待上传`() {
        val drafting = sampleDraft().let { d ->
            d.copy(attachments = d.attachments.map { it.copy(status = AttachmentStatus.UPLOADING) })
        }
        val back = ReleaseDraftStore.parse(ReleaseDraftStore.toJson(drafting))
        assertTrue(
            "「上次传到一半」记下来没有意义：远端收没收到无从判断",
            back.attachments.all { it.status == AttachmentStatus.READY },
        )
    }

    @Test
    fun `坏掉的草稿不会把页面带崩`() {
        // 磁盘上的 json 可能被截断 / 是旧版本写的：读不出来就当没有草稿
        assertTrue(runCatching { ReleaseDraftStore.parse("{ not json") }.isFailure)
        val minimal = ReleaseDraftStore.parse("""{"tag":"v1"}""")
        assertEquals("v1", minimal.tag)
        assertEquals(0, minimal.attachments.size)
        assertEquals("stable", minimal.type)
    }
}
