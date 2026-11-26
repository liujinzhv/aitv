package com.aitv.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.aitv.launcher.Prefs
import com.aitv.launcher.R
import com.aitv.launcher.databinding.ActivitySettingsBinding
import com.aitv.launcher.databinding.ItemSettingBinding
import com.aitv.launcher.lock.KioskController
import com.aitv.launcher.lock.LockAccessibilityService
import com.aitv.launcher.lock.LockController
import com.aitv.launcher.lock.LockState
import com.aitv.launcher.lock.QuestionBank
import com.aitv.launcher.media.ImageLoader
import com.aitv.launcher.media.M3uRepo
import com.aitv.launcher.media.MediaScanner
import com.aitv.launcher.media.VideoScraper
import com.aitv.launcher.util.dp
import com.aitv.launcher.util.focusScale
import com.aitv.launcher.util.formatSize
import com.aitv.launcher.util.immersive
import com.aitv.launcher.util.keepScreenOn
import com.aitv.launcher.util.toast
import com.aitv.launcher.weather.WeatherRepo
import com.aitv.launcher.web.BookmarkRepo
import java.util.Locale

class SettingsActivity : Activity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        keepScreenOn()

        // 家长验证：输对 4 位家长密码后才允许查看/修改设置，防止孩子关掉锁屏
        binding.settingRows.visibility = View.INVISIBLE
        PinGateDialog(
            this,
            onSuccess = {
                binding.settingRows.visibility = View.VISIBLE
                buildRows()
                binding.settingRows.post { binding.settingRows.getChildAt(0)?.requestFocus() }
            },
            onCancel = { finish() }
        ).show()
    }

    private fun buildRows() {
        binding.settingRows.removeAllViews()

        addRow(
            "天气定位",
            "开启后按系统定位 / IP 自动确定城市，关闭后使用下方手动城市与坐标",
            if (Prefs.autoLocation) "自动定位" else "手动城市"
        ) {
            Prefs.autoLocation = !Prefs.autoLocation
            if (Prefs.autoLocation) {
                Prefs.locationAt = 0L
                WeatherRepo.refresh(this, forceLocate = true) { info ->
                    runOnUiThread {
                        toast(if (info != null) "已定位到 ${Prefs.city}" else "定位失败，保留手动设置")
                        buildRows()
                    }
                }
            } else {
                toast("已切换为手动城市")
                buildRows()
            }
        }
        addRow("重新定位", "立即按当前位置更新城市与天气", "立即执行") {
            Prefs.autoLocation = true
            WeatherRepo.refresh(this, forceLocate = true) { info ->
                runOnUiThread {
                    MediaScanner.invalidate()
                    toast(if (info != null) "已定位到 ${Prefs.city}，天气 ${info.temp}°" else "定位失败，请检查网络")
                    buildRows()
                }
            }
        }
        addRow("天气城市", "桌面右上角显示的城市名；手动修改会关闭自动定位", Prefs.city) {
            edit("天气城市", Prefs.city) { value ->
                if (value.isNotBlank()) {
                    Prefs.city = value
                    Prefs.autoLocation = false
                }
                buildRows()
            }
        }
        addRow("纬度", "Open-Meteo 定位参数，西安约 34.3416", String.format(Locale.US, "%.4f", Prefs.latitude)) {
            edit("纬度", Prefs.latitude.toString(), numeric = true) { value ->
                value.toDoubleOrNull()?.let {
                    Prefs.latitude = it.coerceIn(-90.0, 90.0)
                    Prefs.autoLocation = false
                }
                buildRows()
            }
        }
        addRow("经度", "西安约 108.9398", String.format(Locale.US, "%.4f", Prefs.longitude)) {
            edit("经度", Prefs.longitude.toString(), numeric = true) { value ->
                value.toDoubleOrNull()?.let {
                    Prefs.longitude = it.coerceIn(-180.0, 180.0)
                    Prefs.autoLocation = false
                }
                buildRows()
            }
        }
        addRow(
            "TMDB API Key",
            "留空则只用本地 NFO / 文件名解析；有 Key 时自动抓海报和简介",
            Prefs.tmdbKey.ifBlank { "未配置" }
        ) {
            edit("TMDB API Key", Prefs.tmdbKey) { value ->
                Prefs.tmdbKey = value
                buildRows()
            }
        }
        addRow("音乐目录", "多个目录用 ; 分隔，留空自动扫描所有存储卷", Prefs.musicDirs.ifBlank { "自动" }) {
            edit("音乐目录", Prefs.musicDirs) { value ->
                Prefs.musicDirs = value
                MediaScanner.invalidate()
                buildRows()
            }
        }
        addRow("视频目录", "多个目录用 ; 分隔，留空自动扫描", Prefs.videoDirs.ifBlank { "自动" }) {
            edit("视频目录", Prefs.videoDirs) { value ->
                Prefs.videoDirs = value
                MediaScanner.invalidate()
                buildRows()
            }
        }
        addRow(
            "直播源",
            "一行一个 m3u / m3u8 地址或本地路径；Download 目录的 .m3u 也会自动识别",
            if (M3uRepo.sources().isEmpty()) "未配置" else "${M3uRepo.sources().size} 个源"
        ) {
            edit("直播源", Prefs.liveSources, multiline = true) { value ->
                Prefs.liveSources = value
                M3uRepo.invalidate()
                buildRows()
            }
        }
        addRow("相册 / 上传目录", "手机上传照片会存到该目录；留空自动选 U 盘 Photos", Prefs.photoDir.ifBlank { "自动（U 盘 Photos）" }) {
            edit("相册 / 上传目录", Prefs.photoDir) { value ->
                Prefs.photoDir = value
                MediaScanner.invalidate()
                buildRows()
            }
        }
        addRow("上传端口", "手机访问 http://电视IP:端口", Prefs.uploadPort.toString()) {
            edit("上传端口", Prefs.uploadPort.toString(), numeric = true) { value ->
                value.toIntOrNull()?.let { Prefs.uploadPort = it.coerceIn(1024, 65535) }
                buildRows()
            }
        }
        val homeRole = supportsHomeRole()
        addRow(
            "开机进入 智慧家庭TV",
            if (homeRole) {
                "开机后自动回到 智慧家庭TV；若被系统桌面接管，可在桌面选择框中选择 智慧家庭TV"
            } else {
                "当前安装包未声明为桌面候选，开机后由系统桌面接管；可从全部应用手动打开 智慧家庭TV"
            },
            when {
                isDefaultHome() -> "已设为默认桌面"
                homeRole -> "去设置"
                else -> "不可设为桌面"
            }
        ) {
            if (homeRole) openHomePicker() else explainHomeUnavailable()
        }
        addRow(
            "智慧锁屏",
            "开启后，智慧家庭TV 闲置到设定时间会自动全屏出题；答对才能回到桌面",
            if (Prefs.lockEnabled) "已开启" else "已关闭"
        ) {
            Prefs.lockEnabled = !Prefs.lockEnabled
            if (Prefs.lockEnabled && Prefs.lockPin.isBlank()) Prefs.lockPin = "1234"
            if (!Prefs.lockEnabled) {
                LockState.locked = false
                KioskController.stopLock(this)
            }
            LockController.touch()
            toast(
                when {
                    !Prefs.lockEnabled -> "智慧锁屏已关闭"
                    LockAccessibilityService.isEnabled(this) -> "智慧锁屏已开启 · 全系统守护生效"
                    else -> "智慧锁屏已开启；如需按 Home 也锁，请开启全系统守护"
                }
            )
            buildRows()
        }
        addRow(
            "开机自动锁定",
            "电视开机后立即进入答题锁屏；关闭后开机直接进入 智慧家庭TV 桌面",
            if (Prefs.lockOnBoot) "已开启" else "已关闭"
        ) {
            Prefs.lockOnBoot = !Prefs.lockOnBoot
            buildRows()
        }
        addRow(
            "全系统锁屏守护",
            "锁定时拦截 Home / 最近任务，切到其他应用会自动重新盖上答题页",
            if (LockAccessibilityService.isEnabled(this)) "已启用" else "未启用"
        ) {
            val ready = LockAccessibilityService.isEnabled(this)
            val message = if (ready) {
                "已启用全系统锁屏守护。\n\n" +
                    "智慧锁屏处于锁定状态时：\n" +
                    "· 按 Home、最近任务无法离开；\n" +
                    "· 切换到其他应用会被重新盖上答题页。\n\n" +
                    "服务不读取屏幕内容，也不模拟点击；只在锁定时拦截 Home/最近任务。"
            } else {
                "未启用全系统锁屏守护，按 Home 仍可能回到 TCL 桌面。\n\n" +
                    "请在电视「设置 → 无障碍」中打开「智慧家庭TV」；\n" +
                    "也可以把下面的服务追加到系统无障碍列表：\n\n" +
                    "$packageName/com.aitv.launcher.lock.LockAccessibilityService\n\n" +
                    "启用后无需重启，锁定时自动生效。"
            }
            AlertDialog.Builder(this)
                .setTitle(if (ready) "全系统守护已启用" else "全系统守护未启用")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show()
        }
        addRow(
            "自动锁屏时间",
            "只在 智慧家庭TV 前台时计时；关掉则只能从下方「立即锁定」手动触发",
            lockTimeoutLabel()
        ) {
            val labels = arrayOf("关闭自动锁屏", "1 分钟", "5 分钟", "10 分钟", "15 分钟", "30 分钟", "1 小时", "2 小时")
            val values = intArrayOf(0, 1, 5, 10, 15, 30, 60, 120)
            choose("自动锁屏时间", labels, values.indexOf(Prefs.lockTimeoutMinutes)) { index ->
                Prefs.lockTimeoutMinutes = values[index]
                LockController.touch()
                buildRows()
            }
        }
        addRow(
            "解锁难度",
            "学前 / 小班 / 中班 / 大班 / 一年级至九年级 / 高一至高三",
            Prefs.lockDifficulty
        ) {
            val labels = arrayOf(
                "学前", "小班", "中班", "大班",
                "一年级", "二年级", "三年级", "四年级", "五年级", "六年级",
                "七年级", "八年级", "九年级",
                "高一", "高二", "高三"
            )
            choose("选择解锁难度", labels, labels.indexOf(Prefs.lockDifficulty)) { index ->
                Prefs.lockDifficulty = labels[index]
                buildRows()
            }
        }
        addRow(
            "家长密码",
            "4 位数字，用于孩子答不出题时直接解锁；默认 1234",
            Prefs.lockPin.ifBlank { "未设置" }
        ) {
            edit("家长密码", Prefs.lockPin, numeric = true) { value ->
                if (value.matches(Regex("\\d{4}"))) {
                    Prefs.lockPin = value
                    toast("家长密码已更新")
                } else {
                    toast("请输入 4 位数字")
                }
                buildRows()
            }
        }
        addRow(
            "立即锁定",
            "先体验一次答题解锁，不会影响当前设置",
            "测试锁屏"
        ) {
            if (!Prefs.lockEnabled) {
                toast("请先开启智慧锁屏")
            } else {
                startActivity(Intent(this, LockScreenActivity::class.java))
            }
        }
        addRow(
            "锁屏题库",
            "离线题目，覆盖学前到高三；含文字、图片、视频题",
            "查看来源"
        ) {
            val count = QuestionBank.count()
            AlertDialog.Builder(this)
                .setTitle("锁屏题库 $count 题")
                .setMessage(
                    "CMATH · MIT 许可\n" +
                        "小学 1–6 年级数学，带标准答案，可填空或四选一。\n\n" +
                        "C-Eval · CC BY-NC-SA 4.0\n" +
                        "初中 / 高中学科选择题，覆盖数学、物理、化学、生物、语文、历史、地理、政治。\n\n" +
                        "学前、小班、中班、大班为内置自编启蒙题；图片题与视频题为自制素材，随 APK 离线打包。\n\n" +
                        "用途说明：仅用于家庭非商业场景。题目版权归原作者，若需公开分发请先核对上游许可证。"
                )
                .setPositiveButton("知道了", null)
                .show()
        }
        addRow(
            "桌面背景视频",
            "填视频文件绝对路径；留空则使用相册设置的图片或内置「阖家团圆」背景",
            Prefs.wallpaper.ifBlank { "内置团圆图" }
        ) {
            edit("桌面背景视频", Prefs.wallpaper) { value ->
                Prefs.wallpaper = value
                Prefs.wallpaperImage = ""
                buildRows()
            }
        }
        addRow(
            "恢复默认桌面背景",
            "清除自定义图片或视频，恢复内置「阖家团圆」背景",
            "立即恢复"
        ) {
            Prefs.wallpaper = ""
            Prefs.wallpaperImage = ""
            toast("已恢复默认团圆背景")
            buildRows()
        }
        addRow("每行应用数", "4 / 5 / 6 列，影响「全部应用」网格", "${Prefs.columns} 列") {
            edit("每行应用数", Prefs.columns.toString(), numeric = true) { value ->
                value.toIntOrNull()?.let { Prefs.columns = it }
                buildRows()
            }
        }
        addRow("重新扫描媒体库", "清除音频 / 视频 / 图片扫描缓存", "立即执行") {
            MediaScanner.invalidate()
            ImageLoader.clear()
            toast("已清除扫描缓存")
        }
        addRow("清除刮削缓存", "删除视频元数据、海报与抽帧缩略图", "立即执行") {
            VideoScraper.clearMeta(this)
            VideoScraper.clearArtwork(this)
            ImageLoader.clear()
            MediaScanner.invalidate()
            toast("刮削缓存已清除")
        }
        addRow(
            "网页浏览器",
            "输入网址、收藏网页、下载直链 / 迅雷链接；收藏会出现在首页应用行",
            if (BookmarkRepo.all().isEmpty()) "打开" else "已收藏 ${BookmarkRepo.all().size} 个"
        ) {
            startActivity(android.content.Intent(this, BrowserActivity::class.java))
        }
        addRow("关于 智慧家庭TV", "Android 8 TV 桌面 · 本地媒体中心", "版本 1.3.1") {
            AlertDialog.Builder(this)
                .setTitle("智慧家庭TV 1.3.1")
                .setMessage(
                    "桌面：时钟 / 农历日期 / 按定位获取的天气 / 网站收藏 + 应用入口\n" +
                        "智慧锁屏：开关、自动锁屏时间、16 档难度、选择题 / 填空题、图片题 / 视频题、家长密码\n" +
                        "音乐：mp3 / flac / wav 等扫描 + 自定义歌单 + 顺序 / 随机播放 + LRC 同步\n" +
                        "影视：只保留本地视频，点击海报直接播放\n" +
                        "电视直播：读取配置的 m3u / m3u8，按分组展示全部频道，频道信息与台标本地缓存\n" +
                        "相册：EXIF / 文件名 / 文件时间解析拍摄日期，按日期分组 + 手机直传\n" +
                        "浏览器：网址浏览、收藏到首页、迅雷 thunder:// 与直链下载\n\n" +
                        "存储占用：" + formatSize(cacheSize())
                )
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(home, 0) ?: return false
        return resolved.activityInfo?.packageName == packageName
    }

    /** 本安装包是否声明了桌面（CATEGORY_HOME）候选角色。 */
    private fun supportsHomeRole(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(home, 0)
            .any { it.activityInfo?.packageName == packageName }
    }

    private fun explainHomeUnavailable() {
        AlertDialog.Builder(this)
            .setTitle("当前版本不能设为桌面")
            .setMessage(
                "这个安装包没有声明桌面入口（HOME 类别），系统桌面选择框里不会出现 智慧家庭TV，\n" +
                    "开机后会先进入电视自带桌面。\n\n" +
                    "· 开机自启仍由开机广播负责：锁屏开启时先进答题页，解锁后进入 智慧家庭TV；\n" +
                    "· 如需把 智慧家庭TV 设为默认桌面，请安装恢复了桌面类别的电视版安装包。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun openHomePicker() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        try {
            // createChooser 会强制弹出桌面选择框，即使电视已经设过默认桌面。
            startActivity(
                Intent.createChooser(home, "选择默认桌面")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t2: Throwable) {
                toast("请到电视「设置 → 应用 → 默认应用」中把 智慧家庭TV 设为桌面")
            }
        }
    }

    private fun lockTimeoutLabel(): String = when (Prefs.lockTimeoutMinutes) {
        0 -> "不自动锁"
        1 -> "1 分钟"
        5 -> "5 分钟"
        10 -> "10 分钟"
        15 -> "15 分钟"
        30 -> "30 分钟"
        60 -> "1 小时"
        else -> "2 小时"
    }

    private fun choose(
        title: String,
        labels: Array<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addRow(title: String, desc: String, value: String, onClick: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.item_setting, binding.settingRows, false)
        val row = ItemSettingBinding.bind(view)
        row.settingTitle.text = title
        row.settingDesc.text = desc
        row.settingValue.text = value
        view.focusScale(1.02f, 6f)
        view.setOnClickListener { onClick() }
        binding.settingRows.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun edit(
        title: String,
        initial: String,
        numeric: Boolean = false,
        multiline: Boolean = false,
        onSave: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            if (numeric) {
                inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 4
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24f), dp(8f), dp(24f), 0)
        }
        container.addView(
            input,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("保存") { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun cacheSize(): Long {
        fun sizeOf(dir: java.io.File?): Long {
            if (dir == null || !dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        return sizeOf(java.io.File(filesDir, "thumbs")) +
            sizeOf(java.io.File(filesDir, "artwork")) +
            sizeOf(java.io.File(filesDir, "lyrics")) +
            sizeOf(java.io.File(filesDir, "meta"))
    }
}
