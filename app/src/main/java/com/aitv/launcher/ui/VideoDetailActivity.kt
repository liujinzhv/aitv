package com.aitv.launcher.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.aitv.launcher.databinding.ActivityVideoDetailBinding
import com.aitv.launcher.media.ImageLoader
import com.aitv.launcher.media.MediaScanner
import com.aitv.launcher.media.VideoScraper
import com.aitv.launcher.model.VideoItem
import com.aitv.launcher.util.Async
import com.aitv.launcher.util.formatDuration
import com.aitv.launcher.util.formatSize
import com.aitv.launcher.util.immersive
import com.aitv.launcher.util.keepScreenOn
import com.aitv.launcher.util.toast
import java.util.Locale

class VideoDetailActivity : Activity() {

    private lateinit var binding: ActivityVideoDetailBinding
    private var item: VideoItem? = null
    private var requestPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        keepScreenOn()

        requestPath = intent.getStringExtra(EXTRA_PATH) ?: ""
        if (requestPath.isBlank()) {
            toast("缺少视频路径")
            finish()
            return
        }

        val initial = MediaScanner.videoByPath(this, requestPath)
        if (initial == null) {
            toast("视频不存在：$requestPath")
            finish()
            return
        }
        item = initial
        bind(initial)
        binding.btnPlay.requestFocus()

        binding.btnPlay.setOnClickListener {
            startActivity(
                Intent(this, VideoPlayerActivity::class.java)
                    .putExtra(VideoPlayerActivity.EXTRA_PATH, requestPath)
                    .putExtra(VideoPlayerActivity.EXTRA_TITLE, item?.title ?: "")
            )
        }

        Async.io {
            val enriched = VideoScraper.enrich(this, initial)
            runOnUiThread {
                if (requestPath == enriched.path) {
                    item = enriched
                    bind(enriched)
                }
            }
        }
    }

    private fun bind(video: VideoItem) {
        binding.title.text = video.title
        binding.overview.text = video.overview ?: ""
        binding.overview.visibility = if (video.overview.isNullOrBlank()) View.GONE else View.VISIBLE

        val meta = mutableListOf<String>()
        video.year?.let { meta.add("$it") }
        if (video.season != null || video.episode != null) {
            val season = video.season?.let { "第 ${it} 季" } ?: ""
            val episode = video.episode?.let { "第 ${it} 集" } ?: ""
            meta.add(season + episode)
        }
        if (video.durationMs > 0) meta.add(formatDuration(video.durationMs))
        if (video.width > 0 && video.height > 0) meta.add("${video.width}×${video.height}")
        if (video.size > 0) meta.add(formatSize(video.size))
        video.rating?.let { meta.add("★ " + String.format(Locale.US, "%.1f", it)) }
        video.genre?.takeIf { it.isNotBlank() }?.let { meta.add(it) }
        binding.meta.text = meta.joinToString(" · ")

        val source = video.source
        binding.source.text = if (source.isNullOrBlank()) {
            "信息：文件名解析"
        } else {
            "信息：$source"
        }

        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels

        val poster = video.posterPath
        if (!poster.isNullOrBlank()) {
            ImageLoader.loadFile(binding.poster, poster, width / 3, height / 3)
        } else {
            ImageLoader.loadVideoFrame(binding.poster, video.path, width / 3, height / 3, 5000L)
        }

        val backdrop = video.backdropPath ?: poster
        if (!backdrop.isNullOrBlank()) {
            ImageLoader.loadFile(binding.backdrop, backdrop, width, height)
        } else {
            ImageLoader.loadVideoFrame(binding.backdrop, video.path, width, height, 5000L)
        }
    }

    companion object {
        const val EXTRA_PATH = "video_path"
    }
}
