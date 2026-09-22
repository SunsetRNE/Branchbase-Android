package com.branchbase.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.AvatarCache
import com.branchbase.core.AvatarMemory

/**
 * 统一头像组件（首页入口 / 个人页 / 账号管理 / 仓库贡献者共用）。
 *
 * 加载优先级（1.0.62 起**首帧同步直出**）：
 * 1. **进程内解码缓存** [AvatarMemory]：命中即同一帧画出来（重建时不再经历占位层）；
 * 2. **本地缓存** `filesDir/avatars/{login}.png` —— 登录时已预热；
 * 3. 网络：GitHub 的 `avatar_url` 默认是 460×460 原图，落盘后按目标像素解码（`inSampleSize`）；
 * 4. 都没有：显示蓝底首字母占位。
 *
 * ## 为什么不再交给 Coil 异步加载
 *
 * 真机反馈：「头像会**反复**蓝底图标，然后才加载成真实图标」。原因是异步路径下
 * **每一次重建都要重放一遍占位 → 真图**（切 Tab、进出子页、开 More 菜单…），
 * 而头像在界面上出现得非常频繁，这个闪就很显眼。现在解码结果留在进程内（24 条 LRU），
 * 重建时同一帧就能拿到 Bitmap。
 *
 * 缓存按 login 隔离，切换账号不会串头像；网页端换头像后用
 * [AvatarCache.refresh] 手动刷新（个人主页长按头像触发，`version` 递增 → 键随之变化）。
 *
 * ## 需要点击反馈时，用 [iconTap] 而不是直接传 clickable
 *
 * 本组件是 `modifier.size(size).clip(CircleShape)` —— 调用方传进来的 modifier 排在 clip
 * **之前**，所以 `Modifier.clickable {}` 的点击节点落在圆形裁剪**外面**，水波纹会是正方形。
 * `Modifier.iconTap { }`（或带 `onLongClick` 的长按形态）会把 clip 提到点击之前，反馈即圆形。
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
    val px = with(LocalDensity.current) { size.roundToPx() }
    // 首帧同步直出：内存里有解码好的图就**在同一帧**画出来，不再经历「蓝底 → 真图」
    var bitmap by remember(login, px, version) { mutableStateOf(AvatarMemory.get(login, px, version)) }
    LaunchedEffect(login, px, version, url) {
        if (bitmap == null) bitmap = AvatarMemory.loadOrNull(context, login, px, version, url)
    }
    val label = name?.takeIf { it.isNotBlank() } ?: login

    Box(
        modifier = modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp == null) {
            // 占位层：首字母（首次加载期间可见；之后由内存缓存直出，不再出现）
            Text(
                label.take(1).uppercase(),
                color = Color.White,
                fontSize = (size.value * 0.44f).sp,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

