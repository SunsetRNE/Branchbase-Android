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
    private val dragPath = "src/main/java/com/branchbase/ui/navigation/LiquidGlassDrag.kt"

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
            "悬浮形态不许按栏高去改**内容**的内边距（`PaddingValues(barHeight…)` / " +
                "`padding(bottom = barHeight…)` 都不许出现）—— 要给的是「让位高度」这个数，" +
                "不是把内容顶上去",
            !floating.contains("PaddingValues(barHeight") &&
                !floating.contains("padding(bottom = barHeight"),
        )
        assertTrue(
            "让位高度必须**量**出来（`onSizeChanged` 上报 `barHeightPx`，再 `.toDp()` 下发）：" +
                "栏自己带着 `navigationBarsPadding()`，手势条高度、分屏、横屏都会改这个数 —— " +
                "量一次就永远对，按 80dp 推算会让贴底件底边落在栏沿以下",
            floating.contains("onSizeChanged { barHeightPx = it.height }") &&
                floating.contains("barHeightPx.toDp()"),
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
            "必须传 vibrancy()（增色）—— 移植后的容器链是 vibrancy → blur → lens（上游 catalog 同序）；" +
                "少了增色，采进来的页面像素会发灰，滑块里那张染色层也立不起来",
            bar.contains("vibrancy()"),
        )
        assertTrue(
            "滑块必须带上 Highlight / Shadow / InnerShadow（边缘高光与厚度）—— 这三样是「玻璃」与" +
                "「半透明色板」的分界，且都由按下进度驱动",
            bar.contains("Highlight.Default") &&
                bar.contains("Shadow(alpha") &&
                bar.contains("InnerShadow("),
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
    fun `三层结构必须在场：容器、染色录制层、采样合体滑块`() {
        val bar = code(barPath)

        // ② 染色录制层：看不见（alpha 0）但真的被画一遍，且被染成强调色 ——
        // 少了 `layerBackdrop(tabsBackdrop)`，滑块采到的就是空图层（滑块变成一块灰玻璃）；
        // 少了 `ColorFilter.tint(`，滑块里透出的图标与屏幕上一模一样，等于白录。
        assertTrue(
            "必须有染色录制层：alpha(0f) + layerBackdrop(tabsBackdrop) + ColorFilter.tint(强调色) 三件套",
            bar.contains(".alpha(0f)") &&
                bar.contains("layerBackdrop(tabsBackdrop)") &&
                bar.contains("ColorFilter.tint("),
        )

        // ③ 滑块必须采样「页面 × 染色层」的合体，而不是只采页面 ——
        // 只采页面的话，滑块里透出的就是未染色的原始像素，选中态没有任何颜色来源。
        assertTrue(
            "滑块必须用 rememberCombinedBackdrop(page, tabsBackdrop) 采合体图层",
            bar.contains("rememberCombinedBackdrop("),
        )

        // 可见的那一层不许再叠实心选中色（只在没有采样时才允许）——叠上去就把玻璃盖死了。
        assertTrue(
            "可见层必须按 `<项>.selected && !sampled` 决定要不要实心选中色",
            bar.contains("item.selected && !sampled"),
        )

        // 容器与滑块必须是**两个** drawBackdrop 调用点（容器一层、滑块一层、录制层一层）。
        val backdropCalls = Regex("""drawBackdrop\(""").findAll(bar).count()
        assertTrue(
            "GlassBar 里应当有 3 处 drawBackdrop（容器 / 录制层 / 滑块），实际 $backdropCalls 处 —— " +
                "少一处就少一层：没有容器的磨砂、没有录制层的染色、或没有滑块的折射",
            backdropCalls == 3,
        )
    }

    /**
     * 第七条：滑块要**滑**过去，而且是液态地拉长过去。
     *
     * 「液态」不是颜色，是运动：位置用弹簧推（不是硬切），滑动中在两项之间拉长、到站收圆。
     * 少了拉长，它就只是一个会平移的圆圈 —— 那是「动画」，不是「液态」。
     */
    @Test
    fun `滑块必须是上游那套四弹簧物理`() {
        val bar = code(barPath)
        assertTrue(
            "滑块必须挂上游移植过来的 DampedDragAnimation（位移 / 按压 / 形变 / 速度四个弹簧）——" +
                "只留一个位移弹簧的话，它会「滑过去」但不会「被拉长、被甩扁」",
            bar.contains("DampedDragAnimation("),
        )
        assertTrue(
            "拖动必须真的驱动内部槽位（onDrag → updateValue）并落位后回调（onDragStopped → animateToValue）",
            bar.contains("updateValue(") && bar.contains("animateToValue("),
        )
        assertTrue(
            "按下形变与折射必须由 pressProgress 驱动 —— 少了它，按下去栏面纹丝不动",
            bar.contains("drag.pressProgress"),
        )
        assertTrue(
            "滑块折射必须开色散（chromaticAberration = true）—— 上游 catalog 就开了；" +
                "关掉的话边缘只是位移，没有「液体边缘那圈彩边」",
            bar.contains("chromaticAberration = true"),
        )
        assertTrue(
            "滑块必须按速度做非等比压扁（scaleX /= … velocity …, scaleY *= …）—— " +
                "这是「甩动像一滴液体」与「一个方块平移」的差别",
            bar.contains("drag.velocity") && bar.contains("scaleX /="),
        )
        assertTrue(
            "拖动时整条栏必须跟着位移（panelOffset / panelShiftAnimation）",
            bar.contains("panelShiftAnimation") && bar.contains("translationX = panelOffset"),
        )
    }

    /**
     * 第十一条：物理层必须真的被移植进来，且**唯一那处降级要留痕**。
     *
     * 上游 `InteractiveHighlight` 用 `com.kyant.backdrop.RuntimeShader` 画指针高光团；
     * 本项目锁定的 `backdrop` 1.0.6 **没有导出** `RuntimeShader`（javap 实测只有
     * `RuntimeShaderCache` / `ShadersKt`），所以改用 `Brush.radialGradient` + `BlendMode.Plus`
     * 复现同一件事。这条钉住「移植了」与「降级在注释里写明」，避免下一个人以为漏抄了。
     */
    @Test
    fun `运动物理层必须移植到位且降级留痕`() {
        val drag = code(dragPath)
        assertTrue(
            "必须移植拖动识别器（inspectDragGestures）—— 上游不等 touchSlop，按下即开始形变",
            drag.contains("inspectDragGestures"),
        )
        assertTrue(
            "必须移植 awaitFrame（抬手后等一帧再收形变）",
            drag.contains("suspend fun awaitFrame()"),
        )
        assertTrue(
            "指针高光必须用 Compose 原生画法（Brush.radialGradient + BlendMode.Plus）—— " +
                "这正是 1.0.6 缺 RuntimeShader 的降级路径",
            drag.contains("Brush.radialGradient(") && drag.contains("BlendMode.Plus"),
        )
        assertTrue(
            "降级理由必须写在源码注释里（注释里要提到 RuntimeShader）",
            source(dragPath).contains("RuntimeShader"),
        )
        assertTrue(
            "指针高光必须与手势分离：画在表面上（modifier）、跟踪在滑块上（gestureModifier）",
            drag.contains("val modifier: Modifier") && drag.contains("val gestureModifier: Modifier"),
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
            "GlassNavigationBar 的底色与图标色必须从 Primer 角色取（不许写死色值）",
            bar.contains("Primer.BackgroundSecondary") && bar.contains("Primer.IconPrimary"),
        )
        assertTrue(
            "必须按深浅主题分档取色（LocalIsDarkTheme）",
            bar.contains("LocalIsDarkTheme"),
        )
        val backdrop = code(backdropPath)
        assertTrue(
            "Backdrop.kt 必须声明唯一的一组玻璃参数（容器尺寸 / 容器折射 / 滑块折射 / 形变）",
            backdrop.contains("val ContainerHeight") &&
                backdrop.contains("val ContainerBlurRadius") &&
                backdrop.contains("val ContainerRefractionHeight") &&
                backdrop.contains("val ContainerRefractionAmount") &&
                backdrop.contains("val PillRefractionHeight") &&
                backdrop.contains("val PillRefractionAmount") &&
                backdrop.contains("val PressStretch") &&
                backdrop.contains("val PillInnerShadowRadius"),
        )
        assertTrue(
            "折射与拉伸参数必须由栏从 GlassBackdrop 读，不许在调用点写死 dp 数",
            bar.contains("GlassBackdrop.ContainerRefractionHeight") &&
                bar.contains("GlassBackdrop.ContainerRefractionAmount") &&
                bar.contains("GlassBackdrop.PillRefractionHeight") &&
                bar.contains("GlassBackdrop.PillRefractionAmount") &&
                bar.contains("GlassBackdrop.PressStretch") &&
                bar.contains("GlassBackdrop.PillInnerShadowRadius"),
        )
        assertTrue(
            "强调色必须来自主题角色（Primer.Blue500）—— 它是滑块里那张染色层的唯一颜色来源",
            bar.contains("Primer.Blue500"),
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

    /**
     * 第十二条：**虚拟屏上复现出来的两条**（2026-10-08，安装态 `com.branchbase`）。
     *
     * 两条都不会编译报错、单测也不会红，但都会在设备上看得见：
     *
     * 1. **静止态滑块是一块纯蓝方块、里面没有图标** —— 录制层被当成了「降级样式」去画
     *    （实心 `Primer.Blue500` 底 + 白色图标），而外层又整片 `ColorFilter.tint(强调色)`，
     *    于是白图标被染成强调色、压在强调色底上 = 看不见。录制层必须只录**图标本身**。
     * 2. **回调判据读的是快照** —— `LaunchedEffect(drag)` 启动那一刻的 `selectedSlot`，
     *    `0 != 0` 不成立就被吞掉。判据必须读 `rememberUpdatedState` 里的**最新**选中项。
     * 3. **滑块停在上一个槽位不走，并把那一格的点按吃掉**（主 Tab ↔ 消息 Tab 回不来的真因）：
     *    `currentSlot` 用 `remember(selectedSlot)` 作 key，外部选中项一变就换掉 state 对象，
     *    而长活的 `snapshotFlow { currentSlot }` 观察的是旧对象 ⇒ 永不重发 ⇒ `animateToValue`
     *    不被调用 ⇒ 滑块不动；滑块又带着 pointerInput 压在那一格上 ⇒ 点按被它吃掉，
     *    日志里连页面切换记录都没有。所以：state 不许带 key，手势不许挂在滑块上。
     */
    @Test
    fun `录制层不许用降级样式且滑块不许吃掉点按`() {
        val bar = code(barPath)
        assertTrue(
            "染色录制层必须按「有采样」的正常样式画（`GlassBarCells(…, sampled = true, …)`）—— " +
                "传 false 会让单元格画成实心强调色底 + 白图标，再被 tint 整片染色，滑块里就只剩一块纯色",
            bar.contains("GlassBarCells(items, showLabels, sampled = true, trailing = trailing)"),
        )

        val guard = bar.substringAfter("LaunchedEffect(drag)").substringBefore("val highlight = remember")
        assertTrue(
            "回调判据必须读最新选中项（`latestSelectedSlot.value`）—— " +
                "读快照 `selectedSlot` 会让「点回原来的那一项」被静默吞掉",
            guard.contains("latestSelectedSlot.value"),
        )
        assertTrue(
            "判据里不许再出现快照比较 `slot != selectedSlot`",
            !guard.contains("slot != selectedSlot"),
        )

        assertTrue(
            "`currentSlot` 必须用 `remember { … }`（不许带 selectedSlot 当 key）—— " +
                "带 key 会换掉 state 对象，长活 snapshotFlow 观察旧对象永不重发，滑块就此不动",
            bar.contains("var currentSlot by remember { mutableIntStateOf(selectedSlot) }") &&
                !bar.contains("remember(selectedSlot) { mutableIntStateOf"),
        )
        assertTrue(
            "手势照上游挂在滑块上（`drag.modifier` / `highlight.gestureModifier` 各一处）—— " +
                "所以「滑块必须始终跟着真实选中项」是硬约束：它一旦落后一格，" +
                "被 pointerInput 吃掉的就是**另一格**的点按",
            Regex("""\bdrag\.modifier""").findAll(bar).count() == 1 &&
                bar.contains(".then(drag.modifier)") &&
                bar.contains(".then(highlight.gestureModifier)"),
        )
    }

    /**
     * 第十三条：**栏是覆盖层，页面贴底的浮动件必须自己让位**。
     *
     * 悬浮形态下内容铺满全屏、栏盖在底部，页面里 `bottom = 18.dp` 之类的位置正好在栏后面。
     * 2026-10-08 用户报告：消息页的筛选悬浮球被悬浮导航栏遮挡。
     *
     * 契约有三段，缺一段就会出现「有的让了、有的还挡着」：
     * 1. 壳子算出「栏占掉多高」并下发（含手势条）；
     * 2. 页面读它，而不是写死 80dp（写死会在换机型/换形态时错）；
     * 3. 同页**所有**贴底件一起让位（球、面板、多选条、撤销条）—— 只抬球，面板就压在栏后面。
     */
    @Test
    fun `消息页贴底浮动件必须让开悬浮栏`() {
        val shell = code(shellPath)
        assertTrue(
            "壳子必须声明让位高度（`FloatingBarReservedHeight`）与下发通道（`LocalFloatingBarReservedHeight`）",
            shell.contains("val FloatingBarReservedHeight: Dp =") &&
                shell.contains("val LocalFloatingBarReservedHeight = staticCompositionLocalOf") &&
                shell.contains("LocalFloatingBarReservedHeight provides"),
        )

        val notif = code("src/main/java/com/branchbase/ui/notification/NotificationScreen.kt")
        assertTrue(
            "消息页必须读壳子下发的让位高度（不许自己写 80dp）",
            notif.contains("LocalFloatingBarReservedHeight.current"),
        )
        assertTrue(
            "筛选球必须抬到栏之上（`bottom = 18.dp + barReserved`）—— 这就是用户报的那一处",
            notif.contains("bottom = 18.dp + barReserved"),
        )
        assertTrue(
            "面板、多选条、撤销条必须一起让位，否则球抬了、它们还压在栏后面",
            notif.contains("bottom = 78.dp + barReserved") &&
                notif.contains("padding(bottom = barReserved)") &&
                notif.contains("barReserved + if (inSelection)"),
        )
    }
}
