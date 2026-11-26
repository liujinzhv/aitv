package com.aitv.launcher.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.aitv.launcher.App
import com.aitv.launcher.lock.KioskController

class MytvDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        KioskController.prepare(context)
        App.log("设备管理员已启用")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        App.log("设备管理员已停用")
    }
}
