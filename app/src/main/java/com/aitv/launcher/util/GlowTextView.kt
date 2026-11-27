package com.aitv.launcher.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.TextView

/**
 * 聚焦时文字带外发光的 TextView。
 *
 * 硬件加速画布对 `Paint#setShadowLayer` 的文字支持在 Android 8 上并不稳定，
 * 所以这里把文字在离屏 bitmap（软件画布）上模糊画几遍当光晕，再叠在正文下面，
 * 任何版本都能稳定发光，且不需要额外的自绘控件。
 */
class GlowTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : TextView(context, attrs, defStyleAttr) {

    private var glowOn = false
    private var glowBitmap: Bitmap? = null
    private var glowKey: String? = null

    private val density = context.resources.displayMetrics.density
    private val padPx = (GLOW_PAD_DP * density + 0.5f).toInt()

    /** 由 SelectionFx 在父控件获得 / 失去焦点时调用 */
    fun setGlow(enabled: Boolean) {
        if (glowOn == enabled) return
        glowOn = enabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (glowOn) {
            ensureGlow()?.let { canvas.drawBitmap(it, -padPx.toFloat(), -padPx.toFloat(), null) }
        }
        super.onDraw(canvas)
    }

    private fun ensureGlow(): Bitmap? {
        // 深色文字配白色光晕会在浅色背景上糊成一团，直接不画光晕
        if (android.graphics.Color.luminance(currentTextColor) < 0.5f) return null
        val layout = layout ?: return null
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val key = "$w|$h|${textSize}|${text}|${currentTextColor}"
        if (key == glowKey) return glowBitmap

        val bitmap = Bitmap.createBitmap(
            w + padPx * 2,
            h + padPx * 2,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val tp = paint
        val oldColor = tp.color
        val oldStyle = tp.style

        canvas.translate(padPx.toFloat(), padPx.toFloat())
        // 逐行 drawText 而不是 layout.draw：后者会把整块布局一起画进 bitmap，光晕会变成方块底
        for (ring in GLOW_RINGS) {
            tp.color = ring.color
            tp.setShadowLayer(ring.radiusDp * density, 0f, 0f, ring.color)
            for (i in 0 until layout.lineCount) {
                val line = text.subSequence(layout.getLineStart(i), layout.getLineEnd(i)).toString()
                if (line.isEmpty()) continue
                canvas.drawText(line, layout.getLineLeft(i), layout.getLineBaseline(i).toFloat(), tp)
            }
        }

        tp.color = oldColor
        tp.style = oldStyle
        tp.setShadowLayer(0f, 0f, 0f, 0)

        glowBitmap?.recycle()
        glowBitmap = bitmap
        glowKey = key
        return bitmap
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glowBitmap?.recycle()
        glowBitmap = null
        glowKey = null
    }

    private class Ring(val radiusDp: Float, val color: Int)

    private companion object {
        // 留足模糊扩散的余量（约为最大半径的 2.5 倍），否则光晕会在 bitmap 边界被切出方框
        const val GLOW_PAD_DP = 40f

        // 从外到内：大半径低透明度先铺开，小半径高透明度提亮字缘
        val GLOW_RINGS = arrayOf(
            Ring(16f, 0x66FFFFFF),
            Ring(8f, 0xA6FFFFFF.toInt()),
            Ring(2.5f, 0xE6FFFFFFL.toInt())
        )
    }
}
