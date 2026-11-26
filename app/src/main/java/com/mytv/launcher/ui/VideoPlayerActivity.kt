package com.mytv.launcher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flv.FlvExtractor
import com.mytv.launcher.databinding.ActivityVideoPlayerBinding
import com.mytv.launcher.util.immersive
import com.mytv.launcher.util.keepScreenOn
import java.io.File
import java.util.concurrent.TimeUnit

class VideoPlayerActivity : Activity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var streamUrl: String? = null
    private var localFile: File? = null
    private var isLiveMode = false
    private var isVodClip = false
    private lateinit var httpClient: okhttp3.OkHttpClient
    private lateinit var dataSourceFactory: OkHttpDataSource.Factory
    private lateinit var playbackSourceFactory: DataSource.Factory
    private var resolvedUrl: String? = null
    private var resolvedMime: String? = null
    private var resolveDone = false
    private var resolving = false
    private var webMode = false

    private var retryCount = 0
    private var readyAt = 0L
    private var triedCompatDecode = false
    private var handedToSystemPlayer = false
    private val handler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { startPlayback() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        keepScreenOn()

        streamUrl = intent.getStringExtra(EXTRA_URL)
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        isLiveMode = intent.getBooleanExtra(EXTRA_LIVE, false)

        val file = if (streamUrl == null) File(path) else null
        localFile = file
        isVodClip = streamUrl?.let { isMp4(it) } == true
        if (file != null && !file.exists()) {
            showStatus("视频不存在：$path")
            return
        }

        // 电视上的 HLS 源对 UA / 重定向比较敏感，统一走 OkHttp 并伪装成浏览器
        httpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        dataSourceFactory = OkHttpDataSource.Factory(httpClient)
            .setUserAgent(BROWSER_UA)
        // 本地 file:// / content:// 必须交给 DefaultDataSource，直连 OkHttp 会报 Malformed URL
        playbackSourceFactory = DefaultDataSource.Factory(this, dataSourceFactory)
        buildPlayer(compatDecode = false)
        startPlayback()
    }

    private fun buildPlayer(compatDecode: Boolean) {
        // 默认优先硬件解码并开回退；兼容模式改用同步 MediaCodec，避开个别电视芯片的异步解码问题
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        if (compatDecode) {
            renderers.forceDisableMediaCodecAsynchronousQueueing()
        }
        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(playbackSourceFactory))
            .build()
        player = exo
        binding.playerView.player = exo
        binding.playerView.controllerShowTimeoutMs = 4000
        binding.playerView.setControllerAutoShow(true)
        exo.addListener(playerListener)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = handlePlayerError(error)

        override fun onPlaybackStateChanged(playbackState: Int) = handlePlaybackState(playbackState)
    }

    private fun handlePlayerError(error: PlaybackException) {
        // 解码类错误：本地视频 / 点播 mp4 先用兼容解码重试一次
        val canRetryCompat = isDecodeError(error) && !triedCompatDecode &&
            (localFile != null || (isLiveMode && isVodClip))
        if (canRetryCompat) {
            triedCompatDecode = true
            retryWithCompatDecode()
            return
        }
        if (!isLiveMode) {
            val file = localFile
            if (file != null) {
                handOffToSystemPlayer("内置解码器不支持这个视频（${error.errorCodeName}）")
                return
            }
            showStatus("无法播放（${error.errorCodeName}）\n该格式可能不被内置播放器支持，可返回影视库换一个视频")
            return
        }
        if (isVodClip) {
            showStatus("点播视频播放失败（${error.errorCodeName}）\n按返回键换一个频道")
            return
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
            showStatus(
                if (isFlvStream()) "这个 FLV 的编码内置播放器不支持（需 H.264/AAC）\n按返回键换台"
                else "直播源格式无法解析\n按返回键换台"
            )
            return
        }
        if (retryCount >= MAX_RETRY) {
            showStatus("直播源无法连接（${error.errorCodeName}）\n已重试 $MAX_RETRY 次，按返回键换台")
            return
        }
        scheduleRetry("直播连接中断")
    }

    private fun handlePlaybackState(playbackState: Int) {
        val exo = player ?: return
        when (playbackState) {
            Player.STATE_READY -> {
                binding.errorText.visibility = View.GONE
                hideLoading()
                readyAt = SystemClock.elapsedRealtime()
                // 直播源里常混入有限时长的 HLS 点播片段，播完不要按直播反复重连
                if (isLiveMode && !isVodClip && !exo.isCurrentMediaItemLive) {
                    isVodClip = true
                }
            }

            Player.STATE_BUFFERING -> {
                if (!webMode) {
                    showLoading(if (readyAt > 0) "正在缓冲…" else "正在加载…")
                }
            }

            Player.STATE_ENDED -> {
                hideLoading()
                if (!isLiveMode) return
                if (isVodClip) {
                    showStatus("点播视频已播放结束\n按返回键换一个频道")
                    return
                }
                if (retryCount >= MAX_RETRY) {
                    showStatus("直播已断开，重连失败\n按返回键换一个频道")
                    return
                }
                scheduleRetry("直播已断开")
            }
        }
    }

    /** 解码失败时用同步 MediaCodec 重试一次，不少电视芯片的异步解码在个别编码上会直接失败 */
    private fun retryWithCompatDecode() {
        val pos = player?.currentPosition ?: 0L
        showLoading("编码不兼容，正在用兼容解码重试…")
        player?.release()
        player = null
        buildPlayer(compatDecode = true)
        startPlayback()
        if (pos > 0) player?.seekTo(pos)
    }

    private fun isDecodeError(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> true

        else -> false
    }

    /** 内置 ExoPlayer 解不了时交给电视自带的播放器，能播的编码由系统播放器兜住 */
    private fun handOffToSystemPlayer(reason: String) {
        val file = localFile ?: return
        if (handedToSystemPlayer) return
        player?.pause()
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull()
        if (uri == null) {
            showStatus("$reason\n文件路径无法共享给系统播放器，可换一个视频")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val launched = runCatching { startActivity(intent) }.isSuccess
        if (!launched) {
            showStatus("$reason\n本机没有可用的系统播放器，可换一个视频或换 H.264 / MKV 版本")
            return
        }
        handedToSystemPlayer = true
        showStatus("$reason\n已交给系统播放器打开，返回本页可换其他视频")
    }

    private fun startPlayback() {
        val exo = player ?: return
        if (webMode) return
        val raw = streamUrl.orEmpty()
        if (localFile == null && isLiveMode && !resolveDone && needsResolve(raw)) {
            resolveNonDirect(raw)
            return
        }
        binding.errorText.visibility = View.GONE
        showLoading("正在加载…")
        val item = buildMediaItem()
        if (isFlvStream()) {
            // Media3 默认解析器不注册 FLV，必须显式挂 FlvExtractor
            val flvFactory = ProgressiveMediaSource.Factory(
                playbackSourceFactory,
                ExtractorsFactory { arrayOf(FlvExtractor()) }
            )
            exo.setMediaSource(flvFactory.createMediaSource(item))
        } else {
            exo.setMediaItem(item)
        }
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun isFlvStream(): Boolean {
        val url = resolvedUrl ?: streamUrl.orEmpty()
        return resolvedMime == MimeTypes.VIDEO_FLV ||
            url.substringBefore('?').lowercase().endsWith(".flv")
    }

    /** 网页 / 跳转 / FLV 等非直链：先跟随重定向拿到真实流地址，再决定用播放器还是网页。 */
    private fun needsResolve(url: String): Boolean {
        if (url.isBlank() || url.startsWith("file")) return false
        val path = Uri.parse(url).path?.lowercase().orEmpty()
        val ext = path.substringAfterLast('.', "")
        if (url.contains("m3u8", true)) return false
        return ext !in setOf("mp4", "ts", "webm", "mkv", "m3u", "mp3", "aac")
    }

    private fun resolveNonDirect(url: String) {
        if (resolving) return
        resolving = true
        showLoading("正在解析直播地址…")
        Thread {
            var finalUrl = url
            var contentType = ""
            var code = 0
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "*/*")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    code = response.code
                    finalUrl = response.request.url.toString()
                    contentType = response.header("Content-Type").orEmpty().lowercase()
                    Log.d("MyTVPlayer", "resolve code=$code type=$contentType final=$finalUrl")
                }
            } catch (_: Throwable) {
            }
            runOnUiThread {
                resolving = false
                resolveDone = true
                when {
                    finalUrl.contains("error_account_pirated", true) -> {
                        showStatus("这个频道源站返回「账号失效」占位视频\n按返回键换台")
                    }
                    code >= 400 && code != 0 -> {
                        showStatus("直播源返回 HTTP $code，无法打开\n按返回键换台")
                    }
                    contentType.contains("flv") || finalUrl.substringBefore('?').endsWith(".flv") -> {
                        resolvedUrl = finalUrl
                        resolvedMime = MimeTypes.VIDEO_FLV
                        applyReferer(finalUrl)
                        startPlayback()
                    }
                    contentType.contains("mpegurl") || finalUrl.contains("m3u8", true) -> {
                        resolvedUrl = finalUrl
                        resolvedMime = MimeTypes.APPLICATION_M3U8
                        startPlayback()
                    }
                    contentType.contains("mp4") || finalUrl.substringBefore('?').endsWith(".mp4") -> {
                        resolvedUrl = finalUrl
                        resolvedMime = MimeTypes.VIDEO_MP4
                        isVodClip = true
                        startPlayback()
                    }
                    contentType.contains("html") || contentType.contains("text/") || contentType.isBlank() -> {
                        openWeb(url)
                    }
                    else -> {
                        // 没有明确类型时交给 ExoPlayer 自行探测，避免误判
                        resolvedUrl = finalUrl
                        startPlayback()
                    }
                }
            }
        }.start()
    }

    private fun applyReferer(url: String) {
        val referer = when {
            url.contains("douyu", true) -> "https://www.douyu.com/"
            url.contains("huya", true) -> "https://www.huya.com/"
            else -> null
        } ?: return
        dataSourceFactory.setDefaultRequestProperties(mapOf("Referer" to referer))
    }

    private fun openWeb(url: String) {
        webMode = true
        player?.pause()
        binding.playerView.visibility = View.GONE
        binding.errorText.visibility = View.GONE
        hideLoading()
        val web = binding.webView
        web.visibility = View.VISIBLE
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.userAgentString = BROWSER_UA
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        web.loadUrl(url)
    }

    private fun scheduleRetry(prefix: String) {
        resetRetryIfPlayedLongEnough()
        readyAt = 0L
        retryCount++
        showStatus("$prefix，正在重连（$retryCount/$MAX_RETRY）")
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
    }

    private fun resetRetryIfPlayedLongEnough() {
        if (readyAt > 0 && SystemClock.elapsedRealtime() - readyAt >= STABLE_PLAY_MS) {
            retryCount = 0
        }
    }

    private fun showLoading(text: String) {
        if (webMode) return
        binding.loadingText.text = text
        binding.loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        binding.loadingOverlay.visibility = View.GONE
    }

    private fun showStatus(text: String) {
        hideLoading()
        binding.errorText.text = text
        binding.errorText.visibility = View.VISIBLE
    }

    private fun buildMediaItem(): MediaItem {
        val file = localFile
        if (file != null) return buildLocalMediaItem(file)

        val url = resolvedUrl ?: streamUrl.orEmpty()
        val builder = MediaItem.Builder().setUri(Uri.parse(url))
        val mime = resolvedMime
        if (mime != null) {
            builder.setMimeType(mime)
        } else if (isLiveMode && !isVodClip && looksLikeHls(url)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
    }

    private fun looksLikeHls(url: String): Boolean {
        if (url.contains("m3u8", true) || url.contains("/hls/", true)) return true
        val path = Uri.parse(url).path?.lowercase().orEmpty()
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true
        val ext = path.substringAfterLast('.', "")
        return ext.isEmpty() || ext in setOf("php", "asp", "aspx", "jsp", "do", "cgi")
    }

    private fun isMp4(url: String): Boolean {
        val path = Uri.parse(url).path?.lowercase().orEmpty()
        return path.endsWith(".mp4")
    }

    private fun buildLocalMediaItem(file: File): MediaItem {
        val subtitles = mutableListOf<MediaItem.SubtitleConfiguration>()
        val base = file.nameWithoutExtension
        file.parentFile?.listFiles()?.forEach { candidate ->
            if (!candidate.name.startsWith("$base.")) return@forEach
            val ext = candidate.extension.lowercase()
            val mime = when (ext) {
                "srt" -> MimeTypes.APPLICATION_SUBRIP
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt" -> MimeTypes.TEXT_VTT
                else -> null
            }
            if (mime != null) {
                subtitles.add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(candidate))
                        .setMimeType(mime)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            }
        }

        val builder = MediaItem.Builder().setUri(Uri.fromFile(file))
        if (subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(subtitles)
        }
        return builder.build()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_MENU && localFile != null) {
            // 菜单键：本地视频随时可以改用电视自带的播放器打开
            handedToSystemPlayer = false
            handOffToSystemPlayer("按菜单键改用系统播放器")
            return true
        }
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE)
        ) {
            if (webMode && binding.webView.canGoBack()) {
                binding.webView.goBack()
                return true
            }
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (webMode && binding.webView.canGoBack()) {
                binding.webView.goBack()
                return true
            }
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "video_path"
        const val EXTRA_TITLE = "video_title"
        const val EXTRA_URL = "video_url"
        const val EXTRA_LIVE = "video_live"

        private const val MAX_RETRY = 3
        private const val RETRY_DELAY_MS = 2500L
        private const val STABLE_PLAY_MS = 20_000L
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 8.0.0; SmartTV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
