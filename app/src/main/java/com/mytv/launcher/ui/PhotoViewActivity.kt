package com.mytv.launcher.ui

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import com.mytv.launcher.Prefs
import com.mytv.launcher.databinding.ActivityPhotoViewBinding
import com.mytv.launcher.media.ImageLoader
import com.mytv.launcher.util.Async
import com.mytv.launcher.util.immersive
import com.mytv.launcher.util.keepScreenOn
import com.mytv.launcher.util.toast
import java.io.File
import java.io.FileOutputStream

class PhotoViewActivity : Activity() {

    private lateinit var binding: ActivityPhotoViewBinding
    private var photoPath = ""
    private var settingWallpaper = false
    private var longPressHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        keepScreenOn()

        binding.zoomImage.isFocusable = true
        binding.zoomImage.isFocusableInTouchMode = true
        binding.zoomImage.setOnKeyListener { _, keyCode, event ->
            handleWallpaperKey(keyCode, event)
        }
        binding.zoomImage.post { binding.zoomImage.requestFocus() }

        photoPath = intent.getStringExtra(EXTRA_PATH) ?: ""
        val file = File(photoPath)
        if (!file.exists()) {
            toast("图片不存在")
            finish()
            return
        }
        binding.photoName.text = file.name

        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        Async.io {
            val bitmap = ImageLoader.decode(photoPath, width, height)
            runOnUiThread {
                if (bitmap != null) {
                    binding.zoomImage.setImageBitmap(bitmap)
                } else {
                    toast("无法显示这张图片")
                }
            }
        }
    }

    private fun handleWallpaperKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) setAsWallpaper()
            return true
        }
        val center = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        if (!center) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.isLongPress) {
            longPressHandled = true
            setAsWallpaper()
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && longPressHandled) {
            longPressHandled = false
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return handleWallpaperKey(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return handleWallpaperKey(keyCode, event) || super.onKeyUp(keyCode, event)
    }

    private fun setAsWallpaper() {
        if (settingWallpaper) return
        val source = File(photoPath)
        if (!source.exists()) {
            toast("图片不存在")
            return
        }
        settingWallpaper = true
        toast("正在设为桌面背景…")
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        Async.io {
            var saved: File? = null
            val bitmap = ImageLoader.decode(photoPath, width, height)
            if (bitmap != null) {
                val dest = File(filesDir, "home_wallpaper_${System.currentTimeMillis()}.jpg")
                try {
                    FileOutputStream(dest).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
                    }
                    saved = dest
                } catch (_: Throwable) {
                    dest.delete()
                } finally {
                    bitmap.recycle()
                }
            }
            val result = saved
            runOnUiThread {
                settingWallpaper = false
                if (result != null) {
                    val old = File(Prefs.wallpaperImage)
                    Prefs.wallpaperImage = result.absolutePath
                    Prefs.wallpaper = ""
                    if (old.parentFile == filesDir) old.delete()
                    toast("已设为桌面背景")
                } else {
                    toast("设置失败，换一张图片试试")
                }
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "photo_path"
    }
}
