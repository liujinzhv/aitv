package com.aitv.launcher.web

import com.aitv.launcher.Prefs
import com.aitv.launcher.model.Bookmark
import org.json.JSONArray
import org.json.JSONObject

/** 网站收藏：名称 + URL，存于 SharedPreferences 的 JSON。 */
object BookmarkRepo {

    fun all(): List<Bookmark> {
        return try {
            val array = JSONArray(Prefs.bookmarks)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url").trim()
                    if (url.isEmpty()) continue
                    val name = obj.optString("name").trim().ifEmpty { hostOf(url) }
                    add(Bookmark(name, url))
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun find(url: String): Bookmark? = all().firstOrNull { it.url == url }

    fun exists(url: String): Boolean = all().any { it.url == url }

    fun add(name: String, url: String): Boolean {
        val cleanUrl = normalize(url)
        if (cleanUrl.isEmpty()) return false
        val list = all().toMutableList()
        if (list.any { it.url == cleanUrl }) return false
        list.add(Bookmark(name.trim().ifEmpty { hostOf(cleanUrl) }, cleanUrl))
        save(list)
        return true
    }

    fun remove(url: String): Boolean {
        val list = all().toMutableList()
        val removed = list.removeAll { it.url == url }
        if (removed) save(list)
        return removed
    }

    fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://$url"
        }
        return url
    }

    fun hostOf(url: String): String {
        return try {
            val host = java.net.URI(normalize(url)).host
            host?.removePrefix("www.") ?: normalize(url)
        } catch (t: Throwable) {
            normalize(url)
        }
    }

    private fun save(list: List<Bookmark>) {
        val array = JSONArray()
        for (bookmark in list) {
            val obj = JSONObject()
            obj.put("name", bookmark.name)
            obj.put("url", bookmark.url)
            array.put(obj)
        }
        Prefs.bookmarks = array.toString()
    }
}
