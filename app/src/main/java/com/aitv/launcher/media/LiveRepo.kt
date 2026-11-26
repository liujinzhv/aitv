package com.aitv.launcher.media

import com.aitv.launcher.App
import com.aitv.launcher.Prefs
import com.aitv.launcher.model.LiveChannel
import com.aitv.launcher.util.Async
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 直播频道本地缓存：
 * - 频道名称 / 分组 / 地址 / 台标 URL 和解析顺序存成 JSON，离线打开也能先展示；
 * - 台标图片下载到 files/live_logos，后续直接读本地文件，不再依赖源站；
 * - 刷新源时只在成功解析到频道后覆盖缓存，网络失败保留上一次结果。
 */
object LiveRepo {

    private const val MAX_LOGOS = 400
    private const val MAX_LOGO_BYTES = 4 * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val logoDir: File
        get() = File(App.ctx.filesDir, "live_logos").apply { mkdirs() }

    fun cached(): List<LiveChannel> {
        return try {
            val array = JSONArray(Prefs.liveChannels)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    val url = obj.optString("url").trim()
                    if (name.isEmpty() || url.isEmpty()) continue
                    add(
                        LiveChannel(
                            name = name,
                            url = url,
                            group = obj.optString("group", "未分组").ifBlank { "未分组" },
                            logo = obj.optString("logo").trim().ifBlank { null },
                            index = obj.optInt("index", i)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun lastUpdated(): Long = Prefs.liveChannelsAt

    fun favorites(): List<LiveChannel> {
        return try {
            val array = JSONArray(Prefs.liveFavorites)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    val url = obj.optString("url").trim()
                    if (name.isEmpty() || url.isEmpty()) continue
                    add(
                        LiveChannel(
                            name = name,
                            url = url,
                            group = obj.optString("group", "未分组").ifBlank { "未分组" },
                            logo = obj.optString("logo").trim().ifBlank { null },
                            index = obj.optInt("index", i)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun saveFavorites(channels: List<LiveChannel>) {
        val array = JSONArray()
        for ((position, channel) in channels.withIndex()) {
            val obj = JSONObject()
            obj.put("name", channel.name)
            obj.put("url", channel.url)
            obj.put("group", channel.group)
            obj.put("logo", channel.logo ?: "")
            obj.put("index", channel.index.takeIf { it > 0 } ?: position)
            array.put(obj)
        }
        Prefs.liveFavorites = array.toString()
    }

    fun hasCached(): Boolean = cached().isNotEmpty()

    fun hasSources(): Boolean = M3uRepo.sources().isNotEmpty()

    fun subtitle(): String {
        val count = cached().size
        return if (count > 0) "$count 个频道 · 已缓存" else "配置直播源 · 大屏看频道"
    }

    /**
     * 读取配置的直播源；成功解析到频道则写入缓存，否则回退到上一次缓存。
     * callback 第二个参数表示本次是否拿到了新数据。
     */
    fun load(
        force: Boolean = false,
        progress: (String) -> Unit = {},
        callback: (channels: List<LiveChannel>, fresh: Boolean) -> Unit
    ) {
        Async.io {
            val cached = cached()
            if (!hasSources()) {
                callback(cached, false)
                return@io
            }
            progress("正在读取直播源…")
            val list = try {
                M3uRepo.load(force, progress)
            } catch (t: Throwable) {
                emptyList()
            }
            if (list.isNotEmpty()) {
                save(list)
                callback(list, true)
            } else {
                progress("直播源读取失败，使用本地缓存")
                callback(cached, false)
            }
        }
    }

    fun save(channels: List<LiveChannel>) {
        val array = JSONArray()
        for ((position, channel) in channels.withIndex()) {
            val obj = JSONObject()
            obj.put("name", channel.name)
            obj.put("url", channel.url)
            obj.put("group", channel.group)
            obj.put("logo", channel.logo ?: "")
            obj.put("index", channel.index.takeIf { it > 0 } ?: position)
            array.put(obj)
        }
        Prefs.liveChannels = array.toString()
        Prefs.liveChannelsAt = System.currentTimeMillis()
    }

    fun logoFile(channel: LiveChannel): File? {
        val logo = channel.logo?.trim().orEmpty()
        if (logo.isEmpty()) return null
        return File(logoDir, sha1(logo) + ".img")
    }

    fun hasLogo(channel: LiveChannel): Boolean {
        val file = logoFile(channel) ?: return false
        return file.exists() && file.length() > 0
    }

    fun cachedLogoCount(channels: List<LiveChannel>): Int = channels.count { hasLogo(it) }

    /** 后台补齐缺失台标，最多 400 个；完成后回调一次，便于页面刷新。 */
    fun cacheLogos(
        channels: List<LiveChannel>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onDone: () -> Unit = {}
    ) {
        val targets = channels
            .filter { it.logo?.startsWith("http", true) == true }
            .filterNot { hasLogo(it) }
            .take(MAX_LOGOS)
        if (targets.isEmpty()) {
            onDone()
            return
        }

        Async.io {
            val total = targets.size
            val done = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(4)
            for (channel in targets) {
                pool.execute {
                    val ok = downloadLogo(channel)
                    val count = done.incrementAndGet()
                    if (ok || count % 5 == 0 || count == total) {
                        onProgress(count, total)
                    }
                }
            }
            pool.shutdown()
            runCatching { pool.awaitTermination(10, TimeUnit.MINUTES) }
            onProgress(total, total)
            onDone()
        }
    }

    private fun downloadLogo(channel: LiveChannel): Boolean {
        val source = channel.logo?.trim().orEmpty()
        val dest = logoFile(channel) ?: return false
        if (dest.exists() && dest.length() > 0) return true
        var temp: File? = null
        return try {
            val request = Request.Builder()
                .url(source)
                .header("User-Agent", "MyTV/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                temp = File.createTempFile("logo-", ".tmp", logoDir)
                var written = 0L
                body.byteStream().use { input ->
                    FileOutputStream(temp!!).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            written += read
                            if (written > MAX_LOGO_BYTES) return false
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (written <= 0) return false
                if (dest.exists()) dest.delete()
                temp!!.renameTo(dest) || temp!!.copyTo(dest, overwrite = true).length() > 0
            }
        } catch (t: Throwable) {
            false
        } finally {
            temp?.let { if (it.exists()) it.delete() }
        }
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
