package com.aitv.launcher.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import com.aitv.launcher.Prefs
import com.aitv.launcher.databinding.DialogQrUploadBinding
import com.aitv.launcher.server.UploadServer
import com.aitv.launcher.util.QrCode
import com.aitv.launcher.util.StorageUtils

/**
 * 扫码上传弹窗：打开即启动上传服务并显示二维码；
 * 关闭弹窗时立即停止服务，已上传的文件保留在相册目录。
 */
class QrUploadDialog(
    private val host: Activity,
    private val onClosed: () -> Unit
) : Dialog(host) {

    private lateinit var binding: DialogQrUploadBinding
    private var server: UploadServer? = null
    private var running = false
    private var portUsed = Prefs.uploadPort
    private var uploadCount = 0
    private var qrUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogQrUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnClose.requestFocus()
        setOnDismissListener {
            stopServer()
            onClosed()
        }
        startServer(Prefs.uploadPort)
    }

    private fun startServer(preferredPort: Int) {
        stopServer()
        val target = StorageUtils.photoTarget(host)
        var port = preferredPort.coerceIn(1024, 65535)
        var attempt = 0
        var lastError = ""
        while (attempt < 6) {
            try {
                val candidate = UploadServer(target, host, { name -> onUploaded(name) }, port)
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
        binding.qrImage.setImageBitmap(null)
    }

    private fun stopServer() {
        runCatching { server?.stop() }
        server = null
        running = false
    }

    private fun onUploaded(name: String) {
        host.runOnUiThread {
            uploadCount++
            // 服务端已对刚上传的文件做了单文件增量索引，这里只需要更新接收提示
            binding.statusText.text = "已接收 $uploadCount 个文件 · 最近：$name"
        }
    }

    private fun updateUi() {
        if (!running) {
            binding.urlText.text = "服务已停止"
            binding.statusText.text = "关闭弹窗后服务已结束"
            showQr(null)
            return
        }
        val ip = StorageUtils.localIpv4().firstOrNull()
        if (ip == null) {
            binding.urlText.text = "未连接 WiFi"
            binding.statusText.text = "请先让电视连上路由器，再重试"
            showQr(null)
            return
        }
        val url = "http://$ip:$portUsed"
        binding.urlText.text = url
        binding.statusText.text = "服务运行中 · 等待手机扫码上传"
        showQr(url)
    }

    private fun showQr(url: String?) {
        if (url == null) {
            qrUrl = null
            binding.qrImage.setImageBitmap(null)
            return
        }
        if (url == qrUrl) return
        val px = (220 * host.resources.displayMetrics.density).toInt()
        binding.qrImage.setImageBitmap(QrCode.encode(url, px))
        qrUrl = url
    }
}
