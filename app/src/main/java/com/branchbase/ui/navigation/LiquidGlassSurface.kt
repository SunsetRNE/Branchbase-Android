package com.branchbase.ui.navigation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.min

/**
 * 玻璃导航栏的绘制层：分层色 + 顶部高光 + 渐隐亮边。
 *
 * 视觉管线参考 SyntaxJester/DouyinLiquidGlass（MIT）的「分层渐变 / 顶部高光 / 渐隐描边」
 * 三件套；来源与移植边界登记在 `THIRD-PARTY-NOTICES.md` §2.2（不含其 Xposed 注入路径）。
 *
 * ## 为什么它不做模糊（这一版把那条路删了）
 *
 * 这里是一个普通 `View`。`RenderEffect` 作用于**它自己的渲染结果**，看不到身后的
 * Compose 内容 —— 真正的 backdrop blur 必须采样栏后面的页面像素，不是给这一层加个
 * 模糊半径就能得到的。而那个 `blurRadiusPx` 从引入起**没有任何调用点赋值**：属性初始化
 * 写的是 backing field、不经过 setter，所以 `setRenderEffect` 一次都没被执行过，
 * 运行时半径恒为 0。留着它比删掉更糟 —— 它让人以为「模糊已经做了」。
 *
 * 同理不再强制 `LAYER_TYPE_SOFTWARE`：本 View 只画渐变与圆角描边，硬件管线完全够用，
 * 软件层只会把这一层的合成从 GPU 挪回 CPU。
 *
 * 要做真玻璃，前置条件是「页面内容真的铺到栏下方」——今天 `NavigationShell` 是
 * `Column` 上下分行，栏后面没有内容可采；那属于布局契约的改动，见
 * `docs/specs/VERSION-NOTES.md` 1.2.2 的边界说明。
 *
 * ## 颜色为什么必须由调用方下发
 *
 * 它拿不到 Compose 主题。原先写死的白渐变叠在深色页面上就是一条亮带，而且与
 * `ui/theme/` 的主题角色脱节；现在色组由 [GlassNavigationBar] 按当前主题算好后
 * 经 [tint] 传进来，换色板时玻璃跟着走。
 */
class LiquidGlassSurface(context: Context) : View(context) {

    /**
     * 玻璃色组：**已含 alpha 的 ARGB**，由调用方按主题算好。
     *
     * 竖向四段保留原设计的层次：顶部最亮（高光），底部收一层暗色（厚度感）。
     */
    data class Tint(
        val top: Int,
        val upper: Int,
        val lower: Int,
        val bottom: Int,
        val sheen: Int,
        val rimTop: Int,
        val rimBottom: Int,
    ) {
        companion object {
            /**
             * 还没拿到主题时的占位：**全透明**。
             *
             * 不拿白色顶上 —— 宁可有那么一帧什么都不画，也不能闪一帧白。
             */
            val Empty = Tint(0, 0, 0, 0, 0, 0, 0)
        }
    }

    /** 主题下发的色组；[GlassNavigationBar] 在 `AndroidView.update` 里对齐主题。 */
    var tint: Tint = Tint.Empty
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bounds = RectF()

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = min(h / 2f, 30f * resources.displayMetrics.density)
        bounds.set(0f, 0f, w, h)

        // 四段竖向渐变：颜色全部来自主题下发的 [tint]，这里不再有任何写死的色值。
        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(tint.top, tint.upper, tint.lower, tint.bottom),
            floatArrayOf(0f, 0.25f, 0.63f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(bounds, radius, radius, fill)
        fill.shader = null

        val sheenHeight = h * 0.52f
        sheen.shader = LinearGradient(
            0f, 0f, 0f, sheenHeight,
            tint.sheen,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.save()
        canvas.clipRect(0f, 0f, w, sheenHeight)
        canvas.drawRoundRect(bounds, radius, radius, sheen)
        canvas.restore()
        sheen.shader = null

        val stroke = 1.4f * resources.displayMetrics.density
        rim.strokeWidth = stroke
        val inset = stroke / 2f
        val inner = RectF(inset, inset, w - inset, h - inset)
        rim.shader = LinearGradient(
            0f, 0f, 0f, h,
            tint.rimTop,
            tint.rimBottom,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(inner, radius, radius, rim)
        rim.shader = null
    }
}
