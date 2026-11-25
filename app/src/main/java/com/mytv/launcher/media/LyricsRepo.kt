package com.mytv.launcher.media

import com.mytv.launcher.App
import com.mytv.launcher.model.AudioTrack
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class LrcLine(val timeMs: Long, val text: String)

data class LrcResult(
    val lines: List<LrcLine>,
    val source: String,
    val message: String
)

private data class SongQuery(
    val title: String,
    val artist: String,
    val fromFileName: Boolean
)

/**
 * 歌词刮削：同名 .lrc → 本地缓存 → 网易云 → LRCLIB。
 * 先用音频内嵌标签（标题 / 艺术家）搜索；标签缺失时用文件名解析结果，最后才用原始文件名。
 */
object LyricsRepo {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private val cacheDir: File
        get() = File(App.ctx.filesDir, "lyrics").apply { if (!exists()) mkdirs() }

    fun load(track: AudioTrack, force: Boolean = false): LrcResult {
        val audioFile = File(track.path)
        val base = audioFile.nameWithoutExtension
        val queries = buildQueries(track, base)
        val primary = queries.firstOrNull() ?: SongQuery(base, "", true)

        findLocalLrc(audioFile, localNames(queries, base))?.let { lrc ->
            val parsed = parse(readText(lrc))
            if (parsed.isNotEmpty()) {
                return LrcResult(parsed, "本地歌词", "本地歌词 · ${lrc.name}")
            }
        }

        val cacheFile = File(cacheDir, hash(primary, base) + ".lrc")
        if (!force && cacheFile.exists()) {
            val parsed = parse(readText(cacheFile))
            if (parsed.isNotEmpty()) {
                return LrcResult(parsed, "缓存", "已缓存歌词")
            }
        }

        for (query in queries) {
            val online = tryNetease(query) ?: tryLrclib(query)
            if (online != null) {
                val parsed = parse(online.first)
                if (parsed.isNotEmpty()) {
                    runCatching { cacheFile.writeText(online.first, Charsets.UTF_8) }
                    return LrcResult(parsed, "在线", "在线歌词 · ${online.second}")
                }
            }
        }

        val hint = if (primary.fromFileName) {
            "未找到歌词 · 已按文件名搜索，也可在音频旁放同名 .lrc 文件"
        } else {
            "未找到歌词 · 已按歌曲信息搜索，也可在音频旁放同名 .lrc 文件"
        }
        return LrcResult(emptyList(), "", hint)
    }

    fun parse(text: String): List<LrcLine> {
        val tagRegex = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        val result = mutableListOf<LrcLine>()

        text.lineSequence().forEach { rawLine ->
            val matches = tagRegex.findAll(rawLine)
            if (matches.none()) return@forEach

            val content = rawLine.replace(tagRegex, "").trim()
            if (content.isEmpty()) return@forEach

            matches.forEach { m ->
                val min = m.groupValues[1].toIntOrNull() ?: return@forEach
                val sec = m.groupValues[2].toIntOrNull() ?: return@forEach
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    1 -> frac.toInt() * 100
                    2 -> frac.toInt() * 10
                    3 -> frac.toInt()
                    else -> 0
                }
                result.add(LrcLine(min * 60_000L + sec * 1000L + ms, content))
            }
        }

        return result.sortedBy { it.timeMs }
    }

    // ---------------------------------------------------------------- 搜索条件

    private fun buildQueries(track: AudioTrack, base: String): List<SongQuery> {
        val parsed = MusicMeta.parseFileName(base)
        val tagTitle = MusicMeta.cleanTag(track.tagTitle, base)
        val tagArtist = MusicMeta.cleanTag(track.tagArtist, base)

        val title = tagTitle ?: parsed.title.ifBlank { base }
        val artist = tagArtist ?: parsed.artist

        val list = mutableListOf<SongQuery>()
        if (title.isNotBlank()) {
            list.add(SongQuery(title, artist, tagTitle == null))
        }

        // 标签只提供了一部分信息时，用文件名里的另一部分补足
        if (tagTitle == null && parsed.title.isNotBlank() &&
            (parsed.title != title || parsed.artist != artist)
        ) {
            list.add(SongQuery(parsed.title, parsed.artist, true))
        }

        // 完全没有可用标签时，最后一次尝试原始文件名
        if (tagTitle == null && base.isNotBlank() &&
            list.none { it.title.equals(base, ignoreCase = true) }
        ) {
            list.add(SongQuery(base, "", true))
        }

        return list.distinctBy { "${it.title}|${it.artist}" }
    }

    private fun localNames(queries: List<SongQuery>, base: String): List<String> {
        val names = linkedSetOf("$base.lrc")
        for (query in queries) {
            if (query.title.isBlank()) continue
            names.add("${query.title}.lrc")
            if (query.artist.isNotBlank()) {
                names.add("${query.artist} - ${query.title}.lrc")
                names.add("${query.title} - ${query.artist}.lrc")
            }
        }
        return names.toList()
    }

    private fun findLocalLrc(audioFile: File, names: List<String>): File? {
        val dir = audioFile.parentFile ?: return null
        val dirs = listOf(dir, File(dir, "lyrics"))
        for (folder in dirs) {
            if (!folder.exists()) continue
            val files = folder.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile || f.length() <= 0L) continue
                if (!f.extension.equals("lrc", ignoreCase = true)) continue
                if (names.any { it.equals(f.name, ignoreCase = true) }) return f
            }
        }
        return null
    }

    private fun readText(file: File): String {
        return try {
            val bytes = file.readBytes()
            var text = String(bytes, Charsets.UTF_8)
            if (parse(text).isEmpty()) {
                text = String(bytes, charset("GBK"))
            }
            text
        } catch (t: Throwable) {
            ""
        }
    }

    private fun hash(query: SongQuery, base: String): String {
        val raw = "${query.title}|${query.artist}|$base"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return digest.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // ---------------------------------------------------------------- 在线歌词

    private fun tryNetease(query: SongQuery): Pair<String, String>? {
        return try {
            val keyword = if (query.artist.isBlank()) query.title else "${query.title} ${query.artist}"
            val form = FormBody.Builder()
                .add("s", keyword)
                .add("type", "1")
                .add("limit", "10")
                .add("offset", "0")
                .build()
            val searchReq = Request.Builder()
                .url("https://music.163.com/api/search/get/web")
                .post(form)
                .header("User-Agent", ua)
                .header("Referer", "https://music.163.com/")
                .header("Cookie", "appver=2.0.2")
                .build()

            client.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null
                if (songs.length() == 0) return null

                var bestId = 0L
                var bestName = ""
                var bestScore = 0.0
                for (i in 0 until songs.length()) {
                    val song = songs.optJSONObject(i) ?: continue
                    val id = song.optLong("id", 0L)
                    if (id <= 0L) continue
                    val name = song.optString("name", "")
                    val artists = song.optJSONArray("artists")?.let { array ->
                        buildList<String> {
                            for (j in 0 until array.length()) {
                                array.optJSONObject(j)?.optString("name", "")?.let { add(it) }
                            }
                        }.joinToString(" ")
                    }.orEmpty()
                    val score = matchScore(query, name, artists)
                    if (score > bestScore) {
                        bestScore = score
                        bestId = id
                        bestName = name
                    }
                }
                if (bestId <= 0L || bestScore < MATCH_THRESHOLD) return null

                val lyricReq = Request.Builder()
                    .url("https://music.163.com/api/song/lyric?id=$bestId&lv=-1&kv=-1&tv=-1")
                    .header("User-Agent", ua)
                    .header("Referer", "https://music.163.com/")
                    .build()

                client.newCall(lyricReq).execute().use { lyricResp ->
                    if (!lyricResp.isSuccessful) return null
                    val lyricBody = lyricResp.body?.string() ?: return null
                    val lyric = JSONObject(lyricBody).optJSONObject("lrc")?.optString("lyric")
                    if (lyric.isNullOrBlank()) return null
                    Pair(lyric, "网易云 · $bestName")
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun tryLrclib(query: SongQuery): Pair<String, String>? {
        return try {
            val url = buildString {
                append("https://lrclib.net/api/search?track_name=")
                append(encode(query.title))
                if (query.artist.isNotBlank()) {
                    append("&artist_name=")
                    append(encode(query.artist))
                }
            }
            val req = Request.Builder().url(url).header("User-Agent", "MyTV/1.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val array = JSONArray(body)

                var bestLyrics = ""
                var bestLabel = ""
                var bestScore = 0.0
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val synced = item.optString("syncedLyrics", "")
                    if (synced.isBlank()) continue
                    val name = item.optString("trackName", "")
                    val artist = item.optString("artistName", "")
                    val score = matchScore(query, name, artist)
                    if (score > bestScore) {
                        bestScore = score
                        bestLyrics = synced
                        bestLabel = name
                    }
                }
                if (bestScore < MATCH_THRESHOLD || bestLyrics.isBlank()) return null
                Pair(bestLyrics, "LRCLIB · $bestLabel")
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- 结果匹配

    private const val MATCH_THRESHOLD = 1.2

    private fun matchScore(query: SongQuery, candidateTitle: String, candidateArtist: String): Double {
        val titleScore = textScore(normalize(query.title), normalize(candidateTitle))
        val artistScore = if (query.artist.isBlank()) {
            0.0
        } else {
            val wanted = normalize(query.artist)
            val found = normalize(candidateArtist)
            if (wanted.isNotBlank() && found.isNotBlank() &&
                (wanted.contains(found) || found.contains(wanted))
            ) 1.0 else 0.0
        }
        return titleScore * 2.0 + artistScore
    }

    private fun textScore(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.8
        val left = a.toSet()
        val right = b.toSet()
        val overlap = left.count { it in right }
        if (overlap == 0) return 0.0
        return (2.0 * overlap) / (left.size + right.size)
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[\\p{P}\\p{Z}\\s]+"), "")
}
