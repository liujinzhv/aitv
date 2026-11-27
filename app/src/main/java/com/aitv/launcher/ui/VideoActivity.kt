package com.aitv.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aitv.launcher.R
import com.aitv.launcher.databinding.ActivityVideoBinding
import com.aitv.launcher.databinding.ItemVideoBinding
import com.aitv.launcher.media.ImageLoader
import com.aitv.launcher.media.MediaScanner
import com.aitv.launcher.model.VideoItem
import com.aitv.launcher.util.Async
import com.aitv.launcher.util.WallpaperStore
import com.aitv.launcher.util.colorFromKey
import com.aitv.launcher.util.dp
import com.aitv.launcher.util.focusScale
import com.aitv.launcher.util.formatDuration
import com.aitv.launcher.util.formatSize
import com.aitv.launcher.util.immersive
import com.aitv.launcher.util.lightPageChrome
import com.aitv.launcher.util.keepScreenOn
import com.aitv.launcher.util.toast
import java.io.File

/**
 * 本地影视库：只扫描本地视频，点击海报直接播放，不再进入详情页或询问播放器。
 */
class VideoActivity : Activity() {

    private lateinit var binding: ActivityVideoBinding
    private val videos = mutableListOf<VideoItem>()
    private var deleteDialogShowing = false
    private var pickWallpaper = false
    private var applyingWallpaper = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        lightPageChrome()
        keepScreenOn()

        pickWallpaper = intent.getBooleanExtra(EXTRA_PICK_WALLPAPER, false)
        if (pickWallpaper) {
            binding.titleText.text = "选择视频壁纸"
            binding.localHint.text = "选择一部视频作为桌面背景，静音循环播放\n按 OK 应用 · 按返回键取消"
        }

        binding.videoGrid.layoutManager = GridLayoutManager(this, 4)
        binding.videoGrid.setHasFixedSize(true)
        // 元数据回填会 notifyItemChanged；默认 item 动画会和焦点放大动画抢 ViewPropertyAnimator，
        // 低内存电视上容易在动画结束回调里 removeView 而崩溃，列表页直接关掉 item 动画。
        binding.videoGrid.itemAnimator = null
        loadLocal(force = false)
    }

    private fun loadLocal(force: Boolean) {
        binding.scanState.text = "扫描中…"
        Async.io {
            val list = MediaScanner.videos(this, force) { message ->
                runOnUiThread { binding.scanState.text = message }
            }
            runOnUiThread {
                videos.clear()
                videos.addAll(list)
                binding.countText.text = "${list.size} 部"
                binding.scanState.text = if (list.isEmpty()) "没有找到视频" else "扫描完成"
                binding.emptyText.text = if (pickWallpaper) {
                    "没有可用视频\n把视频放进 U 盘的 Movies / Video 目录后重新进入即可选择"
                } else {
                    "没有找到本地视频\n把视频放进 U 盘的 Movies / Video 目录后重新进入影视库即可自动扫描"
                }
                binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.videoGrid.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE

                binding.videoGrid.adapter = VideoAdapter(videos, { item ->
                    if (pickWallpaper) {
                        applyVideoWallpaper(item)
                    } else {
                        startActivity(
                            Intent(this, VideoPlayerActivity::class.java)
                                .putExtra(VideoPlayerActivity.EXTRA_PATH, item.path)
                                .putExtra(VideoPlayerActivity.EXTRA_TITLE, item.title)
                        )
                    }
                }, { item ->
                    if (!pickWallpaper) confirmDelete(item)
                })
                focusFirst()
            }
        }
    }

    private fun confirmDelete(item: VideoItem) {
        if (isFinishing || deleteDialogShowing) return
        deleteDialogShowing = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除视频")
            .setMessage("确定删除「${item.title}」？\n视频文件会从磁盘永久删除，无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteVideo(item) }
            .create()
        dialog.setOnDismissListener { deleteDialogShowing = false }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
        }
        dialog.show()
    }

    private fun deleteVideo(item: VideoItem) {
        Async.io {
            val deleted = try {
                val file = File(item.path)
                file.exists() && file.delete()
            } catch (t: Throwable) {
                false
            }
            runOnUiThread {
                if (deleted) {
                    MediaScanner.removePath(item.path)
                    toast("已删除视频")
                    loadLocal(force = true)
                } else {
                    toast("删除失败，文件可能被占用或 U 盘只读")
                }
            }
        }
    }

    private fun applyVideoWallpaper(item: VideoItem) {
        if (applyingWallpaper) return
        applyingWallpaper = true
        val ok = WallpaperStore.setVideo(this, item.path)
        applyingWallpaper = false
        if (ok) {
            toast("已设为桌面视频壁纸，静音循环播放")
            setResult(RESULT_OK)
            finish()
        } else {
            toast("设置失败，视频文件可能已被移动或删除")
        }
    }

    private fun focusFirst() {
        binding.videoGrid.postDelayed({
            if (binding.videoGrid.isComputingLayout) return@postDelayed
            for (index in 0 until binding.videoGrid.childCount) {
                val child = binding.videoGrid.getChildAt(index)
                if (child.isFocusable && child.requestFocus()) return@postDelayed
            }
            binding.videoGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }, 250)
    }

    companion object {
        const val EXTRA_PICK_WALLPAPER = "pick_wallpaper"
    }
}

class VideoAdapter(
    private val items: MutableList<VideoItem>,
    private val onClick: (VideoItem) -> Unit,
    private val onDelete: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private val main = Handler(Looper.getMainLooper())
    private val loadingMeta = HashSet<String>()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.poster)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
        val badge: TextView = view.findViewById(R.id.badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        view.focusScale(1.05f, 12f)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: VH) {
        ImageLoader.cancel(holder.poster)
        super.onViewRecycled(holder)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        bindMeta(holder, item)

        holder.poster.setBackgroundColor(colorFromKey(item.title))
        val poster = item.posterPath
        val ctx = holder.itemView.context
        if (!poster.isNullOrBlank()) {
            ImageLoader.loadFile(holder.poster, poster, ctx.dp(430f), ctx.dp(240f))
        } else {
            ImageLoader.loadVideoFrame(holder.poster, item.path, ctx.dp(430f), ctx.dp(240f), 5000L)
        }

        // 时长/分辨率只对当前可见的卡片读取，避免打开页面时把上千个视频全部打开一遍
        if (!item.metadataLoaded && loadingMeta.add(item.path)) {
            Async.io {
                val enriched = MediaScanner.enrichVideo(item)
                main.post {
                    loadingMeta.remove(item.path)
                    if (!enriched.metadataLoaded) return@post
                    val index = items.indexOfFirst { it.path == enriched.path }
                    if (index >= 0) {
                        items[index] = enriched
                        notifyItemChanged(index)
                    }
                }
            }
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onDelete(item)
            true
        }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                onDelete(item)
                true
            } else {
                false
            }
        }
    }

    private fun bindMeta(holder: VH, item: VideoItem) {
        val metaParts = mutableListOf<String>()
        item.year?.let { metaParts.add("$it") }
        if (item.durationMs > 0) metaParts.add(formatDuration(item.durationMs))
        if (item.width > 0 && item.height > 0) metaParts.add("${item.width}×${item.height}")
        if (item.size > 0) metaParts.add(formatSize(item.size))
        holder.meta.text = metaParts.joinToString(" · ")

        val badge = when {
            item.height >= 2000 -> "4K"
            item.height >= 1000 -> "1080P"
            item.height >= 700 -> "720P"
            else -> ""
        }
        holder.badge.text = badge
        holder.badge.visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
    }
}
