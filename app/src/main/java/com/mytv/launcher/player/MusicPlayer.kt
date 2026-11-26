package com.mytv.launcher.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mytv.launcher.App
import com.mytv.launcher.Prefs
import com.mytv.launcher.model.AudioTrack
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局音乐播放器：单例持有 ExoPlayer，离开音乐页后仍由前台 Service 继续播放。
 */
object MusicPlayer {

    private var player: ExoPlayer? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    var playlist: List<AudioTrack> = emptyList()
        private set

    var index: Int = -1
        private set

    val currentTrack: AudioTrack?
        get() = playlist.getOrNull(index)

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    val shuffleEnabled: Boolean
        get() = player?.shuffleModeEnabled == true || Prefs.playMode == "shuffle"

    val position: Long
        get() = player?.currentPosition?.coerceAtLeast(0L) ?: 0L

    val duration: Long
        get() {
            val d = player?.duration ?: 0L
            return if (d > 0L) d else (currentTrack?.durationMs ?: 0L)
        }

    fun ensure(ctx: Context): ExoPlayer {
        player?.let { return it }
        val exo = ExoPlayer.Builder(ctx.applicationContext).build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        exo.repeatMode = Player.REPEAT_MODE_ALL
        exo.shuffleModeEnabled = Prefs.playMode == "shuffle"
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = notifyChanged()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newIndex = exo.currentMediaItemIndex
                if (newIndex >= 0) index = newIndex
                notifyChanged()
            }

            override fun onPlaybackStateChanged(playbackState: Int) = notifyChanged()
        })
        player = exo
        return exo
    }

    fun play(ctx: Context, tracks: List<AudioTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val exo = ensure(ctx)
        playlist = tracks
        index = startIndex.coerceIn(0, tracks.lastIndex)

        val items = tracks.map { track ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(File(track.path)))
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .build()
                )
                .build()
        }
        exo.setMediaItems(items, index, 0L)
        exo.shuffleModeEnabled = Prefs.playMode == "shuffle"
        exo.prepare()
        exo.playWhenReady = true
        MusicService.start(ctx)
        notifyChanged()
    }

    /** 切换顺序 / 随机播放，并持久化到设置 */
    fun setShuffle(ctx: Context, enabled: Boolean) {
        val exo = ensure(ctx)
        exo.shuffleModeEnabled = enabled
        Prefs.playMode = if (enabled) "shuffle" else "seq"
        notifyChanged()
    }

    fun toggle(ctx: Context) {
        val exo = player
        if (exo == null) {
            if (playlist.isNotEmpty()) play(ctx, playlist, index.coerceAtLeast(0))
            return
        }
        if (exo.isPlaying) {
            exo.pause()
            MusicService.update(ctx)
        } else {
            if (exo.mediaItemCount == 0 && playlist.isNotEmpty()) {
                play(ctx, playlist, index.coerceAtLeast(0))
            } else {
                exo.play()
                MusicService.start(ctx)
            }
        }
        notifyChanged()
    }

    fun next(ctx: Context) {
        val exo = player ?: return
        if (exo.mediaItemCount == 0) return
        exo.seekToNextMediaItem()
        exo.playWhenReady = true
        MusicService.start(ctx)
        notifyChanged()
    }

    fun previous(ctx: Context) {
        val exo = player ?: return
        if (exo.mediaItemCount == 0) return
        exo.seekToPreviousMediaItem()
        exo.playWhenReady = true
        MusicService.start(ctx)
        notifyChanged()
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0L))
    }

    fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        exo.seekTo(target)
    }

    fun pause(ctx: Context) {
        player?.pause()
        MusicService.update(ctx)
        notifyChanged()
    }

    fun stop(ctx: Context) {
        // 先停前台服务，避免 pause 回调又把通知拉起来
        MusicService.stop(ctx)
        player?.pause()
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
        runCatching { MusicService.update(App.ctx) }
    }
}
