package com.mytv.launcher.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.recyclerview.widget.RecyclerView
import android.widget.EditText
import android.widget.SeekBar
import java.util.WeakHashMap

/**
 * 电视端统一的「选中效果」：
 *
 * · 焦点被选中（遥控器移到某个控件）：轻微放大 + 抬起 + 白色描边；
 *   控件背景本身已经带 selector 焦点态的（按钮、海报、列表项）不再重复描边。
 * · 状态选中（isSelected = true，例如当前曲目 / 当前标签页 / 已收藏）：
 *   保留强调色底 + 描边，靠 setSelected 驱动，不依赖焦点。
 *
 * 页面级控件由 [decorate] 在 Activity 恢复时统一装饰，
 * RecyclerView 的列表项由各自 adapter 调用 [applyFocus] 处理。
 */
object SelectionFx {

    private const val FOCUS_FILL = 0x404EA1FF
    private const val FOCUS_STROKE = -0x1
    private const val SELECTED_FILL = 0x404EA1FF
    private const val SELECTED_STROKE = 0xFF4EA1FF.toInt()

    private val decorated = WeakHashMap<View, Boolean>()

    fun applyFocus(view: View, scale: Float = 1.03f, elevationDp: Float = 6f) {
        if (decorated.containsKey(view)) return
        decorated[view] = true
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            v.animate()
                .scaleX(if (hasFocus) scale else 1f)
                .scaleY(if (hasFocus) scale else 1f)
                .setDuration(160)
                .start()
            v.elevation = if (hasFocus) v.context.dp(elevationDp).toFloat() else 0f
            v.isActivated = hasFocus
            setGlow(v, hasFocus)
        }
        if (!hasOwnFocusState(view.background)) {
            view.background = focusStateDrawable(view.context, view.background)
        }
    }

    /** 焦点变化时把发光开关下发给卡片里的 GlowTextView（焦点在卡片容器上，文字本身不聚焦） */
    private fun setGlow(view: View, on: Boolean) {
        when (view) {
            is GlowTextView -> view.setGlow(on)
            is ViewGroup -> for (i in 0 until view.childCount) setGlow(view.getChildAt(i), on)
        }
    }

    /** 递归补齐页面里所有可点 / 可聚焦控件的选中效果；列表项与播放器画面单独处理 */
    fun decorate(root: View) {
        applyTo(root)
        if (root is WebView) return
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) decorate(root.getChildAt(i))
        }
    }

    private fun applyTo(view: View) {
        // 列表本身不再抢焦点：没有子项时焦点应该跳过它，而不是停在一片没有反馈的空白上
        if (view is RecyclerView) {
            view.isFocusable = false
            view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            return
        }
        if (!view.isClickable && !view.isFocusable) return
        if (skip(view)) return
        if (view is SeekBar) {
            // 进度条只描边，不放大
            applyFocus(view, 1f, 2f)
            return
        }
        applyFocus(view, DEFAULT_SCALE, DEFAULT_ELEVATION_DP)
    }

    /** 这些控件自带交互表现，放大 / 描边反而碍事 */
    private fun skip(view: View): Boolean {
        if (view is EditText) return true
        val name = view.javaClass.name
        return name.contains("WebView") ||
            name.contains("PlayerView") ||
            name.contains("SurfaceView") ||
            name.contains("TextureView") ||
            name.contains("ScrollView")
    }

    /** 背景已经用 selector / ripple 处理过焦点态的，不再包一层描边 */
    private fun hasOwnFocusState(background: Drawable?): Boolean =
        background is StateListDrawable || background is RippleDrawable || background is LayerDrawable

    private fun focusStateDrawable(context: Context, original: Drawable?): Drawable {
        val radius = context.dp(12f).toFloat()

        fun base(): Drawable = original?.constantState
            ?.newDrawable(context.resources)
            ?.mutate() ?: ColorDrawable(Color.TRANSPARENT)

        fun highlight(fill: Int, strokeColor: Int): Drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            setStroke(context.dp(2f), strokeColor)
        }

        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                LayerDrawable(arrayOf(base(), highlight(FOCUS_FILL, FOCUS_STROKE)))
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                LayerDrawable(arrayOf(base(), highlight(FOCUS_FILL, FOCUS_STROKE)))
            )
            addState(
                intArrayOf(android.R.attr.state_selected),
                LayerDrawable(arrayOf(base(), highlight(SELECTED_FILL, SELECTED_STROKE)))
            )
            addState(intArrayOf(), base())
        }
    }

    private const val DEFAULT_SCALE = 1.03f
    private const val DEFAULT_ELEVATION_DP = 6f
}
