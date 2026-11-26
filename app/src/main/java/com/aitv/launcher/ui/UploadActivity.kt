package com.aitv.launcher.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.aitv.launcher.Prefs
import com.aitv.launcher.databinding.ActivityUploadBinding
import com.aitv.launcher.media.MediaScanner
import com.aitv.launcher.server.UploadServer
import com.aitv.launcher.util.StorageUtils
import com.aitv.launcher.util.immersive
import com.aitv.launcher.util.keepScreenOn
import com.aitv.launcher.util.photoPathText
import com.aitv.launcher.util.toast
import java.io.File

class UploadActivity : Activity() {

    private lateinit var binding: ActivityUploadBinding
    private var server: UploadServer? = null
    private var running = false
    private var portUsed = Prefs.uploadPort
    private var uploadCount = 0
    private var targetDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        keepScreenOn()

        targetDir = StorageUtils.photoTarget(this)
        binding.pathText.text = photoPathText(this)

        binding.btnToggle.setOnClickListener {
            if (running) stopServer() else startServer(Prefs.uploadPort)
        }
        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        if (!StorageUtils.hasWritePermission(this)) {
            binding.statusText.text = "未授予存储权限，写入 U 盘可能失败"
        }
        startServer(Prefs.uploadPort)
        binding.btnToggle.requestFocus()
    }

    private fun startServer(preferredPort: Int) {
        stopServer()
        val target = targetDir ?: StorageUtils.photoTarget(this)
        var port = preferredPort.coerceIn(1024, 65535)
        var attempt = 0
        var lastError: String = ""

        while (attempt < 6) {
            try {
                val candidate = UploadServer(target, this, { name -> onUploaded(name) }, port)
                candidate.start()
                server = candidate
                running = true
                portUsed = port
                updateUi()
                return
            } catch (t: Throwable) {
                lastError = t.message ?: "端口被占用"
                attempt++
                port++
            }
        }

        running = false
        server = null
        binding.urlText.text = "启动失败"
        binding.statusText.text = "无法监听端口：$lastError"
    }

    private fun stopServer() {
        runCatching { server?.stop() }
        server = null
        running = false
        if (::binding.isInitialized) updateUi()
    }

    private fun onUploaded(name: String) {
        runOnUiThread {
            uploadCount++
            MediaScanner.invalidate()
            binding.statusText.text = "服务运行中 · 已接收 $uploadCount 个文件 · 最近：$name"
        }
    }

    private fun updateUi() {
        if (!running) {
            binding.urlText.text = "服务已停止"
            binding.statusText.text = "点「启动服务」后，用手机浏览器访问上方地址"
            binding.btnToggle.text = "启动服务"
            return
        }

        val ip = StorageUtils.localIpv4().firstOrNull()
        if (ip == null) {
            binding.urlText.text = "未连接 WiFi"
            binding.statusText.text = "请先让电视连上路由器，再重启服务"
        } else {
            binding.urlText.text = "http://$ip:$portUsed"
            binding.statusText.text = "服务运行中 · 保存到 U 盘相册目录"
        }
        binding.btnToggle.text = "停止服务"
        binding.pathText.text = photoPathText(this)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
