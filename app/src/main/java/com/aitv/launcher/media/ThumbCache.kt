package com.aitv.launcher.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aitv.launcher.App
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 本地缩略图缓存：相册、视频首帧、歌曲封面都写到 files/thumbs。
 *
 * 文件名按「源文件路径 + 大小 + 修改时间 + 目标尺寸」哈希，原图变了自然换成新文件；
 * 原图删除时由扫描器按 SQLite 里的 thumb_path 精确删除，不靠定期清目录。
 */
object ThumbCache {

    private val rootDir: File
        get() = File(App.ctx.filesDir, "thumbs").apply { mkdirs() }

    fun fileFor(kind: String, sourcePath: String, reqW: Int, reqH: Int, timeMs: Long = 0L): File {
        val source = File(sourcePath)
        val raw = "$kind:${source.absolutePath}:${source.length()}:${source.lastModified()}:$reqW:$reqH:$timeMs"
        val key = MessageDigest.getInstance("SHA-1")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val dir = File(rootDir, kind).apply { mkdirs() }
        return File(dir, "$key.jpg")
    }

    /** 命中本地缩略图就直接解码小图；损坏的文件删除后重新生成 */
    fun load(file: File): Bitmap? {
        if (!file.exists() || file.length() <= 0L) return null
        return try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } catch (t: Throwable) {
            file.delete()
            null
        }
    }

    fun save(bitmap: Bitmap, file: File): Boolean {
        val temp = File(file.parentFile, file.name + "." + System.nanoTime() + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(temp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            if (temp.length() <= 0L) {
                temp.delete()
                false
            } else {
                if (file.exists()) file.delete()
                temp.renameTo(file) || temp.copyTo(file, overwrite = true).length() > 0L
            }
        } catch (t: Throwable) {
            temp.delete()
            false
        }
    }

    /** 为单张新照片立即生成缩略图，上传完成后第一次进相册就能直接显示 */
    fun generatePhoto(sourcePath: String, reqW: Int, reqH: Int): File? {
        val dest = fileFor("photo", sourcePath, reqW, reqH)
        if (dest.exists() && dest.length() > 0L) return dest
        val bitmap = ImageLoader.decode(sourcePath, reqW, reqH) ?: return null
        return try {
            if (save(bitmap, dest)) dest else null
        } finally {
            bitmap.recycle()
        }
    }

    fun isOwned(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val file = File(path)
        val ownedRoots = listOf(rootDir, File(App.ctx.filesDir, "artwork"))
        return ownedRoots.any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            val filePath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            filePath.startsWith(rootPath + File.separator)
        }
    }

    fun delete(path: String?) {
        if (!isOwned(path)) return
        runCatching { File(path!!).delete() }
    }

    /**
     * 清理换 key / 重复生成留下的孤儿缩略图：只扫应用自己的 thumbs/photo、thumbs/video，
     * 且只删 5 分钟前的文件，避免误删正在显示/正在写入的新缩略图。
     */
    fun pruneOrphans(knownPaths: Set<String>, minAgeMs: Long = 5 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - minAgeMs
        val known = HashSet<String>(knownPaths.size + 8, 0.75f)
        knownPaths.forEach { path ->
            runCatching { File(path).canonicalPath }.getOrNull()?.let { known.add(it) }
        }
        for (kind in listOf("photo", "video")) {
            val dir = File(rootDir, kind)
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile || !file.name.endsWith(".jpg", true)) continue
                if (file.lastModified() > cutoff) continue
                val canonical = runCatching { file.canonicalPath }.getOrNull() ?: continue
                if (canonical !in known) runCatching { file.delete() }
            }
        }
    }
}
