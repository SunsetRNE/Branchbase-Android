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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.branchbase.core.AvatarCache

/**
 * 统一头像组件（首页入口 / 个人页 / 账号管理 / 仓库贡献者共用）。
 *
 * 加载优先级：
 * 1. **本地缓存** `filesDir/avatars/{login}.png` —— 登录时已预热，命中即「零闪烁」
 * 2. 网络：GitHub 的 `avatar_url` 默认是 460×460 原图，这里按目标尺寸追加 `?s=<px>`
 *    （3 倍密度、48–460），只下需要的像素
 * 3. 两者都没有：先显示首字母占位（图片就绪后覆盖，避免空白）
 *
 * 缓存按 login 隔离，切换账号不会串头像；网页端换头像后用
 * [AvatarCache.refresh] 手动刷新（个人主页长按头像触发）。
 */
@Composable
fun Avatar(
    url: String?,
    login: String,
    size: Dp,
    modifier: Modifier = Modifier,
    name: String? = null,
    background: Color = Primer.Blue500,
    /** 递增此值可强制重新读本地缓存（长按头像刷新后使用）。 */
    version: Int = 0,
) {
    val context = LocalContext.current
    // 本地缓存优先：命中时完全不发网络请求
    val local = remember(login, version) { AvatarCache.localFileOrNull(context, login) }
    val model: Any? = local ?: avatarUrlSized(url, size)
    val label = name?.takeIf { it.isNotBlank() } ?: login

    Box(
        modifier = modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        // 占位层：首字母（本地/网络图就绪前可见，就绪后被覆盖）
        Text(
            label.take(1).uppercase(),
            color = Color.White,
            fontSize = (size.value * 0.44f).sp,
            fontWeight = FontWeight.Bold,
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = label,
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
