package com.branchbase.ui.navigation

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 悬浮导航栏可见时，滚动列表的“底部清障”入口。
 *
 * 悬浮栏是覆盖层（内容要能从栏后穿过，玻璃才有像素可采），所以不能给整个页面加
 * 容器级 bottom padding —— 那会把内容整个顶到栏上方，玻璃就变成一块实色。
 * 正确做法是给**滚动内容**补尾部留白：列表滚到底时最后一项还能继续滚到栏上方。
 *
 * 这里统一做三件事：
 * 1. 读取壳子实测的 `LocalFloatingBarReservedHeight`（栏高 + 上下留白 + 手势条）；
 * 2. 只在栏可见（reserved > 0）时补，栏隐藏的子页保持原来的 contentPadding；
 * 3. 取 `max(原底部留白, 栏高 + 16dp)`，已经有更大留白的页面（例如消息页 110dp）不被压小。
 *
 * 页面仍可继续直接用 `LazyColumn`；只是**带悬浮栏的页面**应使用本包装函数，
 * 否则最后一条会被栏压住（2026-10-19 用户反馈：手机/平板横竖屏均出现）。
 */
/**
 * 纯函数：给定原底部留白与栏占高，求滚动内容最终需要的底部留白。
 *
 * 规则：栏可见时至少 `栏高 + 16dp`；原留白更大则保留（例如消息页的 110dp）。
 * 栏隐藏（reserved = 0）时完全保持原值，避免给子页凭空多出一段空白。
 */
internal fun barAwareBottomPadding(existingBottom: Dp, barReserved: Dp): Dp =
    if (barReserved > 0.dp) maxOf(existingBottom, barReserved + 16.dp) else existingBottom

@Composable
fun BranchbaseLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val barReserved = LocalFloatingBarReservedHeight.current
    val layoutDirection = LocalLayoutDirection.current
    val existingBottom = contentPadding.calculateBottomPadding()
    val targetBottom = barAwareBottomPadding(existingBottom, barReserved)
    val adjustedPadding = PaddingValues(
        start = contentPadding.calculateStartPadding(layoutDirection),
        top = contentPadding.calculateTopPadding(),
        end = contentPadding.calculateEndPadding(layoutDirection),
        bottom = targetBottom,
    )
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = adjustedPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}
