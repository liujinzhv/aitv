package com.mytv.launcher.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable?,
    val leanback: Boolean = false
)

object AppRepo {

    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val result = LinkedHashMap<String, AppEntry>()

        fun collect(category: String, leanback: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val list = try {
                pm.queryIntentActivities(intent, 0)
            } catch (t: Throwable) {
                emptyList()
            }
            for (info in list) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == ctx.packageName) continue
                if (result.containsKey(pkg)) continue

                val label = runCatching { info.loadLabel(pm)?.toString()?.trim() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: pkg
                val icon = runCatching { info.loadIcon(pm) }.getOrNull() ?: pm.defaultActivityIcon
                result[pkg] = AppEntry(label, pkg, icon, leanback)
            }
        }

        collect(Intent.CATEGORY_LAUNCHER, false)
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)

        return result.values.sortedWith(compareBy { it.label })
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun labelOf(ctx: Context, pkg: String): String? {
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (t: Throwable) {
            null
        }
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = try {
            pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        } catch (t: Throwable) {
            null
        } ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
