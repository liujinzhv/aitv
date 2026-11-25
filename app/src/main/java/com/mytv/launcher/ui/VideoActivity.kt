package com.mytv.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytv.launcher.R
import com.mytv.launcher.databinding.ActivityVideoBinding
import com.mytv.launcher.databinding.ItemVideoBinding
import com.mytv.launcher.media.ImageLoader
import com.mytv.launcher.media.MediaScanner
import com.mytv.launcher.model.VideoItem
import com.mytv.launcher.util.Async
import com.mytv.launcher.util.colorFromKey
import com.mytv.launcher.util.dp
import com.mytv.launcher.util.focusScale
import com.mytv.launcher.util.formatDuration
import com.mytv.launcher.util.formatSize
import com.mytv.launcher.util.immersive
import com.mytv.launcher.util.keepScreenOn
import com.mytv.launcher.util.toast
import java.io.File

/**
 * 本地影视库：只扫描本地视频，点击海报直接播放，不再进入详情页或询问播放器。
 */
class VideoActivity : Activity() {

    private lateinit var binding: ActivityVideoBinding
    private var videos: List<VideoItem> = emptyList()
    private var deleteDialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        keepScreenOn()

        binding.videoGrid.layoutManager = GridLayoutManager(this, 4)
        loadLocal(force = false)
    }

    private fun loadLocal(force: Boolean) {
        binding.scanState.text = "扫描中…"
        Async.io {
            val list = MediaScanner.videos(this, force) { message ->
                runOnUiThread { binding.scanState.text = message }
            }
            runOnUiThread {
                videos = list
                binding.countText.text = "${list.size} 部"
                binding.scanState.text = if (list.isEmpty()) "没有找到视频" else "扫描完成"
                binding.emptyText.text = "没有找到本地视频\n把视频放进 U 盘的 Movies / Video 目录后重新进入影视库即可自动扫描"
                binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.videoGrid.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE

                binding.videoGrid.adapter = VideoAdapter(list, { item ->
                    startActivity(
                        Intent(this, VideoPlayerActivity::class.java)
                            .putExtra(VideoPlayerActivity.EXTRA_PATH, item.path)
                            .putExtra(VideoPlayerActivity.EXTRA_TITLE, item.title)
                    )
                }, { item ->
                    confirmDelete(item)
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
                    MediaScanner.invalidate()
                    toast("已删除视频")
                    loadLocal(force = true)
                } else {
                    toast("删除失败，文件可能被占用或 U 盘只读")
                }
            }
        }
    }

    private fun focusFirst() {
        binding.videoGrid.postDelayed({
            for (index in 0 until binding.videoGrid.childCount) {
                val child = binding.videoGrid.getChildAt(index)
                if (child.isFocusable && child.requestFocus()) return@postDelayed
            }
            binding.videoGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }, 250)
    }
}

class VideoAdapter(
    private val items: List<VideoItem>,
    private val onClick: (VideoItem) -> Unit,
    private val onDelete: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

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

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title

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

        holder.poster.setBackgroundColor(colorFromKey(item.title))
        val poster = item.posterPath
        val ctx = holder.itemView.context
        if (!poster.isNullOrBlank()) {
            ImageLoader.loadFile(holder.poster, poster, ctx.dp(430f), ctx.dp(240f))
        } else {
            ImageLoader.loadVideoFrame(holder.poster, item.path, ctx.dp(430f), ctx.dp(240f), 5000L)
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
}
