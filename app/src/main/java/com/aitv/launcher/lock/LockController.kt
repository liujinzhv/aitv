package com.aitv.launcher.lock

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import com.aitv.launcher.Prefs
import com.aitv.launcher.ui.LockScreenActivity

/**
 * 全局无操作计时器。只统计 MyTV 处于前台时的操作；到点后由 App 的
 * ActivityLifecycleCallbacks 拉起全屏答题页。
 */
object LockController {

    private var lastInteractionAt = SystemClock.elapsedRealtime()

    fun touch() {
        lastInteractionAt = SystemClock.elapsedRealtime()
    }

    fun shouldLock(): Boolean {
        if (!Prefs.lockEnabled || Prefs.lockTimeoutMinutes <= 0) return false
        val idle = SystemClock.elapsedRealtime() - lastInteractionAt
        return idle >= Prefs.lockTimeoutMinutes * 60_000L
    }

    /** 强制展示锁屏；即使已经处于锁定状态也可以重新拉起。 */
    fun show(activity: Activity) {
        if (activity is LockScreenActivity) return
        LockState.locked = true
        val intent = Intent(activity, LockScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activity.startActivity(intent)
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    fun checkAndLock(activity: Activity) {
        if (LockState.locked) return
        if (shouldLock()) show(activity)
    }

    fun onUnlocked() {
        LockState.locked = false
        touch()
    }
}
