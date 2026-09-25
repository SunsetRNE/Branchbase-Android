package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码页文件树的**展示顺序**钉子（纯函数 + 源码级接线，与 `CodeFolderBackTest` / `GitWorkbenchWiringTest` 同一套路）。
 *
 * 规则（2026-09-26 用户拍板，对齐 GitHub 网页版那一列）：
 * ① 文件夹优先、文件其次；② 同类型内 `.` 开头的排最前（对齐性规则）；③ 其余按名字 A→Z。
 *
 * 为什么必须钉：**GitHub contents API 返回的顺序不是这个**（实测 `rust-lang/rust` 根目录的 API
 * 是混着的纯名字序：`.clang-format` `.github` `.gitignore` `AGENTS.md` `LICENSES` `compiler` …，
 * 目录不提前）；而网页版那一列是「目录全在前 → 文件按名字」。也就是说这条规则**只能在客户端排**，
 * 排错了 API 不会报错、只有真机上肉眼能看出来，所以两头都钉：
 * 纯函数的行为 + 「排序只有 `sortFileTree` 一个真源、渲染层不许再排一遍」。
 *
 * 顺带钉住**日志插桩**（锚点 `代码页文件树`）：顺序是「只看得见」的那类改动，
 * 设备上 grep 一条日志就能确认，不必逐目录截图比对。
 */
class FileTreeOrderTest {

    private fun item(name: String, type: String = "file", size: Long = 0) =
        FileTreeItem(name = name, type = type, size = size)

    private fun names(items: List<FileTreeItem>) = items.map { it.name }

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    // ── 1. 纯函数：三条规则 ──

    @Test
    fun `文件夹优先文件其次`() {
        val out = sortFileTree(
            listOf(item("app.kt"), item("core", "dir"), item("README.md"), item("docs", "dir")),
        )
        assertEquals(listOf("core", "docs", "README.md", "app.kt"), names(out))
    }

    @Test
    fun `点开头排在同类型最前`() {
        val out = sortFileTree(
            listOf(
                item("src", "dir"), item(".github", "dir"), item("app", "dir"),
                item("LICENSE"), item(".gitignore"), item("README.md"),
            ),
        )
        assertEquals(listOf(".github", "app", "src", ".gitignore", "LICENSE", "README.md"), names(out))
        // 「同类型内」是关键：`.gitignore`（文件）不能因为带点就跑到 `app`（目录）前面
        assertTrue("点开头的文件不许越过目录", names(out).indexOf("app") < names(out).indexOf(".gitignore"))
    }

    @Test
    fun `名字按 A 到 Z 依次排序`() {
        val out = sortFileTree(
            listOf(item("docs", "dir"), item("app", "dir"), item("gradle", "dir"), item("core", "dir")),
        )
        assertEquals(listOf("app", "core", "docs", "gradle"), names(out))
    }

    @Test
    fun `大小写按字节序对齐 GitHub`() {
        // GitHub 的列表是大写在前（`AGENTS.md` 在 `bootstrap.example.toml` 之前），
        // 用 lowercase() 比会把这条对齐性弄丢掉
        val out = sortFileTree(
            listOf(item("bootstrap.toml"), item("AGENTS.md"), item("README.md"), item("app.kt")),
        )
        assertEquals(listOf("AGENTS.md", "README.md", "app.kt", "bootstrap.toml"), names(out))
    }

    @Test
    fun `符号与子模块归到文件侧`() {
        // 与点击行为一致：只有 type == "dir" 才会进目录（symlink / submodule 点开是按文件取的）
        val out = sortFileTree(
            listOf(item("sub", "submodule"), item("link", "symlink"), item("dir", "dir")),
        )
        assertEquals(listOf("dir", "link", "sub"), names(out))
    }

    @Test
    fun `同名同类型保持接口给的相对顺序`() {
        // 稳定排序：GitHub 同一目录内文件名唯一，但别让排序在等价键上乱动
        val a = item("same.md", size = 1)
        val b = item("same.md", size = 2)
        assertEquals(listOf(1L, 2L), sortFileTree(listOf(a, b)).map { it.size })
    }

    // ── 2. 纯函数：解析出来就已经排好 ──

    @Test
    fun `解析出来就已经是_GitHub_顺序`() {
        // 故意按 API 的原样顺序写（混着的纯名字序），断言出来的是网页版那一列
        val json = """
            [
              {"name":".clang-format","type":"file","size":1},
              {"name":".github","type":"dir","size":0},
              {"name":".gitignore","type":"file","size":2},
              {"name":"AGENTS.md","type":"file","size":3},
              {"name":"LICENSES","type":"dir","size":0},
              {"name":"compiler","type":"dir","size":0},
              {"name":"x.py","type":"file","size":4}
            ]
        """.trimIndent()
        assertEquals(
            // 目录组：点最前的 `.github` → 然后字节序 `LICENSES` → `compiler`
            // 文件组：点最前的 `.clang-format` → `.gitignore` → 然后 `AGENTS.md` → `x.py`
            listOf(".github", "LICENSES", "compiler", ".clang-format", ".gitignore", "AGENTS.md", "x.py"),
            names(parseFileTree(json)),
        )
    }

    @Test
    fun `空目录与坏_JSON_都不炸`() {
        assertEquals(emptyList<FileTreeItem>(), sortFileTree(emptyList()))
        assertEquals(emptyList<FileTreeItem>(), parseFileTree("[]"))
        assertEquals(emptyList<FileTreeItem>(), parseFileTree("这不是 JSON"))
        assertEquals(emptyList<FileTreeItem>(), parseFileTree(""))
    }

    // ── 3. 源码级接线：一个真源 + 插桩 ──

    @Test
    fun `排序只有一个真源：解析出来的就是排好的`() {
        val models = source("src/main/java/com/branchbase/ui/repository/RepositoryModels.kt")
        assertTrue(
            "parseFileTree 必须走 sortFileTree：缓存直出与回源两条路径都经过它，才不会有第二种顺序",
            models.contains(".getOrDefault(emptyList()).let(::sortFileTree)"),
        )
        assertTrue(
            "排序规则要写成可单测的纯函数",
            models.contains("fun sortFileTree(items: List<FileTreeItem>): List<FileTreeItem>"),
        )
    }

    @Test
    fun `渲染层不再自己排一遍`() {
        val list = source("src/main/java/com/branchbase/ui/repository/RepositoryListScreens.kt")
        assertFalse(
            "列表层不许有第二套排序（规则只能有一处，否则缓存直出与回源会排出两种顺序）",
            list.contains("sortedWith(") || list.contains("sortedBy("),
        )
        assertTrue("缓存直出这一路要走 parseFileTree", list.contains("val parsed = parseFileTree(cached)"))
        assertTrue("回源这一路也要走 parseFileTree", list.contains("items = parseFileTree(json)"))
    }

    @Test
    fun `项数与顺序写进日志（设备上 grep 就能验证）`() {
        val list = source("src/main/java/com/branchbase/ui/repository/RepositoryListScreens.kt")
        assertTrue(
            "要有日志锚点常量（登记在 LOG_ANCHORS，导出包 report.md 会带上）",
            list.contains("internal const val FILE_TREE_LOG_TAG = \"代码页文件树\""),
        )
        assertTrue(
            "列表定下来之后要记一条（缓存直出与回源只留最终那一个顺序）",
            list.contains("logFileTree(owner, repo, path, items)"),
        )
        assertTrue("日志要带锚点、项数与头几项名字", list.contains("\$FILE_TREE_LOG_TAG ▸ "))
        assertTrue("顺序取前几项就够（日志别把整目录抄一遍）", list.contains("items.take(6).joinToString(\" / \")"))
        assertTrue("空目录/取数失败时也要能看出「0 项」", list.contains("items.count { it.type == \"dir\" }"))
    }
}
