package com.aitv.launcher.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.widget.ImageView
import com.aitv.launcher.util.Async
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * 轻量图片加载器：LruCache + 采样解码 + EXIF 旋转，避免引入额外图片库。
 */
object ImageLoader {

    /** 位图缓存上限：低内存电视上 maxMemory/8 也可能吃掉三分之一堆，这里压到 1/12 并封顶 24MB */
    private val cache = object : LruCache<String, Bitmap>(cacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun cacheSize(): Int {
        val heap = Runtime.getRuntime().maxMemory()
        return (heap / 12).coerceIn(6L * 1024 * 1024, 24L * 1024 * 1024).toInt()
    }

    fun loadFile(view: ImageView, path: String, reqW: Int, reqH: Int) {
        val key = "file:$path:$reqW:$reqH"
        view.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        Async.io {
            val bitmap = decode(path, reqW, reqH)
            if (bitmap == null) {
                view.post { if (view.tag == key) view.setImageDrawable(null) }
                return@io
            }
            cache.put(key, bitmap)
            view.post {
                if (view.tag == key) view.setImageBitmap(bitmap)
            }
        }
    }

    fun loadEmbedded(view: ImageView, mediaPath: String, reqW: Int, reqH: Int) {
        val key = "embed:$mediaPath:$reqW:$reqH"
        view.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        Async.io {
            val bitmap = embeddedPicture(mediaPath, reqW, reqH)
            if (bitmap == null) {
                view.post { if (view.tag == key) view.setImageDrawable(null) }
                return@io
            }
            cache.put(key, bitmap)
            view.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    fun loadVideoFrame(view: ImageView, videoPath: String, reqW: Int, reqH: Int, timeMs: Long = -1L) {
        val key = "video:$videoPath:$timeMs:$reqW:$reqH"
        view.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        Async.io {
            val bitmap = videoFrame(videoPath, reqW, reqH, timeMs)
            if (bitmap == null) {
                view.post { if (view.tag == key) view.setImageDrawable(null) }
                return@io
            }
            cache.put(key, bitmap)
            view.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    fun decode(path: String, reqW: Int, reqH: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(path, opts) ?: return null
            rotateByExif(bitmap, path)
        } catch (oom: OutOfMemoryError) {
            // 大图解码吃满堆时先清空缓存把内存让出来，避免整页崩掉
            cache.evictAll()
            null
        } catch (t: Throwable) {
            null
        }
    }

    private fun embeddedPicture(path: String, reqW: Int, reqH: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            val bytes = mmr.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun videoFrame(path: String, reqW: Int, reqH: Int, timeMs: Long): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            val timesUs = if (timeMs >= 0) {
                listOf(timeMs * 1000, 1_000_000L, 0L)
            } else {
                listOf(5_000_000L, 1_000_000L, 0L)
            }
            var frame: Bitmap? = null
            for (us in timesUs) {
                frame = mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) break
            }
            if (frame == null) return null
            val maxSide = max(reqW, reqH).takeIf { it > 0 } ?: 640
            if (frame.width > maxSide) {
                val ratio = maxSide.toFloat() / frame.width
                val scaled = Bitmap.createScaledBitmap(
                    frame,
                    maxSide,
                    (frame.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled != frame) frame.recycle()
                scaled
            } else {
                frame
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    fun frameToFile(path: String, dest: File, timeMs: Long = 5000, width: Int = 640): Boolean {
        val bitmap = videoFrame(path, width, (width * 9 / 16), timeMs) ?: return false
        return try {
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            true
        } catch (t: Throwable) {
            false
        } finally {
            bitmap.recycle()
        }
    }

    private fun sampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (reqW <= 0 || reqH <= 0) return 1
        var sample = 1
        while (srcW / (sample * 2) >= reqW && srcH / (sample * 2) >= reqH) {
            sample *= 2
        }
        return sample
    }

    private fun rotateByExif(bitmap: Bitmap, path: String): Bitmap {
        return try {
            val orientation = ExifInterface(path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (t: Throwable) {
            bitmap
        }
    }

    fun clear() {
        cache.evictAll()
    }
}
