package com.aitv.launcher.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import com.aitv.launcher.App
import com.aitv.launcher.Prefs
import com.aitv.launcher.model.AudioTrack
import com.aitv.launcher.model.PhotoItem
import com.aitv.launcher.model.VideoItem
import com.aitv.launcher.util.StorageUtils
import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地媒体索引。
 *
 * 页面打开时只查 SQLite；只有首次安装、设置里手动点“扫描”、目录变更时才全量遍历文件。
 * 上传完成按单个文件增量写入。媒体文件、封面、台标仍保存在本地文件系统，库里只存索引路径和元数据。
 */
object MediaScanner {

    data class ScanResult(
        val audio: Int = 0,
        val video: Int = 0,
        val photo: Int = 0,
        val added: Int = 0,
        val reused: Int = 0,
        val elapsedMs: Long = 0L,
        val ok: Boolean = true,
        val permissionDenied: Boolean = false
    ) {
        val total: Int get() = audio + video + photo
    }

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
    private val allExts = audioExts + videoExts + imageExts

    @Volatile
    private var audioCache: List<AudioTrack>? = null

    @Volatile
    private var videoCache: List<VideoItem>? = null

    @Volatile
    private var photoCache: List<PhotoItem>? = null

    @Volatile
    var lastScanAt: Long = 0L
        private set

    /** 扫描结果失效代数；页面用它判断是否需要重新查库 */
    @Volatile
    var generation: Long = 0L
        private set

    fun roots(): List<File> {
        val list = mutableListOf<File>()
        list.addAll(StorageUtils.volumes())
        list.addAll(StorageUtils.dirsFromPref(Prefs.musicDirs))
        list.addAll(StorageUtils.dirsFromPref(Prefs.videoDirs))
        return list.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    /** 仅清内存缓存，不删除 SQLite 索引，页面下一次会直接重新查库 */
    fun invalidate() {
        audioCache = null
        videoCache = null
        photoCache = null
        generation++
    }

    /**
     * 页面第一次打开、或设置里点扫描时调用。全量只枚举文件，不读音频/视频标签；
     * 时长、分辨率、内嵌封面仍由可见项按需 enrich。
     */
    @Synchronized
    fun scanAll(ctx: Context, progress: (String) -> Unit = {}, force: Boolean = false): ScanResult {
        val startedAt = System.currentTimeMillis()
        if (!force && !MediaIndex.needsInitialScan()) {
            val counts = MediaIndex.counts()
            lastScanAt = MediaIndex.lastScanAt()
            return ScanResult(audio = counts.audio, video = counts.video, photo = counts.photo)
        }
        if (!StorageUtils.hasStoragePermission(ctx)) {
            return ScanResult(ok = false, permissionDenied = true)
        }

        progress("正在查找媒体文件…")
        val files = ArrayList<File>(1024)
        val seen = HashSet<String>(2048)
        val scanRoots = roots().toMutableList()
        val photoTarget = StorageUtils.photoTarget(ctx)
        if (photoTarget.exists()) scanRoots.add(photoTarget)

        for (root in scanRoots) {
            StorageUtils.scan(root, allExts) { file ->
                if (seen.add(file.absolutePath)) {
                    files.add(file)
                    if (files.size % 100 == 0) progress("已找到 ${files.size} 个文件…")
                }
            }
        }
        progress("已找到 ${files.size} 个文件，正在写入索引…")

        MediaIndex.beginFullScan()
        val snapshot = MediaIndex.snapshot()
        val presence = ArrayList<String>(files.size)
        val newAudio = ArrayList<AudioTrack>()
        val newVideo = ArrayList<VideoItem>()
        val newPhotos = ArrayList<PhotoItem>()
        var reused = 0
        val storeDates = mediaStorePhotoDates(ctx)

        try {
            for ((position, file) in files.withIndex()) {
                val path = file.absolutePath
                presence.add(path)
                val old = snapshot[path]
                if (old != null && old.size == file.length() && old.modified == file.lastModified()) {
                    reused++
                } else {
                    if (old != null) {
                        ThumbCache.delete(old.thumbPath)
                        ThumbCache.delete(old.posterPath)
                    }
                    when (kindOf(file)) {
                        "audio" -> newAudio.add(audioSkeleton(file))
                        "video" -> newVideo.add(videoSkeleton(file))
                        "photo" -> newPhotos.add(photoSkeleton(file, storeDates))
                    }
                }
                if ((position + 1) % 100 == 0) {
                    progress("正在整理 ${position + 1}/${files.size}…")
                }
            }
            MediaIndex.applyFullScan(presence, newAudio, newVideo, newPhotos) { done, total ->
                if (done % 200 == 0 || done == total) progress("正在写入索引 $done/$total…")
            }
            MediaIndex.finishFullScan()
            // 已删除的原图：行清掉，同时删掉它对应的本地缩略图 / 应用内封面
            MediaIndex.purgeMissing().forEach { ThumbCache.delete(it) }
        } catch (t: Throwable) {
            MediaIndex.abortFullScan()
            App.logWarn("媒体索引全量扫描失败", t)
            return ScanResult(ok = false, elapsedMs = System.currentTimeMillis() - startedAt)
        }

        val counts = MediaIndex.counts()
        audioCache = null
        videoCache = null
        photoCache = null
        lastScanAt = MediaIndex.lastScanAt()
        Prefs.lastScanAt = lastScanAt
        // 索引已经稳定，顺手清掉换 key 后遗留的旧缩略图（只删 5 分钟前的孤儿文件）
        runCatching { ThumbCache.pruneOrphans(MediaIndex.thumbPaths()) }
        generation++
        prefetchMissingThumbs(ctx)
        val elapsed = System.currentTimeMillis() - startedAt
        App.log(
            "media index scan done: audio=${counts.audio} video=${counts.video} photo=${counts.photo} " +
                "reused=$reused added=${newAudio.size + newVideo.size + newPhotos.size} elapsed=${elapsed}ms"
        )
        return ScanResult(
            audio = counts.audio,
            video = counts.video,
            photo = counts.photo,
            added = newAudio.size + newVideo.size + newPhotos.size,
            reused = reused,
            elapsedMs = elapsed
        )
    }

    /** 首次进入页面时补建索引；已经扫过就直接查库 */
    private fun ensureIndexed(ctx: Context, progress: (String) -> Unit) {
        if (MediaIndex.needsInitialScan()) {
            progress("首次建立媒体索引…")
            scanAll(ctx, progress)
        }
    }

    /**
     * 扫描完成后在后台把还没有缩略图的照片/视频补齐：
     * 单线程、最小优先级、一次只解一张图，写完立即回收，峰值内存只有一个缩略图。
     * 生成失败或中途退出时库里仍是 NULL，下次扫描会继续补，不会出现只写一半的脏状态。
     */
    private val thumbPrefetching = AtomicBoolean(false)

    private fun prefetchMissingThumbs(ctx: Context) {
        if (!thumbPrefetching.compareAndSet(false, true)) return
        val appCtx = ctx.applicationContext
        Thread({
            try {
                val targets = runCatching { MediaIndex.missingThumbTargets() }.getOrDefault(emptyList())
                if (targets.isEmpty()) return@Thread
                val density = appCtx.resources.displayMetrics.density
                val photoW = (320f * density).toInt()
                val photoH = (210f * density).toInt()
                val videoW = (430f * density).toInt()
                val videoH = (240f * density).toInt()
                val startedAt = System.currentTimeMillis()
                var generated = 0
                var failed = 0
                for ((index, target) in targets.withIndex()) {
                    val ok = if (target.kind == "video") {
                        ImageLoader.prefetchThumb("video", target.path, videoW, videoH, 5_000L)
                    } else {
                        ImageLoader.prefetchThumb("photo", target.path, photoW, photoH)
                    }
                    if (ok) generated++ else failed++
                    if ((index + 1) % 200 == 0) {
                        App.log("thumb prefetch ${index + 1}/${targets.size} ok=$generated failed=$failed")
                    }
                    // 大批量补图时让出一点 CPU，避免和滚动/焦点动画抢时间片
                    if ((index + 1) % 25 == 0) {
                        runCatching { Thread.sleep(8) }
                    }
                }
                App.log(
                    "thumb prefetch done: total=${targets.size} ok=$generated failed=$failed " +
                        "elapsed=${System.currentTimeMillis() - startedAt}ms"
                )
            } catch (t: Throwable) {
                App.logWarn("后台生成缩略图失败", t)
            } finally {
                thumbPrefetching.set(false)
            }
        }, "mytv-thumb-prefetch").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    fun audio(ctx: Context, force: Boolean = false, progress: (String) -> Unit = {}): List<AudioTrack> {
        val cached = audioCache
        if (!force && cached != null) return cached
        val startedAt = System.currentTimeMillis()
        ensureIndexed(ctx, progress)
        val list = runCatching { MediaIndex.audio() }.getOrElse {
            App.logWarn("读取音乐索引失败", it)
            emptyList()
        }
        audioCache = list
        lastScanAt = MediaIndex.lastScanAt()
        App.log("media query audio count=${list.size} took=${System.currentTimeMillis() - startedAt}ms")
        return list
    }

    /** 读取单首歌曲的标签；只在列表可见项/当前播放项上调用，避免全库逐个开文件 */
    fun enrichAudio(track: AudioTrack): AudioTrack {
        if (track.metadataLoaded) return track
        val file = File(track.path)
        if (!file.exists()) return track
        val enriched = runCatching { readAudio(file) }.getOrDefault(track)
        if (enriched.metadataLoaded) {
            runCatching { MediaIndex.upsertAudio(enriched) }
            audioCache = null
        }
        return enriched
    }

    private fun audioSkeleton(file: File): AudioTrack {
        val base = file.nameWithoutExtension
        val parsed = MusicMeta.parseFileName(base)
        return AudioTrack(
            path = file.absolutePath,
            title = parsed.title.ifBlank { base },
            artist = MusicMeta.displayArtist(parsed.artist),
            album = "",
            durationMs = 0L,
            artworkPath = findArtwork(file)?.absolutePath,
            tagTitle = null,
            tagArtist = null,
            metadataLoaded = false
        )
    }

    fun videos(ctx: Context, force: Boolean = false, progress: (String) -> Unit = {}): List<VideoItem> {
        val cached = videoCache
        if (!force && cached != null) return cached
        val startedAt = System.currentTimeMillis()
        ensureIndexed(ctx, progress)
        val list = runCatching { MediaIndex.videos() }.getOrElse {
            App.logWarn("读取视频索引失败", it)
            emptyList()
        }
        videoCache = list
        lastScanAt = MediaIndex.lastScanAt()
        App.log("media query video count=${list.size} took=${System.currentTimeMillis() - startedAt}ms")
        return list
    }

    fun enrichVideo(item: VideoItem): VideoItem {
        if (item.metadataLoaded) return item
        val file = File(item.path)
        if (!file.exists()) return item
        val enriched = runCatching {
            videoOf(file).copy(posterPath = findArtwork(file)?.absolutePath ?: item.posterPath)
        }.getOrDefault(item)
        if (enriched.metadataLoaded) {
            runCatching { MediaIndex.upsertVideo(enriched) }
            videoCache = null
        }
        return enriched
    }

    private fun videoSkeleton(file: File): VideoItem {
        val parsed = VideoScraper.parseName(file.nameWithoutExtension)
        return VideoItem(
            path = file.absolutePath,
            title = parsed.title,
            year = parsed.year,
            season = parsed.season,
            episode = parsed.episode,
            durationMs = 0L,
            width = 0,
            height = 0,
            size = file.length(),
            dateModified = file.lastModified(),
            posterPath = findArtwork(file)?.absolutePath,
            metadataLoaded = false
        )
    }

    fun photos(ctx: Context, force: Boolean = false, progress: (String) -> Unit = {}): List<PhotoItem> {
        val cached = photoCache
        if (!force && cached != null) return cached
        val startedAt = System.currentTimeMillis()
        ensureIndexed(ctx, progress)
        val list = runCatching { MediaIndex.photos() }.getOrElse {
            App.logWarn("读取相册索引失败", it)
            emptyList()
        }
        photoCache = list
        lastScanAt = MediaIndex.lastScanAt()
        App.log("media query photo count=${list.size} took=${System.currentTimeMillis() - startedAt}ms")
        return list
    }

    private fun photoSkeleton(file: File, storeDates: Map<String, Long>): PhotoItem {
        return PhotoItem(
            path = file.absolutePath,
            name = file.name,
            size = file.length(),
            lastModified = file.lastModified(),
            takenAt = storeDates[file.absolutePath]
                ?: parseDateFromName(file.name)
                ?: file.lastModified()
        )
    }

    /**
     * 上传完成后的单文件增量索引：只解析这一个文件并 upsert，不再触发页面全量重扫。
     * 照片上传可能刚写盘、MediaStore 还没记录，日期依次退回文件名和修改时间。
     */
    fun indexFile(ctx: Context, file: File): Boolean {
        if (!file.exists() || file.length() <= 0) return false
        return runCatching {
            when (kindOf(file)) {
                "audio" -> MediaIndex.upsertAudio(readAudio(file))
                "video" -> {
                    MediaIndex.upsertVideo(videoOf(file))
                    val density = ctx.resources.displayMetrics.density
                    val w = (430f * density).toInt()
                    val h = (240f * density).toInt()
                    val thumb = ThumbCache.fileFor("video", file.absolutePath, w, h, 5000L)
                    if (!thumb.exists() || thumb.length() <= 0L) {
                        ImageLoader.frameToFile(file.absolutePath, thumb, 5_000L, w)
                    }
                    if (thumb.exists()) MediaIndex.updateThumb(file.absolutePath, thumb.absolutePath)
                }
                "photo" -> {
                    val storeDates = mediaStorePhotoDates(ctx)
                    MediaIndex.upsertPhoto(photoSkeleton(file, storeDates))
                    val density = ctx.resources.displayMetrics.density
                    ThumbCache.generatePhoto(
                        file.absolutePath,
                        (320f * density).toInt(),
                        (210f * density).toInt()
                    )?.let {
                        MediaIndex.updateThumb(file.absolutePath, it.absolutePath)
                    }
                }
                else -> return false
            }
            audioCache = null
            videoCache = null
            photoCache = null
            generation++
            App.log("media index incremental: ${file.absolutePath}")
            true
        }.getOrElse {
            App.logWarn("单文件写入索引失败: ${file.absolutePath}", it)
            false
        }
    }

    fun removePath(path: String) {
        val stale = runCatching { MediaIndex.deletePath(path) }.getOrDefault(emptyList())
        stale.forEach { ThumbCache.delete(it) }
        audioCache = null
        videoCache = null
        photoCache = null
        generation++
    }

    /** 清除刮削出的时长/海报，但保留文件索引；页面下一次可见项会重新 enrich */
    fun clearScrapedMetadata() {
        runCatching { MediaIndex.clearVideoMetadata() }
        videoCache = null
        generation++
    }

    /** 一次查询拿到已索引照片的拍摄时间；U 盘未进媒体库时返回空，自动退回文件名/修改时间 */
    private fun mediaStorePhotoDates(ctx: Context): Map<String, Long> {
        val map = HashMap<String, Long>()
        try {
            val projection = arrayOf(
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED
            )
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val dataIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val takenIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val path = if (dataIndex >= 0) cursor.getString(dataIndex) else null
                    if (path.isNullOrBlank()) continue
                    val taken = if (takenIndex >= 0) cursor.getLong(takenIndex) else 0L
                    val modified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else 0L
                    val millis = when {
                        taken > 0L -> taken
                        modified > 0L -> modified * 1000L
                        else -> 0L
                    }
                    if (millis > 0L) map[path] = millis
                }
            }
        } catch (t: Throwable) {
            App.logWarn("MediaStore 照片日期查询失败", t)
        }
        return map
    }

    private fun readExifDate(file: File): Long? = null

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

    private fun kindOf(file: File): String {
        val ext = file.extension.lowercase()
        return when {
            ext in audioExts -> "audio"
            ext in videoExts -> "video"
            ext in imageExts -> "photo"
            else -> ""
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
            tagArtist = tagArtist,
            metadataLoaded = true
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
            posterPath = findArtwork(file)?.absolutePath,
            metadataLoaded = true
        )
    }

    fun videoByPath(ctx: Context, path: String): VideoItem? {
        val file = File(path)
        if (!file.exists()) return null
        videoCache?.firstOrNull { it.path == path }?.let {
            if (it.metadataLoaded) return it
        }
        val fromDb = videoCache?.firstOrNull { it.path == path }
        return runCatching { videoOf(file).copy(posterPath = fromDb?.posterPath) }.getOrDefault(fromDb ?: videoOf(file))
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
