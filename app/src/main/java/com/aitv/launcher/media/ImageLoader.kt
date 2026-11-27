package com.aitv.launcher.media

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * 轻量图片加载器：LruCache + 采样解码 + EXIF 旋转，避免引入额外图片库。
 *
 * 大图库下真正危险的不是缓存，而是滚动时不断排队解码已移出屏幕的图片；
 * 这里用 activeKeys 引用计数，视图重新绑定/回收后，过期任务在解码前直接退出。
 */
object ImageLoader {

    /** 位图缓存上限：低内存电视上 maxMemory/8 也可能吃掉三分之一堆，这里压到 1/16 并封顶 16MB */
    private val cache = object : LruCache<String, Bitmap>(cacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 图片解码固定 2 路，视频取帧固定 1 路，避免取帧把扫描/界面线程池挤满 */
    private val imagePool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "mytv-image").apply { isDaemon = true }
    }
    private val framePool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mytv-video-frame").apply { isDaemon = true }
    }

    private val activeKeys = ConcurrentHashMap<String, Int>()

    private fun cacheSize(): Int {
        val heap = Runtime.getRuntime().maxMemory()
        return (heap / 16).coerceIn(8L * 1024 * 1024, 16L * 1024 * 1024).toInt()
    }

    fun loadFile(view: ImageView, path: String, reqW: Int, reqH: Int) {
        val key = "file:$path:$reqW:$reqH"
        if (!prepare(view, key)) return
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        retain(key)
        imagePool.execute {
            if (!isActive(key)) return@execute
            val thumb = thumbFile("photo", path, reqW, reqH)
            val cachedThumb = thumb?.let { ThumbCache.load(it) }
            val bitmap = cachedThumb ?: decode(path, reqW, reqH)?.also { decoded ->
                if (thumb != null && ThumbCache.save(decoded, thumb)) {
                    MediaIndex.updateThumb(path, thumb.absolutePath)
                }
            }
            if (bitmap == null) {
                view.post { if (view.tag == key) view.setImageDrawable(null) }
                return@execute
            }
            cache.put(key, bitmap)
            view.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    fun loadEmbedded(view: ImageView, mediaPath: String, reqW: Int, reqH: Int) {
        val key = "embed:$mediaPath:$reqW:$reqH"
        if (!prepare(view, key)) return
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        retain(key)
        imagePool.execute {
            if (!isActive(key)) return@execute
            val thumb = thumbFile("audio", mediaPath, reqW, reqH)
            val cachedThumb = thumb?.let { ThumbCache.load(it) }
            val bitmap = cachedThumb ?: embeddedPicture(mediaPath, reqW, reqH)?.also { decoded ->
                if (thumb != null && ThumbCache.save(decoded, thumb)) {
                    MediaIndex.updateThumb(mediaPath, thumb.absolutePath)
                }
            }
            if (bitmap == null) {
                view.post { if (view.tag == key) view.setImageDrawable(null) }
                return@execute
            }
            cache.put(key, bitmap)
            view.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    fun loadVideoFrame(view: ImageView, videoPath: String, reqW: Int, reqH: Int, timeMs: Long = -1L) {
        val key = "video:$videoPath:$timeMs:$reqW:$reqH"
        if (!prepare(view, key)) return
        val cached = cache.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        retain(key)
        framePool.execute {
            if (!isActive(key)) return@execute
            val thumb = thumbFile("video", videoPath, reqW, reqH, timeMs)
            val cachedThumb = thumb?.let { ThumbCache.load(it) }
            val bitmap = cachedThumb ?: videoFrame(videoPath, reqW, reqH, timeMs)?.also { decoded ->
                if (thumb != null && ThumbCache.save(decoded, thumb)) {
                    MediaIndex.updateThumb(videoPath, thumb.absolutePath)
                }
            }
            if (bitmap == null) {
                view.post { if (view.tag == key) view.setImageDrawable(null) }
                return@execute
            }
            cache.put(key, bitmap)
            view.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    /** 后台预生成缩略图：只解码一张、写盘后立即释放，不进入内存 LRU */
    fun prefetchThumb(kind: String, path: String, reqW: Int, reqH: Int, timeMs: Long = 0L): Boolean {
        if (kind != "photo" && kind != "video" && kind != "audio") return false
        if (ThumbCache.isOwned(path)) return false
        val thumb = thumbFile(kind, path, reqW, reqH, timeMs) ?: return false
        if (thumb.exists() && thumb.length() > 0L) {
            if (MediaIndex.updateThumb(path, thumb.absolutePath)) return true
            // 索引里已经没有这个文件（后台补图期间用户删了图），别留下孤儿缩略图
            ThumbCache.delete(thumb.absolutePath)
            return false
        }
        val bitmap = when (kind) {
            "photo" -> decode(path, reqW, reqH)
            "audio" -> embeddedPicture(path, reqW, reqH)
            else -> videoFrame(path, reqW, reqH, timeMs)
        } ?: return false
        return try {
            if (ThumbCache.save(bitmap, thumb)) {
                if (MediaIndex.updateThumb(path, thumb.absolutePath)) {
                    true
                } else {
                    ThumbCache.delete(thumb.absolutePath)
                    false
                }
            } else false
        } finally {
            bitmap.recycle()
        }
    }

    /** 返回 false 表示同一个视图已经在请求同一个 key，不需要重复入队 */
    private fun prepare(view: ImageView, key: String): Boolean {
        val old = view.tag as? String
        if (old == key) return cache.get(key) != null || !activeKeys.containsKey(key)
        if (old != null) {
            release(old)
        }
        view.tag = key
        return true
    }

    /** RecyclerView 回收/离屏时主动取消，避免滚动一下排队几百个过期解码 */
    fun cancel(view: ImageView) {
        val key = view.tag as? String
        if (key != null) release(key)
        view.tag = null
        view.setImageDrawable(null)
    }

    private fun retain(key: String) {
        activeKeys.merge(key, 1, Int::plus)
    }

    private fun release(key: String) {
        activeKeys.computeIfPresent(key) { _, count ->
            if (count > 1) count - 1 else null
        }
    }

    private fun isActive(key: String): Boolean = activeKeys.containsKey(key)

    /** 列表缩略图才走磁盘缓存；大图/壁纸仍直接解码，避免生成一堆大尺寸缓存 */
    private fun thumbFile(kind: String, path: String, reqW: Int, reqH: Int, timeMs: Long = 0L): File? {
        if (reqW <= 0 || reqH <= 0) return null
        if (reqW.toLong() * reqH.toLong() > 800_000L) return null
        // 本身已经是应用内小图（海报/封面/缩略图），直接解码，避免再套一层缩略图造成孤儿文件
        if (ThumbCache.isOwned(path)) return null
        return runCatching { ThumbCache.fileFor(kind, path, reqW, reqH, timeMs) }.getOrNull()
    }

    fun decode(path: String, reqW: Int, reqH: Int): Bitmap? {        val file = File(path)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
                inPreferredConfig = preferredConfig(reqW, reqH)
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

    private fun preferredConfig(reqW: Int, reqH: Int): Bitmap.Config {
        val pixels = reqW.toLong() * reqH.toLong()
        // 列表缩略图/海报用 565 就够，省一半内存；大图/壁纸仍用 8888 保证色彩
        return if (pixels in 1..1_000_000L) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
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
                inPreferredConfig = preferredConfig(reqW, reqH)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (oom: OutOfMemoryError) {
            cache.evictAll()
            null
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun videoFrame(path: String, reqW: Int, reqH: Int, timeMs: Long): Bitmap? {
        val timesUs = if (timeMs >= 0) {
            listOf(timeMs * 1000, 1_000_000L, 0L)
        } else {
            listOf(5_000_000L, 1_000_000L, 0L)
        }
        val targetW = reqW.coerceAtLeast(1)
        val targetH = reqH.coerceAtLeast(1)

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)
            for (us in timesUs) {
                // API 27+ 直接取缩放帧，避免 4K 视频先解出一张 30MB 的整帧
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    runCatching {
                        mmr.getScaledFrameAtTime(
                            us,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            targetW,
                            targetH
                        )
                    }.getOrNull()
                } else {
                    // Android 8.0 没有缩放取帧接口，用系统缩略图接口，仍然比整帧安全
                    runCatching {
                        ThumbnailUtils.createVideoThumbnail(
                            path,
                            MediaStore.Video.Thumbnails.MINI_KIND
                        )
                    }.getOrNull()
                }
                if (frame != null) return scaleFrame(frame, targetW, targetH)
            }
            return null
        } catch (oom: OutOfMemoryError) {
            cache.evictAll()
            return null
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun scaleFrame(frame: Bitmap, reqW: Int, reqH: Int): Bitmap {
        val maxSide = max(reqW, reqH).takeIf { it > 0 } ?: 640
        if (frame.width <= maxSide && frame.height <= maxSide) return frame
        val ratio = maxSide.toFloat() / max(frame.width, frame.height)
        val scaled = Bitmap.createScaledBitmap(
            frame,
            (frame.width * ratio).toInt().coerceAtLeast(1),
            (frame.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled != frame) frame.recycle()
        return scaled
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

    /** 系统内存紧张/界面不可见时主动让出缓存，降低被 LMK 杀进程后重启的概率 */
    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            cache.evictAll()
        }
    }

    fun clear() {
        cache.evictAll()
    }
}
