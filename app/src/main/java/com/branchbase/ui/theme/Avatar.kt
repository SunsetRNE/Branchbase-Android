package com.branchbase.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * 统一头像组件（首页入口 / 个人页 / 账号管理 / 仓库贡献者共用）。
 *
 * 解决三件事：
 * 1. **下载体积**：GitHub 的 `avatar_url` 默认指向 460×460 原图，直接用它渲染 40dp 头像
 *    要下几十 KB。这里按目标尺寸追加 `?s=<px>`（GitHub 支持 1–460），并按 3 倍密度取像素。
 * 2. **加载空白**：图片未就绪时先画首字母占位，加载完成后覆盖 —— 不再出现「先空白再闪现」。
 * 3. **重复实现**：此前 6 处各写一遍 `AsyncImage` + `if/else`，统一到这里，换加载库只改一处。
 *
 * 注：Coil 自身有内存/磁盘缓存，同一 URL 不会重复下载；本组件只降低首次与清缓存后的体积。
 */
@Composable
fun Avatar(
    url: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    background: Color = Primer.Blue500,
) {
    val target = remember(url, size) { avatarUrlSized(url, size) }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        // 占位层：首字母（图片就绪前可见，就绪后被覆盖）
        Text(
            name.take(1).uppercase(),
            color = Color.White,
            fontSize = (size.value * 0.44f).sp,
            fontWeight = FontWeight.Bold,
        )
        if (target != null) {
            AsyncImage(
                model = target,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

/**
 * 给 GitHub 头像 URL 追加尺寸参数。
 *
 * GitHub 的 avatars.githubusercontent.com 支持 `?s=<px>`（1–460）；按 3 倍密度换算，
 * 上限 460、下限 48，避免小头像请求过小图导致高密度屏发虚。
 */
fun avatarUrlSized(url: String?, size: Dp): String? {
    if (url.isNullOrBlank()) return null
    val px = (size.value * 3).toInt().coerceIn(48, 460)
    val sep = if (url.contains('?')) '&' else '?'
    return url + sep + "s=" + px
}
