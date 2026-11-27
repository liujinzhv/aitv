package com.aitv.launcher.update

import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gitee 发布版本读取：走公开的 Release API，不需要 token。
 * 仓库：https://gitee.com/liujinzhu/mytv
 */
object GiteeUpdate {

    private const val OWNER = "liujinzhu"
    private const val REPO = "mytv"
    private const val API = "https://gitee.com/api/v5/repos/$OWNER/$REPO/releases/latest"
    private const val PAGE = "https://gitee.com/$OWNER/$REPO/releases/tag/"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 8.0.0; SmartTV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val apkUrl: String?,
        val pageUrl: String
    )

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 网络请求必须在后台线程调用 */
    fun latest(): Result<Release> = runCatching {
        val request = okhttp3.Request.Builder()
            .url(API)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            val tag = root.optString("tag_name").trim()
            if (tag.isBlank()) error("发布版本缺少 tag_name")
            val assets = root.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                // 优先同渠道 tvapp 包，其次任意 apk
                var fallback: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (!name.endsWith(".apk", true) || url.isBlank()) continue
                    if (fallback == null) fallback = url
                    if (name.contains("tvapp", true)) {
                        apkUrl = url
                        break
                    }
                }
                if (apkUrl == null) apkUrl = fallback
            }
            Release(
                tag = tag,
                name = root.optString("name").ifBlank { tag },
                notes = root.optString("body").trim(),
                apkUrl = apkUrl,
                pageUrl = PAGE + tag
            )
        }
    }

    /** v1.4.6 / 1.4.6-tvapp 都按数字段比较，远端更高才算新版本 */
    fun isNewer(remote: String, current: String): Boolean {
        val a = numbers(remote)
        val b = numbers(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun numbers(version: String): List<Int> {
        val clean = version.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        return clean.split('.').mapNotNull { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull()
        }
    }

    /** 自动检查节流：24 小时内只请求一次 */
    const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
}
