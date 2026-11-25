package com.mytv.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mytv.launcher.App
import com.mytv.launcher.Prefs
import com.mytv.launcher.R
import com.mytv.launcher.databinding.ActivityLockScreenBinding
import com.mytv.launcher.databinding.ItemLockOptionBinding
import com.mytv.launcher.lock.KioskController
import com.mytv.launcher.lock.LockController
import com.mytv.launcher.lock.LockQuestion
import com.mytv.launcher.lock.LockState
import com.mytv.launcher.lock.LunarDate
import com.mytv.launcher.lock.QuestionBank
import com.mytv.launcher.util.dp
import com.mytv.launcher.util.focusScale
import com.mytv.launcher.util.immersive
import com.mytv.launcher.util.keepScreenOn
import com.mytv.launcher.util.toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 全屏智慧锁屏：一道题答对后解锁；支持选择题、数字键盘填空、文字填空、
 * 图片题、视频题，以及家长 PIN 绕过。
 */
class LockScreenActivity : Activity() {

    private lateinit var binding: ActivityLockScreenBinding
    private val handler = Handler(Looper.getMainLooper())

    private var current: LockQuestion? = null
    private var numericBuffer = ""
    private var unlocked = false
    private var kioskEntered = false
    private var switching = false
    private var pendingSwitch: Runnable? = null
    private var loadToken = 0

    private var player: ExoPlayer? = null

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        immersive()
        keepScreenOn()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

        LockState.locked = true
        buildNumberPad()
        bindActions()
        updateClock()
        loadQuestion()
        handler.post(clockTick)
    }

    override fun onResume() {
        super.onResume()
        if (!unlocked) {
            player?.play()
            if (!kioskEntered) kioskEntered = KioskController.startLock(this)
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        LockState.locked = true
    }

    @Deprecated("锁屏页不响应返回键")
    override fun onBackPressed() {
        // 锁屏必须答题或家长解锁才能离开
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        val question = current
        if (!unlocked && !switching && question != null && !question.isChoice && question.numeric) {
            when (keyCode) {
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> return appendNumber("0")
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> return appendNumber("1")
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> return appendNumber("2")
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> return appendNumber("3")
                KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> return appendNumber("4")
                KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> return appendNumber("5")
                KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> return appendNumber("6")
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> return appendNumber("7")
                KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> return appendNumber("8")
                KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> return appendNumber("9")
                KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT -> return appendNumber(".")
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> return appendNumber("-")
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                    numericBuffer = numericBuffer.dropLast(1)
                    updateNumericDisplay()
                    return true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    submitNumeric()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun bindActions() {
        binding.changeQuestion.setOnClickListener {
            if (!unlocked) loadQuestion(current?.id)
        }
        binding.parentUnlock.setOnClickListener {
            if (!unlocked) showPinDialog()
        }
        binding.submitFill.setOnClickListener {
            if (!unlocked) checkFill(binding.answerInput.text.toString())
        }
        binding.answerInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                if (!unlocked) checkFill(binding.answerInput.text.toString())
                true
            } else {
                false
            }
        }
        binding.changeQuestion.focusScale(1.02f, 4f)
        binding.parentUnlock.focusScale(1.02f, 4f)
        binding.submitFill.focusScale(1.02f, 4f)
    }

    private fun updateClock() {
        val now = Date()
        binding.lockClock.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        val calendar = Calendar.getInstance()
        val weekdays = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        val week = weekdays[(calendar.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]
        binding.lockDate.text = SimpleDateFormat("M月d日", Locale.CHINA).format(now) +
            "  " + week + "  ·  " + LunarDate.text(now.time)
        binding.lockLevel.text = "解锁难度 · ${Prefs.lockDifficulty}"
    }

    private fun loadQuestion(excludeId: String? = null) {
        if (unlocked) return
        pendingSwitch?.let { handler.removeCallbacks(it) }
        pendingSwitch = null
        switching = false
        setAnswerEnabled(true)
        val token = ++loadToken
        current = null
        numericBuffer = ""
        binding.lockStatus.text = ""
        binding.questionText.text = "正在准备题目…"
        binding.questionMeta.text = ""
        binding.optionsContainer.removeAllViews()
        binding.fillArea.visibility = View.GONE
        binding.questionImage.visibility = View.GONE
        binding.videoBox.visibility = View.GONE
        releasePlayer()

        Thread {
            val question = QuestionBank.randomFor(Prefs.lockDifficulty, excludeId)
            runOnUiThread {
                if (token == loadToken && !isFinishing && !unlocked) {
                    showQuestion(question)
                }
            }
        }.start()
    }

    private fun showQuestion(question: LockQuestion) {
        switching = false
        setAnswerEnabled(true)
        val displayQuestion = QuestionBank.randomizeChoices(question)
        current = displayQuestion
        binding.questionMeta.text = displayQuestion.subject + " · " +
            (if (displayQuestion.isChoice) "选择题" else "填空题") +
            " · " + Prefs.lockDifficulty
        binding.questionText.text = displayQuestion.question
        binding.questionText.textSize = if (displayQuestion.isChoice) {
            when {
                displayQuestion.question.length > 220 -> 20f
                displayQuestion.question.length > 140 -> 23f
                else -> 27f
            }
        } else {
            when {
                displayQuestion.question.length > 220 -> 18f
                displayQuestion.question.length > 140 -> 19f
                else -> 20f
            }
        }
        binding.lockStatus.text = ""
        setupMedia(displayQuestion)

        if (displayQuestion.isChoice) {
            binding.optionsContainer.visibility = View.VISIBLE
            binding.fillArea.visibility = View.GONE
            buildOptions(displayQuestion)
        } else {
            binding.optionsContainer.visibility = View.GONE
            setupFill(displayQuestion)
        }

        binding.questionScroll.post {
            binding.questionScroll.scrollTo(0, 0)
        }
    }

    private fun setupMedia(question: LockQuestion) {
        binding.questionImage.visibility = View.GONE
        binding.videoBox.visibility = View.GONE
        binding.questionImage.setImageDrawable(null)

        question.image?.let { path ->
            try {
                val bitmap = assets.open(path).use { BitmapFactory.decodeStream(it) }
                if (bitmap != null) {
                    binding.questionImage.setImageBitmap(bitmap)
                    binding.questionImage.visibility = View.VISIBLE
                }
            } catch (t: Throwable) {
                App.logWarn("锁屏图片题加载失败: $path", t)
            }
        }
        question.video?.let { setupVideo(it) }
    }

    private fun setupVideo(path: String) {
        if (!path.startsWith("raw:")) return
        val name = path.removePrefix("raw:")
        val resId = when (name) {
            "lock_count" -> R.raw.lock_count
            else -> 0
        }
        if (resId == 0) return
        try {
            val file = File(cacheDir, "lock_$name.mp4")
            if (!file.exists() || file.length() <= 0L) {
                resources.openRawResource(resId).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val exo = ExoPlayer.Builder(this).build()
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exo.repeatMode = Player.REPEAT_MODE_ONE
            exo.volume = 0f
            exo.playWhenReady = true
            exo.prepare()
            binding.videoPlayer.player = exo
            binding.videoBox.visibility = View.VISIBLE
            player = exo
        } catch (t: Throwable) {
            App.logWarn("锁屏视频题加载失败", t)
            binding.videoBox.visibility = View.GONE
        }
    }

    private fun releasePlayer() {
        player?.let {
            it.stop()
            it.release()
        }
        player = null
        binding.videoPlayer.player = null
    }

    private fun buildOptions(question: LockQuestion) {
        binding.optionsContainer.removeAllViews()
        val letters = "ABCD"
        question.options.forEachIndexed { index, text ->
            val view = layoutInflater.inflate(R.layout.item_lock_option, binding.optionsContainer, false)
            val option = ItemLockOptionBinding.bind(view)
            option.optionLetter.text = letters.getOrElse(index) { '?' }.toString()
            option.optionText.text = text
            view.focusScale(1.02f, 4f)
            view.setOnClickListener {
                if (!unlocked) checkChoice(index)
            }
            binding.optionsContainer.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        binding.optionsContainer.post {
            binding.optionsContainer.getChildAt(0)?.requestFocus()
        }
    }

    private fun setupFill(question: LockQuestion) {
        binding.fillArea.visibility = View.VISIBLE
        numericBuffer = ""
        if (question.numeric) {
            binding.numericDisplay.visibility = View.VISIBLE
            binding.answerInput.visibility = View.GONE
            binding.numberPad.visibility = View.VISIBLE
            binding.submitFill.visibility = View.GONE
            updateNumericDisplay()
            binding.numberPad.post {
                binding.numberPad.getChildAt(0)?.requestFocus()
            }
        } else {
            binding.numericDisplay.visibility = View.GONE
            binding.answerInput.visibility = View.VISIBLE
            binding.answerInput.setText("")
            binding.numberPad.visibility = View.GONE
            binding.submitFill.visibility = View.VISIBLE
            binding.answerInput.requestFocus()
        }
    }

    private fun buildNumberPad() {
        binding.numberPad.removeAllViews()
        val keys = listOf(
            "1", "2", "3", "4", "5",
            "6", "7", "8", "9", "0",
            "删除", ".", "-", "提交"
        )
        var row = 0
        var col = 0
        keys.forEach { label ->
            val span = if (label == "提交") 2 else 1
            val view = TextView(this).apply {
                text = label
                textSize = if (label == "删除" || label == "提交") 16f else 21f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = getDrawable(R.drawable.bg_lock_action)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                focusScale(1.03f, 3f)
                setOnClickListener {
                    if (unlocked) return@setOnClickListener
                    when (label) {
                        "删除" -> {
                            numericBuffer = numericBuffer.dropLast(1)
                            updateNumericDisplay()
                        }
                        "提交" -> submitNumeric()
                        else -> appendNumber(label)
                    }
                }
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(row),
                GridLayout.spec(col, span, span.toFloat())
            ).apply {
                width = 0
                height = dp(42f)
                setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
            }
            binding.numberPad.addView(view, params)
            col += span
            if (col >= 5) {
                col = 0
                row++
            }
        }
    }

    private fun appendNumber(value: String): Boolean {
        if (unlocked || switching) return true
        if (numericBuffer.length >= 12) return true
        if (value == "." && numericBuffer.contains(".")) return true
        if (value == "-" && numericBuffer.isNotEmpty()) return true
        numericBuffer += value
        updateNumericDisplay()
        return true
    }

    private fun updateNumericDisplay() {
        if (numericBuffer.isEmpty()) {
            binding.numericDisplay.text = "请输入答案"
            binding.numericDisplay.setTextColor(getColor(R.color.text_dim))
        } else {
            binding.numericDisplay.text = numericBuffer
            binding.numericDisplay.setTextColor(getColor(R.color.text))
        }
    }

    private fun submitNumeric() {
        if (unlocked || switching) return
        if (numericBuffer.isBlank()) {
            wrong("请先填写答案", autoAdvance = false)
            return
        }
        checkFill(numericBuffer)
    }

    private fun checkChoice(index: Int) {
        if (unlocked || switching) return
        val question = current ?: return
        val selected = "ABCD".getOrElse(index) { '?' }.toString()
        if (selected.equals(question.answer, ignoreCase = true)) {
            unlockSuccess()
        } else {
            wrong("这个选项不对")
        }
    }

    private fun checkFill(value: String) {
        if (unlocked || switching) return
        val question = current ?: return
        val input = value.trim()
        if (input.isEmpty()) {
            wrong("请先填写答案", autoAdvance = false)
            return
        }
        val ok = if (question.numeric) {
            val user = parseNumber(input)
            val answer = parseNumber(question.answer)
            user != null && answer != null && abs(user - answer) <= 1e-6
        } else {
            normalize(input) == normalize(question.answer)
        }
        if (ok) unlockSuccess() else wrong("答案不对")
    }

    private fun parseNumber(value: String): Double? {
        val normalized = value
            .replace('，', '.')
            .replace('。', '.')
            .replace("\u3000", "")
            .filterNot { it.isWhitespace() }
            .map { ch ->
                if (ch in '０'..'９') ('0' + (ch - '０')) else ch
            }
            .joinToString("")
        return normalized.toDoubleOrNull()
    }

    private fun normalize(value: String): String {
        return value.trim().lowercase(Locale.ROOT)
            .replace(Regex("[\\s，。！？、；：,.!?;:\"'“”‘’]"), "")
    }

    private fun wrong(message: String, autoAdvance: Boolean = true) {
        if (unlocked || switching) return
        binding.lockStatus.setTextColor(0xFFFF8A80.toInt())
        binding.lockStatus.text = if (autoAdvance) "$message，换一题…" else message
        binding.lockStatus.animate().alpha(0.45f).alpha(1f).setDuration(260).start()
        if (!autoAdvance) return

        // 答错后短暂展示提示，再自动换一道题；同一道题本次锁定内不再重复出现。
        switching = true
        setAnswerEnabled(false)
        val wrongQuestionId = current?.id
        val runnable = Runnable {
            pendingSwitch = null
            loadQuestion(wrongQuestionId)
        }
        pendingSwitch = runnable
        handler.postDelayed(runnable, 900L)
    }

    private fun setAnswerEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.65f
        binding.optionsContainer.alpha = alpha
        binding.numberPad.alpha = alpha
        binding.fillArea.alpha = alpha
        binding.optionsContainer.isEnabled = enabled
        binding.numberPad.isEnabled = enabled
        binding.answerInput.isEnabled = enabled
        binding.submitFill.isEnabled = enabled
        for (i in 0 until binding.optionsContainer.childCount) {
            binding.optionsContainer.getChildAt(i).isEnabled = enabled
        }
        for (i in 0 until binding.numberPad.childCount) {
            binding.numberPad.getChildAt(i).isEnabled = enabled
        }
    }

    private fun unlockSuccess() {
        if (unlocked) return
        unlocked = true
        LockController.onUnlocked()
        KioskController.stopLock(this)
        kioskEntered = false
        player?.pause()
        binding.lockStatus.setTextColor(0xFF7CFFB2.toInt())
        binding.lockStatus.text = "回答正确，正在解锁…"
        handler.postDelayed({
            if (!isFinishing) {
                // 开机直接进锁屏时，背后没有桌面 Activity；解锁后主动进入 MyTV 桌面
                startActivity(
                    android.content.Intent(this, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                finish()
                overridePendingTransition(0, 0)
            }
        }, 650L)
    }

    private fun showPinDialog() {
        val input = EditText(this).apply {
            setText("")
            hint = "4 位数字"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(28f), dp(8f), dp(28f), 0)
        }
        container.addView(
            input,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle("家长解锁")
            .setMessage("输入 4 位家长密码，可直接解锁电视")
            .setView(container)
            .setPositiveButton("解锁", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            input.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString().trim() == Prefs.lockPin) {
                    dialog.dismiss()
                    unlockSuccess()
                } else {
                    input.error = "密码不对"
                    input.setText("")
                }
            }
        }
        dialog.show()
    }
}
