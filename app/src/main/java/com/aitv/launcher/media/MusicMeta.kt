package com.aitv.launcher.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

data class AudioTagInfo(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val picture: ByteArray? = null
)

data class FileNameInfo(
    val title: String,
    val artist: String
)

/**
 * 歌曲信息解析：
 * 1. 优先使用音频内嵌标签（MediaMetadataRetriever 为主，ID3 / FLAC 作为兜底）；
 * 2. 标签缺失时从文件名解析「歌手 - 歌名」等常见结构；
 * 3. 文件名也解析不出来时才保留文件名本身。
 */
object MusicMeta {

    private val unknownValues = setOf(
        "<unknown>", "unknown", "未知", "未知艺术家", "佚名", "null", "none", "n/a", "na"
    )

    private val trackNumberPrefix = Regex("^\\s*\\d{1,3}\\s*[.、_\\- ]+")
    private val qualitySuffix = Regex(
        "\\s*[\\[(（](?:320k|192k|128k|flac|hires|hq|mp3|aac|ogg|wav)[\\])）]\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val bracketArtist = Regex("^\\s*[\\[(【](.+?)[\\])】]\\s*(.+)$")
    private val guillemetArtist = Regex("^(.+?)\\s*《(.+?)》\\s*$")
    private val parenArtist = Regex("^(.+?)\\s*[(（](.+?)[)）]\\s*$")
    private val spacedDash = Regex("\\s+[-–—]\\s+")
    private val noiseSuffix = Regex(
        "(?:live|演唱会|伴奏|纯音乐|remix|dj|版|试听|完整版|无损|高音质)",
        RegexOption.IGNORE_CASE
    )

    /** 去掉标签里的空值 / unknown / 与文件名相同的伪标签 */
    fun cleanTag(raw: String?, fileBase: String): String? {
        var value = raw?.replace('\u0000', ' ')?.trim() ?: return null
        if (value.isEmpty()) return null
        value = value.replace(Regex("\\s+"), " ")
        val lower = value.lowercase()
        if (lower in unknownValues) return null
        if (lower == fileBase.trim().lowercase()) return null
        if (value.contains('\uFFFD')) return null
        return value
    }

    /** 从「歌手 - 歌名」「[歌手] 歌名」「歌名（歌手）」等文件名结构提取信息 */
    fun parseFileName(rawBase: String): FileNameInfo {
        var base = rawBase.replace('\u0000', ' ').replace('_', ' ').trim()
        base = base.replace(trackNumberPrefix, "").trim()
        base = base.replace(qualitySuffix, "").trim()
        base = base.replace(Regex("\\s+"), " ").trim()
        if (base.isEmpty()) return FileNameInfo(rawBase.trim(), "")

        bracketArtist.matchEntire(base)?.let { m ->
            val artist = m.groupValues[1].trim()
            val title = m.groupValues[2].trim()
            if (title.isNotEmpty()) return FileNameInfo(stripQuality(title), cleanArtist(artist))
        }
        guillemetArtist.matchEntire(base)?.let { m ->
            val artist = m.groupValues[1].trim()
            val title = m.groupValues[2].trim()
            if (title.isNotEmpty()) return FileNameInfo(stripQuality(title), cleanArtist(artist))
        }

        val spaced = spacedDash.find(base)
        if (spaced != null) {
            val left = base.substring(0, spaced.range.first).trim()
            val right = base.substring(spaced.range.last + 1).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) {
                return FileNameInfo(stripQuality(right), cleanArtist(left))
            }
        }

        val paren = parenArtist.matchEntire(base)
        if (paren != null) {
            val left = paren.groupValues[1].trim()
            val right = paren.groupValues[2].trim()
            if (left.isNotEmpty() && right.isNotEmpty() && !noiseSuffix.containsMatchIn(right)) {
                return FileNameInfo(stripQuality(left), cleanArtist(right))
            }
        }

        // 没有空格分隔时，取最后一个连字符：兼容「A-Lin-给我一个理由忘记」
        val dashIndex = listOf(base.lastIndexOf('-'), base.lastIndexOf('–'), base.lastIndexOf('—')).maxOrNull() ?: -1
        if (dashIndex in 1 until base.length - 1) {
            val left = base.substring(0, dashIndex).trim()
            val right = base.substring(dashIndex + 1).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) {
                return FileNameInfo(stripQuality(right), cleanArtist(left))
            }
        }

        return FileNameInfo(stripQuality(base), "")
    }

    fun displayArtist(artist: String?): String =
        cleanTag(artist, "").takeIf { !it.isNullOrBlank() } ?: "未知艺术家"

    private fun cleanArtist(raw: String): String {
        val lower = raw.lowercase()
        return if (lower in unknownValues) "" else raw.trim()
    }

    private fun stripQuality(raw: String): String =
        raw.replace(qualitySuffix, "").trim().ifEmpty { raw.trim() }

    // ---------------------------------------------------------------- 标签读取

    /** 读取音频文件的内嵌标签；只解析文件头附近的元数据，不读取整首歌。 */
    fun readTags(file: File): AudioTagInfo? {
        val ext = file.extension.lowercase()
        return try {
            when (ext) {
                "mp3", "aac" -> readId3v2(file) ?: readId3v1(file)
                "flac" -> readFlac(file)
                else -> readId3v1(file)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun readId3v2(file: File): AudioTagInfo? {
        return RandomAccessFile(file, "r").use<RandomAccessFile, AudioTagInfo?> { raf ->
            val header = readFully(raf, 10) ?: return@use null
            if (header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) return@use null

            val version = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            val size = syncSafeInt(header, 6)
            if (size <= 0 || size > 12 * 1024 * 1024) return@use null

            val data = readFully(raf, size) ?: return@use null
            var offset = 0
            if (flags and 0x40 != 0) {
                offset = when (version) {
                    4 -> syncSafeInt(data, 0).coerceAtLeast(6)
                    3 -> (intAt(data, 0) + 4).coerceAtLeast(4)
                    else -> 0
                }
            }

            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var picture: ByteArray? = null
            val frameHeader = if (version == 2) 6 else 10
            val frameIdLength = if (version == 2) 3 else 4

            while (offset + frameHeader <= data.size) {
                val id = ascii(data, offset, frameIdLength)
                if (!id.all { it.isLetterOrDigit() }) break
                val frameSize = if (version == 2) {
                    intAt24(data, offset + 3)
                } else if (version == 4) {
                    syncSafeInt(data, offset + 4)
                } else {
                    intAt(data, offset + 4)
                }
                if (frameSize <= 0 || offset + frameHeader + frameSize > data.size) break
                val frame = data.copyOfRange(offset + frameHeader, offset + frameHeader + frameSize)

                when (id) {
                    "TIT2", "TT2" -> if (title.isNullOrBlank()) title = decodeTextFrame(frame)
                    "TPE1", "TP1" -> if (artist.isNullOrBlank()) artist = decodeTextFrame(frame)
                    "TALB", "TAL" -> if (album.isNullOrBlank()) album = decodeTextFrame(frame)
                    "APIC", "PIC" -> if (picture == null) picture = decodePictureFrame(frame)
                }
                offset += frameHeader + frameSize
            }

            if (title.isNullOrBlank() && artist.isNullOrBlank() &&
                album.isNullOrBlank() && picture == null
            ) null
            else AudioTagInfo(title, artist, album, picture)
        }
    }

    private fun readId3v1(file: File): AudioTagInfo? {
        if (file.length() < 128) return null
        return RandomAccessFile(file, "r").use<RandomAccessFile, AudioTagInfo?> { raf ->
            raf.seek(file.length() - 128)
            val tag = readFully(raf, 128) ?: return@use null
            if (ascii(tag, 0, 3) != "TAG") return@use null
            val title = decodeLegacy(tag, 3, 30)
            val artist = decodeLegacy(tag, 33, 30)
            val album = decodeLegacy(tag, 63, 30)
            if (title.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank()) null
            else AudioTagInfo(title, artist, album, null)
        }
    }

    private fun readFlac(file: File): AudioTagInfo? {
        return RandomAccessFile(file, "r").use<RandomAccessFile, AudioTagInfo?> { raf ->
            val magic = readFully(raf, 4) ?: return@use null
            if (ascii(magic, 0, 4) != "fLaC") return@use null

            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var picture: ByteArray? = null

            while (true) {
                val head = readFully(raf, 4) ?: break
                val last = (head[0].toInt() and 0x80) != 0
                val type = head[0].toInt() and 0x7F
                val length = intAt24(head, 1)
                if (length < 0 || length > 12 * 1024 * 1024) break
                val data = readFully(raf, length) ?: break

                when (type) {
                    4 -> parseVorbisComment(data)?.let { tag ->
                        title = title ?: tag.title
                        artist = artist ?: tag.artist
                        album = album ?: tag.album
                    }
                    6 -> picture = picture ?: parseFlacPicture(data)
                }
                if (last) break
            }

            if (title.isNullOrBlank() && artist.isNullOrBlank() &&
                album.isNullOrBlank() && picture == null
            ) null
            else AudioTagInfo(title, artist, album, picture)
        }
    }

    private fun parseVorbisComment(data: ByteArray): AudioTagInfo? {
        var p = 0
        val vendorLength = intAtLe(data, p)
        if (vendorLength < 0 || vendorLength > data.size) return null
        p += 4 + vendorLength
        if (p + 4 > data.size) return null
        val count = intAtLe(data, p)
        p += 4
        if (count <= 0 || count > 2000) return null

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        repeat(count) {
            if (p + 4 > data.size) return@repeat
            val length = intAtLe(data, p)
            p += 4
            if (length <= 0 || p + length > data.size) return@repeat
            val text = String(data, p, length, Charsets.UTF_8)
            p += length
            val key = text.substringBefore('=', "").trim().uppercase()
            val value = text.substringAfter('=', "").trim()
            if (value.isEmpty()) return@repeat
            when (key) {
                "TITLE" -> title = title ?: value
                "ARTIST" -> artist = artist ?: value
                "ALBUM" -> album = album ?: value
            }
        }
        return AudioTagInfo(title, artist, album, null)
    }

    private fun parseFlacPicture(data: ByteArray): ByteArray? {
        var p = 0
        // picture type
        p += 4
        if (p + 4 > data.size) return null
        val mimeLen = intAt(data, p)
        p += 4
        if (mimeLen < 0 || p + mimeLen > data.size) return null
        p += mimeLen
        if (p + 4 > data.size) return null
        val descLen = intAt(data, p)
        p += 4
        if (descLen < 0 || p + descLen > data.size) return null
        p += descLen
        p += 16 // width, height, depth, colors
        if (p + 4 > data.size) return null
        val picLen = intAt(data, p)
        p += 4
        if (picLen <= 0 || p + picLen > data.size) return null
        return data.copyOfRange(p, p + picLen)
    }

    private fun decodeTextFrame(frame: ByteArray): String? {
        if (frame.isEmpty()) return null
        val encoding = frame[0].toInt() and 0xFF
        val payload = frame.copyOfRange(1, frame.size)
        val text = when (encoding) {
            1 -> String(payload, Charsets.UTF_16)
            2 -> String(payload, Charsets.UTF_16BE)
            3 -> String(payload, Charsets.UTF_8)
            else -> {
                val utf8 = String(payload, Charsets.UTF_8)
                if (utf8.contains('\uFFFD')) String(payload, Charset.forName("GBK")) else utf8
            }
        }
        return text.replace("\u0000", "").trim().ifEmpty { null }
    }

    private fun decodePictureFrame(frame: ByteArray): ByteArray? {
        if (frame.size < 4) return null
        val encoding = frame[0].toInt() and 0xFF
        var p = 1
        while (p < frame.size && frame[p] != 0.toByte()) p++
        p++ // mime 结束符
        p++ // 图片类型
        if (p >= frame.size) return null

        if (encoding == 1 || encoding == 2) {
            while (p + 1 < frame.size) {
                if (frame[p] == 0.toByte() && frame[p + 1] == 0.toByte()) {
                    p += 2
                    break
                }
                p += 2
            }
        } else {
            while (p < frame.size && frame[p] != 0.toByte()) p++
            p++
        }
        if (p >= frame.size) return null
        return frame.copyOfRange(p, frame.size)
    }

    private fun decodeLegacy(data: ByteArray, offset: Int, length: Int): String? {
        if (offset + length > data.size) return null
        val bytes = data.copyOfRange(offset, offset + length)
            .takeWhile { it != 0.toByte() }
            .toByteArray()
        val text = String(bytes, Charset.forName("GBK")).trim()
        return text.ifEmpty { null }
    }

    // ---------------------------------------------------------------- 基础读取

    private fun readFully(raf: RandomAccessFile, length: Int): ByteArray? {
        if (length <= 0) return null
        val bytes = ByteArray(length)
        var total = 0
        while (total < length) {
            val read = raf.read(bytes, total, length - total)
            if (read <= 0) break
            total += read
        }
        if (total <= 0) return null
        return if (total == length) bytes else bytes.copyOf(total)
    }

    private fun syncSafeInt(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }

    private fun intAt(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun intAt24(data: ByteArray, offset: Int): Int {
        if (offset + 2 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)
    }

    private fun intAtLe(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun ascii(data: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > data.size) return ""
        return String(data, offset, length, Charsets.US_ASCII)
    }
}
