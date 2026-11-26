package com.aitv.launcher.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.aitv.launcher.App
import com.aitv.launcher.Prefs
import com.aitv.launcher.ui.LockScreenActivity
import com.aitv.launcher.ui.MainActivity

/**
 * 开机后拉起 MyTV：开启智慧锁屏时进入答题页，否则直接进入桌面。
 *
 * 兜底结构（按可靠性从近到远）：
 * 1. 进程内延迟重试队列：开机初期 OEM 桌面抢占前台，在 0 / 1.5 / 4 / 9 / 18 秒
 *    多次把目标页面拉到最前，覆盖系统桌面短暂占先的场景；
 * 2. AlarmManager 兜底广播：主进程在重试间隔内被系统回收时，由闹钟重新唤醒
 *    接收器再拉起一次，最多重复 [MAX_ALARM_ATTEMPTS] 次，避免死循环；
 * 3. 无障碍守护（LockAccessibilityService）在已启用时，开机后一段时间内检测到
 *    前台离开 MyTV 会再次拉回；未启用则不阻塞，不影响主流程；
 * 4. 设备所有者（KioskController）就绪时已在 App 启动时写入持久 HOME 偏好。
 *
 * 注意：本工程 targetSdk=28，Android 10 的后台启动 Activity 限制（按 targetSdk>=29
 * 生效）不适用于本包；但部分 OEM ROM 仍可能在开机初期延后 Activity 启动，因此保留
 * 重试队列。BOOT_COMPLETED 在直接加密存储解锁后才会下发，锁屏状态读取走
 * device-protected 存储，Prefs 读取做了异常兜底。
 */
class BootReceiver : BroadcastReceiver() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext

        if (action == ACTION_RETRY) {
            handleRetry(app, intent)
            return
        }
        if (action !in ACTIONS) return

        val (locked, target) = decideTarget(app)
        App.log("开机广播 $action → ${target.simpleName} (locked=$locked)")

        LockState.locked = locked

        // 1. 进程内延迟重试：固定间隔多次拉起，singleTop 不会重复创建已有实例。
        RETRY_DELAYS.forEachIndexed { index, delay ->
            handler.postDelayed({ launch(app, target) }, delay)
        }

        // 2. 进程被回收后的闹钟兜底。
        scheduleAlarm(app, target, attempt = 1, afterMs = ALARM_FIRST_DELAY_MS)

        // 无障碍守护的开机观察窗口（未启用无障碍时该标记不产生任何作用）。
        BootWatchdog.markBootWindow()
    }

    private fun handleRetry(app: Context, intent: Intent) {
        val target = if (intent.getStringExtra(EXTRA_TARGET) == TARGET_LOCK) {
            LockScreenActivity::class.java
        } else {
            MainActivity::class.java
        }
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 1)
        App.log("开机兜底闹钟第 $attempt 次拉起 ${target.simpleName}")
        launch(app, target)
        scheduleAlarm(app, target, attempt = attempt + 1, afterMs = ALARM_NEXT_DELAY_MS)
    }

    private fun decideTarget(context: Context): Pair<Boolean, Class<*>> {
        // Prefs 落在 CE 存储；极端直接开机场景下读取可能失败，失败时按不锁屏处理。
        val locked = runCatching { Prefs.lockEnabled && Prefs.lockOnBoot }
            .getOrDefault(false)
        return if (locked) {
            true to LockScreenActivity::class.java
        } else {
            false to MainActivity::class.java
        }
    }

    private fun launch(context: Context, cls: Class<*>) {
        try {
            val intent = Intent(context, cls).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            context.startActivity(intent)
            App.log("拉起 ${cls.simpleName}")
        } catch (t: Throwable) {
            App.logWarn("拉起 ${cls.simpleName} 失败", t)
        }
    }

    private fun scheduleAlarm(context: Context, target: Class<*>, attempt: Int, afterMs: Long) {
        if (attempt > MAX_ALARM_ATTEMPTS) return
        val alarmIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_RETRY
            setPackage(context.packageName)
            putExtra(EXTRA_TARGET, if (target == LockScreenActivity::class.java) TARGET_LOCK else TARGET_MAIN)
            putExtra(EXTRA_ATTEMPT, attempt)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            attempt,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + afterMs
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }.recoverCatching {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }.onFailure { App.logWarn("调度开机兜底闹钟失败", it) }
    }

    companion object {
        private const val ACTION_RETRY = "com.aitv.launcher.action.BOOT_RETRY"
        private const val EXTRA_TARGET = "boot_target"
        private const val EXTRA_ATTEMPT = "boot_attempt"
        private const val TARGET_LOCK = "lock"
        private const val TARGET_MAIN = "main"

        /** 进程内重试间隔（毫秒）。 */
        private val RETRY_DELAYS = longArrayOf(0L, 1_500L, 4_000L, 9_000L, 18_000L)

        private const val ALARM_FIRST_DELAY_MS = 25_000L
        private const val ALARM_NEXT_DELAY_MS = 25_000L
        private const val MAX_ALARM_ATTEMPTS = 2

        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
            // 注：android.intent.action.REBOOT 是受保护系统广播，第三方应用无法
            // 注册接收，且 manifest 未声明该 action，这里不再列入匹配集合。
        )
    }
}
