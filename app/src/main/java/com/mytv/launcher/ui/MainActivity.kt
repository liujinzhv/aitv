package com.mytv.launcher.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mytv.launcher.Prefs
import com.mytv.launcher.R
import com.mytv.launcher.databinding.ActivityMainBinding
import com.mytv.launcher.databinding.ViewFeatureCardBinding
import com.mytv.launcher.media.ImageLoader
import com.mytv.launcher.media.LiveRepo
import com.mytv.launcher.util.dp
import com.mytv.launcher.util.featureCardBackground
import com.mytv.launcher.util.focusScale
import com.mytv.launcher.util.immersive
import com.mytv.launcher.util.keepScreenOn
import com.mytv.launcher.util.toast
import com.mytv.launcher.weather.WeatherInfo
import com.mytv.launcher.weather.WeatherRepo
import android.icu.util.ChineseCalendar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    private var wallPlayer: ExoPlayer? = null
    private var wallVideoPath: String? = null
    private var weatherFreshAt: Long = 0L

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        immersive()
        keepScreenOn()
        requestStoragePermission()

        updateClock()
        setupWallpaper()
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
        wallPlayer?.play()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        wallPlayer?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseWallPlayer()
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

    private fun setupWallpaper() {
        val path = Prefs.wallpaper.trim()
        val file = if (path.isNotEmpty()) File(path) else null
        if (file == null || !file.exists() || file.length() <= 0) {
            releaseWallPlayer()
            binding.wallpaperPlayer.visibility = View.GONE
            binding.homeWallpaperImage.visibility = View.VISIBLE
            binding.bgLayer.visibility = View.GONE
            applyWallpaperImage()
            return
        }

        if (wallPlayer != null && wallVideoPath == path) {
            binding.wallpaperPlayer.visibility = View.VISIBLE
            binding.homeWallpaperImage.visibility = View.GONE
            binding.bgLayer.visibility = View.GONE
            return
        }

        releaseWallPlayer()
        binding.wallpaperPlayer.visibility = View.VISIBLE
        binding.homeWallpaperImage.visibility = View.GONE
        binding.bgLayer.visibility = View.GONE

        val player = ExoPlayer.Builder(this).build()
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.volume = 0f
        player.playWhenReady = true
        player.prepare()
        binding.wallpaperPlayer.player = player
        wallPlayer = player
        wallVideoPath = path
    }

    private fun applyWallpaperImage() {
        val custom = File(Prefs.wallpaperImage)
        if (Prefs.wallpaperImage.isNotBlank() && custom.exists() && custom.length() > 0) {
            ImageLoader.loadFile(
                binding.homeWallpaperImage,
                custom.absolutePath,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels
            )
        } else {
            binding.homeWallpaperImage.setImageResource(R.drawable.home_reunion)
        }
    }

    private fun releaseWallPlayer() {
        wallPlayer?.let {
            it.stop()
            it.release()
        }
        wallPlayer = null
        wallVideoPath = null
        binding.wallpaperPlayer.player = null
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
            setMargins(dp(6f), 0, dp(6f), 0)
        }
        view.layoutParams = lp

        val card = ViewFeatureCardBinding.bind(view)
        card.cardIcon.setImageResource(iconRes)
        card.cardTitle.text = title

        view.background = featureCardBackground(this, color1, color2)
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
            (Prefs.autoLocation && !com.mytv.launcher.weather.LocationRepo.isFresh())
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
