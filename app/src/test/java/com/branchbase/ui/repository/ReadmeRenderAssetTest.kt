package com.branchbase.ui.repository

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自述文件渲染的**回归钉子**：图片的尺寸与位置只能由 CSS / 作者声明决定。
 *
 * 背景（真实用户反馈）：有个项目的 README 在网页端是「一排 4 张图」，
 * 到 App 里变成「一张一行、而且奇大」。根因是注入脚本里的 `.bb-scroll-x` 逻辑 ——
 * 它把超出视口宽度的图片包进一个**块级**容器、并把宽度强制成 `naturalWidth`：
 * 块级容器打断了 `<p>` 里的行内流（一排拆成四行），强制宽度又覆盖了作者自己的
 * `width` 属性与 GitHub 给的 `max-width: 100%`（28px 的 logo、250×55 的徽章部件全被放大）。
 *
 * 这类问题在真机上才看得见，所以这里直接钉住「脚本不许改图片尺寸」这条边界：
 * 谁再把 `bb-scroll-x` 加回来，单测立刻红。
 */
class ReadmeRenderAssetTest {

    private fun asset(path: String): String {
        val file = File(path)
        assertTrue("找不到资源文件：${file.absolutePath}（单测工作目录应为 app 模块根）", file.exists())
        return file.readText()
    }

    @Test
    fun `样式不对图片强制像素宽度`() {
        val css = asset("src/main/assets/github-markdown-light.css")
        // 与 GitHub 网页一致：只按容器宽度缩放
        assertTrue(css.contains(".markdown-body img { max-width: 100%"))
        assertFalse("横向滚动容器会把超宽图片变回原始像素宽", css.contains(".bb-scroll-x {"))
        assertFalse("max-width: none 会让图片顶破容器", css.contains("max-width: none"))
    }

    @Test
    fun `渲染脚本不改写图片尺寸与行内位置`() {
        val source = asset("src/main/java/com/branchbase/ui/repository/ReadmeWebView.kt")
        assertFalse("包裹层会打断「一排徽章」的行内流", source.contains("className = 'bb-scroll-x'"))
        assertFalse("强制 naturalWidth 会把徽章 / 部件放大成「奇大」", source.contains("naturalWidth + 'px'"))
    }
}
