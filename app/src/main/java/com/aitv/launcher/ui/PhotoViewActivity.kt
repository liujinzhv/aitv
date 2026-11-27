package com.aitv.launcher.ui

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import com.aitv.launcher.databinding.ActivityPhotoViewBinding
import com.aitv.launcher.media.ImageLoader
import com.aitv.launcher.util.Async
import com.aitv.launcher.util.WallpaperStore
import com.aitv.launcher.util.immersive
import com.aitv.launcher.util.keepScreenOn
import com.aitv.launcher.util.toast
import java.io.File

class PhotoViewActivity : Activity() {

    private lateinit var binding: ActivityPhotoViewBinding
    private var photoPath = ""
    private var settingWallpaper = false
    private var longPressHandled = false
    private var shownBitmap: android.graphics.Bitmap? = null

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
                if (isFinishing || isDestroyed) {
                    bitmap?.recycle()
                    return@runOnUiThread
                }
                if (bitmap != null) {
                    binding.zoomImage.setImageDrawable(null)
                    shownBitmap?.recycle()
                    shownBitmap = bitmap
                    binding.zoomImage.setImageBitmap(bitmap)
                } else {
                    toast("无法显示这张图片")
                }
            }
        }
    }

    override fun onDestroy() {
        binding.zoomImage.setImageDrawable(null)
        shownBitmap?.recycle()
        shownBitmap = null
        super.onDestroy()
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
        toast("正在设为桌面和智慧锁屏壁纸…")
        Async.io {
            // 电视没有系统 wallpaper 服务，改为复制到应用私有目录，首页和锁屏直接读取。
            val ok = WallpaperStore.setImageFromFile(this, source)
            runOnUiThread {
                settingWallpaper = false
                toast(if (ok) "已设为桌面和智慧锁屏壁纸" else "设置失败，请换一张图片试试")
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "photo_path"
    }
}
