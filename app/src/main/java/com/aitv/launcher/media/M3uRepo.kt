package com.aitv.launcher.media

import com.aitv.launcher.Prefs
import com.aitv.launcher.model.LiveChannel
import com.aitv.launcher.util.Async
import com.aitv.launcher.util.StorageUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.Reader
import java.util.concurrent.TimeUnit

/**
 * 直播源读取：支持一行一个 m3u / m3u8 地址或本地文件路径，
 * 同时自动扫描 Download 目录下的 *.m3u / *.m3u8。
 *
 * 大源优化：按行流式解析，不再把整个 m3u 读成字符串再 split。
 * 一个几千频道的源文件动辄十几 MB，读成 String + split 会瞬时复制两三份，
 * 电视机的堆只有一百多 MB，很容易被系统直接杀进程（表现为应用自己重启）。
 */
object M3uRepo {

    /** 单次最多保留的频道数，避免超大源把整机内存吃满 */
    private const val MAX_CHANNELS = 8000

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** #EXTINF 属性正则预编译一次，避免每个频道重复编译 */
    private val groupRegex = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val logoRegex = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    @Volatile
    private var cache: List<LiveChannel>? = null

    fun invalidate() {
        cache = null
    }

    fun sources(): List<String> {
        val manual = Prefs.liveSources
            .split('\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val local = StorageUtils.downloadDirs()
            .flatMap { dir ->
                dir.listFiles()?.filter {
                    it.isFile && (it.extension.equals("m3u", true) || it.extension.equals("m3u8", true))
                }?.map { it.absolutePath } ?: emptyList()
            }
        return (manual + local).distinct()
    }

    fun load(force: Boolean = false, progress: (String) -> Unit = {}): List<LiveChannel> {
        val memory = cache
        if (!force && memory != null) return memory

        val channels = ArrayList<LiveChannel>(512)
        val seen = HashSet<String>(1024)
        var truncated = false
        for (source in sources()) {
            progress("正在读取直播源：$source")
            val ok = withSource(source) { reader ->
                parseInto(reader, channels, seen, baseIndex = channels.size)
            }
            if (!ok) progress("读取失败：$source")
            if (channels.size >= MAX_CHANNELS) {
                truncated = true
                break
            }
        }
        if (truncated) progress("直播源频道过多，只保留前 $MAX_CHANNELS 个")

        val result = channels.sortedWith(
            compareBy(
                { groupRank(it.group) },
                { it.group },
                { it.name }
            )
        )
        cache = result
        return result
    }

    fun loadAsync(force: Boolean = false, progress: (String) -> Unit = {}, callback: (List<LiveChannel>) -> Unit) {
        Async.io {
            val list = load(force, progress)
            callback(list)
        }
    }

    private fun groupRank(group: String): Int = when {
        group.contains("央视") -> 0
        group.contains("卫视") -> 1
        group.contains("地方") -> 2
        else -> 3
    }

    /** 打开数据源并逐行交给 [block]，整个读取过程只保留一行字符串。 */
    private fun withSource(source: String, block: (BufferedReader) -> Unit): Boolean {
        return try {
            if (source.startsWith("http://", true) || source.startsWith("https://", true)) {
                val request = Request.Builder()
                    .url(source)
                    .header("User-Agent", "MyTV/1.3")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return false
                    response.body?.charStream()?.let { block(it.buffered(8192)) }
                }
                true
            } else {
                val path = source.removePrefix("file://")
                val file = File(path)
                if (!file.exists() || !file.isFile) return false
                file.bufferedReader(Charsets.UTF_8).use { block(it) }
                true
            }
        } catch (t: Throwable) {
            false
        }
    }

    fun parse(text: String, startIndex: Int = 0): List<LiveChannel> {
        val result = ArrayList<LiveChannel>(256)
        val seen = HashSet<String>()
        parseInto(text.reader().buffered(8192), result, seen, baseIndex = startIndex)
        return result
    }

    private fun parseInto(
        reader: Reader,
        out: MutableList<LiveChannel>,
        seen: MutableSet<String>,
        baseIndex: Int
    ) {
        val buffered = if (reader is BufferedReader) reader else reader.buffered(8192)
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var currentExtGrp: String? = null
        var firstLine = true

        while (true) {
            val raw = buffered.readLine() ?: break
            var line = raw.trim()
            if (firstLine) {
                line = line.removePrefix("\uFEFF")
                firstLine = false
            }
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", true) -> {
                    pendingName = line.substringAfterLast(',', "").trim().ifBlank { null }
                    pendingGroup = groupRegex.find(line)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
                    pendingLogo = logoRegex.find(line)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
                }
                line.startsWith("#EXTGRP", true) -> {
                    currentExtGrp = line.substringAfter(':', "").trim().ifBlank { null }
                }
                line.startsWith("#") -> Unit
                isStreamUrl(line) -> {
                    if (line !in seen) {
                        seen.add(line)
                        val name = pendingName
                            ?: line.substringAfterLast('/').substringBefore('?').ifBlank { "直播频道" }
                        out.add(
                            LiveChannel(
                                name = name,
                                url = line,
                                group = pendingGroup ?: currentExtGrp ?: "未分组",
                                logo = pendingLogo,
                                index = baseIndex + out.size
                            )
                        )
                        if (out.size >= MAX_CHANNELS) return
                    }
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = null
                }
            }
        }
    }

    private fun isStreamUrl(line: String): Boolean {
        return line.startsWith("http://", true) ||
            line.startsWith("https://", true) ||
            line.startsWith("rtmp://", true) ||
            line.startsWith("rtsp://", true) ||
            line.startsWith("file://", true)
    }
}
