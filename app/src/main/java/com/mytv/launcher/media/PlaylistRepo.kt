package com.mytv.launcher.media

import com.mytv.launcher.Prefs
import com.mytv.launcher.model.AudioTrack
import com.mytv.launcher.model.Playlist
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 自定义歌单：名称 + 歌曲绝对路径，存于 SharedPreferences 的 JSON。 */
object PlaylistRepo {

    fun all(): List<Playlist> {
        return try {
            val array = JSONArray(Prefs.playlists)
            val seenNames = HashSet<String>()
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    if (name.isEmpty() || !seenNames.add(name.lowercase())) continue
                    val paths = mutableListOf<String>()
                    val seenPaths = HashSet<String>()
                    val pathArray = obj.optJSONArray("paths") ?: JSONArray()
                    for (j in 0 until pathArray.length()) {
                        val path = pathArray.optString(j).trim()
                        if (path.isNotEmpty() && seenPaths.add(normalizePath(path))) {
                            paths.add(path)
                        }
                    }
                    add(Playlist(name, paths))
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun find(name: String): Playlist? = all().firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun create(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val list = all().toMutableList()
        if (list.any { it.name.equals(clean, ignoreCase = true) }) return false
        list.add(Playlist(clean, emptyList()))
        save(list)
        return true
    }

    fun rename(oldName: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.name.equals(oldName, ignoreCase = true) }
        if (index < 0) return false
        if (list.any { it.name.equals(clean, ignoreCase = true) && it.name != list[index].name }) return false
        list[index] = list[index].copy(name = clean)
        save(list)
        return true
    }

    fun delete(name: String): Boolean {
        val list = all().toMutableList()
        val removed = list.removeAll { it.name.equals(name, ignoreCase = true) }
        if (removed) save(list)
        return removed
    }

    fun addTrack(name: String, path: String): Boolean {
        if (path.isBlank()) return false
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (index < 0) return false
        val playlist = list[index]
        if (playlist.paths.any { samePath(it, path) }) return false
        list[index] = playlist.copy(paths = playlist.paths + path)
        save(list)
        return true
    }

    fun removeTrack(name: String, path: String): Boolean {
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (index < 0) return false
        val playlist = list[index]
        val kept = playlist.paths.filterNot { samePath(it, path) }
        if (kept.size == playlist.paths.size) return false
        list[index] = playlist.copy(paths = kept)
        save(list)
        return true
    }

    fun tracks(playlist: Playlist, allTracks: List<AudioTrack>): List<AudioTrack> {
        val byPath = allTracks.associateBy { normalizePath(it.path) }
        // USB / 移动硬盘重新挂载后路径可能变化，用文件名兜底，避免歌单里的歌凭空消失
        val byName = allTracks.groupBy { File(it.path).name.lowercase() }
        val result = mutableListOf<AudioTrack>()
        val seen = HashSet<String>()

        for (saved in playlist.paths) {
            val track = byPath[normalizePath(saved)]
                ?: byName[File(saved).name.lowercase()]?.firstOrNull()
            if (track != null && seen.add(track.path)) {
                result.add(track)
            }
        }
        return result
    }

    private fun samePath(a: String, b: String): Boolean {
        if (a == b) return true
        return normalizePath(a) == normalizePath(b)
    }

    private fun normalizePath(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)

    private fun save(list: List<Playlist>) {
        val array = JSONArray()
        for (playlist in list) {
            val obj = JSONObject()
            obj.put("name", playlist.name)
            val paths = JSONArray()
            playlist.paths.distinctBy { normalizePath(it) }.forEach { paths.put(it) }
            obj.put("paths", paths)
            array.put(obj)
        }
        Prefs.playlists = array.toString()
    }
}
