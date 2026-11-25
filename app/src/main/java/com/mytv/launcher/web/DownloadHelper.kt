package com.mytv.launcher.web

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.webkit.URLUtil

/** 处理 http(s) 与迅雷 thunder:// 链接，交给系统 DownloadManager 落到公共下载目录。 */
object DownloadHelper {

    data class Result(val ok: Boolean, val message: String, val url: String? = null, val fileName: String? = null)

    /** thunder:// 内容是 Base64，解码后形如 AA<真实地址>ZZ；普通链接原样返回。 */
    fun resolve(raw: String): String? {
        var input = raw.trim()
        if (input.isEmpty()) return null
        if (input.startsWith("thunder://", true)) {
            input = input.substring("thunder://".length).trim().trimEnd('/')
        } else {
            return input.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }
        return try {
            var decoded = String(Base64.decode(input, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (decoded.startsWith("AA")) decoded = decoded.substring(2)
            if (decoded.endsWith("ZZ")) decoded = decoded.dropLast(2)
            decoded.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        } catch (t: Throwable) {
            null
        }
    }

    fun enqueue(context: Context, raw: String): Result {
        val input = raw.trim()
        if (input.startsWith("magnet:", true)) {
            return Result(false, "暂不支持磁力链接，请使用直链或迅雷 thunder:// 链接")
        }
        val url = resolve(input)
            ?: return Result(false, "链接无法识别，请检查是否为 http(s) 或 thunder:// 地址")
        val fileName = URLUtil.guessFileName(url, null, null)
            .takeIf { it.isNotBlank() && it != "unknown" }
            ?: "download-${System.currentTimeMillis()}"
        return try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("来自 智慧家庭TV 浏览器")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Result(true, "已加入下载：$fileName", url, fileName)
        } catch (t: Throwable) {
            Result(false, "下载失败：${t.message ?: t.javaClass.simpleName}")
        }
    }
}
