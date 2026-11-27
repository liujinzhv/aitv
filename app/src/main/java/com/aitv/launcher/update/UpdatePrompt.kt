package com.aitv.launcher.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import com.aitv.launcher.BuildConfig
import com.aitv.launcher.Prefs
import com.aitv.launcher.R
import com.aitv.launcher.lock.LockState
import com.aitv.launcher.ui.BrowserActivity
import com.aitv.launcher.util.Async
import com.aitv.launcher.util.toast

/** 检查与提示：自动检查静默失败，手动检查给出明确结果 */
object UpdatePrompt {

    private var checking = false

    /** 首页启动时调用；24 小时内只查一次，锁屏期间不弹窗 */
    fun autoCheck(activity: Activity) {
        if (LockState.locked) return
        val now = System.currentTimeMillis()
        if (now - Prefs.updateCheckedAt < GiteeUpdate.CHECK_INTERVAL_MS) return
        check(activity, manual = false)
    }

    fun manualCheck(activity: Activity) {
        check(activity, manual = true)
    }

    private fun check(activity: Activity, manual: Boolean) {
        if (checking) {
            if (manual) activity.toast("正在检查更新，请稍候")
            return
        }
        checking = true
        if (manual) activity.toast("正在从 Gitee 检查最新版本…")
        Async.io {
            val result = GiteeUpdate.latest()
            Prefs.updateCheckedAt = System.currentTimeMillis()
            activity.runOnUiThread {
                checking = false
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { release ->
                        if (GiteeUpdate.isNewer(release.tag, BuildConfig.VERSION_NAME)) {
                            showRelease(activity, release)
                        } else if (manual) {
                            activity.toast(
                                "当前 ${BuildConfig.VERSION_NAME}，Gitee 最新 ${release.tag}，无需更新"
                            )
                        }
                    },
                    onFailure = {
                        if (manual) activity.toast("检查更新失败：${it.message ?: "网络异常"}")
                    }
                )
            }
        }
    }

    private fun showRelease(activity: Activity, release: GiteeUpdate.Release) {
        val notes = release.notes.ifBlank { "作者未填写更新说明" }
        val message = buildString {
            append(release.name).append("\n\n")
            append(notes).append("\n\n")
            append("当前版本：").append(BuildConfig.VERSION_NAME)
            if (release.apkUrl == null) append("\n本次发布没有 APK 附件，可打开发布页查看")
            else append("\n下载在后台进行，完成后自动打开系统安装器")
        }
        AlertDialog.Builder(activity, R.style.LightDialogTheme)
            .setTitle("发现新版本 ${release.tag}")
            .setMessage(message)
            .setPositiveButton("后台下载") { _, _ ->
                UpdateDownloader.start(activity, release)
            }
            .setNeutralButton("发布页") { _, _ -> open(activity, release.pageUrl) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun open(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(
                Intent(activity, BrowserActivity::class.java)
                    .putExtra(BrowserActivity.EXTRA_URL, url)
            )
        }.onFailure { activity.toast("无法打开下载页：${it.message ?: "未知错误"}") }
    }
}
