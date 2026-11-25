package com.branchbase.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.ui.adaptive.AdaptiveLayoutPolicy
import com.branchbase.ui.adaptive.LocalLayoutPlan
import com.branchbase.ui.theme.ElementMotion
import com.branchbase.ui.theme.Primer

/**
 * 普通底部导航栏（基础形态 ①）—— Material3 `NavigationBar` 的**压缩版**。
 *
 * 规格（2026-11-25 决策）：
 * - 栏高 = [AdaptiveLayoutPolicy.BottomBarHeight]（80dp → 48dp，砍掉 2/5）；
 * - **只画图标**，不画文字（底栏选项统一去文字）；读屏名仍走 `dest.labelRes`；
 * - 内容宽度走 [AdaptiveLayoutPolicy.metrics]：手机上 2 个 Tab 收成 198dp 的居中方阵，
 *   不再把两个图标摊到屏幕两侧（这就是「压缩原生底栏图标间距」）。
 *
 * M3 的 `NavigationBar` 自带 80dp 下限与窗口内边距，直接压 `.height()` 会把内容挤扁；
 * 所以这里把内边距与底色交给外层 `Box`（栏高之外单独留手势条），
 * `NavigationBar` 本身设成透明 + `windowInsets = 0`，只保留它的选中胶囊与点按态。
 */
@Composable
fun BranchbaseNavigationBar(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    badgeCounts: Map<NavDestination, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val nav = LocalLayoutPlan.current.nav
    val metrics = AdaptiveLayoutPolicy.metrics(nav, NavDestination.entries.size)

    // 底色与手势条内边距放在外层：M3 `NavigationBar` 会把这两件事一起算进 80dp 下限，
    // 直接传 `.height(48.dp)` 时内容会被内边距挤掉一半。外层承担后，栏高就是干净的 48dp。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Primer.BackgroundSecondary)
            .navigationBarsPadding(),
    ) {
        NavigationBar(
            modifier = Modifier.height(nav.height),
            containerColor = Color.Transparent,
            contentColor = Primer.TextPrimary,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            // 内容收窄到 metrics.barWidth 再居中：2 个 Tab 就是 2 × cell（96dp），
            // 图标不再隔着一整块屏幕。NavigationBarItem 内部用 weight(1f)，在固定宽 Row 里均分。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .width(metrics.barWidth)
                        .fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavDestination.entries.forEach { dest ->
                        val count = badgeCounts[dest] ?: 0
                        NavigationBarItem(
                            selected = dest == selected,
                            onClick = { onSelect(dest) },
                            icon = {
                                Box {
                                    Icon(dest.icon, contentDescription = stringResource(dest.labelRes))
                                    // 不再用 `if (count > 0)` 包住：那样徽标是「凭空出现、凭空消失」；
                                    // 可见性交给 CountBadge 自己托管，未读清零时才有淡出
                                    CountBadge(count, Modifier.align(Alignment.TopEnd))
                                }
                            },
                            // 底栏只留图标；文字是读屏名，不上屏
                            label = null,
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 数量 badge（通用组件，可叠加到任意导航变体）。
 *
 * 规格：16dp 高胶囊，红底白字 10sp。出现/消失带缩放淡入淡出 ——
 * 未读数从 0 变 1 是很高频的事件，硬切会显得整条导航栏「抖了一下」。
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = count > 0,
        enter = scaleIn(tween(ElementMotion.BADGE_MS), initialScale = ElementMotion.BADGE_SCALE) +
            fadeIn(tween(ElementMotion.BADGE_MS)),
        exit = scaleOut(tween(ElementMotion.BADGE_MS), targetScale = ElementMotion.BADGE_SCALE) +
            fadeOut(tween(ElementMotion.BADGE_MS)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .background(Primer.Red500, RoundedCornerShape(8.dp))
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (count > 99) "99+" else "$count",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
