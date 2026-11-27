package com.aitv.launcher.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.aitv.launcher.App
import com.aitv.launcher.Prefs
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object StorageUtils {

    private val skipDirs = setOf(
        "Android", "LOST.DIR", "System Volume Information", ".thumbnails",
        ".trash", "\$RECYCLE.BIN", "obb", "data", "cache"
    )

    fun hasStoragePermission(ctx: Context): Boolean {
        return ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun hasWritePermission(ctx: Context): Boolean {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** 所有可读存储卷：内置存储 + /storage、/mnt/usb 等目录下的 SD 卡 / U 盘 */
    fun volumes(): List<File> {
        val map = LinkedHashMap<String, File>()
        fun add(f: File) {
            if (!f.exists() || !f.isDirectory) return
            val key = try {
                f.canonicalPath
            } catch (t: Throwable) {
                f.absolutePath
            }
            map.putIfAbsent(key, f)
        }

        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { add(it) }

        // 部分电视（如 TCL）不生成 /storage/<uuid> 挂载点，U 盘只挂在 /mnt/usb/<uuid>
        val mountRoots = listOf("/storage", "/mnt/usb", "/mnt/media_rw", "/mnt/external_sd")
        for (path in mountRoots) {
            val root = File(path)
            if (!root.exists()) continue
            root.listFiles()?.forEach { f ->
                val name = f.name
                if (f.isDirectory && name != "emulated" && name != "self" && name != "knox" && name != "sdcard") {
                    add(f)
                }
            }
        }
        return map.values.toList()
    }

    fun isRemovable(file: File): Boolean {
        val path = file.absolutePath
        if (path.startsWith("/mnt/usb/") || path.startsWith("/mnt/media_rw/") ||
            path.startsWith("/mnt/external_sd")) {
            return true
        }
        if (path.startsWith("/storage/") && !path.startsWith("/storage/emulated")) return true
        return try {
            Environment.isExternalStorageRemovable(file)
        } catch (t: Throwable) {
            false
        }
    }

    fun primaryVolume(): File? = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()

    fun removableVolume(): File? {
        return volumes().firstOrNull { it != primaryVolume() && isRemovable(it) && it.canWrite() }
    }

    /** 相册目录：优先用户指定，其次 U 盘的 Photos，最后退回应用私有目录。
     *  有些电视的 U 盘挂载点 canWrite() 为 true，但实际写入会报 I/O error，
     *  所以这里必须做一次真实写入探测，不可写就自动退回应用目录。 */
    fun photoTarget(ctx: Context): File {
        val custom = Prefs.photoDir.trim()
        val candidates = ArrayList<File>()
        if (custom.isNotEmpty()) candidates.add(File(custom))
        removableVolume()?.let { candidates.add(File(it, "Photos")) }
        candidates.add(File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Photos"))
        candidates.add(File(ctx.filesDir, "Photos"))

        for (dir in candidates) {
            if (isWritableDir(dir)) return dir
        }
        return candidates.last()
    }

    /** 真正创建并写入一个探针文件，避免 U 盘挂载点“看起来可写、实际 I/O error”。 */
    private fun isWritableDir(dir: File): Boolean {
        var probe: File? = null
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            if (!dir.isDirectory || !dir.canWrite()) return false
            probe = File(dir, ".write_probe_${System.nanoTime()}")
            FileOutputStream(probe).use { it.write(1) }
            true
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { probe?.delete() }
        }
    }

    fun downloadDirs(): List<File> {
        val map = LinkedHashMap<String, File>()
        volumes().forEach { vol ->
            val d = File(vol, "Download")
            if (d.exists() && d.isDirectory) map.putIfAbsent(d.absolutePath, d)
        }
        runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()?.let { d ->
            if (d.exists() && d.isDirectory) map.putIfAbsent(d.absolutePath, d)
        }
        return map.values.toList()
    }

    fun dirsFromPref(raw: String): List<File> {
        return raw.split(";", ",", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(it) }
            .filter { it.exists() && it.isDirectory }
    }

    /** 递归扫描文件，限制深度，跳过隐藏目录与系统目录 */
    fun scan(root: File, exts: Set<String>, maxDepth: Int = 8, onFile: (File) -> Unit) {
        walk(root, exts, 0, maxDepth, onFile)
    }

    private fun walk(dir: File, exts: Set<String>, depth: Int, maxDepth: Int, onFile: (File) -> Unit) {
        if (depth > maxDepth) return
        val children = try {
            dir.listFiles()
        } catch (t: Throwable) {
            null
        } ?: return

        for (f in children) {
            if (f.isDirectory) {
                val name = f.name
                if (name.startsWith(".") || name in skipDirs) continue
                if (f.canRead()) walk(f, exts, depth + 1, maxDepth, onFile)
            } else {
                val ext = f.extension.lowercase()
                if (ext in exts && f.length() > 0) onFile(f)
            }
        }
    }

    /** 当前设备局域网 IPv4，优先 wlan 网卡 */
    fun localIpv4(): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val priority = if (nif.name.startsWith("wlan") || nif.name.startsWith("eth")) 0 else 1
                val addresses = Collections.list(nif.inetAddresses)
                for (addr in addresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        found.add(priority to host.substringBefore("%"))
                    }
                }
            }
        } catch (t: Throwable) {
            App.logWarn("network interface failed", t)
        }
        return found.distinctBy { it.second }.sortedBy { it.first }.map { it.second }
    }

    /** 目标文件重名时自动加序号 */
    fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var index = 1
        while (file.exists()) {
            file = File(dir, "$base (${index})$ext")
            index++
        }
        return file
    }

    fun sdkAtLeast(version: Int): Boolean = Build.VERSION.SDK_INT >= version
}

/** 相册目录的人类可读描述，标明是否在 U 盘上 */
fun photoPathText(ctx: Context): String {
    val dir = StorageUtils.photoTarget(ctx)
    val usb = StorageUtils.removableVolume()
    return when {
        usb != null && dir.absolutePath.startsWith(usb.absolutePath) ->
            dir.absolutePath + "（U 盘）"
        usb != null ->
            dir.absolutePath + "（U 盘不可写，暂存应用目录）"
        else ->
            dir.absolutePath + "（未检测到 U 盘，暂存应用目录）"
    }
}
