package com.aitv.launcher.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 接收下载完成广播，交给 UpdateDownloader 自动安装；安装包替换后清理旧提示 */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                UpdateDownloader.onComplete(context.applicationContext, id)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> UpdateDownloader.clearAfterUpdate(context)
        }
    }
}
