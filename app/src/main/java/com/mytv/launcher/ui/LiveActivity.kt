package com.mytv.launcher.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytv.launcher.R
import com.mytv.launcher.databinding.ActivityLiveBinding
import com.mytv.launcher.media.ImageLoader
import com.mytv.launcher.media.LiveRepo
import com.mytv.launcher.model.LiveChannel
import com.mytv.launcher.util.dp
import com.mytv.launcher.util.focusScale
import com.mytv.launcher.util.immersive
import com.mytv.launcher.util.keepScreenOn

/**
 * 大屏直播频道墙：先展示本地缓存，再后台刷新直播源；
 * 台标图片和频道信息都会落盘，断网或重启后仍能快速打开。
 */
class LiveActivity : Activity() {

    private lateinit var binding: ActivityLiveBinding
    private lateinit var adapter: LiveWallAdapter
    private var channels: List<LiveChannel> = emptyList()
    private var logoJobStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        keepScreenOn()

        adapter = LiveWallAdapter(
            onClick = { channel -> play(channel) },
            onFavorite = { channel -> toggleFavorite(channel) }
        )
        val columns = if (resources.configuration.screenWidthDp >= 1100) 4 else 3
        val grid = GridLayoutManager(this, columns)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == LiveWallAdapter.TYPE_GROUP) columns else 1
            }
        }
        binding.channelGrid.layoutManager = grid
        binding.channelGrid.adapter = adapter
        binding.channelGrid.isFocusable = true
        binding.channelGrid.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

        val cached = LiveRepo.cached()
        if (cached.isNotEmpty()) {
            render(cached, "已加载本地缓存 · 正在刷新直播源")
            cacheLogos(cached)
            refresh(force = false)
        } else if (!LiveRepo.hasSources()) {
            showEmpty("还没有配置直播源", true)
        } else {
            binding.statusText.text = "正在读取直播源…"
            refresh(force = false)
        }
    }

    private fun refresh(force: Boolean) {
        binding.statusText.text = if (force) "正在刷新直播源…" else "正在读取直播源…"
        LiveRepo.load(force, progress = { message ->
            runOnUiThread { binding.statusText.text = message }
        }) { list, fresh ->
            runOnUiThread {
                if (list.isEmpty()) {
                    if (channels.isEmpty()) {
                        showEmpty(
                            if (LiveRepo.hasSources()) {
                                "直播源里没有解析到可播放频道\n检查源地址是否可访问，或点右上角重新读取"
                            } else {
                                "还没有配置直播源\n在「设置 → 直播源」里粘贴 m3u / m3u8 地址"
                            },
                            !LiveRepo.hasSources()
                        )
                    } else {
                        binding.statusText.text = "刷新失败，继续使用本地缓存"
                    }
                    return@runOnUiThread
                }
                render(
                    list,
                    if (fresh) "已刷新 · ${list.size} 个频道" else "使用本地缓存 · ${list.size} 个频道"
                )
                cacheLogos(list)
            }
        }
    }

    private fun render(list: List<LiveChannel>, status: String, focusUrl: String? = null) {
        channels = list
        val favorites = LiveRepo.favorites()
            .distinctBy { it.url }
        val currentByUrl = list.associateBy { it.url }
        val mergedFavorites = favorites.map { currentByUrl[it.url] ?: it }
        val favoriteUrls = mergedFavorites.map { it.url }.toSet()
        val rest = list.filterNot { it.url in favoriteUrls }
        val grouped = rest.groupBy { it.group.ifBlank { "未分组" } }
        val rows = mutableListOf<LiveWallRow>()
        if (mergedFavorites.isNotEmpty()) {
            rows.add(LiveWallRow.Group("收藏频道", mergedFavorites.size))
            mergedFavorites
                .sortedWith(compareBy<LiveChannel> { it.playRank() }.thenBy { it.name })
                .forEach { rows.add(LiveWallRow.Channel(it)) }
        }
        for ((group, items) in grouped) {
            rows.add(LiveWallRow.Group(group, items.size))
            items.sortedWith(compareBy<LiveChannel> { it.playRank() }.thenBy { it.name })
                .forEach { rows.add(LiveWallRow.Channel(it)) }
        }

        val extraFavorites = mergedFavorites.count { it.url !in currentByUrl }
        val displayCount = list.size + extraFavorites
        val displayGroupCount = grouped.size + if (mergedFavorites.isNotEmpty()) 1 else 0
        binding.countText.text = "$displayCount 个频道 · $displayGroupCount 个分组"
        binding.statusText.text = status
        binding.emptyText.visibility = View.GONE
        binding.channelGrid.visibility = View.VISIBLE
        adapter.submit(rows, favoriteUrls)
        if (focusUrl != null) focusChannel(focusUrl) else focusFirstChannel()
    }

    private fun toggleFavorite(channel: LiveChannel) {
        val favorites = LiveRepo.favorites()
        val exists = favorites.any { it.url == channel.url }
        if (exists) {
            LiveRepo.saveFavorites(favorites.filterNot { it.url == channel.url })
            Toast.makeText(this, "已取消收藏：${channel.name}", Toast.LENGTH_SHORT).show()
        } else {
            LiveRepo.saveFavorites(favorites + channel)
            Toast.makeText(this, "已收藏：${channel.name}", Toast.LENGTH_SHORT).show()
        }
        render(channels, binding.statusText.text.toString(), focusUrl = channel.url)
    }

    private fun showEmpty(message: String, needSource: Boolean) {
        binding.countText.text = "0 个频道"
        binding.statusText.text = if (needSource) "未配置直播源" else "没有可用频道"
        binding.emptyText.text = message
        binding.emptyText.visibility = View.VISIBLE
        binding.channelGrid.visibility = View.GONE
    }

    private fun play(channel: LiveChannel) {
        startActivity(
            Intent(this, VideoPlayerActivity::class.java)
                .putExtra(VideoPlayerActivity.EXTRA_URL, channel.url)
                .putExtra(VideoPlayerActivity.EXTRA_TITLE, channel.name)
                .putExtra(VideoPlayerActivity.EXTRA_LIVE, true)
        )
    }

    private fun focusFirstChannel() {
        binding.channelGrid.postDelayed({
            val index = adapter.firstChannelPosition()
            if (index < 0) return@postDelayed
            val groupStart = adapter.groupStartOf(index)
            binding.channelGrid.scrollToPosition(groupStart)

            fun attempt(count: Int) {
                val holder = binding.channelGrid.findViewHolderForAdapterPosition(index)
                if (holder != null && holder.itemView.requestFocus()) {
                    binding.channelGrid.postDelayed({
                        binding.channelGrid.scrollToPosition(groupStart)
                    }, 120)
                    return
                }
                if (count < 8) {
                    binding.channelGrid.postDelayed({ attempt(count + 1) }, 120)
                } else {
                    binding.channelGrid.requestFocus()
                }
            }
            attempt(0)
        }, 300)
    }

    private fun focusChannel(url: String) {
        binding.channelGrid.postDelayed({
            val index = adapter.positionOf(url)
            if (index < 0) {
                focusFirstChannel()
                return@postDelayed
            }
            val groupStart = adapter.groupStartOf(index)
            binding.channelGrid.scrollToPosition(groupStart)
            binding.channelGrid.postDelayed({
                binding.channelGrid.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                binding.channelGrid.postDelayed({
                    binding.channelGrid.scrollToPosition(groupStart)
                }, 150)
            }, 220)
        }, 220)
    }

    private fun cacheLogos(list: List<LiveChannel>) {
        if (logoJobStarted || list.none { !it.logo.isNullOrBlank() }) return
        logoJobStarted = true
        var lastNotified = 0
        LiveRepo.cacheLogos(
            list,
            onProgress = { done, total ->
                runOnUiThread {
                    binding.statusText.text = "台标缓存 $done / $total"
                    if (done == total || done - lastNotified >= 24) {
                        lastNotified = done
                        adapter.refreshLogos()
                    }
                }
            },
            onDone = {
                runOnUiThread {
                    binding.statusText.text = "频道与台标已缓存 · 共 ${list.size} 个频道"
                    adapter.refreshLogos()
                }
            }
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

private fun LiveChannel.sourceTag(): String? {
    val lower = url.lowercase()
    val path = lower.substringBefore('?')
    return when {
        lower.contains("live.metshop.top") -> "非直链"
        lower.contains("live.264788.xyz") -> "失效源"
        path.endsWith(".mp4") -> "点播"
        path.endsWith(".flv") -> "FLV"
        else -> null
    }
}

private fun LiveChannel.playRank(): Int = when (sourceTag()) {
    null -> 0
    "点播" -> 1
    else -> 2
}

sealed class LiveWallRow {
    data class Group(val name: String, val count: Int) : LiveWallRow()
    data class Channel(val channel: LiveChannel) : LiveWallRow()
}

class LiveWallAdapter(
    private val onClick: (LiveChannel) -> Unit,
    private val onFavorite: (LiveChannel) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<LiveWallRow>()
    private val accent = ColorStateList.valueOf(0xFF4EA1FF.toInt())
    private var favoriteUrls: Set<String> = emptySet()

    fun submit(newRows: List<LiveWallRow>, favorites: Set<String>) {
        rows.clear()
        rows.addAll(newRows)
        favoriteUrls = favorites
        notifyDataSetChanged()
    }

    fun positionOf(url: String): Int = rows.indexOfFirst {
        it is LiveWallRow.Channel && it.channel.url == url
    }

    fun firstChannelPosition(): Int = rows.indexOfFirst { it is LiveWallRow.Channel }

    fun groupStartOf(index: Int): Int {
        for (i in index downTo 0) {
            if (rows[i] is LiveWallRow.Group) return i
        }
        return 0
    }

    fun refreshLogos() {
        for (index in rows.indices) {
            val row = rows[index]
            if (row is LiveWallRow.Channel && LiveRepo.hasLogo(row.channel)) {
                notifyItemChanged(index)
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (rows[position] is LiveWallRow.Group) TYPE_GROUP else TYPE_CHANNEL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GROUP) {
            GroupVH(inflater.inflate(R.layout.item_live_group, parent, false))
        } else {
            val view = inflater.inflate(R.layout.item_live_channel, parent, false)
            view.focusScale(1.04f, 10f)
            ChannelVH(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is LiveWallRow.Group -> (holder as GroupVH).bind(row)
            is LiveWallRow.Channel -> (holder as ChannelVH).bind(row.channel)
        }
    }

    override fun getItemCount(): Int = rows.size

    class GroupVH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.liveGroupName)

        fun bind(row: LiveWallRow.Group) {
            name.text = "${row.name}  ·  ${row.count} 个频道"
        }
    }

    inner class ChannelVH(view: View) : RecyclerView.ViewHolder(view) {
        private val logo: ImageView = view.findViewById(R.id.channelLogo)
        private val name: TextView = view.findViewById(R.id.channelName)
        private val group: TextView = view.findViewById(R.id.channelGroup)
        private val favorite: TextView = view.findViewById(R.id.channelFavorite)
        private var longPressFavorite = false

        fun bind(channel: LiveChannel) {
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
            name.text = channel.name
            val tag = channel.sourceTag()
            group.text = if (tag == null) channel.group else "${channel.group} · $tag"
            itemView.contentDescription = "${channel.name} ${channel.group}"
            itemView.setOnClickListener { onClick(channel) }
            itemView.setOnLongClickListener {
                onFavorite(channel)
                true
            }
            itemView.setOnKeyListener { _, keyCode, event ->
                val center = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        onFavorite(channel)
                        return@setOnKeyListener true
                    }
                    if (event.action == KeyEvent.ACTION_UP) return@setOnKeyListener true
                    return@setOnKeyListener false
                }
                if (!center) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.isLongPress) {
                        onFavorite(channel)
                        longPressFavorite = true
                        return@setOnKeyListener true
                    }
                    longPressFavorite = false
                    return@setOnKeyListener false
                }
                if (event.action == KeyEvent.ACTION_UP && longPressFavorite) {
                    longPressFavorite = false
                    return@setOnKeyListener true
                }
                false
            }
            favorite.visibility = if (channel.url in favoriteUrls) View.VISIBLE else View.GONE

            val file = LiveRepo.logoFile(channel)
            if (file != null && file.exists() && file.length() > 0) {
                logo.imageTintList = null
                logo.setPadding(0, 0, 0, 0)
                ImageLoader.loadFile(logo, file.absolutePath, itemView.context.dp(180f), itemView.context.dp(90f))
            } else {
                logo.imageTintList = accent
                logo.setPadding(itemView.context.dp(16f), itemView.context.dp(14f), itemView.context.dp(16f), itemView.context.dp(14f))
                logo.setImageResource(R.drawable.ic_tv)
            }
        }
    }

    companion object {
        const val TYPE_GROUP = 0
        const val TYPE_CHANNEL = 1
    }
}
