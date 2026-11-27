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
 * - 频道名称 / 分组 / 地址 / 台标 URL 和解析顺序只存 SQLite（live_channel），离线打开直接查库；
 * - 台标图片下载到 files/live_logos，并把本地路径回写到 live_channel.logo_path，后续不再依赖源站；
 * - 换源（手填地址或 Download 目录里的 m3u 变化）时只保留新源：旧频道行和旧台标文件一起删除。
 */
object LiveRepo {

    /** 单次补齐的台标数量上限：几千频道的源里挨个下载会把网络和内存一起拖垮 */
    private const val MAX_LOGOS = 240
    private const val MAX_LOGO_BYTES = 2 * 1024 * 1024

    /** 同一个站点连续失败这么多次后，本次不再尝试它下面的台标 */
    private const val HOST_FAIL_LIMIT = 3

    /** 单个台标最多尝试次数：失败到这个次数后不再重试，避免每次进页面都白跑一遍网络 */
    private const val MAX_LOGO_TRIES = 3

    /** 上次写入 SQLite 的直播源列表指纹；源文件增删/改路径时用来判断要不要清掉旧台标 */
    private const val META_SOURCE_SIG = "live_sources_sig"

    /** 内存里保留一份解析结果，避免反复解析大 JSON */
    @Volatile
    private var memCache: List<LiveChannel>? = null

    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val logoDir: File
        get() = File(App.ctx.filesDir, "live_logos").apply { mkdirs() }

    fun cached(): List<LiveChannel> {
        memCache?.let { return it }
        val channels = runCatching { MediaIndex.liveChannels() }.getOrElse {
            App.logWarn("读取直播频道索引失败", it)
            emptyList()
        }
        memCache = channels
        return channels
    }

    fun lastUpdated(): Long {
        val pref = Prefs.liveChannelsAt
        if (pref > 0L) return pref
        return MediaIndex.meta("live_updated_at")?.toLongOrNull() ?: 0L
    }

    /**
     * 配置新的直播源时先清空旧数据：SQLite 里的旧频道、files/live_logos 里的旧台标全部删除。
     * 不做旧 JSON 兼容，旧数据不再迁移。
     */
    fun resetForNewSource() {
        clearChannelsAndLogos()
        Prefs.liveChannels = ""
        Prefs.liveChannelsAt = 0L
        M3uRepo.invalidate()
    }

    /** 只清频道库、本地台标和源指纹，不动用户配置的源地址 */
    private fun clearChannelsAndLogos() {
        memCache = emptyList()
        runCatching { MediaIndex.clearLiveChannels() }
        runCatching { logoDir.deleteRecursively() }
        runCatching { MediaIndex.putMeta(META_SOURCE_SIG, "") }
    }

    private fun sourceSignature(): String = M3uRepo.sources().joinToString("\n")

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
            // 已有 SQLite 频道缓存时不再每次进页面都重新解析源；只有显式刷新才联网
            if (!force && cached.isNotEmpty()) {
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

    /** 设置里改了直播源后做一次全量刷新（重新解析所有源并写 SQLite） */
    fun refreshAll(progress: (String) -> Unit = {}, callback: (fresh: Boolean, count: Int) -> Unit) {
        Async.io {
            val signature = sourceSignature()
            val list = try {
                M3uRepo.load(true, progress)
            } catch (t: Throwable) {
                App.logWarn("直播源全量刷新失败", t)
                emptyList()
            }
            if (list.isNotEmpty()) {
                // Download 目录里的 m3u 变化也算换源：解析成功后再删旧频道/旧台标；
                // 解析失败则保留上一次可用频道，不提前清空。
                val oldSignature = MediaIndex.meta(META_SOURCE_SIG).orEmpty()
                if (oldSignature.isNotBlank() && oldSignature != signature) {
                    clearChannelsAndLogos()
                }
                save(list)
                callback(true, list.size)
            } else {
                callback(false, cached().size)
            }
        }
    }

    fun save(channels: List<LiveChannel>) {
        // 频道列表写 SQLite；台标仍按 logo URL 哈希缓存在 files/live_logos。
        // 不再把几千频道拼成 SharedPreferences JSON，减少一次大字符串和中间对象的内存峰值。
        memCache = channels
        val now = System.currentTimeMillis()
        runCatching {
            MediaIndex.saveLiveChannels(channels)
            MediaIndex.putMeta(META_SOURCE_SIG, sourceSignature())
            Prefs.liveChannels = ""
        }.onFailure {
            App.logWarn("直播频道写入 SQLite 失败", it)
        }
        Prefs.liveChannelsAt = now
    }

    fun logoFile(channel: LiveChannel): File? {
        val cached = channel.logoPath?.trim().orEmpty()
        if (cached.isNotEmpty()) {
            val file = File(cached)
            if (file.exists() && file.length() > 0L) return file
        }
        val logo = channel.logo?.trim().orEmpty()
        if (logo.isEmpty()) return null
        return File(logoDir, sha1(logo) + ".img")
    }

    fun hasLogo(channel: LiveChannel): Boolean {
        val file = logoFile(channel) ?: return false
        return file.exists() && file.length() > 0
    }

    fun cachedLogoCount(channels: List<LiveChannel>): Int = channels.count { hasLogo(it) }

    /** 后台补齐缺失台标：低内存机型只补一小部分、单线程慢慢来；站点连续失败就整段跳过。 */
    fun cacheLogos(
        channels: List<LiveChannel>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onDone: () -> Unit = {}
    ) {
        val lowRam = isLowRam()
        val limit = if (lowRam) 120 else MAX_LOGOS
        // 已经失败满 3 次的台标不再尝试，避免每次进页面都白跑一遍网络
        val attempts = runCatching { MediaIndex.logoTries() }.getOrDefault(emptyMap())
        val targets = channels
            .asSequence()
            .filter { it.logo?.startsWith("http", true) == true }
            .filter { (attempts[it.logo] ?: 0) < MAX_LOGO_TRIES }
            // 本地文件可能已存在但 SQLite 里的 logo_path 还没回写，也要补一次记录
            .filter { it.logoPath.isNullOrBlank() || !hasLogo(it) }
            .take(limit)
            .toList()
        if (targets.isEmpty()) {
            onDone()
            return
        }

        Async.io {
            val total = targets.size
            val done = AtomicInteger(0)
            val lastReported = AtomicInteger(0)
            val hostFails = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
            val pool = Executors.newFixedThreadPool(if (lowRam) 1 else 2)
            for (channel in targets) {
                pool.execute {
                    val host = hostOf(channel.logo)
                    val failed = hostFails[host]?.get() ?: 0
                    val ok = if (failed >= HOST_FAIL_LIMIT) false else downloadLogo(channel)
                    if (!ok && failed < HOST_FAIL_LIMIT) {
                        hostFails.computeIfAbsent(host) { AtomicInteger() }.incrementAndGet()
                    }
                    if (!ok) {
                        val tries = runCatching { MediaIndex.bumpLogoTries(channel.logo.orEmpty()) }.getOrDefault(0)
                        if (tries >= MAX_LOGO_TRIES) {
                            App.log("台标已失败 $tries 次，后续不再尝试：${channel.name}")
                        }
                    }
                    val count = done.incrementAndGet()
                    if (count == total || count - lastReported.get() >= 8) {
                        lastReported.set(count)
                        runCatching { onProgress(count, total) }
                    }
                }
            }
            pool.shutdown()
            runCatching { pool.awaitTermination(10, TimeUnit.MINUTES) }
            runCatching { onProgress(total, total) }
            runCatching { onDone() }
        }
    }

    private fun isLowRam(): Boolean = runCatching {
        val am = App.ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.isLowRamDevice
    }.getOrDefault(false)

    private fun hostOf(url: String?): String {
        val raw = url?.trim().orEmpty()
        return raw.removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()
    }

    private fun downloadLogo(channel: LiveChannel): Boolean {
        val source = channel.logo?.trim().orEmpty()
        val dest = logoFile(channel) ?: return false
        if (dest.exists() && dest.length() > 0) {
            runCatching { MediaIndex.updateLiveLogo(source, dest.absolutePath) }
            return true
        }
        if (inFlight.putIfAbsent(dest.name, true) != null) return false
        var temp: File? = null
        return try {
            val request = Request.Builder()
                .url(source)
                .header("User-Agent", "MyTV/1.3")
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
                val saved = temp!!.renameTo(dest) || temp!!.copyTo(dest, overwrite = true).length() > 0
                if (saved) runCatching { MediaIndex.updateLiveLogo(source, dest.absolutePath) }
                saved
            }
        } catch (t: Throwable) {
            false
        } finally {
            inFlight.remove(dest.name)
            temp?.let { if (it.exists()) it.delete() }
        }
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
