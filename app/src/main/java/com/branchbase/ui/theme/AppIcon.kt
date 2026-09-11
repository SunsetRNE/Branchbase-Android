package com.branchbase.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import com.branchbase.R

/**
 * 应用图标：渲染**当前正在使用的那枚图标**，而不是各页面各自拼一个近似。
 *
 * ## 为什么不能直接用 painterResource(R.mipmap.ic_launcher)
 *
 * API 26+ 上 `mipmap-anydpi-v26/ic_launcher.xml` 是 `<adaptive-icon>`（背景层 + 前景层），
 * `painterResource` 只认 VectorDrawable / 位图，遇到 adaptive-icon 会直接抛异常 ——
 * 「关于」页此前就是为此退回「只画 foreground 层 + 硬编码 #0d1117 底色」的手搓近似，
 * 欢迎页更是干脆用蓝底 + 字母 B 占位。两处都与桌面上的真实图标对不上。
 *
 * ## 做法
 *
 * 向 PackageManager 要系统此刻展示的图标（[android.content.pm.PackageManager.getApplicationIcon]）：
 * - 自适应图标的两层合成、圆形/圆角遮罩、厂商 monochrome 主题化，全部由系统完成，天然与桌面一致；
 * - 将来换图标、换底色，页面代码零改动（不存在第二处需要同步的配色常量）；
 * - 光栅化失败时回退到「background + foreground 两层静态合成」，不留白也**绝不崩**
 *   （这正是上面那个崩溃点，必须有兜底路径）。
 *
 * 结果按像素尺寸缓存：欢迎页与关于页尺寸不同各算一次，同尺寸复用，避免重复解码。
 */
@Composable
fun AppIcon(
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(percent = 22),
    contentDescription: String? = "应用图标",
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }.coerceAtLeast(1)
    val bitmap = remember(px) { AppIconCache.of(context, px) }

    Box(
        modifier = modifier.size(size).clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        // 底图 = adaptive icon 的两层静态合成（矢量背景铺满 + 矢量前景铺满）。
        // 它同时也是兜底：PackageManager 取不到、或某些厂商实现返回空位图时，
        // 这里仍然是一枚完整的图标，绝不会出现「空白方块」。
        Box(Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_background),
                contentDescription = if (bitmap == null) contentDescription else null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (bitmap != null) {
            // 系统此刻展示的那枚图标盖在底图上（正常情况下底图被完全覆盖）
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 应用图标光栅缓存。
 *
 * 只缓存「取不到」与「取到了」两种结果（null 也缓存），
 * 避免每次进入页面都重新走一遍 PackageManager + 光栅化。
 */
private object AppIconCache {

    private val lock = Any()
    private val cache = mutableMapOf<Int, ImageBitmap?>()

    fun of(context: Context, px: Int): ImageBitmap? = synchronized(lock) {
        if (cache.containsKey(px)) return cache[px]
        val bitmap = runCatching {
            val appContext = context.applicationContext
            val drawable = appContext.packageManager.getApplicationIcon(appContext.packageName)
            // 显式传宽高：AdaptiveIconDrawable 的 intrinsicWidth 是 -1，走默认值会拿到 0×0
            drawable.toBitmap(width = px, height = px, config = Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
        cache[px] = bitmap
        bitmap
    }
}
