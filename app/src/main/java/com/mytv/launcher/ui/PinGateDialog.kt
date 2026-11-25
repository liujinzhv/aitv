package com.mytv.launcher.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mytv.launcher.Prefs
import com.mytv.launcher.R
import com.mytv.launcher.util.dp
import com.mytv.launcher.util.focusScale

/**
 * 家长密码验证框：用于进入 MyTV 设置前校验 Prefs.lockPin。
 * 遥控器可用：数字键盘可聚焦，四位输入后自动校验。
 */
class PinGateDialog(
    private val host: Activity,
    private val onSuccess: () -> Unit,
    private val onCancel: () -> Unit
) : Dialog(host, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {

    private var code = ""
    private val dots = mutableListOf<TextView>()
    private lateinit var errorView: TextView
    private var checking = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = host
        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(28f), ctx.dp(24f), ctx.dp(28f), ctx.dp(24f))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_lock_card)
        }
        val cardParams = FrameLayout.LayoutParams(
            ctx.dp(560f),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        root.addView(card, cardParams)

        card.addView(TextView(ctx).apply {
            text = "家长验证"
            textSize = 26f
            setTextColor(ContextCompat.getColor(ctx, R.color.text))
            gravity = Gravity.CENTER
        })
        card.addView(TextView(ctx).apply {
            text = "请输入 4 位家长密码，验证后才能修改设置"
            textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(8f), 0, 0)
        })

        val dotRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(22f), 0, ctx.dp(6f))
        }
        repeat(4) {
            val dot = TextView(ctx).apply {
                gravity = Gravity.CENTER
                textSize = 24f
                setTextColor(ContextCompat.getColor(ctx, R.color.text))
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_lock_input)
                isFocusable = false
            }
            val lp = LinearLayout.LayoutParams(ctx.dp(56f), ctx.dp(60f))
            lp.setMargins(ctx.dp(8f), 0, ctx.dp(8f), 0)
            dotRow.addView(dot, lp)
            dots.add(dot)
        }
        card.addView(dotRow)

        errorView = TextView(ctx).apply {
            text = ""
            textSize = 14f
            setTextColor(0xFFFF8A80.toInt())
            gravity = Gravity.CENTER
            minHeight = ctx.dp(22f)
        }
        card.addView(errorView)

        val pad = GridLayout(ctx).apply {
            columnCount = 3
            rowCount = 4
            setPadding(0, ctx.dp(10f), 0, 0)
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "清空", "0", "删除")
        var firstKey: TextView? = null
        keys.forEachIndexed { index, label ->
            val key = TextView(ctx).apply {
                text = label
                textSize = if (label.length > 1) 17f else 23f
                setTextColor(ContextCompat.getColor(ctx, if (label == "清空" || label == "删除") R.color.text_dim else R.color.text))
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_lock_action)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                focusScale(1.04f, 4f)
                setOnClickListener { onKey(label) }
            }
            if (firstKey == null) firstKey = key
            val row = index / 3
            val col = index % 3
            val params = GridLayout.LayoutParams(
                GridLayout.spec(row, 1, 1f),
                GridLayout.spec(col, 1, 1f)
            ).apply {
                width = 0
                height = ctx.dp(56f)
                setMargins(ctx.dp(5f), ctx.dp(5f), ctx.dp(5f), ctx.dp(5f))
            }
            pad.addView(key, params)
        }
        card.addView(
            pad,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val cancel = TextView(ctx).apply {
            text = "取消，返回桌面"
            textSize = 18f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_lock_action)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            focusScale(1.03f, 4f)
            setOnClickListener {
                dismiss()
                onCancel()
            }
        }
        card.addView(cancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(52f)
        ).apply { topMargin = ctx.dp(14f) })

        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.78f)
        }
        setCanceledOnTouchOutside(false)
        setOnCancelListener {
            onCancel()
        }
        firstKey?.post { firstKey?.requestFocus() }
    }

    private fun onKey(label: String) {
        if (checking) return
        when (label) {
            "清空" -> {
                code = ""
                render()
            }
            "删除" -> {
                code = code.dropLast(1)
                render()
            }
            else -> {
                if (code.length >= 4) return
                code += label
                render()
                if (code.length == 4) verify()
            }
        }
    }

    private fun render() {
        errorView.text = ""
        dots.forEachIndexed { index, dot ->
            dot.text = if (index < code.length) code[index].toString() else ""
        }
    }

    private fun verify() {
        checking = true
        if (code == Prefs.lockPin) {
            dismiss()
            onSuccess()
            return
        }
        errorView.text = "密码不对，请重试"
        code = ""
        checking = false
        render()
        errorView.text = "密码不对，请重试"
    }
}
