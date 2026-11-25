package com.mytv.launcher.media

import com.mytv.launcher.Prefs
import com.mytv.launcher.model.LiveChannel
import com.mytv.launcher.util.Async
import com.mytv.launcher.util.StorageUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 直播源读取：支持一行一个 m3u / m3u8 地址或本地文件路径，
 * 同时自动扫描 Download 目录下的 *.m3u / *.m3u8。
 * 频道缓存与台标缓存由 LiveRepo 负责。
 */
object M3uRepo {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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

        val channels = mutableListOf<LiveChannel>()
        var index = 0
        for (source in sources()) {
            progress("正在读取直播源：$source")
            val text = readSource(source)
            if (text == null) {
                progress("读取失败：$source")
                continue
            }
            val parsed = parse(text, index)
            index += parsed.size
            channels.addAll(parsed)
        }

        val result = channels
            .distinctBy { it.url }
            .sortedWith(
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

    private fun readSource(source: String): String? {
        return try {
            if (source.startsWith("http://", true) || source.startsWith("https://", true)) {
                val request = Request.Builder()
                    .url(source)
                    .header("User-Agent", "MyTV/1.2")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return null
                    response.body?.string()
                }
            } else {
                val path = source.removePrefix("file://")
                val file = File(path)
                if (file.exists() && file.isFile) file.readText(Charsets.UTF_8) else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun parse(text: String, startIndex: Int = 0): List<LiveChannel> {
        val lines = text.removePrefix("\uFEFF").split('\n').map { it.trim() }
        val result = mutableListOf<LiveChannel>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var currentExtGrp: String? = null

        for (line in lines) {
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", true) -> {
                    pendingName = line.substringAfterLast(',', "").trim().ifBlank { null }
                    pendingGroup = attribute(line, "group-title")
                    pendingLogo = attribute(line, "tvg-logo")
                }
                line.startsWith("#EXTGRP", true) -> {
                    currentExtGrp = line.substringAfter(':', "").trim().ifBlank { null }
                }
                line.startsWith("#") -> Unit
                isStreamUrl(line) -> {
                    val name = pendingName
                        ?: line.substringAfterLast('/').substringBefore('?').ifBlank { "直播频道" }
                    result.add(
                        LiveChannel(
                            name = name,
                            url = line,
                            group = pendingGroup ?: currentExtGrp ?: "未分组",
                            logo = pendingLogo,
                            index = startIndex + result.size
                        )
                    )
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = null
                }
            }
        }
        return result
    }

    private fun isStreamUrl(line: String): Boolean {
        return line.startsWith("http://", true) ||
            line.startsWith("https://", true) ||
            line.startsWith("rtmp://", true) ||
            line.startsWith("rtsp://", true) ||
            line.startsWith("file://", true)
    }

    private fun attribute(line: String, key: String): String? {
        val regex = Regex("$key=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
    }
}