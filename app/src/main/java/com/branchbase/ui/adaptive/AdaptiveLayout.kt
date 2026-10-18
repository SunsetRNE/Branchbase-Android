package com.branchbase.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

/** 预览/测试兜底：没有根 provider 时按 360×800 手机判定，避免叶子读取时崩溃。 */
private val DefaultWindowSpec = WindowSpec(widthDp = 360, heightDp = 800, density = 3f, fontScale = 1f)

/**
 * 当前窗口的判定结果。
 *
 * 用 [compositionLocalOf]（而不是 static）：窗口尺寸变化时只让真正读取 plan
 * 的组件重组，而不是整棵树一起重组。
 */
val LocalLayoutPlan = compositionLocalOf { AdaptiveLayoutPolicy.plan(DefaultWindowSpec) }

/**
 * 把当前 Compose 窗口的 Configuration 收敛成 [WindowSpec]。
 *
 * 全项目只在这里读窗口尺寸；页面/组件不要各自读 `LocalConfiguration` 做断点判断，
 * 否则尺寸变化时会出现多处隐式重组。
 */
@Composable
fun rememberWindowSpec(): WindowSpec {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        density.density,
        density.fontScale,
    ) {
        WindowSpec(
            widthDp = configuration.screenWidthDp,
            heightDp = configuration.screenHeightDp,
            density = density.density,
            fontScale = density.fontScale,
        )
    }
}

/**
 * 应用级布局判定入口。放在 `setContent` 的主题之下、业务之上：
 *
 * ```
 * BranchbaseTheme(...) {
 *     ProvideAdaptiveLayout {
 *         LoginFlow()
 *         ...
 *     }
 * }
 * ```
 *
 * 判定本身是纯函数、微秒级，所以这里同步算首帧的 plan（避免先闪一帧错误布局）；
 * 后续页面级的重计算/预取可以再通过后台协程补充，不需要把 measure/layout 搬到后台。
 */
@Composable
fun ProvideAdaptiveLayout(content: @Composable () -> Unit) {
    val window = rememberWindowSpec()
    val plan = remember(window) { AdaptiveLayoutPolicy.plan(window) }
    CompositionLocalProvider(LocalLayoutPlan provides plan, content = content)
}

/**
 * 页面内容容器：把整页内容约束在可读宽度内并居中。
 *
 * 紧凑屏（maxWidth = null）保持原来的整宽行为；平板横屏 840dp / 竖屏 760dp，
 * 多出来的宽度留在两侧，而不是把卡片/正文/列表一起拉宽。
 *
 * **只约束内容区**：底部导航栏、系统栏仍由各自的壳子处理；本容器只负责
 * “页面正文的最大行宽”。
 */
@Composable
fun AdaptiveContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val maxWidth = LocalLayoutPlan.current.content.maxWidth
    if (maxWidth == null) {
        Box(modifier.fillMaxSize()) { content() }
    } else {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = maxWidth)
                    .fillMaxWidth(),
            ) {
                content()
            }
        }
    }
}
