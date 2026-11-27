package com.aitv.launcher.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.view.View
import com.aitv.launcher.media.ImageLoader
import java.io.File

/**
 * 应用内壁纸存储。
 *
 * 这台 TCL 电视没有系统 wallpaper 服务（dumpsys wallpaper 不存在），
 * WallpaperManager 读写都会失败。因此图片复制到应用私有目录、视频只记录本地文件路径，
 * 首页直接播放 / 显示；不依赖系统壁纸，也不会被系统清理。
 */
object WallpaperStore {

    enum class Kind { NONE, IMAGE, VIDEO }

    private const val DIR = "wallpaper"
    private const val IMAGE_NAME = "home_wallpaper.img"
    private const val SP_NAME = "wallpaper_store"
    private const val KEY_VIDEO = "video_path"

    fun imageFile(context: Context): File? {
        val f = File(File(context.filesDir, DIR), IMAGE_NAME)
        return f.takeIf { it.isFile && it.length() > 0 }
    }

    fun videoPath(context: Context): String {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VIDEO, "") ?: ""
    }

    fun videoFile(context: Context): File? {
        val path = videoPath(context)
        if (path.isBlank()) return null
        val f = File(path)
        return f.takeIf { it.isFile && it.length() > 0 }
    }

    fun kind(context: Context): Kind = when {
        imageFile(context) != null -> Kind.IMAGE
        videoFile(context) != null -> Kind.VIDEO
        else -> Kind.NONE
    }

    fun has(context: Context): Boolean = kind(context) != Kind.NONE

    /** 把相册原图复制为壁纸，保留原始分辨率与画质。 */
    fun setImageFromFile(context: Context, source: File): Boolean {
        if (!source.isFile || source.length() <= 0) return false
        return runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val temp = File(dir, "$IMAGE_NAME.tmp")
            source.inputStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() <= 0) {
                temp.delete()
                return@runCatching false
            }
            val dest = File(dir, IMAGE_NAME)
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
            clearVideoPref(context)
            dest.length() > 0
        }.getOrDefault(false)
    }

    /** 视频不复制，避免占双份空间；记录本地路径，桌面用 ExoPlayer 静音循环播放。 */
    fun setVideo(context: Context, path: String): Boolean {
        val file = File(path)
        if (!file.isFile || file.length() <= 0) return false
        clearImage(context)
        return runCatching {
            context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VIDEO, file.absolutePath)
                .commit()
        }.getOrDefault(false)
    }

    fun clear(context: Context): Boolean {
        clearImage(context)
        clearVideoPref(context)
        return true
    }

    private fun clearImage(context: Context) {
        runCatching { File(File(context.filesDir, DIR), IMAGE_NAME).delete() }
    }

    private fun clearVideoPref(context: Context) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_VIDEO).apply()
    }

    /**
     * 解码图片壁纸到 ImageView。回调在主线程执行：(是否成功, 图片平均亮度)。
     */
    fun loadInto(
        context: Context,
        target: ImageView,
        onResult: ((Boolean, Float) -> Unit)? = null
    ) {
        val source = imageFile(context)
        if (source == null) {
            target.setImageDrawable(null)
            target.visibility = View.GONE
            onResult?.invoke(false, 0.12f)
            return
        }
        val width = context.resources.displayMetrics.widthPixels
        val height = context.resources.displayMetrics.heightPixels
        Async.io {
            val bitmap = ImageLoader.decode(source.absolutePath, width, height)
            val luminance = bitmap?.let { averageLuminance(it) } ?: 0.12f
            Handler(Looper.getMainLooper()).post {
                if (bitmap != null) {
                    target.setImageBitmap(bitmap)
                    target.visibility = View.VISIBLE
                    onResult?.invoke(true, luminance)
                } else {
                    target.setImageDrawable(null)
                    target.visibility = View.GONE
                    onResult?.invoke(false, 0.12f)
                }
            }
        }
    }

    /** 缩略采样后算平均亮度，避免逐像素遍历整张 1080P 图。 */
    fun averageLuminance(bitmap: Bitmap): Float {
        val stepX = (bitmap.width / 48).coerceAtLeast(1)
        val stepY = (bitmap.height / 48).coerceAtLeast(1)
        var sum = 0f
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) != 0) {
                    val r = Color.red(color) / 255f
                    val g = Color.green(color) / 255f
                    val b = Color.blue(color) / 255f
                    sum += 0.2126f * r + 0.7152f * g + 0.0722f * b
                    count++
                }
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0.12f else (sum / count).coerceIn(0f, 1f)
    }
}
