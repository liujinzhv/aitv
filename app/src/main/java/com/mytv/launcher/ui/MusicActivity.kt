package com.mytv.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mytv.launcher.R
import com.mytv.launcher.databinding.ActivityMusicBinding
import com.mytv.launcher.media.ImageLoader
import com.mytv.launcher.media.LrcLine
import com.mytv.launcher.media.LyricsRepo
import com.mytv.launcher.media.MediaScanner
import com.mytv.launcher.media.PlaylistRepo
import com.mytv.launcher.model.AudioTrack
import com.mytv.launcher.model.Playlist
import com.mytv.launcher.player.MusicPlayer
import com.mytv.launcher.player.MusicService
import com.mytv.launcher.util.Async
import com.mytv.launcher.util.dp
import com.mytv.launcher.util.focusScale
import com.mytv.launcher.util.formatDuration
import com.mytv.launcher.util.immersive
import com.mytv.launcher.util.keepScreenOn
import com.mytv.launcher.util.toast
import kotlin.math.max

class MusicActivity : Activity() {

    private lateinit var binding: ActivityMusicBinding
    private val handler = Handler(Looper.getMainLooper())

    private var tracks: List<AudioTrack> = emptyList()
    private var shownTracks: List<AudioTrack> = emptyList()
    private var trackAdapter: TrackAdapter? = null
    private var playlistAdapter: PlaylistAdapter? = null
    private var playlistMode = false
    private var currentPlaylist: String? = null
    private var lyricAdapter: LyricAdapter? = null
    private var lyricLines: List<LrcLine> = emptyList()
    private var lyricPath: String? = null
    private var lastLyricIndex: Int = -1

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            syncLyrics()
            handler.postDelayed(this, 400L)
        }
    }

    private val playerListener: () -> Unit = {
        runOnUiThread {
            bindNow(forceLyrics = false)
            updateProgress()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicBinding.inflate(layoutInflater)
        setContentView(binding.root)
        immersive()
        keepScreenOn()

        binding.trackList.layoutManager = LinearLayoutManager(this)
        binding.lyricsList.layoutManager = LinearLayoutManager(this)
        binding.seekBar.max = 1000

        binding.tabSongs.setOnClickListener {
            playlistMode = false
            currentPlaylist = null
            refreshLeftList()
        }
        binding.tabPlaylists.setOnClickListener {
            playlistMode = true
            currentPlaylist = null
            refreshLeftList()
        }
        binding.btnNewPlaylist.setOnClickListener { promptCreatePlaylist(null) }
        binding.btnAddPlaylist.setOnClickListener {
            val track = MusicPlayer.currentTrack
            if (track == null) {
                toast("先播放或选中一首歌，再加入歌单")
            } else {
                choosePlaylistFor(track)
            }
        }
        binding.playModeBtn.setOnClickListener {
            val shuffle = !MusicPlayer.shuffleEnabled
            MusicPlayer.setShuffle(this, shuffle)
            updateModeLabel()
            toast(if (shuffle) "已切换为随机播放" else "已切换为顺序播放")
        }
        updateModeLabel()

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.posText.text = formatDuration(MusicPlayer.duration * progress / 1000L)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = MusicPlayer.duration
                if (duration > 0 && seekBar != null) {
                    MusicPlayer.seekTo(duration * seekBar.progress / 1000L)
                    updateProgress()
                }
            }
        })

        binding.btnPlay.setOnClickListener {
            if (MusicPlayer.currentTrack == null) {
                val queue = currentQueue()
                if (queue.isNotEmpty()) {
                    MusicPlayer.play(this, queue, 0)
                    bindNow(forceLyrics = true)
                } else {
                    toast("还没有扫描到本地音乐")
                }
            } else {
                MusicPlayer.toggle(this)
            }
        }
        binding.btnPrev.setOnClickListener {
            if (MusicPlayer.currentTrack == null) {
                val queue = currentQueue()
                if (queue.isNotEmpty()) {
                    MusicPlayer.play(this, queue, 0)
                    bindNow(forceLyrics = true)
                }
            } else {
                MusicPlayer.previous(this)
            }
        }
        binding.btnNext.setOnClickListener {
            if (MusicPlayer.currentTrack == null) {
                val queue = currentQueue()
                if (queue.isNotEmpty()) {
                    MusicPlayer.play(this, queue, 0)
                    bindNow(forceLyrics = true)
                }
            } else {
                MusicPlayer.next(this)
            }
        }

        binding.nowTitle.isSelected = true
        loadTracks()
    }

    override fun onResume() {
        super.onResume()
        MusicPlayer.addListener(playerListener)
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
        bindNow(forceLyrics = false)
        updateProgress()
    }

    override fun onPause() {
        super.onPause()
        MusicPlayer.removeListener(playerListener)
        handler.removeCallbacks(tickRunnable)
    }

    override fun onStop() {
        super.onStop()
        // 离开播放页（返回桌面 / Home / 熄屏）立刻停止播放，不在后台继续
        if (MusicService.running || MusicPlayer.isPlaying) {
            MusicPlayer.stop(this)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (MusicPlayer.currentTrack == null && currentQueue().isNotEmpty()) {
                    MusicPlayer.play(this, currentQueue(), 0)
                } else {
                    MusicPlayer.toggle(this)
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                MusicPlayer.next(this)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                MusicPlayer.previous(this)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun loadTracks() {
        binding.scanState.text = "正在扫描本地音乐…"
        Async.io {
            val list = MediaScanner.audio(this) { msg ->
                runOnUiThread { binding.scanState.text = msg }
            }
            runOnUiThread {
                tracks = list
                binding.countText.text = "${list.size} 首"
                binding.scanState.text = if (list.isEmpty()) "没有找到音频" else "扫描完成"
                binding.trackEmpty.text = "没有找到音频文件\n把音乐放进 U 盘，或在设置里指定音乐目录"

                refreshLeftList()
                bindNow(forceLyrics = false)
                binding.trackList.post { binding.trackList.getChildAt(0)?.requestFocus() }
            }
        }
    }

    private fun currentQueue(): List<AudioTrack> {
        if (playlistMode && currentPlaylist != null) {
            val playlist = PlaylistRepo.find(currentPlaylist!!) ?: return emptyList()
            return PlaylistRepo.tracks(playlist, tracks)
        }
        return tracks
    }

    private fun updateModeLabel() {
        val shuffle = MusicPlayer.shuffleEnabled
        binding.playModeBtn.text = if (shuffle) "随机播放" else "顺序播放"
        binding.playModeBtn.isSelected = shuffle
    }

    private fun setTabs(active: Int) {
        binding.tabSongs.isSelected = active == 0
        binding.tabPlaylists.isSelected = active == 1
        binding.tabSongs.setTextColor(
            ContextCompat.getColor(this, if (active == 0) R.color.white else R.color.text_dim)
        )
        binding.tabPlaylists.setTextColor(
            ContextCompat.getColor(this, if (active == 1) R.color.white else R.color.text_dim)
        )
    }

    private fun refreshLeftList() {
        trackAdapter = null
        playlistAdapter = null
        updateModeLabel()

        if (playlistMode && currentPlaylist == null) {
            setTabs(1)
            val playlists = PlaylistRepo.all()
            binding.playlistTitle.text = "长按歌单可重命名 / 删除 · 共 ${playlists.size} 个"
            binding.trackEmpty.visibility =
                if (playlists.isEmpty()) View.VISIBLE else View.GONE
            binding.trackEmpty.text = "还没有歌单\n点「＋ 新建歌单」，或长按任意歌曲加入歌单"

            playlistAdapter = PlaylistAdapter(
                playlists,
                onClick = { playlist ->
                    currentPlaylist = playlist.name
                    refreshLeftList()
                },
                onLongClick = { playlist -> playlistMenu(playlist) }
            )
            binding.trackList.adapter = playlistAdapter
            if (playlists.isNotEmpty()) {
                binding.trackList.post { binding.trackList.getChildAt(0)?.requestFocus() }
            }
            return
        }

        if (playlistMode && currentPlaylist != null) {
            setTabs(1)
            val queue = currentQueue()
            shownTracks = queue
            binding.playlistTitle.text = "歌单：$currentPlaylist · ${queue.size} 首（点「我的歌单」返回）"
            binding.trackEmpty.visibility = if (queue.isEmpty()) View.VISIBLE else View.GONE
            binding.trackEmpty.text = "这个歌单还没有歌曲\n回到「全部歌曲」长按歌曲即可加入"
            trackAdapter = buildTrackAdapter(queue, inPlaylist = true)
            binding.trackList.adapter = trackAdapter
            if (queue.isNotEmpty()) {
                binding.trackList.post { binding.trackList.getChildAt(0)?.requestFocus() }
            }
            return
        }

        setTabs(0)
        shownTracks = tracks
        binding.playlistTitle.text = if (tracks.isEmpty()) "" else "长按歌曲可加入歌单"
        binding.trackEmpty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        trackAdapter = buildTrackAdapter(tracks, inPlaylist = false)
        binding.trackList.adapter = trackAdapter
    }

    private fun buildTrackAdapter(list: List<AudioTrack>, inPlaylist: Boolean): TrackAdapter {
        val adapter = TrackAdapter(
            list,
            onClick = { track ->
                MusicPlayer.play(this, list, list.indexOf(track).coerceAtLeast(0))
                bindNow(forceLyrics = true)
            },
            onLongClick = { track ->
                if (inPlaylist) {
                    AlertDialog.Builder(this)
                        .setTitle("从歌单移除")
                        .setMessage("把「${track.title}」从 $currentPlaylist 移除？")
                        .setPositiveButton("移除") { _, _ ->
                            currentPlaylist?.let { PlaylistRepo.removeTrack(it, track.path) }
                            refreshLeftList()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    choosePlaylistFor(track)
                }
            }
        )
        MusicPlayer.currentTrack?.let { current ->
            adapter.activePath = current.path
        }
        return adapter
    }

    private fun choosePlaylistFor(track: AudioTrack) {
        val playlists = PlaylistRepo.all()
        val labels = playlists.map { "${it.name}（${it.paths.size} 首）" } + "＋ 新建歌单并加入"
        AlertDialog.Builder(this)
            .setTitle("把「${track.title}」加入歌单")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < playlists.size) {
                    val added = PlaylistRepo.addTrack(playlists[which].name, track.path)
                    toast(if (added) "已加入 ${playlists[which].name}" else "该歌单里已有这首歌")
                    if (added) {
                        playlistMode = true
                        currentPlaylist = playlists[which].name
                    }
                    refreshLeftList()
                } else {
                    promptCreatePlaylist(track)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptCreatePlaylist(trackToAdd: AudioTrack?) {
        val input = EditText(this).apply {
            hint = "歌单名称"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(dp(24f), dp(8f), dp(24f), 0)
        }
        container.addView(
            input,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        AlertDialog.Builder(this)
            .setTitle("新建歌单")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("歌单名称不能为空")
                    return@setPositiveButton
                }
                if (!PlaylistRepo.create(name)) {
                    toast("歌单已存在")
                    return@setPositiveButton
                }
                trackToAdd?.let { PlaylistRepo.addTrack(name, it.path) }
                playlistMode = true
                // 直接进入新歌单，创建 / 加入的结果马上可见
                currentPlaylist = name
                refreshLeftList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun playlistMenu(playlist: Playlist) {
        val actions = arrayOf("播放这个歌单", "重命名", "删除歌单")
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        val queue = PlaylistRepo.tracks(playlist, tracks)
                        if (queue.isEmpty()) {
                            toast("歌单还没有歌曲")
                        } else {
                            MusicPlayer.play(this, queue, 0)
                            bindNow(forceLyrics = true)
                        }
                    }
                    1 -> {
                        val input = EditText(this).apply {
                            setText(playlist.name)
                            setSelection(text.length)
                            setTextColor(android.graphics.Color.WHITE)
                        }
                        val container = android.widget.FrameLayout(this).apply {
                            setPadding(dp(24f), dp(8f), dp(24f), 0)
                        }
                        container.addView(
                            input,
                            android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        )
                        AlertDialog.Builder(this)
                            .setTitle("重命名歌单")
                            .setView(container)
                            .setPositiveButton("保存") { _, _ ->
                                if (PlaylistRepo.rename(playlist.name, input.text.toString())) {
                                    toast("已重命名")
                                } else {
                                    toast("名称重复或为空")
                                }
                                refreshLeftList()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    else -> {
                        AlertDialog.Builder(this)
                            .setTitle("删除歌单")
                            .setMessage("确定删除「${playlist.name}」？只删除歌单，不删除歌曲文件。")
                            .setPositiveButton("删除") { _, _ ->
                                PlaylistRepo.delete(playlist.name)
                                if (currentPlaylist == playlist.name) currentPlaylist = null
                                refreshLeftList()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun bindNow(forceLyrics: Boolean) {
        val track = MusicPlayer.currentTrack
        if (track == null) {
            binding.nowTitle.text = "选择一首歌开始播放"
            binding.nowArtist.text = if (tracks.isEmpty()) "音乐库为空" else "共 ${tracks.size} 首 · 选中后按 OK 播放"
            binding.cover.setImageDrawable(null)
            binding.cover.setBackgroundResource(R.drawable.bg_poster)
            binding.btnPlay.setImageResource(R.drawable.ic_play)
            binding.lyricsHint.text = "选中歌曲后自动刮削歌词"
            binding.lyricsHint.visibility = View.VISIBLE
            binding.lyricsSource.text = ""
            binding.durText.text = "--:--"
            binding.posText.text = "00:00"
            binding.seekBar.progress = 0
            return
        }

        binding.nowTitle.text = track.title
        binding.nowArtist.text = if (track.album.isBlank() || track.album == "<unknown>") {
            track.artist
        } else {
            "${track.artist} · ${track.album}"
        }

        if (track.path != lyricPath || forceLyrics) {
            lyricPath = track.path
            loadLyrics(track)
        }

        val coverPath = track.artworkPath
        if (coverPath != null) {
            ImageLoader.loadFile(binding.cover, coverPath, dp(150f), dp(150f))
        } else {
            ImageLoader.loadEmbedded(binding.cover, track.path, dp(150f), dp(150f))
        }

        if (trackAdapter?.activePath != track.path) {
            trackAdapter?.activePath = track.path
            trackAdapter?.notifyDataSetChanged()
        }

        binding.btnPlay.setImageResource(if (MusicPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        binding.durText.text = formatDuration(track.durationMs)
    }

    private fun loadLyrics(track: AudioTrack) {
        val requestPath = track.path
        lyricLines = emptyList()
        lyricAdapter = null
        binding.lyricsList.adapter = null
        binding.lyricsHint.visibility = View.VISIBLE
        binding.lyricsHint.text = "歌词自动刮削中…"
        binding.lyricsSource.text = ""
        lastLyricIndex = -1

        Async.io {
            val result = LyricsRepo.load(track, force = false)
            runOnUiThread {
                if (MusicPlayer.currentTrack?.path != requestPath) return@runOnUiThread
                binding.lyricsSource.text = result.source
                if (result.lines.isEmpty()) {
                    binding.lyricsHint.visibility = View.VISIBLE
                    binding.lyricsHint.text = result.message
                    return@runOnUiThread
                }
                lyricLines = result.lines
                binding.lyricsHint.visibility = View.GONE
                lyricAdapter = LyricAdapter(result.lines)
                binding.lyricsList.adapter = lyricAdapter
                binding.lyricsList.scrollToPosition(0)
                lastLyricIndex = -1
            }
        }
    }

    private fun updateProgress() {
        val position = MusicPlayer.position
        val duration = MusicPlayer.duration
        binding.posText.text = formatDuration(position)
        binding.durText.text = formatDuration(duration)
        if (!binding.seekBar.isPressed && duration > 0L) {
            binding.seekBar.progress = ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
        }
        binding.btnPlay.setImageResource(if (MusicPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun syncLyrics() {
        val adapter = lyricAdapter ?: return
        if (lyricLines.isEmpty()) return
        val position = MusicPlayer.position
        var index = -1
        for (i in lyricLines.indices) {
            if (lyricLines[i].timeMs <= position) index = i else break
        }
        if (index != lastLyricIndex) {
            lastLyricIndex = index
            adapter.active = index
            adapter.notifyDataSetChanged()
            if (index >= 0) {
                binding.lyricsList.post {
                    val lm = binding.lyricsList.layoutManager as? LinearLayoutManager
                    if (lm != null) {
                        lm.scrollToPositionWithOffset(index, binding.lyricsList.height / 2 - 40)
                    } else {
                        binding.lyricsList.scrollToPosition(index)
                    }
                }
            }
        }
    }
}

class TrackAdapter(
    private val items: List<AudioTrack>,
    private val onClick: (AudioTrack) -> Unit,
    private val onLongClick: (AudioTrack) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    var activePath: String? = null

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.trackTitle)
        val artist: TextView = view.findViewById(R.id.trackArtist)
        val duration: TextView = view.findViewById(R.id.trackDuration)
        val icon: ImageView = view.findViewById(R.id.trackIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        view.focusScale(1.02f, 4f)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.artist.text = item.artist
        holder.duration.text = formatDuration(item.durationMs)
        val active = item.path == activePath
        holder.itemView.isSelected = active
        val accent = ContextCompat.getColor(holder.itemView.context, R.color.accent)
        val normal = ContextCompat.getColor(holder.itemView.context, R.color.text)
        holder.title.setTextColor(if (active) accent else normal)
        holder.icon.setColorFilter(if (active) accent else ContextCompat.getColor(holder.itemView.context, R.color.text_dim))
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}

class PlaylistAdapter(
    private val items: List<Playlist>,
    private val onClick: (Playlist) -> Unit,
    private val onLongClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

    var activeName: String? = null

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.trackTitle)
        val artist: TextView = view.findViewById(R.id.trackArtist)
        val duration: TextView = view.findViewById(R.id.trackDuration)
        val icon: ImageView = view.findViewById(R.id.trackIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        view.focusScale(1.02f, 4f)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.itemView.isSelected = item.name == activeName
        holder.title.text = item.name
        holder.artist.text = if (item.paths.isEmpty()) "空歌单 · 长按歌曲加入" else "长按歌曲可继续添加"
        holder.duration.text = "${item.paths.size} 首"
        holder.icon.setColorFilter(ContextCompat.getColor(holder.itemView.context, R.color.accent))
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}

class LyricAdapter(
    private val lines: List<LrcLine>
) : RecyclerView.Adapter<LyricAdapter.VH>() {

    var active: Int = -1

    class VH(view: TextView) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric, parent, false) as TextView
        return VH(view)
    }

    override fun getItemCount(): Int = lines.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val textView = holder.itemView as TextView
        textView.text = lines[position].text
        if (position == active) {
            textView.setTextColor(ContextCompat.getColor(textView.context, R.color.white))
            textView.alpha = 1f
            textView.textSize = 20f
            textView.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            textView.setTextColor(0x99FFFFFF.toInt())
            textView.alpha = 0.85f
            textView.textSize = 17f
            textView.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }
}

