package com.aitv.launcher.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri

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

    /** 系统预装且没有更新过的应用不允许普通应用卸载；其余交给系统卸载器确认。 */
    fun canUninstall(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updatedSystem = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (system && !updatedSystem) return false
            pm.resolveActivity(uninstallIntent(pkg), 0) != null
        } catch (t: Throwable) {
            false
        }
    }

    fun isSystemApp(ctx: Context, pkg: String): Boolean = try {
        val info = ctx.packageManager.getApplicationInfo(pkg, 0)
        info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (t: Throwable) {
        false
    }

    fun uninstallIntent(pkg: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))

    fun uninstall(ctx: Context, pkg: String): Boolean = try {
        ctx.startActivity(uninstallIntent(pkg))
        true
    } catch (t: Throwable) {
        false
    }
}
