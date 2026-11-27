package com.aitv.launcher.util

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ImageView
import com.aitv.launcher.media.ImageLoader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 系统壁纸桥接：
 * · 首页使用透出系统壁纸的窗口，静态 / 动态壁纸都由系统自己绘制，更换后自动同步。
 * · 锁屏优先读取系统 FLAG_LOCK 壁纸文件显示；没有独立锁屏壁纸（或动态壁纸无文件）时，
 *   保留透出系统壁纸的窗口，让锁屏壁纸 / 动态壁纸由系统继续绘制。
 * · 相册「设为壁纸」直接写入系统 WallpaperManager，桌面和锁屏一起同步。
 */
object SystemWallpaper {

    fun manager(context: Context): WallpaperManager = WallpaperManager.getInstance(context)

    fun isLive(context: Context): Boolean = runCatching {
        manager(context).wallpaperInfo != null
    }.getOrDefault(false)

    fun hasWallpaper(context: Context): Boolean = runCatching {
        manager(context).wallpaperInfo != null || manager(context).drawable != null
    }.getOrDefault(false)

    /**
     * 采样系统壁纸的整体亮度（0 暗 → 1 亮）。
     * 动态壁纸取系统的静态预览帧；没有壁纸时按深色兜底。
     */
    fun averageLuminance(context: Context): Float = runCatching {
        val drawable = manager(context).drawable
        if (drawable == null) {
            0.12f
        } else {
            val size = 48
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            var sum = 0f
            var count = 0
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val color = bitmap.getPixel(x, y)
                    if (Color.alpha(color) == 0) continue
                    val r = Color.red(color) / 255f
                    val g = Color.green(color) / 255f
                    val b = Color.blue(color) / 255f
                    sum += 0.2126f * r + 0.7152f * g + 0.0722f * b
                    count++
                }
            }
            bitmap.recycle()
            if (count == 0) 0.12f else (sum / count).coerceIn(0f, 1f)
        }
    }.getOrDefault(0.12f)

    fun label(context: Context): String = when {
        isLive(context) -> "动态壁纸"
        hasWallpaper(context) -> "静态壁纸"
        else -> "未设置"
    }

    /** 锁屏壁纸文件可能不存在（系统未单独设置锁屏），此时返回 null 让调用方回退首页壁纸。 */
    fun copyLockWallpaperToCache(context: Context): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return runCatching {
            val pfd = manager(context).getWallpaperFile(WallpaperManager.FLAG_LOCK)
                ?: return null
            val dest = File(context.cacheDir, "system_lock_wallpaper.dat")
            pfd.use { parcel ->
                FileInputStream(parcel.fileDescriptor).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            }
            if (dest.length() > 0) dest else null
        }.getOrNull()
    }

    /**
     * 锁屏页加载系统锁屏壁纸：只读 FLAG_LOCK 文件。
     * 没有独立锁屏壁纸时返回 false，让 Activity 保留系统壁纸窗口（动态壁纸也能继续动）。
     */
    fun loadLockInto(context: Context, target: ImageView, onResult: (Boolean) -> Unit) {
        val width = context.resources.displayMetrics.widthPixels
        val height = context.resources.displayMetrics.heightPixels
        Async.io {
            val lockFile = copyLockWallpaperToCache(context)
            val lockBitmap = lockFile?.let { ImageLoader.decode(it.absolutePath, width, height) }
            Handler(Looper.getMainLooper()).post {
                if (lockBitmap != null) {
                    target.setImageBitmap(lockBitmap)
                    target.visibility = android.view.View.VISIBLE
                    onResult(true)
                } else {
                    target.setImageDrawable(null)
                    target.visibility = android.view.View.GONE
                    onResult(false)
                }
            }
        }
    }

    /**
     * 把相册图片写入系统壁纸：桌面 + 锁屏一起设置，系统设置页也会同步显示。
     * 某些电视 / 老系统不支持独立锁屏壁纸，失败时仍保留桌面设置。
     */
    fun setSystemWallpaper(context: Context, bitmap: Bitmap): Pair<Boolean, Boolean> {
        val wm = manager(context)
        var home = false
        var lock = false
        try {
            wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
            home = true
        } catch (t: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                lock = true
            } catch (t: Throwable) {
            }
        }
        return home to lock
    }

    /** 打开系统壁纸选择器；TV 上没有壁纸选择器时退回系统显示设置。 */
    fun openPicker(activity: Activity): Boolean {
        val picker = Intent(Intent.ACTION_SET_WALLPAPER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = runCatching {
            activity.packageManager.queryIntentActivities(picker, 0)
        }.getOrDefault(emptyList())
        if (resolved.isNotEmpty()) {
            runCatching { activity.startActivity(picker) }
                .onSuccess { return true }
        }
        val display = Intent(Settings.ACTION_DISPLAY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { activity.startActivity(display) }.isSuccess
    }
}
