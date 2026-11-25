package com.mytv.launcher.lock

import android.os.SystemClock

/**
 * 开机观察窗口标记。
 *
 * 由 BootReceiver 在收到开机广播后打标；无障碍守护（LockAccessibilityService）
 * 在窗口内且未处于锁定状态时，检测到前台离开 MyTV 会把桌面拉回来。
 *
 * 纯内存标记：进程被回收后窗口失效，此时由 BootReceiver 的闹钟兜底负责拉起；
 * 无障碍服务未在系统设置中启用时，本标记不产生任何行为。
 */
object BootWatchdog {

    @Volatile
    private var bootUntil = 0L

    /** 标记当前处于开机后观察窗口内。 */
    fun markBootWindow() {
        bootUntil = SystemClock.elapsedRealtime() + WINDOW_MS
    }

    /** 是否仍在开机观察窗口内。 */
    fun inWindow(): Boolean =
        SystemClock.elapsedRealtime() < BOOT_SELF_WINDOW_MS || SystemClock.elapsedRealtime() < bootUntil

    /**
     * 系统开机后 [BOOT_SELF_WINDOW_MS] 内也算观察窗口。
     *
     * 部分电视（TCL 等）不会把 BOOT_COMPLETED 派发给第三方应用，只靠
     * BootReceiver 打标会完全失效；无障碍服务本身会在开机时启动，用开机时长
     * 自判可以兜住这种 ROM。
     */
    private const val BOOT_SELF_WINDOW_MS = 120_000L


    private const val WINDOW_MS = 60_000L
}
