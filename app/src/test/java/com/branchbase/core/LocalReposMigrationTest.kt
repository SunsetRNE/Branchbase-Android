package com.branchbase.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地仓库目录迁移（外部存储 → 内部存储，1.0.91 的兼容处理）的钉子。
 *
 * ## 它守的是哪条结论
 *
 * 真机日志里 clone 稳定失败在 `.git/HEAD.lock`（见 `LocalRepos.base` 的说明）：
 * App 私有的**外部**存储目录这棵 FUSE 子树对 git 的锁文件语义不兼容，
 * 而同一个 FUSE 的别的子树与 ext4 都正常 → 仓库整体换到内部存储。
 *
 * 于是有两件事必须钉住：
 *
 * 1. **搬迁不能丢东西**：换存储位置是「兼容处理」，不是清理 —— 用户可能已经拉过仓库、
 *    里面有未推送的提交。`moveTree` 先试 rename、跨文件系统退化成复制 + 删源，
 *    两条路都要把整棵树带过去；
 * 2. **别改回去**：`base()` 一旦换回 `getExternalFilesDir`，这台机器上的 clone/commit
 *    会再次坏在锁文件上，而且**只会在真机上坏**（单测跑在 JVM 上，永远发现不了）——
 *    所以这条用源码级钉子钉住（与 `JniSignatureTest` 同一手法）。
 */
class LocalReposMigrationTest {

    private fun tempDir(tag: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "bb-localrepos-$tag-${ProcessHandle.current().pid()}")
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    /** 造一棵「账号目录 → 仓库 → .git」的树，返回 (根, 文件清单)。 */
    private fun seedTree(root: File): List<String> {
        val files = listOf(
            "SunsetRNE/Branchbase-Android/.git/HEAD",
            "SunsetRNE/Branchbase-Android/.git/config",
            "SunsetRNE/Branchbase-Android/.git/refs/heads/main",
            "SunsetRNE/Branchbase-Android/README.md",
            "_guest/scratch/notes.txt",
        )
        files.forEach { rel ->
            val f = File(root, rel)
            f.parentFile?.mkdirs()
            f.writeText("内容：$rel")
        }
        return files
    }

    private fun assertTree(root: File, files: List<String>) {
        files.forEach { rel ->
            val f = File(root, rel)
            assertTrue("搬迁后缺少 $rel", f.exists())
            assertEquals("内容：$rel", f.readText())
        }
    }

    @Test
    fun `moveTree 把整棵树搬到新位置且源不残留`() {
        val src = tempDir("move-src")
        val dst = File(tempDir("move-dst"), "repos")
        val files = seedTree(src)

        assertTrue(moveTree(src, dst))

        assertTree(dst, files)
        assertFalse("搬完源目录不该还在（否则下次启动会再搬一遍）", src.exists())
    }

    @Test
    fun `copyTree 用于跨文件系统的退化路径`() {
        // rename 跨文件系统会失败，moveTree 退化成 copyTree + 删源 —— 这条路径必须自己成立
        val src = tempDir("copy-src")
        val dst = File(tempDir("copy-dst"), "repos")
        val files = seedTree(src)

        assertTrue(copyTree(src, dst))

        assertTree(dst, files)
        assertTrue("复制不删源（删源是 moveTree 的事）", src.exists())
    }

    @Test
    fun `单文件也能搬`() {
        val src = tempDir("file-src")
        val dst = tempDir("file-dst")
        val f = File(src, "branchbase-cacert.pem").apply { writeText("ca") }

        assertTrue(moveTree(f, File(dst, "branchbase-cacert.pem")))
        assertEquals("ca", File(dst, "branchbase-cacert.pem").readText())
        assertFalse(f.exists())
    }

    @Test
    fun `源不存在时返回失败而不是假成功`() {
        // 假成功会让迁移提前写标记文件，把「没搬完」当成「搬完了」
        val missing = File(tempDir("missing"), "nope")
        assertFalse(moveTree(missing, File(tempDir("missing-dst"), "nope")))
    }

    @Test
    fun `仓库根目录走内部存储`() {
        val source = File("src/main/java/com/branchbase/core/LocalRepos.kt")
        assertTrue("找不到 ${source.absolutePath}（单测工作目录应为 app 模块根）", source.exists())
        val text = source.readText()
        val base = text.substringAfter("fun base(context: Context): File =")
            .substringBefore("\n")
        assertTrue("base() 必须落在内部存储：$base", base.contains("noBackupFilesDir"))
        assertFalse(
            "base() 不能再回到外部存储 —— FUSE 子树上的锁文件语义会再次让 clone/commit 坏掉：$base",
            base.contains("getExternalFilesDir"),
        )
    }
}
