package com.mytv.launcher

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mytv.launcher.lock.KioskController
import com.mytv.launcher.lock.LockController
import com.mytv.launcher.lock.LockState
import com.mytv.launcher.ui.LockScreenActivity
import com.mytv.launcher.util.SelectionFx

class App : Application() {

    private var resumedActivity: Activity? = null
    private val handler = Handler(Looper.getMainLooper())

    private val lockTick = object : Runnable {
        override fun run() {
            val activity = resumedActivity
            if (activity != null && activity !is LockScreenActivity && !LockState.locked) {
                LockController.checkAndLock(activity)
            }
            handler.postDelayed(this, LOCK_CHECK_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        KioskController.prepare(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                resumedActivity = activity
                // 每个页面统一补上「选中 / 焦点」效果（放大 + 描边）
                activity.window?.decorView?.post {
                    runCatching { SelectionFx.decorate(activity.window.decorView) }
                }
                if (activity is LockScreenActivity) {
                    LockState.locked = true
                } else if (LockState.locked) {
                    // 用户从答题页按 Home 回到桌面/设置页时，重新盖住。
                    LockController.show(activity)
                } else {
                    LockController.touch()
                }
                handler.removeCallbacks(lockTick)
                handler.postDelayed(lockTick, LOCK_CHECK_INTERVAL)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity === activity) resumedActivity = null
                handler.removeCallbacks(lockTick)
            }

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (resumedActivity === activity) resumedActivity = null
            }
        })
    }

    companion object {
        private const val LOCK_CHECK_INTERVAL = 15_000L
        private lateinit var instance: App

        val ctx: Context
            get() = instance.applicationContext

        fun log(msg: String) = Log.d("MyTV", msg)

        fun logWarn(msg: String, t: Throwable? = null) = Log.w("MyTV", msg, t)
    }
}
