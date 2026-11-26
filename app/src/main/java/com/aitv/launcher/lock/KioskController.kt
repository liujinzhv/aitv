package com.aitv.launcher.lock

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.aitv.launcher.App
import com.aitv.launcher.admin.MytvDeviceAdminReceiver
import com.aitv.launcher.ui.MainActivity

/**
 * 设备所有者模式下的全屏锁任务。
 *
 * 只有通过 ADB 一次性执行 `dpm set-device-owner` 后才会生效；未授权时所有方法
 * 都安全退化为普通应用内锁屏，不影响现有功能。
 */
object KioskController {

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(context: Context): ComponentName =
        ComponentName(context, MytvDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    fun isKioskReady(context: Context): Boolean =
        isDeviceOwner(context) && dpm(context).isAdminActive(admin(context))

    /**
     * 每次进程启动时把 MyTV 设为持久 HOME，并打开锁任务白名单。
     * 这些策略由设备所有者写入，卸载/清除设备所有者后自动失效。
     */
    fun prepare(context: Context) {
        if (!isKioskReady(context)) return
        val policy = dpm(context)
        val admin = admin(context)
        try {
            policy.setLockTaskPackages(admin, arrayOf(context.packageName))
        } catch (t: Throwable) {
            App.logWarn("设置锁任务白名单失败", t)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                policy.setLockTaskFeatures(admin, 0)
            } catch (t: Throwable) {
                App.logWarn("设置锁任务特性失败", t)
            }
        }
        try {
            policy.setStatusBarDisabled(admin, true)
        } catch (t: Throwable) {
            App.logWarn("禁用状态栏失败", t)
        }
        try {
            policy.setKeyguardDisabled(admin, true)
        } catch (t: Throwable) {
            App.logWarn("禁用锁屏密码页失败", t)
        }
        try {
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            policy.addPersistentPreferredActivity(
                admin,
                home,
                ComponentName(context, MainActivity::class.java)
            )
        } catch (t: Throwable) {
            App.logWarn("设置默认桌面失败", t)
        }
    }

    /** 进入锁任务：Home、最近任务、通知栏和其他应用都会被系统挡住。 */
    fun startLock(activity: Activity): Boolean {
        if (!isKioskReady(activity)) return false
        return try {
            activity.startLockTask()
            true
        } catch (t: Throwable) {
            App.logWarn("进入全屏锁任务失败", t)
            false
        }
    }

    /** 答对/家长密码后退出锁任务，回到 MyTV 桌面。 */
    fun stopLock(activity: Activity) {
        if (!isKioskReady(activity)) return
        try {
            activity.stopLockTask()
        } catch (t: Throwable) {
            App.logWarn("退出全屏锁任务失败", t)
        }
    }
}
