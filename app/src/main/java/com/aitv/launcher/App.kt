package com.aitv.launcher

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aitv.launcher.lock.KioskController
import com.aitv.launcher.lock.LockController
import com.aitv.launcher.lock.LockState
import com.aitv.launcher.media.ImageLoader
import com.aitv.launcher.ui.LockScreenActivity
import com.aitv.launcher.util.SelectionFx

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
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

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

    /** 任意自家页面是否在前台：开机兜底闹钟用它判断「用户已经在用了，别再抢前台」 */
    fun hasVisibleUi(): Boolean = resumedActivity != null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ImageLoader.trimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            App.log("trim memory level=$level")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ImageLoader.clear()
    }

    companion object {
        private const val LOCK_CHECK_INTERVAL = 15_000L
        private lateinit var instance: App

        val ctx: Context
            get() = instance.applicationContext

        fun log(msg: String) = Log.d("MyTV", msg)

        fun logWarn(msg: String, t: Throwable? = null) = Log.w("MyTV", msg, t)

        fun isUiVisible(): Boolean = runCatching { instance.hasVisibleUi() }.getOrDefault(false)
    }
}
