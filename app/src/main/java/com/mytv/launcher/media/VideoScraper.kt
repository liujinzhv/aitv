package com.mytv.launcher.media

import android.content.Context
import android.util.Xml
import com.mytv.launcher.Prefs
import com.mytv.launcher.model.VideoItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ParsedName(
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?
)

/**
 * 视频信息刮削：
 * 1) 同目录 NFO（Kodi 规范）；2) 同目录 poster/fanart 图片；
 * 3) 配置了 TMDB Key 时在线搜索并缓存海报；
 * 4) 都没有时从视频里抽一帧当封面。
 */
object VideoScraper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val noiseRegex = Regex(
        "(?i)\\b(2160p|1080p|720p|480p|bluray|blu-ray|web-dl|webrip|hdtv|remux|hdr|hdr10|dolby|" +
            "dv|x265|x264|h264|h265|hevc|av1|aac|ac3|eac3|dts|truehd|atmos|10bit|8bit|" +
            "chs|cht|eng|proper|repack|extended|uncut)\\b"
    )

    private val cnNoiseRegex = Regex("(国语|粤语|中字|字幕|高清|蓝光|收藏版|导演剪辑版|加长版|完整版)")

    fun parseName(raw: String): ParsedName {
        var name = raw

        val se = Regex("(?i)S(\\d{1,2})[ ._-]?E(\\d{1,3})").find(name)
        val season = se?.groupValues?.get(1)?.toIntOrNull()
        val episode = se?.groupValues?.get(2)?.toIntOrNull()

        val cnSeason = Regex("第\\s*(\\d{1,2})\\s*季").find(name)?.groupValues?.get(1)?.toIntOrNull()
        val cnEp = Regex("第\\s*(\\d{1,3})\\s*[集话期]").find(name)?.groupValues?.get(1)?.toIntOrNull()

        val yearMatch = Regex("(?<![0-9])(19\\d{2}|20\\d{2})(?![0-9])").find(name)
        val year = yearMatch?.value?.toIntOrNull()

        var title = if (yearMatch != null && yearMatch.range.first > 0) {
            name.substring(0, yearMatch.range.first)
        } else {
            name
        }

        title = title.replace(Regex("\\[[^]]*]"), " ")
        title = title.replace(Regex("\\([^)]*\\)"), " ")
        title = noiseRegex.replace(title, " ")
        title = cnNoiseRegex.replace(title, " ")
        title = title.replace(Regex("[._]"), " ")
        title = title.replace(Regex("\\s+"), " ").trim().trim('-', '[', ']', '(', ')', '【', '】')

        if (title.isBlank()) title = raw.trim()
        return ParsedName(title, year, season ?: cnSeason, episode ?: cnEp)
    }

    fun enrich(ctx: Context, item: VideoItem, force: Boolean = false): VideoItem {
        if (!force) {
            readMeta(ctx, item)?.let { return it }
        }

        val file = File(item.path)
        var title = item.title
        var year = item.year
        var overview = item.overview
        var rating = item.rating
        var genre = item.genre
        var poster: File? = item.posterPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
        var backdrop: File? = null
        var source = "文件名解析"

        if (poster != null) source = "本地海报"

        val dir = file.parentFile
        val base = file.nameWithoutExtension
        if (dir != null) {
            backdrop = listOf("fanart.jpg", "fanart.png", "backdrop.jpg", "backdrop.png", "$base-fanart.jpg")
                .map { File(dir, it) }
                .firstOrNull { it.exists() && it.length() > 0 }

            val nfoFile = listOf("$base.nfo", "movie.nfo", "tvshow.nfo")
                .map { File(dir, it) }
                .firstOrNull { it.exists() && it.length() > 0 }
            val nfo = nfoFile?.let { readNfo(it) }
            if (nfo != null) {
                nfo.title?.takeIf { it.isNotBlank() }?.let { title = it }
                nfo.plot?.takeIf { it.isNotBlank() }?.let { overview = it }
                nfo.rating?.let { rating = it }
                nfo.genre?.takeIf { it.isNotBlank() }?.let { genre = it }
                year = year ?: nfo.year
                nfo.poster?.let { p -> resolveRelative(nfoFile, p)?.takeIf { it.exists() }?.let { poster = it } }
                nfo.fanart?.let { p -> resolveRelative(nfoFile, p)?.takeIf { it.exists() }?.let { backdrop = it } }
                source = "本地 NFO"
            }
        }

        if (Prefs.tmdbKey.isNotBlank() && (overview.isNullOrBlank() || poster == null)) {
            val isTv = item.season != null || item.episode != null
            val hit = tryTmdb(ctx, title, year, isTv)
            if (hit != null) {
                hit.title?.takeIf { it.isNotBlank() }?.let { title = it }
                hit.overview?.takeIf { it.isNotBlank() }?.let { overview = it }
                hit.year?.let { year = it }
                hit.rating?.let { rating = it }
                hit.genre?.takeIf { it.isNotBlank() }?.let { genre = it }
                hit.poster?.takeIf { it.exists() }?.let { poster = it }
                hit.backdrop?.takeIf { it.exists() }?.let { backdrop = it }
                source = "TMDB"
            }
        }

        if (poster == null) {
            val thumb = File(File(ctx.filesDir, "thumbs").apply { mkdirs() }, hash(item.path) + ".jpg")
            if (thumb.exists() && thumb.length() > 0 || ImageLoader.frameToFile(item.path, thumb, 5000, 640)) {
                poster = thumb
                source = "$source + 视频抽帧"
            }
        }

        val enriched = item.copy(
            title = title,
            year = year,
            overview = overview,
            rating = rating,
            genre = genre,
            posterPath = poster?.absolutePath,
            backdropPath = backdrop?.absolutePath,
            source = source
        )
        saveMeta(ctx, enriched)
        return enriched
    }

    private data class NfoData(
        val title: String?,
        val year: Int?,
        val plot: String?,
        val rating: Float?,
        val genre: String?,
        val poster: String?,
        val fanart: String?
    )

    private fun readNfo(file: File): NfoData? {
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            val map = HashMap<String, String>()
            val genres = mutableListOf<String>()
            FileInputStream(file).use { input ->
                parser.setInput(input, "UTF-8")
                var event = parser.eventType
                var tag = ""
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> tag = parser.name
                        XmlPullParser.TEXT -> {
                            val text = parser.text?.trim().orEmpty()
                            if (text.isNotEmpty()) {
                                if (tag == "genre") genres.add(text) else map.putIfAbsent(tag, text)
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            NfoData(
                title = map["title"] ?: map["originaltitle"],
                year = map["year"]?.toIntOrNull() ?: map["premiered"]?.take(4)?.toIntOrNull(),
                plot = map["plot"] ?: map["outline"],
                rating = (map["rating"] ?: map["votes"])?.toFloatOrNull(),
                genre = genres.joinToString(" / ").takeIf { it.isNotBlank() } ?: map["genre"],
                poster = map["poster"] ?: map["cover"] ?: map["thumb"],
                fanart = map["fanart"]
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun resolveRelative(nfo: File?, path: String): File? {
        val p = File(path)
        if (p.isAbsolute) return p
        val dir = nfo?.parentFile ?: return null
        return File(dir, path)
    }

    private data class TmdbHit(
        val title: String?,
        val overview: String?,
        val year: Int?,
        val rating: Float?,
        val genre: String?,
        val poster: File?,
        val backdrop: File?
    )

    private fun tryTmdb(ctx: Context, title: String, year: Int?, isTv: Boolean): TmdbHit? {
        val key = Prefs.tmdbKey.trim()
        if (key.isBlank() || title.isBlank()) return null
        return try {
            val type = if (isTv) "tv" else "movie"
            val query = URLEncoder.encode(title, "UTF-8")
            val yearParam = year?.let {
                if (isTv) "&first_air_date_year=$it" else "&year=$it"
            } ?: ""
            val searchUrl = "https://api.themoviedb.org/3/search/$type?api_key=$key" +
                "&language=zh-CN&query=$query$yearParam"
            val searchJson = httpJson(searchUrl) ?: return null
            val results = searchJson.optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val first = results.optJSONObject(0) ?: return null
            val id = first.optLong("id", 0L)
            if (id <= 0L) return null

            val detail = httpJson("https://api.themoviedb.org/3/$type/$id?api_key=$key&language=zh-CN")
                ?: first

            val nameField = if (isTv) "name" else "title"
            val dateField = if (isTv) "first_air_date" else "release_date"
            val displayTitle = detail.optString(nameField, first.optString(nameField, title))
            val overview = detail.optString("overview", "").takeIf { it.isNotBlank() }
            val date = detail.optString(dateField, "")
            val hitYear = date.take(4).toIntOrNull()
            val rating = detail.optDouble("vote_average", 0.0).takeIf { it > 0 }?.toFloat()

            val genres = mutableListOf<String>()
            detail.optJSONArray("genres")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.optString("name", "")?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
                }
            }

            val artworkDir = File(ctx.filesDir, "artwork").apply { mkdirs() }
            val posterPath = detail.optString("poster_path", "")
            val backdropPath = detail.optString("backdrop_path", "")

            val poster = if (posterPath.isNotBlank()) {
                val dest = File(artworkDir, hash("poster:$posterPath") + ".jpg")
                if (download("https://image.tmdb.org/t/p/w500$posterPath", dest)) dest else null
            } else null

            val backdrop = if (backdropPath.isNotBlank()) {
                val dest = File(artworkDir, hash("backdrop:$backdropPath") + ".jpg")
                if (download("https://image.tmdb.org/t/p/w780$backdropPath", dest)) dest else null
            } else null

            TmdbHit(
                title = displayTitle,
                overview = overview,
                year = hitYear,
                rating = rating,
                genre = genres.joinToString(" / ").takeIf { it.isNotBlank() },
                poster = poster,
                backdrop = backdrop
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun httpJson(url: String): JSONObject? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "MyTV/1.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                JSONObject(body)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun download(url: String, dest: File): Boolean {
        if (dest.exists() && dest.length() > 0) return true
        return try {
            val req = Request.Builder().url(url).header("User-Agent", "MyTV/1.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                FileOutputStream(dest).use { out -> body.byteStream().copyTo(out) }
                dest.length() > 0
            }
        } catch (t: Throwable) {
            dest.delete()
            false
        }
    }

    private fun metaFile(ctx: Context, path: String): File {
        val dir = File(ctx.filesDir, "meta").apply { mkdirs() }
        return File(dir, hash(path) + ".json")
    }

    private fun readMeta(ctx: Context, item: VideoItem): VideoItem? {
        val file = metaFile(ctx, item.path)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val poster = json.optString("poster", "").takeIf { it.isNotBlank() && File(it).exists() }
            val backdrop = json.optString("backdrop", "").takeIf { it.isNotBlank() && File(it).exists() }
            item.copy(
                title = json.optString("title", item.title).ifBlank { item.title },
                year = json.optInt("year", 0).takeIf { it > 0 } ?: item.year,
                overview = json.optString("overview", "").takeIf { it.isNotBlank() } ?: item.overview,
                rating = json.optDouble("rating", 0.0).takeIf { it > 0 }?.toFloat() ?: item.rating,
                genre = json.optString("genre", "").takeIf { it.isNotBlank() } ?: item.genre,
                posterPath = poster ?: item.posterPath,
                backdropPath = backdrop,
                source = json.optString("source", "")
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun saveMeta(ctx: Context, item: VideoItem) {
        try {
            val json = JSONObject().apply {
                put("title", item.title)
                put("year", item.year ?: 0)
                put("overview", item.overview ?: "")
                put("rating", (item.rating ?: 0f).toDouble())
                put("genre", item.genre ?: "")
                put("poster", item.posterPath ?: "")
                put("backdrop", item.backdropPath ?: "")
                put("source", item.source ?: "")
            }
            metaFile(ctx, item.path).writeText(json.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.joinToString("") { String.format("%02x", it) }
    }

    fun clearMeta(ctx: Context) {
        File(ctx.filesDir, "meta").listFiles()?.forEach { it.delete() }
    }

    fun clearArtwork(ctx: Context) {
        File(ctx.filesDir, "thumbs").listFiles()?.forEach { it.delete() }
        File(ctx.filesDir, "artwork").listFiles()?.forEach { it.delete() }
    }
}
