package com.mytv.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.mytv.launcher.databinding.ActivityBrowserBinding
import com.mytv.launcher.web.BookmarkRepo
import com.mytv.launcher.web.DownloadHelper

/** 大屏浏览器：地址栏、收藏、迅雷/直链下载。 */
class BrowserActivity : Activity() {

    companion object {
        const val EXTRA_URL = "browser_url"
    }

    private lateinit var binding: ActivityBrowserBinding
    private var pageTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWebView()
        setupBar()
        val initial = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        if (initial.isNotEmpty()) {
            load(initial)
        } else {
            binding.statusText.text = "输入网址，或从首页收藏进入"
            binding.urlInput.requestFocus()
        }
    }

    private fun setupWebView() {
        val web = binding.webView
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " MyTV/1.1"
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url?.trim().orEmpty()
                if (target.startsWith("thunder://", true) || target.startsWith("magnet:", true)) {
                    requestDownload(target)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                binding.statusText.text = "加载中…"
                updateBookmarkState()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) binding.urlInput.setText(url)
                binding.statusText.text = if (pageTitle.isNotEmpty()) pageTitle else url.orEmpty()
                updateBookmarkState()
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    binding.statusText.text = "加载失败：${error?.description ?: "网络不可达"}"
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) binding.statusText.text = "加载中 $newProgress%"
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                pageTitle = title?.trim().orEmpty()
                if (pageTitle.isNotEmpty()) binding.statusText.text = pageTitle
            }
        }
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:", true) || url.startsWith("data:", true)) {
                toast("该链接类型暂不支持下载")
            } else {
                requestDownload(url)
            }
        }
    }

    private fun setupBar() {
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
        }
        binding.btnGo.setOnClickListener { load(binding.urlInput.text.toString()) }
        binding.urlInput.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                load(binding.urlInput.text.toString())
                true
            } else {
                false
            }
        }
        binding.btnBookmark.setOnClickListener { toggleBookmark() }
        binding.btnDownload.setOnClickListener { requestDownload(currentUrl()) }
    }

    private fun load(raw: String) {
        val url = BookmarkRepo.normalize(raw)
        if (url.isEmpty()) {
            toast("请输入网址")
            return
        }
        pageTitle = ""
        binding.urlInput.setText(url)
        binding.urlInput.clearFocus()
        binding.webView.requestFocus()
        binding.webView.loadUrl(url)
    }

    private fun currentUrl(): String {
        val typed = binding.urlInput.text.toString().trim()
        if (typed.startsWith("http://", true) || typed.startsWith("https://", true) ||
            typed.startsWith("thunder://", true) || typed.startsWith("magnet:", true)
        ) {
            return typed
        }
        return binding.webView.url ?: typed
    }

    private fun toggleBookmark() {
        val url = currentUrl()
        if (url.isEmpty() || !url.startsWith("http", true)) {
            toast("当前没有可收藏的网页")
            return
        }
        if (BookmarkRepo.exists(url)) {
            BookmarkRepo.remove(url)
            toast("已取消收藏")
        } else {
            val name = pageTitle.ifEmpty { BookmarkRepo.hostOf(url) }
            if (BookmarkRepo.add(name, url)) {
                toast("已收藏：$name")
            } else {
                toast("收藏失败")
            }
        }
        updateBookmarkState()
    }

    private fun updateBookmarkState() {
        val url = currentUrl()
        val saved = BookmarkRepo.exists(url)
        binding.btnBookmark.text = if (saved) "已收藏" else "收藏"
        binding.btnBookmark.isSelected = saved
    }

    private fun requestDownload(raw: String) {
        val url = raw.trim()
        if (url.isEmpty()) {
            toast("没有可下载的链接")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("下载到本地")
            .setMessage(if (url.startsWith("thunder://", true)) "解析迅雷链接并加入系统下载？" else "将链接加入系统下载？\n$url")
            .setPositiveButton("下载") { _, _ ->
                val result = DownloadHelper.enqueue(this, url)
                toast(result.message)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let { load(it) }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
