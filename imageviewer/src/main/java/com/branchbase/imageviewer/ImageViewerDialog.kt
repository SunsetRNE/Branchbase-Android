package com.branchbase.imageviewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

/**
 * 全屏图片查看器：双指缩放 / 双击放大 / 拖动平移 / 下拉关闭。
 *
 * ## 为什么是 Dialog 而不是页面
 * 它由正文页（WebView）里的图片点击触发，而 WebView 宿主可能嵌在列表 item 里 ——
 * 走导航栈要宿主页面参与（4 个正文页都得加状态），走 `Dialog` 则「谁点谁弹」，
 * 正文渲染器自己就能兜住。
 *
 * ## 鉴权
 * 私有仓库的图片需要 Token：由调用方按域名决定要不要带（[headers]）。
 * 模块既不认识 GitHub、也不自己拼任何凭据。
 *
 * ## 文案
 * 加载失败一律中文（`图片加载失败 / 点按重试`），不把
 * `UnknownHostException: Unable to resolve host …` 这类英文抛给用户。
 */
@Composable
fun ImageViewerDialog(
    url: String,
    title: String? = null,
    headers: Map<String, String> = emptyMap(),
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismissRequest,
        // 占满整窗；点图外不关闭（用户是在看细节，误触关闭很恼人）。
        // 不额外加 statusBars/navigationBars padding：Dialog 窗口默认的 decor 已经让内容避开系统栏，
        // 再补一次会白多出一条状态栏高度的空隙。
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        // 手势状态放进持有对象：pointerInput 的 lambda 只在 key 变化时重建，
        // 直接捕获局部变量会一直读到「创建那一刻」的旧值（缩放会越缩越怪）。
        val viewer = remember(url) { ViewerState() }
        val context2 = context

        val dismissProgress = ImageViewerMath.dismissProgress(
            viewer.dragY,
            viewer.container.height.toFloat(),
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 1f - dismissProgress * 0.75f)),
        ) {
            Column(Modifier.fillMaxSize()) {
                ViewerTopBar(
                    title = title,
                    onOpenInBrowser = { openInBrowser(context2, url) },
                    onClose = onDismissRequest,
                )

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { viewer.container = it }
                        // 双击：以点击点为锚在 1x / 2.5x 之间切换
                        .pointerInput(url) {
                            detectTapGestures(
                                onDoubleTap = { position ->
                                    val target = ImageViewerMath.doubleTapTarget(viewer.scale)
                                    val factor = if (viewer.scale <= 0f) 1f else target / viewer.scale
                                    viewer.offset = viewer.clamp((viewer.offset - position) * factor + position, target)
                                    viewer.scale = target
                                    viewer.dragY = 0f
                                },
                            )
                        }
                        // 捏合 + 拖动（未放大时的下拉 = 关闭手势）
                        .pointerInput(url) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                if (zoom == 1f && ImageViewerMath.isAtUnitScale(viewer.scale)) {
                                    viewer.dragY = (viewer.dragY + pan.y).coerceAtLeast(0f)
                                    if (ImageViewerMath.shouldDismissByDrag(
                                            viewer.dragY,
                                            viewer.scale,
                                            viewer.container.height.toFloat(),
                                        )
                                    ) {
                                        onDismissRequest()
                                        return@detectTransformGestures
                                    }
                                }
                                val nextScale = ImageViewerMath.clampScale(viewer.scale * zoom)
                                val factor = if (viewer.scale <= 0f) 1f else nextScale / viewer.scale
                                // 以捏合中心为锚点：先平移再围绕 centroid 缩放，手指下的点才不会跑
                                val moved = (viewer.offset + pan) * factor + centroid * (1f - factor)
                                viewer.scale = nextScale
                                viewer.offset = viewer.clamp(moved, nextScale)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // attempt 变化 = 用户点了重试：换 key 重建 painter，强制重新请求
                    key(viewer.attempt) {
                        AsyncImage(
                            model = ImageRequest.Builder(context2)
                                .data(url)
                                .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
                                .crossfade(true)
                                .build(),
                            contentDescription = title ?: "图片",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = viewer.scale
                                    scaleY = viewer.scale
                                    translationX = viewer.offset.x
                                    // 未放大时图片跟着手指走一半，给「松手就关」的反馈
                                    translationY = viewer.offset.y + viewer.dragY * 0.5f
                                },
                            onState = { state -> viewer.onImageState(state) },
                        )
                    }
                    viewer.state?.let { state -> ViewerOverlay(state, onRetry = { viewer.attempt++ }) }
                }

                Text(
                    "双指缩放 · 双击放大 · 下拉关闭",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            }
        }
    }
}

/** 查看器的可变状态（手势状态 + 图片状态 + 容器/图片尺寸）。 */
private class ViewerState {
    var scale by mutableFloatStateOf(ImageViewerMath.MIN_SCALE)
    var offset by mutableStateOf(Offset.Zero)
    var dragY by mutableFloatStateOf(0f)
    var container by mutableStateOf(IntSize.Zero)
    var fitted by mutableStateOf(Size.Zero)
    var attempt by mutableIntStateOf(0)
    var state by mutableStateOf<AsyncImagePainter.State?>(null)

    fun maxOverflowX(scale: Float = this.scale): Float =
        ImageViewerMath.maxOverflow(container.width.toFloat(), fitted.width, scale)

    fun maxOverflowY(scale: Float = this.scale): Float =
        ImageViewerMath.maxOverflow(container.height.toFloat(), fitted.height, scale)

    fun clamp(offset: Offset, scale: Float = this.scale): Offset = Offset(
        ImageViewerMath.clampTranslation(offset.x, maxOverflowX(scale)),
        ImageViewerMath.clampTranslation(offset.y, maxOverflowY(scale)),
    )

    fun onImageState(next: AsyncImagePainter.State) {
        state = next
        if (next is AsyncImagePainter.State.Success) {
            val drawable = next.result.drawable
            val (w, h) = ImageViewerMath.fitSize(
                imageWidth = drawable.intrinsicWidth.toFloat(),
                imageHeight = drawable.intrinsicHeight.toFloat(),
                containerWidth = container.width.toFloat(),
                containerHeight = container.height.toFloat(),
            )
            fitted = Size(w, h)
        }
    }
}

/** 顶栏：标题 + 用浏览器打开 + 关闭。 */
@Composable
private fun ViewerTopBar(title: String?, onOpenInBrowser: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title?.takeIf { it.isNotBlank() } ?: "图片",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "用浏览器打开",
            color = Color(0xFF8AB4F8),
            fontSize = 12.5.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onOpenInBrowser() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Filled.Close,
            contentDescription = "关闭",
            tint = Color.White,
            modifier = Modifier.size(22.dp).clickable { onClose() },
        )
    }
}

/**
 * 加载中 / 失败遮罩。
 *
 * 失败后整块可点重试：移动端网络抖动很常见，「请检查网络 + 只能关闭」是很糟的体验。
 * 只给中文结论，技术细节（异常类型 / 主机名）不进用户视野。
 */
@Composable
private fun ViewerOverlay(state: AsyncImagePainter.State, onRetry: () -> Unit) {
    val failed = state is AsyncImagePainter.State.Error
    val loading = state is AsyncImagePainter.State.Loading
    if (!failed && !loading) return

    Box(
        Modifier
            .fillMaxSize()
            .then(if (failed) Modifier.clickable { onRetry() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("图片加载失败", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(6.dp))
                Text("点按重试", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        } else {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    }
}

/** 「用浏览器打开」：模块不引 :app / :downloader 的任何东西，直接用系统 Intent。 */
private fun openInBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
