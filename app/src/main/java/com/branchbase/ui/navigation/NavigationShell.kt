package com.branchbase.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.Primer

/**
 * 带底部导航栏的页面骨架：**导航栏的槽位在这里，不在页面切换器里**。
 *
 * 全项目只有三处「带底部导航栏的骨架」（主界面 / 仓库页 / 个人页），都走这个壳子；
 * 壳子只负责三件事：内容区、导航栏槽位、两者的内边距契约。**导航栏长什么样不归它管** ——
 * [bar] 是调用方传进来的（普通底栏 / 仓库底栏 / 气泡栏），将来换悬浮、玻璃形态时改的是
 * bar 的实现与参数，不是这个壳子，也不用动三处调用点。
 *
 * ## 为什么栏必须待在切换器外面（这不是洁癖，是 bug）
 *
 * [PageSwitcher] / [TabSwitcher] 是 `AnimatedContent`，同级切换会播
 * `PageTransitions.lightTransform()`（今天是**纯交叉淡化**；2026-09 之前还带 2% 高的垂直位移）。
 * 栏如果长在切换器**里面**，切一次 Tab 就被动一次：当年那 2% 的位移让旧栏一边淡出一边上移、
 * 新栏从下方一边淡入一边归位，两栏同时在屏上错位叠着 —— 用户看到的就是「切页面时导航栏上下跳」。
 * 位移撤掉之后这条**规则仍然不变**，理由是另外两条：
 * 1. **页面切换只该动页面，不该动外壳**；
 * 2. 悬浮栏要求内容从栏下方穿过去（栏不占位），占位式的 `Scaffold.bottomBar` 做不到；
 *    半透明 / 模糊栏如果还在 `AnimatedContent` 里，父级淡入淡出还会和栏自己的 α 相乘，
 *    模糊层也会被每帧重建（掉帧 + 采样到不该采的东西）。
 *
 * ## 内边距契约（现在就是 0，但请照用）
 *
 * [content] 拿到的 [PaddingValues] 由壳子算：今天栏是**占位**的（`Column` 里独占一行），
 * 内容区自然在上面，所以回传 0；将来栏改成**悬浮覆盖**时，壳子回传「栏高 + 间距 + 系统手势条」
 * 的真实内边距，**调用点一行不用改**。所以调用点只允许
 * `Modifier.padding(contentPadding)`，不许自己写死栏高或凑数字。
 *
 * ## 栏的进出由自己负责
 *
 * `barVisible=false`（例如从 Tab 骨架进了全屏详情页）时栏用 [ElementMotion.REVEAL_MS] 收起，
 * 不再被整页动画拖着走；系统手势条的内边距由**栏自己**负责（M3 `NavigationBar` 自带
 * `NavigationBarDefaults.windowInsets`，自定义栏要自己 `navigationBarsPadding()`），
 * 壳子不重复叠加 —— 否则 M3 那条会被叠两层。
 */
@Composable
fun NavigationShell(
    bar: @Composable () -> Unit,
    barVisible: Boolean = true,
    modifier: Modifier = Modifier,
    containerColor: Color = Primer.BackgroundPrimary,
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().background(containerColor)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content(PaddingValues(0.dp))
        }

        // 进出用纵向展开/收起而不是位移：位移不改布局尺寸，栏会让出一块空白再"啪"地消失；
        // expandFrom / shrinkTowards 都取 Top —— 内容锚在栏的上沿，观感就是栏从底部升起、向下沉回。
        AnimatedVisibility(
            visible = barVisible,
            enter = expandVertically(
                animationSpec = tween(ElementMotion.REVEAL_MS),
                expandFrom = Alignment.Top,
            ) + fadeIn(tween(ElementMotion.REVEAL_MS)),
            exit = shrinkVertically(
                animationSpec = tween(ElementMotion.REVEAL_MS),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(tween(ElementMotion.REVEAL_MS)),
        ) {
            bar()
        }
    }
}
