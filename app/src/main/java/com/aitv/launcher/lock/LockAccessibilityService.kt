package com.aitv.launcher.lock

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.aitv.launcher.App
import com.aitv.launcher.Prefs
import com.aitv.launcher.ui.LockScreenActivity
import com.aitv.launcher.ui.MainActivity

/**
 * 全系统锁屏守护。
 *
 * 只在智慧锁屏处于锁定状态时工作：
 * - 拦截 Home / 最近任务，避免回到 TCL 桌面；
 * - 检测到前台切换到其他应用时，把答题页重新盖到最上面。
 *
 * 不读取屏幕内容，也不执行点击/手势。
 */
class LockAccessibilityService : AccessibilityService() {

    private var lastShowAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (isSystemOverlayPackage(pkg)) return
        when {
            // 锁定状态：重新盖上答题页。
            LockState.locked -> showLock()
            // 开机观察窗口内（未锁定）：被系统桌面 / 其他应用抢占时拉回 MyTV 桌面。
            // 窗口外不干预，用户正常切换应用不受影响。
            BootWatchdog.inWindow() -> showBootTarget()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!LockState.locked) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_NOTIFICATION,
            KeyEvent.KEYCODE_MENU -> true
            else -> false
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        App.log("全系统锁屏守护已连接")
        // 进程被回收后系统重新绑定服务时，若仍处于锁定状态，立即恢复答题页。
        if (LockState.locked) {
            showLock()
        } else if (BootWatchdog.inWindow()) {
            // 电视开机后不会给第三方应用发 BOOT_COMPLETED，靠无障碍服务自判开机窗口补拉。
            handler.postDelayed({
                if (!LockState.locked && BootWatchdog.inWindow()) showBootTarget()
            }, 1_500L)
        }
    }

    private fun showBootTarget() {
        val lockOnBoot = runCatching { Prefs.lockEnabled && Prefs.lockOnBoot }.getOrDefault(false)
        if (lockOnBoot) showLock() else showHome()
    }

    private fun isSystemOverlayPackage(pkg: String): Boolean {
        if (pkg == "com.android.systemui" || pkg == "android") return true
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    private fun showLock() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShowAt < 700L) return
        lastShowAt = now
        try {
            val intent = Intent(this, LockScreenActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            startActivity(intent)
        } catch (t: Throwable) {
            App.logWarn("全系统守护重新拉起锁屏失败", t)
        }
    }

    private fun showHome() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShowAt < 1_500L) return
        lastShowAt = now
        try {
            val intent = Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            startActivity(intent)
        } catch (t: Throwable) {
            App.logWarn("全系统守护拉回桌面失败", t)
        }
    }

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, LockAccessibilityService::class.java)

        /** 是否已在系统无障碍设置中启用本服务。 */
        fun isEnabled(context: Context): Boolean {
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = component(context)
            return raw.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
