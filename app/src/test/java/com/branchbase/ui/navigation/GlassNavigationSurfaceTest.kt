package com.branchbase.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃导航栏「画什么 / 谁来给颜色」的结构性钉子（源码级，套路同 [SystemBarInsetsTest]）。
 *
 * ## 为什么这件事要钉在源码上
 *
 * 玻璃层是原生 `View`，它的三条错误路径**编译不报、单测不红、运行时也不崩**：
 * `RenderEffect` 加了但没有背景可采、`LAYER_TYPE_SOFTWARE` 白吃一帧软件合成、
 * 写死的白色渐变只在深色真机上变成一条亮带。2026-10 的现场就是第 1、3 条：
 * `blurRadiusPx` 从引入起没有任何调用点赋值（永远 0，`setRenderEffect` 一次都没跑过），
 * 而 `onDraw` 里那组 `Color.argb(172,255,255,255)` 在深色下是一块亮带。
 *
 * 所以这里把「不许再接回来」写成断言，而不是写在注释里等人自觉。
 *
 * ## 断言前必须先剥注释（这不是洁癖，是踩过的）
 *
 * 这几个名字**恰恰要写在注释里**解释「为什么删掉」—— 直接扫原文等于禁止解释，
 * 而且第一次跑就是红的（`LiquidGlassSurface` 的 KDoc 里三个名字都出现）。
 * 剥注释的实现与 `I18nUiTextTest.stripComments` 同源：字符串字面量原样保留，
 * 块注释按嵌套深度配对。
 */
class GlassNavigationSurfaceTest {

    private fun source(path: String): String {
        val f = File(path)
        assertTrue("找不到源文件：${f.absolutePath}（单测工作目录应为 app 模块根）", f.exists())
        return f.readText()
    }

    /** 去掉 Kotlin 注释后的源码（只留代码与字符串字面量）。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') i++
                c == '/' && src.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < src.length && depth > 0) {
                        when {
                            src.startsWith("/*", i) -> { depth++; i += 2 }
                            src.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                }
                c == '"' -> {
                    out.append(c); i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') { out.append(src[i]); i++ }
                        if (i < src.length) { out.append(src[i]); i++ }
                    }
                    if (i < src.length) { out.append(src[i]); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun code(path: String): String = stripComments(source(path))

    private val surfacePath = "src/main/java/com/branchbase/ui/navigation/LiquidGlassSurface.kt"
    private val barPath = "src/main/java/com/branchbase/ui/navigation/GlassNavigationBar.kt"

    /**
     * 第一条：玻璃层不许再走「模糊」和「软件层」这两条无效/亏本的路。
     *
     * [LiquidGlassSurface] 只拥有自己的画布，`RenderEffect` 作用于**它自己的渲染结果**，
     * 看不到身后的 Compose 内容 —— 做不出 backdrop blur；真要做得先让页面铺到栏下方
     * （见 `docs/specs/VERSION-NOTES.md` 1.2.2）。
     */
    @Test
    fun `玻璃层不许再接回无效的模糊与软件层`() {
        val src = code(surfacePath)
        assertTrue(
            "LiquidGlassSurface 又出现了 RenderEffect —— 它模糊的是本 View 自己的渲染结果，" +
                "看不到身后的页面内容，做不出 backdrop blur（背景模糊要采样栏后面的像素）",
            !src.contains("RenderEffect"),
        )
        assertTrue(
            "LiquidGlassSurface 又出现了 blurRadiusPx —— 这个开关从引入起没有任何调用点赋值，" +
                "永远停在 0，是条从没接上的死路径",
            !src.contains("blurRadiusPx"),
        )
        assertTrue(
            "LiquidGlassSurface 又强制了 LAYER_TYPE_SOFTWARE —— 本 View 只画渐变与圆角描边，" +
                "硬件管线够用，软件层只是把这一层的合成从 GPU 挪回 CPU",
            !src.contains("LAYER_TYPE_SOFTWARE"),
        )
    }

    /**
     * 第二条：玻璃的颜色**只能由主题下发**，不许在 `onDraw` 里写死。
     *
     * 写死一组白/冷灰渐变是「深色下一条亮带」的直接来源，也和 `ui/theme/` 的角色脱节。
     */
    @Test
    fun `玻璃层里不许再写死颜色`() {
        val src = code(surfacePath)
        assertTrue(
            "LiquidGlassSurface 里又出现了写死的 Color.argb(...) —— 颜色必须由主题算好后经 Tint 下发，" +
                "否则深色主题下会顶出一条亮带",
            !src.contains("Color.argb("),
        )
        assertTrue(
            "LiquidGlassSurface 必须保留主题下发的入口（var tint: Tint）",
            src.contains("var tint: Tint"),
        )
    }

    /**
     * 第三条：接线必须真的接上 —— 色组从 Primer 角色来，并经 `AndroidView.update` 交给玻璃层。
     *
     * 只钉表面（「有个 Tint 类」）挡不住「谁都没给它赋值」这类缺陷，这正是第一条里
     * `blurRadiusPx` 的翻版，所以这里同时钉「算」和「传」两端。
     */
    @Test
    fun `玻璃颜色要从主题角色算出来并接到玻璃层`() {
        val bar = code(barPath)
        assertTrue(
            "GlassNavigationBar 不再从 Primer 角色取玻璃底色了 —— 颜色会退回写死值",
            bar.contains("Primer.BackgroundSecondary"),
        )
        assertTrue(
            "GlassNavigationBar 必须按深浅主题取色（玻璃层读不到 CompositionLocal，只能在调用方算）",
            bar.contains("LocalIsDarkTheme"),
        )
        assertTrue(
            "GlassNavigationBar 必须把算好的色组通过 AndroidView.update 交给玻璃层（it.tint = tint），" +
                "否则色组算了也没人用",
            bar.contains("it.tint ="),
        )
    }
}
