package com.aitv.launcher.media

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aitv.launcher.App
import com.aitv.launcher.model.AudioTrack
import com.aitv.launcher.model.PhotoItem
import com.aitv.launcher.model.VideoItem
import com.aitv.launcher.model.LiveChannel

/**
 * 媒体索引库。
 *
 * 只存"索引/元数据"，媒体文件、封面和台标仍然留在本地文件系统：
 * - media_index：歌曲、视频、照片，一行一个文件，按 path 去重；
 * - live_channel：直播频道（台标路径仍指向 files/live_logos）；
 * - meta：扫描时间、扫描中标志等。
 *
 * 页面只查库，不再遍历文件；上传时按单个文件 upsert；设置里手动扫描才全量重建。
 */
object MediaIndex {

    private const val DB_NAME = "mytv_index.db"
    private const val DB_VERSION = 2

    data class Counts(val audio: Int, val video: Int, val photo: Int) {
        val total: Int get() = audio + video + photo
    }

    /** 还没有本地缩略图的照片/视频，用于扫描后台补齐 */
    data class ThumbTarget(val path: String, val kind: String)

    data class SnapshotRow(
        val size: Long,
        val modified: Long,
        val metadataLoaded: Boolean,
        val thumbPath: String?,
        val posterPath: String?
    )

    private val writeLock = Any()

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE media_index (
                    path TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT,
                    album TEXT,
                    tag_title TEXT,
                    tag_artist TEXT,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    year INTEGER NOT NULL DEFAULT 0,
                    season INTEGER NOT NULL DEFAULT 0,
                    episode INTEGER NOT NULL DEFAULT 0,
                    width INTEGER NOT NULL DEFAULT 0,
                    height INTEGER NOT NULL DEFAULT 0,
                    size INTEGER NOT NULL DEFAULT 0,
                    modified INTEGER NOT NULL DEFAULT 0,
                    taken_at INTEGER NOT NULL DEFAULT 0,
                    poster_path TEXT,
                    thumb_path TEXT,
                    metadata_loaded INTEGER NOT NULL DEFAULT 0,
                    missing INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_media_kind_missing ON media_index(kind, missing)")
            db.execSQL(
                """
                CREATE TABLE live_channel (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    url TEXT NOT NULL,
                    group_name TEXT NOT NULL DEFAULT '',
                    logo TEXT,
                    logo_path TEXT,
                    logo_tries INTEGER NOT NULL DEFAULT 0,
                    sort_index INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE meta (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 索引库随时可由全量扫描重建，版本升级直接重建即可
            db.execSQL("DROP TABLE IF EXISTS media_index")
            db.execSQL("DROP TABLE IF EXISTS live_channel")
            db.execSQL("DROP TABLE IF EXISTS meta")
            onCreate(db)
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            // 老库补列：台标尝试次数，避免升级数据库时把频道和台标缓存一起丢掉
            runCatching {
                db.execSQL("ALTER TABLE live_channel ADD COLUMN logo_tries INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    private val helper: Db by lazy { Db(App.ctx) }

    fun meta(key: String): String? = read { db ->
        db.query("meta", arrayOf("value"), "key=?", arrayOf(key), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun putMeta(key: String, value: String) {
        write { db ->
            val values = ContentValues().apply {
                put("key", key)
                put("value", value)
            }
            db.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun lastScanAt(): Long = meta("last_scan_at")?.toLongOrNull() ?: 0L

    fun isScanRunning(): Boolean = meta("scanning") == "1"

    fun needsInitialScan(): Boolean = isScanRunning() || lastScanAt() <= 0L

    /** 全量扫描开始：旧行先标记为 missing，页面查询会立即得到空列表，不会展示已删除文件 */
    fun beginFullScan() {
        write { db ->
            db.execSQL("UPDATE media_index SET missing=1")
            putMetaInternal(db, "scanning", "1")
            putMetaInternal(db, "last_scan_at", "0")
        }
    }

    /** 扫描正常结束：未重新标记 missing 的行就是已经消失的文件 */
    fun finishFullScan() {
        write { db ->
            putMetaInternal(db, "scanning", "0")
            putMetaInternal(db, "last_scan_at", System.currentTimeMillis().toString())
        }
    }

    fun abortFullScan() {
        write { db -> putMetaInternal(db, "scanning", "0") }
    }

    fun snapshot(): Map<String, SnapshotRow> = read { db ->
        val map = HashMap<String, SnapshotRow>()
        db.query(
            "media_index",
            arrayOf("path", "size", "modified", "metadata_loaded", "thumb_path", "poster_path"),
            null,
            null,
            null,
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = SnapshotRow(
                    size = c.getLong(1),
                    modified = c.getLong(2),
                    metadataLoaded = c.getInt(3) == 1,
                    thumbPath = c.getString(4),
                    posterPath = c.getString(5)
                )
            }
        }
        map
    }

    /**
     * 一次事务写入全量扫描结果：presence 为仍存在的路径；newAudio/newVideo/newPhotos 为新增或变更项。
     * 未出现在 presence 里的旧行继续 missing=1，便等于删除。
     */
    fun applyFullScan(
        presence: List<String>,
        newAudio: List<AudioTrack>,
        newVideo: List<VideoItem>,
        newPhotos: List<PhotoItem>,
        onProgress: (Int, Int) -> Unit
    ) {
        val total = presence.size
        write { db ->
            db.beginTransaction()
            try {
                var done = 0
                for (path in presence) {
                    db.execSQL("UPDATE media_index SET missing=0 WHERE path=?", arrayOf(path))
                    done++
                    if (done % 100 == 0) onProgress(done, total)
                }
                for (track in newAudio) upsertAudioInternal(db, track)
                for (item in newVideo) upsertVideoInternal(db, item, resetThumb = true)
                for (photo in newPhotos) upsertPhotoInternal(db, photo)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun markPresent(path: String) {
        write { db -> db.execSQL("UPDATE media_index SET missing=0 WHERE path=?", arrayOf(path)) }
    }

    fun upsertAudio(track: AudioTrack) = write { db -> upsertAudioInternal(db, track) }

    fun upsertVideo(item: VideoItem) = write { db -> upsertVideoInternal(db, item) }

    fun upsertPhoto(photo: PhotoItem) = write { db -> upsertPhotoInternal(db, photo) }

    fun audio(): List<AudioTrack> = read { db ->
        val list = ArrayList<AudioTrack>()
        db.query(
            "media_index",
            arrayOf(
                "path", "title", "artist", "album", "tag_title", "tag_artist",
                "duration_ms", "poster_path", "metadata_loaded"
            ),
            "kind=? AND missing=0",
            arrayOf("audio"),
            null,
            null,
            "title COLLATE NOCASE ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                list.add(
                    AudioTrack(
                        path = c.getString(0),
                        title = c.getString(1) ?: "",
                        artist = c.getString(2) ?: "",
                        album = c.getString(3) ?: "",
                        durationMs = c.getLong(6),
                        artworkPath = c.getString(7),
                        tagTitle = c.getString(4),
                        tagArtist = c.getString(5),
                        metadataLoaded = c.getInt(8) == 1
                    )
                )
            }
        }
        list
    }

    fun videos(): List<VideoItem> = read { db ->
        val list = ArrayList<VideoItem>()
        db.query(
            "media_index",
            arrayOf(
                "path", "title", "year", "season", "episode", "duration_ms",
                "width", "height", "size", "modified", "poster_path", "metadata_loaded", "thumb_path"
            ),
            "kind=? AND missing=0",
            arrayOf("video"),
            null,
            null,
            "modified DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                list.add(
                    VideoItem(
                        path = c.getString(0),
                        title = c.getString(1) ?: "",
                        year = c.getInt(2).takeIf { it > 0 },
                        season = c.getInt(3).takeIf { it > 0 },
                        episode = c.getInt(4).takeIf { it > 0 },
                        durationMs = c.getLong(5),
                        width = c.getInt(6),
                        height = c.getInt(7),
                        size = c.getLong(8),
                        dateModified = c.getLong(9),
                        posterPath = c.getString(10),
                        metadataLoaded = c.getInt(11) == 1,
                        thumbPath = c.getString(12)
                    )
                )
            }
        }
        list
    }

    fun photos(): List<PhotoItem> = read { db ->
        val list = ArrayList<PhotoItem>()
        db.query(
            "media_index",
            arrayOf("path", "title", "size", "modified", "taken_at", "thumb_path"),
            "kind=? AND missing=0",
            arrayOf("photo"),
            null,
            null,
            "taken_at DESC, modified DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                list.add(
                    PhotoItem(
                        path = c.getString(0),
                        name = c.getString(1) ?: "",
                        size = c.getLong(2),
                        lastModified = c.getLong(3),
                        takenAt = c.getLong(4),
                        thumbPath = c.getString(5)
                    )
                )
            }
        }
        list
    }

    fun counts(): Counts = read { db ->
        var audio = 0
        var video = 0
        var photo = 0
        db.query(
            "media_index",
            arrayOf("kind", "COUNT(*)"),
            "missing=0",
            null,
            "kind",
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val value = c.getInt(1)
                when (c.getString(0)) {
                    "audio" -> audio = value
                    "video" -> video = value
                    "photo" -> photo = value
                }
            }
        }
        Counts(audio, video, photo)
    }

    /** 删除索引并返回需要一起删除的本地缓存文件（缩略图、应用内封面） */
    fun deletePath(path: String): List<String> {
        val caches = read { db ->
            val list = ArrayList<String>(2)
            db.query(
                "media_index",
                arrayOf("thumb_path", "poster_path"),
                "path=?",
                arrayOf(path),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { list.add(it) }
                    c.getString(1)?.let { list.add(it) }
                }
            }
            list
        }
        write { db -> db.delete("media_index", "path=?", arrayOf(path)) }
        return caches
    }

    /** 全量扫描后清掉 missing 行，并返回这些行对应的本地缓存路径 */
    fun purgeMissing(): List<String> {
        return write { db ->
            val caches = ArrayList<String>()
            db.query(
                "media_index",
                arrayOf("thumb_path", "poster_path"),
                "missing=1",
                null,
                null,
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.let { caches.add(it) }
                    c.getString(1)?.let { caches.add(it) }
                }
            }
            db.delete("media_index", "missing=1", null)
            caches
        }
    }

    /** 回写缩略图路径；返回 false 表示这条媒体已经不在索引里（可能刚被删除） */
    fun updateThumb(path: String, thumbPath: String): Boolean {
        var updated = 0
        var oldThumb: String? = null
        write { db ->
            db.query(
                "media_index",
                arrayOf("thumb_path"),
                "path=?",
                arrayOf(path),
                null,
                null,
                null
            )?.use { c -> if (c.moveToFirst()) oldThumb = c.getString(0) }
            val values = ContentValues().apply { put("thumb_path", thumbPath) }
            updated = db.update("media_index", values, "path=?", arrayOf(path))
        }
        // 同一个源文件换了缩略图 key（尺寸/时间点变了）时，旧文件不留在磁盘上
        val previous = oldThumb
        if (updated > 0 && !previous.isNullOrBlank() && previous != thumbPath) {
            ThumbCache.delete(previous)
        }
        return updated > 0
    }

    fun clearLiveChannels() {
        write { db -> db.delete("live_channel", null, null) }
    }

    fun updateLiveLogo(logoUrl: String, path: String) {
        if (logoUrl.isBlank()) return
        write { db ->
            val values = ContentValues().apply {
                put("logo_path", path)
                put("logo_tries", 0)
            }
            db.update("live_channel", values, "logo=?", arrayOf(logoUrl))
        }
    }

    /** 已经失败过的台标尝试次数：logoUrl -> 次数（同一地址的频道共用一份计数） */
    fun logoTries(): Map<String, Int> = read { db ->
        val map = HashMap<String, Int>()
        db.rawQuery(
            "SELECT logo, logo_tries FROM live_channel WHERE logo_tries>0 AND logo IS NOT NULL AND logo<>''",
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val url = c.getString(0) ?: continue
                map[url] = c.getInt(1)
            }
        }
        map
    }

    /** 台标下载失败一次：计数加一并返回最新次数 */
    fun bumpLogoTries(logoUrl: String): Int {
        if (logoUrl.isBlank()) return 0
        return write { db ->
            val current = db.rawQuery(
                "SELECT MAX(logo_tries) FROM live_channel WHERE logo=?",
                arrayOf(logoUrl)
            )?.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } ?: 0
            val next = current + 1
            val values = ContentValues().apply { put("logo_tries", next) }
            db.update("live_channel", values, "logo=?", arrayOf(logoUrl))
            next
        }
    }

    /** 清除刮削元数据但保留文件索引，下次进页面再按可见项重新取时长/海报 */
    fun clearVideoMetadata() {
        write { db ->
            val values = ContentValues().apply {
                put("metadata_loaded", 0)
                putNull("poster_path")
            }
            db.update("media_index", values, "kind=?", arrayOf("video"))
        }
    }

    fun saveLiveChannels(channels: List<LiveChannel>) {
        write { db ->
            // 刷新源时解析结果里的 logoPath 都是 null；刷新前先按 URL 记住已下载的本地台标，
            // 避免一次刷新把 SQLite 里的 logo_path 全部清空。台标失败次数按 logo 地址同样保留。
            val oldLogos = HashMap<String, String>()
            val oldTries = HashMap<String, Int>()
            db.query(
                "live_channel",
                arrayOf("url", "logo", "logo_path", "logo_tries"),
                null,
                null,
                null,
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val url = c.getString(0) ?: continue
                    val logo = c.getString(1)?.trim().orEmpty()
                    val path = c.getString(2)
                    if (!path.isNullOrBlank()) oldLogos[url] = path
                    val tries = c.getInt(3)
                    if (logo.isNotEmpty() && tries > 0) {
                        oldTries[logo] = maxOf(oldTries[logo] ?: 0, tries)
                    }
                }
            }

            db.beginTransaction()
            try {
                db.delete("live_channel", null, null)
                channels.forEachIndexed { index, ch ->
                    val localLogo = ch.logoPath?.takeIf { it.isNotBlank() } ?: oldLogos[ch.url]
                    val values = ContentValues().apply {
                        put("name", ch.name)
                        put("url", ch.url)
                        put("group_name", ch.group)
                        put("logo", ch.logo)
                        put("logo_path", localLogo)
                        put("logo_tries", ch.logo?.trim()?.let { oldTries[it] } ?: 0)
                        put("sort_index", index)
                    }
                    db.insertWithOnConflict("live_channel", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                putMetaInternal(db, "live_updated_at", System.currentTimeMillis().toString())
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** 当前索引里所有有效的本地缩略图路径，用于清理换 key 后留下的旧文件 */
    fun thumbPaths(): Set<String> = read { db ->
        val paths = HashSet<String>(2048)
        db.rawQuery(
            "SELECT thumb_path FROM media_index WHERE thumb_path IS NOT NULL AND thumb_path<>''",
            null
        )?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { paths.add(it) }
        }
        paths
    }

    /** 照片/视频里还没有缩略图的行；按拍摄时间或修改时间排序，先补最近看到的 */
    fun missingThumbTargets(): List<ThumbTarget> = read { db ->
        val list = ArrayList<ThumbTarget>()
        db.rawQuery(
            "SELECT path, kind FROM media_index " +
                "WHERE (kind='photo' OR kind='video') " +
                "AND (thumb_path IS NULL OR thumb_path='') " +
                "ORDER BY CASE WHEN kind='video' THEN 0 ELSE 1 END, taken_at DESC",
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                val kind = c.getString(1) ?: continue
                list.add(ThumbTarget(path, kind))
            }
        }
        list
    }

    fun liveChannels(): List<LiveChannel> = read { db ->
        val list = ArrayList<LiveChannel>()
        db.query(
            "live_channel",
            arrayOf("name", "url", "group_name", "logo", "sort_index", "logo_path"),
            null,
            null,
            null,
            null,
            "sort_index ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                list.add(
                    LiveChannel(
                        name = c.getString(0) ?: "",
                        url = c.getString(1) ?: "",
                        group = c.getString(2) ?: "未分组",
                        logo = c.getString(3),
                        index = c.getInt(4),
                        logoPath = c.getString(5)
                    )
                )
            }
        }
        list
    }

    fun liveCount(): Int = read { db ->
        db.rawQuery("SELECT COUNT(*) FROM live_channel", null)?.use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        } ?: 0
    }

    private fun upsertAudioInternal(db: SQLiteDatabase, track: AudioTrack) {
        val values = ContentValues().apply {
            put("path", track.path)
            put("kind", "audio")
            put("title", track.title)
            put("artist", track.artist)
            put("album", track.album)
            put("tag_title", track.tagTitle)
            put("tag_artist", track.tagArtist)
            put("duration_ms", track.durationMs)
            put("size", java.io.File(track.path).length())
            put("modified", java.io.File(track.path).lastModified())
            put("poster_path", track.artworkPath)
            put("metadata_loaded", if (track.metadataLoaded) 1 else 0)
            put("missing", 0)
        }
        db.insertWithOnConflict("media_index", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun upsertVideoInternal(db: SQLiteDatabase, item: VideoItem, resetThumb: Boolean = false) {
        val values = ContentValues().apply {
            put("path", item.path)
            put("kind", "video")
            put("title", item.title)
            put("year", item.year ?: 0)
            put("season", item.season ?: 0)
            put("episode", item.episode ?: 0)
            put("duration_ms", item.durationMs)
            put("width", item.width)
            put("height", item.height)
            put("size", item.size)
            put("modified", item.dateModified)
            put("poster_path", item.posterPath)
            put("metadata_loaded", if (item.metadataLoaded) 1 else 0)
            put("missing", 0)
        }
        if (resetThumb) values.putNull("thumb_path")
        val updated = if (resetThumb) 0 else db.update(
            "media_index",
            values,
            "path=? AND kind=?",
            arrayOf(item.path, "video")
        )
        if (updated == 0) {
            if (!resetThumb) values.put("thumb_path", item.thumbPath)
            db.insertWithOnConflict("media_index", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun upsertPhotoInternal(db: SQLiteDatabase, photo: PhotoItem) {
        val values = ContentValues().apply {
            put("path", photo.path)
            put("kind", "photo")
            put("title", photo.name)
            put("size", photo.size)
            put("modified", photo.lastModified)
            put("taken_at", photo.takenAt)
            put("metadata_loaded", 1)
            put("missing", 0)
        }
        db.insertWithOnConflict("media_index", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun putMetaInternal(db: SQLiteDatabase, key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.insertWithOnConflict("meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private inline fun <T> read(block: (SQLiteDatabase) -> T): T {
        synchronized(writeLock) {
            return block(helper.readableDatabase)
        }
    }

    private inline fun <T> write(block: (SQLiteDatabase) -> T): T {
        synchronized(writeLock) {
            return block(helper.writableDatabase)
        }
    }
}
