package com.aitv.launcher.model

data class AudioTrack(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkPath: String? = null,
    /** 音频标签里真正解析到的标题；为空表示标题是文件名回退 */
    val tagTitle: String? = null,
    /** 音频标签里真正解析到的艺术家；为空表示艺术家是文件名回退 */
    val tagArtist: String? = null,
    /** false 表示当前只有文件名信息，时长/内嵌封面等标签尚未读取 */
    val metadataLoaded: Boolean = false
)

data class VideoItem(
    val path: String,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0L,
    val dateModified: Long = 0L,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val rating: Float? = null,
    val genre: String? = null,
    val source: String? = null,
    /** false 表示时长/分辨率等媒体头信息还没读取，列表可先显示 */
    val metadataLoaded: Boolean = false,
    /** 本地缩略图缓存路径；为空表示还没生成 */
    val thumbPath: String? = null
)

data class PhotoItem(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    /** 实际拍摄时间：EXIF → 文件名日期 → 文件修改时间 */
    val takenAt: Long = lastModified,
    /** 本地缩略图缓存路径；为空表示还没生成 */
    val thumbPath: String? = null
)

data class ApkFile(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long
)

data class LiveChannel(
    val name: String,
    val url: String,
    val group: String = "未分组",
    val logo: String? = null,
    val index: Int = 0,
    /** 台标本地缓存路径；为空时按 logo URL 哈希定位 */
    val logoPath: String? = null
)

data class Bookmark(
    val name: String,
    val url: String
)

data class Playlist(
    val name: String,
    val paths: List<String>
)
