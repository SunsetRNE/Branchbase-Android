package com.branchbase.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「液态玻璃」接线钉子（源码级，套路同 [NavShellStructureTest] / [SystemBarInsetsTest]）。
 *
 * ## 为什么这件事只能钉在源码上
 *
 * 玻璃是一条**接力链**，任何一段掉链子都不会编译报错、不会崩、单测也不会红 —— 但观感会退回
 * 「一块半透明色板」，而且**看不出是哪一步断的**：
 *
 * ```
 * ① NavigationShell   内容铺满 + 给内容挂 layerBackdrop（录进图层）
 * ② 栏可见时才录      没有栏还录，就是白多一次整页离屏绘制
 * ③ LocalBackdrop     图层下发到栏那一层
 * ④ GlassNavigationBar  drawBackdrop { blur / lens } —— 磨砂与透明液态两档
 * ```
 *
 * ## 1.2.3 的第二次返工：为什么钉子从「自研录制」改成「上游接线」
 *
 * 第一版是自研的（`GraphicsLayer.record` + `translate(录制原点 − 自身原点)` + `Modifier.blur`），
 * 钉子也钉的是那几行。它被**上游库**取代的原因是**缺的不是采样而是材质**：
 * 折射、边缘高光、厚度阴影三件事都要 AGSL `RuntimeShader`，而上游
 * [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0）
 * 已经做完并且与酷安 16.6.4 同源（着色器逐字相同，取证见
 * `/root/Project-Integrated-Workspace/apk-lab/out/酷安-液态玻璃-取证报告.md`）。
 * 所以钉子的对象跟着换：**不再钉「我们怎么录」，改钉「我们有没有正确地把库接上、
 * 有没有把两档参数传对、有没有在低版本上老实降级」**。
 *
 * ## 断言前先剥注释
 *
 * 这些名字恰恰要写在注释里解释「为什么这么做」—— 直接扫原文等于禁止解释。
 */
class GlassBackdropTest {

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

    private val shellPath = "src/main/java/com/branchbase/ui/navigation/NavigationShell.kt"
    private val barPath = "src/main/java/com/branchbase/ui/navigation/GlassBar.kt"
    private val mainBarPath = "src/main/java/com/branchbase/ui/navigation/GlassNavigationBar.kt"
    private val repoPath = "src/main/java/com/branchbase/ui/repository/RepositoryScreen.kt"
    private val profilePath = "src/main/java/com/branchbase/ui/profile/ProfileScreen.kt"
    private val backdropPath = "src/main/java/com/branchbase/ui/navigation/Backdrop.kt"

    /**
     * 第一条：壳子必须把内容录进库的图层，并在栏那层下发。
     *
     * 只钉「有个 LocalBackdrop」挡不住「谁都没给它赋值」—— 那正是 1.2.2 里
     * `blurRadiusPx` 的翻版（定义了、没人传、永远不生效），所以「录」「传」两端都钉。
     */
    @Test
    fun `悬浮形态必须录制内容并下发采样层`() {
        val floating = code(shellPath).substringAfter("private fun FloatingNavigationShell(")
        assertTrue(
            "悬浮形态必须用 rememberLayerBackdrop() 建采样层 —— 没有它，栏身后就没有可采的像素",
            floating.contains("rememberLayerBackdrop()"),
        )
        assertTrue(
            "必须给内容挂 Modifier.layerBackdrop(liveBackdrop)（把内容录进图层）—— " +
                "少了这一挂，图层永远是空的：栏身后看着有内容，采出来什么都没有",
            floating.contains("layerBackdrop(liveBackdrop)"),
        )
        assertTrue(
            "必须经 CompositionLocalProvider 下发 LocalBackdrop，否则栏拿不到图层",
            floating.contains("LocalBackdrop provides"),
        )
    }

    /**
     * 第二条：**没有栏的时候不许录**。
     *
     * 录制是一次额外的整页离屏绘制，是全项目最贵的一笔。栏都收了还在录，
     * 就是拿滚动帧率换一个没人看的图层。
     */
    @Test
    fun `栏不可见时不挂录制`() {
        val floating = code(shellPath).substringAfter("private fun FloatingNavigationShell(")
        assertTrue(
            "录制必须被 barVisible 门控（`val record = live && barVisible && …`，再拿它决定挂不挂）—— " +
                "栏收了还在录，就是拿滚动帧率换一个没人看的图层",
            floating.contains("live && barVisible"),
        )
    }

    /**
     * 第三条：悬浮形态**不许**把内容挤出栏外 —— 内边距必须是 0。
     *
     * 三处调用点拿到 [androidx.compose.foundation.layout.PaddingValues] 后都写成
     * `Box(Modifier.fillMaxSize().padding(...))`，那是**容器级**内边距：一旦非 0，
     * 页面会被整个顶到栏上方，录制图层在栏身后是空的，玻璃采到的是一块纯色 ——
     * 花了整页离屏绘制，观感却和「不采样」一模一样。
     */
    @Test
    fun `悬浮形态不许把内容挤出栏外`() {
        val floating = code(shellPath).substringAfter("private fun FloatingNavigationShell(")
        assertTrue(
            "悬浮形态必须回传 PaddingValues(0.dp)",
            floating.contains("content(PaddingValues(0.dp))"),
        )
        assertTrue(
            "悬浮形态不许再引入按栏高/手势条推算的尾部留白（barHeight / WindowInsets.navigationBars）" +
                "—— 那正是把内容挤出栏外的那条路",
            !floating.contains("barHeight") && !floating.contains("WindowInsets.navigationBars"),
        )
    }

    /**
     * 第四条：占位形态**不许**替栏补系统栏内边距。
     *
     * M3 `NavigationBar` 自带 `NavigationBarDefaults.windowInsets`，`RepoBottomBar` 与
     * `ProfileBubbleNavigationBar` 也都自己取了 —— 壳子再加一条就是叠两层，栏凭空变高一段。
     */
    @Test
    fun `占位形态不许替栏补系统栏内边距`() {
        val placeholder = code(shellPath).substringBefore("private fun FloatingNavigationShell(")
        assertTrue(
            "NavigationShell 的占位分支出现了 navigationBarsPadding() —— M3 NavigationBar 自带内边距、" +
                "仓库底栏与个人页气泡栏也各自取了，壳子再加一条会叠成两层",
            !placeholder.contains("navigationBarsPadding()"),
        )
    }

    /**
     * 第五条：栏必须真的把库接上 —— `drawBackdrop` + 两档效果 + 版本门控。
     *
     * 三条缺一不可，而且都不会编译报错：
     * - 不挂 `drawBackdrop` ⇒ 玻璃是块色板（背景白采）；
     * - 不传 `lens(...)` ⇒ 只有磨砂没有折射，「液态」就没了（这是 1.2.3 第一版被否掉的地方）；
     * - 不判版本 ⇒ API 31 以下 `blur` 是空操作，玻璃里透出一张**清晰的原图**。
     */
    @Test
    fun `玻璃栏必须接上库并按版本降级`() {
        val bar = code(barPath)
        assertTrue(
            "GlassNavigationBar 必须从 LocalBackdrop 取采样层",
            bar.contains("LocalBackdrop.current"),
        )
        assertTrue(
            "必须用 drawBackdrop(...) 画玻璃 —— 模糊/折射/边缘高光都由它承担",
            bar.contains("drawBackdrop("),
        )
        assertTrue(
            "必须传 lens(...)（折射）—— 少了它玻璃只是「糊了一块」，这正是第一版被否掉的原因",
            bar.contains("lens("),
        )
        assertTrue(
            "采样必须判版本（Build.VERSION / SDK_INT）—— blur 在 Android 12 以下是空操作，" +
                "硬采会透出一张清晰的原图，比不采更糟",
            bar.contains("Build.VERSION") || bar.contains("SDK_INT"),
        )
    }

    /**
     * 第六条：**选中项透明、未选中磨砂** —— 这条对比是这一版的全部意义。
     *
     * 两个坑都不会编译报错：
     * - 滑块与底面用**同一个**半径 ⇒ 两档糊得一样，分不出选中项，滑块那层白做；
     * - 滑块下还留着那层实心选中色 ⇒ 透明玻璃被盖死，看起来和实心胶囊没区别。
     */
    @Test
    fun `选中态与未选中态必须是两档且不许被实心色盖死`() {
        val bar = code(barPath)
        assertTrue(
            "底面必须用 GlassBackdrop.BlurRadius（磨砂档）",
            bar.contains("blur(GlassBackdrop.BlurRadius"),
        )
        assertTrue(
            "滑块必须用 GlassBackdrop.LensBlurRadius（清玻璃档）——" +
                "两档半径写成一个，滑块那层就等于白做",
            bar.contains("blur(GlassBackdrop.LensBlurRadius"),
        )
        assertTrue(
            "选中项在采样可用时不许再叠实心选中色（判据是 `<项>.selected && !sampled`）—— " +
                "叠上去就把透明玻璃盖死了",
            bar.contains(".selected && !sampled"),
        )

        // 两档必须真的**不一样**，否则「选中透明、其余磨砂」根本不存在。
        val backdrop = code(backdropPath)
        fun radius(name: String): Float? =
            Regex("""val $name:\s*Dp\s*=\s*([0-9.]+)\.dp""").find(backdrop)?.groupValues?.get(1)?.toFloat()
        val base = radius("BlurRadius")
        val lens = radius("LensBlurRadius")
        assertTrue("Backdrop.kt 里读不出两个模糊半径（base=$base lens=$lens）", base != null && lens != null)
        assertTrue(
            "选中态必须比未选中态清楚（滑块半径 $lens 必须 < 底面半径 $base）",
            lens!! < base!!,
        )
    }

    /**
     * 第七条：滑块要**滑**过去，而且是液态地拉长过去。
     *
     * 「液态」不是颜色，是运动：位置用弹簧推（不是硬切），滑动中在两项之间拉长、到站收圆。
     * 少了拉长，它就只是一个会平移的圆圈 —— 那是「动画」，不是「液态」。
     */
    @Test
    fun `滑块必须是弹簧驱动的液态拉长`() {
        val bar = code(barPath)
        assertTrue(
            "滑块位置必须由 Animatable 弹簧驱动（不是硬切）",
            bar.contains("Animatable(") && bar.contains("spring("),
        )
        assertTrue(
            "滑块必须按「项索引」在两值之间取 min/max 算出拉长量 —— " +
                "拉长是液态的关键；只做位移的话它就只是个会移动的圆",
            bar.contains("min(lensIndex.value") && bar.contains("max(lensIndex.value"),
        )
        assertTrue(
            "滑块必须按选中槽位变化重启动画（LaunchedEffect(lensSlot …)）——" +
                "少了它，位置永远停在初始那一项，点第二项不会滑",
            bar.contains("LaunchedEffect(lensSlot"),
        )
    }

    /**
     * 第八条：玻璃的颜色**只能从主题角色来**，且折射参数只有一处真源。
     *
     * 上游 `drawBackdrop` 的效果入参没有默认值，全靠调用方传 —— 散在调用点就会改漏一处；
     * 而写死色值的老毛病（深色下一条亮带）在换库之后**依然会犯**，所以这条照旧钉住。
     */
    @Test
    fun `颜色从主题来且参数只有一处真源`() {
        val bar = code(barPath)
        assertTrue(
            "GlassNavigationBar 的底色必须从 Primer 角色取（不许写死色值）",
            bar.contains("Primer.BackgroundSecondary") && bar.contains("Primer.TextPrimary"),
        )
        assertTrue(
            "必须按深浅主题分档取色（LocalIsDarkTheme）",
            bar.contains("LocalIsDarkTheme"),
        )
        val backdrop = code(backdropPath)
        assertTrue(
            "Backdrop.kt 必须声明唯一的一组玻璃参数（BlurRadius / LensBlurRadius / Refraction*）",
            backdrop.contains("val BlurRadius") &&
                backdrop.contains("val LensBlurRadius") &&
                backdrop.contains("val RefractionHeight") &&
                backdrop.contains("val RefractionAmount"),
        )
        assertTrue(
            "折射参数必须由栏从 GlassBackdrop 读，不许在调用点写死 dp 数",
            bar.contains("GlassBackdrop.RefractionHeight") && bar.contains("GlassBackdrop.RefractionAmount"),
        )
    }

    /**
     * 第九条：**三处底部导航跟随同一个开关**。
     *
     * 主界面 / 仓库页 / 个人页各有自己的壳子，但「玻璃还是传统栏」必须由**同一个**运行时状态决定：
     * 各读各的 `SharedPreferences` 会漏掉「设置页刚改、返回上一页没生效」这类问题
     * （1.1.23 修过一次：开关只写本地、没驱动主界面切换）。
     * 所以三处都要 `GlassNavigationRuntime.enabled.collectAsState()`。
     */
    @Test
    fun `三处底部导航跟随同一个开关`() {
        val screens = mapOf(
            "主界面" to "src/main/java/com/branchbase/ui/main/MainScreen.kt",
            "仓库页" to repoPath,
            "个人页" to profilePath,
        )
        screens.forEach { (name, path) ->
            val src = code(path)
            assertTrue(
                "$name：必须订阅 GlassNavigationRuntime.enabled（同一份运行时状态），" +
                    "不许各自读 SharedPreferences —— 那样设置页改完返回不会生效",
                src.contains("GlassNavigationRuntime.enabled.collectAsState()"),
            )
            assertTrue(
                "$name：必须把开关传给壳子的 floating —— 少了它，玻璃形态下的覆盖层就不会启用，" +
                    "内容被顶到栏上方，栏身后没有可采的像素",
                src.contains("floating = glassNavigation"),
            )
        }

        // 另两处的玻璃栏与主界面**共用同一份实现**：不该在各自文件里再画一遍玻璃。
        listOf("仓库页" to repoPath, "个人页" to profilePath).forEach { (name, path) ->
            assertTrue(
                "$name：玻璃栏必须用共用的 GlassBar( —— 各画一份意味着两档模糊/折射/降级要改三处",
                code(path).contains("GlassBar("),
            )
        }

        // 主界面那条也要收敛成薄包装，否则「共用一份」是空话。
        val mainBar = code(mainBarPath)
        assertTrue(
            "GlassNavigationBar 必须只是 GlassBar 的薄包装（形状与材质不许在它里面再写一遍）",
            mainBar.contains("GlassBar(") && !mainBar.contains("drawBackdrop("),
        )
    }

    /**
     * 第十条：**interop 正文（WebView）在屏时不许录内容** —— 这条是「自述文件那块在闪」的修复。
     *
     * 录制会把内容每帧画两遍（上屏一次、进图层一次）；`WebView` 的画面来自 Chromium 的渲染
     * functor，一帧只能被消费一次，画第二遍时拿到的是空的或上一帧 —— 表现就是闪。
     *
     * 三个缺一不可，而且都不会编译报错：
     * - 正文侧不登记 ⇒ 壳子照旧录，闪；
     * - 壳子不读安全阀 ⇒ 登记了也没人理；
     * - 没有静止底 ⇒ 停掉录制后栏身后是空的（图层从没录过），玻璃会变成一块透明/发黑的板。
     */
    @Test
    fun `interop 正文在屏时不许录内容`() {
        assertTrue(
            "ReadmeWebView 必须在挂载期间登记安全阀（LiveBackdropGate.enter/exit）—— " +
                "不登记的话壳子照旧录，WebView 每帧被画两遍就会闪",
            code("src/main/java/com/branchbase/ui/repository/ReadmeWebView.kt")
                .let { it.contains("LiveBackdropGate.enter()") && it.contains("LiveBackdropGate.exit()") },
        )
        val floating = code(shellPath).substringAfter("private fun FloatingNavigationShell(")
        assertTrue(
            "壳子必须读 LiveBackdropGate.allowed 再决定录不录",
            floating.contains("LiveBackdropGate.allowed"),
        )
        assertTrue(
            "必须有静止底（rememberCanvasBackdrop 画主题底色）：停掉录制后栏身后没有像素，" +
                "没有它玻璃会变成一块空板",
            floating.contains("rememberCanvasBackdrop"),
        )
        assertTrue(
            "静止底必须在 live 为假时提供给栏（LocalBackdrop provides if (live) … else flatBackdrop）",
            floating.contains("else flatBackdrop"),
        )
    }
}
