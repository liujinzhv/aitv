package com.mytv.launcher.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** 焦点被选中：放大 + 抬起 + 描边，统一走 SelectionFx */
fun View.focusScale(scale: Float = 1.05f, elevationDp: Float = 10f) {
    SelectionFx.applyFocus(this, scale, elevationDp)
}

/** 透明玻璃卡片背景，透出桌面壁纸，带焦点描边（描边画最上层，避免被渐变色盖住） */
fun featureCardBackground(context: Context, color1: Int, color2: Int): Drawable {
    val radius = context.dp(18f).toFloat()

    fun fill(color: Int): Drawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    fun tint(a: Int, b: Int): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(alpha(color1, a), alpha(color2, b))
    ).apply { cornerRadius = radius }

    fun border(widthDp: Float, color: Int): Drawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.TRANSPARENT)
        setStroke(context.dp(widthDp), color)
    }

    fun normal(): Drawable = LayerDrawable(
        arrayOf(
            fill(0x26070B11),
            tint(0x70, 0x30),
            border(1f, 0x2BFFFFFF)
        )
    )

    fun focused(): Drawable = LayerDrawable(
        arrayOf(
            fill(0x440B1119),
            tint(0xC8, 0x66),
            border(1f, 0x73FFFFFF)
        )
    )

    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused())
        addState(intArrayOf(android.R.attr.state_pressed), focused())
        addState(intArrayOf(), normal())
    }
}

private fun alpha(color: Int, value: Int): Int = Color.argb(
    value.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)
)

fun Activity.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

fun Activity.immersive() {
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
}

fun Activity.keepScreenOn() {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

fun formatDuration(ms: Long): String {
    if (ms <= 0 || ms == Long.MIN_VALUE) return "--:--"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1 -> String.format(Locale.US, "%.0f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}

/** 由标题生成稳定的暗色，用作没有海报时的占位底色 */
fun colorFromKey(key: String): Int {
    val hash = abs(key.hashCode())
    val hue = (hash % 360).toFloat()
    return Color.HSVToColor(floatArrayOf(hue, 0.38f, 0.34f))
}

fun CharSequence.isNotBlankStr(): Boolean = this.toString().isNotBlank()

