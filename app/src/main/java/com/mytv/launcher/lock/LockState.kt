package com.mytv.launcher.lock

import android.content.Context
import com.mytv.launcher.App

/**
 * 锁屏状态：答题成功后才允许回到桌面。
 *
 * 状态存放在设备加密存储（device-protected storage），进程被回收、或
 * 开机后无障碍服务在用户解锁前启动时都能读取，避免锁定状态丢失。
 */
object LockState {

    private const val NAME = "mytv_lock_state"
    private const val KEY = "locked"

    @Volatile
    private var cached = false

    @Volatile
    private var loaded = false

    private val sp
        get() = App.ctx
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var locked: Boolean
        get() {
            if (!loaded) {
                cached = runCatching { sp.getBoolean(KEY, false) }.getOrDefault(false)
                loaded = true
            }
            return cached
        }
        set(value) {
            cached = value
            loaded = true
            runCatching { sp.edit().putBoolean(KEY, value).apply() }
        }
}
