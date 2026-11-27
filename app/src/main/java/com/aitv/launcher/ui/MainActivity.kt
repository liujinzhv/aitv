package com.aitv.launcher.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aitv.launcher.Prefs
import com.aitv.launcher.R
import com.aitv.launcher.databinding.ActivityMainBinding
import com.aitv.launcher.databinding.ViewFeatureCardBinding
import com.aitv.launcher.update.UpdateDownloader
import com.aitv.launcher.update.UpdatePrompt
import com.aitv.launcher.util.SystemWallpaper
import com.aitv.launcher.util.WallpaperStore
import com.aitv.launcher.util.dp
import com.aitv.launcher.util.featureCardBackground
import com.aitv.launcher.util.focusScale
import com.aitv.launcher.util.immersive
import com.aitv.launcher.util.keepScreenOn
import com.aitv.launcher.util.toast
import com.aitv.launcher.weather.WeatherInfo
import com.aitv.launcher.weather.WeatherRepo
import android.icu.util.ChineseCalendar
import java.text.SimpleDateFormat
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var weatherFreshAt: Long = 0L
    private var lightHome = false
    private var wallpaperPlayer: ExoPlayer? = null
    private var playingWallpaperPath = ""

    /** 首页启动 2.5 秒后从 Gitee 查一次发布版本，避免和首屏渲染抢资源 */
    private val updateCheckTick = Runnable {
        if (!isFinishing && !isDestroyed) UpdatePrompt.autoCheck(this)
    }

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 以桌面身份透出系统壁纸：静态和动态壁纸都由系统自己绘制，更换后自动同步
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        immersive()
        keepScreenOn()
        requestStoragePermission()

        updateClock()
        buildFeatureCards()
        binding.featureRow.post {
            binding.featureRow.getChildAt(0)?.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        updateWeather(force = false)
        setupWallpaper()
        val manualInstall = UpdateDownloader.checkInstallResult(this)
        UpdateDownloader.installIfReady(this)
        if (manualInstall || Prefs.pendingApkPath.isNotBlank()) return
        handler.removeCallbacks(updateCheckTick)
        handler.postDelayed(updateCheckTick, 2500L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(updateCheckTick)
        // 电视只有有限路硬解：进视频/相册前必须把壁纸播放器连同解码器一起释放，
        // 只 pause 仍占着 MediaCodec，再开播放器会卡在 5 秒左右无法继续解码。
        releaseVideoWallpaper()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseVideoWallpaper()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        updateWeather(force = false)
    }

    @Deprecated("Launcher 不响应返回键")
    override fun onBackPressed() {
        // 桌面上按返回键不退出
    }

    private fun updateClock() {
        val now = Date()
        binding.clockText.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        val calendar = Calendar.getInstance()
        val weekdays = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        val week = weekdays[(calendar.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]
        binding.dateText.text = SimpleDateFormat("M月d日", Locale.CHINA).format(now) +
            "  " + week + "  ·  " + lunarText(now.time)

        binding.greetingText.text = when (calendar.get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> "早上好，今天也要好好生活"
            in 11..12 -> "中午好，记得休息一下"
            in 13..17 -> "下午好，欢迎回家"
            in 18..22 -> "晚上好，欢迎回家"
            else -> "夜深了，早点休息"
        }
    }

    private fun lunarText(millis: Long): String {
        val cc = ChineseCalendar()
        cc.timeInMillis = millis
        val month = cc.get(ChineseCalendar.MONTH).coerceIn(0, 11)
        val day = cc.get(ChineseCalendar.DAY_OF_MONTH).coerceIn(1, 30)
        val leap = cc.get(ChineseCalendar.IS_LEAP_MONTH) == 1
        val monthNames = arrayOf("正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "冬月", "腊月")
        return "农历" + (if (leap) "闰" else "") + monthNames[month] + lunarDay(day)
    }

    private fun lunarDay(day: Int): String {
        val digits = arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
        return when (day) {
            in 1..10 -> "初" + digits[day]
            20 -> "二十"
            30 -> "三十"
            in 11..19 -> "十" + digits[day - 10]
            in 21..29 -> "廿" + digits[day - 20]
            else -> "初" + digits[10]
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQUEST_STORAGE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) {
            val storageDenied = permissions.indices.any { index ->
                val name = permissions[index]
                (name == Manifest.permission.READ_EXTERNAL_STORAGE ||
                    name == Manifest.permission.WRITE_EXTERNAL_STORAGE) &&
                    grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED
            }
            if (storageDenied) {
                toast("未授予存储权限，音乐 / 视频 / 相册将无法扫描")
            }
        }
    }

    /**
     * 首页壁纸优先使用应用内图片 / 视频；没有时才回退系统壁纸或内置深色兜底。
     * 这台电视没有系统 wallpaper 服务，不能依赖 WallpaperManager。
     */
    private fun setupWallpaper() {
        binding.wallpaperPlayer.visibility = View.GONE
        binding.homeScrim.visibility = View.VISIBLE

        when (WallpaperStore.kind(this)) {
            WallpaperStore.Kind.VIDEO -> {
                binding.homeWallpaperImage.setImageDrawable(null)
                binding.homeWallpaperImage.visibility = View.GONE
                binding.bgLayer.visibility = View.GONE
                applyHomeTone(false)
                playVideoWallpaper(WallpaperStore.videoPath(this))
                return
            }
            WallpaperStore.Kind.IMAGE -> {
                releaseVideoWallpaper()
                binding.homeWallpaperImage.visibility = View.GONE
                binding.bgLayer.visibility = View.VISIBLE
                WallpaperStore.loadInto(this, binding.homeWallpaperImage) { loaded, luminance ->
                    if (isFinishing || isDestroyed) return@loadInto
                    if (loaded) {
                        binding.bgLayer.visibility = View.GONE
                        applyHomeTone(luminance >= 0.60f)
                    } else {
                        binding.bgLayer.visibility = View.VISIBLE
                        applyHomeTone(false)
                    }
                }
                return
            }
            WallpaperStore.Kind.NONE -> Unit
        }

        releaseVideoWallpaper()
        binding.homeWallpaperImage.setImageDrawable(null)
        binding.homeWallpaperImage.visibility = View.GONE
        val hasSystemWallpaper = SystemWallpaper.hasWallpaper(this)
        binding.bgLayer.visibility =
            if (hasSystemWallpaper) View.GONE else View.VISIBLE

        // 首页文字颜色跟着壁纸整体亮度走：亮壁纸用黑字，暗壁纸用白字。
        val lightBackground = hasSystemWallpaper &&
            SystemWallpaper.averageLuminance(this) >= 0.60f
        applyHomeTone(lightBackground)
    }

    /** 首页视频壁纸：本地文件静音播放，单曲循环，不显示播放器控件。 */
    private fun playVideoWallpaper(path: String) {
        val file = File(path)
        if (!file.isFile || file.length() <= 0) {
            WallpaperStore.clear(this)
            binding.wallpaperPlayer.visibility = View.GONE
            binding.bgLayer.visibility = View.VISIBLE
            applyHomeTone(false)
            toast("视频壁纸文件不存在，已恢复默认背景")
            return
        }
        binding.wallpaperPlayer.visibility = View.VISIBLE
        val player = wallpaperPlayer ?: ExoPlayer.Builder(this).build().also { p ->
            p.volume = 0f
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.playWhenReady = true
            binding.wallpaperPlayer.player = p
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    handler.post {
                        if (isFinishing || isDestroyed) return@post
                        WallpaperStore.clear(this@MainActivity)
                        releaseVideoWallpaper()
                        binding.bgLayer.visibility = View.VISIBLE
                        applyHomeTone(false)
                        toast("视频壁纸无法播放，已恢复默认背景")
                    }
                }
            })
            wallpaperPlayer = p
        }
        val absolutePath = file.absolutePath
        if (playingWallpaperPath != absolutePath) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            playingWallpaperPath = absolutePath
        }
        player.playWhenReady = true
        player.play()
    }

    private fun releaseVideoWallpaper() {
        val player = wallpaperPlayer ?: return
        binding.wallpaperPlayer.visibility = View.GONE
        binding.wallpaperPlayer.player = null
        player.release()
        wallpaperPlayer = null
        playingWallpaperPath = ""
    }

    /** 首页不固定深色文字：按壁纸平均亮度切换白 / 黑，并同步遮罩和天气图标。 */
    private fun applyHomeTone(light: Boolean) {
        lightHome = light
        val primary = if (light) 0xFF11151B.toInt() else getColor(R.color.text)
        val dim = if (light) 0xFF3F4650.toInt() else getColor(R.color.text_dim)
        val faint = if (light) 0xFF66707C.toInt() else getColor(R.color.text_faint)
        val accent = if (light) 0xFF1D4ED8.toInt() else getColor(R.color.accent)
        val glow = if (light) 0x66FFFFFF else 0x99000000.toInt()
        val shadow = if (light) 0x99FFFFFF.toInt() else 0x99000000.toInt()

        binding.homeScrim.setBackgroundResource(
            if (light) R.drawable.bg_home_scrim_light else R.drawable.bg_home_scrim
        )
        binding.weatherCard.background = null

        binding.clockText.setTextColor(primary)
        binding.clockText.setShadowLayer(dp(12f).toFloat(), 0f, dp(3f).toFloat(), glow)
        binding.dateText.setTextColor(dim)
        binding.dateText.setShadowLayer(dp(9f).toFloat(), 0f, dp(2f).toFloat(), glow)
        binding.greetingText.setTextColor(faint)
        binding.greetingText.setShadowLayer(dp(9f).toFloat(), 0f, dp(2f).toFloat(), glow)

        binding.weatherTemp.setTextColor(primary)
        binding.weatherTemp.setShadowLayer(dp(8f).toFloat(), 0f, dp(2f).toFloat(), shadow)
        binding.weatherDesc.setTextColor(dim)
        binding.weatherDesc.setShadowLayer(dp(8f).toFloat(), 0f, dp(2f).toFloat(), shadow)
        binding.weatherCity.setTextColor(faint)
        binding.weatherCity.setShadowLayer(dp(8f).toFloat(), 0f, dp(2f).toFloat(), shadow)
        binding.weatherIcon.setColorFilter(accent)

        // 亮壁纸时白色焦点描边会看不见，卡片改用深色描边。
        for (i in 0 until binding.featureRow.childCount) {
            val card = binding.featureRow.getChildAt(i)
            val colors = card.tag as? IntArray ?: continue
            card.background = featureCardBackground(this, colors[0], colors[1], light)
        }
    }

    private fun buildFeatureCards() {
        binding.featureRow.removeAllViews()

        addCard("直播", R.drawable.ic_tv, 0xFF7A1F2B.toInt(), 0xFFD1465A.toInt()) {
            startActivity(Intent(this, LiveActivity::class.java))
        }

        addCard("视频", R.drawable.ic_video, 0xFF123C66.toInt(), 0xFF2D7BD6.toInt()) {
            startActivity(Intent(this, VideoActivity::class.java))
        }

        addCard("相册", R.drawable.ic_photo, 0xFF7A3A16.toInt(), 0xFFD9803A.toInt()) {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        addCard("音乐", R.drawable.ic_music, 0xFF4A2E86.toInt(), 0xFF8B5CF6.toInt()) {
            startActivity(Intent(this, MusicActivity::class.java))
        }

        addCard("应用", R.drawable.ic_apps, 0xFF12524C.toInt(), 0xFF2AA79B.toInt()) {
            startActivity(Intent(this, AllAppsActivity::class.java))
        }

        addCard("设置", R.drawable.ic_settings, 0xFF2A3038.toInt(), 0xFF556171.toInt()) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun addCard(
        title: String,
        iconRes: Int,
        color1: Int,
        color2: Int,
        onClick: () -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.view_feature_card, binding.featureRow, false)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        view.layoutParams = lp

        val card = ViewFeatureCardBinding.bind(view)
        card.cardIcon.setImageResource(iconRes)
        card.cardTitle.text = title

        view.background = featureCardBackground(this, color1, color2, lightHome)
        view.tag = intArrayOf(color1, color2)
        view.focusScale(1.06f)
        view.setOnClickListener { onClick() }

        binding.featureRow.addView(view)
    }

    private fun updateWeather(force: Boolean) {
        val cached = WeatherRepo.cached()
        if (cached != null) {
            applyWeather(cached)
            weatherFreshAt = cached.updatedAt
        }

        val stale = cached == null || System.currentTimeMillis() - cached.updatedAt > WEATHER_TTL ||
            (Prefs.autoLocation && !com.aitv.launcher.weather.LocationRepo.isFresh())
        if (!force && !stale) return

        if (cached == null) {
            binding.weatherDesc.text = "正在获取天气…"
        }
        WeatherRepo.refresh(this) { info ->
            if (info != null) {
                applyWeather(info)
            } else if (weatherFreshAt <= 0L) {
                binding.weatherTemp.text = "--°"
                binding.weatherDesc.text = "天气获取失败"
                binding.weatherCity.text = Prefs.city
            }
        }
    }

    private fun applyWeather(info: WeatherInfo) {
        weatherFreshAt = info.updatedAt
        binding.weatherTemp.text = "${info.temp}°"
        binding.weatherDesc.text = "${info.desc}   ${info.low}° ~ ${info.high}°"
        binding.weatherCity.text = "${info.city} · 湿度 ${info.humidity}%"
        binding.weatherIcon.setImageResource(WeatherRepo.iconOf(info.code))
    }

    companion object {
        private const val REQUEST_STORAGE = 101
        private const val WEATHER_TTL = 30 * 60 * 1000L
    }
}
