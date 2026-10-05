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
 * Project-owned liquid-glass surface adapted from the visual pipeline in
 * SyntaxJester/DouyinLiquidGlass (MIT): layered tint, top sheen and a bright
 * fading rim. It is a content-layer fallback rather than an Xposed overlay.
 */
class LiquidGlassSurface(context: Context) : View(context) {
    var blurRadiusPx: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 80f)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                setRenderEffect(
                    if (field > 0f) android.graphics.RenderEffect.createBlurEffect(
                        field,
                        field,
                        android.graphics.Shader.TileMode.CLAMP,
                    ) else null,
                )
            }
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bounds = RectF()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = min(h / 2f, 30f * resources.displayMetrics.density)
        bounds.set(0f, 0f, w, h)

        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb(172, 255, 255, 255),
                Color.argb(106, 238, 245, 255),
                Color.argb(76, 196, 211, 232),
                Color.argb(126, 28, 34, 48),
            ),
            floatArrayOf(0f, 0.25f, 0.63f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(bounds, radius, radius, fill)
        fill.shader = null

        val sheenHeight = h * 0.52f
        sheen.shader = LinearGradient(
            0f, 0f, 0f, sheenHeight,
            Color.argb(150, 255, 255, 255),
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
            Color.argb(210, 255, 255, 255),
            Color.argb(36, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(inner, radius, radius, rim)
        rim.shader = null
    }
}
