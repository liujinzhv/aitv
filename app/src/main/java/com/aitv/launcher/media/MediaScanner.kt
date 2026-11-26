package com.aitv.launcher.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.aitv.launcher.App
import com.aitv.launcher.Prefs
import com.aitv.launcher.model.AudioTrack
import com.aitv.launcher.model.PhotoItem
import com.aitv.launcher.model.VideoItem
import com.aitv.launcher.util.StorageUtils
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 本地媒体扫描：音频 / 视频 / 图片。
 * Android 8 没有分区存储限制，直接遍历所有存储卷即可；结果按类型缓存，避免每次进页面都全盘扫。
 */
object MediaScanner {

    private val audioExts = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "mka", "ape")
    private val videoExts = setOf(
        "mp4", "mkv", "avi", "ts", "m2ts", "mov", "webm", "flv", "wmv",
        "rmvb", "rm", "mpg", "mpeg", "m4v", "3gp", "ogv", "divx"
    )
    private val imageExts = buildSet<String> {
        addAll(listOf("jpg", "jpeg", "png", "gif", "webp", "bmp"))
        if (Build.VERSION.SDK_INT >= 28) {
            add("heic")
            add("heif")
        }
    }

    @Volatile
    private var audioCache: List<AudioTrack>? = null

    @Volatile
    private var videoCache: List<VideoItem>? = null

    @Volatile
    private var photoCache: List<PhotoItem>? = null

    @Volatile
    var lastScanAt: Long = 0L
        private set

    fun roots(): List<File> {
        val list = mutableListOf<File>()
        list.addAll(StorageUtils.volumes())
        list.addAll(StorageUtils.dirsFromPref(Prefs.musicDirs))
        list.addAll(StorageUtils.dirsFromPref(Prefs.videoDirs))
        return list.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    fun invalidate() {
        audioCache = null
        videoCache = null
        photoCache = null
        lastScanAt = 0
        Prefs.lastScanAt = 0
    }

    fun audio(ctx: Context, force: Boolean = false, progress: (String) -> Unit = {}): List<AudioTrack> {
        val cached = audioCache
        if (!force && cached != null) return cached
        val result = mutableListOf<AudioTrack>()
        val seen = HashSet<String>()
        var count = 0

        for (root in roots()) {
            StorageUtils.scan(root, audioExts) { file ->
                val key = file.absolutePath
                if (seen.add(key)) {
                    runCatching { result.add(readAudio(file)) }
                    count++
                    if (count % 20 == 0) progress("已找到 $count 首音频…")
                }
            }
        }

        val sorted = result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        audioCache = sorted
        lastScanAt = System.currentTimeMillis()
        Prefs.lastScanAt = lastScanAt
        return sorted
    }

    fun videos(ctx: Context, force: Boolean = false, progress: (String) -> Unit = {}): List<VideoItem> {
        val cached = videoCache
        if (!force && cached != null) return cached
        val result = mutableListOf<VideoItem>()
        val seen = HashSet<String>()
        var count = 0

        for (root in roots()) {
            StorageUtils.scan(root, videoExts) { file ->
                if (seen.add(file.absolutePath)) {
                    result.add(videoOf(file))
                    count++
                    if (count % 20 == 0) progress("已找到 $count 个视频…")
                }
            }
        }

        val sorted = result.sortedByDescending { it.dateModified }
        videoCache = sorted
        return sorted
    }

    fun photos(ctx: Context, force: Boolean = false, progress: (String) -> Unit = {}): List<PhotoItem> {
        val cached = photoCache
        if (!force && cached != null) return cached
        val result = mutableListOf<PhotoItem>()
        val seen = HashSet<String>()
        val target = StorageUtils.photoTarget(ctx)

        val scanRoots = roots().toMutableList()
        if (target.exists()) scanRoots.add(target)

        for (root in scanRoots) {
            StorageUtils.scan(root, imageExts) { file ->
                if (seen.add(file.absolutePath)) {
                    result.add(
                        PhotoItem(
                            path = file.absolutePath,
                            name = file.name,
                            size = file.length(),
                            lastModified = file.lastModified(),
                            takenAt = photoTakenAt(file)
                        )
                    )
                }
            }
            progress("已找到 ${result.size} 张照片…")
        }

        val sorted = result.sortedWith(compareByDescending<PhotoItem> { it.takenAt }
            .thenByDescending { it.lastModified })
        photoCache = sorted
        return sorted
    }

    /** 真实日期优先：EXIF 拍摄时间 → 文件名中的日期 → 文件修改时间 */
    private fun photoTakenAt(file: File): Long {
        readExifDate(file)?.let { return it }
        parseDateFromName(file.name)?.let { return it }
        return file.lastModified()
    }

    private fun readExifDate(file: File): Long? {
        val exif = try {
            ExifInterface(file.absolutePath)
        } catch (t: Throwable) {
            return null
        }
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            ?: return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val patterns = listOf("yyyy:MM:dd HH:mm:ss", "yyyy:MM:dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss")
        for (pattern in patterns) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val date = format.parse(trimmed) ?: continue
                if (date.time > 0L) return date.time
            } catch (t: Throwable) {
                // 尝试下一种格式
            }
        }
        return null
    }

    /**
     * 解析常见相机 / 手机文件名里的日期：
     * IMG_20240318_142530、2024-03-18 14.25.30、PXL_20240318_142530123、VID_20240318_142530 等。
     */
    private fun parseDateFromName(name: String): Long? {
        val regex = Regex(
            "(20\\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\\d|3[01])" +
                "(?:[-_. T]?(\\d{2})[-_.:]?(\\d{2})[-_.:]?(\\d{2}))?"
        )
        val match = regex.find(name) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val hour = match.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
        val minute = match.groupValues.getOrNull(5)?.toIntOrNull() ?: 0
        val second = match.groupValues.getOrNull(6)?.toIntOrNull() ?: 0

        val calendar = Calendar.getInstance().apply {
            timeZone = TimeZone.getDefault()
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
            isLenient = false
        }
        return try {
            val millis = calendar.timeInMillis
            if (millis > 0L && year in 1990..2100) millis else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun readAudio(file: File): AudioTrack {
        val base = file.nameWithoutExtension
        var tagTitle: String? = null
        var tagArtist: String? = null
        var tagAlbum: String? = null
        var embeddedPicture: ByteArray? = null
        var duration = 0L

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            tagTitle = MusicMeta.cleanTag(
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                base
            )
            tagArtist = MusicMeta.cleanTag(
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                base
            )
            tagAlbum = MusicMeta.cleanTag(
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                base
            )
            embeddedPicture = mmr.embeddedPicture
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            // 损坏文件或格式不支持，下面继续用兜底解析
        } finally {
            runCatching { mmr.release() }
        }

        // MediaMetadataRetriever 读不到的标签（部分 WMA / APE / 特殊 ID3），再用轻量解析器兜底
        if (tagTitle == null || tagArtist == null || tagAlbum == null || embeddedPicture == null) {
            val extra = MusicMeta.readTags(file)
            if (extra != null) {
                if (tagTitle == null) tagTitle = MusicMeta.cleanTag(extra.title, base)
                if (tagArtist == null) tagArtist = MusicMeta.cleanTag(extra.artist, base)
                if (tagAlbum == null) tagAlbum = MusicMeta.cleanTag(extra.album, base)
                if (embeddedPicture == null) embeddedPicture = extra.picture
            }
        }

        val parsed = MusicMeta.parseFileName(base)
        val title = tagTitle ?: parsed.title.ifBlank { base }
        val artist = tagArtist ?: parsed.artist
        val album = tagAlbum ?: ""
        val artwork = saveEmbeddedArtwork(file, embeddedPicture) ?: findArtwork(file)

        return AudioTrack(
            path = file.absolutePath,
            title = title,
            artist = MusicMeta.displayArtist(artist),
            album = album,
            durationMs = duration,
            artworkPath = artwork?.absolutePath,
            tagTitle = tagTitle,
            tagArtist = tagArtist
        )
    }

    /** 把内嵌封面写到应用缓存目录，列表和通知栏都能直接复用 */
    private fun saveEmbeddedArtwork(file: File, bytes: ByteArray?): File? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val dir = File(App.ctx.filesDir, "artwork").apply { mkdirs() }
            val raw = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
            val key = MessageDigest.getInstance("MD5")
                .digest(raw.toByteArray())
                .joinToString("") { String.format("%02x", it) }
            val target = File(dir, "$key.img")
            if (target.exists() && target.length() > 0L) return target
            val temp = File(dir, "$key.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.writeBytes(bytes)
                temp.delete()
            }
            target.takeIf { it.exists() && it.length() > 0L }
        } catch (t: Throwable) {
            null
        }
    }

    fun videoOf(file: File): VideoItem {
        var duration = 0L
        var width = 0
        var height = 0
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } catch (t: Throwable) {
            // ignore
        } finally {
            runCatching { mmr.release() }
        }

        val parsed = VideoScraper.parseName(file.nameWithoutExtension)
        return VideoItem(
            path = file.absolutePath,
            title = parsed.title,
            year = parsed.year,
            season = parsed.season,
            episode = parsed.episode,
            durationMs = duration,
            width = width,
            height = height,
            size = file.length(),
            dateModified = file.lastModified(),
            posterPath = findArtwork(file)?.absolutePath
        )
    }

    fun videoByPath(ctx: Context, path: String): VideoItem? {
        videoCache?.firstOrNull { it.path == path }?.let { return it }
        val file = File(path)
        if (!file.exists()) return null
        return videoOf(file)
    }

    fun cachedVideoByPath(path: String): VideoItem? = videoCache?.firstOrNull { it.path == path }

    private fun findArtwork(file: File): File? {
        val dir = file.parentFile ?: return null
        val base = file.nameWithoutExtension
        val candidates = listOf(
            "$base.jpg", "$base.png", "cover.jpg", "cover.png",
            "folder.jpg", "folder.png", "poster.jpg", "poster.png"
        )
        for (name in candidates) {
            val f = File(dir, name)
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }
}
