package com.aitv.launcher

import android.content.Context

/**
 * 轻量偏好存储：所有设置项都落在 SharedPreferences，改完立即生效。
 */
object Prefs {

    private const val NAME = "mytv_prefs"

    private val sp get() = App.ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var city: String
        get() = sp.getString("city", "西安") ?: "西安"
        set(value) = sp.edit().putString("city", value).apply()

    var latitude: Double
        get() = sp.getFloat("lat", 34.3416f).toDouble()
        set(value) = sp.edit().putFloat("lat", value.toFloat()).apply()

    var longitude: Double
        get() = sp.getFloat("lon", 108.9398f).toDouble()
        set(value) = sp.edit().putFloat("lon", value.toFloat()).apply()

    var tmdbKey: String
        get() = sp.getString("tmdb_key", "") ?: ""
        set(value) = sp.edit().putString("tmdb_key", value.trim()).apply()

    /** 多个目录用 ; 分隔，留空表示自动扫描所有存储卷 */
    var musicDirs: String
        get() = sp.getString("music_dirs", "") ?: ""
        set(value) = sp.edit().putString("music_dirs", value).apply()

    var videoDirs: String
        get() = sp.getString("video_dirs", "") ?: ""
        set(value) = sp.edit().putString("video_dirs", value).apply()

    /** 相册目录，同时也是手机上传的目标目录；留空自动选择 U 盘 */
    var photoDir: String
        get() = sp.getString("photo_dir", "") ?: ""
        set(value) = sp.edit().putString("photo_dir", value).apply()

    var uploadPort: Int
        get() = sp.getInt("upload_port", 8686)
        set(value) = sp.edit().putInt("upload_port", value).apply()

    var wallpaper: String
        get() = sp.getString("wallpaper", "") ?: ""
        set(value) = sp.edit().putString("wallpaper", value).apply()

    /** 首页背景图片，留空时使用内置「阖家团圆」默认图 */
    var wallpaperImage: String
        get() = sp.getString("wallpaper_image", "") ?: ""
        set(value) = sp.edit().putString("wallpaper_image", value.trim()).apply()

    var columns: Int
        get() = sp.getInt("columns", 5).coerceIn(4, 6)
        set(value) = sp.edit().putInt("columns", value.coerceIn(4, 6)).apply()

    var jptvPackage: String
        get() = sp.getString("jptv_pkg", "tv.android.jianpian.com") ?: "tv.android.jianpian.com"
        set(value) = sp.edit().putString("jptv_pkg", value.trim()).apply()

    var lastWeather: String
        get() = sp.getString("weather_json", "") ?: ""
        set(value) = sp.edit().putString("weather_json", value).apply()

    var lastWeatherAt: Long
        get() = sp.getLong("weather_at", 0L)
        set(value) = sp.edit().putLong("weather_at", value).apply()

    var lastScanAt: Long
        get() = sp.getLong("scan_at", 0L)
        set(value) = sp.edit().putLong("scan_at", value).apply()

    /** 天气按定位自动更新；关闭后使用手动城市/经纬度 */
    var autoLocation: Boolean
        get() = sp.getBoolean("loc_auto", true)
        set(value) = sp.edit().putBoolean("loc_auto", value).apply()

    var locationAt: Long
        get() = sp.getLong("loc_at", 0L)
        set(value) = sp.edit().putLong("loc_at", value).apply()

    /** 顺序播放 seq / 随机播放 shuffle */
    var playMode: String
        get() = sp.getString("play_mode", "seq") ?: "seq"
        set(value) = sp.edit().putString("play_mode", value).apply()

    /** 直播源，一行一个 m3u / m3u8 地址或本地路径 */
    var liveSources: String
        get() = sp.getString("live_sources", "") ?: ""
        set(value) = sp.edit().putString("live_sources", value).apply()

    /** 直播频道信息缓存：[{name,url,group,logo,index}] */
    var liveChannels: String
        get() = sp.getString("live_channels", "[]") ?: "[]"
        set(value) = sp.edit().putString("live_channels", value).apply()

    var liveChannelsAt: Long
        get() = sp.getLong("live_channels_at", 0L)
        set(value) = sp.edit().putLong("live_channels_at", value).apply()

    /** 直播收藏频道 JSON：[{name,url,group,logo,index}] */
    var liveFavorites: String
        get() = sp.getString("live_favorites", "[]") ?: "[]"
        set(value) = sp.edit().putString("live_favorites", value).apply()

    /** 首页网站收藏 JSON：[{name,url}] */
    var bookmarks: String
        get() = sp.getString("bookmarks", "[]") ?: "[]"
        set(value) = sp.edit().putString("bookmarks", value).apply()

    /** 自定义歌单 JSON：[{name,paths:[...]}] */
    var playlists: String
        get() = sp.getString("playlists", "[]") ?: "[]"
        set(value) = sp.edit().putString("playlists", value).apply()

    /** 智慧锁屏总开关 */
    var lockEnabled: Boolean
        get() = sp.getBoolean("lock_enabled", false)
        set(value) = sp.edit().putBoolean("lock_enabled", value).apply()

    /** 自动锁屏分钟数；0 表示不自动锁 */
    var lockTimeoutMinutes: Int
        get() = sp.getInt("lock_timeout_min", 30).coerceIn(0, 240)
        set(value) = sp.edit().putInt("lock_timeout_min", value.coerceIn(0, 240)).apply()

    /** 解锁难度：学前、小班/中班/大班、一年级至九年级、高一至高三 */
    var lockDifficulty: String
        get() = sp.getString("lock_difficulty", "一年级") ?: "一年级"
        set(value) = sp.edit().putString("lock_difficulty", value.trim()).apply()

    /** 家长 4 位数字密码，用于忘记答案时绕过 */
    var lockPin: String
        get() = sp.getString("lock_pin", "1234") ?: "1234"
        set(value) = sp.edit().putString("lock_pin", value.trim()).apply()

    /** 电视开机后是否立即进入智慧锁屏 */
    var lockOnBoot: Boolean
        get() = sp.getBoolean("lock_on_boot", true)
        set(value) = sp.edit().putBoolean("lock_on_boot", value).apply()

    /** 上次从 Gitee 检查发布版本的时间，用于 24 小时节流 */
    var updateCheckedAt: Long
        get() = sp.getLong("update_checked_at", 0L)
        set(value) = sp.edit().putLong("update_checked_at", value).apply()

    /** 正在后台下载的 DownloadManager 任务 id，-1 表示没有 */
    var pendingDownloadId: Long
        get() = sp.getLong("pending_download_id", -1L)
        set(value) = sp.edit().putLong("pending_download_id", value).apply()

    /** 已下载完成、等待拉起系统安装器的 APK 绝对路径 */
    var pendingApkPath: String
        get() = sp.getString("pending_apk_path", "").orEmpty()
        set(value) = sp.edit().putString("pending_apk_path", value).apply()

    /** 最近一次下载完成的 APK 路径，用于给用户提示手动安装 */
    var lastApkPath: String
        get() = sp.getString("last_apk_path", "").orEmpty()
        set(value) = sp.edit().putString("last_apk_path", value).apply()

    /** 拉起安装器时应用的版本号；如果返回后版本没变，说明系统未完成安装 */
    var installAttemptVersion: String
        get() = sp.getString("install_attempt_version", "").orEmpty()
        set(value) = sp.edit().putString("install_attempt_version", value).apply()

    /** 下载完成通知/手动安装提示是否待展示 */
    var manualInstallNotice: Boolean
        get() = sp.getBoolean("manual_install_notice", false)
        set(value) = sp.edit().putBoolean("manual_install_notice", value).apply()
}
