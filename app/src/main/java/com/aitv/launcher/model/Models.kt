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
    val tagArtist: String? = null
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
    val source: String? = null
)

data class PhotoItem(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    /** 实际拍摄时间：EXIF → 文件名日期 → 文件修改时间 */
    val takenAt: Long = lastModified
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
    val index: Int = 0
)

data class Bookmark(
    val name: String,
    val url: String
)

data class Playlist(
    val name: String,
    val paths: List<String>
)
