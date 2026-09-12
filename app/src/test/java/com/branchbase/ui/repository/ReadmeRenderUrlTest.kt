package com.branchbase.ui.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * README 渲染器的 URL 归一化规则单测。
 *
 * 这些规则决定「徽章 / 图表 / 截图」能不能显示，全部按 GitHub API 的真实输出取证：
 * - API 返回的 HTML 保留相对路径（`<img src="images/logo.png">`），只有外层 `data-path` 说明 README 位置；
 * - 相对路径落到 `/{o}/{r}/blob/{b}/…` 时返回的是 HTML 页面而不是图片字节，必须走 raw 形态；
 * - 徽章统一被改写成 `camo.githubusercontent.com/<sha>/<hex>`（无扩展名）。
 */
class ReadmeRenderUrlTest {

    private val host = "github.com"
    private val owner = "SunsetRNE"
    private val repo = "Branchbase"

    // ── README 路径与基准目录 ──

    @Test
    fun `data-path 决定 README 真实路径`() {
        val html = """<div id="readme" class="md" data-path="docs/README.md"><article class="markdown-body">"""
        assertEquals("docs/README.md", readmePathOf(html))
    }

    @Test
    fun `缺少 data-path 时回退仓库根 README`() {
        assertEquals("README.md", readmePathOf("<article class=\"markdown-body\">hi</article>"))
        assertEquals("README.md", readmePathOf("data-path=\"\""))
    }

    @Test
    fun `优先认 readme 外层容器上的 data-path`() {
        // 正文自己贴了一段含 data-path 的 HTML 时，不能拿它当 README 路径：
        // 路径决定相对图片/链接的基准目录，取错就是整篇图片错位。
        val html = """<div id="readme" class="md" data-path="docs/README.md">""" +
            """<article class="markdown-body"><pre data-path="wrong/path.md"></pre></article>"""
        assertEquals("docs/README.md", readmePathOf(html))
    }

    @Test
    fun `未编码的空格 URL 也能解析出 host 与 path`() {
        // java.net.URI 对未编码空格会抛异常；解析失败必须退回手工拆分，
        // 否则鉴权 + 磁盘缓存整条链路静默失效（私有仓库图片裂图）。
        val parsed = parseUrl("https://github.com/o/r/raw/main/my image.png")
        assertEquals("github.com", parsed?.host)
        assertEquals("/o/r/raw/main/my image.png", parsed?.path)
        // 相对 URL 仍然解析得出（只是没有 host）—— 依赖方一律先判 host 才能用
        assertNull(parseUrl("./relative/a.png")?.host)
    }

    @Test
    fun `基准目录按 README 所在目录计算`() {
        assertEquals("docs/", baseDirOf("docs/README.md"))
        assertEquals("a/b/c/", baseDirOf("a/b/c/README.md"))
        assertEquals("", baseDirOf("README.md"))
    }

    @Test
    fun `raw 基准 URL 落在 README 所在目录`() {
        assertEquals(
            "https://github.com/SunsetRNE/Branchbase/raw/main/docs/",
            rawBaseUrl(host, owner, repo, "main", "docs/"),
        )
        assertEquals(
            "https://github.com/SunsetRNE/Branchbase/raw/main/",
            rawBaseUrl(host, owner, repo, "main", ""),
        )
    }

    @Test
    fun `空分支回退 HEAD 且分支名里的斜杠原样保留`() {
        assertEquals("https://github.com/SunsetRNE/Branchbase/raw/HEAD/", rawBaseUrl(host, owner, repo, "", ""))
        assertEquals(
            "https://github.com/SunsetRNE/Branchbase/raw/feature/html-parser/docs/",
            rawBaseUrl(host, owner, repo, "feature/html-parser", "docs/"),
        )
    }

    // ── 相对链接：raw 形态 → 网页形态 ──

    @Test
    fun `相对文件链接从 raw 还原为 blob`() {
        assertEquals(
            "https://github.com/SunsetRNE/Branchbase/blob/main/docs/CONTRIBUTING.md",
            toNavigationUrl(
                "https://github.com/SunsetRNE/Branchbase/raw/main/docs/CONTRIBUTING.md",
                host, owner, repo, "main",
            ),
        )
    }

    @Test
    fun `相对目录链接（末尾斜杠）还原为 tree`() {
        assertEquals(
            "https://github.com/SunsetRNE/Branchbase/tree/main/docs/images/",
            toNavigationUrl(
                "https://github.com/SunsetRNE/Branchbase/raw/main/docs/images/",
                host, owner, repo, "main",
            ),
        )
    }

    @Test
    fun `已是网页形态的 blob 与 tree 不做改写`() {
        assertNull(
            toNavigationUrl(
                "https://github.com/SunsetRNE/Branchbase/blob/main/README.md",
                host, owner, repo, "main",
            ),
        )
        assertNull(
            toNavigationUrl(
                "https://github.com/SunsetRNE/Branchbase/tree/main/docs",
                host, owner, repo, "main",
            ),
        )
    }

    @Test
    fun `站外链接与其他仓库链接不做改写`() {
        assertNull(toNavigationUrl("https://shields.io/badge/x", host, owner, repo, "main"))
        assertNull(
            toNavigationUrl(
                "https://github.com/other/repo/raw/main/a.md",
                host, owner, repo, "main",
            ),
        )
        assertNull(toNavigationUrl("mailto:a@b.com", host, owner, repo, "main"))
    }

    @Test
    fun `owner 与 repo 大小写不敏感且保留 URL 原始大小写`() {
        assertEquals(
            "https://github.com/sunsetrne/branchbase/blob/main/a.md",
            toNavigationUrl(
                "https://github.com/sunsetrne/branchbase/raw/main/a.md",
                host, owner, repo, "main",
            ),
        )
    }

    @Test
    fun `带斜杠的分支名按已知分支切分`() {
        assertEquals(
            "https://github.com/SunsetRNE/Branchbase/blob/feature/html-parser/docs/a.md",
            toNavigationUrl(
                "https://github.com/SunsetRNE/Branchbase/raw/feature/html-parser/docs/a.md",
                host, owner, repo, "feature/html-parser",
            ),
        )
    }

    // ── 图片：blob/raw → raw 字节地址 ──

    @Test
    fun `blob 形态的图片改判为 raw 字节地址`() {
        assertEquals(
            "https://raw.githubusercontent.com/SunsetRNE/Branchbase/main/docs/images/logo.png",
            rawImageUrl(
                "https://github.com/SunsetRNE/Branchbase/blob/main/docs/images/logo.png",
                host, owner, repo, "main",
            ),
        )
    }

    @Test
    fun `raw 形态的图片改判为 raw 字节地址`() {
        assertEquals(
            "https://raw.githubusercontent.com/SunsetRNE/Branchbase/main/docs/chart.svg",
            rawImageUrl(
                "https://github.com/SunsetRNE/Branchbase/raw/main/docs/chart.svg",
                host, owner, repo, "main",
            ),
        )
    }

    @Test
    fun `rawusercontent 地址原样返回`() {
        val url = "https://raw.githubusercontent.com/SunsetRNE/Branchbase/main/a.png"
        assertEquals(url, rawImageUrl(url, host, owner, repo, "main"))
    }

    @Test
    fun `站外图片（camo 与 shields）不参与鉴权取图`() {
        // camo 是 GitHub 自己的公开图片代理，不需要凭据
        assertNull(
            rawImageUrl(
                "https://camo.githubusercontent.com/c5c243af10c494a46ec066b41a8fda3d3972e2c6d02a946413a91b7ae8bc6eaf/68747470733a2f2f696d672e736869656c64732e696f2f78",
                host, owner, repo, "main",
            ),
        )
        // 站外徽章（README 里的裸 <img>）绝不能附带 PAT
        assertNull(
            rawImageUrl(
                "https://img.shields.io/github/stars/SunsetRNE/Branchbase.svg?style=for-the-badge",
                host, owner, repo, "main",
            ),
        )
    }

    @Test
    fun `GHE 走自身 raw 路径`() {
        assertEquals(
            "https://ghe.example.com/o/r/raw/main/docs/a.png",
            rawImageUrl(
                "https://ghe.example.com/o/r/blob/main/docs/a.png",
                "ghe.example.com", "o", "r", "main",
            ),
        )
    }

    // ── 锚点与图片判定 ──

    @Test
    fun `同文档锚点交给 WebView 自己滚动`() {
        val documentUrl = "https://github.com/SunsetRNE/Branchbase/raw/main/docs/"
        assertTrue(isSameDocumentAnchor("$documentUrl#installation", documentUrl))
        assertFalse(isSameDocumentAnchor("https://github.com/SunsetRNE/Branchbase/raw/main/other#x", documentUrl))
        assertFalse(isSameDocumentAnchor(documentUrl, documentUrl))
    }

    @Test
    fun `camo 徽章没有扩展名 只能靠 Accept 头判定`() {
        val camo = "https://camo.githubusercontent.com/abc123/68747470733a2f2f696d672e736869656c64732e696f2f78"
        assertFalse(hasImageExtension(parseUrl(camo)?.path))
        assertTrue(isImageAccept("image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"))
        assertFalse(isImageAccept("text/html,application/xhtml+xml"))
        assertFalse(isImageAccept(null))
    }

    @Test
    fun `带查询串的图片仍按路径判扩展名`() {
        assertTrue(hasImageExtension(parseUrl("https://github.com/o/r/raw/main/logo.png?raw=true")?.path))
        assertFalse(hasImageExtension(parseUrl("https://github.com/o/r/raw/main/notes.md")?.path))
    }

    @Test
    fun `路径段编码保留斜杠与常规字符`() {
        assertEquals("feature/html-parser", encodePath("feature/html-parser"))
        assertEquals("docs/my%20dir/", encodePath("docs/my dir/"))
        assertEquals("", encodePath(""))
    }
}
