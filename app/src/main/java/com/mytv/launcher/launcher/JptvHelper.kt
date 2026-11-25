package com.mytv.launcher.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.mytv.launcher.Prefs
import com.mytv.launcher.model.ApkFile
import com.mytv.launcher.util.StorageUtils
import java.io.File

/**
 * JPTV（荐片，包名 tv.android.jianpian.com）接入：
 * 已安装直接启动；未安装时在 Download 目录找 JPTV-5.0.4.apk 并拉起系统安装器。
 */
object JptvHelper {

    const val DEFAULT_PACKAGE = "tv.android.jianpian.com"

    val pkg: String
        get() = Prefs.jptvPackage.ifBlank { DEFAULT_PACKAGE }

    fun isInstalled(ctx: Context): Boolean = AppRepo.isInstalled(ctx, pkg)

    fun installedLabel(ctx: Context): String = AppRepo.labelOf(ctx, pkg) ?: "荐片"

    fun launch(ctx: Context): Boolean = AppRepo.launch(ctx, pkg)

    fun isJptv(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("jptv") || name.contains("荐片")
    }

    fun apks(ctx: Context): List<ApkFile> {
        val list = mutableListOf<ApkFile>()
        for (dir in StorageUtils.downloadDirs()) {
            dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".apk") }?.forEach { f ->
                list.add(ApkFile(f.absolutePath, f.name, f.length(), f.lastModified()))
            }
        }
        return list.distinctBy { it.path }
            .sortedWith(compareByDescending<ApkFile> { isJptv(it.name) }.thenByDescending { it.lastModified })
    }

    fun jptvApk(ctx: Context): ApkFile? = apks(ctx).firstOrNull { isJptv(it.name) }

    fun canInstall(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (ignored: Throwable) {
                Toast.makeText(ctx, "请在系统设置中允许安装未知来源应用", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun install(ctx: Context, apk: ApkFile): Boolean {
        val file = File(apk.path)
        if (!file.exists()) {
            Toast.makeText(ctx, "安装包不存在：${apk.path}", Toast.LENGTH_LONG).show()
            return false
        }
        if (!canInstall(ctx)) {
            Toast.makeText(ctx, "请先允许 智慧家庭TV 安装未知来源应用", Toast.LENGTH_LONG).show()
            openInstallSettings(ctx)
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        } catch (t: Throwable) {
            Toast.makeText(ctx, "无法启动安装器：${t.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun statusText(ctx: Context): String {
        return when {
            isInstalled(ctx) -> "已安装 · 点击打开"
            jptvApk(ctx) != null -> "发现安装包 · 点击安装"
            else -> "未安装 · 进入下载目录"
        }
    }
}
