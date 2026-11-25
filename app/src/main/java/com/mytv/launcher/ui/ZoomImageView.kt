package com.mytv.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.min

/**
 * 支持遥控器方向键平移、OK 键缩放，以及触摸双指缩放的图片控件。
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    private val drawMatrix = Matrix()
    private var baseScale = 1f
    private var currentScale = 1f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            panBy(-distanceX, -distanceY)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > baseScale * 1.2f) {
                fitToView()
            } else {
                zoomBy(2f, e.x, e.y)
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { fitToView() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    private fun fitToView() {
        val d = drawable ?: return
        val bw = d.intrinsicWidth
        val bh = d.intrinsicHeight
        if (bw <= 0 || bh <= 0 || width <= 0 || height <= 0) return

        val scale = min(width.toFloat() / bw, height.toFloat() / bh)
        baseScale = scale
        currentScale = scale
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate((width - bw * scale) / 2f, (height - bh * scale) / 2f)
        imageMatrix = drawMatrix
    }

    private fun zoomBy(factor: Float, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        if (baseScale <= 0f) return
        val target = (currentScale * factor).coerceIn(baseScale, baseScale * 4f)
        if (target == currentScale) return
        val step = target / currentScale
        drawMatrix.postScale(step, step, focusX, focusY)
        currentScale = target
        imageMatrix = drawMatrix
    }

    private fun panBy(dx: Float, dy: Float) {
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val step = 90f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> panBy(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> panBy(step, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> panBy(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> panBy(0f, step)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (currentScale > baseScale * 1.2f) {
                    fitToView()
                } else {
                    zoomBy(2f)
                }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
