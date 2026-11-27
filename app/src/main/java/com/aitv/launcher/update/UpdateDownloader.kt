package com.aitv.launcher.update

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.aitv.launcher.BuildConfig
import com.aitv.launcher.Prefs
import com.aitv.launcher.R
import com.aitv.launcher.ui.MainActivity
import com.aitv.launcher.util.toast
import java.io.File

/** 后台下载 APK，完成广播到达后直接拉起系统安装器 */
object UpdateDownloader {

    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val CHANNEL_ID = "app_update"
    private const val NOTIFICATION_ID = 4701

    /** “去下载”入口：DownloadManager 负责后台下载和通知栏进度 */
    fun start(activity: Activity, release: GiteeUpdate.Release) {
        val url = release.apkUrl
        if (url.isNullOrBlank()) {
            activity.toast("本次发布没有 APK 附件，请到发布页手动下载")
            activity.startActivity(
                Intent(activity, com.aitv.launcher.ui.BrowserActivity::class.java)
                    .putExtra(com.aitv.launcher.ui.BrowserActivity.EXTRA_URL, release.pageUrl)
            )
            return
        }
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = fileNameFor(release.tag)
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        runCatching { if (!dir.exists()) dir.mkdirs() }
        val target = File(dir, fileName)
        // 同名旧包先删掉，否则 DownloadManager 会因目标已存在而失败
        runCatching { if (target.exists()) target.delete() }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("智慧家庭TV ${release.tag}")
            .setDescription("正在后台下载新版本，完成后自动安装")
            .setMimeType(MIME_APK)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 8.0.0; SmartTV)")
        val id = runCatching { manager.enqueue(request) }.getOrElse {
            activity.toast("下载任务创建失败：${it.message ?: "存储不可用"}")
            return
        }

        Prefs.pendingDownloadId = id
        Prefs.pendingApkPath = target.absolutePath
        Prefs.lastApkPath = target.absolutePath
        activity.toast("已开始后台下载，完成后自动打开安装")
    }

    /** 下载完成广播：校验任务 id，成功则自动安装 */
    fun onComplete(context: Context, downloadId: Long) {
        if (downloadId <= 0L || downloadId != Prefs.pendingDownloadId) return
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var status = -1
        var localPath = ""
        runCatching {
            manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    localPath = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                    ).orEmpty()
                }
            }
        }

        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                val path = Uri.parse(localPath).path?.takeIf { it.isNotBlank() }
                    ?: Prefs.pendingApkPath
                val file = File(path)
                Prefs.pendingDownloadId = -1L
                if (!file.exists() || !file.canRead()) {
                    Prefs.pendingApkPath = ""
                    return
                }
                Prefs.pendingApkPath = file.absolutePath
                Prefs.lastApkPath = file.absolutePath
                Prefs.manualInstallNotice = true
                notifyDownloaded(context, file)
                if (install(context, file)) {
                    Prefs.pendingApkPath = ""
                }
            }
            DownloadManager.STATUS_FAILED -> {
                Prefs.pendingDownloadId = -1L
                Prefs.pendingApkPath = ""
                Toast.makeText(context, "新版本下载失败，请重试", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 从系统设置返回、或应用重新进前台时补一次安装 */
    fun installIfReady(activity: Activity) {
        val path = Prefs.pendingApkPath
        if (path.isBlank()) return
        val file = File(path)
        if (!file.exists() || !file.canRead()) {
            Prefs.pendingApkPath = ""
            return
        }
        // 不额外判断 canRequestPackageInstalls：交给系统安装器自己处理
        // “安装未知应用”授权，避免部分电视没有标准的授权设置页导致无法继续
        if (install(activity, file)) {
            Prefs.pendingApkPath = ""
        }
    }

    /**
     * 从安装器/下载通知返回后调用：版本号没变化说明系统没完成安装，
     * 此时明确告知“系统限制，只能手动安装”，并给出下载目录路径。
     */
    fun checkInstallResult(activity: Activity): Boolean {
        val attempted = Prefs.installAttemptVersion
        if (attempted.isBlank()) {
            if (!Prefs.manualInstallNotice) return false
            showManualDialog(activity)
            return true
        }
        if (BuildConfig.VERSION_NAME != attempted) {
            // 安装成功，应用已被新版本替换
            Prefs.installAttemptVersion = ""
            Prefs.manualInstallNotice = false
            Prefs.lastApkPath = ""
            cancelNotification(activity)
            return false
        }
        if (!Prefs.manualInstallNotice) return false
        showManualDialog(activity)
        return true
    }

    /** 新包已替换旧包：清理下载/安装临时状态，并撤掉“新版本已下载”通知 */
    fun clearAfterUpdate(context: Context) {
        Prefs.pendingDownloadId = -1L
        Prefs.pendingApkPath = ""
        Prefs.lastApkPath = ""
        Prefs.installAttemptVersion = ""
        Prefs.manualInstallNotice = false
        cancelNotification(context)
    }

    private fun showManualDialog(activity: Activity) {
        Prefs.manualInstallNotice = false
        Prefs.installAttemptVersion = ""
        cancelNotification(activity)
        val path = Prefs.lastApkPath
        val name = path.takeIf { it.isNotBlank() }?.let { File(it).name }
            ?: "smarttv-*.apk"
        val text = buildString {
            append("新版本已下载到电视的“下载 / Download”目录：\n")
            append(name)
            append("\n\n当前电视系统限制了应用内自动安装，无法自动完成。\n")
            append("请打开电视的文件管理器或媒体中心，进入“下载”目录，点击这个 APK 手动安装。")
            if (path.isNotBlank()) append("\n\n完整路径：\n").append(path)
        }
        AlertDialog.Builder(activity, R.style.LightDialogTheme)
            .setTitle("需要手动安装新版本")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun notifyDownloaded(context: Context, file: File) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "已保存到“下载”目录：${file.name}。若未自动弹出安装界面，请手动打开安装。"
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("新版本已下载")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelNotification(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
    }

    private fun install(context: Context, file: File): Boolean = runCatching {
        Prefs.installAttemptVersion = BuildConfig.VERSION_NAME
        Prefs.lastApkPath = file.absolutePath
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrElse {
        Toast.makeText(
            context,
            "拉起安装器失败：${it.message ?: "系统限制"}",
            Toast.LENGTH_LONG
        ).show()
        false
    }

    private fun fileNameFor(tag: String): String {
        val safe = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "smarttv-$safe-tvapp.apk"
    }
}

