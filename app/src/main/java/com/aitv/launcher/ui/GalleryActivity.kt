package com.aitv.launcher.ui

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
import com.aitv.launcher.R
import com.aitv.launcher.databinding.ActivityGalleryBinding
import com.aitv.launcher.media.ImageLoader
import com.aitv.launcher.media.MediaScanner
import com.aitv.launcher.model.PhotoItem
import com.aitv.launcher.util.Async
import com.aitv.launcher.util.dp
import com.aitv.launcher.util.focusScale
import com.aitv.launcher.util.immersive
import com.aitv.launcher.util.keepScreenOn
import com.aitv.launcher.util.photoPathText
import com.aitv.launcher.util.toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class GalleryActivity : Activity() {

    private lateinit var binding: ActivityGalleryBinding
    private var loadedOnce = false
    private var deleteDialogShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        keepScreenOn()

        val grid = GridLayoutManager(this, 6)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val adapter = binding.photoGrid.adapter as? GalleryAdapter ?: return 1
                return if (adapter.isHeader(position)) 6 else 1
            }
        }
        binding.photoGrid.layoutManager = grid
        binding.btnUpload.setOnClickListener {
            startActivity(Intent(this, UploadActivity::class.java))
        }
        load(force = false)
    }

    override fun onResume() {
        super.onResume()
        if (loadedOnce) load(force = true)
    }

    private fun load(force: Boolean) {
        binding.countText.text = "扫描中…"
        binding.pathText.text = "按拍摄日期排列 · 按 OK 查看 · 按菜单键删除\n上传目录：" + photoPathText(this)
        Async.io {
            val list = MediaScanner.photos(this, force)
            val entries = buildEntries(list)
            runOnUiThread {
                loadedOnce = true
                binding.countText.text = "${list.size} 张"
                binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.photoGrid.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                binding.photoGrid.adapter = GalleryAdapter(entries, { photo ->
                    startActivity(
                        Intent(this, PhotoViewActivity::class.java)
                            .putExtra(PhotoViewActivity.EXTRA_PATH, photo.path)
                    )
                }, { photo ->
                    confirmDelete(photo)
                })
                binding.photoGrid.postDelayed({
                    for (index in 0 until binding.photoGrid.childCount) {
                        val child = binding.photoGrid.getChildAt(index)
                        if (child.isFocusable && child.requestFocus()) return@postDelayed
                    }
                }, 250)
            }
        }
    }

    private fun confirmDelete(photo: PhotoItem) {
        if (isFinishing || deleteDialogShowing) return
        deleteDialogShowing = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除照片")
            .setMessage("确定删除「${photo.name}」？\n照片会从磁盘永久删除，无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deletePhoto(photo) }
            .create()
        dialog.setOnDismissListener { deleteDialogShowing = false }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
        }
        dialog.show()
    }

    private fun deletePhoto(photo: PhotoItem) {
        Async.io {
            val deleted = try {
                val file = File(photo.path)
                file.exists() && file.delete()
            } catch (t: Throwable) {
                false
            }
            runOnUiThread {
                if (deleted) {
                    MediaScanner.invalidate()
                    toast("已删除照片")
                    load(force = true)
                } else {
                    toast("删除失败，文件可能被占用或 U 盘只读")
                }
            }
        }
    }

    /** 按实际拍摄日期倒序，并在每天前插入日期标题 */
    private fun buildEntries(photos: List<PhotoItem>): List<GalleryEntry> {
        if (photos.isEmpty()) return emptyList()
        val dayFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
        val weekdays = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val groups = LinkedHashMap<String, MutableList<PhotoItem>>()

        for (photo in photos) {
            val key = keyFormat.format(Date(photo.takenAt))
            groups.getOrPut(key) { mutableListOf() }.add(photo)
        }

        val entries = mutableListOf<GalleryEntry>()
        for ((_, group) in groups) {
            val first = group.first()
            val day = dayFormat.format(Date(first.takenAt))
            val calendar = Calendar.getInstance().apply { timeInMillis = first.takenAt }
            val week = weekdays[(calendar.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]
            entries.add(GalleryEntry.Header("$day  $week · ${group.size} 张"))
            group.forEach { entries.add(GalleryEntry.Photo(it)) }
        }
        return entries
    }
}

sealed class GalleryEntry {
    data class Header(val label: String) : GalleryEntry()
    data class Photo(val item: PhotoItem) : GalleryEntry()
}

class GalleryAdapter(
    private val entries: List<GalleryEntry>,
    private val onClick: (PhotoItem) -> Unit,
    private val onDelete: (PhotoItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class PhotoVH(view: View) : RecyclerView.ViewHolder(view) {
        val photo: ImageView = view.findViewById(R.id.photo)
    }

    class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.dateHeader)
    }

    fun isHeader(position: Int): Boolean = entries[position] is GalleryEntry.Header

    override fun getItemViewType(position: Int): Int =
        if (isHeader(position)) TYPE_HEADER else TYPE_PHOTO

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_gallery_date, parent, false)
            HeaderVH(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            view.focusScale(1.04f, 8f)
            PhotoVH(view)
        }
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = entries[position]) {
            is GalleryEntry.Header -> (holder as HeaderVH).title.text = entry.label
            is GalleryEntry.Photo -> {
                val photoHolder = holder as PhotoVH
                val item = entry.item
                ImageLoader.loadFile(
                    photoHolder.photo,
                    item.path,
                    photoHolder.itemView.context.dp(320f),
                    photoHolder.itemView.context.dp(210f)
                )
                photoHolder.itemView.contentDescription = item.name
                photoHolder.itemView.setOnClickListener { onClick(item) }
                photoHolder.itemView.setOnLongClickListener {
                    onDelete(item)
                    true
                }
                photoHolder.itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                        onDelete(item)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1
    }
}
